---
number: 0074
title: Rec 38 #38-2a — the scope graph ships model-first; RFC-0002's relational table schema has no public extension point (ProgramUserData exposes address-keyed property maps, not tables), so ScopeNode/ScopeEdge/ScopeGraph land as a headless value-semantic in-memory model with idempotent producers and an undirected SAME_VALUE component walk, and only user-asserted edges will persist (a later slice encodes them through ProgramUserData)
status: accepted
date: 2026-06-10
audit_rec: 38
---

# Decision 0074: the scope graph is a model, not a table

## Context

Rec 38 (variable naming across scopes, [RFC-0002](../rfcs/0002-variable-naming-across-scopes.md))
opens with `#38-2`: "ScopeNode and ScopeEdge schema + storage layer". The RFC sketches literal DB
tables. Grounding against the program APIs (the DD-0009/DD-0016 discipline — check the projection
before building it) shows the fork has **no public extension point for those tables**:
`ProgramUserData` exposes typed *address-keyed property maps* and string options, not arbitrary
tables, and extending the program database schema itself is the kind of upstream-merge-hostile
change this fork's design rules avoid.

What actually needs durability is small. The RFC's three producers split cleanly: static analysis
"re-runs on every change" and dataflow "re-runs on demand" — both *recomputable* — while **user
assertions are sticky**. So the storage problem reduces to persisting user-asserted edges only.

## Decision

`#38-2a` ships the model half, in `ghidra.app.util.scope` (Base), the same model-first posture as
Rec 37's `CppTypeSystem` (DD-0011):

- **`ScopeNode`** — a sealed interface with the RFC's four kinds as value-semantic records
  (`GlobalAddress`, `StructField`, `Parameter`, `LocalEquiv`). Equality *is* identity — no
  synthetic ids, the graph deduplicates by value. `StructField` carries the structure's *name*
  rather than a `DataType` reference so the model stays program-decoupled.
- **`ScopeEdge`** — a record of `(source, destination, Kind, confidence, Origin)`, with the RFC's
  `EdgeKind` (`SAME_VALUE`/`ALIAS_OF`/`DERIVED_FROM`) and producer (`STATIC`/`DATAFLOW`/
  `USER_ASSERTED`); confidence validated into `[0, 1]`; self-edges rejected.
- **`ScopeGraph`** — deduplicating `addNode`/`addEdge` (idempotent producers — the DD-0063 feeder
  lesson baked in from the start), per-node edge lookup, `userAssertedEdges()` (the future
  persistence feed), and `sameValueComponent(start)`: the undirected transitive walk over
  `SAME_VALUE` edges only, which is exactly the set rename propagation offers the user.
  `ALIAS_OF`/`DERIVED_FROM` neighbours hold *related* values, not the same one — renaming them
  would be wrong, so the walk never crosses them (never-wrong over complete).

**Band slicing ahead:** `#38-2b` — persistence codec for `userAssertedEdges()` over
`ProgramUserData` (string-property encoding under a fork-owned owner key; per-user durability is
acceptable because everything else is recomputable); `#38-3` — the static-analysis populator;
`#38-4` — rename-propagation UI (DISPLAY-gated, like the other GUI tails).

## Consequences

- The Rec 38 band has its foundation: a tested, headless graph with the propagation query the rest
  of the band builds on. Suite 10/10 (value semantics, idempotent re-add, endpoint registration,
  transitive + undirected component, kind exclusion, isolated node, durable subset, and the three
  contract groups).
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Base:test --tests 'ghidra.app.util.scope.ScopeGraphTest'` and `gradle :Base:ip`,
  Gradle 8.5 / Temurin 21 (the CI-matching toolchain).
