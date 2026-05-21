---
name: Processor / Sleigh change
about: Modifying or adding a `Ghidra/Processors/<arch>/` entry, or a Sleigh runtime/compiler change
labels: lane:processor
---

<!--
Reviewed against docs/governance/lanes/PROCESSOR_LANE.md.
-->

## Architecture & reference

- Architecture: <!-- e.g. RISC-V RV64GC -->
- Reference: <!-- e.g. "RISC-V Instruction Set Manual, Volume I, Unprivileged ISA, Version 20191213, §2.5" -->

## What changes

<!-- One paragraph. Is this a new arch, a bug fix in an existing arch, or a Sleigh runtime change? -->

## Acceptance checklist

- [ ] Architecture reference cited above (manual name, version, section).
- [ ] At least one canonical ISA test vector included as a unit test.
- [ ] For new architectures: ≥20 instructions covering distinct encodings.
- [ ] Existing test corpus passes (CI green).
- [ ] No regressions in decompiler output for unrelated architectures.
- [ ] Test vector license compatible with redistribution (or cite-only).
- [ ] Changes confined to `Ghidra/Processors/<arch>/` (split otherwise).

## Maintainer

<!-- The named maintainer for this architecture in Ghidra/Processors/MAINTAINERS.md, or "orphaned". -->
