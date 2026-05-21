# IPC Payload Schema Replacement

*Addresses Rec 34 of the 2026-05-21 principal-architect audit.*

## What Rec 33 fixed; what Rec 34 fixes

[Rec 33 (versioned framing)](IPC_VERSIONING.md) made the transport
layer survive corruption: greeting, CRC, length-prefix, resync.

Rec 34 is about what rides inside the frame's `payload` byte range.
Today the payload is a hand-rolled byte format coupled tightly to
`marshal.cc` / `xml.cc` on the C++ side and to mirroring Java code
on the host side. The encoding rules live in code, not in a
schema. Three problems follow:

1. **No differential testing.** There is no shared description of
   what a valid payload looks like. We cannot generate random valid
   payloads to feed to fuzzers (Rec 13), and we cannot diff two
   decoder implementations.
2. **No non-Java host.** A Rust front-end, a PyGhidra-only stack,
   or a network-RPC server cannot speak the protocol without
   reimplementing the byte format by reading the C++ code.
3. **Every breaking change is a coordinated release.** A new pcode
   field requires a synchronised change to both encoder and
   decoder; either side getting it wrong silently mis-decodes.

A schema-typed format solves all three: the schema is the
description; both sides generate code from it; new fields are
addable without breakage because the schema describes
forward-and-backward-compatibility rules.

## The choice: FlatBuffers vs Cap'n Proto vs Protobuf

Three candidates. Each gets a paragraph.

### FlatBuffers

- **Zero-copy reads.** The decoder is a pointer into the buffer;
  no field-by-field parse. Big win for the decompiler's hot
  loops over pcode bodies.
- **Tooling: mature.** Schema compiler is well-maintained, code
  generators for Java + C++ are first-class.
- **License:** Apache 2.0.
- **Schema evolution:** additive (new fields are optional,
  default to a known value).
- **Downsides:** the API is less idiomatic in both Java and C++
  than Protobuf's; verbosity at the call site is higher.

### Cap'n Proto

- **Zero-copy reads.** Same property as FlatBuffers.
- **Time-bound parsing.** Has explicit recursion depth + traversal
  limits as part of the API — directly relevant to the
  decompiler's malformed-input survivability.
- **Schema evolution:** additive + supports separate "versioned
  schema" concept.
- **Tooling: mature for C++,** Java support exists but is less
  battle-tested than FlatBuffers' Java.
- **License:** MIT.
- **Downsides:** Smaller community than FlatBuffers/Protobuf.

### Protobuf

- **Streaming and small message size.** Best message size of the
  three.
- **Tooling: extremely mature.** Universal Java + C++ support.
- **Schema evolution:** additive, default behaviour is forgiving.
- **Downsides:** No zero-copy — every read parses. Slower than
  FlatBuffers / Cap'n Proto for the decompiler's read pattern.
- **License:** BSD-3.

## The decision: FlatBuffers

The decompiler's traffic pattern is:

1. Java host encodes a request (small payload).
2. C++ worker decodes the request, runs analysis (long), encodes
   a response (potentially large — pcode bodies, type tables).
3. Java host decodes the response and renders it.

The expensive step is C++ decoding the request and Java decoding
the response. **Both decoders are the bottleneck**; both benefit
from zero-copy reads.

FlatBuffers wins on the *combined* axis of "zero-copy decode in
both Java and C++ + mature schema evolution + Apache-2.0 license +
the most stable Java story of the three." Cap'n Proto is
defensible (its time-bound parsing is real), but the Java
maturity gap is enough to tip the choice.

If Java's Cap'n Proto runtime closes the maturity gap before
implementation starts, we revisit. The decision is not religion.

## Schema scope (Stage 1)

The schema covers the operations the existing protocol already
supports. We are not introducing new IPC commands as part of
this rec; that would conflate two changes.

The Stage 1 schema (file: `Ghidra/Features/Decompiler/src/decompile/cpp/schema/decompile.fbs`):

```fbs
// Request frame: host -> worker.
namespace ghidra.ipc;

table DecompileFunctionRequest {
    program_id : string;
    function_address : uint64;
    timeout_ms : uint32 = 30000;
    flags : uint32 = 0;
}

// Response frame: worker -> host.
table DecompileFunctionResponse {
    status : ResponseStatus;
    pcode : [PcodeOp];
    high_function : HighFunction;
    diagnostics : [Diagnostic];
}

enum ResponseStatus : byte {
    OK = 0,
    TIMEOUT = 1,
    ANALYSIS_FAILED = 2,
    PARTIAL = 3,
}

table PcodeOp {
    opcode : uint16;
    output : Varnode;
    inputs : [Varnode];
    sequence_number : uint32;
}

table Varnode {
    address_space : uint8;
    offset : uint64;
    size : uint32;
}

table HighFunction {
    name : string;
    return_type : DataType;
    parameters : [HighSymbol];
    locals : [HighSymbol];
}

table HighSymbol {
    name : string;
    type : DataType;
    storage : Storage;
}

table DataType {
    name : string;
    size : uint32;
    kind : DataTypeKind;
}

enum DataTypeKind : byte {
    VOID, BOOL, INT, UINT, FLOAT, POINTER, ARRAY, STRUCT, UNION, ENUM, FUNCTION
}

table Storage {
    kind : StorageKind;
    address : uint64;
    space : uint8;
}

enum StorageKind : byte { REGISTER, STACK, MEMORY, HASH }

table Diagnostic {
    severity : Severity;
    message : string;
    pcode_seq : uint32;
}

enum Severity : byte { INFO, WARN, ERROR }

root_type DecompileFunctionRequest;
```

This is a minimal sketch; the full schema is generated by the
Stage 2 PR (#34-3) by walking the existing protocol surface.

## Migration plan

| PR | Scope |
|---|---|
| #34-1 (this PR) | This design doc — choose FlatBuffers, sketch schema |
| #34-2 | Vendor FlatBuffers (Apache 2.0): the C++ headers (single-header `flatbuffers.h`) and the Java runtime jar; pin versions |
| #34-3 | Land full schema (`decompile.fbs`); generated Java + C++ code committed to the tree |
| #34-4 | Dual-encode the decompile request: host writes both v0 (legacy) and v1 (FlatBuffers); worker prefers v1 if greeting v1+ |
| #34-5 | Migrate response path the same way |
| #34-6 | Migrate remaining IPC commands |
| #34-7 (one release after #34-4) | Remove v0 encode path; keep v0 decode for one more cycle |
| #34-8 (one release after #34-7) | Remove v0 decode path |

The deprecation window is **two release cycles** end-to-end. That
gives downstream packagers (Ghidra extensions that talk to the
decompiler directly) time to migrate.

## Coordination with Rec 33

Rec 33 ships the framing; Rec 34 ships the payload. The schema
rides inside the frame's `payload` bytes. The framing version
bump is independent of the schema version bump — a frame at
framing v1 can carry payload at schema v1, v2, etc.

## Coordination with Rec 13 (OSS-Fuzz)

After the schema lands, a **fuzz target generator** becomes
possible: given the schema, generate random valid messages,
feed them to the worker, look for crashes. This is differential
fuzzing against the schema specification.

A new harness `fuzz_ipc_schema` joins the OSS-Fuzz set in #34-9.

## Coordination with non-Java hosts

After the schema lands, a PyGhidra-only or Rust-only host can
generate its own bindings from `decompile.fbs` and talk to the
worker without depending on the existing Java host. The non-Java
host is not a goal of this rec; it's a side benefit that
forecloses on a class of "we can't do that because the protocol
is buried in Java" decisions.

## Risk: schema evolution discipline

A schema that grows fields ad hoc accretes the same coupling
problems the current protocol has. Mitigations:

- **Field deprecation requires a separate PR** with a written
  rationale.
- **Field additions are reviewed by both Java and C++
  maintainers** ([MAINTAINERS.md](../../MAINTAINERS.md)).
- **Schema changes go through the RFC process** (Rec 06) once
  the schema has shipped two minor versions.

## Performance expectation

Rough numbers from public FlatBuffers benchmarks on similar
workloads:

- Decode: ~10x faster than the current marshal.cc parser for
  large pcode bodies. Most of the win comes from skipping the
  XML parse on the C++ side (XML is currently still in the
  picture for some operations; the schema replaces it).
- Encode: similar to current, possibly slightly slower for
  small messages.
- Memory: lower allocator churn — the decoded message is a
  pointer into the buffer rather than a heap of objects.

These are not commitments; the real numbers ship in the #34-4
landing.

## What this does *not* do

- **Does not replace the Sleigh runtime's data formats.** Sleigh
  has its own on-disk `.sla` format; that is unchanged.
- **Does not replace project file formats.** `.gdt`, `.gpr` etc.
  stay on their current format. Rec 18 covers their hardening.
- **Does not introduce streaming RPC.** All IPC remains
  request/response with bounded payloads.
