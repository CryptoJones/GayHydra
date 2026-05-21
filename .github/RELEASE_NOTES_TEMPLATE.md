<!--
GayHydra release notes template.

Drop this into the "Generate release notes" GitHub UI, or paste it
into the body of `gh release create ... --notes "$(cat .github/RELEASE_NOTES_TEMPLATE.md)"`.

The verification block below is permanent and should remain on
every release. Customize the headline + highlights per release.
-->

# GayHydra vXX.Y — _short codename_

**Short summary, 1–2 sentences.** Forked from
[NSA/ghidra@<sha>](https://github.com/NationalSecurityAgency/ghidra/commit/<sha>)
(upstream <version>).

See [`CHANGELOG.md`](https://codeberg.org/CryptoJones/GayHydra/src/branch/vXX.Y/CHANGELOG.md) for the per-PR breakdown.

## Highlights

- _bullet list_

## Breaking changes vs upstream Ghidra <version>

_e.g._ **None.** Or, _if any:_ name + migration guidance.

## Compatibility

JDK ?, Gradle ?, Python ? — list anything that changed since the previous release.

## Verifying this release (Cosign keyless, Rec 17)

Every release artifact in this section is signed by the GayHydra release workflow's GitHub Actions OIDC identity. The signing transparency record lives in the public Sigstore Rekor log.

**Install Cosign** ([instructions](https://docs.sigstore.dev/cosign/system_config/installation/)) and then:

```sh
# Pick the release zip from the assets below.
ZIP=ghidra_<version>_<release>_<date>.zip

cosign verify-blob \
    --certificate-identity-regexp \
        'https://github.com/CryptoJones/GayHydra/.github/workflows/release\.yml@.*' \
    --certificate-oidc-issuer 'https://token.actions.githubusercontent.com' \
    --signature  "$ZIP.sig" \
    --certificate "$ZIP.crt" \
    "$ZIP"
```

A line `Verified OK` confirms the artifact was produced and signed by this repo's release workflow at this tag. The same flow verifies the bundled CycloneDX SBOM:

```sh
cosign verify-blob \
    --certificate-identity-regexp \
        'https://github.com/CryptoJones/GayHydra/.github/workflows/release\.yml@.*' \
    --certificate-oidc-issuer 'https://token.actions.githubusercontent.com' \
    --signature  bom.json.sig \
    --certificate bom.json.crt \
    bom.json
```

See [`docs/security/BINARY_SIGNING.md`](https://codeberg.org/CryptoJones/GayHydra/src/branch/vXX.Y/docs/security/BINARY_SIGNING.md) for the threat model and the rollout history.

## Known limitations

_list anything sprint-planning is still tackling for the next release_

---

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
