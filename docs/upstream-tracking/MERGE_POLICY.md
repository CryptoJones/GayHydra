# Inbound upstream-merge policy

*Sprint 15 (meta-review 2026-06-11), Tier 3. The measurement half is
[`drift-report.md`](drift-report.md) (weekly,
`.github/workflows/upstream-drift.yml`); this is the policy half. The
cadence below is the working recommendation — Aaron ratifies or amends it.*

## Why this exists

The fork has **never** merged upstream (merge-base `94164bd6e9` is the
2026-05-21 audit snapshot). At first measurement we are 203 commits behind
with 74 dry-run conflicts, and every fork-only line makes the next merge
more expensive — superlinearly, because conflicts compound. The outbound
half (give-back) was always tracked; this closes the inbound half.

## Cadence

- **Trigger: each upstream stable release** (NSA tags `Ghidra_X.Y`), plus an
  ad-hoc merge when the weekly drift report shows a security-relevant
  upstream change or dry-run conflicts crossing ~150 (past that, conflict
  archaeology dominates).
- Between releases, the weekly drift report is the watch signal — no
  routine master-to-master merging (upstream master carries churn the
  stable tags later settle).

## Pre-merge checklist (the guards must exist and be green first)

1. Deep-CI green on current master (fork `test.slow` suites, fuzz smoke,
   master decompiler-smoke) — `.github/workflows/deep-ci.yml`.
2. Hint-recall baseline green (`scripts/hint-recall.sh`) — upstream
   decompiler idiom drift is *the* mechanism by which a merge silently
   collapses Rec 37 recall; the corpus is the tripwire.
3. Fresh drift report (`scripts/upstream-drift.sh`) for the conflict list.

## Merge procedure

1. Branch `merge/upstream-<tag>` from master; `git merge <upstream-tag>`.
2. Resolve conflicts per the playbook below; commit with a body listing
   every conflicted file and the resolution rule applied.
3. Full local validation: the deep-CI trio + `ipc_e2e` + hint-recall +
   `decomp_test_dbg unittests` (the podman rig in
   `~/.cache/gayhydra-gradle` reproduces this on toolchain-less boxes).
4. PR to master (normal lanes); the PR description links the drift report
   revision the merge consumed.
5. After merge: re-run `scripts/upstream-drift.sh` (behind-count returns
   to ~0), update the collision watch-list below if any entry fired.

## Conflict playbook

Default rules, in order:

| Situation | Rule |
|---|---|
| Upstream changed a file the fork only *rebranded* (headers, names) | Take upstream, re-apply the rebrand mechanically |
| Upstream changed code adjacent to a fork feature (e.g. `DecompileProcess`, `ghidra_process.cc`) | Take upstream structure, re-thread the fork seam (the fork keeps its seams small and named: `schemaPayloadAvailable`, `loadParametersV1`, framing negotiation) — then the e2e suite is the arbiter |
| Upstream rewrote something the fork also rewrote (e.g. RAII'd files) | Prefer upstream's rewrite if it subsumes ours (less fork-only surface); keep ours only when an audit gate (`cppRaiiAudit`) or DD documents why |
| Upstream touched generated files (`xml.cc`, `slghparse.y` outputs) | Regenerate, never hand-merge; `sleighGrammarAudit` pins must be refreshed deliberately |
| Fork-only files (`ghidra/app/util/cpp`, `ghidra/app/util/scope`, `frame_v1.*`, `schema/`, `fuzz/`, fork workflows/docs) | Never conflict by construction; if one does, something moved — stop and investigate |

## Collision watch-list (behavioral, not just textual)

- **Upstream decompiler IPC evolution vs Rec 33/34** — `decompile/cpp/Makefile`
  is already in the live conflict list; any upstream change to
  `ghidra_process.cc`'s command loop or `DecompileProcess.java` must be
  re-validated against the full `ipc_e2e` suite (framing + schema legs).
- **Upstream Jython/PyGhidra moves vs Rec 42** — check upstream posture
  before executing the removal (issue #441 carries the gate).
- **Upstream `RttiAnalyzer`/demangler churn vs the Rec 37 harvest scans** —
  DD-0061/DD-0065 deliberately consume upstream analyzer *output* (laid-down
  RTTI4 data, published vftable symbols). A change in what upstream lays
  down breaks the feed without any merge conflict. The hint-recall corpus
  (and its future PE column) is the only tripwire — keep it ahead of merges.

## Fork-owned paths (reviewer aid; the drift script computes the live
conflict set dynamically)

`Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/`,
`.../scope/`, `Ghidra/Features/Decompiler/src/decompile/cpp/frame_v1.*`,
`.../schema/`, `.../fuzz/`, `Ghidra/Features/Decompiler/src/main/java/ghidra/app/decompiler/ipc/`,
`Ghidra/Framework/Emulation/src/test/java/ghidra/pcode/difftest/`,
`samples/`, `scripts/`, `docs/`, `.github/workflows/` (fork additions),
`gradle/*Audit.gradle`.
