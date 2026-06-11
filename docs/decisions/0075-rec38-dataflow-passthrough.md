---
number: 0075
title: Rec 38 dataflow source slice 1 — pass-through parameters; the heuristic band opens at its deterministic end, relating Parameter(callerSlot) to Parameter(calleeSlot) when a decompiled direct CALL forwards a caller parameter positionally, at confidence 0.9 with DATAFLOW origin; local-variable arguments are deferred until a stable LocalEquiv id exists rather than keyed off per-decompile HighVariable identity
status: accepted
date: 2026-06-10
audit_rec: 38
---

# Decision 0075: the dataflow band opens with pass-through parameters

## Context

RFC-0002 calls its dataflow population source "the hardest case" and puts it behind a confidence
threshold: "local in function A passed as parameter to function B → the local in A and B's
parameter share a node." With the deterministic static source complete (`#38-3a`/`b`), the question
was which dataflow shape can ship first without violating never-wrong.

Grounding reused the Rec 37 machinery: a decompiled direct `CALL`'s inputs after the target map
positionally to callee parameter slots (the same recovery `CppOperandRenderer.callArguments`
rides), and `HighVariable.getSymbol().isParameter()` / `getCategoryIndex()` identify when an
argument *is* a parameter of the caller. In that case **both endpoints are deterministic
`Parameter` identities** — no equivalence-class problem at all.

## Decision

`ScopeGraphDataflowPopulator.populate(HighFunction, ScopeGraph)`:

- For each direct `CALL` whose target resolves to a `Function`, for each input `i ≥ 1` whose
  varnode carries a caller-parameter `HighSymbol`, add
  `ScopeEdge(Parameter(callerEntry, slot), Parameter(calleeEntry, i−1), SAME_VALUE, 0.9, DATAFLOW)`.
- **Confidence 0.9, origin `DATAFLOW`**: the positional mapping is the decompiler's own prototype
  recovery — high confidence, but deliberately below the 1.0 of static facts and user assertions,
  honouring the RFC's behind-a-threshold posture for everything dataflow. Consumers pick their
  cut-off.
- **Local-variable arguments are deferred, not approximated.** A `LocalEquiv` endpoint needs a
  stable equivalence-class id; per-decompile `HighVariable` identity does not survive
  recomputation, and minting unstable ids would silently break the graph's value semantics. The
  RFC's full local case waits for a grounded id scheme.
- Unresolved/indirect targets, constant or computed arguments: contribute nothing (never-wrong);
  re-population is idempotent via the graph's deduplicating adds.

## Addendum (2026-06-10, same day): local arguments unblocked by DD-0076 + a probe

The deferral above resolved faster than expected. [DD-0076](0076-rec38-localequiv-storage-key.md)
re-keyed `LocalEquiv` by canonical storage string, and a probe answered the remaining question: a
stack-local argument's `HighSymbol` carries concrete `Stack[-0xNN]:size` storage **even when no
database local exists** (the decompiler synthesizes the symbol; the storage is the invariant). So
`argumentIdentity` now also mints `LocalEquiv(callerEntry, storage.toString())` for a local whose
storage is valid (not bad/unassigned); unique temporaries and computed values still contribute
nothing. Harness suite 4/4 (stack-local fixture added).

## Consequences

- The first cross-function `SAME_VALUE` edges flow: renaming a forwarded parameter in the caller
  now reaches the callee's slot in `sameValueComponent` — RFC-0002's motivating propagation,
  end-to-end from a real decompiled call. Verified through the Rec 30 harness
  (`f(int a) { g(a); }` fixture, the `CppDeleteDriverTest` two-function pattern): the expected
  edge with exact confidence/origin, the component reach, idempotent re-run, null contracts.
  Suite 3/3.
- Remaining Rec 38: the local-argument dataflow case (needs the stable-id design), `#38-4`
  rename-propagation UI (DISPLAY-gated).
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Decompiler:integrationTest --tests '…ScopeGraphDataflowPopulatorTest'` and both `ip`
  tasks, Gradle 8.5 / Temurin 21 (the CI-matching toolchain).
