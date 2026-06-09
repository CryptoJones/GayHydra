---
number: 0048
title: Rec 37 #37-10g — render enum-constant constructor arguments as qualified member names; EnumDataType/EnumDB implement the Enum interface but are not AbstractIntegerDataTypes, so the #37-10c..f gate declined an enum argument entirely; argumentExpr now adds an instanceof Enum branch that reads the value at the varnode byte width (sign-extended when the enum is signed), looks up the member via Enum.getName(value), and renders it qualified by the type name (Color::GREEN) — valid C++ for both scoped and unscoped enums; a value naming no member declines rather than fabricate a name or a bare number; closes the typed-constant sub-band; the branch stays a per-form twin (rule of three)
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0048: the #37-10g enum-constant rendering

## Context

The typed-constant slices so far &mdash; `#37-10c`/`#37-10d` integers
([DD-0044](0044-rec37-integer-constant-ctor-args.md),
[DD-0045](0045-rec37-signed-constant-ctor-args.md)), `#37-10e` booleans
([DD-0046](0046-rec37-boolean-constant-ctor-args.md)), `#37-10f` chars
([DD-0047](0047-rec37-char-constant-ctor-args.md)) &mdash; all render a constant whose `HighVariable`
datatype is (a subtype of) `AbstractIntegerDataType`. An `enum` argument is the one common typed
constant that branch does not reach: `EnumDataType` (and its database form `EnumDB`) implement the
`Enum` interface and extend `GenericDataType`, **not** `AbstractIntegerDataType`. So an enum-typed
constant matched none of the existing branches and `argumentExpr` declined the whole hint &mdash; a
`new C(Color::GREEN)` lost its argument entirely.

A grounding probe (run earlier this sub-band through the Rec 30 headless harness, now deleted) confirmed
the shape and the lookup API:

```
PROBE[enumProbe] in[2] const=true off=0x2 size=4 dt=/Color dtClass=EnumDB
    RED: 0  GREEN: 2  BLUE: 3
    AID=false Char=false Bool=false Enum=true enumName=GREEN
```

The constant is a size-4 constant varnode whose `HighVariable` datatype is the `Enum`; the underlying
value is in the low `size * 8` offset bits (`0x2`); `instanceof Enum` is true; `AbstractIntegerDataType`
is false; and `Enum.getName(2)` returns the member name `"GREEN"`.

## Decision

Add an `instanceof Enum` branch to `argumentExpr`, rendering the constant as its **qualified member
name** via a new private `enumConstantLiteral(Varnode, Enum)` helper:

1. **Read the value at the varnode byte width, respecting enum signedness.** The low `bits = size * 8`
   offset bits are sign-extended when `Enum.isSigned()` (so a negative member value matches the stored
   enumerator) and masked otherwise &mdash; the same width-correct reading the integer branch uses
   (`#37-10d`). This is the value passed to `Enum.getName(long)`.

2. **Render the matched member qualified by the type name.** A non-blank `getName(value)` is rendered as
   `TypeName::Member` (e.g. `Color::GREEN`), built from the enum's `getName()` (the type name) and the
   member name. The `::`-qualified form is valid C++ for **both** a scoped `enum class` (where it is
   mandatory) and an unscoped `enum` (where C++11 also permits it), so it is universally compilable. If
   the type name is blank, the bare member name is used as a fallback.

3. **A value naming no member declines.** `Enum.getName(value)` returns `null` for a value that is not a
   named enumerator (a flag/bit combination, or an out-of-range value). Rather than fabricate a name or
   fall back to a bare decimal &mdash; which, for a scoped enum, would not even be valid C++ and would
   mislead either way &mdash; the helper returns `null`, so the whole construction hint declines. This
   keeps the band's advisory, never-wrong contract: say nothing rather than something wrong.

4. **The enum branch stays a per-form twin.** `enumConstantLiteral` is added to both the placement
   ([`CppPlacementConstructionDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppPlacementConstructionDriver.java))
   and heap
   ([`CppConstructorDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppConstructorDriver.java))
   drivers identically, alongside the `argumentExpr`/`charConstantLiteral`/`integerConstantLiteral`/`constructorArguments`/`operandName`
   twins. Per the [DD-0026](0026-rec37-cpp-delete-driver.md) rule-of-three convention the band follows,
   the extraction still waits for a genuine third argument-rendering user (the virtual-call driver is
   blocked on indirect-call prototype recovery &mdash; its `CALLIND` carries no argument varnodes &mdash;
   and the array driver renders trivial-element `new C[n]` with no constructor call). With the argument
   helpers now numbering five identical twins per driver, the *first* genuine third user will earn a
   sizeable extraction; until one exists, the duplication stays honest.

## Consequences

- A decompiled `new C(Color::GREEN)` now renders **`new C(Color::GREEN)`** (heap) /
  **`new (param_1) C(Color::GREEN)`** (placement) instead of declining, and an enum value naming no
  member declines cleanly. Verified end to end through the Rec 30 headless
  `AbstractDecompilerHighFunctionTest` harness (DD-0023):
  [`CppConstructorDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppConstructorDriverTest.java)
  (15/15) adds `testRendersConstructionWithEnumArgument` (value `2` &rarr; `Color::GREEN`) and
  `testDeclinesEnumArgumentWithUnnamedValue` (value `7` &rarr; no hint), and
  [`CppPlacementConstructionDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppPlacementConstructionDriverTest.java)
  (15/15) adds the placement twins. All earlier `#37-10a`&ndash;`f` cases and the decline cases still
  hold.
- **The typed-constant sub-band (`#37-10c`&ndash;`g`) is now complete:** integer (signed/unsigned, any
  width), boolean, char, and enum constant arguments all render faithfully. **What is still deferred**
  (later `#37-10` work): rendering compound argument expressions (a computed value with no name still
  declines), wide-char (`wchar_t`) constants (still decline), `DataType`-signature / template / operator
  rendering, and overload resolution. The genuine third argument-rendering user that earns extracting the
  argument helpers into a shared utility has still not appeared.
- Verified locally before commit (test-before-commit, local-only &mdash; no push, no release):
  `gradle :Decompiler:integrationTest --tests 'ghidra.app.decompiler.CppConstructorDriverTest' --tests 'ghidra.app.decompiler.CppPlacementConstructionDriverTest'`
  (15/15 + 15/15), system `gradle` 8.5.
