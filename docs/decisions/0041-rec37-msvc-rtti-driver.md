---
number: 0041
title: Rec 37 #37-5-3 — the MSVC RTTI driver bridges the pure CppMsvcRttiDecoder decode to the ABI-neutral CppRttiFeeder; given one already-laid-down MSVC RTTI structure (an RTTICompleteObjectLocator/Rtti4Model or the RTTIClassHierarchyDescriptor/Rtti3Model it references) it decodes the class name and direct bases and feeds them into a CppTypeSystem; total-failure-safe, feeds one located structure, does NOT scan a program to discover them
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0041: the #37-5-3 MSVC RTTI driver

## Context

The MSVC `#37-5` work shipped in pure-decode slices: [DD-0039](0039-rec37-msvc-rtti-base-decoder.md)
([`CppMsvcRttiDecoder.decodeBase`](../../Ghidra/Features/MicrosoftCodeAnalyzer/src/main/java/ghidra/app/cmd/data/rtti/CppMsvcRttiDecoder.java))
turns one `RTTIBaseClassDescriptor` into one
[`CppRttiFeeder.BaseSpec`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppRttiFeeder.java);
[DD-0040](0040-rec37-msvc-rtti-class-decoder.md) (`decodeClass`) lifts that to a whole class
(`DecodedClass(derivedName, directBases)`). Both are pure: they read already-laid-down RTTI models and
produce ABI-neutral facts. Neither touches a [`CppTypeSystem`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppTypeSystem.java).

The consumer that *does* attach those facts is `CppRttiFeeder.feedClass(derivedName, List<BaseSpec>)`,
shipped headless by `#37-4` (DD-0013). What was missing is the bridge: take a located MSVC RTTI structure,
decode it, and feed it. That bridge is the value `CppRttiFeeder`'s own javadoc names as the "program-scanning
analyzer wrappers (`#37-4b` Itanium, `#37-5` MSVC)" — but the *full* wrapper conflates two very different
jobs: **(a) discovering** every RTTI structure in a binary, and **(b) feeding** each discovered class.

Job (a) — discovery — is heavy and program-coupled: Ghidra's own
[`RttiAnalyzer`](../../Ghidra/Features/MicrosoftCodeAnalyzer/src/main/java/ghidra/app/plugin/prototype/MicrosoftCodeAnalyzerPlugin/RttiAnalyzer.java)
does it by finding the `type_info` vftable, byte-searching `.rdata` for pointers to it, and walking
vftables to complete object locators — all under the `AbstractAnalyzer`/`TaskMonitor` framework. That is the
program-coupled analyzer wrapper, a separate later item, and not headlessly grounds-and-tests cleanly here.
Job (b) — feeding one located class — *is* headless and testable: the `AbstractRttiTest` fixtures lay down a
full RTTI4→RTTI3→RTTI2→RTTI1→RTTI0 chain that the models read directly.

## Decision

Ship
[`CppMsvcRttiDriver`](../../Ghidra/Features/MicrosoftCodeAnalyzer/src/main/java/ghidra/app/cmd/data/rtti/CppMsvcRttiDriver.java),
a stateless utility that performs job (b) only: `feedClass(model, CppRttiFeeder)` decodes one located MSVC
RTTI structure via `CppMsvcRttiDecoder.decodeClass` and, if it yields a class, calls
`feeder.feedClass(decoded.derivedName(), decoded.directBases())`, returning the fed `CppClass`. It lives in
`ghidra.app.cmd.data.rtti` (the MicrosoftCodeAnalyzer module, which `api project(":Base")`), next to the
decoder it drives.

Three choices pin it:

1. **Two entry forms.** An overload takes the `RTTICompleteObjectLocator`
   ([`Rtti4Model`](../../Ghidra/Features/MicrosoftCodeAnalyzer/src/main/java/ghidra/app/cmd/data/rtti/Rtti4Model.java)
   — the form a vftable's `[-1]` slot points at, the natural discovery anchor) and resolves its
   `getRtti3Model()`; another takes the `RTTIClassHierarchyDescriptor` (`Rtti3Model`) directly. Both funnel
   through `decodeClass`, so the discovery wrapper can call whichever form it has in hand.

2. **Decode failures are advisory; a null feeder is a bug.** A null model, one that does not validate, or
   one that yields no class feeds nothing (`null`) — the same total-failure-safe contract as the decoder.
   A null `feeder`, by contrast, is a programming error and throws `IllegalArgumentException`, matching
   `CppRttiFeeder`'s own null-argument contract (a missing sink is not advisory data, it is a caller bug).

3. **Feeds one located structure; does not discover.** The driver never scans a program. Enumerating every
   complete object locator in a binary is deferred to the program-coupled `CppRttiAnalyzer` wrapper.

## Consequences

- The MSVC RTTI → `CppTypeSystem` inheritance pipeline now exists **end to end** for one located class, and
  is grounded against the real Ghidra MSVC RTTI fixtures. Verified by
  [`CppMsvcRttiDriverTest`](../../Ghidra/Features/MicrosoftCodeAnalyzer/src/test/java/ghidra/app/cmd/data/rtti/CppMsvcRttiDriverTest.java)
  (5/5): feeding the complete-flow `Base`/`Shape`/`Circle` complete object locators reshaped for single
  inheritance produces the right graph (`Base` no edges, `Shape → Base`, `Circle → Shape` with `Base`
  excluded as transitive), each edge pointing at the resolved shared `CppClass`; the RTTI3 entry form feeds
  the same graph; a multiple-inheritance reshape yields two direct edges in array order; null models feed
  nothing; a null feeder is rejected. This is the seam the decoder unit tests (which stop at `BaseSpec`) and
  the feeder unit tests (which start at `BaseSpec`) each cover only one side of.
- **What is still deferred.** Program-wide *discovery* of RTTI structures (the `RttiAnalyzer`-style
  byte-search/vftable walk) is the program-coupled `CppRttiAnalyzer` wrapper — a later item. Virtual-base
  offsets remain deferred (the decoder declines virtual bases; their offset needs the runtime vbtable).
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :MicrosoftCodeAnalyzer:test --tests 'ghidra.app.cmd.data.rtti.CppMsvcRttiDriverTest'` (5/5) and
  `gradle :MicrosoftCodeAnalyzer:ip` (certification green), system `gradle` 8.5.
