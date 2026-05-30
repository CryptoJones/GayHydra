# Decompiler IPC schema (Rec 34)

`decompile.fbs` is the FlatBuffers schema for the typed payloads exchanged
between the Java decompiler host (`DecompInterface` / `DecompileProcess`) and
the C++ decompiler worker (the `ghidra_process.cc` command loop). See the
design in [`docs/decompiler/IPC_SCHEMA.md`](../../../../../../../docs/decompiler/IPC_SCHEMA.md).

## Status

Schema only (PR `#34-3a`). The generated C++ and Java bindings are committed
separately — C++ in PR `#34-3b`, Java in PR `#34-3c` — and the host/worker
dual-encode migration that actually uses them lands command-by-command in
`#34-4`..`#34-6`. Nothing reads or writes these messages yet; this file is
inert until then.

## Scope

The schema covers every command the existing protocol already supports — no
new IPC commands are introduced. The seven commands mirror the `GhidraCommand`
subclasses in `ghidra_process.hh`: `RegisterProgram`, `DeregisterProgram`,
`FlushNative`, `DecompileAt`, `StructureGraph`, `SetAction`, `SetOptions`.

## Regenerating bindings

Bindings are generated with the pinned FlatBuffers schema compiler, **flatc
v25.2.10** — the same release as the vendored Java runtime jar
(`flatbuffers-java-25.2.10.jar`, PR `#34-2b`) and forward-compatible with the
vendored C++ runtime headers (`v25.12.19`, PR `#34-2a`): flatc 25.2.10
generated code links against the newer C++ runtime, which preserves backward
compatibility with code emitted by an older compiler.

    flatc --cpp  -o <cpp-out>  decompile.fbs
    flatc --java -o <java-out> decompile.fbs

The Linux flatc binary used for the committed bindings is
`Linux.flatc.binary.g++-13.zip` from
<https://github.com/google/flatbuffers/releases/tag/v25.2.10> (zip sha256
`6f01258d7475806f375d6da66a61df47add8016edd73f1774673f37b80b9a711`).

## Conventions

- Definitions are ordered **define-before-use**; flatc 25.2.10 rejects forward
  references.
- Each message is encoded as its own FlatBuffers root, selected by the Rec 33
  frame's command id.
- Payloads the legacy protocol carries as opaque XML documents (processor /
  compiler specs, the control-flow `<block>` graph, the `<optionslist>`) are
  modelled as `string`, carrying that same serialized document unchanged.
- The program id is kept as `string` to match the legacy wire, which passes it
  as a decimal-encoded string parameter.
