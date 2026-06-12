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
- `objects/<cc>-<arch>-<opt>.o` — the committed corpus: {gcc, clang} ×
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

The corpus is **DWARF (`-g`) + non-trivial out-of-line ctors/dtors** for a
reason established by investigation (2026-06-11/12): a trivial-ctor /
non-DWARF corpus measures `DELETE`-only and cannot exercise the
type-resolving forms, because the decompiler then types receivers as
`undefined8` and inlines the idioms away. With `-g` + real ctors, receivers
are typed (`Base *`, not `undefined8`) and the ctor/dtor/cast idioms appear.

The investigation also caught a **real shipped bug**: `CppItaniumRttiAnalyzer`
gated `canAnalyze` on the compiler *metadata* string, which is `unknown` for
a relocatable `.o`, so the analyzer never ran and the type system stayed
empty — every type-resolving driver then declined for lack of a class to
resolve. The fix (format-only gate; `_ZTI` symbol presence is the real
filter) is what moved these numbers off zero.

What the current baseline shows (per cell, codegen-pinned):

- **`-O0`**: `CONSTRUCTION`, `DESTRUCTOR_CALL`, `DELETE`, `CAST` all fire
  (gcc additionally gets `PLACEMENT_CONSTRUCTION`). The fed hierarchy +
  typed receivers resolve.
- **`-O2`**: the type-resolving forms largely collapse (the optimizer
  inlines/folds the idioms), leaving `DELETE` + `CAST`. This is a real,
  measured codegen sensitivity — now a tracked column, not a surprise.
- **`VIRTUAL_CALL = 0` everywhere**: needs the `_ZTV` vtable leg
  (`#37-4b-4`, not yet built) to name a recovered slot — a `b->draw()`
  compiles to a `CALLIND` through the vtable the hierarchy feed cannot name.
- **`ARRAY_CONSTRUCTION = 0`**: the array-new idiom shape isn't recovered on
  this codegen; a documented follow-up.
- **`form_upcast` declines** (so `CAST` counts the downcast only): at `-O0`
  an upcast is a *bare* pointer-typed `PTRSUB` with no enclosing `CAST` op,
  which `CppBaseCastRecognizer` (matching `CAST(PTRSUB)`) doesn't catch — a
  recognizer-coverage gap, the documented next fix.

The absolute counts are **codegen-pinned tripwire values** (e.g.
`DESTRUCTOR_CALL=7` includes the ctors'/dtors' own internal destructor
calls), not semantic idiom counts — their job is to fail CI if the Itanium
feed or a recognizer regresses, locking in the gate-fix gain. The remaining
zeros (`VIRTUAL_CALL`, `ARRAY_CONSTRUCTION`, `form_upcast`) are the
well-characterized, tracked gaps. An MSVC PE column via the win11-ci box is
the parallel Windows move.

`DiagnoseCppHints.java` (`@category C++`) is the headless diagnostic that
drove this: it dumps the gate inputs, typeinfo symbols, fed type system, and
per-function p-code with recognizer verdicts — point it at any binary to see
why a form declines without a GUI.
