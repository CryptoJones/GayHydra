---
number: 0042
title: Rec 37 #37-10a — thread explicit constructor arguments into the placement-new driver; CppPlacementConstructionDriver recovers the constructor CALL's inputs after the call target (0) and the this receiver (1) as the explicit arguments, renders each by its HighVariable name, and dispatches them to renderPlacementConstruction so new (buf) C(arg) renders with its argument; an argument with no printable name declines the whole hint (advisory, never-wrong); constants and compound-expression args are deferred
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0042: the #37-10a placement-new constructor-argument threading

## Context

The seven Rec 37 recognition forms shipped end to end through the `#37-9` band: the last,
[`CppPlacementConstructionDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppPlacementConstructionDriver.java)
([DD-0037](0037-rec37-placement-construction-recognizer.md) matcher,
[DD-0016](0016-rec37-cpp-decompiler-hints.md) renderer), recognises the non-elided placement-`new`
idiom `p = operator new(size, buf); C::C(p); ...` and renders `new (buf) ClassName(...)`. But every
driver in the band — placement, heap `#37-9b`, the virtual-call and cast drivers — passed an **empty**
argument list to its renderer: `renderPlacementConstruction(type, placementExpr, List.of())`. So a
constructor with explicit arguments rendered as `new (buf) C()`, silently dropping them.

The renderers were already built to take arguments:
[`CppDecompilerHints.renderPlacementConstruction(CppClass, String placementExpr, List<String> argumentExprs)`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDecompilerHints.java)
renders `new (placementExpr) ClassName(arg, arg, ...)`, joining the list. The gap was entirely
**driver-side**: nobody recovered the arguments from the p-code and handed them over. The `#37-10+`
band (`SprintPlanning.md`) is exactly this rendering-completeness work; `#37-10a` is its first slice and
the smallest honest unit — thread the arguments through one driver, grounded end to end.

The constructor `CALL` op's input layout was grounded empirically (per the grounded-not-guessed rule)
before any matcher code, via a throwaway decompiled-fixture probe: **input 0 is the call target, input 1
is the `this` receiver (the placement buffer), and inputs 2..n are the explicit constructor arguments in
source order.** This mirrors the heap-`new`/alloc shape the band already relies on.

## Decision

Thread the constructor's explicit arguments through `CppPlacementConstructionDriver`:

1. **Recover arguments from the constructor `CALL`.** A new private
   `constructorArguments(PcodeOp constructorCall)` walks `getInput(i)` for `i` from
   `THIS_INPUT_INDEX + 1` (= 2) to `getNumInputs()`, rendering each via the existing
   `operandName(Varnode)` helper — the same `HighVariable`-name rendering the buffer and receiver already
   use. The arguments are returned in call (source) order; a zero-argument constructor yields an empty
   list and still renders `new (buf) C()`.

2. **An unnamed argument declines the whole hint.** If any argument input has no printable
   `HighVariable` name (an unnamed temporary, or a bare constant, which carries no `HighVariable`),
   `constructorArguments` returns `null` and `render` declines the entire construction (contributes no
   hint) rather than rendering a constructor call with a gap in its argument list. This is the same
   advisory, never-wrong contract the receiver rendering already holds: a partial or guessed argument
   list is worse than no hint. Rendering constants and compound-expression arguments — which *do* let a
   currently-declined site render — is later `#37-10` work, as is overload resolution against the
   `DataType` signature.

3. **Dispatch the recovered list.** `render` now calls
   `renderer.renderPlacementConstruction(type, placementExpr, argumentExprs)` with the recovered list
   instead of `List.of()`, so `new (buf) C(arg)` renders with its argument.

The change is confined to the placement driver and is purely additive to its existing total-failure-safe
contract; the matcher, the renderer, and the recovered-fact records are untouched. Threading the same
arguments through the heap `CppConstructorDriver` and the other band drivers are the next `#37-10`
slices, kept as honest per-form twins until a third user earns an extraction (DD-0026 rule of three).

## Consequences

- A decompiled placement `new (buf) C(v)` now renders **`new (param_1) C(param_2)`** rather than
  dropping the argument. Verified end to end by
  [`CppPlacementConstructionDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppPlacementConstructionDriverTest.java)
  (6/6) through the Rec 30 headless `AbstractDecompilerHighFunctionTest` harness (DD-0023): a new
  `testRendersPlacementWithArgument` hand-assembles an x86-64 Windows-ABI
  `C* makeAt(void* buf, longlong v)` whose body is `new (buf) C(v)` — the constructor `CALL` carrying `v`
  as its third input — and asserts the rendering threads the argument. The existing zero-argument case
  (`new (param_1) C()`), the unmodelled-class / non-`operator new` / heap-`new` declines, and the
  null-argument rejection all still hold.
- **What is still deferred** (later `#37-10` slices): threading arguments through the heap and other band
  drivers; rendering constant and compound-expression arguments (which currently decline a hint); and
  `DataType`-signature / template / operator rendering and overload resolution.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Decompiler:integrationTest --tests 'ghidra.app.decompiler.CppPlacementConstructionDriverTest'`
  (6/6), system `gradle` 8.5.
