---
number: 0026
title: Rec 37 #37-9f-b-1 — the delete-recognition wrapper opens with a pure p-code matcher (CppDeleteRecognizer) that recovers only (callTarget, receiver) from a direct CALL in a live HighFunction; classifying the callee as operator delete / delete[] and rendering are the separate #37-9f-b-2 driver slice. Grounded in the p-code the real decompiler emits, observed through the Rec 30 headless harness, not a guessed shape
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0026: the #37-9f-b delete-recognition wrapper opens with a pure p-code matcher

## Context

With the virtual-call form complete ([DD-0024](0024-rec37-virtual-call-recognition-matcher.md)
matcher, [DD-0025](0025-rec37-virtual-call-driver.md) driver), the next of the six
remaining Rec 37 recognition forms is the deallocation idiom — the `delete e` /
`delete[] e` whose renderer
[`CppDecompilerHints.renderDelete`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDecompilerHints.java)
shipped as `#37-9f` (DD-0022). `renderDelete(String receiverExpr, boolean isArray)`
is the *only* one of the seven headless renderers that reads no `CppTypeSystem`
model fact at all: `delete` names no type, so the renderer takes no `CppClass` and
consults no vtable — it needs only the receiver expression and the array-vs-scalar
flag.

That makes delete the right place to establish the second recognition shape this
sprint needs: the **direct-call** idiom. Where the virtual-call form keys off an
indirect `CALLIND` through a vtable slot, the construction / destruction /
allocation / deallocation forms are all *direct* `CALL`s whose identity is carried
by the **callee's name** — `operator new`, `operator delete`, a constructor, a
destructor. Recovering that name is `Program`-coupled; recovering the call's
structural skeleton from the p-code is not. The deferred `#37-9f-b` wrapper bundles
the same kind of separable concerns the `#37-7b` wrapper did:

1. **Structural recovery** — given a direct call, recover the call-target address and
   the varnode carrying the receiver pointer it was handed. This lives entirely in
   the p-code SSA graph.
2. **Callee classification** — resolve the target address to a function and decide
   whether its name denotes scalar `operator delete`, array `operator delete[]`, or
   neither. This needs the `Program`.
3. **Expression rendering** — turn the receiver varnode into the C++ expression
   string `renderDelete` consumes, then call it with the array-vs-scalar flag.

## Decision

**Ship concern 1 alone as the first slice (`#37-9f-b-1`):**
[`CppDeleteRecognizer`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDeleteRecognizer.java),
a stateless static matcher whose sole entry point `recognize(PcodeOp)` returns a
`DeleteCall(Address callTarget, Varnode receiver)` record or `null`. Concerns 2 and
3 — the `HighFunction`-walking driver that resolves the callee, classifies its name,
renders the receiver expression, and dispatches to the renderer — are the next slice
(`#37-9f-b-2`), which carries its own name-resolution and varnode-to-C-expression
design and so earns its own commit and its own decision record.

Three choices pin this slice:

1. **The matcher is grounded in observed p-code, not a guessed shape.** Before
   writing a line of it, a throwaway harness test (built on DD-0023) decompiled a
   hand-assembled x86-64 `f(C* p)` whose body is a single `call operator.delete`
   forwarding `p`, and dumped the resulting syntax tree. The real call is
   `CALL operatorDelete, CAST(p)`: the callee entry is the `input[0]` address varnode
   (a direct `CALL` encodes its target there), and the deleted pointer arrives as
   `input[1]` — but through an interposed `CAST` to `void *`, the deallocation
   function's parameter type. That `CAST` is the one fact a guess gets wrong: the
   varnode whose `HighVariable` carries the printable name (`param_1`) is reached only
   by skipping the `CAST`/`COPY` pass-through (`stripCopyCast`, the same skip the
   virtual-call matcher needed). The dump's decompiled C — `operator_delete((void
   *)param_1)` — confirms `param_1` is exactly the receiver expression to recover.

2. **The scalar-vs-array distinction is deliberately *not* in this slice, because it
   is not in the p-code.** Whether a call is `delete` or `delete[]` is determined
   solely by *which* deallocation function it targets (`operator delete` vs `operator
   delete[]`) — a fact that lives in the callee's name, which the matcher (holding no
   `Program`) cannot read. So `DeleteCall` carries the raw `callTarget` address and
   leaves both the "is this a deallocation function at all?" and the "scalar or
   array?" decisions to the driver. The matcher therefore recovers a *candidate*: any
   direct call with a resolvable target and a receiver argument. This is the
   direct-call skeleton the other callee-identified forms (ctor / dtor / `new` /
   `new[]` / placement) will share; it is kept per-form here rather than prematurely
   unified into a shared extractor — the rule-of-three threshold is not yet met, and
   unifying against one observed form would be the same guessing the harness exists to
   stop.

3. **It lives in Base `ghidra.app.util.cpp`, and is verified against the real
   decompiler.** Like the virtual-call matcher, it imports only Framework p-code types
   (`PcodeOp`, `Varnode`) and the `Address` model type — no `Program`, no Decompiler
   import — so it belongs in Base with the rest of the C++ recognition family; the
   `ip` gate confirms it needs no `certification.manifest` entry. Its coverage is a
   Decompiler `test.slow` integration test
   ([`CppDeleteRecognizerTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppDeleteRecognizerTest.java))
   driving the same x86-64 fixture through the DD-0023 harness, asserting the matcher
   recovers a call target that resolves to the `operator.delete` function and a
   receiver that strips the `void*` `CAST` back to `param_1`. Hand-building the syntax
   tree was rejected for the same reason as DD-0024: it would test the matcher against
   the author's model of the idiom, the very thing the harness exists to stop us
   guessing at.

A fixture detail worth recording, because it is the kind of thing that silently
breaks a two-function harness test: a direct `call` makes its target a known call
reference, so `ProgramBuilder`'s auto-analysis will *auto-create* a function at the
call target during disassembly. Disassembling *before* the explicit
`createEmptyFunction("operator.delete", …)` therefore raced it to an
`OverlappingFunctionException`. The fix is ordering: write the bytes with
`disassemble=false`, create both functions explicitly, *then* disassemble — so
analysis finds the functions already present and creates none of its own.

## Consequences

- The recognition queue's second form now has a load-bearing, fully-tested first
  brick, and the project has its first **direct-call** recognition primitive. The
  `#37-9f-b-2` driver can consume `DeleteCall` directly: it has the target address to
  resolve and classify, and the receiver varnode to render.
- Recognition is advisory and total-failure-safe, mirroring the renderer it feeds: a
  non-matching shape — not a `CALL`, an argument-less call, a call with no resolvable
  target address or no receiver varnode — yields `null`, never an exception or a
  fabricated site. The integration test pins this by asserting every non-`CALL` op,
  and a `null` call site, are declined.
- Known limitations, each a candidate future concern: the matcher recovers only the
  *first* argument as the receiver (a deallocation function takes the pointer first,
  which is all `delete` needs; sized-deallocation `operator delete(void*, size_t)`
  still passes the pointer first); and the destructor-then-`operator delete` pairing a
  real `delete` of a non-trivial type emits (the dtor call before the free) is a
  recognition concern for the driver / a later refinement, not the matcher's — the
  matcher recognises the `operator delete` call itself.
- Verified locally before commit (per the test-before-push rule): `gradle :Base:ip`
  (header/manifest), `gradle :Base:test --tests 'ghidra.app.util.cpp.*'` (no Base
  regression), and `gradle :Decompiler:ip :Decompiler:integrationTest --tests
  CppDeleteRecognizerTest` (the matcher against real decompiler output). The Ghidra
  `gradlew` shim refuses the non-PUBLIC/DEV release name, so a system `gradle` >= 8.5
  is used directly.
