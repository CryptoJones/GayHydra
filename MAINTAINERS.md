# Maintainers

*Addresses Rec 07 of the 2026-05-21 principal-architect audit.*

This file makes the project's bus factor explicit. Every area of the
codebase has a named maintainer; if it doesn't, it's listed as
`orphaned` and that is its own kind of useful information.

The list is descriptive of who currently does the work, not a permission
hierarchy. The Triage SLA and Stale Policy bind every maintainer equally.

## Roles

- **Core maintainer.** Has commit, can merge, reviews against the
  framework lane. Named in this file.
- **Area maintainer.** Has commit *within an area*, reviews against the
  matching lane (processor, decompiler, debugger). Named in this file
  next to the area.
- **Triage contributor.** Has no commit, but actively triages issues and
  reviews PRs. Recognised here. Promotion path to area maintainer is via
  a maintainer's nomination at a maintainers' meeting; no vote, just one
  recorded yes from an existing maintainer of the area.

## Current maintainers

> This list is initial-state for the GayHydra fork. Upstream Ghidra's
> bus factor (as identified in the audit) concentrates on ~9 NSA-affiliated
> logins; that observation is theirs to formalize. Below is the structure
> this fork uses; names are added by PR as people opt in.

### Core

| Login | Role | Areas |
|---|---|---|
| Aaron K. Clark ([CryptoJones](https://github.com/CryptoJones), <aaron.clark@milcyber.org>) | Fork lead | all (interim, recruiting) |

### Decompiler (`Ghidra/Features/Decompiler/`)

| Login | Role |
|---|---|
| (open) | C++ decompiler |
| (open) | Sleigh runtime |

### Processors (`Ghidra/Processors/<arch>/`)

See `Ghidra/Processors/MAINTAINERS.md` ([Rec 41](Ghidra/Processors/MAINTAINERS.md))
for the per-architecture table.

### Debugger (`Ghidra/Debug/`)

| Login | Role |
|---|---|
| (open) | Debugger |

### Framework (`Ghidra/Framework/`)

| Login | Role |
|---|---|
| (open) | Framework / DB |
| (open) | Framework / Gui |
| (open) | Framework / Project |

### Build, CI, release (`gradle/`, `.github/`)

| Login | Role |
|---|---|
| (open) | Build / release |

### Triage contributors

| Login | Areas they triage |
|---|---|
| [CryptoJones](https://github.com/CryptoJones) | all |

## Recognising community contributors

The audit identified `jobermayr`, `astrelsky`, `LukeSerne`, `nneonneo` as
community contributors doing unpaid triage in upstream Ghidra. This fork
recognises that the same dynamic exists wherever a project is large and
under-resourced. The promotion path above is the project's way of making
that work visible and giving it weight.

## Responsibilities

A maintainer commits to:

- **Triage rotation.** One week per quarter (minimum), being the on-call
  triager for incoming PRs in their lane. The rotation calendar is in
  `docs/governance/maintainer-rotation.md`.
- **First-response SLA.** Items routed to their lane receive a first
  response within 10 business days (see [TRIAGE_SLA.md](docs/governance/TRIAGE_SLA.md))
  or the 3-business-day correctness SLA, whichever applies.
- **Disclosure.** Maintainers disclose conflicts of interest in
  `docs/governance/maintainer-disclosures.md`: employer, related
  commercial projects, paid-for upstream work. The disclosure is
  public and updated whenever a relevant fact changes.
- **No back-channel merges.** Merging a PR via a private fork or
  out-of-band review is not allowed. The PR thread is the record.
- **Civility.** The Triage SLA, the stale policy, and every close
  template exist to honor contributors. Sniping at contributors is a
  bigger violation than missing an SLA.

A maintainer does *not* commit to:

- A minimum weekly hours figure. This project does not police
  attendance; it polices outcomes (SLA, response quality).
- Reviewing outside their stated area. The rotation routes; cross-area
  review is voluntary.

## Removing a maintainer

A maintainer who has not commented, reviewed, or merged in 180 days is
moved to `emeritus`. This is not punitive — it is honest. Coming back is
one PR to add the name back.

A maintainer may be removed for cause (a sustained violation of the
responsibilities above, escalating mistreatment of contributors, an
undisclosed conflict of interest). Removal-for-cause is by maintainer
consensus minus the maintainer in question, with the decision and
reasoning recorded publicly in the maintainers' meeting minutes.

## Updating this file

Add yourself (or someone else) via PR. Approval requires one existing
core maintainer's yes. New areas are added as the codebase grows.
