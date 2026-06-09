---
number: 0037
title: Rec 37 #37-9e-b-1 — the placement-construction matcher recovers the non-elided two-call placement-new shape (a placement operator new taking size+buffer whose result feeds the constructor receiver), recovering the constructor target, the allocation target, and the buffer varnode; it shares the heap form's fusion logic but gates on the allocation carrying a buffer operand (three CALL inputs), and the heap matcher (DD-0030) was tightened in lock-step to decline that case, so the two forms partition the fusion shape and never both match a site
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0037: the #37-9e-b-1 matcher recovers placement new from the non-elided two-call shape

## Context

Placement `new (buf) C()` is the **seventh and last** of the Rec 37 recognition forms. Six are now
end-to-end: virtual call (#37-7b), delete (#37-9f-b), destructor (#37-9c-b), heap-new (#37-9b),
array-new (#37-9d-b), and base cast (#37-8b, DD-0035/DD-0036). The
[`CppDecompilerHints.renderPlacementConstruction`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDecompilerHints.java)
renderer (DD-0016) — `new (ptr) ClassName(args)` — has shipped since the renderer phase; this slice is
the matcher half of the Program-coupled wrapper that drives it.

**Why placement is harder than it looks.** The *standard* placement `new (buf) C()` elides
`operator new(size_t, void*)` entirely: that overload's whole body is `return ptr;`, so the compiler
drops the call and emits a **bare constructor on caller-owned storage**. That bare form is
structurally indistinguishable from an ordinary in-place / stack construction (`C c;`), which the
[`#37-9b` heap matcher](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppConstructorRecognizer.java)
deliberately declines (its receiver is a stack/field address, not a call result). So the elided form
is not recoverable as placement without inventing a signal the binary does not carry.

The **recoverable** shape — flagged in the SprintPlanning feasibility note and to be grounded
empirically before writing the matcher (the grounded-not-guessed rule) — is the *non-elided* two-call
form: a real placement `operator new` taking `(size, buffer)` whose result feeds the constructor
receiver. Grounded via a throwaway probe through the Rec 30 headless harness (DD-0023) on an x86-64
`C* makeAt(void* buf) { return new (buf) C(); }`, the decompiler emits exactly:

```
C * makeAt(undefined1 *param_1) {
  pCVar1 = (C *)operator_new(8L, param_1);   // CALL operatorNew, size, buffer  (THREE inputs)
  C::C(pCVar1);                              // CALL ctor, CAST(operatorNew result)
}
```

This is the *same fusion shape* the heap matcher recognises (a constructor whose cast-stripped
receiver is the result of an allocation `CALL`) — with one structural difference: the allocation is
handed a **buffer** beyond the size. A heap `operator new(size_t)` call has two `CALL` inputs (target
+ size); a placement `operator new(size_t, void*)` call has three (target + size + buffer at
`input[2]`).

**The collision this exposes.** Both overloads demangle to the same name, `operator new`. The heap
driver (DD-0031) classifies its allocation as `operator new` by *name only* and does **not** check
operand count, and the heap matcher did not either — so before this slice, the heap recognizer
*matched the placement fixture* and the heap driver would have mis-rendered it as `new C()`, dropping
the buffer. The name cannot separate the forms; only the operand count can.

## Decision

Ship
[`CppPlacementConstructionRecognizer`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppPlacementConstructionRecognizer.java),
a stateless p-code matcher whose `recognize(PcodeOp callSite)` returns
`PlacementConstruction(Address constructorTarget, Address allocationTarget, Varnode placementBuffer)`
or null. It reuses the shared [`CppDirectCallRecognizer`](0032-rec37-direct-call-recognizer-extraction.md)
to recover the constructor call's `(target, cast-stripped receiver)`, walks back from the receiver to
its defining `CALL` (the allocation), and then — the placement-specific part:

1. **Gate on the buffer operand.** The allocation `CALL` must carry a buffer beyond the size:
   `getNumInputs() >= 3`. This is the structural fact that separates placement from heap. The buffer
   is recovered as `input[2]`.

2. **Recover three facts, mirroring the heap matcher plus the buffer.** Constructor target
   (`constructor.callTarget()`), allocation target (`callTargetAddress(allocation)`), and the buffer
   varnode. As with the heap matcher, *what* the callees are (a real constructor / a placement
   `operator new`) and rendering the buffer expression are the driver's job (#37-9e-b-2); the matcher
   recovers only the SSA facts.

3. **Tighten the heap matcher in lock-step.** [`CppConstructorRecognizer`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppConstructorRecognizer.java)
   now **declines** an allocation carrying a buffer operand (`getNumInputs() >= 3`). With placement
   requiring `>= 3` and heap requiring `< 3`, the two matchers **partition** the fusion shape: no site
   can match both, so a placement site is never double-rendered as both `new C()` and `new (buf) C()`.
   This puts the disambiguation in the matchers (a structural property of the SSA graph), where the
   form-membership decision belongs, rather than leaving two drivers to race.

The split point is the same as every other recognition pair: the matcher reads only the SSA graph and
holds no `Program`; the `#37-9e-b-2` driver holds the `Program`, classifies the callees by name,
resolves the class, renders the buffer expression, and dispatches to the renderer.

## Consequences

- The placement matcher is grounded and green, and the heap/placement partition is verified from
  **both** sides by
  [`CppPlacementConstructionRecognizerTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppPlacementConstructionRecognizerTest.java)
  (4/4): the placement fixture recovers `C::C` / `operator.new` / the `param_1` buffer; the heap
  fixture is declined by the placement matcher (`testDeclinesHeapNew`); and the placement fixture is
  declined by the heap matcher (`testHeapMatcherDeclinesPlacement`). The heap matcher's own suite
  ([`CppConstructorRecognizerTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppConstructorRecognizerTest.java),
  3/3) is unchanged and still green — the tightening is regression-safe.
- The matcher is advisory and total-failure-safe: a non-`CALL`, an argument-less call, a receiver that
  is not a call result, an allocation with no buffer operand, or an allocation with no resolvable
  target each yields null, never an exception.
- **Scope: the non-elided two-call form only.** The elided standard placement new (a bare ctor on
  caller-owned storage) is out of scope — it is indistinguishable from in-place construction and the
  heap matcher already declines it. A nothrow `operator new(size_t, const nothrow_t&)` also has three
  `CALL` inputs; separating it from true placement is a *signature* concern (the second parameter is a
  tag reference, not a `void*` buffer) left to the driver if it proves necessary, not a matcher gate.
- This is the last recognition form's matcher; the `#37-9e-b-2` driver (DD-0038) closes the loop and
  takes Rec 37 to seven-of-seven end-to-end.
- Verified locally before commit (test-before-push, local-only — no push, no release):
  `gradle :Base:ip`, `gradle :Decompiler:integrationTest --tests CppPlacementConstructionRecognizerTest
  --tests CppConstructorRecognizerTest` (system `gradle` 8.5) — all green (placement 4/4, heap 3/3).
