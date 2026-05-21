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

## Sequencing

| PR | Scope |
|---|---|
| #39-1 (this PR) | This design doc |
| #39-2 | `ForLoopPattern` analysis pass + output emission |
| #39-3 | `for`-loop unit tests on the existing decompiler datatest corpus |
| #39-4 | `InlinedFunctionPattern` analysis pass + pattern-library loader |
| #39-5 | Initial patterns: `memcpy`, `memset`, `strlen` |
| #39-6 | Additional patterns: `strcmp`, `memcmp`, popcount |
| #39-7 | UI hover/override for inline-call recognition |

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
