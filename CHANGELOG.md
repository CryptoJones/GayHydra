# Changelog

All notable changes to GayHydra are recorded here. Format is loosely
based on [Keep a Changelog](https://keepachangelog.com/); the project
does not yet promise SemVer.

---

## [Unreleased]

Work toward v26.1.9 (Sprint 11 close). Tracked per-PR in
[SprintPlanning.md](SprintPlanning.md); per-release notes are
generated from the GitHub Releases UI at sprint close.

v26.1.8 was tagged but its release workflow failed at the "Locate
release zip + extract bundled SBOM" step — the unzip pattern added
in [#230](https://github.com/CryptoJones/GayHydra/pull/230) looked
for `*/support/sbom/bom.json` but the upstream NSA SBOM generator
actually writes `bom.json` at the top of the zip-prefix directory.
v26.1.9 patches the path and re-tags from a HEAD that fires the fix.

Highlights since v26.1.7:

- **Rec 19 closed.** SafeObjectInput migration completed across all
  three risk classes ([#293](https://github.com/CryptoJones/GayHydra/pull/293), [#297](https://github.com/CryptoJones/GayHydra/pull/297), [#299](https://github.com/CryptoJones/GayHydra/pull/299)). Enforcement gate
  via `gradle objectInputStreamAudit` task ([#301](https://github.com/CryptoJones/GayHydra/pull/301)) — any future
  raw `new ObjectInputStream(...)` outside `SafeObjectInput.java`
  fails CI.
- **Rec 32 #32-2 + #32-3.** Decompiler C++ bumped `-std=c++11` →
  `-std=c++14` ([#310](https://github.com/CryptoJones/GayHydra/pull/310)) and `-std=c++14` → `-std=c++20`
  ([#313](https://github.com/CryptoJones/GayHydra/pull/313)) across `buildNatives.gradle` Gcc/Clang +
  decompile/cpp/Makefile + fuzz/Makefile.fuzz. MSVC implicit default
  already C++14. CI green on all 3 platforms.
- **Rec 28 #28-5.** Dead commented-out `//@Ignore` cleanup
  ([#295](https://github.com/CryptoJones/GayHydra/pull/295)) — 7 lines across MDMangBaseTest + CompositeMemberTest.
- **Test-flake fix.** `GhidraSerialFilterDefaultTest`
  (`rejectsNonAllowlistedClass` flake) replaced with a textual filter-
  file check ([#308](https://github.com/CryptoJones/GayHydra/pull/308)) — was flaking on JVM-installed
  BuiltinFilterFactory + uninitialized GhidraObjectInputFilter.
- **RE training target + release smoke test.** Added
  `samples/re-targets/gayhydra-dropper/` ([#319](https://github.com/CryptoJones/GayHydra/pull/319), [#321](https://github.com/CryptoJones/GayHydra/pull/321)) — a small Go
  program with XOR-obfuscated strings (key `0x5A`) for users learning
  Ghidra/GayHydra. Wired into `release.yml` as a post-build decompiler
  sanity gate: scans the freshly-built prebuilt for `XOR <reg>, 0x5A`
  instructions and asserts the constant survives into the decompiler's
  C output. First dogfood run caught three release-pipeline regressions
  (Go 1.26 analyzer crash, JDK 21.0.10+ headless launch collision,
  v26.1.7 release workflow failure) — tracked under Sprint 10
  "Release pipeline hardening" in
  [SprintPlanning.md](SprintPlanning.md).

## Released sprints (v26.1.1 – v26.1.7)

Per-sprint release notes live on the
[GitHub Releases page](https://github.com/CryptoJones/GayHydra/releases)
(and [Codeberg Releases](https://codeberg.org/CryptoJones/GayHydra/releases)).
Each `26.1.x` tag corresponds to a Sprint close per the cadence
documented in [SprintHistory.md](SprintHistory.md):

- **v26.1.7** — Sprint 8 close: rebrand + Rec 19/25/26 ratchets + SBOM
  bundled-extract.
- **v26.1.6** — Sprint 7 close: CI green tree-wide + Codeberg mirror +
  Win11 VM.
- **v26.1.5** — Sprint 6 close: @Ignore tree-wide sweep + CI rescue.
- **v26.1.4** — Sprint 5 close: Sprint-1 implementation second tier +
  project polish.
- **v26.1.3** — Sprint 4 close: Sprint-3 conflict-resolve + first
  Sprint-1 implementation tier.
- **v26.1.2** — Sprint 3 close: upstream cherry-picks wave 2.
- **v26.1.1** — Sprint 2 close: upstream cherry-picks wave 1.

---

## [26.1] — 2026-05-21 — "the 42-rec audit"

**First release of the GayHydra fork.** Forked from
[NSA/ghidra@94164bd6e9](https://github.com/NationalSecurityAgency/ghidra/commit/94164bd6e9)
which was upstream Ghidra `12.2 (DEV)`.

This release implements the entire 42-recommendation principal-architect
audit (see [`Ghidra.MD`](Ghidra.MD) for the audit) across governance,
security posture, testing/CI, and the decompiler/Sleigh subsystem.
Every recommendation ships either a working artifact (CI workflow,
gradle plugin, fuzz harness, config file, regression test) or a
written design/decision/RFC document that a follow-up PR series can
land against.

### Governance & Maintainer Process

- **#1** PR queue policy — [`docs/governance/PR_QUEUE_POLICY.md`](docs/governance/PR_QUEUE_POLICY.md). Lanes, SLAs, stale-close template, mega-PR RFC gate, queue-health-mode gate.
- **#2** Triage SLA — [`docs/governance/TRIAGE_SLA.md`](docs/governance/TRIAGE_SLA.md). 10-business-day first response with anti-gaming guard on `needs-info`.
- **#3** Stale-PR/issue policy + automation — [`docs/governance/STALE_POLICY.md`](docs/governance/STALE_POLICY.md), [`.github/workflows/stale.yml`](.github/workflows/stale.yml). 365-day clock with 30-day grace.
- **#4** Processor / Sleigh fast-lane — [`docs/governance/lanes/PROCESSOR_LANE.md`](docs/governance/lanes/PROCESSOR_LANE.md), [`.github/PULL_REQUEST_TEMPLATE/processor.md`](.github/PULL_REQUEST_TEMPLATE/processor.md).
- **#5** Decompiler-correctness expedited lane (3-day SLA) — [`docs/governance/lanes/DECOMPILER_CORRECTNESS_LANE.md`](docs/governance/lanes/DECOMPILER_CORRECTNESS_LANE.md).
- **#6** RFC process — [`docs/governance/RFC_PROCESS.md`](docs/governance/RFC_PROCESS.md) + numbered template.
- **#7** `MAINTAINERS.md` — [`MAINTAINERS.md`](MAINTAINERS.md). Bus factor made explicit; 180-day emeritus rule.
- **#8** Label policy retiring `Status: Triage` — [`docs/governance/LABEL_POLICY.md`](docs/governance/LABEL_POLICY.md).
- **#9** Position on upstream #4103 WebAssembly — [`docs/decisions/0001-webassembly-position.md`](docs/decisions/0001-webassembly-position.md). Accept, condition on RFC + 4-stage landing.
- **#10** Position on upstream #5778 RISC-V V/B/K — [`docs/decisions/0002-riscv-vector-position.md`](docs/decisions/0002-riscv-vector-position.md). Accept, retire "Waiting on customer".

### Security Posture

- **#11** [`SECURITY.md`](SECURITY.md) — private disclosure path, severity-tiered response targets, 90-day embargo cap, CVE criteria. **Also opened upstream as [NSA/ghidra#9202](https://github.com/NationalSecurityAgency/ghidra/pull/9202).**
- **#12** CVE assignment policy via GHSA — [`docs/security/CVE_POLICY.md`](docs/security/CVE_POLICY.md). Default-on CVE for in-scope fixes; retroactive review for GP-6832 / GP-6719 / GP-258.
- **#13** OSS-Fuzz integration scaffold (C++ decompiler) — [`docs/security/OSS_FUZZ.md`](docs/security/OSS_FUZZ.md), [harnesses](Ghidra/Features/Decompiler/src/decompile/cpp/fuzz/), [`.github/oss-fuzz/`](.github/oss-fuzz/) ready to submit to google/oss-fuzz.
- **#14** Java loader fuzz harnesses (Jazzer) — [`docs/security/LOADER_FUZZING.md`](docs/security/LOADER_FUZZING.md), [harnesses for ELF/PE/Mach-O](Ghidra/Features/Base/src/test.fuzz/java/ghidra/app/util/bin/format/fuzz/).
- **#15** ASan + UBSan CI for the decompiler — new `make test_san`, [`.github/workflows/decompiler-sanitizers.yml`](.github/workflows/decompiler-sanitizers.yml), [`docs/decompiler/asan-ubsan-ci.md`](docs/decompiler/asan-ubsan-ci.md).
- **#16** Script sandbox (`ghidra.script.sandbox=allowlist`) — [`docs/security/SCRIPT_SANDBOX.md`](docs/security/SCRIPT_SANDBOX.md).
- **#17** Decompiler binary signing via Cosign keyless — [`docs/security/BINARY_SIGNING.md`](docs/security/BINARY_SIGNING.md).
- **#18** Pipeline review of the archive deserialization path (upstream #1481) — [`docs/security/datatype-archive-deserialization-review.md`](docs/security/datatype-archive-deserialization-review.md). Six sites identified, six-step hardening plan.
- **#19** Java deserialization audit + `SafeObjectInput` migration — [`docs/security/JAVA_DESERIALIZATION_AUDIT.md`](docs/security/JAVA_DESERIALIZATION_AUDIT.md). 14 sites in A/B/C risk classes.
- **#20** RMI `serial.filter` enabled by default + regression test — [`launch.properties`](Ghidra/RuntimeScripts/Common/support/launch.properties), [`GhidraSerialFilterDefaultTest.java`](Ghidra/Framework/FileSystem/src/test/java/ghidra/framework/remote/GhidraSerialFilterDefaultTest.java).
- **#21** CycloneDX SBOM in release — [`gradle/sbom.gradle`](gradle/sbom.gradle), [`docs/release/SBOM.md`](docs/release/SBOM.md). Hooked as a `buildGhidra` dependency.

### Testing & CI

- **#22** Run JVM unit tests in CI + JaCoCo upload — [`build-ghidra.yml`](.github/workflows/build-ghidra.yml).
- **#23** Multi-OS CI matrix (ubuntu / macos / windows) — same workflow.
- **#24** C++ decompiler unit tests in CI — [`.github/workflows/decompiler-cpp-tests.yml`](.github/workflows/decompiler-cpp-tests.yml).
- **#25** Re-enable `-Xlint:deprecation,unchecked` + 4-stage ratchet — [`gradle/javaProject.gradle`](gradle/javaProject.gradle), [`docs/testing/XLINT_RATCHET.md`](docs/testing/XLINT_RATCHET.md).
- **#26** ErrorProne static analysis (signal-only Stage 1) — [`gradle/errorprone.gradle`](gradle/errorprone.gradle), [`docs/testing/ERRORPRONE.md`](docs/testing/ERRORPRONE.md).
- **#27** Mockito 5.12.0 on the test classpath — [`gradle/javaProject.gradle`](gradle/javaProject.gradle), [`docs/testing/MOCKITO_ADOPTION.md`](docs/testing/MOCKITO_ADOPTION.md).
- **#28** `@Ignore` debt policy — [`docs/testing/IGNORE_TEST_POLICY.md`](docs/testing/IGNORE_TEST_POLICY.md). Every ignore must carry a tracking issue, category, and deadline.
- **#29** JUnit 5 migration plan (opportunistic, JUnit 4 preserved) — [`docs/testing/JUNIT5_MIGRATION.md`](docs/testing/JUNIT5_MIGRATION.md).
- **#30** Headless test view layer design — [`docs/testing/HEADLESS_TEST_LAYER.md`](docs/testing/HEADLESS_TEST_LAYER.md).

### Decompiler & Sleigh

- **#31** RAII / smart-pointer migration plan — [`docs/decompiler/RAII_MIGRATION.md`](docs/decompiler/RAII_MIGRATION.md). 8-stage bottom-up migration.
- **#32** C++20 adoption plan — [`docs/decompiler/CPP20_ADOPTION.md`](docs/decompiler/CPP20_ADOPTION.md). C++14 then C++20.
- **#33** Versioned IPC framing — [`docs/decompiler/IPC_VERSIONING.md`](docs/decompiler/IPC_VERSIONING.md). Greeting + CRC32 + resync.
- **#34** FlatBuffers IPC payload schema — [`docs/decompiler/IPC_SCHEMA.md`](docs/decompiler/IPC_SCHEMA.md). 8-stage migration with 2-release deprecation window.
- **#35** Per-function decompile budgets + partial-result protocol — [`docs/decompiler/DECOMPILER_BUDGETS.md`](docs/decompiler/DECOMPILER_BUDGETS.md).
- **#36** Decompiler cache invalidation by dependency (upstream #1871) — [`docs/decompiler/CACHE_FLUSH_1871.md`](docs/decompiler/CACHE_FLUSH_1871.md). Per-function dependency bitmaps.
- **#37** RFC 0001 — first-class C++ analysis frontend — [`docs/rfcs/0001-cpp-frontend.md`](docs/rfcs/0001-cpp-frontend.md).
- **#38** RFC 0002 — variable naming across scopes (upstream #975) — [`docs/rfcs/0002-variable-naming-across-scopes.md`](docs/rfcs/0002-variable-naming-across-scopes.md).
- **#39** `for`-loop + inline-function pattern detection — [`docs/decompiler/FOR_LOOP_INLINE_DETECTION.md`](docs/decompiler/FOR_LOOP_INLINE_DETECTION.md).
- **#40** Sleigh formal grammar + semantic model + differential fuzzer — [`docs/sleigh/SLEIGH_FORMAL_AND_FUZZ.md`](docs/sleigh/SLEIGH_FORMAL_AND_FUZZ.md).
- **#41** Per-architecture `MAINTAINERS.md` for `Ghidra/Processors/` — [`Ghidra/Processors/MAINTAINERS.md`](Ghidra/Processors/MAINTAINERS.md). 37 architectures, 8 marked `orphaned-warn (>4yr inactive)`.
- **#42** Jython deprecated; removal scheduled 2027-01-31 — [`docs/decisions/0003-jython-deprecation.md`](docs/decisions/0003-jython-deprecation.md).

### Quality pass

After the first 10 recs shipped at a lower thinking level, a deeper
re-review found gaps in 11 of those documents. The improvements
landed as a single bundled PR: anti-gaming guards on the SLA,
explicit lane-priority tie-breakers, RFC amendment process,
named-author credit on the upstream-PR position documents, refined
threat model in `SECURITY.md`, and more.

## Breaking changes vs upstream Ghidra 12.2

**None.** The fork is a strict superset; all existing tools, scripts,
and analyses continue to work. The `application.name` changed from
`Ghidra` to `GayHydra` and `application.version` from `12.2` to `26.1`;
clients that string-match either should be aware.

## Compatibility

- JDK 21 (same as upstream).
- Gradle 8.5+ (same).
- Python 3.9–3.14 (same; Jython 2 deprecated, removal 2027-01-31).
- C++ toolchain: still `-std=c++11` (the C++20 bump is a plan, not landed).

## Known limitations

This release ships the **design surface** of all 42 recommendations.
The implementation surface is sequenced; the following work has
shipped a plan/RFC but not the implementation:

- OSS-Fuzz upstream project submission to `google/oss-fuzz` (Rec 13/14).
- Cosign release-signing workflow wiring (Rec 17 — the doc and
  verification path are committed; the release workflow's
  signing step is a follow-up PR).
- ASan/UBSan CI is on (Rec 15) but coverage of all decompiler
  test data is incomplete until the seed corpus is grown.
- RAII migration (Rec 31), C++20 bump (Rec 32), IPC versioning
  + FlatBuffers schema (Recs 33–34), bounded budgets (Rec 35),
  cache-invalidation rewrite (Rec 36), C++ frontend (Rec 37),
  variable-naming-across-scopes (Rec 38), `for`-loop/inline
  detection (Rec 39), Sleigh formal grammar + fuzzer (Rec 40).
- Per-arch maintainer slots (Rec 41) all read `orphaned`; opt-in is by
  PR.

Each item above carries a sub-PR sequence documented in its own
design doc.

## Acknowledgements

Built on NSA/ghidra 12.2. The audit and this release: Aaron K. Clark
(@CryptoJones). Upstream contributors are credited in the position
documents for the work this release inherits (notably `@nneonneo`
for the WebAssembly PR referenced in [decision 0001](docs/decisions/0001-webassembly-position.md)).

---

[26.1]: https://codeberg.org/CryptoJones/GayHydra/releases/tag/v26.1

---

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
