//! Fine-tunes MiniLM to decide whether two map records are one place, reporting each step to
//! `calibrate watch`. See ../README.md.

mod data;
mod hook;
mod model;
mod package;

use burn::grad_clipping::GradientClippingConfig;
use burn::optim::{AdamWConfig, GradientsParams};
use burn::prelude::*;
use burn::tensor::activation::log_sigmoid;
use data::{balanced_batch, load_examples, load_tokenizer, soft_targets, to_batch, Example, Random};
use model::{sentence_vector, Model, Weights, HIDDEN, LAYERS};
use std::error::Error;
use std::io::Write;
use std::path::PathBuf;
use std::time::Instant;

const SEED: u64 = 20_261_105;
const BATCH: usize = 32;
const PEAK_LEARNING_RATE: f64 = 5e-5;
const WARMUP: f64 = 0.1;
const USAGE: &str = "Usage: saarthi-matching-train <verify|train|predict|package> [--name NAME] [--steps N] [--soft] [--pad-to N] [--data DIR] [--model DIR] [--input FILE] [--output FILE] [--device cpu|metal] [--threshold X]";

fn main() -> Result<(), Box<dyn Error>> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let data = PathBuf::from(option(&args, "--data").unwrap_or("../data"));
    match args.first().map(String::as_str) {
        Some("verify") => verify(&data),
        Some("train") => train(
            &data,
            option(&args, "--name").unwrap_or("hard"),
            option(&args, "--steps").map_or(Ok(400), str::parse)?,
            args.iter().any(|a| a == "--soft"),
            option(&args, "--pad-to").map_or(Ok(0), str::parse)?,
        ),
        Some("predict") => package::predict(
            &PathBuf::from(required(&args, "--model")?),
            &PathBuf::from(required(&args, "--input")?),
            &PathBuf::from(required(&args, "--output")?),
            device_named(option(&args, "--device").unwrap_or(DEFAULT_DEVICE))?,
        ),
        Some("package") => package::set_threshold(&PathBuf::from(required(&args, "--model")?), required(&args, "--threshold")?.parse()?),
        _ => Err(USAGE.into()),
    }
}

fn option<'a>(args: &'a [String], name: &str) -> Option<&'a str> {
    args.iter().position(|a| a == name).and_then(|at| args.get(at + 1)).map(String::as_str)
}

fn required<'a>(args: &'a [String], name: &str) -> Result<&'a str, Box<dyn Error>> {
    option(args, name).ok_or_else(|| format!("{name} is required. {USAGE}").into())
}

#[cfg(target_os = "macos")]
const DEFAULT_DEVICE: &str = "metal";
#[cfg(not(target_os = "macos"))]
const DEFAULT_DEVICE: &str = "cpu";

fn device_named(name: &str) -> Result<Device, Box<dyn Error>> {
    match name {
        "cpu" => Ok(Device::flex()),
        #[cfg(target_os = "macos")]
        "metal" => Ok(Device::metal(Default::default())),
        other => Err(format!("{other} is not available on this machine").into()),
    }
}

fn device() -> Device {
    device_named(DEFAULT_DEVICE).expect("the default device exists")
}

/// Reproduces the embedding published for this sentence, proving the weights, tokenizer and every
/// layer's arithmetic are right before any training time is spent on them.
fn verify(data: &std::path::Path) -> Result<(), Box<dyn Error>> {
    const EXPECTED: [f32; 5] = [0.067_656_92, 0.063_495_94, 0.048_713_12, 0.079_304_93, 0.037_448_06];
    let device = device();
    let bytes = std::fs::read(data.join("model/model.safetensors"))?;
    let model = Model::load(&Weights(safetensors::SafeTensors::deserialize(&bytes)?), &device).valid();
    let tokenizer = load_tokenizer(&data.join("model/tokenizer.json"))?;
    let encoded = tokenizer.encode("This is an example sentence", true).map_err(|e| e.to_string())?;
    let example = Example {
        id: String::new(),
        rule: String::new(),
        says_same: false,
        tokens: encoded.get_ids().to_vec(),
        segments: encoded.get_type_ids().to_vec(),
        extra: [0.0; 2],
    };
    let batch = to_batch(&[&example], 0, &device);
    let vector = sentence_vector(&model, batch.tokens, batch.segments, batch.mask);
    println!("Got      {:?}", &vector[..5]);
    println!("Expected {EXPECTED:?}");
    let worst = vector.iter().zip(EXPECTED).map(|(a, b)| (a - b).abs()).fold(0.0, f32::max);
    if worst > 1e-4 {
        return Err(format!("embedding differs by {worst}: the model is not loading correctly").into());
    }
    println!("OK: matches the published embedding to within {worst:.1e}.");
    Ok(())
}

fn learning_rate(step: usize, steps: usize) -> f64 {
    let progress = step as f64 / steps as f64;
    PEAK_LEARNING_RATE * if progress < WARMUP { progress / WARMUP + 1e-3 } else { (1.0 - progress) / (1.0 - WARMUP) + 1e-3 }
}

fn seconds_since(start: Instant) -> f64 {
    start.elapsed().as_secs_f64()
}

fn train(data: &std::path::Path, name: &str, steps: usize, soft: bool, pad_to: usize) -> Result<(), Box<dyn Error>> {
    let plain = device();
    let device = plain.clone().autodiff();
    device.seed(SEED);

    let bytes = std::fs::read(data.join("model/model.safetensors"))?;
    let mut model = Model::load(&Weights(safetensors::SafeTensors::deserialize(&bytes)?), &device);
    drop(bytes);
    let tokenizer = load_tokenizer(&data.join("model/tokenizer.json"))?;
    let examples = load_examples(&data.join("train_pairs.csv"), None, &tokenizer)?;
    let gold = load_examples(&data.join("gold_key.csv"), Some("pair_id"), &tokenizer)?;

    let trust = if soft { soft_targets(data)? } else { Default::default() };
    if soft {
        let mut shown: Vec<_> = trust.iter().collect();
        shown.sort_by(|a, b| a.0.cmp(b.0));
        println!("Soft targets from calibration answers, as P(same): {shown:.2?}");
    }
    let target = |e: &Example| *trust.get(&e.rule).unwrap_or(&if e.says_same { 1.0 } else { 0.0 });
    let (same, different): (Vec<usize>, Vec<usize>) = (0..examples.len()).partition(|&i| examples[i].says_same);

    let params = model.num_params();
    println!("pid={} params={params} pairs={} same={} different={}", std::process::id(), examples.len(), same.len(), different.len());
    let mut hook = hook::Hook::start("metal");
    let mut optimizer = AdamWConfig::new().with_weight_decay(0.01).with_grad_clipping(Some(GradientClippingConfig::Norm(1.0))).init();
    let mut random = Random(SEED);
    let started = Instant::now();
    let mut step_start = Instant::now();
    let mut recent_loss = 0.0;

    for step in 0..steps {
        let chosen: Vec<&Example> = balanced_batch(&same, &different, BATCH, &mut random).into_iter().map(|i| &examples[i]).collect();
        let targets: Vec<f32> = chosen.iter().map(|e| target(e)).collect();
        let batch = to_batch(&chosen, pad_to, &device);
        let length = batch.tokens.dims()[1];
        let y: Tensor<1> = Tensor::from_data(TensorData::new(targets, [BATCH]), &device);
        let data_seconds = seconds_since(step_start);

        let compute_start = Instant::now();
        let logits = model.forward(batch);
        let loss = -(y.clone() * log_sigmoid(logits.clone()) + (-y + 1.0) * log_sigmoid(-logits)).mean();
        let grads = GradientsParams::from_grads(loss.backward(), &model);
        device.sync()?;
        let compute_seconds = seconds_since(compute_start);

        let optimizer_start = Instant::now();
        model = optimizer.step(learning_rate(step, steps), model, grads);
        device.sync()?;
        let optimizer_seconds = seconds_since(optimizer_start);
        recent_loss += loss.into_scalar::<f64>();

        let tokens = (BATCH * length) as u64;
        hook.publish(hook::Step {
            total: seconds_since(step_start),
            data: data_seconds,
            compute: compute_seconds,
            optimizer: optimizer_seconds,
            tokens,
            samples: BATCH as u64,
            flops: hook::flops_per_token(params, LAYERS, HIDDEN, length) * tokens as f64,
        });
        step_start = Instant::now();
        if (step + 1) % 20 == 0 {
            println!("step {:>4}/{steps}  loss {:.4}  {:.2} steps/s", step + 1, recent_loss / 20.0, (step + 1) as f64 / seconds_since(started));
            std::io::stdout().flush()?;
            recent_loss = 0.0;
        }
    }
    println!("Training took {:.0} s.", seconds_since(started));
    drop(hook);

    let frozen = model.valid();
    let path = data.join(format!("neural_scores_{name}.csv"));
    let mut out = csv::Writer::from_path(&path)?;
    out.write_record(["pair_id", "score"])?;
    for chunk in gold.chunks(64) {
        let references: Vec<&Example> = chunk.iter().collect();
        let scores = burn::tensor::activation::sigmoid(frozen.forward(to_batch(&references, 0, &plain)));
        let values: Vec<f32> = scores.into_data().convert::<f32>().try_to_vec::<f32>().expect("floats");
        for (example, score) in chunk.iter().zip(values) {
            out.write_record([example.id.as_str(), &score.to_string()])?;
        }
    }
    out.flush()?;
    println!("Scores for the checked pairs are in {}.", path.display());
    let saved = data.join("models").join(name);
    package::save(&frozen, data, &saved, &package::Training { name, steps, soft, pad_to, pairs: examples.len() })?;
    println!("Model saved in {}.", saved.display());
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_learning_rate_rises_then_falls_and_stays_positive() {
        assert!(learning_rate(0, 100) < learning_rate(9, 100));
        assert!(learning_rate(10, 100) > learning_rate(50, 100));
        assert!(learning_rate(99, 100) > 0.0);
    }
}
