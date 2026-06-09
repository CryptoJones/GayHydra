---
number: 0043
title: Rec 37 #37-10b — thread explicit constructor arguments into the heap-new driver; CppConstructorDriver recovers the constructor CALL's inputs after the call target (0) and the this receiver (1) as the explicit arguments, renders each by its HighVariable name, and dispatches them to renderConstruction so new C(arg) renders with its argument; an argument with no printable name declines the whole hint (advisory, never-wrong); the two argument helpers are duplicated from the placement driver as per-form twins (rule of three); constants and compound-expression args are deferred
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0043: the #37-10b heap-new constructor-argument threading

## Context

[DD-0042](0042-rec37-placement-ctor-arg-threading.md) (`#37-10a`) threaded explicit constructor
arguments through the *placement*-`new` driver
([`CppPlacementConstructionDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppPlacementConstructionDriver.java)),
the first slice of the `#37-10+` rendering band. The *heap*-`new` driver
([`CppConstructorDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppConstructorDriver.java),
`#37-9b`, [DD-0030](0030-rec37-cpp-constructor-recognizer.md)) had the identical gap: it called
`renderer.renderConstruction(type, List.of())` with an empty argument list, so a heap `new C(args)`
rendered as `new C()`, dropping the arguments.

The two drivers recover from the same shape. The heap recognizer
([`CppConstructorRecognizer.recognize`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppConstructorRecognizer.java))
is invoked on the **constructor `CALL`** op, and the driver's `render(callSite, ...)` receives that same
op as `callSite`. Its input layout is the one grounded for `#37-10a`: **input 0 is the call target,
input 1 is the `this` receiver (here the allocated storage, the cast result of the `operator new`
call), and inputs 2..n are the explicit constructor arguments in source order.** The renderer was
already built to take them: `renderConstruction(CppClass, List<String> argumentExprs)` renders
`new ClassName(arg, arg, ...)`. The gap was, again, entirely driver-side.

## Decision

Thread the constructor's explicit arguments through `CppConstructorDriver`, exactly as `#37-10a` did for
placement:

1. **Recover arguments from the constructor `CALL`.** A private `constructorArguments(PcodeOp)` walks
   `getInput(i)` for `i` from `THIS_INPUT_INDEX + 1` (= 2) to `getNumInputs()`, rendering each via a
   local `operandName(Varnode)` helper (its `HighVariable` name). Arguments are returned in call order;
   a zero-argument constructor yields an empty list and still renders `new C()`.

2. **An unnamed argument declines the whole hint.** If any argument input has no printable
   `HighVariable` name (an unnamed temporary, or a bare constant, which carries no `HighVariable`),
   `constructorArguments` returns `null` and `render` declines the entire construction — the same
   advisory, never-wrong contract the placement driver and the receiver renderings hold. Rendering
   constants and compound-expression arguments is later `#37-10` work, as is overload resolution.

3. **Dispatch the recovered list.** `render` now calls `renderer.renderConstruction(type, argumentExprs)`
   with the recovered list instead of `List.of()`.

4. **The two helpers are duplicated, not extracted.** `constructorArguments` and `operandName` are
   copied verbatim from the placement driver rather than lifted into a shared utility. This is their
   *second* user; per the [DD-0026](0026-rec37-cpp-delete-driver.md) rule-of-three convention the band
   already follows (each driver renders its operand by `HighVariable` name as an honest per-form twin),
   the extraction waits for the third user. Threading the same arguments through the remaining band
   drivers (the virtual-call and array-construction drivers) is the next `#37-10` slice, and is the point
   at which extracting a shared `constructorArguments`/`operandName` will be earned.

## Consequences

- A decompiled heap `new C(v)` now renders **`new C(param_1)`** rather than dropping the argument.
  Verified end to end by
  [`CppConstructorDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppConstructorDriverTest.java)
  (6/6) through the Rec 30 headless `AbstractDecompilerHighFunctionTest` harness (DD-0023): a new
  `testRendersConstructionWithArgument` hand-assembles an x86-64 Windows-ABI `C* make(longlong v)` whose
  body is `return new C(v);` — the constructor `CALL` carrying `v` as its third input — and asserts the
  rendering threads the argument. The existing zero-argument `new C()`, the non-constructor /
  non-`operator new` / unmodelled-class declines, and the null-argument rejection all still hold.
- **What is still deferred** (later `#37-10` slices): threading arguments through the virtual-call and
  array-construction drivers (the third user, which earns the helper extraction); rendering constant and
  compound-expression arguments (which currently decline a hint); and `DataType`-signature / template /
  operator rendering and overload resolution.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Decompiler:integrationTest --tests 'ghidra.app.decompiler.CppConstructorDriverTest'`
  (6/6), system `gradle` 8.5.
