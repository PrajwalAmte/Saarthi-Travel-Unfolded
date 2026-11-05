First release of the Saarthi place matcher: a model that scores whether two map records from India describe the same real place, from their names, categories and distance.

**This is experimental, and the live site doesn't use it.** On 198 labelled test pairs it did not clearly beat the current rules, because the 95% range for its F1 gain (−0.116 to +0.262) includes zero.

| System | Precision | Recall | F1 |
| --- | --- | --- | --- |
| Current rules | 0.976 | 0.610 | 0.750 |
| This model | 0.781 | 0.897 | 0.835 |

The labels are the author's, with about 30% drafted by AI and then reviewed. Their reliability has not been measured. Results are weighted to each rule's real size.

## What's included

- `saarthi-place-matcher-v0.1.0.tar.gz`: weights, tokenizer, manifest (cut-off 0.154), model card and licence
- `SHA256SUMS`: checksums for the archive and each file in it

The same model is on [Hugging Face](https://huggingface.co/PrajwalAmte/saarthi-place-matcher). See the model card there for the training data, its attribution and the limits.

## Use it

```bash
shasum -a 256 -c SHA256SUMS
tar -xzf saarthi-place-matcher-v0.1.0.tar.gz
```

Then, from `ml/matching/train` in this repository:

```bash
cargo run --release -- predict --model /path/to/saarthi-place-matcher-v0.1.0 --input pairs.csv --output scores.csv --device cpu
```

The input is a CSV with `name_a`, `category_a`, `name_b`, `category_b` and `distance_m`. It has only been tested on macOS on Apple Silicon. A first build takes about 12 minutes.

## Licence

Apache-2.0, based on [all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2). Trained using data from Overture Maps (© Overture Maps Foundation and its contributing datasets), OpenStreetMap (© OpenStreetMap contributors) and Wikidata. That data keeps its own licences and is not included.
