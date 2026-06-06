---
number: 0017
title: Rec 37 #37-8 — the second CppDecompilerHints form renders up/down-casts (a constant pointer offset matched to a CppBaseClass edge → static_cast<T*>), declines a static cast for a dynamic virtual base, and stays headless
status: accepted
date: 2026-06-06
audit_rec: 37
---

# Decision 0017: the up/down-cast renderer (RFC §5 bullet 2) is the second headless `CppDecompilerHints` form — match a constant offset to an inheritance edge, render `static_cast<T*>`, decline virtual bases

## Context

[DD-0016](0016-rec37-decompiler-hints-renderer.md) split RFC-0001 §5 into a
headless renderer core (`CppDecompilerHints`) and a deferred decompiler-side
pattern-recognition pass, and shipped the first renderer form in #37-7: the
virtual-method-call (`receiver->method(args)`). DD-0016's sequencing table
names the up/down-cast renderer as the next headless form (#37-8), and RFC §5
lists it as its second example:

- `static_cast<Base*>(d)` instead of `d + offsetof(Derived, base)` — a pointer
  adjustment that walks from a derived object to one of its base subobjects (an
  **upcast**), or the inverse `b - offset` that walks back down to the derived
  object (a **downcast**).

The model already carries every inheritance fact this needs. The #37-4
`CppRttiFeeder` ([DD-0013](0013-rec37-rtti-inheritance-feeder.md)) populates
`CppClass.getBaseClasses()` with `CppBaseClass` edges, each recording the base
subobject's byte offset and its `virtual`/access qualifiers
(`CppBaseClass.java`: `getBaseClass :56`, `getOffset :63`, `isVirtual :70`,
`isPublic :77`). This DD grounds the #37-8 slice that renders a recognised
base-adjustment as the C++ cast.

### Why this is the right next headless step

It is the same renderer/recognition seam DD-0016 established, applied to the
second form. Rendering a cast is a pure function of resolved model facts plus
the operand expression the caller supplies; *recognising* that a given p-code
pointer-add by a constant is a base-subobject adjustment (and recovering the
`(derived, offset, direction)` it denotes) is inseparable from a
`Program`/`HighFunction` and is the deferred #37-8b wrapper. Grounding the
renderer now keeps the headless queue moving and reuses #37-7's just-shipped
`CppDecompilerHints` rather than minting a new type.

## What the model already provides (grounding)

No new model type or mutator is required (as for #37-7):

- `CppClass.getBaseClasses()` (`CppClass.java:103`) → `CppBaseClass` edges. The
  renderer matches the recognised constant offset against `getOffset()`
  (`CppBaseClass.java:63`) to find the edge the adjustment crosses.
- `CppBaseClass.getBaseClass()` (`:56`) → the base `CppClass`; `getName()`
  (`CppClass.java:61`) gives the type name for an **upcast**'s
  `static_cast<Base*>`. The derived class's own `getName()` gives the target
  type for a **downcast**'s `static_cast<Derived*>`.
- `CppBaseClass.isVirtual()` (`:70`) — the gate that makes the renderer decline
  a `static_cast` (a virtual base's offset is dynamic, resolved through the
  vbase pointer at runtime, not the compile-time constant a `static_cast`
  represents).

## The decision

1. **#37-8 adds a second rendering method to the existing `CppDecompilerHints`**
   (`Ghidra/Features/Base`, package `ghidra.app.util.cpp`) — the up/down-cast
   form. It stays a stateless renderer of already-resolved model facts: it holds
   no `Program`, no `DataTypeManager`, and no decompiler/`HighFunction` handle,
   never scans/demangles/parses, and never mutates the model. Its inputs are the
   derived `CppClass`, the recognised constant byte offset, the cast direction
   (up vs. down), and the source pointer expression as an opaque string; its
   output is the rendered string.

2. **The renderer matches the offset to a `CppBaseClass` edge and picks the cast
   target by direction.** For the derived class's base edges, it finds the one
   whose `getOffset()` equals the recognised offset. On a match: an **upcast**
   renders `static_cast<Base*>(src)` where `Base` is
   `edge.getBaseClass().getName()`; a **downcast** renders
   `static_cast<Derived*>(src)` where `Derived` is the derived class's own
   `getName()`. This is real logic, not concatenation: the edge search, the
   direction-driven choice of type name, and the two decline paths below.

3. **It renders `static_cast`, never `dynamic_cast`.** The recovered constant
   offset *is* the compiler's structural base-subobject adjustment, which is
   exactly what `static_cast` denotes; `dynamic_cast` would imply an
   RTTI-checked conversion that the raw pointer arithmetic does not perform.

4. **It declines to emit a `static_cast` for a `virtual` base** (`isVirtual()`
   edge) and for an offset that matches no edge. A virtual base's offset is
   resolved dynamically through the vbase pointer, so a constant `static_cast`
   would misrepresent it; an unmatched offset is not a base adjustment the model
   knows. In both cases the renderer falls back to a neutral offset-adjustment
   rendering (`src + offset` for an upcast, `src - offset` for a downcast) rather
   than throwing or fabricating a cast — the same advisory-fallback discipline
   #37-7 uses for an unresolvable vtable slot.

5. **Access (`isPublic`) does not change the rendered cast form.** The pointer
   adjustment is identical whether the base is public, protected, or private;
   the hint is advisory output decorating already-compiled code, not a
   compilability claim, so the renderer does not gate the cast form on access.

6. **Hints stay advisory and additive; no `DataType`/signature resolution, no
   model mutation** (RFC invariant, as #37-7). The decompiler-side
   pattern-recognition pass that recognises the raw `ptr ± constant` idiom,
   recovers the `(derived, offset, direction)`, and drives this renderer is the
   deferred #37-8b Program-coupled wrapper, not part of this slice.

## Validation

#37-8 extends the headless `CppDecompilerHintsTest`
(`AbstractGenericTest`, no `Program`/`DataTypeManager`/decompiler), building
resolved fixtures directly through the model setters. Tests assert, for the
cast renderer:

- an upcast across a non-virtual base edge at the recognised offset renders
  `static_cast<Base*>(src)` (base type name), and a downcast renders
  `static_cast<Derived*>(src)` (derived type name);
- a `virtual` base edge declines the `static_cast` and falls back to the neutral
  `src + offset` / `src - offset` form;
- an offset matching no base edge falls back to the neutral form rather than
  fabricating a cast;
- the renderer emits `static_cast`, never `dynamic_cast`;
- the renderer remains stateless (same inputs → same output, interleaved with
  #37-7 virtual-call renders on the same instance).

Gating reminders (each a hard local gate before push, as for #37-7):

- Java-only and headless, so `gradle :Base:test` is the validating gate (the new
  cast cases plus the existing `Cpp*Test`s for no regression); no `--full` C++
  precheck applies ([[always-test-before-push]]).
- #37-8 edits the already-tracked `CppDecompilerHints.java` (no new source file),
  so there is no new `certification.manifest` entry and `cppRaiiAudit` (C++-only)
  does not apply; still run `gradle :Base:ip` to confirm the header gate stays
  green ([[new-source-file-ip-manifest]]).

## Sequencing (unchanged from DD-0016)

| PR | Scope |
|---|---|
| #37-7 | *(shipped, DD-0016)* `CppDecompilerHints` renderer — virtual-method-call form |
| #37-8 | **(this DD's subject)** cast form — match offset → `CppBaseClass` edge, render `static_cast<T*>`, decline virtual bases |
| #37-9 | ctor/dtor-construction form (`new Foo(args)`) — still headless model→string |
| #37-7b / #37-8b / #37-9b | the `HighFunction` pattern-recognition passes that drive each renderer — deferred, not headless |
| #37-10+ | `CppMethod` signature / `DataType` resolution (DTM-coupled); templates; operators |

## Rejected alternatives

- **Render `dynamic_cast` for downcasts.** Rejected: the recovered constant
  offset is a compile-time `static_cast` adjustment; `dynamic_cast` asserts an
  RTTI check the binary's pointer math did not perform, so it would be a false
  rendering. A genuinely RTTI-guarded downcast is a different recognised idiom
  (an RTTI lookup, not a bare offset add) and a later concern.
- **Emit a `static_cast` for a virtual base using the statically-recovered
  vbase offset.** Rejected: the offset is only a where-known snapshot
  (`CppBaseClass` javadoc); the real adjustment is dynamic, so a constant
  `static_cast` would be wrong in general. Declining to a neutral form is the
  honest rendering until a vbase-aware form exists.
- **Have the renderer take a `Program`/`HighFunction` and recover the offset
  itself.** Rejected (as in DD-0016): that drags the decompiler coupling back
  in. The renderer takes the recognised offset and direction as inputs the
  deferred pass supplies.
- **Gate the cast form on `isPublic` (decline or annotate non-public bases).**
  Rejected: the pointer adjustment is access-independent and the hint is
  advisory output over already-compiled code, not a source-compilability claim;
  gating would suppress a correct structural rendering for no benefit.
- **Introduce a new `CppCast` model type / mutator to stash cast facts.**
  Rejected: hints are advisory decorations, not model state (DD-0016 invariant);
  the renderer reads the inheritance edges the #37-4 feeder already built.

## References

- [RFC-0001](../rfcs/0001-cpp-frontend.md) §5 `CppDecompilerHints` bullet 2 — the
  up/down-cast rendering this slice supplies.
- [DD-0016](0016-rec37-decompiler-hints-renderer.md) — #37-7, the renderer this
  extends and the renderer/recognition split it reuses.
- [DD-0013](0013-rec37-rtti-inheritance-feeder.md) — #37-4, the
  `CppRttiFeeder` that populates the `CppBaseClass` edges the cast renderer
  matches against.
- Model the renderer reads (Base, all shipped):
  `CppClass.java` (`getBaseClasses :103`, `getName :61`), `CppBaseClass.java`
  (`getBaseClass :56`, `getOffset :63`, `isVirtual :70`, `isPublic :77`),
  `CppDecompilerHints.java` (the #37-7 renderer this extends).

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
