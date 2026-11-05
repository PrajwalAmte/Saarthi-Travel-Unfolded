//! MiniLM-L6 (a six-layer BERT) as a pair classifier. Layers are written out instead of using
//! Burn's own, so they line up one-to-one with the pretrained weights' names and formulas.

use burn::module::Param;
use burn::nn::{Dropout, DropoutConfig};
use burn::prelude::*;
use burn::tensor::activation::{gelu, softmax};
use burn::tensor::module::embedding;
use safetensors::tensor::{serialize_to_file, Dtype, TensorView};
use safetensors::SafeTensors;
use std::error::Error;
use std::path::Path;

pub const LAYERS: usize = 6;
pub const HIDDEN: usize = 384;
const HEADS: usize = 12;
const HEAD_SIZE: usize = HIDDEN / HEADS;
const DROPOUT: f64 = 0.1;
const LAYER_NORM_EPSILON: f32 = 1e-12;
/// Added to the text encoding: scaled distance, and whether the categories match.
pub const EXTRA_INPUTS: usize = 2;

pub struct Weights<'a>(pub SafeTensors<'a>);

impl Weights<'_> {
    fn tensor<const D: usize>(&self, name: &str, device: &Device) -> Tensor<D> {
        let view = self.0.tensor(name).unwrap_or_else(|e| panic!("{name}: {e}"));
        let values: Vec<f32> = view.data().as_chunks::<4>().0.iter().map(|b| f32::from_le_bytes(*b)).collect();
        Tensor::from_data(TensorData::new(values, view.shape().to_vec()), device)
    }
}

/// A tensor's name, shape and values, ready to write.
struct Exported {
    name: String,
    shape: Vec<usize>,
    bytes: Vec<u8>,
}

fn exported<const D: usize>(name: String, tensor: Tensor<D>) -> Exported {
    let shape = tensor.dims().to_vec();
    let values = tensor.into_data().convert::<f32>().try_to_vec::<f32>().expect("floats");
    Exported { name, shape, bytes: values.iter().flat_map(|v| v.to_le_bytes()).collect() }
}

#[derive(Module, Debug)]
struct Dense {
    weight: Param<Tensor<2>>,
    bias: Param<Tensor<1>>,
}

impl Dense {
    /// PyTorch stores a linear layer's weight as [out, in]; here it multiplies from the right.
    fn load(w: &Weights, prefix: &str, device: &Device) -> Self {
        let weight: Tensor<2> = w.tensor(&format!("{prefix}.weight"), device);
        Dense { weight: Param::from_tensor(weight.swap_dims(0, 1)), bias: Param::from_tensor(w.tensor(&format!("{prefix}.bias"), device)) }
    }

    fn forward(&self, x: Tensor<3>) -> Tensor<3> {
        x.matmul(self.weight.val().unsqueeze()) + self.bias.val().unsqueeze()
    }

    fn export(&self, prefix: &str, out: &mut Vec<Exported>) {
        out.push(exported(format!("{prefix}.weight"), self.weight.val().swap_dims(0, 1)));
        out.push(exported(format!("{prefix}.bias"), self.bias.val()));
    }
}

#[derive(Module, Debug)]
struct LayerNorm {
    weight: Param<Tensor<1>>,
    bias: Param<Tensor<1>>,
}

impl LayerNorm {
    fn load(w: &Weights, prefix: &str, device: &Device) -> Self {
        LayerNorm { weight: Param::from_tensor(w.tensor(&format!("{prefix}.weight"), device)), bias: Param::from_tensor(w.tensor(&format!("{prefix}.bias"), device)) }
    }

    fn forward(&self, x: Tensor<3>) -> Tensor<3> {
        let centred = x.clone() - x.mean_dim(2);
        let variance = centred.clone().powf_scalar(2.0).mean_dim(2);
        centred / (variance + LAYER_NORM_EPSILON).sqrt() * self.weight.val().unsqueeze() + self.bias.val().unsqueeze()
    }

    fn export(&self, prefix: &str, out: &mut Vec<Exported>) {
        out.push(exported(format!("{prefix}.weight"), self.weight.val()));
        out.push(exported(format!("{prefix}.bias"), self.bias.val()));
    }
}

#[derive(Module, Debug)]
struct Layer {
    query: Dense,
    key: Dense,
    value: Dense,
    attention_output: Dense,
    attention_norm: LayerNorm,
    intermediate: Dense,
    output: Dense,
    output_norm: LayerNorm,
    dropout: Dropout,
}

impl Layer {
    fn load(w: &Weights, index: usize, device: &Device) -> Self {
        let p = format!("encoder.layer.{index}");
        Layer {
            query: Dense::load(w, &format!("{p}.attention.self.query"), device),
            key: Dense::load(w, &format!("{p}.attention.self.key"), device),
            value: Dense::load(w, &format!("{p}.attention.self.value"), device),
            attention_output: Dense::load(w, &format!("{p}.attention.output.dense"), device),
            attention_norm: LayerNorm::load(w, &format!("{p}.attention.output.LayerNorm"), device),
            intermediate: Dense::load(w, &format!("{p}.intermediate.dense"), device),
            output: Dense::load(w, &format!("{p}.output.dense"), device),
            output_norm: LayerNorm::load(w, &format!("{p}.output.LayerNorm"), device),
            dropout: DropoutConfig::new(DROPOUT).init(),
        }
    }

    fn heads(x: Tensor<3>) -> Tensor<4> {
        let [batch, length, _] = x.dims();
        x.reshape([batch, length, HEADS, HEAD_SIZE]).swap_dims(1, 2)
    }

    /// `blocked` is very negative on padding and zero elsewhere, shaped to add to the attention scores.
    fn forward(&self, x: Tensor<3>, blocked: Tensor<4>) -> Tensor<3> {
        let [batch, length, _] = x.dims();
        let (query, key, value) = (Self::heads(self.query.forward(x.clone())), Self::heads(self.key.forward(x.clone())), Self::heads(self.value.forward(x.clone())));
        let scores = query.matmul(key.swap_dims(2, 3)) / (HEAD_SIZE as f32).sqrt() + blocked;
        let attention = self.dropout.forward(softmax(scores, 3));
        let mixed = attention.matmul(value).swap_dims(1, 2).reshape([batch, length, HIDDEN]);
        let attended = self.attention_norm.forward(x + self.dropout.forward(self.attention_output.forward(mixed)));
        let expanded = self.output.forward(gelu(self.intermediate.forward(attended.clone())));
        self.output_norm.forward(attended + self.dropout.forward(expanded))
    }
}

#[derive(Module, Debug)]
pub struct Model {
    words: Param<Tensor<2>>,
    positions: Param<Tensor<2>>,
    segments: Param<Tensor<2>>,
    embedding_norm: LayerNorm,
    layers: Vec<Layer>,
    dropout: Dropout,
    head: Param<Tensor<2>>,
    head_bias: Param<Tensor<1>>,
}

/// Token ids, segment ids and padding mask (1 for a real token), all [batch, length].
pub struct Batch {
    pub tokens: Tensor<2, Int>,
    pub segments: Tensor<2, Int>,
    pub mask: Tensor<2>,
    /// [batch, EXTRA_INPUTS]
    pub extra: Tensor<2>,
}

impl Model {
    pub fn load(w: &Weights, device: &Device) -> Self {
        let width = HIDDEN + EXTRA_INPUTS;
        Model {
            words: Param::from_tensor(w.tensor("embeddings.word_embeddings.weight", device)),
            positions: Param::from_tensor(w.tensor("embeddings.position_embeddings.weight", device)),
            segments: Param::from_tensor(w.tensor("embeddings.token_type_embeddings.weight", device)),
            embedding_norm: LayerNorm::load(w, "embeddings.LayerNorm", device),
            layers: (0..LAYERS).map(|i| Layer::load(w, i, device)).collect(),
            dropout: DropoutConfig::new(DROPOUT).init(),
            // A pretrained encoder has no classifier yet; a saved model brings its own.
            head: Param::from_tensor(if w.0.tensor("head.weight").is_ok() {
                w.tensor("head.weight", device)
            } else {
                Tensor::random([width, 1], burn::tensor::Distribution::Normal(0.0, 0.02), device)
            }),
            head_bias: Param::from_tensor(if w.0.tensor("head.bias").is_ok() { w.tensor("head.bias", device) } else { Tensor::zeros([1], device) }),
        }
    }

    /// Writes every weight under the pretrained model's names, so `load` reads a saved model the same way.
    pub fn save(&self, path: &Path) -> Result<(), Box<dyn Error>> {
        let mut tensors = vec![
            exported("embeddings.word_embeddings.weight".into(), self.words.val()),
            exported("embeddings.position_embeddings.weight".into(), self.positions.val()),
            exported("embeddings.token_type_embeddings.weight".into(), self.segments.val()),
            exported("head.weight".into(), self.head.val()),
            exported("head.bias".into(), self.head_bias.val()),
        ];
        self.embedding_norm.export("embeddings.LayerNorm", &mut tensors);
        for (index, layer) in self.layers.iter().enumerate() {
            let p = format!("encoder.layer.{index}");
            layer.query.export(&format!("{p}.attention.self.query"), &mut tensors);
            layer.key.export(&format!("{p}.attention.self.key"), &mut tensors);
            layer.value.export(&format!("{p}.attention.self.value"), &mut tensors);
            layer.attention_output.export(&format!("{p}.attention.output.dense"), &mut tensors);
            layer.attention_norm.export(&format!("{p}.attention.output.LayerNorm"), &mut tensors);
            layer.intermediate.export(&format!("{p}.intermediate.dense"), &mut tensors);
            layer.output.export(&format!("{p}.output.dense"), &mut tensors);
            layer.output_norm.export(&format!("{p}.output.LayerNorm"), &mut tensors);
        }
        let mut views = Vec::with_capacity(tensors.len());
        for tensor in &tensors {
            views.push((tensor.name.clone(), TensorView::new(Dtype::F32, tensor.shape.clone(), &tensor.bytes)?));
        }
        serialize_to_file(views, None, path)?;
        Ok(())
    }

    /// Hidden states for every token, [batch, length, HIDDEN].
    pub fn encode(&self, tokens: Tensor<2, Int>, segments: Tensor<2, Int>, mask: Tensor<2>) -> Tensor<3> {
        let [batch, length] = tokens.dims();
        let device = tokens.device();
        let places = Tensor::<1, Int>::arange(0..length as i64, &device).unsqueeze::<2>().repeat_dim(0, batch);
        let embedded = embedding(self.words.val(), tokens) + embedding(self.positions.val(), places) + embedding(self.segments.val(), segments);
        let mut x = self.dropout.forward(self.embedding_norm.forward(embedded));
        let blocked: Tensor<4> = (mask.unsqueeze_dim::<3>(1).unsqueeze_dim::<4>(1) - 1.0) * 1e4;
        for layer in &self.layers {
            x = layer.forward(x, blocked.clone());
        }
        x
    }

    /// One score per pair, before the sigmoid.
    pub fn forward(&self, batch: Batch) -> Tensor<1> {
        let [size, _] = batch.tokens.dims();
        let hidden = self.encode(batch.tokens, batch.segments, batch.mask);
        let cls = hidden.narrow(1, 0, 1).reshape([size, HIDDEN]);
        let inputs = Tensor::cat(vec![self.dropout.forward(cls), batch.extra], 1);
        (inputs.matmul(self.head.val()) + self.head_bias.val().unsqueeze()).reshape([size])
    }
}

/// Mean-pooled, unit-length sentence vector: how MiniLM's published embeddings are produced.
pub fn sentence_vector(model: &Model, tokens: Tensor<2, Int>, segments: Tensor<2, Int>, mask: Tensor<2>) -> Vec<f32> {
    let hidden = model.encode(tokens, segments, mask.clone());
    let weights: Tensor<3> = mask.unsqueeze_dim(2);
    let pooled = (hidden * weights.clone()).sum_dim(1) / weights.sum_dim(1);
    let norm = pooled.clone().powf_scalar(2.0).sum_dim(2).sqrt();
    (pooled / norm).into_data().convert::<f32>().try_to_vec::<f32>().expect("floats")
}
