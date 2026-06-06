---
number: 0019
title: Rec 37 #37-9c — the fourth CppDecompilerHints form renders an explicit (non-virtual) destructor call (e->~ClassName()); the class name is a total model fact so this form has no neutral fallback, it takes no argument list, and it does no vtable lookup (virtual-dispatch destruction is already the #37-7 form)
status: accepted
date: 2026-06-06
audit_rec: 37
---

# Decision 0019: the destructor-call renderer is the fourth headless `CppDecompilerHints` form — render `e->~ClassName()`, no fallback (the class name is total), no argument list, no vtable lookup

## Context

[DD-0016](0016-rec37-decompiler-hints-renderer.md) split RFC-0001 §5 into a
headless renderer core (`CppDecompilerHints`) and a deferred decompiler-side
recognition pass, and shipped the virtual-method-call form (#37-7);
[DD-0017](0017-rec37-cast-renderer.md) added the up/down-cast form (#37-8);
[DD-0018](0018-rec37-construction-renderer.md) added the heap-construction form
(#37-9). With those three the renderer covers all three examples RFC §5 names
by hand. DD-0018 then listed four **follow-on** forms it deliberately deferred,
the first being the symmetric destruction idiom:

> the explicit destructor call `e->~ClassName()` / `e.~ClassName()` — a distinct
> in-place-destruction idiom (placement-new objects, member dtors).

This DD picks that follow-on up as the next headless slice (**#37-9c**) — the
faithful inverse of #37-9's construction, grounded the same way: a pure function
of the resolved `CppClass` plus the receiver expression the caller supplies.

### Why this is the right next headless step

It is the same renderer/recognition seam as #37-7/#37-8/#37-9, applied to the
construction form's natural pair. Rendering `e->~ClassName()` is a pure function
of `type.getName()` plus the receiver expression and access kind; *recognising*
that a given p-code direct call is `Foo::~Foo(ptr)` and recovering the
`(class, receiver)` it denotes is inseparable from a `Program`/`HighFunction`
and is the deferred recognition wrapper. The construction renderer just shipped
(#37-9); grounding its destruction counterpart now keeps the headless queue
moving and reuses the existing `CppDecompilerHints` rather than minting a new
type. It stays clear of the DTM-coupled #37-10+ band (signatures, overloads),
which is not headless.

### The numbering: #37-9c, the destruction sibling of construction #37-9

DD-0018 grouped these forms as "#37-9 follow-ons" without assigning each a
number, and reserved **#37-9b** for the *construction* recognition wrapper. This
DD designates the explicit destructor-call **renderer** as **#37-9c**: it is the
death-side sibling of the #37-9 birth-side construction renderer, sharing the
"class name is a total fact, no fallback" contract, and is clearly distinct from
the DTM-coupled #37-10+ band. Its own recognition pass (recognising the raw
`Foo::~Foo(ptr)` direct call) is deferred into the same Program-coupled
recognition band as #37-7b/#37-8b/#37-9b.

## What the model already provides (grounding)

No new model type or mutator is required (as for #37-7/#37-8/#37-9):

- `CppClass.getName()` (`CppClass.java:61`) — the type name in
  `e->~ClassName()`. The rendered method name is `~` prepended to it. This is
  the only model fact the form needs.

The renderer does **not** consult `CppClass.getVtable()` or `getMethods()`. A
destructor that dispatches *through the vtable* (a virtual destructor) is
already covered by the #37-7 virtual-call form: its slot is a name-resolved
`CppMethod` whose name is `~ClassName`, and `renderVirtualCall` renders it as
`e->~ClassName()` from that slot. This #37-9c form is specifically the
**explicit / non-virtual** destruction idiom — a direct `Foo::~Foo(ptr)` call
the compiler emitted for in-place destruction (placement-new teardown, a member
or stack object's destructor, manual lifetime management) — where the name comes
from the *class*, not a vtable slot.

## The decision

1. **#37-9c adds a fourth rendering method to the existing `CppDecompilerHints`**
   (`Ghidra/Features/Base`, package `ghidra.app.util.cpp`) — the
   explicit-destructor-call form
   `renderDestructorCall(CppClass type, String receiverExpr, boolean receiverIsPointer)`.
   It stays a stateless renderer of already-resolved model facts: it holds no
   `Program`, no `DataTypeManager`, and no decompiler/`HighFunction` handle,
   never scans/demangles/parses, and never mutates the model. Its inputs are the
   destructed `CppClass`, the receiver expression as an opaque string, and the
   pointer-vs-value access kind; its output is the rendered string.

2. **It renders `receiver->~ClassName()`** for a pointer receiver and
   `receiver.~ClassName()` for a value receiver, where `ClassName` is
   `type.getName()` and the method name is `~` prepended to it. The parentheses
   are always empty: a C++ destructor takes no explicit arguments, so — unlike
   `renderVirtualCall` — this form has **no argument-list parameter** and always
   emits `()`.

3. **This form has no neutral fallback — like #37-9, a deliberate contrast with
   #37-7/#37-8.** Those forms fall back (to `receiver->vtable[i](args)`, to
   `src + offset`) because the model fact they need can be unresolved. The
   destructor form needs only the class name, a *total* model fact:
   `getName()` never fails for a defined class. So the renderer has nothing to
   fall back from; it validates only its boundary inputs — a null `type` or a
   null/blank `receiverExpr` — and rejects those with `IllegalArgumentException`,
   exactly as the #37-9 construction renderer rejects a null type and the #37-7
   renderer rejects a null/blank receiver.

4. **No vtable lookup, no `getMethods()`, no `DataType`/signature work.** The
   name is derived from the class (`~` + `getName()`), not resolved through a
   vtable slot or a `CppMethod` signature. Virtual-dispatch destruction is the
   #37-7 form (a named `~ClassName` slot); this form is the direct/explicit
   idiom, so it deliberately does not consult the vtable — doing so would blur
   the two distinct call shapes and re-introduce the resolution concerns the
   headless split keeps out.

5. **Hints stay advisory and additive** (RFC invariant, as #37-7/#37-8/#37-9):
   the renderer only produces a string; it never rewrites p-code or mutates the
   model. The decompiler-side pass that recognises the `Foo::~Foo(ptr)` direct
   call, recovers the `(class, receiver)`, and drives this renderer is the
   deferred Program-coupled wrapper, in the same recognition band as
   #37-7b/#37-8b/#37-9b, not part of this slice.

6. **Scope is the scalar explicit destructor call of DD-0018's follow-on list.**
   The three remaining follow-ons stay deferred, each because it needs an input
   this slice's inputs do not carry:
   - `delete e` — C++ `delete` infers the type from the pointer and so names no
     `CppClass`; it carries no model fact and pairs with the deallocation
     recognition, not this in-place-destruction one;
   - array construction `new ClassName[count]` and placement
     `new (ptr) ClassName(args)` — each needs a count / allocation-target
     expression the recognition pass recovers separately.

## Validation

#37-9c extends the headless `CppDecompilerHintsTest`
(`AbstractGenericTest`, no `Program`/`DataTypeManager`/decompiler), building
fixtures directly through the model setters. Tests assert, for the
destructor-call renderer:

- a pointer receiver renders `e->~ClassName()` and a value receiver renders
  `e.~ClassName()`, with the `~` + `getName()` name and always-empty parens;
- the rendering ignores any vtable / base edges present on the class (the name
  comes from `getName()`, not a slot) — the explicit, non-virtual idiom;
- a null `type` and a null/blank `receiverExpr` are each rejected with
  `IllegalArgumentException` (no neutral fallback);
- the renderer remains stateless — same inputs → same output, interleaved with
  the #37-7 call, #37-8 cast, and #37-9 construction renders on the same
  instance.

Gating reminders (each a hard local gate before push, as for #37-7/#37-8/#37-9):

- Java-only and headless, so `gradle :Base:test` is the validating gate (the new
  destructor cases plus the existing `Cpp*Test`s for no regression); no
  `--full` C++ precheck applies ([[always-test-before-push]]).
- #37-9c edits the already-tracked `CppDecompilerHints.java` (no new source
  file), so there is no new `certification.manifest` entry and `cppRaiiAudit`
  (C++-only) does not apply; still run `gradle :Base:ip` to confirm the header
  gate stays green ([[new-source-file-ip-manifest]]).

## Sequencing (refines DD-0018)

| PR | Scope |
|---|---|
| #37-7 | *(shipped, DD-0016)* `CppDecompilerHints` renderer — virtual-method-call form |
| #37-8 | *(shipped, DD-0017)* up/down-cast form — `static_cast<T*>` |
| #37-9 | *(shipped, DD-0018)* construction form — `new ClassName(args)` |
| #37-9c | **(this DD's subject)** explicit destructor-call form — `e->~ClassName()` |
| #37-9 follow-ons | `delete e`, array `new[]`, placement `new` — each still deferred (needs inputs this slice does not carry) |
| #37-7b / #37-8b / #37-9b / #37-9c recognition | the `HighFunction` pattern-recognition passes that drive each renderer — deferred, not headless |
| #37-10+ | `CppMethod` signature / `DataType` resolution (DTM-coupled, incl. constructor-overload selection); templates; operators |

## Rejected alternatives

- **Route the destructor through `renderVirtualCall` with a synthesized
  `~ClassName` slot.** Rejected: the explicit destructor's name comes from the
  *class* (`~` + `getName()`), not a vtable slot; faking a slot would require
  fabricating a vtable entry and would erase the real distinction between
  virtual-dispatch destruction (already the #37-7 form, driven by a name-resolved
  slot) and direct/explicit destruction (this form). Keeping them separate keeps
  each renderer honest about what model fact it reads.
- **Give the destructor form a neutral fallback for parity with #37-7/#37-8.**
  Rejected (as for #37-9): the class name is a total model fact, so there is
  nothing to fall back from; the honest contract is to reject malformed boundary
  inputs and otherwise always render the call.
- **Take an argument list (mirror `renderVirtualCall`).** Rejected: a C++
  destructor takes no explicit arguments; the form always renders `()`. An
  argument-list parameter would be dead weight inviting misuse.
- **Bundle `delete e` / array-new / placement-new into #37-9c.** Rejected:
  `delete` names no `CppClass` (C++ infers the type from the pointer), and
  array/placement each need a count / target-pointer expression this slice does
  not carry; each is cleaner as its own follow-on paired with its own
  recognition.
- **Have the renderer take a `Program`/`HighFunction` and recover the receiver
  itself.** Rejected (as in DD-0016/DD-0017/DD-0018): that drags the decompiler
  coupling back in; the renderer takes the class and receiver expression the
  deferred pass supplies.

## References

- [RFC-0001](../rfcs/0001-cpp-frontend.md) §5 `CppDecompilerHints` — the headless
  renderer this extends with the destruction counterpart to construction.
- [DD-0018](0018-rec37-construction-renderer.md) — #37-9, the construction
  renderer this pairs with and whose "class name is total, no fallback" contract
  it reuses; its follow-on list names this destructor form.
- [DD-0016](0016-rec37-decompiler-hints-renderer.md) — #37-7, the renderer and
  the renderer/recognition split this reuses, and the virtual-call form that
  already covers *virtual-dispatch* destruction;
  [DD-0017](0017-rec37-cast-renderer.md) — #37-8, the second form.
- Model the renderer reads (Base, all shipped): `CppClass.java` (`getName :61`),
  `CppDecompilerHints.java` (the #37-7/#37-8/#37-9 renderer this extends).

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
