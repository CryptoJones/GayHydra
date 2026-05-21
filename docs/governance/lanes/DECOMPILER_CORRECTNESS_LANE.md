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

If we cannot meet the 3-business-day SLA on a correctness fix, that is a
recordable incident in the weekly maintainers' digest. The crown jewel
does not get to ship wrong output silently for years.
