# `@Ignore` Test Inventory

*Addresses Rec 28 #28-2 (the inventory). See [IGNORE_TEST_POLICY.md](IGNORE_TEST_POLICY.md) for the policy this inventory feeds.*

Snapshot refreshed: 2026-05-22. Run `grep -rn '@Ignore' Ghidra --include='*.java'` for the live state; this file summarises the standing categories and the remaining sweep targets.

## The six audit-named tests — status

All six tests called out by name in the 2026-05-21 principal-architect audit are now properly categorised per the policy:

| File | Current `@Ignore` arg | Status |
|---|---|---|
| `Ghidra/Framework/SoftwareModeling/.../sleigh/x86AssemblyTest.java` | `@Ignore("blocked-on: x86 scalar-disasm divergence #159")` | Categorised. Fix-or-delete pending owner. |
| `Ghidra/Framework/SoftwareModeling/.../sleigh/dsPIC30FAssemblyTest.java` | `@Ignore("blocked-on: dsPIC30F W4 label-vs-register #160")` | Categorised. Fix-or-delete pending owner. |
| `Ghidra/Framework/SoftwareModeling/.../sleigh/ARMAssemblyTest.java` | *(none)* | Dead `//@Ignore` removed; the test currently runs. |
| `Ghidra/Framework/SoftwareModeling/.../sleigh/x64AssemblyTest.java` | *(none)* | Dead `//@Ignore` removed; the test currently runs. |
| `Ghidra/Framework/SoftwareModeling/.../SymbolPathParserTest.java` | `@Ignore("wip: SymbolPathParser detailed-processing better-results #161")` | Categorised. Needs an owner to define "better results." |
| `Ghidra/Framework/SoftwareModeling/.../charset/CharsetInfoManagerTest.java` | `@Ignore("manual-tool: charset categorisation tool, not a regression test #162")` | Categorised. Candidate for move out of test suite. |

## Active-`@Ignore` census

Live count from `grep -rn '@Ignore("' Ghidra --include='*.java' | grep -v RepeatedStatement`:

| Category | Count |
|---|---|
| `wip` | 46 |
| `blocked-on` | 19 |
| `manual-tool` | 10 |
| `flaky` | 3 |
| **Total properly-categorized** | **78** |

`RepeatedStatement.java` is excluded — its `@Ignore` reference is part of a test-rule mechanism, not test debt (also listed in `ignoreAudit.gradle`'s `EXCLUDED_FILES`).

After [PR #295](https://github.com/CryptoJones/GayHydra/pull/295) the in-tree count of dead commented-out `//@Ignore` lines is zero.

## Sequencing

| PR | Scope | Status |
|---|---|---|
| #28-1 | Policy doc (`IGNORE_TEST_POLICY.md`) | shipped in v26.1 |
| #28-2 | This inventory | shipped |
| #28-3 | `gradle ignoreAudit` task + CI wiring | shipped |
| #28-4 | Tracking issues filed for the six | shipped (#159–#162, #178, etc.) |
| #28-5 | Dead commented-out `//@Ignore` cleanup (7 lines) | shipped ([PR #295](https://github.com/CryptoJones/GayHydra/pull/295)) |
| #28-6+ | Active-`@Ignore` fix-or-delete sweep across the remaining 78 sites | open |

## #28-6+ sweep heuristics

Suggested order for tackling the remaining 78 sites:

1. **`manual-tool` (10 sites)** — most are full classes whose entire purpose is dev-bench experimentation (e.g. `JitMpIntPerformanceExperiment`, `JdiExperimentsTest`, `TraceRmiPerformanceTest`, `ProjectExperimentsTest`). Decide: move to `src/main/test-tools/` or delete. Either resolution is a one-PR-per-class change.
2. **`flaky` (3 sites)** — all in `JavaMethodsTest` referencing issue #178 (race in `putFrames` assert). Either fix the race or convert to a `@RepeatedStatement(retries=N)` pattern.
3. **`blocked-on` (19 sites)** — the dependent issue list is the next sweep target. Closing the upstream blocker for any of these unblocks the test.
4. **`wip` (46 sites)** — largest bucket; mostly Debugger RMI integration work. Sweep last; many will resolve as the Debugger RMI work matures.

---

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
