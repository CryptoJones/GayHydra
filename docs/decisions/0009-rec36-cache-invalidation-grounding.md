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
- #36-3b — cross-function dependency invalidation — **design grounded** (see addendum 3); splits into #36-3b-1 (callee signature/modifier → callers, no bitmap) + #36-3b-2 (shared-datatype → referencing functions, recorded datatype-ID set); impl pending
- #36-4 / #36-5 / #36-6 — sequenced above

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
