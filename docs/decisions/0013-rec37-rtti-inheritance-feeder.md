---
number: 0013
title: Rec 37 #37-4 — the Itanium RTTI path feeds inheritance edges into CppTypeSystem through a pure fact→CppBaseClass mapper in Features/Base; the program-scan stays a deferred wrapper
status: accepted
date: 2026-06-06
audit_rec: 37
---

# Decision 0013: the Itanium RTTI slice ships a headless inheritance-edge mapper (RTTI facts → CppBaseClass), not a program-scanning analyzer

## Context

[DD-0011](0011-rec37-cpptypesystem-skeleton.md) shipped the model-only
`CppTypeSystem` skeleton (#37-2) and [DD-0012](0012-rec37-demangling-feeder.md)
shipped the first *producer*, the `CppDemanglingFeeder` (#37-3): a pure
`DemangledObject → CppTypeSystem` mapper in `Ghidra/Features/Base` package
`ghidra.app.util.cpp`, deliberately decoupled from any demangler (which is
either a native `c++filt` shell-out or a module Base cannot depend on) and
fixture-tested with no native binary.

This DD grounds the next slice, [RFC-0001](../rfcs/0001-cpp-frontend.md)'s #37-4
`CppRttiAnalyzer` (Itanium). The RFC describes it as: *"Walks RTTI records
(Itanium `__class_type_info` / `__si_class_type_info` / `__vmi_class_type_info`)
and adds inheritance edges to `CppTypeSystem`."* That sentence hides two very
different jobs — **walking a program's RTTI** and **translating recovered
inheritance facts into the model's `CppBaseClass` edges**. DD-0012 already drew
this line for #37-3 (the symbol-table walk is the analyzer's job, the
translation is the headless core). This DD draws the same line for #37-4 and, as
before, fixes a slice *shape that is headlessly unit-testable with no `Program`
and no cross-module dependency* — the [[always-test-before-push]] bar.

## What the in-tree Itanium RTTI pipeline provides (grounding)

### Ghidra already recovers Itanium RTTI — in script-land

Ghidra ships a complete GCC/Itanium RTTI class recoverer, but it lives in
**`ghidra_scripts`** (package `classrecovery`), not in a reusable module:

- `Ghidra/Features/Decompiler/ghidra_scripts/classrecovery/RTTIGccClassRecoverer.java`
  — a ~4,900-line recoverer (`createRecoveredClasses()`,
  `createClassHierarchyListAndMap()`) that walks `__class_type_info` /
  `__si_class_type_info` / `__vmi_class_type_info` records in a live `Program`,
  produces `RecoveredClass` objects, and tracks parent edges in
  `classToParentOffsetMap` / `classToParentOrderMap`.
- `Ghidra/Features/Decompiler/ghidra_scripts/classrecovery/GccTypeinfo.java` —
  the per-class Itanium typeinfo model. It already carries exactly the
  inheritance facts #37-4 needs: `addBaseTypeinfo(GccTypeinfo base, int order,
  boolean isPublic, boolean isVirtual, long offset)` and predicates
  `isClassTypeinfo()` / `isSiClassTypeinfo()` / `isVmiClassTypeinfo()` keyed off
  the three special namespaces (`__class_type_info` = no bases,
  `__si_class_type_info` = a single public non-virtual base at offset 0,
  `__vmi_class_type_info` = the multiple/virtual case carrying a
  `__base_class_type_info[]` whose per-base `offset_flags` word encodes the
  offset plus the `__virtual_mask`/`__public_mask` bits).

### Two facts that constrain the #37-4 slice's shape (mirroring DD-0012)

1. **The recoverer is script-land and cannot be imported.** Classes in
   `Ghidra/Features/Decompiler/ghidra_scripts/classrecovery/` (package
   `classrecovery`) are compiled/run as Ghidra scripts, not published as a module
   API. `Features/Base` cannot `import classrecovery.GccTypeinfo` any more than
   #37-3 could import a concrete demangler — and a Base→Decompiler-feature edge
   would invert the module dependency besides.

2. **Walking RTTI needs a real `Program` and an analysis-run harness.** The
   recovery entry points take a `Program` + `ServiceProvider` and scan memory.
   That is exactly the GUI/integration coupling DD-0011 and DD-0012 kept out of
   the headlessly-testable core. A #37-4 that *scanned* could not be validated by
   `gradle :Base:test`.

Both facts point the same way they did for #37-3: the headless core must be a
*consumer of already-recovered inheritance facts*, decoupled from how (and in
which module) those facts were recovered, with tests that build the facts
directly.

### The model already has the destination edge (from #37-2)

`CppBaseClass` (shipped #37-2) is
`CppBaseClass(CppClass baseClass, int offset, boolean virtualBase, boolean publicBase)`
with getters `getBaseClass()` / `getOffset()` / `isVirtual()` / `isPublic()`, and
`CppClass.addBaseClass(CppBaseClass)` / `getBaseClasses()` store the edge in
insertion order. This is a near-exact image of `GccTypeinfo`'s per-base tuple
`(base, order, isPublic, isVirtual, offset)` — the translation is essentially
one-to-one, so the slice is a *mapper*, not an analysis.

## The decision

1. **#37-4 ships a pure inheritance-edge mapper: recovered Itanium facts →
   `CppBaseClass` edges on `CppTypeSystem`. It does not walk a `Program` and does
   not import the script-land recoverer.** Its input is the already-decoded base
   facts of one class (the derived class's fully-qualified name plus, per direct
   base, the base's name, byte offset, `virtual` flag, and `public` flag); its
   effect is to resolve the derived and base `CppClass`es and add a `CppBaseClass`
   edge for each. This is the direct analogue of DD-0012's "pure mapper" decision.

2. **Name the headless core a *feeder*, reserve *analyzer* for the deferred
   program-scan.** To keep the RFC's `CppRttiAnalyzer` name meaningful, this slice
   ships the translation core as a `CppRttiFeeder` (parallel to
   `CppDemanglingFeeder`), and the program-scanning `CppRttiAnalyzer` wrapper —
   the part that walks `GccTypeinfo` records in a real `Program`, decodes the
   `__vmi_class_type_info` `offset_flags`, and calls the feeder — is split out as
   a deferred slice (see Sequencing). DD-0012 made the same split for #37-3 (the
   `CppDemanglingFeeder` core shipped; the symbol-table-walking analyzer that
   selects a demangler was deferred).

3. **It lands in `Ghidra/Features/Base`, package `ghidra.app.util.cpp`**, beside
   the model it fills and the #37-3 feeder — no new module, no dependency
   inversion (consistent with DD-0011/DD-0012; the `Features/Cpp` module question
   stays deferred to #37-6).

4. **The feed contract is the explicit fact→edge mapping.** For a derived class
   FQN `d` and each recovered direct base fact `(baseName b, long offset, boolean
   isVirtual, boolean isPublic)`:
   - resolve `CppClass` for `d` and for `b` via `CppTypeSystem.getCppClass(name)`,
     creating an empty placeholder over `new StructureDataType(name, 0)` on a miss
     — **the identical placeholder rule DD-0012 decision 4 established**, applied
     to both endpoints of the edge (the placeholder-resolution helper is shared
     between the two feeders rather than duplicated);
   - add `new CppBaseClass(baseCppClass, (int) offset, isVirtual, isPublic)` to
     `d` via `CppClass.addBaseClass`. Base-list order is preserved by insertion
     order, so `GccTypeinfo`'s separate `order` field needs no dedicated edge
     field (DD-0011's `CppBaseClass` deliberately omits it).

   The feeder never mutates an existing backing `Structure` (DD-0011 decision 3
   guardrail holds): a placeholder is only minted when the model has no class of
   that name yet, and an already-recovered `CppClass` (e.g. one the #37-3 feeder
   or a layout-recovery slice defined) is reused as-is.

5. **The three Itanium typeinfo kinds map to edge *counts*, decoded by the
   (deferred) scanner, not the feeder.** `__class_type_info` → zero edges;
   `__si_class_type_info` → exactly one edge `(offset 0, virtual false, public
   true)`; `__vmi_class_type_info` → one edge per `__base_class_type_info`, with
   `offset` and the `virtual`/`public` flags **already decoded from the
   `offset_flags` word** by the scanner before they reach the feeder. The feeder
   stays ABI-neutral: it sees decoded `(offset, isVirtual, isPublic)` tuples and
   has no knowledge of `__virtual_mask`/`__public_mask` or the `>> 8` offset
   shift. This is what lets the *same* feeder serve #37-5 (MSVC), whose
   `RTTIBaseClassDescriptor` carries the analogous facts in a different encoding.

6. **No vtable, no method virtuality here.** RTTI yields the inheritance graph
   (base-class edges) only. Mapping vtable slots to `CppMethod`s and setting
   `CppMethod.isVirtual` remains #37-6 (`CppVTableAnalyzer`); the `vtableAddress`
   that `GccTypeinfo` happens to carry is not consumed by this slice.

7. **`offset` is narrowed `long`→`int`.** `GccTypeinfo`/`BaseTypeinfo` store the
   subobject offset as `long`; `CppBaseClass.offset` is `int`. A base subobject's
   offset within a single class layout fits `int` comfortably, so the feeder casts
   `(int) offset`; the (deferred) scanner is the place to guard against a
   pathological value if one is ever observed.

## Validation

#37-4 ships fast **headless** JUnit in
`Ghidra/Features/Base/src/test/java/ghidra/app/util/cpp/` (`CppRttiFeederTest`,
`AbstractGenericTest`) that **builds the recovered base facts directly** — plain
`(derivedName, baseName, offset, isVirtual, isPublic)` inputs — rather than
constructing a `Program` or any `classrecovery.GccTypeinfo`. This keeps the test
pure-Java, program-free, and free of any cross-module dependency on the
Decompiler-feature scripts. The hand-built facts emulate the *shapes* of the
three Itanium typeinfo kinds the recoverer distinguishes. Tests assert:

- a single-inheritance fact (`__si_class_type_info` shape) produces exactly one
  `CppBaseClass` on the derived class with `offset == 0`, `isVirtual() == false`,
  `isPublic() == true`, pointing at the resolved base `CppClass`;
- a multiple/virtual-inheritance fact set (`__vmi_class_type_info` shape)
  produces one edge per base with each base's `offset` / `isVirtual` / `isPublic`
  preserved and base-list order matching feed order;
- a no-base fact set (`__class_type_info` shape) adds no edges;
- both the derived and a not-yet-known base get an empty placeholder
  `StructureDataType(name, 0)` (DD-0012 decision 4 carried to both endpoints),
  while an already-defined `CppClass` is reused with its backing `Structure`
  un-clobbered;
- re-feeding is well-defined (resolving the same names reuses the same
  `CppClass`es);
- `isVirtual` on any `CppMethod` is **not** touched (guardrail: vtable work is
  #37-6).

Gating reminders specific to #37-4 (each a hard local gate before push):

- The feeder is Java-only and headlessly testable, so `gradle :Base:test` is the
  validating gate; no `--full` C++ precheck is required ([[always-test-before-push]]).
- The new `CppRttiFeeder.java` carries the inline `IP: GHIDRA` Apache header, so
  it passes `gradle :Base:ip` via the header with **no `certification.manifest`
  entry** (as verified for the #37-2/#37-3 Base sources). Run `gradle :Base:ip`
  to confirm; a manifest entry is only needed for header-less / generated tracked
  files ([[new-source-file-ip-manifest]]). The `cppRaiiAudit` gate is C++-only and
  does not apply ([[new-cpp-file-raii-audit]]).

## Sequencing (refines DD-0012's table)

| PR | Scope |
|---|---|
| #37-2 | *(shipped, DD-0011)* model-only `CppTypeSystem` skeleton in `Features/Base` |
| #37-3 | *(shipped, DD-0012)* `CppDemanglingFeeder` — pure `DemangledObject`→`CppTypeSystem` mapper |
| #37-4 | **(this DD's subject)** `CppRttiFeeder` — pure Itanium-RTTI-fact → `CppBaseClass` edge mapper in `Features/Base`, fixture-tested; shared placeholder resolution with #37-3 |
| #37-4b | `CppRttiAnalyzer` (Itanium) program-scan wrapper — walks `GccTypeinfo`/`__vmi_class_type_info` records in a `Program`, decodes `offset_flags`, calls the feeder; needs a `Program`/analysis harness (separately gated, not headless) |
| #37-5 | MSVC RTTI → the *same* `CppBaseClass` edges from `Rtti1/3/4Model` (feeder reused; MSVC scanner wrapper) |
| #37-6 | `CppVTableAnalyzer` — slot→`CppMethod`, `isVirtual` population; revisit the dedicated-module question |
| #37-7+ | `CppDecompilerHints`; parameter/return `DataType` resolution; templates + operators |

## Rejected alternatives

- **Import/run the script-land `RTTIGccClassRecoverer` from the #37-4 slice.**
  Rejected: it lives in `ghidra_scripts` (package `classrecovery`), is not a
  published module API, takes a `Program` + `ServiceProvider`, and a
  `Base`→`Decompiler`-scripts dependency would invert the module graph — the same
  family of reasons DD-0012 refused to invoke a concrete demangler from the
  feeder.
- **Make #37-4 a program-scanning auto-analyzer now.** Rejected: an analyzer needs
  a real `Program` and an analysis-run harness (GUI/integration coupling), so it
  cannot be validated by `gradle :Base:test`. The headlessly-testable mapper is
  the core; the scanning wrapper is #37-4b (kept faithful to the RFC's
  `CppRttiAnalyzer` name).
- **Carry the recovered hierarchy as the script-land `RecoveredClass` (or
  `GccTypeinfo`) type into Base.** Rejected: those are heavyweight,
  non-importable, ABI-coupled script types. The feeder consumes the minimal
  *decoded* facts and writes the neutral `CppBaseClass`, which is exactly what
  lets one edge model serve both Itanium (#37-4) and MSVC (#37-5).
- **Decode the Itanium `offset_flags` word (the `__virtual_mask` / `__public_mask`
  bits and `>> 8` offset) inside the model/feeder.** Rejected: that is
  ABI-specific bit-twiddling and belongs in the (deferred) scanner; keeping the
  feeder fed with already-decoded `(offset, isVirtual, isPublic)` preserves the
  ABI-neutral model contract (DD-0011 decision 4).
- **Add an `order` field to `CppBaseClass`.** Rejected: base declaration order is
  already preserved by `CppClass.addBaseClass` insertion order; DD-0011
  deliberately modelled the edge without an explicit ordinal, and nothing in the
  Itanium mapping needs one.

## References

- [DD-0012](0012-rec37-demangling-feeder.md) — the pure-mapper / deferred-scan
  split and the placeholder-`Structure` rule this DD reuses; [DD-0011](0011-rec37-cpptypesystem-skeleton.md)
  — the model and its ABI-neutral edge.
- [RFC-0001](../rfcs/0001-cpp-frontend.md) — parent proposal; this DD grounds its
  #37-4 slice (`### 3. CppRttiAnalyzer — RTTI → class hierarchy`, lines 81-89).
- In-tree Itanium RTTI recovery (shape of the input, not a dependency):
  `Ghidra/Features/Decompiler/ghidra_scripts/classrecovery/GccTypeinfo.java`
  (`addBaseTypeinfo(base, order, isPublic, isVirtual, offset)` `:79`;
  `isClassTypeinfo`/`isSiClassTypeinfo`/`isVmiClassTypeinfo` `:155-174`),
  `RTTIGccClassRecoverer.java` (`createRecoveredClasses` `:152`,
  `classToParentOffsetMap` `:97`, `classToParentOrderMap` `:94`).
- Model under fill (Base, shipped #37-2):
  `Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppBaseClass.java`
  (ctor `:43`), `CppClass.java` (`addBaseClass :93`, `getBaseClasses :103`),
  `CppTypeSystem.java` (`getCppClass`, `defineClass`).
- The #37-3 feeder whose placeholder-resolution this slice shares:
  `Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDemanglingFeeder.java`.

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
