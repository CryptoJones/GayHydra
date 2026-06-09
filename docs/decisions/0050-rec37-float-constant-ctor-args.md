---
number: 0050
title: Rec 37 #37-10i — render floating-point-constant constructor arguments as C++ decimal literals; FloatDataType/DoubleDataType extend AbstractFloatDataType (in turn BuiltIn), not AbstractIntegerDataType, so the #37-10c..h gate declined a float/double argument entirely; argumentExpr now adds one instanceof AbstractFloatDataType branch that decodes the IEEE-754 bit pattern from the constant varnode at its byte width — size 4 via Float.intBitsToFloat (rendered with an f suffix), size 8 via Double.longBitsToDouble (unsuffixed) — through a new floatConstantLiteral(Varnode) helper, declining a non-finite (NaN/Infinity) value and exotic widths so the hint stays never-wrong; the helper stays a per-form twin (rule of three)
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0050: the #37-10i floating-point-constant rendering

## Context

The typed-constant sub-band `#37-10c`–`h` now renders integer
([DD-0044](0044-rec37-integer-constant-ctor-args.md),
[DD-0045](0045-rec37-signed-constant-ctor-args.md)), boolean
([DD-0046](0046-rec37-boolean-constant-ctor-args.md)), char
([DD-0047](0047-rec37-char-constant-ctor-args.md)), enum
([DD-0048](0048-rec37-enum-constant-ctor-args.md)), and wide-char
([DD-0049](0049-rec37-widechar-constant-ctor-args.md)) constant arguments faithfully. Every prior slice
named floating-point constants as the next gap.

`FloatDataType` (`float`) and `DoubleDataType` (`double`) extend `AbstractFloatDataType`, which in turn
extends `BuiltIn` — **not** `AbstractIntegerDataType`, and they are neither `CharDataType` nor a wide-char
type. So a float/double-typed constant matched none of the `#37-10c`–`h` branches and `argumentExpr`
declined the whole hint: a `new C(2.5f)` lost its argument entirely.

A floating-point constructor argument was grounded with a throwaway probe before any matcher was written
(the grounded-not-guessed rule): a `2.5f` argument arrives as a **constant varnode**
(`isConstant() == true`), size `4`, whose `getOffset()` carries the **IEEE-754 bit pattern**
(`0x40200000` for `2.5f`) and whose `getHigh().getDataType()` is `FloatDataType`. In the Windows x64 ABI
the value reaches the call through `mov eax, <bits>` / `movd xmm1, eax` (and `mov rax, <bits>` /
`movq xmm1, rax` for the 8-byte `double` slot); the decompiler propagates that constant and types it
floating-point. The varnode size is the ground-truth width, the same width-correct reading the integer
(`#37-10d`), enum (`#37-10g`), and wide-char (`#37-10h`) branches use.

## Decision

Add one `instanceof AbstractFloatDataType` branch to `argumentExpr` (covering both `float` and `double`,
since both extend it), placed after the wide-char branches and before the `Enum` branch — its order
relative to the integer branch is immaterial because `AbstractFloatDataType` is not an
`AbstractIntegerDataType`. The branch renders through a new private `floatConstantLiteral(Varnode)`
helper:

1. **Decode the IEEE-754 bits at the varnode byte width.** A size-4 varnode is decoded with
   `Float.intBitsToFloat((int) offset)` and a size-8 varnode with `Double.longBitsToDouble(offset)`.
   Any other width (half, x87 80-bit extended, quad) declines in this slice.

2. **Render the shortest round-tripping decimal, typed by suffix.** `Float.toString(float)` /
   `Double.toString(double)` emit the shortest decimal that round-trips to the same value
   (`2.5`, `0.1`, `3.4028235E38`). A `float` gets the `f` suffix so the literal keeps its
   single-precision type (`2.5f`); a `double` is the unsuffixed default (`2.5`).

3. **Decline a non-finite value.** `NaN`, `+Infinity`, and `-Infinity` have **no bare C++ literal**
   (`NaN`/`Infinity` are not valid floating-point tokens). Rather than emit invalid text, a non-finite
   value declines the whole hint — the never-wrong contract the band holds. (Faithfully rendering them
   would require a `std::numeric_limits<...>` expression, which is out of scope for a constant-literal
   slice.)

4. **The float branch stays a per-form twin.** `floatConstantLiteral` is added to both the placement
   ([`CppPlacementConstructionDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppPlacementConstructionDriver.java))
   and heap
   ([`CppConstructorDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppConstructorDriver.java))
   drivers identically, alongside the
   `argumentExpr`/`wideCharConstantLiteral`/`charConstantLiteral`/`enumConstantLiteral`/`integerConstantLiteral`/`constructorArguments`/`operandName`
   twins. Per the [DD-0026](0026-rec37-cpp-delete-driver.md) rule-of-three convention the band follows,
   the extraction still waits for a genuine third argument-rendering user (the virtual-call driver is
   blocked on indirect-call prototype recovery; the array driver renders trivial-element `new C[n]` with
   no constructor call). With the argument helpers now numbering seven identical twins per driver, the
   *first* genuine third user will earn a sizeable extraction; until one exists, the duplication stays
   honest.

## Consequences

- A decompiled `new C(2.5f)` now renders **`new C(2.5f)`** (heap) / **`new (param_1) C(2.5f)`**
  (placement), and a `double` `2.5` renders **`new C(2.5)`** / **`new (param_1) C(2.5)`**, instead of
  declining. A non-finite value still declines. Verified end to end through the Rec 30 headless
  `AbstractDecompilerHighFunctionTest` harness (DD-0023):
  [`CppConstructorDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppConstructorDriverTest.java)
  (22/22) adds `testRendersConstructionWithFloatArgument` (`0x40200000` &rarr; `2.5f`),
  `testRendersConstructionWithDoubleArgument` (`0x4004000000000000` &rarr; `2.5`), and
  `testDeclinesNonFiniteFloatArgument` (a NaN `0x7fc00000` &rarr; no hints), and
  [`CppPlacementConstructionDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppPlacementConstructionDriverTest.java)
  (22/22) adds the placement twins. All earlier `#37-10a`&ndash;`h` cases and the decline cases still
  hold.
- **The scalar-literal story is now complete:** integer (`#37-10d`), boolean (`#37-10e`), char
  (`#37-10f`), enum (`#37-10g`), wide-char (`#37-10h`), and floating-point (`#37-10i`) constant arguments
  all render faithfully. **What is still deferred** (later `#37-10` work): rendering compound argument
  expressions (a computed value with no name still declines), `DataType`-signature / template / operator
  rendering, and overload resolution. The genuine third argument-rendering user that earns extracting the
  argument helpers into a shared utility has still not appeared.
- Verified locally before commit (test-before-commit, local-only &mdash; no push, no release):
  `gradle :Decompiler:integrationTest --tests 'ghidra.app.decompiler.CppConstructorDriverTest' --tests 'ghidra.app.decompiler.CppPlacementConstructionDriverTest'`
  (22/22 + 22/22), system `gradle` 8.5.
