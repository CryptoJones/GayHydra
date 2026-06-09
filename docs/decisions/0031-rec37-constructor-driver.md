---
number: 0031
title: Rec 37 #37-9b-2 — the heap-construction driver closes the new-expression recognition loop: it walks a HighFunction, runs the CppConstructorRecognizer fusion matcher on each direct CALL, resolves the recovered constructor and allocation targets to Functions, classifies the first as a constructor by name==class-namespace and the second as operator new by name, resolves the CppClass in a CppTypeSystem, and dispatches to CppDecompilerHints.renderConstruction with no receiver and (scoped out) no arguments
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0031: the #37-9b-2 driver classifies the two callees and renders `new C()`

## Context

[DD-0030](0030-rec37-constructor-recognition-matcher.md) shipped `#37-9b-1`:
[`CppConstructorRecognizer`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppConstructorRecognizer.java),
a pure p-code *fusion* matcher that recovers `(constructorTarget, allocationTarget)`
from a heap `new C()` — a constructor `CALL` whose cast-stripped receiver is itself
the result of another `CALL` (the allocation) — but deliberately stops there, leaving
the two program-coupled classifications (is the first callee a constructor, and of
which class; is the second `operator new`) to a driver slice. This is that slice
(`#37-9b-2`): the piece that turns a recognised construction into the C++ hint the
shipped
[`CppDecompilerHints.renderConstruction`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDecompilerHints.java)
renderer (DD-0016) produces — `new ClassName(args)` — closing the loop.

## Decision

Ship
[`CppConstructorDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppConstructorDriver.java):
constructed over a `CppDecompilerHints` renderer **and** a `CppTypeSystem` (like the
`#37-7b` virtual-call driver, DD-0025, and the `#37-9c-b` destructor driver, DD-0029,
which both resolve a class; unlike the `#37-9f-b` delete driver which models none).
Its `recognizeAndRender(HighFunction)` walks the function's p-code, runs the matcher
on each `CALL`, and for every recognised construction resolves both targets to
`Function`s, classifies them, resolves the class, and dispatches to the renderer,
returning a list of `RenderedConstruction(site, rendering)`.

Three choices pin it:

1. **The constructor — and its class — is identified by `name == class namespace`.**
   A destructor wears a free lexical marker (the leading `~`); a constructor does not.
   Its demangled Ghidra form instead has the function's local name *equal to its class*:
   `getName()` equals the parent (class) namespace name. Grounded against the in-tree
   GNU demangler parser test: `_ZN3Bar4FredC1Ei` demangles to `Bar::Fred::Fred(int)`
   with local `getName()` `Fred` in namespace `Fred` (parent `Bar`). So the driver
   resolves the recovered `constructorTarget` to a `Function`, requires
   `function.getName().equals(function.getParentNamespace().getName())`, and looks the
   `CppClass` up by that name in the type system. This `name == class` test is the
   constructor's counterpart to the destructor's `~` prefix: a member method
   (`Fred::foo`, name `foo` ≠ class `Fred`) is declined, and a class not modelled
   contributes no hint.

2. **The allocation is classified as `operator new` by name, reusing the delete
   driver's normalisation.** The recovered `allocationTarget` is resolved and its name
   compared to `operator new` after normalising Ghidra's `.` namespace separator to a
   space — byte-for-byte the [`CppDeleteDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDeleteDriver.java)
   `operator.delete` classification (DD-0027). A construction on storage from any other
   call (a placement `new`'s caller-supplied buffer, a custom allocator) is declined
   here; placement is the separate `#37-9e-b` form. Requiring *both* callees to
   classify is what makes the form precise: the matcher's fusion link already proved
   "a ctor on call-produced storage"; the driver proves that ctor is a constructor and
   that storage came from `operator new`.

3. **No receiver, and arguments scoped out.** `renderConstruction` takes no receiver —
   a heap `new` *is* the allocation-plus-construction, with no printed `this` (the
   matcher never exposed the receiver varnode for exactly this reason). Constructor
   arguments are scoped out of this slice: the renderer is called with an empty list,
   matching the `#37-7b` virtual-call driver. Argument recovery and constructor-overload
   resolution are a signature/`DataType` concern — the DTM-coupled `#37-10+` work — not
   a recognition one, so a real `new C()` renders as `new C()`.

## Consequences

- The heap-construction form of Rec 37 recognition is end-to-end: recognise the fused
  alloc→ctor pair → classify the ctor as `name == class` and resolve the class →
  classify the allocation as `operator new` → render `new C()` keyed to the
  constructor call-site address. The harness integration test
  ([`CppConstructorDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppConstructorDriverTest.java))
  asserts that string against the real decompiler output for a `C* make()` doing
  `new C()`, plus the decline-when-not-a-constructor (callee name ≠ class),
  decline-when-allocation-not-operator-new, decline-when-class-not-modelled, and
  null-argument cases (driver 5/5).
- The driver is advisory and total-failure-safe, mirroring the matcher and renderer: a
  target that resolves to no function, a non-`name == class` callee, an allocation that
  is not `operator new`, or an unmodelled class contributes no hint rather than raising
  or mis-rendering.
- **Four of the seven recognition forms are now end-to-end** (virtual call `#37-7b`,
  delete `#37-9f-b`, destructor `#37-9c-b`, construction `#37-9b`). With the constructor
  landed, the direct-call recovery now has **three** concrete copies
  (`CppDeleteRecognizer`, `CppDestructorRecognizer`, and the constructor matcher's
  internal recovery) — the rule-of-three threshold DD-0026/0028/0030 named. The
  immediate next commit is the deferred extraction of a shared `CppDirectCallRecognizer`
  unifying the three; it is a dedicated refactor, not bundled into a feature slice. The
  remaining recognition forms are the array-`new[]` (`#37-9d-b`) and placement
  (`#37-9e-b`) construction variants (reusing this fusion shape) and the cast
  (`#37-8b`) form (the structural `#37-7b` shape).
- Known limitation, a candidate future slice: constructor arguments are not threaded
  (rendered `new C()`, never `new C(x, y)`), the same `#37-10+` DTM-coupled scope the
  virtual-call driver deferred.
- Verified locally before commit (test-before-push, local-only — no push, no release):
  `gradle :Base:ip`, `gradle :Base:test --tests 'ghidra.app.util.cpp.*'`,
  `gradle :Decompiler:ip`, and `gradle :Decompiler:integrationTest --tests
  CppConstructorDriverTest --tests CppConstructorRecognizerTest` (system `gradle` 8.5)
  — all green (driver 5/5, recognizer 3/3).
