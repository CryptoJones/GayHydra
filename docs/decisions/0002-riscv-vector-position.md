---
number: 0002
title: RISC-V vector / bitmanip / crypto extensions — fork position on upstream #5778
status: accepted
date: 2026-05-21
audit_rec: 10
---

# Decision 0002: RISC-V V/B/K extensions — accept, drop "Waiting on customer"

## Context

Upstream NSA/ghidra issue/PR #5778 has tracked RISC-V vector (V), bit-
manipulation (B), and scalar-crypto (K) extension support for **2.7
years** with +6,676 LOC of contributor work. It has been labelled
`Waiting on customer` for most of that time.

"Waiting on customer" is the upstream label for "we will not move on
this until a paying NSA stakeholder asks for it." That is a defensible
internal policy. It is not defensible as a public-facing answer to a
contributor who has written 6,676 lines of code.

RISC-V V/B/K extensions are no longer research-grade. They are shipping
in commodity silicon (RVV 1.0 in Spacemit X60, T-Head C908, SiFive
Performance P670; Zb* in nearly every modern RV core; Zk* in security
profiles). Analyzing firmware from any of these without the extensions
emits incorrect decompiler output.

## Decision

**Accept RISC-V V/B/K extensions. Retire the `Waiting on customer`
status entirely; replace with `triage:accepted-for-review` and route to
`lane:processor`.**

The work proceeds under the same staged-landing structure as Decision
0001 (WebAssembly), because the upstream PR is also too large for a
single review:

1. **Stage 1 — Zb extensions** (Zba, Zbb, Zbc, Zbs). Smallest delta,
   high impact (Zbb in particular is in nearly every binary).
2. **Stage 2 — Zk extensions** (Zknd, Zkne, Zknh, Zksed, Zksh, Zkr,
   Zkt). Crypto primitives; canonical NIST test vectors are public.
3. **Stage 3 — V extension (RVV 1.0)** core: vector configuration,
   load/store, integer arithmetic, fixed-point.
4. **Stage 4 — V extension** advanced: mask, reduction, permutation,
   floating-point.

Each stage is a separate PR under `lane:processor`, with its own ISA
reference (RVV 1.0 spec for V; Zb-ISA Spec 1.0.0 for B; Crypto-Ext
Architecture Specification 1.0.1 for K) and its own canonical test
vector corpus.

## Why this and not the alternatives

- **Keep `Waiting on customer`.** Rejected: this is the public-facing
  failure mode the audit identified. A 2.7-year-old PR with that label
  is the project saying *"we will not move unless someone we recognise
  asks us to."* That is a posture this fork does not take.
- **Accept the upstream PR as one merge.** Rejected: the RFC + lanes
  governance applies to mega-PRs. RISC-V is no exception.
- **Reject and close.** Rejected: shipping silicon uses these
  extensions. Saying no would make the fork less useful than upstream.
- **Vendor a separate RISC-V extension pack.** Rejected: same reasoning
  as WASM — analysis quality is proportional to integration depth.

## Acceptance criteria

The decision is "shipped" when:

- The Zb stage has landed and `Ghidra/Processors/RISCV/` includes the
  Zb instructions in its `.slaspec`.
- At least one canonical Zb test vector (from the RISC-V test suite)
  is committed under `unittests/`.
- The `lane:processor` checklist passes.
- The remaining stages (Zk, V, V-advanced) are tracked as separate
  open issues with the `accepted-for-review` label.

## What this decision does *not* commit to

- A delivery date. The Triage SLA covers responsiveness, not throughput.
- A specific reviewer. The processor lane assigns automatically.

## Linked

- Upstream: <https://github.com/NationalSecurityAgency/ghidra/pull/5778>
- This fork's tracking issue: #10 (closed by this decision being merged).
- Related: Decision 0001 (WASM), same staged-landing structure.
- Related: `Ghidra/Processors/MAINTAINERS.md` (Rec 41).
