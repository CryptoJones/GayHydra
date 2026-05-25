# `samples/`

Sample inputs, binaries, and configurations distributed with the GayHydra fork. Not a runtime requirement — these are aids for users learning the tool and a smoke-test surface for the release pipeline.

## Subdirectories

| Path | What |
|---|---|
| [`re-targets/`](re-targets/) | Binaries (and source) intended to be reverse-engineered. Used both as user training material and as the post-build decompiler-sanity gate in `.github/workflows/release.yml`. |

## Where this differs from upstream NSA Ghidra

Upstream's `Ghidra/Extensions/sample/` holds *framework extension* samples — Java code showing how to write a Ghidra extension. The `samples/` tree here holds the complementary category: targets to point Ghidra *at*. The two categories don't overlap; both stay independently maintained.

## Adding a new sample

For an RE training target, drop it into `re-targets/<sample-name>/` with at minimum a `README.md` explaining the puzzle and a build recipe. If the sample is structured to support an automated smoke test (e.g. embeds a known-good constant that survives compilation), bundle a `scripts/Dump<thing>.java` Ghidra post-script that asserts the constant is recoverable from the decompiler view — see [`re-targets/gayhydra-dropper/scripts/DumpDeobfuscate.java`](re-targets/gayhydra-dropper/scripts/DumpDeobfuscate.java) for the historical example.

`release.yml` runs one decompiler-correctness gate post-build: the `decompiler-smoke` sample, which asserts only that the decompiler emits non-empty C for a known-trivial function. The prior constant-matching gates (gayhydra-dropper, crackme-arrayxor) were removed during the 2026-05-25 v26.1.x release backfill because they returned stable false negatives; `decompiler-smoke` is deliberately weaker so it catches catastrophic regressions without flapping on cosmetic output drift. Wiring another sample back in is a one-step block addition to `.github/workflows/release.yml`.
