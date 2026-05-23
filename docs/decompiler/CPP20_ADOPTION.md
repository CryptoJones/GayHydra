# C++20 Adoption

*Addresses Rec 32 of the 2026-05-21 principal-architect audit.*

## The current state

The decompiler's `Makefile` builds with `-std=c++11`. C++11
shipped in 2011; C++14 in 2014; C++17 in 2017; C++20 in 2020;
C++23 is now stable in major compilers. We are 14 years behind
the language standard for a parser-and-analysis codebase whose
correctness depends on lifetime, range, and error-handling
discipline — exactly the areas where the modern standard library
has matured.

The cost of staying on C++11 is not abstract. The features below
are directly applicable to the decompiler's most common bug
classes:

| Feature | What it replaces | Where it pays off |
|---|---|---|
| `std::span<T>` | `(T *, size_t)` parameter pairs | Every parser that walks a buffer — `xml.cc`, `marshal.cc`, every loader. |
| `std::expected<T, E>` | "return null + check, or throw" idioms | Every parsing function; makes failure visible in the type. |
| `std::format` | `printf`-flavoured assembly across the codebase | Error/diag messages with type-safety. |
| Ranges | Hand-rolled loops over containers | Pattern-driven IR walking; much more compact than current. |
| Concepts | SFINAE / `enable_if` workarounds | Template hygiene; better error messages. |
| `consteval` / `constexpr` widening | Runtime tables for opcode metadata | Move tables to compile-time; smaller binary, faster startup. |

## The decision

Move the decompiler to **C++20** in two steps:

- **Step 1: C++14.** Already conformant once the RAII migration
  (Rec 31) adopts `std::make_unique`. Mechanical bump of the
  Makefile's `-std=c++11` to `-std=c++14`.
- **Step 2: C++20.** Bump the standard, gate on CI green across
  Linux/macOS/Windows (Rec 23 must be landed first).

We skip a dedicated C++17 step because:

- C++17's headline features (`std::optional`, `std::variant`,
  structured bindings) are useful but rarely on the critical
  path for the bug classes we are trying to eliminate.
- C++20 includes everything in C++17 plus the high-EV additions
  in the table above. Doing 17 first means redoing the CI gate
  twice for marginal benefit.

We do *not* go to C++23 in this rec. C++23 compilers are mature
but not universally deployed (some user-environment clang/gcc
versions are still C++20-and-no-newer). Once C++23 support is
table stakes across the supported toolchains, that's a follow-up.

## Toolchain matrix

| Platform | Toolchain | C++20 ready? |
|---|---|---|
| Linux | gcc ≥ 10, clang ≥ 12 | Yes |
| macOS | Xcode 14+ / clang 14+ | Yes |
| Windows | MSVC 2019 16.10+ / 2022 | Yes |

Toolchain requirements are the compatibility matrix above; the
referenced `cpp20-toolchains.md` placeholder was folded into this
doc (the matrix is the requirements). See [`Ghidra/Features/Decompiler/buildNatives.gradle`](../../Ghidra/Features/Decompiler/buildNatives.gradle)
for the actual `-std=` flags currently in effect.

## Migration plan

Phased, like Rec 31 — but the C++ standard bump is much smaller
in scope than the RAII migration. Most C++11 code is also
valid C++20.

| PR | Scope |
|---|---|
| #32-1 (this PR) | This plan |
| #32-2 | ~~Bump to `-std=c++14` everywhere~~ — shipped: flipped `buildNatives.gradle` (Gcc + Clang), `decompile/cpp/Makefile`, and `fuzz/Makefile.fuzz` to `-std=c++14`. MSVC implicit default is already C++14. |
| #32-3 | Bump to `-std=c++20`; CI on Linux + macOS + Windows. No code change to opt into C++20 features; just verify the bump compiles. |
| #32-4 | Opportunistic `std::span` adoption in `xml.cc` + `marshal.cc` parameter pairs (paired with Rec 31 Stage 2). |
| #32-5 | Opportunistic `std::expected` adoption for parser entry points. |
| #32-6 | Opportunistic `std::format` adoption (replaces a portion of the error messages). |
| #32-7+ | Ranges, concepts, `consteval` table moves — opportunistic, no mass change. |

## Pairing with Rec 31

The RAII migration is the bigger lift; the C++20 bump rides
alongside it.

- Rec 31's Stage 1 (foundational types) lands at C++11/14.
- Rec 32's #32-2 (C++14) and #32-3 (C++20) land between
  Rec 31's Stage 2 and Stage 3.
- Rec 32's #32-4 (`std::span` adoption) happens *inside* Rec 31
  Stage 2 — same files, same PR. The two are paired.

After both rec series land:

- All ownership is visible in handle types (Rec 31).
- All buffer-walk parameters use `std::span` (Rec 32).
- The two together close the bulk of UAF + buffer-overrun risk.

## Toolchain-bump risk

The C++14 bump is risk-free — every supported toolchain has
C++14 for over five years.

The C++20 bump is **medium risk**:

- A user environment with old gcc (< 10) or clang (< 12) breaks
  the build. Mitigation: the [DevGuide](../../DevGuide.md)
  documents the new minimum; CI fails closed if the toolchain
  is older.
- MSVC's C++20 module support has been historically buggy. We
  do *not* adopt C++20 modules in this rec; only the standard
  language + library features.
- Some C++20 features (notably `std::format` in older Apple
  clang) are conditionally available. The codebase polyfills
  with `fmt::format` when `__cpp_lib_format` is absent.

## What's NOT in this rec

- **C++20 modules.** Modules are great in theory; in practice the
  ecosystem is still settling. We compile with headers.
- **Coroutines.** Useful elsewhere; not obviously applicable to
  the decompiler's analysis loop.
- **`std::jthread`.** The decompiler's threading model is
  bounded (currently one analysis thread per program); we don't
  need richer thread primitives until Rec 35 (bounded
  decompilation budgets) is in flight.

## Maintenance

- The Makefile's `-std=` flag is the single source of truth.
- A polyfill header (`Ghidra/Features/Decompiler/src/decompile/cpp/cpp20_compat.h`)
  bridges the small set of features where Apple clang lags.
  The header is documented and removed when the toolchain floor
  rises above its conditions.
- Toolchain floor is reviewed yearly. We do not silently raise
  it; raising it is its own PR with the rationale.
