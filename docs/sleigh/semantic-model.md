# Sleigh semantic model

*Rec 40 Workstream 2 (`#40-3`/`#40-4`) of
[SLEIGH_FORMAL_AND_FUZZ.md](SLEIGH_FORMAL_AND_FUZZ.md). Companion to the
formal grammar at [grammar.bnf](grammar.bnf).*

This document defines what each Sleigh construct **means** — in pattern-match
terms for the disassembly layer and in p-code terms for the semantic layer.
The C++ implementation (`slgh_compile.cc` and the template classes it builds)
remains the ground truth this document was reverse-engineered from; the
*document* is the contract, and an implementation change that alters a meaning
below must update this file (and gates a `.sla` schema-version bump, per the
plan doc).

Each entry follows the plan's template: the construct's syntax, its meaning,
its pattern- or pcode-level meaning, anything subtle, and the C++ anchor a
reader can verify against.

**Scope:** complete. Sections 1–5 (`#40-3`) cover the disassembly half —
definitions, constructors and display, pattern equations, context actions,
with-blocks, and macros-as-declarations. Sections 6–9 (`#40-4`) cover the
RTL/expression layer — everything inside `{ … }`.

---

## 1. Definitions

### 1.1 `define endian = big|little ;`

**Meaning:** declares the byte order used to interpret every token field in
the specification. Mandatory first definition of every slaspec (the grammar's
start symbol requires it before anything else).

**Pattern-level meaning:** when a token's bytes are fetched from the
instruction stream, this order decides which byte is most significant before
field extraction. A per-token `endian` clause (1.3) overrides it token-wise.

**Subtle:** endianness applies to *token* interpretation, not to varnode
spaces (space word order is a separate `wordsize` concern).

**C++ anchor:** `SleighCompile::setEndian`.

### 1.2 `define alignment = N ;`

**Meaning:** declares instruction alignment in bytes. The disassembler treats
addresses not divisible by `N` as invalid instruction starts.

**Subtle:** alignment is advisory metadata for flow analysis; it does not
change pattern matching at a given address.

**C++ anchor:** `SleighCompile::setAlignment`.

### 1.3 `define token NAME ( BITS ) [endian = big|little] field…`

**Meaning:** declares an instruction-stream window of `BITS` bits (must be a
multiple of 8) and names bitfields within it. Fields of one token are
extracted from the *same* fetched window.

**Pattern-level meaning:** a constructor whose pattern uses fields from a
token consumes that token's bytes; the token's length contributes to the
instruction length computed during the match.

**Subtle:** the optional trailing `endian` clause overrides the global
endianness for this token only — the mechanism dual-endian ISAs use.

**C++ anchor:** `SleighCompile::defineToken`.

### 1.4 Token field: `NAME = (LO, HI) [signed] [hex|dec]`

**Meaning:** names bits `LO..HI` (inclusive, LSB-0) of the enclosing token.
`signed` makes the extracted value sign-extended; `hex`/`dec` set the display
radix when the field's value prints.

**Pattern-level meaning:** the field is a *family symbol*: in a constraint it
is the pattern value extracted from the fetched token; in display position it
prints its value; attached (1.10–1.12) it maps to names/values/varnodes.

**Subtle:** `signed` affects both constraint comparison semantics and
attached-value lookup (negative indices are legal against `attach values`
lists that contain negatives).

**C++ anchor:** `SleighCompile::addTokenField` (`FieldQuality`).

### 1.5 `define context VARSYM field…`

**Meaning:** declares bitfields over a *context register* — a varnode whose
value participates in pattern matching without consuming instruction bytes.

**Pattern-level meaning:** context fields contribute match bits exactly like
token fields, but read from the processor-state context value at the address
being disassembled rather than from the instruction stream.

**Subtle:** field modifier `noflow` (context fields only) makes a
disassembly-time set of the field apply *only at the target address* instead
of flowing forward to subsequent addresses — the difference between "this
one instruction is thumb" and "from here on, thumb".

**C++ anchor:** `SleighCompile::addContextField`; flow semantics in
`ContextChange`/`ContextOp`.

### 1.6 `define space NAME type=… [size=N] [wordsize=N] [default]`

**Meaning:** declares an address space (`ram_space` or `register_space`
type), its address size in bytes, its addressable-unit word size, and
optionally marks it the default space code addresses live in.

**Pcode-level meaning:** every varnode is `(space, offset, size)`; this
declares the spaces those triples may name. `wordsize` scales offsets:
an offset counts words, each `wordsize` bytes.

**C++ anchor:** `SleighCompile::newSpace` (`SpaceQuality`).

### 1.7 `define SPACESYM offset=O size=S name… ;`

**Meaning:** carves named varnodes (registers, typically) out of a declared
space: each name in the list takes `S` bytes starting at `O`, consecutively.

**Subtle:** the reserved name `_` skips its slot — it advances the offset
without defining a varnode (the hole idiom shared with attach lists).

**C++ anchor:** `SleighCompile::defineVarnodes`.

### 1.8 `define bitrange NAME = VARSYM [ LO , WIDTH ] …`

**Meaning:** names a bit slice of an existing varnode (e.g. a flag bit of a
status register).

**Pcode-level meaning:** reads of the bitrange extract the slice
(shift+mask / `SUBPIECE`-style); writes read-modify-write the parent
varnode. It is sugar over the parent varnode, not independent storage.

**C++ anchor:** `SleighCompile::defineBitrange` (`BitrangeSymbol`).

### 1.9 `define pcodeop NAME… ;`

**Meaning:** declares user-defined p-code operations — opaque black-box ops
for instruction behaviour the p-code core cannot express.

**Pcode-level meaning:** a call of the declared name emits `CPUI_CALLOTHER`
with the op's index as input 0; inputs/outputs are whatever the call site
supplies. Downstream analysis treats it as an uninterpreted function.

**C++ anchor:** `SleighCompile::addUserOp` (`UserOpSymbol`).

### 1.10 `attach values fieldlist [ v0 v1 … ] ;`

**Meaning:** re-maps a field's extracted index to the listed integer values:
field value `i` *means* `list[i]` thereafter (constraints compare against the
mapped value; display prints it; semantics read it).

**Subtle:** `_` marks an *invalid* index — an instruction whose field selects
a hole fails to match entirely. This is load-bearing for encodings with
reserved register numbers.

**C++ anchor:** `SleighCompile::attachValues` (`ValueMapSymbol`).

### 1.11 `attach names fieldlist [ n0 n1 … ] ;`

**Meaning:** gives a field display names only: value `i` *prints as*
`list[i]`. Unlike 1.10/1.12 it changes neither match semantics nor the
value the semantic layer sees.

**C++ anchor:** `SleighCompile::attachNames` (`NameSymbol`).

### 1.12 `attach variables fieldlist [ r0 r1 … ] ;`

**Meaning:** the register-selector idiom: field value `i` *is* the varnode
`list[i]` — in display (prints the register name) and in semantics (reads
and writes go to that varnode).

**Subtle:** `_` holes make the selecting encoding invalid, as in 1.10. The
field becomes a `VARLISTSYM` family symbol; in an RTL body it denotes the
selected varnode for the instruction instance being decoded.

**C++ anchor:** `SleighCompile::attachVarnodes` (`VarnodeListSymbol`).

---

## 2. Constructors, tables, and display

### 2.1 `TABLE : display is pattern [contextblock] body`

**Meaning:** one constructor of subtable `TABLE`. The bare `:` form (no
table name) contributes to the root instruction table. A subtable name used
by several constructors forms an alternation: at disassembly time the
constructor whose pattern matches is selected.

**Pattern-level meaning:** the instruction matches a tree of constructors —
the root constructor plus, recursively, one matching constructor for each
subtable operand its pattern references. Longest/most-specific match rules
order candidates within a table.

**C++ anchor:** `SleighCompile::createConstructor` / `buildConstructor`.

### 2.2 The display section (between `:` and `is`)

**Meaning:** the constructor's print pieces — literal text, whitespace, and
operand references — concatenated to render the disassembly line.

**Subtle (three rules with teeth):**
- Whitespace is significant: a space is itself a print piece.
- `^` concatenates adjacent pieces *without* whitespace (used to glue a
  mnemonic to a suffix operand: `op^cc`).
- An identifier in display position that names an existing symbol
  (`SYMBOLSTRING`) **declares an operand of the constructor** — unless the
  constructor is in the root table and the identifier names the table
  itself, in which case it prints literally (the mnemonic idiom).

**C++ anchor:** `Constructor::addSyntax`, `SleighCompile::newOperand`.

### 2.3 Operands

**Meaning:** a name appearing in the display (2.2) or self-defined in the
pattern (3.5) becomes an operand: a per-instance slot with a print form, a
pattern meaning, and (in the body) a value.

**Pcode-level meaning:** in the RTL body the operand denotes whatever its
pattern resolution produced — the subtable's exported varnode, the attached
varnode, or the field's value as a constant.

**C++ anchor:** `OperandSymbol`; resolution through `OperandEquation`.

---

## 3. Pattern equations (after `is`)

### 3.1 Conjunction `&`, disjunction `|`, concatenation `;`

**Meaning:** `A & B` — both equations must match *the same* bits/window.
`A | B` — either matches (the constructor effectively duplicates per
alternative). `A ; B` — `B` matches the token window *following* `A`'s:
concatenation advances the instruction cursor; `&` does not.

**Subtle:** precedence is `&` tightest, then `;`, then `|` loosest (the
grammar's declaration order) — `a | b ; c` parses as `a | (b ; c)`.

**C++ anchor:** `EquationAnd`, `EquationOr`, `EquationCat`.

### 3.2 Constraints: `field OP pexpression`

**Meaning:** compares the field's extracted (or attached-value-mapped) value
against a disassembly-time expression with `=`, `!=`, `<`, `<=`, `>`, `>=`.
Equality against a constant becomes literal match bits; the other operators
become match-time predicates.

**Subtle:** the right side is a *pattern expression* evaluated at
disassembly time (it may reference other fields, `inst_start`, etc.) — not
an RTL expression. Signedness of comparison follows the field's `signed`
modifier.

**C++ anchor:** `EqualEquation` … `GreaterEqualEquation`.

### 3.3 Ellipsis `...`

**Meaning:** alignment freedom for variable-length encodings. `atomic ...`
lets the matched window of `atomic` sit at the *left* edge of a longer
pattern; `... atomic` right-aligns it.

**C++ anchor:** `EquationLeftEllipsis` / `EquationRightEllipsis`.

### 3.4 Bare family symbol / subtable in pattern position

**Meaning:** an *invisible operand*: the symbol participates in matching
(and, for a subtable, consumes its sub-pattern) without appearing in the
display.

**C++ anchor:** `SleighCompile::defineInvisibleOperand`.

### 3.5 Bare operand name in pattern position

**Meaning:** self-definition — the operand named in the display gets its
meaning here (e.g. the display name `imm` with pattern `imm` binds the
operand to the like-named field). `OPERAND = pexpression` instead defines
the operand's value by computation (a *disassembly-time* binding).

**C++ anchor:** `OperandEquation`, `SleighCompile::selfDefine` /
`constrainOperand` / (context list form) `defineOperand`.

### 3.6 `epsilon` / `instruction` specials (`SPECSYM`)

**Meaning:** specific symbols usable unconstrained in a pattern: `epsilon`
matches nothing (the empty pattern), `inst_start`/`inst_next`/`inst_next2`
expose instruction addresses to pattern expressions and context actions.

**C++ anchor:** `UnconstrainedEquation`; the `start`/`end`/`next2`
`SpecificSymbol` subclasses.

---

## 4. Context actions (`[ … ]` between pattern and body)

### 4.1 `CONTEXTSYM = pexpression ;`

**Meaning:** sets a context field *during disassembly*, before the body
runs. With a `noflow` field the set applies only at the current address;
otherwise it flows to subsequent disassembly.

**Subtle:** `inst_next`/`inst_next2` may not feed a context set — the
instruction length is not final while context is being decided (enforced
with an error in `contextMod`).

**C++ anchor:** `SleighCompile::contextMod` (`ContextOp`).

### 4.2 `globalset(symbol, CONTEXTSYM) ;`

**Meaning:** commits the context field's value at *another* address —
typically a branch target — so disassembly arriving there later sees it
(the cross-instruction half of `noflow` workflows).

**C++ anchor:** `SleighCompile::contextSet` (`ContextCommit`).

---

## 5. Structure and reuse

### 5.1 `with TABLE : pattern [contextblock] { … }`

**Meaning:** a lexical prefix block: every constructor (and nested
definition) inside gets `TABLE` as its default table (empty = root), the
given pattern conjoined (`&`) to its own, and the context block prepended.

**Subtle:** `with` blocks nest; inner blocks compose with outer ones. An
empty `id_or_nil` with a non-empty pattern is the common "every instruction
in this file requires mode bit M" idiom.

**C++ anchor:** `SleighCompile::pushWith` / `popWith`.

### 5.2 `macro NAME(args…) { rtl }` (declaration)

**Meaning:** declares a named, parameterised p-code template. Invocation
(an RTL statement, covered with the semantic layer in `#40-4`) expands the
body at compile time with lexical parameter substitution — inlining, not a
call.

**Subtle:** substitution is lexical: a macro that assigns to a parameter
assigns to the caller's expression. There is no recursion; expansion happens
at `.sla` compile time, never at runtime.

**C++ anchor:** `SleighCompile::createMacro` / `buildMacro`
(`MacroSymbol`); call sites via `createMacroUse`.

---

## 6. RTL statements

### 6.1 Assignment: `lhs = expr ;`

**Meaning:** evaluates `expr` and stores it to the left-hand varnode.

**Pcode-level meaning:** the expression tree emits its op sequence; the final
op's output is set to the LHS varnode. The LHS must be a *specific* symbol
(register varnode, operand, special) — assigning to a table or unknown name
is rejected.

**Subtle:** the bitrange LHS forms `lhs[LO,WIDTH] = expr` and `BITSYM = expr`
are read-modify-write on the parent varnode (mask the slice out, shift the
value in, OR, store) — they emit several ops, not one. Truncation on the LHS
(`v:4 = …`) and subpiece on the LHS (`v(2) = …`) are *illegal* — both are
explicit compile errors, by design: a partial-store must be written as a
bitrange so its read-modify-write cost is visible.

**C++ anchor:** `ExprTree::toVector` (plain), `PcodeCompile::assignBitRange`.

### 6.2 Local declarations: `local NAME [: SIZE] [= expr] ;`

**Meaning:** introduces a temporary varnode in the compiler's `unique` space,
scoped to this constructor body. With `= expr`, declaration and assignment in
one. The bare `NAME = expr ;` form (no `local`, name unbound) also creates a
temporary — the grammar's deliberate shift-resolution — but `local` is the
explicit, collision-proof spelling.

**Subtle:** without `: SIZE`, the temporary's size is inferred from the first
assignment; a size mismatch downstream is a compile error, not a truncation.

**C++ anchor:** `PcodeCompile::newLocalDefinition` / `newOutput`.

### 6.3 Store through pointer: `*[space]:size expr1 = expr2 ;`

**Meaning:** stores `expr2` at the address `expr1` inside `space` (default:
the default code space), writing `size` bytes (default: inferred).

**Pcode-level meaning:** emits `CPUI_STORE` with the space id as input 0,
`expr1` as the pointer, `expr2` as the value. The mirrored *load* form is an
expression (7.2).

**C++ anchor:** `PcodeCompile::createStore` (`StarQuality`).

### 6.4 Flow: `goto`, `call`, `return`, conditional and indirect forms

**Meaning and pcode mapping:**

| Sleigh | P-code |
|---|---|
| `goto DEST;` | `CPUI_BRANCH DEST` |
| `if expr goto DEST;` | `CPUI_CBRANCH DEST, expr` |
| `goto [expr];` | `CPUI_BRANCHIND expr` |
| `call DEST;` | `CPUI_CALL DEST` |
| `call [expr];` | `CPUI_CALLIND expr` |
| `return [expr];` | `CPUI_RETURN expr` |

**Subtle:** bare `return;` is illegal — `CPUI_RETURN` requires its indirect
input (typically the link register / popped address). `goto` to a `<label>`
is intra-constructor flow: the label is a relative jump destination inside
the emitted op sequence, not an address.

**C++ anchor:** `PcodeCompile::createOpNoOut`; labels via
`PcodeCompile::defineLabel` / `placeLabel`.

### 6.5 Jump destinations

**Meaning:** a flow target is one of: an operand (its value becomes a *code
address* in the current space), an integer literal (absolute address in the
current space), `INTEGER[SPACESYM]` (absolute address in a named space), a
start/next special (`JUMPSYM`: `inst_start`, `inst_next`, `inst_next2`), or a
`<label>`.

**Subtle:** naming an operand as a destination flips it to code-address
interpretation (`setCodeAddress`) — this is what makes `call dest` with a
computed operand relocatable, versus `call [dest]` which takes the *value*
as an indirect target.

**C++ anchor:** the `jumpdest` actions (`VarnodeTpl` with `j_curspace` /
`j_relative` const templates).

### 6.6 `build OPERANDSYM ;`

**Meaning:** explicitly orders a subtable operand's p-code emission at this
point in the body. Without `build`, sub-constructor semantics emit in
display order before the body's own ops.

**Pcode-level meaning:** a `BUILD` directive op carrying the operand index,
resolved at instruction-assembly time into the sub-constructor's emitted
sequence.

**C++ anchor:** `PcodeCompile::createOpConst(BUILD, …)`.

### 6.7 `crossbuild varnode, section ;`

**Meaning:** splices the named p-code *section* (2.x named sections,
`<<name>>`) of the instruction at another address — the delay-slot idiom's
big brother, used to stitch semantics across paired instructions.

**C++ anchor:** `SleighCompile::createCrossBuild`.

### 6.8 `delayslot(N) ;`

**Meaning:** marks this point as where the following `N` bytes' instruction
(the delay slot) executes; the disassembler decodes the slot instruction and
its semantics emit here.

**Pcode-level meaning:** a `DELAY_SLOT` directive op with `N`, expanded at
assembly time.

**C++ anchor:** `PcodeCompile::createOpConst(DELAY_SLOT, …)`.

### 6.9 Macro invocation: `MACROSYM(args…) ;`

**Meaning:** expands the macro body (5.2) inline with lexical substitution of
the argument expressions for the parameters. Not a call: no frame, no
return, recursion impossible.

**Subtle:** an argument expression with side effects is evaluated where the
parameter is *used* (substitution), and a macro assignment to a parameter
writes the caller's expression — the model's sharpest edge.

**C++ anchor:** `SleighCompile::createMacroUse` → expansion in
`MacroBuilder`.

### 6.10 User-op statement: `USEROPSYM(args…) ;`

**Meaning:** invokes a declared pcodeop (1.9) for effect (no output).

**Pcode-level meaning:** `CPUI_CALLOTHER` with the user-op index and the
argument varnodes; the expression form (7.6) adds an output.

**C++ anchor:** `PcodeCompile::createUserOpNoOut`.

---

## 7. RTL expressions

### 7.1 Operator → p-code map

Every binary/unary operator emits exactly one `CPUI_*` op. Loosest-to-
tightest precedence: `||` < `&&`,`^^` < `|` < `^` < `&` < `==`,`!=`,`f==`,
`f!=` < relationals (non-assoc) < `<<`,`>>`,`s>>` < `+`,`-`,`f+`,`f-` <
`*`,`/`,`%`,`s/`,`s%`,`f*`,`f/` < unary `!`,`~`,`-`,`f-` (right-assoc).

| Sleigh | P-code | Note |
|---|---|---|
| `+` `-` `*` | `INT_ADD` `INT_SUB` `INT_MULT` | |
| `/` `%` | `INT_DIV` `INT_REM` | unsigned |
| `s/` `s%` | `INT_SDIV` `INT_SREM` | signed |
| `==` `!=` | `INT_EQUAL` `INT_NOTEQUAL` | |
| `<` | `INT_LESS` | unsigned |
| `>` | `INT_LESS` **operands swapped** | canonicalised |
| `<=` | `INT_LESSEQUAL` | |
| `>=` | `INT_LESSEQUAL` **operands swapped** | canonicalised |
| `s<` `s<=` | `INT_SLESS` `INT_SLESSEQUAL` | |
| `s>` `s>=` | `INT_SLESS` / `INT_SLESSEQUAL` **swapped** | canonicalised |
| `f<` `f<=` | `FLOAT_LESS` `FLOAT_LESSEQUAL` | |
| `f>` `f>=` | `FLOAT_LESS` / `FLOAT_LESSEQUAL` **swapped** | canonicalised |
| `^` `&` `\|` | `INT_XOR` `INT_AND` `INT_OR` | bitwise |
| `<<` `>>` `s>>` | `INT_LEFT` `INT_RIGHT` `INT_SRIGHT` | |
| `&&` `\|\|` `^^` | `BOOL_AND` `BOOL_OR` `BOOL_XOR` | one-bit |
| `f+` `f-` `f*` `f/` | `FLOAT_ADD/SUB/MULT/DIV` | |
| `f==` `f!=` | `FLOAT_EQUAL` `FLOAT_NOTEQUAL` | |
| unary `-` | `INT_2COMP` | |
| unary `~` | `INT_NEGATE` | bitwise not |
| unary `!` | `BOOL_NEGATE` | |
| unary `f-` | `FLOAT_NEG` | |

**Subtle (the canonicalisation rule):** the source never reaches p-code with
a greater-than opcode — every `>`/`>=` family operator emits its `<`/`<=`
dual with **swapped operands**. Downstream consumers (and the Rec 37 hint
renderers, which re-derived this empirically) see only the canonical forms.

**C++ anchor:** the `expr` actions (`PcodeCompile::createOp`).

### 7.2 Load through pointer: `*[space]:size expr`

**Meaning:** the expression dual of 6.3 — reads `size` bytes at address
`expr` in `space`. Emits `CPUI_LOAD`.

**C++ anchor:** `PcodeCompile::createLoad`.

### 7.3 Function-style operators

One-input: `zext`/`sext` (`INT_ZEXT`/`INT_SEXT`), `abs`/`sqrt`/`nan`/`trunc`
/`ceil`/`floor`/`round` (`FLOAT_*`), `int2float`/`float2float`
(`FLOAT_INT2FLOAT`/`FLOAT_FLOAT2FLOAT`), `popcount`/`lzcount`
(`POPCOUNT`/`LZCOUNT`). Two-input: `carry`/`scarry`/`sborrow`
(`INT_CARRY`/`INT_SCARRY`/`INT_SBORROW`). Special: `newobject(expr[, expr])`
→ `CPUI_NEW`; `cpool(args…)` → variadic `CPUI_CPOOLREF`, **minimum two
inputs** (enforced).

**C++ anchor:** the corresponding `expr` actions.

### 7.4 Truncation, subpiece, bitrange reads

`v:N` reads the low `N` *bytes* of `v`; `v(N)` reads `v` shifted down `N`
bytes (`SUBPIECE` with byte offset); `v[LO,WIDTH]` reads a *bit* slice; a
declared `BITSYM` reads its parent's slice. All are read-only forms — their
LHS duals are 6.1's bitrange assignment (legal) and the illegal truncation/
subpiece stores.

**C++ anchor:** `PcodeCompile::createBitRange`, the `SUBPIECE` action.

### 7.5 Address-of: `&v`, `&:N v`

**Meaning:** the *constant* address of varnode `v` (optionally sized `N`) —
a disassembly-time constant, not a runtime computation. Usable as an
expression and in `export`.

**C++ anchor:** `PcodeCompile::addressOf`.

### 7.6 User-op expression: `USEROPSYM(args…)`

As 6.10 with an output varnode — `CPUI_CALLOTHER` whose result feeds the
surrounding expression.

**C++ anchor:** `PcodeCompile::createUserOp`.

---

## 8. Constructor results: `export`

### 8.1 `export varnode ;` / `export *[space]:size lhs ;`

**Meaning:** sets the constructor's *result* — the value/location a parent
constructor sees when it uses this subtable as an operand. The starred form
exports a dereferenced location (dynamic address), making the operand an
addressable lvalue for the parent.

**Pcode-level meaning:** the result is a handle template (`HandleTpl`), not
an op: parents splice the handle wherever the operand appears, so an
exported register reads/writes that register in parent semantics.

**Subtle:** a body that emits no ops and exports nothing is recorded as a
NOP constructor (explicitly, so empty bodies are intentional). `unimpl`
(2.x) is different — it marks semantics as *unimplemented*, poisoning
instruction semantics rather than emitting nothing.

**C++ anchor:** `SleighCompile::setResultVarnode` / `setResultStarVarnode`;
`recordNop`.

---

## 9. Named sections (`<<name>>`) in bodies

**Meaning:** splits a body into the default section plus named sections;
named sections do not execute inline — they exist to be spliced elsewhere by
`crossbuild` (6.7).

**C++ anchor:** `SleighCompile::firstNamedSection` / `nextNamedSection` /
`finalNamedSection` / `standaloneSection`.*
