//! A blind re-check: the person labels a sample again with no AI and without seeing their earlier
//! answers, showing how far the original labels can be trusted. Pairs come from the test split only,
//! so they can also judge the models, which never trained on them or set a threshold with them.

use crate::labels::{field, parse_label, read_rows, GoldPair, Label};
use crate::output::SHEET_HEADER;
use crate::sampling::stable_hash;
use std::collections::{BTreeMap, HashMap};
use std::error::Error;
use std::path::Path;

pub const SAMPLE_SIZE: usize = 100;
const SEED: u64 = 20_261_106;

/// A shuffle position. Pair ids are short and alike ("P001"), and FNV alone barely separates them,
/// so its result goes through MurmurHash3's finaliser, which spreads every input bit across the output.
fn position(seed: u64, id: &str) -> u64 {
    let mut hash = stable_hash(seed, id);
    hash ^= hash >> 33;
    hash = hash.wrapping_mul(0xff51_afd7_ed55_8ccd);
    hash ^= hash >> 33;
    hash = hash.wrapping_mul(0xc4ce_b9fe_1a85_ec53);
    hash ^ (hash >> 33)
}

/// Splits `total` across groups in proportion to their size, largest remainders first. Exact shares
/// keep every rule represented as it is in the test split.
fn allocate(sizes: &BTreeMap<String, usize>, total: usize) -> BTreeMap<String, usize> {
    let available: usize = sizes.values().sum();
    let total = total.min(available);
    let mut shares: BTreeMap<String, usize> = sizes.iter().map(|(rule, size)| (rule.clone(), size * total / available.max(1))).collect();
    let mut remainders: Vec<(usize, &String)> = sizes.iter().map(|(rule, size)| (size * total % available.max(1), rule)).collect();
    remainders.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(b.1)));
    let given: usize = shares.values().sum();
    for (_, rule) in remainders.into_iter().take(total.saturating_sub(given)) {
        *shares.get_mut(rule).expect("same keys") += 1;
    }
    shares
}

/// The test pairs to re-check, in the new order they will be shown in.
fn choose(gold: &[GoldPair], total: usize, seed: u64) -> Vec<&GoldPair> {
    let mut by_rule: BTreeMap<String, Vec<&GoldPair>> = BTreeMap::new();
    for pair in gold.iter().filter(|p| p.split == "test") {
        by_rule.entry(pair.rule.clone()).or_default().push(pair);
    }
    let sizes = by_rule.iter().map(|(rule, pairs)| (rule.clone(), pairs.len())).collect();
    let shares = allocate(&sizes, total);
    let mut chosen = Vec::new();
    for (rule, mut pairs) in by_rule {
        pairs.sort_by_key(|p| position(seed, &p.id));
        chosen.extend(pairs.into_iter().take(shares[&rule]));
    }
    chosen.sort_by_key(|p| position(seed.rotate_left(29), &p.id));
    chosen
}

/// Writes the sheet with no earlier label, note or original id, so nothing shows the first answer.
pub fn build(dir: &Path, gold: &[GoldPair], overwrite: bool) -> Result<(), Box<dyn Error>> {
    let sheet_path = dir.join("recheck_sheet.csv");
    if sheet_path.exists() && !overwrite {
        return Err(format!("{} already exists; pass --overwrite only if nothing has been checked in it", sheet_path.display()).into());
    }
    let originals: HashMap<String, HashMap<String, String>> = read_rows(&dir.join("gold_sheet.csv"))?
        .into_iter()
        .map(|row| Ok((field(&row, "pair_id")?.to_string(), row)))
        .collect::<Result<_, String>>()?;

    let mut sheet = csv::Writer::from_path(&sheet_path)?;
    let mut key = csv::Writer::from_path(dir.join("recheck_key.csv"))?;
    sheet.write_record(SHEET_HEADER)?;
    key.write_record(["recheck_id", "pair_id"])?;
    let chosen = choose(gold, SAMPLE_SIZE, SEED);
    for (position, pair) in chosen.iter().enumerate() {
        let id = format!("R{:03}", position + 1);
        let original = originals.get(&pair.id).ok_or_else(|| format!("{} is missing from the sheet", pair.id))?;
        let row: Vec<&str> = SHEET_HEADER
            .iter()
            .map(|column| match *column {
                "pair_id" => Ok(id.as_str()),
                "label" | "note" => Ok(""),
                other => field(original, other),
            })
            .collect::<Result<_, String>>()?;
        sheet.write_record(row)?;
        key.write_record([id.as_str(), &pair.id])?;
    }
    sheet.flush()?;
    key.flush()?;
    println!("{} pairs are in {}.", chosen.len(), sheet_path.display());
    println!("Label them yourself, with no AI and without looking at gold_sheet.csv or recheck_key.csv.");
    Ok(())
}

/// Agreement beyond what chance would give: 1 is perfect, 0 is no better than chance.
fn kappa(pairs: &[(bool, bool)]) -> Option<f64> {
    if pairs.is_empty() {
        return None;
    }
    let n = pairs.len() as f64;
    let observed = pairs.iter().filter(|(a, b)| a == b).count() as f64 / n;
    let first = pairs.iter().filter(|(a, _)| *a).count() as f64 / n;
    let second = pairs.iter().filter(|(_, b)| *b).count() as f64 / n;
    let chance = first * second + (1.0 - first) * (1.0 - second);
    (chance < 1.0).then(|| (observed - chance) / (1.0 - chance))
}

fn label_name(label: Option<Label>) -> &'static str {
    match label {
        Some(Label::Same) => "same",
        Some(Label::Different) => "different",
        Some(Label::Unsure) => "unsure",
        None => "blank",
    }
}

/// Reads the re-check answers, by original pair id.
fn read_answers(dir: &Path) -> Result<HashMap<String, Option<Label>>, Box<dyn Error>> {
    let to_pair: HashMap<String, String> = read_rows(&dir.join("recheck_key.csv"))?
        .into_iter()
        .map(|row| Ok((field(&row, "recheck_id")?.to_string(), field(&row, "pair_id")?.to_string())))
        .collect::<Result<_, String>>()?;
    let mut answers = HashMap::new();
    for row in read_rows(&dir.join("recheck_sheet.csv"))? {
        let id = field(&row, "pair_id")?;
        let pair = to_pair.get(id).ok_or_else(|| format!("{id} is not in recheck_key.csv"))?;
        let label = parse_label(field(&row, "label")?).map_err(|e| format!("{id}: {e}"))?;
        answers.insert(pair.clone(), label);
    }
    Ok(answers)
}

/// Compares the two rounds, then returns a copy of the labels for judging the models: re-checked
/// test pairs carry the new answer and other test pairs none, so only fully hand-checked pairs count.
pub fn compare(dir: &Path, gold: &[GoldPair]) -> Result<Vec<GoldPair>, Box<dyn Error>> {
    let answers = read_answers(dir)?;
    let checked: Vec<(&GoldPair, Option<Label>)> = gold.iter().filter_map(|p| answers.get(&p.id).map(|a| (p, *a))).collect();
    let answered = checked.iter().filter(|(_, new)| new.is_some()).count();
    println!("Re-checked {answered} of {} pairs.\n", checked.len());

    let mut table: BTreeMap<(&str, &str), usize> = BTreeMap::new();
    let mut both = Vec::new();
    let mut by_rule: BTreeMap<&str, (usize, usize)> = BTreeMap::new();
    let mut out = csv::Writer::from_path(dir.join("recheck_disagreements.csv"))?;
    out.write_record(["pair_id", "rule", "first_label", "recheck_label", "name_a", "name_b", "distance_m", "first_note"])?;
    for (pair, new) in checked.iter().filter(|(_, new)| new.is_some()) {
        let new_truth = matches!(new, Some(Label::Same));
        *table.entry((label_name(pair.label), label_name(*new))).or_insert(0) += 1;
        if pair.truth().is_some() && *new != Some(Label::Unsure) {
            both.push((pair.truth() == Some(true), new_truth));
            let rule = by_rule.entry(&pair.rule).or_default();
            rule.0 += 1;
            rule.1 += usize::from(pair.truth() == Some(new_truth));
        }
        if pair.label != *new {
            out.write_record([pair.id.as_str(), &pair.rule, label_name(pair.label), label_name(*new), &pair.name_a, &pair.name_b, &pair.distance_m.to_string(), &pair.note])?;
        }
    }
    out.flush()?;

    println!("{:<14}{:<14}{:>6}", "First round", "Re-check", "Pairs");
    for ((first, second), count) in &table {
        println!("{first:<14}{second:<14}{count:>6}");
    }
    let agree = both.iter().filter(|(a, b)| a == b).count();
    if let Some(share) = (!both.is_empty()).then(|| agree as f64 / both.len() as f64) {
        println!("\nAnswered both times: {} pairs, {:.0}% agree.", both.len(), share * 100.0);
    }
    match kappa(&both) {
        Some(value) => println!("Agreement beyond chance (Cohen's kappa): {value:.2}. As a rough guide, above 0.8 is strong and below 0.6 is a concern."),
        None => println!("Too few answers to measure agreement beyond chance."),
    }
    println!("\n{:<36}{:>9}{:>8}", "Rule", "Compared", "Agree");
    for (rule, (compared, agreed)) in &by_rule {
        println!("{rule:<36}{compared:>9}{agreed:>8}");
    }
    println!("\nEvery changed answer is in {}.\n", dir.join("recheck_disagreements.csv").display());

    Ok(gold
        .iter()
        .cloned()
        .map(|mut pair| {
            if pair.split == "test" {
                pair.label = answers.get(&pair.id).copied().flatten();
            }
            pair
        })
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pair(id: &str, rule: &str, split: &str) -> GoldPair {
        GoldPair { id: id.into(), rule: rule.into(), split: split.into(), ..GoldPair::default() }
    }

    fn sizes(entries: &[(&str, usize)]) -> BTreeMap<String, usize> {
        entries.iter().map(|(rule, size)| (rule.to_string(), *size)).collect()
    }

    #[test]
    fn allocation_is_proportional_and_adds_up() {
        let shares = allocate(&sizes(&[("a", 50), ("b", 30), ("c", 20)]), 10);
        assert_eq!((shares["a"], shares["b"], shares["c"]), (5, 3, 2));
        let shares = allocate(&sizes(&[("a", 5), ("b", 5), ("c", 5)]), 10);
        assert_eq!(shares.values().sum::<usize>(), 10);
        let shares = allocate(&sizes(&[("a", 2), ("b", 1)]), 100);
        assert_eq!((shares["a"], shares["b"]), (2, 1), "never more than exist");
    }

    #[test]
    fn only_test_pairs_are_chosen_and_the_choice_is_stable() {
        let mut gold: Vec<GoldPair> = (0..40).map(|i| pair(&format!("P{i}"), "r", "test")).collect();
        gold.extend((40..80).map(|i| pair(&format!("P{i}"), "r", "calibration")));
        let ids = |seed| choose(&gold, 10, seed).iter().map(|p| p.id.clone()).collect::<Vec<_>>();
        assert_eq!(ids(7).len(), 10);
        assert!(choose(&gold, 10, 7).iter().all(|p| p.split == "test"));
        assert_eq!(ids(7), ids(7));
        assert_ne!(ids(7), ids(8));
    }

    #[test]
    fn similar_ids_are_properly_shuffled() {
        let gold: Vec<GoldPair> = (0..500).map(|i| pair(&format!("P{i:03}"), "r", "test")).collect();
        let chosen = choose(&gold, 100, SEED);
        let last_digits: std::collections::HashSet<char> = chosen.iter().take(30).map(|p| p.id.chars().last().unwrap()).collect();
        assert!(last_digits.len() >= 7, "the first picks should not cluster on one ending: {last_digits:?}");
        let mean = chosen.iter().map(|p| p.id[1..].parse::<f64>().unwrap()).sum::<f64>() / 100.0;
        assert!((mean - 249.5).abs() < 50.0, "the sample should span all ids, mean {mean}");
    }

    #[test]
    fn kappa_discounts_chance() {
        assert_eq!(kappa(&[(true, true), (false, false)]), Some(1.0));
        assert_eq!(kappa(&[(true, true), (true, false), (false, true), (false, false)]), Some(0.0));
        // 90% raw agreement looks strong, but when nearly all answers are "same" it is little better than chance.
        let mut lopsided = vec![(true, true); 18];
        lopsided.extend([(true, false), (false, true)]);
        assert!(kappa(&lopsided).unwrap() < 0.0);
        assert_eq!(kappa(&[]), None);
        assert_eq!(kappa(&[(true, true)]), None);
    }

    #[test]
    fn the_blind_sheet_hides_earlier_answers_and_the_comparison_uses_only_new_ones() {
        let dir = std::env::temp_dir().join(format!("saarthi-recheck-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let mut gold_sheet = csv::Writer::from_path(dir.join("gold_sheet.csv")).unwrap();
        gold_sheet.write_record(SHEET_HEADER).unwrap();
        let mut gold = Vec::new();
        for i in 0..4 {
            let id = format!("P{i}");
            let row: Vec<String> = SHEET_HEADER.iter().map(|c| match *c {
                "pair_id" => id.clone(),
                "label" => "same".to_string(),
                "note" => "FIRST ROUND NOTE".to_string(),
                "name_a" => format!("Place {i}"),
                _ => String::new(),
            }).collect();
            gold_sheet.write_record(&row).unwrap();
            gold.push(GoldPair { label: Some(Label::Same), ..pair(&id, "r", "test") });
        }
        gold.push(GoldPair { label: Some(Label::Same), ..pair("C1", "r", "calibration") });
        gold_sheet.flush().unwrap();

        build(&dir, &gold, true).unwrap();
        let text = std::fs::read_to_string(dir.join("recheck_sheet.csv")).unwrap();
        assert!(text.contains("Place 0") && text.contains("R001"));
        assert!(!text.contains("FIRST ROUND NOTE") && !text.contains("same") && !text.contains("P0"));
        assert!(build(&dir, &gold, false).is_err(), "an existing sheet is never replaced by accident");

        let mut rows = read_rows(&dir.join("recheck_sheet.csv")).unwrap();
        for (i, row) in rows.iter_mut().enumerate() {
            row.insert("label".into(), if i == 0 { "different".into() } else if i == 1 { String::new() } else { "same".into() });
        }
        let mut filled = csv::Writer::from_path(dir.join("recheck_sheet.csv")).unwrap();
        filled.write_record(SHEET_HEADER).unwrap();
        for row in &rows {
            filled.write_record(SHEET_HEADER.iter().map(|c| row[*c].as_str())).unwrap();
        }
        filled.flush().unwrap();

        let judged = compare(&dir, &gold).unwrap();
        let test: Vec<&GoldPair> = judged.iter().filter(|p| p.split == "test").collect();
        assert_eq!(test.iter().filter(|p| p.label == Some(Label::Different)).count(), 1);
        assert_eq!(test.iter().filter(|p| p.label.is_none()).count(), 1, "a blank stays blank, not the first answer");
        assert_eq!(judged.iter().find(|p| p.id == "C1").unwrap().label, Some(Label::Same), "calibration keeps its labels");
    }
}
