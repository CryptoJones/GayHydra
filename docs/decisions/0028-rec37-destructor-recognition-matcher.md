---
number: 0028
title: Rec 37 #37-9c-b-1 — the explicit-destructor recognition matcher recovers (callTarget, receiver) from a direct CALL, the same direct-call shape the delete matcher established; it is the second form to use that shape and is kept a per-form twin of CppDeleteRecognizer rather than prematurely unified, with the rule-of-three extraction earned at the third form (the constructor #37-9b)
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0028: the #37-9c-b-1 matcher recovers the destructor call's structural facts

## Context

[DD-0026](0026-rec37-delete-recognition-matcher.md) shipped the first
direct-call matcher,
[`CppDeleteRecognizer`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDeleteRecognizer.java),
and established the **direct-call recognition shape** (recover the `CALL`'s
`input[0]` target address and its cast-stripped `input[1]` receiver) as the
counterpart to the `#37-7b` indirect vtable-dispatch shape. Its DD closed with a
standing note: the shared extractor is *not* unified yet because the
rule-of-three threshold is not met — keep it per-form until a third user appears.

This slice (`#37-9c-b-1`) is the **second** user of that shape: the matcher half
of the explicit (non-virtual) destructor recognition form. An explicit destructor
call `p->~C()` compiles to a direct call passing `this` as its sole explicit
argument — structurally the same `CALL target, receiver` p-code the delete idiom
produces. The renderer this form feeds,
[`CppDecompilerHints.renderDestructorCall`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDecompilerHints.java)
(DD-0016), emits `receiver->~ClassName()`.

## Decision

Ship
[`CppDestructorRecognizer`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDestructorRecognizer.java):
a stateless p-code matcher whose `recognize(PcodeOp)` returns
`DestructorCall(callTarget, receiver)` for a direct `CALL` carrying a resolvable
memory target address and at least one argument, or `null` otherwise. It reads
only the SSA graph; it holds no `Program` and decides nothing about whether the
callee actually *is* a destructor (that is the driver's job, `#37-9c-b-2`).

Three choices pin it:

1. **It recovers the same two facts as the delete matcher, by the same code.**
   `input[0]` is the callee entry address (a direct `CALL` encodes its target
   there); `input[1]`, after [`stripCopyCast`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDestructorRecognizer.java)
   skips any `CAST`/`COPY` pass-through chain, is the receiver varnode whose
   `HighVariable` carries the printable `this` name. This is byte-for-byte the
   `CppDeleteRecognizer` recovery.

2. **It is kept a per-form twin, not prematurely unified.** Per DD-0026's standing
   note, this is the *second* form to use the direct-call shape; the rule-of-three
   threshold is met at the *third* (the constructor `#37-9b`, whose `C::C(this,
   args)` is the same shape again). Unifying now would mean a wide refactor of the
   already-shipped, already-grounded delete form (rename, re-wire its driver and
   test, rewrite DD-0026/0027 prose) for a one-instance saving — premature. So the
   two matchers stand as honest per-form twins until the constructor lands, when
   the extraction into a shared `CppDirectCallRecognizer` becomes a clean dedicated
   commit with three real call sites proving the abstraction's shape. The twin's
   javadoc flags this explicitly so the duplication reads as deliberate, not
   accidental.

3. **The `stripCopyCast` pass-through skip is retained even though `this` usually
   needs no cast.** A `delete` interposes a `void*` `CAST` (the deallocation
   function's parameter type); a destructor takes its `this` as a typed `C*`, so
   the cast is usually absent. The skip is kept anyway — a receiver can still reach
   the call through a `COPY`/`CAST` (e.g. a base-subobject pointer adjustment), and
   skipping the chain is harmless (a no-op) when it is not there. Dropping it would
   be a guess that no adjustment ever interposes; keeping it costs nothing.

## Consequences

- The destructor form now has its recognition primitive: a real x86-64
  `f(C* p)` whose body is a single `call ~C` recovers the `~C` call target
  (resolving to the destructor function) and the `param_1` receiver, verified
  end-to-end by the harness integration test
  ([`CppDestructorRecognizerTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppDestructorRecognizerTest.java)),
  plus the decline-non-`CALL` and null-safe cases.
- The matcher is advisory and total-failure-safe, mirroring its siblings: a
  non-`CALL` op, an argument-less call, or a call with no resolvable target or
  receiver yields `null`, never an exception.
- The direct-call shape now has two concrete users (delete, destructor). One more
  (the constructor) trips the rule of three; the cast/ctor/new/placement forms
  that follow either reuse this shape (callee-identified direct calls) or the
  `#37-7b` structural shape (cast).
- Verified locally before commit (test-before-push): `gradle :Base:ip`,
  `gradle :Base:test --tests 'ghidra.app.util.cpp.*'`, `gradle :Decompiler:ip`, and
  `gradle :Decompiler:integrationTest --tests CppDestructorRecognizerTest` (system
  `gradle` 8.5).
