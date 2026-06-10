---
number: 0057
title: Rec 37 #37-10p — render equality-comparison constructor arguments as C++ comparisons by peeling exactly one INT_ZEXT (the boolean-widening the decompiler inserts to fit the argument slot) to reach the comparison op, then mapping INT_EQUAL → "==" and INT_NOTEQUAL → "!="; only the symmetric equality operators are mapped (no signed/unsigned split, no operand order to recover), with relational comparisons deferred
status: accepted
date: 2026-06-10
audit_rec: 37
---

# Decision 0057: render the equality-comparison constructor argument as a C++ comparison

## Context

The `#37-10m`–`o` slices ([DD-0054](0054-rec37-compound-expression-ctor-args.md),
[DD-0055](0055-rec37-division-remainder-ctor-args.md),
[DD-0056](0056-rec37-unary-ctor-args.md)) render compound constructor arguments whose computing p-code op
is the value varnode's **direct** definition: the two-operand `binaryExpr` forms (`param_1 + 7`,
`param_1 / 7`) and the one-operand `unaryExpr` forms (`-param_1`, `~param_1`). All of those ops produce a
result the same width as the argument slot, so no widening sits between the value and the op.

A *comparison* breaks that assumption. `new C(v == 7)` computes a one-byte boolean, but the constructor
argument slot is wider (an eight-byte `longlong` in the grounded fixture), so the decompiler widens the
boolean to the slot with an `INT_ZEXT`. A probe through the Rec 30 headless harness over `new C(v == 7)` /
`new C(v != 7)` (x86 `cmp rsi,7; sete dl; movzx rdx,dl`) grounded the shape (constructor value-arg
def-chain):

```
PROBE[EQ] def INT_ZEXT/1   in0 = t                          (one-byte boolean temp)
          t   def INT_EQUAL/2    in0 param_1 (HighParam)     in1 const 0x7
PROBE[NE] def INT_ZEXT/1   in0 = t
          t   def INT_NOTEQUAL/2 in0 param_1 (HighParam)     in1 const 0x7
```

So the value varnode's direct definition is the `INT_ZEXT`; the comparison op is **one hop below it**. A
bare top-level match (as `binaryExpr`/`unaryExpr` do) sees only the `INT_ZEXT` — a one-input op that
`unaryExpr` correctly declines (a zero-extension is not `-`/`~`) — and the whole argument declines. This is
the same reason `#37-10o` deliberately left `BOOL_NEGATE` (logical `!`) unmapped: its result is widened the
same way.

## Decision

Add a `comparisonExpr` helper that peels **exactly one** `INT_ZEXT` to reach the comparison, then renders
it as `leafExpr(in0) OP leafExpr(in1)` over a grounded equality-opcode→glyph map (`comparisonOperator`),
and wire it into `argumentExpr` as the fourth try, after the leaf, binary, and unary tries:

```java
private static String comparisonExpr(Varnode varnode, Program program) {
    PcodeOp widen = varnode.getDef();
    if (widen == null || widen.getOpcode() != PcodeOp.INT_ZEXT || widen.getNumInputs() != 1) {
        return null;
    }
    PcodeOp def = widen.getInput(0).getDef();
    if (def == null || def.getNumInputs() != 2) {
        return null;
    }
    String operator = comparisonOperator(def.getOpcode());
    if (operator == null) {
        return null;
    }
    String left = leafExpr(def.getInput(0), program);
    if (left == null) {
        return null;
    }
    String right = leafExpr(def.getInput(1), program);
    if (right == null) {
        return null;
    }
    return left + " " + operator + " " + right;
}

private static String comparisonOperator(int opcode) {
    return switch (opcode) {
        case PcodeOp.INT_EQUAL -> "==";
        case PcodeOp.INT_NOTEQUAL -> "!=";
        default -> null;
    };
}
```

Three deliberate scoping choices keep the slice never-wrong:

1. **Only equality.** `INT_EQUAL`/`INT_NOTEQUAL` are the only opcodes mapped. They are *symmetric* — `a ==
   b` and `b == a` render the same — so there is no operand order to recover, and they carry no
   signed/unsigned distinction. The *relational* operators are deferred: the decompiler canonicalises `a >
   b` to a swapped `b < a` (`INT_SLESS`/`INT_LESS` with reordered operands), so rendering `<`/`<=`
   faithfully needs the operand-order and signed/unsigned reasoning the shifts and division already use —
   that is its own slice.
2. **`INT_ZEXT` specifically, not any extension.** A one-byte boolean is *zero*-extended, never
   sign-extended, so matching `INT_ZEXT` (not `INT_SEXT`) is the faithful gate; a sign-extension over a
   boolean would be a different, suspicious shape and is left to decline.
3. **Exactly one hop.** The helper peels a single `INT_ZEXT` and no more, so an arbitrary cast/extension
   chain is not silently flattened — only the one widening the decompiler is known to insert for a
   boolean argument is removed.

The operands are rendered as **leaves only**, exactly as `binaryExpr` does, so an operand that is itself a
compound, unnamed, or cast-wrapped makes `leafExpr` decline and the whole hint declines — faithful over
complete.

The helper and map are added identically to both the heap
([`CppConstructorDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppConstructorDriver.java))
and placement
([`CppPlacementConstructionDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppPlacementConstructionDriver.java))
drivers as per-form twins, per the [DD-0026](0026-rec37-cpp-delete-driver.md) rule-of-three convention.

## Consequences

- An equality-comparison constructor argument now **renders**: `new C(param_1 == 7)` and
  `new C(param_1 != 7)`, and the placement twins `new (param_1) C(param_2 == 7)` /
  `new (param_1) C(param_2 != 7)`. Verified end to end through the Rec 30 headless
  `AbstractDecompilerHighFunctionTest` harness (DD-0023):
  [`CppConstructorDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppConstructorDriverTest.java)
  and
  [`CppPlacementConstructionDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppPlacementConstructionDriverTest.java)
  each add two cases over the shared `decompileMakeWithBinaryArg` / `placementWithBinaryArgFixture`
  helper (`sete` → renders `==`, `setne` → renders `!=`). All earlier `#37-10a`–`o` cases still hold
  (heap 43/43, placement 43/43).
- **What this unblocks / defers:** `comparisonExpr` is the first helper in the band to peel the
  boolean-widening `INT_ZEXT`, which is the mechanism the deferred forms also need. Still deferred to
  later `#37-10` work: the relational comparisons (`<`, `<=`, and the `>`/`>=` the decompiler swaps —
  needing operand-order and signed/unsigned reasoning), logical `!` (`BOOL_NEGATE` under the same
  `INT_ZEXT`), nested compounds (precedence-driven parenthesisation), then
  `DataType`-signature / template / operator-overload rendering.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Decompiler:integrationTest --tests 'ghidra.app.decompiler.CppConstructorDriverTest' --tests 'ghidra.app.decompiler.CppPlacementConstructionDriverTest'`
  (43/43 + 43/43), system `gradle` 8.5.
