---
number: 0046
title: Rec 37 #37-10e — render boolean-constant constructor arguments as true/false; BooleanDataType extends AbstractIntegerDataType, so #37-10c/d rendered a bool argument as the decimal 1/0 rather than the source-faithful true/false; argumentExpr now special-cases a BooleanDataType constant of 0 or 1 ahead of the integer branch, while an out-of-range bool falls through to the byte-width-correct integer rendering; the bool branch stays a per-form twin (rule of three)
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0046: the #37-10e boolean-constant rendering

## Context

[DD-0044](0044-rec37-integer-constant-ctor-args.md) (`#37-10c`) began rendering integer-typed constant
constructor arguments, and [DD-0045](0045-rec37-signed-constant-ctor-args.md) (`#37-10d`) fixed the
byte-width/signedness conversion so a negative or wide-unsigned literal renders its true value. Both
gate on the constant's `HighVariable` datatype being an `AbstractIntegerDataType`.

A `bool` argument falls inside that gate but renders wrong. A throwaway grounding probe (a decompiled
constructor call with a `bool` argument, run through the Rec 30 headless harness) confirmed the type
shape and the hierarchy:

```
PROBE[boolProbe] in[2] const=true off=0x1 size=1 dt=bool dtClass=BooleanDataType AID=true Bool=true
```

`BooleanDataType extends AbstractUnsignedIntegerDataType extends AbstractIntegerDataType`, so the
`#37-10c/d` integer branch *accepts* a bool constant and renders it as the decimal `1` or `0` — a
**source-unfaithful** rendering. A C++ source `new C(true)` round-trips to `new C(1)`, which compiles
but is not what the programmer wrote, weakening the band's never-wrong contract (the rendered text
should read as the original literal).

## Decision

Special-case a `BooleanDataType` constant in `argumentExpr`, **ahead of** the `AbstractIntegerDataType`
branch (a more specific type wins over the more general one it extends):

1. **A bool constant of `1` renders `true`, of `0` renders `false`.** These are the only two values a
   well-formed `bool` carries, and they are exactly the source literals.

2. **An out-of-range bool falls through to the integer branch.** If a `BooleanDataType` constant somehow
   carries a value other than `0` or `1` (a miscompiled or corrupt input), the bool branch declines and
   control falls into the `AbstractIntegerDataType` branch, which renders the byte-width-correct decimal
   (`#37-10d`) rather than inventing a `true`/`false` that would misrepresent the bits. Never-wrong is
   preserved at the edge: an unexpected value is shown faithfully as its number, not coerced.

3. **The gate is otherwise unchanged.** A constant is still rendered only when its datatype is a
   `BooleanDataType` or an `AbstractIntegerDataType`; a pointer-typed constant (a global address) still
   declines rather than rendering a misleading bare number.

4. **The bool branch stays a per-form twin.** It is added to both the placement
   ([`CppPlacementConstructionDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppPlacementConstructionDriver.java))
   and heap
   ([`CppConstructorDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppConstructorDriver.java))
   drivers identically, alongside the `argumentExpr`/`integerConstantLiteral`/`constructorArguments`/`operandName`
   twins. Per the [DD-0026](0026-rec37-cpp-delete-driver.md) rule-of-three convention the band follows,
   the extraction still waits for a genuine third argument-rendering user (the virtual-call driver is
   blocked on indirect-call prototype recovery — its `CALLIND` carries no argument varnodes — and the
   array driver renders trivial-element `new C[n]` with no constructor call).

## Consequences

- A decompiled `new C(true)` now renders **`new C(true)`** (heap) / **`new (param_1) C(true)`**
  (placement), and `false` likewise, instead of `new C(1)` / `new C(0)`. Verified end to end through the
  Rec 30 headless `AbstractDecompilerHighFunctionTest` harness (DD-0023):
  [`CppConstructorDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppConstructorDriverTest.java)
  (11/11) adds `testRendersConstructionWithBooleanTrueArgument` and
  `testRendersConstructionWithBooleanFalseArgument`, and
  [`CppPlacementConstructionDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppPlacementConstructionDriverTest.java)
  (11/11) adds the placement twins. The `#37-10c/d` integer constant cases, the named-argument cases
  (`#37-10a/b`), the zero-argument cases, and all the decline cases still hold.
- **What is still deferred** (later `#37-10` slices, unchanged from DD-0045): rendering the remaining
  *typed* constants (a char as `'A'`, an enum constant by name — both grounded but not yet sliced) and
  compound argument expressions (a computed value with no name still declines); `DataType`-signature /
  template / operator rendering; and overload resolution. The genuine third argument-rendering user that
  earns extracting the argument helpers into a shared utility has still not appeared.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Decompiler:integrationTest --tests 'ghidra.app.decompiler.CppConstructorDriverTest' --tests 'ghidra.app.decompiler.CppPlacementConstructionDriverTest'`
  (11/11 + 11/11), system `gradle` 8.5.
