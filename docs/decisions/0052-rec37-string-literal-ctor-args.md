---
number: 0052
title: Rec 37 #37-10k — render const char* string-pointer constructor arguments as C++ string literals; a const char* argument is not a constant varnode but an unnamed char* temporary whose definition copies a global address (declined since DD-0051 as the UNNAMED placeholder), so argumentExpr now traces that temporary's COPY/CAST def-chain to the constant address, reads the NUL-terminated bytes from program memory, and emits an escaped "…" literal (printable ASCII direct, named C escapes, 3-digit octal for other non-printables), gated on a pointer-to-char type and declining on a non-char pointer, an unreadable address, or a missing terminator
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0052: render the `const char*` string-pointer argument as a string literal

## Context

The typed-constant sub-band `#37-10c`–`j` made every *constant* scalar constructor argument render
faithfully and made the one argument shape that could not be named — Ghidra's `"UNNAMED"`
`HighOther` placeholder — *decline* the whole hint rather than emit the misleading `new C(UNNAMED)`
([DD-0051](0051-rec37-unnamed-placeholder-ctor-args.md)). That decline was the never-wrong holding
pattern for the most common unnamed shape: a `const char*` string-pointer argument (`new C("Hi")`).

A `const char*` argument is *not* a constant varnode. The decompiler loads the global string address
(`mov rdx, 0x402000`) into a typed `char *` temporary with no backing symbol; that temporary's
`HighVariable` is a `HighOther` whose `getName()` is the sentinel `"UNNAMED"`, and (grounded with a
throwaway probe) its `getDef()` is a single `COPY` whose input is a `const`-space varnode holding the
address `0x402000`:

```
PROBE[def] depth=0 vn const=false size=8 off=0x10000032 space=unique def=COPY/1
PROBE[def] depth=1 vn const=true  size=8 off=0x402000    space=const  def=null
```

The information to render `"Hi"` is therefore all present at analysis time: the temporary's datatype is
`PointerDataType(char *)`, the def-chain leads to the constant global address, and the bytes live in the
program's memory image. DD-0051 declined it only because no slice yet read them.

## Decision

Add a `stringConstantLiteral(Varnode, Program)` renderer and try it in `argumentExpr` *after*
`operandName` returns null (the `UNNAMED` decline) and *before* the `isConstant()` constant branches
(the string pointer is non-constant):

```java
String name = operandName(varnode);
if (name != null) {
    return name;
}
String stringLiteral = stringConstantLiteral(varnode, program);
if (stringLiteral != null) {
    return stringLiteral;
}
if (varnode.isConstant()) {
    // … #37-10c–j constant branches …
}
return null;
```

`stringConstantLiteral`:

1. **Gates on a pointer-to-char type.** The argument's `HighVariable` datatype must be a `Pointer`
   whose `getDataType()` is a `CharDataType` (so `char*`/`signed char*`/`unsigned char*` match — both
   narrow-char subclasses extend `CharDataType` — but a non-char pointer or a wide-char pointer does
   not, and is left for a later slice). Gating on the *type* rather than on `instanceof HighOther`
   keeps the renderer honest: it renders a char pointer, not "anything unnamed."
2. **Traces the def-chain.** Follows the varnode through up to `MAX_STRING_DEF_HOPS` (4) single-input
   `COPY`/`CAST` pass-throughs to a constant varnode; that constant's offset is the global address.
   Any other op shape, or no constant within the cap, declines.
3. **Reads NUL-terminated bytes.** Forms the address in the program's *default* address space and reads
   bytes from `program.getMemory()` until a `0x00` terminator, capped at `MAX_STRING_LENGTH` (4096). A
   `MemoryAccessException` / out-of-bounds address, or no terminator within the cap, declines (so a null
   or dangling pointer stays never-wrong).
4. **Escapes into a double-quoted literal.** Printable ASCII (`0x20`–`0x7e`) direct; the standard C
   escapes for the common control characters and for `"` and `\`; and a **3-digit octal `\ooo`** escape
   for any other byte.

The **octal** escape (not `\xNN`) is the deliberate, non-obvious choice. A hex escape inside a *string*
literal is greedy — it consumes *every* following hex digit — so `"\x7"` followed by a literal `A`
would be misread as the single code unit `\x7A`. (This is why the per-character `charConstantLiteral`
/ `wideCharConstantLiteral` helpers can use `\xNN`: a character literal holds exactly one code unit, so
there is no following digit to absorb.) The fixed-width 3-digit octal form ends after exactly three
digits, and a byte value (0–255) always fits in three octal digits (`0377`), so it is unambiguous in a
string.

Threading `Program` through `render` → `constructorArguments` → `argumentExpr` is the mechanical change
this requires; `recognizeAndRender` already holds the `Program`. The renderer is added identically to
both the placement
([`CppPlacementConstructionDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppPlacementConstructionDriver.java))
and heap
([`CppConstructorDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppConstructorDriver.java))
drivers as per-form twins; per the [DD-0026](0026-rec37-cpp-delete-driver.md) rule-of-three convention
the band follows, the extraction still waits for a genuine third argument-rendering user.

## Consequences

- A `const char*` string-pointer constructor argument now **renders** `new C("Hi")` /
  `new (param_1) C("Hi")` instead of declining. Verified end to end through the Rec 30 headless
  `AbstractDecompilerHighFunctionTest` harness (DD-0023):
  [`CppConstructorDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppConstructorDriverTest.java)
  and
  [`CppPlacementConstructionDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppPlacementConstructionDriverTest.java)
  each replace the DD-0051 `testDeclinesUnnamedComputedArgument` (which asserted the `char*` declined)
  with three cases: `testRenders…WithStringPtrArgument` (`"Hi"`), `testRenders…WithEscapedStringPtrArgument`
  (bytes `A` / tab / `0x01` → `"A\t\001"`, exercising a named escape *and* the octal path), and
  `testDeclinesUnnamedNonCharPointerArgument` (an `int*` over the same global-address load — still an
  `UNNAMED` `HighOther`, but the pointer-to-char gate rejects it, so it declines, confirming the
  never-wrong contract still holds for a genuinely unnameable pointer). The shared
  `decompileMakeWithPtrArg` / `placementWithPtrArgFixture` helpers parameterise the pointee type and the
  global bytes. All earlier `#37-10a`–`j` render and decline cases still hold (heap 25/25, placement
  25/25).
- **What this unblocks / defers:** the most common unnamed-argument shape now renders. Still deferred to
  later `#37-10` work: wide-char string pointers (`const wchar_t*` / `char16_t*` / `char32_t*`),
  compound-expression arguments (a computed value with no name still declines), and
  `DataType`-signature / template / operator rendering.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Decompiler:integrationTest --tests 'ghidra.app.decompiler.CppConstructorDriverTest' --tests 'ghidra.app.decompiler.CppPlacementConstructionDriverTest'`
  (25/25 + 25/25), system `gradle` 8.5.
