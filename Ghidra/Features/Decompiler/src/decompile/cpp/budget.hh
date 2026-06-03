/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Rec 35 (#35-3): the cooperative per-function analysis budget tracker
// (DECOMPILER_BUDGETS.md). The analysis loop consults a tracker at each yield
// point (pass entry/exit, every N iterations within a pass). The tracker never
// interrupts: when a cap is reached it records which budget class tripped and on
// which pass, and the caller is responsible for checkpointing and returning a
// partial result. Async cancellation in C++ analysis is undefined behaviour we
// deliberately do not take on (see the design doc's Threading section).
//
// This header is dependency-free (std-only) and inert as of #35-3: no production
// pass consults it yet. Wiring the yield-point checks into flow_analysis and
// data_flow is a separate, behaviour-changing follow-up. Caps are taken as plain
// values rather than the FlatBuffers DecompileBudgetV1 so the mechanism stays
// decoupled from the IPC schema; the wiring step translates a decoded budget
// into these caps.
#ifndef __DECOMPILE_BUDGET_HH__
#define __DECOMPILE_BUDGET_HH__

#include <chrono>
#include <cstdint>
#include <functional>
#include <map>
#include <string>

namespace ghidra {

/// \brief Which budget class, if any, has been exhausted.
///
/// Ordered by severity: a hard wall-clock breach outranks a soft one, which
/// outranks the count-based soft caps. DecompileBudgetTracker::check() reports
/// the most severe class currently exceeded.
enum class BudgetExhaustion {
  none = 0,          ///< Still within every cap.
  wallClock,         ///< Soft wall-clock cap reached; checkpoint and return.
  wallClockHard,     ///< Hard wall-clock cap reached; checkpoint and return now.
  pcodeOpLimit,      ///< Soft pcode-op count cap reached.
  iterationLimit     ///< Soft per-pass fixed-point iteration cap reached.
};

/// \brief Per-function analysis budget caps (Rec 35 / DECOMPILER_BUDGETS.md).
///
/// Defaults mirror the v1 request schema (decompile.fbs). Caps are literal: a
/// zero cap is treated as "reached immediately", not "disabled" — callers that
/// want a pass left unbounded leave the cap at its (large, non-zero) default.
struct DecompileBudgetCaps {
  uint32_t wall_clock_ms = 30000;             ///< Soft wall-clock cap (30s).
  uint32_t wall_clock_hard_ms = 60000;        ///< Hard wall-clock cap (60s).
  uint32_t rss_max_mb = 4096;                 ///< Hard resident-set cap (4 GiB).
  uint32_t pcode_op_limit = 1000000;          ///< Soft pcode-op count cap.
  uint32_t iteration_limit_per_pass = 100;    ///< Soft per-pass fixed-point cap.
  uint32_t dataflow_iteration_limit = 100000; ///< Soft data_flow sweep cap (own scale).
  uint32_t typeinfer_iteration_limit = 100;   ///< Soft type_inference propagation-pass cap (own scale).
  uint32_t valueanalysis_iteration_limit = 1000000; ///< Soft value_analysis value-set-iteration cap (own scale, cumulative across the function's solves; large default leaves it effectively unbounded).
};

/// \brief Cooperative, single-thread budget tracker for the analysis loop.
///
/// Construct once per function with the caps for that call, then consult it at
/// each yield point. Wall-clock is measured against a monotonic clock captured
/// at construction (or reset()); the clock source is injectable so tests stay
/// fast and deterministic. The exhaustion class and the pass on which it was
/// first observed are sticky until reset(), so the diagnostic always names the
/// pass that originally ran out of budget.
///
/// Iteration counts are tracked \e per pass: each named pass keeps its own
/// running count and its own iteration cap, registered on first enterPass and
/// accumulated across re-entries within the function (the data_flow rule-pool is
/// re-performed many times by the mainloop). This lets several passes be
/// budgeted independently in one function — one pass reaching its own iteration
/// cap (passIterationExhausted()) does not stop another from making progress —
/// which is what the remaining-pass wiring (DECOMPILER_BUDGETS.md #35-4) needs.
/// The function-global wall-clock and pcode-op caps remain shared across passes.
class DecompileBudgetTracker {
public:
  using ClockMs = std::function<uint64_t()>;

  /// \brief Read the process monotonic clock in milliseconds.
  static uint64_t steadyNowMs(void) {
    return (uint64_t)std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
  }

  /// \param caps   the budget for this function
  /// \param clock  millisecond clock source (defaults to the steady clock)
  explicit DecompileBudgetTracker(const DecompileBudgetCaps &caps = DecompileBudgetCaps(),
                                  ClockMs clock = steadyNowMs)
      : caps(caps), clock(std::move(clock)) {
    reset();
  }

  /// \brief Restart the wall clock and clear all per-pass counters and the diagnostic.
  void reset(void) {
    startMs = clock();
    pcodeOps = 0;
    passes.clear();
    exhaust = BudgetExhaustion::none;
    exhaustPass.clear();
    currentPass.clear();
  }

  /// \brief Mark entry to a named pass, registering it under the default cap.
  ///
  /// The pass is bounded by the default per-pass cap (iteration_limit_per_pass).
  /// Does not clear an already-recorded exhaustion: a budget exhausted in an
  /// earlier pass keeps its diagnostic across the pass boundary.
  void enterPass(const std::string &name) {
    enterPass(name, caps.iteration_limit_per_pass);
  }

  /// \brief Mark entry to a named pass with an explicit per-pass iteration cap.
  ///
  /// Different passes count iterations on different scales (flow_analysis counts
  /// processed instructions; the data_flow rule-pool counts simplification
  /// sweeps), so each pass supplies the cap it is bounded by from the caps
  /// rather than sharing one number.
  ///
  /// Registration is \e once per pass per function: the first entry creates the
  /// pass's counter at zero with this cap; later entries to the same pass only
  /// make it current again and keep the accumulated count (the data_flow pool is
  /// re-performed many times within one function and must accumulate across those
  /// re-entries, not reset each visit). reset() — the per-function boundary —
  /// clears every pass. Does not clear an already-recorded exhaustion.
  void enterPass(const std::string &name, uint32_t iterLimit) {
    currentPass = name;
    passes.emplace(name, PassBudgetState{0, iterLimit, false, false});
  }

  /// \brief Count one iteration of the current pass's fixed-point loop.
  /// \return \b true once the current pass's own iteration cap has been reached,
  ///         so the loop can break; marks the pass iteration-exhausted and
  ///         records the (sticky, function-global) diagnostic on the first hit.
  bool tickIteration(void) {
    PassBudgetState &st = passState(currentPass);
    st.iterations += 1;
    if (st.iterations >= st.iterationLimit) {
      st.iterExhausted = true;
      record(BudgetExhaustion::iterationLimit);
      return true;
    }
    return false;
  }

  /// \brief Accumulate pcode-ops produced so far against the soft pcode cap.
  void addPcodeOps(uint64_t count) { pcodeOps += count; }

  /// \brief Replace the caps this tracker enforces (program-level config).
  ///
  /// Caps are configuration, not per-function state: reset() preserves them.
  void setCaps(const DecompileBudgetCaps &c) { caps = c; }

  /// \brief Arm the tracker so yield points consult it; until engaged, every
  ///        yield point is a no-op and the analysis loop is unaffected.
  ///
  /// Engagement is configuration, not per-function state: reset() preserves it.
  /// Nothing in the production analysis loop engages a tracker by default, so a
  /// default-constructed Architecture budget is inert.
  void engage(void) { engagedFlag = true; }

  /// \brief Disarm the tracker; subsequent yield points are no-ops again.
  void disengage(void) { engagedFlag = false; }

  /// \brief \b true once engage()d; yield points should skip the tracker when false.
  bool engaged(void) const { return engagedFlag; }

  /// \brief Yield-point query: the most severe budget class currently exceeded.
  ///
  /// Evaluates hard wall-clock, then soft wall-clock, then the pcode-op cap,
  /// then the per-pass iteration cap. The first class found exceeded is recorded
  /// (if no exhaustion was recorded yet) and returned; none means keep going.
  BudgetExhaustion check(void) {
    uint64_t elapsed = clock() - startMs;
    if (elapsed >= caps.wall_clock_hard_ms) {
      return record(BudgetExhaustion::wallClockHard);
    }
    if (elapsed >= caps.wall_clock_ms) {
      return record(BudgetExhaustion::wallClock);
    }
    if (pcodeOps >= caps.pcode_op_limit) {
      return record(BudgetExhaustion::pcodeOpLimit);
    }
    auto it = passes.find(currentPass);
    if (it != passes.end() && it->second.iterations >= it->second.iterationLimit) {
      return record(BudgetExhaustion::iterationLimit);
    }
    return BudgetExhaustion::none;
  }

  /// \brief The sticky exhaustion class first observed since the last reset().
  BudgetExhaustion exhaustion(void) const { return exhaust; }

  /// \brief \b true once any cap has been reached since the last reset().
  bool exhausted(void) const { return exhaust != BudgetExhaustion::none; }

  /// \brief Name of the pass on which exhaustion was first observed (the
  ///        partial-result diagnostic); empty while still within budget.
  const std::string &exhaustedPass(void) const { return exhaustPass; }

  /// \brief Name of the pass currently executing (set by enterPass); empty
  ///        before any pass is entered. Lets a re-entrant pass detect that it
  ///        is already active and keep accumulating its iteration count rather
  ///        than resetting it on every visit.
  const std::string &currentPassName(void) const { return currentPass; }

  /// \brief \b true once the named pass has reached its \e own iteration cap.
  ///
  /// Per-pass and independent of every other pass and of the function-global
  /// caps: a pass that spent its iteration budget reports true here so its
  /// fixpoint can be bypassed on later visits, while other passes keep running.
  /// A pass never entered (or still within its cap) reports false.
  bool passIterationExhausted(const std::string &name) const {
    auto it = passes.find(name);
    return it != passes.end() && it->second.iterExhausted;
  }

  /// \brief Iterations counted against the named pass since reset(); 0 if the
  ///        pass has not been entered.
  uint32_t passIterations(const std::string &name) const {
    auto it = passes.find(name);
    return (it == passes.end()) ? 0 : it->second.iterations;
  }

  /// \brief Façade for the coarse/bypass-mode decision a bypassable pass makes
  ///        on each visit. \b true once the named pass should stop precise work
  ///        and run in its coarser mode.
  ///
  /// Bypass is triggered by either of two budget signals:
  ///   - the pass has spent its \e own per-pass iteration budget
  ///     (passIterationExhausted) — independent per pass, and
  ///   - the function as a whole is out of budget: a function-global cap
  ///     (wall-clock soft or hard, or pcode-op) has tripped, so precise work
  ///     anywhere is no longer affordable.
  ///
  /// Crucially it does \e not bypass on another pass's iteration cap: an
  /// \ref BudgetExhaustion::iterationLimit recorded by a \e different pass leaves
  /// this one free to keep running, preserving the per-pass independence the
  /// #35-4a foundation provides. A pass within all budgets, or never entered,
  /// reports false.
  ///
  /// This generalises the bypass condition #35-3d hand-rolled for the data_flow
  /// pool so the remaining bypassable passes (type_inference, value_analysis,
  /// block_structure) each degrade on the same rule rather than re-deriving it;
  /// the non-bypassable passes (flow_analysis, output_emission) do not consult
  /// it — they truncate in place. Like check(), it reads the sticky exhaustion
  /// state, so a yield-point check() (or tickIteration) must have run first.
  bool passShouldBypass(const std::string &name) const {
    if (passIterationExhausted(name)) {
      return true;  // this pass spent its own iteration budget
    }
    switch (exhaust) {  // or the function is globally out of budget
      case BudgetExhaustion::wallClock:
      case BudgetExhaustion::wallClockHard:
      case BudgetExhaustion::pcodeOpLimit:
        return true;
      default:  // none, or another pass's iterationLimit (independence preserved)
        return false;
    }
  }

  /// \brief Claim the right to emit the named pass's partial-result header,
  ///        exactly once per function.
  ///
  /// A bypassable pass whose apply()/yield point is revisited many times within a
  /// function (the value_analysis value-set solve, for example, is re-driven by
  /// every heritage pass that surfaces new load guards) needs to print its
  /// "budget exhausted" warning header only on the first truncation. \b true the
  /// first time it is called for \b name since reset(); \b false on every later
  /// call. Lets a pass guard its diagnostic in the budget mechanism itself rather
  /// than borrowing an unrelated Funcdata flag.
  bool claimDiagnostic(const std::string &name) {
    PassBudgetState &st = passState(name);
    if (st.diagnosticEmitted) return false;
    st.diagnosticEmitted = true;
    return true;
  }

  /// \brief The caps this tracker enforces.
  const DecompileBudgetCaps &budget(void) const { return caps; }

private:
  /// \brief Per-pass fixed-point iteration state (one entry per named pass).
  struct PassBudgetState {
    uint32_t iterations;      ///< Iterations counted against this pass since reset().
    uint32_t iterationLimit;  ///< This pass's own iteration cap.
    bool iterExhausted;       ///< \b true once this pass reached its own cap.
    bool diagnosticEmitted;   ///< \b true once this pass's partial-result header was printed.
  };

  /// \brief Record \b cls as the exhaustion class if none was recorded yet,
  ///        pinning the diagnostic to the current pass; return \b cls.
  BudgetExhaustion record(BudgetExhaustion cls) {
    if (exhaust == BudgetExhaustion::none) {
      exhaust = cls;
      exhaustPass = currentPass;
    }
    return cls;
  }

  /// \brief The current pass's state, lazily registered under the default cap if
  ///        a tick arrives before any enterPass (mirrors the historical default).
  PassBudgetState &passState(const std::string &name) {
    auto it = passes.find(name);
    if (it == passes.end())
      it = passes.emplace(name, PassBudgetState{0, caps.iteration_limit_per_pass, false, false}).first;
    return it->second;
  }

  DecompileBudgetCaps caps;     ///< The budget for this function.
  ClockMs clock;                ///< Millisecond clock source.
  uint64_t startMs = 0;         ///< Clock value captured at construction/reset.
  uint64_t pcodeOps = 0;        ///< Pcode-ops accumulated since reset.
  std::map<std::string, PassBudgetState> passes;  ///< Per-pass iteration counters, cleared by reset().
  BudgetExhaustion exhaust = BudgetExhaustion::none;  ///< Sticky exhaustion class.
  std::string exhaustPass;      ///< Pass that first ran out of budget.
  std::string currentPass;      ///< Pass currently executing.
  bool engagedFlag = false;     ///< Config: are yield points to consult this tracker? (reset() preserves)
};

} // End namespace ghidra

#endif
