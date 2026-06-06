---
number: 0011
title: Rec 37 #37-2 — the CppTypeSystem skeleton is a model-only overlay in Features/Base, not an analyzer
status: accepted
date: 2026-06-06
audit_rec: 37
---

# Decision 0011: the CppTypeSystem skeleton lands as a model-only overlay over the existing type system

## Context

[RFC-0001](../rfcs/0001-cpp-frontend.md) proposes a first-class C++ analysis
frontend — five cooperating components (`CppTypeSystem`, `CppDemanglingFeeder`,
`CppRttiAnalyzer`, `CppVTableAnalyzer`, `CppDecompilerHints`) that compose the
currently-uncomposed C++ point tools into a coherent object-model understanding.
The RFC is, by its own framing, a *months-to-years* undertaking written as a
sequence of independently-reviewable sub-PRs, and it explicitly gates the first
implementation slice (#37-2, the `CppTypeSystem` skeleton) on design sign-off.

This DD is that grounding. It does **not** commit the whole Rec 37 arc — like
[DD-0008](0008-rec39-loop-region-matcher.md) did for Rec 39's loop phase, it
grounds only the *first* slice against what the tree already provides, fixes the
slice's module/package and its testable shape, and records the scope guardrails
the RFC states informally. Each later slice (#37-3+) gets its own grounding as it
comes up; this DD's job is to make #37-2 a concrete, headlessly-shippable unit.

It matters that #37-2 is genuinely shippable now. The other open Sprint-8/9
items are blocked: Rec 35 `#35-5b-2` (retry) needs GUI-runtime validation
([[rec35-35-5b2-retry-gui-untestable]]), and Rec 39 `#39-6` needs loop-collapse
infrastructure that does not exist (DD-0008 addendum). The #37-2 skeleton, by
contrast, is a handful of plain model classes over an existing type system,
covered by fast headless JUnit — so it clears the test-before-push bar without a
display or new infrastructure.

## What the in-tree pipeline already provides (grounding)

The RFC's premise is that Ghidra already has the *pieces* and lacks the
*composition*. A survey of the tree confirms exactly that, and tells us where the
overlay attaches.

### Demangled-name model — the eventual feed source (#37-3), already rich

The demangler output model lives in `Ghidra/Features/Base`:

- `ghidra/app/util/demangler/DemangledObject.java` — abstract base; carries the
  `namespace` path, visibility, storage class, and the `isStatic` / `isVirtual`
  / `isConst` / `isVolatile` qualifiers.
- `ghidra/app/util/demangler/DemangledFunction.java` — adds `returnType`,
  `callingConvention` (`DemangledFunction.java:55`, e.g. `__thiscall`),
  `parameters`, overloaded-operator status, and a trailing-const flag
  (`isTrailingConst()`, `DemangledFunction.java:179`).
- `ghidra/app/util/demangler/DemangledDataType.java` /
  `DemangledParameter.java` — the type and parameter models.

Both ABIs are implemented as the *producers* of this model:
`Ghidra/Features/GnuDemangler` (`GnuDemangler` + `GnuDemanglerAnalyzer`,
Itanium) and `Ghidra/Features/MicrosoftDemangler` (`MicrosoftDemangler` +
`MicrosoftDemanglerAnalyzer`, MSVC). RFC §2's point holds: the feeder is a
*consumer* of existing demangler output, not a new parser.

### RTTI — formal on MSVC, script-based on GCC

- **MSVC** RTTI is a formal auto-analyzer:
  `Ghidra/Features/MicrosoftCodeAnalyzer/.../MicrosoftCodeAnalyzerPlugin/RttiAnalyzer.java`
  ("Windows x86 PE RTTI Analyzer"), with the RTTI0–4 structure models under
  `ghidra/app/cmd/data/rtti/` (`Rtti1Model` BaseClassDescriptor, `Rtti3Model`
  ClassHierarchyDescriptor, `Rtti4Model` CompleteObjectLocator) plus
  `TypeDescriptorModel` (RTTI0).
- **Itanium/GCC** RTTI recovery is **script-based, not a formal analyzer**: it
  lives under `Ghidra/Features/Decompiler/ghidra_scripts/classrecovery/`
  (`RTTIGccClassRecoverer.java`, `GccTypeinfo.java`, `BaseTypeinfo.java`,
  `Vtable.java`, `Vftable.java`), handling `__class_type_info` /
  `__si_class_type_info` / `__vmi_class_type_info` and the `_ZTV`/`_ZTS`/`_ZTI`
  prefixes. This asymmetry — formal analyzer on one ABI, classrecovery scripts on
  the other — is itself part of the "uncomposable point tools" gap Rec 37 names,
  and a reason the overlay model must be *ABI-agnostic* (see decision 4).

### The type system the overlay projects over

A C++ class is, concretely, a layout + a namespace + a method set, all of which
the core type system already expresses (`Ghidra/Framework/SoftwareModeling`):

- `ghidra/program/model/data/StructureDataType.java` — the field layout (with
  alignment/padding).
- `ghidra/program/model/data/FunctionDefinition.java` — a method signature as a
  `DataType` (`setArguments` / `setReturnType` / `setCallingConvention`).
- `ghidra/program/model/listing/GhidraClass.java` (a `Namespace` of
  `Type.CLASS`, `ghidra/program/model/symbol/Namespace.java`, `::` delimiter) —
  the class as a symbol scope / hierarchy node.
- `ghidra/program/model/data/DataTypeManager.java` — the owning manager.

So RFC §1's "a `CppClass` *is* a `Structure` plus annotations" is directly
realizable: the overlay holds **references** to a backing `StructureDataType` and
`GhidraClass`; it does not mint a parallel type.

### The analyzer extension point (relevant from #37-3 on, not #37-2)

New auto-analyzers extend `AbstractAnalyzer`
(`Ghidra/Features/Base/.../app/services/AbstractAnalyzer.java`, implementing the
`Analyzer` ExtensionPoint, discovered by the `*Analyzer.java` naming convention)
and declare an `AnalyzerType` — the enum has exactly four values:
`BYTE_ANALYZER`, `INSTRUCTION_ANALYZER`, `FUNCTION_ANALYZER`, `DATA_ANALYZER`
(`AnalyzerType.java`). `CondenseFillerBytesAnalyzer` is the minimal worked
example. **The #37-2 skeleton wires no analyzer** — this is noted only so the
later feeder/RTTI slices have their attachment point on record.

## The decision

1. **#37-2 is model-only: the four RFC §1 types plus the container, as plain
   classes with no analysis wiring.** Ship `CppTypeSystem`, `CppClass`,
   `CppMethod`, and `CppVTable` (and a `CppCallingConvention` accessor where the
   `this`-pointer placement belongs) as data classes that *reference* the backing
   `StructureDataType` / `GhidraClass` / `FunctionDefinition` from
   SoftwareModeling. No demangling feed, no RTTI parsing, no analyzer, no
   decompiler hints — those are #37-3 onward. The skeleton's whole job is to make
   the object model exist and round-trip in memory.

2. **It lands in `Ghidra/Features/Base`, package `ghidra.app.util.cpp`, not in a
   new module.** Base already owns the demangled model that will feed it
   (`ghidra.app.util.demangler`, decision-adjacent sibling package) and depends
   on SoftwareModeling, which the overlay projects over. Base is depended on by
   `MicrosoftCodeAnalyzer` and the Decompiler, so the later feeder/RTTI/vtable
   slices in *those* modules can consume the Base-resident model without a
   dependency inversion. A dedicated `Features/Cpp` module is deferred until the
   surface (analyzer + feeder + hints) actually justifies one — revisit at #37-6
   (`CppVTableAnalyzer`), the point by which the analyzer side is real.

3. **A `CppClass` is a projection, never a replacement.** It holds a reference to
   its backing `Structure` and `GhidraClass`; tools that read the `Structure`
   keep working unchanged, and C++-aware tools see the richer view. The overlay
   never resolves a competing `DataType` into the `DataTypeManager` for a class it
   annotates. This is RFC §"Drawbacks"/§"Migration"'s overlay-not-fork rule made
   load-bearing: a reviewer can reject any #37-x change that mints a parallel type
   for an annotated class.

4. **The model is ABI-agnostic.** Because RTTI arrives formally on MSVC but via
   classrecovery scripts on GCC (grounding above), the skeleton's inheritance and
   vtable representation must not bake in either ABI's record shapes — it stores
   recovered facts (base-class edges with offset + virtual/public flags, vtable
   slot→method maps) in ABI-neutral form, leaving each feeder (#37-4 Itanium,
   #37-5 MSVC) to translate its ABI's records into that neutral model.

5. **Hard questions stay out of the skeleton.** Templates (materializing
   `vector<int>` from a demangled name), MSVC-RTTI-obfuscated/packed fallback,
   and operator-overload rendering (RFC §"Unresolved questions") are explicitly
   *not* in #37-2. The skeleton must represent a plain single/multiple-inheritance
   class with non-template methods; template and operator support are later,
   separately-grounded slices.

## Validation

#37-2 ships fast **headless** JUnit in
`Ghidra/Features/Base/src/test/java/ghidra/app/util/cpp/`, the model-test
convention this module already uses (`AbstractGenericTest` + a synthetic
`DataTypeManager`/`ProgramBuilder` fixture; cf.
`ghidra/app/analyzers/CondenseFillerBytesAnalyzerTest.java` and
`MicrosoftCodeAnalyzer`'s `app/cmd/data/rtti/RttiModelTest` over
`AbstractRttiTest`). These run without a DISPLAY. The skeleton tests assert the
model alone, with no analysis pipeline:

- a `CppClass` built over a synthetic `StructureDataType` + `GhidraClass` exposes
  its backing types and its member layout unchanged (projection, not copy);
- inheritance edges (base class, offset, virtual/public flags) round-trip;
- `CppMethod`s attach to a class and a `CppVTable` maps slot→`CppMethod`;
- a class annotated by the overlay leaves the `DataTypeManager`'s view of the
  backing `Structure` untouched (the decision-3 guardrail, as an assertion).

Because there is no analyzer and no C++ code-generation, there is **no datatest
and no decompiler-pipeline test** in #37-2 — that surface appears with the feeder
(#37-3) and is grounded then.

Gating reminders specific to #37-2 (each a hard local gate before push):

- The new `CppTypeSystem` / `CppClass` / `CppMethod` / `CppVTable` `.java` files
  are new tracked Java sources and each needs a `certification.manifest` entry —
  verify `gradle :Base:ip` ([[new-source-file-ip-manifest]]). (The
  `cppRaiiAudit` gate is C++-only and does **not** apply to Java sources.)
- The model is Java-only and headlessly testable, so the fast `gradle :Base:test`
  unit run is the validating gate; no `--full` C++ precheck is required for
  #37-2. ([[always-test-before-push]].)

## Sequencing (refines RFC-0001's table for the #37-2 entry)

| PR | Scope |
|---|---|
| #37-2 | **(this DD's subject)** `CppTypeSystem` + `CppClass`/`CppMethod`/`CppVTable` model-only overlay in `Features/Base` `ghidra.app.util.cpp`, ABI-agnostic, + headless model JUnit |
| #37-3 | `CppDemanglingFeeder` — fills the model from `DemangledObject`/`DemangledFunction` (Base); its own grounding for the feed contract |
| #37-4 | `CppRttiAnalyzer` (Itanium) — translates the classrecovery `GccTypeinfo`/`Vtable` facts into inheritance edges |
| #37-5 | `CppRttiAnalyzer` (MSVC) — translates the `Rtti1/3/4Model` records into the same neutral edges |
| #37-6 | `CppVTableAnalyzer` — slot→`CppMethod` mapping; revisit promoting the model to a dedicated module here |
| #37-7+ | `CppDecompilerHints` (upcasts/downcasts, vmethod calls, ctor/dtor); templates + operators last |

#37-2 is the load-bearing definition step: it fixes the model shape and the
overlay-not-fork contract every later slice depends on. It deliberately ships
*nothing that needs an analysis run or a display*, so the strategic sprint opens
on a unit that clears the test-before-push bar.

## Rejected alternatives

- **Stand up a new `Features/Cpp` module in #37-2.** Rejected as premature: the
  skeleton is a few model classes with no analyzer; a module's plugin/extension
  scaffolding buys nothing until the feeder/analyzer exists, and a cross-cutting
  model placed in a leaf module would invert the dependency on Base's demangled
  model. Defer the module question to #37-6 (decision 2).
- **Wire a `CppTypeSystem` analyzer in #37-2.** Rejected: the RFC sequences the
  feeder/analyzers as #37-3+, and an analyzer slice would need a real program and
  an analysis-run harness, dragging GUI/integration coupling into what should be a
  pure-model, fast-unit slice. The skeleton must be testable headlessly with no
  pipeline (validation section).
- **Replace `DataTypeManager` types with C++-native types.** Rejected per the
  RFC's overlay-not-fork rule (decision 3): the frontend annotates, it never
  forks the analysis pipeline or the type store.
- **Bake the model around one ABI's RTTI/vtable records (MSVC `Rtti*Model` or
  GCC `GccTypeinfo`).** Rejected (decision 4): the two ABIs surface RTTI through
  different mechanisms (formal analyzer vs. classrecovery scripts), so an
  ABI-specific model would force one feeder to impedance-match the other's shape.
  Keep recovered facts neutral; translate per ABI in #37-4/#37-5.
- **The five-component frontend in one PR.** Rejected: it is the multi-year scope
  the RFC explicitly breaks into reviewable slices; this DD grounds only the
  first.

## References

- [RFC-0001](../rfcs/0001-cpp-frontend.md) — the parent C++-frontend proposal;
  this DD grounds its #37-2 slice.
- Demangled model (Base): `Ghidra/Features/Base/src/main/java/ghidra/app/util/demangler/DemangledObject.java`,
  `DemangledFunction.java` (`:55` callingConvention, `:179` `isTrailingConst()`),
  `DemangledDataType.java`, `DemangledParameter.java`.
- Demangler producers: `Ghidra/Features/GnuDemangler/.../gnu/GnuDemangler.java` +
  `.../analysis/GnuDemanglerAnalyzer.java`;
  `Ghidra/Features/MicrosoftDemangler/.../microsoft/MicrosoftDemangler.java` +
  `.../analysis/MicrosoftDemanglerAnalyzer.java`.
- MSVC RTTI (MicrosoftCodeAnalyzer):
  `.../MicrosoftCodeAnalyzerPlugin/RttiAnalyzer.java`,
  `app/cmd/data/TypeDescriptorModel.java`, `app/cmd/data/rtti/Rtti1Model.java`,
  `Rtti3Model.java`, `Rtti4Model.java`.
- GCC RTTI / vtable (Decompiler scripts):
  `Ghidra/Features/Decompiler/ghidra_scripts/classrecovery/RTTIGccClassRecoverer.java`,
  `GccTypeinfo.java`, `BaseTypeinfo.java`, `Vtable.java`, `Vftable.java`.
- Type system (SoftwareModeling):
  `Ghidra/Framework/SoftwareModeling/src/main/java/ghidra/program/model/data/DataTypeManager.java`,
  `StructureDataType.java`, `FunctionDefinition.java`;
  `.../program/model/listing/GhidraClass.java`,
  `.../program/model/symbol/Namespace.java`.
- Analyzer extension point (Base):
  `Ghidra/Features/Base/src/main/java/ghidra/app/services/Analyzer.java`,
  `AbstractAnalyzer.java`, `AnalyzerType.java` (four types: BYTE / INSTRUCTION /
  FUNCTION / DATA); example `app/analyzers/CondenseFillerBytesAnalyzer.java`.
- Test precedents (headless):
  `Ghidra/Features/Base/src/test/java/ghidra/app/analyzers/CondenseFillerBytesAnalyzerTest.java`;
  `Ghidra/Features/MicrosoftCodeAnalyzer/src/test/java/ghidra/app/cmd/data/rtti/RttiModelTest.java`
  (+ `AbstractRttiTest.java`).
- [DD-0008](0008-rec39-loop-region-matcher.md) — precedent for a DD that grounds
  only the first slice of a deferred-heavy phase.

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
