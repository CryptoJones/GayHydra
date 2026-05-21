# Ghidra: Top 42 Principal-Architect Recommendations

*Audit date: 2026-05-21. Repo: NationalSecurityAgency/ghidra @ master (94164bd6e9).*

Findings drawn from: 1,553 open issues, 335 open PRs, ~15.5k Java files, 187k LOC C++ decompiler, 39 processor specs, and the actual CI workflows.

Each rec is filed as a GitHub issue ([#1–#42](https://github.com/CryptoJones/GayHydra/issues?q=is%3Aissue+label%3Aaudit-2026-05)). PRs that address a rec close the issue with `closes #N` and tick the checkbox below.

---

## I. Governance & Maintainer Process — *the highest-leverage problems*

- [x] **1. Fix the PR graveyard.** 77% of open PRs (257/335) have never received a single maintainer comment; 67% are still labeled `Status: Triage`, including 4+ year old PRs. This is the single biggest pathology in the project. Nothing else on this list matters as much. — [PR_QUEUE_POLICY.md](docs/governance/PR_QUEUE_POLICY.md)

- [x] **2. Adopt an explicit triage SLA.** Commit publicly to first-response-within-N-days. "First response" can be "rejected, here's why" — that is infinitely better than silence. The current implicit policy ("we'll get to it") is corroding contributor trust. — [TRIAGE_SLA.md](docs/governance/TRIAGE_SLA.md)

- [ ] **3. Close the door on dead PRs.** ~76 PRs are >4yr old. Bulk-close with a respectful template ("we appreciate this work but cannot review it; please reopen if you'd like to rebase against current master and re-submit under [new lane X]"). A clean queue is honest; a 335-deep queue is theater.

- [ ] **4. Create a "processor submission" fast-lane.** 54% of open PRs (180) are processor/Sleigh additions — this is a structurally distinct review (correctness against ISA docs, not architectural fit). It needs a dedicated reviewer rotation, an explicit checklist, and a separate queue. Mixing it with framework work is why both queues stall.

- [ ] **5. Create a separate lane for decompiler *correctness* fixes.** PRs like #6718 (shifted struct-offset loop bug) age the same as 13k-line processor submissions. Correctness regressions in the crown jewel should have an expedited path.

- [ ] **6. Adopt an RFC process for mega-PRs.** #4103 (WebAssembly, 4.2yr, +15,387 LOC) and #5778 (RISC-V vector/crypto, 2.7yr, +6,676) cannot be reviewed line-by-line against a closed-development model. Require a design RFC *first*, then merge in small landings. Today these PRs are humanitarian disasters for the contributors who wrote them.

- [ ] **7. Publish the bus factor.** Comment analysis shows maintainer engagement concentrates on ~9 logins (`ryanmkurtz`, `ghidra1`, `emteere`, `dragonmacher`, `pjsoberoi`, `mumbel`, `GhidorahRex`, `Sleigh-InSPECtor`, `nsadeveloper789`). Make that explicit. Community contributors (`jobermayr`, `astrelsky`, `LukeSerne`, `nneonneo`) are doing unpaid triage with no recognition or commit bit — formalize this.

- [ ] **8. Reform the "Status: Triage" label.** 184 issues and 226 PRs sit in Triage. The label has become a synonym for "we have looked at this exactly zero times." Either remove it or wire it to an SLA.

- [ ] **9. Resolve #4103 WebAssembly definitively.** 46 comments, 4.2 years, the single most-asked-for feature in the queue. Land, fork, or close. Limbo is the worst outcome — it's been the worst outcome for four years.

- [ ] **10. Resolve #5778 RISC-V vector/bitmanip/crypto.** Labeled `Waiting on customer` for years. Convert "waiting" into a yes or a no. RISC-V is no longer a research ISA; analyzing modern firmware without these extensions is an increasingly visible gap.

---

## II. Security Posture

- [ ] **11. Publish SECURITY.md.** None exists. For a tool that parses adversary-controlled binaries and ships a network server, this is below baseline. Document private disclosure address, embargo policy, and CVE assignment path. LLVM and binutils do this; Ghidra should too.

- [ ] **12. Issue public CVE IDs.** Internal `GP-*` tracker IDs (GP-6832, GP-6719, GP-258) hide security fixes from NVD. Downstream packagers and enterprise security teams cannot patch what they cannot see. Recent server-side hardening (path-traversal in username validation, race in writeUserList, RMI deserialization filter) deserved CVEs and didn't get them.

- [ ] **13. Land an OSS-Fuzz integration for the C++ decompiler.** Zero public fuzz harness. 187k LOC of C++ parsing untrusted p-code/Sleigh input with 2,740 raw `new`/`delete` sites. This is the highest-EV security investment in the codebase. Google OSS-Fuzz is free.

- [ ] **14. Land fuzz harnesses for file-format loaders.** ELF, PE, Mach-O, DEX, PDB, DWARF, COFF — every loader parses attacker-controlled input. No public fuzzing exists for any of them. Start with the most-used three.

- [ ] **15. Build ASAN/UBSAN CI variants of the decompiler.** Makefile has the flags *commented out* (`decompile/cpp/Makefile:256`). Enable them in a nightly CI job. With ~2,700 raw allocations, this will surface real bugs immediately.

- [ ] **16. Ship an explicit script sandbox option.** `GhidraScript` (Java) and `PyGhidraScriptProvider` (Python) run with full JVM privileges including reflection and arbitrary classloading. There is no opt-in sandbox mode. Headless mode + a malicious script in a shared directory = full code execution. At minimum, add a "trusted scripts only" mode that refuses to run scripts outside a signed/configured allowlist.

- [ ] **17. Sign released decompiler binaries and update the doc.** The decompiler ships as a native executable; binary distribution integrity is a real supply-chain question. Document the verification path.

- [ ] **18. Investigate #1481 (data-type archive deserialization amplification).** Only CWE-tagged open security issue. The archive/extension format deserialization path deserves a principal-level review across the whole pipeline, not a point fix.

- [ ] **19. Audit Java deserialization sites end-to-end.** 20+ files use raw `ObjectInputStream.readObject()` (FileSystem item storage, RepositoryItem, Version, etc.). Recent GP-6719 added an RMI filter — extend the same allowlist discipline to the non-RMI sites. There should be exactly one approved deserialization helper.

- [ ] **20. Document and ship a `serial.filter` for the desktop client by default.** Recent work added it (`Framework/FileSystem/data/client.rmi.serial.filter`); make sure it's on by default and that the allowlist is regression-tested when classes are renamed.

- [ ] **21. Add SBOM generation to release.** Gradle dependency locking is in place — good. But there's no published SBOM. Generate CycloneDX or SPDX as part of `buildGhidra` and attach it to releases. Enterprise consumers increasingly require this.

---

## III. Testing & CI — *the embarrassing gap*

- [ ] **22. Run tests in CI.** The single CI workflow (`build-ghidra.yml`) calls `./gradlew buildGhidra` and exits. No `test`, no `integrationTest`, no decompiler unit tests. For a project this size and reach, this is the most surprising finding in the audit. JaCoCo is wired up locally and the coverage data never reaches a dashboard.

- [ ] **23. Multi-OS CI matrix.** Decompiler builds on Linux, macOS, and Windows; CI only tests Linux. Native bugs on Windows/macOS are caught by users, not maintainers. Run at minimum a build+smoke matrix on all three.

- [ ] **24. Build and run the C++ decompiler unit tests in CI.** Seven `unittests/*.cc` files plus 84 XML data-driven tests exist and don't run in CI. Wire them in.

- [ ] **25. Re-enable `-Xlint`.** `gradle/javaProject.gradle` currently passes `-Xlint:none`, suppressing every javac warning across the codebase. This is hiding genuine bugs. Re-enable incrementally — start with `-Xlint:deprecation,unchecked` and ratchet.

- [ ] **26. Add static analysis.** No SpotBugs, ErrorProne, Checkstyle, or Sonar configuration anywhere in the tree. ErrorProne is the cheapest win — it integrates as a javac plugin and catches a known class of mistakes (mutable-collection-as-key, missing override, etc).

- [ ] **27. Adopt Mockito (or an equivalent).** Tests rely entirely on JUnit 4 + Hamcrest with no mocking framework. Result: test setup is heavyweight (real Swing, real programs) — and modules with hard dependencies (Project: 404 src files / 21 tests, SoftwareModeling: 1631 / 128) go untested because mocking is too painful.

- [ ] **28. Triage `@Ignore` debt.** Ignored tests in `x86AssemblyTest`, `dsPIC30FAssemblyTest`, `ARMAssemblyTest`, `x64AssemblyTest`, `SymbolPathParserTest`, `CharsetInfoManagerTest`. Each ignored test is a frozen bug report. Either fix or delete.

- [ ] **29. Migrate to JUnit 5.** 4.13.2 is fine but JUnit 5 unlocks parameterized tests, conditional execution, and parallel run modes that would meaningfully accelerate the integration suite.

- [ ] **30. Decouple Swing from integration tests.** Driving JFrame/FieldPanel directly (see `AbstractDecompilerTest`) makes the test slow, flaky on CI, and impossible on headless containers. Introduce a headless view layer for tests.

---

## IV. Decompiler & Sleigh — *the crown jewel*

- [ ] **31. Begin a phased migration from raw `new`/`delete` to RAII/smart pointers.** ~2,740 raw allocation sites across 187k LOC; one (1) `unique_ptr` import in the entire codebase. C++11 has been available since 2011. This isn't about style — it's about exception-safety and use-after-free risk on malformed input (and "use-after-free in Sleigh decompiler backend" is literally in the recent commit history, GP-37838c180a).

- [ ] **32. Adopt C++20.** The codebase is on `-std=c++11`. The decompiler community has moved on. `std::span`, `std::expected`, `std::format`, ranges, concepts — all directly applicable. Bump in two steps (14 → 20) with CI on three platforms.

- [ ] **33. Version the Java↔native IPC protocol.** Custom byte-framing (`{0,0,1,X}` magic markers, `DecompileProcess.java:54-61`) has no schema version, no CRC, no graceful resync. One byte of corruption kills the decompiler process. Add a version handshake and a length-prefix-with-CRC frame.

- [ ] **34. Replace the IPC protocol with a schema (FlatBuffers/Cap'n Proto).** Bigger investment than #33, but solves the recurring "decompiler crashed" UX papercut at the root, enables differential testing across versions, and makes a non-Java host viable (PyGhidra, Rust frontend, etc).

- [ ] **35. Bound decompilation time and memory.** Issue #5730 (huge-function UX) and #8429 (decompiler perf) and PR #9179 (bounded parallel decompiler) all converge on this. The decompiler should never hang the UI. Hard wall-clock + RSS budget per function with partial-result return.

- [ ] **36. Stop flushing the decompiler cache on trivial edits (#1871).** 26 reactions, sitting open. The fix is plausibly small relative to its UX impact.

- [ ] **37. Improve C++ / vtable handling as a coordinated roadmap, not point fixes.** #516 (49 👍), #992, related issues — community is loudly asking for first-class C++ analysis. IDA/Hex-Rays has it. This is a strategic, not tactical, gap. Spec a C++ frontend RFC.

- [ ] **38. Tackle variable-naming-across-scopes (#975, 53 👍).** The single most-upvoted open issue. Touches the symbol/scope model end to end; needs design, not a patch. Worth principal-architect time.

- [ ] **39. Detect typical `for` loops and inline functions (#644, #2376, #4461).** Decompiler output that hides the loop induction variable behind a `while + counter` is the single most common "this looks worse than IDA" complaint. Inline-aware analysis (#2376, #4461) is the same complaint one level deeper.

- [ ] **40. Formalize Sleigh semantics; add a Sleigh fuzzer.** Sleigh has an HTML reference manual, no formal grammar, no semantic model, no fuzz harness. With 39 processor specs and 21k lines of `.slaspec`, silent codegen bugs in stale processors (PowerPC unchanged since 2019, 8051 since 2019, M8C since 2019) are inevitable and undetectable. Differential fuzzing against canonical ISA test vectors is the right answer.

- [ ] **41. Establish a processor-maintenance policy.** Each `Ghidra/Processors/<arch>/` should have a named maintainer (community is fine), a test corpus, and an "orphaned after N years inactive" marker so users know what to trust. Right now everything looks equally maintained from the README, and that's not true.

- [ ] **42. Modernize the build's Python story.** Jython is deprecated in favor of PyGhidra but still ships in `Extensions/Jython`. Pick a date, announce, remove. Two Python paths is worse than either one alone, and the test suite for PyGhidra is correspondingly thin (recurring Debugger PR cluster — #8978 etc — is partly Python-stack churn).

---

## The meta-recommendation

If you do nothing else from this list, do **#1, #2, #11, #13, and #22**:

- **#1/#2** because the project is bleeding contributor trust faster than it can earn stars,
- **#11** because there is no responsible-disclosure process for a tool that ships into thousands of enterprise SOCs,
- **#13** because the C++ decompiler is the highest-EV fuzz target in open-source security tooling and nobody is fuzzing it,
- **#22** because a project this consequential should not be shipping without running its own tests in CI.

Everything else is downstream of those five.

Issue tracking: filed as #1–#42 at https://github.com/CryptoJones/GayHydra/issues.

---

<img src="Ghidra/Features/Base/src/main/resources/images/GHIDRA_3.png" width="400">

# Ghidra Software Reverse Engineering Framework
Ghidra is a software reverse engineering (SRE) framework created and maintained by the 
[National Security Agency][nsa] Research Directorate. This framework includes a suite of 
full-featured, high-end software analysis tools that enable users to analyze compiled code on a 
variety of platforms including Windows, macOS, and Linux. Capabilities include disassembly, 
assembly, decompilation, graphing, and scripting, along with hundreds of other features. Ghidra 
supports a wide variety of processor instruction sets and executable formats and can be run in both 
user-interactive and automated modes. Users may also develop their own Ghidra extension components 
and/or scripts using Java or Python.

In support of NSA's Cybersecurity mission, Ghidra was built to solve scaling and teaming problems 
on complex SRE efforts, and to provide a customizable and extensible SRE research platform. NSA has 
applied Ghidra SRE capabilities to a variety of problems that involve analyzing malicious code and 
generating deep insights for SRE analysts who seek a better understanding of potential 
vulnerabilities in networks and systems.

If you are a U.S. citizen interested in projects like this, to develop Ghidra and other 
cybersecurity tools for NSA to help protect our nation and its allies, consider applying for a 
[career with us][career].

## Security Warning
**WARNING:** There are known security vulnerabilities within certain versions of Ghidra.  Before 
proceeding, please read through Ghidra's [Security Advisories][security] for a better understanding 
of how you might be impacted.

## Install
To install an official pre-built multi-platform Ghidra release:  
* Install [JDK 21 64-bit][jdk]
* Download a Ghidra [release file][releases]
  - **NOTE:** The official multi-platform release file is named 
    `ghidra_<version>_<release>_<date>.zip` which can be found under the "Assets" drop-down.
    Downloading either of the files named "Source Code" is not correct for this step.
* Extract the Ghidra release file
  - **NOTE:** Do not extract on top of an existing installation
* Launch Ghidra: `./ghidraRun` (`ghidraRun.bat` for Windows)
  - or launch [PyGhidra][pyghidra]: `./support/pyghidraRun` (`support\pyghidraRun.bat` for Windows)

For additional information and troubleshooting tips about installing and running a Ghidra release, 
please refer to the [Getting Started][gettingstarted] document which can be found at the root of a 
Ghidra installation directory. 

## Build
[![Build Ghidra][build-ghidra-badge]][build-ghidra-action]

To create the latest development build for your platform from this source repository:

##### Install build tools:
* [JDK 21 64-bit][jdk]
* [Gradle 8.5+][gradle] (or provided Gradle wrapper if Internet connection is available)
* [Python3][python3] (version 3.9 to 3.14) with bundled pip
* GCC or Clang, and make (Linux/macOS-only)
* [Microsoft Visual Studio][vs] 2017+ or [Microsoft C++ Build Tools][vcbuildtools] with the
  following components installed (Windows-only):
  - MSVC
  - Windows SDK
  - C++ ATL

##### Download and extract the source:
[Download from GitHub][master]
```
unzip ghidra-master
cd ghidra-master
```
**NOTE:** Instead of downloading the compressed source, you may instead want to clone the GitHub 
repository: `git clone https://github.com/NationalSecurityAgency/ghidra.git`

##### Download additional build dependencies into source repository:
**NOTE:** If an Internet connection is available and you did not install Gradle, the 
`./gradlew` (or `gradlew.bat`) command may be used in place of the `gradle` command in the following
instructions.

```
gradle -I gradle/support/fetchDependencies.gradle
```

##### Create development build: 
```
gradle buildGhidra
```
The compressed development build will be located at `build/dist/`.

For more detailed information on building Ghidra, please read the [Developer's Guide][devguide].

For issues building, please check the [Known Issues][known-issues] section for possible solutions.

## Develop

### User Scripts and Extensions
Ghidra installations support users writing custom scripts and extensions via the *GhidraDev* plugin 
for Eclipse.  The plugin and its corresponding instructions can be found within a Ghidra release at
`Extensions/Eclipse/GhidraDev/` or at [this link][ghidradev].  Alternatively, Visual Studio Code may
be used to edit scripts by clicking the Visual Studio Code icon in the Script Manager.
Fully-featured Visual Studio Code projects can be created from a Ghidra CodeBrowser window at 
_Tools -> Create VSCode Module project_.

**NOTE:** Both the *GhidraDev* plugin for Eclipse and Visual Studio Code integrations only support 
developing against fully built Ghidra installations which can be downloaded from the
[Releases][releases] page.

### Advanced Development
To develop the Ghidra tool itself, it is highly recommended to use Eclipse, which the Ghidra 
development process has been highly customized for.

##### Install build and development tools:
* Follow the above [build instructions](#build) so the build completes without errors
* Install [Eclipse IDE for Java Developers][eclipse]

##### Prepare the development environment:
``` 
gradle prepdev eclipse buildNatives
```

##### Import Ghidra projects into Eclipse:
* *File* -> *Import...*
* *General* | *Existing Projects into Workspace*
* Select root directory to be your downloaded or cloned ghidra source repository
* Check *Search for nested projects*
* Click *Finish*

When Eclipse finishes building the projects, Ghidra can be launched and debugged with the provided
**Ghidra** Eclipse *run configuration*.

For more detailed information on developing Ghidra, please read the [Developer's Guide][devguide].

## Contribute
If you would like to contribute bug fixes, improvements, and new features back to Ghidra, please 
take a look at our [Contributor's Guide][contrib] to see how you can participate in this open 
source project.

[build-ghidra-action]: https://github.com/NationalSecurityAgency/ghidra/actions/workflows/build-ghidra.yml
[build-ghidra-badge]: https://github.com/NationalSecurityAgency/ghidra/actions/workflows/build-ghidra.yml/badge.svg
[nsa]: https://www.nsa.gov
[contrib]: CONTRIBUTING.md
[devguide]: DevGuide.md
[gettingstarted]: GhidraDocs/GettingStarted.md
[known-issues]: DevGuide.md#known-issues
[career]: https://www.intelligencecareers.gov/nsa
[releases]: https://github.com/NationalSecurityAgency/ghidra/releases
[jdk]: https://adoptium.net/temurin/releases
[gradle]: https://gradle.org/releases/
[python3]: https://www.python.org/downloads/
[vs]: https://visualstudio.microsoft.com/vs/community/
[vcbuildtools]: https://visualstudio.microsoft.com/visual-cpp-build-tools/
[eclipse]: https://www.eclipse.org/downloads/packages/
[master]: https://github.com/NationalSecurityAgency/ghidra/archive/refs/heads/master.zip
[security]: https://github.com/NationalSecurityAgency/ghidra/security/advisories
[ghidradev]: GhidraBuild/EclipsePlugins/GhidraDev/GhidraDevPlugin/README.md
[pyghidra]: Ghidra/Features/PyGhidra/README.md
