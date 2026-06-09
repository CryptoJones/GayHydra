---
number: 0030
title: Rec 37 #37-9b-1 — the heap-construction recognition matcher is a FUSION matcher: it recovers (constructorTarget, allocationTarget) from a ctor CALL whose cast-stripped receiver is itself the result of another CALL (the allocation), the fusion link that distinguishes a heap new C() from stack/member construction; it is the third user of the direct-call shape, so the rule-of-three extraction into a shared CppDirectCallRecognizer is now earned and is the immediate next refactor
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0030: the #37-9b-1 matcher recovers the heap-construction's fused structural facts

## Context

[DD-0026](0026-rec37-delete-recognition-matcher.md) established the **direct-call
recognition shape** — recover a `CALL`'s `input[0]` target address and its
cast-stripped `input[1]` receiver — with
[`CppDeleteRecognizer`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDeleteRecognizer.java),
and [DD-0028](0028-rec37-destructor-recognition-matcher.md) reused it byte-for-byte
in
[`CppDestructorRecognizer`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDestructorRecognizer.java),
the second user. Both DDs carried the same standing note: the shared extractor is
deliberately *not* unified until a **third** user appears (the rule of three), and
that third user is the constructor `#37-9b`.

This slice (`#37-9b-1`) is that third user: the matcher half of the heap-`new`
construction recognition form. The renderer it feeds,
[`CppDecompilerHints.renderConstruction`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDecompilerHints.java)
(DD-0016), emits `new ClassName(args)`.

## Decision

Ship
[`CppConstructorRecognizer`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppConstructorRecognizer.java):
a stateless p-code matcher whose `recognize(PcodeOp)` returns
`ConstructedObject(constructorTarget, allocationTarget)` for a heap-`new`
construction, or `null` otherwise. It reads only the SSA graph; it holds no
`Program` and decides nothing about whether the targets actually *are* a constructor
and `operator new` (that is the driver's job, `#37-9b-2`).

Three choices pin it:

1. **It is a FUSION matcher, not a single-call matcher — and the fusion link is the
   recognition.** Unlike delete and destructor, each a single recognised `CALL`, a
   heap `new C()` is *two* linked calls. Grounded in the p-code the real decompiler
   emits for an x86-64 `C* make() { return new C(); }` (observed via the Rec 30
   headless harness, DD-0023, and a throwaway exploration since deleted): the
   decompiled C is `pCVar1 = (C *)operator_new(8L); C::C(pCVar1); return pCVar1;`,
   whose p-code is
   ```
   uniq = CALL operator.new, #0x8     // allocation: raw void* result
   rax  = CAST uniq                    // CAST void* -> C*
   CALL C::C, rax                      // constructor: this = the allocated storage
   ```
   The matcher anchors on the constructor `CALL`, strips the `CAST`/`COPY`
   pass-through off its `input[1]` receiver, and **requires that receiver's defining
   op to be another `CALL`** — whose `input[0]` is the allocation target. That single
   requirement (`receiver.getDef()` is a `CALL`) is exactly what distinguishes a heap
   `new` from an in-place construction: a stack `C c;` or a member's `this` is a
   stack/field address, *not* a call result, and is declined here. So the form's
   defining structural fact is not "a ctor call" but "a ctor call **on freshly
   allocated storage**", and the matcher encodes precisely that.

2. **It recovers two addresses; the receiver varnode is used internally only.** The
   delete and destructor matchers expose the receiver `Varnode` because their
   renderers print it (`receiver->~C()`, `delete receiver`). `renderConstruction`
   takes **no receiver** — the `new` *is* the allocation-plus-construction, so the
   constructed object has no printed receiver expression. The record therefore
   carries only the two facts the driver classifies: the `constructorTarget` (to
   confirm a constructor and resolve its class) and the `allocationTarget` (to
   confirm `operator new`). The receiver varnode is the matcher's internal stepping
   stone to the allocation, not part of its output.

3. **The rule-of-three extraction is now earned — and deferred to its own commit.**
   This is the third inlined copy of the direct-call recovery (`callTargetAddress` +
   `stripCopyCast`), the threshold DD-0026/0028 named. Rather than bundle a wide
   refactor of two already-shipped, already-grounded forms into this feature slice,
   the recovery is inlined here one last time (the matcher's javadoc says so
   explicitly), keeping this commit a narrow per-form addition. Extracting a shared
   `CppDirectCallRecognizer` that unifies `CppDeleteRecognizer`,
   `CppDestructorRecognizer`, and this matcher's internal recovery is the **immediate
   next refactor** — a dedicated commit with three real call sites proving the
   abstraction's shape, which is the rule-of-three done honestly rather than guessed
   at the first or second instance.

## Consequences

- The construction form now has its recognition primitive: a real x86-64
  `C* make()` doing `new C()` recovers the `C::C` constructor target and the
  `operator.new` allocation target, verified end-to-end by the harness integration
  test
  ([`CppConstructorRecognizerTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppConstructorRecognizerTest.java)),
  which also asserts the matcher declines the allocation `CALL` itself (its argument
  is the size constant, not a call result) and is null-safe.
- The matcher is advisory and total-failure-safe, mirroring its siblings: a non-`CALL`
  op, an argument-less call, a call with no resolvable target, a receiver that is not
  itself a call result, or that result-call having no resolvable target yields `null`,
  never an exception.
- The direct-call shape now has **three** concrete users (delete, destructor,
  constructor). The next commit is the deferred `CppDirectCallRecognizer` extraction.
  The remaining recognition forms split: the array-`new[]` (`#37-9d-b`) and placement
  (`#37-9e-b`) forms are construction variants that reuse this fusion shape (a ctor on
  allocation/placement storage); the cast form (`#37-8b`) follows the `#37-7b`
  structural shape, not the direct-call shape.
- Verified locally before commit (test-before-push, local-only — no push, no release):
  `gradle :Base:ip`, `gradle :Base:test --tests 'ghidra.app.util.cpp.*'`,
  `gradle :Decompiler:ip`, and `gradle :Decompiler:integrationTest --tests
  CppConstructorRecognizerTest` (system `gradle` 8.5) — all green (matcher 3/3).
