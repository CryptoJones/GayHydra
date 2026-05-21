---
number: 0001
title: WebAssembly support — fork position on upstream #4103
status: accepted
date: 2026-05-21
audit_rec: 09
---

# Decision 0001: WebAssembly — accept, with the staged-landing condition

## Context

Upstream NSA/ghidra issue and PR #4103 has tracked first-class WebAssembly
support for **4.2 years** with **46 comments and +15,387 LOC** of work
from external contributors (lead authorship: `nneonneo`, identified in
the audit's bus-factor analysis as one of the community contributors
doing substantial unpaid work). It is the single most-requested feature
in the upstream queue. The PR has not received an actionable maintainer
verdict; it has neither landed nor closed.

For this fork (GayHydra), "neither landed nor closed" is the failure
mode the audit's governance recs (01–08) exist to prevent. We are
making a verdict here, in writing.

## Decision

**Accept WebAssembly support, on the condition that it lands via the
[RFC process](../governance/RFC_PROCESS.md) as a sequence of small
PRs, not a single 15k-LOC drop.**

Concretely:

1. The lead author of upstream #4103 (or any willing contributor) is
   invited to open `docs/rfcs/0003-webassembly.md` against this fork,
   based on the existing #4103 design. The audit notes the work is
   substantially complete; the RFC step is **documentation-only**, not
   re-design.
2. Once the RFC merges (`status: accepted`), implementation PRs land
   under `lane:processor` against an explicit staging:
   - **Stage 1.** Sleigh `.slaspec` for WASM 1.0 (MVP) core
     instructions. Unit-test corpus covering the 172 core opcodes from
     the WebAssembly Core Specification, Section 4 (Numeric/Reference/
     Vector/Parametric/Variable/Table/Memory/Control instructions).
   - **Stage 2.** Loader (`WasmLoader.java`) covering the binary
     format (preamble, sections, custom sections) and a minimal
     analyzer that identifies function boundaries.
   - **Stage 3.** Function-table and `call_indirect` recovery, plus
     element-segment-driven analysis of indirect call targets.
   - **Stage 4a.** SIMD (`fixed-width SIMD` proposal, accepted into
     WASM 2.0).
   - **Stage 4b.** Threads (`threads` proposal, shared memory and
     atomic ops).
   - **Stage 4c.** Reference types, tail calls, GC, exception
     handling — each its own PR, each its own RFC amendment if the
     core design needs to change to accommodate them.
3. The author keeps commit attribution on every landed piece. Each
   landing carries a `Co-Authored-By:` trailer naming the upstream
   PR author(s) where applicable, and the squash-merge message names
   the original upstream PR (`Originally proposed in
   NationalSecurityAgency/ghidra#4103, by @nneonneo`). The fork does
   not absorb upstream work without credit.

## Why this and not the alternatives

- **Accept as-is (one 15k-LOC merge).** Rejected: the entire point of the
  RFC + lanes governance is that 15k-LOC merges are reviewed against a
  design, not a diff. We are not breaking our own rule on the highest-
  profile case.
- **Reject and close.** Rejected: the audit, the upvotes, and four years of
  community interest all say this is wanted. "Reject and close" would be
  honest if no one wanted it; that's not the case.
- **Vendor a third-party WASM plugin.** Rejected: WASM analysis quality is
  proportional to integration with the decompiler's pcode lifting; an
  external plugin recreates the lifting layer and the result is worse
  than first-class.
- **Wait for upstream.** Rejected: this is what created the 4-year stall.
  The fork's job is to act.

## Acceptance criteria

The decision is "shipped" when:

- An accepted RFC exists at `docs/rfcs/00NN-webassembly.md`.
- At least Stage 1 has landed and CI is green on Linux + macOS.
- The `lane:processor` checklist (see
  [PROCESSOR_LANE.md](../governance/lanes/PROCESSOR_LANE.md)) passes
  on every stage.

## What this decision does *not* commit to

- A timeline. The fork does not promise WASM by a date. It promises a
  review path and a reviewer pool.
- Upstreaming the work back to NSA/ghidra. That is the original author's
  choice and outside this fork's authority.

## Linked

- Upstream: <https://github.com/NationalSecurityAgency/ghidra/pull/4103>
- This fork's tracking issue: #9 (closed by this decision being merged).
- Companion RFC (to be opened): `docs/rfcs/00NN-webassembly.md`.
