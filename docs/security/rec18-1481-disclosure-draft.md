# Rec 18 #18-2 coordination message for NSA/ghidra#1481

**Status: POSTED 2026-06-12** —
[NSA/ghidra#1481 comment](https://github.com/NationalSecurityAgency/ghidra/issues/1481#issuecomment-4690019283).
The text below is what was posted; the Tier-5 `ItemDeserializer` hardening
implementation follows when the thread allows (the comment is forward-looking
coordination, not a claim the fix has landed). Original draft note retained:

**Was: DRAFT, Aaron-attended.** Sprint 15 / implementation-order Tier 2
item 8 says: open the disclosure thread *now* (external latency dominates),
land the hardening code later (Tier 5) when the thread allows. This is the
draft to review and post as a comment on
[NSA/ghidra#1481](https://github.com/NationalSecurityAgency/ghidra/issues/1481)
(the issue is already public, so this is fix-coordination, not embargoed
disclosure — no GHSA channel needed for the conversation itself).

Once posted, record the date here and move the Tier 5 implementation item to
"awaiting upstream response / N-week courtesy window".

---

## Proposed comment text

> Hi — we maintain GayHydra, a downstream fork of Ghidra, and we're about to
> land a fix for this issue in our tree. Posting here first in case the
> maintainers want to coordinate or would take it as a PR.
>
> Shape of the fix (full design review in our tree at
> `docs/security/datatype-archive-deserialization-review.md`):
>
> 1. **Declared-size precheck** in `ItemDeserializer.deserialize` — the
>    archive header's declared output length is validated against a
>    configurable cap before any bytes are inflated (default cap generous
>    enough for real archives, low enough to stop amplification).
> 2. **Running decompressed-byte counter** during inflation — enforced
>    against the same cap, so a header that lies is caught mid-stream rather
>    than after the disk fills (CWE-409).
> 3. **Cleanup on failure** — the partial temp file is deleted on any abort,
>    closing the "permanent disk consumption" half of the report.
>
> We'd be happy to submit this as a PR against upstream master if there's
> interest — or if you have a preferred cap/option shape (e.g. a
> `pdb.cache.max` style property vs. a hard constant), we'll match it so the
> fork and upstream don't drift.
>
> Timing: we plan to land the fork-side fix within the next few releases
> regardless; nothing here is embargo-sensitive since the issue and CWE are
> already public in this thread.

---

## Notes for Aaron before sending

- The audit doc pins CVSS ≈ 5.3 (Medium), `AV:L/AC:L/PR:N/UI:R/S:U/C:N/I:N/A:H`
  — fine to mention if asked, not needed in the opener.
- The give-back posture matches `docs/upstream-tracking/give-back-candidates.md`
  Tier 1 (bundled upstream submissions); if upstream says yes, the PR should
  ride the next give-back batch.
- If no response in ~30 days, Tier 5 proceeds fork-side and this draft's
  thread is simply updated when the fix lands (courtesy, not a blocker).
