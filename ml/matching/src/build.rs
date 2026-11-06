//! Builds the second training set. Every pair the database holds is re-decided by the current rules
//! (see `redecide/`), pairs no rule ever compared are added with the decision the rules would give them,
//! and each rule's pairs are weighted by how often the rule agreed with the person on the calibration
//! pairs. Test answers never reach this file.

use crate::labels::{field, read_gold, read_rows, GoldPair};
use crate::sampling::{group_keys, Pair};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::error::Error;
use std::path::Path;

/// Settled on import by name and distance, or a shared Wikidata id, so the rules never decided them.
const AUTOMATIC: &[&str] = &["name_distance", "wikidata"];
/// This rule says only that a better candidate existed, not that the pair is different.
const NOT_EVIDENCE: &str = "another_candidate_matched_better";
/// A rule nobody has checked is trusted a little less than one that agreed 100%.
const PRIOR_AGREEMENT: f64 = 0.85;
const PRIOR_STRENGTH: f64 = 4.0;
const LOWEST_WEIGHT: f64 = 0.2;

/// Evidence the rules look at, as 0 or 1, in this order.
pub const FLAGS: &[&str] =
    &["phones_shared", "phones_conflict", "websites_shared", "websites_conflict", "chain_either", "aggregated_either", "brand_same"];

#[derive(Debug, Clone, Default)]
pub struct Row {
    pub record_id: String,
    pub poi_id: String,
    pub database_rule: String,
    /// "same", "different" or "unknown" (never compared), as the database holds it.
    pub database_label: String,
    /// What the current rules decide, "same" or "different".
    pub fixed_outcome: String,
    pub fixed_rule: String,
    pub flags: Vec<f64>,
    pub name_a: String,
    pub name_a_normalized: String,
    pub category_a: String,
    pub source_a: String,
    pub name_b: String,
    pub name_b_normalized: String,
    pub category_b: String,
    pub distance_m: f64,
}

impl Row {
    fn key(&self) -> String {
        format!("{}|{}", self.record_id, self.poi_id)
    }

    fn group_keys(&self) -> Vec<String> {
        group_keys(&Pair {
            record_id: self.record_id.clone(),
            poi_id: self.poi_id.clone(),
            name_a_normalized: self.name_a_normalized.clone(),
            name_b_normalized: self.name_b_normalized.clone(),
            ..Pair::default()
        })
    }

    /// The rule and decision a pair trains with, or none when it says nothing about the pair.
    pub fn training_view(&self) -> Option<(String, bool)> {
        if self.database_rule == NOT_EVIDENCE {
            return None;
        }
        if AUTOMATIC.contains(&self.database_rule.as_str()) {
            return Some((self.database_rule.clone(), self.database_label == "same"));
        }
        if self.fixed_outcome.is_empty() {
            return None;
        }
        let rule = if self.database_label == "unknown" { format!("never:{}", self.fixed_rule) } else { self.fixed_rule.clone() };
        Some((rule, self.fixed_outcome == "same"))
    }

    /// What the rules as they are now say about this pair, for judging them against a person's answer.
    /// A pair that lost to a better candidate keeps the database's answer, as it is not a decision about the pair.
    pub fn fixed_label(&self) -> &str {
        if AUTOMATIC.contains(&self.database_rule.as_str()) || self.database_rule == NOT_EVIDENCE {
            &self.database_label
        } else {
            &self.fixed_outcome
        }
    }
}

fn number(row: &HashMap<String, String>, name: &str) -> Result<f64, Box<dyn Error>> {
    Ok(field(row, name)?.parse().map_err(|_| format!("bad {name}"))?)
}

fn read_all(dir: &Path) -> Result<Vec<Row>, Box<dyn Error>> {
    let decided: HashMap<String, HashMap<String, String>> = read_rows(&dir.join("pairs_decided.csv"))?
        .into_iter()
        .map(|row| (format!("{}|{}", row["record_id"], row["poi_id"]), row))
        .collect();
    let mut rows = Vec::new();
    let mut missing = 0;
    for full in read_rows(&dir.join("pairs_full.csv"))? {
        let key = format!("{}|{}", field(&full, "record_id")?, field(&full, "poi_id")?);
        let Some(d) = decided.get(&key) else {
            missing += 1;
            continue;
        };
        rows.push(Row {
            record_id: field(&full, "record_id")?.into(),
            poi_id: field(&full, "poi_id")?.into(),
            database_rule: field(&full, "rule")?.into(),
            database_label: field(&full, "rule_label")?.into(),
            fixed_outcome: field(d, "fixed_outcome")?.to_lowercase(),
            fixed_rule: field(d, "fixed_rule")?.into(),
            flags: FLAGS.iter().map(|f| number(d, f)).collect::<Result<_, _>>()?,
            name_a: field(&full, "name_a")?.into(),
            name_a_normalized: field(&full, "name_a_normalized")?.into(),
            category_a: field(&full, "category_a")?.into(),
            source_a: field(&full, "source_a")?.into(),
            name_b: field(&full, "name_b")?.into(),
            name_b_normalized: field(&full, "name_b_normalized")?.into(),
            category_b: field(&full, "category_b")?.into(),
            distance_m: number(&full, "distance_m")?,
        });
    }
    if missing > 0 {
        return Err(format!("{missing} pairs in pairs_full.csv have no decision in pairs_decided.csv; run the redecide program again").into());
    }
    Ok(rows)
}

/// A rule's weight from how often it agreed with the person, pulled towards the prior when few were checked.
pub fn weight(agreed: f64, checked: f64) -> f64 {
    ((agreed + PRIOR_AGREEMENT * PRIOR_STRENGTH) / (checked + PRIOR_STRENGTH)).clamp(LOWEST_WEIGHT, 1.0)
}

/// Agreement per training rule, from calibration answers only.
fn agreement(gold: &[GoldPair], keys: &HashMap<String, &Row>, identity: &HashMap<String, String>) -> BTreeMap<String, (f64, f64)> {
    let mut out: BTreeMap<String, (f64, f64)> = BTreeMap::new();
    for pair in gold.iter().filter(|p| p.split == "calibration") {
        let (Some(truth), Some(key)) = (pair.truth(), identity.get(&pair.id)) else { continue };
        let Some((rule, says_same)) = keys.get(key).and_then(|row| row.training_view()) else { continue };
        let entry = out.entry(rule).or_default();
        entry.1 += 1.0;
        entry.0 += f64::from(u8::from(truth == says_same));
    }
    out
}

/// Evidence and the current rules' answer for each checked pair, which the comparisons read.
fn write_evidence(path: &Path, gold: &[GoldPair], identity: &HashMap<String, String>, by_key: &HashMap<String, &Row>) -> Result<(), Box<dyn Error>> {
    let mut evidence = csv::Writer::from_path(path)?;
    let mut header = vec!["pair_id", "found", "training_rule", "fixed_label"];
    header.extend(FLAGS);
    evidence.write_record(&header)?;
    for pair in gold {
        let row = identity.get(&pair.id).and_then(|k| by_key.get(k));
        let mut record = vec![pair.id.clone(), u8::from(row.is_some()).to_string()];
        record.push(row.and_then(|r| r.training_view()).map(|(rule, _)| rule).unwrap_or_default());
        record.push(row.map(|r| r.fixed_label().to_string()).unwrap_or_default());
        for i in 0..FLAGS.len() {
            record.push(row.map(|r| r.flags[i].to_string()).unwrap_or_default());
        }
        evidence.write_record(&record)?;
    }
    evidence.flush()?;
    Ok(())
}

pub fn run(dir: &Path, hold_out: Option<&Path>, out_name: &str) -> Result<(), Box<dyn Error>> {
    let rows = read_all(dir)?;
    let gold = read_gold(dir)?;
    // The key file holds each checked pair's record and place, which the gold structure does not carry.
    let identity: HashMap<String, String> = read_rows(&dir.join("gold_key.csv"))?
        .iter()
        .map(|r| Ok((field(r, "pair_id")?.to_string(), format!("{}|{}", field(r, "record_id")?, field(r, "poi_id")?))))
        .collect::<Result<_, String>>()?;
    let by_key: HashMap<String, &Row> = rows.iter().map(|r| (r.key(), r)).collect();

    let found = identity.values().filter(|k| by_key.contains_key(*k)).count();
    println!("Checked pairs found in the database export: {found} of {}.", identity.len());

    // Nothing a person will check, or that shares a record, place or name with one, may train. That includes a
    // second sheet when one has been exported, so it stays a clean test.
    let mut held_out: HashSet<String> = HashSet::new();
    let mut sheets = vec![dir.to_path_buf()];
    sheets.extend(hold_out.map(Path::to_path_buf));
    for sheet in &sheets {
        for r in read_rows(&sheet.join("gold_key.csv"))? {
            let key = format!("{}|{}", field(&r, "record_id")?, field(&r, "poi_id")?);
            match by_key.get(&key) {
                Some(row) => held_out.extend(row.group_keys()),
                None => {
                    let plain = |name: &str| name.to_lowercase().chars().filter(|c| c.is_alphanumeric()).collect::<String>();
                    held_out.extend(
                        Row {
                            record_id: field(&r, "record_id")?.into(),
                            poi_id: field(&r, "poi_id")?.into(),
                            name_a_normalized: plain(field(&r, "name_a")?),
                            name_b_normalized: plain(field(&r, "name_b")?),
                            ..Row::default()
                        }
                        .group_keys(),
                    );
                }
            }
        }
    }

    let rule_agreement = agreement(&gold, &by_key, &identity);
    let weight_of = |rule: &str| {
        let (agreed, checked) = rule_agreement.get(rule).copied().unwrap_or((0.0, 0.0));
        weight(agreed, checked)
    };

    write_evidence(&dir.join("gold_evidence.csv"), &gold, &identity, &by_key)?;
    if let Some(sheet) = hold_out {
        let second = read_gold(sheet)?;
        let second_identity: HashMap<String, String> = read_rows(&sheet.join("gold_key.csv"))?
            .iter()
            .map(|r| Ok((field(r, "pair_id")?.to_string(), format!("{}|{}", field(r, "record_id")?, field(r, "poi_id")?))))
            .collect::<Result<_, String>>()?;
        write_evidence(&sheet.join("gold_evidence.csv"), &second, &second_identity, &by_key)?;
        let found = second_identity.values().filter(|k| by_key.contains_key(*k)).count();
        println!("Second sheet's pairs found in the export: {found} of {}.", second_identity.len());
    }

    let mut out = csv::Writer::from_path(dir.join(out_name))?;
    let mut header = vec![
        "record_id", "poi_id", "rule", "rule_label", "name_a", "category_a", "source_a", "name_b", "category_b", "distance_m", "weight",
    ];
    header.extend(FLAGS);
    out.write_record(&header)?;
    let mut counts: BTreeMap<String, (usize, usize)> = BTreeMap::new();
    for row in &rows {
        let Some((rule, same)) = row.training_view() else { continue };
        if row.group_keys().iter().any(|k| held_out.contains(k)) {
            continue;
        }
        let mut record = vec![
            row.record_id.clone(), row.poi_id.clone(), rule.clone(), if same { "same" } else { "different" }.into(), row.name_a.clone(),
            row.category_a.clone(), row.source_a.clone(), row.name_b.clone(), row.category_b.clone(),
            format!("{}", row.distance_m.round() as i64), format!("{:.3}", weight_of(&rule)),
        ];
        record.extend(row.flags.iter().map(f64::to_string));
        out.write_record(&record)?;
        let entry = counts.entry(rule).or_default();
        if same {
            entry.0 += 1;
        } else {
            entry.1 += 1;
        }
    }
    out.flush()?;

    println!("\n{:<44}{:>8}{:>10}{:>9}{:>10}{:>8}", "Training rule", "Same", "Different", "Checked", "Agreed", "Weight");
    for (rule, (same, different)) in &counts {
        let (agreed, checked) = rule_agreement.get(rule).copied().unwrap_or((0.0, 0.0));
        println!("{rule:<44}{same:>8}{different:>10}{checked:>9.0}{agreed:>10.0}{:>8.2}", weight_of(rule));
    }
    let same: usize = counts.values().map(|c| c.0).sum();
    let different: usize = counts.values().map(|c| c.1).sum();
    let never: usize = counts.iter().filter(|(r, _)| r.starts_with("never:")).map(|(_, c)| c.0 + c.1).sum();
    println!("\nTraining pairs: {} ({same} same, {different} different), of which {never} were never compared before.", same + different);
    println!("Wrote {} and {}.", dir.join(out_name).display(), dir.join("gold_evidence.csv").display());
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn row(rule: &str, label: &str, outcome: &str, fixed_rule: &str) -> Row {
        Row { database_rule: rule.into(), database_label: label.into(), fixed_outcome: outcome.into(), fixed_rule: fixed_rule.into(), ..Row::default() }
    }

    #[test]
    fn a_reviewed_pair_trains_with_what_the_current_rules_decide() {
        let r = row("names_differ", "different", "same", "name_words_match");
        assert_eq!(r.training_view(), Some(("name_words_match".to_string(), true)));
    }

    #[test]
    fn a_never_compared_pair_trains_under_its_own_name_and_a_lost_pair_does_not_train() {
        assert_eq!(row("never_compared", "unknown", "different", "names_differ").training_view(), Some(("never:names_differ".into(), false)));
        assert_eq!(row(NOT_EVIDENCE, "different", "same", "shared_phone").training_view(), None);
    }

    #[test]
    fn pairs_settled_on_import_keep_their_answer() {
        let r = row("name_distance", "same", "different", "names_differ");
        assert_eq!(r.training_view(), Some(("name_distance".to_string(), true)));
        assert_eq!(r.fixed_label(), "same");
        assert_eq!(row(NOT_EVIDENCE, "different", "same", "x").fixed_label(), "different");
        assert_eq!(row("names_differ", "different", "same", "x").fixed_label(), "same");
    }

    #[test]
    fn weights_fall_with_disagreement_and_stay_near_the_prior_when_unchecked() {
        assert!((weight(0.0, 0.0) - 0.85).abs() < 1e-12);
        assert!(weight(2.0, 20.0) < weight(18.0, 20.0));
        assert_eq!(weight(0.0, 400.0), LOWEST_WEIGHT);
        assert!(weight(400.0, 400.0) <= 1.0);
    }
}
