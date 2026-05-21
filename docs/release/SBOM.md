# Software Bill of Materials (SBOM)

*Originally addressed Rec 21 of the 2026-05-21 principal-architect audit. The standalone CycloneDX-plugin implementation was removed in Sprint 7; the SBOM still ships, but produced by the upstream NSA generator and surfaced as a separate signed release asset.*

## What ships today

Every release publishes **two** copies of the SBOM:

1. **Inside the release zip** at `support/sbom/bom.json` — written by the upstream NSA SBOM generator at `gradle/support/sbom.gradle` as part of the distribution-assembly step.
2. **As a standalone signed release asset** at `<release_zip_basename>-bom.json` — extracted from the zip by `.github/workflows/release.yml`, Cosign-signed (keyless OIDC) the same way the zip is signed, and uploaded to the GitHub Release alongside `.sig` + `.crt`. PR [#230](https://github.com/CryptoJones/GayHydra/pull/230).

Both copies are byte-identical; the standalone asset just saves downstream supply-chain scanners the round-trip of pulling the full distribution to read 50 KB of JSON.

The upstream generator walks every JAR in the distribution, pulls coordinates from `pom.xml` or `MANIFEST.MF`, and emits a CycloneDX-shaped JSON.

## Sanity gate

`release.yml` counts `.components[]` in the extracted SBOM after the extract step and fails the release if the count is < 10. PR [#233](https://github.com/CryptoJones/GayHydra/pull/233). This catches the silent-regression case where the upstream generator's JAR-walker stops finding components (a Gradle config rename, a packaging-step reorder, etc.) so we don't ship an empty SBOM that downstream scanners would happily ingest as "this build has no dependencies, scan clean!".

## What was removed (Sprint 7)

Rec 21 originally added the [`org.cyclonedx.bom`](https://github.com/CycloneDX/cyclonedx-gradle-plugin) Gradle plugin (v1.10.0) as a separate, top-level SBOM path. It generated `build/reports/sbom/bom.{json,xml}` outside the dist zip and Cosign-signed both as separate release artifacts.

That plugin path is gone (PR [#220](https://github.com/CryptoJones/GayHydra/pull/220)):

- The plugin declaration in `build.gradle` is commented out with the reason.
- `gradle/sbom.gradle` (the plugin's config + the `sbomSanityCheck` gradle task) was deleted.
- The `cyclonedxBom`-dependent steps in `.github/workflows/release.yml` were removed.

**Why removed:** the plugin NPEs on Ghidra's flat-dir dependencies (`:AXMLPrinter2:` and similar — JARs declared via `flatDir` repository with no Maven coordinates). The plugin needs a group + name + version on every dependency to derive a PackageURL; flat-dir deps have only a name. Setting placeholder coords on the root project (PR [#217](https://github.com/CryptoJones/GayHydra/pull/217)) got past the first failure but the plugin then NPEs on each flat-dir entry without a clean way to skip them in the 1.10.0 API.

See [`DesignDecisions.md` DD-019](../../DesignDecisions.md#dd-019) for the full decision record + alternatives considered.

## Sprint 8 re-implementation options (the *full* CycloneDX path)

The bundled-extract path above is a working SBOM that ships today and is signed. It satisfies the practical requirement (verifiable, sanity-gated, downloadable as a separate asset). What it does NOT do is emit a strict CycloneDX-shape XML, or have plugin-grade per-dependency PURLs. If the fork ever needs those, Sprint 8 picks one of:

1. **Upgrade the plugin** — newer cyclonedx-gradle-plugin versions (1.11+) may handle flat-dir deps; verify before adopting by running a test build against a tree that exercises flat-dir.
2. **Switch SBOM generators** — alternatives like Microsoft's `sbom-tool` or the OSSF `scorecard` SBOM emitter can be invoked as workflow steps without a plugin contract; their input is just the on-disk JAR tree.
3. **Extend the upstream generator** — `gradle/support/sbom.gradle` already produces a usable JSON SBOM; extending it to emit XML alongside is a Groovy change in that file (which is the upstream version — touch carries merge cost forever after).

Whichever path is chosen, the post-PR-#230 requirements are:

- Standalone signed SBOM as a release artifact ✓ (already shipped via extract).
- CycloneDX-shape output ✓ (upstream generator emits CycloneDX-shaped JSON).
- Sanity gate ≥10 components ✓ (in `release.yml`).
- Compatible with Ghidra's flat-dir dep layout ✓ (upstream generator parses JARs directly).

So Sprint 8's bar is now "add XML output" or "stricter per-dep PURLs", not "ship an SBOM at all."

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
