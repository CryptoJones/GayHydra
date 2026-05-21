# Java Deserialization Audit

*Addresses Rec 19 of the 2026-05-21 principal-architect audit.*

## Why this matters

Java's built-in `ObjectInputStream.readObject()` is a security
disaster zone. With the right gadget classes on the classpath, a
single `readObject()` call against attacker-controlled bytes is RCE
— this is exactly how the Spring4Shell-era CVEs work, how the
Apache Commons-Collections gadget chain works, and how the Jenkins
remoting CVEs (CVE-2017-1000353 et al) work.

The audit identified ~20 files in Ghidra using raw `readObject()`.
Recent GP-6719 added an `ObjectInputFilter` for the RMI surface
(`Framework/FileSystem/data/client.rmi.serial.filter`); the
non-RMI sites still take raw bytes.

The endgame: **exactly one approved deserialization helper, used
everywhere, with a per-call allowlist of expected classes**. No
direct `readObject()` calls outside that helper.

## Inventory

Sites using `ObjectInputStream` (raw or with partial filtering):

| File | Surface | Risk |
|---|---|---|
| `Framework/FileSystem/.../Version.java` | Repository version metadata | Local-trusted, low risk if storage path is trusted |
| `Framework/FileSystem/.../ItemCheckoutStatus.java` | Multi-user checkout state | Server-trusted; reachable if collaborative server is open |
| `Framework/FileSystem/.../ItemDeserializer.java` | Archive open path | **High — attacker-controlled `.gdt`/`.gpr` files** (see [#1481 review](datatype-archive-deserialization-review.md)) |
| `Framework/FileSystem/.../RepositoryItem.java` | RMI surface | Already covered by GP-6719 filter; verify default-on |
| `Framework/Generic/.../ObjectStorageStreamAdapter.java` | Property storage | Local-trusted |
| `Framework/Generic/.../RedBlackKeySet.java` | Data structure with custom serial form | Local-trusted, no attacker reach |
| `Framework/Generic/.../RedBlackLongKeySet.java` | Same | Same |
| `Framework/Generic/.../map/IntValueMap.java` | Property storage | Local-trusted |
| `Framework/Generic/.../map/ObjectValueMap.java` | Property storage | Local-trusted |
| `Framework/Generic/.../map/ValueMap.java` | Property storage | Local-trusted |
| `Framework/SoftwareModeling/.../DefaultPropertyMap.java` | Property storage | Local-trusted |
| `Framework/SoftwareModeling/.../DataTypeManagerChangeListenerHandler.java` | Event listener machinery | Local-trusted (no attacker reach in normal use) |
| `Features/Base/.../CodeUnitInfo.java` | Clipboard / drag-drop | **Medium — IDE clipboard is shared OS surface** |
| `Debug/Framework-TraceModeling/.../AbstractDBTracePropertyMap.java` | Trace property storage | Local-trusted |

Some entries are not yet confirmed-exhaustive; the grep returned 14
production sources plus tests. Re-grepping at audit time:

```
grep -r "ObjectInputStream" Ghidra --include='*.java' | \
    grep -v /test/ | grep -v Test.java
```

A fresh count is captured in `docs/security/java-deser-inventory.txt`
each time this audit is re-run.

## Risk classes

The sites above fall into three classes; the hardening differs by class.

### Class A: attacker-reachable

Any site that consumes bytes that originated outside the user's
own analysis session. Currently includes:

- `ItemDeserializer` (archive open) — the #1481 / Rec 18 surface.
- `RepositoryItem` and the RMI surface — partly covered by GP-6719.
- `CodeUnitInfo` clipboard ingestion — accepts bytes from the OS
  clipboard, which any process can write to.

**Hardening:** strict allowlist filter via `ObjectInputFilter`
configured per call. Default-deny; the call site declares the
exact classes it expects, and unknown classes fail closed.

### Class B: server-trusted (multi-user collaborative)

Sites reachable only via authenticated server protocols. The
attacker is an authenticated client; the server protocol is
expected to be available only to known users.

- `ItemCheckoutStatus`, server-side `Version`.

**Hardening:** allowlist filter, same as Class A, with a slightly
broader allowlist matching the server's documented schema. CVE
scope: only if the server is reachable beyond the documented user
set.

### Class C: local-trusted

Sites that only deserialize bytes the user wrote themselves
during this analysis session. The attacker would need write access
to the user's project files — at which point they already have
RCE through other means.

- Most `Framework/Generic/` map / property store sites.
- Most `Framework/SoftwareModeling/` property map sites.
- The trace property map.

**Hardening:** still adopt the unified helper for code-quality
reasons (single audit surface, single deprecation path if we
move off Java serialization entirely). Not a CVE-eligible bug if
unfiltered, because attacker reach is gated by the user already
having full code execution.

## The approved helper

A new class:

```java
ghidra.framework.security.SafeObjectInput
```

with one entry point:

```java
public static <T> T readObject(
        InputStream in,
        Class<T> expected,
        ObjectInputFilter filter) throws IOException, ClassNotFoundException;
```

Rules:

1. Every call site declares the expected top-level type. The
   helper enforces `instanceof expected`; mismatch is a hard
   fail.
2. Every call site passes an `ObjectInputFilter` (built via
   `SafeObjectInput.allowlist(Class... classes)` for convenience)
   listing the classes the call expects to materialise.
3. The default reject path is the standard `Status.REJECTED`,
   *not* "allow if unsure."
4. Deep object graphs deeper than 50 levels are rejected.
5. Total bytes deserialized per call are capped at 64 MB by
   default (configurable per call); cap is enforced by counting
   the underlying stream.

All current direct `new ObjectInputStream(...)` sites move to
`SafeObjectInput.readObject(...)`. The migration is mechanical
but per-site (each site has a different expected type and
allowlist).

## Migration plan

| PR | Scope | Sites |
|---|---|---|
| #19-1 | This audit doc + `SafeObjectInput` helper class | New file |
| #19-2 | Class A sites (`ItemDeserializer`, `CodeUnitInfo`) | 2 sites |
| #19-3 | Class B sites (RMI + `ItemCheckoutStatus`) | 2 sites |
| #19-4 | Class C sites in `Framework/Generic` | 6 sites |
| #19-5 | Class C sites in `Framework/SoftwareModeling` and `Debug` | 4 sites |
| #19-6 | Forbid raw `ObjectInputStream` via a Checkstyle rule (or ErrorProne pattern, Rec 26) | enforcement |

Each PR includes a test asserting that an unexpected-type or
unexpected-class payload is rejected without instantiation.

## Coordination with the RMI filter (GP-6719)

The RMI filter is already in place at
`Framework/FileSystem/data/client.rmi.serial.filter`. After this
audit:

- The RMI filter remains the network-edge defence.
- Application-layer sites use `SafeObjectInput` with their own
  per-call allowlists.
- Rec 20 verifies the RMI filter is enabled by default (it's
  already on in some configurations but we have not yet
  regression-tested it).

The two layers compose: a malicious RMI payload is rejected at
the RMI filter; if it somehow squeezed through (filter
misconfigured), `SafeObjectInput` rejects it at the application
layer because the per-call allowlist does not include
gadget-chain classes.

## What this audit does *not* do

- Does not eliminate Java serialization. A full migration off
  `ObjectInputStream` to a schema-based format (CBOR, FlatBuffers
  — see Rec 34 for the IPC-side version of this argument) is a
  separate, much larger initiative.
- Does not address gadget classes shipped by transitive
  dependencies. Those are handled by the allowlist at the call
  site (which never lists them).
- Does not address `XMLDecoder` or other Java-native
  deserializers; those have their own surface.

## Open questions

1. Should `SafeObjectInput` enforce a default class limit even if
   the caller passes an overly-permissive filter? (Tradeoff:
   defence in depth vs. false confidence.)
2. Should the migration land all-at-once (one PR with 14 site
   updates) or per-class (as sequenced above)? Current default:
   sequenced, so each PR has a small blast radius if it breaks
   anything.

## Acknowledgement

The audit work that surfaced these sites was the foundation of
this rec. The follow-up PRs are the implementation.
