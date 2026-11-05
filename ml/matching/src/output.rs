//! Writes the three files. The sheet hides what the rules decided, so they cannot sway the person
//! checking; the training file holds only what the model may see.

use crate::sampling::{population, shuffle_position, training_indexes, Chosen, Pair};
use std::collections::BTreeMap;
use std::error::Error;
use std::path::Path;

pub const SHEET_HEADER: &[&str] = &[
    "pair_id", "label", "note", "name_a", "category_a", "source_a", "address_a", "phone_a", "website_a", "name_b",
    "category_b", "sources_b", "address_b", "phone_b", "website_b", "distance_m", "map", "search_a", "search_b",
];

/// Percent-encodes everything outside the characters a URL query can carry unchanged.
fn encode(text: &str) -> String {
    let mut out = String::new();
    for byte in text.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => out.push(byte as char),
            _ => out.push_str(&format!("%{byte:02X}")),
        }
    }
    out
}

/// A map showing both points, with a route between them to make the gap easy to judge.
fn map_url(pair: &Pair) -> String {
    format!(
        "https://www.openstreetmap.org/directions?route={:.6}%2C{:.6}%3B{:.6}%2C{:.6}",
        pair.lat_a, pair.lon_a, pair.lat_b, pair.lon_b
    )
}

fn search_url(name: &str, lat: f64, lon: f64) -> String {
    format!(
        "https://www.google.com/maps/search/?api=1&query={}",
        encode(&format!("{name} {lat:.6},{lon:.6}"))
    )
}

/// Drops the tracking parameters many listings carry, which only make the sheet harder to read.
fn clean_websites(websites: &str) -> String {
    websites
        .split(" | ")
        .map(|site| site.split(['?', '#']).next().unwrap_or(site))
        .collect::<Vec<_>>()
        .join(" | ")
}

fn metres(distance: f64) -> String {
    format!("{}", distance.round() as i64)
}

/// Sheet order and pair ids come from a shuffle, so neither gives away a pair's rule.
fn sheet_order(pairs: &[Pair], chosen: &[Chosen], seed: u64) -> Vec<Chosen> {
    let mut order = chosen.to_vec();
    order.sort_by_key(|c| (shuffle_position(seed.rotate_left(17), &pairs[c.index]), c.index));
    order
}

pub fn write_gold(dir: &Path, pairs: &[Pair], chosen: &[Chosen], seed: u64, never_compared_population: usize) -> Result<(), Box<dyn Error>> {
    let mut populations = population(pairs);
    populations.insert("never_compared".to_string(), never_compared_population);

    let mut sheet = csv::Writer::from_path(dir.join("gold_sheet.csv"))?;
    let mut key = csv::Writer::from_path(dir.join("gold_key.csv"))?;
    sheet.write_record(SHEET_HEADER)?;
    key.write_record([
        "pair_id", "split", "rule", "rule_label", "rule_population", "record_id", "poi_id", "name_a", "category_a", "source_a",
        "name_b", "category_b", "distance_m",
    ])?;
    for (position, c) in sheet_order(pairs, chosen, seed).iter().enumerate() {
        let p = &pairs[c.index];
        let id = format!("P{:03}", position + 1);
        let distance = metres(p.distance_m);
        sheet.write_record([
            id.as_str(), "", "", &p.name_a, &p.category_a, &p.source_a, &p.address_a, &p.phone_a, &clean_websites(&p.website_a),
            &p.name_b, &p.category_b, &p.sources_b, &p.address_b, &p.phones_b, &clean_websites(&p.websites_b), &distance,
            &map_url(p), &search_url(&p.name_a, p.lat_a, p.lon_a), &search_url(&p.name_b, p.lat_b, p.lon_b),
        ])?;
        let rule_population = populations.get(&p.rule).copied().unwrap_or(0).to_string();
        key.write_record([
            id.as_str(), c.split.as_str(), &p.rule, &p.rule_label, &rule_population, &p.record_id, &p.poi_id, &p.name_a,
            &p.category_a, &p.source_a, &p.name_b, &p.category_b, &distance,
        ])?;
    }
    sheet.flush()?;
    key.flush()?;
    Ok(())
}

/// Returns the number of training pairs per rule.
pub fn write_training(dir: &Path, pairs: &[Pair], chosen: &[Chosen]) -> Result<BTreeMap<String, usize>, Box<dyn Error>> {
    let mut out = csv::Writer::from_path(dir.join("train_pairs.csv"))?;
    out.write_record(["record_id", "poi_id", "rule", "rule_label", "name_a", "category_a", "source_a", "name_b", "category_b", "distance_m"])?;
    let mut counts = BTreeMap::new();
    for index in training_indexes(pairs, chosen) {
        let p = &pairs[index];
        out.write_record([
            p.record_id.as_str(), &p.poi_id, &p.rule, &p.rule_label, &p.name_a, &p.category_a, &p.source_a, &p.name_b,
            &p.category_b, &metres(p.distance_m),
        ])?;
        *counts.entry(p.rule.clone()).or_insert(0) += 1;
    }
    out.flush()?;
    Ok(counts)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sampling::Split;

    fn sample() -> Pair {
        Pair {
            record_id: "r1".into(),
            poi_id: "p1".into(),
            rule: "shared_phone".into(),
            rule_label: "same".into(),
            name_a: "Hotel Grand & Co".into(),
            name_a_normalized: "hotel grand co".into(),
            category_a: "hotel".into(),
            source_a: "overture-places".into(),
            phone_a: "+911234567890".into(),
            website_a: "https://grand.example/rooms?utm_source=x".into(),
            name_b: "Grand".into(),
            name_b_normalized: "grand".into(),
            category_b: "hotel".into(),
            sources_b: "openstreetmap".into(),
            phones_b: "+911234567890".into(),
            websites_b: "https://grand.example/ | https://b.example/#top".into(),
            lat_a: 12.5,
            lon_a: 76.5,
            lat_b: 12.6,
            lon_b: 76.6,
            distance_m: 18.6,
            ..Pair::default()
        }
    }

    fn temp_dir(name: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("saarthi-matching-{name}-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn the_sheet_never_shows_what_the_rules_decided() {
        let dir = temp_dir("sheet");
        let pairs = vec![sample()];
        write_gold(&dir, &pairs, &[Chosen { index: 0, split: Split::Test }], 7, 0).unwrap();
        let sheet = std::fs::read_to_string(dir.join("gold_sheet.csv")).unwrap();
        assert!(!sheet.contains("shared_phone") && !sheet.contains(",same,") && !sheet.contains("test"));
        assert!(sheet.contains("P001"));
        assert!(sheet.contains("+911234567890"), "the person checking may use contact details");
        assert!(!sheet.contains("utm_source"));
        let key = std::fs::read_to_string(dir.join("gold_key.csv")).unwrap();
        assert!(key.contains("shared_phone") && key.contains("test"));
        assert!(!key.contains("+911234567890"), "the key also feeds the model, so it holds no contact details");
    }

    #[test]
    fn the_training_file_holds_no_contact_details() {
        let dir = temp_dir("train");
        let counts = write_training(&dir, &[sample()], &[]).unwrap();
        assert_eq!(counts.get("shared_phone"), Some(&1));
        let train = std::fs::read_to_string(dir.join("train_pairs.csv")).unwrap();
        assert!(train.contains("Hotel Grand & Co"));
        assert!(!train.contains("+911234567890") && !train.contains("grand.example"));
    }

    #[test]
    fn links_are_encoded_and_websites_lose_tracking() {
        assert_eq!(encode("A&B 1,2"), "A%26B%201%2C2");
        assert!(search_url("Tea & Co", 12.5, 76.5).contains("Tea%20%26%20Co%2012.500000%2C76.500000"));
        assert_eq!(clean_websites("https://a.example/x?utm=1 | https://b.example/#top"), "https://a.example/x | https://b.example/");
        assert!(map_url(&sample()).contains("12.500000%2C76.500000%3B12.600000%2C76.600000"));
        assert_eq!(metres(18.6), "19");
    }
}
