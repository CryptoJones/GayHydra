# RFC Process

*Addresses Rec 06 of the 2026-05-21 principal-architect audit.*

## When you need an RFC

Some changes are too big to review line-by-line. Upstream #4103
(WebAssembly, +15,387 LOC, 4.2 years open) and #5778 (RISC-V vector/crypto,
+6,676 LOC, 2.7 years open) demonstrate the failure mode: a contributor
ships months of work, the diff cannot be evaluated against an unwritten
design, and the PR enters a slow death. That outcome is bad for everyone.

An RFC is required when the proposed change is **any** of:

- More than 2,000 net lines of code in one PR, **or**
- Adds a new top-level subsystem (new processor architecture, new file
  format loader, new analyzer module), **or**
- Modifies a cross-module interface (decompiler IPC, Sleigh runtime,
  database schema, plugin API), **or**
- Changes a security boundary (script sandbox, RMI filter, deserialization).

If you are uncertain, open a discussion-only RFC PR with the
`needs-rfc-decision` label; a maintainer will tell you within the
[Triage SLA](TRIAGE_SLA.md) whether an RFC is required.

## What an RFC contains

Use the template at `docs/rfcs/0000-template.md`. The shape:

1. **Summary.** One paragraph. What problem this RFC solves.
2. **Motivation.** Why this matters, with concrete user/issue links.
3. **Detailed design.** Enough that someone other than the author could
   implement it. Diagrams, type signatures, on-disk formats, error modes.
4. **Drawbacks.** What we lose by doing this.
5. **Alternatives.** What else we considered and why it was rejected.
6. **Migration.** How existing users/data are carried forward, including
   the deprecation window.
7. **Unresolved questions.** Things that must be answered before
   acceptance.
8. **Future possibilities.** Out-of-scope follow-ups.

## How an RFC merges

1. **Open as a PR** to `docs/rfcs/` with the next sequential number and
   a slug: `docs/rfcs/00NN-short-slug.md`.
2. **Status: `draft`** on first opening. The PR description includes
   the section list above; the body of the file is the RFC.
3. **Community comment.** The RFC sits in `draft` for at least 14 days.
   Maintainers and community members comment in the PR thread.
4. **Status: `final-comment-period`.** When the author and at least one
   maintainer believe the RFC is ready, the label moves and a 7-day
   final comment period begins.
5. **Merge** (`status: accepted`) or **close** (`status: rejected`).
   Either outcome is explained in the merge/close comment. Accepted
   RFCs are merged at the head of the file with `accepted: 2026-MM-DD`
   metadata. Rejected RFCs are still merged into `docs/rfcs/` so future
   authors can see what has been considered; they are marked
   `status: rejected` in the header.

## What unblocks implementation

An implementation PR may reference an accepted RFC and proceed. The
implementation does not need a second RFC for any work that is faithful
to the accepted design.

### Amendments to an accepted RFC

When implementation reveals that the accepted design is wrong, two
paths exist depending on the size of the change:

- **Minor amendment.** The implementation PR updates the RFC in the
  same change, the PR description names the divergence, and one core
  maintainer's review counts as RFC re-approval. Minor means: behaviour
  unchanged from the user's perspective; type signatures, error modes,
  performance bounds, and migration contract are all preserved.
- **Major amendment.** The change does not meet the "minor" bar. The
  amendment is its own RFC PR (`docs/rfcs/00NN-amendment-of-MMNN.md`)
  with its own 14-day draft + 7-day FCP. The original RFC is not
  silently rewritten — both the original and the amendment ship.

The point of distinguishing the two is that a contributor reading the
RFC three years from now should understand which decisions were the
original consensus and which were later corrections.

### Rejected RFCs

Rejected RFCs are merged into `docs/rfcs/` with `status: rejected`
and a one-paragraph rejection rationale appended at the bottom of
the file. They serve as prior art: a future contributor proposing the
same idea can read why the project decided against it and either
adjust the design or make the case that the situation has changed.

A rejected RFC is not a closed door forever — it is a closed door
*then*. If the rationale no longer holds (new libraries, new
benchmarks, new requirements), open a new RFC that cites the
rejected one and explains what changed.

## File-numbering rule

The number is the next free 4-digit integer in `docs/rfcs/`, padded
with leading zeroes (`0001`, `0023`, `0142`). The slug is
kebab-cased and matches the title. Filename: `00NN-short-slug.md`.

Numbers are not reused. If RFC 0007 is rejected and merged into
`docs/rfcs/`, the next RFC is 0008 — not 0007. A reader who reads
"RFC 0007" anywhere in the codebase should be able to find exactly
one file matching that number.

## RFC retirement

Accepted RFCs that have shipped are not deleted. They are moved into
`docs/rfcs/shipped/` with their original number and a brief
"implemented in PR #XXX" footer. RFCs that have been superseded are
moved into `docs/rfcs/superseded/`.

## Why we are doing this

We want contributors to ship months of work and have it land. The RFC
process front-loads the conversation onto the design, before code is
written, so that a contributor who has done the writing knows the
direction is wanted before they invest in the implementation. The
worst possible outcome is a 15k-line PR that sits because no one knows
whether to want it.
