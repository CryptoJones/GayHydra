# `@Ignore` Debt Policy

*Addresses Rec 28 of the 2026-05-21 principal-architect audit.*

## The problem this closes

Every `@Ignore`'d JUnit test is a frozen bug report. The original
author chose not to delete the test (because the case still
matters) and chose not to fix it (because the underlying issue
wasn't ready to fix). The result is a test that compiles but does
nothing, with no expiration date, and over time forgets why it
exists.

The audit identified these specific Stage-1 examples:

- `x86AssemblyTest`
- `dsPIC30FAssemblyTest`
- `ARMAssemblyTest`
- `x64AssemblyTest`
- `SymbolPathParserTest`
- `CharsetInfoManagerTest`

A broader grep finds `@Ignore` annotations across the assembler,
emulator, debugger-integration, and a handful of framework tests.
This policy applies to all of them.

## The rule

Every `@Ignore` must satisfy **all** of:

1. **A linked tracking issue.** The `@Ignore("...")` string is
   not optional; it must point at a GitHub issue describing what
   would unblock the test. Format:
   `@Ignore("flaky: see #1234")` or `@Ignore("blocked-on: #1234")`.
2. **A category.** One of:
   - `flaky` — passes locally, fails in CI; tracked for fix.
   - `blocked-on` — needs a dependency, environment, or other
     test infrastructure that doesn't exist yet.
   - `wip` — work-in-progress; must include a target stage in
     the linked issue.
3. **A deadline.** The linked issue carries a deadline label
   (`ignore:30d`, `ignore:90d`, `ignore:1y`). After the deadline,
   the test is **fixed or deleted** — there is no fourth option.

An `@Ignore` without all three is a defect; CI surfaces it
(`gradle ignoreAudit`, a follow-up task in this rec series).

## Categorising the current backlog

The audit's six named tests are now categorised in
`docs/testing/ignore-test-inventory.md`. For each:

- Current `@Ignore("...")` string (if any).
- Inferred category.
- Suggested next step (fix or delete).
- Linked tracking issue (created as part of #28-2).

Until the inventory PR lands, the policy applies prospectively
only: new `@Ignore` annotations must satisfy the rule; existing
ones are grandfathered while the audit is being staged.

## What "fix or delete" means

- **Fix** — make the test pass. The work to do that may be
  significant (e.g., a real bug needs fixing first). The
  tracking issue captures the fix's scope; the test is
  re-enabled in the same PR that lands the fix.
- **Delete** — if the test no longer reflects a behaviour we
  care about, or if the test is fundamentally testing the
  wrong abstraction, delete it with a one-line commit message
  explaining why. *Delete is not failure.* A frozen test is
  worse than no test; future maintainers waste time wondering
  what it was supposed to assert.

There is no "fix later." A test that has sat `@Ignore`'d for
five years is not "going to be fixed soon."

## How CI enforces this

The Gradle task `gradle ignoreAudit` (landed in #28-3, see
[`gradle/ignoreAudit.gradle`](../../gradle/ignoreAudit.gradle))
walks the tree and asserts that every `@Ignore` annotation:

- Is not bare (no argument) — must be `@Ignore("...")`.
- Carries a category prefix matching `flaky`, `blocked-on`, `wip`,
  or `manual-tool`.
- Contains a tracking-issue reference like `#1234` in the argument.

A future iteration (#28-4) will additionally:

- Verify the linked issue exists (`gh` query).
- Verify the issue has an `ignore:*` deadline label.
- Fail the build when an item is past its deadline.

For Stage 1 (this PR), the audit gates only on local-text rules so
the policy can land without depending on the GitHub API.

The audit runs as a CI step in
[`.github/workflows/build-ghidra.yml`](../../.github/workflows/build-ghidra.yml)
before the unit tests.

**Stage 1 (warning-only, current).** The audit logs violations as
warnings but does not fail the build. This lets the existing in-tree
`@Ignore` backlog get swept progressively (Rec 28 #28-5..#28-8)
without blocking unrelated PRs.

**Stage 2 (failing, future).** Once the warning count drops to zero,
the audit flips to fail-on-violation. The mode is controlled by the
Gradle property:

```
./gradlew ignoreAudit -PignoreAuditStrict=true
```

Code review still enforces the rule for *new* `@Ignore` additions
even during Stage 1 — the warning makes the violation visible in
the PR's CI output.

## Sequencing

| PR | Scope |
|---|---|
| #28-1 (this PR) | The policy doc |
| #28-2 | The inventory of current `@Ignore`s (named in code + linked issues created) |
| #28-3 | `ignoreAudit` Gradle task + CI wiring |
| #28-4+ | Per-test fix-or-delete sweeps; one batch per test category |

## Coordination with @SuppressWarnings (Rec 25 / Rec 26)

The same shape applies to `@SuppressWarnings` annotations: every
suppression carries a tracking issue. The audit task should
eventually unify the rule across both annotation types — same
discipline, different annotation.

## Why this matters

A test suite that runs but skips 200 tests is not "200 tests
worth of coverage." It is "0 tests worth of coverage with the
appearance of 200." The point of CI is to know what's broken;
silent `@Ignore` is the opposite of that.
