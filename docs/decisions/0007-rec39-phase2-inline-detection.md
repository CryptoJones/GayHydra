---
number: 0007
title: Rec 39 Phase 2 — inlined-library-call detection extends constseq + builtin userops, not a new pattern-library engine
status: accepted
date: 2026-06-03
audit_rec: 39
---

# Decision 0007: inlined-function detection rides the existing CALLOTHER builtin mechanism

## Context

Rec 39 Phase 2 (`FOR_LOOP_INLINE_DETECTION.md`) wants the decompiler
to recognise inlined library calls — a byte/word copy loop is
`memcpy`, a constant fill is `memset`, a scan-for-NUL is `strlen` — and
render the recognised range as a call rather than the raw inlined code.
The original design proposed a **new** subsystem: an
`InlinedFunctionPattern` analysis pass driving a data-driven **pattern
library** of XML templates under
`Ghidra/Features/Decompiler/data/inline-patterns/*.xml`, loaded at
startup, with a read-only annotation layer the printer consumes.

Phase 1 of the same rec turned out to be already provided by upstream
([the for-loop reframe](../decompiler/FOR_LOOP_INLINE_DETECTION.md#phase-1-status-2026-06-03-already-provided-by-upstream)).
A survey for Phase 2 found the same thing **partially**: upstream
already has the exact "render an inlined sequence as a library call"
mechanism this rec wants — it is just narrower than Phase 2's full
ambition. This DD records what exists, what is genuinely missing, and
the decision to **extend the existing mechanism rather than build the
proposed pattern-library engine**.

## What upstream already provides

The decompiler renders an inlined operation as a named call through a
**`CPUI_CALLOTHER` op pointing at a builtin user-op**:

- **Builtin user-ops** (`userop.cc`): `BUILTIN_MEMCPY` (`0x10000003`),
  `BUILTIN_STRNCPY` (`0x10000004`), `BUILTIN_WCSNCPY` (`0x10000005`).
  `UserOpManage::registerBuiltin(id)` (`userop.cc:53`) lazily creates a
  `DatatypeUserOp("builtin_memcpy", glb, id, ptrType, ptrType, ptrType,
  intType)` — a user-op with a display name and a typed signature.
- **Detection rules** (`constseq.cc`): `RuleStringCopy` (root
  `CPUI_COPY`, `constseq.cc:981`) and `RuleStringStore` (root
  `CPUI_STORE`, `constseq.cc:1013`) detect a sequence of single-character
  COPY/STORE ops in one basic block off one base pointer that spells a
  constant string, and replace the whole sequence with one
  `CPUI_CALLOTHER` to the memcpy/strncpy/wcsncpy builtin
  (`StringSequence::buildStringCopy` / `HeapSequence::buildStringCopy`,
  `constseq.cc:367` / `:721`).
- **Emission**: `PrintC` already renders a `CPUI_CALLOTHER` to a builtin
  user-op as `builtin_memcpy(dst, src, n)` via the normal user-op call
  path — no printer change is needed for a new builtin.

So "detect an inlined sequence → emit a synthetic library call" is a
**solved, in-tree pattern**. It is narrow by design: both rules are
gated on `isCharPrint()` (string data only), require a constant
source/value, and match an unrolled op *sequence* in a single block —
not a loop.

## What is genuinely missing (the real Phase 2 gap)

The patterns Phase 2 named that upstream does **not** cover, split by
how hard they are to detect:

**Sequence-shaped (tractable — direct `constseq`-style extension):**

- **`memset`** — a constant fill of a non-`char` buffer (e.g. zeroing a
  struct), or a fill whose element type isn't char-printable, so
  `RuleStringStore` skips it. Same single-block, same-base-pointer,
  constant-value sequence shape `RuleStringStore` already walks; the
  gap is the char-only guard and the lack of a `BUILTIN_MEMSET`.
- **`popcount`** — the GCC/LLVM bit-twiddling expansion is a fixed
  dataflow shape (a SUBPIECE/AND/MULT/SHIFT idiom), matched on the op
  graph, not a loop. A self-contained dataflow-pattern rule.

**Loop-shaped (harder — needs post-structuring loop recognition):**

- **`strlen`** — scan-for-NUL loop. Loop-based, not an op sequence.
- **`strcmp` / `strncmp` / `memcmp`** — comparison loops.
- **non-constant `memcpy` / `memmove`** — copy *loops* (and copies from
  a non-constant source, which `RuleStringCopy`'s `isConstant()` guard
  excludes).

The loop-shaped patterns cannot reuse the `constseq` single-block
sequence walker; they need a matcher that runs **after loop structuring**
(`blockaction.cc`) over a recognised loop region — closer in spirit to
the for-loop detector (`BlockWhileDo::finalTransform`) than to
`RuleStringCopy`. They are a separate, harder sub-problem.

## The decision

1. **Extend the existing `constseq` + builtin-user-op (CALLOTHER)
   mechanism. Do not build the proposed XML pattern-library engine.**
   The audit's "pattern library is data, not hardcoded" goal is not
   worth its cost here: a startup-loaded XML template language plus a
   generic IR-matching engine is a large new subsystem, whereas each
   missing pattern is a ~100-line `Rule` modelled on `RuleStringStore`
   plus one `registerBuiltin` case. The CALLOTHER/builtin rendering path
   is already proven and needs no printer work. A bespoke C++ rule per
   pattern is also *more* precise than a generic template matcher —
   precision is the whole point (a false `memset` is worse than none).

2. **Sequence-shaped patterns first, as `constseq` rules.** Land
   `memset` and `popcount` as new `Rule`s in `constseq.cc` (or a sibling
   file) plus `BUILTIN_MEMSET` / `BUILTIN_POPCOUNT` builtins. These slot
   directly into the existing single-block matcher and the existing
   `cleanup` rule pool in the decompile action group.

3. **Loop-shaped patterns are their own sub-design.** `strlen` /
   `strcmp` / `memcmp` / copy-loops need a post-structuring loop-region
   matcher; defer them to a follow-up DD once the sequence-shaped
   patterns have validated the builtin-rendering end-to-end. Do not
   block the tractable work on the hard work.

4. **Keep it an always-on rewrite gated by an option — not the design's
   read-only opt-in annotation.** The original design wanted a read-only
   annotation with per-occurrence UI override, to avoid "Ghidra
   confidently shows the wrong thing." But the existing memcpy/strncpy
   path already *rewrites* the IR (replaces the sequence with a
   CALLOTHER, `opDestroy`s the originals) and users accept it, because an
   exact constant-sequence match is unambiguous — a 16-byte constant
   fill **is** `memset`. New patterns hold to the same exactness bar
   (only fire on a fully-determined match), so they can follow the same
   rewrite path for consistency and simplicity. Risk is bounded by an
   architecture option (mirroring `analyze_for_loops` /
   `OptionForLoops`) so a user who distrusts the recognition can turn it
   off and see the raw ops. The heavier per-occurrence UI override
   (#39-7) becomes optional polish, not a prerequisite.

## Rejected alternatives

- **The XML pattern-library engine (original Phase 2 design).**
  Rejected as over-engineering: a new template language + generic
  matcher + startup loader is far more code and a worse precision/false-
  positive profile than per-pattern C++ rules, for no benefit the
  in-tree CALLOTHER mechanism doesn't already give. Reconsider only if
  the pattern count grows past the point where bespoke rules don't
  scale — not at five patterns.

- **Read-only annotation layer instead of a rewrite.** Rejected for
  consistency: upstream already rewrites inlined memcpy to a CALLOTHER;
  adding a second, parallel "annotate but don't rewrite" path for the
  new patterns would fork the mechanism. The option-gate covers the
  trust concern. (If a future pattern is *fuzzy* rather than exact, that
  pattern can revisit read-only display — but the initial set is exact.)

- **ML-based recognition.** Out of scope (training data, model hygiene,
  explainability) — unchanged from the original design's own rejection.

## Validation

Each new pattern ships a deterministic datatest under
`src/decompile/datatests/` modelled on the existing string-copy
fixtures (an inlined `memset`/`popcount` sequence, asserting the
rendered `builtin_memset(...)` / `popcount(...)` call appears) plus a
negative fixture (a near-miss that must *not* be folded). These run in
`decomp_test_dbg` (locally buildable per
[[local-decomp-test-build-apple-silicon]] — note datatest XML must stay
pure ASCII, [[decompiler-datatest-gotchas]]) and on CI's C++ unit-tests
job. The always-on-with-option design means existing datatests guard
against regressions in the unbudgeted/normal path.

## Sequencing (revises `FOR_LOOP_INLINE_DETECTION.md` Phase 2 rows)

| PR | Scope |
|---|---|
| #39-4a | `BUILTIN_MEMSET` builtin + `RuleMemset` (non-char constant fill) in `constseq.cc` + option gate + datatest |
| #39-4b | `BUILTIN_POPCOUNT` builtin + `RulePopcount` dataflow-idiom rule + datatest |
| #39-5 | Tighten/extend the `memset` element-type coverage (word/dword fills, AVX-shaped fills) |
| #39-6 (sub-DD) | Loop-shaped patterns (`strlen`, `strcmp`/`strncmp`, `memcmp`, copy-loops): post-structuring loop-region matcher — opens with its own design record |
| #39-7 | Optional per-occurrence UI override (only if exactness ever relaxes) |

## References

- `Ghidra/Features/Decompiler/src/decompile/cpp/constseq.cc:975`
  (`RuleStringCopy`), `:1007` (`RuleStringStore`), `:367`/`:721`
  (`buildStringCopy`).
- `Ghidra/Features/Decompiler/src/decompile/cpp/userop.cc:30-35`
  (builtin IDs), `:53` (`registerBuiltin`), `:77` (`DatatypeUserOp`
  construction).
- `Ghidra/Features/Decompiler/src/decompile/cpp/coreaction.cc` — the
  `cleanup` rule pool in `ActionDatabase::buildDefaultGroups` where the
  string rules live and the new rules attach.
- `docs/decompiler/FOR_LOOP_INLINE_DETECTION.md` — Rec 39 design + the
  Phase 1 upstream reframe.
- [DD-0006](0006-block-structure-budget-bypass.md) — the
  design-step-first pattern this DD follows.
</content>
