# Software Bill of Materials (SBOM)

*Originally addressed Rec 21 of the 2026-05-21 principal-architect audit. The standalone CycloneDX implementation was removed in Sprint 7 and is queued for Sprint 8 re-implementation — see notes below.*

## What ships today

Every release zip includes an SBOM produced by **upstream NSA Ghidra's own SBOM generator** at `gradle/support/sbom.gradle`, written into the zip at:

```
support/sbom/bom.json
```

The upstream generator walks every JAR in the distribution, pulls coordinates from `pom.xml` or `MANIFEST.MF`, and emits a CycloneDX-shaped JSON.

## What was removed (Sprint 7)

Rec 21 added the [`org.cyclonedx.bom`](https://github.com/CycloneDX/cyclonedx-gradle-plugin) Gradle plugin (v1.10.0) as a separate, top-level SBOM path. It generated `build/reports/sbom/bom.{json,xml}` outside the dist zip and was Cosign-signed as a separate release artifact.

That plugin path is gone:

- The plugin declaration in `build.gradle` is commented out with the reason.
- `gradle/sbom.gradle` (the plugin's config) was deleted.
- The `cyclonedxBom`-dependent steps in `.github/workflows/release.yml` were removed.

**Why removed:** the plugin NPEs on Ghidra's flat-dir dependencies (`:AXMLPrinter2:` and similar — JARs declared via `flatDir` repository with no Maven coordinates). The plugin needs a group + name + version on every dependency to derive a PackageURL; flat-dir deps have only a name. Setting placeholder coords on the root project (PR #217) got past the first failure but the plugin then NPEs on each flat-dir entry without a clean way to skip them in the 1.10.0 API.

## Sprint 8 re-implementation options

When the re-implementation lands, pick from:

1. **Upgrade the plugin** — newer cyclonedx-gradle-plugin versions (1.11+) may handle flat-dir deps; verify before adopting.
2. **Switch SBOM generators** — alternatives like Microsoft's `sbom-tool` or the OSSF `scorecard` SBOM emitter can be invoked as steps without a plugin contract.
3. **Extend the upstream generator** — `gradle/support/sbom.gradle` already produces a usable SBOM; extending it to emit XML, sign it separately, and ship as a standalone release artifact may be the simplest path. Trade-off: harder to keep in sync if upstream NSA evolves that file.

Whichever path is chosen, the requirements are:

- Standalone signed SBOM as a release artifact (not only the zip-bundled one).
- CycloneDX-shaped output (downstream consumers prefer it).
- Sanity gate: ≥10 components, fail build otherwise.
- Compatible with Ghidra's flat-dir dep layout (don't NPE on `:AXMLPrinter2:`).

## Verification (zip-bundled SBOM)

For now, downstream consumers verify by extracting the release zip and reading `support/sbom/bom.json` directly. The zip itself is Cosign-signed via [Rec 17 BINARY_SIGNING](../security/BINARY_SIGNING.md), so trust in the bundled SBOM derives from trust in the zip signature.

## Coordination with vulnerability response

Sprint 8's SBOM re-implementation should restore the explicit signed-SBOM-as-separate-artifact pattern so vulnerability scanners can pull the SBOM without unzipping the full distribution.
