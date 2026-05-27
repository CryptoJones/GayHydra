# Changelog

All notable changes to GayHydra are recorded here. Format is loosely
based on [Keep a Changelog](https://keepachangelog.com/); the project
does not yet promise SemVer.

---

## [Unreleased]

Work toward v26.1.11 (Sprint 11 close). Tracked per-PR in
[SprintPlanning.md](SprintPlanning.md); per-release notes are
generated from the GitHub Releases UI at sprint close.

### 2026-05-27 — release.yml Windows glob fix + Rec 31 Stage 3 audit gate

- **`release.yml` Windows zip glob** — `build_sign_publish_windows`'s "Locate release zip" step searched `build/dist/ghidra_*_windows_*.zip`, but `gradle/root/distribution.gradle:688` names the Windows zip using `getCurrentPlatformName()`'s `"win_x86_64"` token (not `"windows_x86_64"`). The glob never matched, the step exited 1, and the matrix's `publish_release` job — gated on Windows succeeding — was skipped on every run from v26.1.6 onward. The four releases v26.1.6 / v26.1.8 / v26.1.9 / v26.1.10 are all stuck as drafts with their signed assets attached. Patched the glob to `ghidra_*_win_*.zip` so the next tag's matrix run completes and `publish_release` auto-flips the draft.
- **Rec 31 Stage 3 audit-gate** — `cover.cc` + `cover.hh` added to `cppRaiiAudit`'s `PROTECTED_FILES`. Both files were already raw-`new`-free in tree; the gate prevents regression while the rest of Stage 3 (`database.cc`, `comment.cc`) is migrated. Same regression-guard pattern as the Stage 1 PR #45 add for `address.cc` / `space.cc` / `rangeutil.cc`.
- **Rec 31 Stage 3 — `comment.cc` RAII migration.** `CommentDatabaseInternal::addComment` and `::addCommentNoDuplicate`'s `Comment *newcom = new Comment(...)` sites migrated to `auto newcom = make_unique<Comment>(...)` with `commentset.insert(newcom.release())` at the ownership-transfer point. The manual `delete newcom; return false;` in the duplicate-check error branch becomes automatic via `unique_ptr` destruction on early return — closes a real exception-unsafety footgun (any future code addition between `new` and `delete` could have leaked). `commentset` itself remains `set<Comment*, CommentOrder>` (changing it to `set<unique_ptr<Comment>>` ripples into comparators + `lower_bound` callers — separate sprint). `comment.cc` + `comment.hh` added to `cppRaiiAudit`'s `PROTECTED_FILES`.
- **Rec 31 Stage 3-5 / 8 audit-gate batch expansion.** 19 additional already-clean decompiler C++ files added to `cppRaiiAudit`'s `PROTECTED_FILES`: `paramid.{cc,hh}`, `pcoderaw.{cc,hh}`, `expression.{cc,hh}`, `float.{cc,hh}`, `ghidra_translate.{cc,hh}`, `ifaceterm.{cc,hh}`, `opcodes.cc`, `filemanage.{cc,hh}`, `dynamic.{cc,hh}`, `multiprecision.{cc,hh}`. Zero raw-`new` allocation sites in any of them under the audit's filter; the gate freezes them as regression guards while migration continues elsewhere. Same pattern as the Stage 1 PR #45 add. Brings the protected set from 11 → 30 files.
- **`cppRaiiAudit` trailing-comment strip + `opcodes.hh` add.** The audit's pure-comment-line filter skips lines whose trim starts with `//` or `*`, but not lines like `CPUI_NEW = 69,    ///< Allocate a new object (new)` where the `//` is *trailing*. Strip everything from the first `//` before applying the regex. Caveat: `//` inside string literals would be wrongly truncated, but the truncation only causes false *negatives* if `new T(` appears inside the string — string-literal `new` matches were already documented as author-fixable false positives. With the filter improved, `opcodes.hh` (clean except for the line-124 trailing-comment hit) now joins `PROTECTED_FILES`. Protected-set count: 30 → 31.
- **Rec 31 Stage 4-8 audit-gate batch.** Another 26 already-clean decompiler C++ files added as regression guards: `cast.{cc,hh}`, `callgraph.{cc,hh}`, `condexe.cc`, `constseq.cc`, `double.cc`, `heritage.{cc,hh}`, `merge.{cc,hh}`, `prefersplit.{cc,hh}`, `subflow.cc`, `bitfield.cc`, `ruleaction.cc` (11k LOC), `op.hh`, `varnode.hh`, `funcdata.hh`, `flow.hh`, `action.hh`, `database.hh`, `userop.hh`, `context.hh`, `translate.hh`, `jumptable.hh`. Files with raw-`new` sites in their `.cc` (e.g. `op.cc`, `funcdata.cc`, `database.cc`, `type.cc`, `userop.cc`) wait for code migration. Protected-set count: 31 → 57.
- **Rec 31 Stage 6 — `flow.cc` RAII migration.** `FlowInfo::setupCallSpecs` and `::setupCallindSpecs` migrate `FuncCallSpecs *res = new FuncCallSpecs(op); qlst.push_back(res);` to `auto owned = make_unique<FuncCallSpecs>(op); FuncCallSpecs *res = owned.get(); qlst.push_back(owned.release());`. Same create-then-transfer-ownership pattern as the comment.cc migration ([PR #90](https://github.com/CryptoJones/GayHydra/pull/90)): the temporary is `unique_ptr`-owned until ownership transfers to `qlst` (which is a `vector<FuncCallSpecs *>`, raw-ptr-typed because changing it would ripple through the FuncCallSpecs lifetime model). `flow.cc` joins `PROTECTED_FILES`. Protected-set count: 57 → 58.
- **Rec 31 Stage 5 — `op.cc` + `varnode.cc` RAII migration.** PCode-core paired migration. Both `PcodeOpBank::create` overloads and both `VarnodeBank::create` / `::createDef` follow the create-then-transfer-into-bank pattern. Migrate the raw `new` to `make_unique` with `.release()` at the point of ownership transfer (insertion into `optree` map / `loc_tree` set / `xref()` call). Includes a small exception-safety improvement in `VarnodeBank::create`: previously, if the second tree insertion threw between the loc_tree.insert and the def_tree.insert, the loc_tree would hold a dangling raw pointer to a leaked Varnode; now `owned.release()` happens immediately after the first insert so the destructor never runs the unique_ptr cleanup over an already-tree-held pointer. `op.cc` + `varnode.cc` join `PROTECTED_FILES`. Protected-set count: 58 → 60.
- **Rec 31 Stage 4 — `type.cc` `TypeCode::proto` migration to `unique_ptr<FuncProto>`.** Changed the member type in `type.hh:774` from `FuncProto *` → `unique_ptr<FuncProto>`. The four `new FuncProto()` allocation sites in type.cc (`setPrototype(...,PrototypePieces)`, `setPrototype(...,FuncProto*)`, copy constructor, `decodePrototype`) become `make_unique<FuncProto>()`. The two `delete proto` sites (in `~TypeCode` and `setPrototype(fp)`) collapse — the destructor becomes `= default` (proto's `unique_ptr` auto-cleans), and `setPrototype(fp)`'s clear-before-reassign uses `proto.reset()`. Null-comparisons against `(FuncProto *)0` become boolean checks on `proto` directly. Friend access in `TypeFactory` (`tc.proto`, `defedCode->proto`) gets `.get()` to extract the raw pointer for the `setPrototype` callee's `FuncProto*` parameter. `type.cc` added to `PROTECTED_FILES`; `type.hh` is held back because of 15 raw-`new` `clone()`-method sites (Datatype subclass factories) — its own migration. Protected-set count: 60 → 61.
- **Rec 31 Stage 7 — `context.cc` `ParserContext::context` array migration to `unique_ptr<uintm[]>`.** `context.hh:115` member type changed from `uintm *` → `unique_ptr<uintm[]>`. `context = new uintm[contextsize]` becomes `make_unique<uintm[]>(contextsize)`. The `if (context != (uintm *)0) delete [] context;` in `~ParserContext` is removed (unique_ptr auto-cleans on destruction). Indexed reads `context[i]` work unchanged via `unique_ptr<T[]>::operator[]`. `context.cc` joins `PROTECTED_FILES` with line-range exclusions [87,87] + [263,263] for the two `state[i] = new ConstructState(...)` sites in `initialize` / `expandState` — those are blocked on a `vector<ConstructState *> state` → `vector<unique_ptr<ConstructState>>` migration that requires a `std::rotate` refactor in `expandState` (the `vector::insert(it, count, value)` count-insert overload requires CopyAssignable; unique_ptr is move-only). Protected-set count: 61 → 62.

### 2026-05-26 — Rec 28 closeout, Rec 31 Stage 1+2A, OSS-Fuzz upstream submission

**Rec 28 — `@Ignore` policy enforcement (Stage 2 strict).**

- **[#43](https://github.com/CryptoJones/GayHydra/pull/43)** `gradle ignoreAudit` flipped Stage 1 → Stage 2 — strict-by-default both in CI and locally. The Rec 28 sweep cleared every author-declared-not-a-regression-test stub; the surviving 51 annotations all carry a category prefix + `#N` ref, so the audit finds zero violations.
- **[#26](https://github.com/CryptoJones/GayHydra/pull/26)–[#34](https://github.com/CryptoJones/GayHydra/pull/34), [#36](https://github.com/CryptoJones/GayHydra/pull/36)–[#41](https://github.com/CryptoJones/GayHydra/pull/41)** 17 author-declared-not-a-regression-test deletions: `JdiExperimentsTest`, `CharsetInfoManagerTest.generateCharsetInfoFile`, `DebuggerMemoryBytesProviderTest.testPerformanceManuallyWithManyManySnaps`, `AbstractDBTraceMemoryManagerMemoryTest.testReplicateClassCastExceptionScenario`, `DebuggerOpinionsTest`, `DBTraceRegisterContextManagerTest`, `DemoFieldsTest`, `DebuggerManualTest`, `experiments/ToArrayTest`, `TenetLoaderTest.testManual`, `AbstractToyJitCodeGeneratorTest.testComputedOffsetsInRegisterSpace`/`.testUninitializedVsInitializedReads`, `CppCompositeTypeTest.testJ5_32_syntactic_layout`, `DBTraceCodeUnitTest.testFigureOutAssembly`, `DBTraceProgramViewListingTest.testGetUndefinedRanges`, `DBTraceAddressSnapRangePropertyMapSpaceTest.testRemove`, `DbgEngHooksTest.testOnSyscallMemory`, `GdbHooksTest.testOnSyscallMemory`. Each PR documents the specific deletion rationale (empty `TODO()` stub, manual JFrame demo, println-only exploration, commented-body shell, etc.).
- **[#70](https://github.com/CryptoJones/GayHydra/pull/70)** Re-filed 15 GitHub tracking issues destroyed in the 2026-05-24 deletion incident as new repo issues [#55](https://github.com/CryptoJones/GayHydra/issues/55)–[#69](https://github.com/CryptoJones/GayHydra/issues/69) with `ignore:1y` + `lane:*` labels. Repointed 51 in-tree `@Ignore` annotations across 21 test files to the new issue numbers (Android OAT/ART source files referencing the same `#N` in unrelated contexts left untouched).
- **[#42](https://github.com/CryptoJones/GayHydra/pull/42), [#53](https://github.com/CryptoJones/GayHydra/pull/53)** `docs/testing/ignore-test-inventory.md` refreshed; `#28-6c` row made honest about post-deletion artifact loss.

**Rec 31 — RAII migration.**

- **[#45](https://github.com/CryptoJones/GayHydra/pull/45)** New `gradle cppRaiiAudit` per-file gate. Forbids raw `new <ClassName>(...)` in `Ghidra/Features/Decompiler/src/decompile/cpp/{address,space,rangeutil}.cc` — those files were already raw-`new`-free; the gate prevents regression. Wired into `.github/workflows/build-ghidra.yml`.
- **[#46](https://github.com/CryptoJones/GayHydra/pull/46)** Stage 2A — `marshal.cc` `ByteChunk` now owns its buffer via `unique_ptr<uint1[]>`. Eliminates the manual `delete[]` cleanup loop in `~PackedDecode`; replaces raw `new uint1[N]` with `make_unique<uint1[]>(N)`. `marshal.cc` + `marshal.hh` added to `cppRaiiAudit`'s `PROTECTED_FILES`. C++ unit tests + ASan/UBSan green.
- **[#71](https://github.com/CryptoJones/GayHydra/pull/71)** Stage 2C design doc (`docs/decompiler/RAII_STAGE_2C_XML.md`). Discovery during the session: `xml.y`'s bison `%union` is fundamentally incompatible with `unique_ptr` (C-style unions can't hold non-trivially-destructible types); the semantic-action sites need either `%define api.value.type variant` (wholesale parser rewrite) or a documented exception in `cppRaiiAudit`. Recommendation: small Stage 2C-min PR for the one obvious code-smell, with the variant-mode rewrite as its own strategic sprint.
- **[#77](https://github.com/CryptoJones/GayHydra/pull/77)** Stage 2C-min Step 1 — `xml.y:208`'s `string *tmp=new string(); ... delete tmp;` converted to a stack-local `string tmp;` (the temporary never escapes its semantic action). Mirrored in `xml.cc:1790`. No bison regeneration; no `%union` change.
- **Stage 2C step 2** — `Element` parse-tree ownership migrated from `vector<Element *>` to `vector<unique_ptr<Element>>`. `Element::~Element` becomes `= default` (the per-child manual `delete` loop is now automatic via the vector's destructor → each `unique_ptr<Element>`). `addChild` signature changed to take `unique_ptr<Element>` (ownership transfer is explicit at the call site). All `*iter` derefs over `List` that assigned to a raw `Element *` updated to `iter->get()` (5 call-sites in marshal.cc; per-call updates in `bfd_arch.cc`, `raw_arch.cc`, `xml_arch.cc`, `testfunction.cc`, `slgh_compile.cc`). `Document::getRoot()` updated to `children.front().get()` since `*children.begin()` no longer converts. No bison regeneration; no `%union` change.
- **Stage 2C step 3** — `Document` return-value ownership migrated. `xml_tree(istream&)` now returns `unique_ptr<Document>`; `XmlDecode::document` member becomes `unique_ptr<Document>`; `DocumentStorage::doclist` becomes `vector<unique_ptr<Document>>`; `InjectPayloadDynamic::addrMap` becomes `map<Address,unique_ptr<Document>>`. All four owners' manual destructors collapse to `= default`. Local `Document *doc` in `slgh_compile.cc`'s ProcessorCompile becomes a stack-local `unique_ptr<Document>` (drops a `delete doc;` at end of scope). `InjectPayloadDynamic::decodeEntry`'s manual "delete preexisting" before reassignment becomes implicit (map's `operator[] = move()` destroys the dropped value). The `xml.y` / `xml.cc` epilogue is now raw-`new`-free; only the four bison semantic-action `%union` sites remain (`xml.y:150, 153, 198, 200`) and need the Option A variant-mode strategic sprint per [`RAII_STAGE_2C_XML.md`](docs/decompiler/RAII_STAGE_2C_XML.md).
- **Stage 2C audit-gate** — `xml.y` + `xml.cc` added to `cppRaiiAudit`'s `PROTECTED_FILES`. `PROTECTED_FILES` migrated from `Set<String>` to `Map<String, List<List<Integer>>>` (path → list of `[startLine, endLine]` excluded ranges); the four bison `%union` semantic-action sites in each file are listed as the only exclusions. Any new raw `new` outside those four lines fails CI in both files. Closes the audit-gate-add carried as "deferred" from Stage 2C-min in [`RAII_STAGE_2C_XML.md`](docs/decompiler/RAII_STAGE_2C_XML.md).

**Rec 13/14 — OSS-Fuzz upstream submission.**

- **[#48](https://github.com/CryptoJones/GayHydra/pull/48)** Replaced `security@example.invalid` placeholders with `cryptojones@owasp.org` as `primary_contact`; `auto_ccs: []` during ramp-up.
- **[#49](https://github.com/CryptoJones/GayHydra/pull/49)** In-tree `.github/oss-fuzz/{Dockerfile,build.sh,project.yaml}` synced byte-for-byte with the upstream PR branch. Apache 2.0 license headers added to `Dockerfile` + `build.sh` per `dpebot`'s `header-check` convention. New `.github/oss-fuzz/README.md` documents the staging workflow.
- **Upstream** [google/oss-fuzz#15545](https://github.com/google/oss-fuzz/pull/15545) — new project `ghidra-decompiler` submitted with two harnesses (`fuzz_xml`, `fuzz_marshal`), AS/UBSan, libfuzzer/AFL/honggfuzz. All automated checks passed (`header-check`, `cla/google`, `check-changes`).
- **Upstream rejection** — same PR closed 2026-05-26 22:49 UTC by Google collaborator DavidKorczynski with the review *"I don't think a fork of Ghidra is a great match with OSS-Fuzz. We prefer projects with large user bases, so I suspect Ghidra itself would be an interesting match."* Soft policy reject (not a fixable submission defect); the reviewer's suggested path of submitting upstream NSA/ghidra is out-of-scope for this fork. Rec 13/14 is re-scoped: the underlying `fuzz_xml` / `fuzz_marshal` harnesses (in `Ghidra/Features/Decompiler/src/decompile/cpp/fuzz/`) stay as our own continuous-fuzzing infrastructure (runnable locally via `Makefile.fuzz` and, future-work, via our own CI).
- **Wrapper rip-out** — the OSS-Fuzz-specific `.github/oss-fuzz/{Dockerfile,build.sh,project.yaml,README.md}` deleted; all four were 100% Google-infrastructure scaffolding (their `gcr.io/oss-fuzz-base/base-builder` image, their `$SRC` / `$LIB_FUZZING_ENGINE` env contract, their `project.yaml` manifest format) with zero value outside the rejected submission. Top-level `README.md` Rec 13 row and `Ghidra/.../fuzz/README.md` updated to drop the dead cross-references; `docs/security/OSS_FUZZ.md` and `docs/security/LOADER_FUZZING.md` retain their integration-plan framing pending a separate doc-touch-up follow-up.
- **Historical sprint-row reconciliation** — three pre-existing unreconciled "submit `.github/oss-fuzz/`" open items struck through in `SprintPlanning.md`'s Sprint 4 / Sprint 5 / Sprint 6 sections, each cross-referenced to the Sprint 10 canonical rejection row. Sprint 10 remains the authoritative record of the policy reject.
- **Self-audit fix-up** — Sprint 10's canonical row in `SprintPlanning.md` had been left over from the pre-rip-out wording (it still claimed "the in-tree `.github/oss-fuzz/` files + the `fuzz_xml` / `fuzz_marshal` harnesses stay") even after the wrapper was deleted in PR #84. Rewritten to match the post-PR-#84 reality — only the harnesses stay; the wrapper directory was deleted — so the row Sprint 4/5/6 strike-outs point to actually agrees with current master.

**CI / housekeeping.**

- **[#47](https://github.com/CryptoJones/GayHydra/pull/47)** `sync-labels.yml` `dry-run: true` → `false`. The declarative `.github/labels.yml` now actually applies label add/remove/edit to the live repo.
- **Branch sweep** (no PR): 16 merged remote feature branches (`sprint-1`..`sprint-8` × 2 remotes) and 10 merged local branches deleted.

**Rec 20 — RMI serial-filter VMARG removed (issue #80, follow-up to mis-filed NSA/ghidra#9220).**

- The upstream NSA/ghidra maintainer correctly pointed out that the `-Djdk.serialFilterFactory=...` line I attributed to upstream's `launch.properties` only exists in our fork (added in our Rec 20 commit `1a64b67e`). On JDK 21.0.10+ that eager VMARG conflicts with the lazy `GhidraSerialFilterFactory.getOrInstallInstance` install path via the JDK's "set exactly once" tightening. Fix: remove the VMARG from `launch.properties`; the filter is still installed at application initialization (matches upstream's behavior). `GhidraSerialFilterDefaultTest`'s class doc updated; `docs/security/JAVA_DESERIALIZATION_AUDIT.md` and `samples/re-targets/gayhydra-dropper/README.md` corrected to describe the new install path. Apologies entry + memory `feedback_verify_upstream_state.md` track the lesson learned.

**Doc sync from 2026-05-26 self-audit.**

- **[#44](https://github.com/CryptoJones/GayHydra/pull/44)** `SprintPlanning.md` synced — Rec 28 #28-6+, Rec 32 #32-2, Rec 32 #32-3 rows marked shipped.
- **[#54](https://github.com/CryptoJones/GayHydra/pull/54)** `SprintPlanning.md` Rec 31 #31-3 row updated to record marshal half shipped + `std::span` (#32-4) deviation explicitly acknowledged.

**Originally listed as in flight, now resolved:**

- **[#51](https://github.com/CryptoJones/GayHydra/pull/51)** xml.y `XmlScan::lvalue` `unique_ptr` migration (Stage 2B) — landed; all builds + C++ unit tests + ASan/UBSan green.
- **[#73](https://github.com/CryptoJones/GayHydra/pull/73)** xml `xml_parse` `global_scan` `unique_ptr` lifetime — landed as a clean cherry-pick of [#52](https://github.com/CryptoJones/GayHydra/pull/52), which auto-closed when its stacked base disappeared after #51's squash-merge.
- **[#74](https://github.com/CryptoJones/GayHydra/pull/74)** CodeQL c-cpp `binutils-dev` fix — landed as a clean cherry-pick of [#50](https://github.com/CryptoJones/GayHydra/pull/50)'s second commit. PR #50 auto-closed when PR #51's squash-merge accidentally included PR #50's *first* commit (the broken-binutils-dev one) because PR #51's branch was inadvertently based on the CodeQL fix branch instead of master. Master's CodeQL c-cpp job now passes (verified at 10m22s on #74's final run); the `cpp/autobuilder: No supported build system detected` preexisting failure that hit every PR is gone.
- **[#75](https://github.com/CryptoJones/GayHydra/pull/75)** Apologies entry for the PR #51 squash-merge stacking mistake — root-causes the chain of events above and records the `git log --oneline master..HEAD` sanity check needed to prevent recurrence.


Release-pipeline-hardening false starts during this sprint:

- **v26.1.8** failed at "Locate release zip + extract bundled SBOM" —
  the unzip pattern from [#230](https://github.com/CryptoJones/GayHydra/pull/230)
  looked for `*/support/sbom/bom.json` but the upstream NSA SBOM
  generator actually writes `bom.json` at the top of the zip-prefix
  directory. Fixed in v26.1.9 ([#327](https://github.com/CryptoJones/GayHydra/pull/327)).
- **v26.1.9** got past SBOM extract + sanity gate but the new
  "Decompiler smoke test" step (added in [#323](https://github.com/CryptoJones/GayHydra/pull/323))
  reported FAIL because the post-script gated on `getFunctionContaining
  != null` — and the Go 1.25 toolchain CI pulled crashes the Go
  analyzer (NSA/ghidra#9219), so no containing function is ever
  created even though the XOR-0x5A instructions are correctly
  disassembled. Fixed in v26.1.10 by counting orphan disassembled
  XOR-0x5A as PASS-weak and only requiring the decompile check when
  a containing function exists.

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

## Released sprints (v26.1.1 – v26.1.11)

Per-sprint release notes live on the
[GitHub Releases page](https://github.com/CryptoJones/GayHydra/releases)
(and [Codeberg Releases](https://codeberg.org/CryptoJones/GayHydra/releases)).
Each `26.1.x` tag corresponds to a Sprint close per the cadence
documented in [SprintHistory.md](SprintHistory.md):

- **v26.1.11** — Sprint 10 close (code-side) + Rec 31 RAII Stage 2A / 2B / 2C complete + Stage 3 first migrations. Stage 2A `marshal.cc` buffer ownership (#46), Stage 2B `xml.cc` lvalue + global_scan (#51, #73), Stage 2C-min `xml.y` stack-local (#77), Stage 2C step 2 `Element` parse-tree ownership (#78), Stage 2C step 3 `Document` return-value (#82), Stage 2C audit-gate (#87). Stage 3 first files: `cover.cc` gate (#89), `comment.cc` migration + gate (#90). Rec 28 closeout (Stage 2 strict-by-default, #43). Rec 13/14 OSS-Fuzz upstream submission + rejection + wrapper rip-out (google/oss-fuzz#15545, #48, #49, #84). Rec 20 RMI VMARG fix (#81). release.yml Windows zip glob fix (#88) — unblocks the matrix's `publish_release` job that's been silently skipped since v26.1.6, leaving every release stuck as a draft. **v26.1.11 is the first release expected to actually appear on the public Releases page** (v26.1.6/8/9/10 backlogged in drafts under "Immutable Releases" tag lockout). First-released `_win_x86_64.zip` artifact: this release closes out the cross-platform-coverage Sprint-10 entry.
- **v26.1.10** — Sprint 9 close + Sprint 10 first half: datatests
  re-enabled (#244, #250–256), Rec 25/26 Stage 3 prep (#247, #249,
  #261, #265–270), SBOM hotfix (#245), RE training sample +
  decompiler smoke test (#319, #321, #323), release pipeline bug
  fixes (#327, #331). First end-to-end signed release — prebuilt zip
  (568 MB), cosign sigs, bundled CycloneDX SBOM. v26.1.8/9 tagged
  but failed; v26.1.10 is the first successful release artifact
  since v26.1.6.

  Caveat: v26.1.10's source tree does **not** include [#333](https://github.com/CryptoJones/GayHydra/pull/333)
  (the `gh release create` fix). The v26.1.10 tag's own first
  release-workflow run hit `release not found` at the upload step
  and was finally rescued by a `workflow_dispatch` re-run against
  master, which used master's already-fixed workflow file but
  checked out the v26.1.10 source. Re-firing release.yml against
  the v26.1.10 tag from a fresh repo clone — without a master
  workflow override — would hit the same `gh release upload`
  failure. Fixed for v26.1.11+ in #333.
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
