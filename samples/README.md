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

`release.yml` does **not** currently run any decompiler-correctness smoke test — the dropper + crackme-arrayxor gates were removed during the 2026-05-25 v26.1.x release backfill (they had begun returning stable false negatives and were blocking every release publish). Wiring a new sample back in would be a one-step block addition to `.github/workflows/release.yml`; consult `git log -- .github/workflows/release.yml` for the prior shape of the step.
