---
number: 0051
title: Rec 37 #37-10j — decline constructor arguments backed by Ghidra's "UNNAMED" HighOther placeholder; a string-pointer (or compound-expression) argument the decompiler cannot name reaches the call as a HighOther whose getName() is the sentinel "UNNAMED", which operandName rendered verbatim as the misleading new C(UNNAMED); operandName (per-form twin in both drivers) now treats "UNNAMED" (alongside a null/blank name) as no-name so the whole hint declines, restoring the never-wrong contract until a later slice renders the underlying literal/expression
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0051: decline the "UNNAMED" placeholder argument

## Context

The typed-constant sub-band `#37-10c`–`i` renders every scalar *constant* constructor argument
faithfully (integer, boolean, char, enum, wide-char, float —
[DD-0044](0044-rec37-integer-constant-ctor-args.md)..[DD-0050](0050-rec37-float-constant-ctor-args.md)).
A *named* argument (a parameter or local with a real name) renders via `operandName`; a constant renders
via `argumentExpr`; and an argument that is neither was meant to **decline** the whole hint, keeping the
never-wrong contract the band holds.

Grounding the next argument shape — a `const char*` string-pointer argument
(`new C("Hi")`) — with a throwaway probe surfaced a leak in that decline path. A string-pointer argument
is **not** a constant varnode: the decompiler resolves the global string address (loaded via
`mov rdx, 0x402000`) into a typed `char *` temp with **no backing symbol**. Such a varnode's
`HighVariable` is a `HighOther`, and
[`HighOther`](../../Ghidra/Framework/SoftwareModeling/src/main/java/ghidra/program/model/pcode/HighOther.java)
sets its name to the sentinel string `"UNNAMED"` (only replaced when a `symref` with a negative offset
resolves to a real symbol). The probe observed the constructor `CALL`'s argument as
`const=false … name=UNNAMED dt=PointerDataType(char *)`.

`operandName` returned that sentinel verbatim (it only guarded against a null/blank name), so the driver
rendered the misleading **`new C(UNNAMED)`** (heap) / **`new (param_1) C(UNNAMED)`** (placement) instead
of declining. `"UNNAMED"` is not a source name — it is Ghidra's "this varnode has no name" marker — so
emitting it breaks the never-wrong contract. This affects any argument the decompiler cannot name: string
pointers, and the result temporaries of compound expressions.

## Decision

Make `operandName` treat the `"UNNAMED"` sentinel as no-name, alongside the existing null/blank guard:

```java
String name = high.getName();
if (name == null || name.isBlank() || name.equals("UNNAMED")) {
    return null;
}
return name;
```

With `operandName` returning null, `argumentExpr` falls through to its constant branches; a non-constant
`UNNAMED` argument matches none of them and returns null, so `constructorArguments` declines the **whole**
hint rather than rendering a gap or the placeholder text. This is the same decline-on-unnamed contract
`#37-10a`/`#37-10b` established, now correctly covering the `HighOther` placeholder.

The fix is applied identically to both the placement
([`CppPlacementConstructionDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppPlacementConstructionDriver.java))
and heap
([`CppConstructorDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppConstructorDriver.java))
drivers, where `operandName` is one of the per-form `argumentExpr` family twins. Per the
[DD-0026](0026-rec37-cpp-delete-driver.md) rule-of-three convention the band follows, the extraction still
waits for a genuine third argument-rendering user.

Matching the literal `"UNNAMED"` (rather than `instanceof HighOther`) is deliberate: a `HighOther` *can*
carry a real symbol name (when a `symref` resolves), so it must still render by name; only the sentinel
itself denotes "no name." A user who pathologically renames a variable to the literal `UNNAMED` would see
it decline — an acceptable, never-wrong outcome.

## Consequences

- A string-pointer (or any unnamed-temporary) constructor argument now **declines** instead of rendering
  `new C(UNNAMED)`. Verified end to end through the Rec 30 headless `AbstractDecompilerHighFunctionTest`
  harness (DD-0023):
  [`CppConstructorDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppConstructorDriverTest.java)
  (23/23) adds `testDeclinesUnnamedComputedArgument` with a `decompileMakeWithStringPtrArg` fixture (a
  `char *` argument pointing to a global `"Hi"` &rarr; no hints), and
  [`CppPlacementConstructionDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppPlacementConstructionDriverTest.java)
  (23/23) adds the placement twin. The decline was first confirmed as a *failing* assertion against the
  pre-fix code (it rendered `new C(UNNAMED)`), then made green by the fix. All earlier `#37-10a`&ndash;`i`
  render and decline cases still hold.
- **What this unblocks / defers:** the never-wrong contract is now intact for unnamed arguments. Faithfully
  *rendering* such arguments — a string-pointer constant as a `"…"` string literal (trace the pointer to
  its global address and read the NUL-terminated bytes), and compound-expression arguments as C
  expressions — is later `#37-10` work, along with `DataType`-signature / template / operator rendering.
  Each will replace the decline with a real rendering for the shapes it covers.
- Verified locally before commit (test-before-commit, local-only &mdash; no push, no release):
  `gradle :Decompiler:integrationTest --tests 'ghidra.app.decompiler.CppConstructorDriverTest' --tests 'ghidra.app.decompiler.CppPlacementConstructionDriverTest'`
  (23/23 + 23/23), system `gradle` 8.5.
