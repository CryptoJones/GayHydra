---
number: 0065
title: Rec 37 #37-11c-2 — program-wide MSVC vftable harvest scan; CppMsvcVftableScan walks the symbol table for the vftable-named symbols upstream's associated-vftable pass publishes, re-validates each address as a VfTableModel through the DD-0064 driver, and feeds every nameable table — the CppMsvcRttiScan twin, anchored on a published symbol name instead of a published datatype name
status: accepted
date: 2026-06-10
audit_rec: 37
---

# Decision 0065: harvest vftables by the symbol upstream publishes

## Context

[DD-0064](0064-rec37-msvc-vftable-driver.md) shipped the per-table driver. What remained before the
`CppVTableAnalyzer` wrapper was the program-wide step: given a whole `Program`, find every laid-down
vftable and feed it — the same position [DD-0061](0061-rec37-msvc-rtti-harvest-scan.md)'s RTTI4
harvest occupies on the RTTI side.

The RTTI4 harvest anchors on defined data with a stable datatype name. A vftable's laid-down data
cannot anchor that way: `VfTableModel.getDataType()` is a plain pointer array (`pointer[n]`), with
nothing distinctive in its name. But upstream's associated-vftable pass
(`CreateRtti4BackgroundCmd` → `CreateVfTableBackgroundCmd` → `RttiUtil.createSymbolFromDemangledType`)
publishes a different stable artifact: a symbol named `vftable` in the class's namespace at the
table's address.

## Decision

`CppMsvcVftableScan.feedProgram(Program, CppVTableFeeder, DataValidationOptions[, TaskMonitor])`
iterates `SymbolTable.getSymbols("vftable")` and drives each symbol's address through
`CppMsvcVftableDriver.feedVtable` (which re-validates via `VfTableModel` and keeps the per-table
decline gates). Same shape as `CppMsvcRttiScan`, same posture:

- **Harvest, not re-discovery.** The meta-pointer byte search belongs to upstream; reading its
  published symbol is a one-line selection criterion. A program the upstream machinery has not
  processed yields no symbols, no tables, an untouched type system.
- **Declines are per-table, not per-program.** One table whose slots cannot be faithfully named
  (DD-0064's gates) contributes nothing; the rest of the harvest proceeds — asserted by a fixture
  where only one class's slot functions are named.
- **Cancellation per symbol** through the monitor-aware overload (the three-argument form delegates
  with `TaskMonitor.DUMMY`), matching the DD-0063 scan contract.
- Null arguments are programming errors and throw; everything else contributes nothing.

The symbol-name constant reuses `VfTableModel.DATA_TYPE_NAME` — upstream spells the label and the
datatype name identically (`vftable`), and the model's constant is the public one.

## Consequences

- A whole program's MSVC vftables now feed in one call, attaching to the same `CppClass`es the RTTI
  harvest resolves. Verified headlessly in
  [`CppMsvcVftableScanTest`](../../Ghidra/Features/MicrosoftCodeAnalyzer/src/test/java/ghidra/app/cmd/data/rtti/CppMsvcVftableScanTest.java)
  against the complete-flow fixture with the RTTI4s applied via `CreateRtti4BackgroundCmd` (whose
  associated-vftable pass publishes the symbols this scan anchors on): all three tables harvested
  with slots in order, the per-table decline, the unanalyzed-program no-op, cancellation, and the
  four null contracts. Scan suite 8/8.
- Remaining `#37-11c` tail: the `CppVTableAnalyzer` lifecycle wrapper (`#37-11c-3`, the
  `CppRttiAnalyzer` twin), then the hints-consumer wiring.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :MicrosoftCodeAnalyzer:test --tests 'ghidra.app.cmd.data.rtti.CppMsvcVftableScanTest'`
  and `gradle :MicrosoftCodeAnalyzer:ip`, Gradle 8.5 / Temurin 21 (the CI-matching toolchain).
