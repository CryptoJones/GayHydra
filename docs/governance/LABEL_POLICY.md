# Label Policy

*Addresses Rec 08 of the 2026-05-21 principal-architect audit.*

## The problem this replaces

Upstream Ghidra has 184 issues and 226 PRs sitting under `Status: Triage`.
The label originally meant "we will look at this." It now means "we have
not." It is the single most-applied label and the single least-meaningful
label.

This fork retires `Status: Triage` entirely. Every label in the table
below is wired to a state — never a holding pattern.

## The label set

### Lane (exactly one, auto-applied)

| Label | Trigger | Priority |
|---|---|---|
| `lane:security` | Reported via SECURITY.md or matches security keywords | 1 (highest) |
| `lane:decomp-correctness` | Wrong-output decompiler fix (see [DECOMPILER_CORRECTNESS_LANE.md](lanes/DECOMPILER_CORRECTNESS_LANE.md)) | 2 |
| `lane:rfc` | Diff under `docs/rfcs/` | 3 |
| `lane:processor` | Diff under `Ghidra/Processors/` (see [PROCESSOR_LANE.md](lanes/PROCESSOR_LANE.md)) | 4 |
| `lane:debugger` | Diff under `Ghidra/Debug/` | 5 |
| `lane:framework` | Default if nothing else matches | 6 (lowest) |

A PR that satisfies multiple lane triggers takes the **highest-priority**
lane only. A security-impacting processor change is `lane:security`,
not `lane:processor` — even though both directories are touched, the
review pool and the SLA come from the security side.

A maintainer can manually override the auto-applied lane label with a
short comment naming why; the override is logged on the dashboard.

### Triage state (exactly one, applied by a human)

| Label | Meaning | SLA |
|---|---|---|
| `triage:accepted-for-review` | A maintainer has read it and put it in the queue | Reviewer assigned within 10 business days |
| `triage:needs-changes` | Specific changes requested in-thread | Author's turn |
| `triage:needs-info` | A specific question pending an author answer | Author's turn; SLA clock pauses |
| `triage:needs-rfc` | Exceeds mega-PR threshold (see [RFC_PROCESS.md](RFC_PROCESS.md)) | RFC must merge first |
| `triage:wont-do` | Closed with a written reason | — |
| `triage:duplicate` | Linked to canonical issue/PR; closed | — |

Notably absent: `Status: Triage`. An item that has been seen has one of
the labels above. An item that has not been seen has none of them, and
shows up on the SLA dashboard as a breach if older than 10 business days.

### Stale machinery (auto, see [STALE_POLICY.md](STALE_POLICY.md))

| Label | Meaning |
|---|---|
| `stale-warning` | 335 days no activity, 30-day grace before close |
| `closed-stale` | Closed by the stale workflow; comment to reopen |
| `pinned` | Exempt from stale (manual; needs a maintainer's nominated reason in the description) |
| `security-embargoed` | Exempt from stale until embargo lifts |

### Severity (for issues)

| Label | Meaning |
|---|---|
| `severity:wrong-output` | Decompiler/analyzer produces incorrect result |
| `severity:crash` | Crashes Ghidra or a subprocess |
| `severity:security` | Reachable from adversary-controlled input |
| `severity:perf` | Unbounded time/memory on bounded input |
| `severity:papercut` | UX/quality-of-life |

### Community

| Label | Meaning |
|---|---|
| `good-first-issue` | Small, well-scoped, owned by no one else, has acceptance criteria |
| `help-wanted` | Maintainers have decided this is wanted but lack the bandwidth |

### `@Ignore`-test deadlines (Rec 28, see [IGNORE_TEST_POLICY.md](../testing/IGNORE_TEST_POLICY.md))

Applied to the issue an `@Ignore("category: … #N")` annotation in a Java test points at. After the deadline the test is fixed or deleted — there is no fourth option.

| Label | Meaning |
|---|---|
| `ignore:30d` | Linked `@Ignore`'d test must be fixed or deleted within 30 days |
| `ignore:90d` | Same, 90 days — e.g. flaky tests with a sprint-scale fix window |
| `ignore:1y` | Same, 1 year — long-running upstream blockers, cluster-cleanup work |

`gradle ignoreAudit` Stage 2 will gate on these (the audit task already enforces the in-tree `@Ignore("category: … #N")` form; Stage 2 adds the via-GitHub-API deadline check).

## Migration from the upstream label set

| Upstream label | Action in this fork |
|---|---|
| `Status: Triage` | Removed. Items relabelled by the triage workflow during the bulk-sweep. |
| `Type: Bug` | Replaced by the `severity:*` set above. |
| `Type: Feature Request` | Issues only; promoted to `triage:accepted-for-review` or `triage:wont-do`. |
| `Waiting on customer` | Replaced by `triage:needs-info`. |

The bulk-sweep is performed once on adoption with a script under
`scripts/relabel-bulk.sh` (committed alongside this policy, no auto-run).

## How labels are enforced

- Lane label auto-applied by `.github/workflows/auto-label.yml` (added
  in a later PR; not in scope for this rec).
- Triage state labels applied by humans only. The SLA dashboard
  considers an item un-triaged if it has zero `triage:*` labels.
- Severity labels applied by either author or triager.
- Stale labels auto-applied by `.github/workflows/stale.yml`.

## Adding, removing, renaming a label

The label set defined in this file is the authoritative list. Changes
require a PR to this file. For a new label:

- Name (kebab-case, namespaced if applicable: `triage:*`, `severity:*`).
- Trigger or applicability rule (one sentence).
- Outcome or SLA the label maps to (one sentence).
- Whether it is auto-applied or human-only.

A new label without a clear outcome is rejected. A label that says
"we have looked at this" is what we are explicitly retiring; we do
not bring it back under a new name.

## Misapplication remedy

When a label is incorrectly applied (lane mis-routed, triage state
unmatched to the maintainer's actual action), any maintainer may
correct it with a short comment naming the correction. The
dashboard logs the change for the week's digest; >5 per week
suggests the auto-label rules need tightening.

## Why this matters

A label that means "we will get to this" without committing to when
means nothing. A label that maps cleanly to a state and an SLA is a
contract. The point of this fork's label set is that every label is a
contract; if there is no contract for an item, there is no label, and
the dashboard surfaces it.
