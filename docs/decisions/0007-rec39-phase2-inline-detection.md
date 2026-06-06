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

2. **Sequence-shaped patterns first, as new `Rule`s.** Land `memset` and
   `popcount` as new `Rule`s in `constseq.cc` (or a sibling file). They
   diverge on *what they rewrite to*, because their rendering targets
   differ (see the [2026-06-06 addendum](#addendum-2026-06-06-popcount-folds-into-the-native-cpui_popcount-op)):
   - `memset` has **no** native p-code op, so it follows the
     memcpy/strncpy precedent — a new `BUILTIN_MEMSET` CALLOTHER builtin
     rendered through the user-op call path.
   - `popcount` **does** have a native op (`CPUI_POPCOUNT`), already
     typed/behaviour'd/printed, so its rule rewrites the recognised SWAR
     idiom into that op directly — **no new builtin**.

   Both slot into the existing `cleanup` rule pool in the decompile
   action group.

3. **Loop-shaped patterns are their own sub-design.** `strlen` /
   `strcmp` / `memcmp` / copy-loops need a post-structuring loop-region
   matcher; defer them to a follow-up DD once the sequence-shaped
   patterns have validated the builtin-rendering end-to-end. Do not
   block the tractable work on the hard work. *(Now landed:
   [DD-0008](0008-rec39-loop-region-matcher.md) — a new control-flow
   `Action` after `ActionFinalStructure`, not a `constseq` `Rule`.)*

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
rendered `builtin_memset(...)` / `POPCOUNT(...)` call appears) plus a
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
| #39-4b | `RulePopcount` SWAR-idiom rule folding into the **existing native** `CPUI_POPCOUNT` op — no new builtin (see [addendum](#addendum-2026-06-06-popcount-folds-into-the-native-cpui_popcount-op)) + datatest |
| #39-5 | ~~Tighten/extend the `memset` element-type coverage (word/dword fills, AVX-shaped fills)~~ — a pre-implementation survey found word/dword **and** single-vector fills **already fold** to `builtin_memset`; reframed (see [#39-5 addendum](#addendum-2026-06-06-39-5-wide-fills-already-fold)) to ship only a regression datatest (`memsetwide.xml`) and defer the one residual (≥2-vector-store fills) |
| #39-6 (sub-DD) | Loop-shaped patterns (`strlen`, `strcmp`/`strncmp`, `memcmp`, copy-loops): post-structuring loop-region matcher — **design landed at [DD-0008](0008-rec39-loop-region-matcher.md)**, re-sequenced into #39-6a..#39-6d (new control-flow `Action` after `ActionFinalStructure`, not a `constseq` `Rule`; `strlen` first) |
| #39-7 | Optional per-occurrence UI override (only if exactness ever relaxes) |

## Addendum (2026-06-06): popcount folds into the native `CPUI_POPCOUNT` op

The original sequencing row (above, now corrected) proposed a
`BUILTIN_POPCOUNT` CALLOTHER builtin for `#39-4b`, by analogy with the
`#39-4a` `BUILTIN_MEMSET` work. A pre-implementation survey of the
in-tree decompiler found that analogy is wrong: **popcount already has a
first-class native p-code op**, so minting a CALLOTHER builtin for it
would be redundant and *less* faithful than the existing path.

What already exists for `CPUI_POPCOUNT`:

- **The op itself** — `CPUI_POPCOUNT`, a unary op.
- **Type/printer** — `TypeOpPopcount` (`typeop.cc:2739`), a `TypeOpFunc`
  named `"POPCOUNT"`, so it renders as `POPCOUNT(x)` through the ordinary
  functional-operator path (`PrintC::opFunc`). No printer change needed.
- **Behaviour** — `OpBehaviorPopcount` (constant folding / emulation).
- **An existing simplification rule** — `RulePopcountBoolXor`
  (`ruleaction.cc:10279`) already *consumes* `CPUI_POPCOUNT`
  (`popcount(b1<<6 | b2<<2) & 1 => b1 ^ b2`). It does **not** *create*
  the op from raw arithmetic — that is exactly the remaining gap.

So `memset` and `popcount` are not symmetric:

| pattern | native p-code op? | `#39-4b`/`#39-4a` rewrite target |
|---|---|---|
| `memset` | no | new `BUILTIN_MEMSET` CALLOTHER user-op (the memcpy precedent) |
| `popcount` | **yes** (`CPUI_POPCOUNT`) | rewrite the SWAR idiom into that op — no new builtin |

**Decision:** `RulePopcount` recognises the constant-masked SWAR
("parallel bit-count") expansion — the
`x -= (x>>1)&0x55..; x = (x&0x33..)+((x>>2)&0x33..); x = (x+(x>>4))&0x0f..;`
`(x*0x0101..)>>(W-8)` dataflow shape, including the common
add-of-shifts variant of the final reduction — and rewrites the root to
a single `CPUI_POPCOUNT` whose input is the original operand. This reuses
the native type/behaviour/printer wholesale, keeps the existing
`RulePopcountBoolXor` simplification composable on top, and holds the
same exactness bar as the string/`memset` rules (fire only on a
fully-determined magic-constant match). The architecture option gate
(decision point 4) still applies. No `userop.cc` change is part of
`#39-4b`.

## Addendum (2026-06-06): #39-5 wide fills already fold

The original `#39-5` row anticipated that "word/dword fills" and
"AVX-shaped fills" would need new *element-type* coverage in
`RuleMemset`, by analogy with the char/non-char split of `#39-4a`. A
pre-implementation survey (probe shapes built at `-O1`/`-O2`, captured in
the `memsetwide.xml` regression datatest) found that analogy is wrong:
**the store width is already handled.**

`ArraySequence::formByteArray` decomposes each STORE's constant operand
into individual bytes and then requires every byte across the contiguous
region to be equal. The pointed-to element type only sets the *stride*;
it does not gate the *store width*. So a run of equal-valued stores
wider than the element already collapses correctly today:

| probe | shape | renders as |
|---|---|---|
| `qwfill` | 4× `mov qword [p+k], 0` (8-byte stores to `char*`) | `builtin_memset(p, 0, 0x20)` |
| `dwfill` | 4× `mov dword [p+k], 0` (4-byte stores to `char*`) | `builtin_memset(p, 0, 0x10)` |
| `xmmfill16` | 1× `movups [p], xmm0` (16-byte vector store of a `pxor`-zeroed reg) | `builtin_memset(p, 0, 0x10)` |
| `nearmiss` | 4× `mov qword [p+k], 0xff` (non-uniform byte pattern) | *not folded* (byte-wise writes) — correct |

The single genuine residual is the **multi-vector fill**: two or more
16/32-byte `movups`/`vmovups` stores (the `-O2` lowering of a ~32-byte+
`memset`). That does **not** fold, and renders as a mixed blob — part
per-byte assignment, part raw aggregate store:

```c
void xmmfill32(char *p) {            // 2× movups xmm0(=0)
  p[0] = '\0'; /* … p[1..0xf] = '\0' … */
  *(xunknown1 (*)[16])(p + 0x10) = (xunknown1 [16])0x0;
}
```

This is an **aggregate-store normalisation** problem (the 16-byte vector
STORE is left as a single aggregate-typed write, and the two halves are
even lowered inconsistently) — it sits *upstream* of `constseq`, not
inside its element-type handling. Extending `RuleMemset`'s element
coverage would not reach it; the STOREs never arrive as the byte/element
COPYs the sequence collector consumes.

**Decision:** `#39-5` ships only `memsetwide.xml`, a regression guard
that pins the already-correct word/dword/single-vector folding so a
future refactor cannot silently lose it. No `constseq.cc` change is part
of `#39-5`. The multi-vector residual is **deferred** — it is revisited
either as its own investigation into vector-aggregate STORE splitting, or
folded into the `#39-6` post-structuring region matcher, whichever lands
first. It is explicitly *not* asserted (positively or negatively) by the
datatest, so cementing the current messy rendering is avoided.

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
- `Ghidra/Features/Decompiler/src/decompile/cpp/typeop.cc:2739`
  (`TypeOpPopcount`, the `"POPCOUNT"` `TypeOpFunc`),
  `ruleaction.cc:10279` (`RulePopcountBoolXor`, consumes but does not
  create `CPUI_POPCOUNT`) — the native popcount support the addendum
  builds on.
- `docs/decompiler/FOR_LOOP_INLINE_DETECTION.md` — Rec 39 design + the
  Phase 1 upstream reframe.
- [DD-0006](0006-block-structure-budget-bypass.md) — the
  design-step-first pattern this DD follows.
</content>
