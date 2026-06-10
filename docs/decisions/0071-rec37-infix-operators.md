---
number: 0071
title: Rec 37 #37-10v — infix operator-call rendering; a virtual call through a slot named operator+ (etc.) renders source-faithfully as binary infix ((*p) + x) when and only when it carries exactly one explicit argument, with the already-valid explicit call form (p->operator+(x, y), p->operator-()) as the universal never-wrong fallback
status: accepted
date: 2026-06-10
audit_rec: 37
---

# Decision 0071: operators render infix where arity makes them unambiguous

## Context

The `#37-10` tail names "operators". Grounding: the pipeline already handles an overloaded-operator
slot *correctly* — the demangler names the method `operator+`, the vftable feed carries it, and
`renderVirtualCall` emits the explicit call form `p->operator+(x)`, which is valid C++. What is
missing is only source-fidelity: real source writes `*p + x`.

## Decision

`renderVirtualCall` gains one try-first step: when the resolved slot name is in a fixed set of
binary-infix operators (`+ - * / % == != < <= > >= & | ^ << >>`) **and** the call carries exactly
one explicit argument, it renders infix — `(*p) + x` for a pointer receiver (dereference
parenthesised unconditionally, the `#37-10r` exact-by-construction stance), `s == x` for a value
receiver. Everything else keeps the explicit form.

- **Arity is the disambiguator.** A *member* binary operator takes exactly one explicit argument;
  zero arguments makes `operator-` negation and `operator*` dereference, whose infix-binary
  rendering would be flatly wrong — they fall through to `p->operator-()`, still valid C++.
- **Deliberately excluded**: `++`/`--` (the postfix forms carry a dummy `int` argument — arity
  alone cannot distinguish pre/post), `[]`, `()`, `->`, and the assignment family — their faithful
  forms are not plain binary infix. They keep the explicit rendering.
- **The fallback is never-wrong by construction**: the explicit call form is itself legal C++ for
  every overloaded operator, so no input renders misleadingly — the infix step only upgrades the
  unambiguous subset.

## Consequences

- `(*p) + x` and `s == other` render from operator slots; zero-arg, two-arg, and non-infix
  operators keep their valid explicit form. Renderer suite +5 (infix pointer/value, zero-arg
  fallback, `[]` fallback, two-arg fallback); all prior renderings unchanged.
- The `#37-10` tail narrows to signature/`DataType` resolution — the last open Rec 37 band.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Base:test --tests 'ghidra.app.util.cpp.CppDecompilerHintsTest'` and `gradle :Base:ip`,
  Gradle 8.5 / Temurin 21 (the CI-matching toolchain).
