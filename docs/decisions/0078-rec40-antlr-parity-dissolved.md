---
number: 0078
title: Rec 40 — the BNF↔parser acceptance-parity job dissolves; upstream already ships a second canonical Sleigh implementation (SoftwareModeling's Java ANTLR3 compiler) that parses every .slaspec on every full build, so building a BNF-generated third parser would duplicate exercised machinery — instead the seven language-defining .g files join sleighGrammarAudit's pin set, making the EBNF a review obligation whenever either canonical implementation moves
status: accepted
date: 2026-06-10
audit_rec: 40
---

# Decision 0078: two canonicals already exist; pin both, build no third

## Context

DD-0077 deferred the plan doc's acceptance-parity check — "re-parse all 39 `.slaspec` files with a
fresh ANTLR-generated parser from the BNF" — as a heavier later slice. Grounding that slice before
building it (the day's recurring lesson) surfaced something the plan doc had not accounted for:
**the tree already contains a second complete Sleigh implementation.** SoftwareModeling applies the
ANTLR gradle plugin and carries the Java sleigh compiler's grammar suite
(`ghidra/sleigh/grammar/*.g`: `SleighLexer`/`SleighParser`, `SemanticLexer`/`SemanticParser`,
`DisplayLexer`/`DisplayParser`, `BaseLexer`, plus tree-walkers) — the compiler behind the
`sleighCompile` build task, which parses **every** `.slaspec` on every full build, in CI, today.

So the parity job's two goals are already met or better met elsewhere:

- **Acceptance over the real corpus**: continuously exercised by `sleighCompile` in the CI build —
  not a periodic check against a derived parser, but the shipped Java compiler against the shipped
  specs on every push.
- **Catching BNF drift**: a third, BNF-generated parser would catch drift only as a build failure
  in duplicated machinery — the DD-0061 anti-pattern ("re-implementing the search would duplicate a
  fragile byte walk this pass does not own"), in grammar form. The leaner instrument already
  exists: the `sleighGrammarAudit` pin gate.

## Decision

- **The acceptance-parity slice is dissolved**, the Rec 39/`#37-10u` pattern: the capability
  exists; what it needs is wiring into the review loop, not a reimplementation.
- **The seven language-defining Java grammars join the pin set** in `grammar.bnf`
  (`BaseLexer.g`, `DisplayLexer.g`, `DisplayParser.g`, `SemanticLexer.g`, `SemanticParser.g`,
  `SleighLexer.g`, `SleighParser.g`), with a header note that "DERIVED-FROM" on these means
  *kept-consistent-with*: the EBNF was derived from the C++ pair; the Java pair is the second
  implementation under the same review obligation. `sleighGrammarAudit` (unchanged — it already
  verifies arbitrarily many pins) now fails when **either** canonical implementation changes
  without the BNF being reviewed. The tree-walker grammars (`SleighCompiler.g`, `SleighEcho.g`)
  and the preprocessor's `BooleanExpression.g` are deliberately not pinned — they implement
  semantics and preprocessing, not the surface language the BNF states.
- **Scope note recorded**: the BNF documents the post-preprocessor language (as does the C++
  parser); the `@`-directive/`$()`-macro preprocessor layer is defined by its implementations and
  the manual, outside `grammar.bnf`'s scope.

## Consequences

- Rec 40 Workstream 1 is complete in its final form: formal grammar + a drift gate spanning both
  canonical implementations, with continuous acceptance verification inherited from the existing
  build. Audit verified at 9 pins on the CI-matching toolchain.
- Remaining Rec 40 is exactly the plan doc's months-scale tail: `#40-5+` differential fuzzing
  (its own sprint; Unicorn/QEMU vendoring decisions belong to Aaron).
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle sleighGrammarAudit` → "9 source pins verified", Gradle 8.5 / Temurin 21.
