---
number: 0008
title: Rec 39 #39-6 — loop-shaped inlined-call detection is a post-structuring loop-region Action, not a constseq Rule
status: accepted
date: 2026-06-06
audit_rec: 39
---

# Decision 0008: loop-shaped idiom recognition rides a post-structuring loop-region matcher

## Context

[DD-0007](0007-rec39-phase2-inline-detection.md) split Rec 39 Phase 2's
inlined-library-call patterns into two tractability classes and disposed
of the easy one:

- **Sequence-shaped** idioms (`memset`, `popcount`) are an unrolled run
  of ops in a single basic block. They were landed as `Rule`s in
  `constseq.cc` running in the `actcleanup` pool — `RuleMemset` (#39-4a),
  `RulePopcount` (#39-4b) — reusing the proven `CPUI_CALLOTHER` →
  builtin-user-op rendering path. #39-5 then surveyed the wide-fill
  variants and found them already folding, shipping only a regression
  guard.
- **Loop-shaped** idioms (`strlen`, `strcmp` / `strncmp`, `memcmp`,
  non-constant `memcpy` / `memmove` copy-loops) are **not** op
  sequences — they are loops. DD-0007 decision point 3 deferred them to
  "their own sub-design ... a post-structuring loop-region matcher" once
  the sequence work had validated the builtin-rendering end-to-end.

The sequence work has now validated that path (two builtins folded, both
rendered with no printer change, both guarded by datatests). This DD is
that deferred sub-design. It records **where** a loop-region matcher
attaches in the decompiler pipeline, **why** it must be a control-flow
`Action` rather than a dataflow `Rule`, and the conservative recognition
discipline it inherits from DD-0007.

## Why the sequence mechanism does not reach these

`RuleStringStore` / `RuleMemset` walk a run of constant-valued
COPY/STORE ops **within one basic block** off one base pointer
(`ArraySequence` / `HeapSequence` in `constseq.cc`). A loop idiom has
none of that shape:

- The repetition is a **back-edge**, not an unrolled run — there is one
  static LOAD/STORE, executed N times, not N static ops.
- The trip count is usually **not constant** (`strlen` scans to a NUL it
  cannot see at compile time; a copy-loop's length is a parameter).
- The recognised "call" spans **multiple basic blocks** (header with the
  test + CBRANCH, body with the increment, a merge `MULTIEQUAL`), and its
  observable result is consumed *outside* the loop (e.g. `len = p_end -
  p_start`).

A single-block, constant-value sequence walker structurally cannot see
this. The loop must first be recognised *as a loop*, which the
decompiler already does — so the matcher should run **after** structuring
and reuse that work, not re-derive loop membership from raw dataflow.

## What the in-tree pipeline already provides (grounding)

### Block structuring and the for-loop precedent

Final block structuring runs as a sequence of `Action`s in
`universalAction` (`coreaction.cc`), strictly **after** the `actcleanup`
rule pool where the sequence-shaped rules live:

| order | item | `coreaction.cc` |
|---|---|---|
| 1 | `actcleanup` pool (`RuleMemset` :5885, `RulePopcount` :5886) | added at `:5894` |
| 2 | `ActionStructureTransform("blockrecovery", true)` — 1st `finalTransform`, op-moves allowed | `:5897` |
| 3 | `ActionBlockStructure` — (re)builds the block graph | `:5913` |
| 4 | `ActionStructureTransform("blockrecovery", false)` — 2nd `finalTransform`, no moves | `:5915` |
| 5 | `ActionFinalStructure` — `finalizePrinting()` refines the for-loop extraction | `:5922` |
| 6 | `ActionStop` | `:5924` |

By step 5 every loop is a fully-formed structured block and the
induction-variable analysis has already run. This is exactly the regime
the existing **for-loop** recognition exploits:
`BlockWhileDo::finalTransform` (`block.cc:3434`) converts a `whiledo`
into a `for` by locating the induction variable
(`findLoopVariable`, `block.cc:3241`) and extracting the initializer and
iterator statements (`findInitializer`, `block.cc:3300`;
`getInitializeOp` / `getIterateOp`, `block.hh:718`–`719`). A loop-idiom
matcher is the same *kind* of pass — read a structured loop region,
recover its induction variable, check a shape — just emitting a synthetic
call instead of a `for` header.

### The loop block types

`block.hh` gives the loop region as a typed block, so the matcher does
not walk raw CFG edges:

- `FlowBlock` (`block.hh:73`) → `BlockGraph` (`:372`) →
  `BlockWhileDo` (`:708`), `BlockDoWhile` (`:736`), `BlockInfLoop`
  (`:750`).
- For a `BlockWhileDo`: `getBlock(0)` is the condition block,
  `getBlock(1)` the body; the exit test is the condition block's
  `lastOp()` CBRANCH and its compared input.
- Body ops are walked with `BlockBasic::beginOp()` / `endOp()`
  (`block.hh:510`–`511`) — the same iteration `PrintC::emitForLoop`
  (`printc.cc:3250`) uses to emit a loop body.

### The CALLOTHER builtin rendering path (reused wholesale)

`userop.cc` already turns a builtin id into a named, typed call with no
printer change:

- Builtin ids (`userop.cc:30`–`36`): `BUILTIN_MEMCPY` `0x10000003`,
  `BUILTIN_STRNCPY` `0x10000004`, `BUILTIN_WCSNCPY` `0x10000005`,
  `BUILTIN_MEMSET` `0x10000006`. **Highest used id is `0x10000006`; the
  next free id is `0x10000007`.**
- `UserOpManage::registerBuiltin` (`userop.cc:433`) lazily constructs a
  `DatatypeUserOp` (`userop.hh:141`) — a display name plus a typed
  signature — for each id.
- `PrintC` renders a `CPUI_CALLOTHER` to such a builtin as
  `builtin_strlen(s)` through the ordinary user-op call path. As with
  `memset`/`memcpy`, **no printer change is needed** for a new builtin.

So the *output* end is solved; the whole of #39-6's novelty is the
**recognition + rewrite** of a structured loop region.

## What is genuinely missing

There is no loop-idiom recognition anywhere in the tree today (grep for
`strlen` / `strcmp` / `memcmp` in `cpp/` finds only the decompiler's own
libc usage, not detection). The patterns and their post-structuring
shapes:

| idiom | structured shape | result consumed as |
|---|---|---|
| `strlen` | `whiledo`; one induction **pointer** `p`; body LOADs `*p` (1 byte); exit when `*p == 0`; `p += 1` | `p_end - p_start` (length) |
| `strcmp` / `strncmp` | two induction pointers; LOAD both; exit on mismatch (and NUL for `strcmp`, or count for `strncmp`) | sign of the byte difference |
| `memcmp` | like `strcmp` but count-bounded, no NUL test | sign of the byte difference |
| `memcpy` / `memmove` (non-const) | induction pointer(s); body LOADs `*src`, STOREs `*dst`; count-bounded | `dst` / void |

(`RuleStringCopy` already folds the **constant-source** unrolled copy;
the loop / non-constant-source variants its `isConstant()` guard excludes
are the gap here.)

## The decision

1. **Recognition is a new control-flow `Action`, not a `constseq`
   `Rule`.** A `Rule` fires inside the `actcleanup` pool, *before* any
   block structuring, where the loop is still raw multi-block dataflow
   with no delimited region. Matching there would force the rule to
   re-derive loop membership the structurer computes anyway, and to clean
   up not-yet-collapsed loop blocks by hand. Instead add a new
   `Action` (working name `ActionLoopRecognize`) **after
   `ActionFinalStructure` (`coreaction.cc:5922`) and before `ActionStop`
   (`:5924`)**, where every loop is a typed `BlockWhileDo` /
   `BlockDoWhile` and `findLoopVariable` has already recovered the
   induction variable. The matcher reuses that structure rather than
   reconstructing it.

2. **Reuse the CALLOTHER builtin path; add one builtin per idiom.** New
   ids from `0x10000007`: `BUILTIN_STRLEN`, `BUILTIN_STRCMP`,
   `BUILTIN_STRNCMP`, `BUILTIN_MEMCMP`, each with a `DatatypeUserOp`
   signature in `registerBuiltin`; the non-constant copy-loop reuses the
   existing `BUILTIN_MEMCPY`. No printer change.

3. **`strlen` first, to validate the post-structuring rewrite surgery.**
   Replacing a structured loop region with a single CALLOTHER is a harder
   rewrite than the sequence rules' op-splice: the loop's ops must be
   removed, the CALLOTHER inserted, the merge `MULTIEQUAL`s repaired, and
   the result varnode retargeted. `strlen` is the smallest such case —
   **one** induction pointer, **one** live output (`end - start`), and
   **no** stores, so there is no memory-ordering side effect to preserve.
   Land the Action scaffold plus `strlen` alone first; only then take on
   the two-pointer (`strcmp`/`memcmp`) and store-bearing (copy-loop)
   cases, which add aliasing and ordering concerns.

4. **Conservative exactness + an option gate, inherited from DD-0007
   decision point 4 — but recognise only enumerated scalar lowerings.**
   Fire only on a fully-determined match (a false `strlen` is worse than
   none) and gate the whole pass behind an architecture option mirroring
   `analyze_for_loops` / the memset/popcount gate, so a distrustful user
   sees the raw loop. Crucially, loop idioms have **many** compiler
   lowerings — `-O0` byte-at-a-time, word-at-a-time with bit-twiddling
   NUL detection, `-O2` SSE `pcmpeqb` / `pcmpistri`. Recognise **only the
   canonical scalar byte-at-a-time shape** initially; the word- and
   vector-at-a-time lowerings are explicitly out of scope for the first
   set and revisited only if demand justifies the false-positive risk.
   This keeps each match exact and the rewrite auditable.

## Rejected alternatives

- **The XML pattern-library engine (original Phase 2 design).** Already
  rejected in DD-0007 and *more* so here: a generic IR-template matcher
  over multi-block loop regions is larger and less precise than a bespoke
  C++ matcher that reuses the structurer's induction-variable analysis.
  Inherited rejection.
- **A pre-structuring `Rule`.** Rejected per decision point 1: the loop
  region is not cleanly delimited before structuring, so the rule would
  duplicate loop-membership analysis and hand-collapse live loop blocks —
  fragile across optimiser variants. Matching after `ActionFinalStructure`
  reuses work already done.
- **Read-only annotation instead of a rewrite.** Same call as DD-0007
  (always-on rewrite + option gate, for consistency with the in-tree
  memcpy/memset path). Noted caveat: a structured-loop rewrite is more
  invasive than a sequence splice, which is *why* `strlen` goes first to
  prove the surgery before the harder idioms commit to it.
- **ML-based recognition.** Out of scope (training data, model hygiene,
  explainability) — inherited from DD-0007 and the original design.

## Validation

Each idiom ships a deterministic datatest under
`src/decompile/datatests/`, modelled on `heapmemset.xml` /
`memsetwide.xml`: a compiled inlined `strlen`/`strcmp`/… loop asserting
the rendered `builtin_strlen(...)` / `builtin_strcmp(...)` call appears,
plus a **negative** fixture (a near-miss loop that must *not* fold — e.g.
a scan that also stores, or a compare against a non-zero terminator).
Because only specific lowerings are recognised, the datatests must pin
the **exact** compiled shapes that fold *and* assert non-recognition of
adjacent shapes, so a future compiler-flag drift cannot silently widen or
break the match. Datatest XML stays pure ASCII
([[decompiler-datatest-gotchas]]); the suite runs in `decomp_test_dbg`
([[local-decomp-test-build-apple-silicon]]) and on CI's C++ unit-tests
job.

Gating reminders specific to #39-6 (each a hard local gate before push):

- The new `Action` `.cc`/`.hh` need a `cppRaiiAudit` PROTECTED_FILES
  entry **and** a Decompiler `certification.manifest` entry — verify
  `gradle cppRaiiAudit` and `gradle :Decompiler:ip`.
- Each new datatest `.xml` needs a `certification.manifest` entry —
  verify `gradle :Decompiler:ip`.
- Header-touching changes (`userop.hh` ids, the new `.hh`) require the
  full C++ unit-tests (`decomp_test_dbg`) green, not just the audit gate,
  before the next dependent PR stacks.

## Sequencing (refines `FOR_LOOP_INLINE_DETECTION.md` #39-6 / DD-0007 #39-6)

| PR | Scope |
|---|---|
| #39-6a | `ActionLoopRecognize` scaffold (post-`ActionFinalStructure` loop-region walker) + option gate + `BUILTIN_STRLEN` + `strlen` recognition (single induction pointer, byte LOAD, NUL-test exit, length = end − start) + positive/negative datatest — **reframed by the [2026-06-06 addendum](#addendum-2026-06-06-39-6a-strlen-folding-needs-new-loop-collapse-infrastructure): the rewrite is not a `constseq`-style splice but foundational loop-collapse infrastructure; deferred** |
| #39-6b | `strcmp` / `strncmp` / `memcmp` (two induction pointers, comparison loops) + builtins + datatests |
| #39-6c | non-constant `memcpy` / `memmove` copy-loops (LOAD+STORE body; reuse `BUILTIN_MEMCPY`) + datatest |
| #39-6d (opt) | absorb the DD-0007 [#39-5 multi-vector-fill residual](0007-rec39-phase2-inline-detection.md#addendum-2026-06-06-39-5-wide-fills-already-fold) if the region matcher's aggregate-store handling reaches it |

#39-6a is the load-bearing one: it must prove the post-structuring rewrite
is safe before b/c/d become incremental shapes on the same scaffold. The
[2026-06-06 addendum](#addendum-2026-06-06-39-6a-strlen-folding-needs-new-loop-collapse-infrastructure)
records the pre-implementation survey of that rewrite — it is not a
splice but new loop-collapse infrastructure, so #39-6a (and with it the
whole #39-6 phase) is deferred until that infrastructure is prioritised.

## Addendum (2026-06-06): #39-6a strlen folding needs new loop-collapse infrastructure

The decision above (point 3) sequenced `strlen` first precisely to
*discover* whether the post-structuring "rewrite surgery" is feasible
before the harder idioms commit to it. A pre-implementation survey of the
in-tree primitives did that discovery, and the answer reshapes the cost
model: **collapsing a recognised loop into a call is not a `constseq`-style
splice — it requires control-flow infrastructure the decompiler does not
have.**

Why the sequence-shaped rules were cheap and this is not. `RuleMemset` /
`RuleStringStore` replace a run of **straight-line** STOREs with a
`CPUI_CALLOTHER` (`constseq.cc` `transform()` → `buildMemset()` →
`removeStoreOps`). There is **no control-flow change** — the ops live in
one basic block, and removing them is local dataflow surgery. A `strlen`
loop is the opposite: a real CFG cycle with a data-dependent back-edge,
producing a value (`len = end − start`) consumed downstream.

What the survey found:

- **No loop-collapse primitive exists.** The CFG-mutation API on
  `Funcdata` removes only **do-nothing** blocks
  (`removeDoNothingBlock`, `funcdata.hh:572` — "a basic block ... that
  performs no operations") and **unreachable** blocks
  (`removeUnreachableBlocks`, `:573`), or **merges straight-line** blocks
  (`spliceBlockBasic`, `:582`). None removes a reachable, data-dependent
  loop. A grep of the whole `cpp/` tree finds no `removeLoop` /
  `collapseLoop` / `foldLoop` / region-replace-with-op of any kind.
- **Structuring nests loops, it never eliminates them.**
  `CollapseStructure` (`blockaction.hh:194`) collapses the raw CFG into a
  nested-block tree via `ruleBlockCat` / `ruleBlockOr` / `ruleBlockGoto`
  (`blockaction.cc:1320`/`:1357`/…) — a `whiledo` becomes a
  `BlockWhileDo`, it does not disappear.
- **The only in-tree precedent for idiomatic loop rendering annotates;
  it does not collapse.** `BlockWhileDo::finalTransform` stores
  `initializeOp` / `iterateOp` on the structured block, and
  `PrintC::emitForLoop` (`printc.cc:3250`, dispatched from
  `emitBlockWhileDo`, `:3300`) reads them — but **still emits the entire
  loop body** (`bl->getBlock(1)->emit(this)`, `printc.cc:3288`). It
  changes *rendering*, not *structure* or *dataflow*.

So both mechanisms DD-0008 weighed are large, novel subsystems for a
**value-producing** loop:

- *Collapse-rewrite* — must remove the recognised loop region from both
  the basic-block CFG (`bblocks`) and the structured tree, prove it
  side-effect-free (the body LOAD may fault), splice a `BUILTIN_STRLEN`
  CALLOTHER, and rewrite the loop-exit pointer `MULTIEQUAL` and the
  length output to consume it — all *after* `ActionFinalStructure`, with
  **no** re-structuring or dead-code pass following to repair the graph
  (`ActionStop` is next, `coreaction.cc:5924`). No primitive supports
  loop removal; this is new CFG infrastructure.
- *Annotate-and-print* (the for-loop precedent's mechanism) — cannot
  simply suppress the loop's emission, because the length varnode is
  *defined inside* the suppressed region. For-loops keep emitting the
  body, so their value definitions stay consistent; a print-as-call would
  reference a value whose defining ops it never emitted. Making that
  consistent needs a printer-level region-suppression + synthetic-call
  mechanism that also has no precedent.

**Decision (revises #39-6a, and the #39-6 phase's cost model):** the
loop-shaped folding does **not** ride the cheap CALLOTHER-splice path the
sequence-shaped phase (#39-4a/#39-4b) validated, and must not be attempted
as a quick `Rule`-like Action. #39-6a is reframed as **foundational
loop-region-collapse infrastructure** — new primitives to remove a
proven-side-effect-free recognised loop region and rewrite its live
outputs — which is a materially larger, regression-prone effort than the
sequence rules, for a readability-nicety payoff. It is **deferred**: if
and when loop-collapse is prioritised it opens with its own
*infrastructure* design (the primitives, the side-effect proof, the
graph-repair story), with `strlen` as the first client.

This leaves Rec 39 Phase 2's **shipped** value as the in-tree-mechanism-
compatible sequence-shaped folding — `memset` (#39-4a) and `popcount`
(#39-4b) — mirroring how the survey-first discipline already established
that #39-2/#39-3 (for-loops) are upstream-provided and #39-5 (wide fills)
already folds. The novel-but-tractable work that fit the proven mechanism
is done; the loop-shaped remainder needs a mechanism that does not yet
exist, and that build is its own decision, not a sprint rule.

## References

- `Ghidra/Features/Decompiler/src/decompile/cpp/coreaction.cc:5894`
  (`actcleanup` added — the sequence rules' pool), `:5897`/`:5913`/`:5915`
  (structure-transform passes), `:5922` (`ActionFinalStructure`), `:5924`
  (`ActionStop`) — the new Action attaches between `:5922` and `:5924`.
- `Ghidra/Features/Decompiler/src/decompile/cpp/block.cc:3434`
  (`BlockWhileDo::finalTransform`), `:3241` (`findLoopVariable`), `:3300`
  (`findInitializer`); `block.hh:73`/`:372`/`:708`/`:736`/`:750`
  (`FlowBlock`/`BlockGraph`/`BlockWhileDo`/`BlockDoWhile`/`BlockInfLoop`),
  `:510`–`:511` (`BlockBasic::beginOp`/`endOp`), `:718`–`:719`
  (`getInitializeOp`/`getIterateOp`).
- `Ghidra/Features/Decompiler/src/decompile/cpp/printc.cc:3250`
  (`emitForLoop` — the body-iteration pattern the matcher mirrors), `:3288`
  (still emits the full body), `:3300` (`emitBlockWhileDo` dispatch) — the
  addendum's evidence that idiomatic loop rendering annotates, not collapses.
- `Ghidra/Features/Decompiler/src/decompile/cpp/funcdata.hh:572`
  (`removeDoNothingBlock`), `:573` (`removeUnreachableBlocks`), `:582`
  (`spliceBlockBasic`); `blockaction.hh:194` (`CollapseStructure`),
  `blockaction.cc:1320`/`:1357` (`ruleBlockCat`/`ruleBlockOr`) — the
  addendum's evidence that no primitive collapses a live loop.
- `Ghidra/Features/Decompiler/src/decompile/cpp/userop.cc:30`–`36`
  (builtin ids; next free `0x10000007`), `:433`
  (`UserOpManage::registerBuiltin`); `userop.hh:141` (`DatatypeUserOp`).
- [DD-0007](0007-rec39-phase2-inline-detection.md) — the Phase 2 parent
  decision; this DD is its deferred decision-point-3 sub-design.
- `docs/decompiler/FOR_LOOP_INLINE_DETECTION.md` — Rec 39 design,
  motivation, and the #39-6 sequencing row.
