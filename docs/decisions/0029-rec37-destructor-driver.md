---
number: 0029
title: Rec 37 #37-9c-b-2 — the destructor driver closes the explicit-destructor recognition loop: it walks a HighFunction, runs the CppDestructorRecognizer matcher on each direct CALL, resolves the callee to a Function, reads the destructed class from the callee's ~ClassName local name (not the receiver type), resolves that CppClass in a CppTypeSystem, reads receiver-is-pointer from the receiver HighVariable, and dispatches to CppDecompilerHints.renderDestructorCall
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0029: the #37-9c-b-2 driver resolves the destructed class and renders the call

## Context

[DD-0028](0028-rec37-destructor-recognition-matcher.md) shipped `#37-9c-b-1`:
[`CppDestructorRecognizer`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDestructorRecognizer.java),
a pure p-code matcher that recovers `(callTarget, receiver)` from a candidate
direct destructor `CALL` but deliberately stops there, leaving the two
program-coupled concerns — deciding whether the target *is* a destructor (and of
which class), and reading whether the receiver is a pointer — to a driver slice.
This is that slice (`#37-9c-b-2`): the piece that turns a recognised call into the
C++ hint the shipped
[`CppDecompilerHints.renderDestructorCall`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDecompilerHints.java)
renderer (DD-0016) produces — `receiver->~ClassName()` — closing the loop.

## Decision

Ship
[`CppDestructorDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDestructorDriver.java):
constructed over a `CppDecompilerHints` renderer **and** a `CppTypeSystem` (like the
`#37-7b` virtual-call driver, DD-0025, and unlike the `#37-9f-b` delete driver
which models no class). Its `recognizeAndRender(HighFunction)` walks the function's
p-code, runs the matcher on each `CALL`, and for every recognised candidate
resolves the call target to a `Function`, classifies its name, resolves the class,
reads receiver-is-pointer, and dispatches to the renderer, returning a list of
`RenderedDestructorCall(site, rendering)`.

Three choices pin it:

1. **The destructed class comes from the callee's name, not the receiver's type.**
   The renderer emits `receiver->~ClassName()`, and the authoritative `ClassName`
   is the destructor being *invoked*, which the callee names. A destructor
   function's Ghidra local name is `~ClassName` — grounded against the in-tree GNU
   demangler parser tests: `_ZN6Magick5ImageD1Ev` demangles to
   `Magick::Image::~Image()` with local `getName()` `~Image`. So the driver holds
   the program (via `function.getFunction().getProgram()`), resolves the recovered
   `callTarget` to a `Function`, strips the leading `~` off its name, and looks the
   `CppClass` up by the remaining text in the type system. Reading the class from
   the callee (not the possibly base-adjusted receiver type) is what makes a base
   destructor invoked on a derived pointer render as the base's `~ClassName`. A
   callee whose name has no leading `~`, or whose class is not modelled, contributes
   no hint. Unlike the delete classifier this needs **no** `.`→space normalisation:
   a destructor local name (`~C`, `~Image`) carries no namespace separator, where
   `operator.delete` did.

2. **Receiver expression and pointer-ness come from the receiver `HighVariable`.**
   The matcher already stripped any `CAST`/`COPY` off the call argument to reach the
   underlying receiver varnode; the driver takes that varnode's `HighVariable` name
   (`param_1`) — the identifier the decompiler prints — and selects `->` over `.`
   from whether its `getDataType()` is a `Pointer`. The usual `this` is a `C*`, so
   the pointer form is the common case; the value form (`.`) falls out of the same
   single `instanceof Pointer` test without a separate code path.

3. **It assumes the demangler has run.** Matching the demangled `~ClassName` (not a
   mangled `_ZN...D1Ev` symbol) encodes the pass's one standing assumption: the
   demangler analyzer has run — which it has, by the time a function decompiles to a
   `HighFunction` in a fully-analyzed program. A not-yet-demangled destructor symbol
   would be declined.

## Consequences

- The explicit-destructor form of Rec 37 recognition is end-to-end: recognise the
  direct call → resolve and classify the callee as `~ClassName` → resolve the class
  → render `param_1->~C()` keyed to the call-site address. The harness integration
  test
  ([`CppDestructorDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppDestructorDriverTest.java))
  asserts that string against the real decompiler output for a callee named `~C`
  with class `C` modelled, plus the decline-when-not-a-destructor,
  decline-when-class-not-modelled, and null-argument cases.
- The driver is advisory and total-failure-safe, mirroring the matcher and
  renderer: a target that resolves to no function, a non-`~ClassName` callee, an
  unmodelled class, or a receiver with no printable name contributes no hint rather
  than raising or mis-rendering.
- Known limitations, each a candidate future slice: the dtor-then-`operator delete`
  pairing a real `delete` of a non-trivial type emits is not yet fused — this slice
  renders the explicit destructor call on its own terms, and the `#37-9f-b` delete
  driver renders the following `operator delete`; fusing them so a `delete p` is
  rendered once is a cross-form refinement. The value-receiver (`.`) form is wired
  (a single `instanceof Pointer` test) but exercised by the renderer's own unit
  tests rather than the driver harness, since a value `this` is not the idiomatic
  register-passed shape.
- With the explicit-destructor form complete, three of the seven recognition forms
  are end-to-end (virtual call `#37-7b`, delete `#37-9f-b`, destructor `#37-9c-b`),
  and the direct-call shape now has **two** callee-identified users (delete,
  destructor). The constructor `#37-9b` is the third user — the rule-of-three point
  at which `CppDeleteRecognizer` and `CppDestructorRecognizer` should be unified into
  a shared `CppDirectCallRecognizer` as a dedicated refactor commit.
- Verified locally before commit (test-before-push): `gradle :Base:ip`,
  `gradle :Base:test --tests 'ghidra.app.util.cpp.*'`, `gradle :Decompiler:ip`, and
  `gradle :Decompiler:integrationTest --tests CppDestructorDriverTest --tests
  CppDestructorRecognizerTest` (system `gradle` 8.5).
