# Design Decisions

Running log of architectural, process, and judgment-call decisions
made on this fork. Newest entries at the top.

Entries are numbered (`DD-NNN`) so other documents can link to
them. Each carries a short rationale and the alternatives that were
considered and rejected — the *why*, not just the *what*.

For documents that are themselves capital-D Decisions (numbered
`docs/decisions/00NN-*.md`), this file is the index plus the
decisions too small/cross-cutting to warrant their own file.

---

## DD-018: SprintHistory.md + SprintPlanning.md are the canonical task source (2026-05-21, active)

**Context:** This fork has been moving at a high commit rate
(140+ PRs in the first 24 hours). The conversation-scoped task
list keeps going stale as it accumulates completed items.

**Decision:** `SprintHistory.md` and `SprintPlanning.md` at repo
root are the canonical "where are we / what's next" artifacts. The
conversation task list mirrors the *current* sprint's open items
only and is kept short.

**Alternatives considered:** GitHub Projects (overkill for a one-
maintainer fork); README "todo" section (drifts from the audit's
checklist); per-rec issue (already exists, but doesn't aggregate).

**Linked:** [`SprintHistory.md`](SprintHistory.md), [`SprintPlanning.md`](SprintPlanning.md).

---

## DD-017: Defer NSA#6897 (BSim) hand-port (2026-05-21, deferred)

**Context:** Sprint 2 attempted to port [NSA#6897](https://github.com/NationalSecurityAgency/ghidra/pull/6897)
(BSim address-space id, 26 files) via `gh pr diff | git apply --3way`.
Two structural drifts blocked it:

1. `ElasticDatabase.java` content conflict.
2. `BSimServerTest.java` moved/renamed in our master.

**Decision:** Defer to Sprint 3 for hand-port against current state.
The patch-apply machinery can't bulldoze through both a content
conflict and a missing file in the same change; the cost of a
careful manual port is worth bearing.

**Alternatives considered:** Force-apply the diff with `--reject`
and hand-merge the rejects (would have created `.rej` files all
over the tree); apply per-commit via `format-patch` (still hits the
missing-file barrier).

**Linked:** [`SprintHistory.md`](SprintHistory.md) Sprint 2 carry-over,
[`SprintPlanning.md`](SprintPlanning.md) Sprint 3.

---

## DD-016: EDGE_LABEL renumber 151 → 159 + UNKNOWN 159 → 160 (2026-05-21, shipped)

**Context:** Porting [NSA#7308](https://github.com/NationalSecurityAgency/ghidra/pull/7308)
(PCode edge-label XML encoding). Upstream PR placed
`ATTRIB_EDGE_LABEL` at ID 151. But our master has 151–158 already
taken by `ATTRIB_SIZES` through `ATTRIB_FILL_ALTERNATE`
(modelrules), which landed in upstream NSA's master after the PR
was authored.

**Decision:** Renumber `ATTRIB_EDGE_LABEL` to 159 (the next free
slot) and bump `ATTRIB_UNKNOWN` from 159 to 160 on **both** the
Java side (`AttributeId.java`) and the C++ side (`marshal.cc` +
`block.cc`). The Java↔C++ IPC requires both sides to agree on IDs.

**Rationale:** ID assignment is wire-protocol-significant; the
attribute number IS the on-wire encoding. The renumber is the only
correct fix; using the old 151 would have caused mis-decoding of
modelrules attributes on the wire.

**Linked:** PR #111 (initial port), PR #112 (C++ side correction —
my first attempt's `Edit` tool silently failed because I hadn't
`Read` the C++ files first; caught by a post-merge verification
grep).

---

## DD-015: Co-Authored-By trailer for every upstream port (2026-05-21, active)

**Context:** Cherry-picking upstream PRs into this fork retains
the original author on the commit, but a squash-merge would
collapse that down to the squash author (me, via Claude Code).

**Decision:** Every upstream-port PR carries an explicit
`Co-Authored-By:` trailer naming the original upstream author by
their git email/no-reply address, plus a second trailer for the
Claude assistant identity. The PR body links to the upstream PR and
commit so attribution is fully discoverable.

**Why:** The 46+ upstream PRs ported in Sprints 2–3 represent
months of community contributor work. Credit is non-negotiable. A
contributor reading our git log should see their own name on the
commits, not mine.

**Linked:** Sprint 2 + Sprint 3 port table in [`SprintHistory.md`](SprintHistory.md);
[`docs/decisions/0001-webassembly-position.md`](docs/decisions/0001-webassembly-position.md)
formalizes the same rule for the future WASM contribution.

---

## DD-014: closing-references-only for the upstream crossref (2026-05-21, active)

**Context:** The crossref script [`scripts/upstream-crossref.py`](scripts/upstream-crossref.py)
correlates upstream NSA PRs with open upstream issues. There are
two ways to find PR↔issue links:

1. **GitHub's `closingIssuesReferences`** — PRs that say "Closes #N" /
   "Fixes #N". High precision; ~67 matches in this fork's snapshot.
2. **Fuzzy text search** — any PR body or title that mentions an
   issue number. Higher recall but much noisier.

**Decision:** Use only option 1 (`closingIssuesReferences`). The
script doesn't fuzzy-match.

**Rationale:** False positives are expensive (we'd port the PR,
then realize it doesn't actually close the issue). False negatives
are cheap (we miss a PR; we can pick it up later when the upstream
author updates the body). The audit is also surfaced manually by a
human reading the report; a precision-bias keeps the report
trustworthy.

**Linked:** [`scripts/upstream-crossref.py`](scripts/upstream-crossref.py),
[`docs/upstream-tracking/pr-issue-matches.md`](docs/upstream-tracking/pr-issue-matches.md).

---

## DD-013: Auto-merge our own PRs (2026-05-21, active)

**Context:** This is a one-maintainer fork. PRs are created by me;
reviewed (or not) by me. Holding them open serves no review purpose.

**Decision:** Auto-squash-merge every PR I create, immediately
after opening, with `gh pr merge --squash --delete-branch`. The
PR exists for the audit trail (linked commit, attribution, issue
close-reference), not as a review gate.

**Rationale:** Without this, the master would lag the PR queue and
the README's progress checkboxes (which are flipped in each PR)
would be incoherent. With it, master stays current and the PR
queue stays at zero open.

**Alternatives considered:** Stack PRs and merge at end of session
(makes mid-session work harder); leave PRs open for "review"
(theatrical — there's no second reviewer).

**Exception:** PRs that change behaviour in a way the user would
want to inspect first (none yet — would manually skip the
auto-merge step).

---

## DD-012: Cherry-pick top commit vs. patch-apply tradeoff (2026-05-21, active)

**Context:** Two ways to port an upstream PR's work into this fork:

- **`git cherry-pick <top-commit>`** — preserves authorship,
  preserves commit message, fails fast on conflict. Works only for
  single-commit PRs.
- **`gh pr diff | git apply --3way`** — applies the squashed diff,
  drops authorship (recovered via Co-Authored-By trailer), succeeds
  more often. Works for multi-commit PRs.

**Decision:**
- Default to cherry-pick of the top commit when the upstream PR is
  a single-commit fix (most are).
- Fall back to patch-apply when the upstream PR has multiple
  commits, or when cherry-pick conflicts.

**Rationale:** Cherry-pick is cleaner (one commit retains the
original author's name on the commit itself, not just in a trailer).
Patch-apply is more robust (can survive structural drift via 3-way
merge against the index).

**Linked:** Sprint 2 used cherry-pick exclusively; Sprint 2 multi-
commit batch (NSA#8543, NSA#6134) switched to patch-apply. Both are
documented in [`SprintHistory.md`](SprintHistory.md).

---

## DD-011: Codeberg mirror deferred (2026-05-21, deferred)

**Context:** Aaron's normal convention (per `dual-remote-pr` skill)
is to mirror repos to both GitHub and Codeberg. During Sprint 1,
Codeberg's `POST /api/v1/user/repos` endpoint returned 504 Gateway
Timeout on 4+ attempts; the web UI showed the same gateway timeout
for Aaron's direct browser attempts. The Codeberg `/api/v1/version`
endpoint *did* respond, and `GET /user` with the same token
returned the expected user info, ruling out an account-level issue.

**Decision:** Defer the Codeberg mirror until Codeberg's repo-
create endpoint recovers. Continue all Sprint work on GitHub-only.
When Codeberg recovers, the mirror is a single `git push codeberg
master --tags`; no rework needed.

**Linked:** Future Sprint backlog in [`SprintPlanning.md`](SprintPlanning.md);
the `dual-remote-pr` skill's normal contract is preserved for
subsequent changes.

---

## DD-010: First release is v26.1 (2026-05-21, shipped)

**Context:** Forked from upstream NSA/ghidra 12.2. Project needs
its own version number.

**Decision:** Skip 12.x → 13.x → ... lineage; start at **26.1**.
Year-based major version (2026), `.1` for the first release of the
year.

**Rationale:** A 12.x version would collide with users' upstream
12.x builds and create confusing version-string overlap. A
year-based major makes the fork's identity explicit and gives room
for `.2`, `.3` later this year.

**Alternatives considered:** Semantic versioning starting at `1.0`
(misleading — this is a derivative work with a deep upstream
history, not a v1 of anything new); calendar versioning
`2026.05.21` (too long); `1.12.2-gh` (continues upstream's
numbering with a suffix — awkward to bump).

**Linked:** [`Ghidra/application.properties`](Ghidra/application.properties);
[GitHub release v26.1](https://codeberg.org/CryptoJones/GayHydra/releases/tag/v26.1).

---

## DD-009: Tagline at the BOTTOM of files by default (2026-05-21, active)

**Context:** Aaron's tagline `Proudly Made in Nebraska. Go Big Red!
🌽 https://xkcd.com/2347/` appears in every project he owns.
Convention across `InterruptingCow`, `RunPodBoss`, `Dave`, `1812`,
`correcthorsebatterystaple`: tagline at the **bottom**.

**Decision:** Tagline goes at the BOTTOM of project files
(README, CHANGELOG, release notes, etc.) by default.

**Exception:** `README.md` of *this project* (GayHydra) has the
tagline at the TOP — a per-project request by Aaron, explicitly
not generalized. Every other file (including CHANGELOG, release
body, SprintHistory, SprintPlanning, this file) puts the tagline
at the bottom.

**Saved to memory:** [`aaron-tagline-convention.md`](~/.claude/projects/-home-akclark-Source-repos-GayHydra/memory/aaron-tagline-convention.md).

---

## DD-008: FlatBuffers over Cap'n Proto / Protobuf for the IPC schema (2026-05-21, planned)

**Context:** Rec 34 replaces the hand-rolled Java↔C++ IPC payload
with a schema-typed format. Three candidates compared on:

| | FlatBuffers | Cap'n Proto | Protobuf |
|---|---|---|---|
| Zero-copy decode (both sides) | ✓ | ✓ | — |
| Mature Java story | ✓ | partial | ✓ |
| Mature C++ story | ✓ | ✓ | ✓ |
| Schema evolution | additive | additive | additive |
| License | Apache 2.0 | MIT | BSD-3 |

**Decision:** FlatBuffers.

**Why:** Both decoders (Java host + C++ worker) are on the
critical path. Zero-copy is the qualitative difference. Cap'n
Proto is defensible (its built-in traversal-limit API is
attractive against malformed input) but the Java maturity gap is
real today. Protobuf doesn't deliver zero-copy.

**Reversible?** Yes — if Cap'n Proto's Java runtime closes the
gap before implementation actually starts, the decision can be
revisited via an RFC amendment. The decision is not religion.

**Linked:** [`docs/decompiler/IPC_SCHEMA.md`](docs/decompiler/IPC_SCHEMA.md).

---

## DD-007: Two-step C++14 → C++20, skip C++17 (2026-05-21, planned)

**Context:** Rec 32 moves the decompiler off `-std=c++11`. C++17
is an in-between standard.

**Decision:** Bump in two steps — C++11 → C++14 (mechanical, needed
for `std::make_unique`), then C++14 → C++20 (the substantive bump
with `std::span`, `std::expected`, `std::format`, ranges, concepts).
**Skip C++17.**

**Why:** C++17 features (`std::optional`, structured bindings) are
useful but not on the critical path for the bug classes Rec 31
(RAII) and Rec 32 jointly address. Doing 17 first means a second
CI re-gate for marginal benefit. C++20 includes everything in
C++17 plus the high-EV additions.

**Linked:** [`docs/decompiler/CPP20_ADOPTION.md`](docs/decompiler/CPP20_ADOPTION.md).

---

## DD-006: Cosign keyless over key-managed release signing (2026-05-21, planned)

**Context:** Rec 17 signs release artifacts. Two models:
key-managed (one human holds the private key) vs. keyless (Sigstore
OIDC identity of the release workflow).

**Decision:** Cosign keyless via GitHub Actions OIDC.

**Why:** Zero key-custody risk; no bus-factor risk on a "release
key holder"; provenance audit trail is the public Rekor
transparency log; setup cost matches the key-managed model. The
only downside is verifier-UX familiarity, which is closing fast.

**Linked:** [`docs/security/BINARY_SIGNING.md`](docs/security/BINARY_SIGNING.md).

---

## DD-005: SafeObjectInput as the only Java deser entry point (2026-05-21, planned)

**Context:** Rec 19 audit found 14 production sites using raw
`ObjectInputStream`. Some are attacker-reachable (the #1481 archive
path); some are local-trusted (property maps).

**Decision:** A new class `ghidra.framework.security.SafeObjectInput`
becomes the only sanctioned deserialization helper. Every call site
declares its expected top-level type and an `ObjectInputFilter`
allowlist of expected classes; depth + byte caps are enforced.
Direct `new ObjectInputStream(...)` is forbidden by Checkstyle /
ErrorProne lint.

**Why:** Class A sites (attacker-reachable) genuinely need
allowlisting. Class C sites (local-trusted) don't, but they should
use the helper anyway for code-quality reasons — a single audit
surface, a single deprecation path if we ever move off Java
serialization. The audit can't trust "this site is local-trusted"
forever; the threat model changes.

**Linked:** [`docs/security/JAVA_DESERIALIZATION_AUDIT.md`](docs/security/JAVA_DESERIALIZATION_AUDIT.md).

---

## DD-004: 2,000-LOC mega-PR threshold (2026-05-21, active)

**Context:** Rec 01's PR queue policy defines when a PR is too big
for line-by-line review and needs an RFC.

**Decision:** Threshold is **>2,000 net LOC** (or cross-module
interface change, or security-boundary change). PRs above this
threshold are gated behind an [RFC](docs/governance/RFC_PROCESS.md).

**Why:** Calibrated from the upstream pathology — the median stuck
mega-PR in NSA/ghidra (e.g., #4103 WASM, #5778 RISC-V) was
4,000–15,000 LOC. 2,000 is the point above which line-by-line
review stops being the right unit of work; below it, the queue
absorbs the change without an RFC dance.

**Reversible?** Yes — the threshold lives in the policy doc and is
amendable via PR.

**Linked:** [`docs/governance/PR_QUEUE_POLICY.md`](docs/governance/PR_QUEUE_POLICY.md).

---

## DD-003: 10-business-day Triage SLA (2026-05-21, active)

**Context:** Rec 02 picks a number for "first response within N days."

**Decision:** **10 business days.** Specifically not 5 (too tight for
a small maintainer pool to absorb without heroics) and not 20+
(ratifies the upstream pathology that motivated the audit).

**Calibration:**

- Contributor-churn research suggests >2 weeks is where most
  one-time contributors disengage. 10 business days = 2 calendar
  weeks → puts us inside the disengagement window.
- Small enough to make maintainers feel the pressure when the
  queue grows.
- Decompiler-correctness PRs have a stricter 3-business-day SLA —
  separate lane (Rec 05).

**Reversible?** Yes; if the dashboard shows we routinely land at
p90 = 3 days, tighten. If we cannot meet 10, expand reviewer
capacity or close the lane, don't widen the SLA.

**Linked:** [`docs/governance/TRIAGE_SLA.md`](docs/governance/TRIAGE_SLA.md).

---

## DD-002: PR-per-rec from the start (2026-05-21, active)

**Context:** Sprint 1 had to deliver 42 distinct deliverables.

**Decision:** One PR per recommendation. No bundling. Each PR carries
one rec's deliverable + a single checkbox flip in `README.md`'s
progress table.

**Why:** Per-rec PRs keep the audit trail one-PR-per-finding; lets
the user roll back any single rec independently; matches the
checkbox-driven progress model in the README. The cost is more PRs
to manage (one quality-pass at the end repaired the first 10 docs
that needed deepening).

**Alternatives considered:** One mega-PR with all 42 docs (too big
to review meaningfully); section-batched PRs (loses one-rec
granularity).

**Quality-pass exception:** PR #54 was a bundled improvement to 11
prior docs after thinking level was raised mid-session.

---

## DD-001: Auto-merge for fork-owned PRs (2026-05-21, active)

(Superseded — see [DD-013](#dd-013-auto-merge-our-own-prs-2026-05-21-active)
for the current canonical statement.)

---

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
