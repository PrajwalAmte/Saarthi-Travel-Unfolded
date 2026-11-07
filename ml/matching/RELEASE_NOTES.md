Second release of the Saarthi place matcher, a model that scores whether two map records from India describe the same real place, from their names, categories and distance.

**This is still experimental, and it is not better than the rules.** On 305 pairs checked by hand after everything was fixed, the model's F1 was 0.771 against 0.848 for Saarthi's current matching rules, and the range for its gain over them (−0.143 to −0.011) is below zero. The live site does not use it.

| System | Precision | Recall | F1 |
| --- | --- | --- | --- |
| Rules before the fixes | 0.910 | 0.556 | 0.690 |
| Rules after the fixes | 0.798 | 0.903 | 0.848 |
| This model | 0.636 | 0.978 | 0.771 |

The pairs are a second sheet, drawn after the first model was built and kept out of all training, with every cut-off fixed on other pairs beforehand. Results are weighted to each sampling group's real size, and 15 pairs marked "unsure" are left out.

## What changed since v0.1.0

- **Trained on the current rules.** The first model copied decisions made by older rules. This one copies the rules as fixed, including 2,363 pairs that no rule had compared before, with each rule weighted by how far it agreed with the author's answers on a separate set.
- **Tested fairly.** The first model's results came from pairs that were later used to design rule fixes. The comparison above is on pairs nobody had tuned against. Version 0.1.0 was not measured on them, because it had seen similar pairs in training, so this release makes no claim to improve on it.
- **The rule fixes helped.** The same rules scored 0.690 before the fixes and 0.848 after them on these pairs. That gain lives in the Saarthi code, not in this model.

## What's included

- `saarthi-place-matcher-v0.2.0.tar.gz`: weights, tokenizer, manifest (cut-off 0.046), model card and licence
- `SHA256SUMS`: checksums for the archive and each file in it

The same model is on [Hugging Face](https://huggingface.co/PrajwalAmte/saarthi-place-matcher). See the model card there for the training data, its attribution and the limits.

## Use it

```bash
shasum -a 256 -c SHA256SUMS
tar -xzf saarthi-place-matcher-v0.2.0.tar.gz
```

Then, from `ml/matching/train` in this repository:

```bash
cargo run --release -- predict --model /path/to/saarthi-place-matcher-v0.2.0 --input pairs.csv --output scores.csv --device cpu
```

The input is a CSV with `name_a`, `category_a`, `name_b`, `category_b` and `distance_m`. It has only been tested on macOS on Apple Silicon. A first build takes about 15 minutes.

## Licence

Apache-2.0, based on [all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2). Trained using data from Overture Maps (© Overture Maps Foundation and its contributing datasets), OpenStreetMap (© OpenStreetMap contributors) and Wikidata. That data keeps its own licences and is not included.
