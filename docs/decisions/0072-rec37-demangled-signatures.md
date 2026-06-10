---
number: 0072
title: Rec 37 #37-12a — demangled method signatures; CppDemanglingFeeder populates the FunctionDefinition field the DD-0011 skeleton reserved, converting the DemangledFunction's return/parameter types through DemangledDataType.getDataType against the type system's bound DataTypeManager — opening the #37-12 signature band with the headless half, declining to null (method still feeds) when no DTM is bound or a type fails to convert
status: accepted
date: 2026-06-10
audit_rec: 37
---

# Decision 0072: signatures feed from the demangler; the model field finally fills

## Context

The last open Rec 37 band is signature/`DataType` resolution — DD-0016's deferral table named it
DTM-coupled and left it. Survey grounding: `CppMethod` has carried a `FunctionDefinition signature`
field (with getter/setter) since the DD-0011 skeleton, **never populated by anything**; and
`CppDemanglingFeeder.feed` reads name, qualifiers, and calling convention off a
`DemangledFunction` while dropping the full signature the demangler already recovered
(`getReturnType()`, `getParameters()` → `DemangledParameter.getType()`).

## Decision

Open the `#37-12` band with the headless half ({@code #37-12a}): the demangling feeder builds and
attaches a `FunctionDefinitionDataType` from the demangled types.

- **Conversion is the demangler's own**: `DemangledDataType.getDataType(DataTypeManager)` — the
  same canonical conversion the upstream demangler analyzer applies when it lays signatures onto
  `Function`s, so the model's view matches the listing's.
- **The bound DTM gates it**: the bare model-only `CppTypeSystem()` has no `DataTypeManager` to
  resolve against, so the signature stays null and the method still feeds with everything else —
  never-wrong over complete. Same decline for any type that fails to convert (and a blanket
  total-failure-safe catch, matching the band's posture).
- **A missing demangled return type** (the constructor form) keeps the definition's default return
  rather than declining — the parameters are still faithful facts.

**Band slicing ahead** (each its own grounded slice): `#37-12b` — vftable-driver signatures from
the slot function's Ghidra `Function` (program-coupled, the demangler analyzer has already applied
them); `#37-12c+` — consumers (hints rendering parameter types, overload-aware slot naming).

## Consequences

- A demangled member function now feeds with its full signature; the model field is live. Feeder
  suite +3 (populate with return+parameter, constructor-form default return, no-DTM null), all
  prior cases unchanged.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Base:test --tests 'ghidra.app.util.cpp.CppDemanglingFeederTest'` and `gradle :Base:ip`,
  Gradle 8.5 / Temurin 21 (the CI-matching toolchain).
