---
number: 0068
title: Rec 37 #37-11d-2 — headless hints surfacing; CppHintsCommenter writes collected hints as idempotent, additive "C++: ..." PRE comments (visible in listing and decompiler view by default), and RecoverCppHintsScript is the thin per-function decompile-collect-annotate driver — hints become user-visible today without the DISPLAY-blocked margin surface
status: accepted
date: 2026-06-10
audit_rec: 37
---

# Decision 0068: surface hints as PRE comments first; the GUI margin stays deferred

## Context

[DD-0067](0067-rec37-hints-collector.md) made the Rec 37 pipeline callable —
`CppHintsCollector.collect(HighFunction)` returns rendered hints — but nothing showed them to a
user. The intended surface (a decompiler margin / annotation display) is part of Sprint 14's
`DISPLAY`-blocked GUI tail. The question for this slice: what is the smallest *visible* consumer
that does not pre-empt that surface?

## Decision

Two pieces, the logic testable and the glue thin:

- **`CppHintsCommenter.annotate(Program, List<CppHint>)`** (Base, beside the collector): writes one
  {@code PRE}-comment line per hint at its site, in the form `C++: param_1->draw()`.
  - **`PRE`, not `EOL`**: the decompiler view displays `PRE` comments by default, so the hint shows
    up exactly where the idiom renders in *both* views; `EOL` display is off by default in the
    decompiler.
  - **Idempotent and additive**: a hint line already present at the site is skipped (the
    `C++: `-prefixed line is the idempotence key), and an existing unrelated comment is preserved
    with the hint appended below — re-running never duplicates and never clobbers an analyst's
    note. A *different* rendering at the same site (the type system improved between runs) appends
    as a new line rather than being suppressed.
  - The caller owns the transaction, matching upstream listing-mutation utilities.
- **`RecoverCppHintsScript`** (`Base/ghidra_scripts`, `@category C++`): decompiles every function,
  collects, annotates, and prints `functions decompiled / hints collected / lines written` totals.
  Deliberately thin — all decisions live in the tested collector and commenter; the script is the
  standard run-it-now consumer (headless `analyzeHeadless -postScript` or the Script Manager).

Not chosen: auto-annotating from the analyzers (writing comments during auto-analysis is a
side-effect users cannot opt out of per-run, and the type system is fed *during* analysis, so
function-order coupling would make early functions miss hints); and the GUI margin (blocked, and
the comment path neither blocks nor pre-empts it — the margin will call the same collector).

## Consequences

- Collected hints are now user-visible end to end: analyze a VS/Clang PE, run the script, read
  `C++: new C(5)` above the call site in the listing or decompiler view. Verified headlessly in
  [`CppHintsCommenterTest`](../../Ghidra/Features/Base/src/test/java/ghidra/app/util/cpp/CppHintsCommenterTest.java):
  write, idempotent re-run, append-below-existing-comment, distinct-renderings-append,
  empty-list no-op, null contracts. Suite 7/7. The script is untested glue by convention (its two
  callees are the tested units).
- Remaining Rec 37 surface work: the decompiler margin/annotation display when the GUI ceiling
  lifts (it will consume the same `CppHintsCollector`), and the `#37-10` different-in-kind tail
  (signature/template/operator rendering).
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Base:test --tests 'ghidra.app.util.cpp.CppHintsCommenterTest'` and `gradle :Base:ip`,
  Gradle 8.5 / Temurin 21 (the CI-matching toolchain).
