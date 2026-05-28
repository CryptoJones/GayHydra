---
number: 0004
title: Windows path for the C++ decompiler unit-tests workflow
status: accepted
date: 2026-05-28
audit_rec: 24
---

# Decision 0004: MSVC + CMake (eventually) for `decompiler-cpp-tests` on Windows; defer until libbfd is dealt with

## Context

Rec 24 of the 2026-05-21 audit asks for Windows coverage on the
C++ decompiler unit-tests workflow
(`.github/workflows/decompiler-cpp-tests.yml`), which currently
runs only on `ubuntu-latest`. The workflow comment notes:

> macOS dropped: TEST_DEBUG_OBJS pulls in
> `analyzesigs`/`bfd_arch`/`loadimage_bfd`/`codedata` via the
> EXTRA wildcard, which require libbfd. Apple's bundled binutils
> predates the bfd.h we need, and Homebrew's binutils is
> keg-only with non-trivial CPPFLAGS/LDFLAGS plumbing. Revisit
> once bfd-on-macOS is sorted.
>
> Windows is its own toolchain story (MSVC); land that as a
> follow-up workflow rather than overload this one.

This DD captures the design choices we considered and the path
forward.

## What the test binary needs to build

`decomp_test_dbg` is built by the GCC-flavored
`Ghidra/Features/Decompiler/src/decompile/cpp/Makefile`. Its
inputs:

- `g++` with `-std=c++20`.
- GNU `make`.
- `bison` and `flex` (for `xml.y`, `grammar.y`, `pcodeparse.y`,
  `slghparse.y`, `slghscan.l`).
- `libbfd` (`-lbfd` linker flag; from GNU binutils). Pulled in
  by `TEST_DEBUG_OBJS` via `analyzesigs.cc`, `bfd_arch.cc`,
  `loadimage_bfd.cc`, `codedata.cc`.

The Makefile has `ifeq($(OS),Linux)` and `ifeq($(OS),Darwin)`
branches; no Windows branch.

## Options considered

### (a) Install MinGW-w64 + binutils on `windows-latest`, reuse the Makefile

Add a Windows matrix entry to the existing workflow. Install
MinGW-w64, MSYS2's `make`/`bison`/`flex`/`binutils-dev` via
chocolatey or `msys2-installer`. The existing Makefile would
compile under MinGW's `g++` essentially unmodified.

**Pros**: smallest CI diff; reuses one Makefile across all three
platforms; fastest path to a working Windows lane.

**Cons**:

- **Toolchain drift.** `release.yml` already builds the Windows
  Ghidra zip on `windows-latest` using **MSVC** (the Build Tools
  2022 channel) via `gradle buildGhidra`. Adding MinGW just for
  the unit-tests workflow means two distinct Windows compilers
  in the same repo's CI surface, with different ABI, different
  CRTs, and different bug sets. Anything we measure with MinGW
  doesn't transfer to the MSVC build a user actually runs.
- **libbfd licensing edge.** Binutils is GPL-3+. The decompiler
  links libbfd dynamically (`-lbfd`), which the GPL linking
  exception covers, but only as long as the resulting binary is
  itself GPL-compatible-licensed. Ghidra is Apache 2.0 + the
  decompile/cpp tree is "BSD-2-Clause-Patent" per upstream
  `LICENSE`. Cross-license carry-along is settled in our favor
  (Apache+BSD with GPL linker dep produces a binary distributed
  under the GPL), but it's a license boundary we'd be deepening
  for a test binary; the Linux path already pays this cost, but
  expanding to Windows propagates it.
- **MSYS2 paths break `gradle`'s native-platform detection.**
  The release.yml Windows job's `getCurrentPlatformName()` is
  hard-coded around `win_x86_64`; MinGW shells report different
  triples. We'd be teaching gradle a second platform name just
  for this workflow.

### (b) MSVC + a new `CMakeLists.txt` + a Windows libbfd replacement

Write a `Ghidra/Features/Decompiler/src/decompile/cpp/
CMakeLists.txt` that drives MSVC `cl.exe` for the same source
tree. Replace `libbfd` for the four files that depend on it
(`analyzesigs.cc`, `bfd_arch.cc`, `loadimage_bfd.cc`,
`codedata.cc`) — either by:

- (b.1) Bundling a minimal Windows port of libbfd (binutils
  proper builds under MSVC with substantial patching but isn't
  shipped that way; LLVM has a partial BFD-like interface via
  `llvm-bfd` that's also licensed differently).
- (b.2) Disabling the BFD-dependent test sources for Windows
  (the `EXTRA` wildcard in the Makefile already controls
  this — make the Windows branch exclude the four files).
- (b.3) Stubbing the libbfd API with a minimal in-tree
  replacement that returns "unsupported" for the load-image
  paths.

**Pros**: matches the project's existing MSVC Windows surface;
no GPL linking; long-term right answer (also unblocks macOS
once the same approach is plumbed there).

**Cons**: multi-day engineering. CMakeLists.txt has to
reproduce ~250 lines of Makefile logic (the `OS`/`ARCH`
matrix, `ifeq($(MAKECMDGOALS),...)` per-target conditionals,
TEST_DEBUG_OBJS construction). The BFD-substitute decision
affects test coverage (path b.2 silently drops signature/
ELF-loading test coverage on Windows; b.3 needs careful audit
of which BFD calls the test paths actually exercise; b.1 is
its own multi-week project).

### (c) Run the existing Linux workflow inside WSL2 on `windows-latest`

Use `Microsoft/setup-wsl@v1` to install WSL2 + Ubuntu, run the
same `make decomp_test_dbg` inside WSL2. The binary that comes
out is a Linux ELF that runs under WSL — not native Windows.

**Pros**: zero change to the Makefile. Smallest CI diff.

**Cons**: doesn't actually test the *Windows* code path. The
binary is Linux-ABI. If we want native-Windows decompiler
testing, this is theater. Useful only if the goal is "run the
decompiler tests from a Windows-runner-shaped budget item,"
which isn't Rec 24's intent.

## Decision

Path **(b)** is the long-term right answer, but it is **not**
narrow-PR scope. It's a strategic sprint of its own, with a
clear gate item: pick a BFD-substitute approach (b.1/b.2/b.3)
and execute. Until that sprint runs, Rec 24 stays open in
backlog.

We **explicitly reject path (a)** to avoid carrying a second
Windows toolchain in the repo for a single workflow's
convenience. Toolchain consolidation matters more than
faster-Rec 24-close.

We **explicitly reject path (c)** because a Linux-ABI binary
under WSL2 is not Windows coverage.

## Consequences

- `decompiler-cpp-tests.yml` stays Ubuntu-only until the Rec 24
  strategic sprint runs. The workflow's existing comment
  (quoted above) is the source of truth for "why no Windows
  matrix entry yet"; this DD is the long-form expansion.
- macOS path is similarly blocked on the same libbfd story.
  Whichever direction the strategic sprint picks for Windows
  (b.1/b.2/b.3) sets the precedent for the macOS path: same
  approach should work both places.
- Rec 24 is moved from a "do this next" backlog row to a
  "strategic sprint pending BFD-substitute pick" row in
  `SprintPlanning.md`. Rec 23 (multi-OS unit tests) is
  unaffected — that's shipped at v26.1.14.

## What's *not* in scope for this DD

- The downstream macOS path (`Apple's bundled binutils
  predates the bfd.h we need`). Same blocker, same sprint
  candidate, but its own follow-up DD when the time comes.
- ASan/UBSan on Windows — runs as a separate workflow
  (`decompiler-sanitizers.yml`) and inherits the same
  blocker. Once Rec 24's sprint lands, the sanitizers
  workflow can adopt the same Windows path.
- The Win11 QEMU CI VM (`win11-ci` alias, used for
  out-of-band v26.1.10 + v26.1.11 builds). That VM is for
  Java/Gradle out-of-band rebuilds, not for the C++ test
  binary's CI lane; it doesn't change this decision.

## References

- `Ghidra/Features/Decompiler/src/decompile/cpp/Makefile`
  (canonical build system, GCC-flavored, no Windows branch).
- `.github/workflows/decompiler-cpp-tests.yml`
  (existing workflow, header comment documents the same
  rejection for macOS).
- `.github/workflows/release.yml`'s `build_sign_publish_windows`
  job (precedent for MSVC on `windows-latest`).
- 2026-05-21 audit Rec 24 (open).
