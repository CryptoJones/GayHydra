# Decompiler Cache Invalidation: Stop Flushing on Trivial Edits

*Addresses Rec 36 of the 2026-05-21 principal-architect audit, tracking
upstream issue [#1871](https://github.com/NationalSecurityAgency/ghidra/issues/1871).*

## The complaint

> Certain 'local' changes, for example, renaming a variable, do not affect
> the decompilation of other functions. Therefore, scrubbing the
> decompiler cache due to the change in state can be very painful
> performance wise.

26 reactions on the issue, four years open. The fix is plausibly small
relative to its UX impact: renaming a local variable in function A
should not invalidate the decompilation of functions B–Z.

## What the cache does today (analysis)

The decompiler caches per-function `DecompileResult` objects keyed by
`(function, snapshot)` where `snapshot` rises any time the user
modifies the program's database. The current invalidation rule is
coarse: **any modification to the program bumps the snapshot, and the
entire cache is invalidated**.

The audit's call-out is exactly this coarse-key problem. Most
modifications are *local* — they affect one function, or one symbol
scope, or one type definition. The cache could keep entries for
functions that are demonstrably unaffected by the change.

## The fix shape

Replace the single global snapshot with **per-function dependency
tracking**: each cached `DecompileResult` records which
program-state pieces went into producing it. When a piece is
modified, only entries whose dependency set includes that piece are
invalidated.

### Dependency pieces

The pieces that go into a function's decompilation:

| Piece | Function-local? | Examples of changes that touch it |
|---|---|---|
| Function bytes (the actual instructions) | Yes | Patching instructions, defining new code at this function's range. |
| Function signature (param types, return type) | Mostly local | Editing the signature of this function. |
| Local symbol names + types | Yes | Renaming a local; retyping a local. |
| Local symbol storage | Yes | Changing where a parameter lives. |
| Callees' signatures | No — shared | Editing the signature of a function this function calls. |
| Global symbol names + types | Partly | Renaming a global this function references. |
| Data types referenced | Partly | Edits to a struct this function uses. |
| Calling convention | Mostly local | Changing this function's calling convention. |
| User comments / pragmas | Yes | Adding a comment line. |
| Decompiler options | No — global | "Show types," "show varnodes," etc. |

A cache entry records its set of dependencies as a sorted list of
opaque IDs. On modification, the change's affected piece-IDs are
known; the cache walks its entries and invalidates any that include
the affected IDs.

### Worst case is still cheap

Even with per-function dependency tracking, the cache could
theoretically have to walk every entry on a global change. We
amortise this:

- Dependencies are recorded as **bitmaps** in a packed
  representation. A modification's affected IDs are AND-ed against
  the bitmap; entries with zero overlap are skipped without
  individual inspection.
- Global changes (calling-convention defaults, decompiler options)
  invalidate everything — this is the same as today and the
  expected behaviour.

## "Directly modify the current decompilation"

The issue's second ask: for trivial changes, modify the cached
`DecompileResult` in-place rather than recomputing. Two examples:

- Renaming a local: walk the result's symbol table, rewrite the
  name. No re-analysis needed.
- Adding a comment at an address: insert the comment into the
  result's display list. No re-analysis needed.

This is a separate optimisation layer riding on top of the cache:

```
modify_local_name(func, oldName, newName):
    cached = cache.get(func)
    if cached and cached.is_in_place_renamable():
        cached.rewrite_symbol_name(oldName, newName)
        return    # done, no decompile needed
    cache.invalidate(func)
    return decompile(func)  # full path
```

This delivers the issue's #2 request (avoid redoing analysis) for
the cases where it's safe — and it's safe for a surprisingly large
fraction of "small UI edits" the user makes.

## Coordination with Rec 35 (budgets)

The cache (now per-function dependency-tracked) also stores **partial
results** from Rec 35. The cache key extends to
`(function, snapshot, budget)` — a request for the same function with
a smaller budget can return a cached partial; a request with a larger
budget re-runs but starts from the cached partial as a checkpoint.

This is the "Retry with 2x budget" path's cheap entry point.

## Sequencing

| PR | Scope |
|---|---|
| #36-1 (this PR) | This design doc |
| #36-2 | Add per-function dependency-bitmap recording to `DecompileResult` |
| #36-3 | Wire the program-modification callbacks to invalidate by bitmap intersection (replaces the global-flush path) |
| #36-4 | Add in-place rewrite paths for: local name, local type, comment add/edit, function name |
| #36-5 | Telemetry: cache-hit rate, in-place-rewrite rate (measured against synthetic workloads) |
| #36-6 | Extend the cache key with `budget` (depends on Rec 35 landing) |

## Risk

- **Missed dependency.** If a change touches a piece the
  dependency-bitmap didn't record, a cache entry stays alive when
  it should have been invalidated. The user sees stale
  decompilation. Mitigations:
  1. Conservative defaults: when in doubt, invalidate.
  2. A debug mode that recomputes anyway and asserts the cached
     result matches; runs on the test corpus.
  3. The "Force re-decompile" GUI option that exists today stays
     as a manual escape hatch.
- **In-place rewrites apply incorrect changes.** Mitigations:
  1. Each in-place rewrite ships with a fallback flag — if the
     rewrite fails any consistency check, fall through to full
     re-decompile.
  2. Test coverage for every in-place rewrite path on every
     supported architecture.

## Performance expectation

The audit and the issue both note "very painful performance" on
the current global-flush path. With per-function tracking:

- Local rename in a 5,000-function binary: only the renamed
  function is invalidated. Cache hit rate ~99.98% post-rename.
- Global type edit: invalidates functions referencing the type.
  Typical hit rate ~80% post-edit, depending on how widely the
  type is used.
- In-place rewrite of a local rename: skips decompile entirely.
  Latency ~milliseconds vs. seconds.

Concrete numbers ship with #36-5's telemetry.
