//! A second sheet to check by hand, for a test nobody has tuned against. The first 500 pairs were used to
//! design the rule fixes, so results on them flatter anything that leans on the rules. These pairs share no
//! record, mapped place or name with those, are all test pairs, and are held out of training by `build`.

use crate::labels::{field, read_rows};
use crate::{option, SEED};
use crate::sampling::{group_keys, pair_key, select_gold, Chosen, Pair, Split};
use crate::{database, output};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::error::Error;
use std::path::{Path, PathBuf};

/// A different seed from the first sheet, so the shuffle differs.
const FRESH_SEED: u64 = 20_261_106;
/// Where the rules are most likely to be wrong, and where a model would act, gets most pairs.
const QUOTAS: &[(&str, usize)] =
    &[("reviewed_different", 90), ("reviewed_same", 60), ("automatic_same", 50), ("never_different", 70), ("never_same", 50)];

/// Which sampling group a pair belongs to, by how it reached its decision and what the current rules decide.
/// Pairs that say nothing about the pair, and ASI monuments, are left out.
fn stratum(pair: &Pair, outcome: &str) -> Option<&'static str> {
    if pair.rule == "another_candidate_matched_better" || pair.rule == "wikidata" {
        return None;
    }
    let same = outcome == "same";
    Some(match (pair.rule.as_str(), pair.rule_label.as_str(), same) {
        ("name_distance", _, _) => "automatic_same",
        (_, "unknown", true) => "never_same",
        (_, "unknown", false) => "never_different",
        (_, _, true) => "reviewed_same",
        (_, _, false) => "reviewed_different",
    })
}

pub fn run(args: &[String], data: &Path) -> Result<(), Box<dyn Error>> {
    let env_file = option(args, "--env").unwrap_or_else(|| PathBuf::from("../../.env"));
    let out_dir = data.join("fresh");
    let sheet = out_dir.join("gold_sheet.csv");
    if sheet.exists() && !args.iter().any(|a| a == "--overwrite") {
        return Err(format!("{} already exists; pass --overwrite only if nothing has been checked in it", sheet.display()).into());
    }
    std::fs::create_dir_all(&out_dir)?;

    let mut client = database::connect(&env_file)?;
    let mut pairs = database::decided_pairs(&mut client)?;
    // Same seed and size as `pairs`, so every pair has a decision in pairs_decided.csv.
    let (never, estimate) = database::never_compared_pairs(&mut client, SEED, 12_000)?;
    let never_pairs = never.len().max(1);
    pairs.extend(never);

    let decisions: HashMap<String, String> = read_rows(&data.join("pairs_decided.csv"))?
        .iter()
        .map(|r| Ok((format!("{}|{}", field(r, "record_id")?, field(r, "poi_id")?), field(r, "fixed_outcome")?.to_string())))
        .collect::<Result<_, String>>()?;

    // Nothing sharing a record, mapped place or name with a pair already checked.
    let checked: HashSet<String> = read_rows(&data.join("gold_key.csv"))?
        .iter()
        .map(|r| Ok(format!("{}|{}", field(r, "record_id")?, field(r, "poi_id")?)))
        .collect::<Result<_, String>>()?;
    let held_out: HashSet<String> = pairs.iter().filter(|p| checked.contains(&pair_key(p))).flat_map(group_keys).collect();
    println!("Already-checked pairs found: {} of {}.", pairs.iter().filter(|p| checked.contains(&pair_key(p))).count(), checked.len());

    let mut eligible = Vec::new();
    let mut without_decision = 0;
    for mut pair in pairs {
        let Some(outcome) = decisions.get(&pair_key(&pair)) else {
            without_decision += 1;
            continue;
        };
        let Some(group) = stratum(&pair, outcome) else { continue };
        if group_keys(&pair).iter().any(|k| held_out.contains(k)) {
            continue;
        }
        pair.rule = group.to_string();
        eligible.push(pair);
    }
    if without_decision > 0 {
        println!("{without_decision} pairs have no decision in pairs_decided.csv (the data changed since `pairs`) and were skipped.");
    }

    // Each sampled never-compared pair stands for many unsampled ones.
    let scale = estimate as f64 / never_pairs as f64;
    let mut populations: BTreeMap<String, usize> = BTreeMap::new();
    for pair in &eligible {
        *populations.entry(pair.rule.clone()).or_insert(0) += 1;
    }
    for (group, count) in populations.iter_mut() {
        if group.starts_with("never_") {
            *count = (*count as f64 * scale).round() as usize;
        }
    }

    let chosen: Vec<Chosen> = select_gold(&eligible, QUOTAS, FRESH_SEED).into_iter().map(|c| Chosen { index: c.index, split: Split::Test }).collect();
    output::write_gold(&out_dir, &eligible, &chosen, FRESH_SEED, &populations, "F")?;

    println!("\n{:<22}{:>12}{:>8}", "Group", "Pairs it is", "Check");
    for (group, quota) in QUOTAS {
        let picked = chosen.iter().filter(|c| eligible[c.index].rule == *group).count();
        println!("{group:<22}{:>12}{picked:>8}  (asked for {quota})", populations.get(*group).copied().unwrap_or(0));
    }
    println!("\nTo check: {}. Open {} to start. Do not open gold_key.csv until checking is finished.", chosen.len(), sheet.display());
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pair(rule: &str, label: &str) -> Pair {
        Pair { rule: rule.into(), rule_label: label.into(), ..Pair::default() }
    }

    #[test]
    fn pairs_fall_into_the_group_for_how_they_were_decided_and_what_the_rules_say_now() {
        assert_eq!(stratum(&pair("names_differ", "different"), "same"), Some("reviewed_same"));
        assert_eq!(stratum(&pair("shared_phone", "same"), "different"), Some("reviewed_different"));
        assert_eq!(stratum(&pair("never_compared", "unknown"), "same"), Some("never_same"));
        assert_eq!(stratum(&pair("never_compared", "unknown"), "different"), Some("never_different"));
        assert_eq!(stratum(&pair("name_distance", "same"), "different"), Some("automatic_same"));
    }

    #[test]
    fn pairs_that_say_nothing_about_the_pair_and_monuments_are_left_out() {
        assert_eq!(stratum(&pair("another_candidate_matched_better", "different"), "same"), None);
        assert_eq!(stratum(&pair("wikidata", "same"), "same"), None);
    }

    #[test]
    fn every_quota_names_a_real_group() {
        let groups = ["reviewed_different", "reviewed_same", "automatic_same", "never_different", "never_same"];
        assert!(QUOTAS.iter().all(|(g, _)| groups.contains(g)));
        assert_eq!(QUOTAS.iter().map(|(_, n)| n).sum::<usize>(), 320);
    }
}
