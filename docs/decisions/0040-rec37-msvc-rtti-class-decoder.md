---
number: 0040
title: Rec 37 #37-5-2 — the MSVC RTTI class decoder lifts the per-descriptor base decoder to a whole class; from one RTTIClassHierarchyDescriptor (Rtti3Model) it recovers the class's own demangled name and the list of its DIRECT bases only, walking the full-hierarchy RTTI2 base-class array in preorder and using each descriptor's numContainedBases subtree size to skip self and skip transitive ancestors; total-failure-safe, no program scanning
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0040: the #37-5-2 MSVC RTTI class decoder

## Context

[DD-0039](0039-rec37-msvc-rtti-base-decoder.md) shipped the first slice of the MSVC `#37-5` work:
[`CppMsvcRttiDecoder.decodeBase(Rtti1Model)`](../../Ghidra/Features/MicrosoftCodeAnalyzer/src/main/java/ghidra/app/cmd/data/rtti/CppMsvcRttiDecoder.java),
the pure decode of one `RTTIBaseClassDescriptor` into one ABI-neutral
[`CppRttiFeeder.BaseSpec`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppRttiFeeder.java)
(base name from the type descriptor, offset from `mdisp`, public-ness from the `BCD_PRIVORPROTBASE`
attribute bit; non-virtual bases only). That slice deliberately stopped at one descriptor — it did
**not** recover a class's direct-base *list*.

The list is the next thing `CppRttiFeeder.feedClass(derivedName, List<BaseSpec>)` needs, and it is not
a trivial map-over-the-array, because of how MSVC lays out the base-class array. The
`RTTIClassHierarchyDescriptor` (RTTI3) points at a base-class array (RTTI2) that lists the **full**
hierarchy in preorder DFS: index 0 is the class *itself*, then every transitive ancestor. For the
single-inheritance fixture `Base ← Shape ← Circle`, `Circle`'s array is `[Circle, Shape, Base]`. A naive
"every entry after index 0 is a direct base" reading would make `Base` a direct base of `Circle`, which is
wrong — `Base` is a transitive base, reached only through `Shape`.

The discriminator MSVC provides is the `numContainedBases` field on each `RTTIBaseClassDescriptor`
([`Rtti1Model.getNumBases()`](../../Ghidra/Features/MicrosoftCodeAnalyzer/src/main/java/ghidra/app/cmd/data/rtti/Rtti1Model.java)):
the size of that base's own subtree. In `Circle`'s array, `Circle` has `numContainedBases == 2`
(`Shape`, `Base`), `Shape` has `1` (`Base`), `Base` has `0`. Walking preorder and, at each direct base,
skipping past that base's `numContainedBases` contained entries lands on exactly the direct bases.

## Decision

Extend
[`CppMsvcRttiDecoder`](../../Ghidra/Features/MicrosoftCodeAnalyzer/src/main/java/ghidra/app/cmd/data/rtti/CppMsvcRttiDecoder.java)
with `decodeClass(Rtti3Model)`, returning a new nested record
`DecodedClass(String derivedName, List<BaseSpec> directBases)`, or `null` if it cannot. It stays in the
same stateless utility, reads only already-laid-down models, and never scans a program.

Four choices pin it:

1. **Derived name from the RTTI3's own type descriptor.** `classHierarchy.getRtti0Model().getDescriptorName()`
   is the class's own demangled unqualified name (`Circle`) — the same form `decodeBase` uses for base
   names and the form [`CppTypeSystem`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppTypeSystem.java)
   keys classes by. `Rtti3Model.getRtti0Model()` resolves to the array's index-0 (self) descriptor's RTTI0.

2. **Direct bases via a `numContainedBases` preorder walk.** Start after self (`i = 1`); at each entry,
   decode it with `decodeBase` (so the base name/offset/access semantics are shared with `#37-5-1`, single
   source of truth) and, regardless of whether it decoded, advance `i += 1 + entry.getNumBases()`. The
   entries jumped over are precisely that base's own ancestors — this class's *transitive* bases — so they
   are never emitted as direct. This mirrors how MSVC's own consumers read the array; Ghidra's
   [`RTTIWindowsClassRecoverer`](../../Ghidra/Features/Decompiler/ghidra_scripts/classrecovery/RTTIWindowsClassRecoverer.java)
   walks the whole array for the *full* ancestor set, but direct-base recovery needs the subtree skip.

3. **Skipped entries still advance the walk.** A virtual base (declined by `decodeBase`, `BaseSpec` null)
   or any non-decoding entry still contributes its `numContainedBases` to the cursor, so the preorder
   alignment is preserved and the *next* direct base is found at the right index. A declined entry drops
   its `BaseSpec` but not its place in the layout.

4. **Total-failure-safe, no scanning.** A null descriptor, one that does not validate, or one whose type
   descriptor yields no printable name yields `null` (no class). A descriptor whose `numContainedBases`
   reads negative breaks the walk (returning the bases found so far) rather than looping. Same advisory
   contract as the rest of the Rec 37 pipeline; reads only laid-down models.

## Consequences

- The MSVC RTTI → (derived name + direct `BaseSpec` list) translation exists and is grounded against the
  real Ghidra MSVC RTTI fixtures. Verified by
  [`CppMsvcRttiDecoderTest`](../../Ghidra/Features/MicrosoftCodeAnalyzer/src/test/java/ghidra/app/cmd/data/rtti/CppMsvcRttiDecoderTest.java)
  (8/8): single inheritance excludes the transitive `Base` from `Circle`'s direct bases
  (`Circle → [Shape]`, `Shape → [Base]`, `Base → []`), and a multiple-inheritance reinterpretation of the
  same array emits two unrelated direct bases in array order (`Circle → [Shape, Base]`).
- **Grounding note.** The complete-flow `AbstractRttiTest` fixtures lay every `RTTIBaseClassDescriptor`
  down with `numContainedBases == 0` (they test struct parsing, not hierarchy shape). The new tests write
  the real subtree sizes into the shared descriptors (a 4-byte `setBytes` at `RTTI1 + 4`) to express each
  graph; `numContainedBases` is not constrained by `Rtti1Model` validation, so the overwrite is safe.
- **Still no virtual bases, still no scanning.** Virtual bases are declined (offset needs the runtime
  vbtable), and the decoder does not walk a program. Feeding `CppRttiFeeder.feedClass` from a program scan
  is the remaining `#37-5-3` slice.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :MicrosoftCodeAnalyzer:test --tests 'ghidra.app.cmd.data.rtti.CppMsvcRttiDecoderTest'` (8/8)
  and `gradle :MicrosoftCodeAnalyzer:ip` (certification green), system `gradle` 8.5.
