---
number: 0077
title: Rec 40 #40-2 — the Sleigh formal grammar ships as docs/sleigh/grammar.bnf, a faithful EBNF derivation of slghparse.y with the lexer's modal and symbol-feedback realities documented rather than papered over, drift-gated by a sha256 pin audit (sleighGrammarAudit, CI-wired) — the plan doc's ANTLR acceptance-parity check deferred as a later heavier slice
status: accepted
date: 2026-06-10
audit_rec: 40
---

# Decision 0077: the grammar is derived honestly; drift is gated cheaply

## Context

Rec 40's Workstream 1 (`#40-2` per [SLEIGH_FORMAL_AND_FUZZ.md](../sleigh/SLEIGH_FORMAL_AND_FUZZ.md))
asks for a tool-agnostic formal grammar at `docs/sleigh/grammar.bnf` plus a CI job that catches
drift between the document and the canonical C++ implementation. The derivation source is
`slghparse.y` (~70 bison productions) with terminal spellings from `slghscan.l`.

Two realities surfaced during derivation that the grammar documents explicitly rather than hides:

1. **SLEIGH is not context-free at the identifier level.** The lexer classifies identifiers by
   symbol-table lookup (feedback), producing distinct token classes (`VARSYM`, `TOKENSYM`, …). The
   EBNF models these as symbol-class terminals with a header note telling a re-implementer to
   either reproduce the feedback or accept plain identifiers and re-validate semantically.
2. **The lexer is modal** (flex start conditions): keyword spellings and even operator meanings
   depend on mode (`&`/`|` are equation operators in PATTERN mode but `$and`/`$or` in action
   expressions; PRINT mode makes whitespace display-significant). The grammar notes the active
   mode per section.

## Decision

- **`docs/sleigh/grammar.bnf`** — complete EBNF of the accepted language. Error-recovery
  alternatives in the `.y` (reportError + `YYERROR` productions, `error` token rules) are
  *diagnostics for rejected input*, not language, and are excluded by stated policy. Operator
  precedence tables are restated from the `%left`/`%nonassoc` declarations.
- **Drift gate now, parity check later.** The plan doc's end-state check — generate a parser from
  the BNF and assert acceptance parity over the 39 `.slaspec` files — needs an ANTLR toolchain and
  a grammar transcription into it; that is its own later slice. The shipping gate is leaner and
  honest: `grammar.bnf` records `DERIVED-FROM … sha256=…` pins for both sources, and the
  `sleighGrammarAudit` gradle task (root-applied, CI step beside `cppRaiiAudit`) fails when either
  source changes without the pins being refreshed. Refreshing a pin is part of reviewing the
  grammar against the change — drift becomes a visible, attributable CI failure instead of silent
  rot, with zero new toolchain.

## Consequences

- The audit's "formal grammar decoupled from the implementation" exists, with provenance and a
  tripwire. Verified on the CI-matching toolchain: the audit task passes against the pinned
  sources and fails with a precise message when a pin mismatches (both paths exercised).
- Remaining Rec 40: `#40-3`/`#40-4` semantic model (document band), `#40-5+` differential fuzzer
  (the months-scale workstream), and the deferred BNF↔Yacc acceptance-parity slice.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle sleighGrammarAudit` (pass + corrupted-pin failure paths), Gradle 8.5 / Temurin 21.
