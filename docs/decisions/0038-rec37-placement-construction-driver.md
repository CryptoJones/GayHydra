---
number: 0038
title: Rec 37 #37-9e-b-2 — the placement-construction driver closes the seventh and last recognition form; it resolves the matcher's recovered constructor and allocation targets to functions, classifies the first as a name==class constructor and the second as operator new (the same two name classifiers the heap driver uses), renders the recovered buffer varnode's HighVariable name as the placement expression, resolves the class in a CppTypeSystem, and dispatches to renderPlacementConstruction → new (buf) C(); advisory and total-failure-safe, with constructor arguments scoped out to the #37-10+ DTM work
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0038: the #37-9e-b-2 driver renders placement new and closes Rec 37

## Context

[DD-0037](0037-rec37-placement-construction-matcher.md) shipped
[`CppPlacementConstructionRecognizer`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppPlacementConstructionRecognizer.java),
the `#37-9e-b-1` matcher, which recovers
`PlacementConstruction(constructorTarget, allocationTarget, placementBuffer)` from the non-elided
two-call placement-`new` shape (a placement `operator new(size, buffer)` whose result feeds the
constructor receiver), gating on the allocation carrying a buffer operand. It is callee-blind: which
class is constructed and whether the two callees really are a constructor and `operator new` was left
to this driver slice. The renderer this driver feeds,
[`CppDecompilerHints.renderPlacementConstruction`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDecompilerHints.java)
(DD-0016), emits `new (ptr) ClassName(args)`.

This is the **seventh and last** Rec 37 recognition form. With it, all seven go end-to-end: virtual
call (#37-7b), delete (#37-9f-b), destructor (#37-9c-b), heap-new (#37-9b), array-new (#37-9d-b), base
cast (#37-8b), and now placement-new (#37-9e-b).

## Decision

Ship
[`CppPlacementConstructionDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppPlacementConstructionDriver.java):
constructed over a `CppDecompilerHints` renderer and a `CppTypeSystem` model (both non-null), its
`recognizeAndRender(HighFunction)` walks the function's `CALL`s, runs the matcher on each, and for each
match resolves and renders a `RenderedPlacement(site, rendering)`, returning the hints in p-code order
(empty if none resolve). It mirrors the `#37-9b` heap [`CppConstructorDriver`](0031-rec37-constructor-driver.md)
exactly, holding the `Program` (via `function.getFunction().getProgram()`) to classify the recovered
callees by name.

Three choices pin it:

1. **The same two name classifiers as the heap driver.** The constructor is recognised by the
   `name == class` marker (its local name equals its parent class namespace), and the class is looked up
   in the type system by that name; the allocation is recognised as `operator new` (its `.` namespace
   separator normalised to a space, matching `operator.new`). These are the heap driver's two
   classifiers verbatim. They are **duplicated**, not extracted: at this second user they are kept as
   honest per-form twins per the DD-0026 rule-of-three convention (the codebase extracted
   `CppDirectCallRecognizer` only at the *third* user, DD-0032), and a third user would earn the
   extraction.

2. **The placement target is the recovered buffer's `HighVariable` name.** What this driver adds over
   the heap driver is the bracketed placement expression: it renders the matcher's recovered buffer
   varnode's `HighVariable` name (e.g. `param_1`) as the placement target, the same operand-rendering
   the delete, destructor, and cast drivers use, and passes it to `renderPlacementConstruction`. A
   buffer with no printable name yields no hint.

3. **Constructor arguments are scoped out.** Like the heap and virtual-call drivers, the renderer is
   called with an empty argument list — argument recovery and overload resolution are a
   signature/`DataType` concern (the DTM-coupled `#37-10+` work), not a recognition one. A
   zero-argument placement construction renders `new (buf) ClassName()`.

## Consequences

- The placement form is now end-to-end — the **seventh and last** recognition form: a real x86-64
  `C* makeAt(void* buf) { return new (buf) C(); }` decompiles, the matcher recovers the constructor /
  the placement `operator new` / the buffer, and the driver renders `new (param_1) C()`. Verified by
  the harness integration test
  ([`CppPlacementConstructionDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppPlacementConstructionDriverTest.java),
  5/5), which also asserts an unmodelled class, a non-`operator new` allocation, and a heap `new` (no
  buffer operand) each yield no hint, and that the constructor rejects nulls.
- **Rec 37 recognition is complete: seven of seven forms end-to-end.** The headless ceiling that
  stalled the queue (renderers shipped Sprint 13, recognition blocked on a live `HighFunction`) is
  fully cleared for the seven C++ idiom forms, on top of the Rec 30 harness (DD-0023).
- The driver is advisory and total-failure-safe: a construction whose constructor target resolves to no
  function or is not a `name == class` constructor, whose allocation is not `operator new`, whose buffer
  has no printable name, or whose class is unmodelled contributes no hint, never an exception.
- **Scope.** The non-elided two-call placement form only (the matcher's scope, DD-0037). Distinguishing
  a nothrow `operator new(size_t, const nothrow_t&)` — which also carries a second operand — from true
  placement by its second parameter's *type* is a signature concern deferred to the `#37-10+` band, not
  done here; this driver renders any buffer-carrying `operator new` as placement.
- Verified locally before commit (test-before-push, local-only — no push, no release):
  `gradle :Base:ip`, `gradle :Base:test --tests 'ghidra.app.util.cpp.*'`, and
  `gradle :Decompiler:integrationTest --tests CppPlacementConstructionDriverTest` (system `gradle` 8.5)
  — all green (driver 5/5, cpp unit suite green).
