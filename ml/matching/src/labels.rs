//! Joins the checked sheet to its key. The two stay separate files so checking stays blind.

use std::collections::HashMap;
use std::error::Error;
use std::path::Path;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Label {
    Same,
    Different,
    Unsure,
}

/// One pair from the key, with what the person decided, if they have got to it yet.
#[derive(Debug, Clone, Default)]
pub struct GoldPair {
    pub id: String,
    pub split: String,
    pub rule: String,
    pub rule_label: String,
    pub rule_population: usize,
    pub name_a: String,
    pub category_a: String,
    pub name_b: String,
    pub category_b: String,
    pub distance_m: f64,
    pub label: Option<Label>,
    pub note: String,
}

impl GoldPair {
    /// What the rules did with the pair. A pair no rule compared stayed two places, so that counts as "different".
    pub fn rules_say_same(&self) -> bool {
        self.rule_label == "same"
    }

    /// The person's decision, when they reached one.
    pub fn truth(&self) -> Option<bool> {
        match self.label {
            Some(Label::Same) => Some(true),
            Some(Label::Different) => Some(false),
            _ => None,
        }
    }
}

pub fn parse_label(text: &str) -> Result<Option<Label>, String> {
    match text.trim().to_lowercase().as_str() {
        "" => Ok(None),
        "same" => Ok(Some(Label::Same)),
        "different" => Ok(Some(Label::Different)),
        "unsure" => Ok(Some(Label::Unsure)),
        other => Err(format!("\"{other}\" is not one of same, different or unsure")),
    }
}

/// A file's rows as maps from column name to value, so a re-exported sheet may reorder its columns.
pub fn read_rows(path: &Path) -> Result<Vec<HashMap<String, String>>, Box<dyn Error>> {
    let mut reader = csv::Reader::from_path(path).map_err(|e| format!("cannot read {}: {e}", path.display()))?;
    let headers = reader.headers()?.clone();
    let mut rows = Vec::new();
    for record in reader.records() {
        rows.push(headers.iter().map(String::from).zip(record?.iter().map(String::from)).collect());
    }
    Ok(rows)
}

pub fn field<'a>(row: &'a HashMap<String, String>, name: &str) -> Result<&'a str, String> {
    row.get(name).map(String::as_str).ok_or_else(|| format!("column {name} is missing"))
}

pub fn read_gold(dir: &Path) -> Result<Vec<GoldPair>, Box<dyn Error>> {
    let sheet = read_rows(&dir.join("gold_sheet.csv"))?;
    let key = read_rows(&dir.join("gold_key.csv"))?;
    join(&sheet, &key).map_err(Into::into)
}

/// Stops on any bad label or a pair missing from the sheet: results built on a damaged sheet would look fine and be wrong.
fn join(sheet: &[HashMap<String, String>], key: &[HashMap<String, String>]) -> Result<Vec<GoldPair>, String> {
    let mut checked = HashMap::new();
    for row in sheet {
        let id = field(row, "pair_id")?;
        let label = parse_label(field(row, "label")?).map_err(|e| format!("{id}: {e}"))?;
        if checked.insert(id.to_string(), (label, field(row, "note")?.to_string())).is_some() {
            return Err(format!("{id} appears twice in the sheet"));
        }
    }
    key.iter()
        .map(|row| {
            let id = field(row, "pair_id")?;
            let (label, note) = checked.get(id).ok_or_else(|| format!("{id} is missing from the sheet"))?;
            Ok(GoldPair {
                id: id.to_string(),
                split: field(row, "split")?.to_string(),
                rule: field(row, "rule")?.to_string(),
                rule_label: field(row, "rule_label")?.to_string(),
                rule_population: field(row, "rule_population")?.parse().map_err(|_| format!("{id}: bad rule_population"))?,
                name_a: field(row, "name_a")?.to_string(),
                category_a: field(row, "category_a")?.to_string(),
                name_b: field(row, "name_b")?.to_string(),
                category_b: field(row, "category_b")?.to_string(),
                distance_m: field(row, "distance_m")?.parse().map_err(|_| format!("{id}: bad distance_m"))?,
                label: *label,
                note: note.clone(),
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn row(cells: &[(&str, &str)]) -> HashMap<String, String> {
        cells.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect()
    }

    fn sheet_row(id: &str, label: &str) -> HashMap<String, String> {
        row(&[("pair_id", id), ("label", label), ("note", "")])
    }

    fn key_row(id: &str) -> HashMap<String, String> {
        row(&[
            ("pair_id", id), ("split", "test"), ("rule", "shared_phone"), ("rule_label", "same"), ("rule_population", "268"),
            ("name_a", "A"), ("category_a", "hotel"), ("name_b", "B"), ("category_b", "hotel"), ("distance_m", "12"),
        ])
    }

    #[test]
    fn joins_labels_whatever_their_case_and_keeps_blanks_as_unchecked() {
        let gold = join(&[sheet_row("P1", " Same "), sheet_row("P2", "")], &[key_row("P1"), key_row("P2")]).unwrap();
        assert_eq!(gold[0].label, Some(Label::Same));
        assert_eq!(gold[0].truth(), Some(true));
        assert_eq!(gold[1].label, None);
        assert_eq!(gold[1].truth(), None);
    }

    #[test]
    fn unsure_is_not_treated_as_an_answer() {
        let gold = join(&[sheet_row("P1", "unsure")], &[key_row("P1")]).unwrap();
        assert_eq!(gold[0].label, Some(Label::Unsure));
        assert_eq!(gold[0].truth(), None);
    }

    #[test]
    fn a_damaged_sheet_stops_the_run() {
        assert!(join(&[sheet_row("P1", "yes")], &[key_row("P1")]).unwrap_err().contains("P1"));
        assert!(join(&[], &[key_row("P1")]).unwrap_err().contains("missing"));
        assert!(join(&[sheet_row("P1", ""), sheet_row("P1", "")], &[]).unwrap_err().contains("twice"));
    }

    #[test]
    fn a_pair_no_rule_compared_counts_as_kept_apart() {
        let never = GoldPair { rule_label: "unknown".into(), ..GoldPair::default() };
        assert!(!never.rules_say_same());
    }
}
