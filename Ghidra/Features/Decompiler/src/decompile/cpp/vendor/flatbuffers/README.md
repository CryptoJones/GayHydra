# Vendored FlatBuffers C++ runtime headers

Upstream: https://github.com/google/flatbuffers
Version:  v25.12.19
License:  Apache License 2.0 (see `LICENSE` in this directory)

Source tarball:
`https://github.com/google/flatbuffers/archive/refs/tags/v25.12.19.tar.gz`
sha256 `f81c3162b1046fe8b84b9a0dbdd383e24fdbcf88583b9cb6028f90d04d90696a`

## What is vendored

Only the **runtime** header closure of `flatbuffers/flatbuffers.h` — the
16 headers needed to read and write FlatBuffers-generated tables. The
schema-compiler headers (`idl.h`, `flatc.h`, `code_generators.h`,
`reflection*.h`, `minireflect.h`, `registry.h`, `grpc.h`, `flexbuffers.h`,
`util.h`, …) are deliberately **not** vendored: they belong to the `flatc`
toolchain, which generates bindings at build time and is not part of the
decompiler's runtime. Keeping the surface minimal limits the third-party
code that ships in the decompiler binary.

The 16 vendored headers (`include/flatbuffers/`):

    allocator.h          buffer.h          flatbuffer_builder.h  table.h
    array.h              buffer_ref.h      flatbuffers.h         vector.h
    base.h               default_allocator.h  stl_emulation.h    vector_downward.h
    detached_buffer.h    string.h          struct.h              verifier.h

## How this is consumed

Nothing in the decompiler `#include`s these headers yet. This is the
vendoring step (Rec 34 PR #34-2). The schema (`decompile.fbs`) and the
generated bindings that `#include "flatbuffers/flatbuffers.h"` land in
PR #34-3, which also adds `-Ivendor/flatbuffers/include` to the build.

## Updating

Re-run the closure extraction against a new upstream tag, replace the
headers verbatim, refresh the version + sha256 above, and update the
`certification.manifest` entries for any added/removed files. Do not edit
the vendored headers by hand — modifications break the "verbatim upstream"
guarantee and the provenance sha.

## Why FlatBuffers / why these files

See `docs/decompiler/IPC_SCHEMA.md` (Rec 34 design, PR #34-1) for the
candidate evaluation (FlatBuffers vs Cap'n Proto vs Protobuf) and the
schema scope.
