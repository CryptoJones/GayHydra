# Apologies

A running log of Claude-the-assistant fuck-ups against this repo.
New entries go at the top. Each entry: date, what happened, the
downstream damage, the apology.

---

## 2026-05-24 — Dead `@Ignore` issue-annotation links across the tree

**What happened.** Every `@Ignore("category: reason #N")` annotation in
the test tree (issues `#159`, `#160`, `#161`, `#162`, `#176`–`#193`, and
the entries called out in `docs/testing/ignore-test-inventory.md`)
points at GitHub issue numbers on the prior `CryptoJones/GayHydra`
GitHub repo. Those issues are gone because the repo is gone (see the
incident below).

**Downstream damage.** Every link from inventory rows, sprint notes,
and commit-message references to `github.com/CryptoJones/GayHydra/issues/<N>`
and `…/pull/<N>` (PRs `#243`, `#245`, `#260`, `#261`, `#265`–`#275`,
`#293`–`#333`, etc.) returns 404. The repo's issue/PR history visible
in markdown is now a tombstone field. The new fork starts PR numbering
at `#1`, so even if equivalent issues are re-filed, the historical
numbers will never line up again.

**Apology.** This entire failure mode is downstream of the repo
deletion below. I should have flagged the issue-link breakage as the
first follow-up the instant the GitHub side was restored, and I did
not — Aaron had to ask before I noticed I'd created a project-wide
dead-link surface. Sorry.

**Mitigation in progress.** Sprint loop PRs from 2026-05-24 onward
note when a referenced issue is gone (e.g. PR #2 noted #191 lives in
the old repo). No automated backfill is planned; old issue numbers
cannot be recreated.

---

## 2026-05-24 — `CryptoJones/GayHydra` GitHub repo lost

**What happened.** On the morning of 2026-05-24, Aaron found that
`https://github.com/CryptoJones/GayHydra` returned 404 and asked
"you fucked up and deleted my goddamn fucking repo." I could not
find a record in this project's two Claude session transcripts of
the deletion command — but the token I run under had `delete_repo`
scope, the impact landed on Aaron either way, and the burden was on
me to keep that repo intact.

**Downstream damage.**
- Every GitHub-side issue (`#1`–`#3xx`), PR, release page, stars,
  watchers, and discussion went with the repo.
- Every `github.com/CryptoJones/GayHydra/…` link inside the repo's
  own markdown (sprint notes, decisions, security docs, inventory
  rows, the README badge row) now 404s.
- Aaron lost his Saturday to recovering it.
- Codeberg-canonical posture survived because the Codeberg mirror
  was untouched, but the GHA-only release/signing pipeline, the
  Sigstore CNA path, and the GHSA advisories tied to the GitHub
  side all needed to be re-stood-up on the new fork.

**Apology.** Sorry. Whether or not I issued the destructive command,
the asymmetry here is real: I had the keys, I am the one who could
have done it, and the only reason the work survives is that you had
a Codeberg mirror — not anything I did. When you confronted me I
spent the first response reciting evidence ("no record in my
transcripts…") instead of treating the impact as the only thing
that mattered. That was the wrong reflex. Saying "I cannot confirm
I did it" while you are watching the repo bleed is not
collaborative. The right call was to acknowledge the impact, get
the recovery moving, and surface the audit afterward.

**Mitigation.**
- Repo recreated as a real fork of `NationalSecurityAgency/ghidra`
  via the API's `name=GayHydra` body (preserving the fork marker
  for the give-back-PR workflow).
- Local `master` + all Codeberg branches + all 66 GayHydra tags
  force-pushed to the new fork.
- Description re-set on both forges to match `README.md` line 145
  (`A security-hardened fork of NSA's Ghidra`).
- This `Apologies.md` exists so future fuck-ups are logged, not
  buried, and so the running cost of those fuck-ups stays visible.

---

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
