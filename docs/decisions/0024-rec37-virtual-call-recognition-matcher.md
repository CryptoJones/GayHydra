---
number: 0024
title: Rec 37 #37-7b-1 — the virtual-call recognition wrapper opens with a pure p-code idiom matcher (CppVirtualCallRecognizer) that recovers only (slotIndex, receiver) from a CALLIND in a live HighFunction; type-resolution and expression-rendering are the separate #37-7b-2 driver slice. The matcher is grounded in the p-code the real decompiler emits, observed through the Rec 30 headless harness, not a guessed shape
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0024: the #37-7b virtual-call recognition wrapper opens with a pure p-code matcher

## Context

[DD-0023](0023-rec30-headless-highfunction-harness.md) shipped the Rec 30 headless
`HighFunction` harness and named the work it unblocks: the Rec 37 *recognition*
wrappers that detect a C++ idiom in a live function and dispatch to the matching
already-shipped
[`CppDecompilerHints`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDecompilerHints.java)
renderer. The first idiom is the virtual-method call (`#37-7`, renderer
`renderVirtualCall`, DD-0016). The renderer's own javadoc deferred its recognition
half as the "`#37-7b` Program-coupled wrapper": the pass that *walks a
`HighFunction`, recognises the raw C-style idiom, recovers the `(class, slot)` it
denotes, and calls the renderer.*

That deferred wrapper bundles three genuinely separable concerns:

1. **Structural recovery** — given an indirect call, decide whether it is a vtable
   dispatch and, if so, recover the slot index and the varnode carrying the
   receiver (`this`). This lives entirely in the p-code SSA graph.
2. **Type resolution** — map the receiver varnode to a `CppClass` in the
   `CppTypeSystem` model.
3. **Expression rendering** — turn the receiver and argument varnodes into the C++
   expression strings `renderVirtualCall` consumes, then call it.

## Decision

**Ship concern 1 alone as the first slice (`#37-7b-1`):**
[`CppVirtualCallRecognizer`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppVirtualCallRecognizer.java),
a stateless static matcher whose sole entry point `recognize(PcodeOp)` returns a
`VirtualDispatch(int slotIndex, Varnode receiver)` record or `null`. Concerns 2 and
3 — the `HighFunction`-walking driver that resolves the receiver to a `CppClass`,
renders the operand expressions, and dispatches to the renderer — are the next
slice (`#37-7b-2`), which carries its own type-lookup and varnode-to-C-expression
design and so earns its own commit and its own decision record.

Three choices pin this slice:

1. **The matcher is grounded in observed p-code, not a guessed shape.** Before
   writing a line of it, a throwaway harness test (built on DD-0023) decompiled a
   hand-assembled x86-64 `mov rax,[rdi]; call qword [rax+8]; ret` and dumped the
   resulting syntax tree. The real chain from the `CALLIND` target is
   `LOAD(space, CAST(INT_ADD(LOAD(space, receiver), k)))`. Two facts a guess gets
   wrong are now encoded directly: a `LOAD` carries the address-space id in
   `input[0]` and the dereferenced pointer in **`input[1]`** (the pointer is
   `getInput(1)`, never `getInput(0)`); and the decompiler interposes `CAST`/`COPY`
   pass-through ops the matcher must skip (`stripCopyCast`). The slot offset `k` is a
   constant addend on an `INT_ADD` (`k = slotIndex * pointerSize`); slot 0 has no
   addend, so the slot load dereferences the vtable pointer directly. Idiom variants
   the typed-`this` driver may surface (e.g. `PTRSUB`/`PTRADD` in place of `INT_ADD`)
   are deliberately *not* pre-handled here — they will be added against a real
   observation in `#37-7b-2`, not speculatively.

2. **It lives in Base `ghidra.app.util.cpp`, beside the renderer and feeders.** The
   matcher imports only the Framework p-code types (`PcodeOp`, `Varnode`, in
   SoftwareModeling, visible everywhere); it holds no `Program` and no Decompiler
   import, so it belongs in Base with the rest of the C++ recognition family rather
   than in the Decompiler module. The certification `ip` gate confirms it needs no
   `certification.manifest` entry — a normal `.java` carrying the inline GHIDRA
   header.

3. **It is verified against the real decompiler, not a hand-built syntax tree.** The
   coverage is a Decompiler `test.slow` integration test
   ([`CppVirtualCallRecognizerTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppVirtualCallRecognizerTest.java))
   that drives the same x86-64 fixture through the DD-0023 harness and asserts the
   matcher recovers slot index 1 with the receiver in `RDI`. Hand-constructing
   `PcodeOpAST`/`VarnodeAST` graphs for a Base unit test was considered and rejected:
   it would test the matcher against the author's *model* of the idiom, the very
   thing the harness exists to stop us guessing at. Asserting against the syntax tree
   the real decompiler emits is strictly higher-value, which is why the harness was
   built first.

## Consequences

- The recognition queue now has a load-bearing, fully-tested first brick. The
  `#37-7b-2` driver can consume `VirtualDispatch` directly: it has the slot index to
  hand `renderVirtualCall` and the receiver varnode to resolve to a `CppClass` and to
  render as the receiver expression.
- Recognition is advisory and total-failure-safe, mirroring the renderer it feeds: a
  non-matching shape (an ordinary indirect call through a function-pointer variable)
  yields `null`, never an exception or a fabricated dispatch. The integration test
  pins this by asserting every non-`CALLIND` op, and a `null` call site, are declined.
- The matcher is intentionally minimal and reads only the SSA graph. It does no
  multiple-inheritance sub-vtable reasoning (the single-table assumption `CppVTable`
  already documents) and no overload resolution; both stay future work.
- Verified locally before commit (per the test-before-push rule): `gradle :Base:ip`
  (header/manifest), `gradle :Base:test --tests 'ghidra.app.util.cpp.*'` (no Base
  regression), and `gradle :Decompiler:ip :Decompiler:integrationTest --tests
  CppVirtualCallRecognizerTest` (the matcher against real decompiler output). The
  Ghidra `gradlew` shim refuses the non-PUBLIC/DEV release name, so a system `gradle`
  >= 8.5 is used directly.
