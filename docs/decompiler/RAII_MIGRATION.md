# RAII / Smart-Pointer Migration

*Addresses Rec 31 of the 2026-05-21 principal-architect audit.*

## The state we are starting from

The audit measured the decompiler's C++ at:

- **~2,740 raw `new`/`delete` allocation sites** across **187k LOC**.
- **One** `unique_ptr` import in the entire tree.
- Active history of use-after-free bugs (audit cites
  `GP-37838c180a` as a use-after-free in the Sleigh decompiler
  backend).

C++11 has shipped `std::unique_ptr` since 2011. The decompiler has
not adopted it. The cost is not stylistic; it is exception-safety
and use-after-free risk against malformed input that the
[OSS-Fuzz harnesses](../security/OSS_FUZZ.md) (Rec 13) are about
to start sending in volume.

## The decision

Migrate the decompiler's heap-allocated ownership from raw
`new`/`delete` to RAII handles — primarily `std::unique_ptr`, with
`std::shared_ptr` where ownership is genuinely shared. Migrate
incrementally, with a freeze on raw `new` at the boundary the
migration has crossed.

This is a months-to-years project. The doc below is the plan, not
the migration.

## Why not "rewrite in Rust" or "rewrite in [other]"

A rewrite of 187k LOC of decompiler analysis code is not a
proposal that lands in any visible timeline. The migration has
to be incremental, has to land in small reviewable PRs, and has
to leave the test corpus green at every step. RAII satisfies
those constraints; a rewrite does not.

A future Rust-frontend hosted on top of a schema-typed IPC (Rec
34) is a separate, parallel possibility. It does not block this
work.

## Plan: who-owns-what audit

Step zero is a *who-owns-what audit* of the existing
allocations. Group each `new` by lifetime:

- **Owned by one** — the allocating function/object exclusively
  owns the object and is responsible for `delete`. → `unique_ptr`.
- **Owned by container** — owned by a `vector<T*>` /
  `map<K, T*>` etc.; container destruction must `delete` the
  pointees. → `vector<unique_ptr<T>>` etc.
- **Shared by N** — the object is jointly owned and freed when
  the last reference goes away. → `shared_ptr`. **Verify this
  before assuming it.** Almost everything in the decompiler is
  actually "owned by one"; "shared" is rare in the codebase, but
  pretending shared ownership where it doesn't exist creates
  reference cycles.
- **Owned by no one (raw pointer)** — caller borrows a non-owning
  view of someone else's allocation. → `T*` or `T&`; the
  challenge is documenting which.

The audit lives in `docs/decompiler/raii-audit.csv`; it is a
table of `file:function`, allocation category, target handle
type, and target PR. The audit is produced by a one-time pass
(separate sub-issue) and is the input to the migration.

## Subsystem migration order

The decompiler's directory structure roughly maps to subsystems
of decreasing coupling. Migration order is bottom-up so each
PR's churn is reviewable:

| Stage | Subsystem | Files (approx.) | Rationale |
|---|---|---|---|
| 1 | `address.cc`, `space.cc`, `range.cc` | 10 | Foundational types; few callers; high-fanout downstream. |
| 2 | `marshal.cc`, `xml.cc` | 8 | Parsers — already in the OSS-Fuzz path; RAII closes the bulk of UAF risk. |
| 3 | `database.cc`, `comment.cc`, `cover.cc` | 20 | Symbol/comment storage; bounded surface. |
| 4 | `type.cc`, `userop.cc` | 30 | Type system — large but mostly local ownership. |
| 5 | `op.cc`, `varnode.cc`, `pcoderaw.cc` | 40 | PCode core — most-touched code; preserved for after we have miles under the belt. |
| 6 | `funcdata.cc`, `flow.cc`, `cast.cc`, `action.cc` | 60 | Analysis passes; most ownership knots. |
| 7 | `sleigh*.cc`, `slgh_*.cc` | 50 | Sleigh runtime; isolated subsystem; can be parallel to Stage 6. |
| 8 | The remaining ~50 files | 50 | Mop-up. |

Stages 1–4 cover the parser surface and the most attacker-
reachable code. Once those land, the OSS-Fuzz crash rate drops
visibly.

## Per-stage acceptance gate

A stage is "done" when **all** of these hold:

1. No raw `new` remains in the stage's files (audited by a
   `grep -nE '\\bnew\\b'` lint).
2. No raw `delete` remains in the stage's files.
3. The C++ unit tests + the data-driven tests pass on Linux,
   macOS (Rec 24).
4. The OSS-Fuzz ASan/UBSan run is green (Rec 13 + Rec 15).
5. A walked-the-tree review certifies no `T*` parameter is
   silently "consume the pointer" semantics.

A stage that lands without (5) is not done; it has just moved the
ownership story without making it visible.

## Migration patterns

The migration is mostly mechanical:

```cpp
// Before
Foo *f = new Foo(args);
... use f ...
delete f;

// After
auto f = std::make_unique<Foo>(args);
... use *f or f-> ...
// destructor runs at scope exit; no delete needed
```

```cpp
// Before
class Owner {
    Foo *child;
public:
    Owner() : child(new Foo()) { }
    ~Owner() { delete child; }
};

// After
class Owner {
    std::unique_ptr<Foo> child;
public:
    Owner() : child(std::make_unique<Foo>()) { }
    // destructor synthesised
};
```

The non-mechanical cases:

- **Pointer-returning factory functions.** `Foo *makeFoo()`
  becomes `std::unique_ptr<Foo> makeFoo()`. Callers update.
- **Container of pointers.** `vector<Foo*>` becomes
  `vector<unique_ptr<Foo>>`; access changes from `vec[i]` to
  `vec[i].get()` where a raw pointer is needed.
- **Cycles.** A `shared_ptr` cycle leaks. If two objects need
  to point at each other, use `weak_ptr` on one side; the audit
  flags every candidate cycle for explicit review.

## What's out of scope

- **Replacing `new` in test code.** Tests get their own pass,
  later.
- **Adding move-semantics to existing classes.** Mechanical
  RAII migration first; rule-of-zero / rule-of-five cleanup is
  a follow-up.
- **Changing public APIs the Java side calls.** The C++↔Java
  boundary is byte-framed; RAII inside C++ doesn't affect the
  byte protocol. Rec 33 and Rec 34 address the protocol itself.

## Coordination with Rec 32 (C++20)

The migration is C++11-compatible (uses `unique_ptr`,
`shared_ptr`, `make_unique` which is C++14 but trivially
portable). C++20 (Rec 32) opens additional handle types
(`std::span` for non-owning ranges, `std::expected` for
error-returning functions) that can be picked up in a second
sweep. The two rec PRs are coordinated but not blocking — RAII
can land at C++11 and C++20 later.

## Sequencing

| PR | Scope |
|---|---|
| #31-1 (this PR) | This plan |
| #31-2 | Stage 1 — `address.cc`, `space.cc`, `range.cc` |
| #31-3 | Stage 2 — `marshal.cc`, `xml.cc` |
| #31-4 | Stage 3 — `database.cc`, `comment.cc`, `cover.cc` |
| #31-5 | Stage 4 — `type.cc`, `userop.cc` |
| #31-6 | Stage 5 — pcode core |
| #31-7 | Stage 6 — analysis passes |
| #31-8 | Stage 7 — Sleigh runtime (parallel to #31-7) |
| #31-9 | Stage 8 — mop-up |
| #31-10 | CI lint enforcing "no raw new in cpp/" |

Each stage is reviewed by at least one decompiler maintainer
(see [MAINTAINERS.md](../../MAINTAINERS.md)).

## Risk: regression

Each `new` -> `unique_ptr` rewrite is a semantic change. The
risk is in the small number of sites where ownership was *not*
exclusive and the audit missed the share. Mitigations:

- The audit is reviewed before any migration PR lands.
- Each stage's PR ships with the C++ unit tests AND the XML
  data-driven tests passing — these together exercise the
  bulk of the decompilation pipeline.
- The OSS-Fuzz ASan run catches use-after-free regressions
  before they ship to users.
- Stage-by-stage progression means a regression's blast radius
  is one stage, not the whole codebase.

## Why this is worth the cost

The audit cited "use-after-free in Sleigh decompiler backend"
already in the commit history. The fuzz infrastructure landing
in Rec 13/14 will find more. Each finding under RAII is
typically a one-line fix (the lifetime is now visible from the
handle type); each finding under the current raw-pointer scheme
takes a senior engineer days to reproduce and fix safely. RAII
moves the cost from incident-response to compile-time.
