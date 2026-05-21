# Processor / Sleigh Lane

*Addresses Rec 04 of the 2026-05-21 principal-architect audit.*

## Why a separate lane

54% of upstream Ghidra's open PRs (180 of 335) are processor/Sleigh
additions. Reviewing a Sleigh `.slaspec` against an architecture manual is a
structurally different activity from reviewing a refactor of the framework's
filesystem layer. Mixing the two queues means each blocks the other.

This lane is for any PR that touches `Ghidra/Processors/<arch>/`.

## Trigger

A PR is auto-labelled `lane:processor` when any of the following match:

- The diff modifies a file under `Ghidra/Processors/`.
- The diff modifies a Sleigh-related file under
  `Ghidra/Features/Decompiler/src/main/doc/sleigh*` or
  `Ghidra/Framework/SoftwareModeling/src/main/java/ghidra/sleigh/`.
- The PR title starts with `proc:` or `sleigh:`.

PRs labelled `lane:processor` are routed to processor maintainers (see
`Ghidra/Processors/MAINTAINERS.md`, [Rec 41](../../../Ghidra/Processors/MAINTAINERS.md)).

## Reviewer rotation

Each architecture in `Ghidra/Processors/<arch>/` has a named maintainer
(or `orphaned`). A PR labelled `lane:processor` is auto-assigned to the
maintainer of the affected architecture. If `orphaned`, the PR is
assigned to the on-rotation processor reviewer of the week.

The processor reviewer rotation is published in
`docs/governance/processor-rotation.md` (one name per week, public).

## Acceptance checklist

A processor PR is reviewed against this checklist; nothing else is
required, and nothing on this list is optional.

- [ ] Architecture reference is cited in the PR description (manual,
      version, page/section).
- [ ] At least one canonical ISA test vector is included as a unit test.
      For new architectures, a non-trivial test corpus (≥20 instructions
      covering distinct encodings) is required.
- [ ] If the change modifies an existing `.slaspec`, the existing test
      corpus passes (CI green is sufficient).
- [ ] If the change introduces new pcode semantics, the
      `Features/Decompiler` test corpus is unchanged (no regression in
      decompiler output for unrelated arches).
- [ ] License of the architecture reference is compatible with
      redistributing test vectors. If unclear, cite the manual instead of
      copying.
- [ ] No changes outside `Ghidra/Processors/<arch>/` (or noted exceptions
      for shared Sleigh runtime). A processor PR that touches framework
      code is split.

## Out of scope for this lane

- Decompiler correctness fixes (see
  [DECOMPILER_CORRECTNESS_LANE.md](DECOMPILER_CORRECTNESS_LANE.md)).
- Framework refactors that happen to also touch a processor (split the PR).
- New language features in Sleigh itself — that is a `lane:framework`
  change to the Sleigh compiler/runtime and requires an RFC.

## Maintainer commitment

Processor PRs in this lane have the same [Triage SLA](../TRIAGE_SLA.md)
as any other PR: first human response within 10 business days.

The point of the lane is **not** that processor PRs are faster — it is
that they are reviewed by people who can review them, against a
checklist that fits the work.
