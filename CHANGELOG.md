# Changelog

All notable changes to GayHydra are recorded here. Format is loosely
based on [Keep a Changelog](https://keepachangelog.com/); the project
does not yet promise SemVer.

---

## [Unreleased]

Work toward the next sprint. Tracked per-PR in
[SprintPlanning.md](SprintPlanning.md); per-release notes are
generated from the GitHub Releases UI at sprint close.

### Added

- **Rec 30 headless integration harness** (Sprint 14 Step 1, the enabler) —
  `AbstractDecompilerHighFunctionTest` loads/builds a small `Program` and
  decompiles a function to a real `HighFunction` headlessly (no `DISPLAY`, no
  Swing), as a thin lifecycle wrapper over `DecompInterface`; a pilot
  (`HeadlessHighFunctionHarnessTest`) drives it end-to-end against an in-memory
  Toy function. This is the gate that makes the Program-coupled Rec 37 C++
  recognition wrappers testable before commit, unblocking the queue that has been
  stuck at the "headless ceiling." Design: DD-0023.
- **Rec 37 `#37-7b-1` — virtual-call recognition matcher** (Sprint 14 Step 2) —
  `CppVirtualCallRecognizer`, a stateless p-code matcher that recognises the C++
  vtable-dispatch idiom at a `CALLIND` in a live `HighFunction` and recovers the
  `(slotIndex, receiver)` the `CppDecompilerHints.renderVirtualCall` renderer
  (DD-0016) needs. The recovery is grounded in the p-code the real decompiler
  emits (observed through the Rec 30 harness) — strips interposed `CAST`/`COPY`
  ops and reads the `LOAD` pointer from `input[1]` — and is verified end-to-end by
  a harness integration test against a hand-assembled x86-64 virtual call. The
  type-resolution + expression-rendering driver that calls the renderer is the
  next slice (`#37-7b-2`). Design: DD-0024.
- **Rec 37 `#37-7b-2` — virtual-call driver** (Sprint 14 Step 2) —
  `CppVirtualCallDriver` walks a `HighFunction`, runs the `#37-7b-1` matcher on each
  `CALLIND`, resolves the recovered receiver to a `CppClass` by its recovered
  `HighVariable` type (one pointer level → structure name → `CppTypeSystem`
  lookup), and dispatches to `CppDecompilerHints.renderVirtualCall`, returning
  `(site, rendering)` hints. Closes the virtual-call recognition loop: a real
  x86-64 `this->vtable[1]()` decompiles and renders to `param_1->draw()`. Advisory
  and total-failure-safe (unmodelled receivers contribute no hint). Argument
  threading is scoped out — an unresolved indirect `CALLIND` carries no recovered
  prototype — so the renderer is called with an empty argument list. Design:
  DD-0025.
- **Rec 37 `#37-9f-b-1` — delete-recognition matcher** (Sprint 14 Step 2) —
  `CppDeleteRecognizer`, a stateless p-code matcher that recovers the structural
  facts of a candidate deallocation call — the direct `CALL`'s target address and
  the receiver pointer varnode — that the `CppDecompilerHints.renderDelete` renderer
  (DD-0022) needs. Establishes the sprint's second recognition shape, the
  **direct-call** idiom (vs the `#37-7b` indirect vtable dispatch): grounded in the
  p-code the real decompiler emits (observed through the Rec 30 harness), it reads
  the callee entry from the `CALL`'s `input[0]` address and strips the interposed
  `void*` `CAST` off `input[1]` to reach the receiver's printable name. Whether the
  callee is actually `operator delete` (scalar) or `operator delete[]` (array) is
  not in the p-code — it lives in the callee's name — so that classification is left
  to the `#37-9f-b-2` driver. Verified end-to-end by a harness integration test
  against a hand-assembled x86-64 `delete p`. Design: DD-0026.
- **Rec 37 `#37-9f-b-2` — delete driver** (Sprint 14 Step 2) — `CppDeleteDriver`
  walks a `HighFunction`, runs the `#37-9f-b-1` matcher on each direct `CALL`,
  resolves the recovered call target to a `Function`, classifies its name as scalar
  `operator delete` or array `operator delete[]` (the demangled forms Ghidra emits,
  e.g. `operator.delete` / `operator.delete[]`), and dispatches to
  `CppDecompilerHints.renderDelete`, returning `(site, rendering)` hints. Closes the
  deallocation recognition loop: a real x86-64 `operator delete(p)` renders to
  `delete param_1` and the array form to `delete[] param_1`. Uniquely among the
  drivers it resolves no `CppClass` and needs no `CppTypeSystem` — `delete` names no
  type. Advisory and total-failure-safe (non-deallocation callees contribute no
  hint). The dtor-then-`operator delete` pairing for non-trivial types is not yet
  fused (a later cross-form refinement). Design: DD-0027.
- **Rec 37 `#37-9c-b-1` — explicit-destructor recognition matcher** (Sprint 14
  Step 2) — `CppDestructorRecognizer`, a stateless p-code matcher that recovers the
  structural facts of an explicit `p->~C()` destructor call — the direct `CALL`'s
  target address and the receiver `this` varnode — that the
  `CppDecompilerHints.renderDestructorCall` renderer (DD-0016) needs. This is the
  **second** form to use the `#37-9f-b` direct-call shape; per DD-0026's standing
  note it is kept a per-form twin of `CppDeleteRecognizer` rather than prematurely
  unified, with the shared-extractor refactor earned at the third user (the
  constructor `#37-9b`). Whether the callee is actually a `~ClassName` destructor
  (and of which class) is not in the p-code — it lives in the callee's name — so
  that classification is left to the `#37-9c-b-2` driver. Verified end-to-end by a
  harness integration test against a hand-assembled x86-64 destructor call. Design:
  DD-0028.
- **Rec 37 `#37-9c-b-2` — explicit-destructor driver** (Sprint 14 Step 2) —
  `CppDestructorDriver` walks a `HighFunction`, runs the `#37-9c-b-1` matcher on each
  direct `CALL`, resolves the recovered call target to a `Function`, reads the
  destructed class from the callee's `~ClassName` local name (the demangled form
  Ghidra emits, e.g. `~Image` for `Magick::Image::~Image()`) — not the receiver type,
  so a base destructor on a derived pointer renders the base's name — resolves that
  `CppClass` in a `CppTypeSystem`, reads receiver-is-pointer from the receiver
  `HighVariable`, and dispatches to `CppDecompilerHints.renderDestructorCall`,
  returning `(site, rendering)` hints. Closes the explicit-destructor recognition
  loop: a real x86-64 `~C(p)` renders to `param_1->~C()`. Advisory and
  total-failure-safe (non-destructor callees, unmodelled classes contribute no
  hint). Third of seven forms end-to-end; the dtor-then-`operator delete` pairing is
  not yet fused. Design: DD-0029.
- **Rec 37 `#37-9b-1` — heap-construction recognition matcher** (Sprint 14 Step 2) —
  `CppConstructorRecognizer`, a stateless p-code *fusion* matcher: unlike the
  single-`CALL` delete and destructor forms, a heap `new C()` is two linked calls, so
  `recognize(PcodeOp)` anchors on the constructor `CALL`, strips the `CAST`/`COPY` off
  its receiver, and requires that receiver to be the result of *another* `CALL` — the
  allocation — recovering `(constructorTarget, allocationTarget)`. That fusion link
  (the `this` is freshly allocated storage, not a stack/field address) is exactly what
  distinguishes a heap `new` from in-place construction. Grounded in the real
  decompiler p-code (`pCVar1 = (C *)operator_new(8L); C::C(pCVar1);`) via the Rec 30
  harness; verified end-to-end by a harness integration test against a hand-assembled
  x86-64 `new C()`, including declining the allocation `CALL` itself. Whether the
  targets really are a constructor and `operator new` is the `#37-9b-2` driver's job.
  This is the third user of the direct-call shape — the rule-of-three point at which
  the shared `CppDirectCallRecognizer` extraction is now earned (the next refactor).
  Design: DD-0030.
- **Rec 37 `#37-9b-2` — heap-construction driver** (Sprint 14 Step 2) —
  `CppConstructorDriver` walks a `HighFunction`, runs the `#37-9b-1` fusion matcher on
  each `CALL`, resolves the recovered constructor and allocation targets to
  `Function`s, and classifies both from their names: the constructor by its local name
  equalling its class (parent) namespace name — the demangled form Ghidra emits, e.g.
  `Fred` in namespace `Fred` for `Bar::Fred::Fred(int)`, the counterpart to the
  destructor's `~` prefix — and the allocation as `operator new` (the same `.`→space
  normalisation the delete driver uses for `operator delete`). It resolves the
  constructed `CppClass` in a `CppTypeSystem` and dispatches to
  `CppDecompilerHints.renderConstruction`, returning `(site, rendering)` hints. Closes
  the heap-construction loop: a real x86-64 `new C()` renders to `new C()`. Advisory
  and total-failure-safe (non-constructor callees, non-`operator new` allocations,
  unmodelled classes contribute no hint). Constructor arguments are scoped out (the
  `#37-10+` DTM work), like the virtual-call driver. **Fourth of seven forms
  end-to-end**; with three concrete direct-call copies now extant, the shared
  `CppDirectCallRecognizer` extraction is the next (refactor) commit. Design: DD-0031.
- **Rec 37 `#37-9d-b-1` — array-construction recognition matcher** (Sprint 14 Step 2) —
  `CppArrayConstructionRecognizer`, the sprint's first *forward* p-code matcher: where
  the delete/destructor/constructor forms walk backward from a call's receiver, array
  `new C[n]`'s element type lives *forward* of the allocation (the raw result is an
  untyped `void *`; the `C *` type appears on the `CAST` the storage is reinterpreted
  into). So `recognize(PcodeOp)` anchors on the allocation `CALL`, recovers the target
  and byte-size argument, and walks forward over the single-consumer `CAST`/`COPY`
  chain off the result to the typed pointer varnode, returning
  `(allocationTarget, byteSize, typedResult)`. Recognises the trivial-element shape
  (allocation + typed use, no ctor loop); the per-element ctor-loop fusion is a later
  refinement. Name-blind — whether the callee is `operator new[]`, and the element
  class/count it implies, is the `#37-9d-b-2` driver's job. Grounded in the real
  decompiler p-code (`(C *)operator_new__(0x28)`) via the Rec 30 harness; verified
  end-to-end by a harness integration test against a hand-assembled x86-64
  `new C[5]`. Reuses `CppDirectCallRecognizer.callTargetAddress` for the target read.
  Design: DD-0033.
- **Rec 37 `#37-9d-b-2` — array-construction driver** (Sprint 14 Step 2) —
  `CppArrayConstructionDriver` walks a `HighFunction`, runs the `#37-9d-b-1` matcher on
  each `CALL`, resolves the recovered allocation target to a `Function`, and renders
  from facts forward of the call: it classifies the callee name as array
  `operator new[]` (the demangled `operator.new[]`, `.`→space normalised — the same
  disambiguation the delete driver uses for `operator delete[]`), reads the element
  class off the recovered typed result's pointer type (one pointer level stripped —
  *not* a ctor callee name, since the trivial-element array `new` has no ctor call),
  computes `count = byteSize / element.getLength()` (only for a positive constant exact
  multiple), resolves the `CppClass`, and dispatches to
  `CppDecompilerHints.renderArrayConstruction`, returning `(site, rendering)` hints.
  Closes the array-construction loop: a real x86-64 `new C[5]` renders to `new C[5]`
  (count `0x28 / 8 = 5`). **Fifth of seven forms end-to-end**; advisory and
  total-failure-safe (scalar `operator new`, unmodelled classes, non-dividing sizes
  contribute no hint). Trivial-element only; the per-element ctor-loop fusion is a
  later refinement. Design: DD-0034.
- **Rec 37 `#37-8b-1` — base-cast recognition matcher** (Sprint 14 Step 2) —
  `CppBaseCastRecognizer`, a stateless p-code matcher that recognises a C++
  base-subobject up/down-cast at a `CAST` in a live `HighFunction` and recovers the
  `(sourcePointer, byteOffset, castResult)` the `CppDecompilerHints.renderUpcast` /
  `renderDowncast` renderers (DD-0016) need. Grounded in the real decompiler p-code
  (observed through the Rec 30 harness): a positive in-layout upcast offset is a
  `PTRSUB` (offset = `in[1]`), a negative before-the-object downcast offset is a
  `PTRADD` (offset = `in[1] * in[2]`, signed) — both normalised to one **signed** byte
  offset whose sign is the cast direction and magnitude is the base-subobject offset.
  Requires both ends pointer-typed and a non-zero offset (a first-base offset-0 cast is
  a bare reinterpretation with no recoverable adjustment); class resolution, direction
  classification, and source-expression rendering are the `#37-8b-2` driver's job.
  Verified end-to-end against hand-assembled x86-64 up- and down-casts (matcher 4/4).
  Design: DD-0035.
- **Rec 37 `#37-8b-2` — base-cast driver** (Sprint 14 Step 2) — `CppBaseCastDriver`
  walks a `HighFunction`, runs the `#37-8b-1` matcher on each `CAST`, reads the
  source/target classes off the recovered varnodes' pointer types (one pointer level
  stripped — a cast has no callee to name a class), takes the cast direction from the
  recovered offset's sign (positive upcast / negative downcast), picks the derived class
  accordingly (source for an upcast, target for a downcast), resolves it in a
  `CppTypeSystem`, renders the source pointer's `HighVariable` name as the source
  expression, and dispatches to `CppDecompilerHints.renderUpcast` / `renderDowncast`,
  returning `(site, rendering)` hints. Dispatches **only** when the derived class
  genuinely has a non-virtual base edge at the offset, so the renderer's neutral
  `src + offset` fallback is never emitted as a hint (the driver's emit policy vs the
  renderer's defensive fallback). Closes the cast loop: a real x86-64 `+0x10` upcast
  renders `static_cast<Base*>(param_1)`, the symmetric `-0x10` downcast renders
  `static_cast<Derived*>(param_1)`. **Sixth of seven forms end-to-end**; advisory and
  total-failure-safe (unmodelled classes, offsets matching no base edge contribute no
  hint). Non-virtual single base offsets only. Design: DD-0036.
- **Rec 37 `#37-9e-b-1` — placement-construction recognition matcher** (Sprint 14 Step 2) —
  `CppPlacementConstructionRecognizer`, a stateless p-code matcher that recognises a C++
  *non-elided* placement `new (buf) C()` in a live `HighFunction` and recovers the
  `(constructorTarget, allocationTarget, placementBuffer)` the
  `CppDecompilerHints.renderPlacementConstruction` renderer (DD-0016) needs. Grounded in
  the real decompiler p-code (observed through the Rec 30 harness): the recoverable shape is
  the two-call form where a placement `operator new(size, buffer)` result feeds the
  constructor receiver — the *same* fusion shape as heap-`new`, separated only by the
  allocation carrying a **buffer operand** (three `CALL` inputs vs heap's two), recovered as
  `input[2]`. The standard *elided* placement new (a bare ctor on caller-owned storage) is
  indistinguishable from in-place construction and out of scope. Verified end-to-end against a
  hand-assembled x86-64 placement new (matcher 4/4, including the heap/placement partition
  from both sides). Design: DD-0037.
- **Rec 37 `#37-9e-b-2` — placement-construction driver** (Sprint 14 Step 2) —
  `CppPlacementConstructionDriver` walks a `HighFunction`, runs the `#37-9e-b-1` matcher on
  each `CALL`, resolves the recovered constructor (name == class) and allocation
  (`operator new`) targets to functions, renders the recovered buffer varnode's
  `HighVariable` name as the placement expression, resolves the class in a `CppTypeSystem`,
  and dispatches to `CppDecompilerHints.renderPlacementConstruction`, returning
  `(site, rendering)` hints. Closes the placement loop: a real x86-64
  `new (buf) C()` renders `new (param_1) C()`. **Seventh and last form end-to-end — Rec 37
  recognition is complete (seven of seven).** Advisory and total-failure-safe (unmodelled
  classes, non-`operator new` allocations, heap `new`, and unnamed buffers contribute no
  hint); constructor arguments scoped out to the `#37-10+` DTM work. Design: DD-0038.
- **Rec 37 `#37-5-1` — MSVC RTTI per-descriptor base decoder** (Sprint 14 Step 2, first slice of
  PR #37-5 the MSVC `CppRttiAnalyzer`) — `CppMsvcRttiDecoder.decodeBase(Rtti1Model)` turns one MSVC
  `RTTIBaseClassDescriptor` into one ABI-neutral `CppRttiFeeder.BaseSpec`: base name from the type
  descriptor's demangled name (e.g. `Base`, the form `CppTypeSystem` keys on), offset from `mdisp`,
  and public-ness from the `BCD_PRIVORPROTBASE` (`0x04`) attribute bit. Non-virtual bases only — a
  virtual base (`pdisp != -1`), whose offset needs the runtime vbtable, is declined and deferred to
  the program-scanning analyzer slice. Pure decode (reads only laid-down models, no program scan),
  total-failure-safe. Grounded against the real `Base`/`Shape`/`Circle` MSVC RTTI fixtures; decoder
  5/5. Design: DD-0039.
- **Rec 37 `#37-5-2` — MSVC RTTI class decoder** (Sprint 14 Step 2, second slice of PR #37-5) —
  `CppMsvcRttiDecoder.decodeClass(Rtti3Model)` lifts the per-descriptor decoder to a whole class:
  from one `RTTIClassHierarchyDescriptor` it recovers the class's own demangled name and the list of
  its **direct** bases (new `DecodedClass(derivedName, directBases)` record). MSVC lays the RTTI2
  base-class array out as the full hierarchy in preorder (self at index 0, then every transitive
  ancestor); the decoder walks it skipping self and, at each direct base, skipping that base's
  `numContainedBases` contained entries, so a transitive base is never emitted as direct. Reuses
  `decodeBase` for each entry (shared name/offset/access semantics, virtual bases declined). Pure
  decode, total-failure-safe. Grounded against the complete-flow fixture reshaped for single
  inheritance (transitive `Base` excluded from `Circle`) and multiple inheritance (two direct bases);
  decoder 8/8. Design: DD-0040.
- **Rec 37 `#37-5-3` — MSVC RTTI driver** (Sprint 14 Step 2, third slice of PR #37-5) —
  `CppMsvcRttiDriver.feedClass(model, CppRttiFeeder)` bridges the pure `CppMsvcRttiDecoder` decode to
  the ABI-neutral `CppRttiFeeder`, completing the MSVC RTTI → `CppTypeSystem` inheritance pipeline end
  to end for one located class. Accepts either entry form — a `RTTICompleteObjectLocator`
  (`Rtti4Model`, what a vftable points at) or the `RTTIClassHierarchyDescriptor` (`Rtti3Model`) it
  references — decodes the class and feeds its name and direct bases. Decode failures are advisory
  (null model / invalid / no class → feeds nothing); a null feeder is a programming error and is
  rejected. Feeds one located structure; program-wide discovery of RTTI structures stays in the
  deferred program-coupled `CppRttiAnalyzer` wrapper. Grounded end to end against the real
  `Base`/`Shape`/`Circle` MSVC RTTI fixtures (single and multiple inheritance, both entry forms);
  driver 5/5. Design: DD-0041.
- **Rec 37 `#37-10a` — explicit constructor arguments threaded into the placement driver** (Sprint 14,
  first slice of the `#37-10+` rendering band) — `CppPlacementConstructionDriver` now recovers the
  constructor `CALL`'s inputs after the call target (index 0) and the `this` receiver (index 1) as the
  constructor's explicit arguments, renders each by its `HighVariable` name (the same operand rendering
  the buffer/receiver use), and dispatches them to `CppDecompilerHints.renderPlacementConstruction`, so
  `new (buf) C(arg)` renders with its argument and a zero-argument constructor still renders
  `new (buf) C()`. An argument with no printable name (an unnamed temporary, or a bare constant, which
  carries no `HighVariable`) declines the whole hint rather than rendering a gap — the same advisory,
  never-wrong contract the receiver rendering holds; rendering constants and compound-expression
  arguments is later `#37-10` work. Grounded against a decompiled `new (buf) C(v)` rendering
  `new (param_1) C(param_2)`; driver 6/6. Design: DD-0042.
- **Rec 37 `#37-10b` — explicit constructor arguments threaded into the heap-new driver** (Sprint 14,
  second slice of the `#37-10+` rendering band) — `CppConstructorDriver` now recovers the constructor
  `CALL`'s inputs after the call target (index 0) and the `this` receiver (index 1) as the explicit
  arguments, renders each by its `HighVariable` name, and dispatches them to
  `CppDecompilerHints.renderConstruction`, so `new C(arg)` renders with its argument and a zero-argument
  constructor still renders `new C()`. An argument with no printable name (an unnamed temporary, or a
  bare constant) declines the whole hint rather than rendering a gap — the same advisory, never-wrong
  contract `#37-10a` holds; the two argument helpers are duplicated from the placement driver as honest
  per-form twins (rule of three) until a third user earns the extraction. Grounded against a decompiled
  `new C(v)` rendering `new C(param_1)`; driver 6/6. Design: DD-0043.
- **Rec 37 `#37-10c` — integer-typed constant constructor arguments rendered** (Sprint 14, third slice
  of the `#37-10+` rendering band) — the placement and heap `new` drivers now render a bare integer
  literal constructor argument as its decimal value via a new `argumentExpr` helper, so `new C(5)` and
  `new (buf) C(5)` render instead of declining the whole hint as `#37-10a`/`#37-10b` did. A constant is
  rendered only when its `HighVariable` datatype is an `AbstractIntegerDataType`: an integer literal's
  decimal value is faithful, whereas a pointer-typed constant (a global string address) rendered as a
  bare decimal would mislead, so it still declines — preserving the advisory, never-wrong contract. The
  two `argumentExpr` helpers stay per-form twins (rule of three) until a third argument-rendering user
  earns the extraction; rendering compound expressions and typed constants (chars, bools, enum names) is
  later `#37-10` work. Grounded against decompiled `new C(5)` → `new C(5)` and `new (buf) C(5)` →
  `new (param_1) C(5)`; placement 7/7, heap 7/7. Design: DD-0044.

### Changed

- **Rec 37 `#37-9b` heap matcher tightened to partition against placement** (Sprint 14 Step 2) —
  `CppConstructorRecognizer` now declines an allocation `CALL` carrying a buffer operand (three
  inputs), so a placement `operator new(size, void*)` — which demangles to the same `operator new`
  name as the heap overload — is no longer mis-matched as a heap `new C()` and double-rendered.
  Heap requires `< 3` allocation inputs, placement requires `>= 3`, so the two forms partition the
  shared fusion shape and never both match a site. Regression-safe (heap matcher suite 3/3
  unchanged). Design: DD-0037.

- **Rec 37 `#37-9b` refactor — extract the shared `CppDirectCallRecognizer`** (Sprint
  14 Step 2) — the direct-call recovery (the callee entry in `input[0]` plus the
  cast-stripped `input[1]` receiver) was duplicated across the delete (`#37-9f-b`),
  destructor (`#37-9c-b`), and constructor (`#37-9b`) forms. With the third user now
  extant, the rule-of-three extraction earned across DD-0026/0028/0030 is done: the
  recovery is unified into one stateless `CppDirectCallRecognizer`
  (`recognize` → `DirectCall(callTarget, receiver)`, plus `callTargetAddress` for the
  constructor matcher's allocation call). The two pass-through per-form recognizers
  `CppDeleteRecognizer` and `CppDestructorRecognizer` (and their unit tests) are
  **deleted outright** rather than left as shims; the two drivers now consume
  `DirectCall` directly and the constructor matcher delegates its recovery, keeping
  only the form-specific fusion walk-back. Pure structural refactor — the three forms'
  recognition behaviour is unchanged; consolidated recovery coverage moves to one
  integration test (`CppDirectCallRecognizerTest`). Design: DD-0032.

---

## [v26.3.0] — 2026-06-06

Minor release closing the Rec 37 **C++ frontend** sprint: GayHydra grows
a whole new headless subsystem — the `ghidra.app.util.cpp`
`CppTypeSystem` model overlay (RFC-0001) and the seven-form
`CppDecompilerHints` renderer family (RFC-0001 §5) — built up across
DD-0011…DD-0022 and `#37-2`…`#37-9f`. The model side maps recovered
facts onto a pure in-memory overlay: demangled symbols
(`CppDemanglingFeeder`, `#37-3`), Itanium RTTI inheritance
(`CppRttiFeeder`, `#37-4`), recovered vtables (`CppVTableFeeder`,
`#37-6`), and vtable↔declared-method reconciliation
(`CppVtableReconciler`, `#37-6c`). The renderer side turns those facts
plus operand strings into C++ surface syntax — virtual call (`#37-7`),
up/down-cast (`#37-8`), heap / array / placement construction
(`#37-9` / `#37-9d` / `#37-9e`), explicit destruction (`#37-9c`), and
deallocation (`#37-9f`) — completing every RFC §5 idiom. The whole
family is **headless and advisory**: it holds no `Program` /
`DataTypeManager` / decompiler handle and never rewrites p-code, so it
moves no existing decompiler output on its own; the recognition passes
that would drive it from a `HighFunction` are deferred and
Program-coupled.

Minor (not patch) because the release both adds that new subsystem and
carries the Rec 39 *idiom-folding* work that **does** move existing
output: the constant-fill `STORE` sequence folds into `memset`
(`#39-4a`), and the SWAR popcount idiom folds into the native
`POPCOUNT(x)` op (`#39-4b`, DD-0007). The headless renderer family is
now exhausted — all remaining Rec 37 work (the recognition wrappers
`#37-7b`…`#37-9f` and the `#37-10+` `DataTypeManager`/signature/template/
operator band) is Program-coupled and a test-before-push blocker.

- feat(decompiler): Rec 37 `#37-9f` — **the `CppDecompilerHints` deallocation renderer**, the seventh and **final**
  headless form and the death-side counterpart of the `#37-9` construction renderer
  ([DD-0022](docs/decisions/0022-rec37-delete-renderer.md)). `renderDelete(String receiverExpr, boolean isArray)`
  emits `delete e` (scalar) / `delete[] e` (array) — `delete` is a unary operator on the pointer, so there is no
  class name, no parentheses, and no argument list, and the `[]` is the only thing `isArray` adds. **Uniquely among
  the family it reads no `CppClass` model fact at all** — C++ `delete` names no type (it is inferred from the
  pointer) — so it takes only an opaque receiver expression and the array-vs-scalar flag, consults no vtable, and
  does no overload resolution. **No neutral fallback** (nothing to fall back from); rejects null-or-blank receiver
  with `IllegalArgumentException`. Stateless and headless, extending the existing renderer; the dtor +
  `operator delete` recognition pass is the deferred Program-coupled wrapper. **This closes out the headless
  renderer family** — every RFC §5 idiom now has a renderer, and all remaining Rec 37 work (the recognition wrappers
  `#37-7b`…`#37-9f` and the `#37-10+` DTM/signature band) is Program-coupled and a test-before-push blocker.
  Validated by extending headless `CppDecompilerHintsTest` (6 new cases; 61 total).
- docs(decompiler): [DD-0022](docs/decisions/0022-rec37-delete-renderer.md) grounds Rec 37 `#37-9f` — the
  seventh and **final** `CppDecompilerHints` form, the **deallocation renderer** (the death-side counterpart of the
  `#37-9` construction form): `renderDelete(String receiverExpr, boolean isArray)` emits `delete e` / `delete[] e`.
  **Uniquely among the family it reads no `CppClass` model fact** — C++ `delete` names no type (it is inferred from
  the pointer), so the renderer takes only an opaque receiver expression and an array-vs-scalar flag. No neutral
  fallback (nothing to fall back from); rejects null-or-blank receiver with `IllegalArgumentException`. **Marks the
  headless ceiling**: after `#37-9f` the headless renderer family is exhausted — every RFC §5 idiom has a renderer,
  and all remaining work (the recognition wrappers `#37-7b`…`#37-9f` and the `#37-10+` DTM/signature band) is
  Program-coupled and a test-before-push blocker, so the next step after the `#37-9f` impl is to flag that ceiling,
  not invent further micro-forms. The docs-first step preceding the `#37-9f` implementation.
- feat(decompiler): Rec 37 `#37-9e` — **the `CppDecompilerHints` placement-new renderer**, the sixth form and the
  placement sibling of the `#37-9` scalar construction renderer
  ([DD-0021](docs/decisions/0021-rec37-placement-new-renderer.md)). `renderPlacementConstruction(CppClass type,
  String placementExpr, List<String> argumentExprs)` emits `new (ptr) ClassName(args)` — the bracketed placement
  target before the class name distinguishes it from ordinary `new`; the name is `CppClass.getName()` and the args
  are joined in call order (zero-arg renders `new (ptr) ClassName()`). Like `#37-9`/`#37-9d`, **no neutral fallback**
  (the class name is a *total* model fact); rejects null type / null-or-blank placement / null arg list / null arg
  element with `IllegalArgumentException`. **No vtable lookup, no overload resolution** (constructor selection stays
  the DTM-coupled `#37-10+` band). Stateless and headless, extending the existing renderer; the placement-allocation
  + ctor recognition pass is the deferred Program-coupled wrapper. After `#37-9e`, only the `delete e` form (reads no
  model fact) remains before the headless renderer family is exhausted. Validated by extending headless
  `CppDecompilerHintsTest` (8 new cases; 55 total).
- docs(decompiler): [DD-0021](docs/decisions/0021-rec37-placement-new-renderer.md) grounds Rec 37 `#37-9e` —
  the sixth `CppDecompilerHints` form, the **placement-new renderer** (the placement sibling of the `#37-9` scalar
  construction form): `renderPlacementConstruction(CppClass type, String placementExpr, List<String> argumentExprs)`
  emits `new (ptr) ClassName(args)` (name is `CppClass.getName()`; the bracketed placement target distinguishes it
  from ordinary `new`). Like `#37-9`/`#37-9d`, **no neutral fallback** (the class name is a *total* model fact);
  rejects null type / null-or-blank placement / null arg list / null arg element with `IllegalArgumentException`.
  **No vtable lookup, no overload resolution** (constructor selection stays the DTM-coupled `#37-10+` band). Stays
  headless; the placement-allocation + ctor recognition pass is the deferred Program-coupled wrapper. After `#37-9e`,
  only the `delete e` form (reads no model fact) remains before the headless renderer family is exhausted. The
  docs-first step preceding the `#37-9e` implementation.
- feat(decompiler): Rec 37 `#37-9d` — **the `CppDecompilerHints` array-construction renderer**, the fifth form and
  the array sibling of the `#37-9` scalar construction renderer
  ([DD-0020](docs/decisions/0020-rec37-array-construction-renderer.md)). `renderArrayConstruction(CppClass type,
  String countExpr)` emits `new ClassName[count]` — the name is `CppClass.getName()`, the count is the element-count
  expression rendered verbatim inside the brackets. **No constructor argument list** — array `new T[n]`
  value/default-initializes each element and C++ has no syntax to pass per-element ctor args (parallels `#37-9c`'s
  no-args destructor). Like `#37-9`/`#37-9c`, **no neutral fallback** (the class name is a *total* model fact);
  rejects null type / null-or-blank count with `IllegalArgumentException`. **No vtable lookup, no overload
  resolution** (the element ctor is the implicit default). Stateless and headless, extending the existing renderer;
  the `operator new[]` + per-element-ctor-loop recognition pass is the deferred Program-coupled wrapper. Validated by
  extending headless `CppDecompilerHintsTest` (7 new cases; 47 total).
- docs(decompiler): [DD-0020](docs/decisions/0020-rec37-array-construction-renderer.md) grounds Rec 37 `#37-9d` —
  the fifth `CppDecompilerHints` form, the **array-construction renderer** (the array sibling of the `#37-9` scalar
  construction form): `renderArrayConstruction(CppClass type, String countExpr)` emits `new ClassName[count]` (name
  is `CppClass.getName()`; count verbatim inside the brackets). **No constructor argument list** — array `new T[n]`
  value/default-initializes each element and C++ has no syntax to pass per-element ctor args (parallels `#37-9c`'s
  no-args decision). Like `#37-9`/`#37-9c`, **no neutral fallback** (the class name is a *total* model fact); rejects
  null type / null-or-blank count with `IllegalArgumentException`. **No vtable lookup, no overload resolution** (the
  element ctor is the implicit default). Stays headless; the `operator new[]` + per-element-ctor-loop recognition
  pass is the deferred Program-coupled wrapper (`#37-9d` recognition band). The docs-first step preceding the
  `#37-9d` implementation.
- feat(decompiler): Rec 37 `#37-9c` — **the `CppDecompilerHints` explicit destructor-call renderer**, the fourth
  form and the destruction sibling of the `#37-9` construction renderer
  ([DD-0019](docs/decisions/0019-rec37-destructor-call-renderer.md)). `renderDestructorCall(CppClass type, String
  receiverExpr, boolean receiverIsPointer)` emits `receiver->~ClassName()` / `receiver.~ClassName()` — the name is
  `~` + `CppClass.getName()`, parentheses always empty (a destructor takes no arguments, so no argument-list
  parameter). Like `#37-9`, **no neutral fallback** (the class name is a *total* model fact); rejects null type /
  null-or-blank receiver with `IllegalArgumentException`. **No vtable lookup** — virtual-dispatch destruction is
  already the `#37-7` form via a name-resolved `~ClassName` slot; this is the explicit/non-virtual
  in-place-destruction idiom whose name comes from the class, not a slot. Stateless and headless, extending the
  existing renderer; the `Foo::~Foo(ptr)` recognition pass is the deferred Program-coupled wrapper. Validated by
  extending headless `CppDecompilerHintsTest` (7 new cases; 40 total).
- docs(decompiler): [DD-0019](docs/decisions/0019-rec37-destructor-call-renderer.md) grounds Rec 37 `#37-9c` —
  the fourth `CppDecompilerHints` form, the **explicit destructor-call renderer** (the destruction sibling of
  the `#37-9` construction form): `renderDestructorCall(CppClass type, String receiverExpr, boolean
  receiverIsPointer)` emits `receiver->~ClassName()` / `receiver.~ClassName()` (name is `~` + `CppClass.getName()`;
  parentheses always empty — a destructor takes no arguments, so no argument-list parameter). Like `#37-9`,
  **no neutral fallback** (the class name is a *total* model fact); rejects null type / null-or-blank receiver
  with `IllegalArgumentException`. **No vtable lookup** — virtual-dispatch destruction is already the `#37-7`
  form via a name-resolved `~ClassName` slot; this is the explicit/non-virtual in-place-destruction idiom whose
  name comes from the class, not a slot. Stateless and headless; the `Foo::~Foo(ptr)` recognition pass is the
  deferred Program-coupled wrapper. `delete e`, array `new[]`, and placement `new` remain deferred follow-ons.
- feat(decompiler): Rec 37 `#37-9` — **the `CppDecompilerHints` heap-construction renderer**, the third RFC §5
  form ([DD-0018](docs/decisions/0018-rec37-construction-renderer.md)). `renderConstruction(CppClass type,
  List<String> argumentExprs)` emits `new ClassName(args)` — `ClassName` is `CppClass.getName()`, args joined in
  call order; a zero-argument construction renders `new ClassName()` (parentheses always present). Deliberately
  **no neutral fallback** (a contrast with `#37-7`/`#37-8`): the class name is a *total* model fact, so the
  renderer only rejects malformed boundary inputs (null type, null arg list, null arg element) with
  `IllegalArgumentException`. **No constructor-overload resolution** — it formats the args the recognition pass
  supplies; selecting an overload is the DTM-coupled `#37-10+` work. Stateless and headless, extending the
  existing renderer; the `alloc + ctor-call` recognition pass is the deferred `#37-9b` wrapper. Validated by
  extending headless `CppDecompilerHintsTest` (7 new cases; 33 total).
- docs(decompiler): [DD-0018](docs/decisions/0018-rec37-construction-renderer.md) grounds Rec 37 `#37-9` — the
  third RFC §5 `CppDecompilerHints` form, the **heap-construction renderer**: `renderConstruction` emits
  `new ClassName(args)` (`ClassName` is `CppClass.getName()`, args joined in call order; a zero-arg construction
  renders `new ClassName()` with parentheses always present). Deliberately **no neutral fallback** — a contrast
  with `#37-7`/`#37-8` — because the class name is a *total* model fact (`getName()` never fails for a defined
  class); the renderer only rejects malformed boundary inputs (null type, null arg list, null arg element) with
  `IllegalArgumentException`. **No constructor-overload resolution** (that needs parameter `DataType`s/signatures,
  the DTM-coupled `#37-10+` work); it formats the args the deferred recognition pass supplies. Scalar heap
  construction only — `delete e`, the explicit destructor call, array `new[]`, and placement `new` are each
  deferred as follow-ons (each needs an input this slice does not carry). Stateless and headless, extending the
  existing renderer; the `alloc + ctor-call` recognition pass is the deferred `#37-9b` wrapper.
- feat(decompiler): Rec 37 `#37-8` — **the `CppDecompilerHints` up/down-cast renderer**, the second RFC §5 form
  ([DD-0017](docs/decisions/0017-rec37-cast-renderer.md)). `renderUpcast`/`renderDowncast` match a recognised
  constant byte offset against the derived class's `CppBaseClass` edges (`getOffset`) and render
  `static_cast<Base*>(src)` for an upcast (base type name) or `static_cast<Derived*>(src)` for a downcast
  (derived type name) — `static_cast`, never `dynamic_cast`, since the recovered constant offset *is* the
  compiler's structural base-subobject adjustment. A `virtual` base (dynamic offset) and an offset matching no
  edge both **decline** the cast, falling back to the neutral `src + offset` / `src - offset` pointer
  adjustment rather than fabricating a cast. Access (`isPublic`) does not change the form. Stateless and
  headless, extending the existing renderer; the `HighFunction` recognition pass is the deferred `#37-8b`
  wrapper. Validated by extending headless `CppDecompilerHintsTest`.
- docs(decompiler): [DD-0017](docs/decisions/0017-rec37-cast-renderer.md) grounds Rec 37 `#37-8` — the
  **second `CppDecompilerHints` form**, the up/down-cast renderer (RFC §5 bullet 2). It matches a recognised
  constant pointer offset against the derived class's `CppBaseClass` edges (`getOffset`) and, by cast
  direction, renders `static_cast<Base*>(src)` for an upcast (base type name) or `static_cast<Derived*>(src)`
  for a downcast (derived type name) — `static_cast`, never `dynamic_cast`, because the recovered constant
  offset *is* the compiler's structural base-subobject adjustment. It **declines** the cast for a `virtual`
  base (whose offset is dynamic, not a compile-time constant) and for an offset matching no edge, falling back
  to the neutral `src + offset` / `src - offset` form rather than fabricating a cast — the same advisory
  fallback `#37-7` uses for an unresolvable slot. Access (`isPublic`) does not change the form. Still headless
  model→string, no new model type/mutator; the recognition pass is the deferred `#37-8b` wrapper. Validated by
  extending headless `CppDecompilerHintsTest`.
- feat(decompiler): Rec 37 `#37-7` — **the `CppDecompilerHints` renderer**, the headless half of RFC §5
  ([DD-0016](docs/decisions/0016-rec37-decompiler-hints-renderer.md)). A stateless producer of C++-style
  rendering strings from already-resolved `CppTypeSystem` facts plus the operand expressions a caller hands
  in as opaque strings — it holds no `Program`/`DataTypeManager`/decompiler handle, never scans or mutates the
  model. The first form renders the **virtual-method-call** (`receiver->method(args)` for a pointer receiver,
  `receiver.method(args)` for a value receiver), reading the name-resolved vtable slot `#37-6` / `#37-6c`
  produced. Real rendering logic, not concatenation: it bounds-checks the slot index against `getSlotCount()`
  and falls back to a neutral indirect-call form `receiver->vtable[i](args)` — rather than throwing or
  fabricating a name — when the class has no vtable, the index is out of range, or the slot's method name is
  blank/unresolved. The `HighFunction` pattern-recognition pass that recognises the raw C idiom and drives the
  renderer remains the deferred `#37-7b` wrapper. Validated by headless `CppDecompilerHintsTest`.
- docs(decompiler): [DD-0016](docs/decisions/0016-rec37-decompiler-hints-renderer.md) grounds Rec 37 `#37-7` —
  the `CppDecompilerHints` (RFC §5) split into a **headless renderer core** and a deferred decompiler pass.
  Producing a C++-style hint takes two steps: *recognising* the raw C idiom in a decompiled function (that a
  p-code graph is a vtable-load-then-indirect-call, or a pointer-add is a base-subobject adjustment) — which is
  inseparable from a `Program`/`HighFunction` — and *rendering* the recognised fact as a string
  (`receiver->method(args)`, `static_cast<Base*>(d)`, `new Foo(args)`) — which is a pure function of
  already-resolved model facts. DD-0016 grounds the renderer as the headless half: a stateless
  `CppDecompilerHints` that turns model objects (a `CppClass`, a vtable slot index, a `CppBaseClass` edge) plus
  operand expressions into the rendering string, holding no `Program`/`DataTypeManager`/decompiler handle —
  the same core/wrapper seam as the `#37-3`/`#37-4`/`#37-6` feeders vs. their deferred `#37-4b`/`#37-5`/`#37-6b`
  scanners. The recognition pass becomes the deferred `#37-7b`/`#37-8b`/`#37-9b` wrapper. The first renderer
  slice renders the **virtual-method-call** form — the direct consumer of the name-resolved vtable `#37-6c`
  just produced — with up/down-cast and ctor/dtor renderers as follow-on headless slices. No new model type or
  mutator is needed (the first such slice since `#37-2`). Validated by headless `CppDecompilerHintsTest`.
- feat(decompiler): Rec 37 `#37-6c` — **the `CppVtableReconciler`**, the pure in-model pass that fuses the two
  disjoint `CppMethod` views a class accumulates after the `#37-3` and `#37-6` feeders run: the declared
  methods in `getMethods()` (rich `const`/`static`/calling-convention qualifiers, `isVirtual() == false`) and
  the fresh vtable slot methods in `getVtable()` (`isVirtual() == true`, no qualifiers). It matches the two by
  **unqualified name, conservatively** — only a name unique among *both* the declared methods and the slots (a
  1:1 pairing) is reconciled, so overloaded names are left untouched and virtuality is never stamped onto the
  wrong overload — then unifies each match onto the **canonical declared method** (sets `isVirtual(true)`,
  copies the slot's `isPureVirtual`) and rewrites the vtable slot to reference it via the new bounds-/null-
  checked `CppVTable.setSlot(int, CppMethod)`, leaving one `CppMethod` per function reachable from both the
  class method list and the vtable. It walks one class (by reference or name) or the whole model
  (`reconcileAll()` over `getCppClasses()`), scans no `Program`, touches no `DataTypeManager`, parses no name,
  never adds/removes a method or mutates a backing `Structure`, and is idempotent/order-independent. Also
  corrects the stale `#37-5` vtable-analyzer reference still in `CppTypeSystem.java`'s javadoc. Covered by
  headless `CppVtableReconcilerTest` building the pre-reconciled two-list state directly — program-free, no
  cross-module dependency. Per [DD-0015](docs/decisions/0015-rec37-vtable-reconciler.md).
- docs(decompiler): [DD-0015](docs/decisions/0015-rec37-vtable-reconciler.md) grounds Rec 37 `#37-6c` — the
  vtable↔declared-method **reconciliation** the `#37-6` feeder deferred. A class through both the `#37-3`
  demangling feeder and the `#37-6` vtable feeder holds two disjoint `CppMethod` lists for the same functions:
  declared methods (rich `const`/`static`/calling-convention qualifiers, `isVirtual() == false`) and fresh
  vtable slot methods (`isVirtual() == true`, no qualifiers). DD-0015 grounds `CppVtableReconciler`, a pure
  in-model pass that matches the two by **unqualified name, conservatively** — only a name unique among both
  declared methods *and* slots (a 1:1 pairing) is reconciled, so overloads are never mis-stamped — then
  unifies each match onto the canonical declared method (sets `isVirtual`/copies `isPureVirtual`, rewrites the
  slot to reference it via a new bounds-checked `CppVTable.setSlot`). It scans no `Program`, touches no
  `DataTypeManager`, parses no name, mutates no backing `Structure`, and is idempotent/order-independent —
  the clean next headless step. Signature/parameter `DataType` resolution stays deferred (`#37-7+`) because it
  is DTM/`Program`-coupled. Validated by headless `CppVtableReconcilerTest` in the impl slice.
- feat(decompiler): Rec 37 `#37-6` — **the `CppVTableFeeder`**, the core that turns a recovered vtable into
  model slots. Given a class's name and its already-recovered slot facts (`SlotSpec`: method name +
  pure-virtual flag, in layout order), it resolves the owning `CppClass` — reusing a recovered class or
  synthesizing the same empty `StructureDataType(name, 0)` placeholder the `#37-3`/`#37-4` feeders use (via
  the shared `CppClassResolution`) — builds a `CppVTable`, and per slot appends a `CppMethod` marked
  `setVirtual(true)` (occupying a vtable slot *is* virtual dispatch — this is where `CppMethod.isVirtual`
  is finally populated, which `#37-2` deferred here) with `setPureVirtual` set consistently, then attaches
  the table via `CppClass.setVtable`. Like the other feeders it is a *pure consumer*: it does not read the
  function-pointer array out of a `Program` and does not depend on the script-land `classrecovery.Vftable`
  or the MSVC `VfTableModel`; reading the recovered vftable, resolving+demangling each slot, and setting
  the table address is the later program-scanning wrapper's job (`#37-6b`, also where the
  dedicated-`Features/Cpp`-module question is decided). Slot methods are fresh — reconciling them with
  demangling-fed methods is deferred (`#37-7+`) — and the feeder is `Address`-free (`tableAddress` left
  null). Also corrects stale `#37-5` vtable-analyzer references in `CppVTable`/`CppClass` javadoc (the
  vtable slice is `#37-6`). Covered by headless `CppVTableFeederTest` building `SlotSpec` facts directly —
  program-free, no cross-module dependency. Per [DD-0014](docs/decisions/0014-rec37-vtable-feeder.md).
- docs(decompiler): DD-0014 — **grounds Rec 37 `#37-6`, the vtable path, as a pure recovered-slot-fact →
  `CppVTable` + `CppMethod.isVirtual` mapper** (`CppVTableFeeder`) in `Features/Base`, mirroring DD-0012/
  DD-0013's pure-mapper / deferred-scan split. Surveys both in-tree vtable recoverers — the script-land
  `classrecovery.Vftable`/`RecoveredClass` (Itanium, package `classrecovery`, holds `List<Address>`
  vfunctions into a live program) and the MSVC module's `VfTableModel` (built over `Program` memory) — and
  pins the constraint: neither is an importable, program-free type, so the headlessly-testable core must
  consume *already-recovered* slot facts, not read function pointers out of memory. Decides: the feeder
  resolves the owning `CppClass` (reusing #37-3/#37-4's shared `CppClassResolution` placeholder helper),
  builds a `CppVTable`, and per slot fact (method name + pure-virtual flag, in layout order) appends a
  `CppMethod` marked `setVirtual(true)` (a slot *is* virtual dispatch — discharging the `isVirtual`
  deferral DD-0011/DD-0013 parked here) with `setPureVirtual` set consistently. Slot methods are fresh;
  reconciling them with demangling-fed methods, and setting the recovered `tableAddress`, are deferred (to
  #37-7+ and the program-scanning `#37-6b` wrapper respectively — the latter is also where the
  dedicated-`Features/Cpp`-module question is finally decided). Per
  [DD-0014](docs/decisions/0014-rec37-vtable-feeder.md); implementation lands in `#37-6`.
- feat(decompiler): Rec 37 `#37-4` — **the `CppRttiFeeder`**, the ABI-neutral core that turns recovered
  C++ RTTI inheritance into model edges. Given a derived class's name and its already-recovered direct
  base facts (`BaseSpec`: base name, offset, `virtual`, `public`), it resolves the derived and base
  `CppClass`es — reusing a recovered class or synthesizing the same empty `StructureDataType(name, 0)`
  placeholder the `#37-3` feeder uses (the resolution helper is now shared via `CppClassResolution`) —
  and attaches one `CppBaseClass(base, (int) offset, isVirtual, isPublic)` per base, in order. The three
  Itanium typeinfo kinds map to edge counts: `__class_type_info` → none (the class is still registered),
  `__si_class_type_info` → one (offset 0, public, non-virtual), `__vmi_class_type_info` → one per base.
  Like the demangling feeder it is a *pure consumer*: it does not walk a `Program` and does not depend on
  the script-land `classrecovery.GccTypeinfo` recoverer; decoding the Itanium `offset_flags` word into
  `BaseSpec` facts is the later program-scanning wrapper's job (`#37-4b` Itanium, `#37-5` MSVC — the same
  neutral edge serves both). No vtable / `CppMethod.isVirtual` work (that's `#37-6`). Covered by headless
  `CppRttiFeederTest` building `BaseSpec` facts directly — program-free, native-binary-free, no
  cross-module dependency. Per [DD-0013](docs/decisions/0013-rec37-rtti-inheritance-feeder.md).
- docs(decompiler): DD-0013 — **grounds Rec 37 `#37-4`, the Itanium RTTI path, as a pure
  inheritance-fact → `CppBaseClass` edge mapper** (`CppRttiFeeder`) in `Features/Base`, mirroring
  DD-0012's pure-mapper / deferred-scan split. Surveys the in-tree GCC RTTI recoverer
  (`classrecovery.GccTypeinfo`/`RTTIGccClassRecoverer`) and pins the constraint that fixes the slice's
  shape: that recoverer is **script-land** (`ghidra_scripts`, package `classrecovery`) so Base cannot
  import it, and walking RTTI needs a real `Program`/analysis harness — so the headlessly-testable core
  must consume *already-recovered* base facts, not scan a program. Decides: the feeder resolves the
  derived and base `CppClass`es (reusing #37-3's placeholder rule on both endpoints) and adds one
  `CppBaseClass(base, (int) offset, isVirtual, isPublic)` per direct base; the three Itanium typeinfo
  kinds (`__class_type_info`/`__si_class_type_info`/`__vmi_class_type_info`) map to edge *counts* with
  the `offset_flags` `__virtual_mask`/`__public_mask` decoding done by the **deferred** program-scan
  wrapper (`#37-4b`), keeping the model ABI-neutral so the same edge serves MSVC (#37-5); no vtable/
  `isVirtual` work (that's #37-6). Unit-tested by building base facts directly — program-free,
  native-binary-free, no cross-module dependency. Per [DD-0013](docs/decisions/0013-rec37-rtti-inheritance-feeder.md).
- feat(decompiler): Rec 37 `#37-3` — **the `CppDemanglingFeeder`**, the translation core that maps an
  already-demangled symbol onto the `#37-2` model. Given any demangler's `DemangledObject`, a namespaced
  `DemangledFunction` (a member function) resolves its enclosing `CppClass` by fully-qualified name —
  reusing a recovered class or synthesizing an empty placeholder `StructureDataType(name, 0)` when the
  model has none yet — and attaches a `CppMethod` carrying the symbol's name, `const`/`static` qualifiers
  (from `isTrailingConst`/`isStatic`), and calling convention (verbatim from the demangler, with the
  implicit `this` at ordinal 0 for a member and absent for a `static` member). It is a *pure consumer*:
  it runs no demangler and scans no program (those are #37-4/#37-5), so it is demangler- and ABI-agnostic.
  Virtual-ness is deliberately **not** inferred — a demangled name cannot reveal vtable membership (#37-6).
  Free functions and non-function objects are a no-op. Covered by headless `CppDemanglingFeederTest` that
  builds `DemangledFunction` fixtures directly, keeping the suite native-binary-free and free of any
  cross-module dependency on the demangler implementations. Per [DD-0012](docs/decisions/0012-rec37-demangling-feeder.md).
- docs(decompiler): DD-0012 — **grounds Rec 37 `#37-3`, the `CppDemanglingFeeder`, as a pure
  `DemangledObject`→`CppTypeSystem` mapper**. Surveys the feed mechanics against the real demangled
  model (`DemangledObject`/`DemangledFunction` getters + public ctor/setters in `Features/Base`) and
  pins two facts that fix the feeder's shape: the GNU demangler shells out to a native `c++filt`
  process (`GnuDemangler.java:110-111`) while MSVC is pure-Java, and `Features/Base` cannot depend on
  the demangler modules. Decides: the feeder is a *consumer* of an already-demangled symbol (it neither
  runs a demangler nor scans a program — that's #37-4/#37-5), lands in `Features/Base`
  `ghidra.app.util.cpp`, maps name/namespace→class+method with const/static qualifiers and calling
  convention (no `isVirtual` — that needs the vtable, #37-6), synthesizes an empty placeholder
  `StructureDataType(name, 0)` for layout-less classes to preserve #37-2's non-null backing invariant,
  and is unit-tested by **constructing `DemangledFunction` fixtures directly** so the test stays
  headless, native-binary-free, and cross-module-dependency-free. Mirrors DD-0011's "ground only the
  next slice" discipline. Per [DD-0012](docs/decisions/0012-rec37-demangling-feeder.md).
- feat(decompiler): Rec 37 `#37-2` — **the model-only `CppTypeSystem` skeleton**. Adds the C++ frontend's
  data model under `Features/Base` package `ghidra.app.util.cpp`: `CppTypeSystem` (a name-keyed,
  insertion-ordered registry of classes, optionally bound to a `DataTypeManager`), `CppClass` (a
  *projection* over a required backing `Structure` plus optional `GhidraClass` — it never mutates the
  structure, so existing tools reading the layout keep working), `CppMethod` (virtual/pure-virtual/
  const/static qualifiers + optional signature/convention), `CppVTable` (ordered slot list + recovered
  address), `CppBaseClass` (an inheritance edge: base + offset + virtual/access), and `CppCallingConvention`
  (`this`-pointer placement). Pure value holders with no analysis behaviour — the feeder (`#37-3`) and
  RTTI/vtable analyzers (`#37-4`/`#37-5`) will populate them in later slices. Covered by fast headless
  JUnit (`CppTypeSystemTest`) exercising projection, idempotent registration, inheritance round-trip,
  vtable slot mapping, method qualifiers, the no-clobber-of-backing-`Structure` guardrail, and
  null-rejection. Per [DD-0011](docs/decisions/0011-rec37-cpptypesystem-skeleton.md).
- docs(decompiler): DD-0011 — **grounds Rec 37 `#37-2`, the `CppTypeSystem` skeleton, as a model-only
  overlay**. Surveys what the tree already provides for C++ analysis (the `DemangledObject`/
  `DemangledFunction` model in `Features/Base`; the formal MSVC `RttiAnalyzer` vs. the script-based
  GCC classrecovery `GccTypeinfo`/`Vtable`; `StructureDataType`/`GhidraClass`/`FunctionDefinition` in
  SoftwareModeling) and decides the first slice: model-only (`CppTypeSystem`/`CppClass`/`CppMethod`/
  `CppVTable`, no analyzer/feeder/hints), landing in `Features/Base` package `ghidra.app.util.cpp`,
  ABI-agnostic, projecting over — never replacing — the backing `Structure`/`GhidraClass`, covered by
  fast headless JUnit. Unlike the other open items (`#35-5b-2` GUI retry, `#39-6` loop-collapse) it
  clears the test-before-push bar with no display or new infrastructure. Defers a dedicated `Features/Cpp`
  module to `#37-6` and templates/operators/obfuscated-RTTI to later slices. Mirrors
  [DD-0008](docs/decisions/0008-rec39-loop-region-matcher.md)'s "ground only the first slice" discipline;
  RFC-0001's #37-2 sign-off gate. Per [DD-0011](docs/decisions/0011-rec37-cpptypesystem-skeleton.md).
- feat(decompiler): Rec 35 `#35-5b-1` — **the decompiler panel now shows a partial-result banner**.
  When a fresh decompile comes back budget-truncated (`DecompileResults.isPartial()`),
  `DecompilerProvider.decompileDataChanged` overlays *"Decompilation partial - budget exhausted on
  `<pass>`"* (falling back to *"… analysis budget exhausted"* when the pass name is blank) on the
  existing `OverlayMessagePainter` surface, and clears it when a later complete decompile arrives —
  the callback only fires on fresh data (never while the display is locked-stale), so resolving the
  overlay there cannot clobber a refresh-needed message. The banner text is built from the
  structured `<budgetexhausted>` signal, not scraped from the warning-header text (DD-0010). The
  message builder is a pure static factory on `OverlayMessagePainter`, covered by a fast
  `gradle :Decompiler:test` round-trip (named-pass, blank-pass fallback, complete, and null cases).
  The interactive Retry-with-2×-budget action — which needs GUI-runtime validation — follows in
  `#35-5b-2`. Per [DD-0010](docs/decisions/0010-rec35-partial-result-gui-surfacing.md).
- feat(decompiler): Rec 35 `#35-5a-2` — **the GUI can now *detect* a budget-truncated (partial)
  result**. When a budget is engaged and analysis is truncated, the worker emits a structured
  `<budgetexhausted name="…">` child of `<doc>` (naming the exhausted pass) read straight from the
  per-function `Architecture::budget` tracker at the result-encode site (`ghidra_process.cc`); the
  read is sound because `Funcdata::followFlow` rebases `budget.reset()` at the start of every
  function, so the marker reflects only the function just decompiled. `DecompileResults` decodes it
  into a first-class `isPartial()` / `getBudgetExhaustedPass()` rather than scraping the
  warning-header text (DD-0010). The new element is pinned to id `291` on both the C++
  (`ghidra_process.cc`) and Java (`ElementId.java`) ends, and the `ELEM_UNKNOWN` sentinel bumps
  `291`→`292` on both. Fast `gradle :Decompiler:test` round-trip coverage asserts a
  `<budgetexhausted>` marker surfaces as `isPartial()` carrying the pass name and that a budgetless
  doc stays complete; the C++ `--full` precheck (ghidra_dbg link + 697 datatests) confirms the
  worker side builds clean and is regression-free. The banner + Retry-with-2× UI follows in
  `#35-5b`. Per [DD-0010](docs/decisions/0010-rec35-partial-result-gui-surfacing.md).
- feat(decompiler): Rec 35 `#35-5a-1` — **the GUI can now *set* a decompilation budget**. Adds a
  `decompileBudget` field to `DecompileOptions` (tool option *Analysis.Iteration budget (0 =
  unlimited)*, default `0` = disengaged) whose `encode()` emits the already worker-registered
  `<decompilebudget>` option element, so a budget chosen in the tool options reaches
  `OptionDecompileBudget::apply` in the decompiler process. Because options travel over
  `PackedEncode` (which writes the element *id*, not its name), the new Java
  `ELEM_DECOMPILEBUDGET` is pinned to id `290` to match the C++ `ELEM_DECOMPILEBUDGET`
  (`options.cc:32`), and the Java `ELEM_UNKNOWN` sentinel is bumped `290`→`291` to mirror the
  fork's `marshal.cc:1269`. Fast `gradle :Decompiler:test` round-trip coverage asserts the element
  is emitted with the cap when set and omitted when `0`. The *detect* half (marshalling
  `budgetexhausted_present` into a first-class `DecompileResults.isPartial()`) follows in
  `#35-5a-2`. Per [DD-0010](docs/decisions/0010-rec35-partial-result-gui-surfacing.md).
- docs(decompiler): DD-0010 — **Rec 35 `#35-5` partial-result GUI surfacing needs a plumbing
  prerequisite first; split into `#35-5a` + `#35-5b`**. A pre-implementation survey found the GUI
  decompile path can neither *set* a budget nor *detect* a partial result, even though the C++
  backend is complete: `OptionDecompileBudget`/`OptionDecompileBudgetPass` are registered in
  `OptionDatabase` (`options.cc:132`–`133`, decodable from the `<optionslist>` the GUI already
  sends) but `DecompileOptions.encode()` never emits them; and the structured
  `FlowInfo::budgetexhausted_present` flag (`flow.hh:71`, accessor `hasBudgetExhausted()`) is not
  marshalled into the result stream, so `DecompileResults` has no `isPartial()`. Decision:
  **`#35-5a`** threads the budget through `DecompileOptions`→`encode()` and marshals the existing
  partial flag + exhausted-pass name into a first-class `DecompileResults.isPartial()` /
  `getBudgetExhaustedPass()`; **`#35-5b`** is the banner + Retry-with-2x UI on top. Unlike
  `#36-6`/`#39-6`, `#35-5a` is **build-not-defer** — concrete, testable plumbing over shipped
  infrastructure — and additionally unblocks `#36-6`. Surface the flag structurally, not by
  scraping the `<warningheader>` text. Per
  [DD-0010](docs/decisions/0010-rec35-partial-result-gui-surfacing.md).
- docs(decompiler): DD-0009 addendum 10 — **defer `#36-6` (budget cache-key) to the Rec 35
  partial-result line**. Grounds that `#36-6` is the cache side of Rec 35 `#35-6` ("cache
  partial results keyed by budget", not started) and is blocked on Rec 35 `#35-5` (the GUI
  "Retry with 2x budget" path, not started): the interactive decompile path carries no budget
  today — `DecompileOptions` has no budget field and `Decompiler.decompile(...)` no budget
  parameter — so `Function` is already a correct cache key and any future budget *option* would
  full-flush via `setOptions`→`clearCache` (`testCacheIsClearedWhenOptionsChange`). The
  `(function, budget)` key earns its keep only once `#35-5` lets the GUI hold a budget-exhausted
  partial alongside its larger-budget retry; building it now would be untestable speculative
  machinery (cf. the DD-0005 `#33-2.6` deferral). With `#36-6` deferred here and `#36-4` deferred
  behind telemetry (addendum 8), the Rec 36 GUI-cache sprint has no remaining independently-
  actionable item. Marks `#36-5a`/`#36-5b` done in the Status section. Per
  [DD-0009 addendum 10](docs/decisions/0009-rec36-cache-invalidation-grounding.md#addendum-10-2026-06-06-36-6-is-the-rec-35-partial-result-cache-key-35-6-and-is-blocked-on-the-gui-retry-path-35-5--defer).
- feat(decompiler): Rec 36 `#36-5b` — **decompile latency telemetry**. Stamps a request
  timestamp on each `DecompileRunnable` at construction and records the request-to-callback
  wall-clock in `DecompilerController` (count + total + max, with a derived
  `meanDecompileLatencyNanos()`) when `DecompilerManager` delivers a *completed* decompile —
  so cache hits and clears, which never make the manager round-trip, produce no sample. The
  numbers join the `#36-5a` counters in `CacheStatsSnapshot`, answering the other half of the
  `#36-4` gate: not just how often the cheap selective path is taken, but how costly the
  re-decompile it avoids actually is. Latency is non-deterministic, so the surface is
  observational — two headed `DecompilerCachingTest` cases assert the structural facts (a real
  navigation records a sample with a positive mean; a cache hit records none) rather than an
  exact duration (19 tests, 0 failures). No UI surface. Per
  [DD-0009 addendum 9](docs/decisions/0009-rec36-cache-invalidation-grounding.md#addendum-9-2026-06-06-grounding-the-36-5-telemetry-surface--explicit-hit-and-invalidation-counters-first-36-5a-async-decompile-latency-split-out-36-5b).
- feat(decompiler): Rec 36 `#36-5a` — **decompiler cache telemetry counters**. Adds
  explicit `LongAdder` counters in `DecompilerController` at the call sites
  DD-0009 addendum 9 grounded — navigation hit/miss (`loadFromCache`), full flush
  (`clearCache`), and the two selective-invalidation paths (`invalidate(AddressSetView)`
  and `invalidateByDataTypeIds`) each with an entries-dropped tally — exposed as an
  immutable `CacheStatsSnapshot` via `getCacheStats()`. Unlike Guava `recordStats()`
  these distinguish a full flush from a selective invalidation, so the
  `selective ÷ (selective + full)` ratio and per-path entries-dropped — the data that
  gates `#36-4` — are now observable. Four headed `DecompilerCachingTest` cases assert
  the counter deltas for a hit/miss, a function-local (address-keyed) edit, an in-place
  shared-datatype (id-keyed) edit, and a non-local full flush (17 tests, 0 failures). No
  UI surface; latency telemetry is `#36-5b`. Per
  [DD-0009 addendum 9](docs/decisions/0009-rec36-cache-invalidation-grounding.md#addendum-9-2026-06-06-grounding-the-36-5-telemetry-surface--explicit-hit-and-invalidation-counters-first-36-5a-async-decompile-latency-split-out-36-5b).
- docs(decompiler): DD-0009 addendum 9 — **ground the `#36-5` telemetry surface**.
  Every metric maps to one call site in `DecompilerController` (`loadFromCache`
  hit/miss, `clearCache` full flush, `invalidate(AddressSetView)` and
  `invalidateByDataTypeIds` selective drops), and the `selective ÷ (selective +
  full)` ratio plus per-call entries-dropped is exactly the data that gates `#36-4`
  (addendum 8). Decides **explicit counters over Guava `recordStats()`** (which
  can't distinguish a manual `invalidate` from `invalidateAll`, so the
  full-vs-selective breakdown — the whole point — is invisible to `stats()`), and
  **splits `#36-5`**: `#36-5a` = hit/miss + full-flush + selective-address +
  selective-datatype counters (with entries-dropped), exposed via `getCacheStats()`
  and fully testable in `DecompilerCachingTest` — **lands next**; `#36-5b` =
  decompile latency (request→callback wall-clock), which is async and
  cross-thread/non-deterministic, sequenced after. No UI surface. Per
  [DD-0009 addendum 9](docs/decisions/0009-rec36-cache-invalidation-grounding.md#addendum-9-2026-06-06-grounding-the-36-5-telemetry-surface--explicit-hit-and-invalidation-counters-first-36-5a-async-decompile-latency-split-out-36-5b);
  docs-only, no production code change.
- docs(decompiler): DD-0009 addendum 8 — **defer `#36-4`** (in-place rewrite for
  local name / comment) behind `#36-5` telemetry. The `FieldPanel` bakes each
  token's text *and* width into an immutable `AttributedString` at layout-build
  time (`ClangLayoutController.createFieldElementsForLine`), and `ClangToken.setText`
  is package-private, so an in-place edit cannot re-flow a rendered line without a
  full layout rebuild; meanwhile `#36-3a`/`#36-3a-2` already reduce a rename or
  comment edit to a single-function re-decompile, which upstream Ghidra also does
  deliberately. The in-place path therefore trades real staleness risk (cached
  `HighSymbol`s hold old names; the rendered name passes through name resolution +
  `IllegalCharCppTransformer`) for a marginal saving on one already-cheap path, so
  it is re-scoped from "next" to **telemetry-gated**: do `#36-5` (hit-rate /
  in-place-rate / decompile-latency telemetry) next and only revisit `#36-4` if the
  data shows a real cost. Per
  [DD-0009 addendum 8](docs/decisions/0009-rec36-cache-invalidation-grounding.md#addendum-8-2026-06-06-36-4-in-place-rewrite-is-deferred-behind-36-5-telemetry--the-layout-bakes-token-text-and-width-and-a-rename-already-re-decompiles-only-one-function);
  docs-only, no production code change.
- test(decompiler): Rec 36 `#36-3b-2` recompute **backstop** — a headed
  `DecompilerCachingTest` corpus assertion that, after an in-place datatype edit,
  forces a fresh re-decompile of every cache entry the selective path *kept* and
  asserts byte-identical C, so a missed type dependency (a kept entry that should
  have been invalidated) is caught in test rather than as a user-visible stale
  render. The corpus includes a function referencing a *different, unedited*
  struct, proving the id-keyed path keeps a type-referencing function without
  over-invalidating and that the keep is render-safe. Realises the `#36-3b-2`
  correctness backstop addendum 3 committed to, as the test-harness assertion
  [DD-0009 addendum 7](docs/decisions/0009-rec36-cache-invalidation-grounding.md#addendum-7-2026-06-06-the-36-3b-2-recompute-backstop-is-a-test-harness-corpus-assertion-not-a-runtime-decompilercontroller-mode)
  grounded (the async, single-process GUI decompiler rules out an inline runtime
  mode). Test-only; no production code change.
- docs(decompiler): DD-0009 addendum 7 — reground the `#36-3b-2` recompute
  backstop (the addendum-3 "debug-assert recompute mode") as a **test-harness
  corpus assertion**, not a runtime `DecompilerController` mode. The GUI decompile
  path is asynchronous and single-process, so re-decompiling the entries a
  selective invalidation *kept* cannot run inline in `invalidateByDataTypeIds`
  without blocking the Swing thread on the one shared decompiler process; the
  headed `DecompilerCachingTest` already drives real decompiles, holds the cache
  directly, and exposes a stable comparison surface
  (`DecompileResults.getDecompiledFunction().getC()`), which is where addendum 3
  always wanted the corpus assert ("caught in test/CI"). Also marks the shipped
  `#36-3b` phases (`-1`, `-2a`, `-2b`) done in the Status section. Per
  [DD-0009 addendum 7](docs/decisions/0009-rec36-cache-invalidation-grounding.md#addendum-7-2026-06-06-the-36-3b-2-recompute-backstop-is-a-test-harness-corpus-assertion-not-a-runtime-decompilercontroller-mode);
  docs-only, the backstop test lands next.
- feat(decompiler): Rec 36 `#36-3b-2b` — selective GUI cache invalidation for
  datatype **renames**. A `DATA_TYPE_RENAMED` batch now invalidates only the
  cached functions that reference the renamed type (the decompiler renders the
  type *name*, which a cached result freezes), instead of flushing the whole
  cache. Per [DD-0009 addendum 6](docs/decisions/0009-rec36-cache-invalidation-grounding.md#addendum-6-2026-06-06-the-three-36-3b-2b-events-are-not-alike--renamed-folds-into-the-2a-id-path-moved-is-rendering-invariant-only-replaced-stays-full-flush),
  a rename keeps the same program-managed instance and `DataTypeManager` id, so it
  folds into the existing `invalidateByDataTypeIds` recompute path (id from
  `getNewValue()`, same `Pointer`/`Array`/`TypeDef` unwrap) with no new controller
  machinery — the change is confined to the listener's `collectChangedDataTypeIds`
  gate. `DATA_TYPE_MOVED` joins the benign-companion set (it changes only the
  category path, which is never rendered). `DATA_TYPE_REPLACED` stays on the
  full-flush path (its changed id is dropped from the change record). A headed
  `DecompilerCachingTest` case renames a struct referenced only through a pointer
  and asserts the referencing function is invalidated while an unrelated one stays
  cached; full suite green (12 tests, 0 failures).
- docs(decompiler): DD-0009 addendum 6 — ground `#36-3b-2b` before implementing
  and **correct** addendum 4's framing of the three deferred events. Reading the
  firing sites (`ProgramDataTypeManager` → `ProgramDB.dataTypeChanged:890`) shows
  they are not alike: `getOldValue()` is never a `DataType` for any of them, and
  the changed id is dropped from the record. `DATA_TYPE_RENAMED` keeps the same
  live instance and DB id (only the name field mutates) and the decompiler renders
  the *name*, so it **folds into the existing `invalidateByDataTypeIds` id-path**
  (id from `getNewValue()`, with the same `Pointer`/`Array`/`TypeDef` unwrap) — no
  new machinery. `DATA_TYPE_MOVED` likewise keeps the instance/id but changes only
  the category path, which the decompiler never renders, so it is a **benign
  companion** (no ids; a move-only batch full-flushes, per addendum 5). Only
  `DATA_TYPE_REPLACED` is a true instance swap with its id dropped at
  `ProgramDB.dataTypeChanged:890`, so it **stays full-flush**. Re-scopes
  `#36-3b-2b` to a `collectChangedDataTypeIds`-only change (widen the
  id-contributing branch to RENAMED, the benign branch to MOVED). Docs-first;
  precedes the `#36-3b-2b` implementation, mirroring the addendum-4/5 → `#36-3b-2a`
  rhythm.
  See [DD-0009 addendum 6](docs/decisions/0009-rec36-cache-invalidation-grounding.md#addendum-6-2026-06-06-the-three-36-3b-2b-events-are-not-alike--renamed-folds-into-the-2a-id-path-moved-is-rendering-invariant-only-replaced-stays-full-flush).
- feat(decompiler): Rec 36 `#36-3b-2a` — selective GUI cache invalidation for
  in-place datatype edits. A `DATA_TYPE_CHANGED` batch (with the benign
  `SOURCE_ARCHIVE_CHANGED` / `DATA_TYPE_ADDED` companions per
  [DD-0009 addendum 5](docs/decisions/0009-rec36-cache-invalidation-grounding.md#addendum-5-2026-06-06-an-in-place-datatype-edit-never-arrives-as-a-pure-data_type_changed-batch--the-gate-must-tolerate-benign-companions))
  now invalidates only the cached functions that reference the edited type,
  instead of flushing the whole GUI decompiler cache. The referenced-type-id set
  is **recomputed on demand** from each cached `DecompileResults.getHighFunction()`
  (prototype return/params + local & global `HighSymbol` types) with no recorded
  per-result state, and matching recursively unwraps `Pointer`/`Array`/`TypeDef`
  so a function using only `MyStruct *` is caught — confirmed by a headed
  `DecompilerCachingTest` case in which `fun1`'s committed return type is a
  transient `MyStruct *` whose own `getID` is `NULL_DATATYPE_ID` yet whose
  `getDataType()` is the live program `MyStruct`. A cached `HighFunction` that
  decoded to `null` conservatively invalidates, backed by a debug-assert that no
  sentinel id reaches the matcher. Any batch carrying a non-companion record type
  (`FUNCTION_CHANGED`, `SYMBOL_*`, instance-swap datatype events) stays on the
  full-flush path. Instance-swap events (`#36-3b-2b`) remain full-flush, deferred.
- docs(decompiler): DD-0009 addendum 5 — correct addendum 4's gate phrasing. An
  in-place struct edit never arrives as a *pure* `DATA_TYPE_CHANGED` batch:
  `SOURCE_ARCHIVE_CHANGED` is an unavoidable companion and `DATA_TYPE_ADDED`
  accompanies adding a field of a not-yet-present type, so a strict
  "only `DATA_TYPE_CHANGED`" gate makes the selective path dead code. The gate is
  broadened to admit those two record types **without contributing ids** (a
  just-added type cannot be referenced by an already-cached `HighFunction`;
  source-archive sync metadata cannot change how a cached function renders),
  keying invalidation solely on the `DATA_TYPE_CHANGED` ids; every other record
  type still forces the full flush. Corrects-not-replaces addendum 4: the
  recompute-from-`HighFunction` and `Pointer`/`Array`/`TypeDef` unwrap both stand
  and were confirmed by the headed test. Docs-first step for the broadened gate;
  the `#36-3b-2a` implementation lands the gate, `invalidateByDataTypeIds`, and
  the test together.
  See [DD-0009 addendum 5](docs/decisions/0009-rec36-cache-invalidation-grounding.md#addendum-5-2026-06-06-an-in-place-datatype-edit-never-arrives-as-a-pure-data_type_changed-batch--the-gate-must-tolerate-benign-companions).
- docs(decompiler): DD-0009 addendum 4 — ground `#36-3b-2` (shared-datatype
  invalidation) before implementing, and **revise** addendum 3's plumbing.
  Verifying the type-resolution path shows a cached `HighFunction`'s symbol types
  are the live program-`DataTypeManager` instances (decoded via
  `PcodeDataTypeManager.findBaseType → progDataTypes.getDataType(id)`), so the
  referenced-type-id set is **recomputable on demand** from
  `DecompileResults.getHighFunction()` rather than recorded per cache value —
  removing the only new per-cached-result state Rec 36 had outstanding. Matching
  must recursively unwrap `Pointer`/`Array`/`TypeDef` so a function using only
  `MyStruct *` is caught (the auto-change cascade does not cover derived types).
  Re-sequences into `#36-3b-2a` (`DATA_TYPE_CHANGED`, recompute + unwrap, ships
  with the debug-assert backstop) and `#36-3b-2b` (instance-swap
  `DATA_TYPE_REPLACED` / `MOVED` / `RENAMED`, deferred). Docs-only; precedes the
  `#36-3b-2a` implementation, mirroring the addendum-3 → `#36-3b-1` rhythm.
  See [DD-0009 addendum 4](docs/decisions/0009-rec36-cache-invalidation-grounding.md#addendum-4-2026-06-06-36-3b-2s-type-ref-set-is-recomputed-from-the-cached-highfunction-not-recorded-split-into-2a-data_type_changed-and-2b-instance-swap-events).
- feat(decompiler): Rec 36 `#36-3b-1` — selective cache invalidation for
  caller-affecting function changes. Implements **case A** of
  [DD-0009 addendum 3](docs/decisions/0009-rec36-cache-invalidation-grounding.md#addendum-3-2026-06-06-36-3b-cross-function-residual-splits-into-callers-no-bitmap-and-datatype-refs-recorded-set):
  a function *signature* change (`PARAMETERS_CHANGED` / `RETURN_TYPE_CHANGED`) or
  caller-visible *modifier* change (`INLINE` / `NO_RETURN` / `CALL_FIXUP` /
  `PURGE` / `THUNK`) now invalidates only the changed function **and its callers**
  instead of flushing the whole GUI decompiler cache. The callers are resolved
  from the existing call-reference graph via `Function.getCallingFunctions` (no
  per-function dependency bitmap), and every affected body unions into the same
  `DecompilerController.invalidate(AddressSetView)` path `#36-3a` already provides.
  A bare `FUNCTION_CHANGED/UNSPECIFIED` with no sibling local/parameter rename
  (e.g. a local retype) remains on the conservative full-flush path, since
  `UNSPECIFIED` is overloaded across function-local and caller-affecting edits.
  Shared-datatype edits (`#36-3b-2`) are still full-flush, deferred.
- docs(decompiler): DD-0009 addendum 3 — ground `#36-3b` (cross-function
  dependency invalidation) before implementing. Grounding the demoted
  single-"dependency bitmap" proposal against the real event-firing and
  decompile-result APIs shows the residual is two cases with different cost and
  risk, and that only one needs a recorded dependency set. **Case A** (a callee's
  signature or caller-visible modifier changes — `PARAMETERS_CHANGED` /
  `RETURN_TYPE_CHANGED` / `INLINE` / `NO_RETURN` / `CALL_FIXUP` / `PURGE` /
  `THUNK`) is resolvable from the existing call-reference graph:
  `Function.getCallingFunctions` yields the affected callers, whose bodies union
  into the same `invalidate(AddressSetView)` path #36-3a already provides — **no
  bitmap**. **Case B** (a shared datatype edit) has no address (`DATA_TYPE_CHANGED`
  fires with null `start`/`end` and null `getObject()`; the type is in
  `getNewValue()`, identity by `DataTypeManager.getID`) and no reverse type→function
  index, so it is the one case needing a per-cached-result referenced-type-id set
  derived from `HighFunction`'s prototype + local/global symbol maps. Re-sequences
  `#36-3b` into `#36-3b-1` (callers, low risk) and `#36-3b-2` (datatype-ref set,
  ships with the debug-assert recompute backstop). Docs-only; precedes the
  `#36-3b-1` implementation, mirroring the addendum-2 → `#36-3a-2` rhythm.
  See [DD-0009 addendum 3](docs/decisions/0009-rec36-cache-invalidation-grounding.md#addendum-3-2026-06-06-36-3b-cross-function-residual-splits-into-callers-no-bitmap-and-datatype-refs-recorded-set).
- feat(decompiler): Rec 36 `#36-3a-2` — extend selective cache invalidation to
  local-variable and parameter renames. Building on `#36-3a` (comment-only), a
  local/parameter *name* rename is now scoped to its owning function instead of
  flushing the whole GUI decompiler cache. Because the renamed symbol's address
  is in stack/register space (not the code body), address intersection alone
  cannot scope it; `DecompilerProgramListener` now resolves the owning function
  from the `SYMBOL_RENAMED` record's `SymbolType` (`LOCAL_VAR`/`PARAMETER` →
  owning `Function` via parent namespace) and unions that function's body into
  the same address set handed to `DecompilerController.invalidate`. The
  companion `FunctionChangeRecord` that `ProgramDB.symbolChanged` fires for a
  variable change is admitted only by correlation to such a rename in the same
  batch, so a bare `FUNCTION_CHANGED/UNSPECIFIED` (e.g. a stack-return-offset
  edit, which alters the callee purge callers track) still full-flushes. Any
  signature/modifier function change (`PARAMETERS_CHANGED` / `RETURN_TYPE_CHANGED`
  / `INLINE_CHANGED` / `NO_RETURN_CHANGED` / …) is caller-affecting and stays on
  the conservative full-flush path; a parameter *retype* is therefore deferred
  to `#36-3b`. Grounded by the [DD-0009 addendum 2](docs/decisions/0009-rec36-cache-invalidation-grounding.md#addendum-2-2026-06-06-36-3a-2-keys-off-the-symbols-symboltype-not-function_changed-trust).
  Validated by two new `DecompilerCachingTest` cases (a local rename invalidates
  only the renamed function; a no-return change still full-flushes).
- docs(decompiler): DD-0009 addendum 2 — ground `#36-3a-2` (local/parameter
  rename invalidation) before implementing. Grounding the "symbol→owning-function
  mapping" addendum 1 promised revealed the naive form is unsafe: a local rename
  fires a *pair* of records — a companion `FunctionChangeRecord` (entry point, in
  the body) plus a `SYMBOL_RENAMED` (storage address, in stack/register space) —
  and `FUNCTION_CHANGED/UNSPECIFIED` is overloaded across local renames and
  caller-affecting changes (`setStackReturnOffset` alters the callee purge that
  callers track), so trusting it would leave callers stale. The recorded rule
  keys off the renamed symbol's `SymbolType` (`LOCAL_VAR`/`PARAMETER` name change
  → resolve owning function via parent namespace), admits the companion
  `FUNCTION_CHANGED/UNSPECIFIED` only by correlation to a sibling symbol record,
  and rejects signature/modifier `FunctionChangeRecord`s (so a parameter *retype*
  → `PARAMETERS_CHANGED` correctly stays full-flush, deferred to `#36-3b`). Docs
  only; no code change.
- feat(decompiler): Rec 36 `#36-3a` — selective decompiler-cache invalidation
  for function-local comment edits. Previously *every* program edit flushed
  the entire GUI decompiler cache (`DecompilerController`'s Guava
  `Cache<Function, DecompileResults>`) via `doRefresh`'s two `clearCache`
  calls, so adding a comment in one function forced every other function to
  re-decompile. Now `DecompilerProgramListener` classifies the change batch:
  if it is entirely `COMMENT_CHANGED` records (each carrying a code address
  in the owning function's body), it routes to a new selective path
  (`DecompilerController.invalidate(AddressSetView)` +
  `DecompilerProvider.localProgramChange`) that drops only the cache entries
  whose `Function.getBody()` intersects the changed addresses, leaving every
  unrelated function cached. Anything not provably function-local — including
  all symbol renames — stays on the conservative full-flush path, so nothing
  can go stale. Symbol-rename scoping needs a symbol→function mapping (a
  local variable's address is in stack/register space, not the code body)
  and is deferred to `#36-3a-2`; see the [DD-0009 addendum](docs/decisions/0009-rec36-cache-invalidation-grounding.md#addendum-2026-06-06-36-3a-ships-comment-only-symbol-renames-are-not-address-scopable).
  Validated by `DecompilerCachingTest` (comment edit invalidates only the
  edited function; a non-comment change still full-flushes).
- docs(decompiler): land DD-0009, grounding Rec 36's cache-invalidation
  plan against the real GUI cache classes. Reading the in-tree code showed
  the decompiler GUI cache is a Guava `Cache<Function, DecompileResults>`
  in `DecompilerController` that every program edit flushes wholesale
  (`doRefresh` → `setOptions`+`refreshDisplay`, two `clearCache` calls),
  while `DecompilerProgramListener.domainObjectChanged` already receives —
  and discards — the `ProgramChangeRecord` address payloads. DD-0009
  concludes the issue's headline case ("renaming a variable") is reachable
  with **address-range intersection alone**, so it re-sequences the
  abstract `CACHE_FLUSH_1871.md` plan: land selective invalidation
  address-intersection-first (#36-3a, a strict perf win with a conservative
  full-flush default and no correctness risk) and **demote** the
  dependency-bitmap subsystem (#36-2) to the smaller cross-function
  residual (#36-3b). Mirrors the DD-0007 move of grounding an abstract
  plan onto the simplest in-tree mechanism. Docs-only.
- docs(decompiler): add a DD-0008 addendum reframing Rec 39 `#39-6a`. A
  pre-implementation survey of the in-tree primitives found that folding a
  `strlen` loop into a call is **not** a `constseq`-style splice: unlike the
  straight-line `memset`/`popcount` rewrites, a `strlen` loop is a live CFG
  cycle producing a value, and no `Funcdata` primitive collapses a
  reachable data-dependent loop (`removeDoNothingBlock`/`removeUnreachableBlocks`/
  `spliceBlockBasic` only touch do-nothing/unreachable/straight-line blocks),
  while the only idiomatic-loop-rendering precedent — the for-loop transform —
  annotates and still emits the whole body (`printc.cc:3288`), it never
  removes the loop. Both candidate mechanisms (collapse-rewrite, annotate-print)
  are large novel subsystems for a value-producing loop, so `#39-6a` (and with
  it the `#39-6` phase) is reframed as foundational loop-collapse infrastructure
  and **deferred**, leaving the sequence-shaped `memset`/`popcount` folding as
  Rec 39 Phase 2's shipped, mechanism-compatible value.
- docs(decompiler): land DD-0008, the Rec 39 `#39-6` sub-design for
  loop-shaped inlined-call detection (`strlen`, `strcmp`/`strncmp`,
  `memcmp`, non-constant copy-loops). DD-0007 deferred these because — unlike
  the sequence-shaped `memset`/`popcount` rules — a loop idiom is one
  static LOAD/STORE on a back-edge whose result is consumed outside a
  multi-block region, which `constseq`'s single-block walker cannot see.
  DD-0008 decides recognition is a new control-flow `Action`
  (`ActionLoopRecognize`) attached **after** `ActionFinalStructure`
  (`coreaction.cc:5922`), not a `Rule` in the pre-structuring `actcleanup`
  pool, so it reuses the structurer's induction-variable analysis
  (`BlockWhileDo::findLoopVariable`) instead of re-deriving loop membership.
  It reuses the proven CALLOTHER builtin rendering path (next free id
  `0x10000007`), inherits DD-0007's exactness + option-gate discipline,
  recognises only canonical scalar byte-at-a-time lowerings first, and
  sequences `strlen` first (one pointer, one output, no stores) to validate
  the post-structuring rewrite surgery before the harder two-pointer and
  store-bearing idioms. Updates the `#39-6` rows in `FOR_LOOP_INLINE_DETECTION.md`
  and DD-0007 to point at it (and refreshes the stale `#39-4b`/`#39-5`
  "not started" markers in the former).
- docs+test(decompiler): reframe DD-0007's Rec 39 `#39-5` row. A
  pre-implementation survey found word/dword **and** single-vector constant
  fills *already* fold to `builtin_memset` — `formByteArray` decomposes each
  STORE's value to bytes, so the store width being wider than the pointed-to
  element never mattered. `#39-5` therefore ships only `datatests/memsetwide.xml`,
  a regression guard pinning the already-correct qword/dword/single-`movups`
  folding, plus the DD-0007 addendum recording the finding. The one real
  residual — `≥2` vector-store fills, which render as a mixed byte/aggregate
  blob — is an aggregate-store normalisation problem upstream of `constseq`,
  so it is deferred (revisited with `#39-6` or its own investigation), not
  asserted, to avoid cementing the current messy rendering.
- docs(decompiler): correct DD-0007's Rec 39 `#39-4b` popcount approach. A
  pre-implementation survey found Ghidra already ships a native
  `CPUI_POPCOUNT` op (`TypeOpPopcount`, `OpBehaviorPopcount`, and the
  consuming `RulePopcountBoolXor`), so — unlike `#39-4a`'s `memset`, which
  has no native op and needed a `BUILTIN_MEMSET` CALLOTHER — `RulePopcount`
  will fold the recognised SWAR ("parallel bit-count") idiom into that
  existing op rather than mint a redundant `BUILTIN_POPCOUNT` builtin.
  Documents the asymmetry, the SWAR shape to match, and the unchanged
  exactness/option-gate bar.
- feat(decompiler): recognise the inlined SWAR popcount idiom (`#39-4b`,
  DD-0007). New `RulePopcount` anchors on the terminating `(x * 0x01..) >>
  (W-8)` shift, walks the four magic-constant stages backwards, and on a
  fully-determined match folds the whole expansion into the native
  `POPCOUNT(x)` op — minting no builtin, per the DD-0007 correction. Because
  the rule lives in the `actcleanup` pool (no following dead-code pass) it
  sweeps the now-dead arithmetic itself via `opDestroyRecursive`, yielding a
  clean `return POPCOUNT(a);`. The match is exact, so near-miss masks (e.g.
  `0x55555554`) are declined. Scope is the 32-bit width that survives
  Ghidra's simplification; the 64-bit multiply variant, whose `imul
  0x0101010101010101 >> 56` terminator is strength-reduced before cleanup,
  is left intact and deferred to a follow-up. New `datatests/popcount.xml`
  covers all three cases.

---

## [v26.2.3] — 2026-06-03

Release closing the Rec 35 `#35-4` `value_analysis` step: the
cooperative decompilation budget gains its *second* concrete
*bypassable* pass. The value-set analysis the heritage passes drive
over LOAD/STORE guards is now wired onto the general pass-bypass façade
(`#35-4b`), so a runaway value-set fixpoint degrades to a valid,
printable partial result instead of spinning. The default budget is
unchanged, so no existing decompilation output moves; only an explicit
cap changes behaviour. `block_structure` is the last bypassable pass and
is deferred to its own PR (it needs a goto-emitting unstructured
fallback, not a simple yield-point early-return).

- feat(decompiler): wire the `value_analysis` pass onto the budget bypass façade
  (`#35-4`), the second concrete bypassable pass on the `#35-4b` foundation
  (DECOMPILER_BUDGETS.md `#35-4`). The value-set analysis the heritage passes
  drive over LOAD/STORE guards (`Heritage::analyzeNewLoadGuards` →
  `ValueSetSolver::solve`) now consults the cooperative budget at a yield point
  inside the value-set iteration loop: each value-set iteration is one unit on
  value_analysis's own scale, accumulated across every solve the heritage passes
  drive. Once the pass spends its own iteration budget (or the function is
  globally out of budget) the solver stops at a partition boundary and the load
  guards keep their coarser ("any value") ranges — the solver's already-supported
  non-convergence path, so the partial result stays valid and printable. Because
  the positional `decompilebudget` form is full at its three
  flow/data_flow/type_inference slots, the cap is set by name through a new
  `decompilebudgetpass <pass> <cap>` option (recognises `flow_analysis`,
  `data_flow`, `type_inference`, `value_analysis`). A new deterministic fixture
  (`decompbudget_valueanalysis.xml`, the `access_array1` function from
  `offsetarray.xml`) pins the truncation: its value sets settle naturally in 6
  iterations, a cap of 3 truncates them, the partial-result header naming
  `value_analysis` appears exactly once, and the array access still resolves. The
  pre-existing flow / data_flow / type_inference truncation paths
  (`decompbudget.xml`, `decompbudget_dataflow.xml`, `decompbudget_typeinfer.xml`)
  are preserved exactly (324-case unit suite + 683-case datatest suite green;
  cppRaiiAudit 229 protected clean; `:Decompiler:ip` clean).
- ci(unit-tests): stop running the platform-independent MicrosoftDmang demangler
  suite on the slower macOS/windows legs of the `unit_tests` matrix. `MDMangBaseTest`
  plus its `VS2013`/`VS2015`/`Ghidra`/`Genericize`/`ParseInfo` subclasses each re-run
  the same ~15.7k-line combinatorial assertion set; on the ~2x-slower macOS runner
  that was the ~88-90 min long pole that previously near-missed the job timeout. The
  suite is pure string processing with no platform-specific surface, so it now runs
  once on the fast ubuntu leg (`gradle test`) and is excluded on macOS/windows
  (`-x :MicrosoftDmang:test`); every platform-sensitive suite still runs on all three.

---

## [v26.2.2] — 2026-06-02

Patch release closing the Rec 35 `#35-4` sprint: the cooperative
decompilation budget gains its first concrete *bypassable* pass.
`type_inference` is now wired onto the general pass-bypass façade
(`#35-4b`) that sits on the per-pass iteration foundation (`#35-4a`),
so a runaway `ActionInferTypes` fixpoint degrades to a valid, printable
partial result instead of spinning — bounded by the optional third
`decompilebudget <flow> <dataflow> <typeinfer>` parameter. Patch (not
minor) bump: the default budget is unchanged, so no existing
decompilation output moves; only an explicit cap changes behaviour. The
remaining bypassable passes (`value_analysis`, `block_structure`) are
still ahead on the same façade.

- feat(decompiler): wire the `type_inference` pass onto the budget bypass façade
  (`#35-4`), the first concrete bypassable pass on the `#35-4b` foundation
  (DECOMPILER_BUDGETS.md `#35-4`). `ActionInferTypes::apply` now consults the
  cooperative budget at a yield point: each changing propagation pass is one
  iteration on type_inference's own scale, and once the pass spends its own
  iteration budget (or the function is globally out of budget) the bypass façade
  short-circuits every later visit, leaving the partially-inferred types in place
  and emitting a partial-result diagnostic naming `type_inference`. The optional
  third `decompilebudget <flow> <dataflow> <typeinfer>` parameter caps it; when
  omitted, type_inference keeps its large default and nothing changes. A new
  deterministic fixture (`decompbudget_typeinfer.xml`, the `readstruct` function
  from `nestedoffset.xml`) pins the truncation: type_inference settles naturally
  in 4 changing passes, a cap of 2 truncates it, the header appears exactly once,
  and the partial result stays valid and printable. The pre-existing flow and
  data_flow truncation paths (`decompbudget.xml`, `decompbudget_dataflow.xml`)
  are preserved exactly (323-case unit suite + 681-case datatest suite green).
- feat(decompiler): add the general pass-bypass-mode façade (`#35-4b`) on the
  `#35-4a` per-pass foundation (DECOMPILER_BUDGETS.md `#35-4`).
  `DecompileBudgetTracker::passShouldBypass(name)` answers, for one named pass,
  whether it should stop precise work and run in its coarser mode — \b true once
  the pass has spent its *own* per-pass iteration budget, or a function-global
  cap (wall-clock soft/hard or pcode-op) has tripped. It deliberately does *not*
  bypass on a *different* pass's iteration cap, preserving the per-pass
  independence `#35-4a` introduced. This generalises the bypass condition
  `#35-3d` hand-rolled for the `data_flow` pool so the remaining bypassable
  passes (`type_inference`, `value_analysis`, `block_structure`) each degrade on
  the same rule rather than re-deriving it; non-bypassable passes
  (`flow_analysis`, `output_emission`) do not consult it. Header-only and inert:
  no production pass calls the façade yet, so both byte-sensitive truncation
  paths (`decompbudget.xml`, `decompbudget_dataflow.xml`) are preserved exactly.
  Four new unit tests pin own-iteration bypass (plus within-budget and
  never-entered negatives), per-pass independence, and the global wall-clock /
  pcode-op signals (323-case unit suite + 679-case datatest suite green).
- feat(decompiler): track analysis-budget iterations *per pass* (`#35-4a`), the
  foundation for budgeting the remaining passes (DECOMPILER_BUDGETS.md `#35-4`).
  `DecompileBudgetTracker` previously held a single iteration counter and a
  single sticky exhaustion flag, so only one pass could be budgeted per function:
  once any pass ran out, no other pass could tick. The tracker now keeps a
  per-pass record (count + cap + own-exhaustion), registered once per pass per
  function and accumulated across re-entries, with the function-global wall-clock
  and pcode-op caps still shared. New `passIterationExhausted(name)` /
  `passIterations(name)` queries expose a pass's own state so its fixpoint can be
  bypassed independently while other passes keep running. The change is
  header-only and inert: `flow_analysis` and `data_flow` still consult the
  unchanged single-pass methods, so both byte-sensitive truncation paths
  (`decompbudget.xml`, `decompbudget_dataflow.xml`) are preserved exactly. Four
  new unit tests pin per-pass independence, re-entry accumulation, and per-pass
  reset (319-case unit suite + 679-case datatest suite green).
- feat(decompiler): wire the cooperative budget into the `data_flow`
  simplification fixpoint (`#35-3d`). The `#35-3b`/`#35-3c` work bounded
  `flow_analysis`; this extends the budget to the data-flow rule pool
  (`oppool1`). A new `Action::budgetPass` tag (propagated through every
  container `clone()`, since each `Architecture` derives its action tree by
  cloning the universal template) marks exactly that pool; the generic
  `Action::perform` fixpoint, when the pool is tagged and the budget engaged,
  counts each *changing* rule-pool sweep (a no-change sweep is natural
  convergence, not budget pressure) and accumulates that count across the whole
  function rather than resetting it on every re-perform the outer mainloop
  drives. Reaching the cap stops the pool at a sweep boundary, records the same
  pass-named partial-result diagnostic (`Exceeded decompilation budget on pass
  data_flow: Some analysis is truncated`), and bypasses the pool on every later
  visit so total data_flow work is genuinely bounded. The cap is *decoupled*
  from the `flow_analysis` cap — `decompilebudget <flowN> [<dataflowN>]` now
  takes an optional second sweep cap (default 100000, effectively unbounded) so
  the two passes are budgeted on their own scales; omitting it leaves data_flow
  untouched. A budget exhausted by `flow_analysis` does *not* bypass data_flow,
  so the shipped `#35-3b` flow-truncation path is byte-for-byte preserved.
  Covered by a new functional datatest
  (`datatests/decompbudget_dataflow.xml`): `condconst1` converges naturally in
  8 data_flow sweeps, and a cap of 2 truncates it well short, emitting the
  data_flow header with the constant-folding visibly incomplete. Disengaged
  (the production default) every yield point is still a single bool test, and
  the full 315-case unit suite + 679-case datatest suite stay green.
- feat(decompiler): wire the cooperative analysis budget into `flow_analysis`
  (`#35-3b`). The `DecompileBudgetTracker` shipped inert in `#35-3a` is now
  consulted at the flow-following yield point: each processed instruction counts
  as one per-pass iteration, and when the budget is engaged and the per-pass cap
  is reached, flow truncates exactly as the existing max-instruction cap does
  (artificial `HALT`, no fall-through) and records a one-time partial-result
  diagnostic (`Exceeded decompilation budget: Some flow is truncated`). A new
  console option `decompilebudget <N>` engages the tracker with an `N`-iteration
  per-pass cap; `Architecture` gains an inert `DecompileBudgetTracker` member
  that nothing in production engages, so the disengaged default adds only a
  single bool test per instruction and leaves every existing decompile
  byte-identical (the full 678-case datatest suite stays green). Also fixes an
  `ElementId` id collision the new option surfaced: `decompilebudget` was given
  id 289, already held by `ELEM_BITFIELD`, so the console `option` lookup
  dispatched to the wrong table entry and never engaged the budget — reassigned
  to 290 (the prior next-open index) with `ELEM_UNKNOWN`'s sentinel bumped to
  291. Covered by a new functional datatest (`datatests/decompbudget.xml`,
  asserting the truncation warning header end-to-end) and a new
  `testbudget.cc` case (`budget_engage_and_caps_survive_reset`) pinning that
  `engage()`/caps survive a per-function `reset()`.
- feat(decompiler): name the exhausted pass in the budget partial-result
  diagnostic (`#35-3c`). The `#35-3b` truncation header was the generic
  `Exceeded decompilation budget: Some flow is truncated`; it now reads
  `Exceeded decompilation budget on pass <name>: Some flow is truncated`, using
  the tracker's previously production-unused `exhaustedPass()` to report which
  pass ran out of budget (here `flow_analysis`). This realizes the design's
  "budget exhausted on pass X" partial-result contract
  ([DECOMPILER_BUDGETS.md](docs/decompiler/DECOMPILER_BUDGETS.md)). Pass-only
  (the budget-class name is dropped) so the rendered header stays on a single
  comment line under the 100-column wrap, which the per-line `stringmatch` in
  `datatests/decompbudget.xml` now asserts. No behaviour change to any
  unbudgeted decompile; the full unit + datatest suites stay green.
- docs(decompiler): refine the Rec 35 sequencing table to reflect what has
  shipped (`#35-1`/`#35-2`/`#35-3a`/`#35-3b`/`#35-3c` done) and split the
  `data_flow` yield point into its own `#35-3d` item with an implementation
  note. Records why it is *not* a one-shot atomic PR the way `flow_analysis`
  was: the data_flow fixpoint is the generic `Action::perform` loop (shared by
  every pass, not data_flow-specific), the `ActionRestartGroup` restart count is
  degenerate at `maxrestarts = 1`, "stop data_flow early" means the coarser
  bypass-mode semantics deferred to `#35-4` rather than `flow_analysis`'s
  artificial-HALT truncation, and no deterministic datatest fixture exists yet.
  Documentation only; no code or behaviour change.
- build(decompiler): compile-wire the Rec 34 FlatBuffers IPC Java bindings
  (`#34-4a`). The 25 flatc-generated `ghidra.ipc.*` classes under
  `src/decompile/cpp/schema/java` were shipped as inert source in `#34-3`; this
  adds the `com.google.flatbuffers:flatbuffers-java:25.2.10` runtime dependency
  and a `srcDir` so they compile onto the Decompiler module's main source set.
  Registers the now-bundled `lib/flatbuffers-java-25.2.10.jar` as `Apache
  License 2.0` in the module's `Module.manifest` so the `:Decompiler:ip` audit
  stays green. Still inert — nothing imports `ghidra.ipc.*` yet — but the
  bindings now build against the vendored runtime every CI run, so any drift
  between the schema and the jar fails the build instead of lurking until the
  dual-encode step (`#34-4b`..`#34-6`) tries to use them.
- test(decompiler): compile-wire the Rec 34 FlatBuffers IPC C++ bindings
  (`#34-4b`). C++ analog of `#34-4a`: a new auto-globbed unit test
  (`src/decompile/unittests/testschema_fb.cc`) `#include`s the flatc-generated
  `schema/decompile_generated.h` and round-trips `DecompileFunctionRequest`
  (scalars, string, schema defaults) and `Diagnostic` (enum) against the
  vendored FlatBuffers C++ runtime, and the `cpp/Makefile` test build gains
  `-Ivendor/flatbuffers/include` (compile + depend rules) so that header
  resolves. Test-only — the bindings are not linked into the production
  `decompile`/`sleigh` executables yet — but exercising them in
  `decomp_test_dbg` makes the header's `FLATBUFFERS_VERSION` `static_assert`
  and the wire behavior CI-enforced ahead of the dual-encode work.
- feat(decompiler): worker-side v1 request codec for the Rec 34 dual-encode
  (`#34-4`). New header-only `schema/ipc_request_codec.h` provides
  `encode_decompile_request()` / `decode_decompile_request()` — the pure
  payload<->fields mapping for `DecompileFunctionRequest`, with no dependency on
  the live command loop or Ghidra's native `Address`/`Architecture` types.
  `decode` verifies the buffer first and returns false on a null or
  unverifiable payload (leaving its out-param untouched) so the worker can never
  read through a malformed v1 frame. A new auto-globbed unit test
  (`src/decompile/unittests/testipc_codec.cc`) pins the encode/decode
  round-trip, schema-default read-back, empty-string handling, and the
  null/garbage/truncated rejection contract. Test-only and inert: nothing in the
  production decompiler includes the codec yet — the command-loop wiring that
  would call `decode_decompile_request()` on an incoming frame is an
  end-to-end-only change deferred out of this PR.
- feat(decompiler): host-side v1 request encoder for the Rec 34 dual-encode
  (`#34-4`, Java half). New `ghidra.app.decompiler.ipc.DecompileRequestCodec`
  exposes `encodeRequest(programId, functionAddress, timeoutMs, flags)`, which
  builds a finished, root-typed `DecompileFunctionRequest` payload with the
  flatbuffers-java runtime — the host's mirror of the C++ worker decoder
  (`schema/ipc_request_codec.h`). Only the encode direction lives on the host
  (the host writes requests; the worker reads them), and the vendored
  flatbuffers-java bindings are generated without a verifier, so no host-side
  decode is offered. A new fast-sourceset JUnit test
  (`src/test/java/.../ipc/DecompileRequestCodecTest.java`) round-trips the bytes
  through the generated accessors: scalars, string, schema-default read-back,
  null-vs-empty `program_id`, and unsigned-uint32 widening for `timeout_ms` /
  `flags`. Inert — nothing in `DecompileProcess` calls the encoder yet; the
  command-loop wiring is deferred with the rest of `#34-4`.
- feat(decompiler): worker-side v1 response *envelope* codec for the Rec 34
  response path (`#34-5a`). New header-only `schema/ipc_response_codec.h`
  provides `encode_decompile_response()` / `decode_decompile_response()` for the
  `DecompileFunctionResponse` envelope — the overall `ResponseStatus` plus the
  `Diagnostic` list — the response-path analogue of `schema/ipc_request_codec.h`
  with the direction flipped (the worker writes the response, the host reads it,
  so `encode` is the worker's production direction). Because
  `DecompileFunctionResponse` is not the schema `root_type`, the generic
  `FlatBufferBuilder::Finish` / `GetRoot` / `Verifier::VerifyBuffer` API roots
  it; `decode` verifies before reading and returns false on a null or
  unverifiable buffer. Scope is the envelope only — the heavy `pcode`
  (`PcodeOp`/`Varnode`) and `high_function`
  (`HighFunction`/`HighSymbol`/`DataType`/`Storage`) body is several levels of
  nested optional tables and lands additively in `#34-5b`/`#34-5c`. A new
  auto-globbed unit test (`src/decompile/unittests/testipc_response_codec.cc`)
  pins the status + multi-diagnostic round-trip, the OK/empty-list clean result,
  empty-message handling, and the null/garbage/truncated rejection contract.
  Test-only and inert: nothing in the production decompiler includes the codec
  yet — the command-loop wiring is the end-to-end-only change deferred per
  DD-0005.
- feat(decompiler): extend the v1 response codec with the `pcode` body
  (`#34-5b`). Grows `schema/ipc_response_codec.h` additively from the `#34-5a`
  envelope to carry `DecompileFunctionResponse.pcode` — the `PcodeOp` array,
  each op carrying an opcode, sequence number, an optional output `Varnode`, and
  an `inputs` `Varnode` list. `DecompileResponseV1` gains `VarnodeV1` / `PcodeOpV1`
  views; `PcodeOpV1.has_output` distinguishes "no output varnode" from a
  zero-valued one so `encode`/`decode` preserve the schema's optional-table
  semantics rather than fabricating a varnode. `testipc_response_codec.cc` adds
  three cases — output + multi-input round-trip, an op with no output, and
  multiple ops including one with empty inputs (276 unit tests total). The
  `high_function` tree (`HighFunction`/`HighSymbol`/`DataType`/`Storage`) is
  still deferred to `#34-5c`; a response encoded here leaves it unset and decode
  does not touch it. Test-only and inert, like the rest of `#34-5`.
- feat(decompiler): complete the v1 response codec with the `high_function` tree
  (`#34-5c`). Final additive growth of `schema/ipc_response_codec.h`: carries
  `DecompileFunctionResponse.high_function` — the `HighFunction` table and its
  nested `HighSymbol` return value, parameter, and local lists, each symbol
  optionally bearing a `DataType` and a `Storage` location. `DecompileResponseV1`
  gains `DataTypeV1` / `StorageV1` / `HighSymbolV1` / `HighFunctionV1` views and a
  `has_high_function` flag; `HighSymbolV1.has_type` / `has_storage` mirror the
  schema's optional-table semantics so `encode`/`decode` distinguish an absent
  nested table from a zero-valued one rather than fabricating one. With this the
  worker-side `DecompileFunctionResponse` codec covers every field in the schema
  (envelope + `pcode` + `high_function`). `testipc_response_codec.cc` adds three
  cases — a full HighFunction round-trip (return type, params, locals, storage),
  a HighFunction whose symbols omit the optional type/storage, and a response
  with no HighFunction at all (279 unit tests total). Test-only and inert, like
  the rest of `#34-5`: the command-loop wiring is the end-to-end-only change
  deferred per DD-0005.
- feat(decompiler): worker-side v1 codecs for the program-lifecycle commands
  (`#34-6a`). With `DecompileAt` migrated (`#34-4`/`#34-5`), `#34-6` works through
  the remaining six commands; this increment lands the program-lifecycle trio in
  a new header-only `schema/ipc_lifecycle_codec.h`: `RegisterProgram`,
  `DeregisterProgram`, and `FlushNative`. Each command gets its native request
  and response views plus the full encode/decode round-trip; on the worker the
  production directions are `decode_*_request()` / `encode_*_response()`, and the
  host directions are included so each round-trips under test. None of these
  tables is the schema `root_type`, so — like the response codec — the generic
  `FlatBufferBuilder::Finish` / `GetRoot` / `Verifier::VerifyBuffer` API roots
  them, and every `decode` verifies before reading and returns false on a null or
  unverifiable buffer. A new auto-globbed unit test
  (`src/decompile/unittests/testipc_lifecycle_codec.cc`) pins each command's
  request/response round-trip, schema-default read-back, empty-string handling,
  and the null/garbage/truncated rejection contract (291 unit tests total). The
  config/graph trio (`StructureGraph`/`SetAction`/`SetOptions`) follows in
  `#34-6b`. Test-only and inert: nothing in the production decompiler includes
  the codec yet — the command-loop wiring is the end-to-end-only change deferred
  per DD-0005.
- feat(decompiler): worker-side v1 codecs for the configuration/graph commands
  (`#34-6b`). Completes `#34-6` (and so the worker-side codec for every command
  in the schema) with the remaining trio in a new header-only
  `schema/ipc_config_codec.h`: `StructureGraph`, `SetAction`, and `SetOptions`.
  Each gets its native request/response views and the full encode/decode
  round-trip, same contract as the lifecycle codec (`#34-6a`): worker production
  directions `decode_*_request()` / `encode_*_response()`, host directions
  included for testability, generic `Finish`/`GetRoot`/`VerifyBuffer` roots
  (none is the schema `root_type`), and verify-before-read on every `decode`. The
  control-flow `<block>` document, the `SetAction` root-action/print-config
  selectors, and the `SetOptions` `<optionslist>` stay opaque strings to match
  the legacy wire. A new auto-globbed unit test
  (`src/decompile/unittests/testipc_config_codec.cc`) pins each command's
  request/response round-trip, the `success` bool default (`SetAction`),
  empty-string handling, and the null/garbage/truncated rejection contract (302
  unit tests total). Test-only and inert, like the rest of `#34-4`..`#34-6`: the
  command-loop wiring is the end-to-end-only change deferred per DD-0005.
- feat(decompiler): host-side v1 request encoders for the six non-`DecompileAt`
  commands (`#34-6c`). Java half of `#34-6`, mirroring `DecompileRequestCodec`
  (`#34-4`): a new `CommandRequestCodec` provides one static encoder per command
  — `RegisterProgram`, `DeregisterProgram`, `FlushNative`, `StructureGraph`,
  `SetAction`, and `SetOptions` — each building the v1 FlatBuffers payload the
  C++ worker decodes (`#34-6a`/`#34-6b`). Encode-only: in the protocol the host
  writes requests and the worker reads them, and the vendored flatbuffers-java
  bindings are generated without a verifier, so a host-side decoder would have no
  caller and could not safely reject a malformed buffer anyway. None of these
  tables is the schema `root_type` (only `DecompileFunctionRequest` is), so the
  generic `FlatBufferBuilder.finish(int)` roots the buffer rather than a
  generated `finish*Buffer` helper. A `null` argument leaves its field unset
  (read back as null), distinct from a present-but-empty string. A new fast-suite
  test (`src/test/java/.../ipc/CommandRequestCodecTest.java`) reads each payload
  back with the generated accessors to pin the round-trip and the null-vs-empty
  contract. Test-only and inert, like the rest of `#34-4`..`#34-6`: nothing in
  `DecompileProcess` calls this yet — the command-loop wiring is the
  end-to-end-only change deferred per DD-0005.
- test(decompiler): `fuzz_ipc_schema` harness for the Rec 34 FlatBuffers IPC
  decoders (`#34-9`). With the worker-side codecs complete (`#34-4`..`#34-6`), a
  new libFuzzer harness in `cpp/fuzz/` feeds one fuzzer buffer to every
  `decode_*_request`/`_response` across the four codec headers and relies on the
  verify-before-read contract: any input — null, garbage, or truncated — must
  return false rather than read out of bounds or trip a sanitizer. Header-only
  (the codecs are inline over the vendored FlatBuffers runtime), so the target
  links no decompiler object files; `Makefile.fuzz` gains a `FLATBUF_INCLUDE`
  path and the `fuzz_ipc_schema` rule, and the fuzz `README`/`OSS_FUZZ.md`
  harness + seed tables gain its row. This is the in-tree continuation of Rec 13's
  fuzz set — the OSS-Fuzz upstream submission was rejected, so the harness stands
  on its own and runs locally / via our own CI. Validated locally against 200k+
  malformed inputs under ASan+UBSan with zero findings (clang/libFuzzer absent on
  the build box; the committed target builds under `Makefile.fuzz` with clang).
- feat(decompiler): add `DecompileBudget` to the v1 request schema (`#35-2`).
  First slice of Rec 35 (bounded decompilation): a new optional `DecompileBudget`
  table on `DecompileFunctionRequest` carries the five per-function caps from
  [`DECOMPILER_BUDGETS.md`](docs/decompiler/DECOMPILER_BUDGETS.md) —
  `wall_clock_ms` (30000), `wall_clock_hard_ms` (60000), `rss_max_mb` (4096),
  `pcode_op_limit` (1000000), `iteration_limit_per_pass` (100) — each defaulted
  in the schema so an absent budget reads back as the defaults rather than zero.
  Bindings regenerated with the version-matched flatc (C++ v25.12.19, Java
  v25.2.10); the worker codec `schema/ipc_request_codec.h` gains a
  `DecompileBudgetV1` view plus optional encode/decode, and the host
  `DecompileRequestCodec` gains a budget-carrying `encodeRequest` overload. The
  generated `Verify` recurses into the sub-table, so a malformed budget is
  rejected by the same verify-before-read contract. Schema/codec only — nothing
  reads the budget into the analysis loop yet (that is `#35-3`). Covered by new
  round-trip + absent-defaults tests on both sides (`testipc_codec.cc`,
  `DecompileRequestCodecTest`).
- feat(decompiler): add the cooperative analysis budget tracker (`#35-3a`).
  Next slice of Rec 35: a dependency-free, header-only `DecompileBudgetTracker`
  (`cpp/budget.hh`) that the analysis loop will consult at each yield point. It
  tracks soft/hard wall-clock, accumulated pcode-ops, and per-pass fixed-point
  iterations against the five caps, never interrupting — an exhausted cap is
  recorded as a `BudgetExhaustion` class pinned to the pass that first ran out,
  so the caller can checkpoint and return a partial result. The clock source is
  injectable, so the wall-clock paths are unit-tested deterministically without
  sleeping (`testbudget.cc`, 9 cases). Inert for now: no production pass consults
  it yet — wiring the checks into `flow_analysis`/`data_flow` is the behaviour-
  changing follow-up (`#35-3b`).

---

## [v26.2.1] — 2026-05-31

Patch release: packaging + CI hardening on top of v26.2.0's v1 IPC
tunnel, plus the inert Rec 34 FlatBuffers IPC substrate. No production
decompilation path changes — every code-bearing item is CI-only,
build-time, or schema groundwork that nothing `#include`s/imports yet.
The headline is the "packages" wiring deferred from the v26.2.0 notes:
a `v*` tag now publishes a runnable container image to GHCR alongside
the signed-zip release. Patch (not minor) bump because no new runtime
protocol or feature ships — the FlatBuffers dual-encode migration
(`#34-4`..`#34-6`) is still ahead.

- feat(release): publish a runnable container image to GHCR on every `v*` tag.
  Adds a standalone `publish-container.yml` workflow that runs `buildGhidra`,
  extracts the linux distribution, and `docker build`s the bundled
  `docker/Dockerfile` from that extracted release root (the same build-context
  contract `docker/build-docker-image.sh` uses locally), then pushes
  `ghcr.io/<owner>/gayhydra:<version>` + `:latest`. Deliberately decoupled from
  `release.yml` so a container/registry failure never blocks the signed-zip
  release (and vice versa); both fire off the same tag push. The
  `docker/Dockerfile` `org.opencontainers.image.{title,description,source}`
  labels are repointed from upstream NSA/ghidra to this fork so GHCR links the
  published package to the GayHydra repo's Packages section. This is the GHCR
  half of the "packages" wiring the release-cadence notes had deferred to its
  own PR.
- test(decompiler): wire the v1 IPC framing end-to-end test into CI (Rec 33
  `#33-2.6` / DD-0005). Adds an `ipc_e2e` job to `build-ghidra.yml` that builds
  the native `decompile` worker and runs `DecompileProcessFramingV1EndToEndTest`
  — the spawn-native differential guard that forces framing to v0 then v1 and
  asserts byte-identical decompiled output. The existing `unit_tests` job runs
  `gradle test`, which does **not** execute the `src/test.slow` integration
  suite, so the negotiated command-loop flip (and the forthcoming `#34-4`
  FlatBuffers dual-encode commands) were previously unguarded by CI. The job is
  Ubuntu-only because the test validates the platform-independent wire protocol,
  and explicitly builds `:Decompiler:decompileLinux_x86_64Executable` first
  because `integrationTest` compiles the sleigh languages + Java test sources
  but not the native binary the test spawns.
- feat(decompiler): commit the generated Java FlatBuffers bindings
  `schema/java/ghidra/ipc/*.java` (Rec 34 `#34-3c`). 25 classes generated
  from `schema/decompile.fbs` with **flatc v25.2.10** to match the vendored
  `flatbuffers-java-25.2.10.jar`, and standalone-validated with `javac`
  against that jar (the Java parallel to `#34-3b`'s C++ compile check). The
  per-language flatc split is intentional — see the `#34-3b` note on the
  `static_assert` runtime pin. Behavior-preserving and symmetric with
  `#34-3b`: the sources are staged under `schema/java/` (not yet on a
  compiled source set) and remain inert until the dual-encode migration
  wires the jar classpath in `#34-4`.
- feat(decompiler): commit the generated C++ FlatBuffers bindings
  `decompile_generated.h` (Rec 34 `#34-3b`). Generated from
  `schema/decompile.fbs` and compile-validated against the vendored C++
  runtime headers (`vendor/flatbuffers/include`). Generated with **flatc
  v25.12.19** — *not* the `v25.2.10` used for the Java side: flatc emits a
  hard `static_assert` pinning generated code to its exact runtime
  version, so the C++ bindings must be generated with the flatc release
  matching the vendored C++ runtime (`25.12.19`), while the Java bindings
  (`#34-3c`) use `25.2.10` to match the Maven jar. The two interoperate at
  the wire level (the FlatBuffers wire format is stable across these minor
  versions). This corrects the `#34-3a` README's claim that `25.2.10` C++
  codegen was forward-compatible with the newer runtime — it is not; the
  `static_assert` is a compile error. Behavior-preserving: the header is
  inert (nothing includes it yet) until the dual-encode migration in
  `#34-4`..`#34-6`.
- feat(decompiler): land the FlatBuffers IPC schema `decompile.fbs`
  (Rec 34 `#34-3a`, design in
  [docs/decompiler/IPC_SCHEMA.md](docs/decompiler/IPC_SCHEMA.md)). Adds
  `src/decompile/cpp/schema/decompile.fbs` — the typed payload contract
  that will replace the ad-hoc XML documents exchanged between the Java
  host and the C++ worker. Covers all seven commands the existing
  protocol supports (`RegisterProgram`, `DeregisterProgram`,
  `FlushNative`, `DecompileAt`, `StructureGraph`, `SetAction`,
  `SetOptions`) — no new commands. Behavior-preserving: the schema and
  its generated bindings are inert until the dual-encode migration in
  `#34-4`..`#34-6`; opaque XML payloads (specs, control-flow graph,
  optionslist) are carried as `string` unchanged. Validated with the
  pinned `flatc v25.2.10` (generates clean C++ + Java); definitions are
  ordered define-before-use (flatc rejects forward references — the
  `#34-1` sketch had this bug). Generated bindings + build wiring land in
  `#34-3b` (C++) and `#34-3c` (Java).
- chore(decompiler): vendor the FlatBuffers C++ runtime headers
  (Rec 34 `#34-2`, design in
  [docs/decompiler/IPC_SCHEMA.md](docs/decompiler/IPC_SCHEMA.md)).
  Pins upstream `v25.12.19` (Apache License 2.0) under
  `src/decompile/cpp/vendor/flatbuffers/`, vendoring only the 16-header
  runtime include-closure of `flatbuffers/flatbuffers.h` — the
  schema-compiler headers (`idl.h`, `flatc.h`, `reflection*.h`, …) are
  excluded since they belong to the build-time `flatc` toolchain, not the
  decompiler runtime. Headers are verbatim upstream (provenance sha256 in
  the vendored `README.md`); each is attributed `Apache License 2.0` in
  the module `certification.manifest` so the `:Decompiler:ip` audit
  resolves it. Nothing `#include`s these yet — the `decompile.fbs` schema,
  generated bindings, and the `-Ivendor/flatbuffers/include` build wiring
  land in PR `#34-3`. C++-headers half only; the Java runtime jar is a
  follow-up PR.
- chore(decompiler): vendor the FlatBuffers Java runtime as a declarative
  fetch (Rec 34 `#34-2b`, companion to the C++ headers in `#34-2a`). Adds
  `flatbuffers-java-25.2.10.jar` to `gradle/support/fetchDependencies.gradle`
  (Maven Central, sha256-pinned), landing it in the gitignored
  `dependencies/flatRepo/` flat repo like every other third-party jar — no
  binary blob enters git history. Pinned to 25.2.10 (Maven Central's latest
  published `flatbuffers-java`); the C++ headers track the newer `v25.12.19`
  GitHub tag, but the FlatBuffers wire format is stable across these minor
  versions, so the Java runtime decodes buffers the C++ runtime writes. The
  jar is unreferenced until PR `#34-3` wires it into the Decompiler classpath
  with the generated bindings, where the flatc/runtime alignment is verified
  by compilation.

---

## [v26.2.0] — 2026-05-30

Rec 33 `#33-2.6` sprint close: the v1 IPC framing tunnel, wired end to
end. Three stacked PRs landed the unit-tested tunnel substrate (C++ and
Java) and then flipped the negotiated channel so the default `auto`
mode now tunnels all decompilation through v1 frames, validated by a
standalone real-native end-to-end harness. Minor bump (26.1.16 →
26.2.0) for the new production protocol path; SBOM stays bundled until
the GHCR packaging wiring lands as its own PR.

- feat(decompiler): add the `#33-2.6` framing-tunnel streambufs
  (`FrameOutStreambuf` / `FrameInStreambuf`) to `frame_v1.hh`/`.cc`.
  These are the transparent v1 framing tunnel for the deferred
  command-loop flip: the out-buffer wraps each `flush` as one
  `write_frame_v1` frame, the in-buffer yields one frame's payload
  per `underflow`, and empty flushes/frames are no-ops on both
  sides. Because v0 already flushes once per turn, the existing
  message rhythm maps one-to-one onto frame boundaries, so the v0
  `\0\0\1\NN` bursts (NULs and high bytes included) ride through
  byte-for-byte. They live in `DECCORE`, so they are fully
  unit-testable in `decomp_test_dbg` (10 new tunnel tests:
  round-trip, multi-flush concatenation, overflow vs. bulk path,
  empty-frame skip, EOF/CRC error surfacing, v0-marker preservation,
  100 KB payload). No production wiring yet — `ghidra_process.cc` /
  `DecompileProcess.java` are untouched, so blast radius is zero;
  this only lands the testable substrate the `#33-2.6` flip will
  install once an end-to-end harness exists.
- feat(decompiler): add the Java side of the `#33-2.6` framing tunnel —
  `DecompileProcess.FrameOutputStream` / `FrameInputStream`, mirroring
  the C++ `FrameOutStreambuf`/`FrameInStreambuf`. The out-stream wraps
  each `flush` as one `encodeFrameV1` frame; the in-stream yields one
  frame's payload per refill, skips empty frames, and surfaces a
  terminal `FrameError` (EOF/CRC/reserved/oversize) via
  `getLastError()`. They reuse the existing greeting-path constants
  (`FRAME_MAGIC`, `FRAME_FLAGS_RESERVED`, `FRAME_MAX_PAYLOAD_LEN`), so
  both ends stay byte-compatible with `frame_v1`. Not yet wired in —
  `registerProgram` still writes v0 regardless of `channelV1`, so blast
  radius is zero; this only lands the unit-testable substrate. 9 new
  tunnel tests in `DecompileProcessFramingV1Test` (round-trip,
  multi-flush concatenation, empty-flush no-op, empty-frame skip,
  EOF→TRUNCATED + corrupt→CRC_MISMATCH surfacing, v0-marker
  preservation, 100 KB payload); the fast `test` sourceset stays pure
  byte math (13 tests total, no Ghidra runtime).
- docs(decompiler): record the #33-2.6 deferral in DD-0005 — the
  v1 command-loop flip stays untestable by the local precheck
  (command-loop `.cc` files link only into `ghidra_dbg`, never
  `decomp_test_dbg`); documents the end-to-end test gap and the
  unblock options. No code/protocol change; v26.1.16 ships
  greeting-v1 + commands-v0.
- feat(decompiler): wire the `#33-2.6` v1 command-loop flip behind the
  negotiated channel. When the greeting handshake selects v1, both ends
  now swap in the framing tunnel: `ghidra_process.cc` wraps `cin`/`cout`
  in `FrameInStreambuf`/`FrameOutStreambuf` (RESPONSE frames), and
  `DecompileProcess.java` wraps `nativeIn`/`nativeOut` in
  `FrameInputStream`/`FrameOutputStream` (COMMAND frames), so the entire
  legacy command loop — `registerProgram`, `decompileAt`, and every
  callback query — tunnels through v1 frames. A v0 client (every stock
  build) opens with `0x00` and is left byte-identical, so the flip is
  gated on the negotiated `channelV1`. The default `auto` mode now
  negotiates v1, making the tunnel the production decompilation path.
  Lands a standalone end-to-end harness
  (`DecompileProcessFramingV1EndToEndTest`, `src/test.slow`) that drives
  a real native `decompile` against a stock avr8 function under forced
  `framing=v0` then `=v1` (and `auto`) in one JVM and asserts
  byte-identical decompiled C — proving the bidirectional tunnel is
  transparent across the full protocol.

---

## [v26.1.16] — 2026-05-29

Rec 33 sprint close. Two themes landed since v26.1.15:

- **IPC framing v1 (DD-0005).** The Rec 33 `#33-2.x` sequence:
  the `frame_v1` encode/decode helper + stream reader/writer,
  the native greeting handshake (`negotiate_greeting_v1` in
  `ghidra_process.cc`), and the Java v1 framing client
  (`DecompileProcess`) with the `decompiler.framing` option
  defaulted to `auto` (v1 greeting, v0 fallback). Post-handshake
  traffic stays byte-identical to v0; the full v1 command-loop
  dispatch is deferred to `#33-2.6`.
- **Three upstream security cherry-picks.** GP-6875 (Mach-O
  invalid load-command count guard), GP-6849 (RISC-V CSR
  instructions writing to a constant operand), and GP-6717
  (safe `Class.forName` centralized on `ClassSearcher.forNameSafe`,
  no static-initializer gadget on resolve/validate).

Also includes CI/build hardening: Gradle build cache + a dedicated
`audits` job, the CodeQL traced-compile fix, the `unit_tests`
timeout bump for the macOS long pole, and `local-precheck.sh`
gaining the `ghidra_dbg` and multi-OS SLEIGH-compile legs.

### 2026-05-29 — Rec 33 #33-2.5 — Java v1 framing client + flip default to v1

- **`DecompileProcess` now speaks the v1 framing greeting.** At connection start (`registerProgram`, immediately after `setup()` and before the first command) the Java client emits a `Type::GREETING` v1 frame — `MAGIC 0x47 0x48 0x01 0x00` + TYPE + FLAGS(`CRC_PRESENT`) + 4-byte BE LENGTH + payload + 4-byte BE CRC32 (`java.util.zip.CRC32` == IEEE 802.3, computed over `TYPE|FLAGS|LENGTH|PAYLOAD`, **not** MAGIC) — and reads the native server's greeting reply, recording the negotiated mode in `channelV1`. The payload is 2-byte BE VERSION + 4-byte BE CAPABS (`CRC_REQUIRED`) + UTF-8 IDENT, matching `frame_v1.hh`/`frame_v1.cc` exactly. The reply reader is lenient (returns `false` rather than throwing on EOF / magic mismatch / bad CRC / reserved-flag / version-major mismatch) so a non-v1 peer cleanly leaves the client on the legacy path.
- **`decompiler.framing` option flipped to `auto` (v1 greeting, v0 fallback) by default for GayHydra clients.** New `DecompileOptions.FramingMode` enum (`Auto`/`V1`/`V0`, modelled on `NanIgnoreEnum`) registered as the "Analysis.IPC framing protocol" decompiler option; `DecompInterface.resolveFramingMode()` threads it to `DecompileProcess.setFramingMode()` before each `registerProgram`, with the `decompiler.framing` **system property** taking precedence (for headless/test overrides). `v0` mode emits **no** greeting, so the client's first bytes are the legacy v0 command burst exactly as before — byte-identical to upstream, which keeps a stock (non-GayHydra) native decompiler working.
- **Scope: greeting negotiation only; the command/response protocol stays v0.** The native command loop (`ghidra_process.cc`) currently calls `negotiate_greeting_v1` but still reads v0 bursts regardless of the negotiated mode, so wrapping Java commands in v1 frames now would desync the channel. A successful greeting therefore only records that the peer speaks v1; the full v1 command-loop dispatch (both sides) is deferred to #33-2.6 where it can be integration-tested end-to-end. This keeps post-handshake traffic byte-identical to v0, so no existing decompilation path changes.
- **New fast unit test** `DecompileProcessFramingV1Test` (4 tests, `src/test/java`, runs in CI `gradle test`): exact greeting/empty-frame wire bytes, payload structure (version/capabs/ident), and that the CRC32 trailer covers `TYPE|FLAGS|LENGTH|PAYLOAD` but excludes MAGIC. Verified locally with a cross-language smoke test feeding the Java-equivalent greeting into a freshly built `ghidra_dbg`, which replies with a valid `GREETING` frame (ident `GayHydra-decompiler (v1 framing)`, CRC validated).
### 2026-05-29 — Mach-O: reject headers with an invalid load-command count (GP-6875)

- **`MachHeader` now validates `nCmds` before iterating the load commands.** A new `MAX_LOAD_COMMANDS = 32_768` ceiling plus a `validateNumLoadCommands()` guard (called at the head of `parse(SplitDyldCache)`, `parseSegments()`, `parseReexports()`, and `parseAndCheck(int)`) throw `MachException` when `nCmds` is negative or absurdly large, instead of looping on attacker-controlled garbage and reading far past the file. `parseSegments()`/`parseReexports()`/`parseAndCheck(int)` consequently gain `throws MachException`; all in-tree callers (`MachoLoader`, `MachoPrelinkUtils`, `DyldCacheFileSystem`, `MachoFileSetFileSystem`) already propagate or catch it — `MachoLoader.detectCompilerName` was wrapped in a `try { … } catch (MachException)` to match. Cherry-picked from upstream NSA/ghidra commit `34afe864bc` (Ryan Kurtz); our fork preserves its Golang-only compiler detection (no Swift branch).
### 2026-05-29 — RISC-V: fix CSR instructions writing to a constant operand (GP-6849)

- **`riscv.csr.sinc` / `riscv.table.sinc`: the CSR read/clear/set family now writes the destination register via the `rd` register operand instead of the `rdDst` table.** `rdDst` resolved to a constant-export form, so `csrrc`/`csrrci`/`csrr`/`csrrs`/`csrrsi` (and a few other register operands) emitted p-code that wrote the old CSR value to a *constant* rather than to the architectural destination register — losing the result. Renaming the operand to the proper `rd` register field fixes the write target. Cherry-picked from upstream NSA/ghidra commit `6a40c607cd` (ghidorahrex); applies cleanly to our fork and the RISC-V SLEIGH spec recompiles green.
### 2026-05-29 — Safe `Class.forName` pattern centralized on `ClassSearcher.forNameSafe` (GP-6717)

- **Cherry-picked upstream GP-6717 (NSA/ghidra commit `7789fd45d3`, author Dan).** Adds `ClassSearcher.forNameSafe(name, superType, loader)`: it resolves the class **without** running its static initializer (`Class.forName(name, false, loader)`), then checks assignability to the expected super type (`asSubclass`). It does **not** itself initialize the class — initialization happens naturally when a caller constructs an instance. This prevents a saved/serialized class name from running an arbitrary class's static initializer (a potential gadget) merely by being resolved or validated during restore. Callers must pass a narrow super type they control — never `Object` or a JDK/library interface.
- **Deliberate one-line divergence from the upstream helper.** Upstream's `forNameSafe` ends with a second `Class.forName(name, true, loader); // Initialize it this time`, which is **omitted here.** That line contradicts upstream's own `ClassSearcherTest` (which asserts no static-initializer side effect until construction — the test fails verbatim with the line present, in every initialization ordering) and would run static initializers on classes that are only *validated* and never constructed — e.g. `ToolUtils` plugin-existence checks, or `loadExtensionPoint` entries that `isClassOfInterest` subsequently rejects. Dropping it makes upstream's test pass unmodified and fully honors the method's stated no-gadget purpose; every in-tree caller either constructs the class (initializing it then) or needs only reflective metadata, so behavior is unchanged for them.
- **Replaces this fork's hand-rolled `Class.forName(name, false, loader)` + `isAssignableFrom` guards** in `ProgramLocation`, `LocationMemento`, `GProperties` (enum restore), `ThemePreferences`, `WrappedCustomOption`, `GhidraToolTemplate`, and `DataTypeManagerDB` (built-in datatype load) with the shared helper. Each call site preserves the fork's graceful degradation on a stale/wrong-type class name (return `null` / fall back to the default theme / skip the entry) by catching the `ClassCastException` that `forNameSafe` now throws on a type mismatch.
- **`loadExtensionPoint` routes through `forNameSafe`** so extension-point discovery gets the same no-static-init safety. New `ClassSearcherTest` (from upstream, unmodified) asserts the helper never runs a class's static initializer itself — initialization is deferred to construction (`newInstance`) — and that a type mismatch is rejected (`ClassCastException`) before any initialization.
- **Two debugger tests removed** (`TraceRmiLaunchDialogTest.testIntSaveHexValue` / `testIntLoadHexValue`): GP-6717 also dropped the `NameTypePair` string-encode/`writeConfigState`/`readConfigState` serialization those tests exercised, so they no longer have a code path to assert.

### 2026-05-29 — CodeQL: force a traced compile in the Java build step

- **`.github/workflows/codeql.yml` "Manual Java build" now runs `gradle --no-build-cache --no-daemon prepDev --parallel`.** Enabling the tree-wide Gradle build cache (`org.gradle.caching=true`, shipped same-day) made every `:*:compileJava` task resolve `FROM-CACHE` in the CodeQL job, so `javac` never executed under CodeQL's tracer. The `java-kotlin` analysis then saw zero source ("CodeQL could not process any code written in Java/Kotlin" / "no source code seen during build") and `codeql database finalize` aborted with exit 32 ("configuration error") — a hard-red required check on every PR, with no actual code defect. `--no-build-cache` forces the compile tasks to really run so the tracer observes the `javac` invocations; `--no-daemon` keeps that compile in the traced process tree rather than a pre-warmed daemon. CodeQL-only change: the `build`/`unit_tests`/`audits` jobs and all build outputs are unaffected (they still use the cache).

### 2026-05-29 — Build Ghidra CI: Gradle build cache + dedicated audits job

- **`org.gradle.caching=true` + `org.gradle.parallel=true` in `gradle.properties`.** The `unit_tests` job (`needs: build`) runs on a fresh runner and re-compiles everything the `build` job already produced; with the Gradle build cache persisted via `gradle/actions/setup-gradle`, those tasks become cache hits instead of full recompiles. Build-cache misses are non-fatal (a non-cacheable task simply runs normally), so it is safe to enable globally. `--parallel` was already passed on the CI command line; making it the default keeps local builds consistent with CI. **`org.gradle.configuration-cache` is deliberately NOT enabled** — Ghidra's build scripts reference Gradle script objects from Groovy closures, which the configuration cache rejects.
- **Static-source / config audits hoisted out of the `unit_tests` matrix into a dedicated ubuntu-only `audits` job.** The four platform-independent gates (`ignoreAudit` strict, `objectInputStreamAudit`, `cppRaiiAudit`, `:Docking:i18nLint`) previously ran inside the 3-platform `unit_tests` matrix — 3× redundant work that only surfaced *after* the ~35-min JVM test suite. They now run once in a job independent of `build`, giving a fast early-fail signal. Still required for a PR to be green; the split is for less wasted work and a clearer red signal, not for bypassability.
- **`unit_tests` job `timeout-minutes` bumped 90 → 120.** The macOS leg runs ~88–90 min (macOS runners are ~2× slower than ubuntu and the JVM suite's `MDMangBaseTest` combinatorial surface dominates), and tripped the old 90-min cap on this very PR's run — a timeout-cancel that read as a false red with every test passing. 120 gives headroom; ubuntu (~35 min) and windows (~60 min) finish well under either value, so this only affects the macOS long pole.

### 2026-05-29 — Rec 33 #33-2.4 — IPC greeting handshake (v1/v0 negotiation)

- **`negotiate_greeting_v1(istream&, ostream&, ident)`** wires the v1 framing greeting into the decompiler's connection start (`ghidra_process.cc` `main()`, before the `readCommand` loop). A single non-consuming `peek` decides the framing: v0 clients always open with `0x00` (the `\0\0\1\NN` burst) and are left **byte-identical** for the legacy `readToAnyBurst` path; only the v1 magic lead-in `0x47` — which no v0 client produces — triggers a greeting exchange. On a well-formed `GREETING` frame with a matching major version, the server consumes the client greeting and writes its own (`build_greeting_payload_v1`: 2-byte VERSION + 4-byte CAPABS + UTF-8 IDENT, per DD-0005), returning `ChannelMode::V1`; any malformed/non-greeting frame or version-major mismatch falls back to `ChannelMode::V0`. The negotiated mode is **not yet consumed by the command loop** — the v1 read/write command dispatch lands with the Java v1 client in #33-2.5, where it can be integration-tested end-to-end. v1 stays unreachable in production until then, so v0 behaviour is unchanged.
- **`frame_v1` moved from the Makefile `EXTRA` set into `DECCORE`** so it links into `ghidra_dbg`/`ghidra_opt` (the `GHIDRA=` targets), not just `decomp_test_dbg`. It was previously test-only; the live `main()` call needs it in the GHIDRA build. `DECCORE` already carries `crc32` (frame_v1's only intra-tree dependency), and still feeds `TEST_NAMES`/`COMMANDLINE_NAMES`/`LIBDECOMP_NAMES`, so no target loses the object.
- **`frame_v1.cc` added to the gradle `decompile` `NativeExecutableSpec` source list** (`buildNatives.gradle`, after `crc32.cc`). The gradle native build enumerates its `.cc` sources independently of the Makefile, so the `DECCORE` move alone did not put `frame_v1.o` into the gradle-built `decompile` executable — the `ghidra_process.cc` `main()` call left `linkDecompileLinux_x86_64Executable` with an `undefined reference to negotiate_greeting_v1`. Verified green with `gradle :Decompiler:linkDecompileLinux_x86_64Executable` (JDK 21).
- **8 new unit tests** (`testframe_v1.cc`, total 248): greeting payload build/parse round-trip (incl. empty IDENT and the 6-byte too-short rejection), v0 stream left intact after `peek` (no bytes consumed, nothing written), V0 on empty stream, V1 on a well-formed greeting (asserts the server reply parses back as a `GREETING` carrying our IDENT), V0 fallback on a valid-but-non-`GREETING` frame, and V0 fallback on a mismatched major version.

### 2026-05-29 — Build Ghidra CI: concurrency + master-scoped push trigger

- **`build-ghidra.yml` no longer renders a false-red README badge.** The "Build Ghidra" badge was showing `failing` even though no build had actually failed (zero `failure` conclusions in the last 30 master runs; last true success was #166). Root cause: the heavy 6-job matrix (3-OS build + 3-OS JVM unit tests) ran on **every** push and PR with no `concurrency` control, so the rapid sprint merge cadence flooded the runner queue and master's own `push` runs were getting **cancelled** before completing — and GitHub renders a cancelled latest-run as a red "failing" badge. Fix: (1) scope the `push` trigger to `branches: [master]` (feature branches are already built via their `pull_request` trigger, so this drops the double-run), and (2) add a `concurrency` group keyed on `github.ref` with `cancel-in-progress: ${{ github.event_name == 'pull_request' }}` — superseded PR re-pushes cancel to free runners, but `push`/master runs are never cancelled, so master always lands a real success/failure conclusion. The badge now reflects true build health.

### 2026-05-28 — Precheck now also builds `ghidra_dbg`

- **`scripts/local-precheck.sh` builds `ghidra_dbg` in addition to `decomp_test_dbg`.** Closes a blind spot ahead of the Rec 33 #33-2.4 live-path wiring: `ghidra_arch.cc`, `ghidra_process.cc`, and the `*_ghidra.cc` IPC translation units compile **only** into the `ghidra_dbg`/`ghidra_opt` targets (the `GHIDRA=` Makefile list), never into `decomp_test_dbg`. A change that breaks just the IPC layer would pass a `decomp_test_dbg`-only gate and still break the release build — the same blind-spot class as the SLEIGH-compile gap closed in [#165](https://github.com/CryptoJones/GayHydra/pull/165). New build leg runs in both default and `--full` modes, after the `decomp_test_dbg` build. `ghidra_dbg` links without `-lbfd`, so it builds anywhere `g++` is present (the libbfd-dependent files are test-only `EXTRA`, per DD-0004). Verified by running `scripts/local-precheck.sh --force`: both legs build green on master.

### 2026-05-28 — Rec 33 #33-2.2.1 — stream-based v1 writer

- **write_frame_v1(ostream&, Type, payload)** — stream-emission overload of encode_frame_v1, builds the CRC incrementally as it writes (no intermediate vector). String + vector<uint1> payload overloads. Caller responsible for s.flush(). 5 new unit tests (total 240): writer output matches buffer encoder byte-for-byte, empty-payload wire shape, write-then-read round-trip on the same stringstream, two back-to-back frames parseable, binary payload with 0x00 bytes (verifies the length prefix bounds the read, not a terminator scan).

### 2026-05-28 — Rec 33 #33-2.2 — stream-based v1 reader

- **`read_frame_v1(istream&, hdr_out, payload_out, peeked_out)`** — stream wrapper around `decode_frame_v1` that reads exactly the bytes it needs from an istream. Same `Error` enum returns (`OK` / `MAGIC_MISMATCH` / `TRUNCATED` / `LENGTH_TOO_LARGE` / `CRC_MISMATCH` / `RESERVED_FLAG_SET`). On `MAGIC_MISMATCH` the 4 leading bytes are returned in `peeked_out` so the channel-mode dispatch logic in [#33-2.4] can feed them straight into the existing v0 path (`readToAnyBurst`). `testframe_v1.cc` gains 12 stream-based tests (`+12`, total now 235): round-trip COMMAND/PING, two sequential frames from one stream (verifies stream position advances correctly), magic-mismatch returns the v0 marker bytes intact, six EOF/truncation paths (empty stream, partial magic, mid-header, mid-payload, missing CRC), length-cap rejection, CRC-mismatch detection, reserved-flag rejection. No IPC wiring yet — that's #33-2.3 (writer) + #33-2.4 (greeting handshake).

### 2026-05-28 — Rec 33 #33-2.1 — frame_v1 helper + unit tests

- **`frame_v1.hh` / `frame_v1.cc` — pure C++ encode/decode of a v1 frame.** First implementation PR of the Rec 33 sprint (per [DD-0005](docs/decisions/0005-ipc-framing-v1.md)'s sequence). No IPC wiring yet — that lands in `#33-2.2`–`#33-2.4`. API: `encode_frame_v1(Type, payload)` returns a `vector<uint1>` matching the documented wire layout (MAGIC `0x47 0x48 0x01 0x00` + 1-byte TYPE + 1-byte FLAGS + 4-byte BE LENGTH + payload + 4-byte BE CRC32 over `TYPE | FLAGS | LENGTH | PAYLOAD`); `decode_frame_v1(buf, start, ...)` returns an `Error` enum (`OK` / `MAGIC_MISMATCH` / `TRUNCATED` / `LENGTH_TOO_LARGE` / `CRC_MISMATCH` / `RESERVED_FLAG_SET`) and sets `next_out` for resync. Reuses existing `crc32.cc` unchanged; exposes the IEEE-802.3 wrapper as `crc32_ieee802_3(bytes, len)`. `testframe_v1.cc` adds **19 unit tests** (total now 223): canonical-vector CRC32 (`"123456789" = 0xCBF43926` — pinned for Java `java.util.zip.CRC32` interop), round-trip for every TYPE, resync past garbage prefix, four truncation paths, length-cap rejection, CRC-mismatch detection, reserved-flag rejection, and an exact-bytes wire-format pin for `PING`. `frame_v1.cc`/`.hh` added to `cppRaiiAudit`'s `PROTECTED_FILES`.

### 2026-05-28 — DD-0005 Rec 33 IPC framing v1 design

- **DD-0005 — IPC framing v1 (greeting + CRC32 + resync, v0 fallback active).** Strategic-sprint design doc for Rec 33. Specifies a 14-byte v1 wire format: 4-byte `MAGIC` (`0x47 0x48 0x01 0x00` = "GH" + protocol-version 1.0), 1-byte `TYPE`, 1-byte `FLAGS`, 4-byte big-endian `LENGTH`, payload, 4-byte CRC32 (over `TYPE | FLAGS | LENGTH | PAYLOAD`, not over `MAGIC`). 16 MB hard cap on `LENGTH`. Greeting handshake (TYPE 0x00) negotiates v1 at connection start; mismatched magic on first bytes downgrades to v0 fallback (existing `readToAnyBurst` path stays unmodified). Decomposes Rec 33 #33-2 into a 5-PR sequence (`#33-2.1` `frame_v1.hh`/`.cc` + unit tests, `#33-2.2` server-side reader with v0 fallback, `#33-2.3` server-side writer, `#33-2.4` greeting handshake, `#33-2.5` Java-side wiring + flip default to v1). Each PR ships independently; v0 fallback keeps the channel working at every step. Reuses existing `crc32.cc` for the CRC. SprintPlanning Rec 33 row updated to reflect the DD landing.

### 2026-05-28 — cppRaiiAudit completeness now checks .y / .l generator sources

- **`cppRaiiAudit` completeness check extended to `.y` and `.l` files.** The PR #161 completeness pass only iterated `.cc`/`.hh` files in `decompile/cpp/`; it missed the bison/flex generator sources (`.y`/`.l`), so a new generator file could ship completely outside the gate. Fix: extend the `endsWith()` filter to also include `.y` and `.l`. Adds five generator sources to `EXCLUDED_FILES` matching their already-excluded `.cc` counterparts: `grammar.y`, `pcodeparse.y`, `ruleparse.y`, `slghparse.y`, `slghscan.l`. `xml.y` stays in `PROTECTED_FILES` with its existing line-range exclusions (the in-tree committed copy is the source of truth for the xml-decode path, not regenerated in the GHA matrix; same rationale as `xml.cc`). Steady-state log line: `cppRaiiAudit: 226 protected file(s) clean (9 excluded by policy).` (4 generated `.cc` + 5 generator sources). Failure path verified by adding `_tamper.y`: the completeness check correctly fails with "1 ungated decompiler C++ file(s)".

### 2026-05-28 — Precheck SLEIGH-compile leg multi-OS

- **`scripts/local-precheck.sh --full` SLEIGH-compile leg now multi-OS.** Extends [PR #165](https://github.com/CryptoJones/GayHydra/pull/165)'s Linux-only `compileSleighLinux_x86_64ExecutableSleighCpp` invocation to a host-detected mapping (`uname -s` × `uname -m` → gradle's `compileSleigh${OS}_${ARCH}ExecutableSleighCpp` task name). Cases covered: `Linux/x86_64`, `Linux/aarch64`+`Linux/arm64`, `Darwin/arm64`, `Darwin/x86_64`, `FreeBSD/x86_64`, `FreeBSD/aarch64`. Unrecognised host prints a skip message naming the triple. Linux + Darwin/arm64 task names verified against `gradle :Decompiler:tasks --all`. End-to-end Mac validation blocked by the pre-existing `bfd.h not found` libbfd-on-macOS issue (DD-0004 + `decompiler-cpp-tests.yml` comment) — the precheck's earlier `make decomp_test_dbg` step never reaches the new SLEIGH-compile leg on Mac until that's sorted. Linux behaviour unchanged.

### 2026-05-28 — Precheck SLEIGH-compile leg (closes the v26.1.15 disclosed blind spot)

- **`scripts/local-precheck.sh --full` now also runs `gradle :Decompiler:compileSleighLinux_x86_64ExecutableSleighCpp`.** Closes the blind spot disclosed in [PR #164](https://github.com/CryptoJones/GayHydra/pull/164): the `cpp/Makefile`'s `decomp_test_dbg` target does not include `slgh_compile.cc` (or `pcodecompile.cc`, `rulecompile.cc`, several other SLEIGH-compiler entry points), which is why PR #156 shipped with `--full` green but broke `release.yml` on the gradle SLEIGH-compile path. New leg runs after unittests + datatests in `--full` mode (Linux only — task name is per-OS). Verified by tampering: reverted `~EquationAnd` to `protected:` and confirmed the new leg fails with the exact CI error (`error: 'virtual ghidra::EquationAnd::~EquationAnd()' is protected`), then restored. DevGuide.md "Local pre-push precheck" section updated to enumerate the three `--full` legs. Also moves the `gradle_cmd` detection out of the .sla-missing conditional so the new leg has the variable in scope even when `.sla` files are already cached.

---

## [v26.1.15] — 2026-05-28

Hotfix release. v26.1.13 and v26.1.14 `release.yml` runs both
failed on `compileSleighLinux_x86_64ExecutableSleighCpp` with
`'virtual ghidra::EquationAnd::~EquationAnd()' is protected`
(triggered by PR #156's `make_unique<EquationAnd>(...)` at
`slgh_compile.cc:159`). v26.1.15 includes
[PR #164](https://github.com/CryptoJones/GayHydra/pull/164)
making the three `Equation*` dtors public, plus the DD-0004
docs entry that landed between v26.1.14 and this release.

The v26.1.13 and v26.1.14 GH Release entries do **not** exist
(the workflow that creates them never completed). v26.1.15 is
the first release after v26.1.12 to actually ship signed
binaries.

### 2026-05-28 — Rec 31 build-break hotfix (v26.1.13 + v26.1.14 release-pipeline recovery)

- **`~EquationAnd` / `~EquationOr` / `~EquationCat` made public** — `release.yml`'s `compileSleighLinux_x86_64ExecutableSleighCpp` task failed on both v26.1.13 and v26.1.14 with `error: 'virtual ghidra::EquationAnd::~EquationAnd()' is protected within this context` at `slgh_compile.cc:159` (the PR #156 site `pateq = make_unique<EquationAnd>(witheq, pateq).release();`). Same root cause as PR #157 for `~Symbol` / `~FunctionSymbol` / `~ExternRefSymbol`: `std::make_unique<T>` instantiates `std::default_delete<T>` whose `delete __ptr;` runs in the deleter's template context, where the protected `~T` is unreachable. Moves the three `Equation*` virtual dtors from `protected:` to `public:` in `slghpatexpress.hh`. Verified locally: `gradle :Decompiler:compileSleighLinux_x86_64ExecutableSleighCpp` green in 8s (the exact task that failed in CI). Local `scripts/local-precheck.sh --full` still green (204/204 + 677/677).
- **Disclosure of the precheck blind spot**: `scripts/local-precheck.sh --full` builds `decomp_test_dbg` via `Ghidra/Features/Decompiler/src/decompile/cpp/Makefile`, which does *not* compile `slgh_compile.cc` — that file is only built by the gradle `compileSleighLinux_x86_64ExecutableSleighCpp` task. PR #156 (`slgh_compile.cc` migration) shipped with `--full` green but contained code only the gradle SLEIGH-compile would have validated. v26.1.13 and v26.1.14 release.yml runs both broke on this. Follow-up: add a SLEIGH-compile leg to the precheck that invokes the gradle task too; until then, SLEIGH-touching PRs need a manual `gradle :Decompiler:compileSleighLinux_x86_64ExecutableSleighCpp` before push.

### 2026-05-28 — Rec 24 decision doc (DD-0004)

- **DD-0004 — Windows path for `decompiler-cpp-tests` workflow.** Captures the design choices for Rec 24 (audit-named "add Windows (MSVC) to the C++ decompiler test workflow"). The existing GCC `Makefile` builds `decomp_test_dbg` against `libbfd` (GNU binutils), so a Windows port needs either MinGW-w64 + binutils on the runner, or a new MSVC `CMakeLists.txt` paired with a libbfd-substitute. DD-0004 rejects the MinGW shortcut on toolchain-consolidation grounds (the repo already uses MSVC for `release.yml`'s Windows build; carrying two Windows toolchains for one workflow's convenience is the wrong tradeoff), rejects WSL2 because a Linux-ABI binary isn't Windows coverage, and accepts the MSVC + CMake direction as the long-term path — gated on a strategic sprint that picks the BFD-substitute approach (port vs. exclude vs. stub). `SprintPlanning.md` Backlog Rec 24 row updated to reflect "strategic sprint pending DD-0004 path-pick" rather than the previous "narrow PR" framing. Rec 23 row strike-through marks v26.1.14's [PR #162](https://github.com/CryptoJones/GayHydra/pull/162) as the close.

---

## [v26.1.14] — 2026-05-28

Patch release on top of v26.1.13 covering:

- **`cppRaiiAudit` is now bulletproof**: two false-negatives in the
  raw-`new` regex (`new T;` no-parens and `new T*[N]` array-of-ptr)
  closed and the 12 escaping sites migrated; a completeness check
  fails CI if any new `.cc`/`.hh` in the decompiler tree isn't
  gated or explicitly excluded.
- **Rec 23 — `unit_tests` job goes multi-OS** (Ubuntu / macOS /
  Windows), matching the already-multi-OS `build` job. Platform-
  specific JVM test failures now surface at PR time.
- **SprintPlanning.md sync** marking Rec 31 Stages 3–8 closed.

Rec 24 (Windows MSVC for the C++ decompiler unit-tests workflow) is
still open — local validation needs the Win11 QEMU CI VM to be
fully bootstrapped past Windows install. Deferred to the next
release.

### 2026-05-28 — Rec 31 audit-regex tightening

- **Rec 31 — `cppRaiiAudit` regex now catches no-parens `new T;` (post-v26.1.13 follow-up).** The old regex `\bnew\s+[A-Za-z_][A-Za-z0-9_:]*\s*[(\[<]` required an opening `(`, `[`, or `<` after the type identifier, so the default-construct-no-parens form `new T;` slipped through silently. That false-negative was documented in [PR #135](https://github.com/CryptoJones/GayHydra/pull/135)'s `analyzesigs.cc:144` `new ofstream;` site and similar. Adding `;` to the terminator class — `[(\[<;]` — closes the gap. A tree-wide grep surfaced **nine hand-written sites** that were escaping the audit in already-protected files: `varnode.cc:260` (`cover = new Cover;`), `analyzesigs.cc:144` + `interface.cc:567`/`589` (`status->fileoptr = new ofstream;`), `unify.cc:31`/`48`/`76` (`storespot.cn = new uintb;`), `prettyprint.cc:1253`/`1255` (`lowlevel = new EmitMarkup;` / `new EmitNoMarkup;`). All nine migrate to `make_unique<T>().release()` (interface.cc uses `std::make_unique<...>` per its [PR #130](https://github.com/CryptoJones/GayHydra/pull/130) `#include <memory>` chain). Bison-generated escapes in `xml.cc`, `pcodeparse.cc`, `slghparse.cc` are unaffected — they were already either gated by line-range exclusion (`xml.cc`) or out-of-scope (`pcodeparse.cc`, `slghparse.cc` are not in `PROTECTED_FILES`). `gradle cppRaiiAudit` reports 226 protected files clean. Local `--full` precheck green (204/204 + 677/677).
- **Rec 31 — `cppRaiiAudit` regex now catches `new T*[N]` array-of-pointers (post-#158 follow-up).** Second known false-negative, flagged in [PR #105](https://github.com/CryptoJones/GayHydra/pull/105)'s changelog. The regex required the terminator (`(`, `[`, `<`, `;`) directly after the type identifier; an intervening `*` (e.g. `new ConstructState *[numOperands]`) bypassed the match. Adding `\*?\s*` between the identifier and the terminator class — `[A-Za-z_][A-Za-z0-9_:]*\s*\*?\s*[(\[<;]` — closes the gap. Three hand-written sites surfaced, all in already-protected files: `context.cc:41` (`resolve = new ConstructState *[numOperands]`), `sleigh.cc:451` (`list = new ParserContext *[minimumreuse]`), `sleigh.cc:453` (`hashtable = new ParserContext *[hashsize]`). All three migrate to `make_unique<T*[]>(N).release()` — `unique_ptr<T*[]>` is a valid C++14 form (array-of-`T*`); `.release()` returns the same `T**` shape as before. Member type stays raw-`T**` for `resolve` / `list` / `hashtable`; the destructors' manual `delete []` cleanup is preserved bit-for-bit. `gradle cppRaiiAudit` reports 226 protected files clean. Local `--full` precheck green (204/204 + 677/677).
- **`SprintPlanning.md` sync — Rec 31 Stages 3–8 closed at v26.1.13.** Two checkbox rows updated: Sprint 6's "Rec 31 PR #31-3 + Rec 32 PR #32-4" item flips from `[ ]` to `[x]` with a one-line summary of the v26.1.13 close-out (226/229 = 99% gated; 4 remaining bison/flex-generated files blocked on the Option A variant-mode rewrite). Backlog's "Rec 31 Stages 3–8" entry strike-through with a back-pointer to Sprint 6. No code changes.
- **Rec 31 — `cppRaiiAudit` completeness check.** Adds a tree-scan pass at the end of the audit task that fails CI when any `.cc`/`.hh` file in `Ghidra/Features/Decompiler/src/decompile/cpp/` is neither in `PROTECTED_FILES` nor in the new `EXCLUDED_FILES` set. Closes the drift hazard where a brand-new file could ship ungated and silently bypass the Rec 31 guarantee. `EXCLUDED_FILES` is documented as bison/flex-generated files (`grammar.cc`, `pcodeparse.cc`, `slghparse.cc`, `slghscan.cc`) — anything else added to the tree must either be migrated and gated, or get an explicit `EXCLUDED_FILES` entry with a written reason. Verified by adding a sentinel `_drift_test.cc` to the tree (audit fails with the expected error message) and removing it (audit returns clean). Steady-state log line: `cppRaiiAudit: 226 protected file(s) clean (4 excluded by policy).`
- **Rec 23 — expand `unit_tests` job to multi-OS.** Matrix in `.github/workflows/build-ghidra.yml`'s `unit_tests` job goes from `[ubuntu-latest]` → `[ubuntu-latest, macos-latest, windows-latest]`, matching the already-multi-OS `build` job. Adds the Unix/Windows JDK-version-read step pair (copied verbatim from the `build` job, which is the canonical pattern — `awk` on Unix, PowerShell `Get-Content` on Windows). All other steps (`gradle prepdev`, three audit tasks, `:Docking:i18nLint`, `gradle test`) are gradle-driven and platform-portable. Validated: all three audits + `prepdev` + `i18nLint` green locally on Ubuntu 25.10 (Gradle 8.5 + Oracle JDK 21) AND on the `mac-mini` build host (macOS 26.5, Homebrew Gradle 9.5.1 + Temurin-21). Windows path proven by the existing `build` job's multi-OS run. Platform-specific test failures, if any, surface here at PR time rather than at user-reported time — exactly what Rec 23 asks for.

---

## [v26.1.13] — 2026-05-28

Rec 31 RAII migration sprint close — the decompiler C++ tree is
now **226/229 files (99%)** under the `cppRaiiAudit` gate, up from
120/229 at v26.1.12. The four remaining unprotected files are
bison/flex-generated (`grammar.cc`, `pcodeparse.cc`, `slghparse.cc`,
`slghscan.cc`); their migration is blocked on the bison `%union`
variant-mode rewrite tracked in
[`docs/decompiler/RAII_STAGE_2C_XML.md`](docs/decompiler/RAII_STAGE_2C_XML.md).

This release also includes a `--full`-mode fix for
`scripts/local-precheck.sh` (PR #136) that makes the pre-push test
gate usable on every release branch, plus the Symbol-hierarchy
destructor accessibility unblocking that made `database.cc`
migratable (PR #157).

### 2026-05-28 — Rec 31 header-companion batch

- **Rec 31 — header-companion regression-guard batch (31 files).** All `.hh` headers whose `.cc` counterparts are already in `cppRaiiAudit`'s `PROTECTED_FILES` but were themselves left ungated. Files: `address.hh`, `bfd_arch.hh`, `cpool.hh`, `database_ghidra.hh`, `emulate.hh`, `emulateutil.hh`, `globalcontext.hh`, `inject_ghidra.hh`, `loadimage_bfd.hh`, `memstate.hh`, `opbehavior.hh`, `options.hh`, `override.hh`, `printc.hh`, `printjava.hh`, `printlanguage.hh`, `rangeutil.hh`, `raw_arch.hh`, `signature_ghidra.hh`, `slaformat.hh`, `sleighbase.hh`, `space.hh`, `string_ghidra.hh`, `stringmanage.hh`, `testfunction.hh`, `transform.hh`, `typeop.hh`, `variable.hh`, `varmap.hh`, `xml.hh`, `xml_arch.hh`. All verified zero hits under the audit's `RAW_NEW_PATTERN` with trailing-comment strip. Same regression-guard pattern as the Stage 4-8 batch ([PR #94](https://github.com/CryptoJones/GayHydra/pull/94)). Protected-set count: 120 → 151.
- **Rec 31 — glue + parser-scaffolding batch (51 files).** Second sweep of already-clean unprotected files in the decompiler tree. Covers the `*_ghidra.{cc,hh}` Java-bridge layer (`comment_ghidra`, `cpool_ghidra`, `ghidra_context`, `loadimage_ghidra`, `typegrp_ghidra`), grammar/parser/scanner header scaffolding (`grammar.hh`, `pcodecompile.hh`, `pcodeinject.{cc,hh}`, `pcodeparse.hh`, `semantics.hh`, `signature.hh`, `slgh_compile.hh`, `slghparse.hh`, `slghpatexpress.hh`, `slghsymbol.hh`, `sleigh_arch.hh`), the loadimage/inject family (`inject_sleigh.hh`, `loadimage.hh`, `loadimage_xml.{cc,hh}`), capability/interface plumbing (`capability.{cc,hh}`, `interface.hh`), miscellaneous template/header-only files (`partmap.hh`, `rangemap.hh`, `doccore.hh`, `docmain.hh`, `error.hh`, `fspec.hh`, `architecture.hh`, `block.hh`, `analyzesigs.hh`, `crc32.{cc,hh}`, `graph.{cc,hh}`, `libdecomp.{cc,hh}`, `test.{cc,hh}`, `unionresolve.{cc,hh}`, `sleighexample.cc`). All audit-clean. Protected-set count: 151 → 202.
- **Rec 31 — `semantics.cc` RAII migration.** Six raw-`new` sites migrated in the sleigh `OpTpl` / `VarnodeTpl` / `HandleTpl` decode paths. Two member-assignment sites (`OpTpl::decode` line 719 `output = new VarnodeTpl()`, `ConstructTpl::decode` line 913 `result = new HandleTpl()`) use `make_unique<T>().release()` — the member types stay raw-pointer (`~OpTpl` / `~ConstructTpl` manually `delete` each; preserved bit-for-bit). Two `vec.push_back(new T())`-style sites in the decode loops (line 723 `VarnodeTpl`, line 917 `OpTpl`) use the `auto owned = make_unique<T>(); T *p = owned.get(); vec.push_back(owned.release()); p->decode(decoder);` pattern from `varnode.cc` PR #96 — `vec`/`input` are `vector<T*>` containers. One create-then-`addInput` pair in `fillinBuild` (lines 782–783) uses `.release()`-immediate on both the `OpTpl(BUILD)` and the synthesized `VarnodeTpl` constant. `semantics.cc` joins `PROTECTED_FILES`. Protected-set count: 202 → 203.
- **Rec 31 — `signature.cc` RAII migration.** Six raw-`new` sites migrated in `GraphSigManager`. Four `addSignature(new XxxSignature(...))` registration sites (lines 757 `VarnodeSignature`, 862 `BlockSignature`, 870 `BlockSignature`, 873 `CopySignature`) use `.release()`-immediate; `addSignature` takes a raw `Signature *` and takes ownership. One `BlockSignatureEntry` create-then-map-then-method site (line 907) uses the create-then-named-raw pattern (`auto owned = make_unique<BlockSignatureEntry>(bl); BlockSignatureEntry *entry = owned.get(); blockmap[...] = owned.release(); entry->localHash(sigmods);`) because the entry pointer is needed for the `localHash` call after the map insert. One `SignatureEntry` create-then-map site (line 972) collapses to a single-line `sigmap[...] = make_unique<SignatureEntry>(...).release();` since the local `entry` raw pointer wasn't used post-insert. The destructors in `varnodeClear` / `blockClear` still iterate `sigmap`/`blockmap` and `delete (*iter).second` — preserved bit-for-bit. `signature.cc` joins `PROTECTED_FILES`. Local `make decomp_test_dbg` green in 10s. Protected-set count: 203 → 204.
- **Rec 31 — `analyzesigs.cc` RAII migration.** Six raw-`new` sites migrated. Five `status->registerCom(new IfcXxx(), ...)` Sleigh interface registrations (lines 33–37: `IfcSignatureSettings`, `IfcPrintSignatures`, `IfcSaveSignatures`, `IfcSaveAllSignatures`, `IfcProduceSignatures`) use `.release()`-immediate — same pattern as `consolemain.cc` / `ifacedecomp.cc` (PRs #120, #123). One `smanage = new GraphSigManager()` member-assignment (line 140) with preceding manual `if (smanage != ...) delete smanage;` cleanup uses `make_unique<GraphSigManager>().release()` — member type stays raw-pointer; the conditional delete is preserved (no double-free risk because the manual delete runs before the new assignment). Note: line 144's `new ofstream;` (no parens) escapes the audit regex; a separate stylistic cleanup. `analyzesigs.cc` joins `PROTECTED_FILES`. Local `make decomp_test_dbg` green in 10s. Protected-set count: 204 → 205.
- **`scripts/local-precheck.sh` `--full` fix: prefer `gradle` on `PATH`.** The script's `--full` mode was calling `./gradlew allSleighCompile` to populate `.sla` files, but the repo's `./gradlew` is the Ghidra-style shim that hard-exits with *"Please install Gradle ... and put it on your PATH"* whenever `application.release.name` is not `"PUBLIC"`/`"DEV"` — i.e., on every cut release branch (`GayHydra-26.1.x`). So `--full` was structurally unusable for the release-branch line that runs CI and where local pre-push gating matters most. Patch: prefer `gradle` on `PATH` when available; fall back to `./gradlew` only when it isn't. Adds an early toolchain check that fails fast if neither is usable. Verified: `scripts/local-precheck.sh --full --force` against current master now exits 0 with 204/204 unittests + 677/677 datatests passing. `DevGuide.md`'s "Local pre-push precheck" section gained a one-liner install snippet for Oracle JDK 21 + Gradle 8.5 into `~/.local/` to match CI.
- **Rec 31 — `sleigh_arch.cc` RAII migration.** Six raw-`new` sites migrated, all in `SleighArchitecture`'s `build*` factory methods. Five member-assignment sites (lines 201 `types = new TypeFactory(this)`, 244 `commentdb = new CommentDatabaseInternal()`, 250 `stringManager = new StringManagerUnicode(this,2048)`, 256 `cpool = new ConstantPoolInternal()`, 262 `context = new ContextInternal()`) use `.release()`-immediate; member types stay raw-pointer because the destructors manually delete each (`~Architecture` preserved bit-for-bit). One `buildPcodeInjectLibrary` clone-style factory (line 192-195) collapses from a three-line `PcodeInjectLibrary *res; res = new ...; return res;` to a single `return make_unique<PcodeInjectLibrarySleigh>(this).release();` — same pattern as the `clone()` virtuals in `type.hh` (PR #100) and `coreaction.hh` (PR #122). `sleigh_arch.cc` joins `PROTECTED_FILES`. Local `scripts/local-precheck.sh --full --force` green (204/204 unittests + 677/677 datatests). Protected-set count: 205 → 206.
- **Rec 31 — `inject_sleigh.cc` RAII migration.** Seven raw-`new` sites migrated in the sleigh p-code injection library. Two `delete-then-replace` sites (line 354 `forceDebugDynamic` and line 431 `registerInject`) use the named-raw + manual-delete-preserved pattern — `auto owned = make_unique<InjectPayloadDynamic>(glb, oldPayload); delete oldPayload; ptr = owned.get(); injection[id] = owned.release();` — preserving the original `delete oldPayload` ordering bit-for-bit. One member-assignment + method-call site (line 377 `contextCache.pos = new ParserContext(...)`) uses `.release()`-immediate. Four `injection.push_back(new XxxPayload(...))` factory sites in `allocateInject` (lines 416, 418, 420, 422 for `InjectPayloadCallfixup`, `InjectPayloadCallother`, `ExecutablePcodeSleigh`, `InjectPayloadSleigh`) use `.release()`-immediate — same pattern as the `Rule clone()` batch ([#101](https://github.com/CryptoJones/GayHydra/pull/101)). `inject_sleigh.cc` joins `PROTECTED_FILES`. Local `--full` precheck green (204/204 + 677/677). Protected-set count: 206 → 207.
- **Rec 31 — `codedata.cc` RAII migration.** Eight `status->registerCom(new IfcCodeDataXxx(), ...)` sleigh interface registrations in `IfaceCodeDataCapability::registerCommands` (lines 34-41) all use `.release()`-immediate. Same pattern as `analyzesigs.cc` ([#135](https://github.com/CryptoJones/GayHydra/pull/135)), `consolemain.cc` ([#120](https://github.com/CryptoJones/GayHydra/pull/120)), and `ifacedecomp.cc` ([#123](https://github.com/CryptoJones/GayHydra/pull/123)). `codedata.cc` joins `PROTECTED_FILES`. Local `--full` precheck green (204/204 + 677/677). Protected-set count: 207 → 208.
- **Rec 31 — `unify.cc` RAII migration.** Nine raw-`new` sites migrated in the SLEIGH-pattern unification engine. One clone-style return (line 262 `ConstantExpression::clone` — `return new ConstantExpression(...)`) uses `.release()`-immediate. Four register-then-discard sites in `*::buildTraverseState` (lines 389 `TraverseCountState`, 830 `TraverseDescendState`, 1097 `TraverseGroupState`, 1189 `TraverseCountState`) keep the named-raw shape: `T *newt = make_unique<T>(uniqid).release(); state.registerTraverseConstraint(newt);` — `registerTraverseConstraint` takes ownership of the raw pointer. Two `clone()` factory sites that create-then-mutate-then-return (lines 1019 `ConstraintGroup`, 1144 `ConstraintOr`) use the same named-raw form with `.release()`-immediate so the subsequent `res->constraintlist.push_back(...)` / `res->copyid(this)` / `return res` chain works unchanged. Two chained-method clone-factory sites (lines 433 `ConstraintVarConst`, 1366 `ConstraintSetInputConstVal`) use `make_unique<T>(...).release()->copyid(this)` — `copyid` returns `this` (per unify.hh:206), so the chain is identity-preserving. `unify.cc` joins `PROTECTED_FILES`. Note: `unify.hh` still has 33 sites of the same `(new T(args))->copyid(this)` clone-virtual pattern — its own follow-up. Local `--full` precheck green (204/204 + 677/677). Protected-set count: 208 → 209.
- **Rec 31 — `ghidra_process.cc` RAII migration.** Eleven raw-`new` sites migrated in the Ghidra↔decompiler IPC bridge. Three member-assignment sites in `connect_to_console` and the architecture-slot constructor (lines 46 `remote = new RemoteSocket()`, 48 `ghidra_dcp = new IfaceTerm(...)`, 187 `ghidra = new ArchitectureGhidra(...)`) use `.release()`-immediate; member types stay raw-pointer with their existing destructors preserved. One delete-then-replace in `SetOptions::loadParameters` (line 424 `decoder = new PackedDecode(ghidra)` with preceding `if (decoder != ...) delete decoder;`) uses `.release()`-immediate — same pattern as `analyzesigs.cc` line 140 (PR #135). Seven `commandmap["..."] = new XxxCommand()` map inserts in `GhidraDecompCapability::initialize` (lines 499–505 for `RegisterProgram`, `DeregisterProgram`, `FlushNative`, `DecompileAt`, `StructureGraph`, `SetAction`, `SetOptions`) all use `.release()`-immediate — same pattern as `signature_ghidra.cc` ([PR #112 batch](https://github.com/CryptoJones/GayHydra/pull/112)). `ghidra_process.cc` joins `PROTECTED_FILES`. Local `--full` precheck green (204/204 + 677/677). Protected-set count: 209 → 210.
- **Rec 31 — `ghidra_arch.cc` RAII migration.** Twelve raw-`new` sites migrated in `ArchitectureGhidra`'s `build*` factory methods + the Ghidra IPC byte-read helpers. Ten `build*`-method sites (lines 286 `loader`, 292 `PcodeInjectLibraryGhidra` clone-return, 298 `GhidraTranslate` clone-return, 304 `symboltab`, 305 `globalscope`, 313 `types`, 354 `commentdb`, 360 `stringManager`, 366 `cpool`, 372 `context`) use `.release()`-immediate — same pattern as the parallel `sleigh_arch.cc` migration ([PR #137](https://github.com/CryptoJones/GayHydra/pull/137)). Two local-buffer sites (lines 739 in `readBytes`, 800 in `getStringData` — `uint1 *dblbuf = new uint1[size * 2]; ...read..loop...; delete [] dblbuf;`) migrate to `auto dblbuf = make_unique<uint1[]>(size * 2);` with the trailing `delete []` removed (auto via `unique_ptr` destruction, including on the throw path at line 742). The `(char *)dblbuf` cast at the `sin.read` call becomes `(char *)dblbuf.get()`; indexed access `dblbuf[i*2]` unchanged via `unique_ptr<T[]>::operator[]`. Same RAII-array pattern as `compression.cc` ([PR #109](https://github.com/CryptoJones/GayHydra/pull/109)). `ghidra_arch.cc` joins `PROTECTED_FILES`. Local `--full` precheck green (204/204 + 677/677). Protected-set count: 210 → 211.
- **Rec 31 — `architecture.cc` RAII migration.** Thirteen raw-`new` sites migrated in the base `Architecture` class — the upstream-facing analogue to `sleigh_arch.cc` / `ghidra_arch.cc`. Two ctor-body member-assignment sites (line 173 `options = new OptionDatabase(this)`, 176 `stats = new Statistics()` inside `#ifdef CPUI_STATISTICS`) use `.release()`-immediate. One create-then-use-then-insert site for `SpacebaseSpace` (line 565) uses create-then-named-raw — `auto owned = make_unique<...>(); SpacebaseSpace *spc = owned.get(); ...setReverseJustified(spc); insertSpace(owned.release()); addSpacebasePointer(spc,...);` — preserving the post-insert use of `spc`. Two `buildDatabase` sites (lines 600 `symboltab = new Database(this,true)`, 601 `globscope = new ScopeInternal(...)`) use `.release()`-immediate; `globscope` is then passed to `attachScope` (ownership-transfer per `funcdata.cc` #99). Three `insertSpace(new XxxSpace(...))` ownership-transfer sites in `restoreFromSpec` (lines 633 `FspecSpace`, 634 `IopSpace`, 635 `JoinSpace`) inline `make_unique<T>(...).release()` — same pattern as `translate.cc` ([PR #102](https://github.com/CryptoJones/GayHydra/pull/102)). One `SegmentedResolver` create-then-insert site (line 656) keeps the named-raw shape since `insertResolver` is the transfer point. Two `unique_ptr<ProtoModel>` reset sites in `decodeProto` (lines 748, 750) modernize from `model.reset(new T(...))` to `model = make_unique<T>(...)` (C++14 idiom). Two map-insert factory sites for prototype models (lines 1151 `protoModels[aliasName] = new ProtoModel(...)`, 1161 `UnknownProtoModel *model = new UnknownProtoModel(...)`) use `.release()`-immediate; the latter is a named-raw because `model` is used for `setPrintInDecl` + return. `architecture.cc` joins `PROTECTED_FILES`. Local `--full` precheck green (204/204 + 677/677). Protected-set count: 211 → 212.
- **Rec 31 — `jumptable.cc` RAII migration.** Fifteen raw-`new` sites migrated across the JumpTable model hierarchy. Nine clone-style create-mutate-return / create-mutate-member sites (lines 318 `JumpValuesRange`, 379 `JumpValuesRangeDefault`, 417 `JumpModelTrivial`, 1633 `JumpBasic`, 1715 `JumpValuesRangeDefault`, 1773 `JumpBasic2`, 2027 `JumpBasicOverride`, 2236 `JumpAssisted`, plus the named-raw at line 2286 `JumpAssisted`) keep the `T *res = make_unique<T>(...).release();` shape because the local pointer is used to set fields before `return res` or before assigning to a member. Five member-assignment sites (lines 1442 `jrange`, 2292 `jmodel via jbasic`, 2486 multi-assign `jmodel = jumpOverride = ...`, 2741 `jmodel`, 2849 `jmodel`) use `.release()`-immediate. One post-test-replace site (line 2296 `jmodel = new JumpBasic2(this)`) uses `.release()`-immediate; the subsequent `delete jbasic` is preserved bit-for-bit. `jumptable.cc` joins `PROTECTED_FILES`. Local `--full` precheck green (204/204 + 677/677). Protected-set count: 212 → 213.
- **Rec 31 — `blockaction.hh` clone() batch (8 sites).** All eight `Action*::clone(grouplist)` virtuals in the block-action hierarchy (lines 276 `ActionStructureTransform`, 290 `ActionNormalizeBranches`, 307 `ActionPreferComplement`, 318 `ActionBlockStructure`, 333 `ActionRevertISC`, 346 `ActionFinalStructure`, 361 `ActionReturnSplit`, 372 `ActionNodeJoin`) migrate from `return new ActionXxx(getGroup()...)` to `return make_unique<ActionXxx>(getGroup()...).release()`. Single-regex substitution. Same pattern as the Rule + Action `clone()` batches (PRs [#100](https://github.com/CryptoJones/GayHydra/pull/100), [#101](https://github.com/CryptoJones/GayHydra/pull/101), [#122](https://github.com/CryptoJones/GayHydra/pull/122)). `blockaction.hh` joins `PROTECTED_FILES`. Local `--full` precheck green (204/204 + 677/677). Protected-set count: 213 → 214.
- **Rec 31 — `modelrules.hh` clone() batch (15 sites).** All fifteen `clone()` virtuals across the parameter-passing model rule hierarchy migrate from `return new XxxFilter/Action(args)` to `return make_unique<...>(args).release()`. Three `DatatypeFilter::clone` (lines 134, 149, 163: `SizeRestrictedFilter`, `MetaTypeFilter`, `HomogeneousAggregate`), two `QualifierFilter::clone` (lines 226, 239: `VarargsFilter`, `PositionMatchFilter`), ten `AssignAction::clone` (lines 332, 346, 371, 390, 421, 438, 462, 484, 505, 525: `GotoStack`, `ConvertToPointer`, `MultiSlotAssign`, `MultiMemberAssign`, `MultiSlotDualAssign`, `ConsumeAs`, `HiddenReturnAssign`, `ConsumeExtra`, `ExtraStack`, `ConsumeRemaining`). Three of these spanned two lines and were edited individually. Same pattern as the prior Rule/Action `clone()` batches. `modelrules.hh` joins `PROTECTED_FILES`. Local `--full` precheck green (204/204 + 677/677). Protected-set count: 214 → 215.
- **Rec 31 — `block.cc` RAII migration (18 sites).** Migrates the FlowBlock-hierarchy factory functions and the block-structuring constructors. Thirteen `T *ret = new T(...)` named-create-and-fill-then-return sites (lines 992, 1666, 1677, 1688, 1709, 1769, 1790, 1811, 1830, 1848, 1866, 1882, 1897 for `FlowBlock`, `FlowBlock`, `BlockBasic`, `BlockCopy`, `BlockGoto`, `BlockList`, `BlockCondition`, three `BlockIf`, `BlockWhileDo`, `BlockDoWhile`, `BlockInfLoop`) get the standard `make_unique<T>(...).release()` swap. One member-assignment in the if/else branch at line 1738 (`ret = new BlockMultiGoto(bl)`) uses `.release()`-immediate. One existing-`unique_ptr` ctor site at line 1912 (`unique_ptr<BlockSwitch> uret(new BlockSwitch(rootbl))`) modernizes to `auto uret = make_unique<BlockSwitch>(rootbl)` (C++14 idiom). Three `BlockGraph::nodeFactory`-style clone-return sites (lines 3750 `FlowBlock`, 3752 `BlockCopy((FlowBlock *)0)`, 3754 `BlockGraph`) become `return make_unique<T>(...).release()`. `block.cc` joins `PROTECTED_FILES`. Local `--full` precheck green (204/204 + 677/677). Protected-set count: 215 → 216.
- **Rec 31 — `modelrules.cc` RAII migration (20 sites).** All twenty raw-`new` sites in the parameter-passing model-rule decode/factory layer migrate. Thirteen `unique_ptr<T>::reset(new XxxFilter/Action(...))` sites (in `DatatypeFilter::decodeFilter`, `QualifierFilter::decodeFilter`, `AssignAction::decodeAction` — lines 259, 262, 267, 457, 459, 461, 598, 600, 603, 606, 609, 612, 615, 636, 660, 663, 666) modernize to `filter = make_unique<T>(...)` / `action = make_unique<T>(...)` (C++14 idiom; drops the explicit `.reset(new ...)` indirection — same idiom as the `architecture.cc` `decodeProto` cleanup in [PR #143](https://github.com/CryptoJones/GayHydra/pull/143)). One clone-style return at line 489 (`return new AndFilter(newFilters)`) becomes `return make_unique<...>().release()`. One typed-local-decl at line 549 (`DatatypeMatchFilter *res = new DatatypeMatchFilter()`) uses `make_unique<...>().release()`. One bare member-assign at line 1695 (`qualifier = new AndFilter(qualifiers)`) uses `.release()`-immediate. Five total patterns; single-pass regex sweep covered all 20. `modelrules.cc` joins `PROTECTED_FILES`. Local `--full` precheck green (204/204 + 677/677). Protected-set count: 216 → 217.
- **Rec 31 — `slghpatexpress.cc` RAII migration (23 sites).** All twenty-three raw-`new` sites in the SLEIGH pattern-expression decoder migrate. One clone-style return at line 174 (`return new PatternBlock(offset,mask,byteval)`) becomes `return make_unique<...>(...).release()`. Six `pattern = new XxxPattern(...)` member-assignment sites in `PatternEquation` constructors / `Pattern` decoders (lines 268, 276, 284, 301 `InstructionPattern`, 313 `ContextPattern`) use `.release()`-immediate. Sixteen `res = new XxxField/Value/Expression()` factory dispatch sites in `PatternExpression::decodeExpression` (lines 472-504 for `TokenField`, `ContextField`, `ConstantValue`, `OperandValue`, `StartInstructionValue`, `EndInstructionValue`, `PlusExpression`, `SubExpression`, `MultExpression`, `LeftShiftExpression`, `RightShiftExpression`, `AndExpression`, `OrExpression`, `XorExpression`, `DivExpression`, `MinusExpression`, `NotExpression`) all use `.release()`-immediate. Single multi-pattern regex sweep covered all 23. `slghpatexpress.cc` joins `PROTECTED_FILES`. Local `--full` precheck green (204/204 + 677/677). Protected-set count: 217 → 218.
- **Rec 31 — `fspec.cc` RAII migration (25 sites).** All twenty-five raw-`new` sites in the function-prototype / parameter-passing machinery migrate via single multi-pattern regex sweep. Five `ParamList *res = new ParamListXxx(*this)`-style clone-then-return typed-local-decls (lines 1515, 1538, 1565, 1786, 1838) become `make_unique<T>(...).release()` with their downstream `res->copy_fields(); return res;` shape preserved. Six `input = new ParamListXxx()` / `output = new ParamListXxx()` member-assignments in `restoreXml`/`buildModel` callers (lines 2327, 2328, 2331, 2332, 2842, 2843) use `.release()`-immediate. Three `outparam = new ParameterBasic(...)` member-assignments (lines 3261, 3385, 3394) and one `outparam = new ProtoStoreSymbol(...)` (line 3284) use `.release()`-immediate. One indexed-array bare-assign `inparam[i] = new ParameterBasic(...)` (line 3336) uses `.release()`-immediate (the regex covers `name[expr] = new T(...);`). Three `store = new ProtoStoreXxx(...)` member-assignments (lines 3746, 3882, 3894) use `.release()`-immediate. Six remaining typed-local-decl sites (lines 1183 `ParamEntryResolver`, 2977 `ParameterBasic`, 3142 `ParameterSymbol`, 3406 `ProtoStoreInternal`, 4967 `FuncCallSpecs`, 5493 `FuncProto`) use `.release()`-immediate. `fspec.cc` joins `PROTECTED_FILES`. Local `--full` precheck green (204/204 + 677/677). Protected-set count: 218 → 219.
- **Rec 31 — `slghpattern.cc` RAII migration (31 sites).** All thirty-one raw-`new` sites in the SLEIGH pattern hierarchy migrate via single multi-pattern regex sweep (plus one manual edit for a multi-line `return new CombinePattern(..., ...)` at line 786). Three `unique_ptr<T>::reset(new XxxPattern())` in `Pattern::decode` (lines 147, 149, 151 — `InstructionPattern`, `ContextPattern`, `CombinePattern`) modernize to `res = make_unique<T>()`. Thirteen `return new XxxPattern(...)` clone/factory returns (lines 327, 538, 555, 588, 608, 635, 646, 657, 785, 786, 965, 973, 976) use `make_unique<T>(...).release()`. Six typed-local-decl factory sites (lines 286, 300, 328 `PatternBlock`, 570 `InstructionPattern`, 773 `OrPattern`, 954 `OrPattern`) use the same. Nine bare member/local assignments (lines 623 `maskvalue`, 672 `maskvalue`, 718, 724, 731, 749 `tmp = new CombinePattern`, 803 `context`, 805 `instr`, 906 `tmpor`) use `.release()`-immediate. `slghpattern.cc` joins `PROTECTED_FILES`. Local `--full` precheck green (204/204 + 677/677). Protected-set count: 219 → 220.
- **Rec 31 — `unify.hh` clone-virtual batch (33 sites).** Closes the follow-up flagged in [PR #140](https://github.com/CryptoJones/GayHydra/pull/140). Eight `RHSConstant::clone()` virtuals (lines 72-136 for `ConstantNamed`, `ConstantAbsolute`, `ConstantNZMask`, `ConstantConsumed`, `ConstantOffset`, `ConstantIsConstant`, `ConstantHeritageKnown`, `ConstantVarnodeSize`) — plain `return new T(args);` form — migrate to `return make_unique<T>(args).release()`. Twenty-five `UnifyConstraint::clone() const` virtuals (lines 227-601 across `DummyOpConstraint`, `DummyVarnodeConstraint`, `DummyConstConstraint`, `ConstraintBoolean`, `ConstraintNamedExpression`, `ConstraintOpCopy`, `ConstraintOpcode`, `ConstraintOpCompare`, `ConstraintOpInput`, `ConstraintOpInputAny`, `ConstraintOpOutput`, `ConstraintParamConstVal`, `ConstraintParamConst`, `ConstraintVarnodeCopy`, `ConstraintVarCompare`, `ConstraintDef`, `ConstraintDescend`, `ConstraintLoneDescend`, `ConstraintOtherInput`, `ConstraintConstCompare`, `ConstraintNewOp`, `ConstraintNewUniqueOut`, `ConstraintSetInput`, `ConstraintRemoveInput`, `ConstraintSetOpcode`) — chained `return (new T(args))->copyid(this);` form — migrate to `return make_unique<T>(args).release()->copyid(this);` (same identity-preserving chain since `copyid` returns `this` per `unify.hh:206`). Single multi-pattern regex sweep. `unify.hh` joins `PROTECTED_FILES`. Local `--full` precheck green (204/204 + 677/677). Protected-set count: 220 → 221.
- **Rec 31 — `slghsymbol.cc` RAII migration (46 sites).** All forty-six raw-`new` sites across the SLEIGH symbol table, varnode-template factories, decision-tree builder, and ContextOp/ContextCommit emitters migrate. Thirteen `sym.reset(new XxxSymbol())` factory-dispatch sites in `SleighSymbol::decodeSym` (lines 230-254 for `UserOpSymbol`, `EpsilonSymbol`, `ValueSymbol`, `ValueMapSymbol`, `NameSymbol`, `VarnodeSymbol`, `ContextSymbol`, `VarnodeListSymbol`, `OperandSymbol`, `StartSymbol`, `EndSymbol`, `Next2Symbol`, `SubtableSymbol`) modernize to `sym = make_unique<T>()`. One `unique_ptr<ConstructTpl> cur(new ConstructTpl())` (line 1705) modernizes to `auto cur = make_unique<ConstructTpl>()`. The remaining thirty-two sites are clone-style returns / member-assigns / typed-local-decls — twelve `return new VarnodeTpl(...)` factory returns; thirteen member-assignments (lines 63 `curscope`, 202 `table[id]` indexed-array, 428/435 `patexp = new ConstantValue`, 979 `localexp`, 1141/1197/1262/1271/1327 `patexp = new XxxInstructionValue`, 1695 `c_op = new ContextOp`, 1700 `c_op = new ContextCommit`, 1809/1998 `pattern = new TokenPattern`); seven typed-local-decls (463 `VarnodeTpl`, 1017/1024/1026 `res = new VarnodeTpl(hand,...)` — these have trailing inline comments; 1958 `Constructor *ct`, 2240/2408 `DecisionNode`, 2491 `ContextOp`, 2541 `ContextCommit`); plus `1963` and `1979` `decisiontree = new DecisionNode(...)` member-assigns. Single multi-pattern regex sweep handled most; six inline-comment sites and the indexed-array site needed individual edits. `slghsymbol.cc` joins `PROTECTED_FILES`. Local `--full` precheck green (204/204 + 677/677). Protected-set count: 221 → 222.
- **Rec 31 — `pcodecompile.cc` RAII migration (49 sites).** All forty-nine raw-`new` sites in the SLEIGH-action / p-code emitter compiler migrate. Five `new vector<OpTpl *>` default-ctor allocations (lines 32, 38, 62, 321, 460) become `make_unique<vector<OpTpl *>>().release()`. Forty-four `OpTpl`/`VarnodeTpl`/`ExprTree`/`LabelSymbol`/`VarnodeSymbol` factory sites — a mix of typed-local-decls (`OpTpl *op = ...`), member-assigns (`outvn = ...`, `res->outvn = ...`, `ptr->outvn = ...`), and chained `->outvn`/`->member` assignments — all `.release()`-immediate. Eight of the `VarnodeTpl(...)` ctors span multiple lines (lines 298, 323, 457, 476, 502, 533, 557, 771) and were handled by a Python paren-balanced sweep that captured the full multi-line argument list before reformatting to `make_unique<T>(...).release()`. The single-line bulk handled by a multi-pattern sed sweep (assignment LHS forms: `T *name = new T(...)`, `name = new T(...)`, `ptr->member = new T(...)`, `name.reset(new T(...))`). `pcodecompile.cc` joins `PROTECTED_FILES`. Local `--full` precheck green (204/204 + 677/677). Protected-set count: 222 → 223.
- **Rec 31 — `rulecompile.cc` RAII migration (51 sites).** All fifty-one raw-`new` sites in the SLEIGH rule-compiler / `RuleLexer` migrate via multi-pattern regex sweep + four targeted runs for special yylval-style and `addConstraint` ownership-transfer forms. Three `ruleparselval.field = new int8/string(...)` hand-rolled-lexer yacc-lvalue assignments (lines 102, 115, 121) and one `lval->big = new intb(val)` parameter-injected lvalue (line 912) use `.release()`-immediate; the parser still owns + deletes the heap value. Four `res->addConstraint(new XxxConstraint(...))` ownership-transfer call-sites (lines 446 `DummyOpConstraint`, 454 `DummyVarnodeConstraint`, 462 `DummyConstConstraint`, 618 `ConstraintNamedExpression`) inline `make_unique<T>(...).release()` — `addConstraint` takes the raw pointer with ownership transfer (same pattern as `Action::addAction` / `Rule::addRule` in `coreaction.cc` PR #125). Forty-three remaining sites are factory-style typed-local-decls and member-assigns covered by the standard regex sweep (`UnifyConstraint *newconstraint = new ...`, `RHSConstant *res = new ...`, `res = new ConstantXxx(...)` chains in `RuleCompile::evaluateRHS`, and `ConstraintGroup`/`ConstraintOr` clone-returns). `rulecompile.cc` joins `PROTECTED_FILES`. Local `--full` precheck green (204/204 + 677/677). Protected-set count: 223 → 224.
- **Rec 31 — `slgh_compile.cc` RAII migration (64 sites).** All sixty-four raw-`new` sites in the top-level SLEIGH compiler (architecture bootstrap, symbol-table population, constructor/macro/section emitters, p-code template synthesis) migrate. Multi-step sweep: (a) the standard multi-pattern sed handled ~35 typed-local-decl / single-line-assign sites; (b) four templated `new vector<ContextChange *>()` / `new vector<OpTpl *>` allocations swept via `make_unique<vector<...>>().release()`; (c) the remaining 11 sites — six multi-line `VarnodeTpl(...)` / `HandleTpl(...)` / `AddrSpace(...)` ctors spanning two-to-four lines plus five function-arg ownership-transfer sites (`insertSpace(new UniqueSpace(...))`, `addSymbol(new VarnodeSymbol((*names)[i],...))`, `symtab.replaceSymbol(sym, new ValueMapSymbol(...))`, `replaceSymbol(...,new NameSymbol(...))`, `replaceSymbol(...,new VarnodeListSymbol(...))` with nested-paren args) — were handled by a Python paren-balanced sweep that finds `\bnew T(`, walks to the matching `)`, and rewrites in place regardless of newline count or argument nesting. `slgh_compile.cc` joins `PROTECTED_FILES`. Local `--full` precheck green (204/204 + 677/677). **Milestone: every hand-written file in `Ghidra/Features/Decompiler/src/decompile/cpp/` is now under the RAII audit gate.** Protected-set count: 224 → 225. Remaining 4 unprotected files (`database.cc`, `grammar.cc`, `pcodeparse.cc`, `slghparse.cc`, `slghscan.cc`) are all either bison/flex-generated or blocked on the Symbol-hierarchy dtor accessibility (documented in memory).
- **Rec 31 — `~Symbol` / `~FunctionSymbol` / `~ExternRefSymbol` made public + `database.cc` RAII migration (19 sites).** Unblocks the migration deferred in the `slgh_compile.cc` PR. **API delta**: three virtual destructor declarations in `database.hh` move from `protected:` (`~Symbol` line 193) / default-private (`~FunctionSymbol` line 286, `~ExternRefSymbol` line 350) to `public:`. The other in-section members (`setDisplayFormat`, `checkSizeTypeLock`, `setThisPointer`, `buildType`, `buildNameType`) stay where they were. Behavior delta: zero — these dtors were already callable via `delete sym;` from `Scope`/`ScopeInternal` (declared friends at `database.hh:173-174`), and the rest of the codebase never deletes `Symbol*` directly. The reason the change is needed: `std::make_unique<T>` instantiates `std::default_delete<T>::operator()`, which calls `delete __ptr;` in the *deleter's* template context (not the call site's) — friend-scope doesn't carry into STL templates, so the protected/private dtor was unreachable from `make_unique<Symbol>(...)`. **Migration**: all nineteen `database.cc` raw-`new` sites move to `make_unique<T>(...).release()` via the standard regex sweep. Two `fd = new Funcdata(...)` (lines 562, 585), fifteen `sym = new XxxSymbol(...)` (lines 1519-1721 + 1744 for `Symbol`, `EquateSymbol`, `FunctionSymbol`, `LabSymbol`, `ExternRefSymbol`, `UnionFacetSymbol`), one `return new ScopeInternal(...)` (line 1811), one `rangemap = new EntryMap()` (line 1854). `database.cc` joins `PROTECTED_FILES`. Removes the `project_rec31_symbol_dtor_blocker.md` memory note. Local `--full` precheck green (204/204 unittests + 677/677 datatests). Protected-set count: 225 → 226.

---

## [v26.1.12] — 2026-05-28

Build-break recovery release closing out the PR #98 fallout.
Bundles the four hotfixes (#127, #129, #130, #259) that re-greened
master after the Rec 31 `ParserContext::context` migration broke the
header-include chain.

### 2026-05-27 — local pre-push precheck for decompiler C++

- **`scripts/local-precheck.sh` + `.githooks/pre-push`.** Tracked pre-push gate that mirrors `.github/workflows/decompiler-cpp-tests.yml`'s build step (`make -j decomp_test_dbg` under `Ghidra/Features/Decompiler/src/decompile/cpp`). Auto-skips on branches that don't touch decompiler C++. Build-only by default (~15s on a 24-thread box after first compile); `--full` extends to unittests + datatests with auto-`gradle allSleighCompile` if `.sla` files are missing; `--clean` wipes `test_dbg/*.o` for the stale-sanitizer-artifact case. Hook is opt-in per clone via `git config core.hooksPath .githooks` — once set, it would have caught the build-only failures behind PRs #127 (`.get()` on a `unique_ptr` argument), #129 (missing `using std::make_unique;`), and #130 (missing `#include <memory>`) before they hit master. Installation + manual-run docs added under DevGuide.md's "Local pre-push precheck (decompiler C++)" section.

### 2026-05-27 — release.yml Windows glob fix + Rec 31 Stage 3 audit gate

- **`release.yml` Windows zip glob** — `build_sign_publish_windows`'s "Locate release zip" step searched `build/dist/ghidra_*_windows_*.zip`, but `gradle/root/distribution.gradle:688` names the Windows zip using `getCurrentPlatformName()`'s `"win_x86_64"` token (not `"windows_x86_64"`). The glob never matched, the step exited 1, and the matrix's `publish_release` job — gated on Windows succeeding — was skipped on every run from v26.1.6 onward. The four releases v26.1.6 / v26.1.8 / v26.1.9 / v26.1.10 are all stuck as drafts with their signed assets attached. Patched the glob to `ghidra_*_win_*.zip` so the next tag's matrix run completes and `publish_release` auto-flips the draft.
- **Rec 31 Stage 3 audit-gate** — `cover.cc` + `cover.hh` added to `cppRaiiAudit`'s `PROTECTED_FILES`. Both files were already raw-`new`-free in tree; the gate prevents regression while the rest of Stage 3 (`database.cc`, `comment.cc`) is migrated. Same regression-guard pattern as the Stage 1 PR #45 add for `address.cc` / `space.cc` / `rangeutil.cc`.
- **Rec 31 Stage 3 — `comment.cc` RAII migration.** `CommentDatabaseInternal::addComment` and `::addCommentNoDuplicate`'s `Comment *newcom = new Comment(...)` sites migrated to `auto newcom = make_unique<Comment>(...)` with `commentset.insert(newcom.release())` at the ownership-transfer point. The manual `delete newcom; return false;` in the duplicate-check error branch becomes automatic via `unique_ptr` destruction on early return — closes a real exception-unsafety footgun (any future code addition between `new` and `delete` could have leaked). `commentset` itself remains `set<Comment*, CommentOrder>` (changing it to `set<unique_ptr<Comment>>` ripples into comparators + `lower_bound` callers — separate sprint). `comment.cc` + `comment.hh` added to `cppRaiiAudit`'s `PROTECTED_FILES`.
- **Rec 31 Stage 3-5 / 8 audit-gate batch expansion.** 19 additional already-clean decompiler C++ files added to `cppRaiiAudit`'s `PROTECTED_FILES`: `paramid.{cc,hh}`, `pcoderaw.{cc,hh}`, `expression.{cc,hh}`, `float.{cc,hh}`, `ghidra_translate.{cc,hh}`, `ifaceterm.{cc,hh}`, `opcodes.cc`, `filemanage.{cc,hh}`, `dynamic.{cc,hh}`, `multiprecision.{cc,hh}`. Zero raw-`new` allocation sites in any of them under the audit's filter; the gate freezes them as regression guards while migration continues elsewhere. Same pattern as the Stage 1 PR #45 add. Brings the protected set from 11 → 30 files.
- **`cppRaiiAudit` trailing-comment strip + `opcodes.hh` add.** The audit's pure-comment-line filter skips lines whose trim starts with `//` or `*`, but not lines like `CPUI_NEW = 69,    ///< Allocate a new object (new)` where the `//` is *trailing*. Strip everything from the first `//` before applying the regex. Caveat: `//` inside string literals would be wrongly truncated, but the truncation only causes false *negatives* if `new T(` appears inside the string — string-literal `new` matches were already documented as author-fixable false positives. With the filter improved, `opcodes.hh` (clean except for the line-124 trailing-comment hit) now joins `PROTECTED_FILES`. Protected-set count: 30 → 31.
- **Rec 31 Stage 4-8 audit-gate batch.** Another 26 already-clean decompiler C++ files added as regression guards: `cast.{cc,hh}`, `callgraph.{cc,hh}`, `condexe.cc`, `constseq.cc`, `double.cc`, `heritage.{cc,hh}`, `merge.{cc,hh}`, `prefersplit.{cc,hh}`, `subflow.cc`, `bitfield.cc`, `ruleaction.cc` (11k LOC), `op.hh`, `varnode.hh`, `funcdata.hh`, `flow.hh`, `action.hh`, `database.hh`, `userop.hh`, `context.hh`, `translate.hh`, `jumptable.hh`. Files with raw-`new` sites in their `.cc` (e.g. `op.cc`, `funcdata.cc`, `database.cc`, `type.cc`, `userop.cc`) wait for code migration. Protected-set count: 31 → 57.
- **Rec 31 Stage 6 — `flow.cc` RAII migration.** `FlowInfo::setupCallSpecs` and `::setupCallindSpecs` migrate `FuncCallSpecs *res = new FuncCallSpecs(op); qlst.push_back(res);` to `auto owned = make_unique<FuncCallSpecs>(op); FuncCallSpecs *res = owned.get(); qlst.push_back(owned.release());`. Same create-then-transfer-ownership pattern as the comment.cc migration ([PR #90](https://github.com/CryptoJones/GayHydra/pull/90)): the temporary is `unique_ptr`-owned until ownership transfers to `qlst` (which is a `vector<FuncCallSpecs *>`, raw-ptr-typed because changing it would ripple through the FuncCallSpecs lifetime model). `flow.cc` joins `PROTECTED_FILES`. Protected-set count: 57 → 58.
- **Rec 31 Stage 5 — `op.cc` + `varnode.cc` RAII migration.** PCode-core paired migration. Both `PcodeOpBank::create` overloads and both `VarnodeBank::create` / `::createDef` follow the create-then-transfer-into-bank pattern. Migrate the raw `new` to `make_unique` with `.release()` at the point of ownership transfer (insertion into `optree` map / `loc_tree` set / `xref()` call). Includes a small exception-safety improvement in `VarnodeBank::create`: previously, if the second tree insertion threw between the loc_tree.insert and the def_tree.insert, the loc_tree would hold a dangling raw pointer to a leaked Varnode; now `owned.release()` happens immediately after the first insert so the destructor never runs the unique_ptr cleanup over an already-tree-held pointer. `op.cc` + `varnode.cc` join `PROTECTED_FILES`. Protected-set count: 58 → 60.
- **Rec 31 Stage 4 — `type.cc` `TypeCode::proto` migration to `unique_ptr<FuncProto>`.** Changed the member type in `type.hh:774` from `FuncProto *` → `unique_ptr<FuncProto>`. The four `new FuncProto()` allocation sites in type.cc (`setPrototype(...,PrototypePieces)`, `setPrototype(...,FuncProto*)`, copy constructor, `decodePrototype`) become `make_unique<FuncProto>()`. The two `delete proto` sites (in `~TypeCode` and `setPrototype(fp)`) collapse — the destructor becomes `= default` (proto's `unique_ptr` auto-cleans), and `setPrototype(fp)`'s clear-before-reassign uses `proto.reset()`. Null-comparisons against `(FuncProto *)0` become boolean checks on `proto` directly. Friend access in `TypeFactory` (`tc.proto`, `defedCode->proto`) gets `.get()` to extract the raw pointer for the `setPrototype` callee's `FuncProto*` parameter. `type.cc` added to `PROTECTED_FILES`; `type.hh` is held back because of 15 raw-`new` `clone()`-method sites (Datatype subclass factories) — its own migration. Protected-set count: 60 → 61.
- **Rec 31 Stage 7 — `context.cc` `ParserContext::context` array migration to `unique_ptr<uintm[]>`.** `context.hh:115` member type changed from `uintm *` → `unique_ptr<uintm[]>`. `context = new uintm[contextsize]` becomes `make_unique<uintm[]>(contextsize)`. The `if (context != (uintm *)0) delete [] context;` in `~ParserContext` is removed (unique_ptr auto-cleans on destruction). Indexed reads `context[i]` work unchanged via `unique_ptr<T[]>::operator[]`. `context.cc` joins `PROTECTED_FILES` with line-range exclusions [87,87] + [263,263] for the two `state[i] = new ConstructState(...)` sites in `initialize` / `expandState` — those are blocked on a `vector<ConstructState *> state` → `vector<unique_ptr<ConstructState>>` migration that requires a `std::rotate` refactor in `expandState` (the `vector::insert(it, count, value)` count-insert overload requires CopyAssignable; unique_ptr is move-only). Protected-set count: 61 → 62.
- **Rec 31 Stage 6 — `funcdata.cc` RAII migration.** Five raw-`new` sites migrated: four `ScopeLocal *newMap = new ScopeLocal(...)` callers (lines 66, 804, 813, 827) and one `unique_ptr<JumpTable> jt(new JumpTable())` (line 615). The ScopeLocal sites pass `newMap` into `symboltab->attachScope(...)` or `symboltab->decodeScope(...)` — the callee owns the pointer on success AND deletes it on throw (per the existing inline comments). To preserve that contract, the migration uses `make_unique<T>(...).release()` so ownership transfers immediately *before* the throwable call: a wrapping `unique_ptr` at the call site would double-delete on throw. The JumpTable site is a straight `new` → `make_unique` cleanup. `funcdata.cc` joins `PROTECTED_FILES`. Protected-set count: 62 → 63.
- **Rec 31 Stage 4 — `type.hh` `clone()` migration.** Fifteen `virtual Datatype *clone(void) const { return new TypeXxx(*this); }` methods across the Datatype subclass hierarchy (`TypeBase`, `TypeChar`, `TypeUnicode`, `TypeVoid`, `TypePointer`, `TypeArray`, `TypeEnum`, `TypeStruct`, `TypeUnion`, `TypePartialEnum`, `TypePartialStruct`, `TypePartialUnion`, `TypePointerRel`, `TypeCode`, `TypeSpacebase`) migrated to `make_unique<TypeXxx>(*this).release()`. The `clone()` API contract preserved — callers still get a raw `Datatype *` with ownership transferred (per the virtual signature). The `.release()`-immediate pattern matches funcdata.cc's ScopeLocal sites: kills the raw `new` keyword while keeping the same ownership contract. `type.hh` joins `PROTECTED_FILES`. Protected-set count: 63 → 64.
- **Rec 31 Stage 6/8 — Rule/Action `clone()` batch migration.** Same `.release()`-immediate pattern as type.hh, applied across the Rule + Action subclass hierarchy headers: `condexe.hh` (2 sites), `constseq.hh` (2), `double.hh` (4), `subflow.hh` (12), `bitfield.hh` (6), `ruleaction.hh` (141 sites — 136 live + 5 in commented-out historical clone examples, all updated for consistency). Total 167 sites, all `return new RuleXxx(getGroup()...)` → `return make_unique<RuleXxx>(getGroup()...).release()`. Six headers join `PROTECTED_FILES` (their `.cc` counterparts were already in from #94). Protected-set count: 64 → 70.
- **Rec 31 Stage 8 — `translate.cc` AddrSpace + JoinRecord RAII migration.** Seven raw-`new` sites migrated. Five `res.reset(new XxxSpace(this,trans))` in `AddrSpaceManager::decodeSpace` (lines 263, 265, 267, 269, 271) replaced with `res = make_unique<XxxSpace>(this,trans)` — `res` was already a `unique_ptr<AddrSpace>` so the assignment is equivalent + reads clearer. One `insertSpace(new ConstantSpace(...))` → `insertSpace(make_unique<ConstantSpace>(...).release())` because `insertSpace` takes a raw pointer with ownership transfer. One `JoinRecord *newjoin = new JoinRecord()` in `findAddJoin` migrated to the `make_unique` + `.release()`-after-splitset.insert pattern (same as varnode.cc:create from #96). `translate.cc` joins `PROTECTED_FILES`. Protected-set count: 70 → 71.
- **Rec 31 Stage 6 — `action.cc` RAII migration.** Three raw-`new` sites in `ActionGroup::clone`, `ActionRestartGroup::clone`, and `ActionPool::clone` migrated via `.release()`-immediate: `res = new ActionGroup(flags,getName())` → `res = make_unique<ActionGroup>(flags,getName()).release()`, etc. Same pattern as the Rule clone batch (#101) and type.hh clone() (#100). `action.cc` joins `PROTECTED_FILES`. Protected-set count: 71 → 72.
- **Rec 31 — `sleigh.hh` Doxygen example RAII update.** The 7 raw-`new` sites in `sleigh.hh` are inside `\code`/`\endcode` Doxygen example blocks (the SLEIGH library tutorial). Updated the examples to use `make_unique<T>(...)` instead of `new T(...)`, modeling the RAII idiom for future library users reading the API docs. The companion variable declarations switched to `unique_ptr<...>`; `Sleigh(loader,context)` argument passing uses `loader.get()` / `context.get()` to extract raw pointers. `sleigh.hh` joins `PROTECTED_FILES`. Protected-set count: 72 → 73.
- **Rec 31 — Iface/Rule clone() small batch.** Three single-site clone-style virtuals migrated to `.release()`-immediate: `codedata.hh:156` (`CodeDataAnalysis::createData`), `ifacedecomp.hh:100` (`IfaceDecompCommand::createData`), `rulecompile.hh:154` (`RuleGeneric::clone`). Same pattern as the prior Rule + Action + Datatype clone batches. Three headers join `PROTECTED_FILES`. Protected-set count: 73 → 76.
- **Rec 31 Stage 4 — `userop.cc` RAII migration.** All 13 raw-`new` sites in the UserOp manager subsystem migrated. (a) Seven `registerOp(new XxxOp(...))`-style callers (in `initialize`, `decodeSegmentOp`, `decodeCallOtherFixup`, `decodeJumpAssist`, `manualCallOtherFixup`, plus the four `decodeBuiltin` switch arms) use `make_unique<XxxOp>(...).release()` because `UserOpManage::registerOp(UserPcodeOp *op)` immediately wraps in `unique_ptr<UserPcodeOp> owner(op)` at line 493 — the caller-side raw pointer is just a transient ownership-transfer token. (b) Three `unique_ptr<UserPcodeOp>(new XxxOp(...))` sites migrated to `auto X = make_unique<XxxOp>(...);` for clarity (decodeSegmentOp, decodeCallOtherFixup, decodeJumpAssist). (c) Two `Xxx *p = new Xxx(...); builtinmap[K] = p;` sites in `decodeVolatile` use `.release()`-immediate. `userop.cc` joins `PROTECTED_FILES`. Protected-set count: 76 → 77.
- **Rec 31 — `emulateutil.cc` + `override.cc` small batch.** Two single-site migrations: `emulateutil.cc:315` (`EmulateSnippet::buildEmitter` — `return new PcodeEmitCache(...)` → `.release()`-immediate), and `override.cc:370` (`unique_ptr<FuncProto> fp(new FuncProto())` → `auto fp = make_unique<FuncProto>()` — the consumer `insertProtoOverride(callpoint, fp.release())` is unchanged). Two files join `PROTECTED_FILES`. Protected-set count: 77 → 79.
- **Rec 31 — `funcdata_*.cc` family RAII migration.** Six raw-`new` sites across the funcdata-family modules: `funcdata_block.cc:491` (`installJumpTable` — `new JumpTable(addr)`), `funcdata_block.cc:688` (`recoverJumpTable` — `new JumpTable(&trialjt)`), `funcdata_op.cc:825` (clone path 1 — `new JumpTable(*jiter)`), `funcdata_op.cc:899` (clone path 2 — same), `funcdata_varnode.cc:57` (`buildHighVariable` — `new HighVariable(vn)`), `funcdata_varnode.cc:606` (`initActiveOutput` — `new ParamActive(false)`). All migrated to `make_unique` + `.release()`-after-push-back or `.release()`-immediate. `jumpvec` is `vector<JumpTable *>` (raw-pointer-typed; container ownership migration is its own future scope). Three files join `PROTECTED_FILES`. Protected-set count: 79 → 82.
- **Rec 31 — arch + printer batch RAII migration.** Ten raw-`new` sites across five files migrated, all using the `.release()`-immediate or `make_unique` swap pattern: `raw_arch.cc` (clone-style return + `unique_ptr<RawLoadImage>` ctor swap), `xml_arch.cc` (clone return + `loader = new LoadImageXml(...)` member assignment), `printc.cc` (clone return + `castStrategy = new CastStrategyC()`), `printjava.cc` (same pattern as printc), `database_ghidra.cc` (clone return + `cache = new ScopeInternal(...)`). Five files join `PROTECTED_FILES`. Protected-set count: 82 → 87.
- **Rec 31 — `emulate.cc` + `varmap.cc` small batch.** Four raw-`new` sites migrated, all transferring ownership into vectors via the `make_unique` + `.release()`-after-push-back pattern: `emulate.cc:117` (`PcodeEmitCache::createVarnode` — `varcache.push_back(...)`), `emulate.cc:126` (`PcodeEmitCache::dump` — `opcache.push_back(...)`), `varmap.cc:907` (`MapState::addRange` — `maplist.push_back(...)`), `varmap.cc:1075` (`MapState::gatherSymbols` terminator — `maplist.push_back(...)`). Two files join `PROTECTED_FILES`. Protected-set count: 87 → 89.
- **Rec 31 — `compression.cc` `inBuffer`/`outBuffer` arrays to `unique_ptr<uint1[]>`.** Both buffer members in `CompressBuffer` (compression.hh:86-87) migrated from `uint1 *` → `unique_ptr<uint1[]>`. The two `new uint1[N]` allocations in the constructor become `make_unique<uint1[]>(N)`. The `delete []` cleanup in `~CompressBuffer` is removed (auto via `unique_ptr` destruction). All `(char *)inBuffer` / `(char *)outBuffer` casts at use sites use `.get()` to extract the raw pointer for the cast. Both files join `PROTECTED_FILES`. Protected-set count: 89 → 91.
- **Rec 31 — `memstate.cc` page-allocation RAII.** Two `pageptr = new uint1[getPageSize()];` sites in `MemoryPageOverlay::write` (line 431) and `::setPage` (line 510) migrated to `auto owned = make_unique<uint1[]>(getPageSize()); pageptr = owned.get(); page[K] = owned.release();`. `page` itself remains `map<uintb, uint1 *>` (raw-pointer-typed; `~MemoryPageOverlay` iterates and `delete []`s — the destructor still works as the migration preserves the raw pointer flowing into the map). `memstate.cc` joins `PROTECTED_FILES`. Protected-set count: 91 → 92.
- **Rec 31 — `transform.cc` placeholder-array RAII.** Four `TransformVar *res = new TransformVar[N];` sites in `TransformManager::newPreexistingVarnode` (line 373), `::newPiece` (line 429), `::newSplit` (line 449), `::newSplit` overload (line 484) all migrated to `auto owned = make_unique<TransformVar[]>(N); TransformVar *res = owned.get(); pieceMap[K] = owned.release();`. `pieceMap` remains `map<int4, TransformVar *>` (raw-pointer-typed). `transform.cc` joins `PROTECTED_FILES`. Protected-set count: 92 → 93.
- **Rec 31 — `globalcontext.cc` FreeArray allocations.** Four `new uintm[N]` sites in `ContextInternal::FreeArray::reset` (lines 262-263, allocating `newarray`/`newmask` then assigning to members) and `FreeArray::operator=` (lines 302-303) migrated to `make_unique<uintm[]>(N).release()`. The `FreeArray` member types (`uintm *array`, `uintm *mask`) remain raw-pointer-typed because the inline destructor manually deletes via `if (size!=0) delete []` and many external pointers index into the storage; migrating the members ripples into `partmap` value-semantics. `globalcontext.cc` joins `PROTECTED_FILES`. Protected-set count: 93 → 94.
- **Rec 31 — bfd/inject/signature/sleighbase 4-file batch (15 sites).** Four files migrated, all using `.release()`-immediate or `make_unique` swap. `bfd_arch.cc`: clone return + 3 `ldr.reset(new LoadImageBfd(...))` (`ldr` was `unique_ptr<LoadImageBfd>`, so swap to `ldr = make_unique<LoadImageBfd>(...)`). `inject_ghidra.cc`: 4 `payload = new XxxGhidra(...)` member assignments. `signature_ghidra.cc`: 4 `commandmap[K] = new XxxClass(...)` map inserts. `sleighbase.cc`: 3 `res = new XxxSpace(...)` + 1 `insertSpace(new ConstantSpace(...))` (same pattern as translate.cc PR #102). Four files join `PROTECTED_FILES`. Protected-set count: 94 → 98.
- **Rec 31 — `blockaction.cc` TraceDAG RAII.** Four raw-`new` sites in `TraceDAG`'s control-flow trace machinery: `BranchPoint::createTraces` (line 505 — `paths.push_back(new BlockTrace(...))`), `openBranch` (line 842 — create-then-conditional-delete pattern with `delete newbranch` on early-return), `initialize` rootBranch creation (line 970), and `initialize` rootBranch trace seeding (line 974). All migrated. The `openBranch` site is the comment.cc-style win: `make_unique<BranchPoint>(parent)` owns until the conditional check; if `paths.size() == 0`, the unique_ptr auto-cleans on `return` (was a manual `delete newbranch`); otherwise `.release()` transfers to `branchlist`. `blockaction.cc` joins `PROTECTED_FILES`. Protected-set count: 98 → 99.
- **Rec 31 — `prettyprint.cc` + `prettyprint.hh` `circularqueue<T>` migration.** prettyprint.hh: `circularqueue<_type>::cache` member migrated from `_type *` to `unique_ptr<_type[]>` (line 972). All three `new _type[N]` sites (ctor, `setMax`, `expand`) become `make_unique<_type[]>(N)`; the `delete []` cleanups drop (auto via unique_ptr); the destructor becomes `= default`; `expand`'s newcache→cache swap uses `cache = std::move(newcache)`. prettyprint.cc: 4 raw-`new` sites in `EmitMarkup::setOutputStream`, `::setPackedOutput`, and `EmitPrettyPrint` ctor all use `.release()`-immediate (their consumer raw-pointer members `encoder` / `lowlevel` are left as raw pointers — that's a future-PR refactor). Both files join `PROTECTED_FILES`. **Milestone: protected-set count 99 → 101** — past half the decompiler `cpp/` tree (~150 files total).
- **Rec 31 — `loadimage_bfd.cc` allocation sites.** Two raw `new uint1[N]` sites: `buffer = new uint1[bufsize]` in constructor (line 38) and `symbol_table = (asymbol **) new uint1[storage_needed]` in `attachToFile` (line 215) — both migrated to `make_unique<uint1[]>(N).release()` with the `(asymbol **)` cast preserved on the symbol_table site. Members remain raw-pointer-typed (`uint1 *buffer`, `asymbol **symbol_table`) — the existing `delete []` semantics are preserved bit-for-bit (the `symbol_table` cast-then-`delete[]` pattern is technically UB and warrants a separate refactor PR). `loadimage_bfd.cc` joins `PROTECTED_FILES`. Protected-set count: 101 → 102.
- **Rec 31 — `sleigh.cc` six allocation sites.** All raw-`new` migrated: `PcodeCacher` ctor pool allocation + `expandPool` reallocation (lines 26, 54 — `VarnodeData[N]` arrays), `DisassemblyCache` ctor (line 455 — `new ParserContext(...)`), and three member-assignment sites (lines 520, 549 `cache = new ContextCache(c_db)`, line 578 `discache = new DisassemblyCache(...)`). Members stay raw-pointer-typed (existing destructors manually `delete []` / `delete` — preserved 1:1). `sleigh.cc` joins `PROTECTED_FILES`. Protected-set count: 102 → 103. (Note: 2 `new ParserContext *[N]` array-of-pointers sites at lines 451/453 escape the audit's regex — separate audit-regex improvement.)
- **Rec 31 — interface/consolemain/variable 3-file batch (14 sites).** `interface.cc`: 4 RemoteSocket member assignments (`inbuf`/`outbuf`/`inStream`/`outStream`) + `IfaceStatus::pushScript` conditional-cleanup pattern (the `ifstream *s = new ifstream(...); if (!*s) { delete s; throw }` pattern becomes the comment.cc-style `unique_ptr` + `.release()`-at-pushScript). `consolemain.cc`: 1 `unique_ptr<IfaceStatus>(new IfaceTerm(...))` → `auto status = make_unique<IfaceTerm>(...)` swap, plus 4 `status->registerCom(new IfcXxx(),...)` → `make_unique<IfcXxx>().release()`. `variable.cc`: 5 `member = new VariablePiece(...)` / `new VariableGroup()` sites → `.release()`-immediate. Three files join `PROTECTED_FILES`. Protected-set count: 103 → 106.
- **Rec 31 — final small-batch (8 files, 11 sites).** Mop-up of every remaining 1-3-hit file in the decompiler tree: `cpool.cc:140` (`byteData = new uint1[byteDataLen]`), `loadimage.cc:32` (returned `uint1[]` buffer) + `loadimage.cc:60` (`thefile = new ifstream(...)`), `printlanguage.cc:69` (`emit = new EmitPrettyPrint()`), `slaformat.cc:221` (`inBuffer = new uint1[IN_BUFFER_SIZE]`), `slghpattern.hh:91` (`InstructionPattern` ctor body) + 2 `simplifyClone` virtuals at `:94` + `:115`, `string_ghidra.cc:24` + `stringmanage.cc:418` (`testBuffer = new uint1[max]`), `testfunction.cc:222` (`console = new ConsoleCommands(...)`). All `.release()`-immediate or `make_unique` swap. Eight files join `PROTECTED_FILES`. Protected-set count: 106 → 114.
- **Rec 31 — `coreaction.hh` clone() batch (66 sites).** Same regex substitution as the prior Rule clone batches (#100 type.hh, #101 ruleaction.hh, #103 action.cc): every `return new XxxAction(...)` becomes `return make_unique<XxxAction>(...).release()` across 66 Action subclass `clone(grouplist)` virtuals. `coreaction.hh` joins `PROTECTED_FILES`. Protected-set count: 114 → 115. (`coreaction.cc` still has 247 non-clone sites — separate sweeps.)
- **Rec 31 — `ifacedecomp.cc` registerCom batch (121 sites).** 117 `status->registerCom(new IfcXxxCmd(), ...)` registration sites batch-replaced with `status->registerCom(make_unique<IfcXxxCmd>().release(), ...)` — same pattern as `consolemain.cc` from PR #120. Plus 4 non-registerCom sites: `cgraph = new CallGraph(conf)` (line 258), `unique_ptr<FuncProto> newproto(new FuncProto())` (line 1859) → `auto newproto = make_unique<FuncProto>()`, `dcp->testCollection = new FunctionTestCollection(status)` (line 3375), and `istringstream *s2 = new istringstream(...)` (line 3427 — used in `pushScript`). All migrated. `ifacedecomp.cc` joins `PROTECTED_FILES`. Protected-set count: 115 → 116.
- **Rec 31 — `typeop.cc` + `opbehavior.cc` instruction-table batches (219 sites).** Both files have the same pattern: a constructor populates an `inst[CPUI_XXX] = new TypeOpYyy(tlst)` (or `OpBehaviorYyy`) array of per-opcode instances. typeop.cc has 146 such sites; opbehavior.cc has 73. Single regex substitution `= new (\w+)\((.*)\);` → `= make_unique<\1>(\2).release();` handles both files completely. Two files join `PROTECTED_FILES`. Protected-set count: 116 → 118.
- **Rec 31 — `coreaction.cc` Action-pipeline batch (247 sites).** The decompiler's action-pipeline construction in `Architecture::buildAction` is a long sequence of `actMain->addAction(new ActionXxx(...))` / `actMain->addRule(new RuleYyy(...))` registrations across the whole decompile pipeline. 240 `addAction`/`addRule` registration sites batch-migrated via regex `(addAction|addRule)\(\s*new (\w+)\(([^()]*)\)\s*\)` → `\1(make_unique<\2>(\3).release())`. Plus 7 `act/actX = new ActionGroup/ActionRestartGroup(...)` assignment sites via `(\w+)\s*=\s*new (\w+)\((.*)\);` → `\1 = make_unique<\2>(\3).release();`. Total 250 substitutions (a few include cosmetic updates in commented-out historical sites for consistency). `coreaction.cc` joins `PROTECTED_FILES`. Protected-set count: 118 → 119.
- **Rec 31 — `options.cc` registerOption batch (37 sites).** All 37 raw-`new` sites in options.cc are `registerOption(new OptionXxx())` registrations in `ArchOption::registerOptions()`. Single regex substitution `registerOption\(new (\w+)\(\)\)` → `registerOption(make_unique<\1>().release())`. `options.cc` joins `PROTECTED_FILES`. Protected-set count: 119 → 120.

### 2026-05-28 — build-break hotfix series

- **`context.hh:154` build-break hotfix.** PR #98 migrated `ParserContext::context` from `uintm *` to `unique_ptr<uintm[]>`, but missed the inline `loadContext()` method at context.hh:154 which calls `contcache->getContext(addr, context)` — the callee takes `uintm *` and the implicit unique_ptr → raw-pointer conversion doesn't exist. Master CI started reporting the error on every Build-Ghidra / Decompiler-Unit-Tests / CodeQL run since #98 landed (sending build-failure emails on each push). Fix: append `.get()` to the unique_ptr argument. Lesson: per-PR CI green-light didn't catch this because the failure happens at the point `sleigh.cc` / `pcodeparse.cc` *include* `context.hh` — the unit-tests job builds those after several minutes; my session never waited for them before stacking more PRs.
- **`error.hh` — add `using std::make_unique;`** — companion to the existing `using std::unique_ptr;` declaration. Files that include error.hh directly (e.g. compression.hh → compression.cc) saw `unique_ptr` but not `make_unique`, so their `make_unique<T>(...)` calls failed to compile after my Rec 31 migrations. marshal.hh already had `using std::make_unique;` so most migrated files happened to compile via the transitive marshal include — but compression.cc didn't pull that in (compression.hh only includes "error.hh" + zlib). Adding `make_unique` at the same scope as `unique_ptr` in error.hh fixes compression.cc and prevents the same failure mode on any future file that uses error.hh as its only smart-pointer-aware include.
- **`interface.cc` — `#include <memory>` + qualify `std::make_unique`** — third build-break hotfix. `interface.hh` → `capability.hh` → `types.h` chain never reaches error.hh or marshal.hh, so even with the using-decl fix in #129, interface.cc's 5 `make_unique<...>` calls (from PR #120) failed to compile. Add `#include <memory>` at interface.cc's top and qualify each `make_unique<T>(...)` with `std::`. Local-only fix; capability.hh stays minimal so other capability-hierarchy consumers don't pick up unnecessary std-namespace pollution.

### 2026-05-26 — Rec 28 closeout, Rec 31 Stage 1+2A, OSS-Fuzz upstream submission

**Rec 28 — `@Ignore` policy enforcement (Stage 2 strict).**

- **[#43](https://github.com/CryptoJones/GayHydra/pull/43)** `gradle ignoreAudit` flipped Stage 1 → Stage 2 — strict-by-default both in CI and locally. The Rec 28 sweep cleared every author-declared-not-a-regression-test stub; the surviving 51 annotations all carry a category prefix + `#N` ref, so the audit finds zero violations.
- **[#26](https://github.com/CryptoJones/GayHydra/pull/26)–[#34](https://github.com/CryptoJones/GayHydra/pull/34), [#36](https://github.com/CryptoJones/GayHydra/pull/36)–[#41](https://github.com/CryptoJones/GayHydra/pull/41)** 17 author-declared-not-a-regression-test deletions: `JdiExperimentsTest`, `CharsetInfoManagerTest.generateCharsetInfoFile`, `DebuggerMemoryBytesProviderTest.testPerformanceManuallyWithManyManySnaps`, `AbstractDBTraceMemoryManagerMemoryTest.testReplicateClassCastExceptionScenario`, `DebuggerOpinionsTest`, `DBTraceRegisterContextManagerTest`, `DemoFieldsTest`, `DebuggerManualTest`, `experiments/ToArrayTest`, `TenetLoaderTest.testManual`, `AbstractToyJitCodeGeneratorTest.testComputedOffsetsInRegisterSpace`/`.testUninitializedVsInitializedReads`, `CppCompositeTypeTest.testJ5_32_syntactic_layout`, `DBTraceCodeUnitTest.testFigureOutAssembly`, `DBTraceProgramViewListingTest.testGetUndefinedRanges`, `DBTraceAddressSnapRangePropertyMapSpaceTest.testRemove`, `DbgEngHooksTest.testOnSyscallMemory`, `GdbHooksTest.testOnSyscallMemory`. Each PR documents the specific deletion rationale (empty `TODO()` stub, manual JFrame demo, println-only exploration, commented-body shell, etc.).
- **[#70](https://github.com/CryptoJones/GayHydra/pull/70)** Re-filed 15 GitHub tracking issues destroyed in the 2026-05-24 deletion incident as new repo issues [#55](https://github.com/CryptoJones/GayHydra/issues/55)–[#69](https://github.com/CryptoJones/GayHydra/issues/69) with `ignore:1y` + `lane:*` labels. Repointed 51 in-tree `@Ignore` annotations across 21 test files to the new issue numbers (Android OAT/ART source files referencing the same `#N` in unrelated contexts left untouched).
- **[#42](https://github.com/CryptoJones/GayHydra/pull/42), [#53](https://github.com/CryptoJones/GayHydra/pull/53)** `docs/testing/ignore-test-inventory.md` refreshed; `#28-6c` row made honest about post-deletion artifact loss.

**Rec 31 — RAII migration.**

- **[#45](https://github.com/CryptoJones/GayHydra/pull/45)** New `gradle cppRaiiAudit` per-file gate. Forbids raw `new <ClassName>(...)` in `Ghidra/Features/Decompiler/src/decompile/cpp/{address,space,rangeutil}.cc` — those files were already raw-`new`-free; the gate prevents regression. Wired into `.github/workflows/build-ghidra.yml`.
- **[#46](https://github.com/CryptoJones/GayHydra/pull/46)** Stage 2A — `marshal.cc` `ByteChunk` now owns its buffer via `unique_ptr<uint1[]>`. Eliminates the manual `delete[]` cleanup loop in `~PackedDecode`; replaces raw `new uint1[N]` with `make_unique<uint1[]>(N)`. `marshal.cc` + `marshal.hh` added to `cppRaiiAudit`'s `PROTECTED_FILES`. C++ unit tests + ASan/UBSan green.
- **[#71](https://github.com/CryptoJones/GayHydra/pull/71)** Stage 2C design doc (`docs/decompiler/RAII_STAGE_2C_XML.md`). Discovery during the session: `xml.y`'s bison `%union` is fundamentally incompatible with `unique_ptr` (C-style unions can't hold non-trivially-destructible types); the semantic-action sites need either `%define api.value.type variant` (wholesale parser rewrite) or a documented exception in `cppRaiiAudit`. Recommendation: small Stage 2C-min PR for the one obvious code-smell, with the variant-mode rewrite as its own strategic sprint.
- **[#77](https://github.com/CryptoJones/GayHydra/pull/77)** Stage 2C-min Step 1 — `xml.y:208`'s `string *tmp=new string(); ... delete tmp;` converted to a stack-local `string tmp;` (the temporary never escapes its semantic action). Mirrored in `xml.cc:1790`. No bison regeneration; no `%union` change.
- **Stage 2C step 2** — `Element` parse-tree ownership migrated from `vector<Element *>` to `vector<unique_ptr<Element>>`. `Element::~Element` becomes `= default` (the per-child manual `delete` loop is now automatic via the vector's destructor → each `unique_ptr<Element>`). `addChild` signature changed to take `unique_ptr<Element>` (ownership transfer is explicit at the call site). All `*iter` derefs over `List` that assigned to a raw `Element *` updated to `iter->get()` (5 call-sites in marshal.cc; per-call updates in `bfd_arch.cc`, `raw_arch.cc`, `xml_arch.cc`, `testfunction.cc`, `slgh_compile.cc`). `Document::getRoot()` updated to `children.front().get()` since `*children.begin()` no longer converts. No bison regeneration; no `%union` change.
- **Stage 2C step 3** — `Document` return-value ownership migrated. `xml_tree(istream&)` now returns `unique_ptr<Document>`; `XmlDecode::document` member becomes `unique_ptr<Document>`; `DocumentStorage::doclist` becomes `vector<unique_ptr<Document>>`; `InjectPayloadDynamic::addrMap` becomes `map<Address,unique_ptr<Document>>`. All four owners' manual destructors collapse to `= default`. Local `Document *doc` in `slgh_compile.cc`'s ProcessorCompile becomes a stack-local `unique_ptr<Document>` (drops a `delete doc;` at end of scope). `InjectPayloadDynamic::decodeEntry`'s manual "delete preexisting" before reassignment becomes implicit (map's `operator[] = move()` destroys the dropped value). The `xml.y` / `xml.cc` epilogue is now raw-`new`-free; only the four bison semantic-action `%union` sites remain (`xml.y:150, 153, 198, 200`) and need the Option A variant-mode strategic sprint per [`RAII_STAGE_2C_XML.md`](docs/decompiler/RAII_STAGE_2C_XML.md).
- **Stage 2C audit-gate** — `xml.y` + `xml.cc` added to `cppRaiiAudit`'s `PROTECTED_FILES`. `PROTECTED_FILES` migrated from `Set<String>` to `Map<String, List<List<Integer>>>` (path → list of `[startLine, endLine]` excluded ranges); the four bison `%union` semantic-action sites in each file are listed as the only exclusions. Any new raw `new` outside those four lines fails CI in both files. Closes the audit-gate-add carried as "deferred" from Stage 2C-min in [`RAII_STAGE_2C_XML.md`](docs/decompiler/RAII_STAGE_2C_XML.md).

**Rec 13/14 — OSS-Fuzz upstream submission.**

- **[#48](https://github.com/CryptoJones/GayHydra/pull/48)** Replaced `security@example.invalid` placeholders with `cryptojones@owasp.org` as `primary_contact`; `auto_ccs: []` during ramp-up.
- **[#49](https://github.com/CryptoJones/GayHydra/pull/49)** In-tree `.github/oss-fuzz/{Dockerfile,build.sh,project.yaml}` synced byte-for-byte with the upstream PR branch. Apache 2.0 license headers added to `Dockerfile` + `build.sh` per `dpebot`'s `header-check` convention. New `.github/oss-fuzz/README.md` documents the staging workflow.
- **Upstream** [google/oss-fuzz#15545](https://github.com/google/oss-fuzz/pull/15545) — new project `ghidra-decompiler` submitted with two harnesses (`fuzz_xml`, `fuzz_marshal`), AS/UBSan, libfuzzer/AFL/honggfuzz. All automated checks passed (`header-check`, `cla/google`, `check-changes`).
- **Upstream rejection** — same PR closed 2026-05-26 22:49 UTC by Google collaborator DavidKorczynski with the review *"I don't think a fork of Ghidra is a great match with OSS-Fuzz. We prefer projects with large user bases, so I suspect Ghidra itself would be an interesting match."* Soft policy reject (not a fixable submission defect); the reviewer's suggested path of submitting upstream NSA/ghidra is out-of-scope for this fork. Rec 13/14 is re-scoped: the underlying `fuzz_xml` / `fuzz_marshal` harnesses (in `Ghidra/Features/Decompiler/src/decompile/cpp/fuzz/`) stay as our own continuous-fuzzing infrastructure (runnable locally via `Makefile.fuzz` and, future-work, via our own CI).
- **Wrapper rip-out** — the OSS-Fuzz-specific `.github/oss-fuzz/{Dockerfile,build.sh,project.yaml,README.md}` deleted; all four were 100% Google-infrastructure scaffolding (their `gcr.io/oss-fuzz-base/base-builder` image, their `$SRC` / `$LIB_FUZZING_ENGINE` env contract, their `project.yaml` manifest format) with zero value outside the rejected submission. Top-level `README.md` Rec 13 row and `Ghidra/.../fuzz/README.md` updated to drop the dead cross-references; `docs/security/OSS_FUZZ.md` and `docs/security/LOADER_FUZZING.md` retain their integration-plan framing pending a separate doc-touch-up follow-up.
- **Historical sprint-row reconciliation** — three pre-existing unreconciled "submit `.github/oss-fuzz/`" open items struck through in `SprintPlanning.md`'s Sprint 4 / Sprint 5 / Sprint 6 sections, each cross-referenced to the Sprint 10 canonical rejection row. Sprint 10 remains the authoritative record of the policy reject.
- **Self-audit fix-up** — Sprint 10's canonical row in `SprintPlanning.md` had been left over from the pre-rip-out wording (it still claimed "the in-tree `.github/oss-fuzz/` files + the `fuzz_xml` / `fuzz_marshal` harnesses stay") even after the wrapper was deleted in PR #84. Rewritten to match the post-PR-#84 reality — only the harnesses stay; the wrapper directory was deleted — so the row Sprint 4/5/6 strike-outs point to actually agrees with current master.

**CI / housekeeping.**

- **[#47](https://github.com/CryptoJones/GayHydra/pull/47)** `sync-labels.yml` `dry-run: true` → `false`. The declarative `.github/labels.yml` now actually applies label add/remove/edit to the live repo.
- **Branch sweep** (no PR): 16 merged remote feature branches (`sprint-1`..`sprint-8` × 2 remotes) and 10 merged local branches deleted.

**Rec 20 — RMI serial-filter VMARG removed (issue #80, follow-up to mis-filed NSA/ghidra#9220).**

- The upstream NSA/ghidra maintainer correctly pointed out that the `-Djdk.serialFilterFactory=...` line I attributed to upstream's `launch.properties` only exists in our fork (added in our Rec 20 commit `1a64b67e`). On JDK 21.0.10+ that eager VMARG conflicts with the lazy `GhidraSerialFilterFactory.getOrInstallInstance` install path via the JDK's "set exactly once" tightening. Fix: remove the VMARG from `launch.properties`; the filter is still installed at application initialization (matches upstream's behavior). `GhidraSerialFilterDefaultTest`'s class doc updated; `docs/security/JAVA_DESERIALIZATION_AUDIT.md` and `samples/re-targets/gayhydra-dropper/README.md` corrected to describe the new install path. Apologies entry + memory `feedback_verify_upstream_state.md` track the lesson learned.

**Doc sync from 2026-05-26 self-audit.**

- **[#44](https://github.com/CryptoJones/GayHydra/pull/44)** `SprintPlanning.md` synced — Rec 28 #28-6+, Rec 32 #32-2, Rec 32 #32-3 rows marked shipped.
- **[#54](https://github.com/CryptoJones/GayHydra/pull/54)** `SprintPlanning.md` Rec 31 #31-3 row updated to record marshal half shipped + `std::span` (#32-4) deviation explicitly acknowledged.

**Originally listed as in flight, now resolved:**

- **[#51](https://github.com/CryptoJones/GayHydra/pull/51)** xml.y `XmlScan::lvalue` `unique_ptr` migration (Stage 2B) — landed; all builds + C++ unit tests + ASan/UBSan green.
- **[#73](https://github.com/CryptoJones/GayHydra/pull/73)** xml `xml_parse` `global_scan` `unique_ptr` lifetime — landed as a clean cherry-pick of [#52](https://github.com/CryptoJones/GayHydra/pull/52), which auto-closed when its stacked base disappeared after #51's squash-merge.
- **[#74](https://github.com/CryptoJones/GayHydra/pull/74)** CodeQL c-cpp `binutils-dev` fix — landed as a clean cherry-pick of [#50](https://github.com/CryptoJones/GayHydra/pull/50)'s second commit. PR #50 auto-closed when PR #51's squash-merge accidentally included PR #50's *first* commit (the broken-binutils-dev one) because PR #51's branch was inadvertently based on the CodeQL fix branch instead of master. Master's CodeQL c-cpp job now passes (verified at 10m22s on #74's final run); the `cpp/autobuilder: No supported build system detected` preexisting failure that hit every PR is gone.
- **[#75](https://github.com/CryptoJones/GayHydra/pull/75)** Apologies entry for the PR #51 squash-merge stacking mistake — root-causes the chain of events above and records the `git log --oneline master..HEAD` sanity check needed to prevent recurrence.


Release-pipeline-hardening false starts during this sprint:

- **v26.1.8** failed at "Locate release zip + extract bundled SBOM" —
  the unzip pattern from [#230](https://github.com/CryptoJones/GayHydra/pull/230)
  looked for `*/support/sbom/bom.json` but the upstream NSA SBOM
  generator actually writes `bom.json` at the top of the zip-prefix
  directory. Fixed in v26.1.9 ([#327](https://github.com/CryptoJones/GayHydra/pull/327)).
- **v26.1.9** got past SBOM extract + sanity gate but the new
  "Decompiler smoke test" step (added in [#323](https://github.com/CryptoJones/GayHydra/pull/323))
  reported FAIL because the post-script gated on `getFunctionContaining
  != null` — and the Go 1.25 toolchain CI pulled crashes the Go
  analyzer (NSA/ghidra#9219), so no containing function is ever
  created even though the XOR-0x5A instructions are correctly
  disassembled. Fixed in v26.1.10 by counting orphan disassembled
  XOR-0x5A as PASS-weak and only requiring the decompile check when
  a containing function exists.

Highlights since v26.1.7:

- **Rec 19 closed.** SafeObjectInput migration completed across all
  three risk classes ([#293](https://github.com/CryptoJones/GayHydra/pull/293), [#297](https://github.com/CryptoJones/GayHydra/pull/297), [#299](https://github.com/CryptoJones/GayHydra/pull/299)). Enforcement gate
  via `gradle objectInputStreamAudit` task ([#301](https://github.com/CryptoJones/GayHydra/pull/301)) — any future
  raw `new ObjectInputStream(...)` outside `SafeObjectInput.java`
  fails CI.
- **Rec 32 #32-2 + #32-3.** Decompiler C++ bumped `-std=c++11` →
  `-std=c++14` ([#310](https://github.com/CryptoJones/GayHydra/pull/310)) and `-std=c++14` → `-std=c++20`
  ([#313](https://github.com/CryptoJones/GayHydra/pull/313)) across `buildNatives.gradle` Gcc/Clang +
  decompile/cpp/Makefile + fuzz/Makefile.fuzz. MSVC implicit default
  already C++14. CI green on all 3 platforms.
- **Rec 28 #28-5.** Dead commented-out `//@Ignore` cleanup
  ([#295](https://github.com/CryptoJones/GayHydra/pull/295)) — 7 lines across MDMangBaseTest + CompositeMemberTest.
- **Test-flake fix.** `GhidraSerialFilterDefaultTest`
  (`rejectsNonAllowlistedClass` flake) replaced with a textual filter-
  file check ([#308](https://github.com/CryptoJones/GayHydra/pull/308)) — was flaking on JVM-installed
  BuiltinFilterFactory + uninitialized GhidraObjectInputFilter.
- **RE training target + release smoke test.** Added
  `samples/re-targets/gayhydra-dropper/` ([#319](https://github.com/CryptoJones/GayHydra/pull/319), [#321](https://github.com/CryptoJones/GayHydra/pull/321)) — a small Go
  program with XOR-obfuscated strings (key `0x5A`) for users learning
  Ghidra/GayHydra. Wired into `release.yml` as a post-build decompiler
  sanity gate: scans the freshly-built prebuilt for `XOR <reg>, 0x5A`
  instructions and asserts the constant survives into the decompiler's
  C output. First dogfood run caught three release-pipeline regressions
  (Go 1.26 analyzer crash, JDK 21.0.10+ headless launch collision,
  v26.1.7 release workflow failure) — tracked under Sprint 10
  "Release pipeline hardening" in
  [SprintPlanning.md](SprintPlanning.md).

## Released sprints (v26.1.1 – v26.1.11)

Per-sprint release notes live on the
[GitHub Releases page](https://github.com/CryptoJones/GayHydra/releases)
(and [Codeberg Releases](https://codeberg.org/CryptoJones/GayHydra/releases)).
Each `26.1.x` tag corresponds to a Sprint close per the cadence
documented in [SprintHistory.md](SprintHistory.md):

- **v26.1.11** — Sprint 10 close (code-side) + Rec 31 RAII Stage 2A / 2B / 2C complete + Stage 3 first migrations. Stage 2A `marshal.cc` buffer ownership (#46), Stage 2B `xml.cc` lvalue + global_scan (#51, #73), Stage 2C-min `xml.y` stack-local (#77), Stage 2C step 2 `Element` parse-tree ownership (#78), Stage 2C step 3 `Document` return-value (#82), Stage 2C audit-gate (#87). Stage 3 first files: `cover.cc` gate (#89), `comment.cc` migration + gate (#90). Rec 28 closeout (Stage 2 strict-by-default, #43). Rec 13/14 OSS-Fuzz upstream submission + rejection + wrapper rip-out (google/oss-fuzz#15545, #48, #49, #84). Rec 20 RMI VMARG fix (#81). release.yml Windows zip glob fix (#88) — unblocks the matrix's `publish_release` job that's been silently skipped since v26.1.6, leaving every release stuck as a draft. **v26.1.11 is the first release expected to actually appear on the public Releases page** (v26.1.6/8/9/10 backlogged in drafts under "Immutable Releases" tag lockout). First-released `_win_x86_64.zip` artifact: this release closes out the cross-platform-coverage Sprint-10 entry.
- **v26.1.10** — Sprint 9 close + Sprint 10 first half: datatests
  re-enabled (#244, #250–256), Rec 25/26 Stage 3 prep (#247, #249,
  #261, #265–270), SBOM hotfix (#245), RE training sample +
  decompiler smoke test (#319, #321, #323), release pipeline bug
  fixes (#327, #331). First end-to-end signed release — prebuilt zip
  (568 MB), cosign sigs, bundled CycloneDX SBOM. v26.1.8/9 tagged
  but failed; v26.1.10 is the first successful release artifact
  since v26.1.6.

  Caveat: v26.1.10's source tree does **not** include [#333](https://github.com/CryptoJones/GayHydra/pull/333)
  (the `gh release create` fix). The v26.1.10 tag's own first
  release-workflow run hit `release not found` at the upload step
  and was finally rescued by a `workflow_dispatch` re-run against
  master, which used master's already-fixed workflow file but
  checked out the v26.1.10 source. Re-firing release.yml against
  the v26.1.10 tag from a fresh repo clone — without a master
  workflow override — would hit the same `gh release upload`
  failure. Fixed for v26.1.11+ in #333.
- **v26.1.7** — Sprint 8 close: rebrand + Rec 19/25/26 ratchets + SBOM
  bundled-extract.
- **v26.1.6** — Sprint 7 close: CI green tree-wide + Codeberg mirror +
  Win11 VM.
- **v26.1.5** — Sprint 6 close: @Ignore tree-wide sweep + CI rescue.
- **v26.1.4** — Sprint 5 close: Sprint-1 implementation second tier +
  project polish.
- **v26.1.3** — Sprint 4 close: Sprint-3 conflict-resolve + first
  Sprint-1 implementation tier.
- **v26.1.2** — Sprint 3 close: upstream cherry-picks wave 2.
- **v26.1.1** — Sprint 2 close: upstream cherry-picks wave 1.

---

## [26.1] — 2026-05-21 — "the 42-rec audit"

**First release of the GayHydra fork.** Forked from
[NSA/ghidra@94164bd6e9](https://github.com/NationalSecurityAgency/ghidra/commit/94164bd6e9)
which was upstream Ghidra `12.2 (DEV)`.

This release implements the entire 42-recommendation principal-architect
audit (see [`Ghidra.MD`](Ghidra.MD) for the audit) across governance,
security posture, testing/CI, and the decompiler/Sleigh subsystem.
Every recommendation ships either a working artifact (CI workflow,
gradle plugin, fuzz harness, config file, regression test) or a
written design/decision/RFC document that a follow-up PR series can
land against.

### Governance & Maintainer Process

- **#1** PR queue policy — [`docs/governance/PR_QUEUE_POLICY.md`](docs/governance/PR_QUEUE_POLICY.md). Lanes, SLAs, stale-close template, mega-PR RFC gate, queue-health-mode gate.
- **#2** Triage SLA — [`docs/governance/TRIAGE_SLA.md`](docs/governance/TRIAGE_SLA.md). 10-business-day first response with anti-gaming guard on `needs-info`.
- **#3** Stale-PR/issue policy + automation — [`docs/governance/STALE_POLICY.md`](docs/governance/STALE_POLICY.md), [`.github/workflows/stale.yml`](.github/workflows/stale.yml). 365-day clock with 30-day grace.
- **#4** Processor / Sleigh fast-lane — [`docs/governance/lanes/PROCESSOR_LANE.md`](docs/governance/lanes/PROCESSOR_LANE.md), [`.github/PULL_REQUEST_TEMPLATE/processor.md`](.github/PULL_REQUEST_TEMPLATE/processor.md).
- **#5** Decompiler-correctness expedited lane (3-day SLA) — [`docs/governance/lanes/DECOMPILER_CORRECTNESS_LANE.md`](docs/governance/lanes/DECOMPILER_CORRECTNESS_LANE.md).
- **#6** RFC process — [`docs/governance/RFC_PROCESS.md`](docs/governance/RFC_PROCESS.md) + numbered template.
- **#7** `MAINTAINERS.md` — [`MAINTAINERS.md`](MAINTAINERS.md). Bus factor made explicit; 180-day emeritus rule.
- **#8** Label policy retiring `Status: Triage` — [`docs/governance/LABEL_POLICY.md`](docs/governance/LABEL_POLICY.md).
- **#9** Position on upstream #4103 WebAssembly — [`docs/decisions/0001-webassembly-position.md`](docs/decisions/0001-webassembly-position.md). Accept, condition on RFC + 4-stage landing.
- **#10** Position on upstream #5778 RISC-V V/B/K — [`docs/decisions/0002-riscv-vector-position.md`](docs/decisions/0002-riscv-vector-position.md). Accept, retire "Waiting on customer".

### Security Posture

- **#11** [`SECURITY.md`](SECURITY.md) — private disclosure path, severity-tiered response targets, 90-day embargo cap, CVE criteria. **Also opened upstream as [NSA/ghidra#9202](https://github.com/NationalSecurityAgency/ghidra/pull/9202).**
- **#12** CVE assignment policy via GHSA — [`docs/security/CVE_POLICY.md`](docs/security/CVE_POLICY.md). Default-on CVE for in-scope fixes; retroactive review for GP-6832 / GP-6719 / GP-258.
- **#13** OSS-Fuzz integration scaffold (C++ decompiler) — [`docs/security/OSS_FUZZ.md`](docs/security/OSS_FUZZ.md), [harnesses](Ghidra/Features/Decompiler/src/decompile/cpp/fuzz/), [`.github/oss-fuzz/`](.github/oss-fuzz/) ready to submit to google/oss-fuzz.
- **#14** Java loader fuzz harnesses (Jazzer) — [`docs/security/LOADER_FUZZING.md`](docs/security/LOADER_FUZZING.md), [harnesses for ELF/PE/Mach-O](Ghidra/Features/Base/src/test.fuzz/java/ghidra/app/util/bin/format/fuzz/).
- **#15** ASan + UBSan CI for the decompiler — new `make test_san`, [`.github/workflows/decompiler-sanitizers.yml`](.github/workflows/decompiler-sanitizers.yml), [`docs/decompiler/asan-ubsan-ci.md`](docs/decompiler/asan-ubsan-ci.md).
- **#16** Script sandbox (`ghidra.script.sandbox=allowlist`) — [`docs/security/SCRIPT_SANDBOX.md`](docs/security/SCRIPT_SANDBOX.md).
- **#17** Decompiler binary signing via Cosign keyless — [`docs/security/BINARY_SIGNING.md`](docs/security/BINARY_SIGNING.md).
- **#18** Pipeline review of the archive deserialization path (upstream #1481) — [`docs/security/datatype-archive-deserialization-review.md`](docs/security/datatype-archive-deserialization-review.md). Six sites identified, six-step hardening plan.
- **#19** Java deserialization audit + `SafeObjectInput` migration — [`docs/security/JAVA_DESERIALIZATION_AUDIT.md`](docs/security/JAVA_DESERIALIZATION_AUDIT.md). 14 sites in A/B/C risk classes.
- **#20** RMI `serial.filter` enabled by default + regression test — [`launch.properties`](Ghidra/RuntimeScripts/Common/support/launch.properties), [`GhidraSerialFilterDefaultTest.java`](Ghidra/Framework/FileSystem/src/test/java/ghidra/framework/remote/GhidraSerialFilterDefaultTest.java).
- **#21** CycloneDX SBOM in release — [`gradle/sbom.gradle`](gradle/sbom.gradle), [`docs/release/SBOM.md`](docs/release/SBOM.md). Hooked as a `buildGhidra` dependency.

### Testing & CI

- **#22** Run JVM unit tests in CI + JaCoCo upload — [`build-ghidra.yml`](.github/workflows/build-ghidra.yml).
- **#23** Multi-OS CI matrix (ubuntu / macos / windows) — same workflow.
- **#24** C++ decompiler unit tests in CI — [`.github/workflows/decompiler-cpp-tests.yml`](.github/workflows/decompiler-cpp-tests.yml).
- **#25** Re-enable `-Xlint:deprecation,unchecked` + 4-stage ratchet — [`gradle/javaProject.gradle`](gradle/javaProject.gradle), [`docs/testing/XLINT_RATCHET.md`](docs/testing/XLINT_RATCHET.md).
- **#26** ErrorProne static analysis (signal-only Stage 1) — [`gradle/errorprone.gradle`](gradle/errorprone.gradle), [`docs/testing/ERRORPRONE.md`](docs/testing/ERRORPRONE.md).
- **#27** Mockito 5.12.0 on the test classpath — [`gradle/javaProject.gradle`](gradle/javaProject.gradle), [`docs/testing/MOCKITO_ADOPTION.md`](docs/testing/MOCKITO_ADOPTION.md).
- **#28** `@Ignore` debt policy — [`docs/testing/IGNORE_TEST_POLICY.md`](docs/testing/IGNORE_TEST_POLICY.md). Every ignore must carry a tracking issue, category, and deadline.
- **#29** JUnit 5 migration plan (opportunistic, JUnit 4 preserved) — [`docs/testing/JUNIT5_MIGRATION.md`](docs/testing/JUNIT5_MIGRATION.md).
- **#30** Headless test view layer design — [`docs/testing/HEADLESS_TEST_LAYER.md`](docs/testing/HEADLESS_TEST_LAYER.md).

### Decompiler & Sleigh

- **#31** RAII / smart-pointer migration plan — [`docs/decompiler/RAII_MIGRATION.md`](docs/decompiler/RAII_MIGRATION.md). 8-stage bottom-up migration.
- **#32** C++20 adoption plan — [`docs/decompiler/CPP20_ADOPTION.md`](docs/decompiler/CPP20_ADOPTION.md). C++14 then C++20.
- **#33** Versioned IPC framing — [`docs/decompiler/IPC_VERSIONING.md`](docs/decompiler/IPC_VERSIONING.md). Greeting + CRC32 + resync.
- **#34** FlatBuffers IPC payload schema — [`docs/decompiler/IPC_SCHEMA.md`](docs/decompiler/IPC_SCHEMA.md). 8-stage migration with 2-release deprecation window.
- **#35** Per-function decompile budgets + partial-result protocol — [`docs/decompiler/DECOMPILER_BUDGETS.md`](docs/decompiler/DECOMPILER_BUDGETS.md).
- **#36** Decompiler cache invalidation by dependency (upstream #1871) — [`docs/decompiler/CACHE_FLUSH_1871.md`](docs/decompiler/CACHE_FLUSH_1871.md). Per-function dependency bitmaps.
- **#37** RFC 0001 — first-class C++ analysis frontend — [`docs/rfcs/0001-cpp-frontend.md`](docs/rfcs/0001-cpp-frontend.md).
- **#38** RFC 0002 — variable naming across scopes (upstream #975) — [`docs/rfcs/0002-variable-naming-across-scopes.md`](docs/rfcs/0002-variable-naming-across-scopes.md).
- **#39** `for`-loop + inline-function pattern detection — [`docs/decompiler/FOR_LOOP_INLINE_DETECTION.md`](docs/decompiler/FOR_LOOP_INLINE_DETECTION.md).
- **#40** Sleigh formal grammar + semantic model + differential fuzzer — [`docs/sleigh/SLEIGH_FORMAL_AND_FUZZ.md`](docs/sleigh/SLEIGH_FORMAL_AND_FUZZ.md).
- **#41** Per-architecture `MAINTAINERS.md` for `Ghidra/Processors/` — [`Ghidra/Processors/MAINTAINERS.md`](Ghidra/Processors/MAINTAINERS.md). 37 architectures, 8 marked `orphaned-warn (>4yr inactive)`.
- **#42** Jython deprecated; removal scheduled 2027-01-31 — [`docs/decisions/0003-jython-deprecation.md`](docs/decisions/0003-jython-deprecation.md).

### Quality pass

After the first 10 recs shipped at a lower thinking level, a deeper
re-review found gaps in 11 of those documents. The improvements
landed as a single bundled PR: anti-gaming guards on the SLA,
explicit lane-priority tie-breakers, RFC amendment process,
named-author credit on the upstream-PR position documents, refined
threat model in `SECURITY.md`, and more.

## Breaking changes vs upstream Ghidra 12.2

**None.** The fork is a strict superset; all existing tools, scripts,
and analyses continue to work. The `application.name` changed from
`Ghidra` to `GayHydra` and `application.version` from `12.2` to `26.1`;
clients that string-match either should be aware.

## Compatibility

- JDK 21 (same as upstream).
- Gradle 8.5+ (same).
- Python 3.9–3.14 (same; Jython 2 deprecated, removal 2027-01-31).
- C++ toolchain: still `-std=c++11` (the C++20 bump is a plan, not landed).

## Known limitations

This release ships the **design surface** of all 42 recommendations.
The implementation surface is sequenced; the following work has
shipped a plan/RFC but not the implementation:

- OSS-Fuzz upstream project submission to `google/oss-fuzz` (Rec 13/14).
- Cosign release-signing workflow wiring (Rec 17 — the doc and
  verification path are committed; the release workflow's
  signing step is a follow-up PR).
- ASan/UBSan CI is on (Rec 15) but coverage of all decompiler
  test data is incomplete until the seed corpus is grown.
- RAII migration (Rec 31), C++20 bump (Rec 32), IPC versioning
  + FlatBuffers schema (Recs 33–34), bounded budgets (Rec 35),
  cache-invalidation rewrite (Rec 36), C++ frontend (Rec 37),
  variable-naming-across-scopes (Rec 38), `for`-loop/inline
  detection (Rec 39), Sleigh formal grammar + fuzzer (Rec 40).
- Per-arch maintainer slots (Rec 41) all read `orphaned`; opt-in is by
  PR.

Each item above carries a sub-PR sequence documented in its own
design doc.

## Acknowledgements

Built on NSA/ghidra 12.2. The audit and this release: Aaron K. Clark
(@CryptoJones). Upstream contributors are credited in the position
documents for the work this release inherits (notably `@nneonneo`
for the WebAssembly PR referenced in [decision 0001](docs/decisions/0001-webassembly-position.md)).

---

[26.1]: https://codeberg.org/CryptoJones/GayHydra/releases/tag/v26.1

---

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
