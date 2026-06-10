---
number: 0067
title: Rec 37 #37-11d-1 — hints-consumer facade; CppHintsCollector.collect(HighFunction) runs all seven recognition drivers against the provider's shared per-program type system and returns one uniform, site-ordered List<CppHint(site, kind, rendering)> — the production assembly point that closes the loop from the analyzer-fed type system to consumable hints
status: accepted
date: 2026-06-10
audit_rec: 37
---

# Decision 0067: one facade collects what seven drivers render

## Context

With the analyzer wrappers shipped (DD-0063, DD-0066), opening a VS/Clang PE feeds the shared
per-program `CppTypeSystem` automatically. But the consumption side still had the same gap DD-0062
found on the construction side: **every recognition driver was constructed only by tests**, each
with a hand-fed type system. Nothing in production code ran the drivers over a decompiled function,
and a would-be consumer (a GUI margin, a headless report, a script) had no single thing to call —
it would have to know all seven driver classes, their slightly differing constructors, and where
the type system lives.

## Decision

`CppHintsCollector.collect(HighFunction)` in `ghidra.app.util.cpp` (Base, beside the drivers):

- Derives the `Program` from the function itself (`function.getFunction().getProgram()`) and the
  type system from `CppTypeSystemProvider.get(program)` — a consumer needs nothing beyond the
  `HighFunction` it already has. This is the first production call site of the provider's read
  side, completing DD-0062's contributor/consumer split.
- Runs all seven shipped drivers (virtual call, heap construction, array construction, placement
  construction, explicit destructor, delete, base cast) with one shared `CppDecompilerHints`
  renderer, and normalises their per-form `Rendered*` records into one
  `CppHint(Address site, Kind kind, String rendering)` — `Kind` is an enum with one constant per
  renderer form, so consumers can filter without string-matching.
- Orders hints by site address (kind as tie-break): a deterministic, listing-ordered stream
  regardless of driver iteration order.
- **Advisory, never wrong — inherited, not re-implemented.** Each driver declines what it cannot
  faithfully render; the collector adds no gating of its own and never invents a hint. An empty
  list is the normal result for a function with no recognized idioms or an unfed type system. A
  null function is a programming error and throws.

What this deliberately is not: a cache (drivers are cheap relative to the decompile that produced
the `HighFunction`), a service interface (none of the consumers exist yet to shape one), or a GUI
surface (Sprint 14's GUI work is blocked on a `DISPLAY`; the facade is what that surface will call).

## Consequences

- The full Rec 37 pipeline is now end-to-end in production form: upstream analysis → fork analyzers
  feed the provider's type system → `collect(highFunction)` returns rendered C++ hints. Verified in
  [`CppHintsCollectorTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/CppHintsCollectorTest.java)
  through the Rec 30 harness: the virtual-call fixture's class fed into the *provider's* instance
  (the production wiring, not a hand-passed type system) yields exactly one
  `VIRTUAL_CALL / param_1->draw()` hint; an unfed type system yields none; null throws. Suite 3/3.
- Remaining `#37-11` work: surfacing — where the collected hints become visible (decompiler margin
  / annotations once the GUI ceiling lifts; possibly a headless script/report consumer sooner).
- Verified locally before commit (test-before-commit, local-only — no push, no release):
  `gradle :Decompiler:integrationTest --tests 'ghidra.app.decompiler.CppHintsCollectorTest'` and
  `gradle :Base:ip`, Gradle 8.5 / Temurin 21 (the CI-matching toolchain).
