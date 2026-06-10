---
number: 0059
title: Rec 37 #37-10r — render nested compound constructor arguments by recursing through a new operandExpr helper that wraps every nested sub-expression in unconditional parentheses; fully-parenthesised rendering is exact by construction, so no C precedence/associativity table is consulted and there is no table to get wrong, while a CAST-wrapped or unrecognised operand still declines the whole hint and a MAX_OPERAND_NESTING bound caps the recursion as defense-in-depth
status: accepted
date: 2026-06-10
audit_rec: 37
---

# Decision 0059: render the nested compound constructor argument with unconditional parentheses

## Context

The `#37-10m`–`q` band renders one-level compound constructor arguments: `binaryExpr` (two-operand
arithmetic/bitwise/shift/division, [DD-0054](0054-rec37-compound-expression-ctor-args.md),
[DD-0055](0055-rec37-division-remainder-ctor-args.md)), `unaryExpr` (negation/complement,
[DD-0056](0056-rec37-unary-ctor-args.md)), and `comparisonExpr` (equality/relational under one
`INT_ZEXT`, [DD-0057](0057-rec37-equality-ctor-args.md), [DD-0058](0058-rec37-relational-ctor-args.md)).
All of them rendered operands as **leaves only** — an operand that was itself a compound declined the
whole hint, deferring "nested compounds, which need precedence-driven parenthesisation."

A probe through the Rec 30 headless harness over four nested shapes grounded that (a) the def-chains
arrive exactly as written — the decompiler did not fold any of the probed AND/OR/XOR/ADD/NEG chains —
and (b) the candidate recursive renderer produces:

```
(v & 7) + 1      ->  new C((param_1 & 7) + 1)         INT_ADD(INT_AND(param_1,7), 1)
(~v) & 7         ->  new C((~param_1) & 7)            INT_AND(INT_NEGATE(param_1), 7)
-(v & 7)         ->  new C(-(param_1 & 7))            INT_2COMP(INT_AND(param_1,7))
((v & 7) | 9)^5  ->  new C(((param_1 & 7) | 9) ^ 5)   INT_XOR(INT_OR(INT_AND(...),9), 5)
```

## Decision

Add an `operandExpr` helper and route both `binaryExpr` and `unaryExpr` operands through it (the public
zero-arg-depth entry points are kept; a `depth` parameter threads through the recursion):

```java
private static String operandExpr(Varnode varnode, Program program, int depth) {
    String leaf = leafExpr(varnode, program);
    if (leaf != null) {
        return leaf;
    }
    if (depth >= MAX_OPERAND_NESTING) {
        return null;
    }
    String binary = binaryExpr(varnode, program, depth + 1);
    if (binary != null) {
        return "(" + binary + ")";
    }
    String unary = unaryExpr(varnode, program, depth + 1);
    if (unary != null) {
        return "(" + unary + ")";
    }
    return null;
}
```

The deliberate design choice is **unconditional parentheses over a precedence table**. The alternative —
rendering `~param_1 & 7` without the redundant pair by consulting C precedence and associativity — would
be prettier but introduces an entire class of subtle wrongness (precedence rows, associativity edge
cases, the shift-vs-arithmetic gotchas C is famous for). A fully-parenthesised nested rendering is exact
*by construction*: `(a OP b)` composed into any context can never change the computation. The band's
contract is never-wrong, not maximally-idiomatic, so the occasional redundant pair (`(~param_1) & 7`) is
the accepted cost. The **top level stays bare** (`new C((param_1 & 7) + 1)`, not
`new C(((param_1 & 7) + 1))`), preserving every existing one-level rendering unchanged.

Termination and gating:

- **Structural termination.** Only opcodes mapped in `binaryOperator`/`unaryOperator` recurse. The ops
  that can close an SSA def-chain cycle through a loop (`MULTIEQUAL`, `INDIRECT`) are unmapped, so the
  recursion cannot revisit a varnode. `MAX_OPERAND_NESTING` (8) is defense-in-depth and a readability
  cap, not a fix for a known divergence.
- **The no-peel rule is unchanged.** A `CAST` is neither a leaf nor a mapped opcode, so a cast-wrapped
  operand declines through `operandExpr` exactly as it declined through `leafExpr` before — every
  unsigned-form decline grounded in `#37-10m`–`q` (logical shift, unsigned division/remainder, unsigned
  comparison) still holds, verified by the existing suites.
- **Scope.** `comparisonExpr`'s operands stay leaf-only this slice; nesting them is the same one-line
  change but doubles the grounding matrix, so it is deferred with the rest (logical `!`,
  signature/template/operator rendering).

The helper and rewires are added identically to both the heap
([`CppConstructorDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppConstructorDriver.java))
and placement
([`CppPlacementConstructionDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppPlacementConstructionDriver.java))
drivers as per-form twins, per the [DD-0026](0026-rec37-cpp-delete-driver.md) rule-of-three convention.

## Consequences

- A nested compound constructor argument now **renders**, parenthesised at every nested level:
  `new C((param_1 & 7) + 1)` (binary-in-binary), `new C((~param_1) & 7)` (unary-in-binary),
  `new C(-(param_1 & 7))` (binary-in-unary), `new C(((param_1 & 7) | 9) ^ 5)` (three levels), and the
  placement twins. Verified end to end through the Rec 30 headless
  `AbstractDecompilerHighFunctionTest` harness (DD-0023):
  [`CppConstructorDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppConstructorDriverTest.java)
  and
  [`CppPlacementConstructionDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppPlacementConstructionDriverTest.java)
  each add four cases over the shared `decompileMakeWithBinaryArg` / `placementWithBinaryArgFixture`
  helper. All earlier `#37-10a`–`q` cases still hold (heap 52/52, placement 52/52) — including every
  cast-gated decline.
- **What this unblocks / defers:** the arithmetic/bitwise/shift/unary expression grammar is now closed
  under composition (any mix of mapped ops over leaves renders). Still deferred to later `#37-10` work:
  compound operands inside comparisons, logical `!` (`BOOL_NEGATE` — note the decompiler typically
  canonicalises `!v` to `v == 0`, which already renders via `#37-10p`), then
  `DataType`-signature / template / operator-overload rendering.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Decompiler:integrationTest --tests 'ghidra.app.decompiler.CppConstructorDriverTest' --tests 'ghidra.app.decompiler.CppPlacementConstructionDriverTest'`
  (52/52 + 52/52), system `gradle` 8.5.
