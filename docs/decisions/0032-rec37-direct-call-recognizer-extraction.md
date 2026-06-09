---
number: 0032
title: Rec 37 #37-9b refactor — extract the shared CppDirectCallRecognizer at the rule-of-three threshold; the direct-call recovery (input[0] target + cast-stripped input[1] receiver) duplicated across the delete, destructor, and constructor forms is unified into one stateless matcher, and the two pass-through per-form recognizers (CppDeleteRecognizer, CppDestructorRecognizer) are deleted outright rather than left as shims
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0032: extract the shared CppDirectCallRecognizer (rule-of-three)

## Context

Three Rec 37 recognition forms recover the same two structural facts from a direct
`CALL`: the callee entry address in `input[0]`, and the first-argument *receiver*
varnode reached after stripping any interposed `CAST`/`COPY` pass-through off
`input[1]`.

- [DD-0026](0026-rec37-delete-recognition-matcher.md) established the recovery for the
  `#37-9f-b` delete form (first user).
- [DD-0028](0028-rec37-destructor-recognition-matcher.md) reused it byte-for-byte for
  the `#37-9c-b` destructor form (second user).
- [DD-0030](0030-rec37-constructor-recognition-matcher.md) used it a third time inside
  the `#37-9b` constructor *fusion* matcher (third user).

Every one of those DDs carried the same standing note: the shared extractor is
deliberately **not** unified until a third user appears (the rule of three), at which
point the extraction is earned and is done as its own dedicated commit — the
abstraction's shape proven by three real call sites rather than guessed at the first
or second instance. DD-0030 named this extraction "the immediate next refactor." This
is that refactor.

## Decision

Ship
[`CppDirectCallRecognizer`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDirectCallRecognizer.java):
a single stateless p-code matcher carrying the recovery the three forms shared. Its
surface is the union of what the three users actually called:

- `record DirectCall(Address callTarget, Varnode receiver)` — the two recovered facts.
- `DirectCall recognize(PcodeOp callSite)` — null/non-`CALL`/argument-less → `null`;
  otherwise `callTarget` via `callTargetAddress` and `receiver` =
  `stripCopyCast(input[1])`. The delete and destructor drivers call this.
- `Address callTargetAddress(PcodeOp call)` — null-safe; returns `input[0]`'s memory
  entry address or `null`. The constructor fusion matcher calls this for the
  *allocation* call (which has no receiver of interest, only a target).
- `stripCopyCast` stays private — it is the recovery's internal step, not surface.

Two choices pin the refactor:

1. **The per-form recognizers are deleted, not shimmed.** `CppDeleteRecognizer` and
   `CppDestructorRecognizer` (each a `record DeleteCall`/`DestructorCall` plus a
   `recognize`) are removed outright, along with their dedicated unit tests
   `CppDeleteRecognizerTest` and `CppDestructorRecognizerTest`. Leaving them as
   pass-through wrappers that delegate to the new class, or re-exporting their old
   record types, would be a backwards-compatibility shim for an internal,
   single-fork, not-yet-published API with exactly two in-tree callers (the two
   drivers) — pure dead weight. The drivers are updated to consume
   `CppDirectCallRecognizer.DirectCall` directly. The constructor matcher
   ([DD-0030](0030-rec37-constructor-recognition-matcher.md)) is rewritten to delegate
   its recovery to `recognize`/`callTargetAddress` and keep only the form-specific
   *fusion walk-back* (the receiver's defining op must itself be a `CALL`, whose
   target is the allocation).

2. **The recovery's behaviour is unchanged; only its home moved.** This is a pure
   structural refactor: the three forms' recognition semantics — what matches, what is
   declined, what is recovered — are identical before and after. The consolidated
   recovery coverage moves to one integration test
   ([`CppDirectCallRecognizerTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppDirectCallRecognizerTest.java)),
   which exercises the recovery once on a typed-receiver direct call and once on a
   `void *`-receiver call (the delete shape, where a `CAST` is interposed and stripped),
   plus the `callTargetAddress` reader, non-`CALL` decline, and null-safety. The
   per-form *semantics* stay covered by the form drivers' own tests
   (`CppDeleteDriverTest`, `CppDestructorDriverTest`, `CppConstructorDriverTest`) and
   the constructor matcher test (`CppConstructorRecognizerTest`).

## Consequences

- The direct-call recovery now lives in exactly one place. The two per-form
  recognizers and their unit tests are gone; net source shrinks. The three forms'
  behaviour is unchanged.
- `CppDirectCallRecognizer` is the single point of future refinement for direct-call
  recovery — e.g. when the `#37-7b` argument-threading work needs more than the first
  receiver, it extends one matcher, not three copies.
- DDs [0026](0026-rec37-delete-recognition-matcher.md) and
  [0028](0028-rec37-destructor-recognition-matcher.md) now contain dead relative links
  to the deleted `CppDeleteRecognizer.java` / `CppDestructorRecognizer.java`. They are
  left intact as historical records of the state at *their* decision time; this DD is
  the authoritative record that the recovery has since been unified here.
- The remaining Rec 37 recognition forms are unaffected and unblocked: array-`new[]`
  (`#37-9d-b`) and placement (`#37-9e-b`) are construction variants that reuse the
  fusion shape (and thus `CppDirectCallRecognizer` transitively); the cast form
  (`#37-8b`) follows the `#37-7b` structural shape, not the direct-call shape.
- Verified locally before commit (test-before-push, local-only — no push, no release):
  `gradle :Base:ip`, `gradle :Base:test --tests 'ghidra.app.util.cpp.*'`,
  `gradle :Decompiler:ip`, and `gradle :Decompiler:integrationTest` over
  `CppDirectCallRecognizerTest`, `CppDeleteDriverTest`, `CppDestructorDriverTest`,
  `CppConstructorRecognizerTest`, `CppConstructorDriverTest` (system `gradle` 8.5) —
  all green (21/21: 5 + 4 + 4 + 3 + 5).
