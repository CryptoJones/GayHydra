---
number: 0063
title: Rec 37 #37-11b — CppRttiAnalyzer lifecycle wrapper; a default-enabled BYTE_ANALYZER in MicrosoftCodeAnalyzer at REFERENCE_ANALYSIS.after() (strictly after upstream's RttiAnalyzer at REFERENCE_ANALYSIS.before()) that runs the DD-0061 harvest scan through the DD-0062 per-program CppTypeSystemProvider on every trigger, idempotent by the feeder's own contracts, with cancellation threaded through a new monitor-aware feedProgram overload
status: accepted
date: 2026-06-10
audit_rec: 37
---

# Decision 0063: the MSVC RTTI harvest becomes an analyzer, ordered after its producer

## Context

[DD-0061](0061-rec37-msvc-rtti-harvest-scan.md) shipped the program-wide harvest
(`CppMsvcRttiScan.feedProgram`) and [DD-0062](0062-rec37-type-system-provider.md) shipped the place the
result lives (`CppTypeSystemProvider`). What remained of the "Program-coupled `CppRttiAnalyzer`" sprint
item was the `Analyzer`-lifecycle wrapper itself: the piece that makes the harvest run *automatically*
during auto-analysis, so a user who opens a Visual Studio / Clang PE gets a fed type system without
calling anything.

## Decision

`CppRttiAnalyzer` in `ghidra.app.plugin.prototype.MicrosoftCodeAnalyzerPlugin` (beside upstream's
`RttiAnalyzer`, whose output it consumes), discovered like every other analyzer through the
`ClassSearcher` extension-point scan. Shape:

- **`AnalyzerType.BYTE_ANALYZER`, priority `REFERENCE_ANALYSIS.after()`.** Upstream's `RttiAnalyzer`
  runs at `REFERENCE_ANALYSIS.before()`; the harvest reads the `RTTICompleteObjectLocator` data it lays
  down, so the wrapper orders strictly after it. The ordering contract is asserted in the test against
  the upstream analyzer's actual priority, not a copied constant.
- **Same `canAnalyze` gate** (`PEUtil.isVisualStudioOrClangPe`): where the upstream analyzer cannot
  run, there is nothing to harvest.
- **`added()` ignores the added set and re-walks the whole program.** A byte analyzer triggers
  repeatedly as analysis progresses, and RTTI4s laid down outside the notified set must still be fed.
  Re-feeding is a no-op by the feeder's contracts (`defineClass` returns the existing projection;
  `resolveOrPlaceholder` fills placeholders), so the wrapper needs no `hasRun` gating — unlike
  upstream's `RttiAnalyzer`, whose byte-search re-run would be expensive rather than merely redundant.
- **Feeds the shared type system**: `new CppRttiFeeder(CppTypeSystemProvider.get(program))` — the
  DD-0062 assembly point's first production contributor.
- **Cancellation threaded, not bolted on**: `CppMsvcRttiScan` gains a monitor-aware `feedProgram`
  overload that calls `monitor.checkCancelled()` per defined-data entry (the existing three-argument
  form delegates with `TaskMonitor.DUMMY`), so a user can cancel the walk over a large binary's
  defined data. Default enablement on, one-time analysis supported, default
  `DataValidationOptions` (matching upstream's `RttiAnalyzer`); analyzer-specific options are deferred
  until a real knob is needed.

## Consequences

- Opening a VS/Clang PE that upstream's RTTI analysis has processed now feeds the per-program
  `CppTypeSystem` automatically — the first end-to-end production path from binary to fed type system.
  The recognition drivers can now find a populated type system where before only tests ever fed one.
- **The repeated-trigger test caught a real feeder bug.** `CppRttiFeeder.feedClass` appended base
  edges unconditionally, so a second trigger doubled every class's base list — contradicting the
  idempotence DD-0062 documented and this analyzer relies on. `feedClass` now skips a base identical
  to an already-attached edge (same resolved base class, offset, virtuality, access) while still
  attaching a genuinely different edge to the same base (a repeated non-virtual base at another
  offset). Covered at the feeder level (`CppRttiFeederTest`, new re-feed case) and at the analyzer
  level (the repeated-trigger test that found it).
- Verified headlessly in
  [`CppRttiAnalyzerTest`](../../Ghidra/Features/MicrosoftCodeAnalyzer/src/test/java/ghidra/app/cmd/data/rtti/CppRttiAnalyzerTest.java):
  priority strictly after upstream's `RttiAnalyzer`, `canAnalyze` accepts a VS PE and declines non-PE /
  non-VS-Clang, a complete-flow program with laid-down RTTI4s feeds `Base ← Shape ← Circle` into the
  *provider's* shared instance, a repeated trigger neither duplicates nor corrupts, an unanalyzed
  program leaves the type system untouched, and a cancelled monitor aborts with `CancelledException`
  having fed nothing. The fixture helpers are per-suite twins of `CppMsvcRttiScanTest`'s (rule of
  three; a third user earns the `AbstractRttiTest` extraction). Analyzer suite 6/6; scan suite
  (including the new null-monitor contract) and sibling driver/decoder suites green.
- Remaining `#37-11` tail: the `CppVTableAnalyzer` twin (`#37-11c`) and the hints-consumer wiring that
  hands the shared type system to the recognition drivers.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :MicrosoftCodeAnalyzer:test --tests 'ghidra.app.cmd.data.rtti.Cpp*'` and
  `gradle :MicrosoftCodeAnalyzer:ip` (new files self-certify via standard headers), Gradle 8.5 /
  Temurin 21 (the CI-matching toolchain).
