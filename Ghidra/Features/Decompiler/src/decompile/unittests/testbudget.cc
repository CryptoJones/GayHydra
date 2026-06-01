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
// Rec 35 (#35-3): unit tests for the cooperative analysis budget tracker
// (budget.hh). A fake millisecond clock is injected so wall-clock behaviour is
// exercised deterministically without sleeping. These pin the yield-point
// contract the flow_analysis/data_flow wiring will depend on: within-budget is
// silent, each cap trips its own exhaustion class, the per-pass iteration count
// resets at a pass boundary, and the diagnostic pass is sticky once recorded.
#include "test.hh"
#include "budget.hh"

namespace ghidra {

// A freshly-constructed tracker at t=0 with no activity is within every cap.
TEST(budget_within_default_caps) {
  uint64_t fake = 0;
  DecompileBudgetTracker t(DecompileBudgetCaps(), [&fake]() { return fake; });
  ASSERT_EQUALS((int)t.check(), (int)BudgetExhaustion::none);
  ASSERT(!t.exhausted());
  ASSERT_EQUALS(t.exhaustedPass(), std::string(""));
}

// The soft wall-clock cap trips exactly at the deadline, names the running pass,
// and stays one millisecond short of tripping just before it.
TEST(budget_soft_wall_clock) {
  uint64_t fake = 0;
  DecompileBudgetCaps caps;
  caps.wall_clock_ms = 10000;
  caps.wall_clock_hard_ms = 60000;
  DecompileBudgetTracker t(caps, [&fake]() { return fake; });
  t.enterPass("data_flow");

  fake = 9999;
  ASSERT_EQUALS((int)t.check(), (int)BudgetExhaustion::none);

  fake = 10000;
  ASSERT_EQUALS((int)t.check(), (int)BudgetExhaustion::wallClock);
  ASSERT(t.exhausted());
  ASSERT_EQUALS(t.exhaustedPass(), std::string("data_flow"));
}

// At or past the hard cap, the hard class is reported in preference to the soft
// one even though the soft deadline is also exceeded.
TEST(budget_hard_wall_clock_outranks_soft) {
  uint64_t fake = 0;
  DecompileBudgetTracker t(DecompileBudgetCaps(), [&fake]() { return fake; });  // 30000/60000 defaults

  fake = 60000;  // >= soft 30000 and >= hard 60000
  ASSERT_EQUALS((int)t.check(), (int)BudgetExhaustion::wallClockHard);
}

// The soft pcode-op cap trips once the accumulated count reaches the limit.
TEST(budget_pcode_op_limit) {
  uint64_t fake = 0;
  DecompileBudgetCaps caps;
  caps.pcode_op_limit = 1000;
  DecompileBudgetTracker t(caps, [&fake]() { return fake; });
  t.enterPass("data_flow");

  t.addPcodeOps(999);
  ASSERT_EQUALS((int)t.check(), (int)BudgetExhaustion::none);

  t.addPcodeOps(1);
  ASSERT_EQUALS((int)t.check(), (int)BudgetExhaustion::pcodeOpLimit);
  ASSERT_EQUALS(t.exhaustedPass(), std::string("data_flow"));
}

// tickIteration returns false until the per-pass cap is reached, then true, and
// records the iteration class against the running pass.
TEST(budget_iteration_limit) {
  uint64_t fake = 0;
  DecompileBudgetCaps caps;
  caps.iteration_limit_per_pass = 3;
  DecompileBudgetTracker t(caps, [&fake]() { return fake; });
  t.enterPass("flow_analysis");

  ASSERT(!t.tickIteration());  // 1
  ASSERT(!t.tickIteration());  // 2
  ASSERT(t.tickIteration());   // 3 -> cap reached
  ASSERT_EQUALS((int)t.exhaustion(), (int)BudgetExhaustion::iterationLimit);
  ASSERT_EQUALS(t.exhaustedPass(), std::string("flow_analysis"));
}

// The per-pass iteration count resets at a pass boundary: ticks accumulated in
// one pass do not carry into the next, so neither pass exhausts on its own.
TEST(budget_iteration_resets_per_pass) {
  uint64_t fake = 0;
  DecompileBudgetCaps caps;
  caps.iteration_limit_per_pass = 5;
  DecompileBudgetTracker t(caps, [&fake]() { return fake; });

  t.enterPass("flow_analysis");
  ASSERT(!t.tickIteration());
  ASSERT(!t.tickIteration());
  ASSERT(!t.tickIteration());

  t.enterPass("data_flow");
  ASSERT(!t.tickIteration());
  ASSERT(!t.tickIteration());
  ASSERT(!t.tickIteration());

  ASSERT(!t.exhausted());
}

// Once recorded, the exhaustion class and diagnostic pass are sticky: a later
// breach in a different pass does not overwrite the pass that first ran out.
TEST(budget_diagnostic_pass_is_sticky) {
  uint64_t fake = 0;
  DecompileBudgetCaps caps;
  caps.wall_clock_ms = 10000;
  caps.wall_clock_hard_ms = 60000;
  DecompileBudgetTracker t(caps, [&fake]() { return fake; });

  t.enterPass("flow_analysis");
  fake = 10000;
  ASSERT_EQUALS((int)t.check(), (int)BudgetExhaustion::wallClock);
  ASSERT_EQUALS(t.exhaustedPass(), std::string("flow_analysis"));

  t.enterPass("data_flow");
  fake = 20000;
  t.check();
  ASSERT_EQUALS((int)t.exhaustion(), (int)BudgetExhaustion::wallClock);
  ASSERT_EQUALS(t.exhaustedPass(), std::string("flow_analysis"));
}

// reset() restarts the clock and clears counters and the diagnostic, so a reused
// tracker starts a fresh function from a clean slate.
TEST(budget_reset_clears) {
  uint64_t fake = 0;
  DecompileBudgetCaps caps;
  caps.wall_clock_ms = 10000;
  DecompileBudgetTracker t(caps, [&fake]() { return fake; });
  t.enterPass("flow_analysis");

  fake = 10000;
  ASSERT_EQUALS((int)t.check(), (int)BudgetExhaustion::wallClock);
  ASSERT(t.exhausted());

  t.reset();
  ASSERT(!t.exhausted());
  ASSERT_EQUALS(t.exhaustedPass(), std::string(""));
  ASSERT_EQUALS((int)t.check(), (int)BudgetExhaustion::none);  // clock rebased to fake=10000
}

// Caps are literal, not "disabled": a zero wall-clock cap is reached at t=0.
TEST(budget_zero_cap_trips_immediately) {
  uint64_t fake = 0;
  DecompileBudgetCaps caps;
  caps.wall_clock_ms = 0;
  caps.wall_clock_hard_ms = 60000;
  DecompileBudgetTracker t(caps, [&fake]() { return fake; });
  ASSERT_EQUALS((int)t.check(), (int)BudgetExhaustion::wallClock);
}

}  // namespace ghidra
