---
number: 0014
title: Rec 37 #37-6 — the vtable path feeds slot methods into CppTypeSystem through a pure recovered-slot-fact→CppVTable mapper in Features/Base; the program-scan stays a deferred wrapper
status: accepted
date: 2026-06-06
audit_rec: 37
---

# Decision 0014: the vtable slice ships a headless slot-fact mapper (recovered vtable slots → CppVTable + CppMethod.isVirtual), not a program-scanning analyzer

## Context

[DD-0011](0011-rec37-cpptypesystem-skeleton.md) shipped the model-only
`CppTypeSystem` skeleton (#37-2); [DD-0012](0012-rec37-demangling-feeder.md)
shipped the first producer, the `CppDemanglingFeeder` (#37-3); and
[DD-0013](0013-rec37-rtti-inheritance-feeder.md) shipped the second, the
`CppRttiFeeder` (#37-4) — both pure mappers in `Ghidra/Features/Base` package
`ghidra.app.util.cpp`, deliberately decoupled from how (and in which module)
their input facts are recovered, and fixture-tested with no `Program`.

This DD grounds the next *producer* slice, [RFC-0001](../rfcs/0001-cpp-frontend.md)'s
#37-6 `CppVTableAnalyzer`. The RFC describes it as: *"The current vtable
recovery output (a list of function pointers at a known address) is the input;
the output is a `CppVTable` with each slot mapped to a `CppMethod`."* As with
#37-3 and #37-4, that sentence hides two very different jobs — **walking a
program's recovered vftable** (reading the function-pointer array out of memory,
resolving each slot's `Function`, demangling its name) and **translating the
recovered slot list into the model's `CppVTable` + `CppMethod` slots**. This DD
draws the same line DD-0012/DD-0013 drew and, as before, fixes a slice *shape
that is headlessly unit-testable with no `Program` and no cross-module
dependency* — the [[always-test-before-push]] bar.

This is also the slice that finally populates `CppMethod.isVirtual`, which
DD-0011 and DD-0013 both deferred here: a method occupying a vtable slot is, by
definition, virtually dispatched.

## What the in-tree vtable pipeline provides (grounding)

### Ghidra already recovers vtables — split across script-land and the MSVC module

Both producers are coupled to a live `Program` and neither is a reusable type
`Features/Base` can import:

- **Script-land (Itanium + general).**
  `Ghidra/Features/Decompiler/ghidra_scripts/classrecovery/Vftable.java`
  (package `classrecovery`) is the per-vftable model: it carries
  `List<Address> vfunctions` (`addVfunction(Address)` `:87`,
  `getNumVfunctions()` `:91`) — function pointers into a live program image —
  and is constructed from an `Address`/`Namespace`/`Vtable`. `RecoveredClass`
  tracks the recovered slots and order in `getAllVirtualFunctions()` `:117`,
  `getVirtualFunctions(Address vftableAddress)` `:113`, and
  `getOrderToVftableMap()` `:182`; `GccTypeinfo.getVtableAddress()` `:67` is the
  recovered table address. These run as Ghidra scripts (package `classrecovery`),
  not a published module API — the identical non-importability DD-0013 hit with
  `GccTypeinfo`.
- **Module-land (MSVC).**
  `Ghidra/Features/MicrosoftCodeAnalyzer/src/main/java/ghidra/app/cmd/data/rtti/VfTableModel.java`
  *is* a real module type, but it is constructed over a `Program` + an `Address`
  and validated against program memory — so consuming it still requires the
  program-scan + analysis harness, and a `Base`→`MicrosoftCodeAnalyzer`
  dependency would invert the module graph besides.

### Two facts that constrain the #37-6 slice's shape (mirroring DD-0012/DD-0013)

1. **The recoverers are Program-coupled and (for the Itanium path)
   script-land.** Reading slot function pointers out of memory and resolving
   each to a `Function` needs a real `Program` + `ServiceProvider`; the
   script-land `classrecovery.Vftable` cannot be imported from `Features/Base`
   any more than `GccTypeinfo` could.
2. **Walking the vftable is exactly the GUI/integration coupling the headless
   cores keep out.** A #37-6 that *scanned* could not be validated by
   `gradle :Base:test`.

Both facts point the same way they did for #37-3 and #37-4: the headless core
must be a *consumer of already-recovered slot facts*, decoupled from how those
slots were read out of the program, with tests that build the facts directly.

### The model already has the destination (from #37-2)

`CppVTable` (shipped #37-2) is a mutable holder with `addSlot(CppMethod)`
appending in layout order (`:46`), `getSlots()` / `getSlotCount()`, and an
optional `setTableAddress(Address)`. `CppClass.setVtable(CppVTable)` / `getVtable()`
attach it to the class. `CppMethod` carries `setVirtual(boolean)` (`:105`) and
`setPureVirtual(boolean)` (`:123`), whose javadoc explicitly notes the holder
leaves the two flags independent and *"the feeder is responsible for setting
both consistently"* — this DD's feeder is that feeder. The translation from a
recovered ordered slot list to this model is essentially one-to-one, so the
slice is a *mapper*, not an analysis.

## The decision

1. **#37-6 ships a pure slot-fact mapper: recovered vtable slots → a `CppVTable`
   of `CppMethod`s on a `CppClass`, with each slot method marked virtual. It does
   not walk a `Program` and does not import the script-land `classrecovery.Vftable`
   or the MSVC `VfTableModel`.** Its input is the decoded slots of one class's
   vtable (the owning class's fully-qualified name plus, per slot in layout
   order, the slot method's name and whether it is pure-virtual); its effect is
   to resolve the owning `CppClass`, build a `CppVTable`, append one `CppMethod`
   per slot, and attach the table to the class. This is the direct analogue of
   DD-0012/DD-0013's "pure mapper" decision.

2. **Name the headless core a *feeder*, reserve *analyzer* for the deferred
   program-scan.** To keep the RFC's `CppVTableAnalyzer` name meaningful, this
   slice ships the translation core as a `CppVTableFeeder` (parallel to
   `CppDemanglingFeeder` and `CppRttiFeeder`), and the program-scanning
   `CppVTableAnalyzer` wrapper — the part that reads the function-pointer array
   at the recovered vftable address out of a `Program`, resolves each slot's
   `Function`, demangles it, and calls the feeder — is split out as a deferred
   slice **#37-6b** (see Sequencing). DD-0012 and DD-0013 made the same split.

3. **It lands in `Ghidra/Features/Base`, package `ghidra.app.util.cpp`**, beside
   the model it fills and the #37-3/#37-4 feeders — no new module, no dependency
   inversion. The **dedicated-`Features/Cpp`-module question** that DD-0013
   parked "at #37-6" is **not** decided by this headless slice; it is re-parked
   on the program-scanning #37-6b wrapper (which is the first slice that actually
   needs to register an auto-analyzer and a `Program`), where the trade-off is
   real.

4. **The feed contract is the explicit slot→`CppMethod` mapping.** For an owning
   class FQN `c` and an ordered list of recovered slot facts, each
   `(String methodName, boolean isPureVirtual)`:
   - resolve `CppClass` for `c` via `CppTypeSystem.getCppClass(name)`, creating
     an empty placeholder over `new StructureDataType(name, 0)` on a miss — the
     **same placeholder rule DD-0012 decision 4 established**, via the shared
     resolution helper (`CppClassResolution`) the two existing feeders already
     use, not a third copy;
   - build one `CppVTable`, and for each slot fact in order create
     `new CppMethod(methodName)`, call `setVirtual(true)` and
     `setPureVirtual(isPureVirtual)`, and `addSlot(...)` it; layout order is
     preserved by `CppVTable.addSlot` insertion order;
   - attach the table via `CppClass.setVtable`.

   The feeder never mutates an existing backing `Structure` (DD-0011 decision 3
   guardrail holds), and an already-recovered `CppClass` is reused as-is.

5. **Every slot method is virtual; pure-virtual is set per fact, consistently
   with `isVirtual`.** Occupying a vtable slot *is* virtual dispatch, so the
   feeder sets `setVirtual(true)` on every slot method unconditionally; a
   pure-virtual (`= 0`) slot additionally gets `setPureVirtual(true)` while
   keeping `isVirtual() == true` (a pure-virtual method is implicitly virtual —
   the `CppMethod` javadoc's "set both consistently" contract). This is the slice
   that discharges DD-0011/DD-0013's deferral of `isVirtual` population.

6. **Vtable slot methods are *fresh* `CppMethod`s; reconciling them with
   demangling-fed methods is deferred.** A method already attached to the class
   by the #37-3 demangling feeder is a distinct `CppMethod` object from the slot
   method created here. The model has no method-identity key (name + signature
   matching) yet, and inventing a slot↔declared-method matching heuristic is its
   own concern — deferred to the `DataType`-resolution / dedup slice (#37-7+).
   This slice appends slot methods to the `CppVTable` and does **not** attempt to
   merge them into `CppClass.getMethods()` or de-duplicate against demangled
   methods.

7. **The feeder is `Address`-free; `tableAddress` is the scanner's to set.** A
   real recovered vftable address only exists once a `Program` has been scanned,
   so the headless feeder leaves `CppVTable.tableAddress` null and the deferred
   #37-6b wrapper calls `setTableAddress(...)` with the address it read. Keeping
   `Address` out of the feed contract keeps the core (and its tests) free of even
   `AddressSpace` construction, consistent with the ABI/Program-neutral model
   contract (DD-0011 decision 4; DD-0013 decision 5/7).

## Validation

#37-6 ships fast **headless** JUnit in
`Ghidra/Features/Base/src/test/java/ghidra/app/util/cpp/` (`CppVTableFeederTest`,
`AbstractGenericTest`) that **builds the recovered slot facts directly** — plain
`(owningClassName, ordered list of (methodName, isPureVirtual))` inputs — rather
than constructing a `Program`, a `classrecovery.Vftable`, or a `VfTableModel`.
This keeps the test pure-Java, program-free, and free of any cross-module
dependency. Tests assert:

- a recovered slot list produces a `CppVTable` on the owning class with one
  `CppMethod` per slot, in feed order (`getSlots()` order matches), each with
  `isVirtual() == true`;
- a pure-virtual slot fact yields `isPureVirtual() == true` **and**
  `isVirtual() == true` (set consistently), while a normal slot yields
  `isPureVirtual() == false`;
- an empty slot list attaches an empty `CppVTable` (slot count 0) and registers
  the class — no slots invented;
- the owning class is created as an empty placeholder `StructureDataType(name, 0)`
  when unknown (DD-0012 decision 4 via the shared helper) and an already-defined
  `CppClass` is reused with its backing `Structure` un-clobbered and its existing
  demangling-fed methods in `getMethods()` untouched (guardrail for decision 6);
- `CppVTable.getTableAddress()` is null (the feeder is `Address`-free; decision 7);
- the placeholder/blank/null-argument boundaries reject as the sibling feeders do
  (null type system, null/blank owning class name, null slot list, null/blank
  slot method name).

Gating reminders specific to #37-6 (each a hard local gate before push):

- The feeder is Java-only and headlessly testable, so `gradle :Base:test` is the
  validating gate; no `--full` C++ precheck is required ([[always-test-before-push]]).
- The new `CppVTableFeeder.java` carries the inline `IP: GHIDRA` Apache header, so
  it passes `gradle :Base:ip` via the header with **no `certification.manifest`
  entry** (as verified for the #37-2/#37-3/#37-4 Base sources). Run `gradle :Base:ip`
  to confirm; a manifest entry is only needed for header-less / generated tracked
  files ([[new-source-file-ip-manifest]]). The `cppRaiiAudit` gate is C++-only and
  does not apply ([[new-cpp-file-raii-audit]]).
- The impl slice should also correct the stale `#37-5` references in
  `CppVTable.java` / `CppClass.java` javadoc (the vtable analyzer is #37-6, not
  #37-5) as in-scope cleanup beside the new code.

## Sequencing (refines DD-0013's table)

| PR | Scope |
|---|---|
| #37-2 | *(shipped, DD-0011)* model-only `CppTypeSystem` skeleton in `Features/Base` |
| #37-3 | *(shipped, DD-0012)* `CppDemanglingFeeder` — pure `DemangledObject`→`CppTypeSystem` mapper |
| #37-4 | *(shipped, DD-0013)* `CppRttiFeeder` — pure Itanium-RTTI-fact → `CppBaseClass` edge mapper; shared placeholder resolution |
| #37-4b | `CppRttiAnalyzer` (Itanium) program-scan wrapper — walks `GccTypeinfo`/`__vmi_class_type_info` records, decodes `offset_flags`, calls the feeder; needs a `Program`/analysis harness (deferred, not headless) |
| #37-5 | MSVC RTTI program-scan wrapper → the *same* `CppBaseClass` edges from `Rtti1/3/4Model` (feeder reused; deferred, not headless) |
| #37-6 | **(this DD's subject)** `CppVTableFeeder` — pure recovered-slot-fact → `CppVTable` + `CppMethod.isVirtual` mapper in `Features/Base`, fixture-tested; shared placeholder resolution with #37-3/#37-4 |
| #37-6b | `CppVTableAnalyzer` program-scan wrapper — reads the vftable function-pointer array out of a `Program`, resolves+demangles each slot, sets `tableAddress`, calls the feeder; **decides the dedicated-`Features/Cpp`-module question**; needs a `Program`/analysis harness (deferred, not headless) |
| #37-7+ | `CppDecompilerHints`; parameter/return `DataType` resolution; slot↔declared-method reconciliation; templates + operators |

## Rejected alternatives

- **Import/run the script-land `classrecovery.Vftable` (or the MSVC
  `VfTableModel`) from the #37-6 slice.** Rejected: the Itanium path lives in
  `ghidra_scripts` (package `classrecovery`), is not a published module API, and
  is `Program`-coupled; `VfTableModel` is a real module type but is constructed
  over `Program` memory and a `Base`→`MicrosoftCodeAnalyzer` dependency would
  invert the module graph — the same family of reasons DD-0012/DD-0013 refused to
  invoke a concrete demangler or the RTTI recoverer from a feeder.
- **Make #37-6 a program-scanning auto-analyzer now.** Rejected: an analyzer needs
  a real `Program` and an analysis-run harness (GUI/integration coupling), so it
  cannot be validated by `gradle :Base:test`. The headlessly-testable mapper is
  the core; the scanning wrapper is #37-6b (kept faithful to the RFC's
  `CppVTableAnalyzer` name).
- **Reconcile vtable slot methods with already-fed demangled methods (dedup
  method identity) in this slice.** Rejected: the model has no method-identity
  key, so this needs a name+signature matching heuristic that is its own slice
  (#37-7+). Appending fresh slot methods keeps #37-6 a one-to-one mapper.
- **Set `CppVTable.tableAddress` in the feeder.** Rejected: a real recovered
  address only exists after a `Program` scan; pulling `Address`/`AddressSpace`
  into the headless feed contract would taint the core and its tests for no
  model benefit. The deferred #37-6b wrapper sets it.
- **Leave `CppMethod.isVirtual` unset (defer further).** Rejected: a vtable slot
  is definitionally a virtually-dispatched method, and DD-0011/DD-0013 explicitly
  parked `isVirtual` population *at* #37-6 — this is that slice.
- **Decide the dedicated-`Features/Cpp`-module question in this DD.** Rejected:
  the headless feeder neither registers an analyzer nor touches a `Program`, so
  the module trade-off has no teeth here; it is re-parked on #37-6b, the first
  slice that actually needs an auto-analyzer.

## References

- [DD-0013](0013-rec37-rtti-inheritance-feeder.md) — the immediately preceding
  feeder/analyzer split and shared placeholder helper this DD reuses;
  [DD-0012](0012-rec37-demangling-feeder.md) — the original pure-mapper /
  deferred-scan split and placeholder-`Structure` rule; [DD-0011](0011-rec37-cpptypesystem-skeleton.md)
  — the model, its `CppVTable`/`CppMethod` holders, and the ABI-neutral contract.
- [RFC-0001](../rfcs/0001-cpp-frontend.md) — parent proposal; this DD grounds its
  #37-6 slice (`### 4. CppVTableAnalyzer — vtable → CppMethod table`, lines 91-100)
  and populates the `CppVTable` of `### 1.` (lines 56-58).
- In-tree vtable recovery (shape of the input, not a dependency):
  script-land `Ghidra/Features/Decompiler/ghidra_scripts/classrecovery/Vftable.java`
  (`addVfunction` `:87`, `getNumVfunctions` `:91`), `RecoveredClass.java`
  (`getVirtualFunctions(Address)` `:113`, `getAllVirtualFunctions` `:117`,
  `getOrderToVftableMap` `:182`), `GccTypeinfo.java` (`getVtableAddress` `:67`);
  module-land `Ghidra/Features/MicrosoftCodeAnalyzer/src/main/java/ghidra/app/cmd/data/rtti/VfTableModel.java`.
- Model under fill (Base, shipped #37-2):
  `Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppVTable.java`
  (`addSlot :46`, `setTableAddress :90`), `CppClass.java` (`setVtable :138`,
  `getVtable :129`), `CppMethod.java` (`setVirtual :105`, `setPureVirtual :123`),
  `CppTypeSystem.java` (`getCppClass`, `defineClass`).
- The feeders whose placeholder-resolution this slice shares:
  `Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppClassResolution.java`,
  `CppDemanglingFeeder.java`, `CppRttiFeeder.java`.

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
