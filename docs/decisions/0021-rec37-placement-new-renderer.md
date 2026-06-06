---
number: 0021
title: Rec 37 #37-9e — the sixth CppDecompilerHints form renders placement construction (new (ptr) ClassName(args)); the class name is a total model fact so this form has no neutral fallback, it carries a placement-target pointer expression alongside the constructor arguments, and it does no constructor-overload resolution
status: accepted
date: 2026-06-06
audit_rec: 37
---

# Decision 0021: the placement-new renderer is the sixth headless `CppDecompilerHints` form — render `new (ptr) ClassName(args)`, no fallback (the class name is total), no overload resolution

## Context

[DD-0016](0016-rec37-decompiler-hints-renderer.md) split RFC-0001 §5 into a
headless renderer core (`CppDecompilerHints`) and a deferred decompiler-side
recognition pass, and shipped the virtual-method-call form (#37-7);
[DD-0017](0017-rec37-cast-renderer.md) added the up/down-cast form (#37-8);
[DD-0018](0018-rec37-construction-renderer.md) added the scalar heap-construction
form (#37-9); [DD-0019](0019-rec37-destructor-call-renderer.md) added the
explicit destructor-call form (#37-9c); [DD-0020](0020-rec37-array-construction-renderer.md)
added the array-construction form (#37-9d). DD-0018 listed **placement
`new (ptr) ClassName(args)`** among its deferred follow-ons:

> array construction `new ClassName[count]` and placement `new (ptr)
> ClassName(args)` — each needs a count / allocation-target expression the
> recognition pass recovers separately.

DD-0020 picked up the array half; this DD picks up the **placement** half as the
next headless slice (**#37-9e**) — the construction-family form that names an
explicit allocation target. It is grounded the same way as #37-9: a pure
function of the resolved `CppClass` plus the placement-target and
constructor-argument expressions the caller supplies.

### Why this is the right next headless step

It is the same renderer/recognition seam as #37-7/#37-8/#37-9/#37-9c/#37-9d,
applied to the placement form of construction. Rendering
`new (ptr) ClassName(args)` is a pure function of `type.getName()` plus the
placement-target expression and the constructor-argument expressions;
*recognising* that a given p-code graph is a placement-`new` (an already-owned
buffer passed to `operator new(size, ptr)` — or simply reused — then a
constructor call into it) and recovering the `(class, placement, args)` it
denotes is inseparable from a `Program`/`HighFunction` and is the deferred
recognition wrapper. The scalar and array construction renderers shipped (#37-9,
#37-9d); grounding the placement sibling now nearly completes the headless
construction family and reuses the existing `CppDecompilerHints` rather than
minting a new type. It stays clear of the DTM-coupled #37-10+ band (signatures,
overloads), which is not headless.

### The numbering: #37-9e, the placement sibling of construction #37-9

DD-0018 grouped these forms as "#37-9 follow-ons" without numbering each, and
reserved **#37-9b** for the scalar *construction* recognition wrapper; DD-0019
took **#37-9c** for the explicit destructor-call renderer and DD-0020 took
**#37-9d** for the array-construction renderer. This DD designates the
placement-new **renderer** as **#37-9e**: it is the placement-shaped sibling of
the #37-9 scalar construction renderer, sharing the "class name is a total fact,
no fallback" contract, and is clearly distinct from the DTM-coupled #37-10+ band.
Its own recognition pass (recognising the placement allocation + ctor) is
deferred into the same Program-coupled recognition band as
#37-7b/#37-8b/#37-9b.

## What the model already provides (grounding)

No new model type or mutator is required (as for #37-7/#37-8/#37-9/#37-9c/#37-9d):

- `CppClass.getName()` (`CppClass.java:61`) — the type name in
  `new (ptr) ClassName(...)`. This is the only model fact the form needs.

The renderer does **not** consult `CppClass.getMethods()` to pick a constructor:
the recognition pass has already established that a placement construction
happened and hands over the placement-target and argument expressions; the model
carries no constructor flag on `CppMethod`, and choosing among overloaded
constructors would require the parameter `DataType`s/signatures that are the
DTM/`Program`-coupled #37-10+ work. The placement target and arguments are opaque
expressions, exactly as #37-9 treats its constructor arguments and #37-9d treats
its count.

## The decision

1. **#37-9e adds a sixth rendering method to the existing `CppDecompilerHints`**
   (`Ghidra/Features/Base`, package `ghidra.app.util.cpp`) — the
   placement-construction form
   `renderPlacementConstruction(CppClass type, String placementExpr, List<String> argumentExprs)`.
   It stays a stateless renderer of already-resolved model facts: it holds no
   `Program`, no `DataTypeManager`, and no decompiler/`HighFunction` handle, never
   scans/demangles/parses, and never mutates the model. Its inputs are the
   constructed `CppClass`, the placement-target expression as an opaque string,
   and the constructor-argument expressions as opaque strings; its output is the
   rendered string.

2. **It renders `new (ptr) ClassName(args)`**, where `ptr` is the
   placement-target expression, `ClassName` is `type.getName()`, and `args` is the
   constructor argument expressions joined in call order (reusing the same join
   behaviour the #37-7 call renderer and #37-9 construction renderer use). A
   zero-argument placement construction renders `new (ptr) ClassName()` — the
   constructor parentheses are always emitted, as in #37-9. The placement
   parentheses around `ptr` are always emitted: that bracketed allocation target
   is exactly what distinguishes placement-`new` from the #37-9 ordinary `new`.

3. **This form has no neutral fallback — like #37-9/#37-9c/#37-9d.** The forms
   that fall back (#37-7 to `receiver->vtable[i](args)`, #37-8 to `src + offset`)
   do so because the model fact they need can be unresolved. The
   placement-construction form needs only the class name, a *total* model fact:
   `getName()` never fails for a defined class. So the renderer has nothing to
   fall back from; it validates only its boundary inputs — a null `type`, a
   null/blank `placementExpr`, a null argument list, or a null argument element —
   and rejects those with `IllegalArgumentException`, exactly as #37-9 rejects a
   null type / null arg list and #37-9d rejects a null/blank count.

4. **No constructor-overload resolution, no `DataType`/signature work.** The
   renderer emits the placement and argument expressions the recognition pass
   supplies; it does not identify *which* constructor was called or type the
   arguments. Constructor selection is a signature/`DataType` concern (#37-10+),
   not a rendering concern, and pulling it in would re-couple the renderer to the
   DTM the headless split keeps it free of.

5. **No vtable lookup, no `getMethods()`.** The name is derived from the class
   (`getName()`), not resolved through a vtable slot or a `CppMethod` signature.
   Placement-`new` constructs into an existing buffer; nothing about it consults
   the vtable.

6. **Hints stay advisory and additive** (RFC invariant, as
   #37-7/#37-8/#37-9/#37-9c/#37-9d): the renderer only produces a string; it never
   rewrites p-code or mutates the model. The decompiler-side pass that recognises
   the placement-allocation + ctor idiom, recovers the `(class, placement, args)`,
   and drives this renderer is the deferred Program-coupled wrapper, in the same
   recognition band as #37-7b/#37-8b/#37-9b, not part of this slice.

7. **Scope is the scalar placement `new (ptr) ClassName(args)` of DD-0018's
   follow-on list.** The one remaining follow-on stays deferred because it carries
   no model fact this slice's inputs do:
   - `delete e` / `delete[] e` — C++ `delete` infers the type from the pointer and
     so names no `CppClass`; it carries no model fact at all (it would read no
     model and render purely from a receiver expression) and pairs with the
     deallocation recognition. After #37-9e it is the last headless renderer form;
     once it ships, the headless renderer family is exhausted and the remaining
     work (the recognition wrappers and the #37-10+ DTM/signature band) is
     Program-coupled, not headless.

## Validation

#37-9e extends the headless `CppDecompilerHintsTest`
(`AbstractGenericTest`, no `Program`/`DataTypeManager`/decompiler), building
fixtures directly through the model setters. Tests assert, for the
placement-construction renderer:

- a placement construction with arguments renders `new (ptr) ClassName(a, b)`
  with the placement target bracketed and the arguments joined in order;
- a zero-argument placement construction renders `new (ptr) ClassName()`
  (constructor parentheses always present);
- the rendering ignores any vtable / base edges present on the class (the name
  comes from `getName()`, not a slot or signature);
- a null `type`, a null/blank `placementExpr`, a null argument list, and a null
  argument element are each rejected with `IllegalArgumentException` (no neutral
  fallback);
- the renderer remains stateless — same inputs → same output, interleaved with
  the #37-7 call, #37-8 cast, #37-9 construction, #37-9c destructor, and #37-9d
  array-construction renders on the same instance.

Gating reminders (each a hard local gate before push, as for the prior forms):

- Java-only and headless, so `gradle :Base:test` is the validating gate (the new
  placement-construction cases plus the existing `Cpp*Test`s for no regression);
  no `--full` C++ precheck applies ([[always-test-before-push]]).
- #37-9e edits the already-tracked `CppDecompilerHints.java` (no new source
  file), so there is no new `certification.manifest` entry and `cppRaiiAudit`
  (C++-only) does not apply; still run `gradle :Base:ip` to confirm the header
  gate stays green ([[new-source-file-ip-manifest]]).

## Sequencing (refines DD-0020)

| PR | Scope |
|---|---|
| #37-7 | *(shipped, DD-0016)* `CppDecompilerHints` renderer — virtual-method-call form |
| #37-8 | *(shipped, DD-0017)* up/down-cast form — `static_cast<T*>` |
| #37-9 | *(shipped, DD-0018)* scalar construction form — `new ClassName(args)` |
| #37-9c | *(shipped, DD-0019)* explicit destructor-call form — `e->~ClassName()` |
| #37-9d | *(shipped, DD-0020)* array-construction form — `new ClassName[count]` |
| #37-9e | **(this DD's subject)** placement-construction form — `new (ptr) ClassName(args)` |
| #37-9f | `delete e` / `delete[] e` — the last headless renderer form (reads no model fact); after it the headless renderer family is exhausted |
| #37-7b … #37-9e recognition | the `HighFunction` pattern-recognition passes that drive each renderer — deferred, not headless |
| #37-10+ | `CppMethod` signature / `DataType` resolution (DTM-coupled, incl. constructor-overload selection); templates; operators |

## Rejected alternatives

- **Reuse `renderConstruction` with the placement target folded into the argument
  list.** Rejected: placement-`new` is a distinct expression shape — a bracketed
  allocation target *before* the class name, `new (ptr) T(args)`, not a scalar
  `new T(args)`; threading the placement pointer through the constructor-argument
  path would conflate the placement target with a constructor argument and erase
  the very fact this form encodes.
- **Give the placement form a neutral fallback for parity with #37-7/#37-8.**
  Rejected (as for #37-9/#37-9c/#37-9d): the class name is a total model fact, so
  there is nothing to fall back from; the honest contract is to reject malformed
  boundary inputs and otherwise always render the placement-`new` expression.
- **Resolve the constructor overload from the argument expressions.** Rejected:
  that needs parameter `DataType`s/signatures (the #37-10+ DTM-coupled work) and
  would re-couple the renderer to the type manager the headless split keeps it
  free of. The renderer formats the placement and args the pass supplies.
- **Bundle `delete e` into #37-9e.** Rejected: `delete` names no `CppClass` (C++
  infers the type from the pointer), so it carries no model fact and reads nothing
  from the model; it is cleaner as the final follow-on paired with the
  deallocation recognition, keeping #37-9e the faithful placement rendering of the
  construction family.
- **Have the renderer take a `Program`/`HighFunction` and recover the placement
  target itself.** Rejected (as in DD-0016/DD-0017/DD-0018/DD-0019/DD-0020): that
  drags the decompiler coupling back in; the renderer takes the class, placement
  target, and argument expressions the deferred pass supplies.

## References

- [RFC-0001](../rfcs/0001-cpp-frontend.md) §5 `CppDecompilerHints` — the headless
  renderer this extends with the placement-construction variant.
- [DD-0018](0018-rec37-construction-renderer.md) — #37-9, the scalar construction
  renderer this is the placement sibling of, and whose "class name is total, no
  fallback" contract and argument-join behaviour it reuses; its follow-on list
  names this placement form.
- [DD-0020](0020-rec37-array-construction-renderer.md) — #37-9d, the array
  sibling shipped just before this, the other half of DD-0018's
  count/allocation-target follow-on.
- [DD-0019](0019-rec37-destructor-call-renderer.md) — #37-9c, the destruction
  sibling; [DD-0016](0016-rec37-decompiler-hints-renderer.md) — #37-7, the
  renderer and the renderer/recognition split this reuses;
  [DD-0017](0017-rec37-cast-renderer.md) — #37-8, the second form.
- Model the renderer reads (Base, all shipped): `CppClass.java` (`getName :61`),
  `CppDecompilerHints.java` (the #37-7/#37-8/#37-9/#37-9c/#37-9d renderer this
  extends).

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
