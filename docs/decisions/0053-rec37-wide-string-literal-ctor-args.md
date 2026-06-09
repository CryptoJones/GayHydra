---
number: 0053
title: Rec 37 #37-10l — render wide string-pointer constructor arguments as C++ wide string literals; a wchar_t*/char16_t*/char32_t* argument reaches the call exactly like the const char* of DD-0052 (an unnamed character-pointer temp whose def-chain COPYs a global address), so stringConstantLiteral's pointer-to-char gate is generalised via a stringLiteralPrefix helper that maps the pointee to its ""/L/u/U prefix, reads code units at the pointee's own byte width (1/2/4) in the program's endian order, and emits the prefixed literal, escaping low units by the DD-0052 octal policy and high wide units (>= 0x80) as fixed-width non-greedy universal-character-names (\uXXXX/\UXXXXXXXX), declining a lone surrogate or out-of-range value to stay never-wrong
status: accepted
date: 2026-06-09
audit_rec: 37
---

# Decision 0053: render the wide string-pointer argument as a wide string literal

## Context

[DD-0052](0052-rec37-string-literal-ctor-args.md) (`#37-10k`) made a `const char*` string-pointer
constructor argument render as a narrow `"…"` literal: such an argument is not a constant varnode but the
unnamed `char *` `HighOther` temp that [DD-0051](0051-rec37-unnamed-placeholder-ctor-args.md) declined,
whose `getDef()` is a `COPY` of a `const`-space varnode holding the global string address. `argumentExpr`
traces that def-chain, reads the NUL-terminated bytes, and escapes them. The renderer was deliberately
**gated on a pointer-to-`char` type**, leaving a wide-char pointer (`const wchar_t*` /
`const char16_t*` / `const char32_t*`) to decline as a later slice.

A wide string pointer is the same shape. Grounded with a throwaway probe over a `wchar_t*` argument whose
global region holds UTF-16LE `"Hi\0"`:

```
PROBE[arg2] const=false size=8 off=0x10000032 space=unique high=HighOther name=UNNAMED dt=PointerDataType pointee=WideCharDataType len=2
  PROBE[def hop=0] const=false size=8 off=0x10000032 space=unique def=COPY/1
  PROBE[def hop=1] const=true  size=8 off=0x402000    space=const  def=null
```

The argument is still an `UNNAMED` `HighOther` reached by a single `COPY` of the `const`-space global
address `0x402000`; only the pointee differs — a `WideCharDataType` reporting `getLength() == 2` (so
`wchar_t` is 2 bytes on this `_X64` compiler spec). The bytes of the wide string live in the program image
exactly as the narrow ones do. So `#37-10l` is a *generalisation* of `#37-10k`, not a new mechanism.

## Decision

Generalise `stringConstantLiteral` rather than add a parallel renderer.

1. **Gate on any string-char pointee, mapping it to a literal prefix.** A new
   `stringLiteralPrefix(DataType pointee)` returns `""` for `CharDataType` (narrow), `"L"` for
   `WideCharDataType` (`wchar_t`), `"u"` for `WideChar16DataType` (`char16_t`), and `"U"` for
   `WideChar32DataType` (`char32_t`), or `null` when the pointee is not a string-char type. The four types
   are unrelated by inheritance (`CharDataType extends AbstractIntegerDataType`; the three wide types each
   `extend BuiltIn`), so the `instanceof` order is immaterial, and a `null` return — not the empty narrow
   prefix — is the not-a-string signal. The code-unit width is the pointee's own `getLength()` (so
   `wchar_t` reads at its ground-truth 2-byte MSVC / 4-byte Itanium width rather than a hard-coded one); a
   width other than 1/2/4 declines.

2. **Read code units at the pointee width, in program endian order.** `readStringLiteral` now takes the
   `prefix` and `unitWidth` and reads each code unit with the width-appropriate `Memory` accessor —
   `getByte` / `getShort` / `getInt`, whose single-argument forms honor the program's default endianness —
   stepping `unitWidth` bytes per unit until a zero code unit terminates. The cap stays `MAX_STRING_LENGTH`
   (4096) code units; an unreadable address still declines.

3. **Escape per code unit, declining the unrepresentable.** `escapeStringByte` becomes
   `escapeStringUnit(long unit, int unitWidth)`:
   - Named C escapes for the common control characters and for `"` and `\`; printable ASCII
     (`0x20`–`0x7e`) direct — identical to `#37-10k`, so narrow rendering is unchanged.
   - A control unit `<= 0x7f` (and, for a narrow string, any high byte `0x80`–`0xff`) renders as the
     **3-digit octal `\ooo`** escape — the DD-0052 choice, because a hex escape is greedy inside a string
     literal.
   - A **high wide unit (`>= 0x80`)** is a Unicode code point and renders as a **fixed-width
     universal-character-name** — `\uXXXX` (four hex digits) up to `0xffff`, `\UXXXXXXXX` (eight hex
     digits) above. Like the octal form, a UCN is **not greedy** (it consumes exactly four or eight
     digits), so it is safe in a string literal where a width-padded `\x…` would absorb a following hex
     digit. (The per-character `wideCharConstantLiteral` of `#37-10h` can still use `\x…` precisely because
     a *character* literal holds one code unit with nothing after it to absorb.)
   - A code unit that has **no well-formed UCN** — a lone surrogate (`0xd800`–`0xdfff`) or a value beyond
     the Unicode range (`> 0x10ffff`, reachable only at width 4) — returns `null`, and `readStringLiteral`
     abandons the whole literal so the hint declines rather than emit ill-formed text.

The octal-vs-UCN split is the deliberate, non-obvious choice. Octal cannot represent a unit above `0777`
(511), so it cannot carry a 16- or 32-bit code point; the UCN can, and both forms are fixed-width and
therefore non-greedy, which `\x…` is not. Routing `<= 0x7f` through octal and `>= 0x80` through the UCN
keeps narrow output byte-for-byte identical to `#37-10k` while giving wide output a faithful, compilable
form for every representable code point.

The renderer is added identically to both the placement
([`CppPlacementConstructionDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppPlacementConstructionDriver.java))
and heap
([`CppConstructorDriver`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppConstructorDriver.java))
drivers as per-form twins; per the [DD-0026](0026-rec37-cpp-delete-driver.md) rule-of-three convention the
band follows, the extraction still waits for a genuine third argument-rendering user.

## Consequences

- A `wchar_t*` / `char16_t*` / `char32_t*` string-pointer constructor argument now **renders**
  `new C(L"Hi")` / `new C(u"Hi")` / `new C(U"Hi")` (and the placement twins `new (param_1) C(L"Hi")` …)
  instead of declining. Verified end to end through the Rec 30 headless
  `AbstractDecompilerHighFunctionTest` harness (DD-0023):
  [`CppConstructorDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppConstructorDriverTest.java)
  and
  [`CppPlacementConstructionDriverTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppPlacementConstructionDriverTest.java)
  each add five cases over the shared `decompileMakeWithPtrArg` / `placementWithPtrArgFixture` helper
  (which parameterise the pointee type and the global bytes): the `L"Hi"` / `u"Hi"` / `U"Hi"` prefix
  renders, an escaped wide case (units `A` / tab / `0x01` / `0x20ac` → `u"A\t\001€"`, exercising a
  named escape, the octal path, *and* the `\u` UCN path), and a lone-surrogate decline
  (`0xd800` → no hint, confirming the never-wrong contract). All earlier `#37-10a`–`k` render and decline
  cases still hold (heap 30/30, placement 30/30).
- **What this unblocks / defers:** the common unnamed-string-pointer shapes — narrow and wide — now all
  render. Still deferred to later `#37-10` work: compound-expression arguments (a computed value with no
  name still declines) and `DataType`-signature / template / operator rendering. A wide string containing a
  lone surrogate or an out-of-range unit deliberately declines rather than guess.
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Decompiler:integrationTest --tests 'ghidra.app.decompiler.CppConstructorDriverTest' --tests 'ghidra.app.decompiler.CppPlacementConstructionDriverTest'`
  (30/30 + 30/30), system `gradle` 8.5.
