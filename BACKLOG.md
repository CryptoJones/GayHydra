# GayHydra backlog

One list, two views: this file and the [GitHub Issues tab](https://github.com/CryptoJones/GayHydra/issues) are kept in sync. Every open item here has an issue and every open issue has a line here; adding an item means filing the issue and linking it, closing an issue means checking the box. Lanes follow the `lane:*` labels; `ignore:1y` marks a test that is `@Ignore`'d with a one-year review clock. Sprint-level planning stays in [SprintPlanning.md](SprintPlanning.md); the *why* lives in [DesignDecisions.md](DesignDecisions.md).

Seeded 2026-09-06 from the 23 open issues.

## engine correctness (found by Scylla's A/B parity harness)

- [ ] Auto-analysis is nondeterministic on Rust std functions: body extent flips between identical headless runs (26.3.0 / upstream 12.2) — [#473](https://github.com/CryptoJones/GayHydra/issues/473)
- [ ] GolangSymbolAnalyzer crashes on Go 1.26 binaries (InvocationTargetException in GoTypeManager.markupGoTypes); stripped Go 1.26 loses every function name — [#474](https://github.com/CryptoJones/GayHydra/issues/474)

## framework

- [ ] SymbolPathParser detailed-processing — better-results test owner needed — [#57](https://github.com/CryptoJones/GayHydra/issues/57) _(`ignore:1y`)_
- [ ] JitJvmTypeUtilsTest tightly bound to Java version — manual-tool — [#62](https://github.com/CryptoJones/GayHydra/issues/62) _(`ignore:1y`)_
- [ ] AbstractToyJitCodeGenerator undefined-cases — edge-case JIT codegen — [#63](https://github.com/CryptoJones/GayHydra/issues/63) _(`ignore:1y`)_
- [ ] MDMangBaseTest undocumented bare-ignore — demangler corpus — [#64](https://github.com/CryptoJones/GayHydra/issues/64) _(`ignore:1y`)_
- [ ] DBCachedObjectStore + TenetLoader test correctness review — [#69](https://github.com/CryptoJones/GayHydra/issues/69) _(`ignore:1y`)_
- [ ] Rec 42: flip Jython to default-off — due 2026-09-30 — [#440](https://github.com/CryptoJones/GayHydra/issues/440) _(`audit-2026-05`)_
- [ ] Rec 42: remove Jython — due 2027-01-31 (coordinate with upstream PyGhidra posture first) — [#441](https://github.com/CryptoJones/GayHydra/issues/441) _(`audit-2026-05`)_

## processor

- [ ] x86 scalar-disasm divergence — x86AssemblyTest @Ignore — [#55](https://github.com/CryptoJones/GayHydra/issues/55) _(`ignore:1y`)_
- [ ] dsPIC30F W4 label-vs-register ambiguity — dsPIC30FAssemblyTest @Ignore — [#56](https://github.com/CryptoJones/GayHydra/issues/56) _(`ignore:1y`)_
- [ ] HexagonPcodeUseropLibraryTest DFP NaN handling — [#65](https://github.com/CryptoJones/GayHydra/issues/65) _(`ignore:1y`)_

## debugger

- [ ] LLDB EXC_BAD_ACCESS on string allocation — LldbCommandsTest cluster — [#58](https://github.com/CryptoJones/GayHydra/issues/58) _(`ignore:1y`)_
- [ ] LLDB temp var $x semantics — LldbCommandsTest cluster — [#59](https://github.com/CryptoJones/GayHydra/issues/59) _(`ignore:1y`)_
- [ ] Debugger RMI integration test cluster — undocumented bare-ignore — [#60](https://github.com/CryptoJones/GayHydra/issues/60) _(`ignore:1y`)_
- [ ] LldbConnectorsTest TODO — local-lldb / qemu / SSH launchers — [#61](https://github.com/CryptoJones/GayHydra/issues/61) _(`ignore:1y`)_
- [ ] Debug/TraceModeling cluster — DBTrace test gaps — [#66](https://github.com/CryptoJones/GayHydra/issues/66) _(`ignore:1y`)_
- [ ] Debugger plugin cluster — UI / tracermi launch plumbing — [#67](https://github.com/CryptoJones/GayHydra/issues/67) _(`ignore:1y`)_

## decomp-correctness

- [ ] misc cluster — cross-feature @Ignore'd tests — [#68](https://github.com/CryptoJones/GayHydra/issues/68) _(`ignore:1y`)_

## papercuts

- [ ] Debugger Python packages pinned to upstream 12.2; no Python CI — [#446](https://github.com/CryptoJones/GayHydra/issues/446) _(`severity:papercut`)_
- [ ] C++ decompiler unit/data tests run only on Ubuntu — [#447](https://github.com/CryptoJones/GayHydra/issues/447) _(`severity:papercut`)_
- [ ] Audit ~118 System.exit call sites in library-ish classes — [#455](https://github.com/CryptoJones/GayHydra/issues/455) _(`severity:papercut`)_
- [ ] Triage residual TODO/FIXME markers (fork-owned vs upstream) — [#456](https://github.com/CryptoJones/GayHydra/issues/456) _(`severity:papercut`)_

---

Proudly Made in Nebraska. Go Big Red! 🌽 https://xkcd.com/2347/
