---
number: 0064
title: Rec 37 #37-11c-1 — MSVC vftable driver; CppMsvcVftableDriver bridges one located VfTableModel to CppVTableFeeder, naming the owning class from the vftable's own RTTI (the same TypeDescriptorModel.getDescriptorName() the RTTI decoder keys by) and each slot from its function's primary symbol, declining the whole table when any slot lacks a faithful name (no symbol, default FUN_..., or _purecall)
status: accepted
date: 2026-06-10
audit_rec: 37
---

# Decision 0064: the vftable driver names slots from symbols and declines what it cannot name

## Context

The `#37-11` band's RTTI half is end-to-end (DD-0061 harvest, DD-0062 provider, DD-0063 analyzer);
the vtable half had only the headless feeder. DD-0014 shipped `CppVTableFeeder` as a pure consumer —
"reading the pointer array at the recovered vftable address, resolving each slot to a `Function` and
demangling its name belongs to the later program-scanning analyzer wrapper" — and left
`CppVTable.getTableAddress()` null for that wrapper to set. This slice is that deferred core, for the
MSVC ABI: the bridge from one located `vftable` to the feeder, mirroring how `CppMsvcRttiDriver`
(DD-0041) bridges one located RTTI structure to `CppRttiFeeder`.

Upstream already models the structure: `VfTableModel` validates a laid-down MSVC vftable (meta
pointer at `table − ptrsize` → `RTTICompleteObjectLocator`; `elementCount` slots of function
pointers) and exposes `getVirtualFunctionPointer(i)` and `getRtti0Model()`.

## Decision

`CppMsvcVftableDriver.feedVtable(VfTableModel, CppVTableFeeder)` in `ghidra.app.cmd.data.rtti`:

- **The owning class comes from the vftable's own RTTI.** `getRtti0Model().getDescriptorName()` is
  the same demangled unqualified name `CppMsvcRttiDecoder` keys classes by, so the fed vtable
  attaches to the very `CppClass` the RTTI harvest resolves — no name translation layer, placeholder
  resolution covering whichever feed arrives first. Asserted by identity in the test
  (`rttiFed.getVtable() == vtableFed`).
- **Slot names come from the slots' primary symbols.** In the real pipeline the demangler has named
  each virtual function (`Circle::draw` → symbol `draw` in namespace `Circle`); the driver reads
  `getPrimarySymbol(functionAddress).getName()`. Three unfaithful-name shapes decline the **whole**
  table rather than feed a misleading slot (never-wrong): no symbol at the slot's function, a
  `SourceType.DEFAULT` symbol (`FUN_...` — analysis ran but the demangler never named it), and
  `_purecall` (an abstract class's pure-virtual slot points at the runtime trap; the method's own
  name is not recoverable from the slot). Whole-table decline over skip-the-slot because a partial
  vtable mis-numbers every later slot — slot *index* is the contract the virtual-call renderer
  dispatches on.
- **Pure-virtual recovery is explicitly deferred.** `SlotSpec.isPureVirtual` is always false here;
  naming a pure slot needs grounding against a real abstract-class binary (a later slice), and until
  then `_purecall` declines.
- **`CppVTable.getTableAddress()` is finally set** (the field DD-0014 left for this scanner), from
  `VfTableModel.getAddress()`.
- Same advisory contract as the RTTI driver: null model / failed validation / unnameable class or
  slot → `null`, never an exception or a mis-fed table; null feeder is a programming error and
  throws. Re-feeding is idempotent — the feeder replaces the class's vtable wholesale.

## Consequences

- One located MSVC vftable now feeds end-to-end into the shared model with its slots named the way
  the decompiler hints will render them. Verified headlessly in
  [`CppMsvcVftableDriverTest`](../../Ghidra/Features/MicrosoftCodeAnalyzer/src/test/java/ghidra/app/cmd/data/rtti/CppMsvcVftableDriverTest.java)
  against the complete-flow fixture (meta at `table − 4`, two slots): feed with names/order/flags
  and table address, identity with the RTTI-fed class, the three decline gates (unnamed slot,
  default-source slot, `_purecall`), invalid-model and null contracts. Driver suite 8/8.
- Remaining `#37-11c` tail: the program-wide vftable harvest scan (walk what upstream's
  `RttiAnalyzer` lays down — the `CppMsvcRttiScan` twin) and the `CppVTableAnalyzer` lifecycle
  wrapper (the `CppRttiAnalyzer` twin), then the hints-consumer wiring.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :MicrosoftCodeAnalyzer:test --tests 'ghidra.app.cmd.data.rtti.CppMsvcVftableDriverTest'`
  and `gradle :MicrosoftCodeAnalyzer:ip`, Gradle 8.5 / Temurin 21 (the CI-matching toolchain).
