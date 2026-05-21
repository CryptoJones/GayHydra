# Triage SLA

*Addresses Rec 02 of the 2026-05-21 principal-architect audit.*

## The commitment

Every issue and every pull request opened against this fork receives a
**human first-response within 10 business days** of creation.

"First response" must be one of:

| Outcome | What it looks like |
|---|---|
| `accepted-for-review` | A maintainer has labelled the PR with its lane and added it to the active review queue. |
| `needs-changes` | A maintainer has reviewed and requested specific, named changes. |
| `needs-rfc` | The change exceeds the mega-PR threshold; an [RFC](RFC_PROCESS.md) is required before further review. |
| `wont-do` | A maintainer has explained, in writing, why this won't be accepted. |
| `duplicate` | Linked to the canonical issue/PR. |
| `needs-info` | A specific question has been asked of the author. Resets the clock once the author replies. |

What is **not** a first-response: a bot comment, a label change without
explanation, or silence.

### Anti-gaming for `needs-info`

The clock-reset on `needs-info` is a known abuse vector: ping the
author with progressively narrower questions to keep the SLA green
without ever doing the review. Two safeguards:

1. **The question must be answerable from the PR.** "What is your
   threat model?" on a one-line patch is not a `needs-info`; it's
   stalling.
2. **Repeat `needs-info` is tracked.** If an item cycles through
   `needs-info` more than twice without a substantive review comment,
   the next maintainer to touch it must either move it to
   `accepted-for-review` or `wont-do`. Cycling further is a recorded
   process miss in the weekly digest.

### Why 10 business days

10 is calibrated against three constraints: it has to be small
enough that a contributor feels seen (research on contributor churn
suggests >2 weeks is where most one-time contributors give up), large
enough that maintainers can absorb a normal incoming volume without
heroics, and a round-number commitment that survives translation
across timezones and team configurations. Faster (3–5 days) is the
right SLA for the [decompiler-correctness lane](lanes/DECOMPILER_CORRECTNESS_LANE.md);
slower (20+ days) ratifies the upstream pathology.

The number is reviewable. If the dashboard shows we routinely land at
p90 = 3 days, we tighten. If we cannot meet 10, the right answer is
to add reviewer capacity or close the lane — not to widen the SLA.

## Why a stated SLA matters

Stating an SLA does two things the implicit "we'll get to it" cannot:

1. **It makes failure measurable.** If we miss the SLA on a PR, that is a
   defect with a name and a date, not a vibe.
2. **It is honest to contributors.** A 10-day "no" is infinitely better than
   a 4-year silence. A contributor who is told "we can't take this" can move
   on; a contributor who is ignored cannot.

## Where this clock runs

The SLA timer starts when an issue or PR is opened. It stops on first
human response. If the response is `needs-info`, the timer restarts when
the author replies.

A nightly GitHub Action publishes the current SLA dashboard:

- Count of items past SLA.
- p50 / p90 hours to first response (7-day rolling).
- Per-lane breakdown.

The dashboard is committed to `docs/governance/sla-dashboard.md` and is
the public record. When p90 exceeds the SLA for two consecutive weeks,
the maintainers' meeting agenda is rewritten to put queue health first
until the SLA is met again.

## When we will miss it

Holidays, conference weeks, security embargoes. These are announced in
advance in `docs/governance/sla-blackouts.md`; during a blackout the
clock pauses for everyone equally. The blackout list is short and
public — not a license to drift.

### Security-embargoed items

Items reported via [SECURITY.md](../../SECURITY.md) run on the
embargo-specific timing in that policy (Critical: 2 business days
first response, etc.). The general 10-business-day SLA does not
override the security-specific commitments and is not a backstop for
them — a security report must hit its own faster SLA or it is a
specific, surfaced security-process miss.

## Escalation

If a PR or issue passes the SLA without a first response, anyone may
comment `@maintainers SLA` on the thread. That bumps the item into the
next maintainers' meeting agenda and adds the `sla-breach` label.

If a maintainer disagrees with this policy, they may propose a change
via an RFC. They may not silently ignore it. Silence on an item is not
a position; the SLA is what stops silence from becoming the position.
