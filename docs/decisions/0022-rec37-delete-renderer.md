---
number: 0022
title: Rec 37 #37-9f — the seventh and final CppDecompilerHints form renders deallocation (delete e / delete[] e); uniquely among the family it reads NO CppClass model fact (C++ delete infers the type from the pointer), so it takes only an opaque receiver expression and an array-vs-scalar flag, has no neutral fallback, and after it ships the headless renderer family is exhausted
status: accepted
date: 2026-06-06
audit_rec: 37
---

# Decision 0022: the deallocation renderer is the seventh and final headless `CppDecompilerHints` form — render `delete e` / `delete[] e`, no model fact read, no fallback; it closes out the headless renderer family

## Context

[DD-0016](0016-rec37-decompiler-hints-renderer.md) split RFC-0001 §5 into a
headless renderer core (`CppDecompilerHints`) and a deferred decompiler-side
recognition pass, and shipped the virtual-method-call form (#37-7);
[DD-0017](0017-rec37-cast-renderer.md) added the up/down-cast form (#37-8);
[DD-0018](0018-rec37-construction-renderer.md) added the scalar heap-construction
form (#37-9); [DD-0019](0019-rec37-destructor-call-renderer.md) added the
explicit destructor-call form (#37-9c); [DD-0020](0020-rec37-array-construction-renderer.md)
added the array-construction form (#37-9d);
[DD-0021](0021-rec37-placement-new-renderer.md) added the placement-construction
form (#37-9e). DD-0018 named `delete e` as a deferred follow-on, and DD-0021
identified it as the last remaining one:

> `delete e` / `delete[] e` — C++ `delete` infers the type from the pointer and
> so names no `CppClass`; it carries no model fact at all … After #37-9e it is
> the last headless renderer form; once it ships, the headless renderer family is
> exhausted.

This DD picks that final follow-on up (**#37-9f**) — the deallocation idiom that
pairs with the #37-9 / #37-9d / #37-9e construction forms. It is grounded
differently from every prior form: it reads **no** model fact.

### Why this is the right next — and last — headless step

It is the same renderer/recognition seam, applied to deallocation. Rendering
`delete e` / `delete[] e` is a pure function of the receiver expression and the
array-vs-scalar flag; *recognising* that a given p-code graph is a destructor
call followed by `operator delete`/`operator delete[]` (and that the pointer is a
single object vs. an array) is inseparable from a `Program`/`HighFunction` and is
the deferred recognition wrapper. Grounding it now closes out the headless
renderer family and reuses the existing `CppDecompilerHints` rather than minting
a new type.

After #37-9f the headless renderer family is **exhausted**: every C++ surface
idiom RFC §5 sketches (virtual call, cast, construction and its array/placement
variants, destruction, deallocation) has a renderer. Everything that remains —
the `HighFunction` pattern-recognition passes that *drive* these renderers
(#37-7b … #37-9f), and the #37-10+ `CppMethod`-signature / `DataType` /
template / operator work — is `Program`/DTM-coupled and so a test-before-push
blocker, not headless. That ceiling is called out here so the next step after
#37-9f is to flag it, not to invent further micro-forms.

### The numbering: #37-9f, the deallocation sibling of construction #37-9

DD-0018 grouped these as "#37-9 follow-ons"; the renderers have been numbered
#37-9c (destructor, DD-0019), #37-9d (array, DD-0020), #37-9e (placement,
DD-0021). This DD designates the deallocation **renderer** as **#37-9f**: the
death-side deallocation counterpart that pairs with the #37-9 birth-side
allocation, distinct from the DTM-coupled #37-10+ band. Its own recognition pass
(recognising the dtor + `operator delete` idiom and the array-vs-scalar shape) is
deferred into the same Program-coupled recognition band as #37-7b/#37-8b/#37-9b.

## What the model provides (grounding) — nothing, and that is the point

Unlike every prior form, #37-9f reads **no** `CppTypeSystem` fact:

- `CppClass.getName()` is **not** consulted. In C++, `delete e` and `delete[] e`
  name no type — the operand is a pointer and the type to destroy is inferred
  from it (the destructor and `operator delete` are resolved from the pointer's
  static type by the compiler, not spelled in the expression). So the rendered
  string contains no class name and the renderer needs no `CppClass`.

This makes #37-9f the degenerate / terminal member of the family: it is still a
faithful C++ rendering and still belongs in `CppDecompilerHints` next to its
construction pair, but it is a pure function of operand strings alone. That it
reads no model fact is *why* it is the natural last form — there is nothing
past it that a headless, model-fact renderer could add.

## The decision

1. **#37-9f adds a seventh rendering method to the existing `CppDecompilerHints`**
   (`Ghidra/Features/Base`, package `ghidra.app.util.cpp`) — the deallocation
   form `renderDelete(String receiverExpr, boolean isArray)`. It stays a stateless
   renderer: it holds no `Program`, no `DataTypeManager`, and no
   decompiler/`HighFunction` handle, never scans/demangles/parses, and never
   mutates the model. Its inputs are the receiver expression as an opaque string
   and the array-vs-scalar flag; its output is the rendered string. It takes
   **no `CppClass`** — deliberately, because `delete` names none.

2. **It renders `delete receiver`** for a scalar pointer and
   `delete[] receiver` for an array pointer. The `[]` is the only thing the
   `isArray` flag changes; there is no class name, no parentheses, and no argument
   list — `delete` is a unary operator on the pointer.

3. **This form has no neutral fallback — like #37-9/#37-9c/#37-9d/#37-9e.** The
   forms that fall back (#37-7, #37-8) do so because a model fact they read can be
   unresolved. #37-9f reads no model fact at all, so there is even less to fall
   back from than the class-name-only forms; it validates only its single boundary
   input — a null/blank `receiverExpr` — and rejects it with
   `IllegalArgumentException`, exactly as the #37-7 renderer rejects a null/blank
   receiver.

4. **No `CppClass`, no vtable lookup, no `getMethods()`, no `DataType`/signature
   work.** There is no class name to render and no constructor/destructor overload
   to resolve: `delete` is rendered purely from the operand and the array flag.
   The destructor that `delete` invokes before freeing is a recognition concern
   (the pass pairs the dtor call with the `operator delete`), not a rendering one;
   the explicit-destructor *rendering* is already the separate #37-9c form.

5. **Hints stay advisory and additive** (RFC invariant): the renderer only
   produces a string; it never rewrites p-code or mutates the model. The
   decompiler-side pass that recognises the dtor + `operator delete` /
   `operator delete[]` idiom, decides array-vs-scalar, recovers the receiver, and
   drives this renderer is the deferred Program-coupled wrapper, in the same
   recognition band as #37-7b/#37-8b/#37-9b, not part of this slice.

6. **Scope is exactly scalar `delete e` and array `delete[] e`.** Both are the
   same unary-operator rendering distinguished only by the `isArray` flag, so they
   are one method, not two slices. There are no further headless renderer forms
   after this: the family is closed.

## Validation

#37-9f extends the headless `CppDecompilerHintsTest`
(`AbstractGenericTest`, no `Program`/`DataTypeManager`/decompiler). Because the
form reads no model fact, several cases need no `CppClass` fixture at all. Tests
assert, for the deallocation renderer:

- a scalar receiver renders `delete e` (`isArray` false) and an array receiver
  renders `delete[] e` (`isArray` true);
- a null and a blank `receiverExpr` are each rejected with
  `IllegalArgumentException` (no neutral fallback);
- the renderer remains stateless — same inputs → same output, interleaved with
  the #37-7 call, #37-8 cast, #37-9 construction, #37-9c destructor, #37-9d
  array-construction, and #37-9e placement renders on the same instance.

Gating reminders (each a hard local gate before push, as for the prior forms):

- Java-only and headless, so `gradle :Base:test` is the validating gate (the new
  deallocation cases plus the existing `Cpp*Test`s for no regression); no
  `--full` C++ precheck applies ([[always-test-before-push]]).
- #37-9f edits the already-tracked `CppDecompilerHints.java` (no new source
  file), so there is no new `certification.manifest` entry and `cppRaiiAudit`
  (C++-only) does not apply; still run `gradle :Base:ip` to confirm the header
  gate stays green ([[new-source-file-ip-manifest]]).

## Sequencing (refines DD-0021) — and the headless ceiling

| PR | Scope |
|---|---|
| #37-7 | *(shipped, DD-0016)* `CppDecompilerHints` renderer — virtual-method-call form |
| #37-8 | *(shipped, DD-0017)* up/down-cast form — `static_cast<T*>` |
| #37-9 | *(shipped, DD-0018)* scalar construction form — `new ClassName(args)` |
| #37-9c | *(shipped, DD-0019)* explicit destructor-call form — `e->~ClassName()` |
| #37-9d | *(shipped, DD-0020)* array-construction form — `new ClassName[count]` |
| #37-9e | *(shipped, DD-0021)* placement-construction form — `new (ptr) ClassName(args)` |
| #37-9f | **(this DD's subject)** deallocation form — `delete e` / `delete[] e` — the last headless renderer form |
| **— headless ceiling —** | after #37-9f the headless renderer family is exhausted; the work below is `Program`/DTM-coupled and a test-before-push blocker, not headless |
| #37-7b … #37-9f recognition | the `HighFunction` pattern-recognition passes that drive each renderer — deferred, not headless |
| #37-10+ | `CppMethod` signature / `DataType` resolution (DTM-coupled, incl. constructor-overload selection); templates; operators |

## Rejected alternatives

- **Give `delete` a `CppClass` parameter for symmetry with the construction
  forms.** Rejected: C++ `delete e` names no type — the operand is a pointer and
  the type is inferred from it. A `CppClass` parameter would be dead weight the
  renderer could not faithfully use; the honest signature is the receiver
  expression plus the array flag.
- **Split scalar `delete` and array `delete[]` into two slices/methods.**
  Rejected: they are the same unary-operator rendering differing only by the `[]`,
  which a single boolean cleanly expresses; two methods would duplicate the same
  validation and rendering for no gain.
- **Give the deallocation form a neutral fallback for parity with #37-7/#37-8.**
  Rejected: it reads no model fact at all, so there is nothing to fall back from;
  the honest contract is to reject a malformed receiver and otherwise always
  render the `delete` expression.
- **Fold the destructor call into the `delete` rendering (`(e->~T(), free(e))`).**
  Rejected: `delete` *is* the C++ surface idiom that subsumes the dtor + free; the
  explicit-destructor rendering is already the separate #37-9c form, and pairing
  the dtor with the free is a recognition concern, not a rendering one.
- **Invent further headless renderer forms after #37-9f to keep the sprint
  going.** Rejected: the family is closed — RFC §5's idioms are all covered, and
  anything further (recognition passes, signatures, templates, operators) is
  `Program`/DTM-coupled and a test-before-push blocker. The honest next step after
  the #37-9f impl is to flag that ceiling, not to manufacture micro-forms.

## References

- [RFC-0001](../rfcs/0001-cpp-frontend.md) §5 `CppDecompilerHints` — the headless
  renderer this closes out with the deallocation form.
- [DD-0018](0018-rec37-construction-renderer.md) — #37-9, the construction form
  this is the deallocation counterpart to; its follow-on list named `delete`.
- [DD-0021](0021-rec37-placement-new-renderer.md) — #37-9e, the form shipped just
  before this, which identified `delete` as the last remaining headless form.
- [DD-0019](0019-rec37-destructor-call-renderer.md) — #37-9c, the explicit
  destructor *rendering* (distinct from the dtor the recognition pass pairs with
  `operator delete`); [DD-0016](0016-rec37-decompiler-hints-renderer.md) — #37-7,
  the renderer and the renderer/recognition split this reuses.
- Model the renderer reads: **none** — #37-9f is the family's only form that
  consults no `CppTypeSystem` fact. `CppDecompilerHints.java` is the
  #37-7/#37-8/#37-9/#37-9c/#37-9d/#37-9e renderer this extends.

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
