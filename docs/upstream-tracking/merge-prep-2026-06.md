# Upstream merge prep — 2026-06 window

*Conflict triage for the first upstream sync ([MERGE_POLICY.md](MERGE_POLICY.md)).
Dated because the conflict set drifts; regenerate before the actual merge.
Read-only analysis — the merge itself is Aaron-attended.*

## Headline

Merge base `94164bd6e9` (2026-05-20), **203 commits behind** upstream master.
The dry-run merge (`git merge-tree`) conflicts on **14 files** — *not* the 74
the drift report previously showed (that was a script bug counting
`Auto-merging` info lines; fixed in the same change as this doc). **None are
in fork-owned files** (the `ghidra.app.util.cpp`/`scope`, `frame_v1`,
`schema/`, `fuzz/`, `ipc/`, `difftest/` trees merge clean). The 14 fall into
three buckets, all shallow:

## Bucket A — already-ported upstream PRs → **take upstream** (reduces divergence)

The fork hand-ported these upstream PRs; upstream master now contains the
canonical merge of the same change, so the conflict is "same fix, two forms."
Resolution: **take upstream's version**, drop the fork's port. This *shrinks*
the fork delta.

| File | Fork port |
|---|---|
| `Ghidra/Features/Base/.../bin/format/pe/SectionHeader.java` | Port of upstream PR #9173 (max loadable chars) |
| `Ghidra/Framework/SoftwareModeling/.../symbol/SymbolUtilities.java` | Port of upstream PR #9172 (char 127 invalid) |
| `Ghidra/Processors/x86/data/languages/fma.sinc` | Port of upstream PR #9197 (YMM FMA p-code temporaries) |

After the merge, the corresponding fork-port commits can be noted as
upstreamed in `give-back-candidates.md`.

## Bucket B — fork security pattern `ClassSearcher.forNameSafe` → re-thread (mechanical)

Eight files conflict from **one** fork commit (`fix(security): adopt
ClassSearcher.forNameSafe safe Class.forName`), which replaced unsafe
`Class.forName(...)` calls with the fork's safe variant. They conflict only
because upstream also edited these files. Resolution: **take upstream's
version, re-apply the one-line `forNameSafe` substitution** at each call site
(the change is uniform — find the `Class.forName`/`ClassSearcher.forName` the
fork hardened and swap it back to `forNameSafe`).

- `Ghidra/Features/Base/.../app/nav/LocationMemento.java`
- `Ghidra/Framework/Generic/.../framework/options/GProperties.java`
- `Ghidra/Framework/Generic/.../util/classfinder/ClassSearcher.java` (the
  helper itself — careful: this defines `forNameSafe`; take fork's definition,
  merge upstream's other edits)
- `Ghidra/Framework/Generic/.../util/classfinder/ClassSearcherTest.java`
  (add/add — both sides added tests; concatenate the two test sets)
- `Ghidra/Framework/Gui/.../generic/theme/ThemePreferences.java`
- `Ghidra/Framework/Gui/.../framework/options/WrappedCustomOption.java`
- `Ghidra/Framework/Project/.../project/tool/GhidraToolTemplate.java`
- `Ghidra/Framework/SoftwareModeling/.../program/util/ProgramLocation.java`

`ClassSearcher.java` is also where the runtime `ClassCastException` noise seen
during headless runs originates — worth confirming the merged version keeps
the fork's `forNameSafe` contract intact.

## Bucket C — rebrand / fork-feature / trivial

| File | Nature | Resolution |
|---|---|---|
| `SECURITY.md` (add/add) | Fork rebrand to GayHydra (Rec 11) | Take fork's; graft any new upstream policy substance |
| `Ghidra/Features/Decompiler/src/decompile/cpp/Makefile` | Fork Rec 32 (c++20) + Rec 34 (FlatBuffers `ghi_*` rules) | Re-thread: take upstream structure, re-apply the c++20 std bump + the `FLATBUF_INCLUDE`/schema compile rules |
| `Ghidra/Framework/FileSystem/data/serialFilterREADME.md` | Fork typo-fix batch | Trivial; merge both (take upstream text + re-apply the typo corrections) |

## Recommended merge order

1. Branch `merge/upstream-<tag>` per MERGE_POLICY; `git merge upstream/<tag>`.
2. Bucket A first (take-upstream — removes 3 conflicts and shrinks the delta).
3. Bucket B (mechanical `forNameSafe` re-application — 8 files, one pattern).
4. Bucket C (3 files, judgment but shallow).
5. Validate: deep-CI trio + `ipc_e2e` + hint-recall baseline + `decomp_test_dbg`
   (the podman rig reproduces all of this).

The whole set is a few hours of mechanical work, not the multi-day
archaeology "74 conflicts" implied — the corrected count is the de-risking
finding.
