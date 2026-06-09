---
number: 0023
title: Rec 30 — the first concrete headless-test-layer deliverable is a HighFunction integration harness (load a small program, decompile to a real HighFunction headlessly, assert on it), not the UI view-interface layer; it is a thin lifecycle wrapper over DecompInterface living in the Decompiler module's test.slow, and it is the gate that unblocks the Program-coupled Rec 37 / Rec 35 / Rec 33 queue
status: accepted
date: 2026-06-09
audit_rec: 30
---

# Decision 0023: Rec 30's first deliverable is a headless `HighFunction` harness, not the UI view-interface layer

## Context

Sprint 14 opens with a single blocker shared across three audit recs. Everything
left in Rec 37 (the C++ *recognition* wrappers that detect ctor/dtor/cast/`new`/
`delete` idioms in a live function and dispatch to the shipped
[`CppDecompilerHints`](../../Ghidra/Features/Base/src/main/java/ghidra/app/util/cpp/CppDecompilerHints.java)
renderers — DD-0016…DD-0022), Rec 35 #35-5b-2 (the retry-with-2x GUI action), and
Rec 33 #33-2.6 (the v1 IPC command-loop flip) needs a *live* `Program` /
`HighFunction` or a GUI `DISPLAY`. None of that fits the fast headless-unit layer:
`gradle :test` and the C++ `decomp_test_dbg` corpus never spin up a `Program` or run
the decompiler process, so a pass that walks a `HighFunction` has nothing to run
against and cannot satisfy the test-before-push rule. The recognition wrappers in
particular have been deferred at every Rec 37 step for exactly this reason — the
"headless ceiling."

Rec 30 in the 2026-05-21 audit is "the headless test layer." Its design doc,
[`docs/testing/HEADLESS_TEST_LAYER.md`](../testing/HEADLESS_TEST_LAYER.md), frames
that layer as a set of **UI view interfaces** — `DecompilerView`, `ListingView`,
`FunctionGraphView`, `SymbolTreeView` — each with a Swing impl and a headless impl,
so the bulk of the *existing* integration suite can drop its `JFrame`/`FieldPanel`
dependency and run without a display. That is a real and valuable program (the doc's
#30-2…#30-7 sequence), but it solves a different problem: it removes Swing from
tests that assert on *rendered UI state*. It does **not**, on its own, give a
recognition pass a `HighFunction` to walk. Sequencing the UI view-interface work
first would leave the Program-coupled queue blocked for the entire duration of that
migration.

## Decision

**Adopt the "integration harness" reading of Rec 30 as its first concrete
deliverable**, ahead of the UI view-interface layer: a headless fixture that loads
or builds a small `Program`, decompiles a named function to a real `HighFunction`
without a display, and lets a test assert on the syntax tree and C output. This is
the narrow capability the blocked queue actually needs, and the gate that makes the
Rec 37 recognition wrappers testable before they are committed.

Concretely, this slice ships
[`AbstractDecompilerHighFunctionTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/AbstractDecompilerHighFunctionTest.java)
plus a pilot,
[`HeadlessHighFunctionHarnessTest`](../../Ghidra/Features/Decompiler/src/test.slow/java/ghidra/app/decompiler/HeadlessHighFunctionHarnessTest.java).

Four choices pin the shape:

1. **A thin lifecycle wrapper over `DecompInterface`, not a new abstraction.** The
   harness opens a private `DecompInterface` against the program, decompiles the one
   function, asserts completion, and disposes the native process in a `finally`
   block. It returns the `DecompileResults` (and a `HighFunction` convenience). No
   view interface, no headless/Swing duality, no record-what-would-be-drawn buffer —
   none of the UI-view machinery is needed to hand a recognition pass a syntax tree.
   `DecompileResults` decodes its entire result stream eagerly in its constructor, so
   the `HighFunction`, the C markup, and the pretty-printed C all outlive the disposed
   interface; the pilot asserts exactly that (reads the C *after* the helper
   disposed).

2. **It lives in the Decompiler module's `test.slow`, not Base.** `HighFunction` is a
   Framework type (`ghidra.program.model.pcode`, in SoftwareModeling) and so is
   visible everywhere, but `DecompInterface` lives in the Decompiler feature module,
   and **Base does not depend on Decompiler** (the dependency runs the other way:
   Decompiler `api project(':Base')`). A harness that produces a `HighFunction`
   therefore cannot live in Base. The Decompiler module already depends on Base and
   pulls its test artifacts (`integrationTestImplementation project(path: ':Base',
   configuration: 'testArtifacts')`), so its integration tests can both *produce* a
   `HighFunction` and *see* the `ghidra.app.util.cpp` model the recognition wrappers
   target. The recognition wrappers themselves stay in Base alongside the feeders and
   renderers — they consume only the Framework `HighFunction` type, no Decompiler
   import — and the Decompiler integration tests drive them through this harness.

3. **In-memory `ToyProgramBuilder` fixtures over a committed binary blob.** Sprint 14
   phrases the harness as "load a small prebuilt binary," and that remains a valid
   option for an idiom that only a real compiler emits. But a committed binary adds a
   tracked blob (certification-manifest surface), pins an architecture/ABI, and is
   opaque. The proven in-tree pattern (the existing
   `DecompileProcessFramingV1EndToEndTest`, and `DecompilerTest`) builds the program
   in memory and decompiles it deterministically in well under a second with no
   toolchain. The harness imposes no fixture choice — a subclass may load a binary if
   it must — but the pilot uses a two-instruction Toy function, keeping the gate fast
   and self-contained.

4. **Extends `AbstractGhidraHeadlessIntegrationTest`, not the `Headed` variant.** The
   only live components are the in-memory program and the decompiler subprocess; no
   GUI tool is created, so the harness carries no `DISPLAY` requirement and none of
   the Swing event-ordering flakiness the Rec 30 doc calls out.

## Consequences

- The Program-coupled queue is unblocked at its narrowest point: a Rec 37 recognition
  wrapper can now be written in Base, driven from a Decompiler `test.slow` test that
  decompiles a fixture exhibiting the idiom and asserts the wrapper recovers the
  `(class, slot)` / `(derived, offset, direction)` it denotes and renders the expected
  string. Verified locally before commit via
  `gradle :Decompiler:integrationTest --tests <test>` (the Ghidra `gradlew` shim
  refuses a non-PUBLIC/DEV release name, so a system `gradle` ≥ 8.5 is used directly).
- The UI view-interface layer of `HEADLESS_TEST_LAYER.md` (#30-2…#30-7) is **not**
  withdrawn — it remains the path for migrating the *existing* Swing-bound suite off
  the display. It is simply de-prioritized behind the harness, because it does not
  unblock recognition. When it lands, the two are complementary: this harness produces
  the `HighFunction`; the view layer asserts on rendered UI state.
- The harness is deliberately minimal. Resist growing it into a general decompiler
  test framework; add helpers only when a recognition test actually needs one.
