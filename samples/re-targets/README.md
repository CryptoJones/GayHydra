# `samples/re-targets/`

Binaries (and source) intended to be reverse-engineered with GayHydra. Each sample is:

- A self-contained build, runnable with the language's standard toolchain (Go, Cmake, etc.).
- Designed to exercise a specific RE skill (string deobfuscation, control-flow recovery, packed-binary unpacking, anti-debug detection, etc.).
- Safe to run as a non-root user — failures are non-destructive.
- Wired (optionally) into the release pipeline as a decompiler-sanity smoke test.

## Catalog

| Sample | Language | Skill exercised | Smoke-tested in release.yml? |
|---|---|---|---|
| [`gayhydra-dropper/`](gayhydra-dropper/) | _(binary removed; README + post-script retained as design notes)_ | XOR-with-constant string deobfuscation (key `0x5A`) | ❌ no (sample binary removed; the gate this fed was dropped to unblock the v26.1.x release backfill) |
| [`rot13-secret/`](rot13-secret/) | Go | ROT13 — a **non-XOR** cipher. Recognizing the `LEA -base` / `ADD $0xd` / `SHR $0xd` (constant-modulo trick) pattern. | ❌ no (no current decompiler-correctness gate runs in `release.yml`) |

## Why these exist

Two reasons, per [`gayhydra-dropper/README.md`](gayhydra-dropper/README.md):

1. **User training.** Ghidra / GayHydra users learning the tool need realistic-but-small binaries to practice on. Each sample is sized to be readable end-to-end (~150 LOC of source) and exercises one specific deobfuscation/recovery skill.

2. **Release-pipeline smoke test.** Each sample's `scripts/Dump*.java` post-script asserts that GayHydra's decompiler can still recover the obfuscated artifact (XOR key, packed buffer, etc.). If a future GayHydra release breaks the decompiler — or the Go/Sleigh/analyzer chain regresses — the smoke test fails, gating the release before users see it.

The first dogfood run of the dropper sample against v26.1.6 caught three real regressions: Go 1.25/1.26 analyzer crash (NSA/ghidra#9219), JDK 21.0.10+ headless launch collision (NSA/ghidra#9220), and the cyclonedx-plugin schemaVersion enum/String mismatch (CryptoJones/GayHydra#245, lives in the prior repo).

The dropper smoke test itself was removed from `release.yml` during the 2026-05-25 v26.1.x release-backfill effort — it had begun returning a stable false negative (XOR key not recovered in `main.main` decompilation) and was blocking every release publish. The dropper directory retains its README and `scripts/DumpDeobfuscate.java` as historical record of what the gate asserted; the binary (`main.go`) and Go module are gone.

## Contributing a new sample

See [`../README.md`](../README.md) → "Adding a new sample". Minimum bar: README + build recipe. Stronger contribution: also wire a smoke-test post-script.
