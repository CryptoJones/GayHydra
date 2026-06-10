---
number: 0060
title: Rec 37 #37-10s — render compound operands inside comparison constructor arguments by routing comparisonExpr's two operands through the #37-10r operandExpr helper; a nested compound operand renders recursively in parentheses ((param_1 & 7) == 5) while a cast-wrapped or unrecognised operand still declines, and a probe showed a unary compound under a comparison is typically never seen because the decompiler folds it into the constant (~v == 5 arrives as v == -6)
status: accepted
date: 2026-06-10
audit_rec: 37
---

# Decision 0060: render compound operands inside comparison constructor arguments

## Context

[DD-0059](0059-rec37-nested-compound-ctor-args.md) (`#37-10r`) introduced `operandExpr`, which renders an
operand either as a bare leaf or as a recursively-rendered nested compound wrapped in unconditional
parentheses, and routed `binaryExpr`/`unaryExpr` operands through it. `comparisonExpr`
([DD-0057](0057-rec37-equality-ctor-args.md), [DD-0058](0058-rec37-relational-ctor-args.md)) still
rendered its two operands as leaves only, so a comparison over a compound — `new C((v & 7) == 5)` —
declined.

A probe through the Rec 30 headless harness over four comparison-operand shapes grounded:

```
(v & 7) == 5  ->  new C((param_1 & 7) == 5)    INT_ZEXT(INT_EQUAL(INT_AND(param_1,7), 5))
(v & 7) != 5  ->  new C((param_1 & 7) != 5)    INT_ZEXT(INT_NOTEQUAL(INT_AND(param_1,7), 5))
(v & 7) < 5   ->  new C((param_1 & 7) < 5)     INT_ZEXT(INT_SLESS(INT_AND(param_1,7), 5))
(~v) == 5     ->  new C(param_1 == -6)         INT_ZEXT(INT_EQUAL(param_1, -6))
```

Two findings. First, the compound operands arrive directly under the comparison op — the signed
relational kept its `INT_AND` operand bare (a masked value needs no unsigned cast), so the `#37-10r`
recursion composes with the `#37-10p`/`q` `INT_ZEXT` peel with no new mechanism. Second, a **unary
compound under a comparison is typically never seen**: the decompiler folds the complement into the
constant (`~v == 5 ⟺ v == ~5 == -6`), so the argument arrives as a plain leaf-level equality and was
already rendered by `#37-10p` — more of the same canonicalisation the relational probe found in DD-0058.

## Decision

Route `comparisonExpr`'s two operands through `operandExpr` instead of `leafExpr` — the whole change per
driver:

```java
String left = operandExpr(def.getInput(0), program, 0);
...
String right = operandExpr(def.getInput(1), program, 0);
```

Everything else is inherited unchanged from the existing helpers:

- A leaf operand renders bare, so every grounded `#37-10p`/`q` rendering is unchanged.
- A nested compound operand renders parenthesised by `operandExpr`'s unconditional-parentheses policy
  (DD-0059) — exact by construction, no precedence table.
- A cast-wrapped operand (the unsigned comparison forms) still declines: a `CAST` is neither a leaf nor
  a mapped opcode, so the signed/unsigned split holds, verified by the existing `setb` decline tests.
- Recursion termination and the `MAX_OPERAND_NESTING` bound are `operandExpr`'s (DD-0059).

The depth starts at {@code 0} from the comparison just as it does from `argumentExpr`'s binary/unary
tries: the comparison itself is the top level (rendered bare), and its compound operands are nesting
level one.

The rewire is applied identically to both the heap
([`CppConstructorDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppConstructorDriver.java))
and placement
([`CppPlacementConstructionDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppPlacementConstructionDriver.java))
drivers as per-form twins, per the [DD-0026](0026-rec37-cpp-delete-driver.md) rule-of-three convention.

## Consequences

- A comparison constructor argument over a compound operand now **renders**:
  `new C((param_1 & 7) == 5)`, `new C((param_1 & 7) != 5)`, `new C((param_1 & 7) < 5)`, and the
  placement twins; the folded `~v == 5` form renders `new C(param_1 == -6)` at leaf level (the exact
  boolean computed). Verified end to end through the Rec 30 headless
  `AbstractDecompilerHighFunctionTest` harness (DD-0023):
  [`CppConstructorDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppConstructorDriverTest.java)
  and
  [`CppPlacementConstructionDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppPlacementConstructionDriverTest.java)
  each add four cases. All earlier `#37-10a`–`r` cases still hold (heap 56/56, placement 56/56),
  including every cast-gated unsigned decline.
- **What this closes / defers:** the expression-rendering band is now compositional across all three
  helper families — binary, unary, and comparison operands all route through `operandExpr`. Logical `!`
  needs no slice of its own on current grounding (the decompiler canonicalises it away, as it did
  `~v == 5` here and `<=`/`>`/`>=` in DD-0058). Remaining `#37-10` work is the different-in-kind tail:
  `DataType`-signature / template / operator-overload rendering.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Decompiler:integrationTest --tests 'ghidra.app.decompiler.CppConstructorDriverTest' --tests 'ghidra.app.decompiler.CppPlacementConstructionDriverTest'`
  (56/56 + 56/56), system `gradle` 8.5.
