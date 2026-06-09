---
number: 0025
title: Rec 37 #37-7b-2 — the virtual-call driver closes the recognition loop: it walks a HighFunction, resolves each CppVirtualCallRecognizer dispatch's receiver to a CppClass by its recovered HighVariable type via CppTypeSystem, and dispatches to CppDecompilerHints.renderVirtualCall. Receiver/slot/class are rendered; arguments are scoped out because an unresolved indirect CALLIND carries no recovered prototype
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0025: the #37-7b-2 driver resolves the receiver by its recovered type and renders the call

## Context

[DD-0024](0024-rec37-virtual-call-recognition-matcher.md) shipped `#37-7b-1`:
[`CppVirtualCallRecognizer`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppVirtualCallRecognizer.java),
a pure p-code matcher that recovers `(slotIndex, receiverVarnode)` from a
vtable-dispatch `CALLIND` but deliberately stops there, leaving the two remaining
concerns — mapping the receiver to a `CppClass` and rendering the operand
expressions — to a driver slice. This is that slice (`#37-7b-2`): the piece that
turns a recognised dispatch into the C++ hint string the shipped
[`CppDecompilerHints.renderVirtualCall`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDecompilerHints.java)
renderer (DD-0016) produces, closing the loop the renderer's javadoc opened.

## Decision

Ship
[`CppVirtualCallDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppVirtualCallDriver.java):
constructed over a `CppDecompilerHints` renderer and a `CppTypeSystem` model, its
`recognizeAndRender(HighFunction)` walks the function's p-code, runs the matcher on
each `CALLIND`, and for every recognised dispatch resolves the receiver to a
`CppClass` and dispatches to the renderer, returning a list of
`RenderedVirtualCall(site, rendering)`.

Three choices pin it, each grounded in p-code observed through the Rec 30 harness
(DD-0023) before any code was written:

1. **The receiver becomes a class through its recovered `HighVariable` type, and the
   `CppTypeSystem` name registry is the resolver — no new abstraction.** Dumping the
   typed fixture showed the matcher's receiver varnode carries a `HighParam`
   `param_1` whose `getDataType()` is `Pointer -> Structure "C"`. So the driver
   unwraps one pointer level, takes the structure name, and calls
   `CppTypeSystem.getCppClass(name)` — the lookup the model already exposes (keyed by
   `Structure.getName()`). A bespoke resolver interface was unnecessary: the type
   system *is* the name→class map. The receiver expression handed to the renderer is
   the `HighVariable`'s own name (`param_1`), the identifier the decompiler itself
   prints. Pointer-ness drives the renderer's `->` vs `.`.

2. **A known calling convention and the right ABI register are part of the fixture,
   not an afterthought.** The first typed fixture used `mov rax,[rdi]` (System-V
   first arg) but `x86:LE:64:default`'s default compiler spec is Windows
   (`__fastcall`, first arg in `RCX`). Under that spec the decompiler left `RDI`
   *unaffected* and never tied it to the typed `C *param_1`, so the receiver's
   recovered type was a generic `longlong *` and resolution failed. Switching the
   fixture to `mov rax,[rcx]` and setting the function's convention to the spec
   default made the receiver `param_1 : C *`. The lesson is recorded here because it
   is the kind of mismatch that silently degrades a recognition pass: the receiver
   must land in the ABI's first-argument register for its declared type to reach the
   `HighVariable`.

3. **Arguments are explicitly out of scope for this slice, because the decompiler
   does not recover them here.** An unresolved indirect call has no recovered
   prototype, so its `CALLIND` carries only the call-target input and no argument
   varnodes (the dump confirms `numInputs == 1`). Rather than ship speculative,
   untested argument-rendering logic, the driver renders `receiver->method()` with an
   *empty* argument list — which the renderer fully supports — and the limitation is
   documented. Threading virtual-call arguments depends on indirect-call prototype
   recovery and is left to a later slice.

## Consequences

- The virtual-call form of Rec 37 recognition is end-to-end for the no-argument case:
  recognise the idiom → resolve the class → render `param_1->draw()` keyed to the
  call-site address. The harness integration test
  ([`CppVirtualCallDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppVirtualCallDriverTest.java))
  asserts exactly that string against the real decompiler output, plus the
  decline-when-unmodelled and null-guard cases.
- The driver is advisory and total-failure-safe, mirroring the matcher and renderer:
  a receiver with no `HighVariable`, an unmodelled type, or no printable name
  contributes no hint rather than raising or mis-rendering.
- Known limitations, each a candidate future slice: no argument threading (above); one
  pointer level only (no typedef/multi-level unwrap — not observed, so not
  pre-handled, per the same grounding discipline as DD-0024); and the single-table
  vtable assumption `CppVTable` already documents (no multiple-inheritance
  sub-vtables).
- With the virtual-call form complete, the remaining six recognition forms
  (cast / ctor / dtor / `new[]` / placement / `delete`) follow the same shape: a
  matcher slice that recovers the structural facts from p-code, then a driver slice
  that resolves the class and dispatches to the matching shipped renderer.
- Verified locally before commit (test-before-push): `gradle :Base:ip`,
  `gradle :Decompiler:ip`, and `gradle :Decompiler:integrationTest --tests
  CppVirtualCallDriverTest --tests CppVirtualCallRecognizerTest` (system `gradle`
  >= 8.5, since the Ghidra `gradlew` shim refuses the non-PUBLIC/DEV release name).
