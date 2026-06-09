---
number: 0054
title: Rec 37 #37-10m — render a one-level compound-expression constructor argument as a C++ binary expression; the argument is an unnamed HighOther temp whose def is a binary p-code op over a named leaf and a constant, so argumentExpr splits into a leafExpr (names, string literals, typed constants) and a new binaryExpr that emits leafExpr(in0) OP leafExpr(in1) over a grounded opcode→glyph map, using leaf operands only with no CAST/COPY peeling so the cast-wrapped logical right shift and any nested compound cleanly decline rather than risk a semantically-wrong render
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0054: render a one-level compound constructor argument as a binary expression

## Context

The `#37-10` band renders constructor-call arguments. Through [DD-0053](0053-rec37-wide-string-literal-ctor-args.md)
(`#37-10l`) every rendered argument was a *leaf*: a named variable (`operandName`), a string-pointer
literal (`stringConstantLiteral`), or a typed constant varnode (bool / char / wide-char / enum / float /
integer). [DD-0051](0051-rec37-unnamed-placeholder-ctor-args.md) (`#37-10j`) established the never-wrong
floor — an argument the renderer cannot name declines the whole hint rather than emit the `UNNAMED`
placeholder.

A *computed* argument — `new C(v + 7)` — is the next-commonest shape and was declining. Grounded with a
throwaway multi-op probe over a `make(longlong v)` that constructs `new C(v OP k)` for each operator:

```
PROBE[arg2] const=false size=8 space=unique high=HighOther name=UNNAMED def=INT_ADD/2
  PROBE[in0] const=false high=HighParam name=param_1 def=null
  PROBE[in1] const=true  off=0x7 high=HighConstant def=null
```

The argument is an `UNNAMED` `HighOther` temp whose `getDef()` is the **binary p-code op** itself (two
inputs): `in0` the named `param_1` (`HighParam`, `def=null`), `in1` the constant `k` (`HighConstant`). The
probe grounded the opcode→glyph map directly from decompiled output:

| source operator | p-code opcode | glyph | `in0` shape (grounded)            |
|-----------------|---------------|-------|----------------------------------|
| `+`             | `INT_ADD`     | `+`   | `param_1` direct                 |
| `-`             | `INT_SUB`     | `-`   | `param_1` direct                 |
| `*`             | `INT_MULT`    | `*`   | `param_1` direct                 |
| `&`             | `INT_AND`     | `&`   | `param_1` direct                 |
| `\|`            | `INT_OR`      | `\|`  | `param_1` direct                 |
| `^`             | `INT_XOR`     | `^`   | `param_1` direct                 |
| `<<`            | `INT_LEFT`    | `<<`  | `param_1` direct                 |
| `>>` arithmetic | `INT_SRIGHT`  | `>>`  | `param_1` direct                 |
| `>>` logical    | `INT_RIGHT`   | `>>`  | **`CAST`(param_1 → unsigned)/1** |

The one non-uniform case is decisive: only the **logical** right shift `INT_RIGHT` wraps its left operand
in a `CAST` to an unsigned type (the source's `unsigned long long`), because the signedness of the operand
is what distinguishes a logical from an arithmetic shift. The arithmetic `INT_SRIGHT` carries `param_1`
directly.

## Decision

Split the existing leaf renderer out and add a binary-expression layer above it.

1. **`argumentExpr` becomes a thin dispatcher.** It tries `leafExpr` first (the entire former body —
   `operandName`, `stringConstantLiteral`, then the typed-constant cascade), and falls back to `binaryExpr`.
   Leaf rendering is byte-for-byte unchanged, so every `#37-10a`–`l` case is untouched.

2. **`binaryExpr` renders `leafExpr(in0) OP leafExpr(in1)` — leaf operands only.** It declines unless the
   def is a binary op (`getNumInputs() == 2`) whose opcode `binaryOperator` maps to a glyph, and unless
   *both* operands render as leaves. There is **no `CAST`/`COPY` peeling** of the operands — this is the
   load-bearing choice. Its consequences:
   - All of ADD/SUB/MULT/AND/OR/XOR/SHL and the arithmetic `INT_SRIGHT` have a direct `param_1` left
     operand, so they render faithfully (`param_1 + 7`, `param_1 >> 3`, …).
   - The logical `INT_RIGHT`'s left operand is a `CAST` temp, not a leaf, so `leafExpr(in0)` returns null
     and the whole hint **declines**. This is deliberate: peeling the unsigned cast to render a bare
     `param_1 >> 3` over the *signed* operand would silently turn a logical shift into an arithmetic one —
     a wrong decompilation. Declining is never-wrong; rendering would be sometimes-wrong.
   - A nested compound (an operand that is itself a binary op) likewise makes `leafExpr` return null, so
     `new C((a + b) * c)` declines rather than emit an unparenthesised, precedence-ambiguous string.

3. **No parentheses are emitted, and that is safe.** A single operator over two leaf operands can never be
   precedence-ambiguous — there is nothing to bind tighter or looser against. Parens only become necessary
   once a nested compound renders, which is explicitly deferred (and currently declines), so adding them
   now would be dead code.

`binaryOperator` is a pure `switch` over the grounded opcode set, mapping both `INT_RIGHT` and
`INT_SRIGHT` to `>>` (the source-level glyph is identical; the logical case is gated out one level up by
the cast-wrapped operand, not by the operator map). Any unlisted opcode — division, remainder, comparison,
unary negation — returns null and declines, reserving those for later slices where their own grounding and
precedence handling can be done honestly.

The renderer is added identically to both the heap
([`CppConstructorDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppConstructorDriver.java))
and placement
([`CppPlacementConstructionDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppPlacementConstructionDriver.java))
drivers as per-form twins; per the [DD-0026](0026-rec37-cpp-delete-driver.md) rule-of-three convention the
band follows, the extraction still waits for a genuine third argument-rendering user.

## Consequences

- A one-level compound constructor argument now **renders** `new C(param_1 + 7)` /
  `new (param_1) C(param_2 << 3)` (and the bitwise/shift twins) instead of declining. Verified end to end
  through the Rec 30 headless `AbstractDecompilerHighFunctionTest` harness (DD-0023):
  [`CppConstructorDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppConstructorDriverTest.java)
  and
  [`CppPlacementConstructionDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppPlacementConstructionDriverTest.java)
  each add five cases over a shared `decompileMakeWithBinaryArg` / `placementWithBinaryArgFixture` helper
  (which parameterise the `computeHex` instruction sequence writing `rdx = f(rsi)`): the `+` (`lea`), `&`,
  `<<`, and arithmetic `>>` (`sar`) renders, plus a logical `>>` (`shr`, operand cast to unsigned) decline
  confirming the never-wrong contract. All earlier `#37-10a`–`l` render and decline cases still hold
  (heap 35/35, placement 35/35).
- **What this unblocks / defers:** the common one-level computed-argument shapes now render. Still deferred
  to later `#37-10` work: division and remainder operators, comparison operators, unary operators, and
  nested compounds (which need parenthesisation driven by operator precedence), then
  `DataType`-signature / template / operator-overload rendering. The logical-shift and nested-compound
  cases deliberately decline rather than guess.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Decompiler:integrationTest --tests 'ghidra.app.decompiler.CppConstructorDriverTest' --tests 'ghidra.app.decompiler.CppPlacementConstructionDriverTest'`
  (35/35 + 35/35), system `gradle` 8.5.
