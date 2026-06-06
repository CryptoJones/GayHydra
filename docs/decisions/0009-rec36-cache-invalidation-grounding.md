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
- #36-3a — address-intersection selective invalidation — **done** (comment-only; see addendum 1)
- #36-3a-2 — symbol→owning-function invalidation for local/param renames — **done** (see addendum 2)
- #36-3b — cross-function dependency invalidation — **design grounded** (see addendum 3); splits into #36-3b-1 (callee signature/modifier → callers) + #36-3b-2 (shared-datatype → referencing functions):
  - #36-3b-1 — callee signature/modifier change → invalidate callee + its `getCallingFunctions` set via the existing `invalidate(AddressSetView)` path, no bitmap — **done**
  - #36-3b-2a — in-place `DATA_TYPE_CHANGED` → `invalidateByDataTypeIds`, type-ref set **recomputed** from the cached `HighFunction` (no recorded per-result state, addendum 4), gate tolerates the benign `DATA_TYPE_ADDED`/`SOURCE_ARCHIVE_CHANGED` companions (addendum 5) — **done**
  - #36-3b-2b — instance-swap event triage (addendum 6): `DATA_TYPE_RENAMED` folds into the 2a id-path, `DATA_TYPE_MOVED` is a rendering-invariant benign companion, `DATA_TYPE_REPLACED` **stays permanently full-flush** (its id is dropped at `ProgramDB.dataTypeChanged:890`) — **done**
  - #36-3b-2 recompute backstop — the addendum-3 debug-assert recompute mode, regrounded as a **test-harness corpus assertion** (addendum 7) — **done** (`DecompilerCachingTest.testKeptEntriesReDecompileUnchangedAfterDataTypeEdit`)
- #36-4 — in-place rewrite for local name / comment — **deferred behind #36-5 telemetry** (see addendum 8): the `FieldPanel` bakes token text+width at layout build, and #36-3a/3a-2 already reduce a rename/comment to a single-function re-decompile, so the optimisation is not worth its staleness risk until telemetry shows a real cost
- #36-5 — telemetry (hit rate, in-place/selective rate, decompile latency) — grounded in addendum 9; split into:
  - #36-5a — hit/miss + full-flush + selective-address + selective-datatype counters, exposed via `getCacheStats()` — **done**
  - #36-5b — decompile latency (request→callback wall-clock), async/cross-thread, recorded only for completed decompiles, exposed on `CacheStatsSnapshot` — **done** (see addendum 9)
- #36-6 — `budget` cache-key extension (Rec 35) — **deferred** (see addendum 10): it is the cache side of Rec 35 #35-6 and is blocked on Rec 35 #35-5 (the GUI partial/retry path); the GUI carries no budget today, so `Function` is already a correct key and there is nothing budget-tagged to key on or test

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

## Addendum 2 (2026-06-06): #36-3a-2 keys off the symbol's `SymbolType`, not `FUNCTION_CHANGED` trust

Addendum 1 deferred local/parameter renames to #36-3a-2 and named the needed
mechanism a "symbol → owning-function mapping". Grounding that mapping against
the actual event-firing code revealed the naive form of it is unsafe; this
addendum records the corrected rule before the #36-3a-2 implementation PR.

### What a local/parameter NAME rename actually fires

Renaming a local variable or parameter runs
`SymbolDB.setName` → `SymbolManager.symbolRenamed` →
`ProgramDB.symbolChanged(symbol, SYMBOL_RENAMED, symbol.getAddress(), …)`
(`ProgramDB.java:1033`). Because the symbol is a `VariableSymbolDB` whose
parent namespace is a `Function`, that method **also** fires a companion
`FunctionChangeRecord(function, null)` carrying the function entry point
(`ProgramDB.java:1041-1048`), and only then the plain
`ProgramChangeRecord(SYMBOL_RENAMED, …)`. So the delivered
`DomainObjectChangedEvent` batch for one rename is a **pair**:

| record | eventType | start = end | object |
|---|---|---|---|
| `FunctionChangeRecord` | `FUNCTION_CHANGED` (`UNSPECIFIED`) | function entry point — **in the code body** | the owning `Function` |
| `ProgramChangeRecord` | `SYMBOL_RENAMED` | the symbol storage address — **stack / register space, not the body** | the renamed `Symbol` |

This is why #36-3a's address-intersection alone could not scope a rename
(addendum 1): the only record whose address is in the body is the companion
`FunctionChangeRecord`, not the `SYMBOL_RENAMED` itself.

### Why "trust the companion `FUNCTION_CHANGED` entry point" is not safe

Intersecting the companion record's entry-point address against function
bodies *would* correctly hit the renamed function. But `FUNCTION_CHANGED`
with the `UNSPECIFIED` change type (`changeType == null`) is **overloaded** —
it is fired from many sites that are not local-variable renames:
`setStackLocalSize` (`FunctionDB.java:1028`), `setStackReturnOffset`
(`FunctionDB.java:1054`), `setSignatureSource` (`FunctionDB.java:1295`),
non-parameter variable retypes (`FunctionDB.dataTypeChanged`,
`FunctionVariables`), etc. At least one of these — the stack return offset,
i.e. the callee's stack purge — influences how **callers** decompile the call.
Treating every `FUNCTION_CHANGED/UNSPECIFIED` as function-local would
therefore silently leave callers stale: precisely the missed-dependency
failure [CACHE_FLUSH_1871.md](../decompiler/CACHE_FLUSH_1871.md) warns about,
and the reason rule 2's conservative default exists. So `FUNCTION_CHANGED`
is **not** a safe stand-alone "local" signal.

### The corrected rule for #36-3a-2

Admit a change batch as function-local — and invalidate only the owning
function(s) — iff **every** record is admissible under one of:

1. `COMMENT_CHANGED` (already shipped in #36-3a): owning function resolved by
   address intersection of `start`/`end` against cached bodies.
2. `SYMBOL_RENAMED` whose `getObject()` is a `Symbol` with
   `getSymbolType()` ∈ { `LOCAL_VAR`, `PARAMETER` }: a local/parameter **name**
   change. Such a symbol's parent namespace is the owning `Function`
   (the `VariableSymbolDB` invariant `ProgramDB.java:1041-1043` relies on), so
   resolve the function from it and add `function.getBody()` to the set. A
   parameter/local *name* change does not alter the signature callers render
   (callers show argument expressions, not the callee's local names), so it is
   genuinely function-local.
3. A companion `FunctionChangeRecord` with `getSpecificChangeType() ==
   UNSPECIFIED` is admitted **only by correlation** — iff its `getFunction()`
   equals a function already resolved from a sibling rule-2 record in the same
   batch. A `FUNCTION_CHANGED/UNSPECIFIED` that arrives **alone** (e.g. a bare
   `setStackReturnOffset`) has no such sibling and forces the whole batch to
   the full flush.

Any record that is none of the above forces a full flush. In particular a
`FunctionChangeRecord` for which `isFunctionSignatureChange()`
(`PARAMETERS_CHANGED` / `RETURN_TYPE_CHANGED`) or `isFunctionModifierChange()`
(`INLINE` / `NO_RETURN` / `CALL_FIXUP` / `THUNK` / `PURGE`) is true is
caller-affecting and stays on the full-flush path. So a parameter **retype**
(which fires `PARAMETERS_CHANGED`, `FunctionDB.dataTypeChanged`) is correctly
*not* treated as local; cross-function retype/signature scoping is the job of
the #36-3b dependency bitmap, not #36-3a-2.

### Plumbing impact

This is still a *source* added onto the #36-3a foundation, not new plumbing:
the classifier in `DecompilerProgramListener` grows from "every record is
`COMMENT_CHANGED`" to "every record is admissible-local", and resolved
function bodies are unioned into the same `AddressSet` already handed to
`DecompilerController.invalidate(AddressSetView)`. The #36-3a-2 implementation
PR will add a headed `DecompilerCachingTest` case that renames a local in
`fun1` and asserts `fun1` misses while `fun2`/`fun3` stay cached, plus a
negative case asserting a parameter **retype** still full-flushes — and that
test will dump the real delivered record batch to lock this empirical taxonomy
against upstream event-firing changes.

## Addendum 3 (2026-06-06): #36-3b cross-function residual splits into callers (no bitmap) and datatype-refs (recorded set)

Addenda 1 and 2 closed out the function-*local* cases (comments, local/parameter
renames). What remains for Rec 36 is the **cross-function** residual this DD's
rule 3 deferred: a change inside one function — or to a shared type — that alters
how *other* functions decompile. CACHE_FLUSH_1871.md proposed a single
"per-function dependency bitmap" for all of it (the demoted #36-2). Grounding
that proposal against the real event-firing and decompile-result APIs shows the
residual is **two cases with very different cost and risk**, and that only one of
them actually needs a recorded dependency set. This addendum records the split
before the #36-3b implementation PRs, the same way addendum 2 corrected the
#36-3a-2 rule before its implementation.

### Case A — callee signature/modifier change → invalidate the callers

When a function's signature or a caller-visible modifier changes, every function
that **calls** it may decompile differently (the caller renders argument
expressions against the callee prototype, drops code after a now-`noreturn`
call, inlines an now-`inline` callee, adjusts for a changed stack purge, etc.).

What this fires is a `FunctionChangeRecord`
(`FunctionChangeRecord.java:42-45`): `super(FUNCTION_CHANGED,
function.getEntryPoint(), function.getEntryPoint(), function, null, null)`, so
`getObject()`/`getFunction()` is the **changed callee** and `getStart()`/
`getEnd()` is its entry point. The discriminators already exist on the record:
`isFunctionSignatureChange()` covers `PARAMETERS_CHANGED`/`RETURN_TYPE_CHANGED`
(`FunctionChangeRecord.java:68-71`) and `isFunctionModifierChange()` covers
`THUNK`/`INLINE`/`NO_RETURN`/`CALL_FIXUP`/`PURGE` (`:77-85`) — **all** of which
are caller-affecting.

The key grounding result: **the callers are already enumerable from the existing
call-reference graph — no recorded per-function dependency set is needed.**
`Function.getCallingFunctions(TaskMonitor)` returns `Set<Function>`
(`Function.java:727`), the set of functions that reference the changed callee via
a call. So Case A reduces to: resolve the changed function's callers, union each
caller's `getBody()` (and the callee's own body) into the **same `AddressSet`**
already handed to `DecompilerController.invalidate(AddressSetView)`, and let the
existing #36-3a plumbing invalidate exactly those cache entries. This mirrors the
addendum-2 finding for renames: the simplest in-tree path (an existing graph
query) beats the proposed subsystem.

Residual to note, not block on: `getCallingFunctions` is the direct-call
reference graph, so a purely *indirect* (`CALLIND`) caller is not in the set —
but a caller that reaches the callee only indirectly has no resolved callee
prototype at that site, so the signature change does not alter its rendering.
The graph is therefore the same set the decompiler itself uses to apply the
callee prototype. The debug-assert recompute mode below is the backstop if that
assumption is ever wrong.

### Case B — shared datatype edit → invalidate the referencing functions

Editing a struct/union/typedef/enum changes every function that references that
type. Here address-intersection is *structurally* impossible, and the
single-bitmap proposal is genuinely needed. Grounding the event:
`ProgramDataTypeManager.dataTypeChanged` (`ProgramDataTypeManager.java:172-178`)
calls `program.dataTypeChanged(getID(dt), DATA_TYPE_CHANGED, isAutoChange, null,
dt)`, and `ProgramDB.dataTypeChanged` (`ProgramDB.java:874-891`) fires
`new ProgramChangeRecord(eventType, null, null, null, oldValue, newValue)`.

So — correcting a plausible-but-wrong assumption — the delivered record for a
datatype edit has **`getStart()` == `getEnd()` == null** (no address to
intersect, as rule 3 anticipated) **and `getObject()` == null**. The changed
`DataType` is carried in **`getNewValue()`**, not `getObject()`; for
`DATA_TYPE_REPLACED` (`:195-201`) `getOldValue()` is the existing `DataTypePath`
and `getNewValue()` is the replacement instance. Identity must be taken by the
manager's id (`DataTypeManager.getID(dt)`) / `UniversalID`, **not** object
identity, because `DATA_TYPE_REPLACED` swaps the instance.

There is **no reverse index** from `DataType` → referencing functions in the
program DB (`ReferenceManager` tracks address xrefs, not type usage). This is the
one case that justifies recording a per-cached-result dependency set. The set is
derivable from the already-cached `DecompileResults.getHighFunction()`
(`DecompileResults.java:175`): the referenced types are the
`FunctionPrototype` return + parameter types plus the types of every
`HighSymbol` in `getLocalSymbolMap().getSymbols()` and
`getGlobalSymbolMap().getSymbols()`. Record those type ids alongside each cache
value; on a datatype edit, invalidate exactly the entries whose recorded id set
contains the changed type's id.

Containment is handled by the existing auto-change cascade rather than by storing
a transitive closure: editing a contained type fires `dataTypeChanged` with
`isAutoChange` on the dependent/container types too, so a function that
references only the outer struct still receives a `DATA_TYPE_CHANGED` for that
struct. The recorded set can therefore stay **direct references** and rely on the
cascade — a grounding assumption the #36-3b-2 test must pin down (edit an inner
field type, assert a function using only the outer struct is invalidated).

### Re-sequencing #36-3b

| PR | Scope | Why this risk tier |
|---|---|---|
| #36-3b-1 | Callee signature/modifier change → invalidate callee + its `getCallingFunctions` set, by unioning their bodies into the existing `invalidate(AddressSetView)` path. **No new per-result state, no bitmap.** | Low — reuses the #36-3a plumbing and an existing graph query; the conservative full-flush default still catches bare overloaded `UNSPECIFIED` (e.g. `setStackReturnOffset`), unchanged from addendum 2 |
| #36-3b-2 | Shared-datatype edit → record each cached result's referenced-type-id set (from `HighFunction`) and add a new datatype-keyed invalidation path (not address-based). | Higher — new per-cached-result state and the first invalidation that cannot fall back to address intersection; ships **with** the debug-assert recompute mode |

### Correctness backstop (debug-assert recompute mode)

#36-3b-2 is the first phase that reintroduces this DD's headline risk (a missed
type/caller dependency → stale decompilation). It ships behind a debug-assert
recompute mode: in an assert build, after a cross-function edit, re-decompile the
entries that selective invalidation **kept** and assert their output is
unchanged. A violation means the dependency set under-approximated, and is caught
in test/CI rather than as a user-visible stale render. The conservative default
(rule 2) is unchanged throughout: any event neither address-scopable (addenda 1)
nor classified here still full-flushes, so liveness can only shrink.

### Plumbing impact

#36-3b-1 adds **no** new plumbing — it is another *source* feeding the existing
`AddressSet` → `invalidate(AddressSetView)` path, exactly like #36-3a-2.
#36-3b-2 adds the only genuinely new machinery in Rec 36's invalidation work: a
referenced-type-id set recorded per cache value and a datatype-keyed invalidation
entry point on `DecompilerController`, sitting beside the existing
address-keyed `invalidate(AddressSetView)` rather than replacing it.

## Addendum 4 (2026-06-06): #36-3b-2's type-ref set is recomputed from the cached HighFunction, not recorded; split into 2a (DATA_TYPE_CHANGED) and 2b (instance-swap events)

Addendum 3 grounded Case B's design but projected its plumbing as a
**referenced-type-id set recorded per cache value** — the "Higher risk: new
per-cached-result state" tier. Verifying the type-resolution path before
implementing #36-3b-2 shows that recorded state is **not needed for the common
case**, which both lowers the risk and removes the only genuinely new per-result
machinery this DD had outstanding.

### The cached HighFunction's symbol types are live program-managed instances

A `HighSymbol`'s data type is decoded via
`HighSymbol.decode → dtmanage.decodeDataType(decoder)`
(`HighSymbol.java:479`), where `dtmanage` is a `PcodeDataTypeManager`.
`PcodeDataTypeManager.decodeDataType` resolves a non-builtin type through
`findBaseType(name, id)` (`PcodeDataTypeManager.java:213-247`), which returns
`progDataTypes.getDataType(id)` (`:181-184`) — and `progDataTypes` is exactly
`prog.getDataTypeManager()` (`:137`). So the `DataType` that
`HighSymbol.getDataType()` (`HighSymbol.java:212`) hands back is the **same
program-`DataTypeManager`-managed instance** the editor mutates, carrying the
**same id** that `DataTypeManager.getID` (`DataTypeManager.java:263`) returns and
that the `DATA_TYPE_CHANGED` event reports.

Consequence: for an **in-place** `DATA_TYPE_CHANGED` (a struct/union/enum/typedef
field/member edit — the instance and its id are stable), the referenced-type-id
set is **recomputable on demand** from the already-cached
`DecompileResults.getHighFunction()`. There is no need to record it at
insertion time. The cache is the bounded GUI cache (single-digit-to-dozens of
entries), so an O(cached entries × symbols) walk per datatype edit — a rare,
human-paced action — is cheap. This **supersedes addendum 3's "record per cache
value"** for #36-3b-2a: no new per-cached-result state, no `RemovalListener`
lifecycle to keep a side map in sync with eviction. The only new machinery is the
datatype-id-keyed entry point `DecompilerController.invalidateByDataTypeIds`,
beside the existing address-keyed `invalidate(AddressSetView)`.

### Enumerating a cached function's referenced types

From `hf = results.getHighFunction()` (null ⇒ no model to prove non-reference ⇒
**conservatively invalidate**, liveness only shrinks):

- `hf.getFunctionPrototype()` (`HighFunction.java:109`) → `getReturnType()`
  (`FunctionPrototype.java:243`) and `getParam(i).getDataType()` for
  `i < getNumParams()` (`:204-216`);
- `hf.getLocalSymbolMap().getSymbols()` (`HighFunction.java:127`,
  `LocalSymbolMap.java:381`) and `hf.getGlobalSymbolMap().getSymbols()`
  (`:134`, `GlobalSymbolMap.java:172`) → each `HighSymbol.getDataType()`.

### Matching must unwrap derived types (the real correctness hazard)

A symbol typed `MyStruct *` or `MyStruct[10]` has a **different** id than
`MyStruct`, so a top-level `getID` compare misses functions that reference the
edited type only through a pointer/array/typedef. Addendum 3 hoped the auto-change
cascade would cover containment, but the cascade fires for *stored dependent
types*, and pointers/arrays to a type are frequently derived on the fly, not
separate manager entries that receive their own `DATA_TYPE_CHANGED`. So matching
must **recursively unwrap** each candidate type and test the changed-id set at
every level: `Pointer.getDataType()` (`Pointer.java:34`),
`Array.getDataType()` (`Array.java:51`), `TypeDef.getDataType()`
(`TypeDef.java:47`). Skip the `NULL_DATATYPE_ID` (`-1`) /
`BAD_DATATYPE_ID` (`-2`) sentinels (`DataTypeManager.java:47-52`). This unwrap —
not the cascade — is what the #36-3b-2a test must pin down (edit a struct; assert
a function whose only use is `struct *` is invalidated).

### Re-sequencing #36-3b-2

| PR | Scope | Risk |
|---|---|---|
| #36-3b-2a | `DATA_TYPE_CHANGED` only → `invalidateByDataTypeIds` recomputed from each cached `HighFunction`, with the derived-type unwrap above. Ships **with** the debug-assert recompute backstop. | Moderate — first non-address invalidation, but **no recorded per-result state** (recompute), and the unwrap removes the cascade dependency. Anything not a pure-`DATA_TYPE_CHANGED` batch still full-flushes. |
| #36-3b-2b | `DATA_TYPE_REPLACED` / `DATA_TYPE_MOVED` / `DATA_TYPE_RENAMED` — instance-swap / path-change events where the cached `HighFunction` may hold the **old** instance, so the id must come from `getOldValue()` and recompute-from-cache no longer matches cleanly. Deferred. | Higher — instance identity churns; may reintroduce a recorded id set or fall back to full-flush. Conservative full-flush remains the default until shipped. |

Docs-only; precedes the #36-3b-2a implementation, mirroring the addendum-3 →
#36-3b-1 rhythm. The conservative default (rule 2) is unchanged: any datatype
event that is not an in-place `DATA_TYPE_CHANGED`, and any batch mixing datatype
records with other event types, still full-flushes — liveness can only shrink.

## Addendum 5 (2026-06-06): an in-place datatype edit never arrives as a *pure* `DATA_TYPE_CHANGED` batch — the gate must tolerate benign companions

Addendum 4 closed by restating the conservative default as: "any batch mixing
datatype records with other event types still full-flushes." Implementing
#36-3b-2a and exercising it under the headed `DecompilerCachingTest` showed that
restated-as-written, that default makes the selective path **dead code**: an
in-place struct edit *never* produces a batch containing only
`DATA_TYPE_CHANGED`.

### What the editor actually fires

Adding a field to a resolved struct (the headed test's
`struct.add(new ByteDataType(), …)`) produces the batch:

```
SOURCE_ARCHIVE_CHANGED  DATA_TYPE_ADDED  DATA_TYPE_CHANGED
```

and even reusing a type already in the manager (so no new type is pulled in)
still yields:

```
SOURCE_ARCHIVE_CHANGED  SOURCE_ARCHIVE_CHANGED  DATA_TYPE_CHANGED
```

So `SOURCE_ARCHIVE_CHANGED` is an unavoidable companion of an in-place edit, and
`DATA_TYPE_ADDED` accompanies the common case of adding a field whose type is not
yet present. A strict "only `DATA_TYPE_CHANGED`" gate rejects both and
full-flushes every real edit — the optimisation would never fire.

### The two companions are provably benign for *already-cached* functions

The gate is therefore broadened to admit these two record types **without
contributing ids**, keying invalidation solely on the `DATA_TYPE_CHANGED` ids:

- `DATA_TYPE_ADDED` — a type that has just been added did not exist when any
  currently-cached function was decompiled, so no cached `HighFunction` can
  reference it. Skipping it cannot under-invalidate.
- `SOURCE_ARCHIVE_CHANGED` — data-type source-archive sync metadata (a
  `UniversalID`, no address, no per-function rendering impact). It cannot change
  how any cached function renders.

Every *other* record type — `FUNCTION_CHANGED`, any `SYMBOL_*`, `REFERENCE_*`,
the instance-swap datatype events, etc. — still forces the full flush, so the
conservative default holds for anything that *could* carry cross-function impact.
A batch with no `DATA_TYPE_CHANGED` at all (e.g. a pure type import:
`SOURCE_ARCHIVE_CHANGED`/`DATA_TYPE_ADDED` only) contributes no ids and falls back
to the full flush rather than being specially treated — still safe, since
liveness only shrinks.

### Corrects, does not replace, addendum 4

Addendum 4's substantive contributions stand and were confirmed by the
implementation: the type-ref set is **recomputed from the cached `HighFunction`**
(no recorded per-result state), and matching **recursively unwraps**
`Pointer`/`Array`/`TypeDef` — verified by the headed test, in which `fun1`'s
committed return type is a *transient* `MyStruct *` whose own
`getID` is `NULL_DATATYPE_ID` (it is not a manager-registered pointer), yet whose
`getDataType()` is the live program `MyStruct`; the unwrap is what lets the edit
reach it. Only addendum 4's *pure-`DATA_TYPE_CHANGED`* phrasing of the gate is
superseded here.

This addendum is shipped as the docs-first step for the broadened gate, and the
#36-3b-2a implementation lands the gate, the `invalidateByDataTypeIds`
entry point, and the headed test together.

## Addendum 6 (2026-06-06): the three #36-3b-2b events are not alike — RENAMED folds into the 2a id-path, MOVED is rendering-invariant, only REPLACED stays full-flush

Addendum 4 deferred `DATA_TYPE_REPLACED` / `DATA_TYPE_MOVED` / `DATA_TYPE_RENAMED`
to #36-3b-2b under one banner: "instance-swap / path-change events where the
cached `HighFunction` may hold the **old** instance, so the id must come from
`getOldValue()` and recompute-from-cache no longer matches cleanly." Reading the
actual firing sites before implementing shows that banner is **true of only one
of the three**, and even there the id is not in `getOldValue()`.

### What each event actually carries

All three route through `ProgramDB.dataTypeChanged`
(`ProgramDB.java:874-891`), which builds
`new ProgramChangeRecord(eventType, null, null, null, oldValue, newValue)` — so
`getStart`/`getEnd`/`getObject` are null and only `getOldValue()`/`getNewValue()`
carry payload. The `dataTypeID` argument is used **only** to record the change set
(`:878-880`) and is **not placed in the record**. The Program-side firing sites
(`ProgramDataTypeManager.java`) are:

| Event | `getOldValue()` | `getNewValue()` | id arg (dropped from record) |
|---|---|---|---|
| `DATA_TYPE_RENAMED` (`:218-221`) | `oldName` (`String`) | `dt` — the **same live instance**, renamed in place | `getID(dt)` |
| `DATA_TYPE_MOVED` (`:210-215`) | old `Category` | `dt` — the **same live instance**, recategorised | `getID(dt)` |
| `DATA_TYPE_REPLACED` (`:195-201`) | `existingPath` (`DataTypePath`) | `replacementDt` — a **different** instance | `existingDtID` |

So `getOldValue()` is **never a `DataType`** for any of the three — addendum 4's
"id must come from `getOldValue()`" does not hold. The split is by instance/id
stability, which is the opposite of how addendum 4 grouped them.

### RENAMED — stable id, renders by name → reuse the 2a id-path verbatim

A rename mutates the type's name field; its DB id (a `DataTypeManager` key
independent of name) and its instance are **stable**. `getNewValue()` is the same
program-`DataTypeManager` instance a cached `HighFunction`'s
`HighSymbol.getDataType()` hands back (addendum 4), carrying the **same id**.
The decompiler renders the type *name* into its `ClangToken` text, which a cached
`DecompileResults` froze at decode time — so a rename leaves the displayed name
**stale** until the referencing functions re-decompile. Both facts mean RENAMED is
handled by **exactly the addendum-4/5 recompute path**: extract the id from
`getNewValue()` (identical to the `DATA_TYPE_CHANGED` branch — both put the
`DataType` in `getNewValue()`), run it through `invalidateByDataTypeIds`, and the
`Pointer`/`Array`/`TypeDef` unwrap catches functions that reference the renamed
type only through a derived type. **No new controller machinery.**

### MOVED — stable id, but category is not rendered → benign companion

A move changes only the `CategoryPath`; the instance and id are stable and the
**name is unchanged**. The Ghidra decompiler renders the bare type name, never its
category path, so a move **cannot change any cached function's rendering**. MOVED
is therefore admitted as a **benign companion** alongside `SOURCE_ARCHIVE_CHANGED`
and `DATA_TYPE_ADDED` (addendum 5): it contributes no ids. Consistent with
addendum 5's deliberate choice, a batch whose only datatype record is a MOVED
(no RENAMED/CHANGED) sees no id-contributing event and falls back to the
conservative full flush rather than a special no-op return — moves are rare and
this keeps the gate's contract unchanged (liveness only shrinks).

### REPLACED — the genuine residual, stays full-flush

REPLACED is the only true instance swap: `getNewValue()` is a **different**
instance (`replacementDt`) and the changed type's id (`existingDtID`) is the one
piece addendum 4 wanted — but it is **dropped at `ProgramDB.dataTypeChanged:890`**,
absent from the record. Recompute-from-cache cannot match it: a cached
`HighFunction` may still hold the **old** instance, whose id after replacement no
longer resolves cleanly, and the record offers no id to test against. REPLACED is
also flagged `isAutoChange = true` (`ProgramDataTypeManager.java:199`), i.e. it is
the program-DB's own replace bookkeeping. It therefore **stays on the full-flush
path** (it falls through to the gate's `else → return null`); a correct selective
handling would need a recorded id set keyed at replace time and is not pursued
here.

### Re-scope of #36-3b-2b

The implementation is confined to `collectChangedDataTypeIds`:

- the id-contributing branch widens from `DATA_TYPE_CHANGED` to
  `DATA_TYPE_CHANGED || DATA_TYPE_RENAMED` (both carry the live `DataType` in
  `getNewValue()`);
- the benign-companion branch widens to include `DATA_TYPE_MOVED`;
- `DATA_TYPE_REPLACED` and everything non-datatype keep forcing the full flush.

`invalidateByDataTypeIds` and the recompute/unwrap helpers are **unchanged**. A
headed `DecompilerCachingTest` case renames a struct referenced through a pointer
and asserts only the referencing function is invalidated (mirroring the 2a test),
proving the rename reaches the cached `HighFunction` via the live instance. This
addendum is the docs-first step; the #36-3b-2b implementation lands the widened
gate and the rename test together.

## Addendum 7 (2026-06-06): the #36-3b-2 recompute backstop is a test-harness corpus assertion, not a runtime DecompilerController mode

Addendum 3 committed #36-3b-2 to ship *behind a debug-assert recompute mode*:
"in an assert build, after a cross-function edit, re-decompile the entries
selective invalidation **kept** and assert their output is unchanged. A violation
means the dependency set under-approximated, and is caught in test/CI rather than
as a user-visible stale render." #36-3b-2a and #36-3b-2b shipped the selective
datatype-keyed invalidation but **not** that backstop. Grounding *where* the
backstop can run, against the real decompile/cache plumbing, shows it cannot be a
runtime mode on `DecompilerController` and belongs in the headed test harness —
which, as addendum 3 itself anticipated ("caught in test/CI"), is where the
corpus assert was always meant to live.

### What the backstop has to do

`invalidateByDataTypeIds` (`DecompilerController.java:427-440`) and its
address-keyed sibling `invalidate(AddressSetView)` (`:399-409`) work by *keeping*
every cache entry they cannot prove is affected. The backstop's contract is the
dual of that decision: for each entry the selective path **kept**, prove a fresh
decompile would render identically; a difference means
`referencesAnyDataType` (`:442-466`) under-approximated the dependency set.

### Why it cannot be a runtime DecompilerController mode

The GUI decompile path is **asynchronous and single-process**. A decompile is
dispatched through `decompilerMgr.decompile(...)`
(`DecompilerController.java:124`, `:283`) and its result is delivered back later
on the Swing thread via `setDecompileData` → `updateCache`
(`:230-244`), which is the only place a `DecompileResults` is `put` into the
cache (`:242`). `invalidateByDataTypeIds` runs synchronously inside the
`domainObjectChanged` listener dispatch; it has **no** synchronous
re-decompile-and-compare available to it. Bolting one on would mean, for every
kept entry, blocking the Swing thread on the single shared decompiler process
while it re-runs — serialising the whole UI on a correctness check, on every
datatype edit. That is the opposite of Rec 36's goal (the cache exists to *avoid*
re-decompiling), and an `-ea` assert is supposed to be cheap. So the
"debug-assert recompute mode" as a property of the live controller is rejected.

### Where it does belong: a corpus assertion in DecompilerCachingTest

The headed `DecompilerCachingTest`
(`src/test.slow/.../component/DecompilerCachingTest.java`) already has every
primitive the backstop needs and none of the constraints:

- it drives real decompiles through the GUI (`goTo(addr)`) and waits for them to
  settle (`waitForBusyDecompile`, `:491-495`), so a fresh decompile is a method
  call, not an inline block on the dispatch thread;
- it holds the cache directly (`cache.asMap()`, `:386`) and can enumerate exactly
  which entries a selective invalidation kept;
- the comparison surface is already frozen in each cached value:
  `DecompileResults.getDecompiledFunction().getC()`
  (`DecompileResults.java:206-213`) is the rendered C text, and
  `getCCodeMarkup()` (`:195`) the token tree — either is a stable equality key.

So the backstop is a **test helper**: snapshot the C of every entry the edit
*kept*, force each of those functions to re-decompile, and assert the C is
byte-identical (and, dually, that every entry the edit *dropped* was one whose C
actually changes). Run over the datatype-edit corpus (the 2a/2b struct-edit and
rename cases, extended), it is exactly addendum 3's "re-decompile the kept
entries and assert unchanged", realised as a CI assertion rather than a
production code path.

### Corrects, does not replace, addendum 3

Addendum 3's intent stands unchanged — the backstop exists, it guards the
headline risk (a missed type/caller dependency → stale render), and it is
"caught in test/CI". Only its projected *form* ("a debug-assert recompute mode"
read as a runtime `DecompilerController` behaviour) is superseded: the async,
single-process decompiler makes an inline runtime recompute self-defeating, and
the headed test is the correct host. This mirrors addenda 4/5/6, each of which
corrected a projected plumbing detail against the real APIs before the matching
implementation PR. This addendum is the docs-first step; the #36-3b-2 recompute
backstop lands as a `DecompilerCachingTest` corpus assertion next.

## Addendum 8 (2026-06-06): #36-4 in-place rewrite is deferred behind #36-5 telemetry — the layout bakes token text and width, and a rename already re-decompiles only one function

With #36-3b complete (the selective-invalidation work and its recompute
backstop), the next sequenced item is #36-4: the "in-place rewrite"
optimisation this DD's section 4 kept as orthogonal — "modify the cached
`DecompileResults` for a rename/comment without re-decompiling at all". Grounding
it against the real render model before implementing shows the projected form is
not worth building now, and that the honest move is to **defer it behind #36-5
telemetry** rather than ship speculative, fragile token surgery. This continues
the addenda-4/6/7 pattern of correcting a projected approach against the APIs;
here the correction is to *not* implement the phase yet.

### The token text is mutable, but the layout that displays it is baked

`ClangToken` does carry a mutable text field (`ClangToken.java:51`) with a setter
— but the setter is **package-private** (`ClangToken.java:187`), and, more
decisively, the on-screen text is **not read from the token at paint time**. When
the panel builds its layout, `ClangLayoutController.createFieldElementsForLine`
snapshots each token's text into a fixed `AttributedString`
(`ClangLayoutController.java:202`) wrapped in a `ClangFieldElement` (`:203`); the
`FieldPanel` then measures line wrapping and column positions from those
fixed-width elements. So calling `token.setText(newName)` on a cached
`ClangTokenGroup` changes the model but **not the display**: the line keeps the
old width and the rename never appears (and a length change — `x` →
`myLongName` — would misalign even if it did). Reflecting an edit therefore
requires rebuilding the layout (re-running `PrettyPrinter`/`ClangLine` →
`FieldElement[]`), i.e. re-doing the **Java-side render**, not just a repaint.

### What an in-place rewrite would and would not save

The expensive part of a refresh is the **native decompiler round-trip** (the
async, single-process C++ process that produces the markup), not the Java layout
build. So an in-place rewrite *could* in principle save the native decompile by
keeping the cached `ClangTokenGroup`, mutating the affected tokens, and rebuilding
only the Java layout. But making that correct is the hard part:

- A rename must update **every** token that renders the variable, matched by
  `Varnode`/`HighSymbol` identity off the cached `HighFunction` (whose decoded
  `HighSymbol`s still hold the *old* name). The displayed name is not the raw DB
  symbol name either — it passes through the decompiler's own name resolution
  (uniqueness suffixes, `IllegalCharCppTransformer`), so substituting the right
  rendered string is decompiler-internal and brittle.
- A comment edit looks simpler but the comment text is parsed for annotations
  (`CommentUtils.parseTextForAnnotations`, `ClangLayoutController.java:198`) and
  can be aggregated across tokens (`ClangCommentToken.derive`), so it has the same
  rebuild-and-re-parse coupling.

A wrong substitution shows a **stale or incorrect name** — strictly worse than a
brief re-decompile.

### Why deferral is the right call

The optimisation's saving is only over an **already-selective single-function
re-decompile**: #36-3a (comments) and #36-3a-2 (local renames) made these edits
invalidate just the owning function (see addenda 1–2), so a rename today costs one
function's native decompile, not a full-cache flush. Upstream Ghidra, written by
people who know the decompiler internals, also **re-decompiles on rename** — there
is no in-place token-rewrite path anywhere in the GUI (the `tokenRenamed`
callback only repairs highlight state, then waits for the rebuilt tokens). Adding
fragile token surgery to shave one single-function decompile, against an
architecture that bakes the layout and an upstream that deliberately avoids it, is
a premature optimisation that reintroduces a staleness risk Rec 36 spent its whole
arc removing.

So #36-4 is **re-scoped from "next" to "telemetry-gated"**: do **#36-5** (hit-rate
/ in-place-rate / decompile-latency telemetry) next, and only revisit #36-4 if the
measured single-function re-decompile latency × rename/comment frequency shows a
real user-visible cost. This is the same "measure before optimising; liveness only
shrinks" discipline the rest of the DD follows. If telemetry later justifies it,
the implementation path is the layout-rebuild-without-native-decompile route above
— recorded here so the analysis is not redone.

## Addendum 9 (2026-06-06): grounding the #36-5 telemetry surface — explicit hit and invalidation counters first (#36-5a), async decompile latency split out (#36-5b)

Addendum 8 deferred #36-4 *behind* #36-5: the in-place rewrite is only worth
building if telemetry shows the single-function re-decompile it would save is a
real, frequent cost. So #36-5 is no longer a vague "add some metrics" item — it
has a **specific job**: measure how often a program edit takes the cheap
selective path versus a full flush, and how expensive the resulting re-decompile
actually is. This addendum grounds that surface against the real
`DecompilerController` touchpoints before any code is written.

### What the cache code already exposes to count

Every event #36-5 needs to measure is already a single, identifiable call site in
`DecompilerController.java`:

| Metric | Touchpoint | Today's behaviour |
|---|---|---|
| navigation hit / miss | `loadFromCache` — `getIfPresent` non-null vs null (`:165–168`); the early `function == null` return (`:161`) is also a non-hit | returns a boolean; nothing recorded |
| cache populate | `updateCache` — `decompilerCache.put` (`:242`) | unconditional put on a completed decompile |
| full flush | `clearCache` — `invalidateAll` (`:386–387`) | called by the listener's buffered fallback, plus `setOptions`/`dispose`/`refreshDisplay` |
| selective address invalidation | `invalidate(AddressSetView)` (`:399–409`) | walks the key set, drops body-intersecting entries |
| selective datatype invalidation | `invalidateByDataTypeIds(Set<Long>)` (`:427–440`) | walks entries, drops type-referencing ones |

The **ratio that gates #36-4** falls straight out of the last three rows:
`selective ÷ (selective + edit-driven full)` is "how often an edit hit the cheap
path", and the per-call *entries-dropped* count on the two selective rows is "how
much work the cheap path saved". A rename/comment edit that consistently lands on
`invalidate(AddressSetView)` dropping one entry is exactly the already-cheap case
addendum 8 said in-place rewrite would only marginally improve.

### Decision 1 — explicit counters, not Guava `recordStats()`

Guava's `CacheBuilder.recordStats()` is the obvious free option, but it measures
the **wrong surface** for this gate:

- It counts hits/misses at the `getIfPresent` mechanic, so it would miss the
  `function == null` non-hit (`:161`) that still drives a decompile, and it folds
  soft-value evictions into its miss count — noise for a "did the user's
  navigation re-use a decompile" question.
- It records **nothing** about *why* an entry left the cache: a manual
  `invalidate` and a manual `invalidateAll` are both just "manual removals" to
  Guava, so the full-vs-selective breakdown — the entire point of #36-5 — is
  invisible to `stats()`.

So #36-5 uses explicit counters at the five call sites above. They are semantic
("a navigation missed", "an edit took the selective datatype path dropping N
entries"), which is what the gate and the headed test both need to assert on.

### Decision 2 — split latency (#36-5b) out from the counters (#36-5a)

Hit/invalidation counters are **synchronous and single-threaded-ish**: they
increment inside `loadFromCache` / the `invalidate*` methods, which the test
drives directly and can read back deterministically. Decompile *latency* is not:
the work spans `decompilerMgr.decompile(...)` (`:124`/`:283`) to the async
`setDecompileData` callback (`:230`) on a different thread, crossing the native
C++ process boundary, and its wall-clock varies run to run — so it neither lives
at a single call site nor yields a deterministic test assertion. Threading a
request timestamp through `DecompileData`/`DecompilerManager` is a larger, more
invasive change than the counters and belongs in its own slice.

Following the "scope shrinks; ground before building" discipline of addenda 4/8:

- **#36-5a** — hit/miss + full-flush + selective-address + selective-datatype
  counters (with entries-dropped on the selective paths), exposed as an immutable
  snapshot via a `getCacheStats()` accessor; counters held as `AtomicLong`/`LongAdder`
  since `setDecompileData`/`updateCache` can fire on the async callback thread
  while the listener invalidates on Swing. Fully testable in
  `DecompilerCachingTest` (it already holds the controller and cache directly and
  drives real edits), so it ships first.
- **#36-5b** — decompile latency (request→callback wall-clock), threaded through
  the decompile path; non-deterministic, so it carries an observational/log
  surface rather than a byte-exact test assertion. Sequenced after #36-5a.

No UI surface is introduced by either: the gate needs the numbers *queryable*
(test + a debug log/action later), not a panel. This addendum is docs-only and
carries no C++/manifest/RAII-audit gate; #36-5a lands next as the first
implementation slice.

## Addendum 10 (2026-06-06): #36-6 is the Rec 35 partial-result cache key (#35-6) and is blocked on the GUI retry path (#35-5) — defer

With #36-5a/#36-5b shipped, the only Rec 36 item left in the sequence is #36-6,
"`budget` cache-key extension (Rec 35)". Grounding it against the real code and
the Rec 35 plan shows it is **not an independent Rec 36 slice**: it is the cache
side of Rec 35 #35-6, and it cannot be built or tested until Rec 35 #35-5 lands.
Like #36-4 (addendum 8) it is deferred — but for a *harder* reason than #36-4's:
#36-4 waits on telemetry ("is it worth it"); #36-6 waits on a missing
**prerequisite feature** ("there is nothing yet to key on").

### What #36-6 actually is

[DECOMPILER_BUDGETS.md](../decompiler/DECOMPILER_BUDGETS.md) "Coordination with
Rec 36" states it directly: the cache "needs to store partial results keyed by
`(function, budget)` so a *Retry with 2x* run starts from the partial it has
cached. This is a small extension to the cache key shape." The same work is
tracked on the Rec 35 side as **#35-6 — "Cache partial results keyed by budget"
(not started)**. #36-6 and #35-6 are one item seen from two recs.

The `(function, budget)` key only earns its keep when the GUI can hold **two
results for the same function at once** — a budget-exhausted *partial* and the
larger-budget *retry* of it — so the retry resumes from the cached partial
instead of recomputing. That two-result situation is created entirely by
**#35-5 — "UI banner + retry path" (not started)**: the
"Decompilation partial — budget exhausted… [Retry with 2x budget]" affordance.

### Why it cannot ship before #35-5

The interactive decompile path carries no budget today, so the cache never sees
a budget to key on:

- `DecompileOptions` has **no** budget field (the budget is a console/IPC
  surface — `decompilebudget` / `decompilebudgetpass`, and the
  `DecompileBudget` request sub-table in `ghidra.app.decompiler.ipc`).
- The GUI decompile entry point,
  `Decompiler.decompile(program, function, debugFile, monitor)`, has **no**
  budget parameter. Every GUI decompile runs the default budget.

So for the GUI, `Function` alone is already a **complete and correct** cache key
— there is no second budget that could collide with a cached entry. And even if
a budget were later exposed as a `DecompileOptions` field, changing it routes
through `setOptions` → `clearCache` (a full flush, asserted by
`DecompilerCachingTest.testCacheIsClearedWhenOptionsChange`), which is correct on
its own. The `(function, budget)` *key* is needed only for the narrower
partial-keeps-and-retry-resumes optimisation #35-5/#35-6 introduce — not for
plain correctness under a changing budget.

Building `(function, budget)` keying now would therefore add a key dimension no
caller can vary and no test can exercise (there is no GUI path that produces a
budget-tagged partial), which is exactly the untestable-speculative-machinery
shape the test-before-push discipline rejects — the same reason the v1 IPC
command-loop flip was deferred in [DD-0005](0005-ipc-framing-v1.md) #33-2.6.

### Decision

Defer #36-6 and fold it into Rec 35's #35-5 → #35-6 line. When #35-5 lands the
GUI partial/retry affordance, #35-6/#36-6 becomes a real, testable slice:
key the cache on `(function, budget)`, store the budget-exhausted partial, and
let a retry-with-more-budget find it. The cache machinery #36-3a..#36-3b/#36-5
already built (selective invalidation keyed on `Function`, telemetry) is
forward-compatible: the key gains a `budget` component; the invalidation walks
(by address set, by datatype id) stay keyed on the function and are unaffected.

With #36-6 deferred to the Rec 35 line and #36-4 deferred behind telemetry
(addendum 8), the Rec 36 GUI-cache-invalidation sprint has **no remaining
independently-actionable item**: #36-3a/#36-3a-2/#36-3b and #36-5a/#36-5b are
shipped, and both deferrals are grounded prerequisites rather than open work.
This addendum is docs-only and carries no C++/manifest/RAII-audit gate.
