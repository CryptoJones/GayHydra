---
number: 0034
title: Rec 37 #37-9d-b-2 — the array-new driver closes the recognition loop; it classifies the recovered allocation target as operator new[] by name, reads the element class off the forward-recovered typed result's pointer type (not a ctor callee name, since the trivial-element array-new has no ctor), computes count = byteSize / element size (only for a positive exact-multiple constant), resolves the CppClass, and renders new C[5]
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0034: the #37-9d-b-2 driver renders the array-new from forward facts

## Context

[DD-0033](0033-rec37-array-construction-matcher.md) shipped
[`CppArrayConstructionRecognizer`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppArrayConstructionRecognizer.java),
the `#37-9d-b-1` *forward* matcher, which recovers
`ArrayAllocation(allocationTarget, byteSize, typedResult)` from a candidate array-`new`
allocation — anchoring on the allocation `CALL` and walking forward over the
`CAST`/`COPY` chain to the typed pointer the raw `void *` storage is reinterpreted
into. It is name-blind: whether the callee actually *is* `operator new[]`, and the
element class and count it implies, was left to this driver slice. The renderer this
driver feeds,
[`CppDecompilerHints.renderArrayConstruction`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDecompilerHints.java)
(DD-0016), emits `new ClassName[count]`.

## Decision

Ship
[`CppArrayConstructionDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppArrayConstructionDriver.java):
constructed over a `CppDecompilerHints` renderer and a `CppTypeSystem` model (both
non-null), its `recognizeAndRender(HighFunction)` walks the function's `CALL`s, runs
the matcher on each, and for each match resolves and renders a
`RenderedArrayConstruction(site, rendering)`, returning the hints in p-code order
(empty if none resolve). It mirrors the other drivers' shape (the
`function.getFunction().getProgram().getFunctionManager()` access, the advisory
total-failure-safety).

Three choices pin it:

1. **The element class comes from the typed result, not a ctor callee name.** The
   scalar `#37-9b` constructor driver reads its class from the constructor *callee's
   name* (local name equal to its class namespace). The trivial-element array `new`
   this form recognises has **no constructor call** to name the class — so the driver
   reads the class from the one place it lives: the forward-recovered
   `typedResult`. It strips one pointer level off that varnode's `HighVariable` data
   type (`C *` → `C`) and looks the element type's name up as a `CppClass`. A typed
   result that is not a pointer, or points at a class the type system does not model,
   yields no hint.

2. **The count is `byteSize / elementSize`, and only when that divides cleanly.** The
   renderer needs an element *count*; what the matcher recovered is the allocation's
   *byte size* (e.g. `0x28`). The driver divides it by the element type's
   `getLength()` (e.g. `8`) to get the count (e.g. `5`). It renders a count only when
   the byte size is a **positive constant that is an exact multiple** of the element
   size; a non-constant size (a runtime-computed `new C[k]`), a non-dividing size, or
   a zero/negative size is declined rather than rendering a fabricated, fractional, or
   nonsensical count. This keeps the form advisory-and-never-wrong: it emits a count
   only when it can read one exactly.

3. **The array-vs-scalar distinction is the callee name, read here.** The matcher
   anchor is structurally identical for `operator new[]`, `operator new`, and
   `malloc`. The driver resolves the recovered `allocationTarget` to a `Function` and
   classifies its name in the demangled form Ghidra emits (its `.` namespace
   separator normalised to a space), requiring exactly `operator new[]` — so a scalar
   `operator new` allocation, even one whose result is a typed pointer, is declined.
   This is the same name-classification the delete driver (DD-0027) uses to separate
   `operator delete[]` from `operator delete`.

## Consequences

- The array-`new` form is now end-to-end — the **fifth** of seven recognition forms:
  a real x86-64 `C* makeArray()` doing `new C[5]` decompiles, the matcher recovers the
  `operator.new[]` target / `0x28` byte size / `C *` typed result, and the driver
  renders `new C[5]` (count `0x28 / 8 = 5`). Verified by the harness integration test
  ([`CppArrayConstructionDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppArrayConstructionDriverTest.java)),
  which also asserts a scalar `operator.new` and an unmodelled class each yield no
  hint, and that the constructor rejects nulls.
- The driver is advisory and total-failure-safe: a call whose target resolves to no
  function, whose name is not `operator new[]`, whose typed result is not a pointer to
  a modelled class, or whose byte size is not a positive exact multiple of the element
  size contributes no hint, never an exception.
- Scope: trivial-element arrays. A non-trivial element type additionally emits a
  per-element default-constructor loop; fusing that loop (to confirm the element
  constructor and read its class from the ctor name) is a later cross-form refinement,
  noted on the matcher (DD-0033) and the renderer.
- Two recognition forms remain: the cast (`#37-8b`) follows the structural `#37-7b`
  shape; the placement (`#37-9e-b`) reuses the construction fusion shape (a constructor
  on caller-owned storage).
- Verified locally before commit (test-before-push, local-only — no push, no release):
  `gradle :Base:ip`, `gradle :Base:test --tests 'ghidra.app.util.cpp.*'`,
  `gradle :Decompiler:ip`, and `gradle :Decompiler:integrationTest --tests
  CppArrayConstructionDriverTest` (system `gradle` 8.5) — all green (driver 4/4).
