# Inbound upstream-merge policy

*Sprint 15 (meta-review 2026-06-11), Tier 3. The measurement half is
[`drift-report.md`](drift-report.md) (weekly,
`.github/workflows/upstream-drift.yml`); this is the policy half. The
cadence below is the working recommendation — **ratified 2026-06-12.***

## Why this exists

The fork has **never** merged upstream (merge-base `94164bd6e9` is the
2026-05-21 audit snapshot). At first measurement we are 203 commits behind
with 74 dry-run conflicts, and every fork-only line makes the next merge
more expensive — superlinearly, because conflicts compound. The outbound
half (give-back) was always tracked; this closes the inbound half.

## Operating model (read first — it sets the safety rules below)

**An agent performs the merges; the maintainer QCs the UI/result, not the
merge intent.** The safety of an inbound merge therefore rests on exactly two
things, in this order:

1. The **automated safety net** — it compiles, the test suite passes
   (especially the fork's own guard tests), deep-CI is green, the hint-recall
   baseline holds. This is what catches a wrong merge decision.
2. The maintainer's **UI/result QC** — the last line, for behaviour the tests
   don't cover.

It does **not** rest on a human reviewing every conflict's intent. That is the
whole reason small, well-tested changes are safe to delegate and large
swallow-a-release merges are not: the bigger the merge, the more likely a wrong
decision lands in code no test guards, slips past (1), and surfaces as a UI bug
in (2). Optimise every rule below for "keep each change small enough that the
safety net can actually catch a mistake."

## Divergence stages — which dial setting we are on

Taking upstream is a dial, not on/off. As fork-only surface grows, turn it down:

| Stage | What it means | When |
|---|---|---|
| **1 — Full merge** | Merge whole upstream releases. | While fork-only surface is small and conflicts are mostly mechanical. |
| **2 — Cherry-pick** | Stop bulk-merging; pull only specific upstream commits we want (security + bug fixes in code we use); skip refactors/features we've diverged on. | Once conflicts start hitting code where the fork and upstream *deliberately* disagree. |
| **3 — Security-only** | Pull only critical security fixes. | When even cherry-picks routinely fight fork architecture. |
| **4 — Hard fork** | Stop entirely; own all maintenance forever. | When upstream's direction no longer aligns with the fork's. |

**Downgrade trigger (formal):** move to the next stage down the first time a
*routine* merge hits a conflict in code the test suite does **not** cover, or a
backwards-architecture divergence (fork is ahead of the merge target in a
subsystem). One such hit = a warning; two in one merge = downgrade.

**Current stage: 2 (cherry-pick) — active as of 2026-06-12.** The first and
last full-release sync (PR #443, `Ghidra_12.1.2_build`, merged 2026-06-12) hit
*both* downgrade signals — a fork security-hardening divergence
(`ClassSearcher.forNameSafe` no-clinit, guarded by a test, so caught) and a
backwards-architecture divergence (the fork carries upstream master's Swift
restructure the 12.1.2 stable tag lacks). Per the formal trigger, two signals
in one merge = downgrade, so Stage 1 (bulk-merge) is **retired**: going forward
pull specific upstream commits (security + bug fixes in code we use), not whole
releases — unless a release is overwhelmingly bug-fixes with little fork
overlap.

### Sync log

| Date | Target | Merge commit | Conflicts resolved | Result |
|---|---|---|---|---|
| 2026-06-12 | `Ghidra_12.1.2_build` (`c0f584bf`) | `628a2749` (PR #443; upstream merge `8e32e006`) | 8 → 0 | Base advanced; tag now an ancestor of master, `merge-tree` vs the tag is clean. Stage 1 retired. |

## STOP conditions (hard rules — the agent must not cross these)

These are absolute. When one trips, **stop and surface it to the maintainer; do
not guess.**

1. **No oracle for a conflict.** If resolving a conflict requires *choosing a
   side* and there is **no test and no written rule** (a DD, a code comment, an
   existing fork pattern) that determines which side is correct — STOP. A guess
   that compiles and passes tests is exactly the failure mode the safety net
   cannot catch. Escalate; do not pick.
2. **A fork guard test would have to be deleted or weakened to make the merge
   pass.** That test encodes a deliberate fork decision (e.g. the no-clinit
   security property). Never resolve a merge by relaxing a fork test — STOP and
   ask. (Adding new upstream tests is fine; removing/loosening fork tests is
   not.)
3. **A fork-only file or subsystem conflicts.** Fork-owned paths (see the
   manifest below) should never conflict; if one does, something moved or was
   restructured upstream in a way that needs intent, not mechanics — STOP.
4. **The validation can't be run.** If the merged tree cannot be built and
   test-validated end-to-end before landing (no rig available), the merge does
   not land. An un-validatable merge is never "probably fine."

If none trip and every conflict has an oracle (test or written rule), the agent
may resolve and open a PR — but the merge still lands only through that PR, never
a direct push to master.

## Cadence

*Subordinate to the divergence stage above. At Stage 2 (cherry-pick), "cadence"
means "when we scan upstream for fixes worth picking," not "when we merge a
whole release."*

- **Stage 1 trigger (retired after PR #443): each upstream stable release.**
- **Stage 2 trigger (current): the weekly drift report + any security-relevant
  upstream commit.** Scan upstream for specific fixes in code the fork uses;
  cherry-pick those, skip the rest.
- **Target caveat (learned from PR #443):** the fork is *master-based*, so an
  upstream *stable tag* can be architecturally **behind** the fork in some
  subsystems (it lacked the Swift restructure the fork has). If a whole-release
  merge is ever done again, prefer the target whose lineage matches the fork
  (usually `master` for specific commits), and expect backwards-divergence when
  merging a stable tag — resolve it by **preferring the fork's newer code**.
- Ad-hoc escalation: dry-run conflicts crossing ~150 means the drift is past
  the point cherry-picking can keep up — surface it, don't bulk-merge.

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
