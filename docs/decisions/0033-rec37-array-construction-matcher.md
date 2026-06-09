---
number: 0033
title: Rec 37 #37-9d-b-1 — the array-new recognition matcher is a FORWARD matcher; unlike the backward direct-call forms it anchors on the operator new[] allocation CALL and walks forward over the CAST/COPY chain off the call's result to the typed pointer varnode (where the element type lives), recovering (allocationTarget, byteSize, typedResult); it recognises the trivial-element shape (allocation + typed use, no ctor loop) and is name-blind, leaving operator-new[] classification + count division to the driver
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0033: the #37-9d-b-1 matcher recovers the array-new's forward facts

## Context

Four Rec 37 recognition forms are now end-to-end: virtual call (`#37-7b`), delete
(`#37-9f-b`), destructor (`#37-9c-b`), and heap construction (`#37-9b`); the shared
direct-call recovery was extracted at the rule-of-three threshold into
[`CppDirectCallRecognizer`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDirectCallRecognizer.java)
([DD-0032](0032-rec37-direct-call-recognizer-extraction.md)). Three recognition forms
remain: the cast (`#37-8b`), array-`new` (`#37-9d-b`), and placement (`#37-9e-b`)
forms. This slice (`#37-9d-b-1`) is the matcher half of the array-`new` form. The
renderer it feeds,
[`CppDecompilerHints.renderArrayConstruction`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDecompilerHints.java)
(DD-0016), emits `new ClassName[count]`.

## Decision

Ship
[`CppArrayConstructionRecognizer`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppArrayConstructionRecognizer.java):
a stateless p-code matcher whose `recognize(PcodeOp)` returns
`ArrayAllocation(allocationTarget, byteSize, typedResult)` for a candidate array-`new`
allocation, or `null` otherwise. It reads only the SSA graph; it holds no `Program`
and decides nothing about whether the callee actually *is* `operator new[]` (that is
the driver's job, `#37-9d-b-2`).

Three choices pin it, each grounded — not guessed — against the p-code the real
decompiler emits, observed via a throwaway Rec 30 harness probe (DD-0023) for an
x86-64 `C* makeArray() { return new C[5]; }`:

```
uniq = CALL operator.new[], #0x28     // allocation: raw void* result; 0x28 = 5 * sizeof(C)
rax  = CAST uniq                       // CAST void* -> C*  -- this output carries the C* type
RETURN rax                             // (high = pCVar1 : C *)
```

1. **It is a FORWARD matcher — the first one.** The delete, destructor, and
   constructor forms recover their facts by walking *backward* from a call's receiver
   argument (the shared `CppDirectCallRecognizer`). Array-`new`'s defining facts live
   *forward* of the call: the allocation result (`uniq`) is an untyped `void *`; the
   element type `C` appears only downstream, on the `CAST` output the storage is
   reinterpreted into. So the matcher anchors on the allocation `CALL` and walks
   *forward* over the single-consumer `CAST`/`COPY` pass-through chain off the call's
   result to the varnode carrying a pointer-typed `HighVariable` (the `typedResult`).
   The forward walk stops at a fork (a result used in more than one place is not a
   clean pass-through chain) or a real use, so it lands on the typed reinterpretation,
   not past it.

2. **It recognises the trivial-element shape; the ctor loop is not fused.** For a
   non-trivial element type `new C[n]` additionally emits a per-element
   default-constructor loop over the storage. This matcher recognises the
   allocation-and-type shape a *trivial*-element array `new` reduces to (allocation
   plus typed use, no ctor loop) — deliberately, the same way the delete form renders
   the `operator delete` call on its own terms without fusing the preceding
   destructor. Fusing the per-element constructor loop (which would let the element
   class be read from the ctor callee name rather than the result pointer type, the
   way `#37-9b` reads it) is a later cross-form refinement.

3. **The structural anchor is name-blind; the driver disambiguates.** A raw
   `operator new[]` call is structurally indistinguishable from `operator new` or a
   bare `malloc` — all are a sized allocation whose result becomes a typed pointer.
   What makes it an array `new` is the callee's *name* (`operator new[]`), which is
   `Program`-coupled and therefore the driver's call (`#37-9d-b-2`), exactly as the
   delete form's scalar-vs-array distinction is. The matcher contributes only the
   SSA-graph facts: a sized `CALL` with a resolvable target whose result flows forward
   to a pointer-typed varnode. The element *count* the renderer needs is likewise the
   driver's: `byteSize` divided by the element size read off `typedResult`'s pointer
   type.

## Consequences

- The array-`new` form now has its recognition primitive: a real x86-64
  `C* makeArray()` doing `new C[5]` recovers the `operator.new[]` allocation target,
  the `0x28` byte-size argument, and the typed `C *` result, verified end-to-end by
  the harness integration test
  ([`CppArrayConstructionRecognizerTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppArrayConstructionRecognizerTest.java)),
  which asserts the recovered target, the constant byte size, and that the forward
  walk reaches a `C *` pointer, plus null- and non-`CALL`-safety.
- This is the first forward-flow recognition shape in the sprint; the backward
  direct-call recovery (`CppDirectCallRecognizer`) does not apply, so the matcher is a
  standalone class rather than a `CppDirectCallRecognizer` user (it does reuse
  `CppDirectCallRecognizer.callTargetAddress` to read the call's `input[0]` target,
  the one fact it shares with the direct-call forms).
- The matcher is advisory and total-failure-safe: a non-`CALL` op, an argument-less
  call, a call with no resolvable target, no result, or a result that never reaches a
  pointer-typed varnode yields `null`, never an exception.
- Next is the `#37-9d-b-2` driver: classify `allocationTarget` as `operator new[]`,
  read the element `Structure` off `typedResult`'s pointer type, compute
  `count = byteSize / element.getLength()`, resolve the `CppClass`, and dispatch to
  `renderArrayConstruction` → `new C[5]`. The cast (`#37-8b`) and placement
  (`#37-9e-b`) forms remain after.
- Verified locally before commit (test-before-push, local-only — no push, no release):
  `gradle :Base:ip`, `gradle :Base:test --tests 'ghidra.app.util.cpp.*'`,
  `gradle :Decompiler:ip`, and `gradle :Decompiler:integrationTest --tests
  CppArrayConstructionRecognizerTest` (system `gradle` 8.5) — all green (matcher 3/3).
