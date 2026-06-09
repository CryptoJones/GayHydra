---
number: 0036
title: Rec 37 #37-8b-2 — the base-cast driver closes the recognition loop; it reads the source/target classes off the recovered varnodes' pointer types, takes the cast direction from the recovered offset's sign (positive upcast / negative downcast), picks the derived class accordingly (source for an upcast, target for a downcast), and dispatches to renderUpcast/renderDowncast — but only when that derived class genuinely has a non-virtual base edge at the offset, so the renderer's neutral fallback is never emitted as a hint
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0036: the #37-8b-2 driver renders the base cast from the recovered facts

## Context

[DD-0035](0035-rec37-base-cast-matcher.md) shipped
[`CppBaseCastRecognizer`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppBaseCastRecognizer.java),
the `#37-8b-1` matcher, which recovers `BaseCast(sourcePointer, byteOffset, castResult)`
from a base-subobject pointer-adjustment `CAST` — anchoring on the `CAST` and normalising
the two grounded shapes (`PTRSUB` for a positive in-layout upcast offset, `PTRADD`
`index*scale` for a negative before-the-object downcast offset) into a single **signed**
byte offset. It is class-blind: which classes are involved and which direction the cast
runs was left to this driver slice. The renderers this driver feeds,
[`CppDecompilerHints.renderUpcast` / `renderDowncast`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDecompilerHints.java)
(DD-0016), emit `static_cast<Base*>(src)` / `static_cast<Derived*>(src)`, or a neutral
`src + offset` / `src - offset` adjustment when no non-virtual base edge matches.

## Decision

Ship
[`CppBaseCastDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppBaseCastDriver.java):
constructed over a `CppDecompilerHints` renderer and a `CppTypeSystem` model (both
non-null), its `recognizeAndRender(HighFunction)` walks the function's `CAST`s, runs the
matcher on each, and for each match resolves and renders a `RenderedCast(site, rendering)`,
returning the hints in p-code order (empty if none resolve). It mirrors the other drivers'
shape (the per-op walk, the advisory total-failure-safety) but, unlike the call-based
drivers, needs no `FunctionManager` — a cast has no callee to resolve, so the facts come
entirely from the recovered varnodes' types.

Three choices pin it:

1. **The two classes come from the recovered varnodes' pointer types.** The matcher
   recovered the source and result varnodes; the driver strips one pointer level off each
   one's `HighVariable` data type (`Derived *` → `Derived`, `Base *` → `Base`) and looks the
   names up as `CppClass`es. There is no callee name to read a class from (the scalar `#37-9b`
   constructor driver reads its class from the ctor callee's name) — a cast is pure pointer
   arithmetic, so both class facts live in the SSA varnode types, exactly as the array-`new`
   driver (DD-0034) reads its element class off the typed result.

2. **Direction is the offset's sign; the derived class is the one carrying the base edge.**
   The matcher's signed offset already encodes direction: positive means the pointer was
   adjusted *into* a base subobject (an upcast — the **source** is the derived class), negative
   means it was adjusted back *out* to the enclosing object (a downcast — the **target** is the
   derived class). The driver picks the derived class accordingly and calls `renderUpcast` (with
   the source class) or `renderDowncast` (with the target class), passing the offset's magnitude
   and the source pointer's `HighVariable` name as the source expression (the same
   operand-rendering the delete and destructor drivers use).

3. **It dispatches only when a real non-virtual base edge sits at the offset.** The renderers
   are defensively stateless: asked to render a cast at an offset where the derived class has no
   non-virtual base edge, they fall back to a neutral `src + offset` adjustment rather than
   fabricating a `static_cast`. That fallback is faithful but adds nothing over what the
   decompiler already prints — as a *hint* it is noise. So the driver makes the emit decision
   the renderer cannot: it checks the resolved derived class genuinely has a non-virtual base
   edge at the recovered offset and declines otherwise, never emitting the neutral form. The
   renderer keeps its own check; the two concerns differ — the renderer answers "given I am
   asked, what string?", the driver answers "should a hint be emitted at all?". (A `virtual`
   base's offset is dynamic, not the compile-time constant a `static_cast` represents, so a
   virtual-base edge at the offset is not a match — in both the driver gate and the renderer.)

## Consequences

- The base-cast form is now end-to-end — the **sixth** of seven recognition forms: a real
  x86-64 `Base* f(Derived*)` doing the `+0x10` upcast decompiles, the matcher recovers the
  `Derived *` source / `+0x10` offset / `Base *` result, and the driver renders
  `static_cast<Base*>(param_1)`; the symmetric `-0x10` downcast renders
  `static_cast<Derived*>(param_1)`. Verified by the harness integration test
  ([`CppBaseCastDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppBaseCastDriverTest.java)),
  which also asserts an unmodelled class and a modelled class with no base edge at the offset
  each yield no hint, and that the constructor rejects nulls.
- The driver is advisory and total-failure-safe: a cast whose source or result is not a pointer
  to a modelled class, whose derived class is unmodelled, whose offset matches no non-virtual
  base edge, or whose source pointer has no printable name contributes no hint, never an
  exception.
- Scope: non-virtual single base offsets. The offset-0 first-base reinterpretation the matcher
  already declines (no recoverable adjustment) is out of scope; resolving a `virtual` base's
  dynamic offset is a later slice, mirrored on the matcher (DD-0035) and the renderer.
- One recognition form remains: the placement (`#37-9e-b`) form, which reuses the construction
  fusion shape (a constructor on caller-owned storage).
- Verified locally before commit (test-before-push, local-only — no push, no release):
  `gradle :Base:ip`, `gradle :Base:test --tests 'ghidra.app.util.cpp.*'`, `gradle
  :Decompiler:ip`, and `gradle :Decompiler:integrationTest --tests CppBaseCastDriverTest`
  (system `gradle` 8.5) — all green (driver 5/5).
