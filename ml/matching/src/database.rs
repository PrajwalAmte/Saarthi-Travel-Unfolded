//! Reads candidate pairs. The session is read-only, so this tool cannot change the database.

use crate::sampling::Pair;
use native_tls::TlsConnector;
use postgres::config::SslMode;
use postgres::{Client, Config, Row};
use postgres_native_tls::MakeTlsConnector;
use std::collections::HashMap;
use std::error::Error;
use std::path::Path;

/// Other records of the mapped place, never the record being compared: for a merged pair that
/// record would trivially repeat its own phone and website on side B.
const COLUMNS: &str = r#"
    r.id::text AS record_id, p.id::text AS poi_id,
    r.name AS name_a, coalesce(idi_normalize(r.name), '') AS name_a_normalized, r.category AS category_a, r.source_id AS source_a,
    coalesce(r.attributes ->> 'address', '') AS address_a, coalesce(r.attributes ->> 'phone', '') AS phone_a,
    coalesce(r.attributes ->> 'website', '') AS website_a, r.latitude AS lat_a, r.longitude AS lon_a,
    p.name AS name_b, coalesce(p.name_normalized, '') AS name_b_normalized, p.category AS category_b,
    other.sources AS sources_b, other.addresses AS address_b, other.phones AS phones_b, other.websites AS websites_b,
    p.latitude AS lat_b, p.longitude AS lon_b, ST_Distance(r.geog, p.geog) AS distance_m
"#;

const OTHER_RECORDS: &str = r#"
    CROSS JOIN LATERAL (
        SELECT string_agg(DISTINCT o.source_id, ' | ') AS sources,
               coalesce(string_agg(DISTINCT o.attributes ->> 'address', ' | '), '') AS addresses,
               coalesce(string_agg(DISTINCT o.attributes ->> 'phone', ' | '), '') AS phones,
               coalesce(string_agg(DISTINCT o.attributes ->> 'website', ' | '), '') AS websites
        FROM poi_source_record o WHERE o.poi_id = p.id AND o.id <> r.id AND o.deleted_at IS NULL) other
"#;

fn pair_from(row: &Row, rule: String, rule_label: String) -> Pair {
    Pair {
        record_id: row.get("record_id"),
        poi_id: row.get("poi_id"),
        rule,
        rule_label,
        name_a: row.get("name_a"),
        name_a_normalized: row.get("name_a_normalized"),
        category_a: row.get("category_a"),
        source_a: row.get("source_a"),
        address_a: row.get("address_a"),
        phone_a: row.get("phone_a"),
        website_a: row.get("website_a"),
        lat_a: row.get("lat_a"),
        lon_a: row.get("lon_a"),
        name_b: row.get("name_b"),
        name_b_normalized: row.get("name_b_normalized"),
        category_b: row.get("category_b"),
        sources_b: row.get("sources_b"),
        address_b: row.get("address_b"),
        phones_b: row.get("phones_b"),
        websites_b: row.get("websites_b"),
        lat_b: row.get("lat_b"),
        lon_b: row.get("lon_b"),
        distance_m: row.get("distance_m"),
    }
}

/// Reads DATABASE_URL (JDBC form), DATABASE_USERNAME and DATABASE_PASSWORD from a .env file.
fn settings(env_file: &Path) -> Result<HashMap<String, String>, Box<dyn Error>> {
    let text = std::fs::read_to_string(env_file).map_err(|e| format!("cannot read {}: {e}", env_file.display()))?;
    Ok(text
        .lines()
        .filter(|line| !line.trim_start().starts_with('#'))
        .filter_map(|line| line.split_once('='))
        .map(|(key, value)| (key.trim().to_string(), value.trim().trim_matches(['"', '\'']).to_string()))
        .collect())
}

/// Splits `jdbc:postgresql://host:port/db?options` into host, port and database name.
fn parse_jdbc(url: &str) -> Option<(String, u16, String)> {
    let rest = url.strip_prefix("jdbc:postgresql://")?;
    let (address, path) = rest.split_once('/')?;
    let database = path.split('?').next()?.to_string();
    let (host, port) = match address.split_once(':') {
        Some((host, port)) => (host, port.parse().ok()?),
        None => (address, 5432),
    };
    Some((host.to_string(), port, database))
}

pub fn connect(env_file: &Path) -> Result<Client, Box<dyn Error>> {
    let env = settings(env_file)?;
    let get = |key: &str| env.get(key).ok_or_else(|| format!("{key} is missing from {}", env_file.display()));
    let url = get("DATABASE_URL")?;
    let (host, port, database) = parse_jdbc(url).ok_or("DATABASE_URL is not a jdbc:postgresql:// address")?;
    let mut config = Config::new();
    config.host(&host).port(port).dbname(&database).user(get("DATABASE_USERNAME")?).password(get("DATABASE_PASSWORD")?).ssl_mode(SslMode::Require);
    let tls = MakeTlsConnector::new(TlsConnector::new()?);
    let mut client = config.connect(tls)?;
    client.batch_execute("SET default_transaction_read_only = on")?;
    Ok(client)
}

/// Every pair a rule has decided, skipping records and places that have since left the map.
pub fn decided_pairs(client: &mut Client) -> Result<Vec<Pair>, Box<dyn Error>> {
    let reviewed = format!(
        r#"SELECT {COLUMNS}, m.resolution AS rule, CASE m.status WHEN 'merged' THEN 'same' ELSE 'different' END AS rule_label
           FROM poi_match_review m
           JOIN poi_source_record r ON r.id = m.record_id AND r.deleted_at IS NULL
           JOIN poi p ON p.id = m.candidate_poi_id AND p.status = 'active'
           {OTHER_RECORDS}
           WHERE m.status IN ('merged', 'distinct') AND other.sources IS NOT NULL"#
    );
    // These never reached review: name and distance, or a shared Wikidata id, settled them on import.
    let automatic = format!(
        r#"SELECT {COLUMNS}, r.match_method AS rule, 'same' AS rule_label
           FROM poi_source_record r
           JOIN poi p ON p.id = r.poi_id AND p.status = 'active'
           {OTHER_RECORDS}
           WHERE r.deleted_at IS NULL AND r.match_confidence = 'high' AND r.match_method IN ('name_distance', 'wikidata')
             AND other.sources IS NOT NULL"#
    );
    let mut pairs = Vec::new();
    for sql in [reviewed, automatic] {
        for row in client.query(sql.as_str(), &[])? {
            pairs.push(pair_from(&row, row.get("rule"), row.get("rule_label")));
        }
    }
    Ok(pairs)
}

/// Overture records no rule ever compared with anything, each with its most similarly named
/// neighbour. Matches the rules missed can only be found here. Returns the pairs and an estimate of
/// how many such pairs exist across all of these records, for weighting results.
pub fn never_compared_pairs(client: &mut Client, seed: u64, sample_size: i64) -> Result<(Vec<Pair>, usize), Box<dyn Error>> {
    let total: i64 = client
        .query_one(
            "SELECT count(*) FROM poi_source_record WHERE source_id = 'overture-places' AND deleted_at IS NULL AND match_confidence = 'new'",
            &[],
        )?
        .get(0);
    let sql = format!(
        r#"WITH sampled AS (
               SELECT * FROM poi_source_record
               WHERE source_id = 'overture-places' AND deleted_at IS NULL AND match_confidence = 'new'
                 AND NOT EXISTS (SELECT 1 FROM poi_match_review m WHERE m.record_id = poi_source_record.id)
               ORDER BY md5(id::text || $1) LIMIT $2)
           SELECT {COLUMNS}
           FROM sampled r
           CROSS JOIN LATERAL (
               SELECT n.* FROM poi n
               WHERE n.status = 'active' AND n.id IS DISTINCT FROM r.poi_id AND ST_DWithin(n.geog, r.geog, 400)
                 AND (n.category_group = r.category_group
                      OR (n.category_group IN ('eat', 'drink') AND r.category_group IN ('eat', 'drink'))
                      OR (n.category_group IN ('see', 'do') AND r.category_group IN ('see', 'do')))
                 AND similarity(n.name_normalized, idi_normalize(r.name)) >= 0.3
               ORDER BY similarity(n.name_normalized, idi_normalize(r.name)) DESC, n.id LIMIT 1) p
           {OTHER_RECORDS}
           WHERE other.sources IS NOT NULL"#
    );
    let rows = client.query(sql.as_str(), &[&seed.to_string(), &sample_size])?;
    let pairs: Vec<Pair> = rows.iter().map(|row| pair_from(row, "never_compared".to_string(), "unknown".to_string())).collect();
    let estimate = if sample_size > 0 { pairs.len() as f64 / sample_size as f64 * total as f64 } else { 0.0 };
    Ok((pairs, estimate.round() as usize))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_a_jdbc_address() {
        assert_eq!(
            parse_jdbc("jdbc:postgresql://db.example.com:6543/saarthi?sslmode=require"),
            Some(("db.example.com".to_string(), 6543, "saarthi".to_string()))
        );
        assert_eq!(parse_jdbc("jdbc:postgresql://db.example.com/saarthi"), Some(("db.example.com".to_string(), 5432, "saarthi".to_string())));
        assert_eq!(parse_jdbc("postgres://db.example.com/saarthi"), None);
    }
}
