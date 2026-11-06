# Place matching: labels

Saarthi's place-matching rules decide whether a source record and a mapped place are the same real
place. Nobody has checked those decisions by hand, so they cannot show how good the rules are, or
whether a model beats them. This tool produces a set a person can check, and training pairs that
share nothing with it.

## Run

The tool opens a read-only session. It needs the `DATABASE_*` values in the repository's `.env`.

```bash
cd ml/matching && cargo run --release -- export
```

The same seed always picks the same pairs while the data stays the same. An existing sheet is never
replaced unless you pass `--overwrite`, which discards any checking done in it.

## Files

All are written to `data/`, which git ignores because the sheet holds contact details.

| File | Contents | Who uses it |
| --- | --- | --- |
| `gold_sheet.csv` | 500 pairs in random order, with contact details and map links, and no rule decisions | The person checking |
| `gold_key.csv` | Each pair's rule, its decision, its test or calibration split and the model's inputs | Scoring only; do not open it before checking is finished |
| `train_pairs.csv` | Names, categories, distance and the rule's decision, with no phones, websites or addresses | Training |

Checked pairs share no record, mapped place or name with each other or with the training pairs.

## How to check a pair

Fill in `label` with one of these, and use `note` for anything odd. Use the links as well as the
sheet; the map link shows both points and the search links open Google Maps.

| Label | Meaning |
| --- | --- |
| `same` | The same place or business at the same location, even if spelled differently or listed by a booking site such as OYO |
| `different` | Different places: another branch of a chain, a restaurant inside a hotel, a bus stop named after a park |
| `unsure` | You could not tell after a minute of looking |

- Judge the real places, not the records. Identical names can be two places, and different names can
  be one.
- A place that has been renamed at the same location is `same`.
- Use `unsure` freely. A guess would be treated as truth, and these labels are what everything else
  is measured against.

## Score the rules and set the baseline

Save the checked sheet over `data/gold_sheet.csv`. Neither command needs the database, and both work
on a partly checked sheet.

```bash
cargo run --release -- score
```

This shows how often each rule agrees with you, how many of its decisions you would overturn across
all its pairs, and a `data/disagreements.csv` to read when improving the rules. Pairs are weighted to
each rule's real size, because rules were not sampled evenly.

```bash
cargo run --release -- baseline
```

This compares the current rules, name similarity alone and gradient boosting on the test pairs.
Thresholds come from the calibration pairs only. It sets the score a neural model has to beat, and
reports a range for the gain so that a lucky sample is not mistaken for an improvement.

## Blind re-check

About 30% of the first-round labels were drafted by AI and then reviewed by the person checking, and the
sheet does not record which. Reviewing a drafted answer can still anchor the reviewer. This optional
re-check measures how reliable the labels are, and is worth running before publishing accuracy figures:

```bash
cargo run --release -- recheck
```

This writes `data/recheck_sheet.csv`: 100 test pairs in a new order, under new ids, with earlier labels and
notes removed. Label them yourself with no AI, using the rules above, and do not open `gold_sheet.csv` or
`recheck_key.csv` until you have finished. Then:

```bash
cargo run --release -- recheck-score --scores data/neural_scores_hard.csv
```

This reports agreement between the two rounds, overall and by rule, and writes every changed answer to
`data/recheck_disagreements.csv`. It then compares the systems on the re-checked pairs alone. With about 100
pairs the ranges will be wide, so this can test the overall picture but not rank close systems.

## Fine-tune the neural model

`train/` fine-tunes MiniLM as a cross-encoder in Rust, with Burn on the Apple GPU. Put
`model.safetensors` and `tokenizer.json` from `sentence-transformers/all-MiniLM-L6-v2` in `data/model/`.

```bash
cd train
cargo run --release -- verify
cargo run --release -- train --name hard
cd .. && cargo run --release -- baseline --scores data/neural_scores_hard.csv
```

`verify` checks that the model reproduces MiniLM's published embedding before any training. `--soft` uses
targets based on how reliable each rule was on the calibration pairs. Choose between models using the
calibration F1 that `baseline` prints, never the test results.

Training saves a self-contained folder in `data/models/<name>/`: the weights, tokenizer and a manifest. Set
the cut-off that `baseline` printed for that model, then score any pairs without the training data:

```bash
cargo run --release -- package --model ../data/models/hard --threshold 0.154
cargo run --release -- predict --model ../data/models/hard --input pairs.csv --output scores.csv --device cpu
```

The input needs `name_a`, `category_a`, `name_b`, `category_b` and `distance_m`, and may have a `pair_id`.
`--device` is `cpu` everywhere or `metal` on a Mac, and defaults to `metal` on a Mac and `cpu` elsewhere.

The trainer reports each step to [Calibrate](https://github.com/PrajwalAmte/Calibrate): attach with
`calibrate watch --pid <pid>`.

## Retrain with the current rules

The first training set copied the decisions the database held, which came from older rules, and held no
pair that no rule ever compared. This rebuilds it from the rules as they are now. None of the steps changes
the checked sheet.

```bash
cargo run --release -- pairs                 # read-only: every decided pair, and a larger never-compared sample
./redecide/run.sh                            # decide each pair with the matching rules in this checkout
cargo run --release -- build                 # writes train_pairs_v2.csv and gold_evidence.csv
```

`pairs` writes `data/pairs_full.csv`, which holds phone numbers and websites, so it stays in `data/`.
`redecide/run.sh` compiles the real `MatchEvidence` and runs it over those pairs, so the decisions cannot drift
from the importer's. Pass a git ref, such as `./redecide/run.sh 0707c48`, to decide with the rules as they were
then. Against the database's own recorded decisions, the rules before the fixes agree on 98.6% of reviewed pairs,
which shows the program is faithful.

`build` re-decides reviewed pairs with the current rules, keeps the database's answer for pairs settled on import
(`name_distance`, `wikidata`), and adds never-compared pairs as the rules would decide them. Each rule's pairs
are weighted by how often the rule agreed with the person on the **calibration** pairs, pulled towards 0.85 when
few were checked. Nothing a person will check, or that shares a record, place or name with one, is trained on.

```bash
cd train && cargo run --release -- train --name v2 --train train_pairs_v2.csv
cd .. && cargo run --release -- stack --scores data/neural_scores_v2.csv
```

`stack` compares the rules before and after the fixes, boosting with and without contact evidence, and the neural
model, then combines them with a short logistic fit on the calibration answers. It sets two cut-offs from
predictions made on pairs the fit had not seen: an automatic one that must reach 95% precision, and a lower one for
review.

## A clean test

The first 500 checked pairs were used to design rule fixes, so results on them flatter anything built on the rules.
A second sheet of 320 pairs is a test nobody has tuned against:

```bash
cargo run --release -- fresh                 # read-only; writes data/fresh/gold_sheet.csv and gold_key.csv
cargo run --release -- build                 # keeps data/fresh out of training when it exists
cd train && cargo run --release -- train --name v2 --train train_pairs_v2.csv
```

Its pairs share no record, mapped place or name with the first sheet, are all test pairs, and have ids `F001`
onwards. Retrain after exporting it, because a model trained earlier has seen some of its pairs. Label the sheet as
before, without AI help, and do not change rules or models using these answers.

## What is in `data/`

All of it is git-ignored. Keep the first two rows safe: nothing else can recreate them.

| Files | What they are | Can be rebuilt? |
| --- | --- | --- |
| `gold_sheet.csv`, `gold_key.csv`, `fresh/gold_sheet.csv`, `fresh/gold_key.csv` | The checked pairs and their answers | No |
| `model/` | MiniLM, the pretrained starting point | Yes, from Hugging Face |
| `models/v2/` | The trained model that is published | By training again |
| `train_pairs_v2.csv`, `neural_scores_v2.csv`, `fresh/neural_scores_v2.csv`, `gold_evidence.csv`, `fresh/gold_evidence.csv` | Training pairs, and scores and evidence for the checked pairs | `build`, `train`, `predict` |
| `pairs_full.csv`, `pairs_decided.csv` | Every pair from the database, and what the rules decide | `pairs`, `redecide/run.sh` |
| `train_pairs.csv` | The first model's training pairs, which `baseline` reads | No; the database has changed |
| `recheck_sheet.csv`, `recheck_key.csv` | The optional blind re-check, not yet filled in | `recheck` |

## Publish a model

[MODEL_CARD.md](MODEL_CARD.md) becomes the Hugging Face page. This builds the release files in
`data/release/` and uploads nothing:

```bash
./publish.sh v2 matcher-v0.2.0
```

Add `--upload` to send them to Hugging Face. The token is read from `HF_TOKEN` in the repository's `.env`.
Attach the `.tar.gz` and `SHA256SUMS` from `data/release/` to the GitHub release, whose text is in
[RELEASE_NOTES.md](RELEASE_NOTES.md).

Looking at results part-way through can sway the checking that remains, so keep the looks few. Avoid
tuning anything against the test pairs, or the final numbers stop meaning what they say.
