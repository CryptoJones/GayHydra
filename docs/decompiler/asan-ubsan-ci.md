# ASan / UBSan CI for the C++ Decompiler

*Addresses Rec 15 of the 2026-05-21 principal-architect audit.*

## What this enables

The decompiler's Makefile carried the line

```make
#decomp_test_dbg:	DBG_CXXFLAGS += -D_GLIBCXX_ASSERTIONS -fsanitize=address,undefined
```

commented out for years. The audit identified this as low-cost,
high-EV: with ~2,740 raw `new`/`delete` sites across 187k LOC of
parser-heavy code, AddressSanitizer + UndefinedBehaviorSanitizer
will surface real bugs the moment they are run.

This rec wires the flags into a new make target (`test_san`) without
disturbing the default `test` target, then runs that target nightly
in CI.

## What changed

- **`Ghidra/Features/Decompiler/src/decompile/cpp/Makefile`**: added
  `decomp_test_san` and `test_san` targets. The existing
  `decomp_test_dbg` and `test` targets are unchanged so existing
  workflows are not disturbed.
- **`.github/workflows/decompiler-sanitizers.yml`**: nightly job
  (plus pull-request trigger on any change under `decompile/`) that
  builds and runs the sanitized tests on Ubuntu.

## Sanitizers selected

- **AddressSanitizer (`-fsanitize=address`)** — heap overflows,
  use-after-free, double-free, stack overflow, leaks. Highest signal
  for a parser of attacker-controlled input.
- **UndefinedBehaviorSanitizer (`-fsanitize=undefined`)** — signed
  overflow, null dereference, divide-by-zero, alignment violation,
  bad cast. Catches latent bugs invisible under "this compiles and
  runs."
- **`-D_GLIBCXX_ASSERTIONS`** — libstdc++ debug assertions on
  iterators and container indexing.
- **`-fno-omit-frame-pointer`** — readable sanitizer stack traces.

Not enabled yet:

- **MemorySanitizer** — requires an MSan-built libc++ and all
  dependent libraries; cost too high to land in one change. Tracked
  as a follow-up.
- **ThreadSanitizer** — not useful until threading semantics in
  the decompiler are documented (Rec 35 / Rec 34).

## CI behaviour

The job runs on every PR that touches `Ghidra/Features/Decompiler/src/decompile/`
and on a nightly schedule. A red sanitizer job blocks merge on
touching PRs but does not block other PRs from landing.

Failure artifacts (the test binary + any log files) are uploaded on
failure so a reproducer survives the runner cleanup.

## Coordination with OSS-Fuzz (Rec 13)

OSS-Fuzz runs the C++ fuzz harnesses (Rec 13) under ASan, UBSan, and
optionally MSan. Findings in OSS-Fuzz should reproduce locally
under the same sanitizer build the CI job produces here. The
shared compiler (`clang++`), the shared `-fsanitize` flags, and the
shared `_GLIBCXX_ASSERTIONS` choice mean a fuzz crash and a CI
crash are the same crash.

## Running locally

```
cd Ghidra/Features/Decompiler/src/decompile/cpp
CXX=clang++ make -j$(nproc) test_san
```

To produce a sanitized binary without running the tests:

```
CXX=clang++ make decomp_test_san
```

Sanitizer options are set in the workflow via `ASAN_OPTIONS` and
`UBSAN_OPTIONS`. Defaults match what the CI job uses, so a local
reproducer behaves the same way.

## Maintenance commitment

- New decompiler code is expected to compile cleanly under
  `-fsanitize=address,undefined`. If a PR introduces a sanitizer
  failure, fix-or-revert; the test gate is real.
- Suppression files (for known third-party issues) live at
  `Ghidra/Features/Decompiler/src/decompile/cpp/.asan-suppressions`
  and `.ubsan-suppressions`. They start empty.
- The nightly job is monitored by the build/release maintainer
  ([MAINTAINERS.md](../../MAINTAINERS.md)); sustained red is a P1.
