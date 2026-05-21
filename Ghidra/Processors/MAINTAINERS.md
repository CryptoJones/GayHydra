# Processor Maintainers

*Addresses Rec 41 of the 2026-05-21 principal-architect audit.*

This file names a maintainer (or marks orphaned) for every
architecture under `Ghidra/Processors/`. Maintainers are the
review path for processor-lane PRs (see [PROCESSOR_LANE.md](../../docs/governance/lanes/PROCESSOR_LANE.md))
and the first responders for findings from the Sleigh differential
fuzzer (Rec 40).

The list is descriptive of who currently does the work; everything
starts at `orphaned` and is filled in by PR as people opt in.

## Architectures

| Architecture | Maintainer | Last `.slaspec` activity | Test corpus | Status |
|---|---|---|---|---|
| 6502 | orphaned | TBD | TBD | active |
| 68000 | orphaned | TBD | TBD | active |
| 8048 | orphaned | TBD | TBD | orphaned-warn (>4yr inactive) |
| 8051 | orphaned | 2019 | TBD | orphaned-warn (>4yr inactive) |
| 8085 | orphaned | TBD | TBD | orphaned-warn (>4yr inactive) |
| AARCH64 | orphaned | TBD | TBD | active |
| ARM | orphaned | TBD | TBD | active |
| Atmel | orphaned | TBD | TBD | active |
| BPF | orphaned | TBD | TBD | active |
| CP1600 | orphaned | TBD | TBD | active |
| CR16 | orphaned | TBD | TBD | active |
| Dalvik | orphaned | TBD | TBD | active |
| eBPF | orphaned | TBD | TBD | active |
| HCS08 | orphaned | TBD | TBD | orphaned-warn (>4yr inactive) |
| HCS12 | orphaned | TBD | TBD | active |
| Hexagon | orphaned | TBD | TBD | active |
| JVM | orphaned | TBD | TBD | active |
| Loongarch | orphaned | TBD | TBD | active |
| M16C | orphaned | TBD | TBD | active |
| M8C | orphaned | 2019 | TBD | orphaned-warn (>4yr inactive) |
| MC6800 | orphaned | TBD | TBD | orphaned-warn (>4yr inactive) |
| MCS96 | orphaned | TBD | TBD | orphaned-warn (>4yr inactive) |
| MIPS | orphaned | TBD | TBD | active |
| NDS32 | orphaned | TBD | TBD | active |
| PA-RISC | orphaned | TBD | TBD | orphaned-warn (>4yr inactive) |
| PIC | orphaned | TBD | TBD | active |
| PowerPC | orphaned | 2019 | TBD | orphaned-warn (>4yr inactive) |
| RISCV | orphaned | TBD | partial (see [decision 0002](../../docs/decisions/0002-riscv-vector-position.md)) | active |
| Sparc | orphaned | TBD | TBD | active |
| SuperH | orphaned | TBD | TBD | active |
| SuperH4 | orphaned | TBD | TBD | active |
| TI_MSP430 | orphaned | TBD | TBD | active |
| tricore | orphaned | TBD | TBD | active |
| V850 | orphaned | TBD | TBD | active |
| x86 | orphaned | TBD | TBD | active |
| Xtensa | orphaned | TBD | TBD | active |
| Z80 | orphaned | TBD | TBD | active |

Not architectures (excluded from this table):

| Directory | Why excluded |
|---|---|
| `Toy` | Test fixture, not a real ISA. |
| `DATA` | Data definitions shared across processors. |

## Status meanings

- **active** — someone has touched the `.slaspec` within the past
  4 years. May still be `orphaned` (no named maintainer) but the
  spec is presumed current.
- **orphaned-warn (>4yr inactive)** — `.slaspec` unchanged for >4
  years. Users should treat with extra caution; instruction set
  extensions or errata since the last update may not be reflected.
  Findings from Rec 40's difftest are especially valuable for
  these.
- **deprecated** — explicitly marked for removal; new analyses
  should use a successor architecture if available.

## Becoming a maintainer

Open a PR adding your GitHub handle to the `Maintainer` column.
Approval requires one existing core maintainer's yes (see the
top-level [MAINTAINERS.md](../../MAINTAINERS.md) for the
core-maintainer list).

When you opt in, you commit to:

- First response on PRs touching your architecture within the
  [Triage SLA](../../docs/governance/TRIAGE_SLA.md).
- First look at Rec 40 differential-fuzzer findings affecting
  your architecture.
- Light review on community PRs against your `.slaspec`.

The commitment is on outcomes (SLA, response quality), not hours.

## Test corpus

Each architecture should ship with a test corpus: a set of
canonical instructions exercising the encoding rules. The audit
called out that several processors have no public test corpus
of any kind.

Format: a directory at
`Ghidra/Processors/<arch>/data/test-vectors/` containing:

- Hex instruction words.
- Expected disassembly.
- Expected pcode (golden output).

The corpus is the input to:

- The processor-lane PR checklist (any change to the `.slaspec`
  must keep the corpus green).
- The Rec 40 differential fuzzer (the corpus is the seed input).

Corpus contributions are welcome from anyone, attributed in the
test-vectors README; no `Maintainer` status required.

## Maintenance

This file is updated by PR. The status column (active vs
orphaned-warn) is also updated by a Gradle task
(`gradle processorAudit`, added in a follow-up PR) that walks
the `.slaspec` git mtimes and refreshes the column nightly.

The full per-arch policy lives at
[`docs/governance/lanes/PROCESSOR_LANE.md`](../../docs/governance/lanes/PROCESSOR_LANE.md).
