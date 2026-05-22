# Sprint Planning

Upcoming sprints. Each sprint is a logical batch, not a fixed
time-box. Sprints are *ordered*, not *scheduled* — they ship when
they ship.

For completed sprints, see [SprintHistory.md](SprintHistory.md).
For the *why* behind individual choices, see
[DesignDecisions.md](DesignDecisions.md).

---

## Sprint 10 — OSS-Fuzz submission + Stage 3 finish + give-back PRs

**Done:**

- [x] ~~**Audit-datatests as ongoing regression guard**~~ — [PR #260](https://github.com/CryptoJones/GayHydra/pull/260) (weekly schedule).
- [x] ~~**Rec 25/26 Stage 3 pre-clean**~~ — PRs [#261](https://github.com/CryptoJones/GayHydra/pull/261), [#265](https://github.com/CryptoJones/GayHydra/pull/265), [#267](https://github.com/CryptoJones/GayHydra/pull/267), [#268](https://github.com/CryptoJones/GayHydra/pull/268), [#269](https://github.com/CryptoJones/GayHydra/pull/269), [#270](https://github.com/CryptoJones/GayHydra/pull/270), [#271](https://github.com/CryptoJones/GayHydra/pull/271) cleared the warning floor across every ≥5-warning subproject. javacc-generated source patched via a `buildJavacc` `doLast` hook.
- [x] ~~Consolidated datatest regex updates to NSA~~ — [NSA/ghidra#9207](https://github.com/NationalSecurityAgency/ghidra/pull/9207).
- [x] ~~`-dumpdir` audit-tooling flag to NSA~~ — [NSA/ghidra#9208](https://github.com/NationalSecurityAgency/ghidra/pull/9208).
- [x] ~~**Mac Mini bootstrap**~~ — `mac-mini` SSH alias (172.16.28.199) now has Homebrew + Temurin-21 + Gradle 9.5.1 + bison + flex + the repo. Driver at `~/bin/mac-mini-build`. First green build 6m31s cold, 3m53s incremental. Saved to memory at `~/.claude/projects/.../memory/macmini-build-host.md` so future sessions reach for it.
- [x] ~~**PIC-24F GE-recognition regression**~~ — [PR #275](https://github.com/CryptoJones/GayHydra/pull/275) ported the slaspec half of upstream NSA/ghidra#8778 and re-enabled the two `pic_branch_ge.xml` stringmatch tests. Closes [issue #259](https://github.com/CryptoJones/GayHydra/issues/259).

**Open:**

- [ ] **Rec 13/14 OSS-Fuzz submission** — blocked on [issue #262](https://github.com/CryptoJones/GayHydra/issues/262): replace placeholder `primary_contact` / `auto_ccs` in `.github/oss-fuzz/project.yaml` with real maintainer emails before the upstream PR. External-contact decision (whose inbox monitors OSS-Fuzz crash reports?) — needs Aaron to pick the addresses.
- [ ] **Stage 3 step 6 — `-Werror` + ErrorProne ratchet** — deferred per [PR #271](https://github.com/CryptoJones/GayHydra/pull/271). The local Mac Mini test surfaced an ErrorProne/-Werror Catch-22 (`allErrorsAsWarnings = true` degrades ErrorProne errors to javac warnings, which `-Werror` then promotes back to errors). Needs a global ErrorProne reconfiguration OR a per-file suppression sweep across the tree. Bigger than originally scoped — its own sprint.
- [ ] **`Automatic Dependency Submission (Gradle)`** pre-existing workflow failure — [issue #273](https://github.com/CryptoJones/GayHydra/issues/273): disable in repo Settings → Code security. In-tree fix attempted but only moves failure deeper (dbgeng TLB assert, then MarkdownSupport repos) — needs Aaron to click through Settings (no REST API).

---

## Sprint 6 — finish the Sprint-1 implementation surface

**Quick wins:**

- [ ] Flip `.github/workflows/sync-labels.yml`'s `dry-run` to `false` after Aaron reviews the first workflow run.
- [ ] **Rec 11 follow-up:** track [NSA/ghidra#9202](https://github.com/NationalSecurityAgency/ghidra/pull/9202) and respond to reviewer comments.

**External submissions:**

- [ ] **Rec 13:** submit `.github/oss-fuzz/` to [google/oss-fuzz](https://github.com/google/oss-fuzz) as `projects/ghidra-decompiler/`.
- [ ] **Rec 14:** same for `projects/ghidra-loader/` (JVM project, Jazzer harnesses).

**Code-touching implementation:**

- [x] ~~**Rec 19 #19-2:** SafeObjectInput migration for `ItemDeserializer`~~ — [PR #293](https://github.com/CryptoJones/GayHydra/pull/293). Added `SafeObjectInput.headerStream()` helper; `CodeUnitInfo` reclassified out of Class A (not a deserialization surface — `CodeUnitInfoTransferable` uses `javaJVMLocalObjectMimeType`, no bytes cross OIS).
- [x] ~~**Rec 19 #19-3:** Class B regression test~~ — [PR #297](https://github.com/CryptoJones/GayHydra/pull/297). Class B sites (`ItemCheckoutStatus`, `Version`, `RepositoryItem`) confirmed structurally covered by GP-6719 RMI filter + XML-not-Java-serial on-disk path. Added `allowsClassBSites` regression test.
- [x] ~~**Rec 19 #19-5:** `AbstractDBTracePropertyMap` migration~~ — [PR #299](https://github.com/CryptoJones/GayHydra/pull/299). The last direct `new ObjectInputStream(...)` site in production. New `SafeObjectInput.openStream()` helper accepts a caller-supplied filter; baseline `allowlist()` (String + primitive wrappers) permits the adapter's String reads, rejects everything else.
- [x] ~~**Rec 19 #19-6:** enforcement gate~~ — [PR #301](https://github.com/CryptoJones/GayHydra/pull/301). `gradle objectInputStreamAudit` task forbids any new raw OIS construction outside `SafeObjectInput.java`. **Closes Rec 19** of the 42-rec audit.
- [x] ~~**Rec 25 Stage 2:** widen `-Xlint` to `deprecation,unchecked,rawtypes,cast`~~ — already shipped (see `gradle/javaProject.gradle` default `lintOpts`).
- [x] ~~**Rec 26 Stage 2:** `JavaUtilDate` and `JdkObsolete` ErrorProne checks at WARNING~~ — already shipped (see `gradle/errorprone.gradle`).
- [x] ~~**Rec 28 #28-5:** dead commented-out `//@Ignore` cleanup~~ — [PR #295](https://github.com/CryptoJones/GayHydra/pull/295) removed 7 dead lines in `MDMangBaseTest` + `CompositeMemberTest`. Active-`@Ignore` sweep continues as #28-6+.
- [ ] **Rec 28 #28-6+:** active-`@Ignore` fix-or-delete sweep across the remaining 78 properly-categorized sites (46 `wip` / 19 `blocked-on` / 10 `manual-tool` / 3 `flaky`).

**Carried (still deferred):**

- [ ] 3 structural-drift upstream PRs (NSA#5593, NSA#3974, NSA#3137).

**Give-back PRs to NSA/ghidra:**

- [ ] Identify ports where our resolution is cleaner than the original and open follow-up PRs upstream.

---

## Sprint 5 — More Sprint-1 implementation + give-back PRs (delivered — see SprintHistory.md)

**Carried from Sprint 4:**

- [ ] 3 structural-drift upstream PRs (NSA#5593, NSA#3974, NSA#3137) — decide whether to invest the hand-port effort or just close as "won't backport".

**Implementation surface — pick the cleanest next batch:**

- [ ] **Rec 11 follow-up:** track [NSA/ghidra#9202](https://github.com/NationalSecurityAgency/ghidra/pull/9202) and respond to reviewer comments.
- [ ] **Rec 12:** open draft GHSAs for the three audit-named internal trackers (`GP-6832`, `GP-6719`, `GP-258`). Requires CVSS + affected-version range — needs git blame work to identify the affected commits in our fork.
- [ ] **Rec 13:** submit `.github/oss-fuzz/` to [google/oss-fuzz](https://github.com/google/oss-fuzz) as `projects/ghidra-decompiler/`.
- [ ] **Rec 14:** same submission for `projects/ghidra-loader/` (JVM project, Jazzer harnesses).
- [ ] **Rec 17 #17-3:** add Cosign verification commands to release-notes template.
- [ ] **Rec 19 #19-2:** first SafeObjectInput migration — `ItemDeserializer` + `CodeUnitInfo` (Class A sites — attacker-reachable).
- [ ] **Rec 21:** SBOM build-sanity gate (≥10 components or fail).
- [ ] **Rec 25 Stage 2:** widen `-Xlint` to `deprecation,unchecked,rawtypes,cast`. Pre-clean any subproject ≥50 warnings.
- [ ] **Rec 26 Stage 2:** `JavaUtilDate` and `JdkObsolete` ErrorProne checks at WARNING.
- [ ] **Rec 28 #28-5+:** broader `@Ignore` sweep across the ~25 other in-tree sites.

**Give-back PRs to NSA/ghidra:**

- [ ] Identify ports where our resolution is cleaner than the original (e.g. PRs that hand-resolved a conflict by deleting commented-out lines) and open follow-up PRs back to upstream NSA/ghidra.

---

## Sprint 4 — Security Foundations

The Rec 11–21 design surfaces shipped in v26.1; this sprint lands the
first implementation tier.

- [ ] **Rec 11 follow-up:** track [NSA/ghidra#9202](https://github.com/NationalSecurityAgency/ghidra/pull/9202) and respond to reviewer comments.
- [ ] **Rec 12:** open draft GHSAs for the three audit-named internal trackers (`GP-6832`, `GP-6719`, `GP-258`); fill in CVSS + affected ranges.
- [ ] **Rec 13:** submit `.github/oss-fuzz/` to [google/oss-fuzz](https://github.com/google/oss-fuzz) as `projects/ghidra-decompiler/`; verify first trial-run on their infrastructure.
- [ ] **Rec 14:** add `projects/ghidra-loader/` JVM project to OSS-Fuzz once Rec 13 is green.
- [ ] **Rec 18 PR #18-2:** `ItemDeserializer` hardening — declared-size precheck + running-counter cap + clean-up on failure (closes [upstream #1481](https://github.com/NationalSecurityAgency/ghidra/issues/1481)). Coordinate-disclose with NSA before publishing.
- [ ] **Rec 19 PR #19-1:** `SafeObjectInput` helper class + per-call allowlist + depth/byte caps + unit tests.

---

## Sprint 5 — CI / Static-Analysis Foundations

- [ ] **Rec 17 PR #17-2:** Cosign keyless release-signing workflow wired into the release flow.
- [ ] **Rec 17 PR #17-3:** update release-notes template with verification commands.
- [ ] **Rec 21:** SBOM build-sanity gate (≥10 components or fail).
- [ ] **Rec 25 Stage 2:** widen `-Xlint` to `deprecation,unchecked,rawtypes,cast`. Pre-clean the floor in any subproject ≥50 warnings.
- [ ] **Rec 26 Stage 2:** turn on `JavaUtilDate` and `JdkObsolete` ErrorProne checks at WARNING.
- [ ] **Rec 28 PR #28-2:** inventory the six audit-named `@Ignore` tests; file tracking issues per rule.
- [ ] **Rec 28 PR #28-3:** `gradle ignoreAudit` task + CI wiring.

---

## Sprint 6 — Decompiler Foundations: C++14/20 + RAII Stage 1–2

- [ ] **Rec 32 PR #32-2:** bump `-std=c++11` → `-std=c++14` in `decompile/cpp/Makefile`. CI green on all 3 platforms.
- [ ] **Rec 31 PR #31-2:** RAII Stage 1 — convert `address.cc`, `space.cc`, `range.cc` to `unique_ptr`. CI lint: no raw `new` in these files.
- [ ] **Rec 31 PR #31-3 + Rec 32 PR #32-4:** RAII Stage 2 (`marshal.cc`, `xml.cc`) paired with `std::span` adoption in their parameter pairs. Joint review.
- [ ] **Rec 32 PR #32-3:** bump to `-std=c++20`. CI gate.

---

## Sprint 7 — IPC Modernization (Recs 33, 34)

- [ ] **Rec 33 PR #33-2:** framing v1 — greeting, CRC32, resync. v0 fallback active.
- [ ] **Rec 34 PR #34-2:** vendor FlatBuffers C++ headers + Java jar.
- [ ] **Rec 34 PR #34-3:** land `decompile.fbs` schema + generated bindings.
- [ ] **Rec 34 PR #34-4:** dual-encode the decompile-function request path.

---

## Sprint 8 — Decompiler UX Wins (Recs 35, 36, 39)

- [ ] **Rec 35 PR #35-2:** add `DecompileBudget` to request schema; yield-point checks in `flow_analysis` and `data_flow`.
- [ ] **Rec 35 PR #35-3:** UI partial-result banner + Retry-with-2x path.
- [ ] **Rec 36 PR #36-2 + PR #36-3:** per-function dependency bitmaps replace global-flush invalidation.
- [ ] **Rec 36 PR #36-4:** in-place rewrite paths for local rename / type / comment.
- [ ] **Rec 39 PR #39-2:** `ForLoopPattern` analysis pass + output emission.
- [ ] **Rec 39 PR #39-4 + #39-5:** `InlinedFunctionPattern` + initial pattern library (`memcpy`, `memset`, `strlen`).

---

## Sprint 9 — Strategic: C++ Frontend (Rec 37)

- [ ] **PR #37-2:** `CppTypeSystem` skeleton + tests.
- [ ] **PR #37-3:** `CppDemanglingFeeder`.
- [ ] **PR #37-4:** `CppRttiAnalyzer` (Itanium).
- [ ] **PR #37-5:** `CppRttiAnalyzer` (MSVC).
- [ ] **PR #37-6:** `CppVTableAnalyzer`.

---

## Sprint 10 — Strategic: Variable Naming (Rec 38)

- [ ] **PR #38-2:** `ScopeNode` and `ScopeEdge` schema + storage layer.
- [ ] **PR #38-3:** static-analysis populator.
- [ ] **PR #38-4:** rename-propagation UI + opt-in dialog.

---

## Sprint 11 — Sleigh Formalization (Rec 40)

- [ ] **PR #40-2:** BNF grammar at `docs/sleigh/grammar.bnf` + CI drift-detection job.
- [ ] **PR #40-3 + #40-4:** semantic model document.
- [ ] **PR #40-5:** differential-fuzzer framework + x86-64 via Unicorn.

---

## Sprint 12 — Strategic: Decompiler Hints (Rec 37 cont.)

- [ ] **PR #37-7:** `CppDecompilerHints` for upcasts + downcasts.
- [ ] **PR #37-8:** vmethod calls.
- [ ] **PR #37-9:** ctor/dtor recognition.

---

## Backlog

Work that doesn't fit a current sprint but is documented in the audit:

- **Rec 7:** opt-in PRs from community contributors to populate [`MAINTAINERS.md`](MAINTAINERS.md).
- **Rec 23:** expand the `unit_tests` job from Ubuntu-only to multi-OS once Linux baseline is stable.
- **Rec 24:** add Windows (MSVC) to the C++ decompiler test workflow.
- **Rec 30:** headless test layer — ships after Rec 29 (JUnit 5) is partway through; opportunistic.
- **Rec 31 Stages 3–8:** the bulk of the RAII migration.
- **Rec 34 PRs #34-5 through #34-8:** the rest of the FlatBuffers migration + v0 removal.
- **Rec 41:** opt-in PRs to fill the per-architecture maintainer column.
- **Rec 42 milestones:** 2026-09-30 default-off Jython; 2027-01-31 removal.
- Mirror to Codeberg once their repo-creation gateway recovers.

---

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
