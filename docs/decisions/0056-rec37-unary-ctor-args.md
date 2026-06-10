---
number: 0056
title: Rec 37 #37-10o — render unary constructor arguments (arithmetic negation, bitwise complement) as C++ unary expressions via a new unaryExpr/unaryOperator pair wired into argumentExpr after the leaf and binary tries; INT_2COMP → "-" and INT_NEGATE → "~" preserve the operand's width, so the unary op is the value varnode's direct definition with no intervening cast/extension, while the 1-byte boolean result of a comparison or logical ! is widened by an INT_ZEXT and so stays deliberately declined
status: accepted
date: 2026-06-10
audit_rec: 37
---

# Decision 0056: render the unary constructor argument as a C++ unary expression

## Context

[DD-0054](0054-rec37-compound-expression-ctor-args.md) (`#37-10m`) added `binaryExpr`, which renders a
one-level *two-operand* compound constructor argument as `leafExpr(in0) OP leafExpr(in1)` over a grounded
opcode→glyph map (`binaryOperator`), using leaf operands only with no `CAST`/`COPY` peeling.
[DD-0055](0055-rec37-division-remainder-ctor-args.md) (`#37-10n`) extended that map with the signed
division and remainder opcodes. Both slices handled only the two-input shape; a *single*-operand compound
argument — arithmetic negation `new C(-v)` or bitwise complement `new C(~v)` — matched nothing and the
whole hint declined.

A throwaway probe over `new C(-v)` / `new C(~v)` for a named signed `param_1`, run through the Rec 30
headless harness, grounded the shapes (constructor value-arg def-chain):

```
PROBE[NEG] def INT_2COMP/1  in0 param_1 (HighParam, direct)   →  size(out) == size(in) == 8
PROBE[NOT] def INT_NEGATE/1 in0 param_1 (HighParam, direct)   →  size(out) == size(in) == 8
```

Two facts mattered:

1. **Opcode identity.** x86 `neg` lowers to `INT_2COMP` (arithmetic two's-complement negation) and `not`
   lowers to `INT_NEGATE` (bitwise complement) — *not* to a two-input `INT_SUB(0, x)` / `INT_XOR(x, -1)`.
   So neither already renders through `binaryExpr`; a dedicated single-input helper is needed.
2. **No widening wrapper.** Both opcodes produce an output the same byte width as their operand, so when
   the argument slot is an 8-byte `longlong` and the operand is an 8-byte param, the unary op is the
   value varnode's **direct** definition — there is no intervening `INT_ZEXT`/`CAST` to peel. This is the
   crucial difference from a *comparison* (`<`, `==`, …) or a *logical* `!`: those produce a 1-byte
   boolean that the decompiler widens to the argument slot with an `INT_ZEXT`, so the comparison/`!` op is
   one hop below the value varnode and a bare match at the top would see only the `INT_ZEXT` (a 1-input op
   that `unaryExpr` declines, correctly, since `~`/`-` are not what a zero-extension means).

## Decision

Add a `unaryExpr` helper and a grounded `unaryOperator` map, structurally identical to the `binaryExpr` /
`binaryOperator` pair but for the one-input shape, and wire it into `argumentExpr` as the third try, after
the leaf and binary tries:

```java
private static String argumentExpr(Varnode varnode, Program program) {
    String leaf = leafExpr(varnode, program);
    if (leaf != null) {
        return leaf;
    }
    String binary = binaryExpr(varnode, program);
    if (binary != null) {
        return binary;
    }
    return unaryExpr(varnode, program);
}

private static String unaryExpr(Varnode varnode, Program program) {
    PcodeOp def = varnode.getDef();
    if (def == null || def.getNumInputs() != 1) {
        return null;
    }
    String operator = unaryOperator(def.getOpcode());
    if (operator == null) {
        return null;
    }
    String operand = leafExpr(def.getInput(0), program);
    if (operand == null) {
        return null;
    }
    return operator + operand;
}

private static String unaryOperator(int opcode) {
    return switch (opcode) {
        case PcodeOp.INT_2COMP -> "-";
        case PcodeOp.INT_NEGATE -> "~";
        default -> null;
    };
}
```

The ordering between `binaryExpr` and `unaryExpr` is immaterial — the two are mutually exclusive on input
arity (`getNumInputs() == 2` vs `== 1`) — but the leaf try must stay first, since a named operand should
render as itself before any compound decomposition is attempted.

The operand is rendered as a **leaf only**, exactly as `binaryExpr` does. A unary prefix binds tighter
than any binary operator and a leaf carries no operator of its own, so `-param_1` / `~param_1` needs no
parentheses and is never precedence-ambiguous. An operand that is itself a compound, an unnamed temporary,
or a cast-wrapped varnode makes `leafExpr` decline, so the whole hint declines rather than guess at
nesting — the same faithful-over-complete contract the band has kept throughout.

`BOOL_NEGATE` (the logical `!`) is deliberately **not** mapped: its 1-byte result is widened to the
argument slot by an `INT_ZEXT`, so like a comparison it is not the value varnode's direct definition and
would need extension peeling this band does not yet do. Mapping it now would never fire (the top-level def
is the `INT_ZEXT`, not the `BOOL_NEGATE`), so leaving it out keeps `unaryOperator` an honest record of the
opcodes the helper actually renders.

The helper and map are added identically to both the heap
([`CppConstructorDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppConstructorDriver.java))
and placement
([`CppPlacementConstructionDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppPlacementConstructionDriver.java))
drivers as per-form twins, per the [DD-0026](0026-rec37-cpp-delete-driver.md) rule-of-three convention.

## Consequences

- A one-level unary constructor argument now **renders**: `new C(-param_1)` (`INT_2COMP`) and
  `new C(~param_1)` (`INT_NEGATE`), and the placement twins `new (param_1) C(-param_2)` /
  `new (param_1) C(~param_2)`. Verified end to end through the Rec 30 headless
  `AbstractDecompilerHighFunctionTest` harness (DD-0023):
  [`CppConstructorDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppConstructorDriverTest.java)
  and
  [`CppPlacementConstructionDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppPlacementConstructionDriverTest.java)
  each add two cases over the shared `decompileMakeWithBinaryArg` / `placementWithBinaryArgFixture`
  helper (`neg` → renders `-`, `not` → renders `~`). All earlier `#37-10a`–`n` cases still hold
  (heap 41/41, placement 41/41).
- **What this unblocks / defers:** the two common single-operand compound shapes now render. Still
  deferred to later `#37-10` work: comparison operators and logical `!` (which need `INT_ZEXT` peeling to
  reach the boolean-producing op below the widening), nested compounds (which need precedence-driven
  parenthesisation), then `DataType`-signature / template / operator-overload rendering. The widened
  boolean forms deliberately decline rather than render a bare zero-extension.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Decompiler:integrationTest --tests 'ghidra.app.decompiler.CppConstructorDriverTest' --tests 'ghidra.app.decompiler.CppPlacementConstructionDriverTest'`
  (41/41 + 41/41), system `gradle` 8.5.
