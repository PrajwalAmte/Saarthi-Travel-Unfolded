//! Builds and scores the hand-checked set for Saarthi's place matching. See README.md.

mod baseline;
mod database;
mod features;
mod gbdt;
mod labels;
mod output;
mod recheck;
mod sampling;
mod score;

use std::error::Error;
use std::path::{Path, PathBuf};

/// Changing this picks a different sample, so keep it once people have started checking.
const SEED: u64 = 20_261_105;
const NEVER_COMPARED_SAMPLE: i64 = 4_000;
const USAGE: &str = "Usage: saarthi-matching <export|score|baseline|recheck|recheck-score> [--data DIR] [--env FILE] [--overwrite] [--scores FILE]";

fn main() -> Result<(), Box<dyn Error>> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let data = option(&args, "--data").unwrap_or_else(|| PathBuf::from("data"));
    match args.first().map(String::as_str) {
        Some("export") => export(&args, &data),
        Some("score") => score::run(&data, &labels::read_gold(&data)?),
        Some("baseline") => baseline::run(&data, &labels::read_gold(&data)?, option(&args, "--scores").as_deref()),
        Some("recheck") => recheck::build(&data, &labels::read_gold(&data)?, args.iter().any(|arg| arg == "--overwrite")),
        Some("recheck-score") => {
            let judged = recheck::compare(&data, &labels::read_gold(&data)?)?;
            baseline::run(&data, &judged, option(&args, "--scores").as_deref())
        }
        _ => Err(USAGE.into()),
    }
}

fn export(args: &[String], out_dir: &Path) -> Result<(), Box<dyn Error>> {
    let env_file = option(args, "--env").unwrap_or_else(|| PathBuf::from("../../.env"));
    std::fs::create_dir_all(out_dir)?;

    // Writing over a sheet could destroy hours of checking.
    let sheet = out_dir.join("gold_sheet.csv");
    if sheet.exists() && !args.iter().any(|arg| arg == "--overwrite") {
        return Err(format!("{} already exists; pass --overwrite only if nothing has been checked in it", sheet.display()).into());
    }

    let mut client = database::connect(&env_file)?;
    let mut pairs = database::decided_pairs(&mut client)?;
    println!("Pairs decided by rules: {}", pairs.len());
    let (never_compared, estimate) = database::never_compared_pairs(&mut client, SEED, NEVER_COMPARED_SAMPLE)?;
    println!("Never-compared pairs: {} from {NEVER_COMPARED_SAMPLE} sampled records, about {estimate} in all", never_compared.len());
    pairs.extend(never_compared);

    let chosen = sampling::select_gold(&pairs, sampling::QUOTAS, SEED);
    output::write_gold(out_dir, &pairs, &chosen, SEED, estimate)?;
    let training = output::write_training(out_dir, &pairs, &chosen)?;

    println!("\n{:<36}{:>10}{:>8}{:>8}", "Rule", "Decided", "Check", "Train");
    let population = sampling::population(&pairs);
    for (rule, decided) in &population {
        let check = chosen.iter().filter(|c| pairs[c.index].rule == *rule).count();
        println!("{rule:<36}{decided:>10}{check:>8}{:>8}", training.get(rule).copied().unwrap_or(0));
    }
    println!("\nTo check: {}. Training pairs: {}.", chosen.len(), training.values().sum::<usize>());
    println!("Open {} to start. Do not open gold_key.csv until checking is finished.", sheet.display());
    Ok(())
}

fn option(args: &[String], name: &str) -> Option<PathBuf> {
    args.iter().position(|arg| arg == name).and_then(|at| args.get(at + 1)).map(PathBuf::from)
}
