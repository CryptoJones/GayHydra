---
number: 0062
title: Rec 37 #37-11a — per-program CppTypeSystem provider; CppTypeSystemProvider.get(Program) is a get-or-create over TransientProgramProperties (SCOPE.PROGRAM) that answers where the shared type system lives without feeding it, so contributors in any module feed the one instance and consumers read it; PROGRAM scope is safe because the model holds standalone projection structures, not program-DB data types
status: accepted
date: 2026-06-10
audit_rec: 37
---

# Decision 0062: one per-program CppTypeSystem, provided centrally, fed by contributors

## Context

Every Rec 37 component shipped so far — the feeders (demangling DD-0014, Itanium RTTI, MSVC RTTI
DD-0039–41, vtable), the recognition matchers and drivers (DD-0024 through DD-0060), and the program-wide
MSVC harvest scan ([DD-0061](0061-rec37-msvc-rtti-harvest-scan.md)) — takes a `CppTypeSystem` as an
argument. A survey grounded the gap: **no production code constructs one**. `new CppTypeSystem()` appears
only in tests; the entire stack is complete plumbing with no production assembly point. Before the
analyzer-lifecycle wrappers (the unchecked sprint item) can exist, something must answer: *where does the
per-program type system live, who creates it, and how do contributors and consumers find the same one?*

Upstream Ghidra already has the right mechanism: `TransientProgramProperties`
(`ghidra.app.plugin.core.analysis`, in Base) associates values with an open program — get-or-create by
key, one value per program, automatically released when the program closes — with two scopes
(`PROGRAM`, `ANALYSIS_SESSION`) and a documented caveat that rollback-sensitive contents (program-DB
`DataType`s, `CodeUnit`s) should not be PROGRAM-scoped.

## Decision

Add `CppTypeSystemProvider.get(Program)` to `ghidra.app.util.cpp` (Base):

```java
public static CppTypeSystem get(Program program) {
    // null program throws
    return TransientProgramProperties.getProperty(program, CppTypeSystem.class, SCOPE.PROGRAM,
        CppTypeSystem.class, () -> new CppTypeSystem(program.getDataTypeManager()));
}
```

Three deliberate properties:

1. **The provider answers location only — it does not feed.** Contributors (feeders, module-specific
   scans, the future analyzer wrappers) call `get` and feed classes in; consumers (the recognition
   drivers behind `CppDecompilerHints`) call `get` and read. This split keeps the module dependency
   direction clean: the provider lives in Base, and a contributor in a downstream module — e.g.
   MicrosoftCodeAnalyzer's `CppMsvcRttiScan` — reaches it without Base knowing the contributor exists.
   (The alternative, a Base-resident builder that runs all feeders, cannot even compile: Base cannot
   call into MicrosoftCodeAnalyzer.) Feeding is idempotent by the model's own contracts
   (`defineClass` returns the existing projection; `resolveOrPlaceholder` fills placeholders), so
   contributors may run repeatedly and in any order.
2. **`SCOPE.PROGRAM`, not `ANALYSIS_SESSION`.** The decompiler hints are consumed interactively long
   after auto-analysis ends — a session-scoped value would be discarded exactly when it becomes useful.
3. **The rollback caveat does not bite.** Grounded: the model's placeholder classes are standalone
   `new StructureDataType(name, 0)` objects (`CppClassResolution.resolveOrPlaceholder`), not program-DB
   data types, so a transaction rollback cannot dangle the cached graph. The bound `DataTypeManager` is
   held as a lookup handle (the manager object is stable across transactions), not as stored data-type
   state.

The key is the `CppTypeSystem.class` literal — unique, collision-free, and self-describing in
`TransientProgramProperties`' shared key space.

**Slice numbering:** this opens the `#37-11` integration band. `#37-11a` is this provider; the
`Analyzer`-lifecycle wrappers (`CppRttiAnalyzer` calling the DD-0061 harvest through the provider,
`CppVTableAnalyzer` twin) and the hints-consumer wiring are the next slices.

## Consequences

- Contributors and consumers in any module now share one `CppTypeSystem` per open program through a
  single call. Verified headlessly in
  [`CppTypeSystemProviderTest`](../../Ghidra/Features/Base/src/test/java/ghidra/app/util/cpp/CppTypeSystemProviderTest.java):
  same instance per program, bound to the program's `DataTypeManager`, distinct per program, a
  contributor's fed class visible to a later consumer, released on program close
  (`TransientProgramProperties.hasProperty` goes false after `dispose()`), null rejected. Suite 6/6.
- **What this unblocks:** `#37-11b` — the `CppRttiAnalyzer` lifecycle wrapper in MicrosoftCodeAnalyzer
  (priority after upstream's `RttiAnalyzer`, obtains the program's type system via the provider, runs
  the DD-0061 harvest); the `CppVTableAnalyzer` twin; and ultimately the consumer-side wiring that
  hands the shared type system to the recognition drivers.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Base:test --tests 'ghidra.app.util.cpp.CppTypeSystemProviderTest'` (6/6), system `gradle`
  8.5.
