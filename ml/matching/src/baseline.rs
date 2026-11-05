//! Sets the bar a neural model has to clear: the current rules, name similarity alone, and gradient
//! boosting, all judged only against what a person decided. Thresholds come from the calibration
//! pairs, and the test pairs are used once, for the final numbers.

use crate::features::{features, CORE_NAME_SIMILARITY};
use crate::gbdt::{fit, Model, Params};
use crate::labels::{field, read_rows, GoldPair};
use crate::sampling::stable_hash;
use std::collections::{BTreeMap, HashMap};
use std::error::Error;
use std::path::Path;

/// Below this many answers per class, thresholds and F1 mean little.
const MIN_PER_CLASS: usize = 10;
const RESAMPLES: usize = 2_000;
const SEED: u64 = 20_261_105;

struct TrainPair {
    poi_id: String,
    rule: String,
    same: bool,
    inputs: Vec<f64>,
}

fn read_training(dir: &Path) -> Result<Vec<TrainPair>, Box<dyn Error>> {
    read_rows(&dir.join("train_pairs.csv"))?
        .iter()
        .map(|row| {
            let distance: f64 = field(row, "distance_m")?.parse().map_err(|_| "bad distance_m in train_pairs.csv")?;
            Ok(TrainPair {
                poi_id: field(row, "poi_id")?.to_string(),
                rule: field(row, "rule")?.to_string(),
                same: field(row, "rule_label")? == "same",
                inputs: features(field(row, "name_a")?, field(row, "category_a")?, field(row, "name_b")?, field(row, "category_b")?, distance),
            })
        })
        .collect()
}

/// Each class counts equally, since nine in ten training pairs are "same".
fn balanced_weights(labels: &[bool]) -> Vec<f64> {
    let positive = labels.iter().filter(|l| **l).count().max(1) as f64;
    let negative = labels.iter().filter(|l| !**l).count().max(1) as f64;
    let total = labels.len() as f64;
    labels.iter().map(|l| if *l { total / (2.0 * positive) } else { total / (2.0 * negative) }).collect()
}

fn train(pairs: &[&TrainPair]) -> Model {
    let x: Vec<Vec<f64>> = pairs.iter().map(|p| p.inputs.clone()).collect();
    let y: Vec<bool> = pairs.iter().map(|p| p.same).collect();
    fit(&x, &y, &balanced_weights(&y), Params::default())
}

/// A person's answer on one pair and how much of its rule's population it stands for.
#[derive(Debug, Clone)]
struct Case {
    rule: String,
    truth: bool,
    weight: f64,
}

#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct Metrics {
    pub precision: f64,
    pub recall: f64,
    pub f1: f64,
}

fn metrics(indexes: impl Iterator<Item = usize>, cases: &[Case], predictions: &[bool]) -> Metrics {
    let (mut tp, mut fp, mut fn_) = (0.0, 0.0, 0.0);
    for i in indexes {
        match (predictions[i], cases[i].truth) {
            (true, true) => tp += cases[i].weight,
            (true, false) => fp += cases[i].weight,
            (false, true) => fn_ += cases[i].weight,
            (false, false) => {}
        }
    }
    let precision = if tp + fp > 0.0 { tp / (tp + fp) } else { 0.0 };
    let recall = if tp + fn_ > 0.0 { tp / (tp + fn_) } else { 0.0 };
    let f1 = if precision + recall > 0.0 { 2.0 * precision * recall / (precision + recall) } else { 0.0 };
    Metrics { precision, recall, f1 }
}

/// Each rule was sampled at its own rate, so each answer is weighted to stand for its share of the
/// rule's pairs. Without this, small rules would count far beyond their real size.
fn cases(pairs: &[&GoldPair]) -> Vec<Case> {
    let mut answered: HashMap<&str, usize> = HashMap::new();
    for pair in pairs {
        *answered.entry(&pair.rule).or_insert(0) += 1;
    }
    pairs
        .iter()
        .map(|p| Case { rule: p.rule.clone(), truth: p.truth().unwrap_or(false), weight: p.rule_population as f64 / answered[p.rule.as_str()] as f64 })
        .collect()
}

/// The score cut-off with the best F1 on the calibration pairs.
fn best_threshold(scores: &[f64], cases: &[Case]) -> f64 {
    let mut candidates = scores.to_vec();
    candidates.sort_by(f64::total_cmp);
    candidates.dedup();
    let mut best = (0.5, -1.0);
    for threshold in candidates {
        let predictions: Vec<bool> = scores.iter().map(|s| *s >= threshold).collect();
        let f1 = metrics(0..cases.len(), cases, &predictions).f1;
        if f1 > best.1 {
            best = (threshold, f1);
        }
    }
    best.0
}

/// xorshift64*: enough for resampling, and the same on every machine.
struct Random(u64);

impl Random {
    fn below(&mut self, limit: usize) -> usize {
        self.0 ^= self.0 >> 12;
        self.0 ^= self.0 << 25;
        self.0 ^= self.0 >> 27;
        (self.0.wrapping_mul(0x2545_f491_4f6c_dd1d) >> 33) as usize % limit
    }
}

type Range = (f64, f64);

/// 95% ranges by resampling answers within each rule, for each system and for a system's gain over
/// the first one. The same resamples score every system, so the gain is a fair paired comparison.
fn bootstrap(cases: &[Case], systems: &[&[bool]]) -> (Vec<Range>, Vec<Range>) {
    let mut by_rule: BTreeMap<&str, Vec<usize>> = BTreeMap::new();
    for (i, case) in cases.iter().enumerate() {
        by_rule.entry(&case.rule).or_default().push(i);
    }
    let mut random = Random(SEED | 1);
    let mut f1s = vec![Vec::with_capacity(RESAMPLES); systems.len()];
    let mut gains = vec![Vec::with_capacity(RESAMPLES); systems.len()];
    let mut sample = Vec::with_capacity(cases.len());
    for _ in 0..RESAMPLES {
        sample.clear();
        for rows in by_rule.values() {
            for _ in rows {
                sample.push(rows[random.below(rows.len())]);
            }
        }
        let scores: Vec<f64> = systems.iter().map(|p| metrics(sample.iter().copied(), cases, p).f1).collect();
        for (s, score) in scores.iter().enumerate() {
            f1s[s].push(*score);
            gains[s].push(score - scores[0]);
        }
    }
    let range = |mut values: Vec<f64>| {
        values.sort_by(f64::total_cmp);
        (values[RESAMPLES * 25 / 1000], values[RESAMPLES * 975 / 1000])
    };
    (f1s.into_iter().map(range).collect(), gains.into_iter().map(range).collect())
}

fn enough(pairs: &[&GoldPair]) -> bool {
    let same = pairs.iter().filter(|p| p.truth() == Some(true)).count();
    let different = pairs.iter().filter(|p| p.truth() == Some(false)).count();
    same >= MIN_PER_CLASS && different >= MIN_PER_CLASS
}

/// Trains on most pairs and predicts the rest, keeping each mapped place on one side. High agreement
/// means the model copies the rules well, which is not the same as being right.
fn check_against_rules(pairs: &[TrainPair]) {
    let (held, used): (Vec<&TrainPair>, Vec<&TrainPair>) = pairs.iter().partition(|p| stable_hash(SEED, &p.poi_id).is_multiple_of(5));
    let model = train(&used);
    println!("How well it copies the rules on {} training pairs held back:", held.len());
    println!("{:<36}{:>10}{:>11}", "Rule", "Held back", "Agreement");
    let mut by_rule: BTreeMap<&str, (usize, usize)> = BTreeMap::new();
    for pair in &held {
        let entry = by_rule.entry(&pair.rule).or_default();
        entry.0 += 1;
        entry.1 += usize::from((model.score(&pair.inputs) >= 0.5) == pair.same);
    }
    for (rule, (total, agree)) in by_rule {
        println!("{rule:<36}{total:>10}{:>10.0}%", agree as f64 / total as f64 * 100.0);
    }
    println!("This shows imitation, not accuracy. Only a person's answers can show accuracy.\n");
}

/// Scores another model produced for the checked pairs, by pair id.
fn read_scores(path: &Path) -> Result<HashMap<String, f64>, Box<dyn Error>> {
    read_rows(path)?
        .iter()
        .map(|row| {
            let score: f64 = field(row, "score")?.parse().map_err(|_| format!("bad score in {}", path.display()))?;
            Ok((field(row, "pair_id")?.to_string(), score))
        })
        .collect()
}

pub fn run(dir: &Path, gold: &[GoldPair], neural: Option<&Path>) -> Result<(), Box<dyn Error>> {
    let training = read_training(dir)?;
    check_against_rules(&training);

    let answered = |split: &str| -> Vec<&GoldPair> { gold.iter().filter(|p| p.split == split && p.truth().is_some()).collect() };
    let (calibration, test) = (answered("calibration"), answered("test"));
    if !enough(&calibration) || !enough(&test) {
        println!(
            "Not enough answers to compare yet: {} calibration and {} test pairs answered. Each needs at least {MIN_PER_CLASS} same and {MIN_PER_CLASS} different.",
            calibration.len(), test.len()
        );
        return Ok(());
    }

    let model = train(&training.iter().collect::<Vec<_>>());
    let inputs = |p: &&GoldPair| features(&p.name_a, &p.category_a, &p.name_b, &p.category_b, p.distance_m);
    let name_score = |p: &&GoldPair| inputs(p)[CORE_NAME_SIMILARITY];
    let model_score = |p: &&GoldPair| model.score(&inputs(p));

    let (calibration_cases, test_cases) = (cases(&calibration), cases(&test));
    let name_cut = best_threshold(&calibration.iter().map(name_score).collect::<Vec<_>>(), &calibration_cases);
    let model_cut = best_threshold(&calibration.iter().map(model_score).collect::<Vec<_>>(), &calibration_cases);

    let rules: Vec<bool> = test.iter().map(|p| p.rules_say_same()).collect();
    let name: Vec<bool> = test.iter().map(|p| name_score(p) >= name_cut).collect();
    let boosted: Vec<bool> = test.iter().map(|p| model_score(p) >= model_cut).collect();
    let mut systems: Vec<(&str, &[bool])> = vec![("Current rules", &rules), ("Name similarity", &name), ("Gradient boosting", &boosted)];

    let neural_predictions: Option<Vec<bool>> = neural
        .map(|path| -> Result<Vec<bool>, Box<dyn Error>> {
            let scores = read_scores(path)?;
            let score = |p: &&GoldPair| scores.get(&p.id).copied().ok_or_else(|| format!("{} has no score for {}", path.display(), p.id));
            let calibration_scores = calibration.iter().map(score).collect::<Result<Vec<_>, _>>()?;
            let cut = best_threshold(&calibration_scores, &calibration_cases);
            let calibration_predictions: Vec<bool> = calibration_scores.iter().map(|s| *s >= cut).collect();
            let calibration_f1 = metrics(0..calibration_cases.len(), &calibration_cases, &calibration_predictions).f1;
            println!("Neural model {}: cut-off {cut:.3}, F1 on calibration pairs {calibration_f1:.3}.", path.display());
            Ok(test.iter().map(score).collect::<Result<Vec<_>, _>>()?.into_iter().map(|s| s >= cut).collect())
        })
        .transpose()?;
    if let Some(predictions) = &neural_predictions {
        systems.push(("Neural cross-encoder", predictions));
    }
    let predictions: Vec<&[bool]> = systems.iter().map(|(_, p)| *p).collect();
    let (ranges, gains) = bootstrap(&test_cases, &predictions);

    println!("Test pairs answered by a person: {}. Results are weighted to each rule's full size.", test.len());
    println!("Cut-offs from calibration pairs: name similarity {name_cut:.3}, gradient boosting {model_cut:.3}.\n");
    println!("{:<22}{:>11}{:>8}{:>6}{:>16}{:>22}", "System", "Precision", "Recall", "F1", "F1 95% range", "Gain over rules");
    for (i, (name, prediction)) in systems.iter().enumerate() {
        let m = metrics(0..test_cases.len(), &test_cases, prediction);
        let gain = if i == 0 { "-".to_string() } else { format!("{:+.3} to {:+.3}", gains[i].0, gains[i].1) };
        println!("{name:<22}{:>11.3}{:>8.3}{:>6.3}{:>16}{gain:>22}", m.precision, m.recall, m.f1, format!("{:.3}-{:.3}", ranges[i].0, ranges[i].1));
    }
    let last = systems.len() - 1;
    let (low, high) = gains[last];
    println!(
        "\n{}",
        if low > 0.0 {
            format!("{} clearly beats the rules.", systems[last].0)
        } else if high < 0.0 {
            format!("The rules clearly beat {}.", systems[last].0)
        } else {
            format!("{} vs the rules: no clear winner, because the range for the gain includes zero.", systems[last].0)
        }
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn case(rule: &str, truth: bool, weight: f64) -> Case {
        Case { rule: rule.into(), truth, weight }
    }

    #[test]
    fn metrics_use_the_weights() {
        let cases = [case("big", true, 90.0), case("small", false, 10.0), case("big", true, 90.0)];
        let m = metrics(0..3, &cases, &[true, true, false]);
        assert_eq!(m.precision, 0.9);
        assert_eq!(m.recall, 0.5);
        assert!((m.f1 - 2.0 * 0.9 * 0.5 / 1.4).abs() < 1e-12);
        assert_eq!(metrics(0..1, &cases, &[false, false, false]), Metrics::default());
    }

    #[test]
    fn each_answer_stands_for_its_share_of_the_rule() {
        let pair = |rule: &str, population| GoldPair { rule: rule.into(), rule_population: population, label: Some(crate::labels::Label::Same), ..GoldPair::default() };
        let (a, b, c) = (pair("big", 1000), pair("big", 1000), pair("small", 30));
        let weights: Vec<f64> = cases(&[&a, &b, &c]).iter().map(|c| c.weight).collect();
        assert_eq!(weights, vec![500.0, 500.0, 30.0]);
    }

    #[test]
    fn the_threshold_separates_the_classes_when_it_can() {
        let cases = [case("r", false, 1.0), case("r", false, 1.0), case("r", true, 1.0), case("r", true, 1.0)];
        assert_eq!(best_threshold(&[0.1, 0.2, 0.8, 0.9], &cases), 0.8);
    }

    #[test]
    fn a_better_system_shows_a_gain_and_an_equal_one_does_not() {
        let cases: Vec<Case> = (0..200).map(|i| case("r", i % 2 == 0, 1.0)).collect();
        let truth: Vec<bool> = cases.iter().map(|c| c.truth).collect();
        let poor: Vec<bool> = (0..200).map(|i| if i % 4 == 0 { i % 2 != 0 } else { i % 2 == 0 }).collect();
        let (ranges, gains) = bootstrap(&cases, &[&poor, &truth, &poor]);
        assert!(gains[1].0 > 0.0, "{:?}", gains[1]);
        assert_eq!(gains[2], (0.0, 0.0));
        assert_eq!(ranges[1], (1.0, 1.0));
        assert_eq!(bootstrap(&cases, &[&poor, &truth]).1[1], gains[1], "the same every run");
    }

    #[test]
    fn balanced_weights_give_each_class_equal_total() {
        let labels = [true, true, true, false];
        let weights = balanced_weights(&labels);
        assert_eq!(weights[0] * 3.0, weights[3]);
        assert_eq!(weights.iter().sum::<f64>(), 4.0);
    }
}
