# decompiler-smoke

A trivial C source + headless post-script wired into `release.yml` as the **decompiler-correctness gate** for every prebuilt before it ships.

## What this asserts

Only one thing: that the headless analyzer + decompiler can produce **non-empty** decompiled C for **at least one** function in a small known-good binary. No constant-matching, no symbol assertions, no string-content checks.

## Why it's deliberately weak

The prior `gayhydra-dropper` smoke test asserted that the literal constant `0x5A` survived from the source through to the decompiled C view. That gate kept blocking releases — sometimes for actual regressions, sometimes because the Go analyzer + decompiler combination drifted enough between Ghidra versions that the constant landed in a different shape (split across operands, folded into a different intrinsic, etc.). Net effect: 100% of release attempts after a certain window failed the gate, the v26.1.x release backfill stalled completely (see `Apologies.md` 2026-05-25), and the gate was eventually removed entirely (PR #11).

This replacement is sized to the actual class of failure we want a release-time gate to catch:

- **Build broke and produced a half-baked Ghidra** — caught: `analyzeHeadless` exits non-zero before the post-script runs.
- **Analyzer crashes on import** — caught: function manager is empty, `pickTarget` returns null, RESULT: FAIL.
- **Decompiler binary missing / native-library mismatch** — caught: `openProgram()` returns false.
- **Decompiler throws on a known-trivial input** — caught: `decompileCompleted()` returns false.
- **Decompiler returns empty body** — caught: explicit length check.

What this gate does **not** catch:

- Subtle decompiler-output drift (different operand grouping, renamed locals, etc.) — that's `audit-datatests.yml`'s job.
- Architecture-specific regressions outside `x86_64` — would need parallel matrix builds.
- Performance regressions — out of scope for a smoke test.

The other coverage (`decompiler-cpp-tests.yml` per-PR, `audit-datatests.yml` weekly) handles the precision side.

## Files

- `main.c` — ~10 lines: `main` calls a static `add_one` then `printf`. Two functions, one external call, trivial control flow.
- `scripts/AssertDecompiles.java` — picks `main` → `add_one` → first non-external function and asserts the decompiler produces non-empty C output for it. Prints `RESULT: PASS` or `RESULT: FAIL <reason>` to stdout.

## How `release.yml` invokes this

Identical shape to the prior dropper / crackme steps, with a different sample. See the `Decompiler smoke test (decompiler-smoke)` step in `.github/workflows/release.yml`.

To reproduce locally:

```bash
gcc -O0 -o /tmp/decompiler-smoke samples/re-targets/decompiler-smoke/main.c
PROJECT_DIR=$(mktemp -d)
/path/to/ghidra/support/analyzeHeadless "$PROJECT_DIR" SmokeRE \
  -import /tmp/decompiler-smoke \
  -postScript AssertDecompiles.java \
  -scriptPath samples/re-targets/decompiler-smoke/scripts \
  -deleteProject
```

Expected tail output: `RESULT: PASS` (or `RESULT: FAIL <reason>` if the gate would block release).
