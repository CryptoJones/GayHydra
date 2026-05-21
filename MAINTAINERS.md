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
| (open) | Fork lead | |

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
| (open) | |

## Recognising community contributors

The audit identified `jobermayr`, `astrelsky`, `LukeSerne`, `nneonneo` as
community contributors doing unpaid triage in upstream Ghidra. This fork
recognises that the same dynamic exists wherever a project is large and
under-resourced. The promotion path above is the project's way of making
that work visible and giving it weight.

## Removing a maintainer

A maintainer who has not commented, reviewed, or merged in 180 days is
moved to `emeritus`. This is not punitive — it is honest. Coming back is
one PR to add the name back.

## Updating this file

Add yourself (or someone else) via PR. Approval requires one existing
core maintainer's yes. New areas are added as the codebase grows.
