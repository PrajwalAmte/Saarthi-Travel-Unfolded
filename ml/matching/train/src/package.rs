//! A saved model as a self-contained folder: weights, tokenizer and a manifest. The folder is all that
//! `predict` needs, so it can run on any machine without the training data or the pretrained model.

use crate::data::{load_examples, load_tokenizer, to_batch, Example};
use crate::model::{Model, Weights};
use burn::prelude::*;
use burn::tensor::activation::sigmoid;
use serde_json::{json, Value};
use std::error::Error;
use std::path::Path;
use std::time::Instant;

const BATCH: usize = 64;
const MANIFEST: &str = "manifest.json";
const WEIGHTS: &str = "model.safetensors";
const TOKENIZER: &str = "tokenizer.json";

pub struct Training<'a> {
    pub name: &'a str,
    pub steps: usize,
    pub soft: bool,
    pub pad_to: usize,
    pub pairs: usize,
}

fn manifest(training: &Training) -> Value {
    json!({
        "format": 1,
        "name": training.name,
        "task": "Decide whether two map records are the same real place, from names, categories and distance",
        "base_model": "sentence-transformers/all-MiniLM-L6-v2",
        "inputs": ["name_a", "category_a", "name_b", "category_b", "distance_m"],
        "training": {
            "pairs": training.pairs,
            "steps": training.steps,
            "soft_targets": training.soft,
            "pad_to": training.pad_to,
            "labels": "Decisions by Saarthi's matching rules; judged against labels by the author, about 30% AI-drafted then reviewed",
        },
        // Set by `package`, from the cut-off chosen on the calibration pairs.
        "threshold": null,
    })
}

pub fn save(model: &Model, data: &Path, folder: &Path, training: &Training) -> Result<(), Box<dyn Error>> {
    std::fs::create_dir_all(folder)?;
    model.save(&folder.join(WEIGHTS))?;
    std::fs::copy(data.join("model").join(TOKENIZER), folder.join(TOKENIZER))?;
    std::fs::write(folder.join(MANIFEST), serde_json::to_string_pretty(&manifest(training))?)?;
    Ok(())
}

fn read_manifest(folder: &Path) -> Result<Value, Box<dyn Error>> {
    let path = folder.join(MANIFEST);
    let text = std::fs::read_to_string(&path).map_err(|e| format!("cannot read {}: {e}", path.display()))?;
    Ok(serde_json::from_str(&text)?)
}

pub fn set_threshold(folder: &Path, threshold: f64) -> Result<(), Box<dyn Error>> {
    if !(0.0..=1.0).contains(&threshold) {
        return Err("the threshold must be between 0 and 1".into());
    }
    let mut manifest = read_manifest(folder)?;
    manifest["threshold"] = json!(threshold);
    std::fs::write(folder.join(MANIFEST), serde_json::to_string_pretty(&manifest)?)?;
    println!("{} now decides \"same\" at a score of {threshold} or above.", folder.display());
    Ok(())
}

/// Longest pairs first keeps similar lengths together, so less time goes on padding. Answers go back
/// in the input's order.
fn batches_by_length(examples: &[Example]) -> Vec<Vec<usize>> {
    let mut order: Vec<usize> = (0..examples.len()).collect();
    order.sort_by_key(|&i| std::cmp::Reverse(examples[i].tokens.len()));
    order.chunks(BATCH).map(<[usize]>::to_vec).collect()
}

fn decision(score: f32, threshold: Option<f64>) -> &'static str {
    match threshold {
        Some(cut) if f64::from(score) >= cut => "same",
        Some(_) => "different",
        None => "",
    }
}

pub fn predict(folder: &Path, input: &Path, output: &Path, device: Device) -> Result<(), Box<dyn Error>> {
    let threshold = read_manifest(folder)?["threshold"].as_f64();
    let bytes = std::fs::read(folder.join(WEIGHTS))?;
    let model = Model::load(&Weights(safetensors::SafeTensors::deserialize(&bytes)?), &device).valid();
    drop(bytes);
    let tokenizer = load_tokenizer(&folder.join(TOKENIZER))?;
    let examples = load_examples(input, Some("pair_id"), &tokenizer)?;

    let started = Instant::now();
    let mut scores = vec![0f32; examples.len()];
    for batch in batches_by_length(&examples) {
        let chosen: Vec<&Example> = batch.iter().map(|&i| &examples[i]).collect();
        let values: Vec<f32> = sigmoid(model.forward(to_batch(&chosen, 0, &device))).into_data().convert::<f32>().try_to_vec::<f32>().expect("floats");
        for (&at, value) in batch.iter().zip(values) {
            scores[at] = value;
        }
    }
    let seconds = started.elapsed().as_secs_f64();

    let mut out = csv::Writer::from_path(output)?;
    out.write_record(["pair_id", "score", "decision"])?;
    for (example, score) in examples.iter().zip(&scores) {
        out.write_record([example.id.as_str(), &score.to_string(), decision(*score, threshold)])?;
    }
    out.flush()?;
    println!("Scored {} pairs in {seconds:.1} s ({:.0} pairs/s). Results are in {}.", examples.len(), examples.len() as f64 / seconds.max(1e-9), output.display());
    if threshold.is_none() {
        println!("This model has no threshold yet, so the decision column is empty. Set one with `package`.");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decisions_follow_the_threshold_and_stay_empty_without_one() {
        assert_eq!(decision(0.2, Some(0.154)), "same");
        assert_eq!(decision(0.1, Some(0.154)), "different");
        assert_eq!(decision(0.154, Some(0.154_f32 as f64)), "same");
        assert_eq!(decision(0.9, None), "");
    }

    #[test]
    fn the_manifest_starts_without_a_threshold_and_records_how_it_was_trained() {
        let m = manifest(&Training { name: "hard", steps: 400, soft: false, pad_to: 0, pairs: 19_332 });
        assert!(m["threshold"].is_null());
        assert_eq!(m["training"]["pairs"], 19_332);
        assert_eq!(m["base_model"], "sentence-transformers/all-MiniLM-L6-v2");
    }

    #[test]
    fn set_threshold_changes_only_the_threshold_and_refuses_nonsense() {
        let dir = std::env::temp_dir().join(format!("saarthi-package-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let m = manifest(&Training { name: "hard", steps: 400, soft: false, pad_to: 0, pairs: 10 });
        std::fs::write(dir.join(MANIFEST), serde_json::to_string(&m).unwrap()).unwrap();
        set_threshold(&dir, 0.154).unwrap();
        let read = read_manifest(&dir).unwrap();
        assert_eq!(read["threshold"], 0.154);
        assert_eq!(read["name"], "hard");
        assert!(set_threshold(&dir, 1.5).is_err());
    }

    #[test]
    fn batches_keep_every_pair_once_with_similar_lengths_together() {
        let example = |n| Example { id: String::new(), rule: String::new(), says_same: false, tokens: vec![1; n], segments: vec![0; n], extra: [0.0; 2] };
        let examples: Vec<Example> = (1..=200).map(|i| example(i % 40 + 1)).collect();
        let batches = batches_by_length(&examples);
        let mut seen: Vec<usize> = batches.iter().flatten().copied().collect();
        seen.sort_unstable();
        assert_eq!(seen, (0..200).collect::<Vec<_>>());
        let lengths = |batch: &Vec<usize>| batch.iter().map(|&i| examples[i].tokens.len()).collect::<Vec<_>>();
        let (first, last) = (lengths(&batches[0]), lengths(&batches[batches.len() - 1]));
        assert!(first.iter().min() >= last.iter().max(), "longest pairs batch together");
    }
}
