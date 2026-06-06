---
number: 0012
title: Rec 37 #37-3 — the CppDemanglingFeeder is a pure DemangledObject→CppTypeSystem mapper in Features/Base, demangler-agnostic and fixture-tested
status: accepted
date: 2026-06-06
audit_rec: 37
---

# Decision 0012: the CppDemanglingFeeder maps an already-demangled symbol onto the model; it neither runs a demangler nor scans a program

## Context

[DD-0011](0011-rec37-cpptypesystem-skeleton.md) shipped the model-only
`CppTypeSystem` skeleton (#37-2): plain classes (`CppTypeSystem`, `CppClass`,
`CppMethod`, `CppVTable`, `CppBaseClass`, `CppCallingConvention`) in
`Ghidra/Features/Base` package `ghidra.app.util.cpp`, projecting over a backing
`Structure`/`GhidraClass`, covered by fast headless JUnit. That slice
deliberately shipped *nothing that fills the model* — it only made the model
exist.

This DD grounds the next slice, [RFC-0001](../rfcs/0001-cpp-frontend.md)'s #37-3
`CppDemanglingFeeder`: the first *producer* that populates the model. Per the
RFC and DD-0011's "each later slice gets its own grounding" rule, this DD fixes
the feed contract (what the feeder reads, what it writes, where the seams are)
and — most importantly for the [[always-test-before-push]] bar — fixes a feeder
*shape that is headlessly unit-testable with no native binary and no
cross-module dependency*. The sign-off gate in RFC-0001 is worded against #37-2
landing; #37-3 is the next sequenced row and is not separately gated.

## What the in-tree demangler pipeline provides (grounding)

DD-0011 already established that the demangled-name model is the eventual feed
source. This DD grounds the feed *mechanics* against the actual API, because two
of those facts directly constrain the feeder's shape.

### The demangled model the feeder consumes (Base, pure data)

In `Ghidra/Features/Base/src/main/java/ghidra/app/util/demangler/`:

- `DemangledObject.java` — abstract base. Getters: `getName()`, `getNamespace()`
  (returns the `Demangled` interface, chainable upward via `getNamespace()` until
  null), `getNamespaceString()` (full `::`-joined path). Qualifier getters
  `isStatic()` / `isVirtual()` / `isConst()`. Public setters
  `setName(String)` (`:240`), `setNamespace(Demangled)` (`:315`),
  `setStatic(boolean)` (`:183`), `setVirtual(boolean)` (`:191`),
  `setConst(boolean)` (`:159`).
- `DemangledFunction.java` (extends `DemangledObject`) — public constructor
  `DemangledFunction(String mangled, String originalDemangled, String name)`
  (`:84`); `getCallingConvention()` / `setCallingConvention(String)` (`:122`),
  `isTrailingConst()` / `setTrailingConst()` (`:183`), `getReturnType()`,
  `getParameters()` (`List<DemangledParameter>`).
- `Demangled.java`, `DemangledType.java`, `DemangledNamespaceNode.java` — the
  namespace/type chain; `DemangledDataType.java` / `DemangledParameter.java` —
  the type and parameter models.

The calling-convention vocabulary is the `CompilerSpec` constant set
(`Ghidra/Framework/SoftwareModeling/.../program/model/lang/CompilerSpec.java`):
`__cdecl`, `__thiscall`, `__stdcall`, `__fastcall`, `__vectorcall`, `__pascal`,
`unknown`, `default`, or null.

### Two facts that constrain the feeder's shape

1. **The GNU demangler shells out to a native process.**
   `Ghidra/Features/GnuDemangler/.../gnu/GnuDemangler.java` demangles by calling
   `GnuDemanglerNativeProcess` (`GnuDemangler.java:110-111`) — an external
   `c++filt`/demangler binary. Only the *parser* half (`GnuDemanglerParser`,
   invoked at `GnuDemangler.java:238-239` on an already-demangled string) is
   pure-Java. By contrast `Ghidra/Features/MicrosoftDemangler/.../microsoft/
   MicrosoftDemangler.java` is pure-Java (`MDMangGhidra`). **A feeder that
   *invoked* GnuDemangler could not be unit-tested headlessly** without provisioning
   a native binary in CI.

2. **`Features/Base` does not — and must not — depend on the demangler
   modules.** `GnuDemangler` and `MicrosoftDemangler` are separate feature
   modules that depend *on* Base (which owns the `DemangledObject` model); Base's
   `build.gradle` references neither. So a feeder living in Base **cannot import
   a concrete demangler** to produce test inputs even if it wanted to.

Both facts point the same way: the feeder must be a *consumer of the
`DemangledObject` model*, decoupled from which demangler produced it — and its
tests must build `DemangledObject` fixtures directly (the public constructor +
setters above), not invoke a demangler.

### The model's non-null backing-structure invariant (from #37-2)

`CppClass` (shipped in #37-2) *requires* a non-null backing `Structure`
(`new CppClass(Structure)` rejects null). A feeder working from a demangled
symbol alone often names a class for which no recovered `Structure` exists yet.
The feeder must therefore reconcile "I have a class *name* but no layout" with
that invariant — see decision 4.

## The decision

1. **The feeder is a pure mapper `DemangledObject → CppTypeSystem`. It does not
   run a demangler and it does not scan a program.** Its input is an
   already-demangled `DemangledObject` (whatever produced it); its effect is to
   read that object and populate the model. Choosing a demangler, iterating a
   program's symbol table, and deciding *which* symbols to feed belong to a later
   analyzer slice (#37-4/#37-5 wire a concrete demangler/RTTI source); #37-3 is
   the translation core those slices call.

2. **It lands in `Ghidra/Features/Base`, package `ghidra.app.util.cpp`**, beside
   the model it fills and the `ghidra.app.util.demangler` model it reads — no new
   module, no dependency inversion (consistent with DD-0011 decision 2; the
   `Features/Cpp` module question stays deferred to #37-6).

3. **The feed contract is the explicit read→write mapping below.** For a
   `DemangledFunction df` with a non-empty namespace chain (a member function):
   - resolve the enclosing `CppClass` by the namespace-chain class name
     (decision 4), then attach a `CppMethod`;
   - `CppMethod.name` ← `df.getName()`;
   - `CppMethod.isConst` ← `df.isTrailingConst()`;
   - `CppMethod.isStatic` ← `df.isStatic()`;
   - `CppMethod.callingConvention` ← `new CppCallingConvention(df.getCallingConvention(), ord)`
     where `ord = 0` for a non-static member (the implicit `this`, as `__thiscall`
     surfaces) and `CppCallingConvention.NO_THIS` for a `static` member.

   The feeder **does not set `isVirtual`/`isPureVirtual`**: a demangled name does
   not reveal vtable membership (`DemangledObject.isVirtual()` reflects a parsed
   storage qualifier, not vtable slotting). Virtual/vtable population is #37-6.
   A function with **no class namespace (a free function)** is a no-op for this
   slice — the model only holds classes; free-function handling, if ever needed,
   is a separate slice.

4. **A class named by a symbol with no existing backing `Structure` gets an empty
   placeholder.** The feeder resolves a class through
   `CppTypeSystem.getCppClass(name)`; on a miss it defines one over a freshly
   created empty `new StructureDataType(name, 0)`. This preserves #37-2's non-null
   backing-`Structure` invariant (decision above) without the feeder needing a
   real layout, and it is consistent with the projection rule: the placeholder is
   a real (empty) `Structure` that a later layout-recovery slice fills in. The
   feeder never *mutates* an existing backing `Structure` (DD-0011 decision 3
   guardrail still holds).

5. **The feeder is demangler-agnostic / ABI-agnostic.** Because it consumes the
   common `DemangledObject` model, one feeder serves both Itanium (`GnuDemangler`)
   and MSVC (`MicrosoftDemangler`) output. ABI-specific inheritance and vtable
   translation is *not* here — that is the #37-4 (Itanium classrecovery) and
   #37-5 (MSVC `Rtti*Model`) analyzers feeding base-class edges and vtable slots
   into the same neutral model (DD-0011 decision 4).

6. **Heavy resolution stays out of #37-3.** Mapping each `DemangledDataType`
   parameter/return type onto a concrete Ghidra `DataType` (which needs a
   `DataTypeManager` and recursive type construction) is deferred; the feeder
   records the method's name, qualifiers, and calling convention in this slice and
   leaves `CppMethod.signature` to a later slice that owns type resolution.
   Templated and operator names are stored **verbatim** as the demangler rendered
   them, not specially interpreted (DD-0011 decision 5).

## Validation

#37-3 ships fast **headless** JUnit in
`Ghidra/Features/Base/src/test/java/ghidra/app/util/cpp/`
(`CppDemanglingFeederTest`, `AbstractGenericTest`), and — per the grounding above
— **constructs `DemangledFunction` fixtures directly** with the public
constructor + `setNamespace`/`setCallingConvention`/`setTrailingConst`/
`setStatic` setters, rather than invoking any demangler. This keeps the test
pure-Java, native-binary-free (no `c++filt`), and free of any cross-module
dependency on `GnuDemangler`/`MicrosoftDemangler`. The hand-built fixtures
emulate the *shapes* of known-good vectors that the demangler tests already
cover — e.g. Itanium `Bar::Fred::Fred(int)` (member, namespaced) and MSVC
`public: long __thiscall ATL::CRegKey::Close(void)` (`__thiscall`, member) — so
the contract is anchored to real demangler output without importing the
producers. Tests assert:

- a namespaced `DemangledFunction` feeds a `CppClass` (resolved or
  placeholder-created) carrying a `CppMethod` with the mapped name;
- `isTrailingConst()` → `CppMethod.isConst()`, `isStatic()` →
  `CppMethod.isStatic()` round-trip;
- `getCallingConvention()` → `CppCallingConvention` with `this`-ordinal 0 for a
  member and `NO_THIS` for a static member;
- a class with no pre-existing backing `Structure` gets an empty placeholder
  `StructureDataType(name, 0)` and re-feeding the same class name is idempotent
  (reuses the `CppClass`, no clobber);
- a free function (no namespace) is a no-op;
- `isVirtual` is **not** inferred from the demangled qualifier (guardrail).

Gating reminders specific to #37-3 (each a hard local gate before push):

- The feeder is Java-only and headlessly testable, so `gradle :Base:test` is the
  validating gate; no `--full` C++ precheck is required ([[always-test-before-push]]).
- The new `CppDemanglingFeeder.java` carries the inline `IP: GHIDRA` Apache
  header, so it passes `gradle :Base:ip` via the header with **no
  `certification.manifest` entry** (verified for the #37-2 sources). Run
  `gradle :Base:ip` to confirm; a manifest entry is only needed for header-less /
  generated tracked files ([[new-source-file-ip-manifest]]). The `cppRaiiAudit`
  gate is C++-only and does not apply.

## Sequencing (refines RFC-0001's / DD-0011's #37-3 entry)

| PR | Scope |
|---|---|
| #37-2 | *(shipped, DD-0011)* model-only `CppTypeSystem` skeleton in `Features/Base` |
| #37-3 | **(this DD's subject)** `CppDemanglingFeeder` — pure `DemangledObject`→`CppTypeSystem` mapper in `Features/Base`, demangler-agnostic, fixture-tested; maps name/namespace→class+method, const/static qualifiers, calling convention; placeholder `Structure` for layout-less classes |
| #37-4 | `CppRttiAnalyzer` (Itanium) — translates classrecovery `GccTypeinfo`/`Vtable` facts into base-class edges; wires a concrete feed source |
| #37-5 | `CppRttiAnalyzer` (MSVC) — translates `Rtti1/3/4Model` into the same neutral edges |
| #37-6 | `CppVTableAnalyzer` — slot→`CppMethod`, `isVirtual` population; revisit the dedicated-module question |
| #37-7+ | `CppDecompilerHints`; parameter/return `DataType` resolution; templates + operators |

## Rejected alternatives

- **Have the feeder invoke a demangler (e.g. call `GnuDemangler`/`DemanglerUtil`
  internally).** Rejected: `GnuDemangler` shells out to a native `c++filt`
  process (`GnuDemangler.java:110-111`), which breaks headless CI unit testing,
  and invoking a concrete demangler couples the feeder to an ABI and (for the MSVC
  path) to a module Base cannot depend on. Keep the feeder a consumer of the
  already-produced `DemangledObject`; demangler selection is the caller's job.
- **Put the feeder in a demangler module (`GnuDemangler`/`MicrosoftDemangler`).**
  Rejected: those modules depend on Base's `DemangledObject` model, so a feeder
  that also writes the Base-resident `CppTypeSystem` belongs in Base; placing it
  in a leaf demangler module would invert the dependency and bind a
  demangler-agnostic mapper to one ABI.
- **Relax `CppClass` to allow a null backing `Structure`** so the feeder need not
  synthesize one. Rejected: it would weaken the #37-2 invariant every other slice
  relies on. A real empty placeholder `StructureDataType(name, 0)` is cheap, keeps
  the projection contract honest, and gives the later layout-recovery slice a
  concrete object to fill (decision 4).
- **Resolve parameter/return `DemangledDataType`s into Ghidra `DataType`s now.**
  Rejected as scope creep: recursive `DataType` construction needs a
  `DataTypeManager` and is a slice of its own; #37-3 carries name + qualifiers +
  convention and defers `CppMethod.signature` (decision 6).
- **Make #37-3 a program-scanning auto-analyzer.** Rejected: an analyzer needs a
  real program and an analysis-run harness (GUI/integration coupling) — the same
  reason DD-0011 kept #37-2 model-only. The pure mapper is the headlessly-testable
  core; the analyzer wrapper that walks a symbol table arrives with #37-4/#37-5.

## References

- [DD-0011](0011-rec37-cpptypesystem-skeleton.md) — the model this feeder fills;
  established the package, the projection rule, and ABI-agnosticism.
- [RFC-0001](../rfcs/0001-cpp-frontend.md) — parent proposal; this DD grounds its
  #37-3 slice.
- Demangled model (Base):
  `Ghidra/Features/Base/src/main/java/ghidra/app/util/demangler/DemangledObject.java`
  (`setName :240`, `setNamespace :315`, `setStatic :183`, `setVirtual :191`,
  `setConst :159`), `DemangledFunction.java` (ctor `:84`,
  `setCallingConvention :122`, `setTrailingConst :183`), `Demangled.java`,
  `DemangledType.java`, `DemangledNamespaceNode.java`, `DemangledDataType.java`,
  `DemangledParameter.java`.
- Demangler producers (shape of the input, not a dependency):
  `Ghidra/Features/GnuDemangler/.../gnu/GnuDemangler.java` (native process at
  `:110-111`; pure-Java parser at `:238-239`),
  `Ghidra/Features/MicrosoftDemangler/.../microsoft/MicrosoftDemangler.java`
  (pure-Java `MDMangGhidra`).
- Calling-convention vocabulary:
  `Ghidra/Framework/SoftwareModeling/.../program/model/lang/CompilerSpec.java`
  (`CALLING_CONVENTION_thiscall` etc.).
- Demangler test vectors emulated by the fixtures:
  `Ghidra/Features/GnuDemangler/src/test/java/ghidra/app/util/demangler/GnuDemanglerParserTest.java`
  (`_ZN3Bar4FredC1Ei` → `Bar::Fred::Fred(int)`);
  `Ghidra/Features/MicrosoftDemangler/src/test/java/ghidra/app/util/demangler/microsoft/MicrosoftDemanglerExtraTest.java`
  (`?CloseM@CRegKeyM@ATL@@QAEJXZ` → `public: long __thiscall ATL::CRegKey::Close(void)`).
- Model under fill (Base, shipped #37-2):
  `Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/` —
  `CppTypeSystem.java`, `CppClass.java`, `CppMethod.java`,
  `CppCallingConvention.java`.

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
