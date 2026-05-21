# Data-Type Archive Deserialization — Principal-Level Review

*Addresses Rec 18 of the 2026-05-21 principal-architect audit.*

Tracking the only CWE-tagged open security issue in upstream Ghidra:
[#1481 — Ghidra deserialization of data type archive can fill up
arbitrary disk space (CWE-409: Data amplification)](https://github.com/NationalSecurityAgency/ghidra/issues/1481).

The audit's recommendation was explicit: review this *as a pipeline*,
not as a point fix. This document is that review.

## The reported failure mode

`ItemDeserializer.deserialize(...)` reads a Ghidra archive (`.gdt`,
`.gpr`, `.gar`) as a `ZipInputStream`. The on-disk path is roughly:

```
~/archives/foo.gdt              (compressed, attacker-controlled)
    │
    ▼   ItemDeserializer.deserialize
    │   (currently: no pre-check on declared output size)
    ▼
$TMPDIR/.../packed-db-cache/<random>/db.N.gbf   (decompressed)
```

The attacker controls the `.gdt` file. A 16 MB compressed input
expands to ~16 GB on disk. Ghidra detects the failure *after*
filling the disk, and on failure does not clean up the partial
write. The bug is a textbook **zip bomb** in a Ghidra-specific
shape:

- The malicious input is a `.gdt` file the user is induced to open.
- The compression ratio is unlimited because Ghidra does not check
  the declared output size against any policy.
- The result is a denial-of-service on the user's machine,
  permanent if the partial file is not cleaned up.

CWE-409 is the right tag. The CVSS 3.1 vector is roughly
`AV:L/AC:L/PR:N/UI:R/S:U/C:N/I:N/A:H` ≈ 5.3 (Medium), since the
user must be induced to open the file but the impact on the host
is high (full disk → cascading failure of unrelated processes).

## The pipeline (where else this pattern lives)

The reported bug is one instance of a class. The class is "Ghidra
reads a zipped-or-otherwise-compressed-or-deserialized stream of
attacker-controlled bytes and trusts the declared output shape."
The pipeline carries this pattern in several places. Each site
needs the same hardening, not just `ItemDeserializer`.

### Sites identified for review

| Component | File | Pattern |
|---|---|---|
| Archive open | `ghidra.framework.store.local.ItemDeserializer` | The reported #1481 case. ZipInputStream → temp file with no size cap. |
| Packed DB cache | `ghidra.framework.store.db.PackedDatabaseCache` | Caches unpacked archives without a cumulative-size policy. |
| Extension load | `ghidra.util.extensions.ExtensionUtils` | Extension `.zip` ingestion; unpacks under `$GHIDRA/Extensions/`. |
| Project archive | `ghidra.framework.protocol.ghidra.GhidraURLConnection` | Downloads + opens archives from remote project URIs. |
| Data type archive XML | `ghidra.program.model.data.FileDataTypeManager` | XML-backed archive open path; no upper bound on XML element count. |
| Object stream | `ItemStorage.read*` raw `ObjectInputStream.readObject()` | Touches deserialization gadgets (Rec 19). |

Each site is its own sub-issue under #18 and gets its own hardening
PR. The plan below applies to all six.

## Hardening plan (applied to every site above)

### 1. Declared-size precheck

Before the first byte is decompressed, read the archive's manifest
(or `ZipEntry.getSize()`) and refuse if the claimed expanded size
exceeds the policy. Policy is:

- Per-entry: max **1 GB** by default, configurable via
  `ghidra.archive.max-entry-bytes`.
- Per-archive cumulative: max **8 GB** by default, configurable
  via `ghidra.archive.max-total-bytes`.

These limits sit at the highest legitimate size we have seen for
real data-type archives plus generous headroom. They are
configurable for users with genuinely large analyses; the default
is the policy.

### 2. Running-counter cap during decompression

Even with a declared-size precheck, the *actual* decompressed
size may diverge (declared-size lies). During decompression we
maintain a `BoundedOutputStream` that throws after `max-total-bytes`
have been written. The bound is hard; we do not fall through to
the original error path.

### 3. Clean up on failure

A failed unpack must `Files.deleteIfExists(...)` on every temp
file it created. Currently the failure path leaks the partial
write. The fix is a `try (BoundedOutputStream out = ...)` plus a
catch that records the temp path and deletes it.

### 4. Refuse to operate near a full disk

Before unpack, query `FileStore.getUsableSpace()` on the target
directory. If less than `max-total-bytes + 1 GB` of headroom,
refuse. This is belt-and-suspenders; the bounded stream is the
primary defence.

### 5. Manifest cross-check (Phase 2)

`.gdt` archives carry an internal manifest. Compare the manifest's
declared sizes against the actual decompressed bytes; mismatch is a
hard fail. This catches a malicious archive whose manifest claims
small but whose payload is large (or vice versa).

### 6. Signed archives (Phase 3)

A `signed-only` mode where Ghidra refuses to open archives without
a valid signature from a configured trust root. Pairs with the
script sandbox (Rec 16) and the binary signing path (Rec 17). Out
of scope for the initial hardening PR.

## What gets a CVE

Per [CVE_POLICY.md](CVE_POLICY.md), the reported failure mode
satisfies the criteria:

- Reachable from adversary-controlled binary input (the archive
  file).
- Causes a sustained DoS on the user's machine.

A CVE is filed at the time of the first hardening PR. Severity
(CVSS 3.1) and version range are recorded in the GHSA at that
point.

## Sequencing

| PR | Scope | Issue |
|---|---|---|
| #18-1 | This review document | #18 |
| #18-2 | `ItemDeserializer` hardening (the original #1481 site) | New sub-issue, closes #1481 in this fork |
| #18-3 | `PackedDatabaseCache` cumulative-size policy | New sub-issue |
| #18-4 | `ExtensionUtils` archive hardening | New sub-issue |
| #18-5 | `GhidraURLConnection` archive hardening | New sub-issue |
| #18-6 | `FileDataTypeManager` XML upper bounds | New sub-issue |
| #18-7 | `ItemStorage` deserialization (cross-refs Rec 19) | New sub-issue |

Each lands under [`lane:security`](../governance/lanes/PROCESSOR_LANE.md)
(highest priority per [LABEL_POLICY.md](../governance/LABEL_POLICY.md)).
The fixes are landed publicly only after coordinated disclosure
with upstream NSA/ghidra ([SECURITY.md](../../SECURITY.md)) — they
have the same code, the same bug, and a faster patch cycle for
their users than we can achieve unilaterally.

## Test coverage

Each hardening PR ships:

- A regression test exercising the original PoC pattern (16 MB
  compressed → multi-GB expand attempted).
- A regression test for clean-up-on-failure.
- A regression test for the size-budget cap (legitimate archive at
  the boundary, illegitimate archive just over).

Test fixtures live under
`Ghidra/Framework/FileSystem/src/test/resources/archives-malformed/`
and are checked in as small (≤1 KB) crafted samples — the
expansion happens at test time, not at checkout.

## Open questions

1. Should `max-total-bytes` be a per-process limit (one archive at
   a time can fill `max-total-bytes`) or a global limit (across all
   open archives)? Current default: per-archive, with a separate
   global watchdog at the cache-eviction layer.
2. Does the manifest cross-check (Phase 2) need to read the entire
   archive to verify? If yes, it is an additional walk; if a
   sampling check is sufficient, we save the walk.
3. Does the signed-archive mode (Phase 3) cover only `.gdt` or
   also `.gpr` (project) and `.gar` (general)?

Tracked in `docs/security/archive-deser-followups.md` after #18-2
lands.

## Acknowledgement

The original reporter of [#1481](https://github.com/NationalSecurityAgency/ghidra/issues/1481)
gave a clean PoC and a concrete reproducer; their work made this
review tractable.
