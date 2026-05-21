# Loader Fuzz Harnesses

Scaffold for Rec 14. See [`docs/security/LOADER_FUZZING.md`](../../../../../../../../../../../docs/security/LOADER_FUZZING.md)
for the full plan.

## Harnesses

| File | Loader |
|---|---|
| `ElfFuzz.java` | ELF (`ghidra.app.util.bin.format.elf.ElfHeader`) |
| `PeFuzz.java` | PE (`ghidra.app.util.bin.format.pe.PortableExecutable`) |
| `MachoFuzz.java` | Mach-O (`ghidra.app.util.bin.format.macho.MachHeader`) |

## Contract

Each harness:

1. Wraps the fuzzer-supplied `byte[]` in a `ByteArrayProvider`.
2. Invokes the loader's parsing entry point.
3. Catches loader-defined exceptions and `IOException` (expected on
   malformed input). Anything else escapes and triggers a finding.

An unparenthesised `catch (Exception e)` in a harness is a bug.

## Local run

See [`LOADER_FUZZING.md`](../../../../../../../../../../../docs/security/LOADER_FUZZING.md#local-build--run).
Briefly:

```
java -cp <ghidra-classpath>:jazzer-standalone.jar \
    com.code_intelligence.jazzer.Jazzer \
    --target_class=ghidra.app.util.bin.format.fuzz.ElfFuzz \
    seeds_elf
```

## Adding a new harness

1. Write `<Loader>Fuzz.java` here.
2. Add the loader row to the table in `LOADER_FUZZING.md`.
3. Add `projects/ghidra-loader/build.sh` entry for it in the
   OSS-Fuzz integration.
