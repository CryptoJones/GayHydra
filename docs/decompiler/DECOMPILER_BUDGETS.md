# Bounded Decompilation: Wall-Clock and Memory Budgets

*Addresses Rec 35 of the 2026-05-21 principal-architect audit.*

## What's broken today

The C++ decompiler runs analysis passes until they finish.
"Finish" means: the pass terminates of its own accord, the
process hits OOM and exits, or the parent kills the worker after
a long timeout. There is no per-function budget.

Concrete user-visible effects:

- **Issue #5730 (huge-function UX)** — a function with tens of
  thousands of basic blocks chews CPU for minutes; the GUI is
  unresponsive in the meantime; the user can't cancel.
- **Issue #8429 (decompiler perf)** — analysis passes that
  scale super-linearly with function size run away on
  pathological input.
- **PR #9179 (bounded parallel decompiler)** — community
  contribution that adds bounded parallelism; sits as a partial
  solution because the underlying per-function bound is missing.

The pattern: a single function with adversarial structure (or
just legitimately huge structure) is *unbounded* in time and
space. The decompiler is the wrong abstraction for "give me
whatever you can in 10 seconds"; today it's "give me everything,
no matter how long it takes."

## The decision

Add **per-function budgets**: wall-clock and resident-set-size
caps that the analysis loop checks at every yield point. When a
budget is exhausted, the analysis returns a **partial result**:
whatever it had produced so far, plus a diagnostic flag saying
"budget exhausted on pass X."

Partial results are first-class. The UI renders them with a clear
indicator that the result is partial; the user can then choose to
re-run with a larger budget or accept the partial. The choice is
the user's; the *hang* goes away.

## Budget shape

A `DecompileBudget` struct carries:

| Field | Default | What it means |
|---|---|---|
| `wall_clock_ms` | 30000 (30s) | Soft wall-clock cap. The analysis loop checks at every yield point and returns partial when exceeded. |
| `wall_clock_hard_ms` | 60000 (60s) | Hard wall-clock cap. The worker process kills the analysis (and returns partial-from-checkpoint). |
| `rss_max_mb` | 4096 | Hard cap on resident set size. Checked at major allocations; exceeded -> partial-from-checkpoint. |
| `pcode_op_limit` | 1_000_000 | Soft cap on pcode-op count. Above this, analysis passes opt into reduced-precision modes. |
| `iteration_limit_per_pass` | 100 | Soft cap on iterations within a single fixed-point pass. |

All five are independently configurable per call. Defaults are
chosen against the audit's perf numbers on real binaries:

- 30s wall-clock is double the median observed time-to-decompile
  for the upstream test corpus.
- 4 GB RSS is sized to handle big firmware images without OOM
  on a developer's 16 GB laptop.

## Checkpoint and partial result

The analysis loop is organised into named **passes**. Each pass
has a checkpoint: a snapshot of the in-flight HighFunction state
at pass entry. When a budget is exhausted mid-pass, we return
the checkpoint (the result up to the last completed pass) plus
the diagnostic.

This is the same shape as long-running query engines (BigQuery,
ClickHouse, Spark) that bound execution and return partial.
It's a known-good abstraction.

### Pass list (and which budget-class is critical)

| Pass | Budget concern | Bypassable? |
|---|---|---|
| `flow_analysis` | wall-clock, iteration | No — without flow, no IR. |
| `data_flow` | wall-clock, iteration | Yes — coarser mode skips alias analysis. |
| `type_inference` | wall-clock | Yes — coarser mode returns `void *` for everything. |
| `value_analysis` | wall-clock, iteration | Yes — degrades to "any value." |
| `block_structure` | wall-clock | Yes — degrades to unstructured. |
| `output_emission` | minimal | No — small pass. |

Passes marked "bypassable" can be skipped under budget pressure;
the output is still a function, just with less inferred. Passes
not bypassable mean the worker returns the equivalent of "couldn't
even start" — but at least it returns it within the budget.

## API surface

Java side (`DecompInterface.decompileFunction(...)`):

```java
DecompileResult result = decompiler.decompileFunction(
    function,
    DecompileBudget.builder()
        .wallClockMs(10_000)        // give up early
        .pcodeOpLimit(100_000)      // tighter than default
        .allowPartial(true)
        .build()
);
if (result.isPartial()) {
    log.warn("decompile of {} budget-exhausted at pass {}",
             function, result.budgetExhaustedPass());
}
```

C++ side: the budget rides inside the FlatBuffers
`DecompileFunctionRequest` (Rec 34). The C++ analysis loop reads
it once at request entry and threads it through.

## What the UI shows

When the decompiler returns a partial result:

- A banner: "Decompilation partial — budget exhausted on
  `data_flow`. [Retry with 2x budget] [Accept partial]"
- The displayed pseudocode is annotated where it's incomplete
  (e.g., `// budget exhausted — types not inferred below`).
- The user's session keeps the partial; they can come back to
  it later with a bigger budget.

This is closely related to [#1871's cache flush problem](CACHE_FLUSH_1871.md)
(Rec 36): the partial result needs to stay cached so the "Retry
with 2x budget" path is fast.

## Threading

The budget check has to run on the analysis thread, not from a
watchdog thread, to be safe: an interrupt at an arbitrary point
in C++ analysis is undefined behaviour. The pattern:

- Analysis loop checks budget at each yield point (pass entry,
  pass exit, every N iterations within a pass).
- Wall-clock soft cap: the yield-point check sees the deadline
  passed; the loop checkpoints and returns.
- Wall-clock hard cap: enforced by the *parent* (Java side) via
  a timeout on the IPC read. After the hard cap, the parent
  closes the IPC stream, the worker observes the close, and
  exits cleanly. The parent returns the last partial it
  received.
- RSS cap: checked on each major allocation site in the analysis
  loop; exceeding triggers the same checkpoint-and-return.

## Coordination with PR #9179 (bounded parallel)

The upstream community PR #9179 adds a *parallel* decompile
driver — bounded by the number of cores. This rec is
*orthogonal*: PR #9179 bounds the across-functions concurrency;
this rec bounds the per-function cost. The two compose. PR #9179
can land on top of (or under) this rec without conflict; the
budget per worker is per-function regardless of how many
workers exist.

## Coordination with Rec 33 + 34 (IPC)

The budget rides in the FlatBuffers schema (Rec 34). Until Rec 34
lands, the budget rides in a new XML field on the existing
protocol (already structurally additive).

## Coordination with Rec 36 (cache flush)

The decompiler cache (Rec 36) needs to store partial results
keyed by `(function, budget)` so a "Retry with 2x" run starts
from the partial it has cached. This is a small extension to
the cache key shape.

## Sequencing

| PR | Scope | Status |
|---|---|---|
| #35-1 | This design doc | done |
| #35-2 | Add `DecompileBudget` to the request schema (XML for now, FlatBuffers post-Rec-34) | done |
| #35-3a | Land the cooperative `DecompileBudgetTracker` (inert, std-only, unit-tested) | done |
| #35-3b | Wire the tracker into the `flow_analysis` yield point (per-instruction iteration cap → artificial-HALT truncation + partial-result diagnostic); `decompilebudget <N>` console option | done |
| #35-3c | Name the exhausted pass in the partial-result header (`…on pass flow_analysis…`) | done |
| #35-3d | `data_flow` yield point — decoupled per-sweep cap on the `oppool1` simplification pool, bypass-on-own-exhaustion; see note below | done |
| #35-4 | Add the remaining passes; pass-bypass-mode | not started |
| #35-5 | UI banner + retry path | not started |
| #35-6 | Cache partial results keyed by budget | not started |
| #35-7 | Tune defaults from production telemetry (after one release) | not started |

### Implementation note: the `data_flow` yield point

`flow_analysis` was wired first because its loop
(`FlowInfo::processInstruction`) is a clean, pass-specific,
deterministically-testable yield point: one processed instruction is one
iteration, and truncation is the existing artificial-HALT path. The
`data_flow` yield point had none of those properties, which is why it was
sequenced after `flow_analysis` rather than alongside it. `#35-3d` resolves
each obstacle the original analysis raised, as follows.

- **No pass-specific loop → tag the pool, not the loop.** The data_flow
  fixpoint is the *generic* `Action::perform` loop (`action.cc`,
  `do { apply() } while(lcount<count && rule_repeatapply)`), shared by every
  pass. Rather than special-casing data_flow inside that loop, `#35-3d` adds an
  `Action::budgetPass` string tag and sets it on exactly one action — the
  `oppool1` simplification pool (`coreaction.cc`). `Action::perform` only ticks
  when the running action carries a tag *and* the budget is engaged; every
  untagged action runs a single bool test and is otherwise untouched. The tag
  is propagated through `ActionGroup`/`ActionRestartGroup`/`ActionPool::clone`,
  because each `Architecture` builds its action tree by *cloning* the universal
  template (`ActionDatabase::deriveAction`), so a tag that did not survive
  `clone()` would never reach the runtime pool.
- **The restart count is degenerate → count changing sweeps instead.**
  `ActionRestartGroup`'s `maxrestarts = 1` is useless as a metric, so the cap
  is not restart-based. The pool is performed many times by the outer mainloop;
  `#35-3d` counts each *changing* rule-pool sweep (`lcount<count`; a no-change
  sweep is natural convergence, not budget pressure) and **accumulates that
  count across the whole function** by entering the `data_flow` pass exactly
  once (guarded on `currentPassName() != budgetPass`) instead of resetting the
  counter on every re-perform. Without that, the cap would be a meaningless
  per-perform limit.
- **Truncation ≠ HALT here → clean bypass-mode.** "Stop data_flow early" is the
  bypass/coarser-mode semantics: on reaching the cap the pool stops at a sweep
  boundary, records the partial-result diagnostic, and on every later visit
  returns `0` immediately (`exhausted() && exhaustedPass() == budgetPass`), so
  total data_flow work is genuinely bounded even though the mainloop keeps
  re-driving the pool. The partially-simplified IR remains valid and printable
  (constant-folding is simply left incomplete). A budget exhausted by a
  *different* pass (e.g. `flow_analysis`) does **not** bypass data_flow, so the
  shipped `#35-3b` flow-truncation path is preserved exactly.
- **Needs a deterministic harness → decouple the caps.** The flow and data_flow
  caps are independent: `decompilebudget <flowN> [<dataflowN>]`, with
  `dataflow_iteration_limit` defaulting to 100000 (effectively unbounded). That
  lets a fixture leave flow uncapped (`flowN = 1000`) while truncating data_flow
  on its own scale. `datatests/decompbudget_dataflow.xml` pins it: `condconst1`
  converges naturally in 8 data_flow sweeps, and `decompilebudget 1000 2`
  truncates it at 2, emitting a stable, single-line partial-result header.

The remaining passes and a general pass-bypass façade are still **#35-4**;
`#35-3d` lands the data_flow pass on the same mechanism so that work can follow
the pattern rather than invent it.

## What this does *not* do

- **Does not promise a specific function will succeed within a
  budget.** A 50k-block function may not finish flow analysis in
  any human-sized budget; the answer is partial, not timeout.
- **Does not cancel running passes asynchronously.** All cancel
  points are cooperative. Async cancel in C++ is undefined
  behaviour we choose not to take on.
- **Does not replace the GUI's existing "Cancel" button.**
  Cancel is still useful for "I changed my mind"; the budget
  is "the *system* changed its mind."

## Test coverage

Each pass that has a budget check gets:

- A unit test asserting checkpoint-and-return on an artificial
  pathological input.
- An integration test on a real big function asserting the
  partial-result diagnostic surfaces.

Tests live under `unittests/budget/` and ride the
[Rec 13 / Rec 15](../security/OSS_FUZZ.md) ASan + UBSan path.
