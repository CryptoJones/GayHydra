# PR Queue Policy

*Addresses Rec 01 of the 2026-05-21 principal-architect audit.*

## Problem

Upstream Ghidra carries ~335 open PRs; 77% (257) have never received a single
maintainer comment, 67% sit untouched under the `Status: Triage` label, and
~76 are more than four years old. Each silent PR is a small breach of trust
with a contributor who showed up.

This document declares how this fork (GayHydra) handles its PR queue. It is
the only document that decides whether a PR sits or moves.

## Principles

1. **No silent rejection.** Every PR receives a human first-response within
   the [Triage SLA](TRIAGE_SLA.md). "Rejected, here's why" counts.
2. **Lanes, not a single queue.** Reviews are structurally different across
   work types. A processor port and an IPC protocol change cannot be reviewed
   by the same person on the same checklist.
3. **A clean queue is honest.** A queue that has grown larger than the team
   can review is theater. Stale PRs are closed with a respectful, scripted
   template (see [STALE_POLICY.md](STALE_POLICY.md)).
4. **Big work needs a design first.** PRs above the mega-PR threshold
   (>2,000 net LOC or touching cross-module interfaces) require an
   [RFC](RFC_PROCESS.md) merged first.

## Lanes

| Lane | Trigger | Reviewer pool | Checklist |
|---|---|---|---|
| Processor / Sleigh | New or modified `Ghidra/Processors/<arch>/` | Processor maintainers (see `Ghidra/Processors/MAINTAINERS.md`) | [PROCESSOR_LANE.md](lanes/PROCESSOR_LANE.md) |
| Decompiler correctness | Changes under `Features/Decompiler/src/decompile/cpp/` that fix a wrong-output bug | Decompiler maintainers | [DECOMPILER_CORRECTNESS_LANE.md](lanes/DECOMPILER_CORRECTNESS_LANE.md) |
| Framework | Anything else | Core maintainers | Standard review checklist (TBD) |
| Mega-PR (>2k LOC) | Auto-detected by size label | RFC author + 2 core maintainers | Requires merged RFC |

Each PR is auto-labelled with one (and only one) of these lanes by a
GitHub Action. Default if rules don't match is `Framework`.

## SLA

See [TRIAGE_SLA.md](TRIAGE_SLA.md). Briefly: first human response within
**10 business days**.

## Bulk-close of the existing graveyard

PRs older than 4 years and without a maintainer comment are closed in one
sweep using the [stale workflow](../../.github/workflows/stale.yml) and the
[STALE_POLICY.md](STALE_POLICY.md) template. The close message is the
same for every PR; it is not personal, and it invites resubmission against
current `master` under the appropriate lane.

## Metrics

Reported weekly in the public maintainers' digest:

- Open PR count.
- p50 / p90 days to first human response.
- Count of PRs missing the lane label.
- Count of PRs sitting >30 days in `awaiting-maintainer`.

When p90 days-to-first-response exceeds the SLA, no new feature work lands
until the SLA is met again. The queue is the customer.
