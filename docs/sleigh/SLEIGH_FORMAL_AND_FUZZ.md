# Sleigh: Formal Grammar, Semantic Model, Differential Fuzzer

*Addresses Rec 40 of the 2026-05-21 principal-architect audit.*

## What we have today

Sleigh is the language that describes processor architectures in
Ghidra: instruction encoding, mnemonic display, and pcode
semantics for every supported ISA. The current state:

- An **HTML reference manual** describing the language.
- A **lexer + parser** in C++ (`slghscan.l`, `slghparse.y`,
  `slgh_compile.*`).
- **39 `.slaspec` files** comprising **21k lines** of architecture
  description.

What we do not have:

- A **formal grammar** (BNF or similar) decoupled from the
  compiler's implementation.
- A **semantic model** — a written specification of what each
  Sleigh construct means in terms of pcode.
- A **fuzz harness** for the Sleigh compiler.
- Differential testing against reference ISA test vectors.

The audit identified the consequence: "silent codegen bugs in
stale processors (PowerPC unchanged since 2019, 8051 since 2019,
M8C since 2019) are inevitable and undetectable."

A `.slaspec` describing a CPU instruction has a chance of being
*subtly wrong* — wrong flag effect, wrong rounding, wrong endian
in one corner case. Without a reference to differentially-test
against, the bug ships and stays shipped until a user notices
miscompiled output.

## The plan

Three workstreams, sequenced:

1. **Extract a formal Sleigh grammar.** Convert the C++ Yacc/Lex
   to a BNF written in a tool-agnostic notation (probably ANTLR
   or pure EBNF, doc-only). The grammar lives at
   `docs/sleigh/grammar.bnf`. The C++ parser stays canonical;
   the BNF is doc + an independent re-implementation source.
2. **Write a semantic model.** A document at
   `docs/sleigh/semantic-model.md` defines what each Sleigh
   construct means in pcode terms. This is reverse-engineering
   the implementation — the implementation is the current
   ground truth — but the *document* is a contract.
3. **Differential fuzzer.** A harness that:
   - Generates random valid Sleigh instructions (random
     mnemonics + valid operand encodings) for an arch.
   - Runs them through Ghidra's Sleigh.
   - Runs them through a reference disassembler/emulator for
     that ISA (Capstone, Unicorn, QEMU's TCG, or vendor-supplied
     test vectors).
   - Compares the pcode semantics to the reference's effect.
   - Reports mismatches.

The differential fuzzer is the long-running win; the formal
grammar and semantic model are prerequisites for being able to
generate valid input and interpret mismatches.

## Workstream 1: formal grammar

The grammar extraction is mostly mechanical translation from
`slghparse.y` (Yacc) to BNF. The artifact:

```ebnf
<slaspec> ::= <definition>*

<definition> ::= <token-def>
             | <varnode-def>
             | <attach-def>
             | <macro-def>
             | <constructor>
             | <with-block>

<constructor> ::= <table-name> ":" <display> "is" <pattern> [ <semantic-block> ]

<pattern> ::= <pattern-expression>
<pattern-expression> ::= <pattern-atom>
                       | <pattern-expression> ";" <pattern-expression>
                       | <pattern-expression> "&" <pattern-expression>
                       | <pattern-expression> "|" <pattern-expression>
                       | "(" <pattern-expression> ")"
... (continued in docs/sleigh/grammar.bnf) ...
```

The grammar is checked against the Yacc by a CI job that re-parses
all 39 `.slaspec` files with a fresh ANTLR-generated parser from
the BNF and asserts it accepts everything the canonical Yacc
accepts. Drift is a CI failure.

## Workstream 2: semantic model

The semantic model documents each Sleigh construct's meaning:

```
SEMANTIC: macro <ident> "(" <params> ")" "{" <body> "}"

Meaning: introduces a named pcode-emitting block. At each call
site, the body is substituted with `<params>` bound to the
caller's expressions. Lexical scoping; no recursion. The macro is
expanded at .sla-compile time, not at runtime.

Pcode-level meaning: the macro body emits a sequence of pcode ops
identical to inlining the body's statements with parameter
substitution.

Examples: ARM's `setFlag` macro, x86's `OF_Sub`.

Subtle: the parameter substitution is lexical, not value-based. A
macro that mutates a parameter mutates the caller's expression, not
a copy.

Equivalent C-side construct: `MacroSymbol` in `slgh_compile.cc`.
```

Every Sleigh construct gets a paragraph like the above. The model
ships as `docs/sleigh/semantic-model.md`; it is a living document
that gets updated when the canonical implementation changes (and
the update gates a `.sla` schema-version bump).

## Workstream 3: differential fuzzer

The fuzzer ships as a new tool:

```
sleigh-difftest --arch arm32 --reference unicorn --iters 100000
```

For each iteration:

1. Generate a random instruction word respecting the arch's
   encoding rules (derived from the `.slaspec`'s pattern
   expressions).
2. Disassemble + emit pcode via Ghidra's Sleigh.
3. Emulate the pcode (Ghidra's pcode emulator).
4. Emulate the same instruction word via the reference (Unicorn /
   QEMU / vendor).
5. Compare register + memory deltas after one step.
6. Report mismatches with a reproducer (`(arch, instruction
   word, ghidra pcode, ghidra delta, reference delta)`).

Mismatches go into `docs/sleigh/difftest-findings/`; each is a
candidate bug in either Ghidra's `.slaspec` or in the reference
(the reference can also be wrong; the difftest output is
*evidence*, not a verdict).

### Reference choice

| Arch | Reference | Rationale |
|---|---|---|
| x86-64 | Unicorn engine | Best-tested; high coverage |
| ARM32 + AArch64 | Unicorn engine | Same |
| RISC-V | Spike (official simulator) | Reference implementation |
| MIPS | QEMU TCG | Mature TCG path |
| PowerPC | QEMU TCG | Same |
| 8051, M8C, PIC, etc. | Vendor test vectors where available | Embedded archs without a TCG path |

Stages 1 and 2 (grammar + semantic model) are blocking
prerequisites for the difftest; without a clear semantic model,
"mismatch" is ambiguous.

## Sequencing

| PR | Scope |
|---|---|
| #40-1 (this PR) | This design doc |
| #40-2 | Workstream 1: BNF grammar; CI job comparing BNF vs Yacc on the 39 .slaspecs |
| #40-3 | Workstream 2: semantic-model first 30 constructs |
| #40-4 | Workstream 2: remaining constructs |
| #40-5 | Workstream 3: difftest framework + x86-64 / Unicorn integration |
| #40-6 | Difftest: ARM32 + AArch64 |
| #40-7 | Difftest: RISC-V (via Spike) |
| #40-8 | Difftest: MIPS, PowerPC (via QEMU TCG) |
| #40-9 | Per-arch findings doc + .slaspec patches as findings come in |

This is months-to-years of work. The doc + grammar (Workstream 1)
ships in #40-1 and #40-2 with bounded scope; everything after is
opportunistic per arch.

## Risk

- **The reference can be wrong.** Mitigation: every difftest
  finding is investigated; sometimes the fix is in our `.slaspec`,
  sometimes the report is to the reference. This is normal.
- **Random instruction generation can be biased.** Mitigation:
  use the grammar to drive generation so the input space is
  uniform-by-construction over valid encodings.
- **Difftest reproducer churn.** Stable instruction encodings
  reproduce; randomised reproducers don't. Mitigation: every
  reported mismatch is captured as a hex-instruction-word with
  a seed in the reproducer file.

## Coordination with Rec 13 (OSS-Fuzz) and Rec 14 (loader fuzz)

- **Rec 13** fuzzes the C++ decompiler's parsers.
- **Rec 14** fuzzes the Java loaders.
- **This rec** fuzzes the Sleigh compiler and the disassembly
  semantics.

Together they cover the major attacker-controlled-input surfaces.
Rec 40's difftest is qualitatively different — it's a
*correctness* test, not a crash test — but the harness is in the
same family.

## Coordination with Rec 41 (processor maintainers)

Each arch's `.slaspec` is owned by a named maintainer per Rec 41.
Difftest findings are routed to that maintainer in the first
instance; ownership is what makes the findings actionable.
