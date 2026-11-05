//! How often each rule agrees with a person, and what that means across everything the rule decided.

use crate::labels::GoldPair;
use std::collections::BTreeMap;
use std::error::Error;
use std::path::Path;

#[derive(Debug, Clone, Default, PartialEq)]
pub struct RuleScore {
    pub says_same: bool,
    pub population: usize,
    pub agree: usize,
    pub disagree: usize,
    pub unsure: usize,
}

impl RuleScore {
    fn decided(&self) -> usize {
        self.agree + self.disagree
    }

    fn agreement(&self) -> Option<f64> {
        (self.decided() > 0).then(|| self.agree as f64 / self.decided() as f64)
    }

    /// Estimated decisions by this rule across all its pairs that a person would overturn.
    fn estimated_errors(&self) -> f64 {
        self.agreement().map_or(0.0, |agreement| (1.0 - agreement) * self.population as f64)
    }
}

pub fn by_rule(gold: &[GoldPair]) -> BTreeMap<String, RuleScore> {
    let mut rules: BTreeMap<String, RuleScore> = BTreeMap::new();
    for pair in gold {
        let score = rules.entry(pair.rule.clone()).or_default();
        score.says_same = pair.rules_say_same();
        score.population = pair.rule_population;
        match (pair.truth(), pair.label) {
            (Some(truth), _) if truth == pair.rules_say_same() => score.agree += 1,
            (Some(_), _) => score.disagree += 1,
            (None, Some(_)) => score.unsure += 1,
            (None, None) => {}
        }
    }
    rules
}

/// 95% Wilson interval: unlike the usual formula it stays sensible with small samples and with
/// results near 0% or 100%, which is most of what a rule check produces.
pub fn wilson(successes: usize, total: usize) -> Option<(f64, f64)> {
    if total == 0 {
        return None;
    }
    const Z: f64 = 1.96;
    let n = total as f64;
    let p = successes as f64 / n;
    let denominator = 1.0 + Z * Z / n;
    let centre = (p + Z * Z / (2.0 * n)) / denominator;
    let margin = Z * (p * (1.0 - p) / n + Z * Z / (4.0 * n * n)).sqrt() / denominator;
    Some(((centre - margin).max(0.0), (centre + margin).min(1.0)))
}

/// Precision of one side of the rules, each rule counted by how many pairs it decided.
pub fn weighted_agreement(rules: &BTreeMap<String, RuleScore>, says_same: bool) -> Option<f64> {
    let scored = || rules.values().filter(|r| r.says_same == says_same && r.decided() > 0);
    let population: usize = scored().map(|r| r.population).sum();
    (population > 0).then(|| scored().map(|r| r.agreement().unwrap_or(0.0) * r.population as f64).sum::<f64>() / population as f64)
}

pub fn run(dir: &Path, gold: &[GoldPair]) -> Result<(), Box<dyn Error>> {
    let rules = by_rule(gold);
    let unsure: usize = rules.values().map(|r| r.unsure).sum();
    let answered = gold.iter().filter(|p| p.truth().is_some()).count();
    println!("Checked {} of {} pairs: {answered} answered, {unsure} unsure.\n", answered + unsure, gold.len());
    if answered == 0 {
        println!("Nothing to score yet. Fill in the label column of gold_sheet.csv first.");
        return Ok(());
    }

    println!("{:<36}{:>8}{:>10}{:>7}{:>7}{:>11}{:>14}{:>9}", "Rule", "Says", "Decided", "Agree", "Differ", "Agreement", "95% range", "Errors");
    for (rule, score) in &rules {
        let agreement = score.agreement().map_or("-".to_string(), |a| format!("{:.0}%", a * 100.0));
        let range = wilson(score.agree, score.decided()).map_or("-".to_string(), |(low, high)| format!("{:.0}-{:.0}%", low * 100.0, high * 100.0));
        let says = if score.says_same { "same" } else { "apart" };
        println!(
            "{rule:<36}{says:>8}{:>10}{:>7}{:>7}{agreement:>11}{range:>14}{:>9.0}",
            score.population, score.agree, score.disagree, score.estimated_errors()
        );
    }
    println!("\nErrors estimates how many of the rule's decisions a person would overturn.");
    if let Some(agreement) = weighted_agreement(&rules, true) {
        let wrong: f64 = rules.values().filter(|r| r.says_same).map(RuleScore::estimated_errors).sum();
        println!("Merges: {:.1}% look right, so about {wrong:.0} wrong merges.", agreement * 100.0);
    }
    if let Some(agreement) = weighted_agreement(&rules, false) {
        let wrong: f64 = rules.values().filter(|r| !r.says_same).map(RuleScore::estimated_errors).sum();
        println!("Kept apart: {:.1}% look right, so about {wrong:.0} missed matches.", agreement * 100.0);
    }
    if answered < 300 {
        println!("\nWith {answered} answers, these are early signs. Most rules have too few checks to judge alone.");
    }

    let path = dir.join("disagreements.csv");
    let count = write_disagreements(&path, gold)?;
    println!("\n{count} pairs where you and the rules disagree are in {}.", path.display());
    Ok(())
}

fn write_disagreements(path: &Path, gold: &[GoldPair]) -> Result<usize, Box<dyn Error>> {
    let mut out = csv::Writer::from_path(path)?;
    out.write_record(["pair_id", "rule", "rules_say", "you_say", "name_a", "category_a", "name_b", "category_b", "distance_m", "note"])?;
    let mut count = 0;
    for pair in gold {
        if pair.truth().is_some_and(|truth| truth != pair.rules_say_same()) {
            let (rules, you) = if pair.rules_say_same() { ("same", "different") } else { ("different", "same") };
            out.write_record([
                pair.id.as_str(), &pair.rule, rules, you, &pair.name_a, &pair.category_a, &pair.name_b, &pair.category_b,
                &pair.distance_m.to_string(), &pair.note,
            ])?;
            count += 1;
        }
    }
    out.flush()?;
    Ok(count)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::labels::Label;

    fn pair(rule: &str, rule_label: &str, population: usize, label: Option<Label>) -> GoldPair {
        GoldPair { rule: rule.into(), rule_label: rule_label.into(), rule_population: population, label, ..GoldPair::default() }
    }

    #[test]
    fn counts_agreement_and_leaves_out_unsure_and_unchecked() {
        let gold = [
            pair("r", "same", 100, Some(Label::Same)),
            pair("r", "same", 100, Some(Label::Same)),
            pair("r", "same", 100, Some(Label::Same)),
            pair("r", "same", 100, Some(Label::Different)),
            pair("r", "same", 100, Some(Label::Unsure)),
            pair("r", "same", 100, None),
        ];
        let score = &by_rule(&gold)["r"];
        assert_eq!((score.agree, score.disagree, score.unsure), (3, 1, 1));
        assert_eq!(score.agreement(), Some(0.75));
        assert_eq!(score.estimated_errors(), 25.0);
    }

    #[test]
    fn a_pair_no_rule_compared_that_is_the_same_place_is_a_missed_match() {
        let gold = [pair("never_compared", "unknown", 1000, Some(Label::Same)), pair("never_compared", "unknown", 1000, Some(Label::Different))];
        let rules = by_rule(&gold);
        assert!(!rules["never_compared"].says_same);
        assert_eq!(rules["never_compared"].estimated_errors(), 500.0);
    }

    #[test]
    fn overall_agreement_counts_each_rule_by_how_much_it_decided() {
        let gold = [pair("big", "same", 900, Some(Label::Same)), pair("small", "same", 100, Some(Label::Different))];
        assert_eq!(weighted_agreement(&by_rule(&gold), true), Some(0.9));
        assert_eq!(weighted_agreement(&by_rule(&gold), false), None);
    }

    #[test]
    fn wilson_stays_honest_at_the_extremes() {
        let (low, high) = wilson(30, 30).unwrap();
        assert!(low > 0.88 && low < 0.9, "30 out of 30 still leaves doubt: {low}");
        assert_eq!(high, 1.0);
        let (low, high) = wilson(50, 100).unwrap();
        assert!((low - 0.404).abs() < 0.001 && (high - 0.596).abs() < 0.001);
        assert_eq!(wilson(0, 0), None);
    }
}
