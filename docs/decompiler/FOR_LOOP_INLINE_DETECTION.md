# Detecting `for` Loops and Inlined Functions

*Addresses Rec 39 of the 2026-05-21 principal-architect audit, tracking
upstream issues [#644](https://github.com/NationalSecurityAgency/ghidra/issues/644),
[#2376](https://github.com/NationalSecurityAgency/ghidra/issues/2376), and
[#4461](https://github.com/NationalSecurityAgency/ghidra/issues/4461).*

## The complaint

The most common "Ghidra looks worse than IDA" comment is variants of:

```c
// What Ghidra produces:
iVar1 = 0;
do {
    process(buf + iVar1);
    iVar1 += 1;
} while (iVar1 < 16);

// What IDA produces:
for (int i = 0; i < 16; ++i) {
    process(buf + i);
}
```

Both decompilations are *correct*. The Ghidra version exposes the
induction variable as a separate counter; the IDA version recognises
the `for`-loop idiom and renders it idiomatically. The information
content is identical; the readability is not.

The same shape applies to inlined functions: IDA recognises a recurring
basic block pattern as "this looks like `memcpy`" and labels it;
Ghidra emits the pattern inline as raw code.

## Why this matters

A user's eye scans 30 lines of decompilation in seconds when the
shapes are conventional. The same 30 lines with raw counter/comparison
idioms take minutes to read. For binaries with thousands of functions,
this is the difference between a half-day and a half-week of analysis.

The audit named these two as the single most common quality complaint
about Ghidra's decompiler output.

## Design

### Phase 1: `for`-loop detection

#### What we are matching

A `for` loop in the IR has a specific shape:

```
init:
    iv = init_value
loop_header:
    if iv >= bound goto exit
    body
    iv = iv + step
    goto loop_header
exit:
```

The induction variable `iv`:

- Has a definition at `init`.
- Has a single back-edge update at the end of the body (`iv = iv + step`).
- Is the only varnode in the loop's exit condition.

These three constraints together identify the canonical
`for`-loop shape. Compiler optimisation produces a wider variety,
but the canonical shape covers a high majority of real cases.

#### What we are NOT matching

- **Unrolled loops.** A `for` that was unrolled at compile time
  doesn't look like a loop in IR. Detecting "this is unrolled" is
  a separate (harder) problem.
- **Loops with multiple induction variables.** Common in vectorised
  code. Out of scope; Stage 2 if we tackle it.
- **`while`-with-trailing-update.** Genuinely the wrong shape for a
  `for`; renders as `while`, not `for`.

#### Where this lives

A new analysis pass `ForLoopPattern` runs in the decompiler after
loop-structure analysis (`blockaction.cc`) and before output
emission. The pass:

1. Walks each loop region.
2. Identifies the induction variable by the three constraints above.
3. If the constraints hold, annotates the region with a
   `ForLoopShape` record: `(iv_varnode, init_value, bound,
   step, body_starts_at)`.
4. Output emission consumes the annotation and renders `for (...)`.

The pass is *additive*: if it doesn't recognise a loop, the
existing `while` output is unchanged.

### Phase 2: Inlined-function detection

A separate analysis pass `InlinedFunctionPattern` runs after
function-boundary detection. It matches blocks of code against a
**pattern library**:

- `memcpy` / `memmove` (byte-by-byte, word-by-word, AVX
  variants).
- `memset`.
- `strlen`.
- `strcmp` / `strncmp`.
- `memcmp`.
- Optimiser-produced common patterns (e.g., GCC's
  `__builtin_popcount` expansion).

Each pattern is described by:

- A normalised IR template (after some canonicalisation).
- The signature of the "as-if" call.
- A confidence threshold.

A match labels the basic-block range with a synthetic
`InlinedCall` annotation. Output emission renders the range as a
call expression rather than the underlying instructions.

The pattern library is data — not hardcoded. Patterns live under
`Ghidra/Features/Decompiler/data/inline-patterns/*.xml` and are
loaded at decompiler startup.

#### Conservative matching

Inlined-function recognition is *opt-in display*. If the user
hovers a recognised inline, they see "this was identified as
`memcpy(dst, src, 16)` — show as inline call". The user can
override per-occurrence.

This avoids the "Ghidra confidently shows the wrong thing" failure
mode that worried the maintainers when this was proposed before:
the user always has the underlying code one click away.

## Drawbacks

- **More analysis time** (~5–15% per function). Mitigated by the
  per-function cache (Rec 36).
- **Pattern false positives.** Mitigation: confidence thresholds;
  override path; pattern library is reviewable XML.
- **Maintenance burden of the pattern library.** Mitigation: the
  library starts small (5 patterns); growth is opt-in.

## Alternatives

- **Don't do this.** Status quo. Status quo is the single most
  common complaint about Ghidra.
- **Hard-code patterns in C++.** Less flexible; harder to extend.
- **Train an ML model.** Possible but: training data, model
  hygiene, and explainability all become maintenance debt the
  audit's #11 (security) governance doesn't want to take on
  right now.

The pattern-library approach is the smallest design that solves
the user-visible problem.

## Phase 1 status (2026-06-03): already provided by upstream

Before implementing #39-2, a survey of the tree found that **canonical
`for`-loop detection already exists in upstream Ghidra and is present in
this fork**, so #39-2 and #39-3 are already satisfied and reimplementing
a separate `ForLoopPattern` pass would be wasted, regression-prone
duplication. The evidence:

- **Detection + transform.** `BlockWhileDo::finalTransform`
  (`block.cc:3434`) converts a `whiledo` loop into a `for` loop: it
  finds the induction variable (`findLoopVariable`), extracts the
  iterator statement from the loop tail and the initializer from the
  head block (`findInitializer`), and stores them on the `BlockWhileDo`.
  It is gated by the `analyze_for_loops` architecture flag
  (`architecture.hh:180`, default **on**;
  `OptionForLoops` / `analyzeforloops`). This is the same canonical shape
  this doc's Phase 1 describes - induction variable with an init, a
  single back-edge update, and the loop's exit condition - just realised
  as a `whiledo` -> `for` transform at `finalTransform` time rather than
  as a standalone "ForLoopPattern" pass.
- **Emission.** `PrintC::emitForLoop` (`printc.cc:3250`, dispatched from
  `printc.cc:3301`) renders the recognised loop as `for (init; cond;
  iterate)`.
- **Test corpus (#39-3).** Upstream's "New combined decompiler testing
  framework" ships positive fixtures `forloop1.xml`, `forloop_varused.xml`,
  `forloop_withskip.xml`, `forloop_loaditer.xml`, `forloop_thruspecial.xml`
  and negative fixtures `noforloop_globcall.xml`, `noforloop_iterused.xml`,
  `noforloop_alias.xml` under `src/decompile/datatests/`. All pass in this
  fork's build (verified 2026-06-03), so the canonical-detection contract
  #39-3 asks for is already covered and guarded against regression.

The "What we are NOT matching" cases above (unrolled loops, multiple
induction variables, while-with-trailing-update) remain out of scope and
are *also* not handled by upstream - consistent with this doc.

**Net:** Phase 1 (#39-2, #39-3) is **complete via upstream**; no fork
implementation is needed. The fork's remaining Rec 39 value is entirely
**Phase 2 - inlined-function detection** (#39-4+), which upstream does
*not* provide and which is the genuinely novel work. Phase 2 should open
with its own design-decision record (the pattern-library matching engine,
annotation layer, and conservative opt-in display) before implementation,
following the project's design-step-first pattern.

## Sequencing

| PR | Scope | Status |
|---|---|---|
| #39-1 | This design doc | done |
| #39-2 | `ForLoopPattern` analysis pass + output emission | **provided by upstream** (`BlockWhileDo::finalTransform` + `emitForLoop`); no fork work needed - see "Phase 1 status" above |
| #39-3 | `for`-loop unit tests on the existing decompiler datatest corpus | **provided by upstream** (`forloop*.xml` / `noforloop*.xml`, verified passing) |
| #39-4 | `InlinedFunctionPattern` analysis pass + pattern-library loader | **design landed at [DD-0007](../decisions/0007-rec39-phase2-inline-detection.md)** — *revised*: extend the existing `constseq` + builtin-user-op (CALLOTHER) mechanism that already renders inlined memcpy/strncpy, **not** a new XML pattern-library engine. Re-sequenced below. |
| #39-4a | `BUILTIN_MEMSET` + `RuleMemset` (constant fill -> memset) + datatest | **done** — `RuleMemset` reuses `HeapSequence`'s STORE collection in a new fill mode and runs after `RuleStringStore`, so it claims only the zero-fills and non-char fills that rule declines (no regression to string rendering). No option gate: it adds no new *recognition* (those sequences were already collected), only a clearer rendering, so it carries no new false-positive risk. Validated by `datatests/heapmemset.xml`. |
| #39-4b | `RulePopcount` SWAR-idiom rule folding into the **existing native** `CPUI_POPCOUNT` op — no new builtin (see [DD-0007 addendum](../decisions/0007-rec39-phase2-inline-detection.md#addendum-2026-06-06-popcount-folds-into-the-native-cpui_popcount-op)) + datatest | **done** (revised: folds into native `CPUI_POPCOUNT`, not a new builtin) |
| #39-5 | ~~Extend `memset` element-type coverage (word/dword/AVX-shaped fills)~~ — word/dword **and** single-vector fills **already fold**; reframed to a regression guard (`memsetwide.xml`), residual multi-vector fill deferred (see [DD-0007 #39-5 addendum](../decisions/0007-rec39-phase2-inline-detection.md#addendum-2026-06-06-39-5-wide-fills-already-fold)) | **done** (docs+test only) |
| #39-6 | Loop-shaped patterns (`strlen`, `strcmp`/`strncmp`, `memcmp`, copy-loops) — post-structuring loop-region matcher | **sub-DD landed at [DD-0008](../decisions/0008-rec39-loop-region-matcher.md)**; re-sequenced into #39-6a..#39-6d there |
| #39-7 | Optional per-occurrence UI override (only if exactness relaxes) | not started |

> **Note (2026-06-03):** the original Phase 2 design (the `## Phase 2:
> Inlined-function detection` section above) proposed a data-driven XML
> pattern-library engine. [DD-0007](../decisions/0007-rec39-phase2-inline-detection.md)
> supersedes that mechanism: upstream already renders inlined string
> copies as `builtin_memcpy`/`builtin_strncpy` via `CPUI_CALLOTHER` +
> builtin user-ops (`constseq.cc` / `userop.cc`), so the missing patterns
> are added as small C++ `Rule`s + new builtins on that proven path
> rather than as a new subsystem. The prose below is retained for the
> *motivation* and *pattern list*; the *implementation mechanism* is
> DD-0007's.

## Coordination with Rec 37 (C++ frontend)

The C++ frontend (Rec 37) introduces `CppDecompilerHints` for
upcasts and ctor/dtor recognition. The pattern-library approach
in this rec is the same shape: an annotation layer between the
IR and the output. The two should share the annotation
infrastructure (one annotation pipeline, multiple producers).

## What this does *not* do

- **Does not rewrite the IR.** Pattern matching is read-only;
  the underlying pcode is unchanged. The user can always see the
  raw code.
- **Does not detect arbitrary user-defined patterns.** The
  library is data-driven, but the matching engine is the same
  for all patterns. Custom patterns are a follow-up.
- **Does not address the harder structural-recovery problems**
  (switch tables, jump-threading, tail-call recognition). Those
  are separate RFCs.
