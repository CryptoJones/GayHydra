---
number: 0055
title: Rec 37 #37-10n — render signed division and remainder constructor arguments as C++ binary expressions by extending the #37-10m binaryOperator map with INT_SDIV/INT_DIV → "/" and INT_SREM/INT_REM → "%"; the signed forms carry a leaf operand and render while the unsigned forms cast their operand to unsigned and so decline under the existing leaf-only no-peel policy, exactly as the arithmetic vs logical right shift already does
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0055: render the signed division/remainder constructor argument as a binary expression

## Context

[DD-0054](0054-rec37-compound-expression-ctor-args.md) (`#37-10m`) added `binaryExpr`, which renders a
one-level compound constructor argument as `leafExpr(in0) OP leafExpr(in1)` over a grounded
opcode→glyph map, using **leaf operands only with no `CAST`/`COPY` peeling**. That policy made the two
right-shift forms split cleanly: the arithmetic `INT_SRIGHT` carries its operand directly and renders
`param_1 >> 3`, while the logical `INT_RIGHT` casts its operand to unsigned and so declines (a bare render
would silently change an arithmetic shift into a logical one). Division and remainder were left unmapped
and declined.

A throwaway probe over `new C(v / k)` / `new C(v % k)` for signed and unsigned operands grounded the
shapes (constructor value-arg def-chain):

```
PROBE[SDIV] def INT_SDIV/2  in0 param_1 (HighParam, direct)            in1 const 0x7
PROBE[SREM] def INT_SREM/2  in0 param_1 (HighParam, direct)            in1 const 0x7
PROBE[UDIV] def INT_DIV/2   in0 CAST(param_1 → unsigned) (HighOther)   in1 const 0x7
PROBE[UREM] def INT_REM/2   in0 CAST(param_1 → unsigned) (HighOther)   in1 const 0x7
```

This is the **same split as the shifts**: the signed opcodes (`INT_SDIV`, `INT_SREM`) carry `param_1` as a
direct leaf; the unsigned opcodes (`INT_DIV`, `INT_REM`) wrap the left operand in a `CAST` to an unsigned
type, because operand signedness is what distinguishes signed from unsigned division at the machine level.

## Decision

Extend `binaryOperator`'s grounded map with two paired entries and change nothing else:

```java
case PcodeOp.INT_SDIV, PcodeOp.INT_DIV -> "/";
case PcodeOp.INT_SREM, PcodeOp.INT_REM -> "%";
```

No change to `binaryExpr` is needed. Its existing leaf-only no-peel rule does all the gating for free:

- `INT_SDIV` / `INT_SREM` have a direct `param_1` left operand, so both `leafExpr` calls succeed and the
  argument renders `param_1 / 7` / `param_1 % 7`.
- `INT_DIV` / `INT_REM` have a cast-wrapped left operand, so `leafExpr(in0)` returns null and the whole
  hint **declines** — rendering a bare `param_1 / 7` over the signed operand would silently change the
  computation's signedness, so declining is the never-wrong choice.

Mapping both forms of each pair to the same glyph (the source `/` and `%` are identical; the
logical-vs-arithmetic distinction lives in the cast on the operand, not the operator) keeps `binaryOperator`
a faithful record of which opcodes mean which C operator, while the operand policy one level up decides
render-vs-decline. This is deliberately the identical mechanism the right-shift pair already uses, so the
band gains division and remainder with no new control flow.

The two entries are added identically to both the heap
([`CppConstructorDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppConstructorDriver.java))
and placement
([`CppPlacementConstructionDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppPlacementConstructionDriver.java))
drivers as per-form twins, per the [DD-0026](0026-rec37-cpp-delete-driver.md) rule-of-three convention.

## Consequences

- A signed division or remainder constructor argument now **renders** `new C(param_1 / 7)` /
  `new C(param_1 % 7)` (and the placement twins `new (param_1) C(param_2 / 7)` …); the unsigned forms
  deliberately **decline**. Verified end to end through the Rec 30 headless
  `AbstractDecompilerHighFunctionTest` harness (DD-0023):
  [`CppConstructorDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppConstructorDriverTest.java)
  and
  [`CppPlacementConstructionDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppPlacementConstructionDriverTest.java)
  each add four cases over the shared `decompileMakeWithBinaryArg` / `placementWithBinaryArgFixture`
  helper (signed `idiv` quotient → renders `/`, signed `idiv` remainder → renders `%`, unsigned `div`
  quotient → declines, unsigned `div` remainder → declines). All earlier `#37-10a`–`m` cases still hold
  (heap 39/39, placement 39/39).
- **What this unblocks / defers:** the four common arithmetic/bitwise/shift/division compound shapes now
  render for signed operands. Still deferred to later `#37-10` work: comparison operators, unary operators,
  nested compounds (which need precedence-driven parenthesisation), then
  `DataType`-signature / template / operator-overload rendering. The unsigned division/remainder and
  cast-wrapped operands deliberately decline rather than guess.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Decompiler:integrationTest --tests 'ghidra.app.decompiler.CppConstructorDriverTest' --tests 'ghidra.app.decompiler.CppPlacementConstructionDriverTest'`
  (39/39 + 39/39), system `gradle` 8.5.
