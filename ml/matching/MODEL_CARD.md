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

# Saarthi place matcher, v2

**Experimental, and not better than the rules it was compared with.** This model scores whether two map records from
India describe the same real place, from their names, categories and the distance between them. On 305 pairs checked
by hand after everything was fixed, it was clearly worse than the matching rules used by
[Saarthi](https://github.com/PrajwalAmte/Saarthi-Travel-Unfolded), which is why the live site does not use it.

| | |
| --- | --- |
| Base model | [sentence-transformers/all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2), revision `1110a243`, Apache-2.0 |
| Licence | Apache-2.0 for the weights and the code |
| Size | 90 MB of 32-bit weights |
| Decision cut-off | 0.046, chosen on other pairs before the test |
| Speed | 46 pairs per second on an Apple M1 CPU, measured on 320 pairs |

## Intended use

Use it to rank candidate duplicates for a person to review, or to compare against other approaches. It finds nearly
every true match, but at its cut-off about a third of the pairs it calls "same" are not. It must not merge places by
itself.

It is not meant for places outside India, for general entity matching, or for matching people or companies.

## How it was made

MiniLM's six encoder layers are fine-tuned as a cross-encoder. Each pair is read as `name (category)` for both sides. A
new classifier takes the first token's output plus two extra inputs: the log-scaled distance and whether the
categories are identical.

| Setting | Value |
| --- | --- |
| Steps and batch | 400 steps of 32 pairs, half of each batch from each class |
| Optimiser | AdamW, peak learning rate 5e-5, 10% warm-up, weight decay 0.01, gradient clipping 1.0 |
| Framework | [Burn](https://burn.dev) 0.22 in Rust, on an Apple GPU |

## Training data

The model trained on 21,257 pairs of a source record and a mapped place. **The training answers are decisions by
Saarthi's matching rules, not human labels**, so the model partly learns to copy those rules. 17,751 pairs were
"same" and 3,506 "different". The pairs held only names, categories, distance and the rule's decision, and are not
published with this model.

- The rules are those in the Saarthi repository as of commit `84e2931`, which re-decided each pair.
- 2,363 of the pairs are ones no rule had compared before (1,735 judged different, 628 same).
- Each rule's pairs were weighted, between 0.42 and 0.97, by how often that rule agreed with the author's answers on a
  separate set of pairs.
- Pairs that share a record, mapped place or name with either hand-checked sheet were left out of training.

| Source of the record | Pairs | Licence |
| --- | --- | --- |
| [Overture Maps](https://docs.overturemaps.org/attribution/) Places | 20,340 | By contributing dataset: CDLA Permissive 2.0, Apache-2.0 or CC0 |
| Protected monuments from [Wikidata](https://www.wikidata.org/wiki/Property:P1371) (ASI numbers) | 756 | CC0 1.0 |
| [OpenStreetMap](https://www.openstreetmap.org/copyright) | 161 | ODbL 1.0 |

The mapped place on the other side of a pair may also take its name from any of these sources.

Attribution: © OpenStreetMap contributors. © Overture Maps Foundation and its contributing datasets. Wikidata
statements are CC0.

The Apache-2.0 licence does not change the terms of that source data. This model has not had a legal review of
whether weights trained on ODbL names count as a derived database, so make your own assessment before relying on it
where that matters.

## Results

The test was a second sheet of 320 pairs, drawn after the first model was built and kept out of all training. The
author labelled it after the model, its cut-off and the rules were fixed, and nothing was changed afterwards. The
cut-off and every fitted combination came from a different, earlier set of pairs. 305 pairs got an answer (150 same,
155 different), and 15 marked "unsure" were left out.

The sheet was sampled in five groups, two of them pairs the rules rejected or had never compared, so each answer is
weighted to its group's real size. The ranges are 95% ranges from resampling.

| System | Precision | Recall | F1 | F1 range | F1 gain over the rules after the fixes |
| --- | --- | --- | --- | --- | --- |
| Rules before the fixes | 0.910 | 0.556 | 0.690 | 0.638–0.742 | −0.224 to −0.084 |
| Rules after the fixes | 0.798 | 0.903 | 0.848 | 0.793–0.898 | n/a |
| Gradient boosting, names only | 0.830 | 0.875 | 0.852 | 0.797–0.904 | −0.031 to +0.038 |
| Gradient boosting, names and contact details | 0.783 | 0.943 | 0.856 | 0.802–0.903 | −0.040 to +0.055 |
| **This model** | 0.636 | 0.978 | 0.771 | 0.710–0.828 | **−0.143 to −0.011** |
| This model, boosting and the rules combined | 0.785 | 0.934 | 0.853 | 0.799–0.901 | −0.020 to +0.034 |

- **The rules clearly beat this model.** The range for its gain over the rules is below zero.
- **The combination only ties the rules.** Its range includes zero. It is not part of this download.
- **The fixes to the rules were the real gain.** The same rules before and after the fixes gained 0.085 to 0.224 in F1
  on pairs nobody had tuned against.
- **Earlier figures were too good.** The same combination scored an F1 of 0.982 on the first sheet, which was used to
  design the fixes. Only the second sheet is a fair test.

## Limits

- **Low precision.** At its cut-off, 0.636 of the pairs it calls "same" are, on these pairs. A higher cut-off would
  trade recall for precision, but none was tuned on the second sheet.
- **India only.** It has only been checked on Indian places in Saarthi's own categories, such as `restaurant`, `hotel`
  and `railway_station`. Other category names may behave unpredictably.
- **Mostly Latin script.** Only 0.7% of training names held non-ASCII characters, and the base model was trained on
  English. Don't expect names in Indian scripts to work well.
- **Unsure pairs are missing.** 15 of 320 pairs were left out, so real-world accuracy may be lower.
- **A modest test.** With 305 answers the ranges are wide, and they can't separate close systems.
- **Inherited errors.** It trained on the rules' decisions, so their mistakes can carry into the model.
- **Names only.** It sees no phone numbers, websites or addresses, so it can't use them to separate two branches of a
  chain.
- **Scores are not probabilities.** Balanced training pushes them away from real-world rates, which is why the cut-off
  is so low.
- **Labels.** The cut-off and the rule weights came from the author's answers on the first sheet, about 30% of which
  were drafted by AI and then reviewed by the author. How reliable those labels are has not been measured.
- **Tested on one platform.** It has only been run on macOS on Apple Silicon. Linux has not been tested.

## How to use it

The weights need the Rust tool in the Saarthi repository. They won't load with `transformers`' `AutoModel`, because
the classifier and its extra inputs are custom. Rust is pinned by the tool's `rust-toolchain.toml`, and a first build
takes about 15 minutes.

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
