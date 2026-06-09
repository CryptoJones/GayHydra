---
number: 0047
title: Rec 37 #37-10f — render char-constant constructor arguments as C character literals; CharDataType extends AbstractIntegerDataType, so #37-10c/d/e rendered a char argument as the decimal byte value (0x41 as 65) rather than the source 'A'; argumentExpr now special-cases a CharDataType constant ahead of the integer branch, rendering the byte as a single-quoted literal with standard escapes for control/special characters and \xNN for any other non-printable byte, so every byte renders faithfully and a char constant never declines; SignedCharDataType and UnsignedCharDataType extend CharDataType so all three 1-byte char types render, while the BuiltIn-derived wide-char types fall through to decline; the char branch stays a per-form twin (rule of three)
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0047: the #37-10f char-constant rendering

## Context

The `#37-10c`/`#37-10d` ([DD-0044](0044-rec37-integer-constant-ctor-args.md),
[DD-0045](0045-rec37-signed-constant-ctor-args.md)) integer-constant rendering and the `#37-10e`
([DD-0046](0046-rec37-boolean-constant-ctor-args.md)) boolean rendering both gate on the constant's
`HighVariable` datatype being an `AbstractIntegerDataType` (with `bool` carved out ahead of it). A
`char` argument falls inside that gate but renders wrong.

`CharDataType extends AbstractIntegerDataType`, so the integer branch *accepts* a char constant and
renders it as the decimal byte value — `new C('A')` round-trips to `new C(65)`. A grounding probe (run
this slice through the Rec 30 headless harness, now deleted) confirmed the shape: a char constant is a
size-1 constant varnode whose `HighVariable` datatype is `CharDataType` and whose offset carries the
character byte (`'A'` is `0x41`). Rendering that as `65` compiles but is not the source literal — a
source-unfaithful hint that weakens the band's never-wrong contract (the rendered text should read as
what the programmer wrote).

## Decision

Special-case a `CharDataType` constant in `argumentExpr`, **ahead of** the `AbstractIntegerDataType`
branch (and alongside the `bool` carve-out — a more specific type wins over the `AbstractIntegerDataType`
it extends), via a new private `charConstantLiteral(Varnode)` helper:

1. **Render the byte as a single-quoted C character literal.** The character byte is read from the low 8
   bits of the varnode offset. A printable ASCII byte (`0x20`&ndash;`0x7e`) is emitted directly inside
   single quotes (`'A'`).

2. **Escape control and special characters.** The standard C escapes are used for the common control
   characters (`\0`, `\a`, `\b`, `\t`, `\n`, `\v`, `\f`, `\r`) and for the single quote (`\'`) and
   backslash (`\\`). Any other non-printable byte is emitted as a `\xNN` two-digit hex escape (e.g.
   `'\x80'`). Every one of the 256 byte values therefore renders to a faithful, compilable literal that
   denotes exactly that byte, so a `char` constant **never declines** — there is no out-of-range case to
   fall through (unlike `bool`, where only `0`/`1` are meaningful).

3. **All three 1-byte char types render; wide chars decline.** `SignedCharDataType` and
   `UnsignedCharDataType` both extend `CharDataType`, so `instanceof CharDataType` covers `char`,
   `signed char`, and `unsigned char` alike — all one byte, all rendered the same way. The wide-char
   types (`WideCharDataType`, `WideChar16DataType`, `WideChar32DataType`) extend `BuiltIn`, not
   `AbstractIntegerDataType`, so they match neither the char branch nor the integer branch and decline
   rather than being mis-rendered as a byte — never-wrong preserved for the multi-byte case this slice
   does not yet handle.

4. **The char branch stays a per-form twin.** `charConstantLiteral` is added to both the placement
   ([`CppPlacementConstructionDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppPlacementConstructionDriver.java))
   and heap
   ([`CppConstructorDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppConstructorDriver.java))
   drivers identically, alongside the `argumentExpr`/`integerConstantLiteral`/`constructorArguments`/`operandName`
   twins. Per the [DD-0026](0026-rec37-cpp-delete-driver.md) rule-of-three convention the band follows,
   the extraction still waits for a genuine third argument-rendering user (the virtual-call driver is
   blocked on indirect-call prototype recovery — its `CALLIND` carries no argument varnodes — and the
   array driver renders trivial-element `new C[n]` with no constructor call).

## Consequences

- A decompiled `new C('A')` now renders **`new C('A')`** (heap) / **`new (param_1) C('A')`** (placement),
  and a control character renders its escaped literal (`new C('\n')`), instead of the decimal `65` / `10`.
  Verified end to end through the Rec 30 headless `AbstractDecompilerHighFunctionTest` harness (DD-0023):
  [`CppConstructorDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppConstructorDriverTest.java)
  (13/13) adds `testRendersConstructionWithCharArgument` (printable `0x41` &rarr; `'A'`) and
  `testRendersConstructionWithEscapedCharArgument` (control `0x0a` &rarr; `'\n'`), and
  [`CppPlacementConstructionDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppPlacementConstructionDriverTest.java)
  (13/13) adds the placement twins. The `#37-10c/d` integer, `#37-10e` boolean, named-argument
  (`#37-10a/b`), zero-argument, and decline cases all still hold.
- **What is still deferred** (later `#37-10` slices, narrowed from DD-0046): rendering an enum constant by
  its member name (the last typed-constant slice) and compound argument expressions (a computed value with
  no name still declines); `DataType`-signature / template / operator rendering; overload resolution; and
  wide-char (`wchar_t`) constants, which still decline. The genuine third argument-rendering user that
  earns extracting the argument helpers into a shared utility has still not appeared.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Decompiler:integrationTest --tests 'ghidra.app.decompiler.CppConstructorDriverTest' --tests 'ghidra.app.decompiler.CppPlacementConstructionDriverTest'`
  (13/13 + 13/13), system `gradle` 8.5.
