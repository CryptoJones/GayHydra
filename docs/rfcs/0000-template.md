---
number: 0000
title: Short title
status: draft
author: <@github-handle>
created: YYYY-MM-DD
---

# RFC 0000: Short title

## Summary

One paragraph. The problem this RFC solves, in plain language. If a
reader stops after this paragraph, they should know whether the RFC is
about something they care about.

## Motivation

Concrete reasons. Issue links, user reports, audit findings, perf
numbers, CVE references. Not "it would be nice" — what currently fails
and how often.

## Detailed design

The body of the RFC. Enough that an implementor who is not the author
could begin work. Include:

- Type signatures, on-disk formats, wire protocols, where relevant.
- Diagrams (ASCII or images committed under `docs/rfcs/assets/`).
- Error modes, including what the user sees on each.
- Performance envelope (bounds, not promises).
- Backwards-compatibility story for in-flight data.

## Drawbacks

What we lose. Be specific. "Adds dependency on X", "increases binary
size by ~Y MB", "breaks Z plugin contract". A short list here is
healthier than a long defensive section in *Alternatives*.

## Alternatives

What else we considered. For each, one paragraph on what it would have
looked like and why it was rejected. "Do nothing" is a valid
alternative — if it's listed, explain why this RFC is preferred over
the status quo.

## Migration

How existing users/data move to the new world. Deprecation window
length. Tooling provided (one-shot migration script, dual-write phase,
read-old-write-new period, etc).

## Unresolved questions

Things this RFC does not answer and that must be answered before
acceptance. If everything is resolved, write "None known."

## Future possibilities

Out-of-scope follow-ups. This section exists so reviewers don't have
to ask "but what about X?" — the author has already noted X as
intentionally out of scope.

---

<!--
Author checklist (delete before opening):
- [ ] Number is the next free integer in docs/rfcs/.
- [ ] Slug in filename matches the title.
- [ ] Status is one of: draft, final-comment-period, accepted, rejected, superseded.
- [ ] Created date is set.
- [ ] Linked from at least one issue or PR.
-->
