---
number: 0039
title: Rec 37 #37-5-1 — the MSVC RTTI per-descriptor base decoder is the first slice of the MSVC CppRttiAnalyzer; it turns one RTTIBaseClassDescriptor (Rtti1Model) into one ABI-neutral CppRttiFeeder.BaseSpec — base name from the type descriptor, offset from mdisp, public-ness from the BCD_PRIVORPROTBASE attribute bit — for non-virtual bases only, declining virtual bases whose offset needs the runtime vbtable; total-failure-safe, no program scanning
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0039: the #37-5-1 per-descriptor MSVC RTTI base decoder

## Context

[DD-0013](0013-rec37-rtti-feeder.md) shipped
[`CppRttiFeeder`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppRttiFeeder.java),
the ABI-neutral consumer that takes a derived class's name and its already-recovered direct
[`BaseSpec`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppRttiFeeder.java)
facts (`baseName`, `offset`, `isVirtual`, `isPublic`) and attaches one `CppBaseClass` edge per base.
Its javadoc names the two analyzers that decode real RTTI into those facts before calling it:
`#37-4b` (Itanium) and `#37-5` (MSVC). The Itanium feeder half shipped as `#37-4`; the MSVC
program-scanning analyzer is **PR #37-5**, and was not started.

Rec 37 recognition (the seven C++ idiom forms detected in a live `HighFunction`) went seven-of-seven
end-to-end with the placement-new driver ([DD-0038](0038-rec37-placement-construction-driver.md)).
PR #37-5 is the next Sprint 14 Step 2 item: walk a program's MSVC RTTI and feed the inheritance graph
into the type system. MSVC exposes inheritance through a chain Ghidra already models maturely
([`Rtti4Model`](../../Ghidra/Features/MicrosoftCodeAnalyzer/src/main/java/ghidra/app/cmd/data/rtti/Rtti4Model.java)
→ `Rtti3Model` → `Rtti2Model` → `Rtti1Model` → `TypeDescriptorModel`): the
`RTTIClassHierarchyDescriptor` (RTTI3) points at a base-class array (RTTI2) of
`RTTIBaseClassDescriptor`s (RTTI1), each of which names a class (via its RTTI0 type descriptor) and
carries a `PMD` (member/vbtable/vdisp displacements) plus an `attributes` bitfield.

PR #37-5 is sliced small. This decision ships the **first slice (`#37-5-1`)**: the pure decode of a
*single* `RTTIBaseClassDescriptor` into a single `BaseSpec`. The array walk that recovers a class's
full direct-base list, and the program-scanning analyzer that finds the descriptors and calls the
feeder, are the later `#37-5` slices.

## Decision

Ship
[`CppMsvcRttiDecoder`](../../Ghidra/Features/MicrosoftCodeAnalyzer/src/main/java/ghidra/app/cmd/data/rtti/CppMsvcRttiDecoder.java),
a stateless utility whose `decodeBase(Rtti1Model)` turns one base class descriptor into one `BaseSpec`,
or `null` if it cannot. It lives in `ghidra.app.cmd.data.rtti` (the MicrosoftCodeAnalyzer module, which
`api project(":Base")`), next to the RTTI models it reads; that is the one package that can import both
the `RttiNModel` chain and Base's `CppRttiFeeder.BaseSpec` without splitting the `ghidra.app.util.cpp`
package across two modules.

Four choices pin it:

1. **Base name from the type descriptor's demangled name.** The base's name is
   `descriptor.getRtti0Model().getDescriptorName()` — the *unqualified demangled* class name (e.g.
   `Base`, not the mangled `.?AVBase@@`), which is exactly the form
   [`CppTypeSystem`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppTypeSystem.java)
   keys classes by (`Structure.getName()`) and the form the Rec 37 drivers resolve. Grounded
   empirically: a probe through the `AbstractRttiTest` complete-flow fixture showed
   `getDescriptorName()` returns `Base`/`Shape`/`Circle`.

2. **Offset from `mdisp`, for non-virtual bases only.** For a non-virtual base the subobject offset is
   exactly the descriptor's member displacement `mdisp`. A descriptor whose `pdisp` is not `-1` names a
   **virtual** base, whose true offset is `*(vbtable + vdisp) + pdisp` — it depends on the runtime
   `vbtable` contents, program data a *pure descriptor* decode cannot reach. Such a descriptor is
   declined (`null`); virtual-base offset recovery is deferred to the program-scanning slice that has
   the `vbtable`. This matches how Ghidra's own
   [`RTTIWindowsClassRecoverer`](../../Ghidra/Features/Decompiler/ghidra_scripts/classrecovery/RTTIWindowsClassRecoverer.java)
   reads the PMD (`pdisp == -1` ⇒ non-virtual, offset `mdisp`; else virtual via the vbtable).

3. **Public-ness from the `BCD_PRIVORPROTBASE` attribute bit.** A base is `public` unless the
   `0x04` bit of the descriptor's `attributes` word is set (MSVC `<rttidata.h>` base-class-descriptor
   flags). `Rtti1Model` exposes the raw `attributes` word but does not interpret it, so the bit is
   named as a constant in the decoder with the ABI citation.

4. **Total-failure-safe, no scanning.** A null descriptor, one that does not validate, a virtual base,
   or one whose type descriptor yields no printable name contributes no fact (`null`), never an
   exception or a mis-decode — the same advisory contract the rest of the Rec 37 pipeline holds. The
   decoder reads only already-laid-down models; it never walks a program.

## Consequences

- The MSVC RTTI → `BaseSpec` translation core exists and is grounded against real Ghidra MSVC RTTI
  fixtures. Verified by
  [`CppMsvcRttiDecoderTest`](../../Ghidra/Features/MicrosoftCodeAnalyzer/src/test/java/ghidra/app/cmd/data/rtti/CppMsvcRttiDecoderTest.java)
  (5/5): the real `Base`/`Shape`/`Circle` descriptors decode to non-virtual public offset-0 bases, a
  non-zero `mdisp` proves the offset is read from the descriptor, a `BCD_PRIVORPROTBASE` bit yields
  `isPublic == false`, a virtual base (`pdisp != -1`) is declined, and a null descriptor is safe.
- **Scope of this slice.** One descriptor → one fact. It does **not** yet recover a class's direct-base
  *list* (the RTTI3→RTTI2 array walk) — and note the MSVC base-class array lists the *full* hierarchy
  (the class itself at index 0, then every transitive base), so the array-walk slice must skip self and
  distinguish direct from transitive bases; that is `#37-5` follow-on work. It does not feed
  `CppRttiFeeder` (no program scanning), and it does not handle virtual-base offsets.
- The `BaseSpec.isVirtual` field is always `false` from this slice (virtual descriptors are declined,
  not emitted as virtual). Emitting virtual bases with real offsets arrives with the vbtable-aware
  analyzer slice.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :MicrosoftCodeAnalyzer:test --tests 'ghidra.app.cmd.data.rtti.CppMsvcRttiDecoderTest'`
  (5/5) and `gradle :MicrosoftCodeAnalyzer:ip` (certification green for the two new files), system
  `gradle` 8.5.
