---
number: 0049
title: Rec 37 #37-10h — render wide-char-constant constructor arguments as prefixed C++ character literals; WideCharDataType/WideChar16DataType/WideChar32DataType extend BuiltIn (not AbstractIntegerDataType), so the #37-10c..f gate declined a wide-char argument entirely; argumentExpr now adds three instanceof branches that read the value at the varnode byte width and render an L'A'/u'A'/U'A'-prefixed literal via a new wideCharConstantLiteral(Varnode, String) helper — printable ASCII directly, the standard C escapes for control/quote/backslash, and a width-padded \xNN... hex escape (free of the \u/\U universal-character-name restrictions) for any other unit; a wide-char constant therefore never declines; the helper stays a per-form twin (rule of three)
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0049: the #37-10h wide-char-constant rendering

## Context

The typed-constant sub-band `#37-10c`–`g` is complete: integer
([DD-0044](0044-rec37-integer-constant-ctor-args.md),
[DD-0045](0045-rec37-signed-constant-ctor-args.md)), boolean
([DD-0046](0046-rec37-boolean-constant-ctor-args.md)), char
([DD-0047](0047-rec37-char-constant-ctor-args.md)), and enum
([DD-0048](0048-rec37-enum-constant-ctor-args.md)) constant arguments all render faithfully. The
`#37-10f` char slice deliberately handled only the three **1-byte** char types (`CharDataType` and its
`SignedCharDataType`/`UnsignedCharDataType` subtypes) and left the wide-char types to decline, noting
the gap.

The wide-char types — `WideCharDataType` (`wchar_t`), `WideChar16DataType` (`char16_t`), and
`WideChar32DataType` (`char32_t`) — all extend `BuiltIn`, **not** `AbstractIntegerDataType`, and are not
`CharDataType`. So a wide-char-typed constant matched none of the `#37-10c`–`g` branches and
`argumentExpr` declined the whole hint — a `new C(L'A')` lost its argument entirely.

Their widths differ, and `wchar_t`'s is platform-dependent: `WideChar16DataType.getLength()` is fixed at
`2`, `WideChar32DataType.getLength()` at `4`, but `WideCharDataType.getLength()` returns
`getDataOrganization().getWideCharSize()` (2 bytes under the MSVC ABI, 4 under the Itanium/SysV ABI) and
declares `hasLanguageDependantLength() == true`. The decompiled constant's **varnode size** is the
ground-truth width of the value as it flows into the call, so the renderer reads the value at the varnode
byte width rather than trusting the declared type length — the same width-correct reading the integer
(`#37-10d`) and enum (`#37-10g`) branches use.

## Decision

Add three `instanceof` branches to `argumentExpr` (one per wide-char type), each rendering the constant
through a new private `wideCharConstantLiteral(Varnode, String prefix)` helper. The caller passes the C++
literal prefix: `"L"` for `wchar_t`, `"u"` for `char16_t`, `"U"` for `char32_t`.

1. **Read the value at the varnode byte width.** The low `bits = size * 8` value bits are masked from the
   varnode offset (a wide-char unit is unsigned, so no sign-extension). This is the code-unit value
   rendered.

2. **Render a prefixed, escaped single-quoted literal.** A printable ASCII unit
   (`0x20`–`0x7e`) renders directly (`L'A'`); the standard C escapes cover the common control characters
   and the quote and backslash (`L'\n'`, `L'\t'`, `L'\''`, `L'\\'`, …); and any other unit renders as a
   **width-padded `\xNN…` hex escape** — `u'\x20ac'` for a 2-byte unit, `U'\x0001f600'` for a 4-byte one.
   The `\x` form is chosen over the `\u`/`\U` universal-character-name forms deliberately: a UCN is
   **ill-formed** for a control code point (`< 0x20`) or a surrogate code point (`0xD800`–`0xDFFF`),
   whereas `\x` is valid for any value in a wide-character literal, keeping every unit faithfully and
   compilably renderable. A wide-char constant therefore **never declines**.

3. **The wide-char branches stay per-form twins.** `wideCharConstantLiteral` is added to both the
   placement
   ([`CppPlacementConstructionDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppPlacementConstructionDriver.java))
   and heap
   ([`CppConstructorDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppConstructorDriver.java))
   drivers identically, alongside the `argumentExpr`/`charConstantLiteral`/`enumConstantLiteral`/`integerConstantLiteral`/`constructorArguments`/`operandName`
   twins. Per the [DD-0026](0026-rec37-cpp-delete-driver.md) rule-of-three convention the band follows,
   the extraction still waits for a genuine third argument-rendering user (the virtual-call driver is
   blocked on indirect-call prototype recovery; the array driver renders trivial-element `new C[n]` with
   no constructor call). With the argument helpers now numbering six identical twins per driver, the
   *first* genuine third user will earn a sizeable extraction; until one exists, the duplication stays
   honest.

## Consequences

- A decompiled `new C(L'A')` now renders **`new C(L'A')`** (heap) / **`new (param_1) C(L'A')`**
  (placement) — and `u'A'`/`U'A'` for `char16_t`/`char32_t`, and a non-ASCII unit as a prefixed hex
  escape (`u'\x20ac'`) — instead of declining. Verified end to end through the Rec 30 headless
  `AbstractDecompilerHighFunctionTest` harness (DD-0023):
  [`CppConstructorDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppConstructorDriverTest.java)
  (19/19) adds `testRendersConstructionWithWideCharArgument` (`wchar_t` `0x41` &rarr; `L'A'`),
  `testRendersConstructionWithChar16Argument` (`u'A'`), `testRendersConstructionWithChar32Argument`
  (`U'A'`), and `testRendersConstructionWithNonAsciiWideCharArgument` (`char16_t` `0x20ac` &rarr;
  `u'\x20ac'`), and
  [`CppPlacementConstructionDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppPlacementConstructionDriverTest.java)
  (19/19) adds the placement twins. All earlier `#37-10a`&ndash;`g` cases and the decline cases still
  hold.
- **The character-literal story is now complete:** 1-byte `char` (`#37-10f`) and the three wide-char
  types (`#37-10h`) all render faithfully. **What is still deferred** (later `#37-10` work): rendering
  compound argument expressions (a computed value with no name still declines), floating-point constants,
  `DataType`-signature / template / operator rendering, and overload resolution. The genuine third
  argument-rendering user that earns extracting the argument helpers into a shared utility has still not
  appeared.
- A Java source gotcha surfaced and was resolved during authoring: a `\u` sequence anywhere in source —
  **including a `//` comment** — is processed by the Unicode-escape preprocessor before lexing, so a
  comment reading `\u universal-character-name` was an "illegal unicode escape" compile error. The
  comments were reworded to drop the bare `\u`; the driver javadocs use the doubly-escaped `\\u`/`\\U`
  (the backslash before `u` is preceded by an odd number of backslashes, so it is not an eligible
  escape introducer) and compile cleanly.
- Verified locally before commit (test-before-commit, local-only &mdash; no push, no release):
  `gradle :Decompiler:integrationTest --tests 'ghidra.app.decompiler.CppConstructorDriverTest' --tests 'ghidra.app.decompiler.CppPlacementConstructionDriverTest'`
  (19/19 + 19/19), system `gradle` 8.5.
