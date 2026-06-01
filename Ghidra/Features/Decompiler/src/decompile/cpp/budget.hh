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
};

/// \brief Cooperative, single-thread budget tracker for the analysis loop.
///
/// Construct once per function with the caps for that call, then consult it at
/// each yield point. Wall-clock is measured against a monotonic clock captured
/// at construction (or reset()); the clock source is injectable so tests stay
/// fast and deterministic. The exhaustion class and the pass on which it was
/// first observed are sticky until reset(), so the diagnostic always names the
/// pass that originally ran out of budget.
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

  /// \brief Restart the wall clock and clear all counters and the diagnostic.
  void reset(void) {
    startMs = clock();
    pcodeOps = 0;
    passIterations = 0;
    exhaust = BudgetExhaustion::none;
    exhaustPass.clear();
    currentPass.clear();
  }

  /// \brief Mark entry to a named pass, resetting the per-pass iteration count.
  ///
  /// Does not clear an already-recorded exhaustion: a budget exhausted in an
  /// earlier pass keeps its diagnostic across the pass boundary.
  void enterPass(const std::string &name) {
    currentPass = name;
    passIterations = 0;
  }

  /// \brief Count one iteration of the current pass's fixed-point loop.
  /// \return \b true once the per-pass iteration cap has been reached, so the
  ///         loop can break; records iterationLimit exhaustion on the first hit.
  bool tickIteration(void) {
    passIterations += 1;
    if (passIterations >= caps.iteration_limit_per_pass) {
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
    if (passIterations >= caps.iteration_limit_per_pass) {
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

  /// \brief The caps this tracker enforces.
  const DecompileBudgetCaps &budget(void) const { return caps; }

private:
  /// \brief Record \b cls as the exhaustion class if none was recorded yet,
  ///        pinning the diagnostic to the current pass; return \b cls.
  BudgetExhaustion record(BudgetExhaustion cls) {
    if (exhaust == BudgetExhaustion::none) {
      exhaust = cls;
      exhaustPass = currentPass;
    }
    return cls;
  }

  DecompileBudgetCaps caps;     ///< The budget for this function.
  ClockMs clock;                ///< Millisecond clock source.
  uint64_t startMs = 0;         ///< Clock value captured at construction/reset.
  uint64_t pcodeOps = 0;        ///< Pcode-ops accumulated since reset.
  uint32_t passIterations = 0;  ///< Iterations of the current pass since enterPass.
  BudgetExhaustion exhaust = BudgetExhaustion::none;  ///< Sticky exhaustion class.
  std::string exhaustPass;      ///< Pass that first ran out of budget.
  std::string currentPass;      ///< Pass currently executing.
  bool engagedFlag = false;     ///< Config: are yield points to consult this tracker? (reset() preserves)
};

} // End namespace ghidra

#endif
