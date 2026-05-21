# `-Xlint` Ratchet Plan

*Addresses Rec 25 of the 2026-05-21 principal-architect audit.*

## Why this matters

`gradle/javaProject.gradle` was passing `-Xlint:none` to every
`compileJava` and `compileTestJava` invocation. That mutes every
javac warning across ~15.5k Java files. The audit identified the
mute as actively harmful: real deprecation warnings and
unchecked-cast warnings were invisible to maintainers and
reviewers.

This rec re-enables `-Xlint` and starts the ratchet.

## What changed

`gradle/javaProject.gradle` now passes:

```
-Xlint:deprecation,unchecked
```

by default. The list is overridable per-subproject via
`ext.lintOpts = ['none']` (used as an escape hatch only while fixing
that subproject's backlog — see "Opt-out path" below).

Warnings are **not** errors yet (`-Werror` is not set). The first
sweep is to make warnings visible.

## The ratchet

| Stage | Flags | Goal | Status |
|---|---|---|---|
| 0 | `-Xlint:none` | (the prior status quo) | retired |
| 1 | `-Xlint:deprecation,unchecked` | warnings visible; build passes | **this PR** |
| 2 | `-Xlint:deprecation,unchecked,rawtypes,cast` | broader surface | once Stage 1 floor is fixed |
| 3 | `-Xlint:all` | everything | once Stage 2 floor is fixed |
| 4 | `-Xlint:all -Werror` | warnings become errors | end state |

We move to the next stage only when the warning count at the
current stage is ≤ a published floor (target: 0 across the tree,
acceptable: <50 in any one subproject for one cycle).

Each stage is its own PR. The progression is recorded in
`docs/testing/xlint-ratchet-progress.md` (added on first sweep).

## Opt-out path

While a subproject is being cleaned up, its `build.gradle` can set:

```groovy
ext.lintOpts = ['none']
```

to temporarily mute its warnings. This is **not** "permanent off"
— it's a flag visible in the subproject that *someone has to come
back to this*. The next stage of the ratchet requires opt-outs to
be removed before it lands.

Each opt-out PR carries a tracking issue with a deadline. Opt-outs
without a tracking issue are rejected by code review.

## Specific call-outs from the audit

The audit identified these warning categories as the most likely to
matter:

- **deprecation** — APIs that have moved or been removed in Java 17/21.
  These are silent in production but indicate maintenance debt.
- **unchecked** — generic raw types, unchecked casts. The most common
  shape of "this compiles but does the wrong thing on null/empty input."
- **rawtypes** — closely related; collections without parameter types.
- **cast** — redundant or suspicious casts.

Stage 1 turns on the two highest-EV categories. Stage 2 adds the
next two. Stage 3 is the broom.

## Coordinated with Rec 26 (static analysis)

`-Xlint` is the javac side. Rec 26 ([ErrorProne](../testing/JUNIT5_MIGRATION.md))
adds annotation-driven static analysis on top: ErrorProne catches a
strictly broader set of issues than `-Xlint` (mutable-collection-as-key,
missing-override, async-call-in-finally, etc.) without javac changes.

The two are complementary and intended to land in sequence: this
rec first (gets the floor visible), then ErrorProne on top.

## Local check

A subproject's current warning count, for picking work:

```
gradle :Ghidra:Features:Base:compileJava \
    -Dorg.gradle.warning.mode=all 2>&1 | grep -c 'warning:'
```

Pick the highest-count subproject and start there.

## Maintenance commitment

Stage transitions are PR-gated. The CI build does not silently
ratchet — that would change the warning floor under reviewers
without notice. Every transition is reviewable and reversible.
