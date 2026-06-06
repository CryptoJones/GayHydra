---
number: 0009
title: Rec 36 — selective cache invalidation is address-range intersection first, dependency bitmaps later
status: accepted
date: 2026-06-06
audit_rec: 36
---

# Decision 0009: Rec 36 cache invalidation lands address-intersection-first, not bitmap-first

## Context

[CACHE_FLUSH_1871.md](../decompiler/CACHE_FLUSH_1871.md) (#36-1) is the
abstract design for Rec 36 of the 2026-05-21 audit, tracking upstream
issue [#1871](https://github.com/NationalSecurityAgency/ghidra/issues/1871):

> Certain 'local' changes, for example, renaming a variable, do not affect
> the decompilation of other functions. Therefore, scrubbing the
> decompiler cache due to the change in state can be very painful
> performance wise.

That doc proposes the fix as **per-function dependency-bitmap tracking**:
each cached `DecompileResult` records a sorted set of opaque
dependency IDs, and a modification ANDs its affected IDs against the
bitmaps to invalidate only the overlapping entries. Its sequencing
(#36-2..#36-6) puts the bitmap recording first (#36-2), then the
invalidation wiring (#36-3).

That doc was written **before grounding in the actual GUI cache and
event classes**. Having now read them, this DD records where the cache
and its global-flush actually live, and concludes that the dominant
user-visible win — the issue's own headline case, "renaming a variable"
— is reachable with **address-range intersection alone**, with no
dependency-bitmap subsystem. The bitmap is needed only for the residual
*cross-function* dependency cases and is demoted to a later phase. This
re-sequences #36-2/#36-3 so the first code PR is behavioral, testable,
and conservative.

This mirrors the Rec 39 Phase 2 rhythm: DD-0007 grounded the abstract
`FOR_LOOP_INLINE_DETECTION.md` plan against the real `constseq.cc`
mechanism and found the simplest in-tree path (extend `constseq`) beat
the proposed grand subsystem (an XML pattern-library engine). DD-0009 is
the same move for Rec 36 — the simplest in-tree path (intersect change
addresses against cached `Function` keys) beats the proposed subsystem
(opaque dependency bitmaps) for the case that matters most.

## What the in-tree cache actually is (grounding)

The decompiler GUI cache is a Guava cache on the **Java** side, not in
the C++ decompiler. Verified locations (2026-06-06 tree):

### The cache

`DecompilerController`
(`Ghidra/Features/Decompiler/src/main/java/ghidra/app/decompiler/component/DecompilerController.java`):

- The cache itself: `Cache<Function, DecompileResults> decompilerCache`
  (line 50), built with `softValues()` + `maximumSize(cacheSize)`
  (`buildCache`, line 354). The key is the `Function`; the value is its
  full `DecompileResults`.
- Population: `updateCache` puts `(function, results)` on every
  completed decompile (lines 235–241).
- Lookup: `loadFromCache` does `decompilerCache.getIfPresent(function)`
  for the function containing the cursor location (lines 153–173).
- **The global flush:** `clearCache()` = `decompilerCache.invalidateAll()`
  (lines 369–371).
- A *selective* invalidation already exists for one case:
  `programClosed` walks `decompilerCache.asMap().keySet()` and
  invalidates only the entries whose `function.getProgram()` is the
  closed program (lines 373–380). This is the existing precedent for
  "iterate the keys and invalidate a subset" — the new path generalises
  it from "by program" to "by address-set intersection".

### The global-flush trigger path

Every program modification flushes the **entire** cache via two paths
inside one refresh:

`DecompilerProvider`
(`.../app/plugin/core/decompile/DecompilerProvider.java`):

- A buffered updater: `redecompileUpdater = new SwingUpdateManager(500,
  5000, () -> doRefresh(false))` (line 179), wired to the program
  listener (line 183).
- `doRefresh` (line 325) calls `controller.setOptions(decompilerOptions)`
  (line 349) **and** `controller.refreshDisplay(program, currentLocation,
  null)` (line 356).
- `setOptions` calls `clearCache()` (DecompilerController line 185);
  `refreshDisplay` calls `clearCache()` again (line 265). So a single
  comment edit flushes the whole cache twice over.

### The payload-bearing event source (currently discarded)

`DecompilerProgramListener`
(`.../app/decompiler/component/DecompilerProgramListener.java`):

- `domainObjectChanged(DomainObjectChangedEvent ev)` (line 60) is the one
  place that *sees* the actual change records. Today it inspects only a
  few coarse signals (`MEMORY_BLOCK_*`/`RESTORED` → `resetDecompiler`;
  spec-extension property change → `resetDecompiler`) and otherwise just
  calls `updater.update()` (line 82) — **throwing the records away**. The
  buffered `doRefresh` that eventually runs has no idea what changed, so
  it can only flush everything.

### What the records carry

`ProgramChangeRecord`
(`Ghidra/Framework/SoftwareModeling/src/main/java/ghidra/program/util/ProgramChangeRecord.java`):

- `getEventType()` — a `ProgramEvent` enum value.
- `getStart()` / `getEnd()` — the affected address range (lines 55, 63).
- `getObject()` — the affected object (line 71).

`ProgramEvent`
(`.../program/util/ProgramEvent.java`) is a fine-grained taxonomy:
`COMMENT_CHANGED`, `SYMBOL_RENAMED`, `SYMBOL_ADDRESS_CHANGED`,
`SYMBOL_DATA_CHANGED`, `DATA_TYPE_CHANGED`, `FUNCTION_CHANGED`,
`CODE_*`, `MEMORY_*`, etc. Each modification fires a specific type with a
specific address range — exactly the signal needed to decide *which*
cached function(s) a change can affect.

### The dependency source for a future bitmap phase

`DecompileResults.getHighFunction()` (line 175) exposes the
`HighFunction`, from which the callees, referenced data types, and
referenced globals of a decompiled function are enumerable. So the
*full* per-function dependency set the abstract doc wants is derivable —
but it is only needed for the cross-function cases below, not for the
headline local-edit case.

## Decision

### 1. First code PR = address-range intersection, not bitmaps

Replace the blanket flush on the refresh path with **selective
invalidation keyed on the change records' address ranges**:

- `DecompilerProgramListener.domainObjectChanged` collects the affected
  address ranges (`getStart()`/`getEnd()`) of records whose
  `ProgramEvent` is on a **function-local allow-list**, into an
  `AddressSet`, and hands it to the controller.
- A new `DecompilerController.invalidate(AddressSetView)` generalises the
  existing `programClosed` key-walk (lines 373–380): for each cached
  `Function` key, invalidate it iff its body
  (`function.getBody()`) intersects the changed set. Non-intersecting
  entries stay cached.
- The refresh path stops calling the unconditional `clearCache()` for
  these allow-listed local edits; it invalidates only the intersecting
  functions and re-decompiles the current one.

The allow-list starts minimal and conservative — the events whose
`start`/`end` map cleanly to a single function body:

- `COMMENT_CHANGED` (the issue's "adding a comment" case),
- `SYMBOL_RENAMED` / `SYMBOL_ADDRESS_CHANGED` / `SYMBOL_DATA_CHANGED`
  whose address falls inside a function body (the issue's "renaming a
  variable" case),
- `USER_DATA_CHANGED` / `CODE_UNIT_USER_DATA_CHANGED` for a single code
  unit.

### 2. Conservative default: anything not allow-listed still flushes

Any event type **not** on the allow-list — `DATA_TYPE_*`, `MEMORY_*`,
`FUNCTION_BODY_CHANGED`, `LANGUAGE_CHANGED`, options changes, or any
type added upstream later that we have not classified — keeps the
existing full `clearCache()`. This makes the first phase **strictly a
performance improvement with no correctness risk**: cache liveness can
only shrink relative to today (more invalidation), never grow. The
design doc's headline risk ("missed dependency → user sees stale
decompilation") cannot occur in this phase, because an unrecognised or
ambiguous change flushes everything exactly as it does now.

### 3. Dependency bitmaps (original #36-2) demoted to a later phase

The opaque dependency-bitmap subsystem is needed only for the residual
**cross-function** cases that address-intersection cannot resolve:

- A callee's signature changes → every *caller's* decompilation may
  change, but the change record's address is in the *callee*, so
  intersection alone would (correctly, conservatively) miss the callers
  and they would fall under the rule-2 full-flush for that event class.
- A shared data type or global is edited → every function referencing it
  is affected, with no single address range to intersect.

For these, the bitmap (built from `DecompileResults.getHighFunction()`'s
callee/datatype/global enumeration) lets invalidation be narrowed below
"flush everything". That is real value, but it is the *smaller* slice of
the win (type/callee edits are rarer than renames/comments) and it
reintroduces the missed-dependency correctness risk, so it ships **after**
the address-intersection phase is landed and measured — guarded by the
design doc's debug-assert recompute mode.

### 4. In-place rewrite (#36-4) unchanged

The in-place-rewrite optimisation (modify the cached `DecompileResults`
for a rename/comment without re-decompiling at all) is orthogonal and
keeps its place after the invalidation work.

## Re-sequencing

| Original (CACHE_FLUSH_1871.md) | This DD |
|---|---|
| #36-1 design doc | done |
| #36-2 dependency-bitmap recording on `DecompileResult` | **demoted** to #36-3b below — needed only for cross-function edits |
| #36-3 wire callbacks to invalidate by bitmap intersection | **reframed** to #36-3a: invalidate by **address-set** intersection (no bitmap), conservative allow-list, full-flush default |
| #36-4 in-place rewrite paths | unchanged (after #36-3a) |
| #36-5 telemetry | unchanged |
| #36-6 budget cache key (depends on Rec 35) | unchanged |

New code-PR order:

| PR | Scope | Gate |
|---|---|---|
| #36-3a | `DecompilerController.invalidate(AddressSetView)` + thread the allow-listed change-record address-set from `DecompilerProgramListener` to it; replace the blanket flush for local edits. **Java GUI change.** | headed integration test (extend `DecompilerCachingTest`) — run locally before push |
| #36-3b | Per-function dependency bitmap (callee/datatype/global) for the cross-function residual + debug-assert recompute mode | headed integration test + corpus assert |
| #36-4 | In-place rewrite for local name / comment | headed integration test |
| #36-5 | Telemetry: hit rate, in-place rate | — |
| #36-6 | `budget` cache-key extension (Rec 35) | — |

## Testability note (gating the code PR)

The cache and its flush live entirely in GUI code exercised by
`DecompilerCachingTest`
(`src/test.slow/.../component/DecompilerCachingTest.java`), an
`AbstractGhidraHeadedIntegrationTest`. Its existing
`testDomainChangeClearsTheCache` asserts that a `createFunctionComment`
on `fun1` clears all three cached entries — i.e. it currently *encodes
the bug*. #36-3a flips that expectation: a comment edit on `fun1`
invalidates **only** `fun1`, leaving `fun2`/`fun3` cached. That headed
integration test is the local test-before-push gate for the code PR;
this DD is docs-only and carries no C++/manifest/RAII-audit gate.

## Status

- #36-1 — design doc — **done** (CACHE_FLUSH_1871.md)
- This DD (#36 grounding + re-sequencing) — **done**
- #36-3a — address-intersection selective invalidation — **done** (comment-only; see addendum)
- #36-3b / #36-4 / #36-5 / #36-6 — sequenced above

## Addendum (2026-06-06): #36-3a ships comment-only; symbol renames are not address-scopable

Implementing #36-3a surfaced a correctness constraint that narrows the
"function-local" allow-list this DD sketched above (which named
`COMMENT_CHANGED` **and** `SYMBOL_RENAMED` / `SYMBOL_ADDRESS_CHANGED` /
`SYMBOL_DATA_CHANGED`). The narrowing:

**Address-range intersection only correctly scopes an edit whose change
record carries an address inside the owning function's *code body*.**
That holds for `COMMENT_CHANGED`: `CommentChangeRecord` carries the code
address of the comment (`start = end = address`,
`CommentChangeRecord.java:39`), and a function comment routes through
`FunctionDB.setComment` → `CodeManager.setComment(entryPoint, PLATE, …)`
→ a single `COMMENT_CHANGED` at the function entry
(`CodeManager.java:3006`), which lies in that function's body. Intersecting
it against the cached `Function.getBody()` sets invalidates exactly the
right entry.

It does **not** hold for a **local-variable rename**. A local symbol's
address is in **stack / register space**, not the function's code body, so
intersecting a `SYMBOL_RENAMED` address against function bodies would match
**nothing** and invalidate **nothing** — leaving the renamed variable's
function showing the stale old name. That is precisely the
"missed dependency → stale decompilation" failure
[CACHE_FLUSH_1871.md](../decompiler/CACHE_FLUSH_1871.md) warns about.
Correctly scoping a rename needs a **symbol → owning-function** mapping
(map the changed symbol to its function, then invalidate that function),
which is a different mechanism than address intersection.

Therefore #36-3a's shipped allow-list is **`COMMENT_CHANGED` only** — the
issue's "adding a comment" example, provably correct by intersection.
Every other event, including all symbol renames, stays on the conservative
full-flush path (unchanged from today), so no rename can go stale. This is
consistent with this DD's rule 2 ("conservative default: anything not
allow-listed still flushes") and rule 1's "the allow-list starts minimal".

Re-sequenced follow-up:

| PR | Scope |
|---|---|
| #36-3a (this) | `COMMENT_CHANGED` selective invalidation by address intersection |
| #36-3a-2 | symbol → owning-function invalidation for local renames/retypes (the issue's "renaming a variable" headline) |
| #36-3b | cross-function dependency bitmap (callee-signature / shared-datatype edits) |

The shipped mechanism — `DecompilerController.invalidate(AddressSetView)`,
the `DecompilerProgramListener` classifier, and the `localProgramChange`
selective-refresh path — is the reusable foundation all three build on;
#36-3a-2 and #36-3b add new *sources* of invalidation onto it, not new
plumbing.
