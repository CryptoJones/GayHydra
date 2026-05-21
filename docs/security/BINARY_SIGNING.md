# Decompiler Binary Signing

*Addresses Rec 17 of the 2026-05-21 principal-architect audit.*

## Why this matters

Ghidra ships a native executable (the C++ decompiler, ~3 MB per
platform) inside the release zip. Users put that binary on disk and
exec it every time they decompile a function. The release ships
without a signature, without an SBOM (Rec 21), and without a
documented verification path.

A user who downloads a release file and runs it has no way to know
they got the artifact our CI built. The build-Ghidra workflow
produces the binary; the release flow ships it; the connection
between those two stages is not currently auditable from the
outside.

## Decision

Sign the native decompiler binaries (and the release zip) with
[Sigstore Cosign](https://docs.sigstore.dev/), keyless mode, using
the GitHub Actions OIDC identity of the release workflow. Cosign
keyless signing:

- Requires no key custody by maintainers (no `release.key` to
  protect, lose, or steal).
- Records the build provenance in the public Sigstore transparency
  log (Rekor); a verifier can prove the binary was signed by *this
  repo's release workflow at this commit*.
- Is the current industry default for OSS release signing (used by
  Kubernetes, Tekton, Helm, Distroless, OCI artifacts, etc).

The release workflow signs:

1. Each per-platform native decompiler binary (`decompile.linux64`,
   `decompile.macos`, `decompile.windows.exe` — or however they
   end up named on disk).
2. The release zip.
3. The release SBOM (when [Rec 21](../release/SBOM.md) lands).

Signatures are uploaded as release assets alongside the binaries
they sign.

## Verification path (what the user does)

```
# Install cosign (one-time)
# https://docs.sigstore.dev/cosign/system_config/installation/

# Verify the release zip
cosign verify-blob \
    --certificate-identity-regexp \
        'https://github.com/NationalSecurityAgency/ghidra/.github/workflows/.*' \
    --certificate-oidc-issuer 'https://token.actions.githubusercontent.com' \
    --signature ghidra_<version>.zip.sig \
    --certificate ghidra_<version>.zip.crt \
    ghidra_<version>.zip
```

A green `Verified OK` line tells the user the zip was signed by an
identity registered as our release workflow. The `--certificate-identity-regexp`
pins the issuer (the workflow file on this repo); changing CI to
sign from a different workflow would fail this check.

For this fork (GayHydra), substitute `CryptoJones/GayHydra` for
`NationalSecurityAgency/ghidra` in the regexp.

The same flow verifies the native binaries individually. The
release notes link to this doc and show the exact command pre-filled
with the current version.

## Where this lives

- **`.github/workflows/release.yml`** (existing or new): adds a
  `cosign-sign` job that runs after artifacts are produced. Job is
  blocking — a release without successful signing does not publish.
- **`docs/security/BINARY_SIGNING.md`** (this file): the user-facing
  verification doc.
- **`.github/cosign/`** (new): an empty directory for future
  per-key configuration if we ever switch off keyless (we don't
  expect to).

## Threat model coverage

Signing closes the **release integrity** gap:

- A binary downloaded from anywhere other than our official release
  page can be verified against the same signature, because the
  signature is over the binary's content. A mirror can't lie.
- A future compromise of the release zip after publication is
  detectable: the published signature won't match.
- A user who pins `cosign verify-blob` into their automation gets
  an alert if anyone (including us) substitutes a binary they
  already trusted.

Signing does **not** close:

- Compromise of the build itself (a maliciously-introduced commit
  that gets built and signed legitimately). Mitigations for that
  are SLSA Level 3 attestations, which Cosign supports natively;
  out of scope for this rec, tracked as a follow-up.
- Compromise of the user's verification environment. Cosign trust
  bottoms out at the Sigstore root, which itself bottoms out at
  the user's trust in Sigstore's TUF setup.

## Why keyless

Comparing to key-managed signing (one human holds the private key,
signs each release):

| Property | Key-managed | Cosign keyless |
|---|---|---|
| Key custody risk | High | None |
| Bus-factor risk | Person leaves, key is lost | Workflow change is reviewable |
| Provenance audit trail | Manual | Public Rekor log |
| Setup cost | Low | Low (one workflow step) |
| Verifier UX | `gpg --verify` | `cosign verify-blob` |

Cosign keyless wins on every axis except verifier-UX familiarity,
and that gap is closing fast as Cosign becomes the default in the
OSS ecosystem.

## Rollout

1. **PR 1 (shipped in v26.1):** This doc. No CI changes yet.
2. **PR 2 (#17-2, shipped):** [`.github/workflows/release.yml`](../../.github/workflows/release.yml). Triggers on tag push or workflow_dispatch; builds Ghidra (so the release zip + SBOM are produced fresh), signs the zip + SBOM with cosign keyless using the GitHub Actions OIDC identity, and uploads zip + .sig + .crt + SBOM + SBOM .sig + SBOM .crt to the GitHub release.
3. **PR 3 (follow-up):** Verification commands in the release-notes template.
4. **PR 4 (follow-up):** Wire SLSA Level 3 attestations (`slsa-framework/slsa-github-generator`) for build provenance.

## Coordination with upstream

This is a per-fork decision; if upstream NSA/ghidra adopts a
different signing path, both can coexist. The Cosign verify command
pins the issuer to the signing repo, so users can verify whichever
artifact they downloaded.
