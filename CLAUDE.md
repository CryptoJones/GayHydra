# GayHydra — project rules for Claude

## Versioning: bump the patch on every push that runs CI (REQUIRED)

`Ghidra/application.properties` holds `application.version=X.Y.n`. **Before every
push to `master` that triggers a CI build, increment the patch number `n` by one**
(e.g. `26.3.0` → `26.3.1` → `26.3.2`). The bump rides in the same push — fold it
into the commit, or add a trailing `chore: bump to X.Y.n` commit, but never push
work-bearing commits to `master` at a stale version.

Why: every CI run and build artifact must carry a unique version. A push that
reuses the previous version produces an artifact indistinguishable from the prior
build, which defeats traceability (which commit produced which decompiler binary?)
and breaks "did my change actually ship" checks.

- Minor (`Y`) / major (`X`) bumps stay reserved for sprint-close `chore(release):`
  commits, as the git history shows. The per-push rule only moves `n`.
- `application.upstream.version` and `application.layout.version` are independent —
  do not touch them for a routine patch bump.

## Build / CI conventions (learned, load-bearing)

- **Validate builds in the podman rig**, not on the host. Image `ghbuild7`
  (ubuntu24 / JDK21 / gradle8.5 / clang / g++ / aarch64-cross / xvfb); mount the
  repo at `/work` and a gradle cache volume. The host has no JDK/gradle for this.
- **Delete stray `${sys:*}` files before `buildGhidra`**: `find . -name '${sys:*}'
  -delete`. A log4j sink occasionally writes `${sys:logFilename}`-named files that
  trip the distribution assembly.
- **Nightly `deep-ci.yml`** runs what per-PR CI does not: the fork `test.slow`
  suites (Rec 37 recognition/driver, Rec 30 harness, Rec 38 populators), fuzz
  smoke, master decompiler-smoke, and the **hint-recall corpus baseline**
  (`scripts/hint-recall.sh` vs `samples/hint-recall-corpus/baseline.json`). The
  corpus baseline is the tripwire for silent Rec 37 recall collapse — regenerate it
  with `--write-baseline` only when a *deliberate* recall gain is being locked in.

## Upstream merges

Governed by `docs/upstream-tracking/MERGE_POLICY.md` (operating model, divergence
stages, STOP conditions, sync log). As of 2026-06-12 the fork is at **Stage 2
(cherry-pick)** — pull specific upstream commits, not whole releases.
