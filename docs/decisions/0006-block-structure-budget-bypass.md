---
number: 0006
title: block_structure budget bypass — goto-only coarse mode, not early-return
status: accepted
date: 2026-06-03
audit_rec: 35
---

# Decision 0006: block_structure degrades to unstructured under budget pressure

## Context

Rec 35 (`DECOMPILER_BUDGETS.md`) makes each analysis pass consult a
cooperative per-function budget at a yield point and, when the pass
has spent its budget, drop into a *coarser mode* that still produces a
valid, printable result. Four bypassable passes are already wired onto
the general bypass façade (`DecompileBudgetTracker::passShouldBypass`,
#35-4b):

| Pass | Coarse mode | Wired in |
|---|---|---|
| `data_flow` | stop the `oppool1` simplification fixpoint at a sweep boundary; partially-simplified IR is still valid | #35-3d |
| `type_inference` | stop propagating; leave partially-inferred types (`void *` where not yet resolved) | #35-4 (#245) |
| `value_analysis` | stop the value-set fixpoint at a partition boundary; load guards keep their coarser "any value" ranges | #35-4 (#249) |

For all three, "coarser mode" is a **plain early-return at the yield
point**: the pass stops doing more work and whatever it has produced
so far is a legal partial. `block_structure` is the last bypassable
pass (`DECOMPILER_BUDGETS.md` pass table) and is the one that **cannot**
take that shape — which is why #35-4 deferred it to this design step.

## Why block_structure is different

`block_structure` is `ActionBlockStructure::apply`
(`blockaction.cc:2172`), which builds a working copy of the
control-flow graph and runs the structuring algorithm:

```cpp
CollapseStructure collapse(graph);
collapse.collapseAll();
```

`CollapseStructure::collapseAll` (`blockaction.cc:1879`) must reduce the
graph **all the way down to a single root block** — that postcondition
is what the C-emitter (`PrintC`) depends on: it walks one root
`FlowBlock` tree to emit pseudocode. The driver is:

```cpp
isolated_count = collapseInternal((FlowBlock *)0);     // apply structuring rules
while (isolated_count < graph.getSize()) {             // not fully collapsed yet?
  FlowBlock *targetbl = selectGoto();                  // force one more edge to a goto
  isolated_count = collapseInternal(targetbl);
}
```

`collapseInternal` (`blockaction.cc:1770`) greedily applies the
**precise** structuring rules — `ruleBlockProperIf`, `ruleBlockIfElse`,
`ruleBlockWhileDo`, `ruleBlockDoWhile`, `ruleBlockInfLoop`,
`ruleBlockSwitch`, `ruleBlockIfNoExit`, `ruleCaseFallthru` — plus the
two cheap structure-preserving reductions `ruleBlockGoto` and
`ruleBlockCat`. When the precise rules stall before the graph is a
single root, `selectGoto` (`blockaction.cc:1262`) converts one more
edge into an `unstructured` goto and the loop re-collapses; once the
likely-goto list is exhausted, `clipExtraRoots` marks the remaining
inter-component edges as gotos. **goto-injection is the existing,
always-terminating fallback** that guarantees a single root.

The consequence for the budget: if `block_structure` simply *returned
early* at a yield point the way the other three passes do, it would
hand the printer a graph with **more than one root** — undefined for
`PrintC`, a crash or garbage, not a valid partial. The pass has no
"stop and keep what I have" state that is printable. It only has a
*fully collapsed* state.

## The decision

`block_structure`'s coarse mode is **"collapse with gotos instead of
structure"**, not "stop collapsing." Under budget pressure the pass
keeps running `collapseAll` to its single-root postcondition — so the
output is always printable — but stops spending iterations on the
precise pattern rules and instead drives the graph to its single root
through the goto-injection path that already exists. The result is the
same legal output the decompiler already emits for genuinely
unstructurable control flow: a function whose body is mostly
`goto`/`label`, accurate but ugly. That is the documented
"degrades to unstructured" coarse mode.

### Mechanism

1. **`CollapseStructure` learns the budget.** Today it is constructed
   with only the `BlockGraph` and has no path to `glb->budget`. Add an
   optional `DecompileBudgetTracker *` (default `nullptr`, set by
   `ActionBlockStructure::apply` from `data.getArch()->budget` when
   `engaged()`). When null — every non-budgeted decompile, which is the
   default — the algorithm is byte-for-byte what it is today.

2. **The yield point and iteration scale.** A `block_structure`
   iteration is **one block-scan sweep** of `collapseInternal` — one
   full turn of its inner `while (index < graph.getSize())` loop over
   every block, the unit `collapseInternal`'s own `do { … } while
   (change)` already iterates on. This is the same per-sweep scale the
   sibling passes use (`data_flow` counts changing rule-pool sweeps,
   `value_analysis` counts value-set iterations): it ticks on *any*
   multi-block function, so the truncation point is tunable and
   deterministically fixture-pinnable rather than only engaging on
   irreducible graphs. `tickIteration()` is called once at the end of
   each sweep against the `block_structure` pass; `enterPass` (in
   `collapseAll`, before the first `collapseInternal`) registers the
   pass under `blockstructure_iteration_limit` (new cap, own scale) and
   the count accumulates across every `collapseInternal` call in the
   function (the initial call plus each `selectGoto`-driven recollapse,
   and across the two structuring Actions — `ActionBlockStructure` and
   the `ActionRevertISC` re-structure path). A function-global
   wall-clock / pcode-op breach is observed by a `check()` alongside the
   tick, so `passShouldBypass` flips on global pressure too.

3. **Coarse mode = skip the precise rules.** Once
   `passShouldBypass("block_structure")` becomes true (this pass spent
   its own iteration budget, **or** a function-global cap — wall-clock,
   pcode-op — tripped), `collapseInternal` stops attempting the eight
   precise pattern rules (`ruleBlockProperIf`, `ruleBlockIfElse`,
   `ruleBlockWhileDo`, `ruleBlockDoWhile`, `ruleBlockInfLoop`,
   `ruleBlockSwitch`, and the second-stage `ruleBlockIfNoExit` /
   `ruleCaseFallthru`) and runs in **goto-only mode**: it keeps only
   `ruleBlockGoto` + `ruleBlockCat` (cheap, always-valid folds) and lets
   the outer `selectGoto` / `clipExtraRoots` path force every remaining
   decision edge to a goto. So the first N sweeps structure precisely
   (keeping whatever structure the budget bought) and the tail runs
   goto-only — the structure already built in earlier sweeps stays. The
   graph still collapses to a single root — `selectGoto` +
   `clipExtraRoots` is a total function over any connected graph — so
   the postcondition holds and the printer is safe. The only thing lost
   is the *structure* of the not-yet-collapsed remainder, which is
   exactly the coarse-mode contract.

   Termination in coarse mode does not depend on the precise rules: the
   goto-injection path is monotone (each `selectGoto` strictly reduces
   the number of non-goto decision edges, `clipExtraRoots` strictly
   reduces the number of roots), so goto-only collapse always reaches a
   single root in a bounded number of rounds. Skipping the precise rules
   only removes work; it cannot remove termination.

4. **Diagnostic, exactly once.** On the first round that bypasses, emit
   `warningHeader("Exceeded decompilation budget on pass block_structure:
   Some analysis is truncated")`, guarded by
   `budget.claimDiagnostic("block_structure")` so the once-per-function
   contract matches the other passes. (`block_structure` runs a bounded
   number of times per function, unlike the re-driven `value_analysis`
   solve, but the guard is free and keeps the four passes uniform.)

5. **Option surface.** `block_structure` has no positional slot in
   `decompilebudget <flow> <dataflow> <typeinfer>` (full at three) — it
   is set by name through the existing `decompilebudgetpass <pass>
   <cap>` option (`options.cc:1019`), which already routes
   `value_analysis`; add `block_structure` as a fifth recognised name
   mapping to the new `blockstructure_iteration_limit` cap. Default cap
   is large (1_000_000) so default decompilation is inert and
   byte-identical.

## Rejected alternatives

- **Early-return like the other three passes.** Rejected: leaves a
  multi-root graph, which `PrintC` cannot emit. This is the whole
  reason for a separate design step.

- **Time-box `collapseInternal` and emit a placeholder body.** Rejected:
  the decompiler already has a correct unstructured representation
  (all-goto). Inventing a second "gave up" rendering would duplicate the
  goto path and risk a non-printable tree; reusing the existing
  goto-injection fallback is strictly less code and reuses a path that
  is already exercised on real unstructurable functions.

- **Convert *all* remaining decision edges to gotos in one sweep on
  bypass** (a new `markAllAsGotos`), instead of letting `selectGoto`
  drive them one at a time. Rejected as the *first* implementation: it
  is a genuinely new code path (a new method, new ordering questions
  about loop bodies vs. inter-component edges) that the existing
  `selectGoto`/`clipExtraRoots` already solves correctly. The
  skip-the-precise-rules approach reaches the same all-goto endpoint
  through code that is already there. If profiling later shows the
  one-at-a-time `selectGoto` loop is itself the runaway cost on some
  pathological graph, a batch `markAllAsGotos` is a clean follow-up — it
  is *not* needed to make the pass bounded, because goto-only collapse
  is already monotone and bounded.

- **Bound by a wall-clock check only, no iteration cap.** Rejected for
  parity: every other pass has a deterministic per-pass iteration cap
  that a datatest fixture can pin (the wall-clock path is real but not
  unit-testable without a fake clock at the datatest layer). The
  iteration cap gives `block_structure` the same deterministic,
  fixture-pinnable truncation the other three have; the function-global
  wall-clock path still triggers bypass through `passShouldBypass`.

## Validation

Per the project's "test everything locally before push" bar, the
implementation PR ships a deterministic datatest fixture
`datatests/decompbudget_blockstructure.xml`: a function whose
structuring takes several block-scan sweeps, capped via
`decompilebudgetpass block_structure <N>` below that natural sweep
count, so the tail of structuring flips to the all-goto rendering and
the partial-result header naming `block_structure` appears exactly
once. A unit test in
`testbudget.cc` covers the per-pass tracker mechanics
(`block_structure` entering its own pass, `passShouldBypass` on its own
cap vs. another pass's cap) on the same pattern as the existing
per-pass tests.

Both `decomp_test_dbg` and `ghidra_dbg` build clean under
`scripts/local-precheck.sh --full` (the structuring change is in
`blockaction.cc`/`.hh`, which link into both binaries).

## Sequencing

This DD is the design half of the `#35-4 (block_structure)` row in
`DECOMPILER_BUDGETS.md`. The implementation lands as a single follow-up
PR (the change is one yield point + one coarse-mode branch + the option
name + the fixture — small enough not to split further). After it
merges, the bypassable-pass table in `DECOMPILER_BUDGETS.md` is
complete and the remaining Rec 35 work is `#35-5` (UI banner + retry
path), `#35-6` (cache partial results by budget), `#35-7` (tune
defaults from telemetry).

## References

- `Ghidra/Features/Decompiler/src/decompile/cpp/blockaction.cc:1770`
  (`collapseInternal`), `:1879` (`collapseAll`), `:1262`
  (`selectGoto`), `:1110` (`clipExtraRoots`), `:2172`
  (`ActionBlockStructure::apply`).
- `Ghidra/Features/Decompiler/src/decompile/cpp/budget.hh` —
  `passShouldBypass`, `tickIteration`, `claimDiagnostic`,
  `DecompileBudgetCaps`.
- `Ghidra/Features/Decompiler/src/decompile/cpp/options.cc:1019`
  (`OptionDecompileBudgetPass::apply`).
- `docs/decompiler/DECOMPILER_BUDGETS.md` — pass table and #35-4
  sequencing.
- [DD-0005](0005-ipc-framing-v1.md) — the design-decision-first
  pattern this DD follows.
</content>
</invoke>
