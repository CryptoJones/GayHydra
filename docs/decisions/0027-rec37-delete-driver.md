---
number: 0027
title: Rec 37 #37-9f-b-2 — the delete driver closes the deallocation recognition loop: it walks a HighFunction, runs the CppDeleteRecognizer matcher on each direct CALL, resolves the recovered call target to a Function, classifies its name as scalar operator delete / array operator delete[] (or neither), and dispatches to CppDecompilerHints.renderDelete. It resolves no CppClass — delete names no type — so it is the one driver that needs no CppTypeSystem
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0027: the #37-9f-b driver resolves the callee name and renders the delete

## Context

[DD-0026](0026-rec37-delete-recognition-matcher.md) shipped `#37-9f-b-1`:
[`CppDeleteRecognizer`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDeleteRecognizer.java),
a pure p-code matcher that recovers `(callTarget, receiver)` from a candidate
direct deallocation `CALL` but deliberately stops there, leaving the two
program-coupled concerns — deciding whether the target *is* a deallocation
function (and scalar vs array), and rendering the receiver expression — to a driver
slice. This is that slice (`#37-9f-b-2`): the piece that turns a recognised call
into the C++ hint string the shipped
[`CppDecompilerHints.renderDelete`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDecompilerHints.java)
renderer (DD-0022) produces, closing the loop the renderer's javadoc opened.

## Decision

Ship
[`CppDeleteDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDeleteDriver.java):
constructed over a `CppDecompilerHints` renderer alone, its
`recognizeAndRender(HighFunction)` walks the function's p-code, runs the matcher on
each `CALL`, and for every recognised candidate resolves the call target to a
`Function`, classifies its name, and (when it is a deallocation) dispatches to the
renderer, returning a list of `RenderedDelete(site, rendering)`.

Three choices pin it:

1. **This is the one driver that resolves no `CppClass`, so it needs no
   `CppTypeSystem`.** Every other recognition form maps a receiver or a constructed
   type onto a modelled class; `delete e` / `delete[] e` names no type at all
   (`renderDelete` takes no `CppClass` and consults no vtable, per DD-0022), so the
   driver's only model fact is the receiver's printed name, which it reads straight
   off the receiver varnode's `HighVariable`. The constructor therefore takes the
   renderer and nothing else — a real, principled asymmetry with the `#37-7b`
   virtual-call driver, not an oversight.

2. **The scalar-vs-array decision is the callee's name, resolved here.** The matcher
   could not read it (it holds no `Program`), so the driver holds the program — via
   `function.getFunction().getProgram()` — resolves the recovered `callTarget`
   address to a `Function`, and classifies `getName()`. The names matched are the
   demangled forms Ghidra actually emits, grounded against the in-tree GNU demangler
   parser tests: `_ZdlPv` → `operator.delete` (scalar) and `_ZdaPv` →
   `operator.delete[]` (array), with Ghidra's `.` namespace separator. The classifier
   normalises that `.` to a space (so the plain `operator delete` form a different
   demangler might emit also matches) and checks the **array form first**, because
   `operator delete[]` contains the scalar substring. A callee that resolves to no
   function, or whose name is neither, contributes no hint. Matching demangled (not
   mangled) names encodes the pass's one standing assumption: the demangler analyzer
   has run — which it has, by the time a function decompiles to a `HighFunction` in a
   fully-analyzed program.

3. **The receiver expression is the `HighVariable`'s own name.** The matcher already
   stripped the `void*` `CAST` off the call argument to reach the underlying receiver
   varnode; the driver takes that varnode's `HighVariable` name (`param_1`) — the
   identifier the decompiler itself prints — and hands it to `renderDelete`. No
   pointer-ness flag is needed: `delete` is a unary operator on the pointer.

## Consequences

- The deallocation form of Rec 37 recognition is end-to-end: recognise the direct
  call → resolve and classify the callee → render `delete param_1` (scalar) or
  `delete[] param_1` (array) keyed to the call-site address. The harness integration
  test
  ([`CppDeleteDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppDeleteDriverTest.java))
  asserts exactly those strings against the real decompiler output for callees named
  `operator.delete` and `operator.delete[]`, plus the decline-when-not-a-deallocation
  and null-renderer cases.
- The driver is advisory and total-failure-safe, mirroring the matcher and renderer:
  a target that resolves to no function, a non-`operator delete` callee, a receiver
  with no `HighVariable` or no printable name contributes no hint rather than raising
  or mis-rendering.
- Known limitations, each a candidate future slice: the dtor-then-`operator delete`
  pairing a real `delete` of a non-trivial type emits is not yet fused — this slice
  renders the `operator delete` call itself, and consuming the preceding destructor
  call (so the future `#37-9c-b` explicit-destructor driver does not also render it)
  is a cross-form refinement; and only demangled callee names are matched (a
  not-yet-demangled `_ZdlPv` symbol would be declined), a deliberate scope line given
  recognition runs after analysis.
- With the deallocation form complete, two of the seven recognition forms are
  end-to-end (virtual call `#37-7b`, delete `#37-9f-b`) and the project now has both
  recognition primitives: the indirect vtable-dispatch shape and the direct-call
  shape. The remaining four callee-identified forms (ctor / dtor / `new` / placement)
  reuse the direct-call primitive established here; cast (`#37-8b`) follows the
  structural `#37-7b` shape.
- Verified locally before commit (test-before-push): `gradle :Base:ip`,
  `gradle :Base:test --tests 'ghidra.app.util.cpp.*'`, `gradle :Decompiler:ip`, and
  `gradle :Decompiler:integrationTest --tests CppDeleteDriverTest --tests
  CppDeleteRecognizerTest` (system `gradle` >= 8.5, since the Ghidra `gradlew` shim
  refuses the non-PUBLIC/DEV release name).
