# CVE Assignment Policy

*Addresses Rec 12 of the 2026-05-21 principal-architect audit.*

## The gap this closes

Upstream Ghidra has shipped security fixes under internal tracker IDs
(`GP-6832`, `GP-6719`, `GP-258`, etc.) without public CVE IDs. Recent
examples called out in the audit:

- Path-traversal in username validation (`GP-6832`).
- Race in `writeUserList` (server-side, multi-user collaborative
  server).
- RMI deserialization filter addition (`GP-6719`).

Each of these would have deserved a CVE on a project the size and
reach of Ghidra. Without one, downstream packagers (Debian, Homebrew,
Arch AUR, the various enterprise repos), security teams running
scanners against installed software, and SOC/IR teams comparing
versions against advisories cannot patch what they cannot see.

The internal tracker ID is fine for an audit trail; it is not a
substitute for a public identifier.

## Rule: every in-scope security fix gets a CVE

A fix is **in scope** for a CVE if it satisfies any of the conditions
in [SECURITY.md](../../SECURITY.md) ("What gets a CVE"). The
overwhelming majority of fixes to loaders, the decompiler IPC, the
collaborative server, the RMI surface, and the deserialization paths
will satisfy this.

The default is **assign a CVE**. The exception is fixes for which a
CVE would be misleading: pure hardening with no demonstrated
exploit, refactors that close a class of bug rather than a specific
one, or work that has not yet reached a state where impact is
characterizable. Exceptions are recorded with a one-paragraph
rationale in `docs/security/no-cve-rationale.md`. Silence is not an
exception.

## Mechanism: GitHub Security Advisory CNA

This fork uses the GitHub Security Advisory pathway (`gh secadv`
or the web UI). GitHub is an approved CVE Numbering Authority and
will mint a CVE for any advisory we publish through that pathway.
No external coordination required for most cases.

Process for assignment:

1. **Triage.** When a security report comes in (see
   [SECURITY.md](../../SECURITY.md)), the assigned maintainer opens
   a draft advisory via `gh secadv` immediately, even before the
   fix is written. This reserves the advisory thread; the CVE is
   minted at publication time.
2. **Develop the fix.** Under embargo, on a private fork.
3. **Score.** Compute a CVSS 3.1 vector and base score. Record it
   in the advisory. The score is a description, not a vote.
4. **Publish.** Advisory goes public after the embargo window
   (default 90 days from fix availability, sometimes sooner with
   reporter consent).
5. **Cross-reference.** The internal `GP-NNNN` tracker ID is added
   to the advisory body. The mapping is published at
   `docs/security/gp-to-cve.md`.

## Coordination with upstream

For vulnerabilities that exist in both this fork and upstream
NSA/ghidra:

- The fork's CVE refers to the fork. If upstream issues their own
  CVE, both CVEs are cross-referenced in our advisory's
  `References:` section.
- If upstream does not issue a CVE (their policy is theirs), our
  advisory still mints one for users of this fork. We do not wait
  on upstream's choices.
- The advisory's "Affected products" lists this fork's
  package/repo coordinates and the affected version range. It
  does not claim to speak for upstream.

## Retroactive CVE assignment

Audit-identified upstream fixes that should have had CVEs but do
not (the three named above) are tracked in
`docs/security/retroactive-cve-tracking.md`. For each, we record:

- Upstream `GP-*` tracker ID and the commit hash.
- Our judgement of whether the issue applies to a build of this
  fork (depends on whether the affected code path has been
  changed here).
- If yes: we mint a CVE for our fork's range.
- If no: no CVE needed; the entry is closed with the reasoning.

We do not retroactively assign CVEs against upstream — that is
their CNA path, not ours. We only assign for our own
distribution.

## What the user sees

- Every release notes file (`Ghidra/Configurations/Public_Release/data/ChangeHistory.md`
  or its successor) names the CVEs fixed in that release with a
  one-line summary and a link to the advisory.
- The `CHANGELOG.md` at repo root carries the same list for the
  current development tip.
- A long-form security history is in `docs/security/advisories/`,
  one Markdown file per CVE, mirroring the GitHub advisory body so
  the history survives platform changes.

## Mapping table

| Internal tracker | Public CVE | First-fixed version | Severity (CVSS 3.1 base) |
|---|---|---|---|
| `GP-6832` (path traversal) | (TBD — open advisory) | (TBD) | (TBD) |
| `GP-6719` (RMI deser filter) | (TBD — open advisory) | (TBD) | (TBD) |
| `GP-258` (writeUserList race) | (TBD — open advisory) | (TBD) | (TBD) |

The table is updated by PR as advisories are minted. An empty row
is a TODO; an absent row is "we have not yet reviewed this internal
tracker entry for CVE eligibility." Either is a public signal.

## Why this policy matters

The asymmetry the audit identified is real: an enterprise SOC can
read NVD but cannot read NSA's internal tracker. A CVE is the
narrowest, most-machine-readable promise we can make to that SOC:
"we shipped a security fix; here is the identifier; here is the
version range; here is the score." Saying nothing — or saying
"see `GP-6832`" — leaves users without a way to act.
