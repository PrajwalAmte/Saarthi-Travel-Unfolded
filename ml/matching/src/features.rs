//! Turns a pair into numbers using only what the model may see: names, categories and distance.

use std::collections::HashSet;
use strsim::{jaro_winkler, normalized_levenshtein};

/// Words that describe the kind of business rather than identify it, as in `MatchEvidence`.
const GENERIC: &[&str] = &[
    "the", "a", "an", "and", "of", "at", "in", "by", "hotel", "hotels", "restaurant", "restaurants", "resort", "resorts", "lodge",
    "inn", "guest", "house", "guesthouse", "homestay", "hostel", "cafe", "café", "bar", "pub", "family", "veg", "pure", "oyo", "new",
];

#[cfg(test)]
const NAMES: &[&str] = &[
    "full_name_jaro_winkler", "core_name_jaro_winkler", "core_name_levenshtein", "core_word_overlap", "core_trigram_overlap",
    "core_name_contained", "core_name_length", "distance_m", "same_category", "compatible_group",
];

/// Position of the name-only score, used alone as the simplest baseline.
pub const CORE_NAME_SIMILARITY: usize = 1;

fn words(name: &str) -> Vec<String> {
    name.to_lowercase()
        .chars()
        .map(|c| if c.is_alphanumeric() { c } else { ' ' })
        .collect::<String>()
        .split_whitespace()
        .map(String::from)
        .collect()
}

/// A name without its generic words and listing numbers ("OYO 5647 Shilpi Hill Resort" becomes
/// "shilpi hill"), or the whole name when nothing else would be left.
fn core_words(all: &[String]) -> Vec<String> {
    let core: Vec<String> = all
        .iter()
        .filter(|w| !GENERIC.contains(&w.as_str()) && !w.chars().all(char::is_numeric))
        .cloned()
        .collect();
    if core.is_empty() { all.to_vec() } else { core }
}

fn overlap<T: Eq + std::hash::Hash>(a: &HashSet<T>, b: &HashSet<T>) -> f64 {
    let union = a.union(b).count();
    if union == 0 { 0.0 } else { a.intersection(b).count() as f64 / union as f64 }
}

fn trigrams(text: &str) -> HashSet<Vec<char>> {
    let chars: Vec<char> = format!("  {text} ").chars().collect();
    chars.windows(3).map(<[char]>::to_vec).collect()
}

/// The same grouping as `PoiCategories`. Unknown categories fall in their own group, so they never look compatible.
fn group_of(category: &str) -> &'static str {
    match category {
        "restaurant" | "fast_food" | "food_court" | "bakery" | "ice_cream" | "sweet_shop" | "cafe" | "juice_bar" | "bar" | "pub"
        | "nightclub" => "food_and_drink",
        "hotel" | "motel" | "resort" | "hostel" | "lodge" | "guest_house" | "holiday_rental" | "serviced_apartment" | "cottage"
        | "camp_site" => "stay",
        "museum" | "gallery" | "attraction" | "heritage_site" | "heritage_place_of_worship" | "place_of_worship" | "fort" | "palace"
        | "monument" | "memorial" | "ruins" | "archaeological_site" | "tomb" | "city_gate" | "park" | "nature_reserve" | "garden"
        | "beach" | "waterfall" | "lake" | "cave" | "peak" | "viewpoint" | "theme_park" | "water_park" | "zoo" | "aquarium" | "theatre"
        | "arts_centre" | "tour" => "see_and_do",
        "mall" | "market" | "handicrafts" => "buy",
        "railway_station" | "metro_station" | "bus_station" | "airport" | "ferry_terminal" => "transport",
        _ => "other",
    }
}

pub fn features(name_a: &str, category_a: &str, name_b: &str, category_b: &str, distance_m: f64) -> Vec<f64> {
    let (words_a, words_b) = (words(name_a), words(name_b));
    let (core_a, core_b) = (core_words(&words_a), core_words(&words_b));
    let (full_a, full_b) = (words_a.join(" "), words_b.join(" "));
    let (joined_a, joined_b) = (core_a.join(" "), core_b.join(" "));
    let (set_a, set_b): (HashSet<_>, HashSet<_>) = (core_a.iter().collect(), core_b.iter().collect());
    let group_a = group_of(category_a);
    vec![
        jaro_winkler(&full_a, &full_b),
        jaro_winkler(&joined_a, &joined_b),
        normalized_levenshtein(&joined_a, &joined_b),
        overlap(&set_a, &set_b),
        overlap(&trigrams(&joined_a), &trigrams(&joined_b)),
        f64::from(u8::from(set_a.is_subset(&set_b) || set_b.is_subset(&set_a))),
        joined_a.chars().count().min(joined_b.chars().count()) as f64,
        distance_m,
        f64::from(u8::from(category_a == category_b)),
        f64::from(u8::from(group_a != "other" && group_a == group_of(category_b))),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn value(f: &[f64], name: &str) -> f64 {
        f[NAMES.iter().position(|n| *n == name).unwrap()]
    }

    #[test]
    fn generic_words_and_listing_numbers_do_not_count_as_identity() {
        let f = features("OYO 5647 Shilpi Hill Resort", "resort", "Shilpi Hill", "hotel", 282.0);
        assert_eq!(value(&f, "core_word_overlap"), 1.0);
        assert_eq!(value(&f, "core_name_contained"), 1.0);
        assert_eq!(value(&f, "core_name_jaro_winkler"), 1.0);
        assert!(value(&f, "full_name_jaro_winkler") < 1.0);
        assert_eq!(value(&f, "same_category"), 0.0);
        assert_eq!(value(&f, "compatible_group"), 1.0);
        assert_eq!(value(&f, "distance_m"), 282.0);
    }

    #[test]
    fn a_shared_generic_word_says_nothing() {
        let f = features("Hotel Radha", "hotel", "Hotel Prism", "hotel", 10.0);
        assert_eq!(value(&f, "core_word_overlap"), 0.0);
        assert_eq!(value(&f, "core_name_contained"), 0.0);
    }

    #[test]
    fn a_name_of_only_generic_words_is_kept_whole() {
        assert_eq!(core_words(&words("The Hotel")), vec!["the", "hotel"]);
        assert_eq!(value(&features("The Hotel", "hotel", "The Hotel", "hotel", 1.0), "core_word_overlap"), 1.0);
    }

    #[test]
    fn spelling_variants_score_close_and_punctuation_does_not_matter() {
        let f = features("Ambady", "hotel", "Ambadi", "hotel", 50.0);
        assert!(value(&f, "core_name_jaro_winkler") > 0.9);
        assert!(value(&f, "core_word_overlap") == 0.0, "the words differ, so characters have to carry it");
        let g = features("Jack's Corner", "bar", "JACK'S  CORNER!", "pub", 22.0);
        assert_eq!(value(&g, "core_name_levenshtein"), 1.0);
    }

    #[test]
    fn unrelated_kinds_of_place_are_not_compatible() {
        let f = features("Deshapriya Park", "bus_station", "Deshapriya Park", "park", 97.0);
        assert_eq!(value(&f, "compatible_group"), 0.0);
        assert_eq!(value(&features("X", "unheard_of", "X", "unheard_of", 1.0), "compatible_group"), 0.0);
    }

    #[test]
    fn empty_names_do_not_break_anything() {
        let f = features("", "", "", "", 0.0);
        assert_eq!(f.len(), NAMES.len());
        assert!(f.iter().all(|v| v.is_finite()));
    }
}
