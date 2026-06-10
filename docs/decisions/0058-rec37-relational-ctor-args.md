---
number: 0058
title: Rec 37 #37-10q — render relational-comparison constructor arguments as C++ comparisons by extending comparisonExpr's grounded map with INT_SLESS/INT_LESS → "<"; a probe showed the decompiler canonicalises every signed relational source form (< <= > >=) to a strict INT_SLESS by adjusting the constant or swapping the operands, so all four render faithfully as the exact boolean computed, while the unsigned INT_LESS casts its operand and so declines under the existing leaf-only rule, the same signed/unsigned split division and the shifts already have
status: accepted
date: 2026-06-10
audit_rec: 37
---

# Decision 0058: render the relational-comparison constructor argument as a C++ comparison

## Context

[DD-0057](0057-rec37-equality-ctor-args.md) (`#37-10p`) added `comparisonExpr`, which peels exactly one
`INT_ZEXT` (the boolean-widening the decompiler inserts to fit the argument slot) to reach the comparison
op, then renders it as `leafExpr(in0) OP leafExpr(in1)` over a grounded opcode→glyph map
(`comparisonOperator`). That slice mapped only the symmetric equality operators (`INT_EQUAL` → `==`,
`INT_NOTEQUAL` → `!=`) and deferred the relational operators, because they appeared to need operand-order
and signed/unsigned reasoning.

A throwaway probe over the four signed relational source forms (`new C(v < 7)`, `<= 7`, `> 7`, `>= 7`),
each compiled as `cmp rsi,7; setCC dl; movzx rdx,dl` and run through the Rec 30 headless harness, grounded
a simpler reality than expected:

```
setl  (v < 7)  -> new C(param_1 < 7)      INT_SLESS(param_1, 7)
setle (v <= 7) -> new C(param_1 < 8)      INT_SLESS(param_1, 8)    -- <= 7 rewritten as < 8
setg  (v > 7)  -> new C(7 < param_1)      INT_SLESS(7, param_1)    -- > rewritten as swapped <
setge (v >= 7) -> new C(6 < param_1)      INT_SLESS(6, param_1)    -- >= 7 rewritten as 6 < v
```

The decompiler **canonicalises every signed relational form to a strict less-than** (`INT_SLESS`) by
adjusting the constant (`<= 7` → `< 8`, `>= 7` → `6 <`) or swapping the operands (`>` → swapped `<`). The
`INT_SLESSEQUAL` opcode never appears for these forms. Each rendering is the *exact* boolean the p-code
computes — `param_1 < 8` is identically `v <= 7` over integers, `6 < param_1` is identically `v >= 7` — so
all four are faithful by the only standard this band holds: render what the p-code computes, not the
original source syntax.

A second probe over the four *unsigned* forms (`setb`/`setbe`/`seta`/`setae`, which read the unsigned
flags) grounded the other half:

```
setb / setbe / seta / setae  ->  <declined> (all four)
```

An unsigned comparison is `INT_LESS`, and the decompiler casts its operand to an unsigned type first
(operand signedness is what distinguishes signed from unsigned comparison), so `leafExpr` sees a
cast-wrapped operand, not a leaf, and the whole hint declines. This is the **identical signed/unsigned
split** that [DD-0055](0055-rec37-division-remainder-ctor-args.md) and the shift forms already have: the
signed opcode carries its operand directly and renders, the unsigned opcode casts and so declines rather
than silently change signedness.

## Decision

Extend `comparisonOperator`'s grounded map with one paired entry and change nothing else:

```java
case PcodeOp.INT_SLESS, PcodeOp.INT_LESS -> "<";
```

`comparisonExpr` is unchanged — its existing one-`INT_ZEXT`-peel + leaf-only operand rule does all the
gating for free:

- `INT_SLESS` carries both operands as direct leaves (a named param and a constant, in either order), so
  both `leafExpr` calls succeed and the argument renders the canonical `param_1 < 7` / `param_1 < 8` /
  `7 < param_1` / `6 < param_1`.
- `INT_LESS` has a cast-wrapped left operand, so `leafExpr(in0)` returns null and the whole hint
  **declines** — rendering a bare `param_1 < 7` over the signed operand would silently change the
  comparison's signedness, so declining is the never-wrong choice.

`INT_SLESSEQUAL` / `INT_LESSEQUAL` are deliberately **not** mapped: the decompiler canonicalises `<=`/`>=`
to a strict less-than (adjusting the constant), so those opcodes are not emitted for these forms. Mapping
them would be ungrounded; in the rare case one does arise (a `<=` against a type's maximum, where
`< (max + 1)` would overflow and cannot be formed), declining is never-wrong. Mapping both `INT_SLESS` and
`INT_LESS` to the same `<` glyph keeps `comparisonOperator` a faithful record of which opcodes mean which C
operator (the source `<` is identical; the signed-vs-unsigned distinction lives in the cast on the operand,
not the glyph), exactly as `binaryOperator` does for the division and shift pairs.

The entry is added identically to both the heap
([`CppConstructorDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppConstructorDriver.java))
and placement
([`CppPlacementConstructionDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppPlacementConstructionDriver.java))
drivers as per-form twins, per the [DD-0026](0026-rec37-cpp-delete-driver.md) rule-of-three convention.

## Consequences

- A signed relational constructor argument now **renders** the decompiler's canonical strict-less-than
  form: `new C(param_1 < 7)` (`<`), `new C(param_1 < 8)` (`<=`), `new C(7 < param_1)` (`>`),
  `new C(6 < param_1)` (`>=`), and the placement twins; the unsigned forms deliberately **decline**.
  Verified end to end through the Rec 30 headless `AbstractDecompilerHighFunctionTest` harness (DD-0023):
  [`CppConstructorDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppConstructorDriverTest.java)
  and
  [`CppPlacementConstructionDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppPlacementConstructionDriverTest.java)
  each add five cases over the shared `decompileMakeWithBinaryArg` / `placementWithBinaryArgFixture`
  helper (`setl`/`setle`/`setg`/`setge` → render the four canonical forms, `setb` → declines). All
  earlier `#37-10a`–`p` cases still hold (heap 48/48, placement 48/48).
- **What this unblocks / defers:** the full set of integer relational comparisons now renders (signed) or
  faithfully declines (unsigned), completing the comparison sub-band `#37-10p`–`q` (equality + relational)
  alongside the arithmetic/bitwise/shift/division `binaryExpr` band and the unary `unaryExpr` band. Still
  deferred to later `#37-10` work: logical `!` (`BOOL_NEGATE` under the same `INT_ZEXT` widening), nested
  compounds (which need precedence-driven parenthesisation), then
  `DataType`-signature / template / operator-overload rendering.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Decompiler:integrationTest --tests 'ghidra.app.decompiler.CppConstructorDriverTest' --tests 'ghidra.app.decompiler.CppPlacementConstructionDriverTest'`
  (48/48 + 48/48), system `gradle` 8.5.
