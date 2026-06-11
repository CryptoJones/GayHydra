# Sprint Planning

Upcoming sprints. Each sprint is a logical batch, not a fixed
time-box. Sprints are *ordered*, not *scheduled* — they ship when
they ship.

For completed sprints, see [SprintHistory.md](SprintHistory.md).
For the *why* behind individual choices, see
[DesignDecisions.md](DesignDecisions.md).

*Numbering note (2026-06-11):* some sprint numbers below appear twice
(two "Sprint 5"/"Sprint 6"/"Sprint 10" sections) — an artifact of two
numbering eras (the audit-phase plan vs the later operational sprints).
The numbers are kept as-is because DDs and PRs cross-reference them;
the section *titles* disambiguate.

---

## Implementation order (2026-06-11) — every open item, by necessity

The canonical queue. "Necessity" = dependency order + cost-of-delay + hard dates,
not effort or interest. Each entry links to the section that specifies it; tiers
are barriers (don't start tier N+1 work while tier N has unstarted items), order
*within* a tier is the recommended sequence but items are parallelizable.

**Tier 1 — guards.** Everything below changes code that PR CI cannot see; these are
small and multiply the safety of all later work.

1. **Scheduled deep-CI job** (Sprint 15) — full `integrationTest` + bounded fuzz
   smoke + master-build decompiler-smoke, nightly/weekly. Without it the entire
   Rec 37 stack regresses invisibly.
2. **Upstream remote + drift report** (Sprint 15, measurement half only) — prices
   the existential risk so the merge itself can be scheduled on data, not anxiety.
3. **File the dated obligations** (Sprint 15) — the two Rec 42 issues
   (2026-09-30 / 2027-01-31). Minutes of work; calendar risk if skipped.

**Tier 2 — finish the in-flight band to its natural checkpoint.** Context is
loaded and each slice is mechanical against DD-0080; stopping mid-band wastes it.

4. **#34-10d-2** — `setOptions` + `structureGraph` as XML-text schema payloads
   (Sprint 7 row; designed, mechanical).
5. **#34-10e** — `decompileAt` + `DecompileBudget`. Completes the request
   direction; starts the re-keyed #34-7 clock.
6. **DD-0080 go/no-go on #34-10f+** (Aaron decision, at the #34-10e checkpoint) —
   decides whether the response direction exists at all; deciding *now* prevents
   speculative prep on a band that may dissolve.
7. **Rec 12 GHSA redo** (Aaron-attended) — oldest open security commitment;
   git-blame for affected ranges + re-create the three drafts.
8. **Open the Rec 18 #18-2 disclosure thread with NSA** — external latency
   dominates this item, so *start the coordination now*; the `ItemDeserializer`
   hardening code lands later (Tier 5) whenever the thread allows.

**Tier 3 — measurement before the merge.** The recall corpus and perf baseline
must exist *before* the first big upstream sync, or merge-induced regressions in
hint recall / decompiler throughput land invisibly.

9. **Recall corpus** (Backlog, surfacing & measurement band) — fixed multi-
   compiler/arch/opt-level corpus with counted hint hits.
10. **Perf baseline** (same band) — decompile-throughput baseline over the same
    corpus + regression threshold; also restates the Rec 36 #36-4 gate honestly.
11. **MERGE_POLICY.md + first upstream sync** (Sprint 15, policy half) — now
    guarded by items 1, 9, 10. Cadence decision + conflict playbook + the merge.

**Tier 4 — surface the shipped value.** Features users can't see don't exist;
do this before the next feature release announcement.

12. **C++ hints user docs** (Backlog band) — one page + README/CHANGELOG pointers.
13. **Xvfb enabler + pilot headed test** (Backlog, DISPLAY-ceiling reframe) — the
    Rec 30 move for the GUI queue; unblocks the three parked tails.
14. **Rec 35 #35-5b-2** — retry-with-2x action (smallest GUI tail; everything
    else already shipped headlessly).
15. **Rec 38 #38-4 (minimal)** — rename-propagation opt-in; gives the scope graph
    its first production consumer, which the whole of Rec 38 currently lacks.

**Tier 5 — dated and clock-driven.** Land when their clocks fire; don't let them
slip past Tier 4.

16. **Rec 42 default-off Jython** — must ship in a release before **2026-09-30**.
17. **#34-7 (re-keyed)** — remove host v0 request encode, one release after
    #34-10e ships.
18. **Rec 18 #18-2 implementation** — when the Tier-2 disclosure thread allows.

**Tier 6 — strategic sprints, pick by appetite once Tiers 1–4 are done.**

19. **Rec 24 MSVC decompiler tests** — DD-0004's libbfd decision; note the
    win11-ci QEMU box with MSVC already exists for the experiment.
20. **Stage 3 step 6 `-Werror`/ErrorProne ratchet** — its own sprint per PR #271.
21. **Rec 40 #40-5c+** — reference adapters (vendoring **Aaron-gated**), pattern
    generation, CLI packaging.
22. **Bison/flex Option A variant rewrite** + the `xml.cc`/`marshal.cc`
    `std::span` audit tail — closes the last 4 files of Rec 31.

**Deliberately parked (gated, not queued):** #34-8 (v27.x horizon per DD-0080);
Rec 36 #36-4 (evidence-gated — gate becomes real once item 10 exists); Rec 39
loop-shaped patterns (needs loop-collapse infra); #37-12c+ signature consumers
(until a real consumer); Rec 30 #30-2..#30-7 view-interface layer (item 13 may
dissolve most of its motivation — reassess after the Xvfb pilot); the 3
structural-drift upstream PRs (NSA#5593/#3974/#3137 — **recommend closing as
won't-port** after two sprints of carrying); Rec 7/41 (community-passive);
Codeberg mirror (their gateway); Jython removal 2027-01-31 (issue filed in
item 3, executes on its date).

---

## Sprint 14 — Headless integration harness (Rec 30) → unblock the Program-coupled queue

*Next up.* Everything left across Rec 37, Rec 35, and Rec 33 is blocked on the
same thing: it needs a *live* `Program`/`HighFunction` or a GUI `DISPLAY`, so
it can't satisfy the test-before-push rule under the current headless-unit
layer. Build that missing test layer first (Rec 30,
[`docs/testing/HEADLESS_TEST_LAYER.md`](docs/testing/HEADLESS_TEST_LAYER.md)),
then land the work it unblocks.

**Step 1 — the enabler:**

- [x] ~~**Rec 30 headless integration harness** — load a small prebuilt binary,
  run analysis to a real `HighFunction`, and assert on decompiler output / hint
  emission from a headless fixture (JUnit or `decomp_test_dbg`-style). This is
  the gate that makes every item below testable-before-push.~~ Shipped (DD-0023):
  `AbstractDecompilerHighFunctionTest` + pilot `HeadlessHighFunctionHarnessTest`
  in the Decompiler module's `test.slow` — a thin lifecycle wrapper over
  `DecompInterface` that decompiles a function to a real `HighFunction`
  headlessly. Adopts the HighFunction-harness reading of Rec 30 (vs the UI
  view-interface layer in `HEADLESS_TEST_LAYER.md`, which stays as later #30-2…
  #30-7 work) because that is what unblocks the recognition queue. The
  recognition wrappers in Step 2 build on it.

**Step 2 — Rec 37 recognition phase (unblocked by Step 1):**

- [x] ~~**Recognition wrappers** for the seven shipped `CppDecompilerHints`
  renderer forms — detect ctor/dtor/cast/`new`/`new[]`/placement/`delete`
  idioms in a live function and dispatch to the matching (already-shipped) renderer.~~
  **Complete (2026-06-09): all seven forms end-to-end** — virtual call (#37-7b), delete (#37-9f-b),
  destructor (#37-9c-b), heap-new (#37-9b), array-new (#37-9d-b), base cast (#37-8b), placement-new
  (#37-9e-b). DD-0024..DD-0038. The headless ceiling is cleared for the seven C++ idiom forms.
  - [x] ~~**`#37-7b-1`** — virtual-call recognition *matcher*: `CppVirtualCallRecognizer`
    recovers `(slotIndex, receiver)` from a vtable-dispatch `CALLIND` in a live
    `HighFunction`.~~ Shipped (DD-0024): pure p-code matcher in Base, grounded in the
    real decompiler idiom (observed via the Rec 30 harness), verified by a harness
    integration test against an x86-64 virtual call.
  - [x] ~~**`#37-7b-2`** — virtual-call *driver*: walk the `HighFunction`, resolve the
    recovered receiver to a `CppClass`, and dispatch to
    `CppDecompilerHints.renderVirtualCall`.~~ Shipped (DD-0025): `CppVirtualCallDriver`
    resolves the receiver by its recovered `HighVariable` type via `CppTypeSystem` and
    renders `param_1->draw()` from a real x86-64 virtual call. Argument threading
    scoped out (an unresolved indirect `CALLIND` has no recovered prototype) → later slice.
  - [x] ~~**`#37-9f-b-1`** — delete-recognition *matcher*: `CppDeleteRecognizer` recovers
    `(callTarget, receiver)` from a direct `CALL` in a live `HighFunction`.~~ Shipped
    (DD-0026): pure p-code matcher in Base, grounded in the real decompiler idiom — reads
    the callee entry from the `CALL`'s `input[0]` and strips the `void*` `CAST` off `input[1]`
    to reach the receiver. Establishes the **direct-call** recognition shape (vs `#37-7b`'s
    indirect vtable dispatch). Scalar-vs-array is the callee's name → left to the driver.
  - [x] ~~**`#37-9f-b-2`** — delete *driver*: walk the `HighFunction`, resolve the recovered
    `callTarget` to a function, classify its name as `operator delete` / `operator delete[]`
    (or neither), and dispatch to `CppDecompilerHints.renderDelete`.~~ Shipped (DD-0027):
    `CppDeleteDriver` renders `delete param_1` / `delete[] param_1` from a real x86-64
    deallocation call. The one driver that resolves no `CppClass` (delete names no type) →
    needs no `CppTypeSystem`. Dtor-then-delete pairing for non-trivial types not yet fused.
  - [x] ~~**`#37-9c-b-1`** — explicit-destructor *matcher*: `CppDestructorRecognizer` recovers
    `(callTarget, receiver)` from a direct `CALL`.~~ Shipped (DD-0028): the **second** user of
    the `#37-9f-b` direct-call shape — kept a per-form twin of `CppDeleteRecognizer` per
    DD-0026's rule-of-three note (the shared `CppDirectCallRecognizer` extraction is earned at
    the third user, the constructor `#37-9b`). Whether the callee is a `~ClassName` destructor
    is the callee's name → left to the driver.
  - [x] ~~**`#37-9c-b-2`** — explicit-destructor *driver*: walk the `HighFunction`, classify
    the recovered callee name as `~ClassName`, resolve that `CppClass` in a `CppTypeSystem`,
    read receiver-is-pointer from the receiver type, and dispatch to
    `CppDecompilerHints.renderDestructorCall`.~~ Shipped (DD-0029): `CppDestructorDriver` renders
    `param_1->~C()` from a real x86-64 destructor call. Reads the destructed class from the
    callee's `~ClassName` name (not the receiver type) → authoritative for a base dtor on a
    derived pointer. Third of seven forms end-to-end; direct-call shape now has two
    callee-identified users (delete, dtor) → constructor `#37-9b` is the rule-of-three point.
  - [x] ~~**`#37-9b-1`** — heap-construction *matcher*: recover the `new C()` shape.~~ Shipped
    (DD-0030): `CppConstructorRecognizer`, a **fusion** matcher — a heap `new C()` is two linked
    calls, so it anchors on the ctor `CALL` and requires its cast-stripped receiver to be the
    result of *another* `CALL` (the allocation), recovering `(constructorTarget, allocationTarget)`.
    That fusion link (the `this` is freshly allocated, not a stack/field address) is what separates
    a heap `new` from in-place construction. Grounded in the real decompiler p-code
    (`pCVar1 = (C *)operator_new(8L); C::C(pCVar1);`). **Third** user of the direct-call shape →
    the rule-of-three `CppDirectCallRecognizer` extraction is now earned (the next refactor).
  - [x] ~~**`#37-9b-2`** — heap-construction *driver*: classify the ctor target (local name ==
    class namespace name) and resolve its `CppClass`, classify the allocation target as
    `operator new`, and dispatch to `CppDecompilerHints.renderConstruction` → `new C()`.~~ Shipped
    (DD-0031): `CppConstructorDriver` renders `new C()` from a real x86-64 `new C()`. Constructor
    identified by `name == class namespace` (the demangler form, counterpart to the dtor's `~`),
    allocation by `operator new` (same `.`→space normalisation as the delete driver). Args scoped
    out (`#37-10+`). **Fourth** of seven forms end-to-end → three concrete direct-call copies now
    exist, so the `CppDirectCallRecognizer` extraction is the next commit.
  - [x] ~~**refactor** — extract shared `CppDirectCallRecognizer` unifying `CppDeleteRecognizer`,
    `CppDestructorRecognizer`, and the constructor matcher's internal recovery (rule of three, now
    met at three concrete users) — a dedicated commit, not bundled into a feature slice.~~ Shipped
    (DD-0032): one stateless `CppDirectCallRecognizer` (`recognize` → `DirectCall`, plus
    `callTargetAddress` for the ctor matcher's allocation call). The two pass-through recognizers and
    their unit tests deleted outright (no shims); drivers consume `DirectCall` directly, ctor matcher
    delegates recovery + keeps only the fusion walk-back. Recovery coverage consolidated into
    `CppDirectCallRecognizerTest`. Pure structural refactor — behaviour unchanged; 21/21 green.
  - [x] ~~**`#37-9d-b-1`** — array-construction *matcher*: recover the `new C[n]` allocation shape.~~
    Shipped (DD-0033): `CppArrayConstructionRecognizer`, the sprint's first **forward** matcher —
    array `new`'s element type lives forward of the allocation (raw result is `void *`; `C *` appears
    on the downstream `CAST`), so it anchors on the allocation `CALL`, recovers `(allocationTarget,
    byteSize, typedResult)`, and walks forward over the single-consumer `CAST`/`COPY` chain to the
    typed pointer. Recognises the trivial-element shape (no ctor loop); name-blind (operator-new[]
    classification + count = byteSize/elemSize are the driver's). Grounded on real decompiler p-code
    (`(C *)operator_new__(0x28)`); reuses `CppDirectCallRecognizer.callTargetAddress`. Matcher 3/3.
  - [x] ~~**`#37-9d-b-2`** — array-construction *driver*: classify `allocationTarget` as `operator
    new[]`, read the element `Structure` off `typedResult`'s pointer type, compute `count = byteSize /
    element.getLength()`, resolve the `CppClass`, and dispatch to
    `CppDecompilerHints.renderArrayConstruction` → `new C[5]`.~~ Shipped (DD-0034):
    `CppArrayConstructionDriver` renders `new C[5]` from a real x86-64 `new C[5]` (count `0x28/8 = 5`).
    Class read from the typed result's pointer type (no ctor to name it); array-vs-scalar by the
    `operator new[]` name; count only for a positive exact-multiple constant. **Fifth** of seven forms
    end-to-end. Trivial-element only (ctor-loop fusion deferred). Driver 4/4.
  - [x] ~~**`#37-8b-1`** — base-cast *matcher*: recover the up/down-cast pointer-adjustment shape.~~
    Shipped (DD-0035): `CppBaseCastRecognizer`, the sprint's **second forward** matcher — anchors on
    the `CAST` and normalises the two grounded pointer-adjustment shapes into one **signed** byte
    offset: a positive in-layout upcast is a `PTRSUB` (offset = `in[1]`), a negative before-the-object
    downcast is a `PTRADD` (offset = `in[1] * in[2]`, signed). Sign = direction, magnitude =
    base-subobject offset. Recovers `(sourcePointer, byteOffset, castResult)`; requires both ends
    pointer-typed + non-zero offset (offset-0 first-base cast is a bare reinterpretation, no
    recoverable adjustment). Class-blind; class resolution + direction-vs-edge + source-expr rendering
    left to the driver. Grounded on real decompiler p-code (`(Base *)&d->field_0x10` /
    `(Derived *)(b + -2L)`). Matcher 4/4.
  - [x] ~~**`#37-8b-2`** — base-cast *driver*: read source/target classes off the recovered varnodes'
    pointer types, classify direction from the offset sign, resolve the derived class, and dispatch to
    `CppDecompilerHints.renderUpcast` / `renderDowncast` → `static_cast<Base*>(d)` /
    `static_cast<Derived*>(b)`.~~ Shipped (DD-0036): `CppBaseCastDriver` renders both directions from a
    real x86-64 cast. Classes read off the recovered varnodes' pointer types (no callee to name them);
    direction from the offset sign (derived = source for an upcast, target for a downcast); source-expr
    from the source pointer's `HighVariable` name. Dispatches **only** when the derived class has a
    non-virtual base edge at the offset, so the renderer's neutral `src + offset` fallback is never
    emitted as a hint. **Sixth** of seven forms end-to-end. Non-virtual single base offsets only.
    Driver 5/5.
  - last form (placement `#37-9e-b`) — a matcher slice + driver slice; placement reuses the
    construction fusion shape (a ctor on caller-owned storage). (Array `new[]` and cast no longer reuse
    the fusion shape — array is a forward allocation-and-type shape, cast is a forward
    pointer-adjustment shape; see DD-0033, DD-0035.) **Feasibility note (grounded 2026-06-09):** the
    standard placement `new (buf) C()` elides `operator new(size_t, void*)` (it just returns its buffer
    arg) and so compiles to a bare ctor call on caller-owned storage — structurally indistinguishable
    from an ordinary in-place / stack construction, which the `#37-9b` ctor matcher deliberately
    declines, so it is out of scope. The recoverable placement shape is the *non-elided* two-call form
    (a real placement `operator new` taking size+buffer whose result feeds the ctor receiver), grounded
    empirically via the Rec 30 harness before writing the matcher.
    - [x] ~~**`#37-9e-b-1`** — placement *matcher*: recover `(constructorTarget, allocationTarget,
      placementBuffer)` from the non-elided two-call shape, gating on the allocation carrying a buffer
      operand (three `CALL` inputs); recover the buffer as `input[2]`.~~ Shipped (DD-0037):
      `CppPlacementConstructionRecognizer` reuses the heap fusion logic plus the buffer-operand gate.
      Heap matcher `#37-9b` tightened in lock-step to decline a buffer-carrying allocation, so the two
      forms partition the fusion shape and never both match. Matcher 4/4 (partition verified both
      sides); heap suite 3/3 unchanged.
    - [x] ~~**`#37-9e-b-2`** — placement *driver*: resolve the constructor (name == class) and the
      allocation (`operator new`), render the buffer's `HighVariable` name as the placement expression,
      and dispatch to `CppDecompilerHints.renderPlacementConstruction` → `new (buf) C()`. Closes the
      **seventh and last** form.~~ Shipped (DD-0038): `CppPlacementConstructionDriver` renders
      `new (param_1) C()` from a real x86-64 placement new. Same two name classifiers as the heap
      driver (duplicated as per-form twins, not yet rule-of-three); buffer rendered from its
      `HighVariable` name; ctor args scoped out to `#37-10+`. Driver 5/5. **Rec 37 recognition is now
      seven-of-seven end-to-end.**
- [x] ~~**PR #37-5** — MSVC `CppRttiAnalyzer` (Itanium feeder #37-4 shipped).~~ **Complete** — all slices below plus the DD-0061 harvest scan and the DD-0063 `CppRttiAnalyzer` lifecycle wrapper. Sliced small:
  - [x] ~~**#37-5-1** — per-descriptor base decoder: one `RTTIBaseClassDescriptor` (`Rtti1Model`) →
    one `CppRttiFeeder.BaseSpec`.~~ Shipped (DD-0039): `CppMsvcRttiDecoder.decodeBase` — base name
    from the type descriptor's demangled name, offset from `mdisp`, public-ness from the
    `BCD_PRIVORPROTBASE` (`0x04`) attribute bit; non-virtual bases only (virtual `pdisp != -1`
    declined, offset needs the vbtable). Pure decode, total-failure-safe, grounded against the real
    `Base`/`Shape`/`Circle` MSVC RTTI fixtures. Decoder 5/5.
  - [x] ~~**#37-5-2** — class decoder: RTTI3 (`RTTIClassHierarchyDescriptor`) → derived name +
    direct-base `List<BaseSpec>`, walking the RTTI2 base-class array (skip self at index 0,
    distinguish direct from transitive bases).~~ Shipped (DD-0040): `CppMsvcRttiDecoder.decodeClass`
    → `DecodedClass(derivedName, directBases)`. Preorder walk skipping self and each base's
    `numContainedBases` subtree, so transitive bases are excluded; reuses `decodeBase` per entry.
    Pure decode, total-failure-safe, grounded against the complete-flow fixture reshaped for single
    and multiple inheritance. Decoder 8/8.
  - [x] ~~**#37-5-3** — program-scanning analyzer/driver: find the MSVC RTTI structures and feed
    `CppRttiFeeder.feedClass`.~~ Headless driver shipped (DD-0041): `CppMsvcRttiDriver.feedClass`
    bridges `decodeClass` → `CppRttiFeeder.feedClass` for one located structure (RTTI4 or RTTI3 entry
    form), completing the MSVC RTTI → `CppTypeSystem` pipeline end to end; driver 5/5. The program-wide
    *discovery* scan (find every RTTI structure) is the separate program-coupled `CppRttiAnalyzer`
    wrapper below.
- [x] ~~**Program-coupled `CppRttiAnalyzer` / `CppVTableAnalyzer`** wrappers around the shipped headless
  feeders.~~ **Complete (2026-06-10)**: both analyzers ship end-to-end (DD-0061..DD-0066) — opening a
  VS/Clang PE feeds the shared per-program `CppTypeSystem` automatically with class hierarchy (RTTI)
  and named vtables (vftables). Remaining `#37-11` work is the hints-consumer wiring.
  - [x] ~~**#37-5-4** — program-wide MSVC RTTI harvest scan.~~ Shipped (DD-0061):
    `CppMsvcRttiScan.feedProgram` walks defined data for the `RTTICompleteObjectLocator` entries
    upstream's `RttiAnalyzer` already laid down, re-validates each as an `Rtti4Model`, and feeds it
    through `CppMsvcRttiDriver` — one call feeds a whole binary's MSVC class graph. **Harvest, not
    re-discovery**: upstream's byte-search runs by default for VS/Clang PEs and publishes defined data
    with a stable datatype name; reading that output beats duplicating the fragile byte walk. Feed order
    irrelevant (defined-data order can be derived-before-base; `resolveOrPlaceholder` fills in). Grounded
    against the complete-flow fixture with RTTI4s laid down via `CreateRtti4BackgroundCmd`; empty harvest
    on an unanalyzed program. Scan 5/5. Remaining tail: the `Analyzer`-lifecycle wrapper (priority after
    `RttiAnalyzer`, options, where the fed `CppTypeSystem` lives) and the `CppVTableAnalyzer` twin.
    Note: upstream `RttiCreateCmdTest` `*FollowFlow` cases flake locally (order-sensitive, reproduce on
    pure master; CI green) — pre-existing, out of scope.
  - [x] ~~**#37-11a** — per-program `CppTypeSystem` provider.~~ Shipped (DD-0062):
    `CppTypeSystemProvider.get(Program)` — get-or-create over `TransientProgramProperties`
    (`SCOPE.PROGRAM`), one shared type system per open program, bound to the program's
    `DataTypeManager`, released on close. Fills the grounded gap that *no production code constructed a
    `CppTypeSystem`* (tests only) — the whole Rec 37 stack had no assembly point. Provider answers
    location only; contributors feed, consumers read (module direction: provider in Base, contributors
    downstream). PROGRAM scope is rollback-safe — the model holds standalone placeholder structures,
    not program-DB datatypes. Provider 6/6. Next `#37-11` slices: **#37-11b** `CppRttiAnalyzer`
    lifecycle wrapper in MicrosoftCodeAnalyzer (priority after upstream `RttiAnalyzer`, calls the
    DD-0061 harvest through the provider), **#37-11c** `CppVTableAnalyzer` twin, then the
    hints-consumer wiring that hands the shared type system to the recognition drivers.
  - [x] ~~**#37-11b** — `CppRttiAnalyzer` lifecycle wrapper.~~ Shipped (DD-0063): a default-enabled
    `BYTE_ANALYZER` beside upstream's `RttiAnalyzer` that runs the DD-0061 harvest through the DD-0062
    provider during auto-analysis — opening a VS/Clang PE now feeds the shared `CppTypeSystem`
    automatically, the first end-to-end production path from binary to fed type system. Priority
    `REFERENCE_ANALYSIS.after()` (strictly after the upstream analyzer whose laid-down RTTI4 data it
    consumes, asserted against its actual priority); same `canAnalyze` gate; `added()` re-walks the
    whole program (re-feeding is a no-op — no `hasRun` gating). Cancellation threaded through a
    monitor-aware `feedProgram` overload. The repeated-trigger test caught a real bug:
    `CppRttiFeeder.feedClass` duplicated base edges on re-feed (DD-0062's idempotence claim was wrong);
    it now skips identical edges. Analyzer 6/6, feeder +1. Remaining `#37-11` tail: **#37-11c**
    `CppVTableAnalyzer` twin, then the hints-consumer wiring.
  - [x] ~~**#37-11c-1** — MSVC vftable driver.~~ Shipped (DD-0064): `CppMsvcVftableDriver.feedVtable`
    bridges one located `VfTableModel` to `CppVTableFeeder` — the program-coupled core DD-0014
    deferred. Owning class from the vftable's own RTTI (same `getDescriptorName()` the RTTI decoder
    keys by → attaches to the very `CppClass` the RTTI feed resolves, asserted by identity); slot
    names from each slot function's primary symbol. No-symbol / default-`FUN_...` / `_purecall` slots
    decline the *whole* table (slot index is the virtual-call renderer's dispatch contract; a partial
    table would mis-number every later slot). Sets `CppVTable.getTableAddress()`; pure-virtual name
    recovery deferred. Driver 8/8. Remaining: **#37-11c-2** program-wide vftable harvest scan
    (`CppMsvcRttiScan` twin), **#37-11c-3** `CppVTableAnalyzer` lifecycle wrapper, then the
    hints-consumer wiring.
  - [x] ~~**#37-11c-2** — program-wide MSVC vftable harvest scan.~~ Shipped (DD-0065):
    `CppMsvcVftableScan.feedProgram` walks the symbol table for the `vftable`-named symbols
    upstream's associated-vftable pass publishes and drives each through the DD-0064 driver — the
    `CppMsvcRttiScan` twin, anchored on a published *symbol* (a vftable's laid-down data is a plain
    `pointer[n]`, nothing distinctive to select by datatype name). Declines per-table, not
    per-program; cancellation per symbol; same null contracts. Scan 8/8. Remaining: **#37-11c-3**
    `CppVTableAnalyzer` lifecycle wrapper, then the hints-consumer wiring.
  - [x] ~~**#37-11c-3** — `CppVTableAnalyzer` lifecycle wrapper.~~ Shipped (DD-0066): the
    `CppRttiAnalyzer` twin — default-enabled `BYTE_ANALYZER` at `REFERENCE_ANALYSIS.after()` running
    the DD-0065 harvest through the DD-0062 provider. Opening a VS/Clang PE now feeds the shared
    type system with *both* halves automatically (hierarchy from RTTI, named vtables from vftables);
    sibling order deliberately irrelevant (placeholder-resolving feeders; composition test asserts
    the end state). Analyzer 7/7. **Closes this sprint item.** Remaining `#37-11` work: the
    hints-consumer wiring (hand `CppTypeSystemProvider.get(program)` to the recognition drivers).
  - [x] ~~**refactor** — extract the shared `Cpp*` RTTI test fixture helpers (rule of three, met at
    three-to-four users).~~ Shipped: `layDownRtti4Data` / `setNumContainedBases` /
    `assertSingleBaseEdge` consolidated into a fork-owned `AbstractCppRttiTest` between upstream's
    `AbstractRttiTest` and the six `Cpp*` suites (an intermediate class, not edits to the upstream
    file, so upstream merges stay clean); two-user helpers (`nameSlots`,
    `setExecutableFormatAndCompiler`) stay twins. Pure structural refactor; all six suites green.
  - [x] ~~**#37-11d-1** — `CppHintsCollector` hints-consumer facade.~~ Shipped (DD-0067):
    `collect(HighFunction)` runs all seven recognition drivers against
    `CppTypeSystemProvider.get(program)` (the provider's first production *read*) and returns one
    uniform, site-ordered `List<CppHint(site, kind, rendering)>`. **The full Rec 37 pipeline is now
    end-to-end in production form**: upstream analysis → fork analyzers feed → `collect` renders.
    Advisory posture inherited (never invents a hint; empty list is the normal unfed result).
    Verified through the Rec 30 harness with the class fed into the provider's instance; 3/3.
    Remaining `#37-11` work: surfacing (GUI margin blocked on `DISPLAY`; headless consumers
    sooner).
  - [x] ~~**#37-11d-2** — headless hints surfacing.~~ Shipped (DD-0068): `CppHintsCommenter`
    writes hints as idempotent, additive `C++: <rendering>` `PRE` comments (visible in listing
    *and* decompiler view by default); `RecoverCppHintsScript` (`@category C++`) is the thin
    per-function decompile→collect→annotate driver (Script Manager or
    `analyzeHeadless -postScript`). Analyzer-side auto-annotation deliberately rejected
    (un-opt-out-able + mid-analysis feed-order coupling). Commenter 7/7. Remaining Rec 37 surface
    work: the GUI margin (same collector) when the `DISPLAY` ceiling lifts; the `#37-10`
    signature/template/operator tail.
- [x] ~~**PR #37-10+ band** — argument / `DataTypeManager` / signature / template / operator rendering.~~ **Complete 2026-06-10**: argument/expression sub-band `#37-10a`–`s`, vcall args `#37-10t`, templates `#37-10u` (dissolved with guards), infix operators `#37-10v`, signature feeds `#37-12a`/`b`. Only `#37-12c+` consumers remain, deferred until a real consumer exists (DD-0073).
  - [x] ~~**#37-10a** — thread explicit constructor arguments into the placement driver.~~ Shipped
    (DD-0042): `CppPlacementConstructionDriver` recovers the constructor `CALL`'s inputs after the call
    target (0) and `this` receiver (1) as the explicit arguments, renders each by its `HighVariable`
    name, and dispatches them to `renderPlacementConstruction`, so `new (buf) C(arg)` renders with its
    argument. An argument with no printable name (unnamed temporary or bare constant) declines the whole
    hint rather than rendering a gap; constants / compound-expression args are later slices. Grounded
    against a decompiled `new (buf) C(v)` → `new (param_1) C(param_2)`; driver 6/6.
  - [x] ~~**#37-10b** — thread explicit constructor arguments into the heap-new driver.~~ Shipped
    (DD-0043): `CppConstructorDriver` recovers the constructor `CALL`'s inputs after the call target (0)
    and the `this` receiver (1) as the explicit arguments, renders each by its `HighVariable` name, and
    dispatches them to `renderConstruction`, so `new C(arg)` renders with its argument. Same
    decline-on-unnamed contract as `#37-10a`; the two argument helpers are duplicated as per-form twins
    (rule of three) until a third user earns extraction. Grounded against a decompiled `new C(v)` →
    `new C(param_1)`; driver 6/6.
  - [x] ~~**#37-10c** — render integer-typed constant constructor arguments.~~ Shipped (DD-0044): the
    placement and heap drivers render a bare integer literal argument as its decimal value via a new
    `argumentExpr` helper, so `new C(5)` / `new (buf) C(5)` render instead of declining. Gated on
    `AbstractIntegerDataType` so a pointer-typed constant (a global address) still declines rather than
    rendering a misleading bare number (never-wrong). The two `argumentExpr` helpers stay per-form twins
    until a third argument-rendering user earns extraction; compound expressions and typed constants
    (chars, bools, enums) deferred. Grounded against `new C(5)` → `new C(5)` and `new (buf) C(5)` →
    `new (param_1) C(5)`; placement 7/7, heap 7/7.
  - [x] ~~**#37-10d** — render integer-constant arguments at the varnode's byte width.~~ Shipped
    (DD-0045): a constant varnode's `getOffset()` carries only the low `size*8` value bits, so
    `#37-10c`'s `Long.toString(getOffset())` mis-rendered a negative signed argument (`new C(-1)` as
    `new C(4294967295)`) and a wide unsigned one (`unsigned long long ~0` as `-1`). A new
    `integerConstantLiteral` helper (per-form twin in both drivers) sign-extends a signed type from the
    varnode width and renders an unsigned type across the full range via `Long.toUnsignedString`, so
    `new C(-1)` / `new (buf) C(-1)` and a wide unsigned `18446744073709551615` render faithfully — the
    never-wrong contract restored for negative and wide-unsigned literals. Gate unchanged; helper stays a
    per-form twin until a third user earns extraction. Grounded against `new C(-1)` and `new C(~0ull)`
    (and placement twins); placement 9/9, heap 9/9.
  - [x] ~~**#37-10e** — render boolean-constant arguments as `true`/`false`.~~ Shipped (DD-0046):
    `BooleanDataType extends AbstractIntegerDataType`, so `#37-10c`/`#37-10d` rendered a `bool` argument
    through the integer branch as the decimal `1`/`0`. `argumentExpr` (per-form twin in both drivers) now
    special-cases a `BooleanDataType` constant of `1`/`0` ahead of the integer branch, so `new C(true)` /
    `new (buf) C(true)` (and `false`) render source-faithfully; an out-of-range bool falls through to the
    byte-width-correct integer rendering, so unexpected bits stay a faithful number rather than coerced —
    never-wrong preserved at the edge. Gate otherwise unchanged; bool branch stays a per-form twin until a
    third user earns extraction. Grounded against `new C(true)` / `new C(false)` (and placement twins);
    placement 11/11, heap 11/11. Next typed-constant slices: `#37-10f` char → `'A'`, `#37-10g` enum →
    member name.
  - [x] ~~**#37-10f** — render char-constant arguments as C character literals.~~ Shipped (DD-0047):
    `CharDataType extends AbstractIntegerDataType`, so `#37-10c`/`#37-10d`/`#37-10e` rendered a `char`
    argument through the integer branch as the decimal byte (`new C('A')` as `new C(65)`). `argumentExpr`
    (per-form twin in both drivers) now special-cases a `CharDataType` constant ahead of the integer
    branch via a new `charConstantLiteral` helper: a printable ASCII byte renders directly (`'A'`), the
    standard C escapes cover the control/special characters (`'\n'`, `'\t'`, `'\''`, `'\\'`, …), and a
    `\xNN` hex escape covers any other non-printable byte — every byte renders faithfully, so a char
    constant never declines. `SignedCharDataType`/`UnsignedCharDataType` extend `CharDataType` (all three
    1-byte char types render); the `BuiltIn`-derived wide-char types decline rather than mis-render as a
    byte. Helper stays a per-form twin until a third user earns extraction. Grounded against `new C('A')` /
    `new C('\n')` (and placement twins); placement 13/13, heap 13/13. Next typed-constant slice: `#37-10g`
    enum → member name.
  - [x] ~~**#37-10g** — render enum-constant arguments as qualified member names.~~ Shipped (DD-0048):
    `EnumDataType`/`EnumDB` implement the `Enum` interface and extend `GenericDataType`, not
    `AbstractIntegerDataType`, so an enum-typed constant matched none of the `#37-10c`–`f` branches and
    `argumentExpr` declined the whole hint (`new C(Color::GREEN)` lost its argument). `argumentExpr`
    (per-form twin in both drivers) now adds an `instanceof Enum` branch via a new `enumConstantLiteral`
    helper: it reads the value at the varnode byte width (sign-extended when `Enum.isSigned()`, masked
    otherwise), looks up the member with `Enum.getName(value)`, and renders `TypeName::Member` — valid C++
    for both scoped `enum class` and unscoped `enum`. A value naming no member declines rather than
    fabricate a name or a bare number. Helper stays a per-form twin until a third user earns extraction.
    **Completes the typed-constant sub-band `#37-10c`–`g`** (integer signed/unsigned, boolean, char,
    enum). Grounded against `new C(Color::GREEN)` (and placement twin); placement 15/15, heap 15/15. Next
    `#37-10` work: compound argument expressions, wide-char (`wchar_t`) constants, and
    signature/template/operator rendering.
  - [x] ~~**#37-10h** — render wide-char-constant arguments as prefixed C++ character literals.~~ Shipped
    (DD-0049): `WideCharDataType`/`WideChar16DataType`/`WideChar32DataType` extend `BuiltIn`, not
    `AbstractIntegerDataType`, and are not `CharDataType`, so a wide-char constant matched none of the
    `#37-10c`–`g` branches and `argumentExpr` declined the whole hint (`new C(L'A')` lost its argument).
    `argumentExpr` (per-form twin in both drivers) now adds three `instanceof` branches via a new
    `wideCharConstantLiteral` helper: it reads the value at the varnode's own byte width (the ground-truth
    width — `wchar_t` is 2 bytes on MSVC and 4 on the Itanium ABI, so the declared length is not relied on)
    and emits a prefix-tagged literal (`L'A'`/`u'A'`/`U'A'`) — printable ASCII directly, the standard C
    escapes for control/quote/backslash, and a width-padded `\xNN…` hex escape (`u'\x20ac'`) for any other
    unit. `\x` is used over `\u`/`\U` because a universal-character-name is ill-formed for control and
    surrogate code points; every unit renders faithfully, so a wide-char constant never declines. Helper
    stays a per-form twin until a third user earns extraction. Completes the character-literal story
    (1-byte `char` + the three wide-char types). Grounded against `new C(L'A')` (and placement twin);
    placement 19/19, heap 19/19. Next `#37-10` work: compound argument expressions, floating-point
    constants, and signature/template/operator rendering.
  - [x] ~~**#37-10i** — render floating-point-constant arguments as C++ decimal literals.~~ Shipped
    (DD-0050): `FloatDataType`/`DoubleDataType` extend `AbstractFloatDataType` (in turn `BuiltIn`), not
    `AbstractIntegerDataType`, so a float constant matched none of the `#37-10c`–`h` branches and
    `argumentExpr` declined the whole hint (`new C(2.5f)` lost its argument). A throwaway probe grounded
    the shape first: a `2.5f` arg arrives as a size-4 constant varnode carrying the IEEE-754 bit pattern
    (`0x40200000`), typed `FloatDataType`. `argumentExpr` (per-form twin in both drivers) now adds one
    `instanceof AbstractFloatDataType` branch via a new `floatConstantLiteral` helper that decodes the bits
    at the varnode byte width — size 4 via `Float.intBitsToFloat` (with an `f` suffix), size 8 via
    `Double.longBitsToDouble` (unsuffixed), rendered with `Float`/`Double.toString` for the shortest
    round-tripping decimal. A non-finite value (`NaN`/`Infinity`, no bare C++ literal) and exotic widths
    decline, keeping the never-wrong contract. Helper stays a per-form twin until a third user earns
    extraction. Completes the scalar-literal story (integer/bool/char/enum/wide-char/float). Grounded
    against `new C(2.5f)`/`new C(2.5)` (and placement twins); placement 22/22, heap 22/22. Next `#37-10`
    work: compound argument expressions, and signature/template/operator rendering.
  - [x] ~~**#37-10j** — decline arguments backed by Ghidra's `UNNAMED` placeholder (never-wrong fix).~~
    Shipped (DD-0051): grounding the next argument shape (a `const char*` string pointer) with a probe
    surfaced a leak. A string-pointer arg is **not** a constant varnode — the decompiler resolves the
    global address into a typed `char *` temp with no backing symbol, whose `HighVariable` is a `HighOther`
    carrying the sentinel name `"UNNAMED"` (set in `HighOther` unless a symbol resolves it). `operandName`
    returned that sentinel verbatim, so the driver rendered the misleading `new C(UNNAMED)` instead of
    declining. `operandName` (per-form twin in both drivers) now treats `"UNNAMED"` — alongside a
    null/blank name — as no-name, so an argument the decompiler cannot name (string pointer, or a
    compound-expression result temp) declines the whole hint, restoring never-wrong. Matching the literal
    sentinel (not `instanceof HighOther`) is deliberate: a `HighOther` can carry a real symbol name and
    must still render by name. Decline confirmed first as a failing assertion against the pre-fix
    `new C(UNNAMED)`, then made green. Placement 23/23, heap 23/23. Next `#37-10` work: faithfully
    *rendering* string-literal args (trace the pointer + read the NUL-terminated bytes) and compound
    expressions, plus signature/template/operator rendering.
  - [x] ~~**#37-10k** — render `const char*` string-pointer arguments as C++ string literals.~~ Shipped
    (DD-0052): replaces the `#37-10j` decline for the most common unnamed shape. A `const char*` arg is
    not a constant varnode — it is the unnamed `char *` `HighOther` temp DD-0051 declines, defined by a
    `COPY` of the `const`-space global address (grounded with a def-chain probe). A new
    `stringConstantLiteral` helper (tried in `argumentExpr` after the `operandName` decline and before the
    `isConstant` branches) gates on a pointer-to-`char` type, traces the temp through up to four
    `COPY`/`CAST` pass-throughs to the constant address, reads the NUL-terminated bytes from program
    memory, and escapes them into a `"…"` literal — printable ASCII direct, named C escapes, and **3-digit
    octal `\ooo`** for other bytes (octal, not `\xNN`, which is greedy in a string). A non-char pointer,
    an unreadable address, or a missing terminator within 4096 bytes declines (never-wrong). Required
    threading `Program` through `render`/`constructorArguments`/`argumentExpr`; the helper stays a per-form
    twin (rule of three). Grounded against `new C("Hi")`, an escaped-bytes string, and an `int*` decline
    (and placement twins). Placement 25/25, heap 25/25. Next `#37-10` work: wide-char string pointers
    (`wchar_t*`/`char16_t*`/`char32_t*`), compound-expression args, plus signature/template/operator
    rendering.
  - [x] ~~**#37-10l** — render wide string-pointer arguments as C++ wide string literals.~~ Shipped
    (DD-0053): generalises the `#37-10k` narrow `char*` renderer to `wchar_t*` / `char16_t*` /
    `char32_t*`, emitting `new C(L"Hi")` / `new C(u"Hi")` / `new C(U"Hi")`. A wide pointer reaches the
    call identically to a narrow one — the unnamed character-pointer `HighOther` temp defined by a `COPY`
    of the `const`-space global address (grounded with a probe: pointee `WideCharDataType`, `len=2` on the
    `_X64` spec). `stringConstantLiteral`'s gate now accepts the four string-char pointee types via a
    `stringLiteralPrefix` helper (`""`/`L`/`u`/`U`), reads code units at the pointee's own byte width
    (1/2/4) in the program's endian order, and escapes per unit: low units keep the `#37-10k` policy
    (printable direct, named escapes, 3-digit octal `\ooo`), while a high wide unit (`>= 0x80`) renders as
    a fixed-width, non-greedy universal-character-name (`\uXXXX`/`\UXXXXXXXX`). A lone surrogate or
    out-of-range value declines the whole hint (never-wrong). Helper stays a per-form twin (rule of three).
    Grounded against `L"Hi"`/`u"Hi"`/`U"Hi"`, an escaped wide case (`u"A\t\001€"`), and a lone-surrogate
    decline (and placement twins). Placement 30/30, heap 30/30. Next `#37-10` work: compound-expression
    args, plus signature/template/operator rendering.
  - [x] ~~**#37-10m** — render one-level compound-expression arguments as C++ binary expressions.~~
    Shipped (DD-0054): a single-operator compound argument (`new C(param_1 + 7)` /
    `new (buf) C(param_2 << 3)`) now renders instead of declining the unnamed temporary. The arg is an
    unnamed `HighOther` temp whose `getDef()` is a binary p-code op over a named leaf and a constant
    (grounded with a multi-op probe). `argumentExpr` is split into `leafExpr` (names, string literals,
    typed constants) and a new `binaryExpr` rendering `leafExpr(in0) OP leafExpr(in1)` for a grounded
    opcode→glyph map (`INT_ADD`→`+`, `INT_SUB`→`-`, `INT_MULT`→`*`, `INT_AND`→`&`, `INT_OR`→`|`,
    `INT_XOR`→`^`, `INT_LEFT`→`<<`, `INT_SRIGHT`→`>>`). Operands are leaves only with no `CAST`/`COPY`
    peeling, so a logical right shift (`INT_RIGHT`, operand cast to unsigned) and any nested compound
    cleanly decline rather than risk a wrong arithmetic-vs-logical-shift render; a single operator over
    two leaves is never precedence-ambiguous (no parens). Helper stays a per-form twin (rule of three).
    Grounded against `param + 7` / `param & 7` / `param << 3` / arithmetic `param >> 3` and a
    logical-shift decline (and placement twins). Placement 35/35, heap 35/35. Next `#37-10` work:
    division/remainder, comparison and unary operators, nested compounds with parens, plus
    signature/template/operator rendering.
  - [x] ~~**#37-10n** — render signed division/remainder arguments as C++ binary expressions.~~ Shipped
    (DD-0055): extends the `#37-10m` `binaryOperator` map with `INT_SDIV`/`INT_DIV`→`/` and
    `INT_SREM`/`INT_REM`→`%`, so `new C(param_1 / 7)` / `new C(param_1 % 7)` (and placement twins)
    render. A probe grounded the same signed/unsigned split the shifts have: the signed opcodes carry the
    named operand directly (render), the unsigned opcodes cast the operand to unsigned, so the existing
    leaf-only no-peel rule in `binaryExpr` declines them rather than silently change signedness — two map
    entries, no new control flow. Per-form twin (rule of three). Grounded against signed `idiv` quotient
    and remainder (render) and unsigned `div` quotient and remainder (decline), and placement twins.
    Placement 39/39, heap 39/39. Next `#37-10` work: comparison and unary operators, nested compounds
    with parens, plus signature/template/operator rendering.
  - [x] ~~**#37-10o** — render unary arguments (arithmetic negation, bitwise complement) as C++ unary
    expressions.~~ Shipped (DD-0056): a new `unaryExpr` / `unaryOperator` pair, wired into `argumentExpr`
    after the leaf and binary tries, renders `new C(-param_1)` (`INT_2COMP`) and `new C(~param_1)`
    (`INT_NEGATE`) over a leaf operand. A probe grounded the shape: both opcodes preserve the operand's
    width, so the unary op is the value varnode's direct definition with no intervening cast/extension —
    unlike a comparison or logical `!`, whose 1-byte boolean result the decompiler widens with an
    `INT_ZEXT` (deliberately declined, awaiting an extension-peeling slice). A unary prefix over a leaf
    needs no parentheses; a compound or cast-wrapped operand declines the whole hint (never-wrong).
    Per-form twin (rule of three). Grounded against decompiled `neg`/`not` over a named param (render).
    Placement 41/41, heap 41/41. Next `#37-10` work: comparison operators (need `INT_ZEXT` peeling),
    nested compounds with parens, plus signature/template/operator rendering.
  - [x] ~~**#37-10p** — render equality-comparison arguments (`==`, `!=`) as C++ comparisons.~~ Shipped
    (DD-0057): a new `comparisonExpr` / `comparisonOperator` pair, wired into `argumentExpr` after the
    leaf, binary, and unary tries, renders `new C(param_1 == 7)` (`INT_EQUAL`) and `new C(param_1 != 7)`
    (`INT_NOTEQUAL`). A probe grounded the shape: a comparison computes a one-byte boolean the decompiler
    widens to the argument slot with an `INT_ZEXT`, so the comparison sits one hop below the value
    varnode's def; `comparisonExpr` peels exactly one `INT_ZEXT` to reach it. Only the symmetric equality
    operators are mapped — no signed/unsigned split, no operand order to recover; relational comparisons
    (which the decompiler canonicalises to swapped `<`/`<=`) are deferred. Operands rendered as leaves
    only, so a compound/cast-wrapped operand declines (never-wrong). Per-form twin (rule of three).
    Grounded against decompiled `sete`/`setne` over a named param (render). Placement 43/43, heap 43/43.
    Next `#37-10` work: relational comparison operators (`<`/`<=`/`>`/`>=`, need operand-order +
    signed/unsigned reasoning), logical `!`, nested compounds with parens, plus
    signature/template/operator rendering.
  - [x] ~~**#37-10q** — render relational-comparison arguments (`<`/`<=`/`>`/`>=`) as C++ comparisons.~~
    Shipped (DD-0058): extends the `#37-10p` `comparisonOperator` map with `INT_SLESS`/`INT_LESS` → `<`,
    no other change. A probe grounded a simpler reality than expected: the decompiler canonicalises every
    signed relational source form to a strict `INT_SLESS` by adjusting the constant or swapping the
    operands, so `new C(v < 7)` → `param_1 < 7`, `v <= 7` → `param_1 < 8`, `v > 7` → `7 < param_1`,
    `v >= 7` → `6 < param_1` (each the exact boolean computed); `INT_SLESSEQUAL`/`INT_LESSEQUAL` are never
    emitted and stay unmapped. The unsigned `INT_LESS` casts its operand, so `comparisonExpr`'s leaf-only
    rule declines it — the same signed/unsigned split division and the shifts have. Per-form twin (rule of
    three). Grounded against `setl`/`setle`/`setg`/`setge` (render) + `setb` (decline), and placement
    twins. Placement 48/48, heap 48/48. Completes the comparison sub-band `#37-10p`–`q`. Next `#37-10`
    work: logical `!` (`BOOL_NEGATE` under the same `INT_ZEXT`), nested compounds with parens, plus
    signature/template/operator rendering.
  - [x] ~~**#37-10r** — render nested compound arguments with unconditional parentheses.~~ Shipped
    (DD-0059): a new `operandExpr` helper routes `binaryExpr`/`unaryExpr` operands; a leaf renders bare,
    a nested compound renders recursively wrapped in parentheses — `new C((param_1 & 7) + 1)`,
    `new C((~param_1) & 7)`, `new C(-(param_1 & 7))`, `new C(((param_1 & 7) | 9) ^ 5)` (and placement
    twins). Design choice: fully-parenthesised rendering over a C precedence table — exact by
    construction, no table to get wrong, occasional redundant pair accepted (faithful over pretty); top
    level stays bare so every one-level rendering is unchanged. Terminates structurally (only mapped
    opcodes recurse; `MULTIEQUAL`/`INDIRECT` unmapped) + `MAX_OPERAND_NESTING` 8 as defense-in-depth;
    `CAST` still declines, so all grounded unsigned declines hold. The arithmetic/bitwise/shift/unary
    grammar is now closed under composition. Comparison operands stay leaf-only. Per-form twin (rule of
    three). Placement 52/52, heap 52/52. Next `#37-10` work: comparison-operand compounds, logical `!`
    (decompiler typically canonicalises `!v` to `v == 0`, already rendered by `#37-10p`), plus
    signature/template/operator rendering.
  - [x] ~~**#37-10s** — render compound operands inside comparison arguments.~~ Shipped (DD-0060):
    `comparisonExpr`'s two operands route through the `#37-10r` `operandExpr` instead of `leafExpr` (the
    whole change per driver), so `new C((param_1 & 7) == 5)` / `!= 5` / `< 5` render (and placement
    twins). Probe findings: compound operands arrive directly under the comparison (signed relational
    kept its `INT_AND` operand bare — a masked value needs no unsigned cast), and a unary compound under
    a comparison is typically never seen — the decompiler folds `~v == 5` into `INT_EQUAL(param_1, -6)`,
    rendering `param_1 == -6` at leaf level (exact boolean). Leaves render bare (all `#37-10p`/`q`
    renderings unchanged), cast-wrapped unsigned forms still decline. Binary, unary, and comparison
    operands now all compose through `operandExpr`; logical `!` needs no slice on current grounding.
    Per-form twin (rule of three). Placement 56/56, heap 56/56. **Closes the `#37-10` expression
    sub-band** (`#37-10m`–`s`). Remaining `#37-10` work is the different-in-kind tail:
    signature/template/operator-overload rendering.
  - [x] ~~**refactor** — extract the shared `CppOperandRenderer` (rule of three, met when the
    `#37-10t` virtual-call argument threading became the third user).~~ Shipped: the
    `#37-10a`–`s` argument-expression machinery (`callArguments` né `constructorArguments`,
    typed-literal renderers, compound grammar) consolidated from the heap/placement per-form twins
    (verified diff-identical modulo javadoc) into one package-private class; the two name
    classifiers stay twins at two users. Net −823 lines; behaviour unchanged — placement 56/56,
    heap 56/56. Grounding for `#37-10t` (probe 2026-06-10): a decompiled vtable `CALLIND` carries
    its recovered args as `inputs[2..]` like the ctor `CALL`, but typed `undefined8` (no prototype
    on an unresolved indirect call) — the typed-literal gates would decline, so `#37-10t` needs an
    explicit undefined-constant policy.
  - [x] ~~**#37-10t** — thread recovered arguments into the virtual-call driver.~~ Shipped
    (DD-0069): `CALLIND` `inputs[2..]` rendered through the shared `CppOperandRenderer`, so
    `param_1->draw(5)` renders. New `undefinedConstantLiteral` policy: a prototype-less constant
    renders as decimal iff its sign bit at the varnode width is clear (identical under either
    signedness — faithful by construction); ambiguous patterns decline. An unrenderable argument
    declines the *whole* hint (the old no-argument rendering misrepresented arity); zero-argument
    calls unchanged. Vcall 5/5; collector + construction suites unchanged. Remaining `#37-10`:
    the signature/template/operator different-in-kind tail.
  - [x] ~~**#37-10u** — template class names.~~ Dissolved with guards (DD-0070): probe-grounded,
    the demangler hands the pipeline `MyVec<int>` and every layer keys/renders by name verbatim —
    no feature code needed (the Rec 39 `for`-loop pattern). Two guard tests pin it (decoder
    `derivedName == "MyVec<int>"`, renderer `new MyVec<int>(n)`). Remaining `#37-10`:
    signature/`DataType` resolution and operator-overload rendering.
  - [x] ~~**#37-10v** — infix operator-call rendering.~~ Shipped (DD-0071): an operator slot with
    exactly one explicit argument (a member binary operator's arity) renders infix — `(*p) + x` /
    `s == other`; zero-arg (negation/dereference), `++`/`--` (postfix dummy-int ambiguity),
    `[]`/`()`/assignment keep the explicit `p->operatorX(args)` form, itself valid C++ — the
    fallback is never-wrong by construction. Renderer +5, 68/68. **Remaining `#37-10` (and the
    last open Rec 37 band): signature/`DataType` resolution.**
  - [x] ~~**#37-12a** — demangled method signatures (opens the `#37-12` signature band).~~ Shipped
    (DD-0072): the demangling feeder populates `CppMethod.signature` (the DD-0011 field nothing had
    ever fed) via `DemangledDataType.getDataType` against the type system's bound DTM — the
    demangler's own canonical conversion. No DTM / unconvertible type → null signature, method
    still feeds (never-wrong); ctor form keeps default return. Feeder 16/16. Next: **#37-12b**
    vftable-driver signatures from the slot `Function`, **#37-12c+** hint consumers.
  - [x] ~~**#37-12b** — vftable slot signatures.~~ Shipped (DD-0073): `SlotSpec` gains an optional
    `FunctionDefinition` (two-arg form unchanged); the MSVC vftable driver fills it from the
    `Function` at the slot's address (the signature the demangler analyzer applied). Label-only
    slot → null signature, still feeds by name — per-slot never-wrong. Both `#37-12` feed paths
    now fill the same model field. Feeder +1, driver +1. Remaining: **#37-12c+** hint consumers.

**Step 3 — deferred runtime blockers (unblocked by Step 1 / a GUI harness):**

- [x] ~~**Rec 35 #35-5b-2** — Retry-with-2x-budget action (re-decompile +
  partial-banner clear; needs a DISPLAY). The budget-doubling + `isPartial`
  enablement helpers are already headlessly tested and shipped; only the GUI
  action remains.~~ Shipped 2026-06-11 behind the new Xvfb layer: a
  `DecompilerProvider` local action enabled only while the displayed result
  `isPartial()`; the action doubles the iteration-budget tool option
  (saturating) and lets the existing options-changed listener re-decompile —
  the fresh result resolves the `#35-5b-1` banner, and the action can never
  diverge from a manual option edit. Three headed tests
  (`RetryWithDoubledBudgetHeadedTest`: installed/disabled-on-complete,
  double-and-redecompile, overflow saturation) pass under `xvfb-run` and run
  weekly via `xvfb-gui-tests.yml`. **Closes Rec 35.**
- [x] ~~**Rec 33 #33-2.6** — flip the v1 IPC command-loop default. The live
  command loop only links into `ghidra_dbg`; needs an end-to-end IPC test, not
  just the headless precheck (DD-0005).~~ **Already shipped** (entry was stale,
  caught 2026-06-10): the v1 framing tunnel landed as the 26.2.0 sprint close
  ([PR #189](https://github.com/CryptoJones/GayHydra/pull/189) — streambuf swap in
  `ghidra_process.cc` + `DecompileProcess.negotiateFramingV1`), with the
  end-to-end IPC test wired into CI by
  [PR #201](https://github.com/CryptoJones/GayHydra/pull/201) — DD-0005's path (a),
  executed. **Rec 33 is closed.**

---

## Sprint 15 — Fork sustainability (from the 2026-06-11 meta-review)

A top-to-bottom meta-review of the planning corpus + tree (2026-06-11) found the
per-slice discipline strong but four *one-level-up* risks unowned. This sprint owns
them. They are ordered by strategic weight, not effort.

- **Inbound upstream-merge strategy** — the existential fork risk. Everything under
  `docs/upstream-tracking/` was *outbound* (give-back). With ~9k LOC of fork-only
  production code and growing, every sprint makes the next upstream merge more
  expensive, and nothing measured or scheduled it.
  - [x] ~~**Measurement half** — add the `NationalSecurityAgency/ghidra` remote + a
    scheduled `upstream-drift.yml` publishing a drift report (commits behind,
    both-touched conflict candidates, worktree-safe `git merge-tree` dry-run
    conflict count).~~ Shipped 2026-06-11: `scripts/upstream-drift.sh` +
    `.github/workflows/upstream-drift.yml` (weekly Mon 05:11 UTC, auto-commits
    [`drift-report.md`](docs/upstream-tracking/drift-report.md)). First live
    numbers: merge-base `94164bd6e9` (2026-05-20 — the fork has **never** merged
    upstream), **203 behind**, 635 ahead, 80 both-touched, **74 dry-run conflicts**
    — including `decompile/cpp/Makefile`, the predicted Rec 34 collision.
  - [x] ~~**Policy half (Tier 3)** — `docs/upstream-tracking/MERGE_POLICY.md`: merge
    cadence, conflict playbook, fork-owned-paths manifest, collision watch-list.~~
    Drafted 2026-06-11 (cadence recommendation: each upstream stable tag, ad-hoc on
    security-relevant drift or conflicts crossing ~150 — **Aaron ratifies**). The
    guards it requires now exist (deep-CI, hint-recall corpus). Remaining: the
    **first actual sync** (`merge/upstream-<tag>` branch per the procedure), an
    Aaron-scheduled event.
- [x] ~~**Scheduled deep-CI job** — a nightly workflow running what PR CI never
  does: the fork-owned `test.slow` suites, a bounded fuzz smoke, and the
  decompiler-smoke gate against a master build.~~ Shipped 2026-06-11:
  `.github/workflows/deep-ci.yml` (nightly 03:23 UTC + dispatch), three jobs —
  `fork_integration_tests` (Cpp*/Rec 30/ScopeGraph suites by class-name filter, so
  the upstream headed suites stay out), `fuzz_smoke` (5 min each, ASan+UBSan),
  `master_smoke` (release.yml's gate vs a fresh master build). Standing the fuzz
  leg up **proved the dormancy finding**: both harnesses had bit-rotted against
  shipped RAII refactors (PR #46 removed `ingestBytes`, PR #82 changed `xml_tree`
  to `unique_ptr`), the Makefile's `CXX ?= clang++` was dead (make predefines CXX,
  so the documented build always picked g++ — no `-fsanitize=fuzzer`), and the
  minimal link closure had drifted. All repaired and container-verified against
  the CI runner image; the two gradle legs get first live validation via
  `workflow_dispatch` after push.
- [x] ~~**Rec 42 calendar lands in the queue** — Jython default-off is dated
  **2026-09-30** and removal **2027-01-31**; neither has a sprint or issue.~~ Filed
  2026-06-11: [#440](https://github.com/CryptoJones/GayHydra/issues/440)
  (default-off) and [#441](https://github.com/CryptoJones/GayHydra/issues/441)
  (removal, gated on checking upstream's Jython/PyGhidra posture first so deleting
  `Ghidra/Features/Jython/` doesn't create a permanent merge-conflict surface).
- [x] ~~**Rec 12 GHSA redo** — the oldest open security commitment (drafts lost in the
  repo deletion, carried across three sprint sections since).~~ Done 2026-06-11: the
  upstream remote made the archaeology possible — all three `GP-*` fixes **predate the
  fork's merge-base** (GP-6832/GP-6719 landed upstream 2026-05; GP-258 is from 2020), so
  no fork release was ever affected; the audit's GP-258 description was wrong (PE
  certificate parsing, not a `writeUserList` race). Three draft GHSAs created
  (GHSA-v5vp-434r-62c6, GHSA-q3fq-pjqf-c3m5, GHSA-58gj-m52h-89xx) with diff-reviewed
  descriptions and CVSS deliberately TBD per policy;
  [`retroactive-cve-tracking.md`](docs/security/retroactive-cve-tracking.md) table
  filled. **Publish-or-discard is Aaron's call** (fork-unaffected advisories may not
  merit a CVE mint).
- [x] ~~**Hygiene pass** — DD-0005 body correction (stale "clients use v1 by default"
  claim), duplicate-sprint-number note at the top of this file, governance docs
  reconciled to implementation status (sla-dashboard + lane-labeler Action are
  documented but unbuilt), `dependency-submission.yml` Gradle pin aligned to CI's
  8.5.~~ Shipped with the commit that added this section.

---

## Sprint 10 — OSS-Fuzz submission + Stage 3 finish + give-back PRs

**Done:**

- [x] ~~**Audit-datatests as ongoing regression guard**~~ — [PR #260](https://github.com/CryptoJones/GayHydra/pull/260) (weekly schedule).
- [x] ~~**Rec 25/26 Stage 3 pre-clean**~~ — PRs [#261](https://github.com/CryptoJones/GayHydra/pull/261), [#265](https://github.com/CryptoJones/GayHydra/pull/265), [#267](https://github.com/CryptoJones/GayHydra/pull/267), [#268](https://github.com/CryptoJones/GayHydra/pull/268), [#269](https://github.com/CryptoJones/GayHydra/pull/269), [#270](https://github.com/CryptoJones/GayHydra/pull/270), [#271](https://github.com/CryptoJones/GayHydra/pull/271) cleared the warning floor across every ≥5-warning subproject. javacc-generated source patched via a `buildJavacc` `doLast` hook.
- [x] ~~Consolidated datatest regex updates to NSA~~ — [NSA/ghidra#9207](https://github.com/NationalSecurityAgency/ghidra/pull/9207).
- [x] ~~`-dumpdir` audit-tooling flag to NSA~~ — [NSA/ghidra#9208](https://github.com/NationalSecurityAgency/ghidra/pull/9208).
- [x] ~~**Mac Mini bootstrap**~~ — `mac-mini` SSH alias (172.16.28.199) now has Homebrew + Temurin-21 + Gradle 9.5.1 + bison + flex + the repo. Driver at `~/bin/mac-mini-build`. First green build 6m31s cold, 3m53s incremental. Saved to memory at `~/.claude/projects/.../memory/macmini-build-host.md` so future sessions reach for it.
- [x] ~~**PIC-24F GE-recognition regression**~~ — [PR #275](https://github.com/CryptoJones/GayHydra/pull/275) ported the slaspec half of upstream NSA/ghidra#8778 and re-enabled the two `pic_branch_ge.xml` stringmatch tests. Closes [issue #259](https://github.com/CryptoJones/GayHydra/issues/259).

**Open:**

- [x] ~~**Rec 13/14 OSS-Fuzz submission**~~ — **rejected** 2026-05-26 by Google collaborator DavidKorczynski in [google/oss-fuzz#15545](https://github.com/google/oss-fuzz/pull/15545): *"I don't think a fork of Ghidra is a great match with OSS-Fuzz. We prefer projects with large user bases, so I suspect Ghidra itself would be an interesting match."* Soft policy reject — the reviewer's suggested path of submitting upstream NSA/ghidra is out-of-scope for this fork. Re-scoped: the underlying `fuzz_xml` / `fuzz_marshal` harnesses (in `Ghidra/Features/Decompiler/src/decompile/cpp/fuzz/`) stay as our own continuous-fuzzing infrastructure (runnable locally via `Makefile.fuzz` and, future-work, via our own CI); the OSS-Fuzz-specific `.github/oss-fuzz/` wrapper directory was deleted in [PR #84](https://github.com/CryptoJones/GayHydra/pull/84). Rec 14 (`projects/ghidra-loader/` Jazzer harnesses) deferred indefinitely for the same policy reason.
- [x] ~~**Stage 3 step 6 — `-Werror` + ErrorProne ratchet** — deferred per [PR #271](https://github.com/CryptoJones/GayHydra/pull/271). The local Mac Mini test surfaced an ErrorProne/-Werror Catch-22 (`allErrorsAsWarnings = true` degrades ErrorProne errors to javac warnings, which `-Werror` then promotes back to errors). Needs a global ErrorProne reconfiguration OR a per-file suppression sweep across the tree. Bigger than originally scoped — its own sprint.~~ **Dissolved by probe 2026-06-11**: ErrorProne's native ERROR severity fails builds with no `-Werror` involved, and a tree-wide forced-recompile probe measured the default-ERROR backlog at **zero** (124 main + 356 test compile tasks, 0 error lines) — so `allErrorsAsWarnings` flipped to `false` outright (`-PerrorProneLenient` escape hatch). Neither the global reconfiguration nor the suppression sweep was needed; the "own sprint" was one measurement. `-Xlint` warning ratchet unchanged (its own floor story).
- [x] ~~**`Automatic Dependency Submission (Gradle)`** pre-existing workflow failure~~ **Resolved** — the workflow runs green on master pushes as of 2026-06-10 (verified in the Actions run list); the linked issue number now resolves to an unrelated PR after the repo renumbering. Original text: — [issue #273](https://github.com/CryptoJones/GayHydra/issues/273): disable in repo Settings → Code security. In-tree fix attempted but only moves failure deeper (dbgeng TLB assert, then MarkdownSupport repos) — needs Aaron to click through Settings (no REST API).

**Release pipeline hardening (from gayhydra-dropper dogfood):**

The first end-to-end run of the new `samples/re-targets/gayhydra-dropper/` smoke test against the v26.1.6 prebuilt surfaced three release-pipeline regressions ([PR #321](https://github.com/CryptoJones/GayHydra/pull/321) findings). All shipped.

- [x] ~~**Cut v26.1.10 release.**~~ Shipped: [v26.1.10 release](https://github.com/CryptoJones/GayHydra/releases/tag/v26.1.10) with prebuilt zip (568 MB) + cosign zip/sbom signatures + bundled-CycloneDX SBOM. Closing the missing-prebuilt gap left by v26.1.7. The pipeline iterated v26.1.8 → v26.1.9 → v26.1.10 as each tag surfaced a different release-pipeline bug. Bugs 1–3 were each fixed in master before the next tag was cut and so are *in* the next tag's source tree (#327 → in v26.1.9 source, #331 → in v26.1.10 source). Bug 4 ([#333](https://github.com/CryptoJones/GayHydra/pull/333)) was fixed *after* v26.1.10 was tagged; v26.1.10's release artifacts only exist because a `workflow_dispatch` re-run picked up master's already-fixed workflow file. v26.1.10's source tree at the tag does NOT include #333; cloning at v26.1.10 and re-firing release.yml from a fresh repo would still hit the upload bug. v26.1.11+ will pick up #333 in source.
- [x] ~~**Wire `DumpDeobfuscate.java` into `release.yml`.**~~ Shipped: [PR #323](https://github.com/CryptoJones/GayHydra/pull/323), iterated by [PR #331](https://github.com/CryptoJones/GayHydra/pull/331) (orphan-XOR tolerance for Go-analyzer-crashed binaries). Post-build decompiler-sanity gate now runs as the third-from-last release step.
- [x] ~~**File upstream NSA/ghidra bugs.**~~ Shipped, with a correction: [NSA/ghidra#9219](https://github.com/NationalSecurityAgency/ghidra/issues/9219) (`GolangSymbolAnalyzer` EOFException on Go 1.25/1.26 binaries) stands; [NSA/ghidra#9220](https://github.com/NationalSecurityAgency/ghidra/issues/9220) was mis-attributed — the `-Djdk.serialFilterFactory=...` line lives in **our** fork's `launch.properties` (Rec 20 commit `1a64b67e`), not upstream's, and upstream maintainer correctly pushed back. Apologized and requested closure; the actual fork-side fix is tracked at [issue #80](https://github.com/CryptoJones/GayHydra/issues/80). See [Apologies.md 2026-05-26 entry](Apologies.md) for the chain.

**Cross-platform release coverage (added late 2026-05-24):**

- [x] ~~**Mac (arm64) build of v26.1.10.**~~ Shipped: `ghidra_26.1.10_GayHydra-26.1.10_20260524_mac_arm_64.zip` (567 MB) + `.sha256` uploaded as separate v26.1.10 assets on both forges. Built out-of-band on mac-mini (`mac-mini` SSH alias, macOS 26.5, 3m17s after clearing two stray `${sys:...}`-named files left by a prior log4j run). **Unsigned** — sigstore keyless chains to GHA OIDC, only available inside a GHA runner; out-of-band builds verify via SHA256 instead.
- [x] ~~**Windows (x86_64) build of v26.1.10.**~~ Shipped: `ghidra_26.1.10_GayHydra-26.1.10_20260524_win_x86_64.zip` (568 MB) + `.sha256` uploaded as separate v26.1.10 assets on both forges. Built out-of-band on the local QEMU Win11 VM (`win11-ci` SSH alias, port-forwarded to 127.0.0.1:2222) in 13m21s after a bootstrap session (Chocolatey: Temurin-21 + Gradle + git + winflexbison + python313 + VS Build Tools — Aaron noted post-hoc that VS Professional was already on the upstairs box; chose the QEMU path mostly because it works from anywhere now, including when traveling). Unsigned for the same out-of-band-no-OIDC reason as the mac zip; SHA256 verification path. **Going forward, v26.1.11+ ships windows automatically** via the matrix in `release.yml` (see "Matrix release.yml" item below), so this manual path is a one-shot v26.1.10 backfill.
- [x] ~~**Matrix release.yml over runners.**~~ Shipped: two new top-level jobs (`build_sign_publish_mac` on `macos-latest`, `build_sign_publish_windows` on `windows-latest`) added to `release.yml` alongside the existing linux job. Each runs the same buildGhidra → cosign-sign → upload-to-GH-release pattern, with platform-specific tooling installs (Homebrew bison/flex on mac, Chocolatey winflexbison+python313 and `ilammy/msvc-dev-cmd@v1` on windows). Both gated on the linux job's release-entry creation so uploads find an existing release. Codeberg-mirror logic mirrored too (gated on the same `CB_TOKEN` secret as the linux mirror). GHA on a public repo: $0 — confirmed free for all three runner OSes. Next tag push exercises the matrix end-to-end.

**Release-pipeline bugs found and fixed during v26.1.8 → v26.1.10:**

The smoke test wiring + first three release attempts together exposed four pipeline bugs that would have silently shipped broken releases to users without it:

| Tag attempt | Failing step | Bug | Fix |
|---|---|---|---|
| v26.1.7 | Fetch Dependencies | `cyclonedx-gradle-plugin` 3.x changed `schemaVersion` from `String` to `org.cyclonedx.Version` enum | [PR #245](https://github.com/CryptoJones/GayHydra/pull/245) (pre-this-sprint) |
| v26.1.8 | Locate release zip + extract bundled SBOM | unzip pattern `*/support/sbom/bom.json` was wrong; upstream NSA writes `bom.json` at top of zip-prefix dir | [PR #327](https://github.com/CryptoJones/GayHydra/pull/327) |
| v26.1.9 | Decompiler smoke test | post-script silently dropped XOR-0x5A instructions when no containing function existed (Go-analyzer crash) | [PR #331](https://github.com/CryptoJones/GayHydra/pull/331) |
| v26.1.10 (first run) | Upload signed artifacts to release | `gh release upload` requires the Release entry to pre-exist; tag-push doesn't auto-create | [PR #333](https://github.com/CryptoJones/GayHydra/pull/333) |

Each fix shipped as a self-contained PR with the corresponding bug as the linked issue. The smoke-test step has now been validated end-to-end on CI; future releases will fail-fast if any of these regress.

---

## Sprint 6 — finish the Sprint-1 implementation surface

**Quick wins:**

- [x] ~~Flip `.github/workflows/sync-labels.yml`'s `dry-run` to `false` after Aaron reviews the first workflow run.~~ Shipped: Aaron confirmed 2026-05-26 that the dry-run output looked correct; flipped to live mode.
- [x] ~~**Rec 11 follow-up:** track [NSA/ghidra#9202](https://github.com/NationalSecurityAgency/ghidra/pull/9202) and respond to reviewer comments.~~ Resolved 2026-06-10: upstream closed the PR without comments or reviews — nothing to respond to; tracking ends.

**External submissions:**

- [x] ~~**Rec 13:** submit `.github/oss-fuzz/` to [google/oss-fuzz](https://github.com/google/oss-fuzz) as `projects/ghidra-decompiler/`.~~ Rejected 2026-05-26 by Google; see canonical row in Sprint 10. Wrapper deleted in [PR #84](https://github.com/CryptoJones/GayHydra/pull/84); harnesses retained for local + own-CI fuzzing.
- [x] ~~**Rec 14:** same for `projects/ghidra-loader/` (JVM project, Jazzer harnesses).~~ Deferred indefinitely — same OSS-Fuzz policy rejection.

**Code-touching implementation:**

- [x] ~~**Rec 19 #19-2:** SafeObjectInput migration for `ItemDeserializer`~~ — [PR #293](https://github.com/CryptoJones/GayHydra/pull/293). Added `SafeObjectInput.headerStream()` helper; `CodeUnitInfo` reclassified out of Class A (not a deserialization surface — `CodeUnitInfoTransferable` uses `javaJVMLocalObjectMimeType`, no bytes cross OIS).
- [x] ~~**Rec 19 #19-3:** Class B regression test~~ — [PR #297](https://github.com/CryptoJones/GayHydra/pull/297). Class B sites (`ItemCheckoutStatus`, `Version`, `RepositoryItem`) confirmed structurally covered by GP-6719 RMI filter + XML-not-Java-serial on-disk path. Added `allowsClassBSites` regression test.
- [x] ~~**Rec 19 #19-5:** `AbstractDBTracePropertyMap` migration~~ — [PR #299](https://github.com/CryptoJones/GayHydra/pull/299). The last direct `new ObjectInputStream(...)` site in production. New `SafeObjectInput.openStream()` helper accepts a caller-supplied filter; baseline `allowlist()` (String + primitive wrappers) permits the adapter's String reads, rejects everything else.
- [x] ~~**Rec 19 #19-6:** enforcement gate~~ — [PR #301](https://github.com/CryptoJones/GayHydra/pull/301). `gradle objectInputStreamAudit` task forbids any new raw OIS construction outside `SafeObjectInput.java`. **Closes Rec 19** of the 42-rec audit.
- [x] ~~**Rec 25 Stage 2:** widen `-Xlint` to `deprecation,unchecked,rawtypes,cast`~~ — already shipped (see `gradle/javaProject.gradle` default `lintOpts`).
- [x] ~~**Rec 26 Stage 2:** `JavaUtilDate` and `JdkObsolete` ErrorProne checks at WARNING~~ — already shipped (see `gradle/errorprone.gradle`).
- [x] ~~**Rec 28 #28-5:** dead commented-out `//@Ignore` cleanup~~ — [PR #295](https://github.com/CryptoJones/GayHydra/pull/295) removed 7 dead lines in `MDMangBaseTest` + `CompositeMemberTest`. Active-`@Ignore` sweep continues as #28-6+.
- [x] ~~**Rec 28 #28-6+:** active-`@Ignore` fix-or-delete sweep — author-declared-not-a-regression-test sub-bucket cleared (PRs #26–#34, #36–#41 deleted 17 such sites); `ignoreAudit` task graduated to Stage 2 strict-by-default via [PR #43](https://github.com/CryptoJones/GayHydra/pull/43). Residual = 51 properly-categorized real tests (31 `wip` / 19 `blocked-on` / 1 `manual-tool`) all blocked on real upstream/cluster work; fix-the-real-blocker not delete-the-stub from here.~~

**Carried (still deferred):**

- [ ] 3 structural-drift upstream PRs (NSA#5593, NSA#3974, NSA#3137).

**Give-back PRs to NSA/ghidra:**

- [x] ~~Identify ports where our resolution is cleaner than the original and open follow-up PRs upstream.~~ **Done** — evaluated and documented in [`docs/upstream-tracking/give-back-candidates.md`](docs/upstream-tracking/give-back-candidates.md) (#377); actually opening upstream PRs is an Aaron-attended decision.

---

## Sprint 5 — More Sprint-1 implementation + give-back PRs (delivered — see SprintHistory.md)

**Carried from Sprint 4:**

- [ ] 3 structural-drift upstream PRs (NSA#5593, NSA#3974, NSA#3137) — decide whether to invest the hand-port effort or just close as "won't backport".

**Implementation surface — pick the cleanest next batch:**

- [x] ~~**Rec 11 follow-up:** track [NSA/ghidra#9202](https://github.com/NationalSecurityAgency/ghidra/pull/9202) and respond to reviewer comments.~~ Resolved 2026-06-10: upstream closed the PR without comments or reviews — nothing to respond to; tracking ends.
- [ ] **Rec 12:** open draft GHSAs for the three audit-named internal trackers (`GP-6832`, `GP-6719`, `GP-258`). Requires CVSS + affected-version range — needs git blame work to identify the affected commits in our fork. *(Status note 2026-06-10: drafts were created once and lost in the repo deletion — see Apologies.md / PR #15; the redo is the open work.)*
- [x] ~~**Rec 13:** submit `.github/oss-fuzz/` to [google/oss-fuzz](https://github.com/google/oss-fuzz) as `projects/ghidra-decompiler/`.~~ Rejected 2026-05-26 by Google; see Sprint 10 canonical row.
- [x] ~~**Rec 14:** same submission for `projects/ghidra-loader/` (JVM project, Jazzer harnesses).~~ Deferred indefinitely — same policy rejection.
- [x] ~~**Rec 17 #17-3:** add Cosign verification commands to release-notes template.~~ Shipped. The `gh release create` step in `.github/workflows/release.yml`'s upload block now emits a templated body containing the `cosign verify-blob` commands for both the zip and the bundled SBOM, plus a pointer to [`BINARY_SIGNING.md`](docs/security/BINARY_SIGNING.md). Identity-regex matches both push and `workflow_dispatch` invocations of `release.yml`.
- [x] ~~**Rec 19 #19-2:** first SafeObjectInput migration — `ItemDeserializer` + `CodeUnitInfo` (Class A sites — attacker-reachable).~~ Shipped — see Sprint 6 canonical row ([PR #293](https://github.com/CryptoJones/GayHydra/pull/293)).
- [x] ~~**Rec 21:** SBOM build-sanity gate (≥10 components or fail).~~ Shipped — the gate runs in `release.yml` ("SBOM sanity gate (≥10 components)").
- [x] ~~**Rec 25 Stage 2:** widen `-Xlint` to `deprecation,unchecked,rawtypes,cast`.~~ Shipped — see Sprint 6 canonical row (`gradle/javaProject.gradle`).
- [x] ~~**Rec 26 Stage 2:** `JavaUtilDate` and `JdkObsolete` ErrorProne checks at WARNING.~~ Shipped — see Sprint 6 canonical row (`gradle/errorprone.gradle`).
- [x] ~~**Rec 28 #28-5+:** broader `@Ignore` sweep across the ~25 other in-tree sites.~~ Done — see Sprint 6 `#28-6+` canonical row (sweep + `ignoreAudit` Stage 2).

**Give-back PRs to NSA/ghidra:**

- [x] ~~Identify ports where our resolution is cleaner than the original (e.g. PRs that hand-resolved a conflict by deleting commented-out lines) and open follow-up PRs back to upstream NSA/ghidra.~~ **Done** — evaluated and documented in [`docs/upstream-tracking/give-back-candidates.md`](docs/upstream-tracking/give-back-candidates.md) (#377); actually opening upstream PRs is an Aaron-attended decision.

---

## Sprint 4 — Security Foundations

The Rec 11–21 design surfaces shipped in v26.1; this sprint lands the
first implementation tier.

- [x] ~~**Rec 11 follow-up:** track [NSA/ghidra#9202](https://github.com/NationalSecurityAgency/ghidra/pull/9202) and respond to reviewer comments.~~ Resolved 2026-06-10: upstream closed the PR without comments or reviews — nothing to respond to; tracking ends.
- [ ] **Rec 12:** open draft GHSAs for the three audit-named internal trackers (`GP-6832`, `GP-6719`, `GP-258`); fill in CVSS + affected ranges. *(Status note 2026-06-10: drafts were created once and lost in the repo deletion — see Apologies.md / PR #15; the redo is the open work.)*
- [x] ~~**Rec 13:** submit `.github/oss-fuzz/` to [google/oss-fuzz](https://github.com/google/oss-fuzz) as `projects/ghidra-decompiler/`; verify first trial-run on their infrastructure.~~ Rejected 2026-05-26 by Google; see Sprint 10 canonical row.
- [x] ~~**Rec 14:** add `projects/ghidra-loader/` JVM project to OSS-Fuzz once Rec 13 is green.~~ Deferred indefinitely — same policy rejection.
- [ ] **Rec 18 PR #18-2:** `ItemDeserializer` hardening — declared-size precheck + running-counter cap + clean-up on failure (closes [upstream #1481](https://github.com/NationalSecurityAgency/ghidra/issues/1481)). Coordinate-disclose with NSA before publishing.
- [x] ~~**Rec 19 PR #19-1:** `SafeObjectInput` helper class + per-call allowlist + depth/byte caps + unit tests.~~ Shipped — `SafeObjectInput` exists with `headerStream()`/`openStream()` helpers and the `objectInputStreamAudit` gate (Sprint 6 rows, PRs #293/#299/#301).

---

## Sprint 5 — CI / Static-Analysis Foundations

- [x] ~~**Rec 17 PR #17-2:** Cosign keyless release-signing workflow wired into the release flow.~~ Shipped — `release.yml` signs zip + SBOM with cosign on all three runner OSes (Sprint 10 release rows).
- [x] ~~**Rec 17 PR #17-3:** update release-notes template with verification commands.~~ Shipped — see the Sprint 5 canonical row above.
- [x] ~~**Rec 21:** SBOM build-sanity gate (≥10 components or fail).~~ Shipped — gate in `release.yml`.
- [x] ~~**Rec 25 Stage 2:** widen `-Xlint` to `deprecation,unchecked,rawtypes,cast`. Pre-clean the floor in any subproject ≥50 warnings.~~ Shipped — Sprint 6 canonical row.
- [x] ~~**Rec 26 Stage 2:** turn on `JavaUtilDate` and `JdkObsolete` ErrorProne checks at WARNING.~~ Shipped — Sprint 6 canonical row.
- [x] ~~**Rec 28 PR #28-2:** inventory the six audit-named `@Ignore` tests; file tracking issues per rule.~~ Done — the `ignore:1y` issue cluster (#55–#69) is that inventory.
- [x] ~~**Rec 28 PR #28-3:** `gradle ignoreAudit` task + CI wiring.~~ Shipped — graduated to strict-by-default ([PR #43](https://github.com/CryptoJones/GayHydra/pull/43), Sprint 6 row).

---

## Sprint 6 — Decompiler Foundations: C++14/20 + RAII Stage 1–2

- [x] ~~**Rec 32 PR #32-2:** bump `-std=c++11` → `-std=c++14` in `decompile/cpp/Makefile`.~~ Shipped: [PR #310](https://github.com/CryptoJones/GayHydra/pull/310).
- [x] ~~**Rec 32 PR #32-3:** bump to `-std=c++20`.~~ Shipped: rolled in with [PR #314](https://github.com/CryptoJones/GayHydra/pull/314); same three sites as #32-2 (`buildNatives.gradle` Gcc/Clang blocks, `decompile/cpp/Makefile`, `decompile/cpp/fuzz/Makefile.fuzz`), flag-only change. Toolchain floor recorded in `docs/decompiler/CPP20_ADOPTION.md` (gcc ≥10, clang ≥12, MSVC 2019 16.10+/2022).
- [x] ~~**Rec 31 PR #31-2:** RAII Stage 1 — convert `address.cc`, `space.cc`, `range.cc` to `unique_ptr`. CI lint: no raw `new` in these files.~~ Shipped: the three foundation files were already raw-`new`-free in tree (only `new` mentions are in comments); the `gradle cppRaiiAudit` per-file gate was added to fail CI on any regression. Tree path uses `rangeutil.cc` (the file the audit named as `range.cc`).
- [x] ~~**Rec 31 PR #31-3 + Rec 32 PR #32-4:** RAII Stage 2 (`marshal.cc`, `xml.cc`) paired with `std::span` adoption in their parameter pairs. Joint review.~~ **Hand-written tree closed at v26.1.13** — 226/229 decompiler `.cc`/`.hh` files under `cppRaiiAudit`. Four remaining (`grammar.cc`, `pcodeparse.cc`, `slghparse.cc`, `slghscan.cc`) are bison/flex-generated, blocked on the Option A variant-mode rewrite (separate strategic sprint per [`docs/decompiler/RAII_STAGE_2C_XML.md`](docs/decompiler/RAII_STAGE_2C_XML.md)). Status of the originally-named files:
  - **`marshal.cc` RAII (#31-3 marshal half)** — shipped [PR #46](https://github.com/CryptoJones/GayHydra/pull/46). ByteChunk now owns its buffer via `unique_ptr<uint1[]>`; `~PackedDecode` no longer walks `inStream` to manual-`delete[]` the entries. `marshal.cc` / `marshal.hh` added to `cppRaiiAudit`'s `PROTECTED_FILES` so any regression that reintroduces raw owning pointers in those files fails CI.
  - **`marshal.cc` std::span (#32-4 marshal half)** — NOT shipped with #46. Deviation from the documented "same files, same PR" plan in `docs/decompiler/CPP20_ADOPTION.md`. Honest read: marshal's public API uses `[start, end)` pointer-pair ranges (`ByteChunk(uint1*, uint1*)`, `allocateNextInputBuffer` → `uint1*`), not the `(T*, size_t)` shape that `std::span` naturally replaces. The `std::span` adoption may not have an obvious site in marshal; revisit when the xml.cc half lands and decide whether to write a separate `std::span` migration PR for marshal or just accept that marshal didn't have a natural target.
  - **`xml.y` / `xml.cc` RAII (#31-3 xml half)** — Stage 2B + Stage 2C-min + Stage 2C steps 2/3 + audit-gate-add shipped. [PR #51](https://github.com/CryptoJones/GayHydra/pull/51) migrated `XmlScan::lvalue` (per-token string buffer); [PR #73](https://github.com/CryptoJones/GayHydra/pull/73) migrated `xml_parse`'s `global_scan` lifetime. Stage 2C: [PR #77](https://github.com/CryptoJones/GayHydra/pull/77) converted the `xml.y:208` `string *tmp` stack-local; [PR #78](https://github.com/CryptoJones/GayHydra/pull/78) refactored `Element::children` to `vector<unique_ptr<Element>>`; [PR #82](https://github.com/CryptoJones/GayHydra/pull/82) migrated all four `Document *` owners to `unique_ptr<Document>`. `cppRaiiAudit`'s `PROTECTED_FILES` now lists both `xml.y` and `xml.cc` with the four bison `%union` semantic-action sites (`xml.y:150,153,198,200` / `xml.cc:1598,1616,1736,1748`) as the only exclusions; everything else in both files is gated. **Remaining:** the bison semantic-action sites themselves, which block on the Option A `%define api.value.type variant` rewrite (its own strategic sprint per [`docs/decompiler/RAII_STAGE_2C_XML.md`](docs/decompiler/RAII_STAGE_2C_XML.md)).
  - **`xml.cc` std::span (#32-4 xml half)** — open. Audit it alongside the bison semantic-action `%union` redesign.

---

## Sprint 7 — IPC Modernization (Recs 33, 34)

- [x] ~~**Rec 33 PR #33-2:** framing v1 — greeting, CRC32, resync. v0 fallback active. **Design landed at [DD-0005](docs/decisions/0005-ipc-framing-v1.md)** — sequence is `#33-2.1` (`frame_v1.hh`/`.cc` + unit tests), `#33-2.2` (server-side reader with v0 fallback), `#33-2.3` (server-side writer), `#33-2.4` (greeting handshake), `#33-2.5` (Java-side wiring + default flip to v1).~~ **`#33-2.1`–`#33-2.5` all shipped in v26.1.16** (see DD-0005's post-#33-2.5 status section — this entry was stale). Both ends negotiate a v1 *greeting*; the command loop deliberately stays v0 on both sides. `#33-2.6` (the command-loop flip) also shipped later — 26.2.0 sprint close ([PR #189](https://github.com/CryptoJones/GayHydra/pull/189)) with the e2e IPC test in CI ([PR #201](https://github.com/CryptoJones/GayHydra/pull/201)). **Rec 33 is closed.**
- [x] ~~**Rec 34 PR #34-2:** vendor FlatBuffers C++ headers + Java jar.~~ Shipped (prerequisite of the landed schema/codecs below).
- [x] ~~**Rec 34 PR #34-3:** land `decompile.fbs` schema + generated bindings.~~ Shipped — `src/decompile/cpp/schema/decompile.fbs` in tree.
- [x] ~~**Rec 34 PR #34-4:** dual-encode the decompile-function request path.~~ Shipped — host-side encoder ([PR #213](https://github.com/CryptoJones/GayHydra/pull/213)), worker-side codec ([PR #211](https://github.com/CryptoJones/GayHydra/pull/211)); band advanced through `#34-5a` (#215) and the `fuzz_ipc_schema` harness `#34-9` (#227). **Truth note (2026-06-11, DD-0080):** the *codec halves* shipped; the live dual-encode wiring did not — every `#34-4`..`#34-6` codec is deliberately inert (test/fuzz-only; the production Makefile rules can't even compile them), the live loop is v0 in both directions, and no Java v1 response decoder exists. Go-live is the `#34-10` band below; the `#34-7`/`#34-8` removal clocks re-key to go-live.
- [ ] **Rec 34 #34-10 band — payload-v1 go-live** (DD-0080): wire the inert codecs into the live
  loop behind two greeting capability bits (`SCHEMA_V1_REQUESTS` worker-side,
  `SCHEMA_V1_RESPONSES` host-side) and a `SCHEMA_PAYLOAD` frame flag whose payload is
  `[u8 command-id][FlatBuffers bytes]`. Requests before responses; smallest command first; the
  PR #201 `ipc_e2e` harness gains a payload-v1 leg from `#34-10b` on. Signature commands + the
  mid-decompile callback sub-protocol are carved out (no schema tables — future `#34-11`).
  - [x] ~~**#34-10a** — capability bits + `SCHEMA_PAYLOAD` flag + command-id registry, both ends
    (pure layer, unit-testable; negotiated but unused).~~ Shipped: `frame_v1.hh` gains
    `flags::SCHEMA_PAYLOAD` (0x08) + `capab::SCHEMA_V1_REQUESTS`/`_RESPONSES` (0x04/0x08) with
    advertise/record greeting overloads (production call sites unchanged — byte-identical wire);
    the host records the peer's CAPABS (`getPeerCapabilities`/`peerAcceptsSchemaV1Requests`);
    the command-id registry ships as twins — `schema/ipc_command_ids.h` (deliberately
    FlatBuffers-free, so the live loop can include it without Makefile changes) and
    `IpcCommandId.java` — locked by pinned-value tests on both sides (the framing-CRC
    golden-vector pattern). C++ 334/334 (9 new); Java framing 15/15 (+2), registry 4/4.
  - [x] ~~**#34-10b** — worker v1-request dispatch + `flushNative` go-live end-to-end;
    `FLATBUF_INCLUDE` enters the production compile rules; `ipc_e2e` payload-v1 leg.~~ Shipped —
    **the first live schema-v1 payload**: `FrameInStreambuf` surfaces per-frame FLAGS
    (`lastFlags`/`takeSchemaPayload`); `readCommand` dispatches a `SCHEMA_PAYLOAD` frame at the
    command boundary through the registry to a new `doitSchema`/`loadParametersV1` path (unknown
    id → existing bad-command response); the worker advertises `SCHEMA_V1_REQUESTS`; the host
    sends `flushNative` as `[0x03][FlushNativeRequest]` when advertised, behind a
    `decompiler.schemapayload` kill switch (DD-0005 staging pattern, default auto) with a
    sent-counter test observable. FlatBuffers entered the production builds (Makefile `ghi_*`
    rules + a `buildNatives.gradle` exportedHeaders srcDir). Responses stay v0 (#34-10f+).
    Ronin28: C++ 336/336, `ghidra_dbg` builds clean, Java framing 18/18, e2e 5/5 (3 new legs:
    advertisement, flush-survives, kill-switch).
  - [x] ~~**#34-10c** — lifecycle remainder (`registerProgram`, `deregisterProgram`).~~ Shipped —
    mechanical against the `#34-10b` dispatch: two `loadParametersV1` overrides on the worker
    (four-spec decode for register; FlushNative-twin id parse for deregister) and two host
    branches through a new `writeSchemaRequest` core, split out because registerProgram must
    keep its callback decoders alive across the send (the worker queries back during
    registration regardless of request encoding) while the simple commands null them. Every
    e2e leg now exercises schema register/deregister implicitly via openProgram/dispose.
    Ronin28: C++ 336/336, `ghidra_dbg` clean, e2e 5/5 (forced rerun).
  - [x] ~~**#34-10d-1** — `setAction` goes schema-v1.~~ Shipped — mechanical branch + override,
    plus the rule-of-three `parseSchemaProgramId` extraction (third user: FlushNative,
    DeregisterProgram, SetAction). Every e2e leg exercises it via openProgram's mandatory
    setAction. Ronin28: C++ 336/336, `ghidra_dbg` clean, e2e 5/5 (forced rerun).
  - [x] ~~**#34-10d-2** — `setOptions` + `structureGraph` go schema-v1 **as XML text** (DD-0080
    addendum): their packed-binary documents cannot ride FlatBuffers UTF-8/NUL-unsafe `string`
    fields, so the host encodes the same document with `XmlEncode` and the worker's
    `loadParametersV1` constructs an `XmlDecode` — no schema change, format-agnostic `Decoder`
    consumers unchanged.~~ Shipped 2026-06-11 — the config trio completes. Host side: the
    encoding choice can't live behind the command-name branches (the document is already
    encoded by then), so `DecompileProcess` exposes `schemaDocumentCommandsAvailable()` and
    `DecompInterface` picks `XmlEncode` before encoding; `sendStructureGraphSchema` keeps the
    v0 timeout + callback semantics. Two new e2e legs assert each command rides schema-v1
    (sent-counter) and is output-byte-identical to v0. The `ipc_config_codec.h` "inert"
    marker (stale since `#34-10d-1`) corrected per DD-0080's rule. Validated in the podman
    rig (this box has no host toolchain): Java framing/ipc fast suites green, C++
    `decomp_test_dbg` 336/336, e2e 7/7. **Request direction lacks only `decompileAt`
    (`#34-10e`).**
  - [x] ~~**#34-10e** — `decompileAt` incl. the `DecompileBudget` sub-table. Completes the request
    direction; starts the re-keyed `#34-7` clock (one release after this ships).~~ Shipped
    2026-06-11 in grounded form (second DD-0080 addendum): the entry rides as a verbatim
    **default-space bare offset** (the schema has no field to name a space — any other space
    falls back to v0 per call, narrowing `#34-7`'s removal scope until an `address_space`
    field append), and the `DecompileBudget` sub-table stays **absent on the wire** (the
    five-cap sketch never matched the shipped `#35-4` options-document budget; no worker
    implementation exists). New e2e leg isolates the decompileAt send and asserts
    byte-identical output v0↔schema. Podman rig: Java fast suites green, C++ 336/336,
    e2e 8/8. **Request direction complete — the `#34-7` clock starts at the next release;
    the `#34-10f+` go/no-go checkpoint is open (see the DD-0080 response-direction
    addendum).**
  - [ ] **#34-10f+** — response direction: host-side `DecompileResponseCodec` (new), worker emit
    behind `SCHEMA_V1_RESPONSES`, command-by-command.
  - [ ] **#34-7 (re-keyed)** — remove the host v0 request encode for the seven schematized
    commands, one release after `#34-10e`.
  - [ ] **#34-8 (re-keyed + subordinated)** — remove the worker v0 request decode: deferred to
    the v27.x horizon with DD-0005's framing-v0 fallback, so an upstream-Ghidra host driving the
    GayHydra worker keeps working (DD-0080 resolves the contract tension in DD-0005's favour).

---

## Sprint 8 — Decompiler UX Wins (Recs 35, 36, 39)

- [x] ~~**Rec 35 PR #35-2:** add `DecompileBudget` to request schema; yield-point checks in `flow_analysis` and `data_flow`.~~ Shipped in re-scoped form — per-pass budget + bypass façade (`#35-4`/`#35-4b`, PRs #243/#251), GUI budget + partial detection (`#35-5a-1`/`-2`, PRs #306/#308).
- [x] ~~**Rec 35 PR #35-3:** UI partial-result banner + Retry-with-2x path.~~ Complete 2026-06-11: the banner is `#35-5b-1` (PR #310); the Retry action shipped as `#35-5b-2` behind the Xvfb GUI test layer (see the Sprint 14 row). **Rec 35 is closed.**
- [x] ~~**Rec 36 PR #36-2 + PR #36-3:** per-function dependency bitmaps replace global-flush invalidation.~~ Shipped in re-scoped form per [DD-0009](docs/decisions/0009-rec36-incremental-invalidation.md): `#36-2` demoted into `#36-3b`; address-set invalidation `#36-3a` + recompute backstop `#36-3b-2` (PR #293) landed, plus the `#36-5a`/`b` telemetry (PRs #299/#301).
- [ ] **Rec 36 PR #36-4:** in-place rewrite paths for local rename / type / comment. *(Evidence-gated per DD-0009 addendum 8 — not merely deferred: the layout bakes token text, upstream deliberately re-decompiles on rename, and #36-3a already reduced the cost to one function's decompile. Revisit only if the shipped #36-5 telemetry, read from real sessions, shows measured user-visible cost. Building it speculatively would reintroduce the staleness risk Rec 36 removed.)*
- [x] ~~**Rec 39 PR #39-2 + #39-3:** `for`-loop detection + datatest corpus.~~ **Already provided by upstream** — `BlockWhileDo::finalTransform`/`PrintC::emitForLoop` (gated by `analyze_for_loops`, default on) already render canonical `for` loops, and upstream's `forloop*.xml` / `noforloop*.xml` datatests cover + guard it (verified passing 2026-06-03). No fork reimplementation; see the "Phase 1 status" section in [`FOR_LOOP_INLINE_DETECTION.md`](docs/decompiler/FOR_LOOP_INLINE_DETECTION.md). Remaining Rec 39 value is Phase 2 below.
- [x] ~~**Rec 39 PR #39-4 + #39-5:** inlined-library-call detection — the genuinely novel fork work.~~ Sequence-shaped phase **complete**: `#39-4a` memset (Sprint 8 row) and `#39-4b` SWAR popcount → native `CPUI_POPCOUNT` ([PR #261](https://github.com/CryptoJones/GayHydra/pull/261), `datatests/popcount.xml`). Loop-shaped phase (`#39-6a` strlen…) deferred — needs new loop-collapse infrastructure (DD-0008 addendum, PR #267). Original text: **Design landed at [DD-0007](docs/decisions/0007-rec39-phase2-inline-detection.md):** upstream *already* renders inlined string copies as `builtin_memcpy`/`builtin_strncpy` (CALLOTHER + builtin user-ops in `constseq.cc`/`userop.cc`), so the real gap is `memset`/`popcount` (sequence-shaped, tractable) and `strlen`/`strcmp`/`memcmp`/copy-loops (loop-shaped, harder). Decision: **extend that mechanism** with per-pattern C++ rules + new builtins, **not** the originally-proposed XML pattern-library engine. Implementation starts with #39-4a (`BUILTIN_MEMSET`/`RuleMemset`), then #39-4b (`popcount`); loop-shaped patterns get their own sub-DD. **#39-4a shipped:** `RuleMemset` folds a run of equal-constant STOREs into `builtin_memset`, reusing `HeapSequence` in a fill mode and running after `RuleStringStore` so it claims only the zero-fills / non-char fills that rule declines (zero regression; `datatests/heapmemset.xml`).

---

## Sprint 9 — Strategic: C++ Frontend (Rec 37)

**Headless model + feeder layer delivered 2026-06-06 as v26.3.0 — see [SprintHistory.md](SprintHistory.md) (Sprint 13).** The headless deliverables shipped as *feeders* (model-only); the Program-coupled *analyzer*/recognition wrappers the items below named roll to **Sprint 14**.

- [x] ~~**PR #37-2:** `CppTypeSystem` skeleton + tests.~~ Shipped (DD-0011).
- [x] ~~**PR #37-3:** `CppDemanglingFeeder`.~~ Shipped (DD-0012).
- [x] ~~**PR #37-4:** `CppRttiAnalyzer` (Itanium).~~ Shipped as `CppRttiFeeder` (DD-0013); Program-coupled analyzer → Sprint 14.
- [x] ~~**PR #37-5:** `CppRttiAnalyzer` (MSVC). **→ Sprint 14** (not started).~~ **Complete in Sprint 14** (DD-0039..41, DD-0061, DD-0063).
- [x] ~~**PR #37-6:** `CppVTableAnalyzer`.~~ Shipped as `CppVTableFeeder` + `CppVtableReconciler` (#37-6c, DD-0014/DD-0015); Program-coupled analyzer → Sprint 14.

---

## Sprint 10 — Strategic: Variable Naming (Rec 38)

- [x] ~~**PR #38-2:** `ScopeNode` and `ScopeEdge` schema + storage layer.~~ **Complete** (both slices below).
  - [x] ~~**#38-2a** — the model half.~~ Shipped (DD-0074): `ScopeNode`/`ScopeEdge`/`ScopeGraph`
    in `ghidra.app.util.scope` — value-semantic, idempotent, with the undirected `SAME_VALUE`
    component walk rename propagation needs. Grounded: the RFC's table schema has no public
    extension point (`ProgramUserData` = address-keyed maps, not tables); only user-asserted edges
    need durability. Suite 10/10.
  - [x] ~~**#38-2b** — persistence codec: encode `userAssertedEdges()` through `ProgramUserData`
    string properties (fork-owned owner key); per-user durability acceptable (everything else
    recomputable).~~ Shipped: `ScopeGraphUserAssertions` — one versioned program-level string
    property; fresh-program/unknown-version/corrupt-line loads are never-wrong (skip, not fail);
    names percent-escaped; empty save removes the property. Codec 7/7. **Completes #38-2.** Next:
    **#38-3** static-analysis populator.
- [ ] **PR #38-3:** static-analysis populator.
  - [x] ~~**#38-3a** — deterministic identity minting.~~ Shipped: `ScopeGraphStaticPopulator` —
    one `Parameter` node per function parameter slot, one `GlobalAddress` per defined data unit;
    identity only (edges are the dataflow source's and the user's per the RFC split); idempotent,
    cancellable. Suite 5/5.
  - [x] ~~**#38-3b** — struct-field identities from references.~~ Shipped: references into defined
    `Structure` units resolve to their containing component and mint type-level
    `StructField(structure, offset)` identities shared across instances — the RFC's "same struct
    field across loads", evidenced not speculative. Populator 7/7. **The deterministic static
    source is complete.** Remaining `#38-3`: the dataflow source (heuristic, the RFC's "hardest
    case", behind a confidence threshold — its own grounded band).
  - [x] ~~**dataflow slice 1** — pass-through parameters.~~ Shipped (DD-0075):
    `ScopeGraphDataflowPopulator` — caller-parameter forwarded in a resolved direct `CALL` →
    `SAME_VALUE` edge between the two `Parameter` slots at confidence 0.9/`DATAFLOW` (both
    endpoints deterministic; the RFC's motivating case). Locals deferred until a stable
    `LocalEquiv` id scheme exists (per-decompile `HighVariable` identity would not survive
    recomputation). Harness 3/3.
  - [x] ~~**stable-id design** — `LocalEquiv` keys by canonical storage string.~~ Shipped
    (DD-0076): storage is the decompile-invariant anchor (Ghidra's own `HighSymbol`↔DB-local
    matching); opaque ids invite unstable minting, hashes are not identities. Codec `L` tag carries
    the escaped key; landed before anything persisted real `LocalEquiv` nodes (no format bump).
    Suites 10/7/7.
  - [x] ~~**local-argument dataflow minting.**~~ Shipped (DD-0075 addendum): probe showed a
    stack-local argument's `HighSymbol` carries concrete storage even with no DB local — the
    populator mints `LocalEquiv(callerEntry, storageKey)` alongside the parameter case. RFC-0002's
    full motivating case flows end-to-end. Harness 4/4. **Remaining Rec 38: only `#38-4` UI
    (DISPLAY-gated).**
- [x] ~~**PR #38-4:** rename-propagation UI + opt-in dialog. *(DISPLAY-gated, like the other GUI
  tails.)*~~ **Minimal slice shipped 2026-06-11** behind the Xvfb layer — the scope graph's
  first production consumer. Deliberately an *explicit* action ("Propagate Name to Same-Value
  Peers", on a parameter token) rather than a rename-flow interception: the headless
  `ScopeGraphRenamePropagator` walks the `SAME_VALUE` component (graph populated from the
  function + its direct callers via the DD-0075 dataflow populator), resolves `Parameter`
  peers to live slots, and applies the name with per-peer collision-skip; the action wraps it
  in the RFC-0002 confirmation dialog + transaction. Propagator 3/3 (headless, the pass-through
  fixture taken to a live rename), headed install test under Xvfb. Remaining #38-4 tail:
  `LocalEquiv` peer renames (needs a decompile-context rename surface), the automatic
  on-rename hook + per-project remember setting, and user-asserted-edge UI — each its own
  slice now that the consumer exists.

---

## Sprint 11 — Sleigh Formalization (Rec 40)

- [x] ~~**PR #40-2:** BNF grammar at `docs/sleigh/grammar.bnf` + CI drift-detection job.~~
  Shipped (DD-0077): complete EBNF derivation of `slghparse.y` (error-recovery productions
  excluded by policy; symbol-feedback and lexer-modality documented in the header) +
  `sleighGrammarAudit` sha256-pin drift gate (root task + CI step beside `cppRaiiAudit`).
  **Parity follow-up dissolved (DD-0078)**: the tree already ships a second canonical Sleigh
  implementation (the Java ANTLR3 compiler behind `sleighCompile`, parsing every `.slaspec` on
  every CI build) — its seven language-defining `.g` files joined the pin set instead of building
  a duplicated third parser. Audit verified at 9 pins. **Workstream 1 complete in final form.**
- [ ] **PR #40-3 + #40-4:** semantic model document.
  - [x] ~~**#40-3** — disassembly half.~~ Shipped: `docs/sleigh/semantic-model.md`, 24 constructs
    (definitions, attach forms, constructors/display, pattern equations, context actions,
    with-blocks, macro declarations) per the plan's template; every C++ anchor verified.
    Remaining: **#40-4** RTL/expression layer (assignments, operator→`CPUI_*` map incl. the
    swapped-comparison canonicalisations, flow statements, export, sized deref, address-of).
  - [x] ~~**#40-4** — RTL/expression layer.~~ Shipped: sections 6–9 complete the model — flow
    table, operator→`CPUI_*` map with the swapped-comparison canonicalisations as written
    contract, export's handle-template result model, macro-expansion edges, NOP-vs-`unimpl`.
    Every anchor verified. **Workstream 2 complete.** Remaining Rec 40: `#40-5+` differential
    fuzzer (months-scale; needs Unicorn et al. — its own sprint) and the deferred ANTLR parity
    slice.
- [ ] **PR #40-5:** differential-fuzzer framework + x86-64 via Unicorn.
  - [x] ~~**#40-5a** — architecture DD.~~ Shipped (DD-0079): the sprint splits at the *reference
    seam* — slice 1 is a zero-new-dependency Ghidra-side `SleighInstructionExecutor` on the
    in-tree `PcodeEmulator` equivalence-test pattern, golden-case-validated, with a pluggable
    `DifftestReference` interface; Unicorn/QEMU adapters wait behind it for Aaron's vendoring
    approval. Test-layer first (the Rec 30 precedent).
  - [x] ~~**#40-5b** — `SleighInstructionExecutor` + golden x86-64 cases (implements DD-0079
    slice 1).~~ Shipped: the Ghidra half executes one instruction through the in-tree
    `PcodeEmulator` and samples registers, behind the `DifftestReference` seam; goldens incl. the
    `CF` carry-out case all hand-computed. Suite 4/4.
  - [ ] **#40-5c+** — reference adapters (vendoring: **Aaron-gated**), pattern-driven generation,
    CLI packaging.

---

## Sprint 12 — Strategic: Decompiler Hints (Rec 37 cont.)

**Renderers delivered 2026-06-06 as v26.3.0 — see [SprintHistory.md](SprintHistory.md) (Sprint 13).** The stateless *renderer* half of every form shipped — all seven: virtual call, up/down-cast, construction, explicit destructor, array `new[]`, placement-new, `delete` (#37-7 … #37-9f, DD-0016..DD-0022). The Program-coupled *recognition* half (detecting each idiom in a live `HighFunction` and dispatching to the matching renderer) is the "headless ceiling" — it rolls to **Sprint 14**.

- [x] ~~**PR #37-7 / #37-8 / #37-9 + #37-9c/9d/9e/9f:** `CppDecompilerHints` renderers (upcast/downcast, vmethod calls, ctor/dtor, array/placement/delete).~~ All seven renderer forms shipped (headless); recognition → Sprint 14.

---

## Backlog

Work that doesn't fit a current sprint but is documented in the audit:

- **Rec 7:** opt-in PRs from community contributors to populate [`MAINTAINERS.md`](MAINTAINERS.md).
- ~~**Rec 23:** expand the `unit_tests` job from Ubuntu-only to multi-OS once Linux baseline is stable.~~ **Shipped at v26.1.14** ([PR #162](https://github.com/CryptoJones/GayHydra/pull/162)) — `build-ghidra.yml`'s `unit_tests` job now runs on `[ubuntu-latest, macos-latest, windows-latest]`, matching the already-multi-OS `build` job.
- **Rec 24:** add Windows (MSVC) to the C++ decompiler test workflow. **Strategic sprint pending** — see [DD-0004](docs/decisions/0004-decompiler-cpp-tests-windows.md): gated on picking a Windows libbfd-substitute approach (port vs. exclude vs. stub) and writing the MSVC `CMakeLists.txt` that replaces the GCC `Makefile`. MinGW shortcut explicitly rejected to avoid carrying two Windows toolchains.
- **Rec 30:** headless test layer — ships after Rec 29 (JUnit 5) is partway through; opportunistic.
- ~~**Rec 31 Stages 3–8:** the bulk of the RAII migration.~~ **Closed at v26.1.13** — see Rec 31 row in Sprint 6 above. Hand-written tree at 226/229 = 99% under audit gate. The four remaining (bison/flex-generated) need the Option A variant-mode rewrite, tracked separately.
- ~~**Rec 34 PRs #34-5 through #34-8:** the rest of the FlatBuffers migration + v0 removal.~~
  **Superseded by the `#34-10` go-live band** (Sprint 7 row, DD-0080): the `#34-5`/`#34-6` codec
  halves shipped inert in v26.2.2; the genuinely open work is the live wiring, after which the
  re-keyed `#34-7`/`#34-8` removals follow.
- **Rec 41:** opt-in PRs to fill the per-architecture maintainer column.
- **Rec 42 milestones:** 2026-09-30 default-off Jython; 2027-01-31 removal. *(Now
  owned by Sprint 15 — file the dated issues.)*
- Mirror to Codeberg once their repo-creation gateway recovers.
- **DISPLAY-ceiling reframe (meta-review 2026-06-11):** ~~Rec 35 `#35-5b-2`
  (retry-with-2x action), Rec 38 `#38-4` (rename-propagation UI), and the Rec 37 GUI
  hints margin are all parked on "needs a DISPLAY" — build the Xvfb enabler first.~~
  **Enabler shipped 2026-06-11**: `XvfbPilotHeadedTest` (fork-owned, feature-free —
  a real tool frame showing on a real X display via `AbstractGhidraHeadedIntegrationTest`
  + `TestEnv`) passes under `xvfb-run` (1/1, ~2s; container-verified — the only gap
  was AWT's X client libs, which GHA runners already carry);
  `.github/workflows/xvfb-gui-tests.yml` runs it weekly + on dispatch, with the
  filter list as the landing pad for each GUI feature's own headed tests. **The
  DISPLAY ceiling is empirically a door — `#35-5b-2`, `#38-4`, and the hints margin
  are now unblocked** and land behind this layer, each bringing its own headed test.
  (The `HEADLESS_TEST_LAYER.md` `#30-2`..`#30-7` view-interface layer remains the
  deeper alternative; reassess its motivation now that this exists.)
- **Surfacing & measurement band (meta-review 2026-06-11)** — sequenced *before*
  any new Rec 37 recognition forms, because shipped-but-invisible features and
  unmeasured quality compound:
  - *User-facing docs for the C++ hints pipeline.* The flagship Rec 37 feature is
    reachable only via `RecoverCppHintsScript` and no user-facing document says it
    exists. One docs page (enable, run headless via `-postScript`, read the `C++:`
    PRE comments) + README/CHANGELOG pointers; consider an opt-in tool-menu/analyzer
    surfacing path.
  - *Recall corpus.* ~~Every Rec 37 matcher is grounded on x86-64 / one compiler's
    idioms (per their own javadoc; no `Language` gating exists in
    `ghidra.app.util.cpp`). The never-wrong contract protects precision, but recall
    on arm64/MSVC-codegen/-O2 binaries is unmeasured, and upstream-decompiler idiom
    churn could silently collapse it — the tests pin fork fixtures, not the real
    output distribution. Build a small fixed corpus (gcc/clang/msvc × x86-64/arm64 ×
    O0/O2) with counted hint hits as a tracked recall metric.~~ **Shipped
    2026-06-11**: `samples/hint-recall-corpus/` (8 committed ELF objects: gcc/clang
    × x86_64/aarch64 × O0/O2; MSVC PE column deferred to the win11-ci box) +
    `CountCppHintRecallScript` + `scripts/hint-recall.sh` + `baseline.json`, wired
    into deep-ci's `master_smoke` job (fails on any per-form drop). **First real
    numbers prove the gap**: only `DELETE` fires (2/binary) — every type-resolving
    form is 0 across all 8 cells because the production analyzers gate on MSVC PE
    and the ELF type system goes unfed. Closing the gap (an Itanium-RTTI analyzer
    leg, the PE column) is now a measured, baseline-locked workstream.
  - *Perf baseline.* Rec 35/36 are performance recs with no benchmark loop anywhere
    in CI — a 2x decompiler slowdown would ship silently. Add a decompile-throughput
    baseline over the same corpus + a regression threshold. This also resolves the
    Rec 36 `#36-4` telemetry gate honestly: nothing collects "real session"
    telemetry today, so either build a collection path or restate the gate as
    closed-until-users-exist.

---

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
