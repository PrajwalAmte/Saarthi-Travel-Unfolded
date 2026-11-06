//! Chooses the pairs a person checks and keeps them from leaking into training.
//! Pure logic with no database, so the guarantees can be tested.

use std::collections::{BTreeMap, HashSet};

/// One candidate pair: a source record (side A) and an existing mapped place (side B).
#[derive(Debug, Clone, Default)]
pub struct Pair {
    pub record_id: String,
    pub poi_id: String,
    /// The rule that decided the pair, which is also its sampling group.
    pub rule: String,
    /// "same", "different", or "unknown" for pairs no rule has ever looked at.
    pub rule_label: String,
    pub name_a: String,
    pub name_a_normalized: String,
    pub category_a: String,
    pub source_a: String,
    pub address_a: String,
    pub phone_a: String,
    pub website_a: String,
    pub brand_a: String,
    pub group_a: String,
    pub group_b: String,
    pub brands_b: String,
    pub lat_a: f64,
    pub lon_a: f64,
    pub name_b: String,
    pub name_b_normalized: String,
    pub category_b: String,
    pub sources_b: String,
    pub address_b: String,
    pub phones_b: String,
    pub websites_b: String,
    pub lat_b: f64,
    pub lon_b: f64,
    pub distance_m: f64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Split {
    Test,
    Calibration,
}

impl Split {
    pub fn as_str(self) -> &'static str {
        match self {
            Split::Test => "test",
            Split::Calibration => "calibration",
        }
    }
}

/// A pair chosen for hand-checking, with its position in the rows it was chosen from.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Chosen {
    pub index: usize,
    pub split: Split,
}

/// How many pairs to check per rule, 500 in all. Rules whose mistakes would matter most, or that
/// rest on the weakest evidence, get more. `wikidata` is left out: those pairs are ASI monuments.
pub const QUOTAS: &[(&str, usize)] = &[
    ("same_name_same_spot", 30),
    ("name_contains_other", 40),
    ("spelling_variant", 35),
    ("same_name_nearby", 35),
    ("name_words_match", 40),
    ("shared_phone", 25),
    ("shared_website", 25),
    ("different_kind_of_place", 35),
    ("too_far_for_weak_name_match", 35),
    ("chain_branch", 25),
    ("names_differ", 30),
    ("different_phones", 30),
    ("another_candidate_matched_better", 10),
    ("name_distance", 50),
    ("never_compared", 55),
];

/// This rule says only that a better candidate existed, not that this pair is different.
const NOT_EVIDENCE: &str = "another_candidate_matched_better";

/// FNV-1a: stable across runs, machines and Rust versions, unlike the standard library's hasher.
pub fn stable_hash(seed: u64, text: &str) -> u64 {
    let mut hash = 0xcbf2_9ce4_8422_2325u64 ^ seed;
    for byte in text.as_bytes() {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    hash
}

pub fn pair_key(pair: &Pair) -> String {
    format!("{}|{}", pair.record_id, pair.poi_id)
}

/// A stable shuffle position, so the same seed always gives the same order.
pub fn shuffle_position(seed: u64, pair: &Pair) -> u64 {
    stable_hash(seed, &pair_key(pair))
}

/// Picks up to each rule's quota. No record, mapped place or name appears twice, so one chain or
/// place cannot fill a group or sit on both sides of the test and calibration split. Rules with
/// the fewest pairs choose first, so a large group cannot use up a small group's names.
pub fn select_gold(pairs: &[Pair], quotas: &[(&str, usize)], seed: u64) -> Vec<Chosen> {
    let mut by_rule: BTreeMap<&str, Vec<usize>> = BTreeMap::new();
    for (index, pair) in pairs.iter().enumerate() {
        by_rule.entry(pair.rule.as_str()).or_default().push(index);
    }
    let mut order: Vec<(&str, usize)> = quotas.to_vec();
    order.sort_by_key(|(rule, _)| (by_rule.get(rule).map_or(0, Vec::len), *rule));

    let mut used = HashSet::new();
    let mut chosen = Vec::new();
    for (rule, quota) in order {
        let Some(indexes) = by_rule.get_mut(rule) else { continue };
        indexes.sort_by_key(|&i| (shuffle_position(seed, &pairs[i]), i));
        let mut taken = 0;
        for &index in indexes.iter() {
            if taken == quota {
                break;
            }
            let pair = &pairs[index];
            let keys = group_keys(pair);
            if keys.iter().any(|key| used.contains(key)) {
                continue;
            }
            used.extend(keys);
            let split = if taken % 2 == 0 { Split::Test } else { Split::Calibration };
            chosen.push(Chosen { index, split });
            taken += 1;
        }
    }
    chosen
}

/// What ties two pairs together: sharing any of these would let a model recognise a test pair.
pub fn group_keys(pair: &Pair) -> Vec<String> {
    let mut keys = vec![format!("record:{}", pair.record_id), format!("poi:{}", pair.poi_id)];
    for name in [&pair.name_a_normalized, &pair.name_b_normalized] {
        if !name.is_empty() {
            keys.push(format!("name:{name}"));
        }
    }
    keys
}

/// The pairs a model may train on: decided by a rule that says something about the pair, and
/// sharing no record, mapped place or name with anything a person will check.
pub fn training_indexes(pairs: &[Pair], chosen: &[Chosen]) -> Vec<usize> {
    let held_out: HashSet<String> = chosen.iter().flat_map(|c| group_keys(&pairs[c.index])).collect();
    pairs
        .iter()
        .enumerate()
        .filter(|(_, pair)| pair.rule_label != "unknown" && pair.rule != NOT_EVIDENCE)
        .filter(|(_, pair)| !group_keys(pair).iter().any(|key| held_out.contains(key)))
        .map(|(index, _)| index)
        .collect()
}

/// How many pairs each rule decided, so results can be weighted back to the full population.
pub fn population(pairs: &[Pair]) -> BTreeMap<String, usize> {
    let mut counts = BTreeMap::new();
    for pair in pairs {
        *counts.entry(pair.rule.clone()).or_insert(0) += 1;
    }
    counts
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pair(id: usize, rule: &str, name: &str) -> Pair {
        Pair {
            record_id: format!("r{id}"),
            poi_id: format!("p{id}"),
            rule: rule.to_string(),
            rule_label: "same".to_string(),
            name_a_normalized: name.to_string(),
            name_b_normalized: name.to_string(),
            ..Pair::default()
        }
    }

    fn many(rule: &str, count: usize) -> Vec<Pair> {
        (0..count).map(|i| pair(i, rule, &format!("{rule} {i}"))).collect()
    }

    #[test]
    fn honours_quotas_and_splits_each_rule_evenly() {
        let pairs = many("spelling_variant", 100);
        let chosen = select_gold(&pairs, &[("spelling_variant", 10)], 7);
        assert_eq!(chosen.len(), 10);
        assert_eq!(chosen.iter().filter(|c| c.split == Split::Test).count(), 5);
    }

    #[test]
    fn the_same_seed_gives_the_same_choice_whatever_the_row_order() {
        let pairs = many("spelling_variant", 100);
        let mut reversed = pairs.clone();
        reversed.reverse();
        let names = |rows: &[Pair], chosen: Vec<Chosen>| {
            let mut out: Vec<String> = chosen.iter().map(|c| rows[c.index].record_id.clone()).collect();
            out.sort();
            out
        };
        let first = names(&pairs, select_gold(&pairs, &[("spelling_variant", 10)], 7));
        let second = names(&reversed, select_gold(&reversed, &[("spelling_variant", 10)], 7));
        let other = names(&pairs, select_gold(&pairs, &[("spelling_variant", 10)], 8));
        assert_eq!(first, second);
        assert_ne!(first, other);
    }

    #[test]
    fn one_chain_cannot_fill_a_group() {
        let mut pairs: Vec<Pair> = (0..50).map(|i| pair(i, "chain_branch", "kfc")).collect();
        pairs.extend((50..60).map(|i| pair(i, "chain_branch", &format!("cafe {i}"))));
        let chosen = select_gold(&pairs, &[("chain_branch", 5)], 7);
        let kfc = chosen.iter().filter(|c| pairs[c.index].name_a_normalized == "kfc").count();
        assert!(kfc <= 1);
    }

    #[test]
    fn no_record_place_or_name_is_chosen_twice() {
        let mut pairs = many("same_name_nearby", 20);
        for p in &mut pairs {
            p.poi_id = "shared".to_string();
        }
        pairs.extend(many("names_differ", 20));
        let chosen = select_gold(&pairs, &[("same_name_nearby", 5), ("names_differ", 5)], 7);
        assert_eq!(chosen.iter().filter(|c| pairs[c.index].rule == "same_name_nearby").count(), 1, "they all share one place");
        assert_eq!(chosen.iter().filter(|c| pairs[c.index].rule == "names_differ").count(), 5);
    }

    #[test]
    fn a_small_rule_is_filled_before_a_large_one_can_take_its_names() {
        let mut pairs: Vec<Pair> = (0..200).map(|i| pair(i, "large", &format!("n{}", i % 3))).collect();
        pairs.extend((200..203).map(|i| pair(i, "small", &format!("n{}", i % 3))));
        let chosen = select_gold(&pairs, &[("large", 3), ("small", 3)], 7);
        assert_eq!(chosen.iter().filter(|c| pairs[c.index].rule == "small").count(), 3);
    }

    #[test]
    fn training_shares_nothing_with_the_checked_pairs() {
        let mut pairs = many("spelling_variant", 40);
        pairs.push(Pair { record_id: "other record".into(), poi_id: pairs[0].poi_id.clone(), ..pair(100, "name_distance", "unrelated") });
        pairs.push(Pair { poi_id: "other place".into(), ..pairs[0].clone() });
        let chosen = select_gold(&pairs[..40], &[("spelling_variant", 40)], 7);
        let held: Vec<&Pair> = chosen.iter().map(|c| &pairs[c.index]).collect();
        let training = training_indexes(&pairs, &chosen);
        for &index in &training {
            let keys = group_keys(&pairs[index]);
            for checked in &held {
                assert!(group_keys(checked).iter().all(|key| !keys.contains(key)));
            }
        }
        assert!(training.is_empty(), "both extra pairs touch a checked record or place");
    }

    #[test]
    fn training_leaves_out_pairs_that_say_nothing_about_the_pair() {
        let mut unknown = pair(1, "never_compared", "a");
        unknown.rule_label = "unknown".into();
        let not_evidence = pair(2, NOT_EVIDENCE, "b");
        let good = pair(3, "shared_phone", "c");
        assert_eq!(training_indexes(&[unknown, not_evidence, good], &[]), vec![2]);
    }

    #[test]
    fn quotas_add_up_to_five_hundred_with_no_repeats() {
        assert_eq!(QUOTAS.iter().map(|(_, n)| n).sum::<usize>(), 500);
        assert_eq!(QUOTAS.iter().map(|(rule, _)| rule).collect::<HashSet<_>>().len(), QUOTAS.len());
    }
}
