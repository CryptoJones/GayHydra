# `@Ignore` Test Inventory

*Addresses Rec 28 #28-2 (the inventory). See [IGNORE_TEST_POLICY.md](IGNORE_TEST_POLICY.md) for the policy this inventory feeds.*

Snapshot refreshed: 2026-05-26. Run `grep -rn '@Ignore' Ghidra --include='*.java'` for the live state; this file summarises the standing categories and the remaining sweep targets.

## The six audit-named tests — status

All six tests called out by name in the 2026-05-21 principal-architect audit are now properly categorised per the policy:

| File | Current `@Ignore` arg | Status |
|---|---|---|
| `Ghidra/Framework/SoftwareModeling/.../sleigh/x86AssemblyTest.java` | `@Ignore("blocked-on: x86 scalar-disasm divergence #159")` | Categorised. Fix-or-delete pending owner. |
| `Ghidra/Framework/SoftwareModeling/.../sleigh/dsPIC30FAssemblyTest.java` | `@Ignore("blocked-on: dsPIC30F W4 label-vs-register #160")` | Categorised. Fix-or-delete pending owner. |
| `Ghidra/Framework/SoftwareModeling/.../sleigh/ARMAssemblyTest.java` | *(none)* | Dead `//@Ignore` removed; the test currently runs. |
| `Ghidra/Framework/SoftwareModeling/.../sleigh/x64AssemblyTest.java` | *(none)* | Dead `//@Ignore` removed; the test currently runs. |
| `Ghidra/Framework/SoftwareModeling/.../SymbolPathParserTest.java` | `@Ignore("wip: SymbolPathParser detailed-processing better-results #161")` | Categorised. Needs an owner to define "better results." |
| `Ghidra/Framework/SoftwareModeling/.../charset/CharsetInfoManagerTest.java` | *(method-level annotation removed with the method)* | `generateCharsetInfoFile` codegen helper deleted from the test class; the remaining `testCharsetsArePresent` regression test still runs. |

## Active-`@Ignore` census

Live count from `grep -rn '@Ignore("' Ghidra --include='*.java' | grep -v RepeatedStatement`:

| Category | Count |
|---|---|
| `wip` | 31 |
| `blocked-on` | 19 |
| `manual-tool` | 1 |
| `flaky` | 0 |
| **Total properly-categorized** | **51** |

The `manual-tool` count dropped from 10 → 8 and `flaky` from 3 → 0 in [PR #28-6a](#sequencing): five method-level `@Ignore` lines were removed because their enclosing classes are already `@Ignore`'d at class level (two in `JdiExperimentsTest`/`ProjectExperimentsTest`, three in `JavaMethodsTest`). The method annotations were dead — the class-level annotation skipped them first. Issue references (#178, #190, #193) remain valid; if the class-level ignores are ever lifted, those issues can be re-attached at the method level.

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
| #28-6a | Redundant inner `@Ignore` cleanup (5 lines inside already-`@Ignore`'d classes) | shipped |
| #28-6b | `ignore:30d` / `ignore:90d` / `ignore:1y` deadline labels declared in `.github/labels.yml` | shipped |
| #28-6c | Deadline labels (`ignore:30d` / `ignore:90d` / `ignore:1y`) declared in `.github/labels.yml` and created in the live GitHub repo. **Originally** applied to 18 tracking issues (#159, #160, #161, #162, #176, #177, #179, #180, #181, #182, #183, #187, #188, #189, #190, #191, #192, #193) referenced by `@Ignore` annotations in-tree. **Artifact loss 2026-05-24:** those 18 issues were destroyed with the prior repo (see [Apologies.md](../../Apologies.md) entry 2026-05-24). The deadline labels themselves were recreated on the new repo and currently exist with zero applications. The in-tree `@Ignore` annotations still reference the dead `#N` numbers; per the Apologies entry no automated backfill is planned — re-file the 18 tracking issues opportunistically as each annotation is touched and update its `#N` to the new repo's issue number. | shipped pre-deletion; artifacts destroyed; labels-recreated, issues not |
| #28-6d+ | Remaining fix-or-delete sweep across `manual-tool`, `blocked-on`, `wip` buckets — author-declared-not-a-regression-test sub-bucket cleared (PRs #26–#34, #36–#41 deleted 17 such sites; counts now wip 31, blocked-on 19, manual-tool 1) | open (residual = real tests blocked-on upstream/cluster work) |

## #28-6+ sweep heuristics

Suggested order for tackling the remaining 51 sites:

1. **`manual-tool` (1 site)** — `JitJvmTypeUtilsTest` is the only remaining manual-tool entry; it's "real test, ignored for infrastructure reason" (Java-version-bound) and needs an infra fix or move to `src/main/test-tools/` rather than deletion. The "author-declared not-a-regression-test" sub-bucket (experiment notebooks, codegen tools, developer-desk perf scratchpads, empty `TODO()`-throw stubs, JFrame UI demos, manual harnesses, commented-body shells) is now empty across both `manual-tool` and `wip` buckets. Deleted (in order of PR): `JitMpIntPerformanceExperiment`, `TraceRmiPerformanceTest`, `ProjectExperimentsTest`, `JdiExperimentsTest`, `CharsetInfoManagerTest.generateCharsetInfoFile`, `DebuggerMemoryBytesProviderTest.testPerformanceManuallyWithManyManySnaps`, `AbstractDBTraceMemoryManagerMemoryTest.testReplicateClassCastExceptionScenario`, `DebuggerOpinionsTest`, `DBTraceRegisterContextManagerTest`, `DemoFieldsTest`, `DebuggerManualTest`, `experiments/ToArrayTest`, `TenetLoaderTest.testManual`, `AbstractToyJitCodeGeneratorTest.testComputedOffsetsInRegisterSpace` + `.testUninitializedVsInitializedReads`, `CppCompositeTypeTest.testJ5_32_syntactic_layout`, `DBTraceCodeUnitTest.testFigureOutAssembly`, `DBTraceProgramViewListingTest.testGetUndefinedRanges`, `DBTraceAddressSnapRangePropertyMapSpaceTest.testRemove`, `DbgEngHooksTest.testOnSyscallMemory`, `GdbHooksTest.testOnSyscallMemory`.
2. **`flaky` (0 sites)** — historical bucket; all three former sites in `JavaMethodsTest` (issue #178) were collapsed in [PR #28-6a](#sequencing) when the enclosing class's class-level `@Ignore` was recognised as already covering them.
3. **`blocked-on` (19 sites)** — the dependent issue list is the next sweep target. Closing the upstream blocker for any of these unblocks the test.
4. **`wip` (31 sites)** — largest residual bucket. Composition: Debugger RMI integration work (3 class-level `JavaHooksTest` / `JavaMethodsTest` / `JavaCommandsTest`; assorted method-level for #180/#191), real-but-unfinished demangler + parser corpus (`MDMangBaseTest` ×9 for #187, `SymbolPathParserTest` ×1 for #161), real-but-unfinished DFP NaN cases (`HexagonPcodeUseropLibraryTest` ×4 for #188), and other documented future-aspiration tests (`DecompilerTaintTest`, `HTMLDataTypeRepresentationTest`, etc.). All have real bodies; none are author-declared-not-a-regression-test. Sweep last; many will resolve as the Debugger RMI / demangler / emulation work matures.

---

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
