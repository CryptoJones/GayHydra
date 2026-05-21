# Sprint History

Past sprints. Each sprint is a logical batch of work, not a fixed
time-box. Newest first.

For upcoming work, see [SprintPlanning.md](SprintPlanning.md).
For why decisions were made the way they were, see
[DesignDecisions.md](DesignDecisions.md).

---

## Sprint 9 — datatest audit, SBOM hotfix, Stage 3 prep (delivered 2026-05-21)

**Goal:** Land the strict CycloneDX SBOM hotfix (Sprint 8's PR #243 ship-out broke master), clear the inherited-from-upstream 189-datatest audit (issue #215), and prep Rec 25/26 Stage 3 for the next sprint.

**Delivered SBOM hotfix (master unbroken):**

- PR #243 (Sprint-8 close-out) rolled in the cyclonedx-gradle-plugin 1.10.0→3.2.4 bump but shipped with two latent String→enum bugs only visible on its first CI run against master. [PR #245](https://github.com/CryptoJones/GayHydra/pull/245) — five iterations — converged on the working pattern:
  1. `schemaVersion` is now `org.cyclonedx.Version` (enum) — needs the value loaded via the plugin's own classloader (the apply-from script's classloader doesn't include cyclonedx-core-java).
  2. `projectType` is now `org.cyclonedx.model.Component$Type` (enum) — same lookup path.
  3. Guard the whole `cyclonedxBom { ... }` block in `if (plugins.hasPlugin('org.cyclonedx.bom'))` so the gradle/actions/dependency-submission action (which runs Gradle in a stripped-down mode without the plugin) doesn't trip.
  4. Disable per-subproject `cyclonedxDirectBom` tasks — they write into the subproject's build directory which `assembleDistribution` copies, tripping Gradle 8.5+ undeclared-input validation.
- PR #241 closed as superseded (#243 absorbed its diff).

**Delivered 189-datatest audit (issue #215 closed):**

- Audit tooling upgrade [PR #244](https://github.com/CryptoJones/GayHydra/pull/244) — added `-dumpdir` flag to `decomp_test_dbg`. When set, the test harness writes `<dumpdir>/<testfile-basename>.actual` with the raw decompiled C bulkout per file, so the audit workflow's artifact lets us compare expected stringmatch regex vs. actual output offline. Fixes a SUMMARY.md grep bug (`grep -c ... || echo 0` double-emitted `0\n0` cells on no-match).
- Five batched regex-fix PRs landed [#250](https://github.com/CryptoJones/GayHydra/pull/250) ... [#254](https://github.com/CryptoJones/GayHydra/pull/254) closing all **189 of 189** audit failures. Categorization:
  - **L-suffix drift** (decompiler now appends `L` on int8/long literals + pointer-arithmetic array indices) — 153 failures across modulo (38), condconst (4), divopt (68), switchmulti (7), and many smaller files. Fixed with `L?` markers (matches both old + new output).
  - **Space-after-comma drift** (decompiler now emits spaces after function-call commas) — 27 failures across mixfloatint, longdouble, heapstring, stackstring, stackreturn, injectoverride, gp, stackspill. Fixed with ` ?` after the comma.
  - **Sign simplification** (`+ -N` → `- N`) — 2 failures in copytrim and ifswitch. Fixed with non-capturing alternation `(?:\+ -|- )`.
  - **Line-wrap** (decompiler now wraps long expressions) — 1 failure in enum.xml. Single-line pattern split into two lines.
  - **Float-op prefix** (raw pcode dump emits `f+` instead of `+`) — 1 failure in doublemove. Fixed with `f?\+`.
  - **L-suffix on trailing comparison constant** — 1 in enum.xml.
- [PR #255](https://github.com/CryptoJones/GayHydra/pull/255) re-enabled the datatests step in `.github/workflows/decompiler-cpp-tests.yml`.

**Delivered Rec 25/26 Stage 3 prep:**

- [`docs/testing/STAGE3_ENUMERATION.md`](docs/testing/STAGE3_ENUMERATION.md) ([PR #246](https://github.com/CryptoJones/GayHydra/pull/246)) — warning census from the last green master Build-Ghidra log (844 total post-Stage-2, 6 subprojects ≥50, three of those capped at javac's default `-Xmaxwarns=100`). Documents the 6-step Stage-3 PR sequence so the next contributor can pick up any starting point.
- [PR #249](https://github.com/CryptoJones/GayHydra/pull/249) — step 1: bump `-Xmaxwarns` to 0 (uncapped) so the true per-subproject counts surface in future master logs.
- [PR #247](https://github.com/CryptoJones/GayHydra/pull/247) — step 2: `@SuppressWarnings("removal")` on 29 self-deprecated classes (the deprecated Vertex/VertexSet graph types + per-arch emulator-state-modifier siblings). Expected to halve the tree-wide warning total (from ~844 to ~400) by removing the `[removal]` noise category in one mechanical PR.

Steps 3–6 (per-subproject pre-clean of the remaining categories, then `-Werror` + ErrorProne WARN→ERROR) queued for Sprint 10 per `STAGE3_ENUMERATION.md`.

**Delivered planning/docs:**

- [PR #248](https://github.com/CryptoJones/GayHydra/pull/248) — SprintPlanning.md updated to capture Sprint-9 in-flight state at the time the 6 dependent PRs were open.

**v26.1.7 release artifacts (Sprint 8 close):** the Sprint-8 close shipped the 26.1.7 bump; first-attempt release CI was blocked by the same #243 SBOM bug. Sprint 9's #245 hotfix unblocks it.

**Carried to Sprint 10:**

- **Rec 13/14 OSS-Fuzz submission** — `.github/oss-fuzz/project.yaml` still has `security@example.invalid` placeholder for `primary_contact` and `auto_ccs`. External-facing config; needs maintainer decision before the upstream PR to google/oss-fuzz.
- **Rec 25/26 Stage 3 steps 3–6** — per-subproject pre-clean (six subprojects at ≥50 warnings, see STAGE3_ENUMERATION.md), then `-Werror` flip + ErrorProne WARN→ERROR. Approach: smallest subproject first (PIC), measure tree-wide drop, iterate.
- **PR #141** — port of NSA/ghidra#9015 (PyGhidra `from __main__ import` improvement). Pre-existing, not Sprint-9.

---

## Sprint 8 — Rebrand + Rec 19/25/26 ratchets + SBOM re-impl (delivered 2026-05-21)

**Goal:** Land Sprint 8's user-facing rebrand at the right scope, knock down the carried Rec 19/25/26 items, and set up the foundation for the deferred datatest audit + SBOM re-implementation.

**Delivered visual + textual rebrand (Tier 1 + Tier 2):**

- Top-level rebrand of project name references in user-facing surfaces: README marketing paragraph + Security Warning + Install/Build/Develop/Contribute sections ([PR #223](https://github.com/CryptoJones/GayHydra/pull/223)). Upstream attribution, file paths, Gradle task names, Eclipse plugin and run-config names, build-artifact filename pattern, and launcher script names preserved as documented in the new README addendum.
- README image swap from upstream Ghidra logo to [`GayHydra.png`](GayHydra.png) ([PR #222](https://github.com/CryptoJones/GayHydra/pull/222)).
- Program icons under `Ghidra/` rebranded (Aaron explicitly approved the merge-conflict cost on these binaries): 8x `GhidraIcon{16,24,32,40,48,64,128,256}.png`, `GHIDRA_Splash.png` (500x500), `GHIDRA_1/2/3.png`, multi-size `ghidra.ico` ([PR #224](https://github.com/CryptoJones/GayHydra/pull/224)).
- `SECURITY.md` rebrand of generic project-name refs ([PR #226](https://github.com/CryptoJones/GayHydra/pull/226)); "upstream Ghidra" attribution + "Ghidra server" proper-noun preserved.
- `DevGuide.md` fork-addendum quote block at top (same pattern as `CONTRIBUTING.md`) — 61 mentions are >95% technical references that apply unchanged ([PR #227](https://github.com/CryptoJones/GayHydra/pull/227)).
- Tier 2: rebrand of 4 user-visible `gradle tasks` task descriptions (`prepDev`, `createGhidraStubsWheel`, `buildSourcesArchive`, `buildGhidra`) ([PR #238](https://github.com/CryptoJones/GayHydra/pull/238)). Task names preserved.

**Delivered Rec 19/25/26 ratchets:**

- **Rec 19 #19-2 first migration** — install reject-all `ObjectInputFilter` on `ItemDeserializer`'s `ObjectInputStream` ([PR #235](https://github.com/CryptoJones/GayHydra/pull/235)). Wire format preserved (matching `ItemSerializer` uses `ObjectOutputStream`'s 6-byte magic prefix), so `DataInputStream` wasn't an option. `CodeUnitInfo`'s `readObject` is commented out, so its #19-2 entry was a no-op.
- **Rec 25 Stage 2** — widen `-Xlint` default from `deprecation,unchecked` → `deprecation,unchecked,rawtypes,cast` ([PR #240](https://github.com/CryptoJones/GayHydra/pull/240)). Non-blocking; per-subproject `lintOpts` opt-out preserved.
- **Rec 26 ErrorProne re-enable** ([PR #236](https://github.com/CryptoJones/GayHydra/pull/236)). The Gradle-8.5 timing bug (the `errorprone` configuration registered AFTER the `subprojects { plugins.withId('java-library') { ... } }` body ran) is worked around by deferring the `dependencies { errorprone ... }` add inside `pluginManager.withPlugin`. The follow-up [PR #242](https://github.com/CryptoJones/GayHydra/pull/242) moved the matching `options.errorprone { ... }` block inside the same defer after the first attempt hit "No signature of method: CompileOptions.errorprone()" on Windows CI.
- **Rec 26 Stage 2** — flip `JavaUtilDate` + `JdkObsolete` ErrorProne checks from `OFF` → `WARN` ([PR #239](https://github.com/CryptoJones/GayHydra/pull/239)). Non-blocking; surfaces the backlog for Stage 3 pre-clean.

**Delivered audit + SBOM foundations:**

- Audit-datatests workflow ([PR #229](https://github.com/CryptoJones/GayHydra/pull/229)) — `workflow_dispatch` trigger that runs `decomp_test_dbg datatests` per XML file under `decompile/datatests/`, captures full output per file, uploads as artifact, generates `SUMMARY.md` with pass/fail counts. Foundation for issue #215.
- Partial Rec 21 SBOM re-impl: extract upstream-NSA-generated `support/sbom/bom.json` out of the release zip post-build, Cosign-sign it (keyless OIDC), upload as standalone signed release asset ([PR #230](https://github.com/CryptoJones/GayHydra/pull/230)). The ≥10-component sanity gate that was lost when the cyclonedxBom plugin was reverted in Sprint 7's #220 is re-added at the workflow level via Python `jq`-style component counting ([PR #233](https://github.com/CryptoJones/GayHydra/pull/233)).
- `docs/release/SBOM.md` updated to describe both the in-zip and standalone signed paths + the Sprint-8-options decision matrix ([PR #234](https://github.com/CryptoJones/GayHydra/pull/234)).

**Delivered decision records:**

- DD-019 captures the Rec 21 SBOM revert + the partial bundled-extract re-impl decision and alternatives considered ([PR #231](https://github.com/CryptoJones/GayHydra/pull/231)).
- DD-011 flipped from "Codeberg mirror deferred" → "shipped" (same PR).

**v26.1.6 release artifacts (Sprint 7 close):** the v26.1.6 release workflow ran successfully — first ever signed release on this fork. Three signed assets attached: `ghidra_26.1.6_GayHydra-26.1.6_20260521_linux_x86_64.zip` (568 MB), `.zip.sig`, `.zip.crt`. Verified via `cosign verify-blob` with OIDC identity at `github.com/CryptoJones/GayHydra/`.

**Carried to Sprint 9:**

- **PR #241** in flight: cyclonedx-gradle-plugin bump `1.10.0` → `3.2.4`, the proper strict-CycloneDX re-impl using the post-PR-#532 rewrite. Branch built against the new 3.x DSL (`jsonOutput`/`xmlOutput` properties, no `outputFormat`). CI signal pending on merge into master.
- Audit the 189 decompiler datatest failures (issue #215). Workflow_dispatch run in flight; categorization is Sprint 9 work.
- Re-enable datatests in `decompiler-cpp-tests.yml` once the audit is done.
- Rec 13/14 OSS-Fuzz external submission to google/oss-fuzz.
- Rec 25/26 Stage 3 — pre-clean ≥50-warning subprojects + promote ratcheted checks to `ERROR` + `-Werror`.

---

## Sprint 7 — Codeberg mirror + local Win11 VM + CI green tree-wide (delivered 2026-05-21)

**Goal:** Mirror the repo to Codeberg per Aaron's dual-remote convention; stand up a local QEMU Win11 VM for .NET testing; bring all three CI workflows green after Sprint 6's cascade revealed deeper layer-by-layer breakage.

**Delivered Codeberg mirror:**

- Mirror live at [`codeberg.org/CryptoJones/GayHydra`](https://codeberg.org/CryptoJones/GayHydra) — `master`, all `sprint-1`..`sprint-7` branches, all `v26.1.x` tags pushed.
- Per-sprint git tags `v26.1`..`v26.1.6` at each sprint's close commit, with matching `sprint-N` branches.
- GitHub Releases entries for `v26.1` through `v26.1.6` with per-sprint highlights.
- `dual-remote-pr` skill cycle exercised end-to-end across the sprint's 16+ PRs.
- README repointed to Codeberg as canonical — [PR #208](https://github.com/CryptoJones/GayHydra/pull/208) shield row + primary doc links, [PR #221](https://github.com/CryptoJones/GayHydra/pull/221) install instructions, [PR #225](https://github.com/CryptoJones/GayHydra/pull/225) archive-extract directory.

**Delivered Win11 VM for .NET testing:**

- `~/qemu-win11/` scaffolding: `launch.sh`, `start-tpm.sh`, `autounattend.xml` + ISO, `setup-ssh.ps1`, README.
- KVM-accelerated Q35 UEFI VM with TPM 2.0 via swtpm, 200 GiB qcow2, e1000 NIC, AHCI SATA disk (after debugging the virtio-blk driver gap that's missing from Win11 install media).
- OpenSSH Server installed in-guest via `setup-ssh.ps1`; host pubkey baked into `administrators_authorized_keys`; firewall profile widened to `Any` so QEMU NAT's Public-classified network passes through. `ssh win11-ci` works from the host.
- Disk grown from 60 GiB to 200 GiB after install via `qemu-img resize` + `Remove-Partition` (Recovery partition was at end of disk, blocking C: extend) + `Resize-Partition`.
- VS Pro 2022 bootstrapper staged on a CD ISO (`E:\vs_professional_2022.exe`) for manual install from inside the VM.

**Delivered CI green tree-wide (8 PRs):**

When the sprint began, each push to master cascaded through new failures as Sprint 1–6's Rec wirings hit their first end-to-end CI run. The chase:

- **Decompiler C++ Unit Tests — [PR #212](https://github.com/CryptoJones/GayHydra/pull/212):** Install `binutils-dev` + `libiberty-dev` on the Ubuntu runner so `analyzesigs.cc` finds `bfd.h`. Drop macOS from the matrix — Apple's bundled bfd headers are incompatible and Homebrew's `binutils` is keg-only with non-trivial CPPFLAGS/LDFLAGS plumbing.
- **[PR #214](https://github.com/CryptoJones/GayHydra/pull/214):** Add JDK + Gradle setup + `gradle allSleighCompile` before `make test`. Without precompiled `.sla` files, `startDecompilerLibrary()` fails at every unit test that loads an x86/MIPS architecture.
- **[PR #216](https://github.com/CryptoJones/GayHydra/pull/216):** After sleigh-compile, build passes but datatests/*.xml regress with 189/677 failures — pre-existing brittleness inherited from upstream NSA (XML stringmatch regexes drifted out of sync with the decompiler's current output formatting; NSA doesn't run them in CI, so the drift went uncaught). Scope the workflow to `decomp_test_dbg unittests` only; queue the data-test audit as Sprint 8 work (see [issue #215](https://github.com/CryptoJones/GayHydra/issues/215)).
- **Dependency Submission — [PR #213](https://github.com/CryptoJones/GayHydra/pull/213) → [PR #218](https://github.com/CryptoJones/GayHydra/pull/218):** The action's default `generate-submit-and-upload` hits GitHub's Dependency Graph API which returns "disabled for this repository" on a public fork without the feature explicitly enabled. PR #213 worked around it with `generate-and-upload`; after Aaron enabled Dependency Graph + Dependabot security updates + Secret Scanning + Push Protection in repo Settings → Code security, PR #218 reverted the band-aid back to the real submit path. Verified live: `/repos/CryptoJones/GayHydra/dependency-graph/sbom` returns a valid SPDX-2.3 doc.
- **Build Ghidra (the long pole) — [PR #217](https://github.com/CryptoJones/GayHydra/pull/217) → [PR #220](https://github.com/CryptoJones/GayHydra/pull/220):** cyclonedx-gradle-plugin 1.10.0 (Rec 21) refused to run without `rootProject.group`/`version`; PR #217 set them. Then it NPE'd on every flat-dir dependency (`:AXMLPrinter2:` etc.) that ships with no Maven coordinates. PR #220 reverted the Rec 21 SBOM wiring entirely — the plugin declaration in `build.gradle`, `gradle/sbom.gradle` (cyclonedxBom config + sbomSanityCheck ≥10-component gate), the `cyclonedxBom`-dependent steps in `release.yml`. The upstream-NSA SBOM generator at `gradle/support/sbom.gradle` still produces an SBOM bundled inside the release zip (`support/sbom/bom.json`), Cosign-covered by the zip signature. Rec 21 re-implementation queued for Sprint 8 — three options laid out in `docs/release/SBOM.md`.

**Delivered visual rebrand (Sprint 8 Tier 1, pulled forward):**

- **[PR #222](https://github.com/CryptoJones/GayHydra/pull/222):** Drop in `GayHydra.png` at repo root and update the README marketing image — `Ghidra/` source tree untouched.
- **[PR #223](https://github.com/CryptoJones/GayHydra/pull/223):** README user-facing rebrand. Replace generic project-name "Ghidra" with "GayHydra" in marketing paragraph, Security Warning, Install/Build/Develop/Contribute sections. Preserved: upstream attribution, the audit-doc title (Ghidra: Top 42), file paths, Gradle task names, Eclipse plugin / run-config names, the `ghidra_<version>` build-artifact filename, launcher script names. Dropped the NSA-recruitment paragraph.
- **[PR #224](https://github.com/CryptoJones/GayHydra/pull/224):** Program icons under `Ghidra/` (Aaron explicitly approved the merge-conflict cost on these binaries) — 8x `GhidraIcon{16,24,32,40,48,64,128,256}.png`, `GHIDRA_Splash.png`, `GHIDRA_1/2/3.png`, multi-size `ghidra.ico`. Square GayHydra logo centered on transparent letterboxing where source aspects don't match. Filenames stay upstream because Java/HTML resource references expect those names.
- **[PR #226](https://github.com/CryptoJones/GayHydra/pull/226):** `SECURITY.md` — rebrand generic project-name refs; keep "upstream Ghidra" attribution; keep "Ghidra server" as the proper-noun component name.
- **[PR #227](https://github.com/CryptoJones/GayHydra/pull/227):** `DevGuide.md` — fork-addendum at top (same pattern as `CONTRIBUTING.md`'s preamble), upstream body preserved unchanged. 61 mentions of "Ghidra" in DevGuide are >95% technical (paths, gradle tasks, Eclipse plugin names) that apply unchanged to the fork.

**Carried to Sprint 8:**

- Audit the 189 decompiler datatest failures — see [issue #215](https://github.com/CryptoJones/GayHydra/issues/215). Cosmetic regex updates vs. real decompiler regressions; batch the former, consider upstream give-back PRs for the latter.
- Re-implement Rec 21 SBOM as a separate signed release artifact — three implementation options in `docs/release/SBOM.md`.
- Re-enable datatests in `decompiler-cpp-tests.yml` once the audit completes.
- ErrorProne (Rec 26) re-wiring — currently disabled (works on Gradle 9.5+, fails on the pinned 8.5).
- Sprint 5's still-open items: Rec 13/14 OSS-Fuzz submission, Rec 19 #19-2 SafeObjectInput migration, Rec 25/26 Stage 2.

---

## Sprint 6 — @Ignore tree-wide sweep + CI rescue (delivered 2026-05-21)

**Goal:** Complete the Rec 28 `@Ignore` cleanup across the whole tree
so the audit can flip from warn-only to strict; then, once the
audit was enforced, repair the long-broken CI workflows so a
green build is reachable across all three platforms.

**Delivered Rec 28 wrap-up (4 PRs):**

- **#28-5 batch 1 — [PR #184](https://codeberg.org/CryptoJones/GayHydra/pulls/184):** Compliance-fix 23 sites across 9 clusters (LldbCommands EXC_BAD_ACCESS x4 / temp-var-$x x6, JavaMethods race x3, Debugger RMI bare-ignore x5, LldbConnectors TODO x5, JitJvm version-bound, JitMpInt dev-workstation, AbstractToyJitCodeGen x4). Files tracking issues #176–#183.
- **ignoreAudit warn-only mode — [PRs #185](https://codeberg.org/CryptoJones/GayHydra/pulls/185) + [#186](https://codeberg.org/CryptoJones/GayHydra/pulls/186):** Stage 1 / Stage 2 split so the audit doesn't break CI mid-sweep.
- **#28-6 batch 2 — [PR #194](https://codeberg.org/CryptoJones/GayHydra/pulls/194):** Sweep the remaining ~35 `@Ignore` sites in MDMang, Hexagon, Debug/Framework-TraceModeling, Debug-jpda, Debugger plugin tests, and Misc. Files tracking issues #187–#193.
- **#28-7 strict flip — [PR #195](https://codeberg.org/CryptoJones/GayHydra/pulls/195):** Tree-wide sweep complete → flip ignoreAudit to `-PignoreAuditStrict=true` in CI. Future bare/uncategorized `@Ignore` additions now fail the build.

**Delivered CI rescue (8 PRs):**

When the user asked "ARE TESTS FAILING IN THE PRs??" I discovered CI had been broken since the fork point — `./gradlew` failed because NSA never checked in the Gradle wrapper jar. Cascading fixes:

- **[PR #196](https://codeberg.org/CryptoJones/GayHydra/pulls/196):** Replace `./gradlew` with `gradle/actions/setup-gradle@v4` + `gradle` across `build-ghidra.yml`, `release.yml`, `dependency-submission.yml`.
- **[PR #197](https://codeberg.org/CryptoJones/GayHydra/pulls/197):** Decouple `application.version` (fork's 26.1) from `application.upstream.version` (the NSA/ghidra-data tag 12.2 that fetchDependencies uses). The v26.1 bump silently broke fetchDeps's URL construction.
- **[PR #198](https://codeberg.org/CryptoJones/GayHydra/pulls/198):** Move `plugins {}` block from `gradle/sbom.gradle` + `gradle/errorprone.gradle` to root `build.gradle`. The plugin DSL is only legal at the top of Project/Settings scripts.
- **[PR #199](https://codeberg.org/CryptoJones/GayHydra/pulls/199):** ErrorProne `CheckSeverity` import attempt (didn't fully fix; superseded).
- **[PR #200](https://codeberg.org/CryptoJones/GayHydra/pulls/200):** ErrorProne raw `-Xep:` args (still failed under Gradle 8.5).
- **[PR #201](https://codeberg.org/CryptoJones/GayHydra/pulls/201):** ARM `certification.manifest` — register `ARM8m_cp_be.slaspec` + `ARM8m_cp_le.slaspec` from PR #137.
- **[PR #202](https://codeberg.org/CryptoJones/GayHydra/pulls/202):** Temporarily disable ErrorProne wiring (keep design, defer plumbing to a focused PR).
- **[PR #203](https://codeberg.org/CryptoJones/GayHydra/pulls/203):** `* IP: GHIDRA` headers on the 5 fuzz-harness `.java`/`.cc` files + cert-manifest entries for the 3 supporting non-source files (README.md x2, Makefile.fuzz).

**Verification:**

- A local Apple Silicon mac mini (macOS 26.3) stood up as the canonical mac test environment — caught the ARM cert manifest hole and the missing `* IP: GHIDRA` headers that GH Actions also fails on.
- Local QEMU+Win11+Puppet was considered but deferred: GH Actions ships a Windows runner already, and the Windows failures so far have been the same toolchain issues as the other platforms (per [DD-019](DesignDecisions.md) — not yet written).

**Sprint 6 total: 12 PRs (4 Rec 28 wrap + 8 CI rescue) + 18 tracking issues filed (#176–#193).**

**Carried into Sprint 7:**

- Re-wire ErrorProne cleanly (move config into root `build.gradle` or use a different plugin-application pattern that works under Gradle 8.5).
- Verify CI is fully green tree-wide after PR #203.
- Sprint 5's still-open items: Rec 13/14 OSS-Fuzz upstream submissions, Rec 19 #19-2 SafeObjectInput migration, Rec 25 Stage 2 / Rec 26 Stage 2.

---

## Sprint 5 — Sprint-1 implementation second tier + project polish (delivered 2026-05-21)

**Goal:** Continue landing Sprint-1 implementation surface (SBOM gate, release notes, retroactive-CVE tracking, JUnit 5 deps) and clean up the project's contributor-onboarding edges (declarative labels, issue templates, fork addendum to CONTRIBUTING).

**Delivered (8 PRs):**

- **Rec 21 — [PR #167](https://codeberg.org/CryptoJones/GayHydra/pulls/167):** `sbomSanityCheck` Gradle task fails the build if the CycloneDX SBOM has <10 components.
- **Rec 17 #17-3 — [PR #168](https://codeberg.org/CryptoJones/GayHydra/pulls/168):** `.github/RELEASE_NOTES_TEMPLATE.md` with the permanent Cosign `verify-blob` snippet (OIDC-identity-pinned to our release workflow).
- **Rec 12 — [PR #169](https://codeberg.org/CryptoJones/GayHydra/pulls/169):** `docs/security/retroactive-cve-tracking.md` workspace for the three GP-* trackers. Rows stay TBD until a maintainer applies CVSS by hand.
- **Rec 29 Stage 1 — [PR #170](https://codeberg.org/CryptoJones/GayHydra/pulls/170):** JUnit 5 Jupiter + Vintage engine + Platform launcher on `testImplementation`.
- **Rec 29 Stage 2 — [PR #171](https://codeberg.org/CryptoJones/GayHydra/pulls/171):** `useJUnitPlatform()` wired on both `test` and `integrationTest` tasks.
- **Rec 08 — [PR #172](https://codeberg.org/CryptoJones/GayHydra/pulls/172):** Declarative `.github/labels.yml` + `sync-labels.yml` workflow (dry-run mode for first review).
- **[PR #173](https://codeberg.org/CryptoJones/GayHydra/pulls/173):** Five GitHub issue templates aligned with the lane/severity model + a `config.yml` routing security reports to the private GHSA path.
- **[PR #174](https://codeberg.org/CryptoJones/GayHydra/pulls/174):** CONTRIBUTING.md fork-addendum prepended to NSA's upstream content; points contributors at the entire governance stack in one place.

**Carried into Sprint 6:**

- Flip `sync-labels.yml`'s `dry-run` to `false` after Aaron reviews the first workflow run.
- Rec 11 follow-up: monitor NSA#9202 review.
- Rec 13 / Rec 14: submit OSS-Fuzz projects to google/oss-fuzz.
- Rec 19 #19-2: `SafeObjectInput` migration of `ItemDeserializer` (Class A).
- Rec 25 Stage 2 + Rec 26 Stage 2: warning-floor cleanup before widening.
- Rec 28 #28-5+: broader `@Ignore` sweep across the remaining ~25 sites.

---

## Sprint 4 — Sprint-3 conflict-resolve + first Sprint-1 implementation tier (delivered 2026-05-21)

**Goal:** Hand-resolve the 9 conflict-skipped cherry-picks from Sprint 3, hand-port the carried-over BSim PR, and start landing the implementation surface for the recs shipped as designs in Sprint 1.

**Delivered (12 PRs):**

- **6 hand-resolved cherry-picks** (PRs #150–#155 + #165):
  - [PR #150](https://codeberg.org/CryptoJones/GayHydra/pulls/150) ports NSA#8270 (PIC18 RLNCF/RRNCF use rotate, closes #8269)
  - [PR #151](https://codeberg.org/CryptoJones/GayHydra/pulls/151) ports NSA#2244 (exclude `.vscode/`, closes #2243)
  - [PR #152](https://codeberg.org/CryptoJones/GayHydra/pulls/152) ports NSA#8635 (Decompiler: in-place C operations for STOREs, closes #8634)
  - [PR #153](https://codeberg.org/CryptoJones/GayHydra/pulls/153) ports NSA#3687 (ARM exception-return `goto [pc]`→`return [pc]`, closes #3678)
  - [PR #154](https://codeberg.org/CryptoJones/GayHydra/pulls/154) ports NSA#8815 (Linux syscall numbers, closes #8814)
  - [PR #155](https://codeberg.org/CryptoJones/GayHydra/pulls/155) ports NSA#6390 (RISC-V WCH/QingKe XW extension, closes #6391)
  - [PR #165](https://codeberg.org/CryptoJones/GayHydra/pulls/165) hand-ports NSA#6897 (BSim address-space id, 25 files, gson↔json-simple drift resolved, closes #6896)

- **3 cherry-picks deferred** (structural drift too deep for a quick resolve): NSA#5593 (constant integer export — depends on `largetemp` infra missing in our master), NSA#3974 (Comments Set... — 5 file conflicts + missing file), NSA#3137 (cspec docs in build — 7 conflict points across build.gradle + docs).

- **Sprint-1 implementation surface — first PRs landed:**
  - **Rec 19 #19-1 — [PR #156](https://codeberg.org/CryptoJones/GayHydra/pulls/156):** `SafeObjectInput` helper class + 7 JUnit 4 tests. The sanctioned entry point for Java object deserialization.
  - **Rec 28 #28-2 — [PR #157](https://codeberg.org/CryptoJones/GayHydra/pulls/157):** `@Ignore` test inventory document.
  - **Rec 28 #28-3 — [PR #158](https://codeberg.org/CryptoJones/GayHydra/pulls/158):** `gradle ignoreAudit` task + CI step gating the policy.
  - **Rec 28 #28-4 — [PR #163](https://codeberg.org/CryptoJones/GayHydra/pulls/163):** Filed tracking issues #159–#162 for the audit-named tests + rewrote each annotation in compliance with the policy + deleted commented-out `//@Ignore` noise.
  - **Rec 17 #17-2 — [PR #164](https://codeberg.org/CryptoJones/GayHydra/pulls/164):** `.github/workflows/release.yml` — Cosign keyless signing of the release zip + SBOM on tag push.

**Sprint 4 total: 12 PRs (7 upstream ports + 5 implementation-surface advances) + 4 tracking issues filed.**

**Carried into Sprint 5:**

- 3 structural-drift upstream PRs (NSA#5593, NSA#3974, NSA#3137) — defer further, may not be worth porting.
- More Sprint-1 implementation: Rec 11 follow-up (NSA#9202 review), Rec 12 (GHSA drafts — needs CVSS), Rec 13 (submit OSS-Fuzz project to google/oss-fuzz), Rec 21 SBOM sanity gate, Rec 25 Stage 2 (-Xlint widening — needs warning-floor cleanup), Rec 26 Stage 2 (ErrorProne tier 2).

---

## Sprint 3 — Upstream Cherry-Picks, Wave 2 (delivered earlier 2026-05-21)

**Goal:** Continue mining the [crossref report](docs/upstream-tracking/pr-issue-matches.md) for cleanly-applying upstream PRs, prioritised by size (smallest first to maximize ports-per-effort).

**Delivered (28 more upstream PR ports + Ghidra.MD add):**

- **Tiny batch (PRs #114–#128, 15 ports landed, 2 cherry-pick conflicts skipped):**
  CompareExecutablesScript Elastic-BSim fix (NSA#8947), invalid-char 127 (NSA#9172), RuleAddUnsigned extend (NSA#8628), batch-import FS select (NSA#7999), don't propagate types through call clobbers (NSA#4759), AArch64 S-bit decode (NSA#9082), BSim sig-file warning (NSA#8755), `__cdecl16far` 32-bit return (NSA#2633), ExecutableComparison missing-vectors skip (NSA#8949), x86 real-mode CS calc (NSA#8521), thumb-pointer ptrsubundo (NSA#8990), Z80 (HL) indirect addressing (NSA#9196), PE `SizeOfRawData` round-up (NSA#9175), msr apsr input register (NSA#6598), unbounded recursion in `Varnode::eraseDescend` (NSA#8626).
  Skipped (conflict): PIC18 RLNCF/RRNCF (NSA#8270), VScode exclude (NSA#2244).

- **Small batch (PRs #129–#141, 13 ports landed, 2 conflicts skipped):**
  PointerToRawData round (NSA#9176), PIC24 OV/N pattern (NSA#8778), PE/COFF symbol-count limit (NSA#9174), Toggle-Type-Casts decompiler menu (NSA#5623), max symbol-name length (NSA#9173), SleighPreprocessor undefined directives (NSA#1835), x86 AF flag support (NSA#9071), x86 YMM FMA 128-bit truncation (NSA#9197), ARM v8M+coprocessor CDE-disable (NSA#8582), i8085 unofficial instructions (NSA#8843), V850 p-code emulation tests (NSA#8997), BSim `listdatabases` (NSA#8439), PyGhidra `from __main__ import` (NSA#9015).
  Skipped (conflict): constant-integer-export detect (NSA#5593), Comments-Set-default-edit (NSA#3974).

- **Medium batch (PRs #144–#148, 5 ports landed, 5 conflicts skipped):**
  AArch64 LSE Rs=zr propagation (NSA#9079), TriCore FPU conversion/division semantics (NSA#8999), V850 26 SLEIGH semantic bugs (NSA#8996), comment/label history username anonymize API (NSA#8729), PowerPC MSVC switch-table analysis (NSA#8964).
  Skipped (conflict, deferred to Sprint 4 hand-port): ARM Old Exception Return (NSA#3687), cspec docs in build (NSA#3137), Decompiler in-place C operations (NSA#8635), RISC-V WCH/QingKe XW extension (NSA#6390), Linux syscall numbers (NSA#8815).

- **[Ghidra.MD now in tree (PR #142)](https://codeberg.org/CryptoJones/GayHydra/pulls/142):** the audit doc was sitting untracked since fork creation; README + CHANGELOG referenced it but the file itself wasn't on GitHub. Now committed.

- **[DesignDecisions.md (PR #143)](https://codeberg.org/CryptoJones/GayHydra/pulls/143):** new top-level doc capturing the *why* behind 18 architectural / process / judgment-call decisions made in Sprints 1–3.

**Sprint 3 total: 33 upstream PR ports + Ghidra.MD + DesignDecisions.md.**

**Carried into Sprint 4:**

- 9 conflict-skipped PRs from Sprint 3 (NSA#8270, NSA#2244, NSA#5593, NSA#3974, NSA#3687, NSA#3137, NSA#8635, NSA#6390, NSA#8815) — hand-resolve.
- NSA#6897 (BSim address-space id) — still deferred from Sprint 2 (see [DD-017](DesignDecisions.md#dd-017-defer-nsa6897-bsim-hand-port-2026-05-21-deferred)).
- 3 remaining largest crossref matches (NSA#3315 enum-name collision, NSA#9036 e200 VLE PowerPC language, NSA#9107 PDiff Version Tracking, NSA#6596 AVR8 rcall, NSA#9112 XMOS processor) — multi-commit or 1500+ LOC.
- Begin Sprint 4 implementation surface for the 42-rec design docs from Sprint 1.

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

- [PR #112](https://codeberg.org/CryptoJones/GayHydra/pulls/112) — followup fixing the C++ side of PR #111's ID collision (`Edit` tool silently failed mid-resolve; caught and fixed in the same session).

**Carried into Sprint 3:**

- [NSA#6897](https://github.com/NationalSecurityAgency/ghidra/pull/6897) (BSim address-space id) — patch-apply hit two structural drifts (`ElasticDatabase.java` content conflict + `BSimServerTest.java` moved/renamed in our master). Needs hand-port against current state.
- ~47 remaining PR↔issue matches in [the crossref report](docs/upstream-tracking/pr-issue-matches.md) that I didn't tackle this sprint — mostly 0-upvote issues but still real fixes.

---

## Sprint 1 — 42-Rec Principal-Architect Audit (delivered 2026-05-21)

**Goal:** Implement the entire 42-recommendation principal-architect
audit ([`Ghidra.MD`](Ghidra.MD)) as either a working artifact (CI
workflow, gradle plugin, fuzz harness, config file, regression test)
or a written design/decision/RFC document.

**Released as:** [GayHydra v26.1 — "the 42-rec audit"](https://codeberg.org/CryptoJones/GayHydra/releases/tag/v26.1).

**Delivered:**

- [42 audit recommendations](CHANGELOG.md#261--2026-05-21--the-42-rec-audit) as artifacts + plans. Every rec has a deliverable file linked from the README's checklist.
- [Quality-pass PR (#54)](https://codeberg.org/CryptoJones/GayHydra/pulls/54) deepening the first 10 docs after the model thinking level was raised.
- [SECURITY.md upstream PR opened against NSA](https://github.com/NationalSecurityAgency/ghidra/pull/9202) (Rec 11 — the easiest landable upstream win).
- Release-v26.1 PR (#89), CHANGELOG.md, README progress checklist with all 42 boxes ticked, `application.name=GayHydra`, `application.version=26.1`.

**Carried into Sprint 3+:** The audit recs ship the *design surface*;
the *implementation surface* (the sub-PRs each design doc enumerates)
is sequenced across multiple future sprints. See
[SprintPlanning.md](SprintPlanning.md) for the breakdown.

---

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
