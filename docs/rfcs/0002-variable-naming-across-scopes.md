---
number: 0002
title: Variable naming across scopes
status: draft
author: @CryptoJones
created: 2026-05-21
audit_rec: 38
upstream_issue: 975
---

# RFC 0002: Variable naming across scopes

## Summary

When a reverse engineer renames a local variable, that variable
often corresponds to the *same value* in other functions: a global
that's read in three places, a struct field accessed from many call
sites, a parameter passed transitively down a call chain. Today
Ghidra forgets this correspondence: each scope's name is
independent; renaming in one place names *only* that occurrence.

This RFC proposes a **scope graph**: an explicit data structure
that links varnodes across functions when they refer to the same
underlying value, so that a rename in one place is propagated to
every related occurrence the user opts into.

Upstream [issue #975 (53 👍)](https://github.com/NationalSecurityAgency/ghidra/issues/975)
is the most-upvoted open issue in Ghidra's tracker, and the
audit named this as needing principal-architect time.

## Motivation

The reverse-engineering workflow is annotation-heavy. A user
identifies a buffer in function A, names it `inputBuf`, then
finds the same buffer used in functions B and C. In Ghidra today
the user renames `local_8` in B and C separately — and the next
time analysis re-runs, those names may not stick because the
varnode hash has shifted.

The cost of this is real:

- **Throwaway names.** Users avoid renaming because they don't
  trust the rename to survive.
- **Lost context.** A binary with 5,000 functions and an
  annotation budget of "things you can manually re-do" has a
  thin ceiling on how much can be annotated.
- **Tribal knowledge.** Reverse engineers maintain external
  notes (markdown files, OneNote, paper) tracking what
  `local_8` "actually is" because Ghidra forgets.

A coherent scope graph would let the user say "this is the input
buffer" once, and every occurrence — including ones discovered
later — would inherit the name.

## Detailed design

### The scope graph

A new artifact in the project database:

```
ScopeGraph {
    nodes: Map<NodeId, ScopeNode>
    edges: Set<(NodeId, NodeId, EdgeKind)>
}

ScopeNode = enum {
    GlobalAddress { address }
    StructField { struct_id, field_offset }
    Parameter { function_id, param_index }
    LocalEquiv { function_id, equivalence_class_id }
}

EdgeKind = enum {
    SameValue       // varnodes hold the same value
    AliasOf         // value can be reached by aliasing
    DerivedFrom     // computed-from (offset, cast, etc.)
}
```

A `ScopeNode` is an opaque handle to a value-identity. A `varnode`
in a function's pcode may map to a `ScopeNode` (or to several, if
it's a derived value). The mapping is computed by an analysis pass.

### Rename propagation

When the user renames a `varnode`:

1. Look up the `ScopeNode` for that varnode.
2. Walk the graph by `SameValue` edges to find all related nodes.
3. For each related node, find all varnodes in their respective
   functions that map to it.
4. Rename those varnodes too.

The user gets a confirmation dialog:

```
You renamed `local_8` in function `parse_packet` to `inputBuf`.
This variable also appears in 7 other functions. Apply the rename?

[ ] handle_request (varnode at 0x401234)
[ ] do_work (varnode at 0x405678)
... (etc) ...

[Apply to checked] [Apply to all] [Apply to this only]
```

Default: dialog shown the first time; remembered per project as
"always propagate" / "never propagate" / "always ask."

### Population of the graph

The graph is populated by three sources:

1. **Static analysis** (deterministic). Same global address ->
   same node. Same struct field across loads -> same node.
   Same parameter slot of the same function -> same node.
2. **Dataflow analysis** (heuristic). Local in function A passed
   as parameter to function B -> the local in A's caller and B's
   parameter share a node. This is the hardest case; ships behind
   a confidence threshold.
3. **User assertion**. The user can explicitly say "this varnode
   is the same value as that one" via a context menu; the user's
   assertion is stored as a `UserAsserted` edge and overrides
   automatic ones.

All three sources are persisted to the project DB. Static analysis
re-runs on every change; dataflow analysis re-runs on demand or as
a background task; user assertions are sticky.

### Storage

The scope graph extends the project DB schema:

```
table ScopeNode {
    id : uint64 (primary key)
    kind : uint8
    payload : bytes (kind-specific)
    user_name : string?  // user-asserted name, if any
}

table ScopeEdge {
    src_id : uint64
    dst_id : uint64
    kind : uint8
    confidence : float (0..1)
    source : uint8  (static / dataflow / user)
}

index varnode_to_scope: (function_id, address, varnode_seq) -> ScopeNode
```

The migration path is forward-only: existing projects without a
`ScopeGraph` table get an empty one on first open; user renames
post-migration populate it incrementally.

## Drawbacks

- **Project DB schema growth.** New tables, new indices. Modest.
- **Performance.** The graph walk on rename can touch many
  functions; mitigated by caching and by the per-function cache
  (Rec 36) for the affected decompilations.
- **Risk of incorrect propagation.** A heuristic dataflow link
  might propagate a name where it shouldn't. Mitigations:
  confidence threshold, opt-in dialog, ability to undo, ability
  to mark a propagation "wrong" (which adds an explicit
  `NotSameValue` user edge).

## Alternatives

- **Per-function naming only.** Status quo. Doesn't solve the
  problem.
- **Symbol-table-based propagation.** Rename a symbol globally;
  no cross-function logic. Works for globals; doesn't work for
  struct fields or parameters.
- **Hash-based equivalence.** Compute a hash of each varnode's
  defining expression; equal hashes share a name. Breaks on any
  optimisation that rewrites the expression; tested upstream
  ad-hoc and abandoned.
- **External name database.** A side table of "rename suggestions"
  the user can apply manually. Adds friction; doesn't change the
  underlying capability.

The scope graph is the only design that makes the rename
propagation an automatic-by-default but user-controllable
behaviour.

## Migration

- Existing projects keep working — `ScopeGraph` is empty until
  the user renames.
- The first rename in a function builds the local sub-graph for
  that function and its immediate static neighbours.
- A "Build full scope graph" GUI action runs the dataflow
  analysis across the whole program; this is a one-time cost
  per project (matter of minutes for a typical binary).

## Unresolved questions

1. **How to surface confidence?** Should the dialog show the
   inferred confidence per related occurrence? Probably yes,
   but how visually.
2. **Type propagation.** Should retyping a varnode propagate
   the same way? Almost certainly yes, but typing has more
   constraints (must be valid against the structure already
   inferred); a follow-up RFC.
3. **Sharing across projects.** A user analysing two binaries of
   the same software (different versions) wants to share the
   scope graph. Out of scope for this RFC.

## Future possibilities

- **Cross-project graph reuse** (deferred).
- **Semantic naming.** Once the scope graph exists, naming
  suggestions could come from a model trained on (varnode
  shape -> common name) pairs. Out of scope.
- **Visualisation.** A graph viewer of "all uses of this value
  across the program."

## Sequencing

| PR | Scope |
|---|---|
| #38-1 (this RFC) | This document |
| #38-2 | `ScopeNode` and `ScopeEdge` schema + storage layer |
| #38-3 | Static-analysis populator (global addresses, struct fields, parameters) |
| #38-4 | Rename propagation dialog + opt-in UI |
| #38-5 | Dataflow-analysis populator (heuristic, behind confidence threshold) |
| #38-6 | "Build full scope graph" GUI action |
| #38-7 | Type propagation (separate RFC may be required) |

Reviewers: type-system maintainer + UX maintainer (see
[MAINTAINERS.md](../../MAINTAINERS.md)).
