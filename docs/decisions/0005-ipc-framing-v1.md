---
number: 0005
title: Decompiler ↔ Ghidra IPC framing v1 — design + 5-PR sequence
status: accepted
date: 2026-05-28
audit_rec: 33
---

# Decision 0005: IPC framing v1 — greeting + CRC32 + resync, v0 fallback active

## Context

The decompiler binary talks to Ghidra over `stdin`/`stdout` (or
a Unix socket in `consolemain` debug mode). The current "v0"
framing is in `ArchitectureGhidra::readToAnyBurst` /
`writeStringStream` (`ghidra_arch.cc:79–187`):

- Each chunk boundary is a 4-byte marker: `\0\0\1\NN` where `NN`
  is one of `0x04` (command start) / `0x05` (command end) /
  `0x0c`–`0x0f` (byte/string start/end).
- The payload is the raw bytes between two markers — no length
  prefix, no checksum, no version negotiation.
- Loss of a single byte in the stream desynchronizes the parser
  permanently: `readToAnyBurst` busy-loops on `s.get()` until it
  sees `\0…\1`, but a corrupted payload can contain that
  sequence by coincidence, and the parser will happily resume
  in the wrong frame.

Rec 33 of the 2026-05-21 audit ("IPC framing v1") asks for:

1. **Greeting** — explicit version negotiation at connection
   start so client + server agree on framing.
2. **CRC32** — per-frame integrity check; corruption is detected
   and the channel raises a known error rather than silently
   delivering wrong bytes.
3. **Resync** — after a CRC mismatch (or any framing error), the
   reader walks forward until it finds the next valid frame
   header, instead of permanent desync.
4. **v0 fallback active** — existing Ghidra Java clients that
   still speak v0 must keep working during a multi-quarter
   rollout window; new GayHydra clients negotiate v1.

This DD captures the wire format, the migration steps, and the
PR sequence.

## v1 wire format

```
+---------+--------+--------+---------+-----------+---------+
| MAGIC   | TYPE   | FLAGS  | LENGTH  | PAYLOAD   | CRC32   |
| 4 bytes | 1 byte | 1 byte | 4 bytes | LENGTH    | 4 bytes |
+---------+--------+--------+---------+-----------+---------+
```

| Field | Width | Value | Notes |
|---|---|---|---|
| `MAGIC` | 4 | `0xGH 0x11 0x01 0x00` | `0x47 0x48 0x01 0x00` — "GH" then framing-version 1.0. Big-endian (network order). The `0x47 0x48` is "GH" ASCII; the `0x01 0x00` are minor.major of the framing protocol, not the decompiler. |
| `TYPE` | 1 | 0x00 = greeting, 0x01 = command, 0x02 = response, 0x03 = response-byte-data, 0x04 = response-string, 0x05 = exception, 0x06 = continue, 0x7E = ping, 0x7F = error | One-byte enum. v0 had 8-ish distinct markers; we collapse + extend. |
| `FLAGS` | 1 | bit 0 = CRC32 present, bit 1 = compression (reserved), bit 2 = continuation (reserved) | Per-frame, not per-channel. v1 always sets bit 0. |
| `LENGTH` | 4 | payload length in bytes | Big-endian unsigned. Hard cap at 16 MB; over that the reader rejects the frame. Keeps a single bad length from making us allocate gigabytes. |
| `PAYLOAD` | LENGTH | raw bytes | XML, byte stream, etc. — semantics by `TYPE`. |
| `CRC32` | 4 | CRC32 over `TYPE \| FLAGS \| LENGTH \| PAYLOAD` | IEEE 802.3 polynomial (the one `crc32.cc` already implements). Big-endian. NOT over `MAGIC` — magic is a frame separator, not part of the protected message. |

Total header overhead: 10 bytes per frame (magic+type+flags+length).
Plus the 4-byte CRC trailer. 14 bytes of framing overhead per
message vs. v0's 8 (two 4-byte markers).

### Why this shape

- **Magic prefix instead of marker bytes** — gives the resync
  routine an unambiguous needle to search for. A corrupted
  payload byte can match a single `0x47` here and there, but
  hitting all four `MAGIC` bytes in order, followed by a valid
  `TYPE` and a `LENGTH` whose value plus the framing trailer
  ends at the next valid magic, is astronomically unlikely.

- **Big-endian throughout** — matches network byte order and
  the upstream Ghidra Java client's `DataInputStream` default.
  Saves a `htonl`/`ntohl` shim on the Java side.

- **Length precedes payload** — reader can `read(LENGTH)` in
  one shot; no need to scan for a terminator. Avoids the v0
  problem where the terminator byte sequence can occur inside
  a payload.

- **CRC32 over `TYPE | FLAGS | LENGTH | PAYLOAD`, not magic** —
  magic isn't part of the protected message; corruption that
  matches magic but corrupts payload triggers a CRC fail, not
  a misclassification.

- **Hard 16 MB length cap** — any larger decompile request /
  response should chunk. Prevents a malformed `LENGTH=0xFFFFFFFF`
  from making the reader hang or OOM.

## Greeting / version negotiation

At connection setup (server side: when `ghidra_process` starts
listening; client side: when Ghidra spawns the decompiler), each
side writes a single `TYPE=0x00` greeting frame as the **first**
bytes on the channel. Greeting payload:

```
+-----------+-----------+----------------+
| VERSION   | CAPABS    | IDENT (UTF-8)  |
| 2 bytes   | 4 bytes   | rest of LENGTH |
+-----------+-----------+----------------+
```

- `VERSION` — `0x01 0x00` for v1. Reader rejects mismatched
  major; bumps minor are forward-compatible.
- `CAPABS` — bitmask. Bit 0 = CRC32-required (default for v1),
  bit 1 = compression-supported (reserved). Reader takes the
  intersection of advertised capabilities.
- `IDENT` — free-text UTF-8 string like
  `"GayHydra/26.1.15 build=linux_x86_64"`. Logged but
  not policy-enforcing.

If a side starts and the first bytes on the wire are **not** the
v1 `MAGIC`, the side downgrades to v0 framing (the byte stream
is parsed by the legacy `readToAnyBurst` path) and emits a
single line to stderr identifying the peer as v0. This is the
v0 fallback active behavior the audit asked for.

## v0/v1 coexistence

| Server (decompiler) speaks | Client (Ghidra) speaks | Result |
|---|---|---|
| v0 only | v0 | works (today) |
| v0 only | v1 | client first-bytes are v1 magic → server's `readToAnyBurst` sees `\0\0\1\NN`-not-matching and exits-1, just like today. **No regression**. |
| v1 + v0 fallback | v0 | server sees first-bytes are not v1 magic → drops to v0 reader. **Works**. |
| v1 + v0 fallback | v1 | both send greeting, both verify CRC. **Native v1**. |

The v1 + v0 fallback path is what GayHydra ships. The legacy v0-only
code remains in `ghidra_arch.cc` unmodified; the v1 layer is a new
file that v0 path-falls-through to.

## Why not just use a real RPC library

The audit considered (and rejected) bringing in gRPC, Cap'n Proto,
or an off-the-shelf binary framing. Reasons in the audit:

- **Ghidra's Java side**: upstream maintainers reject heavyweight
  cross-language dependencies in the JVM tree. A pure-Java tiny
  framing reader is acceptable; pulling in a gRPC-Java runtime
  is not.

- **Single peer, single channel**: the decompiler talks to
  exactly one Ghidra instance over exactly one stdin/stdout pair.
  Full RPC machinery (service multiplexing, streaming, deadlines)
  is overkill.

- **Forward-compat is owned by us**: this fork's decompiler talks
  to this fork's GayHydra Java side and (optionally) upstream
  Ghidra. We control both ends of the v1 protocol; upstream
  Ghidra stays on v0 until/if they want to adopt.

## PR sequence

Each PR is independently mergeable and shippable. Each can ship
without the next one ever landing — the v0 fallback is the safety
net.

### PR #33-2.1 — `frame_v1.hh` / `frame_v1.cc` + unit tests

Pure C++ helper module — encode/decode of a single v1 frame.
No wiring into the IPC layer yet.

- `frame_v1.hh` declares `struct FrameV1Header`, `encode_frame`,
  `decode_frame`, `FrameError` (CRC mismatch / length cap /
  truncated input).
- `frame_v1.cc` implements them using `crc32.cc`'s existing
  `crc32` function.
- `testframe_v1.cc` unit tests: round-trip every TYPE value,
  CRC mismatch detection, length-cap rejection, truncated-input
  rejection, magic-mismatch rejection, resync (skip garbage,
  find next valid frame).

Add to `cppRaiiAudit`'s PROTECTED_FILES + the Makefile's test
target.

### PR #33-2.2 — server-side reader path with v0 fallback

`ghidra_arch.cc`: add `readFrameV1OrFallthrough(istream &s)`
that peeks the first 4 bytes; on v1 magic, parses the v1 frame
and returns the payload + type; otherwise rewinds (or buffers
the peeked bytes) and lets the existing v0 path run. Wire into
`GhidraDecompCapability::execute()`'s outer loop.

Tests: send v0 bytes, see v0 path taken; send v1 bytes, see
v1 path taken; send a v1 frame with bad CRC, see `FrameError`;
mid-stream corruption skips one v1 frame, resyncs on the next.

### PR #33-2.3 — server-side writer path

`ghidra_arch.cc`: add `writeFrameV1(ostream &s, uint1 type,
const string &payload)`. Wire into `writeStringStream` etc. as
"emit a v1 frame if the channel is v1-mode; otherwise emit the
v0 marker sequence." Channel mode is set during greeting at
connection start.

Tests: capture stdout into a `stringstream`, verify the bytes
match the documented v1 layout, CRC included.

### PR #33-2.4 — greeting handshake

On connection start (the first iteration of
`GhidraDecompCapability::execute()`), if the first 4 bytes are
v1 magic, parse the greeting frame, set channel mode = v1.
Otherwise leave channel mode = v0 (existing behavior).

Server-side writes its own greeting frame **after** parsing the
client's; reuses `writeFrameV1`. Identifies as `GayHydra/{version}
build={triple}`.

Tests: synthesize v1 greeting, verify channel transitions to v1
mode; synthesize bytes that aren't v1 magic, verify channel stays
v0.

### PR #33-2.5 — wire the Java side, flip default to v1

This PR touches `Ghidra/Features/Decompiler/src/main/java/.../
DecompileProcess.java` to:

- Emit a v1 greeting frame on connection.
- Read v1 frames if the server's greeting was v1, else stay v0.
- Add a `decompiler.framing=v0|v1|auto` system property; default
  `auto` (sends v1 greeting; falls back to v0 if the server
  doesn't reply v1).

Tests: the existing JUnit integration tests under `Features/
Decompiler/src/test/java/` exercise the end-to-end decompile
path; they should pass unchanged (v1 is wire-format-only, the
semantic XML payloads are identical to v0). Add one new test
that asserts v1 greeting bytes appear on the channel.

After this lands, **GayHydra clients use v1 by default**.
Upstream Ghidra Java still speaks v0; if a user invokes the
GayHydra decompile binary from upstream Ghidra, the v0 fallback
keeps it working.

## Open questions deferred to the implementation PRs

- Whether `compression` (FLAGS bit 1) lands in this sprint or a
  later one. The audit doesn't ask for it. Recommendation: skip
  for now, leave the bit reserved.

- Whether `ping` (TYPE 0x7E) is implemented in v1 or deferred.
  Recommendation: implement as a no-op responder in PR #33-2.2
  so the wire shape exercises a non-`0x00–0x06` value.

- Whether the v0 fallback ever gets removed. Recommendation:
  not in the v26.1.x line. Mark for removal in v27.x release
  notes when upstream Ghidra adopts v1 (won't happen unless
  this DD's protocol gets pushed upstream — out of scope per
  `feedback_no_upstream_prs.md`).

## What's *not* in scope for this DD

- Rec 34 (FlatBuffers schema for the request/response payloads).
  That's a separate strategic sprint that **runs on top of**
  this framing. The v1 frame doesn't care what's in its
  PAYLOAD; FlatBuffers vs. XML is the next layer up.

- IPC over anything other than stdin/stdout. The audit doesn't
  ask for socket-mode changes; this DD only refines the framing
  on the existing transport.

- A formal-grammar definition of the v1 wire format (e.g.
  ANTLR-style). Recommendation: prose + the ASCII art above
  is sufficient for a 14-byte header. Revisit if the protocol
  grows.

## References

- `Ghidra/Features/Decompiler/src/decompile/cpp/ghidra_arch.cc:79–187`
  — current v0 implementation (`readToAnyBurst`,
  `readStringStream`, `writeStringStream`).
- `Ghidra/Features/Decompiler/src/decompile/cpp/crc32.cc` —
  IEEE 802.3 CRC32 used by the SLEIGH compiler; reused
  unchanged for the frame CRC.
- 2026-05-21 audit Rec 33 (open until PR #33-2.5 lands).
- [DD-0004](0004-decompiler-cpp-tests-windows.md) — the
  pattern this DD follows (design-decision-first, then a sequence
  of narrow PRs).
