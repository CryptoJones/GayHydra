---
number: 0044
title: Rec 37 #37-10c — render integer-typed constant constructor arguments; the placement and heap drivers now render a bare integer literal argument (e.g. new C(5)) as its decimal value via a new argumentExpr helper, instead of declining the whole hint as #37-10a/b did; gated on AbstractIntegerDataType so a pointer-typed constant (a global address) still declines rather than rendering a misleading bare number; the two argumentExpr helpers stay per-form twins (rule of three); compound expressions and typed constants (chars, bools, enums) are deferred
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0044: the #37-10c integer-constant constructor-argument rendering

## Context

[DD-0042](0042-rec37-placement-ctor-arg-threading.md) (`#37-10a`) and
[DD-0043](0043-rec37-heap-ctor-arg-threading.md) (`#37-10b`) threaded *named* constructor arguments
through the placement and heap `new` drivers: each argument is rendered by its `HighVariable` name, and
**an argument with no printable name declines the whole hint**. That decline is total: a constructor
called with a bare integer literal — `new C(5)`, extremely common — has no `HighVariable` name for the
literal, so the whole construction declined and produced no hint at all.

Grounded via the Rec 30 headless harness (a throwaway probe over a decompiled `return new C(5);`), a
literal constructor argument appears in the constructor `CALL` as a **constant varnode**:
`in[2] const=true off=0x5 size=8 high=HighConstant name=null dt=longlong`. So the literal *is*
recoverable — `varnode.isConstant()` is true, the value is `varnode.getOffset()`, and the
`HighConstant`'s datatype is the argument's integer type — it was simply being dropped because
`operandName` (which only reads a `HighVariable`'s *name*) returns null for an unnamed `HighConstant`.

## Decision

Render integer-typed constant constructor arguments in both drivers via a new private
`argumentExpr(Varnode)` helper that `constructorArguments` now calls instead of `operandName`:

1. **Named variable, else integer constant, else decline.** `argumentExpr` first tries `operandName`
   (the existing `HighVariable`-name rendering); if that is null and the varnode is a constant whose
   `HighVariable` datatype is an `AbstractIntegerDataType`, it renders `Long.toString(getOffset())` —
   the literal's decimal value. The same decimal rendering the `#37-9d-b` array driver already uses for
   its recovered element count. `operandName` itself is **unchanged**, so the placement buffer / `this`
   receiver rendering stays strict (it still requires a name; a receiver is never a literal).

2. **Gated on integer type, to stay never-wrong.** A constant is rendered only when its datatype is an
   `AbstractIntegerDataType`. An integer literal's decimal value is a faithful hint (`5` is `5`), but a
   *pointer*-typed constant — e.g. a global string-literal address passed as `const char*` — rendered as
   a bare decimal would be a misleading number, not the `"..."` / `&DAT_...` the decompiler shows. Such
   a constant therefore still declines, preserving the advisory, never-wrong contract: a hint is emitted
   only when it is faithful.

3. **The helper stays a per-form twin.** `argumentExpr` is added to both the placement and heap drivers
   identically rather than extracted to a shared utility. They remain the two users (the virtual-call
   driver is blocked on indirect-call prototype recovery — its `CALLIND` carries no argument varnodes —
   and the array driver renders trivial-element `new C[n]` with no constructor call), so per the
   [DD-0026](0026-rec37-cpp-delete-driver.md) rule-of-three convention the band follows, the extraction
   still waits for a genuine third user.

## Consequences

- A decompiled `new C(5)` now renders **`new C(5)`** (heap) and a `new (buf) C(5)` renders
  **`new (param_1) C(5)`** (placement), instead of declining. Verified end to end through the Rec 30
  headless `AbstractDecompilerHighFunctionTest` harness (DD-0023):
  [`CppConstructorDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppConstructorDriverTest.java)
  (7/7) adds `testRendersConstructionWithConstantArgument`, and
  [`CppPlacementConstructionDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppPlacementConstructionDriverTest.java)
  (7/7) adds `testRendersPlacementWithConstantArgument`, each hand-assembling a `C(5)` whose constructor
  `CALL` carries the literal as an integer-typed constant third input. The named-argument cases
  (`#37-10a/b`), the zero-argument cases, and all the decline cases still hold.
- **What is still deferred** (later `#37-10` slices): rendering compound argument expressions (a
  computed value with no name still declines) and *typed* constants (a char as `'A'`, a bool as
  `true`, an enum constant by name — currently an integer constant renders only as a plain decimal);
  `DataType`-signature / template / operator rendering; and overload resolution. The genuine third
  argument-rendering user that earns extracting `argumentExpr`/`operandName`/`constructorArguments` into
  a shared helper has still not appeared.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Decompiler:integrationTest --tests 'ghidra.app.decompiler.CppConstructorDriverTest' --tests 'ghidra.app.decompiler.CppPlacementConstructionDriverTest'`
  (7/7 + 7/7), system `gradle` 8.5.
