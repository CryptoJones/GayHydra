---
number: 0080
title: Rec 34 #34-10 — payload-v1 go-live; the #34-7 removal clock was void because every #34-4..#34-6 codec shipped deliberately inert (the live loop is v0 in both directions), so the band wires the codecs into the live loop behind greeting capability bits and a SCHEMA_PAYLOAD frame flag — requests before responses, smallest command first — and the v0-removal clocks re-key to one release after each direction's go-live
status: accepted
date: 2026-06-11
audit_rec: 34
---

# Decision 0080: the v0-removal clock re-keys to go-live, and go-live splits at the capability seam

## Context

The IPC_SCHEMA.md migration table schedules `#34-7` ("Remove v0 encode path; keep v0
decode for one more cycle") *one release after #34-4*. By tag arithmetic that clock has
run out: the `#34-4`..`#34-6` codecs and the `#34-9` fuzz harness all shipped in
v26.2.2 (2026-06-02), and v26.2.3 + v26.3.0 have released since with no Rec 34 work.

Grounding `#34-7` before building it (the `#40-5a`/truth-audit habit) surfaced that the
clock's precondition was never met. The table's `#34-4` row describes a *live*
dual-encode — "host writes both v0 (legacy) and v1 (FlatBuffers); worker prefers v1 if
greeting v1+". What actually shipped, deliberately and self-documentedly, is the
testable codec halves only:

- **Every codec is inert by design.** Each header/class says so itself
  (`ipc_request_codec.h:20-25`, `ipc_response_codec.h:40-41`, `ipc_lifecycle_codec.h:36-37`,
  `ipc_config_codec.h:37-38`, `DecompileRequestCodec.java:37-39`,
  `CommandRequestCodec.java:50-52`), every v26.2.2 CHANGELOG entry repeats it, and the
  C++ build enforces it: `FLATBUF_INCLUDE` exists only on the unit-test compile rules
  (`cpp/Makefile:228,372`), so the codecs *cannot compile into* `ghi_dbg`/`ghi_opt`
  today. The Java request codecs have zero main-source callers; the generated Java
  `*Response` classes have zero references anywhere, tests included.
- **The live loop is v0 in both directions.** `DecompInterface.decompileFunction` →
  `DecompileProcess.sendCommandTimeout` writes v0 marker bursts; the worker's
  `GhidraCapability::readCommand` (`ghidra_process.cc:475-497`) parses v0 bursts and
  `DecompileAt::rawAction` emits v0 `PackedEncode` responses. What IS live since 26.2.0
  is the Rec 33 *framing* v1 tunnel (DD-0005), which by its own comments carries the v0
  payload bytes verbatim.
- **No payload-version signal exists.** The greeting payload carries framing
  version + a CRC capability bit + ident only; `frame_v1::Type` has no schema
  discriminator; `FLAGS` bits 0–2 are assigned (CRC / compression-reserved /
  continuation-reserved). "Worker prefers v1 if greeting v1+" has nothing to key off.
- **No Java v1 response decoder exists at all** — the worker-side response encoders
  (`#34-5`) have no consumer in any language.
- **Part of the wire surface has no schema.** The signature commands
  (`generateSignatures`, `debugSignatures`, `get/setSignatureSettings` —
  `DecompInterface.java:961-1116`) and the worker-initiated mid-decompile callback
  queries (`ghidra_arch.cc` `PackedEncode` upcalls; `DecompileProcess.java:910-1124`)
  appear nowhere in `decompile.fbs`.

Executing `#34-7` literally today would therefore delete the **only** production encode
path and break all decompilation. The true next work is the wiring half that every
codec PR explicitly deferred.

## Decision

**The `#34-7`/`#34-8` clocks are void as written and re-key to go-live** (below). The
go-live work is a new band, **`#34-10`**, sliced so every step is testable before push
— the `#33-2` framing band's arc (pure layer → wiring → flip), reusing the PR #201
`ipc_e2e` harness for the end-to-end legs.

**Capability seam (the negotiation signal).** Two new greeting CAPABS bits, one per
direction, carried by the existing DD-0005 greeting — no new handshake:

- **bit 2 `SCHEMA_V1_REQUESTS`** — advertised by the *worker*: "I can decode
  FlatBuffers schema-v1 request payloads."
- **bit 3 `SCHEMA_V1_RESPONSES`** — advertised by the *host*: "I can decode
  FlatBuffers schema-v1 response payloads."

A sender uses v1 only toward a peer that advertised the matching bit. Framing and
payload *version numbering* stay independent (IPC_SCHEMA.md's claim holds); the
greeting is merely the capability carrier. The table's "host writes both v0 and v1"
is satisfied as *per-session selection with v0 fallback* — the host can speak both
and picks per negotiated session; double-writing every command on the wire buys
nothing and was never implementable (the worker reads one payload per frame).

**Per-frame discrimination.** New frame `FLAGS` **bit 3 `SCHEMA_PAYLOAD` (0x08)** on
`COMMAND` (and later `RESPONSE`-family) frames. A flagged frame's payload is
`[u8 command-id][FlatBuffers bytes]`, with a command-id registry covering exactly the
seven schematized commands; an unflagged frame carries tunneled v0 bytes exactly as
today. Per-frame (not per-session) discrimination is required because the
un-schematized surface must keep riding v0 inside the same session. Readers reject
the flag unless the matching capability was negotiated, so a non-advertising peer
never sees it — the existing `RESERVED_FLAG_SET` behaviour toward old peers is
unchanged.

**Slices:**

- **`#34-10a`** — capability bits + `SCHEMA_PAYLOAD` flag + command-id registry on
  both ends (pure-layer, unit-testable like `#33-2.1`); negotiated but unused.
- **`#34-10b`** — worker v1-request dispatch skeleton + first command go-live:
  `flushNative` (smallest payload), host + worker, with a payload-v1 leg added to the
  `ipc_e2e` harness (same byte-identical-output assertion the framing flip used).
  This slice also adds `FLATBUF_INCLUDE` to the production compile rules — the first
  time FlatBuffers compiles into the worker.
- **`#34-10c`** — lifecycle remainder: `registerProgram`, `deregisterProgram`.
- **`#34-10d`** — config trio: `structureGraph`, `setAction`, `setOptions`.
- **`#34-10e`** — `decompileAt`, including the `DecompileBudget` sub-table (the
  Rec 35 budget rides the same payload). Completes the request direction.
- **`#34-10f+`** — response direction: a host-side `DecompileResponseCodec` (new —
  no Java v1 response decoder exists), worker emit behind `SCHEMA_V1_RESPONSES`,
  command-by-command. Sequenced after the request direction soaks.

Stale markers (the codec "inert" headers, `build.gradle`'s "nothing imports
ghidra.ipc.*" comment, `DecompileProcess.java`'s stays-v0 comments) are updated by
the slice that makes each false, not batched.

**Carve-outs.** The signature commands and the mid-decompile callback sub-protocol
stay v0 — they have no schema tables. Schematizing them is its own future band
(`#34-11`, unscheduled); until then `#34-7`/`#34-8` scope is **the seven schematized
commands only**.

**Re-keyed removal clocks:**

- **`#34-7`** (remove the host's v0 *request* encode for the seven commands) — one
  release after `#34-10e` ships.
- **`#34-8`** (remove the worker's v0 request *decode*) — additionally subordinated
  to DD-0005's coexistence clause: the worker keeps reading v0 so an upstream-Ghidra
  host driving the GayHydra worker binary keeps working; revisit at v27.x with the
  framing-v0 fallback. This resolves the standing tension between IPC_SCHEMA.md's
  two-cycle window and DD-0005's upstream-client commitment in DD-0005's favour —
  the never-break-a-working-peer posture wins.

## Addendum (#34-10d, 2026-06-11): document-carrying fields hold XML text, not packed bytes

Wiring the config trio surfaced a constraint the inert codecs never hit: the schema's
document-carrying fields (`SetOptionsRequest.options`, `StructureGraphRequest.control_flow`)
are FlatBuffers `string`s, but the v0 wire carries those documents as *packed* binary
(`PatchPackedEncode`). FlatBuffers strings are UTF-8 by contract and the C++ codec rides
`c_str()` (NUL-truncating); arbitrary packed bytes cannot survive that channel.

**Decision:** the schema-v1 encoding of these fields is the **XML text** form of the same
document — exactly what the field comments ("encoded `<optionslist>` document") describe.
The host encodes with `XmlEncode` instead of `PatchPackedEncode` when sending schema-v1; the
worker's `loadParametersV1` constructs an `XmlDecode` instead of a `PackedDecode` (the
`Decoder` consumer is format-agnostic). No schema change, no binding regeneration; the
performance difference is irrelevant for rare config commands. Changing the field types to
`[ubyte]` was rejected: it would churn generated bindings and codecs for fields that have
never carried production traffic, to preserve a wire format (packed) whose only virtue on
these messages was that v0 already used it.

`setAction` (pure selector strings) ships first as `#34-10d-1` alongside the
rule-of-three `parseSchemaProgramId` extraction; `setOptions` + `structureGraph` follow as
`#34-10d-2` implementing this addendum.

## Addendum (#34-10e, 2026-06-11): address rides as a space-gated bare offset; the budget sub-table stays absent

Grounding `decompileAt` before wiring it surfaced two gaps between the #34-1
schema sketch and shipped reality:

**Address space.** The schema carries `function_address : uint64`, but the v0
wire carries a full `<addr>` element — space name + `getUnsignedOffset()`
verbatim (no wordsize conversion on either side; verified in
`AddressXML.encodeAttributes` and `AddrSpace::decodeAttributes`). A bare
uint64 cannot name a space. **Decision: the host sends schema-v1 `decompileAt`
only when the function's entry is in the program's default address space**
(the same space the worker resolves as `getDefaultCodeSpace()` — both derive
from the one language definition the registerProgram tspec carries); any
other space (overlay, non-default) falls back to v0 **per call**. This is
never-wrong by construction and covers the overwhelmingly common case.
Consequence for the re-keyed `#34-7`: the v0 `decompileAt` encode survives as
the non-default-space path — full removal needs an additive
`address_space : string` schema field first (FlatBuffers-sanctioned append,
but it requires a flatc 25.12.19 binding regen — its own slice, undertaken
only if evidence shows non-default-space decompiles matter).

**Budget sub-table.** The five-cap `DecompileBudget` sketch (wall-clock, RSS,
pcode-op, per-pass caps) never matched the budget that actually shipped:
Rec 35 `#35-4` landed a *single* flow-iteration cap riding the **options
document** — which itself rides schema-v1 since `#34-10d-2`. No worker code
implements the five sketch caps. **Decision: the sub-table stays absent on
the wire** (the four-arg encoder; absent reads back as schema defaults, which
nothing consumes), and `timeout_ms`/`flags` ride their schema defaults —
host-side `GTimer` remains the timeout enforcement, exactly as v0. Wiring
sketch caps with no implementation would be speculative behavior. The
sub-table waits for a real producer/consumer pair; if none appears by the
response-direction checkpoint below, fold its removal into the same decision.

## Addendum (2026-06-11): #34-10e is a go/no-go checkpoint for the response direction

The `#34-10d` addendum above concedes that for *document-shaped* payloads the schema
buys an envelope, not a format: the document rides as XML text inside a FlatBuffers
string. The response direction (`#34-10f+`) is **dominated** by document-shaped
payloads — a decompile result is one large marshaled document — so the same concession
would recur at scale: v1 responses risk becoming FlatBuffers envelopes around the same
documents, while a third protocol combination stays alive (v0 framing / v1 framing +
v0 payload / v1 + schema payloads, × per-command capability) at least until the v27.x
horizon the `#34-8` subordination already commits to. That carrying cost (test matrix,
upstream-host compatibility story) has not been totalled anywhere.

**Decision point:** when `#34-10e` ships (request direction complete, re-keyed `#34-7`
clock starts), make an explicit go/no-go on `#34-10f+` before starting it. *Go*
requires showing the response schema carries structured, non-document data whose typed
decode is worth the third protocol state. Otherwise *no-go*: declare **requests-only
schema-v1 the terminal state** — greeting + framing + request schema are the realized
hardening wins — and close `#34-10f+` as not-earned, the same honest dissolution the
`#37-10u` template slice got (DD-0070).

## Resolution (2026-06-12): NO-GO — requests-only schema-v1 is the terminal state

`#34-10e` has shipped; the request direction is complete. The go/no-go is decided
**no-go**, on the evidence the addendum laid out: the response payload (the decompile
result) is a large marshaled *document*, so a v1 response would be exactly the
XML-text-in-a-FlatBuffers-string envelope the `#34-10d-2` addendum already showed buys
no typed-decode benefit — while keeping a third live protocol combination (and its test
matrix + upstream-host-compat cost) alive past the v27.x horizon. The realized hardening
wins (greeting negotiation, CRC framing, schema-validated *requests* for the seven
commands) stand on their own.

**`#34-10f+` is closed as not-earned**, the honest dissolution the `#37-10u` template
slice got (DD-0070). The `#34-7` request-encode removal (one release after `#34-10e`)
and the `#34-8` v27.x decode removal still proceed — they are about the request
direction, unaffected by this. If a future need surfaces a response schema carrying
genuinely structured non-document data, this re-opens on that evidence.

## Consequences

- `#34-7` executed on the written clock would have deleted the only production encode
  path; the re-keyed clocks make the removals real changes instead of breakage.
- The band is end-to-end testable from `#34-10b` on, because the `ipc_e2e` harness
  the framing flip required already exists — the constraint that deferred the wiring
  out of the codec PRs (DD-0005's testability rule) no longer holds.
- The migration table's remaining rows execute in deterministic order with no
  further design points: each `#34-10` slice is mechanical against this seam.
- `cppRaiiAudit`'s completeness gate will force classification of any new worker-side
  files the wiring adds; the codec headers themselves remain header-only.
