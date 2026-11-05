//! Reads pairs, tokenizes them and builds the balanced batches. No contact details reach this code:
//! they are not in `train_pairs.csv`, and only the fields below are read from `gold_key.csv`.

use burn::prelude::*;
use std::collections::HashMap;
use std::error::Error;
use std::path::Path;
use tokenizers::{Tokenizer, TruncationParams};

use crate::model::{Batch, EXTRA_INPUTS};

/// Place names are short. This fits all but very long listing names, and keeps memory low on an 8 GB Mac.
const MAX_TOKENS: usize = 48;

#[derive(Debug, Clone)]
pub struct Example {
    pub id: String,
    pub rule: String,
    pub says_same: bool,
    pub tokens: Vec<u32>,
    pub segments: Vec<u32>,
    pub extra: [f32; EXTRA_INPUTS],
}

pub fn load_tokenizer(path: &Path) -> Result<Tokenizer, Box<dyn Error>> {
    let mut tokenizer = Tokenizer::from_file(path).map_err(|e| e.to_string())?;
    tokenizer.with_padding(None);
    tokenizer.with_truncation(Some(TruncationParams { max_length: MAX_TOKENS, ..TruncationParams::default() })).map_err(|e| e.to_string())?;
    Ok(tokenizer)
}

/// Distance on a log scale so the difference between 5 m and 50 m matters as much as 50 m and 500 m.
fn extra_inputs(distance_m: f32, category_a: &str, category_b: &str) -> [f32; EXTRA_INPUTS] {
    [(distance_m.max(0.0) + 1.0).ln() / 7.0, f32::from(u8::from(category_a == category_b))]
}

fn describe(name: &str, category: &str) -> String {
    format!("{name} ({})", category.replace('_', " "))
}

fn rows(path: &Path) -> Result<Vec<HashMap<String, String>>, Box<dyn Error>> {
    let mut reader = csv::Reader::from_path(path).map_err(|e| format!("cannot read {}: {e}", path.display()))?;
    let headers = reader.headers()?.clone();
    let mut out = Vec::new();
    for record in reader.records() {
        out.push(headers.iter().map(String::from).zip(record?.iter().map(String::from)).collect());
    }
    Ok(out)
}

pub fn load_examples(path: &Path, id_column: Option<&str>, tokenizer: &Tokenizer) -> Result<Vec<Example>, Box<dyn Error>> {
    rows(path)?
        .iter()
        .map(|row| {
            let get = |name: &str| row.get(name).map(String::as_str).ok_or_else(|| format!("column {name} is missing"));
            let (category_a, category_b) = (get("category_a")?, get("category_b")?);
            let text = (describe(get("name_a")?, category_a), describe(get("name_b")?, category_b));
            let encoded = tokenizer.encode(text, true).map_err(|e| e.to_string())?;
            let distance: f32 = get("distance_m")?.parse()?;
            Ok(Example {
                id: id_column.and_then(|column| row.get(column)).cloned().unwrap_or_default(),
                rule: row.get("rule").cloned().unwrap_or_default(),
                says_same: row.get("rule_label").is_some_and(|label| label == "same"),
                tokens: encoded.get_ids().to_vec(),
                segments: encoded.get_type_ids().to_vec(),
                extra: extra_inputs(distance, category_a, category_b),
            })
        })
        .collect()
}

/// Soft targets: how likely a rule's decision is to mean "same", learned from the person's answers
/// on the calibration pairs only. Test answers never reach training. The smoothing pulls a thinly
/// checked rule towards what it claims, as if it came with two extra checks agreeing with it.
pub fn soft_targets(data: &Path) -> Result<HashMap<String, f32>, Box<dyn Error>> {
    let labels: HashMap<String, String> = rows(&data.join("gold_sheet.csv"))?
        .into_iter()
        .filter_map(|mut row| Some((row.remove("pair_id")?, row.remove("label")?.trim().to_lowercase())))
        .collect();
    let mut counts: HashMap<String, (bool, f32, f32)> = HashMap::new();
    for row in rows(&data.join("gold_key.csv"))? {
        if row.get("split").map(String::as_str) != Some("calibration") {
            continue;
        }
        let (Some(id), Some(rule), Some(rule_label)) = (row.get("pair_id"), row.get("rule"), row.get("rule_label")) else { continue };
        let entry = counts.entry(rule.clone()).or_insert((rule_label == "same", 0.0, 0.0));
        match labels.get(id).map(String::as_str) {
            Some("same") => entry.1 += 1.0,
            Some("different") => entry.2 += 1.0,
            _ => {}
        }
    }
    Ok(counts
        .into_iter()
        .map(|(rule, (says_same, same, different))| {
            let (same, different) = if says_same { (same + 2.0, different) } else { (same, different + 2.0) };
            (rule, same / (same + different))
        })
        .collect())
}

/// xorshift64*: the same batches on every run.
pub struct Random(pub u64);

impl Random {
    pub fn below(&mut self, limit: usize) -> usize {
        self.0 ^= self.0 >> 12;
        self.0 ^= self.0 << 25;
        self.0 ^= self.0 >> 27;
        (self.0.wrapping_mul(0x2545_f491_4f6c_dd1d) >> 33) as usize % limit
    }
}

/// Half of each batch from each side, since nine in ten rule decisions are "same".
pub fn balanced_batch(same: &[usize], different: &[usize], size: usize, random: &mut Random) -> Vec<usize> {
    (0..size)
        .map(|i| {
            let side = if i % 2 == 0 { same } else { different };
            side[random.below(side.len())]
        })
        .collect()
}

/// Every batch's padded length: its longest pair, or `pad_to` if that is longer. A fixed length keeps
/// tensor shapes the same from step to step.
fn batch_length(examples: &[&Example], pad_to: usize) -> usize {
    examples.iter().map(|e| e.tokens.len()).max().unwrap_or(0).max(pad_to)
}

pub fn to_batch(examples: &[&Example], pad_to: usize, device: &Device) -> Batch {
    let (size, length) = (examples.len(), batch_length(examples, pad_to));
    let (mut tokens, mut segments, mut mask) = (vec![0i32; size * length], vec![0i32; size * length], vec![0f32; size * length]);
    let mut extra = Vec::with_capacity(size * EXTRA_INPUTS);
    for (row, example) in examples.iter().enumerate() {
        for (column, token) in example.tokens.iter().enumerate() {
            let at = row * length + column;
            tokens[at] = *token as i32;
            segments[at] = example.segments[column] as i32;
            mask[at] = 1.0;
        }
        extra.extend_from_slice(&example.extra);
    }
    Batch {
        tokens: Tensor::from_data(TensorData::new(tokens, [size, length]), device),
        segments: Tensor::from_data(TensorData::new(segments, [size, length]), device),
        mask: Tensor::from_data(TensorData::new(mask, [size, length]), device),
        extra: Tensor::from_data(TensorData::new(extra, [size, EXTRA_INPUTS]), device),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn distance_is_on_a_log_scale_and_categories_compare_exactly() {
        let [near, same] = extra_inputs(0.0, "hotel", "hotel");
        assert_eq!((near, same), (0.0, 1.0));
        let [far, different] = extra_inputs(400.0, "hotel", "resort");
        assert!(far > 0.85 && far < 0.87);
        assert_eq!(different, 0.0);
    }

    #[test]
    fn each_batch_is_half_each_side() {
        let (same, different): (Vec<usize>, Vec<usize>) = ((0..90).collect(), (90..100).collect());
        let batch = balanced_batch(&same, &different, 32, &mut Random(7));
        assert_eq!(batch.iter().filter(|i| **i >= 90).count(), 16);
    }

    #[test]
    fn pad_to_sets_a_floor_on_batch_length() {
        let example = Example { id: String::new(), rule: String::new(), says_same: true, tokens: vec![1; 10], segments: vec![0; 10], extra: [0.0; 2] };
        assert_eq!(batch_length(&[&example], 0), 10);
        assert_eq!(batch_length(&[&example], 48), 48);
    }

    #[test]
    fn describes_a_place_with_a_readable_category() {
        assert_eq!(describe("Hotel Lotus", "guest_house"), "Hotel Lotus (guest house)");
    }
}
