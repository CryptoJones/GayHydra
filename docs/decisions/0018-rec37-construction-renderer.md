---
number: 0018
title: Rec 37 #37-9 — the third CppDecompilerHints form renders heap construction (new ClassName(args)); the class name is a total model fact so this form has no neutral fallback, and it does no constructor-overload resolution
status: accepted
date: 2026-06-06
audit_rec: 37
---

# Decision 0018: the construction renderer (RFC §5 bullet 3) is the third headless `CppDecompilerHints` form — render `new ClassName(args)`, no fallback (the class name is total), no overload resolution

## Context

[DD-0016](0016-rec37-decompiler-hints-renderer.md) split RFC-0001 §5 into a
headless renderer core (`CppDecompilerHints`) and a deferred decompiler-side
recognition pass, and shipped the virtual-method-call form (#37-7);
[DD-0017](0017-rec37-cast-renderer.md) added the up/down-cast form (#37-8). The
sequencing table in both DDs names the construction form as the third headless
slice (#37-9), and RFC §5 lists it as its closing example:

- `new Foo(args)` instead of `malloc(...) + Foo_ctor(...)` — a heap
  construction, where the `new` keyword fuses the allocation and the constructor
  call into one C++ expression.

The model already names the type. `CppClass.getName()` (`CppClass.java:61`)
delegates to the backing structure, so it is always present for any class the
feeders defined. This DD grounds the #37-9 slice that renders a recognised
heap-construction as the `new` expression.

### Why this is the right next headless step

It is the same renderer/recognition seam as #37-7 and #37-8, applied to RFC §5's
third form. Rendering `new ClassName(args)` is a pure function of the resolved
`CppClass` plus the constructor argument expressions the caller supplies;
*recognising* that a given p-code graph is an allocation followed by a
constructor call (`malloc`/`operator new` then `Foo::Foo(ptr, args…)`) and
recovering the `(class, args)` it denotes is inseparable from a
`Program`/`HighFunction` and is the deferred #37-9b wrapper. Grounding the
renderer now finishes RFC §5's three headless forms and reuses the existing
`CppDecompilerHints` rather than minting a new type.

## What the model already provides (grounding)

No new model type or mutator is required (as for #37-7/#37-8):

- `CppClass.getName()` (`CppClass.java:61`) — the type name in
  `new ClassName(...)`. This is the only model fact the form needs.

The renderer does **not** consult `CppClass.getMethods()` to pick a constructor:
the recognition pass has already established that a construction happened and
hands over the argument expressions; the model carries no constructor flag on
`CppMethod` (only virtual/pure-virtual/const/static), and choosing among
overloaded constructors would require the parameter `DataType`s/signatures that
are still the DTM/`Program`-coupled #37-10+ work.

## The decision

1. **#37-9 adds a third rendering method to the existing `CppDecompilerHints`**
   (`Ghidra/Features/Base`, package `ghidra.app.util.cpp`) — the heap-construction
   form. It stays a stateless renderer of already-resolved model facts: it holds
   no `Program`, no `DataTypeManager`, and no decompiler/`HighFunction` handle,
   never scans/demangles/parses, and never mutates the model. Its inputs are the
   constructed `CppClass` and the constructor argument expressions supplied as
   opaque strings; its output is the rendered string.

2. **It renders `new ClassName(args)`**, where `ClassName` is
   `type.getName()` and `args` is the argument expressions joined in call order
   (reusing the same join behaviour the #37-7 call renderer uses). A
   zero-argument construction renders `new ClassName()` — the parentheses are
   always emitted so the rendering is unambiguously a construction.

3. **This form has no neutral fallback — a deliberate contrast with #37-7 and
   #37-8.** Those forms fall back (to `receiver->vtable[i](args)`, to
   `src + offset`) precisely because the model fact they need can be unresolved
   (an unnamed slot, an offset matching no base edge). The construction form
   needs only the class name, which is a *total* model fact: `getName()` never
   fails for a defined class. So the renderer has nothing to fall back from; it
   validates only its boundary inputs — a null `type`, a null argument list, or a
   null argument element — and rejects those with `IllegalArgumentException`,
   exactly as the #37-7 renderer rejects a null/blank receiver.

4. **No constructor-overload resolution, no `DataType`/signature work.** The
   renderer emits the argument expressions the recognition pass supplies; it does
   not try to identify *which* constructor of the class was called or to type the
   arguments. Constructor selection is a signature/`DataType` concern (#37-10+),
   not a rendering concern, and pulling it in would re-couple the renderer to the
   DTM the headless split keeps it free of.

5. **Hints stay advisory and additive** (RFC invariant, as #37-7/#37-8): the
   renderer only produces a string; it never rewrites p-code or mutates the
   model. The decompiler-side pass that recognises the `alloc + ctor-call` idiom,
   recovers the `(class, args)`, and drives this renderer is the deferred #37-9b
   Program-coupled wrapper, not part of this slice.

6. **Scope is the scalar heap `new ClassName(args)` of RFC §5 bullet 3.** Three
   related renderings are deliberately deferred as follow-ons, each because it
   needs something this slice's inputs do not carry:
   - the symmetric `delete e` — C++ `delete` infers the type from the pointer and
     so names no `CppClass`; it carries no model fact and pairs with the
     deallocation recognition, not this construction one;
   - the explicit destructor call `e->~ClassName()` / `e.~ClassName()` — a
     distinct in-place-destruction idiom (placement-new objects, member dtors);
   - array construction `new ClassName[count]` and placement `new (ptr)
     ClassName(args)` — each needs a count / allocation-target expression the
     recognition pass recovers separately.

## Validation

#37-9 extends the headless `CppDecompilerHintsTest`
(`AbstractGenericTest`, no `Program`/`DataTypeManager`/decompiler), building
fixtures directly through the model setters. Tests assert, for the construction
renderer:

- a construction with arguments renders `new ClassName(a, b)` with the arguments
  joined in order;
- a zero-argument construction renders `new ClassName()` (parentheses always
  present);
- a null `type`, a null argument list, and a null argument element are each
  rejected with `IllegalArgumentException` (no neutral fallback);
- the renderer remains stateless — same inputs → same output, interleaved with
  #37-7 call and #37-8 cast renders on the same instance.

Gating reminders (each a hard local gate before push, as for #37-7/#37-8):

- Java-only and headless, so `gradle :Base:test` is the validating gate (the new
  construction cases plus the existing `Cpp*Test`s for no regression); no
  `--full` C++ precheck applies ([[always-test-before-push]]).
- #37-9 edits the already-tracked `CppDecompilerHints.java` (no new source file),
  so there is no new `certification.manifest` entry and `cppRaiiAudit` (C++-only)
  does not apply; still run `gradle :Base:ip` to confirm the header gate stays
  green ([[new-source-file-ip-manifest]]).

## Sequencing (refines DD-0016/DD-0017)

| PR | Scope |
|---|---|
| #37-7 | *(shipped, DD-0016)* `CppDecompilerHints` renderer — virtual-method-call form |
| #37-8 | *(shipped, DD-0017)* up/down-cast form — `static_cast<T*>` |
| #37-9 | **(this DD's subject)** construction form — `new ClassName(args)` |
| #37-9 follow-ons | `delete e`, explicit destructor call, array `new[]`, placement `new` — each deferred (needs inputs this slice does not carry) |
| #37-7b / #37-8b / #37-9b | the `HighFunction` pattern-recognition passes that drive each renderer — deferred, not headless |
| #37-10+ | `CppMethod` signature / `DataType` resolution (DTM-coupled, incl. constructor-overload selection); templates; operators |

## Rejected alternatives

- **Give the construction form a neutral fallback for parity with #37-7/#37-8.**
  Rejected: there is nothing to fall back from — the class name is a total model
  fact. A contrived fallback would be dead code; the honest contract is to reject
  malformed boundary inputs and otherwise always render the `new` expression.
- **Resolve the constructor overload from the argument expressions.** Rejected:
  that needs parameter `DataType`s/signatures (the #37-10+ DTM-coupled work) and
  would re-couple the renderer to the type manager the headless split keeps it
  free of. The renderer formats the args the pass supplies.
- **Bundle `delete e` / the explicit destructor into #37-9.** Rejected: `delete`
  names no `CppClass` (C++ infers the type from the pointer) so it carries no
  model fact, and the explicit-destructor idiom is a distinct recognition; both
  are cleaner as follow-ons paired with their own recognition, keeping #37-9 the
  faithful inverse-free rendering of RFC §5 bullet 3.
- **Have the renderer take a `Program`/`HighFunction` and recover the args
  itself.** Rejected (as in DD-0016/DD-0017): that drags the decompiler coupling
  back in; the renderer takes the class and argument expressions the deferred
  pass supplies.
- **Introduce a `CppConstructor` model type to mark constructors.** Rejected:
  hints are advisory decorations, not model state (DD-0016 invariant); the
  renderer needs only the class name, and constructor identification belongs to
  the demangling/signature layers, not the hint renderer.

## References

- [RFC-0001](../rfcs/0001-cpp-frontend.md) §5 `CppDecompilerHints` bullet 3 — the
  heap-construction rendering this slice supplies.
- [DD-0016](0016-rec37-decompiler-hints-renderer.md) — #37-7, the renderer this
  extends and the renderer/recognition split it reuses;
  [DD-0017](0017-rec37-cast-renderer.md) — #37-8, the second form and the
  decline/fallback discipline this form deliberately departs from.
- Model the renderer reads (Base, all shipped): `CppClass.java` (`getName :61`),
  `CppDecompilerHints.java` (the #37-7/#37-8 renderer this extends).

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
