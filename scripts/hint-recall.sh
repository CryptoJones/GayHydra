#!/usr/bin/env bash
# Hint-recall corpus runner (meta-review 2026-06-11, Tier 3; the measurement
# half of samples/hint-recall-corpus/). Runs CountCppHintRecallScript over
# every committed corpus object with a built Ghidra and compares per-form
# counts against the committed baseline.
#
# Usage:
#   scripts/hint-recall.sh <ghidra-install-dir> [--write-baseline]
#
# <ghidra-install-dir> is an extracted distribution (the dir holding
# support/analyzeHeadless). With --write-baseline the observed counts
# overwrite samples/hint-recall-corpus/baseline.json (a deliberate
# baseline-update event); otherwise any count BELOW baseline fails (recall
# regression) and any count above prints a notice to update the baseline.

set -euo pipefail

GHIDRA=${1:?usage: hint-recall.sh <ghidra-install-dir> [--write-baseline]}
MODE=${2:-check}
cd "$(git rev-parse --show-toplevel)"

CORPUS=samples/hint-recall-corpus
BASELINE=$CORPUS/baseline.json
HEADLESS=$GHIDRA/support/analyzeHeadless
test -x "$HEADLESS" || { echo "no analyzeHeadless at $HEADLESS" >&2; exit 2; }

RESULTS=$(mktemp)
trap 'rm -f "$RESULTS"' EXIT

for obj in "$CORPUS"/objects/*.o; do
  name=$(basename "$obj" .o)
  PROJECT_DIR=$(mktemp -d)
  t0=$(date +%s%3N)
  OUT=$("$HEADLESS" "$PROJECT_DIR" RecallRE \
    -import "$obj" \
    -postScript CountCppHintRecallScript.java \
    -scriptPath "$(pwd)/Ghidra/Features/Base/ghidra_scripts" \
    -deleteProject 2>&1) || {
      echo "$OUT" | tail -40
      echo "analyzeHeadless failed on $name" >&2
      exit 2
    }
  t1=$(date +%s%3N)
  line=$(echo "$OUT" | grep -oE 'RECALL( [A-Z_]+=[0-9]+)+ TOTAL=[0-9]+' | tail -1)
  test -n "$line" || { echo "$OUT" | tail -40; echo "no RECALL line for $name" >&2; exit 2; }
  echo "$name $line"
  # Perf is published, not gated (meta-review Tier 3 item 10 v1): shared-CI
  # wall-time variance is unmeasured, so a hard threshold would flake. The
  # PERF lines accumulate trend data (job logs / step summary); a gate comes
  # once variance is known. Time covers import + auto-analysis + the
  # all-functions decompile pass — the user-visible decompiler path.
  echo "PERF $name elapsed_ms=$((t1 - t0))"
  echo "$name ${line#RECALL }" >> "$RESULTS"
  rm -rf "$PROJECT_DIR"
done

python3 - "$BASELINE" "$RESULTS" "$MODE" <<'EOF'
import json, sys

baseline_path, results_path, mode = sys.argv[1], sys.argv[2], sys.argv[3]

observed = {}
with open(results_path) as f:
    for raw in f:
        parts = raw.split()
        observed[parts[0]] = {k: int(v) for k, v in (p.split("=") for p in parts[1:])}

if mode == "--write-baseline":
    with open(baseline_path, "w") as f:
        json.dump(observed, f, indent=2, sort_keys=True)
        f.write("\n")
    print(f"baseline written: {baseline_path}")
    sys.exit(0)

with open(baseline_path) as f:
    baseline = json.load(f)

failures, improvements = [], []
for name, base_counts in sorted(baseline.items()):
    obs = observed.get(name)
    if obs is None:
        failures.append(f"{name}: missing from run (corpus object not analyzed)")
        continue
    for form, base in sorted(base_counts.items()):
        got = obs.get(form, 0)
        if got < base:
            failures.append(f"{name}: {form} dropped {base} -> {got}")
        elif got > base:
            improvements.append(f"{name}: {form} improved {base} -> {got}")

for line in improvements:
    print(f"IMPROVED {line} — re-run with --write-baseline to lock it in")
if failures:
    print("RECALL REGRESSION:")
    for line in failures:
        print(f"  {line}")
    sys.exit(1)
print("hint-recall: no regressions vs baseline")
EOF
