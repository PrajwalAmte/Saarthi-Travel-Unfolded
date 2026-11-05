---
license: apache-2.0
base_model: sentence-transformers/all-MiniLM-L6-v2
language:
  - en
tags:
  - entity-matching
  - cross-encoder
  - geospatial
  - india
  - safetensors
  - burn
pipeline_tag: text-classification
---

# Saarthi place matcher

**Experimental.** A small model that scores whether two map records from India describe the same real place,
using their names, categories and the distance between them. It is not used by the live
[Saarthi](https://github.com/PrajwalAmte/Saarthi-Travel-Unfolded) site, and it has not clearly beaten the
hand-written rules it was compared with.

| | |
| --- | --- |
| Base model | [sentence-transformers/all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2), revision `1110a243`, Apache-2.0 |
| Licence | Apache-2.0 for the weights and the code |
| Size | 90 MB of 32-bit weights, about 265 MB of memory while running |
| Decision cut-off | 0.154, chosen on calibration pairs |

## Intended use

Use it to review or rank candidate duplicates, with a person or stricter checks behind it. A score must not
merge two places by itself: on the test pairs, about one in five of its "same" answers was wrong after
weighting.

It is not meant for places outside India, for general entity matching, or for matching people or companies.

## How it was changed from the base model

MiniLM's six encoder layers are kept and fine-tuned as a cross-encoder. Each pair is read as
`name (category)` for both sides. A new classifier takes the first token's output plus two extra inputs: the
log-scaled distance and whether the categories are identical.

| Setting | Value |
| --- | --- |
| Steps and batch | 400 steps of 32 pairs, half of each batch from each class |
| Optimiser | AdamW, peak learning rate 5e-5, 10% warm-up, weight decay 0.01, gradient clipping 1.0 |
| Framework | [Burn](https://burn.dev) 0.22 in Rust, on an Apple GPU |
| Reproducibility | A second run from the same seed gave scores within 0.000002 |

## Training data

The model trained on 19,332 pairs of a source record and a mapped place. **The training answers are the
decisions of Saarthi's matching rules, not human labels**, so the model partly learns to copy those rules: 90%
of the answers were "same" and 10% "different". The pairs held only names, categories, distance and the rule's
decision. They are not published with this model.

| Source of the record | Pairs | Licence |
| --- | --- | --- |
| [Overture Maps](https://docs.overturemaps.org/attribution/) Places | 18,406 | By contributing dataset: CDLA Permissive 2.0, Apache-2.0 or CC0 |
| Protected monuments from [Wikidata](https://www.wikidata.org/wiki/Property:P1371) (ASI numbers) | 761 | CC0 1.0 |
| [OpenStreetMap](https://www.openstreetmap.org/copyright) | 165 | ODbL 1.0 |

The mapped place on the other side of a pair may also take its name from any of these sources.

Attribution: © OpenStreetMap contributors. © Overture Maps Foundation and its contributing datasets. Wikidata
statements are CC0.

The Apache-2.0 licence does not change the terms of that source data. This model has not had a legal review of
whether weights trained on ODbL names count as a derived database, so make your own assessment before relying
on it where that matters.

## Results

The test used pairs set aside before training, sharing no record, mapped place or name with the training pairs.
The labels are the author's, with **about 30% drafted by AI and then reviewed by the author**. The sheet does
not record which ones. Reviewing a drafted answer can anchor the reviewer, and a blind re-check has not been
run, so how reliable the labels are has not been measured.

Of 254 test pairs, 198 got an answer (128 same, 70 different). Results are weighted to each rule's real size,
because the rules were not sampled evenly.

| System | Precision | Recall | F1 | F1 95% range | F1 gain over the rules, 95% range |
| --- | --- | --- | --- | --- | --- |
| Current rules | 0.976 | 0.610 | 0.750 | 0.638–0.880 | n/a |
| Name similarity alone | 0.653 | 0.916 | 0.762 | 0.636–0.882 | −0.219 to +0.205 |
| Gradient boosting | 0.904 | 0.803 | 0.850 | 0.726–0.957 | −0.064 to +0.244 |
| **This model** | 0.781 | 0.897 | 0.835 | 0.714–0.945 | −0.116 to +0.262 |

**There is no clear winner.** The range for this model's gain includes zero. It finds more true matches than the
rules but makes many more false ones, and a simpler gradient boosting model scored about as well.

## Limits

- **India only.** It has only been checked on Indian places, in Saarthi's own categories, such as `restaurant`,
  `hotel` and `railway_station`. Other category names may behave unpredictably.
- **Mostly Latin script.** Only 0.7% of training names held non-ASCII characters, and the base model was trained
  on English. Don't expect names in Indian scripts to work well.
- **Hard pairs are missing from the results.** The 56 test pairs marked "unsure" (22%) were left out, so
  real-world accuracy may be lower.
- **A small test.** With 198 answers, the ranges are wide and can't separate close systems.
- **Inherited errors.** Mistakes in the rules used for training can carry into the model.
- **Older rules.** The comparison uses the rules as they were when the pairs were exported. The rules have since
  been changed, and their accuracy has not been measured again.
- **Names can mislead.** It sees no phone numbers, websites or addresses, so it can't use them to separate two
  branches of a chain.
- **Scores are not probabilities.** Balanced training pushes them away from real-world rates, which is why the
  cut-off is so low. Use them to rank, or with the stated cut-off.
- **Tested on one platform.** It has only been run on macOS on Apple Silicon, at 54 pairs per second on CPU and 66
  on the GPU. Linux has not been tested.

## How to use it

The weights need the Rust tool in the Saarthi repository. They won't load with `transformers`' `AutoModel`, because
the classifier and its extra inputs are custom. Rust is pinned by the tool's `rust-toolchain.toml`, and a first
build takes about 12 minutes.

```bash
hf download PrajwalAmte/saarthi-place-matcher --local-dir saarthi-place-matcher
git clone https://github.com/PrajwalAmte/Saarthi-Travel-Unfolded.git
cd Saarthi-Travel-Unfolded/ml/matching/train
cargo run --release -- predict --model ../../../../saarthi-place-matcher --input pairs.csv --output scores.csv --device cpu
```

The input is a CSV with `name_a`, `category_a`, `name_b`, `category_b` and `distance_m` in metres, and optionally
`pair_id`. The output has `pair_id`, `score` and `decision`.

```csv
pair_id,name_a,category_a,name_b,category_b,distance_m
1,OYO 47006 Hotel Lotus Grand,hotel,Hotel Lotus Grand,hotel,20
```
