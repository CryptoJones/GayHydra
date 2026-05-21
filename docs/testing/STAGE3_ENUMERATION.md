# Rec 25 / Rec 26 Stage 3 — warning enumeration

*Addresses Sprint 9's Stage-3 ratchet under Rec 25 (`-Xlint`) and Rec 26
(ErrorProne).*

> **Status: enumeration phase landed; pre-clean + `-Werror` flip queued
> (2026-05-21).** Numbers in this file come from the master ubuntu
> Build-Ghidra log of run `26239059501` (the last green master run
> before PR #243's CycloneDX SBOM bump turned the tree red — see PR
> #245 hotfix). They reflect Stage-2 lint settings as merged in PR #240
> (`-Xlint:deprecation,unchecked,rawtypes,cast`) and PR #239 (ErrorProne
> `JavaUtilDate` + `JdkObsolete` at WARN).

## Total warning census

| Category | Count |
|---|---|
| `[removal]`   | 447 |
| `[deprecation]` | 227 |
| `[rawtypes]`  | 89  |
| `[unchecked]` | 59  |
| `[cast]`      | 19  |
| `[options]`   | 3   |
| **Total**     | **844** |

Note: javac's `-Xmaxwarns` defaults to 100 per compile; three
subprojects hit the cap, so their true counts are unknown without
re-running with `-Xmaxwarns 0`. That re-run is itself a Stage-3
prerequisite (own PR, one-line change).

## Per-subproject ≥50 (Stage-3 opt-out candidates)

These need pre-clean OR `ext.lintOpts = ['none']` opt-out before the
default flips to ERROR + `-Werror`:

| Subproject | Warnings | Capped? |
|---|---|---|
| Ghidra/Framework/Generic        | ≥100 | yes |
| Ghidra/Framework/Emulation      | ≥100 | yes |
| Ghidra/Features/Base            | ≥100 | yes |
| Ghidra/Framework/SoftwareModeling | 91 | no |
| Ghidra/Processors/AARCH64       | 63 | no |
| Ghidra/Processors/PIC           | 58 | no |

## Per-subproject 25–49 (clean-or-tag for ratchet)

These are small enough that the cheapest path is to actually fix them
before the `-Werror` flip:

| Subproject | Warnings |
|---|---|
| Ghidra/Features/VersionTracking | 44 |
| Ghidra/Framework/Docking        | 36 |
| Ghidra/Processors/Hexagon       | 31 |
| Ghidra/Framework/Project        | 26 |

Per-subproject counts <25 are not enumerated here — they live in the
master Build-Ghidra log and decay quickly. Re-derive them by grepping
`warning:` on the next green master run if needed.

## The `[removal]` lever

The largest single category, `[removal]` (447), is dominated by usage
of `@Deprecated(forRemoval = true)` classes — primarily `Vertex` /
`VertexSet` / `DirectedGraph` in `ghidra.util.graph` and the per-arch
`*EmulateInstructionStateModifier` siblings of the deprecated emulator.

Of the 80 source files emitting at least one `[removal]` warning, **29
are themselves already annotated `@Deprecated` at class level**. Their
combined warning count sums to roughly half of the 447 (top 5: AARCH64
modifier 63, `Emulate.java` 46, `VertexSet.java` 34, Hexagon modifier
31, `DirectedGraph.java` 30 — that's 204 just there).

Adding `@SuppressWarnings("removal")` to the 29 self-deprecated files
would drop the tree-wide total from ~844 → ~400 in a single mechanical
PR. The annotation is correct semantically — javac is warning a
deprecated class about its use of another deprecated class, which is
noise, not a real action item.

## Stage-3 PR sequence — landed in Sprint 10

1. **Bump `-Xmaxwarns` to 0** ([PR #249](https://github.com/CryptoJones/GayHydra/pull/249)) — uncapped the per-file ceiling. Surfaced the real tail behind the prior 100-cap.
2. **`@SuppressWarnings("removal")` on 29 self-deprecated files** ([PR #247](https://github.com/CryptoJones/GayHydra/pull/247)).
3. **Pre-clean every ≥5-warning subproject** ([PRs #261, #265, #267, #268, #269](https://github.com/CryptoJones/GayHydra)) — class-level `@SuppressWarnings({"deprecation","removal","rawtypes","unchecked"})` on each offender file, with a Sprint-10 marker comment. Don't fix the underlying API misuse (separate per-subproject migration work); just acknowledge it and clear the floor.
4. **javacc-generated source: inject `@SuppressWarnings("all")` post-codegen** (PRs [#265](https://github.com/CryptoJones/GayHydra/pull/265) + [#270](https://github.com/CryptoJones/GayHydra/pull/270)) — Features/Base's parser scaffolding can't be source-edited (regenerated each build), so a `doLast` on `buildJavacc` patches the generated `.java`. Skips files that already self-annotate (PreProcessor.java, CParserTokenManager.java).

### Status

After steps 1–4: tree-wide warning floor near zero across every pre-cleaned subproject. Local Mac Mini `gradle assembleAll` green in 6m31s.

### Deferred (bigger than originally scoped)

5. **`-Werror` on javac** — attempted on the Mac Mini in Sprint 10; the local build immediately surfaced an `EqualsGetClass` ErrorProne warning that `-Werror` promoted to an error. ErrorProne's `allErrorsAsWarnings = true` degrades its (large) default-on check set to javac warnings, which `-Werror` then promotes BACK to errors — a Catch-22. Flipping needs either a global ErrorProne reconfiguration (per-check overrides for everything default-on) or a per-file suppression sweep across the tree. Defer until those are addressed; it's its own sprint task, not a same-PR step.
6. **Promote ErrorProne checks WARN → ERROR** — Stage 1 set (`MissingOverride`, `MutableConstantField`, etc.) need their per-check floor at zero first; `JavaUtilDate` migration is its own sub-sprint per [`docs/testing/ERRORPRONE.md`](ERRORPRONE.md). Same deferral.

Per-subproject opt-out pattern (Rec 25 stays cleanly reversible):

```groovy
// build.gradle in the opted-out subproject
ext.lintOpts = ['none']
```

See [`gradle/javaProject.gradle`](../../gradle/javaProject.gradle) line
~56 for the resolver.

---

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
