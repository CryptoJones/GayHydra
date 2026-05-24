# `samples/re-targets/`

Binaries (and source) intended to be reverse-engineered with GayHydra. Each sample is:

- A self-contained build, runnable with the language's standard toolchain (Go, Cmake, etc.).
- Designed to exercise a specific RE skill (string deobfuscation, control-flow recovery, packed-binary unpacking, anti-debug detection, etc.).
- Safe to run as a non-root user — failures are non-destructive.
- Wired (optionally) into the release pipeline as a decompiler-sanity smoke test.

## Catalog

| Sample | Language | Skill exercised | Smoke-tested in release.yml? |
|---|---|---|---|
| [`gayhydra-dropper/`](gayhydra-dropper/) | Go | XOR-with-constant string deobfuscation (key `0x5A`) | ✅ yes |
| [`crackme-arrayxor/`](crackme-arrayxor/) | Go | Per-position XOR with a 16-byte key array — array-scoped deobfuscation (harder than constant XOR) | ✅ yes (data-segment signal, complementary to the dropper's decompiler-output signal) |

## Why these exist

Two reasons, per [`gayhydra-dropper/README.md`](gayhydra-dropper/README.md):

1. **User training.** Ghidra / GayHydra users learning the tool need realistic-but-small binaries to practice on. Each sample is sized to be readable end-to-end (~150 LOC of source) and exercises one specific deobfuscation/recovery skill.

2. **Release-pipeline smoke test.** Each sample's `scripts/Dump*.java` post-script asserts that GayHydra's decompiler can still recover the obfuscated artifact (XOR key, packed buffer, etc.). If a future GayHydra release breaks the decompiler — or the Go/Sleigh/analyzer chain regresses — the smoke test fails, gating the release before users see it.

The first dogfood run of the dropper sample against v26.1.6 caught three real regressions: Go 1.25/1.26 analyzer crash (NSA/ghidra#9219), JDK 21.0.10+ headless launch collision (NSA/ghidra#9220), and the cyclonedx-plugin schemaVersion enum/String mismatch ([PR #245](https://github.com/CryptoJones/GayHydra/pull/245)).

## Contributing a new sample

See [`../README.md`](../README.md) → "Adding a new sample". Minimum bar: README + build recipe. Stronger contribution: also wire a smoke-test post-script.
