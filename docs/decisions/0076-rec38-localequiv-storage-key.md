---
number: 0076
title: Rec 38 — LocalEquiv keys by canonical storage string, not an analysis-assigned id; storage is the decompile-invariant anchor Ghidra itself uses to match a HighSymbol back to the database local, an opaque long invites exactly the unstable minting DD-0075 declined, and the value-identity contract tolerates no collisions (a hash is not an identity)
status: accepted
date: 2026-06-10
audit_rec: 38
---

# Decision 0076: a local's identity is its storage

## Context

[DD-0075](0075-rec38-dataflow-passthrough.md) deferred local-variable dataflow endpoints because
`LocalEquiv` (as sketched in RFC-0002 and modelled in DD-0074) carried an "analysis-assigned
equivalence class id (stable within one population pass)" — and nothing defines that id *across*
passes. Keying off per-decompile `HighVariable` identity would not survive recomputation; any
producer that minted such ids would silently break the graph's value semantics, where node equality
*is* identity.

The design study asked: what property of a local is stable across decompiles of the same program
state? The answer is the one Ghidra already relies on: **storage**. The decompiler's
`HighSymbol` ↔ database-local matching is storage-based; a stack local is `Stack[-0x8]:4`
regardless of which decompile produced it, a register local is its register and size. (It changes
when the *binary or the user's variable definitions* change — which is when the identity genuinely
is a different thing.)

## Decision

`ScopeNode.LocalEquiv` re-keys from `long equivalenceClassId` to `String storageKey` — the local's
canonical `VariableStorage` string. Rationale over the alternatives:

- **An opaque long with a registry** (mint sequential ids, persist the mapping): adds a second
  durable artifact whose lifecycle must track every variable edit — heavyweight and fragile.
- **A hash of the storage into the long**: a hash is not an identity; collisions would silently
  merge two different locals' rename sets — the worst possible failure for a propagation feature.
- **The canonical string itself**: deterministic, decompile-invariant, human-debuggable in the
  persisted blob, and already escaped correctly by the DD-0074 codec (the same percent-escaping
  structure names use).

The persistence codec's `L` tag now carries the escaped storage key. Done now, while the model is
one day old and nothing has persisted real `LocalEquiv` nodes — a forward-only format change later
would have needed a version bump.

## Consequences

- The dataflow band's local-argument case is unblocked: a local argument's `LocalEquiv` endpoint
  can be minted from `HighSymbol.getStorage().toString()` and will mean the same thing on every
  decompile. That minting is the next grounded slice (it needs a fixture with a database-backed
  local).
- Model, codec, and populator suites updated and green (10/7/7); no persisted-format version bump
  needed (nothing real persisted `L` nodes yet).
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Base:test --tests 'ghidra.app.util.scope.*'` and `gradle :Base:ip`, Gradle 8.5 /
  Temurin 21 (the CI-matching toolchain).
