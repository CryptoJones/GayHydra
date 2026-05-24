# Give-back candidates — GayHydra → NSA/ghidra

What's in the fork that's small enough, focused enough, and reusable enough to plausibly be accepted upstream as a PR against [`NationalSecurityAgency/ghidra`](https://github.com/NationalSecurityAgency/ghidra).

Snapshot: 2026-05-24. Re-evaluate whenever Aaron asks "what should we give back?"

Ground truth: `git log upstream/master..master --oneline | wc -l` was **288** GayHydra-unique commits at snapshot time. Most are governance, rebrand, or sprint-bookkeeping (won't fly upstream). The list below is the filtered subset.

## Already given back

| Upstream PR | GayHydra source | What |
|---|---|---|
| [NSA/ghidra#9015](https://github.com/NationalSecurityAgency/ghidra/pull/9015) (port) | PR #141 | PyGhidra `from __main__ import` improvement |
| [NSA/ghidra#9202](https://github.com/NationalSecurityAgency/ghidra/pull/9202) | Rec 11 follow-up | (tracked) |
| [NSA/ghidra#9207](https://github.com/NationalSecurityAgency/ghidra/pull/9207) | Sprint 10 | Consolidated datatest regex updates |
| [NSA/ghidra#9208](https://github.com/NationalSecurityAgency/ghidra/pull/9208) | Sprint 10 | `-dumpdir` audit-tooling flag |
| [NSA/ghidra#9219](https://github.com/NationalSecurityAgency/ghidra/issues/9219) (issue) | Sprint 10 release-hardening | `GolangSymbolAnalyzer` EOFException on Go 1.25/1.26 binaries |
| [NSA/ghidra#9220](https://github.com/NationalSecurityAgency/ghidra/issues/9220) (issue) | Sprint 10 release-hardening | `GhidraSerialFilterFactory` collision on JDK 21.0.10+ |

## Tier 1 — would PR today (small, focused, mechanical, low review friction)

These are the highest-confidence-of-acceptance items. Each is a self-contained fix that doesn't touch upstream's design surface; the diffs are mechanical and the rationale is on the face of the change.

### Bug fixes — slaspec / certification / IP-header

| GayHydra PR | Diff | Title | Why upstream takes it |
|---|---|---|---|
| #210 | +2/-0, 1 file | Fix RISC-V QingKe slaspec: add missing `@define CONTEXTLEN` | Strict bug — the missing define blocks slaspec compilation for that processor variant. Upstream regression. |
| #201 | +2/-0, 1 file | Fix ARM `certification.manifest` after upstream PR #137 | Upstream PR #137 added two sleigh files but forgot to register them in the certification manifest, which gates inclusion in the release. One-line fix per file. |
| #206 | +30/-0, 2 files | Add `IP: GHIDRA` header to `ToggleTypeCastsAction` + `GhidraSerialFilterDefaultTest` | The IP header is required by upstream's `gradle ip` task. Two files in upstream are missing it. Audit-style fix. |
| #275 | +7/-19, 3 files | PIC-24F GE-recognition regression (port of upstream PR #8778 slaspec half) | Closes upstream issue #259 (or equivalent — needs cross-reference); re-enables 2 stringmatch tests. Upstream has already merged the *Java* half; the slaspec half languishes. |

**Bundling strategy:** these 4 are independent enough to ship as separate upstream PRs, BUT NSA's PR queue is overloaded (per the original 42-rec audit, 77% of PRs never get a maintainer comment — the entire reason GayHydra exists). Better to bundle as "fork audit found 4 small breakages, here's a single PR" with one cover commit + four sub-commits — gives reviewers one queue slot instead of four. Mention the fork prominently so it's clearly a "audit-driven give-back" and not an unsolicited single-feature drop.

### Typo batches — `chore: ` PRs #316, #317, #343–#370

19 typo batches across this fork's history. ~150+ comment/javadoc/string fixes across `.java`, `.md`, `.gradle`, `.htm`, `.sinc`, `.slaspec`, `.cc`, `.hh`, `.y` files.

| Bundle | Size | Notes |
|---|---|---|
| `.java`/`.md`/`.gradle` typos | ~120 fixes | Already proven upstream-acceptable — `occured/neccessary/recieve` etc. are NSA-inherited misspellings. |
| `.htm`/`.html` help-doc typos | ~20 fixes | Same approach, different file extension. |
| `.cc`/`.hh`/`.y` (decompiler C++) typos | ~10 fixes | Pure-comment fixes (`overriden → overridden`, `signifigant → significant`). Careful: 4 sites of `explict` in `varnode.hh` are typo'd ENUM identifiers — can't be renamed because `explicit` is a C++ keyword. Skip those. |
| `.sinc`/`.slaspec` sleigh comment typos | 7 fixes | Comment-only inside Hexagon, PA-RISC, M16C sleigh files. CAREFUL: `PropogateLoopCfg` (5 sites in `hexagon.sinc`) is a grammar identifier we deliberately left alone — same skip pattern. |

**Bundling strategy:** one large "fork-wide typo audit, ~150 mechanical fixes" PR. Or 3-5 smaller PRs by file-extension category if NSA prefers narrow reviews. Either way, **CRITICAL** to call out the identifier-skips (`explict`, `Propogate*`, `hasUncomittedEntry`) in the cover note so reviewers don't ask "why didn't you fix these too" — preempt the question.

### Javadoc errors in `buildDecompilerDocumentationPdfs`

| GayHydra PR | Diff | Title |
|---|---|---|
| #329 | small | Fix 3 javadoc errors surfaced by `buildDecompilerDocumentationPdfs` |

Three real javadoc errors in `PseudoLocaleGenerator.java` (unqualified `{@link MessageFormat}` + unterminated inline tag) and `FunctionDescription.java` (`@param spacemap` vs actual param `spaceMap`). Surfaces every release build today; tiny fixes.

## Tier 2 — plausible but needs scrutiny / discussion

### Decompiler `-std=` bumps (Rec 32)

| GayHydra PR | Diff | Title |
|---|---|---|
| #310 | small | `-std=c++11` → `-std=c++14` (decompiler) |
| #313 | small | `-std=c++14` → `-std=c++20` (decompiler) |

Upstream is still on `-std=c++11` (verified `git show upstream/master:.../cpp/Makefile`). Two-step bump that's already been compiled + tested on all 3 platforms by the fork.

**Why "tier 2 not tier 1":** changing C++ stdlib version on a security-relevant component is a maintainer-discretion call upstream. NSA may have specific reasons for staying on c++11 (older toolchain support, etc.). The bump is mechanical but the *decision* belongs to upstream.

**Pitch:** bundle the two bumps as one PR ("`-std=c++11` → `-std=c++20`") since they're already shipped together in GayHydra. Include the compile + run evidence from GayHydra's CI (Build Ghidra + Decompiler C++ Unit Tests + Decompiler ASan/UBSan all green on all 3 platforms at v26.1.6+).

### Rec 25/26 Stage 3 `@SuppressWarnings` pre-clean

| Cluster | Diff | Title |
|---|---|---|
| PRs #261, #265–#270 | medium-large | Pre-clean PIC / SoftwareModeling / Base / VersionTracking / Docking / Project / BSim / Gui / Debug-TraceModeling / DB / PDB / Help — `@SuppressWarnings` on self-deprecated classes + capped-warning-count subprojects |

Mechanical and large. Upstream value: makes future `-Werror` adoption tractable without per-PR cleanup churn. **But:** large diff touching many subprojects; NSA's review queue may not absorb a multi-subproject PR easily. Probably better as a series of per-subproject PRs.

### `gradle ignoreAudit` + IGNORE_TEST_POLICY (Rec 28 #28-3)

Already shipped to the fork; would be a useful CI gate upstream too. The audit task itself is the give-back; the policy doc may be too opinionated for upstream taste.

**Pitch:** drop just the `gradle ignoreAudit` Stage 1 task (text-rule gate without GitHub-API integration) as a standalone PR. Skip the policy doc and the deadline-label scheme.

### Decompiler smoke test (gayhydra-dropper sample)

Sample + Ghidra headless post-script that gates releases on the decompiler still recovering a known XOR-0x5A constant.

**Pitch:** value to upstream is real (catches the kind of regressions the GayHydra smoke test caught: Go 1.25/1.26 analyzer crash, JDK 21.0.10+ headless launch collision, SBOM extract path bug). **But** the sample itself ("gayhydra-dropper" with "File generated by GayHydra" calling-card) is fork-branded. To upstream, would need to rebrand the sample as something neutral ("ghidra-decompiler-smoke" or similar).

## Tier 3 — won't fly upstream as-is

These are fork-specific and shouldn't be proposed upstream:

- Governance docs (`PR_QUEUE_POLICY.md`, `TRIAGE_SLA.md`, `STALE_POLICY.md`, `RFC_PROCESS.md`, `LABEL_POLICY.md`, `MAINTAINERS.md`) — NSA has its own governance approach; the *whole point* of GayHydra is that upstream's queue is broken.
- Rebrand commits (GayHydra logo, "GayHydra" naming throughout).
- SprintHistory.md / SprintPlanning.md / DesignDecisions.md — fork-process documentation.
- `release.yml` cosign keyless signing — depends on the workflow's OIDC identity belonging to *this* repo; not transferable as-is to NSA's CI.
- `release.yml` matrix over mac+windows runners — NSA has its own release tooling; unlikely to want our YAML.
- Codeberg-mirror logic — fork-specific.
- The i18n PoC (Messages → I18n rename + 135 Docking literals externalized) — upstream has no l10n; this is a substantive new feature, not a give-back. Would need an RFC-style upstream proposal first.

## Suggested sequencing

If we wanted to actively push give-back PRs upstream over the next sprint:

1. **First wave** (highest acceptance probability, smallest diffs): the 4 Tier-1 bug fixes (#210, #201, #206, #275). Either 1 bundled PR or 4 separate PRs depending on NSA preference.
2. **Second wave**: the typo bundles. Start with the `.java/.md/.gradle` set (most-traveled file types) as a single PR; gauge maintainer response before sending the `.htm` / `.cc` / `.sinc` follow-ups.
3. **Third wave**: javadoc errors (#329). Tiny PR; can run in parallel with the others.
4. **Fourth wave** (conditional on Tier-1 + Tier-2 going well): `-std=` bump (Rec 32) bundled as one PR with the GayHydra CI evidence attached.
5. **Defer indefinitely**: `gradle ignoreAudit`, decompiler smoke test, Rec 25/26 Stage 3 — these are larger asks that need maintainer interest signaled first.

## Cadence

Per Aaron's "top-3-then-pause" rule for upstream submissions, send the first wave (4 bug fixes — possibly bundled) then **wait for response** before sending the next batch. NSA's PR queue saturation is the rec-audit's #1 finding; flooding it ourselves would be embarrassing.

---

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
