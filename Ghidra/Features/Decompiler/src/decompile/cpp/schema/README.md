# Decompiler IPC schema (Rec 34)

`decompile.fbs` is the FlatBuffers schema for the typed payloads exchanged
between the Java decompiler host (`DecompInterface` / `DecompileProcess`) and
the C++ decompiler worker (the `ghidra_process.cc` command loop). See the
design in [`docs/decompiler/IPC_SCHEMA.md`](../../../../../../../docs/decompiler/IPC_SCHEMA.md).

## Status

Schema (`#34-3a`) plus the generated C++ bindings (`decompile_generated.h`,
`#34-3b`). The Java bindings are committed separately in `#34-3c`, and the
host/worker dual-encode migration that actually uses any of this lands
command-by-command in `#34-4`..`#34-6`. Nothing reads or writes these messages
yet; the bindings are inert until then.

## Scope

The schema covers every command the existing protocol already supports — no
new IPC commands are introduced. The seven commands mirror the `GhidraCommand`
subclasses in `ghidra_process.hh`: `RegisterProgram`, `DeregisterProgram`,
`FlushNative`, `DecompileAt`, `StructureGraph`, `SetAction`, `SetOptions`.

## Regenerating bindings

Each language's bindings are generated with the flatc release that **matches
that language's vendored runtime**, because flatc emits a hard
`static_assert(FLATBUFFERS_VERSION_{MAJOR,MINOR,REVISION} == ...)` into the
generated code pinning it to the exact runtime version it was generated with.
A version-skewed runtime is a compile error, not a forward-compatible upgrade —
so the C++ and Java sides legitimately use different flatc versions:

| Language | flatc        | Runtime                                              |
|----------|--------------|------------------------------------------------------|
| C++      | **v25.12.19**| vendored headers `vendor/flatbuffers/include` (`#34-2a`) |
| Java     | **v25.2.10** | vendored jar `flatbuffers-java-25.2.10.jar` (`#34-2b`)   |

The two runtimes differ (`25.12.19` vs `25.2.10`) only because Maven Central's
newest published `flatbuffers-java` artifact is `25.2.10`. This is safe: the
FlatBuffers **wire format** is stable across these minor versions, so a buffer
written by the `25.12.19` C++ runtime decodes in the `25.2.10` Java runtime and
vice versa. The exact-version `static_assert` is a *within-language*
codegen/runtime check, not a cross-language wire constraint.

    flatc --cpp  -o <cpp-out>  decompile.fbs    # flatc v25.12.19
    flatc --java -o <java-out> decompile.fbs    # flatc v25.2.10

The Linux flatc binaries used for the committed bindings are both
`Linux.flatc.binary.g++-13.zip`:

- C++  — <https://github.com/google/flatbuffers/releases/tag/v25.12.19>
  (zip sha256 `9f87066dc5dfa7fe02090b55bab5f3e55df03e32c9b0cdf229004ade7d091039`)
- Java — <https://github.com/google/flatbuffers/releases/tag/v25.2.10>
  (zip sha256 `6f01258d7475806f375d6da66a61df47add8016edd73f1774673f37b80b9a711`)

## Conventions

- Definitions are ordered **define-before-use**; flatc rejects forward
  references.
- Each message is encoded as its own FlatBuffers root, selected by the Rec 33
  frame's command id.
- Payloads the legacy protocol carries as opaque XML documents (processor /
  compiler specs, the control-flow `<block>` graph, the `<optionslist>`) are
  modelled as `string`, carrying that same serialized document unchanged.
- The program id is kept as `string` to match the legacy wire, which passes it
  as a decimal-encoded string parameter.
