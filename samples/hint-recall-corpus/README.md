# hint-recall-corpus

The fixed measurement corpus for Rec 37 C++ hint **recall** (meta-review
2026-06-11, the "surfacing & measurement" band in SprintPlanning.md).

The Rec 37 pipeline is engineered never-wrong (precision), but until this
corpus existed its *recall* was unmeasured: every matcher is grounded on the
p-code one decompiler version emits for x86-64/one compiler's idioms, and an
upstream idiom change, a driver regression, or an analyzer-feed break would
lower recall silently — the unit suites pin fork fixtures, not the real
output distribution.

## What's here

- `corpus.cpp` — one call site per hint form (virtual call, heap new, array
  new, placement new, delete, explicit dtor, up/down-cast), each in its own
  function for attribution. Header-free so cross-compiles need no sysroot;
  deliberately unstripped (callee-name classification is part of the
  measured contract — stripped recall is a known zero, a future column).
- `build/<cc>-<arch>-<opt>.o` — the committed corpus: {gcc, clang} ×
  {x86_64, aarch64} × {O0, O2}. **The committed bytes are the corpus**, not
  the source: recall is pinned to exact codegen. `build.sh` regenerates them
  — that is a deliberate baseline-update event, never CI.
- `baseline.json` — per-binary per-form counts from
  `scripts/hint-recall.sh <ghidra-dir> --write-baseline`.

## How it runs

`scripts/hint-recall.sh <extracted-ghidra-dir>` imports each object
headlessly, runs `CountCppHintRecallScript` (decompile every function →
`CppHintsCollector.collect` → per-form counts), and compares against
`baseline.json`: a count **below** baseline fails (recall regression); a
count above prints an `IMPROVED` notice — lock it in with
`--write-baseline`. CI: the `master_smoke` job in
`.github/workflows/deep-ci.yml` runs the check against each night's master
build.

## Reading the numbers

Zeros are data. In particular: the production type-system analyzers
(`CppRttiAnalyzer`/`CppVTableAnalyzer`) gate on Visual Studio / Clang **PE**
binaries, so on these ELF objects most type-resolving drivers decline by
construction — the baseline quantifies exactly that gap, and closing it
(an Itanium-RTTI analyzer leg, an MSVC PE column via the win11-ci box) moves
the numbers up, locked in by baseline updates.
