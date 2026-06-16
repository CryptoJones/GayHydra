# Software Bill of Materials (SBOM)

*Originally addressed Rec 21 of the 2026-05-21 principal-architect audit. There are two SBOMs in the tree today: the **release SBOM** shipped to users (produced by the upstream NSA generator, surfaced as a separate signed release asset) and a build-local **CycloneDX-plugin SBOM** (`org.cyclonedx.bom`, reverted in Sprint 7 and re-added in Sprint 8 at 3.2.4). The release pipeline ships the former; see "CycloneDX plugin" below for the latter.*

## What ships today

Every release publishes **two** copies of the SBOM:

1. **Inside the release zip** at `support/sbom/bom.json` — written by the upstream NSA SBOM generator at `gradle/support/sbom.gradle` as part of the distribution-assembly step.
2. **As a standalone signed release asset** at `<release_zip_basename>-bom.json` — extracted from the zip by `.github/workflows/release.yml`, Cosign-signed (keyless OIDC) the same way the zip is signed, and uploaded to the GitHub Release alongside `.sig` + `.crt`. PR [#230](https://github.com/CryptoJones/GayHydra/pull/230).

Both copies are byte-identical; the standalone asset just saves downstream supply-chain scanners the round-trip of pulling the full distribution to read 50 KB of JSON.

The upstream generator walks every JAR in the distribution, pulls coordinates from `pom.xml` or `MANIFEST.MF`, and emits a CycloneDX-shaped JSON.

## Sanity gate

`release.yml` counts `.components[]` in the extracted SBOM after the extract step and fails the release if the count is < 10. PR [#233](https://github.com/CryptoJones/GayHydra/pull/233). This catches the silent-regression case where the upstream generator's JAR-walker stops finding components (a Gradle config rename, a packaging-step reorder, etc.) so we don't ship an empty SBOM that downstream scanners would happily ingest as "this build has no dependencies, scan clean!".

## CycloneDX plugin (reverted Sprint 7, re-added Sprint 8)

Rec 21 originally added the [`org.cyclonedx.bom`](https://github.com/CycloneDX/cyclonedx-gradle-plugin) Gradle plugin (v1.10.0) as a separate SBOM path generating `build/reports/sbom/bom.{json,xml}`.

**Reverted in Sprint 7** (PR [#220](https://github.com/CryptoJones/GayHydra/pull/220)) because v1.10.0 NPEs on Ghidra's flat-dir dependencies (`:AXMLPrinter2:` and similar — JARs declared via `flatDir` repository with no Maven coordinates). The plugin needs a group + name + version on every dependency to derive a PackageURL; flat-dir deps have only a name. Setting placeholder coords on the root project (PR [#217](https://github.com/CryptoJones/GayHydra/pull/217)) got past the first failure but the plugin then NPE'd on each flat-dir entry without a clean way to skip them in the 1.10.0 API.

**Re-added in Sprint 8.** The plugin is back, bumped to **3.2.4** — the 3.x rewrite uses `Configuration.getIncoming()` + a lenient `artifactView` that handles deps without resolvable POMs (the flat-dir case), so the original NPE no longer occurs. Current wiring:

- The plugin is declared in `build.gradle`'s `plugins {}` block (`id 'org.cyclonedx.bom' version '3.2.4'`); `gradle/sbom.gradle` configures the task + the `sbomSanityCheck` gate.
- The aggregate `cyclonedxBom` task writes `build/reports/sbom/bom.{json,xml}` (CycloneDX schema 1.6, both JSON and XML), guarded behind `plugins.hasPlugin('org.cyclonedx.bom')` so the stripped-down `dependency-submission` action doesn't trip over it.
- `buildGhidra` `dependsOn 'cyclonedxBom'`, which is `finalizedBy 'sbomSanityCheck'` (≥10 components, else the build fails).
- The per-subproject `cyclonedxDirectBom` tasks are disabled — they'd write into the tree-walked dist directories and break `:assembleDistribution` with an undeclared-input error; only the root aggregate is wanted.
- The `org.cyclonedx.Version` / `Component$Type` enums are loaded via the plugin instance's classloader (an `apply from:` script can't `import` them — see the comments in `gradle/sbom.gradle`).

**Relationship to the release SBOM:** these are two distinct artifacts. The plugin SBOM under `build/reports/sbom/` is build-local — `release.yml` does **not** ship or sign it. The release-shipped, signed standalone asset is still the in-zip SBOM from the upstream generator (the "What ships today" path above). The release pipeline keeps its own Python sanity gate (rather than reusing `sbomSanityCheck`) because it gates the extracted in-zip SBOM, not the plugin's `build/reports/` output.

See [`DesignDecisions.md` DD-019](../../DesignDecisions.md#dd-019) for the full decision record + alternatives considered.

## Verification

For a downloaded standalone SBOM asset (`<base>-bom.json`):

```sh
# 1. Cosign-verify the SBOM signature
cosign verify-blob \
  --certificate <base>-bom.json.crt \
  --signature   <base>-bom.json.sig \
  --certificate-identity-regexp 'https://github.com/CryptoJones/GayHydra/' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  <base>-bom.json

# 2. Inspect components
jq '.components | length' <base>-bom.json
jq '.components[] | .name' <base>-bom.json
```

Or fall back to the in-zip copy if the standalone asset hasn't been generated yet (releases pre-#230):

```sh
unzip -p <release_zip> '*/support/sbom/bom.json' | jq '.components | length'
```

The release zip is also Cosign-signed via [Rec 17 BINARY_SIGNING](../security/BINARY_SIGNING.md); trust in the in-zip SBOM derives from trust in the zip signature.

## Coordination with vulnerability response

When the fork ships a CVE fix ([Rec 12](../security/CVE_POLICY.md)), the advisory references the affected version range and the SBOM tells the downstream scanner which JAR moved. The two artifacts compose — `.components[].name` keys in the SBOM are stable, so a Dependabot-style scanner can map a CVE to a specific JAR coordinate without unzipping anything.
