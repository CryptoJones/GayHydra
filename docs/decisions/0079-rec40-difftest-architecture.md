---
number: 0079
title: Rec 40 #40-5 — difftest architecture; slice 1 is a zero-new-dependency Ghidra-side instruction executor (PcodeEmulator-based, the in-tree equivalence-test pattern) with golden-case validation and a pluggable DifftestReference seam, so the harness ships and hardens now while the Unicorn/QEMU reference adapters wait behind the interface for Aaron's vendoring approval
status: accepted
date: 2026-06-11
audit_rec: 40
---

# Decision 0079: the difftest splits at the reference seam

## Context

Workstreams 1 and 2 (grammar, semantic model) are complete; `#40-5` opens Workstream 3 — the
differential fuzzer the plan doc calls the long-running win. The plan's loop: generate an
instruction word, run it through Ghidra's Sleigh + p-code emulator, run it through a reference
(Unicorn / QEMU / Spike / vendor vectors), compare deltas, report mismatches with reproducers.

Two facts shape the slicing:

- **The reference adapters require vendoring decisions** (native Unicorn/QEMU libraries or
  bindings) that belong to Aaron — explicitly out of autonomous scope.
- **The Ghidra-side executor needs nothing new.** Grounding found the exact pattern proven
  in-tree: `Framework/Emulation`'s `AbstractEmulationEquivalenceTest` constructs
  `new PcodeEmulator(language)`, injects instruction bytes into shared state, seeds registers via
  the thread's `PcodeArithmetic`, `setCounter` + `overrideContextWithDefault` +
  `stepInstruction(n)`, and reads registers back — fully headless against a `SleighLanguage`.

## Decision

**Slice 1 (zero new dependencies, test-layer first — the Rec 30/DD-0023 precedent):**

- `SleighInstructionExecutor` — `(LanguageID, instruction bytes, initial registers, registers to
  sample) → sampled register values after one step`, implemented on the in-tree
  `PcodeEmulator` pattern. This is the difftest's Ghidra half and independently useful (golden
  instruction-semantics regression tests for any arch, today).
- `DifftestReference` — the seam: the same signature as the executor; a future Unicorn adapter
  implements it, as do QEMU/Spike/vendor-vector adapters. The comparison loop is
  `executor-result == reference-result` over the sampled set.
- **Golden-case validation**: hand-computed deltas for known x86-64 instructions (e.g.
  `48 01 d8` `ADD RAX,RBX`: `rax=2, rbx=3 → rax=5`) assert the executor end-to-end. Until a real
  reference plugs in, the golden corpus *is* the reference for harness correctness.
- **Reproducer record** from day one: `(language id, instruction hex, initial registers, sampled
  deltas)` — the plan doc's stable-reproducer requirement, baked into the executor's API shape.

**Deferred behind the seam (need Aaron):** the reference adapters and their vendoring
(`#40-5b+`); pattern-driven random generation (`#40-5c` — uniform-by-construction generation from
the slaspec patterns, which the grammar work now makes specifiable); the `sleigh-difftest` CLI
packaging.

**Home:** slice 1 lives in the emulation test layer (`ghidra.pcode.difftest`) beside the pattern
it generalises, promoted to a main-source tool when the CLI lands — the same
harness-in-tests-first arc Rec 30 followed.

## Consequences

- The difftest sprint opens without waiting on vendoring: the Ghidra half ships, hardens against
  golden cases, and defines the exact contract a reference adapter must meet — so the vendoring
  decision, when Aaron makes it, drops into a tested socket.
- Slice 2 implements `SleighInstructionExecutor` + goldens per this design.
