---
number: 0035
title: Rec 37 #37-8b-1 — the base-cast recognition matcher anchors on the CAST and normalises the two grounded pointer-adjustment shapes (PTRSUB for a positive in-layout upcast offset, PTRADD index*scale for a negative before-the-object downcast offset) into one signed byte offset whose sign is the cast direction and whose magnitude is the base-subobject offset; it recovers (sourcePointer, byteOffset, castResult) and is class-blind, leaving class resolution, direction-vs-edge classification, and source-expression rendering to the driver
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0035: the #37-8b-1 matcher recovers the base-cast's pointer-adjustment facts

## Context

Five Rec 37 recognition forms are now end-to-end: virtual call (`#37-7b`), delete
(`#37-9f-b`), destructor (`#37-9c-b`), heap construction (`#37-9b`), and array-`new`
(`#37-9d-b`, [DD-0033](0033-rec37-array-construction-matcher.md) /
[DD-0034](0034-rec37-array-construction-driver.md)). Two remain: the cast (`#37-8b`)
and placement (`#37-9e-b`) forms. This slice (`#37-8b-1`) is the matcher half of the
cast form. The renderers it feeds,
[`CppDecompilerHints.renderUpcast` / `renderDowncast`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDecompilerHints.java)
(DD-0016), emit `static_cast<Base*>(src)` / `static_cast<Derived*>(src)` — or, when no
non-virtual base edge sits at the recovered offset, a neutral `src + offset` /
`src - offset` adjustment.

## Decision

Ship
[`CppBaseCastRecognizer`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppBaseCastRecognizer.java):
a stateless p-code matcher whose `recognize(PcodeOp)` returns
`BaseCast(sourcePointer, byteOffset, castResult)` for a candidate base-subobject cast,
or `null` otherwise. It reads only the SSA graph; it holds no `Program` and decides
nothing about which classes are involved or which direction the cast runs (that is the
driver's job, `#37-8b-2`).

Three choices pin it, each grounded — not guessed — against the p-code the real
decompiler emits, observed via a throwaway Rec 30 harness probe (DD-0023) for an
x86-64 `lea rax,[rcx±0x10]; ret` typed at both ends as two related classes:

```
// upcast  Base* f(Derived* d) { return (Base*)((char*)d + 0x10); }
CAST   out=Base*         in[0]=PTRSUB out          // (Base *)&d->field_0x10
  PTRSUB out=undefined1* in[0]=d(Derived*) in[1]=#0x10

// downcast  Derived* f(Base* b) { return (Derived*)((char*)b - 0x10); }
CAST   out=Derived*      in[0]=PTRADD out          // (Derived *)(b + -2L)
  PTRADD out=Base*       in[0]=b(Base*) in[1]=#-2 in[2]=#8   // -2 * 8 = -16
```

1. **It anchors on the `CAST`, and normalises two shapes into one signed offset.** The
   direction the offset runs determines which p-code the decompiler emits: a positive
   in-layout offset (Derived → Base subobject) is a `PTRSUB` (address-of-subcomponent),
   whose `in[1]` *is* the byte offset; a negative before-the-object offset (Base →
   enclosing Derived) is a `PTRADD` (scaled pointer arithmetic), whose byte offset is
   `in[1] * in[2]` (index times element size, with a signed index, here `-2 * 8`). The
   matcher recovers from either and stores **one signed `byteOffset`**: its *sign* is
   the cast direction (positive = upcast, negative = downcast) and its *magnitude* is
   the base-subobject offset the renderers match against a base-class edge. This keeps
   the two grounded shapes behind a single recovered fact rather than leaking the
   PTRSUB/PTRADD distinction past the matcher.

2. **It requires both ends pointer-typed, and a non-zero offset.** The fact that
   separates a base cast from arbitrary scalar pointer arithmetic is that *both* the
   source (the `PTRSUB`/`PTRADD` input) and the `CAST` result carry a pointer-typed
   `HighVariable`. A zero offset is declined outright: a first-base (offset-0) cast is
   a bare pointer reinterpretation with no arithmetic at all, so it leaves no recoverable
   adjustment in the SSA graph — there is nothing structural to anchor on, and emitting a
   `static_cast` with no recovered offset would be a guess. (The driver still validates
   the offset against a real base edge; the matcher's pointer-and-non-zero gate is the
   cheap structural prefilter.)

3. **It is class-blind; the driver disambiguates direction and resolves classes.** The
   matcher contributes only SSA-graph facts: the source varnode, the signed offset, and
   the typed cast result. Reading the source/target class names off those varnodes'
   pointer types, resolving them to `CppClass`es in a `CppTypeSystem`, choosing
   `renderUpcast` vs `renderDowncast` from the offset sign (the derived class is the
   *source* for an upcast, the *target* for a downcast), and rendering the source
   expression are all the driver's call (`#37-8b-2`) — `Program`/model-coupled work the
   matcher deliberately stays out of, exactly as the array-`new` matcher leaves
   `operator new[]` classification and count division to its driver.

## Consequences

- The cast form now has its recognition primitive: a real x86-64 upcast recovers the
  `Derived *` source, `+0x10` offset, and `Base *` result; a downcast recovers the
  `Base *` source, `-0x10` offset, and `Derived *` result — verified end-to-end by the
  harness integration test
  ([`CppBaseCastRecognizerTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppBaseCastRecognizerTest.java)),
  which asserts the recovered source/target pointed-type names and the signed offset for
  both directions, plus null- and non-`CAST`-safety.
- This is the second forward-flow recognition shape (after array-`new`): the backward
  direct-call recovery (`CppDirectCallRecognizer`) does not apply, so the matcher is a
  standalone class. Unlike the array matcher it shares nothing with the direct-call
  forms (no `callTargetAddress` reuse — a cast has no call).
- The matcher is advisory and total-failure-safe: a non-`CAST` op, a `CAST` not over a
  constant `PTRSUB`/`PTRADD`, a non-pointer-typed source or result, or a zero offset
  yields `null`, never an exception.
- Next is the `#37-8b-2` driver: read the source/target classes off the recovered
  varnodes' pointer types, resolve them in a `CppTypeSystem`, classify direction from
  the offset sign, render the source expression, and dispatch to `renderUpcast` /
  `renderDowncast` → `static_cast<Base*>(d)` / `static_cast<Derived*>(b)`. The placement
  (`#37-9e-b`) form remains after.
- Verified locally before commit (test-before-push, local-only — no push, no release):
  `gradle :Base:ip`, `gradle :Base:test --tests 'ghidra.app.util.cpp.*'`,
  `gradle :Decompiler:ip`, and `gradle :Decompiler:integrationTest --tests
  CppBaseCastRecognizerTest` (system `gradle` 8.5) — all green (matcher 4/4).
