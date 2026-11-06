#!/usr/bin/env bash
# Decides every pair in data/pairs_full.csv with Saarthi's matching rules, written to data/pairs_decided.csv.
# With a git ref, uses the rules as they were at that commit (read-only) and writes pairs_decided_<ref>.csv,
# which shows whether this program reproduces the rules' own past decisions.
# Usage: ./redecide/run.sh [git-ref]
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
root="$(cd "$here/../.." && pwd)"
poi=backend/src/main/java/io/destinationintel/poi
ref="${1:-}"

work="$(mktemp -d)"
trap 'rm -rf "${work:?}"' EXIT
src="$work/src/io/destinationintel/poi"
mkdir -p "$src" "$work/classes"
for file in MatchEvidence PoiCategories; do
  if [ -n "$ref" ]; then git -C "$root" show "$ref:$poi/$file.java" > "$src/$file.java"; else cp "$root/$poi/$file.java" "$src/"; fi
done
cp "$here/redecide/Redecide.java" "$src/"
javac -d "$work/classes" "$src"/*.java

out="$here/data/pairs_decided.csv"
[ -n "$ref" ] && out="$here/data/pairs_decided_$(echo "$ref" | tr -c 'A-Za-z0-9\n' '_').csv"
java -cp "$work/classes" io.destinationintel.poi.Redecide "$here/data/pairs_full.csv" "$out"
echo "Decided $(($(wc -l < "$out") - 1)) pairs: $out"
