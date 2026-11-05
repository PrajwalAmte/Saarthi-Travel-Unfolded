#!/usr/bin/env bash
# Builds the release files for a saved model in data/release/. Uploads only when given --upload.
# Usage: ./publish.sh <model name> <tag> [--upload]     e.g. ./publish.sh matcher matcher-v0.1.0
set -euo pipefail

REPO=PrajwalAmte/saarthi-place-matcher
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
name="${1:?model name, such as matcher}"
tag="${2:?release tag, such as matcher-v0.1.0}"
upload="${3:-}"

model="$here/data/models/$name"
python3 -I -c "import json,sys; sys.exit(json.load(open(sys.argv[1]))['threshold'] is None)" "$model/manifest.json" \
  || { echo "This model has no threshold yet. Set one with the trainer's package command." >&2; exit 1; }

release="$here/data/release"
bundle="saarthi-place-$tag"
rm -rf "${release:?}/$bundle" "$release/$bundle.tar.gz" "$release/SHA256SUMS"
mkdir -p "$release/$bundle"
cp "$model/model.safetensors" "$model/tokenizer.json" "$model/manifest.json" "$root/LICENSE" "$release/$bundle/"
cp "$here/MODEL_CARD.md" "$release/$bundle/README.md"

cd "$release"
COPYFILE_DISABLE=1 tar -czf "$bundle.tar.gz" "$bundle"
shasum -a 256 "$bundle.tar.gz" "$bundle"/* > SHA256SUMS
echo "Built $release/$bundle.tar.gz and SHA256SUMS."

if [ "$upload" = "--upload" ]; then
  # Read as plain text: the file is never run, so a bad line can't print the token.
  HF_TOKEN="$(sed -n 's/^HF_TOKEN=//p' "$root/.env")"
  [ -n "$HF_TOKEN" ] || { echo "HF_TOKEN is missing from .env." >&2; exit 1; }
  export HF_TOKEN
  hf upload "$REPO" "$release/$bundle" . --commit-message "Publish $tag"
  echo "Uploaded to https://huggingface.co/$REPO. Its visibility has not changed."
else
  echo "Nothing was uploaded. Add --upload to send it to $REPO."
fi
