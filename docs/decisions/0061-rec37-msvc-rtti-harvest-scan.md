---
number: 0061
title: Rec 37 #37-5-4 — program-wide MSVC RTTI harvest scan; CppMsvcRttiScan walks the program's defined data for the RTTICompleteObjectLocator entries Ghidra's upstream RttiAnalyzer has already laid down, re-validates each as an Rtti4Model, and feeds it through CppMsvcRttiDriver into a CppTypeSystem — harvest of upstream's discovery rather than a re-implementation of its byte-search, with feed order made irrelevant by the feeder's placeholder resolution
status: accepted
date: 2026-06-10
audit_rec: 37
---

# Decision 0061: harvest upstream's laid-down RTTI4s instead of re-discovering them

## Context

The `#37-5` MSVC RTTI band shipped bottom-up: [DD-0039](0039-rec37-msvc-rtti-base-decoder.md) decoded one
base descriptor, [DD-0040](0040-rec37-msvc-rtti-class-decoder.md) decoded one class hierarchy,
[DD-0041](0041-rec37-msvc-rtti-driver.md) bridged the decode to `CppRttiFeeder` for **one located
structure**. What remained (the unchecked "Program-coupled `CppRttiAnalyzer`" sprint item) was the
program-wide step: given a whole `Program`, find every RTTI structure and feed it.

Upstream Ghidra already owns that discovery. `RttiAnalyzer` (enabled by default for Visual Studio /
Clang PEs, `AnalyzerType.BYTE_ANALYZER`) performs the byte-level walk — locate the `type_info` vftable,
find direct references to it in `.data` (potential `TypeDescriptor`s), byte-search `.rdata`/`.data`/
`.text` for RTTI4 candidates referencing each one, validate, and **lay each down as defined `Data` of
type `RTTICompleteObjectLocator`** (`Rtti4Model.DATA_TYPE_NAME`).

## Decision

The program-wide step is a **harvest, not a re-discovery**: `CppMsvcRttiScan.feedProgram(Program,
CppRttiFeeder, DataValidationOptions)` iterates the program's defined data, selects the entries whose
datatype name is `Rtti4Model.DATA_TYPE_NAME`, constructs an `Rtti4Model` at each address, and feeds it
through the shipped `CppMsvcRttiDriver.feedClass` (which re-validates and stays total-failure-safe).
Returns the fed `CppClass`es in defined-data order; a program the upstream analyzer has not processed
(or with no RTTI) yields an empty list and an untouched type system.

Why harvest:

- **No duplicated fragile machinery.** Upstream's byte-search (image-base-offset patterns, block
  selection, vftable heuristics) is a moving target this pass does not own. Re-implementing it would be
  a second copy to keep correct; reading its *output* — defined data with a stable, public datatype
  name — is a one-line selection criterion against a published constant.
- **Correct pipeline composition.** In a real analysis run the upstream `RttiAnalyzer` executes by
  default before any consumer of class hierarchies (the same run-after-the-prerequisite-analyzer
  posture the constructor pass takes toward the demangler). The harvest is exactly the step a future
  `CppRttiAnalyzer` wrapper will call once per program.
- **Feed order is already solved.** Defined-data order is address order, which can put a derived class
  before its base — grounded: the complete-flow fixture lays Circle's RTTI4 (`0x01003240`) below Base's
  (`0x01003340`). `CppRttiFeeder` resolves bases through `CppClassResolution.resolveOrPlaceholder`
  (DD-0041's contract), so a base fed later fills the placeholder its derived class created; the test
  asserts the harvest order Circle, Base, Shape produces the correct `Base ← Shape ← Circle` graph.

Null `program`/`feeder`/`validationOptions` are programming errors and throw; everything else
contributes nothing rather than failing (the band's advisory, never-wrong posture).

**What this is not yet:** the `Analyzer`-lifecycle wrapper (priority after `RttiAnalyzer`, options,
one-shot gating) and the `CppVTableAnalyzer` twin remain the sprint item's tail; this slice is the
headless, grounded core both will call.

## Consequences

- A whole program's MSVC RTTI class graph now feeds in one call. Verified headlessly in
  [`CppMsvcRttiScanTest`](../../Ghidra/Features/MicrosoftCodeAnalyzer/src/test/java/ghidra/app/cmd/data/rtti/CppMsvcRttiScanTest.java)
  against the complete-flow fixture with the three RTTI4s laid down via `CreateRtti4BackgroundCmd`
  (the same application pattern as upstream's `RttiCreateCmdTest`, simulating "the upstream analyzer
  has run"): all three harvested in address order (derived-first tolerated), `Base ← Shape ← Circle`
  edges correct, empty harvest on an unanalyzed program, null-argument contracts. Scan suite 5/5;
  sibling `CppMsvcRttiDriverTest`/`CppMsvcRttiDecoderTest` unchanged.
- Pre-existing, unrelated: upstream's `RttiCreateCmdTest` `*FollowFlow` cases fail intermittently in
  the local headless environment (order-sensitive, reproduce on pure master without this change; CI's
  full suite has been green throughout the `#37-5` band). Out of scope here.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :MicrosoftCodeAnalyzer:test --tests 'ghidra.app.cmd.data.rtti.CppMsvcRttiScanTest'` (5/5)
  and `gradle :MicrosoftCodeAnalyzer:ip` (new files self-certify via standard headers), system
  `gradle` 8.5.
