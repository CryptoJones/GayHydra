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
   (>2,000 net LOC, or touching cross-module interfaces, or changing a
   security boundary) require an [RFC](RFC_PROCESS.md) merged first.

   *Why 2,000?* It is calibrated from the upstream pathology: the median
   stuck mega-PR (#4103 WASM, #5778 RISC-V, etc.) was 4,000–15,000 LOC.
   2,000 is the point above which line-by-line review stops being the
   right unit of work; below it, the queue absorbs the change without a
   design dance. The number is reviewable — if data shows we're catching
   the wrong PRs, the threshold moves via RFC, not folklore.

## Lanes

| Lane | Trigger | Reviewer pool | Checklist |
|---|---|---|---|
| Processor / Sleigh | New or modified `Ghidra/Processors/<arch>/` | Processor maintainers (see `Ghidra/Processors/MAINTAINERS.md`) | [PROCESSOR_LANE.md](lanes/PROCESSOR_LANE.md) |
| Decompiler correctness | Changes under `Features/Decompiler/src/decompile/cpp/` that fix a wrong-output bug | Decompiler maintainers | [DECOMPILER_CORRECTNESS_LANE.md](lanes/DECOMPILER_CORRECTNESS_LANE.md) |
| Framework | Anything else | Core maintainers | Standard review checklist (TBD) |
| Mega-PR (>2k LOC) | Auto-detected by size label | RFC author + 2 core maintainers | Requires merged RFC |

Each PR is auto-labelled with one (and only one) of these lanes by a
GitHub Action. Default if rules don't match is `Framework`.

> **Status (2026-06-11, later same day):** implemented —
> [`lane-labeler.yml`](../../.github/workflows/lane-labeler.yml) applies
> exactly one `lane:*` label by path rule on PR open/sync/reopen, and
> defers to a maintainer's re-lane (an existing single differing lane
> label is left alone). The decomp-correctness lane is a routing default
> — the policy scopes it to wrong-output fixes, which a path rule cannot
> read; re-lane as needed.

## SLA

See [TRIAGE_SLA.md](TRIAGE_SLA.md). Briefly: first human response within
**10 business days**.

The SLA covers the **first** response, not the full review. A PR that
moves to `triage:accepted-for-review` enters a second commitment:
**reviewer assigned within 10 business days, first round of review
within 20 business days of assignment**. These are tracked separately
on the queue-health dashboard so a "fast first response, then silence"
pattern shows up as its own failure mode rather than hiding inside an
SLA-green column.

## Bulk-close of the existing graveyard

PRs older than 4 years and without a maintainer comment are closed in one
sweep using the [stale workflow](../../.github/workflows/stale.yml) and the
[STALE_POLICY.md](STALE_POLICY.md) template. The close message is the
same for every PR; it is not personal, and it invites resubmission against
current `master` under the appropriate lane.

## Metrics

Reported weekly in the public maintainers' digest, committed to
`docs/governance/sla-dashboard.md` by a workflow:

> **Status (2026-06-11):** the dashboard workflow is not yet
> implemented — `sla-dashboard.md` does not exist, and the queue-health
> gate below is therefore not armed. Build the workflow or amend this
> policy before relying on either.

- Open PR count, broken down by lane.
- p50 / p90 days to first human response.
- p50 / p90 days from `triage:accepted-for-review` to first round of review.
- Median age of items in `triage:accepted-for-review` (catches the
  "accepted but never actually reviewed" failure mode).
- Count of PRs missing any `triage:*` label (SLA-breach candidates).
- Count of PRs in `triage:needs-info` >30 days (probable abandonment).
- Stale-warning and closed-stale counts (7-day delta).

## Queue health gate

When p90 days-to-first-response exceeds the SLA for two consecutive
weeks, the project enters **queue-health mode**:

- No new feature PRs from maintainers (bug fixes and correctness fixes
  exempt).
- The next maintainers' meeting agenda leads with queue health, not
  product.
- Maintainers cycle in on rotation to triage until p90 returns to SLA.

The gate is an honest commitment, not a moral position: a maintainer
who is shipping new features while the queue rots is making a
visibility-asymmetry trade against their own contributors. The
queue-health gate makes that trade impossible to make silently.

The queue is the customer.

