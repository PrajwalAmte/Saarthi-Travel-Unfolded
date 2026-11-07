//! Compares the models trained on `train_pairs_v2.csv` and combines them. The models learn from rule
//! decisions; a short logistic fit on the person's calibration answers then weighs them, and two cut-offs
//! come from predictions made on pairs the fit had not seen. The test pairs are read once, for the numbers
//! at the end.

use crate::baseline::{balanced_weights, best_threshold, bootstrap, cases, enough, metrics, read_scores, Case, Metrics};
use crate::build::FLAGS;
use crate::features::features;
use crate::gbdt::{fit, Model, Params};
use crate::labels::{field, read_gold, read_rows, GoldPair};
use crate::sampling::stable_hash;
use std::collections::HashMap;
use std::error::Error;
use std::path::Path;

const SEED: u64 = 20_261_105;
const FOLDS: u64 = 5;
/// What a pair the system merges by itself has to achieve on the calibration pairs.
const AUTOMATIC_PRECISION: f64 = 0.95;
/// Fewer pairs than this above a cut-off say too little about it.
const MIN_AUTOMATIC_PAIRS: usize = 15;
const RIDGE: f64 = 0.5;

struct Training {
    names: Vec<f64>,
    flags: Vec<f64>,
    same: bool,
    weight: f64,
}

fn read_training(dir: &Path) -> Result<Vec<Training>, Box<dyn Error>> {
    read_rows(&dir.join("train_pairs_v2.csv"))?
        .iter()
        .map(|row| {
            let distance: f64 = field(row, "distance_m")?.parse().map_err(|_| "bad distance_m")?;
            Ok(Training {
                names: features(field(row, "name_a")?, field(row, "category_a")?, field(row, "name_b")?, field(row, "category_b")?, distance),
                flags: FLAGS.iter().map(|f| field(row, f)?.parse::<f64>().map_err(|_| format!("bad {f}"))).collect::<Result<_, _>>()?,
                same: field(row, "rule_label")? == "same",
                weight: field(row, "weight")?.parse().map_err(|_| "bad weight")?,
            })
        })
        .collect()
}

/// What the current rules and the contact evidence say about a checked pair.
struct Evidence {
    rules_say_same: bool,
    flags: Vec<f64>,
}

fn read_evidence(dir: &Path) -> Result<HashMap<String, Evidence>, Box<dyn Error>> {
    read_rows(&dir.join("gold_evidence.csv"))?
        .iter()
        .map(|row| {
            let found = field(row, "found")? == "1";
            let flags = if found { FLAGS.iter().map(|f| field(row, f)?.parse::<f64>().map_err(|_| format!("bad {f}"))).collect::<Result<_, _>>()? } else { vec![0.0; FLAGS.len()] };
            Ok((field(row, "pair_id")?.to_string(), Evidence { rules_say_same: field(row, "fixed_label")? == "same", flags }))
        })
        .collect()
}

fn boost(rows: &[&Training], with_flags: bool) -> Model {
    let x: Vec<Vec<f64>> = rows.iter().map(|r| if with_flags { [r.names.clone(), r.flags.clone()].concat() } else { r.names.clone() }).collect();
    let y: Vec<bool> = rows.iter().map(|r| r.same).collect();
    // Each class counts equally, and each pair counts as much as its rule agreed with the person.
    let weights: Vec<f64> = balanced_weights(&y).iter().zip(rows).map(|(w, r)| w * r.weight).collect();
    fit(&x, &y, &weights, Params::default())
}

fn logit(p: f64) -> f64 {
    let p = p.clamp(1e-4, 1.0 - 1e-4);
    (p / (1.0 - p)).ln()
}

fn sigmoid(z: f64) -> f64 {
    1.0 / (1.0 + (-z).exp())
}

/// Solves `a x = b` by Gaussian elimination with partial pivoting.
fn solve(mut a: Vec<Vec<f64>>, mut b: Vec<f64>) -> Vec<f64> {
    let n = b.len();
    for col in 0..n {
        let pivot = (col..n).max_by(|&i, &j| a[i][col].abs().total_cmp(&a[j][col].abs())).unwrap_or(col);
        a.swap(col, pivot);
        b.swap(col, pivot);
        for row in col + 1..n {
            let factor = a[row][col] / a[col][col];
            let pivot_row = a[col].clone();
            for (cell, pivot_cell) in a[row].iter_mut().zip(&pivot_row).skip(col) {
                *cell -= factor * pivot_cell;
            }
            b[row] -= factor * b[col];
        }
    }
    let mut x = vec![0.0; n];
    for row in (0..n).rev() {
        let tail: f64 = (row + 1..n).map(|k| a[row][k] * x[k]).sum();
        x[row] = (b[row] - tail) / a[row][row];
    }
    x
}

/// Logistic regression by Newton's method with a ridge penalty. The last coefficient is the intercept.
fn fit_logistic(x: &[Vec<f64>], y: &[bool]) -> Vec<f64> {
    let k = x[0].len() + 1;
    let mut beta = vec![0.0; k];
    for _ in 0..40 {
        let mut gradient = vec![0.0; k];
        let mut hessian = vec![vec![0.0; k]; k];
        for (row, &label) in x.iter().zip(y) {
            let mut input = row.clone();
            input.push(1.0);
            let p = sigmoid(input.iter().zip(&beta).map(|(a, b)| a * b).sum());
            for a in 0..k {
                gradient[a] += (p - f64::from(u8::from(label))) * input[a];
                for b in 0..k {
                    hessian[a][b] += p * (1.0 - p) * input[a] * input[b];
                }
            }
        }
        for a in 0..k - 1 {
            gradient[a] += RIDGE * beta[a];
            hessian[a][a] += RIDGE;
        }
        hessian[k - 1][k - 1] += 1e-6;
        let step = solve(hessian, gradient);
        for a in 0..k {
            beta[a] -= step[a];
        }
    }
    beta
}

fn predict(beta: &[f64], row: &[f64]) -> f64 {
    sigmoid(row.iter().zip(beta).map(|(a, b)| a * b).sum::<f64>() + beta[beta.len() - 1])
}

/// 95% interval for a share, from counts: how far a small sample can be trusted.
pub fn wilson(successes: usize, total: usize) -> (f64, f64) {
    if total == 0 {
        return (0.0, 1.0);
    }
    let (n, p, z) = (total as f64, successes as f64 / total as f64, 1.96_f64);
    let centre = p + z * z / (2.0 * n);
    let spread = z * (p * (1.0 - p) / n + z * z / (4.0 * n * n)).sqrt();
    ((centre - spread) / (1.0 + z * z / n), (centre + spread) / (1.0 + z * z / n))
}

/// The lowest score cut-off whose pairs reach the precision target with enough pairs above it.
fn automatic_cut(scores: &[f64], cases: &[Case]) -> Option<f64> {
    let mut candidates = scores.to_vec();
    candidates.sort_by(f64::total_cmp);
    candidates.dedup();
    candidates.into_iter().find(|&cut| {
        let above = || (0..scores.len()).filter(|&i| scores[i] >= cut);
        let (mut right, mut all) = (0.0, 0.0);
        for i in above() {
            all += cases[i].weight;
            if cases[i].truth {
                right += cases[i].weight;
            }
        }
        above().count() >= MIN_AUTOMATIC_PAIRS && all > 0.0 && right / all >= AUTOMATIC_PRECISION
    })
}

struct Scored<'a> {
    pair: &'a GoldPair,
    neural: f64,
    boosted: f64,
    names_only: f64,
    rules_say_same: bool,
}

impl Scored<'_> {
    fn stack_inputs(&self) -> Vec<f64> {
        vec![logit(self.neural), logit(self.boosted), f64::from(u8::from(self.rules_say_same))]
    }
}

fn score_pairs<'a>(
    pairs: &[&'a GoldPair],
    evidence: &HashMap<String, Evidence>,
    neural_scores: &HashMap<String, f64>,
    names_model: &Model,
    boosted_model: &Model,
) -> Result<Vec<Scored<'a>>, Box<dyn Error>> {
    pairs
        .iter()
        .map(|&pair| {
            let e = evidence.get(&pair.id).ok_or_else(|| format!("{} is missing from gold_evidence.csv", pair.id))?;
            let base = features(&pair.name_a, &pair.category_a, &pair.name_b, &pair.category_b, pair.distance_m);
            Ok(Scored {
                pair,
                neural: *neural_scores.get(&pair.id).ok_or_else(|| format!("{} has no neural score", pair.id))?,
                boosted: boosted_model.score(&[base.clone(), e.flags.clone()].concat()),
                names_only: names_model.score(&base),
                rules_say_same: e.rules_say_same,
            })
        })
        .collect()
}

fn band_row(name: &str, cut: Option<f64>, members: &[(&Scored, f64)]) {
    let same = members.iter().filter(|(s, _)| s.pair.truth() == Some(true)).count();
    let different = members.len() - same;
    let cut = cut.map_or("none".to_string(), |c| format!("{c:.3}"));
    let share = if members.is_empty() { "-".to_string() } else { format!("{:.0}%", same as f64 / members.len() as f64 * 100.0) };
    println!("{name:<14}{cut:>9}{:>8}{same:>8}{different:>11}{share:>12}", members.len());
}

/// With `fresh`, everything is fitted on the first sheet's calibration pairs and applied unchanged to the second
/// sheet, whose pairs are then the test. Their scores come from the same-named file in that folder.
pub fn run(dir: &Path, gold: &[GoldPair], neural: Option<&Path>, fresh: Option<&Path>) -> Result<(), Box<dyn Error>> {
    let neural_path = neural.ok_or("pass the neural model's scores with --scores")?;
    let training = read_training(dir)?;
    let evidence = read_evidence(dir)?;
    let neural_scores = read_scores(neural_path)?;
    println!("Training pairs: {} ({} same).", training.len(), training.iter().filter(|t| t.same).count());

    let all: Vec<&Training> = training.iter().collect();
    let names_model = boost(&all, false);
    let boosted_model = boost(&all, true);

    let answered = |split: &str| -> Vec<&GoldPair> { gold.iter().filter(|p| p.split == split && p.truth().is_some()).collect() };
    let fresh_gold;
    let fresh_evidence;
    let fresh_scores;
    let (calibration, test, test_evidence, test_neural) = if let Some(folder) = fresh {
        fresh_gold = read_gold(folder)?;
        fresh_evidence = read_evidence(folder)?;
        fresh_scores = read_scores(&folder.join(neural_path.file_name().ok_or("--scores needs a file name")?))?;
        let answered_fresh: Vec<&GoldPair> = fresh_gold.iter().filter(|p| p.truth().is_some()).collect();
        println!("Second sheet: {} pairs answered, {} left unsure.", answered_fresh.len(), fresh_gold.len() - answered_fresh.len() - fresh_gold.iter().filter(|p| p.label.is_none()).count());
        (answered("calibration"), answered_fresh, &fresh_evidence, &fresh_scores)
    } else {
        (answered("calibration"), answered("test"), &evidence, &neural_scores)
    };
    if !enough(&calibration) || !enough(&test) {
        return Err("not enough answered pairs to compare systems".into());
    }
    let (calibration_scored, test_scored) = (
        score_pairs(&calibration, &evidence, &neural_scores, &names_model, &boosted_model)?,
        score_pairs(&test, test_evidence, test_neural, &names_model, &boosted_model)?,
    );
    let (calibration_cases, test_cases) = (cases(&calibration), cases(&test));

    // Out-of-fold predictions: each calibration pair is scored by a fit that never saw it.
    let truth: Vec<bool> = calibration_scored.iter().map(|s| s.pair.truth() == Some(true)).collect();
    let inputs: Vec<Vec<f64>> = calibration_scored.iter().map(Scored::stack_inputs).collect();
    let mut out_of_fold = vec![0.0; inputs.len()];
    for fold in 0..FOLDS {
        let in_fold = |i: usize| stable_hash(SEED, &calibration_scored[i].pair.id) % FOLDS == fold;
        let (train_x, train_y): (Vec<Vec<f64>>, Vec<bool>) = (0..inputs.len()).filter(|&i| !in_fold(i)).map(|i| (inputs[i].clone(), truth[i])).unzip();
        let beta = fit_logistic(&train_x, &train_y);
        for i in (0..inputs.len()).filter(|&i| in_fold(i)) {
            out_of_fold[i] = predict(&beta, &inputs[i]);
        }
    }
    let review_cut = best_threshold(&out_of_fold, &calibration_cases);
    let automatic = automatic_cut(&out_of_fold, &calibration_cases).map(|c| c.max(review_cut));

    let at = |scores: &[f64], cut: f64| -> Metrics {
        let predictions: Vec<bool> = scores.iter().map(|s| *s >= cut).collect();
        metrics(0..calibration_cases.len(), &calibration_cases, &predictions)
    };
    println!("\nOn calibration pairs, predicted by fits that never saw them:");
    let m = at(&out_of_fold, review_cut);
    println!("  review cut-off {review_cut:.3}: precision {:.3}, recall {:.3}, F1 {:.3}", m.precision, m.recall, m.f1);
    match automatic {
        Some(cut) => {
            let m = at(&out_of_fold, cut);
            let n = out_of_fold.iter().filter(|s| **s >= cut).count();
            println!("  automatic cut-off {cut:.3}: precision {:.3}, recall {:.3} ({n} pairs above)", m.precision, m.recall);
        }
        None => println!("  no cut-off reached {AUTOMATIC_PRECISION} precision with at least {MIN_AUTOMATIC_PAIRS} pairs above it"),
    }

    // The other systems' own cut-offs, from the calibration pairs.
    let cut_of = |score: &dyn Fn(&Scored) -> f64| best_threshold(&calibration_scored.iter().map(score).collect::<Vec<_>>(), &calibration_cases);
    let (neural_cut, boosted_cut, names_cut) = (cut_of(&|s| s.neural), cut_of(&|s| s.boosted), cut_of(&|s| s.names_only));

    // The final combination, fitted on every calibration answer.
    let beta = fit_logistic(&inputs, &truth);
    println!("  combination: neural {:+.2}, boosting {:+.2}, current rules {:+.2}, intercept {:+.2}", beta[0], beta[1], beta[2], beta[3]);
    let stacked: Vec<f64> = test_scored.iter().map(|s| predict(&beta, &s.stack_inputs())).collect();

    let before: Vec<bool> = test_scored.iter().map(|s| s.pair.rules_say_same()).collect();
    let after: Vec<bool> = test_scored.iter().map(|s| s.rules_say_same).collect();
    let names: Vec<bool> = test_scored.iter().map(|s| s.names_only >= names_cut).collect();
    let boosted: Vec<bool> = test_scored.iter().map(|s| s.boosted >= boosted_cut).collect();
    let neural_says: Vec<bool> = test_scored.iter().map(|s| s.neural >= neural_cut).collect();
    let combined: Vec<bool> = stacked.iter().map(|s| *s >= review_cut).collect();
    let systems: Vec<(&str, &[bool])> = vec![
        ("Rules before fixes", &before),
        ("Rules after fixes", &after),
        ("Boosting, names", &names),
        ("Boosting, + evidence", &boosted),
        ("Neural", &neural_says),
        ("Combined", &combined),
    ];
    let predictions: Vec<&[bool]> = systems.iter().map(|(_, p)| *p).collect();
    let (ranges, gains_before) = bootstrap(&test_cases, &predictions);
    let mut after_first: Vec<&[bool]> = vec![&after, &before];
    after_first.extend(predictions.iter().skip(2));
    let (_, gains_after) = bootstrap(&test_cases, &after_first);

    println!("\nTest pairs answered by a person: {}. Results are weighted to each rule's full size.", test.len());
    println!("{:<22}{:>11}{:>8}{:>6}{:>16}{:>22}{:>22}", "System", "Precision", "Recall", "F1", "F1 95% range", "Gain vs before", "Gain vs after");
    for (i, (name, prediction)) in systems.iter().enumerate() {
        let m = metrics(0..test_cases.len(), &test_cases, prediction);
        let gain = |g: (f64, f64), reference: bool| if reference { "-".to_string() } else { format!("{:+.3} to {:+.3}", g.0, g.1) };
        // `gains_after` lists the fixed rules first and the unfixed ones second; the later rows follow the table's order.
        let vs_after = match i {
            0 => format!("{:+.3} to {:+.3}", gains_after[1].0, gains_after[1].1),
            1 => "-".to_string(),
            _ => gain(gains_after[i], false),
        };
        println!(
            "{name:<22}{:>11.3}{:>8.3}{:>6.3}{:>16}{:>22}{vs_after:>22}",
            m.precision, m.recall, m.f1, format!("{:.3}-{:.3}", ranges[i].0, ranges[i].1), gain(gains_before[i], i == 0)
        );
    }

    println!("\nTwo cut-offs on the combined score, test pairs (counts are people's answers, not weighted):");
    println!("{:<14}{:>9}{:>8}{:>8}{:>11}{:>12}", "Band", "Cut-off", "Pairs", "Same", "Different", "Really same");
    let in_band = |low: f64, high: f64| -> Vec<(&Scored, f64)> { test_scored.iter().zip(&stacked).filter(|(_, s)| **s >= low && **s < high).map(|(t, s)| (t, *s)).collect() };
    let high = automatic.unwrap_or(f64::INFINITY);
    band_row("Automatic", automatic, &in_band(high, f64::INFINITY));
    band_row("Review", Some(review_cut), &in_band(review_cut, high));
    band_row("Different", None, &in_band(f64::NEG_INFINITY, review_cut));
    let automatic_pairs = in_band(high, f64::INFINITY);
    let automatic_right = automatic_pairs.iter().filter(|(s, _)| s.pair.truth() == Some(true)).count();
    let (low, up) = wilson(automatic_right, automatic_pairs.len());
    let total_same = test_scored.iter().filter(|s| s.pair.truth() == Some(true)).count();
    let missed = in_band(f64::NEG_INFINITY, review_cut).iter().filter(|(s, _)| s.pair.truth() == Some(true)).count();
    println!(
        "\nAutomatic band: {automatic_right} of {} really the same place (95% range {:.0}%-{:.0}%). Different band misses {missed} of {total_same} real matches.",
        automatic_pairs.len(), low * 100.0, up * 100.0
    );
    let rules_pairs: Vec<&Scored> = test_scored.iter().filter(|s| s.rules_say_same).collect();
    let rules_right = rules_pairs.iter().filter(|s| s.pair.truth() == Some(true)).count();
    let (rules_low, rules_up) = wilson(rules_right, rules_pairs.len());
    println!("For comparison, the fixed rules call {} pairs the same and {rules_right} are (95% range {:.0}%-{:.0}%).", rules_pairs.len(), rules_low * 100.0, rules_up * 100.0);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn solves_a_small_system() {
        let x = solve(vec![vec![2.0, 1.0], vec![1.0, 3.0]], vec![5.0, 10.0]);
        assert!((x[0] - 1.0).abs() < 1e-9 && (x[1] - 3.0).abs() < 1e-9);
    }

    #[test]
    fn logistic_regression_separates_a_clear_signal_and_ignores_noise() {
        let x: Vec<Vec<f64>> = (0..200).map(|i| vec![if i % 2 == 0 { 2.0 } else { -2.0 } + (i % 7) as f64 * 0.01, (i % 5) as f64]).collect();
        let y: Vec<bool> = (0..200).map(|i| i % 2 == 0).collect();
        let beta = fit_logistic(&x, &y);
        assert!(predict(&beta, &x[0]) > 0.9 && predict(&beta, &x[1]) < 0.1);
        assert!(beta[0].abs() > beta[1].abs() * 3.0);
    }

    #[test]
    fn wilson_ranges_widen_for_small_samples_and_hold_the_share() {
        let (small_low, small_up) = wilson(9, 10);
        let (big_low, big_up) = wilson(900, 1000);
        assert!(small_low < big_low && small_up > big_up);
        assert!(big_low < 0.9 && big_up > 0.9);
        assert_eq!(wilson(0, 0), (0.0, 1.0));
    }

    fn case(truth: bool) -> Case {
        Case { rule: "r".into(), truth, weight: 1.0 }
    }

    #[test]
    fn the_automatic_cut_takes_the_lowest_score_that_keeps_precision_and_needs_enough_pairs() {
        // 20 sure pairs above 0.9, then a mixed band. At 0.50, 22 of 24 are right (0.917); from 0.52, 22 of 23 (0.957).
        let mut scores: Vec<f64> = (0..20).map(|i| 0.9 + i as f64 * 0.001).collect();
        let mut cases: Vec<Case> = (0..20).map(|_| case(true)).collect();
        scores.extend([0.5, 0.52, 0.54, 0.56]);
        cases.extend([case(false), case(true), case(false), case(true)]);
        assert_eq!(automatic_cut(&scores, &cases), Some(0.52));
        // Too few pairs above any cut-off that is clean enough: no automatic cut-off.
        assert_eq!(automatic_cut(&scores[..5], &cases[..5]), None);
    }
}
