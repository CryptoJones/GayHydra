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

Zeros are data. Only `DELETE` fires (2/binary) because it classifies by the
callee *name* (`operator delete`); every other form needs the decompiler to
type the *receiver* as the class, and the fed hierarchy then resolves it.

### Why the Itanium-RTTI analyzer (shipped #37-4b-1..3) did NOT move these

Grounded by probe (2026-06-11), in two stacked reasons:

1. **No typed receiver.** These are unstripped but **non-DWARF** `.o`
   objects, so the decompiler types a `Base *` parameter as `undefined8` —
   the recognition drivers have no class name to resolve against the fed
   hierarchy. (`DELETE` is immune: it never looks at the receiver type.)
2. **The corpus source doesn't emit most idioms in an unlinked `.o`.** The
   classes have trivial/implicit constructors, so `new C()`'s ctor call is
   inlined away (nothing to fuse → CONSTRUCTION/ARRAY/PLACEMENT can't
   match); `operator new`/`operator delete` and the ctors are undefined
   externals an unlinked object never resolves. A `-g` rebuild probe
   confirmed reason 1 is not the only gate: the numbers stayed at
   `DELETE=2` even with DWARF, because of reason 2 plus —
3. **VIRTUAL_CALL additionally needs the `_ZTV` vtable leg** (`#37-4b-4`,
   not yet built) to map a recovered slot index to its method name; the
   hierarchy feed alone cannot name `param_1->draw()`.

### Sharper finding (2026-06-12 probe): even `-g` + non-trivial ctors stays `DELETE`-only

A second probe rebuilt the corpus with **DWARF (`-g`) *and* non-trivial
out-of-line constructors/destructors** (so receivers are typed *and* the
ctor/dtor calls are real, locally-resolvable functions). Recall **still
measured `DELETE`-only** — so the gate is deeper than receiver typing, and
it is **per-form**, each needing `HighFunction`/p-code-level investigation
rather than a corpus change:

- **`DESTRUCTOR_CALL`** — a `virtual ~Base()` compiles `b->~Base()` to a
  *vtable dispatch* (`CALLIND`), not the direct `CALL` the destructor
  recognizer matches. Only a *non-virtual* dtor is a direct call; a virtual
  one is really a virtual-call site and needs the `_ZTV` leg. (The extra
  `DELETE` hits the probe showed are the dtor's own internal `delete this`.)
- **`CONSTRUCTION` / `CAST`** — declined even with a typed receiver, a real
  local ctor, and the now-fed base-offset edge. Why needs reading the
  decompiled `HighFunction` (is the DWARF param type actually applied to the
  varnode? does the cast survive as the expected `PTRSUB`/`PTRADD` shape? is
  `operator new`'s extern `CALL` classified?) — not answerable by black-box
  recall counting.

**Conclusion:** moving ELF type-resolving recall is a **multi-recognizer
investigation best done attended** (with the decompiler output in hand),
plus the `_ZTV` vtable leg — not a single corpus or analyzer change, and
not tractable through ~40-minute black-box build probes. The shipped
Itanium RTTI leg (`#37-4b-1..3`) is correct, matrix-verified infrastructure
that this investigation will build on; it simply isn't *sufficient* alone.
The unlinked-`.o` corpus stays as the `DELETE`/regression tripwire; the
type-resolving columns are the tracked, now-well-characterized gap. An MSVC
PE column via the win11-ci box is the parallel Windows move.
