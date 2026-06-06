---
number: 0016
title: Rec 37 #37-7 — CppDecompilerHints ships as a pure model-driven hint *renderer* (resolved CppTypeSystem facts → C++-style rendering strings); the HighFunction pattern-recognition pass is the deferred Program-coupled wrapper
status: accepted
date: 2026-06-06
audit_rec: 37
---

# Decision 0016: the C++ decompiler hints (RFC §5) split into a headless renderer core and a deferred decompiler pass; the renderer ships first, virtual-method-call form first

## Context

The model layer of the Rec 37 frontend is now coherent. Across #37-2 … #37-6c
(DD-0011 … DD-0015) the `CppTypeSystem` holds, for a class that has been fed:

- inheritance edges with offsets — `CppClass.getBaseClasses()` of
  `CppBaseClass` (`getOffset`, `isVirtual`, `isPublic`), from the #37-4
  `CppRttiFeeder` ([DD-0013](0013-rec37-rtti-inheritance-feeder.md));
- declared methods with `const`/`static`/calling-convention qualifiers —
  `CppClass.getMethods()`, from the #37-3 `CppDemanglingFeeder`
  ([DD-0012](0012-rec37-demangling-feeder.md));
- a vtable whose slots are **name-resolved and unified onto the canonical
  declared methods** — `CppClass.getVtable().getSlot(i)`, from the #37-6
  `CppVTableFeeder` ([DD-0014](0014-rec37-vtable-feeder.md)) made coherent by the
  #37-6c `CppVtableReconciler` ([DD-0015](0015-rec37-vtable-reconciler.md)).

That is exactly the state [RFC-0001](../rfcs/0001-cpp-frontend.md) §5
(`CppDecompilerHints`) is meant to consume. RFC §5 lists the C++-style
renderings the frontend should surface in place of the raw C-style decompiler
output:

- `obj->method(args)` instead of `(*(*obj))[3](obj, args)` — a virtual call
  through a vtable slot;
- `static_cast<Base*>(d)` instead of `d + offsetof(Derived, base)` — an
  up/down-cast across an inheritance edge;
- `new Foo(args)` instead of `malloc(...) + Foo_ctor(...)` — a heap construction.

RFC §5 says the hints "are produced from the `CppTypeSystem` state by a new
analysis pass between the existing type-inference pass and the
output-emission pass." That single sentence bundles two very different
responsibilities, and this DD grounds the slice that separates them.

### Why this splits — and why the renderer half is the headless slice

Producing a hint requires two things:

1. **Recognising the raw pattern** in the decompiled function — that a given
   `PcodeOp`/`Varnode` graph *is* a load-of-vtable-then-indirect-call, or that a
   pointer add by a constant offset *is* a base-subobject adjustment, and
   recovering which `(class, slot)` or `(derived, base, offset)` it corresponds
   to. This is inherently coupled to a `Program` / `HighFunction` / the
   decompiler's p-code: it has no meaning without a decompiled function to scan.
2. **Rendering** the recognised fact as a C++-style string — given "a virtual
   call through slot *i* of class *C* with receiver expression `e` and argument
   expressions `a…`", emit `e->name(a…)` where `name = C.getVtable().getSlot(i)`.
   This is a pure function of *already-resolved model objects* plus the operand
   expressions the pass hands in; it needs no `Program`, no `DataTypeManager`,
   and no decompiler handle.

Responsibility (2) is the clean next **headless** step — the same producer /
program-scanning-wrapper seam every Rec 37 component has used (#37-3/#37-4/#37-6
shipped a headless pure-model core; the #37-4b/#37-5/#37-6b program-scanning
wrappers are deferred test-before-push blockers because they need a `Program`).
Responsibility (1) is the natural deferred wrapper. Grounding the renderer now
keeps the headless queue moving and gives the eventual decompiler pass a
fully-tested rendering contract to call.

## What the model already provides (grounding)

Every fact the renderer needs is already present; **no new model type or mutator
is required** (the first slice that has needed none since #37-2):

- `CppClass.getVtable()` (`CppClass.java:129`) → `CppVTable.getSlot(int)` /
  `getSlotCount()` (`CppVTable.java:60`/`:67`) — the name-resolved slot method
  (post-#37-6c). `CppMethod.getName()` (`CppMethod.java:57`), `isConst()`
  (`:130`) — what the virtual-call form renders.
- `CppClass.getBaseClasses()` (`CppClass.java:103`) of `CppBaseClass`
  (`getBaseClass :56`, `getOffset :63`, `isVirtual :70`, `isPublic :77`) — what
  the up/down-cast form matches an offset against and renders.
- `CppClass.getName()` (`CppClass.java:61`) — the type name in
  `static_cast<Base*>` / `new Foo(...)`.
- `CppTypeSystem.getCppClass(name)` (`CppTypeSystem.java:84`) — to resolve a
  class the pass names.

## The decision

1. **#37-7 ships a pure model-driven hint *renderer*: `CppDecompilerHints`, in
   `Ghidra/Features/Base` package `ghidra.app.util.cpp`, beside the feeders.**
   It is a stateless renderer of *already-resolved* model facts into the
   C++-style rendering strings RFC §5 lists. It holds no `Program`, no
   `DataTypeManager`, and no decompiler/`HighFunction` reference; it never scans,
   never demangles, never parses, and never mutates the model. Its inputs are
   model objects (a `CppClass`, a vtable slot index, a `CppBaseClass` edge) plus
   the operand expressions (receiver / arguments / source pointer) supplied as
   opaque strings by whatever drives it. Its output is the rendered string (or a
   small immutable hint record carrying it).

2. **The decompiler-side pattern-recognition pass — which walks a `HighFunction`,
   recognises the raw C-style idiom, recovers the `(class, slot)` /
   `(derived, base, offset)` it denotes, and feeds the renderer — is the deferred
   `#37-7b`/`#37-8b`/`#37-9b` Program-coupled wrapper**, not part of this slice.
   It is a test-before-push blocker (needs a decompiled `Program`), exactly like
   #37-4b/#37-5/#37-6b. Keeping it out of #37-7 is what makes #37-7 headless.

3. **The first renderer slice renders the virtual-method-call form**
   (`receiver->method(args)`), because it is the direct consumer of the
   name-resolved vtable that #37-6c just produced and is RFC §5's flagship
   example. Real rendering logic this exercises — not mere concatenation:
   bounds-checking the slot index against `getSlotCount()`; falling back to a
   neutral rendering when the slot method name is absent/unresolved; choosing
   `->` vs `.` from whether the receiver is a pointer; optionally qualifying with
   the owning class name. The up/down-cast renderer (RFC §5 bullet 2; matches an
   offset to a `CppBaseClass` edge, picks cast direction, and must decline a
   static cast for a *virtual* base whose offset is dynamic) and the
   construction renderer (bullet 3) are follow-on renderer slices, each still
   headless, each with its own deferred recognition pass.

4. **Hints are advisory and additive (RFC invariant).** The renderer only
   *produces* a string/record; it never rewrites p-code, never replaces a
   non-C++ analysis pass, and the eventual pass attaches the result as something
   the user can fall back from. Nothing here changes existing decompiler output;
   it adds a rendering the deferred pass may surface.

5. **No `DataType`/signature resolution, no name parsing, no model mutation.**
   The renderer reads the model the feeders built and the reconciler unified; it
   does not resolve parameter/return `DataType`s (still the DTM/`Program`-coupled
   #37-10+ work) and does not invent model state.

## Validation

#37-7 ships fast **headless** JUnit in
`Ghidra/Features/Base/src/test/java/ghidra/app/util/cpp/`
(`CppDecompilerHintsTest`, `AbstractGenericTest`) that builds resolved model
fixtures directly through the model setters / feeders — no `Program`, no
`DataTypeManager`, no decompiler. Tests assert, for the virtual-method-call
renderer:

- a call through a valid, name-resolved slot renders `receiver->name(args)` with
  the arguments joined in order;
- a pointer receiver renders `->` and a value receiver renders `.`;
- an out-of-range slot index is rejected (or rendered as a documented neutral
  fallback, per the impl's chosen contract) rather than throwing an unchecked
  index error to the caller;
- a slot whose method name is blank/unresolved falls back to a neutral rendering
  rather than emitting a malformed call;
- the renderer holds no model reference between calls (stateless: same inputs →
  same output, in any order).

Gating reminders specific to #37-7 (each a hard local gate before push):

- The renderer and its test are Java-only and headless, so `gradle :Base:test`
  is the validating gate (run the new test plus the sibling `Cpp*Test`s for no
  regression); no `--full` C++ precheck applies ([[always-test-before-push]]).
- The new `CppDecompilerHints.java` carries the inline `IP: GHIDRA` Apache
  header, so it passes `gradle :Base:ip` via the header with **no
  `certification.manifest` entry** (as for #37-2/#37-3/#37-4/#37-6/#37-6c). Run
  `gradle :Base:ip` to confirm ([[new-source-file-ip-manifest]]). The
  `cppRaiiAudit` gate is C++-only and does not apply ([[new-cpp-file-raii-audit]]).

## Sequencing (refines DD-0015's table)

| PR | Scope |
|---|---|
| #37-2 … #37-6 | *(shipped)* model skeleton; demangling, RTTI (Itanium) & vtable feeders |
| #37-6c | *(shipped, DD-0015)* `CppVtableReconciler` — unifies vtable slots with declared methods |
| #37-7 | **(this DD's subject)** `CppDecompilerHints` renderer — headless pure model→string; **virtual-method-call form first** |
| #37-8 / #37-9 | renderer forms for up/down-casts and ctor/dtor-construction — each still headless model→string |
| #37-4b / #37-5 / #37-6b | RTTI (Itanium/MSVC) & vtable program-scan wrappers — deferred, not headless |
| #37-7b / #37-8b / #37-9b | the `HighFunction` pattern-recognition passes that recognise the raw idioms and drive the renderer — deferred, not headless |
| #37-10+ | `CppMethod` signature / `DataType` resolution (DTM-coupled); templates; operators |

## Rejected alternatives

- **Ship the whole `CppDecompilerHints` as one analysis pass (recognition +
  rendering together).** Rejected: the recognition half is inseparable from a
  `Program`/`HighFunction`, so bundling them makes the slice untestable headlessly
  and violates the test-before-push rule. The renderer/pass split mirrors the
  core/wrapper seam used for every other Rec 37 component.
- **Make the up/down-cast renderer the first form.** Rejected (ordering only):
  the virtual-call renderer is the direct consumer of #37-6c's just-delivered
  name-resolved slots and is RFC §5's lead example, so it is the tightest next
  step; the cast renderer follows and is equally headless.
- **Have the renderer take a `Program`/`HighFunction` and pull operands itself.**
  Rejected: that drags the decompiler coupling back in. The renderer takes
  operand expressions as opaque strings the (deferred) pass supplies, keeping it
  a pure function of model facts.
- **Emit hints by mutating the model (e.g. stashing rendered strings on
  `CppMethod`).** Rejected: hints are advisory decompiler-output decorations, not
  model state; the model stays the demangler/RTTI/vtable truth, and the renderer
  is read-only over it (DD-0011 projection guardrail in spirit).
- **Defer §5 entirely and jump to `DataType`/signature resolution.** Rejected:
  that work is DTM/`Program`-coupled (see [DD-0015](0015-rec37-vtable-reconciler.md))
  and not headless; the renderer is the available headless step now.

## References

- [RFC-0001](../rfcs/0001-cpp-frontend.md) §5 `CppDecompilerHints` — the parent
  proposal; this slice supplies the headless rendering half of §5.
- [DD-0015](0015-rec37-vtable-reconciler.md) — #37-6c, which made vtable slots
  name-resolved (the virtual-call renderer's input); [DD-0013](0013-rec37-rtti-inheritance-feeder.md)
  — #37-4, the inheritance edges the cast renderer will consume;
  [DD-0011](0011-rec37-cpptypesystem-skeleton.md) — the model and the projection
  guardrail.
- Model the renderer reads (Base, all shipped):
  `Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppVTable.java`
  (`getSlot :60`, `getSlotCount :67`), `CppMethod.java` (`getName :57`,
  `isConst :130`), `CppClass.java` (`getVtable :129`, `getBaseClasses :103`,
  `getName :61`), `CppBaseClass.java` (`getOffset :63`, `isVirtual :70`,
  `isPublic :77`), `CppTypeSystem.java` (`getCppClass :84`).

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
