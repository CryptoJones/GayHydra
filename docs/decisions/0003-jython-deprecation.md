---
number: 0003
title: Jython deprecation and removal
status: accepted
date: 2026-05-21
audit_rec: 42
---

# Decision 0003: Jython is deprecated; removed on 2027-01-31

## Context

Ghidra ships two Python paths:

- **Jython 2.x** — an in-tree extension at
  `Ghidra/Features/Jython/` providing Python 2.7 script support
  via the JVM-hosted Jython runtime. Jython's upstream has been
  effectively abandoned since 2020; Python 2 itself reached
  end-of-life in 2020.
- **PyGhidra** — a native CPython 3 extension that hosts Ghidra
  through Python (rather than the other way around). PyGhidra is
  the actively-maintained path, supports Python 3.9–3.14, and
  is the recommended way to script Ghidra.

The audit identified the two-Python-paths situation as a
maintenance liability:

- **Two paths is worse than one.** A user reading two sets of
  examples cannot tell which path is canonical without
  cross-referencing.
- **PyGhidra's test coverage is thin partly because Jython
  shares the mind-share.** The recurring Debugger PR cluster
  (#8978 and peers) is partly Python-stack churn from
  maintaining both.
- **Jython is shipping a Python-2-era runtime as the user's
  default Python.** Any user importing a CPython 3-only library
  from a Jython script discovers the mismatch only at import
  time.

Removing Jython is the right call. The question is when.

## Decision

**Jython is deprecated as of 2026-05-21 (today). The
`Ghidra/Features/Jython/` extension will be removed from the
tree on 2027-01-31, eight months from now.**

The eight-month window is intentional:

- One full release cycle so the deprecation notice is in users'
  hands for an actual release, not just a master commit.
- Time for downstream projects (Ghidra extensions that bundle
  Jython scripts) to migrate to PyGhidra.
- Time for the Ghidra-script community to update their guides
  and tutorials.
- Time for any pre-PyGhidra scripts in the wild to be ported.

## What happens in the deprecation window

**Now (2026-05-21):**

- A deprecation banner appears at Ghidra startup the first time
  a Jython script runs in a session: "Jython will be removed on
  2027-01-31. See docs/decisions/0003-jython-deprecation.md for
  the migration guide."
- The `Ghidra/Features/Jython/` README documents the migration.
- All in-tree examples that ship Jython scripts get a PyGhidra
  equivalent in the same directory; the Jython example points
  at the PyGhidra one with a "this is the preferred path" note.
- No new Jython-only features land.

**2026-09-30 (four months in):**

- The Jython extension is moved from "default-on" to "default-off".
  Users who want Jython can re-enable via
  `support/launch.properties` (`VMARGS=-Dghidra.jython.enabled=true`).
- The deprecation banner becomes a hard log line every time a
  Jython script runs, not just first-use.

**2027-01-31 (removal):**

- `Ghidra/Features/Jython/` is deleted from the tree.
- Jython-related code in launchers, classloader, script provider
  is removed.
- The release notes for the release containing the removal
  prominently mention it.

## Migration guide

Most Jython 2 scripts port to PyGhidra 3 with mechanical changes:

| Jython 2 | PyGhidra |
|---|---|
| `print "hello"` | `print("hello")` |
| `from ghidra.app.script import GhidraScript` | `from ghidra.app.script import GhidraScript` (same import works) |
| `currentProgram.getName()` | `currentProgram.getName()` (Java API identical) |
| `xrange(10)` | `range(10)` |
| `dict.iteritems()` | `dict.items()` |

The non-mechanical case: Jython scripts that relied on Java↔Python
type quirks (e.g., implicit `long` ↔ `int` conversion) need a
once-over. The PyGhidra docs cover this; in practice the cases
are rare.

A **migration helper script** lives at
`Ghidra/Features/PyGhidra/scripts/jython_to_pyghidra.py`. It
runs `2to3` over a Jython script directory and surfaces remaining
manual edits.

## What we are NOT removing

- **Java GhidraScript.** Java-based scripting is unchanged. This
  decision is about Python only.
- **The headless mode.** Headless analyzeHeadless supports Java
  and PyGhidra; that path stays.
- **Pre-existing user scripts.** Users keep their Jython script
  collections; they just need to port them to PyGhidra to keep
  running them on the post-2027-01-31 releases.

## Communication

- **2026-05-21:** This decision merges; deprecation banner ships
  in the next release.
- **2026-05-22:** Release-notes draft prepared for the
  next release calling out the deprecation in the "Important"
  section.
- **2026-09-30:** Default-off; banner upgraded to hard log line.
- **2026-12-01:** Two-month-warning banner: "Jython will be removed
  on 2027-01-31. Migrate now."
- **2027-01-31:** Removal commit lands on master.

## Risks

- **A widely-used third-party Ghidra extension still depends on
  Jython.** Mitigation: discoverable migration guide; the
  PyGhidra team offers to help port well-known extensions
  during the deprecation window.
- **A user discovers a PyGhidra bug that blocks their port.**
  Mitigation: PyGhidra bugs filed during the window are
  prioritised. The window length is intentional.

## Reversal

This decision is *not* reversible after 2027-01-31 (the code is
deleted). It *is* reversible before then: a maintainer who
wants to extend the window opens a PR amending this decision
with a new date.

If the deprecation reveals an unexpected user impact, the
amendment path is the right escape valve. The default is the
deletion lands on schedule.

## Linked

- Upstream PyGhidra path: `Ghidra/Features/PyGhidra/README.md`.
- The audit's note on "the test suite for PyGhidra is
  correspondingly thin (recurring Debugger PR cluster — #8978
  etc — is partly Python-stack churn)."
