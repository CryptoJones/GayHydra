# kuna core as a headless backend (third REA parity lane)

*Issue #479. Builds on the DecompileProcessFactory seam from #478 (PR #481).*

[kuna](https://github.com/Noelo-Lab/kuna) ships `kuna_ghidra`, a replacement
for Ghidra's native `decompile` process that speaks the decompiler wire
protocol. Pointing a GayHydra headless run at it gives REA a third lane next to
stock Ghidra and GayHydra:

| Lane | Java side | Decompiler core |
|---|---|---|
| 1 | stock Ghidra | stock `decompile` |
| 2 | GayHydra | GayHydra `decompile` |
| 3 | GayHydra | kuna `kuna_ghidra` |

Lane 3 catches provider code that assumes the exact shape of Ghidra's C output.
kuna's output is close to Ghidra's but not byte-identical. The lane also gives
Scylla's A/B parity harness a second engine at no cost.

## How the swap works

Nothing in the Ghidra install is copied or overwritten. Since #478,
`DecompileProcessFactory` picks the executable to spawn in this order:

1. `DecompileProcessFactory.setExecutable(path)` (runtime, for plugins);
2. the system property `ghidra.decompiler.exe`;
3. the environment variable `GHIDRA_DECOMPILER_EXE`;
4. the stock `os/<platform>/decompile`.

A value that does not name an executable file is reported once and ignored, so
a typo falls back to the stock core rather than to no decompiler. The script
uses the environment variable (3) because it reaches every launcher (the shell,
`analyzeHeadless`, PyGhidra, and REA's Ghidra provider) without editing
`launch.properties`.

### The framing option is required

The fork's IPC layer (decision 0005, `docs/decisions/0005-ipc-framing-v1.md`)
defaults to `decompiler.framing=auto`. In that mode the Java side opens with a
v1 greeting frame. `kuna_ghidra` speaks the stock v0 protocol and never answers
the greeting, so **`analyzeHeadless` hangs at the first decompile**. Forcing
legacy framing fixes it:

```
JAVA_TOOL_OPTIONS=-Ddecompiler.framing=v0
```

The script therefore sets both variables. The hang is a fork bug in the `auto`
negotiation, tracked as #491; this page only works around it.

## The script

`scripts/kuna-backend.sh` downloads the kuna release, picks the core for this
host and prints shell code. A script cannot change its caller's environment,
so `install` and `revert` are used with `eval`:

```bash
eval "$(scripts/kuna-backend.sh install)"   # latest kuna; or --version v1.980
scripts/kuna-backend.sh status              # which core a headless run spawns
scripts/kuna-backend.sh verify              # end-to-end check, below
eval "$(scripts/kuna-backend.sh revert)"    # stock core again (--purge drops the cache)
```

- **install** fetches `kuna-v<ver>-KunaDecompiler-ghidra_<ghidra>.zip` from the
  kuna releases into `${KUNA_CACHE_DIR:-~/.cache/gayhydra/kuna}/<ver>/`,
  unpacks it, and selects `KunaDecompiler/os/<platform>/kuna_ghidra`. The zip
  ships `linux_x86_64`, `linux_arm_64`, `mac_x86_64`, `mac_arm_64` and
  `win_x86_64`. The `ghidra_<ver>` part of the name is the stock Ghidra version
  kuna built its GUI extension for; the headless core does not depend on it.
  On macOS the quarantine attribute is cleared; the binary is ad-hoc signed.
  It prints `export GHIDRA_DECOMPILER_EXE=...` and a `JAVA_TOOL_OPTIONS` that
  keeps your existing options and adds `-Ddecompiler.framing=v0`.
- **revert** prints `unset GHIDRA_DECOMPILER_EXE` and removes only the framing
  token from `JAVA_TOOL_OPTIONS`.
- **status** follows the factory's resolution order and reports the core
  (`KUNA`, `STOCK` or `CUSTOM`), where it came from, the framing, and whether
  the env file exists. With `GHIDRA_INSTALL_DIR` set, it also warns when that
  install predates #478, since such a build ignores the variable.
- **verify** runs the recipe below.
- **env-file** prints the path of the file `install` writes.

### REA and other launchers

`install` also writes an env file that makes the same changes when sourced.
Source it in whatever starts the process that launches Ghidra, for example the
shell or wrapper that starts REA's MCP server:

```bash
. "$(scripts/kuna-backend.sh env-file)"
```

Ghidra child processes inherit both variables, so every decompiler process
that REA's Ghidra provider spawns uses the kuna core. Restart the server
after `install` or `revert`: decompiler processes that are already running
keep their core.

## Verification recipe

`scripts/kuna-backend.sh verify [binary|- [function]]` needs `GHIDRA_INSTALL_DIR`
pointing at a GayHydra build that includes #481. With no binary, or `-`, it
compiles a small C sample with `cc`. The sample has two functions:
`kuna_sample` (straight-line, the default) and `kuna_loop` (a loop, which shows
the `edgelabel` gap below: `verify - kuna_loop`). It then runs:

```bash
"$GHIDRA_INSTALL_DIR/support/analyzeHeadless" <tmp> kuna-verify \
    -import <binary> -deleteProject \
    -scriptPath scripts/ghidra_scripts -postScript KunaMarkerCheck.java [function]
```

`KunaMarkerCheck.java` prints `DecompileProcessFactory.getExecutablePath()`,
decompiles one function, and prints the C followed by one result line:

- `KUNA_MARKER_FOUND <function> /* Kuna v<ver> */`: the kuna core answered;
- `KUNA_MARKER_ABSENT <function>`: a decompiler answered without the marker,
  so the stock core ran;
- `KUNA_CHECK_ERROR <reason>`: no function found, or the decompile failed.

The pass condition is the marker. The run log should also show the factory's
line `Decompiler executable taken from environment variable GHIDRA_DECOMPILER_EXE: ...`.
A watchdog (`KUNA_VERIFY_TIMEOUT`, default 300 s) kills the whole
`analyzeHeadless` process group (launcher, JVM and decompiler core) if the run
hangs, so a missing framing option fails the check instead of blocking it.

To check by hand without the script, run the same `analyzeHeadless` command
with the two variables exported and look for the marker in the output.

### Results

Run on 2026-10-23 with kuna v1.980 and JDK 21:

| Platform | GayHydra | stock core | kuna, `kuna_sample` | kuna, `kuna_loop` | kuna, framing `auto` |
|---|---|---|---|---|---|
| mac_arm_64 (macOS 26.6) | master `3fef84cd` | `KUNA_MARKER_ABSENT` (correct) | `KUNA_MARKER_FOUND /* Kuna v1.980 */` | `edgelabel` decode error | hangs; watchdog kills it |
| linux_x86_64 | build `ff0b17d2` (has #481) | `KUNA_MARKER_ABSENT` (correct) | `KUNA_MARKER_FOUND /* Kuna v1.980 */` | `edgelabel` decode error | hangs (#491) |

In every kuna run the log carried `Decompiler executable taken from
environment variable GHIDRA_DECOMPILER_EXE`.

## Known gaps

- **Functions with control flow fail to decode (`edgelabel`).** GayHydra's
  Java block-graph decoder (`PcodeBlock.BlockEdge.decode`, added in #163)
  requires an `edgelabel` attribute on every `<edge>`. Stock Ghidra has no
  such attribute, so `kuna_ghidra` never sends it. Any function whose result
  carries block edges (loops, branches) fails with `Pcode: Decoding error:
  Attribute edgelabel is not present`; straight-line functions decompile
  fine. This is a fork compatibility bug, not a kuna bug, and it limits lane 3
  to straight-line functions until the decoder treats the attribute as
  optional. It is not fixed here.
- **Unresolved globals and types.** kuna's engine cannot yet resolve some
  globals and types that Ghidra's core names from the program database. They
  render with the default names (`sub_…` for functions, `DAT_…` for data).
  Lane-3 diffs in these spots are expected and are not provider bugs.
- **Output is not byte-identical.** Whitespace, cast placement and variable
  naming differ from Ghidra's core. Compare structure (functions, calls,
  control flow), not text.
- **v0 framing only.** Until the fork's `auto` negotiation falls back
  correctly, lane 3 runs on legacy framing and skips the v1 CRC and resync
  path entirely. Lane 3 says nothing about v1 framing.
- **Built for stock Ghidra.** kuna tests `kuna_ghidra` against stock Ghidra
  12.1.x. Fork-only protocol extensions stay off: schema-v1 payloads
  (`decompiler.schemapayload`) need capability bits from the v1 greeting, and
  a v0 channel has no greeting, so the Java side keeps sending v0 payloads.
- **Builds before #481 ignore the variable.** They need kuna's older file-drop
  method (a `decompile` copy under `build/os/<platform>/`), which this script
  does not do.

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
