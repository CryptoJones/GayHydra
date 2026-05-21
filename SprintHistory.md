# Sprint History

Past sprints. Each sprint is a logical batch of work, not a fixed
time-box. Newest first.

For upcoming work, see [SprintPlanning.md](SprintPlanning.md).

---

## Sprint 2 — Upstream Cherry-Picks, Wave 1 (delivered 2026-05-21)

**Goal:** Identify upstream NSA/ghidra open PRs that close currently-
open upstream issues, and port the work into this fork so users get
the benefit ahead of NSA's own merge timeline.

**Delivered (18 PR ports + 1 ID-collision fix + 1 nightly workflow):**

- [Crossref report](docs/upstream-tracking/pr-issue-matches.md) — 67 PR↔open-issue matches identified from 336 open upstream PRs.
- [`scripts/upstream-crossref.py`](scripts/upstream-crossref.py) + [nightly refresh workflow](.github/workflows/upstream-crossref-refresh.yml) keep the report current (our PR #107).
- 18 upstream PRs ported into the fork, each as its own squashed PR with `Co-Authored-By:` credit to the original upstream author:

  | Our PR | Upstream PR | Upstream issue | 👍 | What |
  |---|---|---|---|---|
  | #92 | [NSA#4681](https://github.com/NationalSecurityAgency/ghidra/pull/4681) | [#4606](https://github.com/NationalSecurityAgency/ghidra/issues/4606) | 1 | Prevent out-of-bounds in `findSymbol()` |
  | #93 | [NSA#1640](https://github.com/NationalSecurityAgency/ghidra/pull/1640) | [#1630](https://github.com/NationalSecurityAgency/ghidra/issues/1630) | 1 | tricore CFSR wrong space |
  | #94 | [NSA#8891](https://github.com/NationalSecurityAgency/ghidra/pull/8891) | [#7032](https://github.com/NationalSecurityAgency/ghidra/issues/7032) | 1 | C-header lexing of quoted-string directives |
  | #95 | [NSA#8827](https://github.com/NationalSecurityAgency/ghidra/pull/8827) | [#3587](https://github.com/NationalSecurityAgency/ghidra/issues/3587) | 2 | Don't restrict long-integer literals |
  | #96 | [NSA#9143](https://github.com/NationalSecurityAgency/ghidra/pull/9143) | [#2786](https://github.com/NationalSecurityAgency/ghidra/issues/2786) | 2 | Parens around double-unary tokens |
  | #97 | [NSA#9149](https://github.com/NationalSecurityAgency/ghidra/pull/9149) | [#7234](https://github.com/NationalSecurityAgency/ghidra/issues/7234) | 1 | Complement representation for bitflag enums |
  | #98 | [NSA#8834](https://github.com/NationalSecurityAgency/ghidra/pull/8834) | [#1299](https://github.com/NationalSecurityAgency/ghidra/issues/1299) | 10 | Space after comma in function call/proto |
  | #99 | [NSA#5312](https://github.com/NationalSecurityAgency/ghidra/pull/5312) | [#5309](https://github.com/NationalSecurityAgency/ghidra/issues/5309) | 1 | cpp-decompiler root-path test fix |
  | #100 | [NSA#2089](https://github.com/NationalSecurityAgency/ghidra/pull/2089) | [#1772](https://github.com/NationalSecurityAgency/ghidra/issues/1772) | 2 | MIPS Octeon-specific instructions |
  | #101 | [NSA#4952](https://github.com/NationalSecurityAgency/ghidra/pull/4952) | [#2449](https://github.com/NationalSecurityAgency/ghidra/issues/2449) | 5 | Power ISA e200 embedded core |
  | #102 | [NSA#1437](https://github.com/NationalSecurityAgency/ghidra/pull/1437) | [#1422](https://github.com/NationalSecurityAgency/ghidra/issues/1422) | 3 | `instructionEndian` in generated `.ldefs` |
  | #103 | [NSA#9084](https://github.com/NationalSecurityAgency/ghidra/pull/9084) | [#1244](https://github.com/NationalSecurityAgency/ghidra/issues/1244) | 3 | Motorola CPU32 (683xx) processor variant |
  | #104 | [NSA#7235](https://github.com/NationalSecurityAgency/ghidra/pull/7235) | [#5212](https://github.com/NationalSecurityAgency/ghidra/issues/5212) | 3 | FunctionID `AddSingleFunction.java` |
  | #105 | [NSA#5063](https://github.com/NationalSecurityAgency/ghidra/pull/5063) | [#4951](https://github.com/NationalSecurityAgency/ghidra/issues/4951) | 1 | Decompiler `printRaw` for ambiguous `TypeOp` |
  | #108 | [NSA#8543](https://github.com/NationalSecurityAgency/ghidra/pull/8543) | [#1294](https://github.com/NationalSecurityAgency/ghidra/issues/1294) | **30** | Decompiler code folding (highest-impact upstream PR by upvotes) |
  | #109 | [NSA#6134](https://github.com/NationalSecurityAgency/ghidra/pull/6134) | [#6133](https://github.com/NationalSecurityAgency/ghidra/issues/6133) | 7 | Decompiler deopt for irreducible statements |
  | #110 | [NSA#7228](https://github.com/NationalSecurityAgency/ghidra/pull/7228) | [#5858](https://github.com/NationalSecurityAgency/ghidra/issues/5858) | 6 | FunctionID disable-namespace-stripping (manual conflict resolve) |
  | #111 | [NSA#7308](https://github.com/NationalSecurityAgency/ghidra/pull/7308) | [#7029](https://github.com/NationalSecurityAgency/ghidra/issues/7029) | 1 | PCode edge-label XML encoding (renumbered to avoid ID collision) |

- [PR #112](https://github.com/CryptoJones/GayHydra/pull/112) — followup fixing the C++ side of PR #111's ID collision (`Edit` tool silently failed mid-resolve; caught and fixed in the same session).

**Carried into Sprint 3:**

- [NSA#6897](https://github.com/NationalSecurityAgency/ghidra/pull/6897) (BSim address-space id) — patch-apply hit two structural drifts (`ElasticDatabase.java` content conflict + `BSimServerTest.java` moved/renamed in our master). Needs hand-port against current state.
- ~47 remaining PR↔issue matches in [the crossref report](docs/upstream-tracking/pr-issue-matches.md) that I didn't tackle this sprint — mostly 0-upvote issues but still real fixes.

---

## Sprint 1 — 42-Rec Principal-Architect Audit (delivered 2026-05-21)

**Goal:** Implement the entire 42-recommendation principal-architect
audit ([`Ghidra.MD`](Ghidra.MD)) as either a working artifact (CI
workflow, gradle plugin, fuzz harness, config file, regression test)
or a written design/decision/RFC document.

**Released as:** [GayHydra v26.1 — "the 42-rec audit"](https://github.com/CryptoJones/GayHydra/releases/tag/v26.1).

**Delivered:**

- [42 audit recommendations](CHANGELOG.md#261--2026-05-21--the-42-rec-audit) as artifacts + plans. Every rec has a deliverable file linked from the README's checklist.
- [Quality-pass PR (#54)](https://github.com/CryptoJones/GayHydra/pull/54) deepening the first 10 docs after the model thinking level was raised.
- [SECURITY.md upstream PR opened against NSA](https://github.com/NationalSecurityAgency/ghidra/pull/9202) (Rec 11 — the easiest landable upstream win).
- Release-v26.1 PR (#89), CHANGELOG.md, README progress checklist with all 42 boxes ticked, `application.name=GayHydra`, `application.version=26.1`.

**Carried into Sprint 3+:** The audit recs ship the *design surface*;
the *implementation surface* (the sub-PRs each design doc enumerates)
is sequenced across multiple future sprints. See
[SprintPlanning.md](SprintPlanning.md) for the breakdown.

---

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
