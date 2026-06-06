---
number: 0020
title: Rec 37 #37-9d — the fifth CppDecompilerHints form renders array heap construction (new ClassName[count]); the class name is a total model fact so this form has no neutral fallback, it takes no constructor argument list (array-new value-initializes each element), and it does no vtable lookup
status: accepted
date: 2026-06-06
audit_rec: 37
---

# Decision 0020: the array-construction renderer is the fifth headless `CppDecompilerHints` form — render `new ClassName[count]`, no fallback (the class name is total), no constructor argument list, no vtable lookup

## Context

[DD-0016](0016-rec37-decompiler-hints-renderer.md) split RFC-0001 §5 into a
headless renderer core (`CppDecompilerHints`) and a deferred decompiler-side
recognition pass, and shipped the virtual-method-call form (#37-7);
[DD-0017](0017-rec37-cast-renderer.md) added the up/down-cast form (#37-8);
[DD-0018](0018-rec37-construction-renderer.md) added the scalar heap-construction
form (#37-9); [DD-0019](0019-rec37-destructor-call-renderer.md) added the
explicit destructor-call form (#37-9c). With those four the renderer covers the
three examples RFC §5 names by hand plus the destruction sibling. DD-0018 listed
**array construction `new ClassName[count]`** among its deferred follow-ons:

> array construction `new ClassName[count]` and placement `new (ptr)
> ClassName(args)` — each needs a count / allocation-target expression the
> recognition pass recovers separately.

This DD picks the array-construction half of that follow-on up as the next
headless slice (**#37-9d**) — a construction *variant* grounded the same way as
#37-9: a pure function of the resolved `CppClass` plus the element-count
expression the caller supplies.

### Why this is the right next headless step

It is the same renderer/recognition seam as #37-7/#37-8/#37-9/#37-9c, applied to
the array form of construction. Rendering `new ClassName[count]` is a pure
function of `type.getName()` plus the count expression; *recognising* that a
given p-code graph is an array allocation (`operator new[]` then a per-element
constructor loop) and recovering the `(class, count)` it denotes is inseparable
from a `Program`/`HighFunction` and is the deferred recognition wrapper. The
scalar construction renderer just shipped (#37-9); grounding its array sibling
now keeps the headless queue moving and reuses the existing `CppDecompilerHints`
rather than minting a new type. It stays clear of the DTM-coupled #37-10+ band
(signatures, overloads), which is not headless.

### The numbering: #37-9d, the array sibling of construction #37-9

DD-0018 grouped these forms as "#37-9 follow-ons" without assigning each a
number, and reserved **#37-9b** for the scalar *construction* recognition
wrapper; [DD-0019](0019-rec37-destructor-call-renderer.md) took **#37-9c** for
the explicit destructor-call renderer. This DD designates the array-construction
**renderer** as **#37-9d**: it is the array-shaped sibling of the #37-9 scalar
construction renderer, sharing the "class name is a total fact, no fallback"
contract, and is clearly distinct from the DTM-coupled #37-10+ band. Its own
recognition pass (recognising the raw `operator new[]` + per-element ctor loop)
is deferred into the same Program-coupled recognition band as
#37-7b/#37-8b/#37-9b.

## What the model already provides (grounding)

No new model type or mutator is required (as for #37-7/#37-8/#37-9/#37-9c):

- `CppClass.getName()` (`CppClass.java:61`) — the type name in
  `new ClassName[count]`. This is the only model fact the form needs.

The renderer does **not** consult `CppClass.getMethods()` to pick a constructor
and does **not** consult `CppClass.getVtable()`. Array `new[]` calls the element
type's *default* constructor on each element — C++ provides no syntax to pass
per-element constructor arguments through `new T[n]` — so there is no
constructor-overload choice to make and no argument list to format. The element
count is an opaque expression the recognition pass recovers, exactly as #37-9
treats the scalar constructor arguments as opaque expressions.

## The decision

1. **#37-9d adds a fifth rendering method to the existing `CppDecompilerHints`**
   (`Ghidra/Features/Base`, package `ghidra.app.util.cpp`) — the
   array-heap-construction form
   `renderArrayConstruction(CppClass type, String countExpr)`. It stays a
   stateless renderer of already-resolved model facts: it holds no `Program`, no
   `DataTypeManager`, and no decompiler/`HighFunction` handle, never
   scans/demangles/parses, and never mutates the model. Its inputs are the
   constructed `CppClass` and the element-count expression as an opaque string;
   its output is the rendered string.

2. **It renders `new ClassName[count]`**, where `ClassName` is
   `type.getName()` and `count` is the count expression verbatim. The brackets
   are always emitted and always carry the count — array `new[]` with no extent
   is not a thing in C++, so the recognition pass always recovers a count and the
   renderer always renders one.

3. **This form takes no constructor argument list — a deliberate contrast with
   #37-9.** Scalar `renderConstruction` takes the constructor argument
   expressions because `new Foo(args)` calls a chosen constructor; array `new
   Foo[n]` value/default-initializes each element and C++ has no syntax to thread
   per-element constructor arguments through it. An argument-list parameter would
   be dead weight inviting misuse, exactly as the destructor form (#37-9c)
   declines one because a destructor takes no arguments.

4. **This form has no neutral fallback — like #37-9 and #37-9c.** The forms that
   fall back (#37-7 to `receiver->vtable[i](args)`, #37-8 to `src + offset`) do so
   because the model fact they need can be unresolved. The array-construction form
   needs only the class name, a *total* model fact: `getName()` never fails for a
   defined class. So the renderer has nothing to fall back from; it validates only
   its boundary inputs — a null `type` or a null/blank `countExpr` — and rejects
   those with `IllegalArgumentException`, exactly as #37-9 rejects a null type and
   #37-9c rejects a null/blank receiver.

5. **No vtable lookup, no `getMethods()`, no `DataType`/signature work.** The
   name is derived from the class (`getName()`), not resolved through a vtable
   slot or a `CppMethod` signature, and the element constructor is implicit
   (default), so there is no overload to resolve. Pulling in signature/`DataType`
   work would re-couple the renderer to the DTM the headless split keeps it free
   of (#37-10+).

6. **Hints stay advisory and additive** (RFC invariant, as
   #37-7/#37-8/#37-9/#37-9c): the renderer only produces a string; it never
   rewrites p-code or mutates the model. The decompiler-side pass that recognises
   the `operator new[]` + per-element-ctor-loop idiom, recovers the
   `(class, count)`, and drives this renderer is the deferred Program-coupled
   wrapper, in the same recognition band as #37-7b/#37-8b/#37-9b, not part of this
   slice.

7. **Scope is the scalar-element array `new ClassName[count]` of DD-0018's
   follow-on list.** The two remaining follow-ons stay deferred, each because it
   needs an input this slice's inputs do not carry:
   - placement `new (ptr) ClassName(args)` — needs an allocation-target (the
     placement pointer) expression alongside the constructor arguments; it pairs
     with the placement recognition, not this array one;
   - `delete e` / `delete[] e` — C++ `delete` infers the type from the pointer and
     so names no `CppClass`; it carries no model fact and pairs with the
     deallocation recognition.

## Validation

#37-9d extends the headless `CppDecompilerHintsTest`
(`AbstractGenericTest`, no `Program`/`DataTypeManager`/decompiler), building
fixtures directly through the model setters. Tests assert, for the
array-construction renderer:

- a construction with a count expression renders `new ClassName[n]` with the
  count verbatim inside the brackets;
- the rendering ignores any vtable / base edges present on the class (the name
  comes from `getName()`, not a slot or signature);
- a null `type` and a null/blank `countExpr` are each rejected with
  `IllegalArgumentException` (no neutral fallback);
- the renderer remains stateless — same inputs → same output, interleaved with
  the #37-7 call, #37-8 cast, #37-9 construction, and #37-9c destructor renders on
  the same instance.

Gating reminders (each a hard local gate before push, as for
#37-7/#37-8/#37-9/#37-9c):

- Java-only and headless, so `gradle :Base:test` is the validating gate (the new
  array-construction cases plus the existing `Cpp*Test`s for no regression); no
  `--full` C++ precheck applies ([[always-test-before-push]]).
- #37-9d edits the already-tracked `CppDecompilerHints.java` (no new source
  file), so there is no new `certification.manifest` entry and `cppRaiiAudit`
  (C++-only) does not apply; still run `gradle :Base:ip` to confirm the header
  gate stays green ([[new-source-file-ip-manifest]]).

## Sequencing (refines DD-0019)

| PR | Scope |
|---|---|
| #37-7 | *(shipped, DD-0016)* `CppDecompilerHints` renderer — virtual-method-call form |
| #37-8 | *(shipped, DD-0017)* up/down-cast form — `static_cast<T*>` |
| #37-9 | *(shipped, DD-0018)* scalar construction form — `new ClassName(args)` |
| #37-9c | *(shipped, DD-0019)* explicit destructor-call form — `e->~ClassName()` |
| #37-9d | **(this DD's subject)** array-construction form — `new ClassName[count]` |
| #37-9 follow-ons | placement `new (ptr) ClassName(args)`, `delete e` / `delete[] e` — each still deferred (needs inputs this slice does not carry) |
| #37-7b / #37-8b / #37-9b / #37-9c / #37-9d recognition | the `HighFunction` pattern-recognition passes that drive each renderer — deferred, not headless |
| #37-10+ | `CppMethod` signature / `DataType` resolution (DTM-coupled, incl. constructor-overload selection); templates; operators |

## Rejected alternatives

- **Give the array form a constructor argument list (mirror `renderConstruction`).**
  Rejected: array `new T[n]` value/default-initializes each element and C++ has
  no syntax to pass per-element constructor arguments through it; an argument-list
  parameter would be dead weight inviting misuse, exactly as #37-9c declines one
  for the no-argument destructor.
- **Give the array form a neutral fallback for parity with #37-7/#37-8.**
  Rejected (as for #37-9/#37-9c): the class name is a total model fact, so there
  is nothing to fall back from; the honest contract is to reject malformed
  boundary inputs and otherwise always render the array-`new` expression.
- **Reuse `renderConstruction` with a synthesized `[count]` argument.** Rejected:
  array `new T[n]` is a distinct expression shape (square brackets around an
  extent, no constructor parentheses), not a scalar `new T(args)` with a funny
  argument; threading a count through the argument path would conflate two
  different C++ constructs and erase the no-ctor-args fact this form encodes.
- **Bundle placement-new / `delete` into #37-9d.** Rejected: placement-new needs
  a placement-target pointer expression and array-new does not carry one, and
  `delete` names no `CppClass` (C++ infers the type from the pointer); each is
  cleaner as its own follow-on paired with its own recognition.
- **Have the renderer take a `Program`/`HighFunction` and recover the count
  itself.** Rejected (as in DD-0016/DD-0017/DD-0018/DD-0019): that drags the
  decompiler coupling back in; the renderer takes the class and count expression
  the deferred pass supplies.

## References

- [RFC-0001](../rfcs/0001-cpp-frontend.md) §5 `CppDecompilerHints` — the headless
  renderer this extends with the array-construction variant.
- [DD-0018](0018-rec37-construction-renderer.md) — #37-9, the scalar construction
  renderer this is the array sibling of, and whose "class name is total, no
  fallback" contract it reuses; its follow-on list names this array form.
- [DD-0019](0019-rec37-destructor-call-renderer.md) — #37-9c, the no-argument
  contract (empty parens) this form's no-constructor-args decision parallels.
- [DD-0016](0016-rec37-decompiler-hints-renderer.md) — #37-7, the renderer and
  the renderer/recognition split this reuses;
  [DD-0017](0017-rec37-cast-renderer.md) — #37-8, the second form.
- Model the renderer reads (Base, all shipped): `CppClass.java` (`getName :61`),
  `CppDecompilerHints.java` (the #37-7/#37-8/#37-9/#37-9c renderer this extends).

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
