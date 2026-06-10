---
number: 0066
title: Rec 37 #37-11c-3 — CppVTableAnalyzer lifecycle wrapper; a default-enabled BYTE_ANALYZER at REFERENCE_ANALYSIS.after() that runs the DD-0065 vftable harvest through the DD-0062 provider on every trigger — the CppRttiAnalyzer twin, with order relative to its sibling deliberately irrelevant because both feed the same shared type system through placeholder-resolving feeders
status: accepted
date: 2026-06-10
audit_rec: 37
---

# Decision 0066: the vftable harvest becomes an analyzer; the Program-coupled sprint item closes

## Context

[DD-0064](0064-rec37-msvc-vftable-driver.md) shipped the per-table driver and
[DD-0065](0065-rec37-msvc-vftable-harvest-scan.md) the program-wide harvest. What remained of the
"Program-coupled `CppRttiAnalyzer` / `CppVTableAnalyzer` wrappers" sprint item was this last piece:
the `Analyzer` that makes the vftable harvest run automatically during auto-analysis — the
[DD-0063](0063-rec37-cpp-rtti-analyzer.md) twin.

## Decision

`CppVTableAnalyzer` in `ghidra.app.plugin.prototype.MicrosoftCodeAnalyzerPlugin`, shaped exactly
like its DD-0063 sibling: default-enabled `BYTE_ANALYZER` at `REFERENCE_ANALYSIS.after()` (strictly
after upstream's `RttiAnalyzer`, whose associated-vftable pass publishes the symbols the harvest
anchors on — asserted against the upstream analyzer's actual priority), the same
`PEUtil.isVisualStudioOrClangPe` gate, `added()` ignoring the notified set and re-walking the whole
program, cancellation threaded through the scan's monitor-aware overload, no `hasRun` gating
(re-feeding replaces each class's vtable wholesale).

One deliberate non-decision: **order relative to the sibling `CppRttiAnalyzer` at the same priority
is irrelevant.** Both feed the same provider instance through placeholder-resolving feeders —
whichever runs first creates the `CppClass`es the other fills in. The composition test asserts the
end state (base edges *and* vtables on the same shared classes), not an ordering.

## Consequences

- **The Program-coupled wrapper sprint item is closed.** Opening a VS/Clang PE now feeds the shared
  per-program `CppTypeSystem` with both halves automatically: class hierarchy from RTTI (DD-0063)
  and named vtables from vftables (this slice). The remaining `#37-11` work is the hints-consumer
  wiring that hands the shared type system to the recognition drivers behind `CppDecompilerHints`.
- Verified headlessly in
  [`CppVTableAnalyzerTest`](../../Ghidra/Features/MicrosoftCodeAnalyzer/src/test/java/ghidra/app/cmd/data/rtti/CppVTableAnalyzerTest.java):
  priority strictly after upstream's `RttiAnalyzer`, `canAnalyze` both ways, end-to-end feed of all
  three fixture classes' vtables into the provider's instance, composition with `CppRttiAnalyzer` on
  the same shared classes, repeated-trigger idempotence, unanalyzed-program no-op, cancelled-monitor
  abort. Analyzer suite 7/7. Fixture helpers are per-suite twins (rule of three — the
  `AbstractRttiTest` extraction is now earned at three users and is a natural next refactor commit).
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :MicrosoftCodeAnalyzer:test --tests 'ghidra.app.cmd.data.rtti.CppVTableAnalyzerTest'` and
  `gradle :MicrosoftCodeAnalyzer:ip`, Gradle 8.5 / Temurin 21 (the CI-matching toolchain).
