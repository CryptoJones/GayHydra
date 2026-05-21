# Decompiler Correctness Lane

*Addresses Rec 05 of the 2026-05-21 principal-architect audit.*

## Why an expedited lane

The C++ decompiler is Ghidra's crown jewel. A correctness regression in it
produces wrong code in the user's window — silently. A wrong-output bug is
qualitatively different from a missing feature, a slow path, or a UI
papercut: there is no warning, the user only finds it when they trust the
output and act on it.

Upstream PRs like #6718 (shifted struct-offset loop bug) have sat for as
long as 13k-line processor submissions. That equivalence is wrong. This
lane exists to break it.

## Trigger

A PR is in this lane if it claims to fix a **wrong-output** bug in the C++
decompiler. The PR description must include:

- A minimal reproducer (input binary or pcode fragment) committed under
  `Ghidra/Features/Decompiler/src/decompile/cpp/unittests/correctness/`.
- Expected vs actual output, captured as a test that fails before the
  patch and passes after.
- A short root-cause sentence.

Auto-label rules:

- PR title starts with `decomp-correctness:` or
- Diff touches `Ghidra/Features/Decompiler/src/decompile/cpp/` AND the
  PR description matches `/wrong.?output|miscompile|incorrect decompilation/i`.

## What "expedited" means

- **SLA:** first human response within **3 business days**, not 10.
- **Reviewer pool:** the active decompiler maintainers (named in
  `MAINTAINERS.md`, [Rec 07](../../../MAINTAINERS.md)).
- **CI gate:** the change must include the failing reproducer as a unit
  test under `unittests/correctness/`. CI runs that test before and after
  the patch in the same job; a green diff is required for merge.
- **Backport:** every merged correctness fix is auto-cherry-picked to the
  current `release/*` branch within 7 days, unless explicitly opted out
  with the `no-backport` label and a written reason.

## What this is not

- Not a lane for performance fixes. Performance work is `lane:framework`
  and goes through the standard queue.
- Not a lane for new analysis passes or refactors of the decompiler.
  Those are RFC-gated (Rec 06).
- Not a lane for "the decompiler crashed." Crash bugs go to
  `lane:security` (and if they parse adversary-controlled input, they
  may be embargoed under [SECURITY.md](../../../SECURITY.md)).

## Acceptance checklist

- [ ] Reproducer committed under `unittests/correctness/<short-name>/`.
- [ ] Test fails on parent commit, passes on this commit (CI shows both).
- [ ] One-sentence root-cause in the PR description.
- [ ] No collateral changes outside `decompile/cpp/`.
- [ ] Backport flag set (default: backport).

## Maintainer commitment

If we cannot meet the 3-business-day SLA on a correctness fix, that is
a *recorded process miss* — captured by the queue-health workflow as
`decomp-correctness-sla-miss` in the weekly digest with the PR
number, age, and a one-line cause attribution from a maintainer.

When the count of `decomp-correctness-sla-miss` exceeds **2 in any
rolling 8-week window**, the project enters queue-health mode (see
[PR_QUEUE_POLICY.md](../PR_QUEUE_POLICY.md)): no new decompiler
feature work lands until the moving count drops to zero.

## Backport edge cases

The auto-cherry-pick assumes the file affected by the fix exists on
the release branch. When it does not (the bug was introduced after
the release was cut), the auto-cherry-pick is skipped and the PR is
labelled `no-backport: not-in-release`. The PR description records
this explicitly so reviewers can confirm the bug was not present in
the release branch.

When the cherry-pick conflicts non-trivially, the workflow opens a
fresh PR against the release branch with `[backport]` in the title
and labels both for maintainer attention. The original fix is not
held hostage by the conflict.

## Reproducer vs regression test

The `unittests/correctness/<short-name>/` artifact functions as both
the reproducer (it failed before the patch) *and* the regression
test (it passes after, and will fail again if the bug recurs). A
plain reproducer that is not also a regression test does not
satisfy this lane's checklist — the point is to make this specific
class of bug visible to future contributors, not just to fix today's
instance.

The crown jewel does not get to ship wrong output silently for years.
