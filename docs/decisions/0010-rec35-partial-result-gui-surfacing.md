---
number: 0010
title: Rec 35 #35-5 — partial-result GUI surfacing needs the budget + partial flag threaded through the DecompInterface path first; split into #35-5a (plumbing) + #35-5b (banner)
status: accepted
date: 2026-06-06
audit_rec: 35
---

# Decision 0010: the partial-result UI banner rides a GUI-plumbing prerequisite, not the existing decompile path as-is

## Context

[`DECOMPILER_BUDGETS.md`](../decompiler/DECOMPILER_BUDGETS.md) §"What
the UI shows" specifies the user-facing end of Rec 35: when a decompile
returns a budget-exhausted **partial** result, the GUI shows a banner —
*"Decompilation partial — budget exhausted on `data_flow`. [Retry with
2x budget] [Accept partial]"* — and the retry path re-runs at a doubled
budget. That is sequencing row **#35-5** ("UI banner + retry path"), the
last open item of Sprint 8's Rec 35 line and the documented prerequisite
that unblocks the deferred **#36-6** (cache partial results keyed by
budget — see [DD-0009 addendum 10](0009-rec36-cache-invalidation-grounding.md)).

The whole C++ budget machinery is shipped (#35-1…#35-4: all five passes
wired onto the cooperative tracker). The natural assumption is that
#35-5 is now just UI work on top of a finished backend. A
pre-implementation survey of the GUI decompile path found that
assumption is **wrong in two specific places**: the interactive GUI can
neither *set* a budget nor *detect* that a result came back partial. So
#35-5 as written is not directly shippable — it sits on a plumbing
prerequisite. This DD records what the survey found and splits #35-5
into that prerequisite (**#35-5a**) and the banner proper (**#35-5b**),
mirroring how DD-0007/DD-0008 grounded each Rec 39 step before building.

## What the backend already provides (grounding)

The C++ side is complete and — importantly — already reachable through
the GUI's existing option channel:

- **Both budget options are registered in `OptionDatabase`.**
  `registerOption(make_unique<OptionDecompileBudget>())` and
  `…<OptionDecompileBudgetPass>()` (`options.cc:132`–`133`). Option
  elements are decoded from the `<optionslist>` XML that
  `DecompileOptions.encode()` produces and `DecompInterface.setOptions`
  ships via `sendCommand1Param("setOptions", …)`
  (`DecompInterface.java:686`–`701`). So the C++ **decode + apply** side
  is already wired to the GUI protocol — the budget is a global
  `Architecture` option (`glb->budget`), set once per `setOptions`, not a
  per-call parameter. Nothing in the native worker needs to change for
  the GUI to *set* a budget.
- **A structured partial-result flag already exists.**
  `FlowInfo::budgetexhausted_present = 0x800` (`flow.hh:71`) with the
  accessor `bool hasBudgetExhausted(void) const` (`flow.hh:167`); set at
  `flow.cc:416` when the cooperative tracker truncates flow.
- **A human-readable diagnostic is emitted too.** `flow.cc:421`–`425`
  builds `"Exceeded decompilation budget on pass <name>: Some flow is
  truncated"` and calls `data.warningHeader(...)`; `flow.cc:414` also
  records a `data.warning(...)` at the truncation address. Warning-header
  comments render as `<warningheader>` text above the decompiled
  function.

## What is genuinely missing (the two GUI gaps)

### Gap 1 — the GUI cannot set a budget

`DecompileOptions.java` has **no** budget field (no per-pass iteration
cap, no `decompilebudget`/`decompilebudgetpass` equivalent, no
"allow-partial"), and `DecompileOptions.encode()` never emits the
`<decompilebudget>` / `<decompilebudgetpass>` elements the registered
C++ options would decode. The interactive decompile entry,
`DecompInterface.decompileFunction(func, timeoutSecs, monitor)`
(`DecompInterface.java:794`), sends only the function address via the
legacy `"decompileAt"` command (`:820`–`821`). The budget surface is
therefore reachable today **only** from the console/headless option
parser and (future) the FlatBuffers `DecompileRequestCodec` path — never
from the GUI. With no budget ever engaged from the GUI, no GUI decompile
can *be* partial, and "retry at 2× the budget" has no budget to double.

### Gap 2 — the GUI cannot detect a partial result

The `budgetexhausted_present` flag lives on the native `FlowInfo` and is
**not marshalled** into the result stream `DecompileResults` decodes.
`DecompileResults.decodeStream` (`DecompileResults.java:215`) reads only
`<function>` and `<parammeasures>`; there is no partial attribute to
read. Consequently `DecompileResults` exposes **no** `isPartial()` /
`getBudgetExhaustedPass()` — and the existing state predicates are the
wrong tools:

- `decompileCompleted()` (`:107`) is `hfunc != null || hparamid != null`
  — **true** for a partial (a budget-truncated function is still a valid,
  just-incomplete `HighFunction`).
- `isValid()` (`:152`) keys off `errMsg`; the budget diagnostic does not
  populate `errMsg`.
- `isTimedOut()` / `isCancelled()` / `failedToStart()` are
  process-disposal states, orthogonal to a cooperative budget truncation.

The only partial signal that reaches the GUI today is the
`<warningheader>` *text* rendered in the markup. A banner that branched
on string-scraping that comment would be brittle (format/locale-fragile,
couples UI logic to a diagnostic string).

### Gap 3 — no retry affordance

Nothing on the Java side remembers the budget a decompile ran under, so
there is no value to double for a "retry with 2×" request.

## The decision

1. **Split #35-5 into #35-5a (plumbing prerequisite) and #35-5b
   (banner).** #35-5a threads a budget *into* and a partial-result
   signal *out of* the GUI `DecompInterface` path; #35-5b is the banner +
   retry UI that #35-5a makes possible. The banner cannot be built
   first — it would have nothing to display and nothing to retry.

2. **Build #35-5a now — it is not a defer.** Unlike #36-6 (speculative
   `(function, budget)` cache keying with no GUI budget to key on) and
   #39-6 (needs new loop-collapse CFG infrastructure that does not
   exist), #35-5a is concrete, additive plumbing over infrastructure that
   *already exists*: the C++ option is already registered for XML decode,
   and the partial flag already exists as `budgetexhausted_present`. It
   is deterministically testable end-to-end (set a tiny budget on a large
   function through the GUI options, assert `isPartial()` and the pass
   name). #35-5a additionally **unblocks #36-6**, since it is the GUI
   budget surface that #36-6's cache key needs.

3. **Surface the partial result as a structured marshalled signal, not by
   scraping the warning text.** #35-5a marshals the existing
   `budgetexhausted_present` flag and the exhausted-pass name into the
   result stream (a small additive attribute on the decompiler's result
   document) and decodes them into first-class
   `DecompileResults.isPartial()` / `getBudgetExhaustedPass()` accessors.
   The structured C++ flag already exists; reusing it keeps the UI
   decision off the rendered diagnostic string. The `<warningheader>`
   text stays as-is for the in-code annotation
   ([`DECOMPILER_BUDGETS.md`](../decompiler/DECOMPILER_BUDGETS.md)
   §"What the UI shows" already wants both a banner *and* an inline
   annotation).

4. **#35-5a budget surface mirrors the existing option conventions.** Add
   budget fields to `DecompileOptions` and emit the *already-registered*
   `<decompilebudget>` / `<decompilebudgetpass>` elements in `encode()` —
   the same encode→`setOptions`→`OptionDatabase` round-trip every other
   option already uses (`OptionForLoops`, `OptionMaxInstruction`, …). No
   new native option, no new command verb. A GUI tools-options entry for
   the budget (mirroring `analyze_for_loops`) is #35-5a's user control;
   default leaves the budget disengaged so existing behaviour is
   unchanged until a user opts in.

5. **Keep #35-5b's retry semantics in the GUI layer.** "Retry with 2×"
   re-issues the decompile with `DecompileOptions` carrying double the
   prior budget; the banner hangs off the existing
   `OverlayMessagePainter` / decoration surface in `DecompilerProvider`
   rather than a new component. The cached-partial fast-retry
   optimisation is **#36-6's** job (keyed `(function, budget)`), not
   #35-5b's — #35-5b can re-decompile from scratch and stays correct; the
   cache speedup composes on top once #36-6 lands on the #35-5a surface.

## Rejected alternatives

- **Build the banner (#35-5b) directly on the current path.** Rejected:
  the GUI can neither engage a budget nor observe a partial result, so
  there is nothing to show and nothing to retry. Gaps 1–3 are
  load-bearing, not polish.
- **Detect partial by parsing the `<warningheader>` comment text.**
  Rejected per decision 3: a structured `budgetexhausted_present` flag
  already exists C++-side; branching UI on a rendered English diagnostic
  is brittle and fragile to wording/format changes. Marshal the flag.
- **Route the GUI budget through the FlatBuffers `DecompileRequestCodec`
  (Rec 34) instead of the XML optionslist.** Rejected for #35-5a: that
  codec exists but is not yet wired into `DecompileProcess`, so adopting
  it would drag the GUI decompile path onto an unshipped IPC surface.
  The XML option round-trip is already live and "structurally additive"
  per [`DECOMPILER_BUDGETS.md`](../decompiler/DECOMPILER_BUDGETS.md)
  §"Coordination with Rec 33 + 34"; the budget moves to FlatBuffers when
  the rest of the request path does.
- **Defer #35-5 like #36-6/#39-6.** Rejected per decision 2: those were
  deferred for speculativeness / missing infrastructure. #35-5a has
  neither problem — it is testable plumbing over shipped backend support
  and is itself the unblocker for #36-6.

## Validation

#35-5a ships a GUI integration test under the Decompiler `test.slow`
tree (modelled on `DecompilerCachingTest`, run with `DISPLAY=:0`): set a
deliberately tiny `decompilebudget` through `DecompileOptions`, decompile
a function large enough to exhaust it, and assert
`results.isPartial()` is true and `getBudgetExhaustedPass()` names the
truncated pass; then assert an unbudgeted (or generous-budget) decompile
of the same function reports `isPartial() == false`. If #35-5a touches
native marshalling code, the Decompiler C++ unit-tests
([[local-decomp-test-build-apple-silicon]]) must be green, any new
`.cc`/`.hh` needs a `cppRaiiAudit` PROTECTED_FILES entry and a
`certification.manifest` entry (`gradle cppRaiiAudit`,
`gradle :Decompiler:ip`), and a header-touching change needs the full
unit suite green before #35-5b stacks. #35-5b ships its banner/retry
behaviour behind the same option gate, validated in the GUI.

## Sequencing (refines `DECOMPILER_BUDGETS.md` #35-5)

| PR | Scope |
|---|---|
| #35-5a | Thread the budget through the GUI: `DecompileOptions` budget fields + `encode()` emits the already-registered `<decompilebudget>`/`<decompilebudgetpass>`; marshal `budgetexhausted_present` + exhausted-pass name into the result stream; first-class `DecompileResults.isPartial()` / `getBudgetExhaustedPass()`; GUI tools-option control; integration test. **Actionable now; also unblocks #36-6.** |
| #35-5b-1 | Partial-result banner (display): `DecompilerProvider.decompileDataChanged` resolves the `OverlayMessagePainter` surface to a passive banner naming the exhausted pass when `DecompileResults.isPartial()`, off the structured signal. Message builder is a pure static factory, fast-unit-testable. Depends on #35-5a. |
| #35-5b-2 | Retry affordance: a docking action enabled only on a partial result that re-decompiles with `DecompileOptions` carrying 2× the prior `decompileBudget` ("Retry with 2× budget" / "Accept partial"). Split from #35-5b-1 because the re-decompile behaviour needs GUI-runtime validation, not a headless unit test. Depends on #35-5b-1. |

#35-5a is the load-bearing one: it converts the budget from a
console/headless-only capability into a GUI-observable one, which both
#35-5b and #36-6 require.

## References

- `Ghidra/Features/Decompiler/src/decompile/cpp/options.cc:132`–`133`
  (`OptionDecompileBudget` / `OptionDecompileBudgetPass` registered in
  `OptionDatabase`), `:970` / `:1019` (their `apply()`).
- `Ghidra/Features/Decompiler/src/decompile/cpp/flow.hh:71`
  (`budgetexhausted_present`), `:167` (`hasBudgetExhausted`);
  `flow.cc:414`–`425` (flag set + `warning` + `warningHeader`
  "Exceeded decompilation budget on pass X").
- `Ghidra/Features/Decompiler/src/main/java/ghidra/app/decompiler/DecompInterface.java:686`–`701`
  (`setOptions` → `DecompileOptions.encode` → `sendCommand1Param("setOptions", …)`),
  `:794`/`:820`–`821` (`decompileFunction` → `"decompileAt"`, address only).
- `Ghidra/Features/Decompiler/src/main/java/ghidra/app/decompiler/DecompileOptions.java`
  — no budget field; `encode()` emits no `<decompilebudget>`.
- `Ghidra/Features/Decompiler/src/main/java/ghidra/app/decompiler/DecompileResults.java:107`
  (`decompileCompleted`), `:152` (`isValid`), `:215` (`decodeStream`,
  reads `<function>`/`<parammeasures>` only) — no `isPartial()`.
- `Ghidra/Features/Decompiler/src/main/java/ghidra/app/plugin/core/decompile/DecompilerProvider.java`
  — `OverlayMessagePainter` / decoration surface the #35-5b banner hangs off.
- [`DECOMPILER_BUDGETS.md`](../decompiler/DECOMPILER_BUDGETS.md) —
  Rec 35 design; §"What the UI shows" (banner spec), §"Coordination with
  Rec 36" (the #36-6 link), the #35-5 sequencing row this DD refines.
- [DD-0009 addendum 10](0009-rec36-cache-invalidation-grounding.md) —
  #36-6 deferral that names #35-5 as its unblocker.
- [DD-0006](0006-block-structure-budget-bypass.md) — the
  design-step-first pattern this DD follows for the Rec 35 line.
