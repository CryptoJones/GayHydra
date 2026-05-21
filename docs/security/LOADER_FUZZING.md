# Loader Fuzz Harnesses

*Addresses Rec 14 of the 2026-05-21 principal-architect audit.*

## Why this exists

Every binary Ghidra opens — ELF, PE, Mach-O, DEX, PDB, DWARF, COFF —
is parsed by a loader written in Java that consumes attacker-shaped
input byte-by-byte. There is no public fuzz harness for any of them.
A malformed object file is the most common shape of "first thing
Ghidra touches when looking at a sample", so a fuzz-discoverable
crash in any loader is reachable from "the user opened the file."

This document is the integration plan for fuzzing the Java loaders.
The harness scaffold lives at:

- `Ghidra/Features/Base/src/test.fuzz/java/ghidra/app/util/bin/format/fuzz/`

## Tooling: Jazzer

We use [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer),
the industry-standard libFuzzer-compatible coverage-guided Java
fuzzer. Jazzer is the same engine OSS-Fuzz uses for every JVM
project it hosts (Tomcat, h2, Apache PDFBox, etc.).

Why Jazzer:

- Coverage-guided. Discovers paths libFuzzer-style, not by enumeration.
- libFuzzer-compatible CLI: same corpus format, same crash artifact
  format, same OSS-Fuzz integration story as the C++ decompiler
  harnesses (Rec 13).
- Apache 2.0 license.
- Maintained by Code Intelligence; the maintainers actively land
  fixes for OSS-Fuzz projects.

Each harness file is one `.java` with a `public static void
fuzzerTestOneInput(byte[] data)` method. Jazzer wraps it as a
libFuzzer entry point.

## Initial harnesses

The audit identified ELF, PE, and Mach-O as "the most-used three";
DEX, PDB, DWARF, and COFF follow in a second wave.

| Harness | Loader | Entry |
|---|---|---|
| `ElfFuzz` | `ghidra.app.util.bin.format.elf.ElfHeader` | `ElfHeader.createElfHeader(...)` over a `ByteProvider`. |
| `PeFuzz` | `ghidra.app.util.bin.format.pe.PortableExecutable` | `PortableExecutable.createPortableExecutable(...)`. |
| `MachoFuzz` | `ghidra.app.util.bin.format.macho.MachHeader` | `MachHeader(reader)`. |

Second-wave harnesses (open as follow-up issues once these three are
healthy): `DexFuzz`, `PdbFuzz`, `DwarfFuzz`, `CoffFuzz`.

## Harness contract

Each harness:

1. Wraps the fuzzer-supplied `byte[]` in a `ByteArrayProvider`
   (Ghidra's `ghidra.app.util.bin.ByteProvider` implementation).
2. Invokes the loader's parsing entry point.
3. Catches loader-defined exceptions
   (`ghidra.app.util.bin.format.*.exception.*`,
   `java.io.IOException`) and returns. These are *expected* on
   malformed input.
4. Lets all other exceptions escape — Jazzer treats them as findings.

The same rule as the C++ harnesses (Rec 13): an unparenthesised
`catch (Exception e)` is a bug in the harness.

## Local build & run

Prerequisites:

- Jazzer release jar (`jazzer-standalone.jar`) on the classpath.
- A built Ghidra (`gradle prepdev`).

Run a harness:

```
java -cp \
    Ghidra/Features/Base/build/libs/Base.jar:\
    Ghidra/Features/Decompiler/build/libs/Decompiler.jar:\
    Ghidra/Framework/.../*.jar:\
    .github/oss-fuzz/jazzer-standalone.jar \
    com.code_intelligence.jazzer.Jazzer \
    --target_class=ghidra.app.util.bin.format.fuzz.ElfFuzz \
    --reproducer_path=crashes/ \
    -max_total_time=600 \
    corpus_elf seeds_elf
```

A `scripts/run-jazzer.sh` wrapper computes the classpath automatically;
the long form is shown above so the contract is visible.

## Seed corpus

The seed corpus for each loader pulls minimal valid samples from:

| Loader | Seed source |
|---|---|
| ELF | `/usr/bin/ls` (Linux) and small `crt*.o` files; one statically-linked binary; one shared object. |
| PE | A minimal MSVC-built `hello.exe`; an MSYS2-built `.dll`; one signed binary; one delay-load binary. |
| Mach-O | `/bin/ls` (macOS), a universal binary, a dyld_shared_cache stub. |

Seeds live under `Ghidra/Features/Base/src/test.fuzz/seeds/<loader>/`
and are committed. License: every seed is a tiny, redistributable
upstream artifact or a hand-built sample with source committed
alongside.

## OSS-Fuzz integration

OSS-Fuzz supports JVM projects via Jazzer. The `projects/ghidra-loader/`
manifest (added to google/oss-fuzz under that name) reuses the same
`.github/oss-fuzz/` layout as the C++ harnesses (Rec 13), with:

- `project.yaml` listing `language: jvm`.
- `Dockerfile` extending `gcr.io/oss-fuzz-base/base-builder-jvm`.
- `build.sh` running a focused Gradle subtree build (`gradle :Base:fuzzJar`)
  and shipping the harness classes + minimal classpath into `$OUT/`.

These three files will land in a follow-up commit once the Jazzer
classpath shape is verified on the upstream OSS-Fuzz infrastructure.

## Per-loader gotchas

### ELF

- `ElfHeader.createElfHeader` is intentionally permissive — it will
  parse a wide variety of malformed but recognizable ELF. The
  interesting findings are in the section-header / program-header
  walkers downstream; the harness should drive enough of the
  parser to reach them.
- `e_shoff` and `e_phoff` overflow checks are the classic
  attack-surface: feed gigantic offsets and watch for
  `ArrayIndexOutOfBoundsException` instead of clean errors.

### PE

- `PortableExecutable` has a long pipeline: DOS stub, NT headers,
  section table, optional COFF symbols, debug directory, resource
  tree. Each layer is a potential finding site.
- The resource tree (`.rsrc`) supports nested directories; recursion
  depth limits are not obviously enforced.

### Mach-O

- Universal binaries (`fat` headers) wrap multiple slices; the
  per-arch dispatch path is its own parser.
- `dyld_shared_cache` headers are out of scope for the initial
  harness (separate file shape).

## Maintenance

Same as Rec 13:

- OSS-Fuzz crash reports honour [SECURITY.md](../../SECURITY.md) timing.
- A red build is a P1; OSS-Fuzz disables sustained-red projects.
- Every new loader (or significant loader refactor) adds or updates a
  harness in the same PR.

## Open questions

1. Should the corpus include compiler-generated outputs from `gcc -Os`
   / `clang -Os` / `msvc /O1` to maximise structural diversity?
2. How to handle loaders that require a known processor architecture
   (DEX needs the Dalvik bytecode lifting)? Possibly mark them as
   Stage 2.
3. Should we differentially fuzz Ghidra's ELF loader against
   `llvm-objdump`'s on the same input, to surface semantic
   divergences as well as crashes?

Tracked in `docs/security/loader-fuzzing-followups.md` once initial
harnesses land.
