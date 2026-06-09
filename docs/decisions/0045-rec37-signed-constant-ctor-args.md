---
number: 0045
title: Rec 37 #37-10d — render integer-constant constructor arguments at the varnode's byte width; a constant varnode's getOffset carries only the low size*8 value bits, so #37-10c's Long.toString(getOffset()) mis-rendered a negative signed argument (int -1 as 4294967295) and a wide unsigned one (unsigned long long ~0 as -1); a new integerConstantLiteral helper sign-extends a signed type from the varnode width and renders an unsigned type across the full range, keeping the never-wrong contract for negative and wide-unsigned literals; the helper stays a per-form twin (rule of three)
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0045: the #37-10d byte-width-correct integer-constant rendering

## Context

[DD-0044](0044-rec37-integer-constant-ctor-args.md) (`#37-10c`) began rendering integer-typed constant
constructor arguments: a bare literal like `new C(5)` is recovered from the constructor `CALL`'s
constant input varnode and rendered as `Long.toString(varnode.getOffset())`. That was grounded only on
a **positive** literal carried in a **size-8** varnode (`new C(5)`, offset `0x5`), where the raw offset
and the faithful value coincide.

A throwaway grounding probe (a decompiled `return new C(-1);` whose constructor takes a 4-byte signed
`int`, run through the Rec 30 headless harness) showed the assumption breaks for the common case of a
**negative** argument:

```
PROBE in[2] const=true off=0xffffffff size=4 dt=int signed=true longToString=4294967295
```

A constant varnode's `getOffset()` carries only the low `size * 8` value bits, zero-filled above — here
the size-4 two's-complement encoding of `-1`, `0xffffffff`. `Long.toString` of that raw long is
`4294967295`, not `-1`: a **misleading number**, which violates the band's never-wrong contract. The
symmetric hole exists at the top of the unsigned range: a size-8 `unsigned long long` argument of
`0xffffffffffffffff` has an offset that, read as a signed Java `long`, `Long.toString`s as `-1` rather
than its true `18446744073709551615`.

## Decision

Render an integer-constant argument at the **varnode's own byte width**, respecting the type's
signedness, via a new private `integerConstantLiteral(Varnode, AbstractIntegerDataType)` helper that
`argumentExpr` now calls instead of the inline `Long.toString(getOffset())`:

1. **Signed: sign-extend from the varnode width.** For a signed type narrower than a `long`, the low
   `bits = size * 8` value bits are sign-extended to the full 64-bit width
   (`(raw << (64 - bits)) >> (64 - bits)`, an arithmetic right shift) before `Long.toString`. So a
   size-4 signed `int` constant `0xffffffff` renders `-1`. A size-8 signed value already fills the long,
   so it is rendered directly (the `#37-10c` `new C(5)` case is unchanged).

2. **Unsigned: render across the full unsigned range.** For an unsigned type the low `bits` are masked
   (zero-extended) and rendered with `Long.toUnsignedString`, so a size-8 `unsigned long long`
   `0xffffffffffffffff` renders `18446744073709551615` rather than `-1`. A small unsigned value renders
   the same as before.

3. **The gate is unchanged.** A constant is still rendered only when its `HighVariable` datatype is an
   `AbstractIntegerDataType` (the `#37-10c` decision): a pointer-typed constant (a global address) still
   declines rather than rendering a misleading bare number. This slice only fixes *how* a value that was
   already going to be rendered is converted to text.

4. **The helper stays a per-form twin.** `integerConstantLiteral` is added to both the placement
   ([`CppPlacementConstructionDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppPlacementConstructionDriver.java))
   and heap
   ([`CppConstructorDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppConstructorDriver.java))
   drivers identically, alongside the `argumentExpr`/`constructorArguments`/`operandName` twins it joins.
   Per the [DD-0026](0026-rec37-cpp-delete-driver.md) rule-of-three convention the band follows, the
   extraction still waits for a genuine third argument-rendering user (the virtual-call driver is blocked
   on indirect-call prototype recovery — its `CALLIND` carries no argument varnodes — and the array
   driver renders trivial-element `new C[n]` with no constructor call).

## Consequences

- A decompiled `new C(-1)` now renders **`new C(-1)`** (heap) / **`new (param_1) C(-1)`** (placement)
  instead of `new C(4294967295)`, and a wide `unsigned long long` argument renders its true large value
  instead of a spurious negative. Verified end to end through the Rec 30 headless
  `AbstractDecompilerHighFunctionTest` harness (DD-0023):
  [`CppConstructorDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppConstructorDriverTest.java)
  (9/9) adds `testRendersConstructionWithSignedNegativeArgument` (a size-4 signed `int` `-1`) and
  `testRendersConstructionWithUnsignedWideArgument` (a size-8 `unsigned long long` `~0`), and
  [`CppPlacementConstructionDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppPlacementConstructionDriverTest.java)
  (9/9) adds the placement twins. The `#37-10c` positive constant cases, the named-argument cases
  (`#37-10a/b`), the zero-argument cases, and all the decline cases still hold.
- **What is still deferred** (later `#37-10` slices, unchanged from DD-0044): rendering compound argument
  expressions (a computed value with no name still declines) and *typed* constants (a char as `'A'`, a
  bool as `true`, an enum constant by name — an integer constant still renders only as a plain decimal);
  `DataType`-signature / template / operator rendering; and overload resolution. The genuine third
  argument-rendering user that earns extracting the argument helpers into a shared utility has still not
  appeared.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Decompiler:integrationTest --tests 'ghidra.app.decompiler.CppConstructorDriverTest' --tests 'ghidra.app.decompiler.CppPlacementConstructionDriverTest'`
  (9/9 + 9/9), system `gradle` 8.5.
