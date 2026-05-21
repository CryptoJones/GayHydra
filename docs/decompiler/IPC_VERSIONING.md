# Java ↔ Native IPC Versioning

*Addresses Rec 33 of the 2026-05-21 principal-architect audit.*

## What we have now

The decompiler's Java-side host and C++-side worker speak over a
custom byte-framed protocol. The framing is fixed magic markers
(`{0, 0, 1, X}` style) defined in `DecompileProcess.java:54-61`
and matched in the C++ `marshal.cc` / `xml.cc`.

What this protocol does not have:

- A **version handshake**. Either side sends bytes; the other side
  expects a fixed framing.
- A **length prefix** with bounds. A truncated or malformed frame
  walks the parser into undefined territory.
- A **CRC** or any integrity check.
- A **graceful resync**. One byte of corruption desynchronises the
  parser and the parent process kills the worker on the next
  protocol error.

The audit named the consequence: every "decompiler crashed"
message in the bug tracker is partly this protocol's fault. A
robust IPC layer would not kill the worker on a single corrupted
byte; it would resync, log, and continue.

## Scope: this rec is incremental versioning

This rec ships **versioned framing** that is wire-compatible with
the current protocol when both sides are at the current version,
and forward-compatible when sides differ.

Rec 34 is the bigger change: replace the framing with a schema
(FlatBuffers / Cap'n Proto). The two are sequenced: versioning
first because it's small and tractable; schema replacement after
when we have the deprecation window working.

## The handshake

On worker startup, the C++ worker writes a four-byte greeting:

```
0xDE 0xC0  major (u8)  minor (u8)
```

The Java host expects this greeting and replies with its own:

```
0xDE 0xC0  major (u8)  minor (u8)
```

If majors mismatch, both sides abort with a clear error message
("decompiler process is at version 5.3, host expects 5.x — try
re-running fetchDependencies").

If majors match but minors differ, both sides log the mismatch
and continue at the **lower** minor's feature set. Minor-version
differences are additive features (new operations, new query
fields); each side knows what it can use.

This is identical to how Postgres, the SSH wire protocol, and
TLS negotiate features. We are not inventing anything.

## The frame

After the handshake, every frame is:

```
[magic: 4 bytes][version: 1 byte][type: 1 byte]
[length: 4 bytes BE][payload: length bytes][crc32: 4 bytes BE]
```

- `magic` — fixed to `{0xDE, 0xC0, 0xDF, 0xCE}`. Distinct from
  the existing magic so a v1 host receiving v0 traffic (or
  vice versa) cannot silently misinterpret it.
- `version` — frame schema version (independent of the protocol
  major). Bump for breaking schema changes.
- `type` — payload type (request, response, error, partial-result,
  log).
- `length` — bytes of payload that follow.
- `payload` — type-dependent.
- `crc32` — CRC32 over `version || type || length || payload`.
  Using CRC32 (not CRC32C) for compatibility with `java.util.zip.CRC32`
  which is on every JDK.

## Resync

If the parser hits a bad CRC, a bad magic at a frame boundary, or
a length that goes past the stream:

1. Log the event with the position offset and the corrupted bytes.
2. Scan forward byte-by-byte for the next `magic` sequence.
3. Resume parsing from there.

A single bad byte costs ~16 bytes of skipped stream. The worker
does not exit; the host gets a partial-result frame for any
operation that was in-flight, and the system continues. The
audit's "one byte of corruption kills the decompiler process" is
solved.

## CRC choice

We pick CRC32 (the IEEE 802.3 polynomial) because:

- `java.util.zip.CRC32` ships on every JDK; no dependency.
- It catches >99.99% of single-bit and burst errors up to 31 bits.
- It is computationally cheap (CPU instructions on every modern
  CPU; for older CPUs, a software fallback is fast enough).

It is **not** a security check. A determined attacker who can
modify bytes in the stream can also compute the right CRC. The
CRC is for honest corruption — bit flips, truncation, framing
errors — not adversarial inputs. The Java↔native pipe is between
two trusted processes; the threat model is honest failure, not
attack.

## Backward compatibility

For one release cycle after #33 lands:

- A v1 worker (this PR's version) talking to a v0 host (legacy)
  sends the v0 framing if the v0 magic is observed first.
- A v0 worker talking to a v1 host: the v1 host detects the
  absence of the new greeting and falls back to v0 framing.
- Both sides log the downgrade.

After the deprecation cycle, the v0 framing is removed. The
removal is its own PR (PR #33-3 below).

## Sequencing

| PR | Scope |
|---|---|
| #33-1 (this PR) | This design doc |
| #33-2 | Implementation: greeting, frame, CRC, resync. Both Java and C++ sides. v0 fallback active. |
| #33-3 (one release later) | Remove v0 fallback. |

## Coordination with Rec 34 (schema replacement)

Rec 34 replaces the payload encoding with a schema. The framing
work in this rec is unchanged by Rec 34 — the schema rides
*inside* the `payload` byte range. The greeting, frame, CRC, and
resync logic are reused.

This sequencing is deliberate: framing is the smaller, more
contained change, and lands first. Schema is the larger change
and lands on top of the framing.

## Performance

The audit didn't call out a perf concern, but worth confirming:

- CRC32 over typical frame payloads (a few KB of pcode):
  microseconds. Below the noise floor of the parse step it
  protects.
- Hardware-accelerated CRC32 (CRC32C-style on x86_64, the IEEE
  polynomial available via `_mm_crc32_*` and an equivalent on
  ARM64) takes the cost to nanoseconds. We use the JVM's
  built-in implementation, which the JIT compiles down.

## What this does *not* fix

- Slow parses on the *content* of the frames. That's Rec 35
  (decompilation budgets) and Rec 36 (cache flush).
- Cross-version state migration. A worker that crashes in the
  middle of analysing a function still drops that function;
  framing resync helps the worker survive corruption, not
  the analysis state survive a crash.
- The semantic mismatches that schema-typed IPC (Rec 34)
  would catch. Framing alone catches transport errors, not
  semantic ones.

## Testing

A regression test fuzzes the framing: random byte-flip mutations
on a captured stream are fed to the parser; the parser must
either correctly resync or report a clean error, never crash or
loop. The test ships alongside #33-2 and joins the OSS-Fuzz
harness (Rec 13) on the C++ side.

## Maintenance

- Version major bumps are reserved for protocol-breaking changes;
  they should be rare.
- Version minor bumps are routine; document each in
  `docs/decompiler/ipc-protocol-history.md` (new file at PR #33-2).
- The CRC choice (CRC32 IEEE) is recorded as the contract; do
  not silently switch to CRC32C without a major bump.
