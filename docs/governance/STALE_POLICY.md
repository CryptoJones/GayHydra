# Stale PR / Issue Policy

*Addresses Rec 03 of the 2026-05-21 principal-architect audit.*

## The problem we are closing

Upstream Ghidra carries ~76 PRs older than 4 years and ~257 PRs that have
never received a single maintainer comment. A queue this depth stops being
a queue and becomes a moral failure mode: every entry is a promise the
project cannot keep.

This fork closes that door. The queue is allowed to be small and honest
even if that means closing PRs we wish we could review.

## What counts as stale

A PR or issue is **stale** when *all* of these are true:

- It has had no commit, comment, or label change for **365 days**.
- It is not labelled `pinned`, `security-embargoed`, or `needs-rfc`.
- It is not blocked on an open RFC.

A PR with a maintainer comment is not stale by silence alone. The clock
resets on each new commit, comment, or label change.

## What happens to stale items

1. **Day 335.** The `stale.yml` workflow posts a heads-up comment on the
   item and adds the `stale-warning` label. The author has 30 days to
   respond.
2. **Day 365.** If no response, the workflow closes the item with the
   template below and adds the `closed-stale` label.

A `closed-stale` item is not dead. The template invites the author to
reopen with a rebase against current `master` and a lane label. No moral
judgement is attached; one comment from a maintainer at any time will
remove the `stale-warning` label and reset the clock.

## The bulk-close

On adoption, all PRs older than **4 years without a single maintainer
comment** are closed in one sweep with the same template. This is not
done lazily — it is done once, with a public announcement, and never
again at the same scale. The point of the SLA + 365-day stale rule is
to make a future bulk-close unnecessary.

## The close template

```
Hi @{author},

Thank you for this contribution. We are not going to be able to review it
in its current form — it has been open for {age_days} days without a
maintainer response, and that silence is on us, not on you.

We are closing this PR as part of our stale-PR policy
(docs/governance/STALE_POLICY.md). The history is preserved; nothing is
deleted.

If you are still interested in landing this work, please:

  1. Rebase against current `master`.
  2. Reopen under the {lane} lane — see docs/governance/PR_QUEUE_POLICY.md.
  3. If the change exceeds 2,000 net LOC or crosses module boundaries, an
     RFC is required first (docs/governance/RFC_PROCESS.md).

We are sorry for the wait. The new triage SLA
(docs/governance/TRIAGE_SLA.md) commits us to a 10-business-day first
response on reopened work.
```

## Exemptions

- `pinned` — a maintainer has explicitly marked the item to never expire.
- `security-embargoed` — the item is under an embargo (see
  [SECURITY.md](../../SECURITY.md)). Stale clock pauses until embargo lifts.
- `needs-rfc` — the item is blocked on an unmerged RFC. Stale clock pauses
  until the RFC merges or is rejected.

## What this is not

This policy is not a way to clear the queue without engaging with it. The
SLA commits us to engaging within 10 business days; the stale policy
commits us to acknowledging when, despite our best effort, an item has
fallen through. These two policies are a pair.
