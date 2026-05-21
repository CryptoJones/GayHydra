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

## Suggested Stage-3 PR sequence

1. **Bump `-Xmaxwarns` to 0** (one-line javaProject.gradle edit) so the
   three capped subprojects expose their real numbers on the next green
   master run.
2. **`@SuppressWarnings("removal")` on the 29 self-deprecated files**
   (one mechanical PR). Cuts `[removal]` roughly in half. After this
   lands, redo the per-subproject census.
3. **Pre-clean the ≥50 set**, ideally one PR per subproject so each
   reviewer can stay narrow. Order suggested: smallest first (PIC, then
   AARCH64, then SoftwareModeling, then the three capped giants).
4. **`ext.lintOpts = ['none']` opt-out** on any subproject still ≥50
   after step 3 — preserves the per-subproject backlog explicitly
   instead of leaving the tree red.
5. **Flip the default to ERROR + add `-Werror` to javac** in
   `gradle/javaProject.gradle`. The opt-out list from step 4 keeps the
   tree green while the remaining subprojects ship gates.
6. **Promote ErrorProne checks from WARN → ERROR** in
   `gradle/errorprone.gradle` (currently `JavaUtilDate` + `JdkObsolete`
   at WARN per PR #239). Final Stage-3 milestone.

Per-subproject opt-out pattern (Rec 25 stays cleanly reversible):

```groovy
// build.gradle in the opted-out subproject
ext.lintOpts = ['none']
```

See [`gradle/javaProject.gradle`](../../gradle/javaProject.gradle) line
~56 for the resolver.

---

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
