# Security Policy

*Addresses Rec 11 of the 2026-05-21 principal-architect audit.*

## Scope

GayHydra (like its upstream Ghidra) parses adversary-controlled binaries
and ships a network server. That makes the project a security artifact,
not just a tool. This document specifies how to report a vulnerability
in this fork (GayHydra), how we will respond, and what gets a CVE.

The scope of this policy is the GayHydra fork. For upstream
NSA/ghidra, follow [their advisories](https://github.com/NationalSecurityAgency/ghidra/security/advisories).

## Reporting a vulnerability

**Please do not file a public issue or PR for a suspected vulnerability.**

Use one of the following private channels, in order of preference:

1. **GitHub private vulnerability report**:
   <https://github.com/CryptoJones/GayHydra/security/advisories/new>.
   This is the preferred channel because it keeps the entire embargo,
   patch, and disclosure thread in one place, and it's how we mint
   public CVEs (see [CVE_POLICY.md](docs/security/CVE_POLICY.md)).
   The fork is also mirrored to Codeberg, but security tooling stays
   on GitHub for the CNA path.
2. **Email** to the address listed at <https://github.com/CryptoJones>
   (PGP key fingerprint published there). Mark the subject line
   `[GayHydra security]` to route correctly.

Include in your report:

- A description of the vulnerability.
- Steps to reproduce (binary, headless invocation, or scripted test
  case). A minimal reproducer is the single most useful thing you can
  send.
- The version (`git describe`) you tested against.
- Your assessment of impact (RCE, info-disclosure, DoS, etc).
- Whether you have already disclosed this to upstream NSA/ghidra. If
  yes, share the upstream advisory ID so we can align embargo timing.
  If a vulnerability exists in upstream as well as this fork, we
  coordinate disclosure with upstream and do not publish ahead of
  their advisory — coordinated disclosure is the default, not the
  exception.
- How you would like to be credited in the advisory.

## What gets a CVE

A vulnerability gets a CVE if **any** of the following are true:

- It is reachable from adversary-controlled binary input (any
  loader: ELF, PE, Mach-O, DEX, PDB, DWARF, COFF, archive).
- It is reachable across a network boundary (Ghidra server, RMI,
  collaborative server).
- It allows code execution outside a documented sandbox.
- It allows information disclosure of data the user has marked
  sensitive (project notes, analysis state, source code).

CVE IDs are assigned via the GitHub Security Advisory CNA pathway. See
[CVE_POLICY.md](docs/security/CVE_POLICY.md) ([Rec 12](docs/security/CVE_POLICY.md))
for the full assignment policy and the mapping from internal `GP-*`
tracker IDs to public CVEs.

## Response targets

| Severity | First response | Patch ETA shared | Public advisory |
|---|---|---|---|
| Critical (RCE, network) | 2 business days | 5 business days | 30 days after fix is available |
| High (RCE, local) | 5 business days | 14 days | 60 days |
| Medium (info-disclosure, DoS) | 10 business days | 30 days | 90 days |
| Low | 15 business days | best effort | best effort |

These are targets, not guarantees. If we cannot meet them on a specific
report, we tell you in writing before we miss them, with a revised ETA
and the reason.

## Embargo

We honour reporter-requested embargo windows up to **90 days** from the
date a fix is available. We do not extend embargoes past 90 days; this
gives downstream packagers a known schedule and keeps the project from
sitting on patches.

During embargo:

- The advisory thread is private.
- The fix may be developed under a private fork; the merged commit may
  be pushed to `master` only at the end of embargo (or, with reporter
  consent, sooner — sometimes the fix is obviously a fix and silently
  publishing it gives users the most protection fastest).
- Reporters are kept informed of the patch and release timeline.

## Hall of fame

Reporters who responsibly disclose are credited in:

- The published advisory (with reporter consent).
- `docs/security/CREDITS.md` (created on first credit; opt-in).

## The threat model in one paragraph

GayHydra is run by a security-aware user against a binary the user
does not trust. The user's environment (the host OS, the user's files,
the user's network) is in scope to protect. The binary being analyzed
is the *adversary*: anything that binary causes GayHydra to do —
outside of "be disassembled, decompiled, and shown on screen" — is a
vulnerability we want to know about. The Ghidra server (the upstream
networked-collaboration component, name preserved), when run, exposes
additional network-reachable surface; everything that crosses that
boundary from an untrusted client is in scope.

## What is in scope for "security"

- Crash, OOM, infinite-loop, stack-overflow on adversary-controlled input.
- Information disclosure from the local user's projects or environment.
- Code execution from any loader path, script path, or extension path.
- Network protocol weaknesses in the Ghidra server / RMI / collaborative
  server.
- Java deserialization vectors (see [JAVA_DESERIALIZATION_AUDIT.md](docs/security/JAVA_DESERIALIZATION_AUDIT.md),
  Rec 19).
- A trusted-script-only / sandbox mode (see [SCRIPT_SANDBOX.md](docs/security/SCRIPT_SANDBOX.md),
  Rec 16) that fails to gate untrusted scripts when configured to.

## What is out of scope

- Slow performance on degenerate-but-honest input. File a perf bug, not
  an advisory.
- "GayHydra disassembled my malware and now I have malware on my screen."
  That is the tool working.
- Behaviour reproducible only by modifying GayHydra itself. We are not
  in the business of defending against ourselves; we are in the
  business of defending the user from the binary the user is reverse
  engineering.
- User-authored scripts running outside sandbox mode. Scripts in
  `~/ghidra_scripts/` and the project script paths are trusted by
  design; if a user runs a malicious script, that is the same as
  running any other untrusted program on their machine.
- Vulnerabilities in third-party extensions distributed outside
  this repo. Report those to the extension's maintainer.

## Related

- [CVE_POLICY.md](docs/security/CVE_POLICY.md) — Rec 12.
- [JAVA_DESERIALIZATION_AUDIT.md](docs/security/JAVA_DESERIALIZATION_AUDIT.md) — Rec 19.
- [BINARY_SIGNING.md](docs/security/BINARY_SIGNING.md) — Rec 17.
