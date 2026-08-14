# ErrorProne Static Analysis

*Addresses Rec 26 of the 2026-05-21 principal-architect audit.*

> **Status: enabled (Sprint 8).** The `net.ltgt.errorprone` plugin is
> declared (`apply false`) in [`build.gradle`](../../build.gradle)'s
> `plugins {}` block and applied via
> `apply from: 'gradle/errorprone.gradle'`. The original Gradle 8.5
> timing bug — the `errorprone` dependency configuration not registering
> in time for the `dependencies { errorprone ... }` / `options.errorprone`
> references — is worked around by deferring the entire wiring inside
> `pluginManager.withPlugin('net.ltgt.errorprone') { ... }`, which runs
> only after the plugin has fully applied. That works on both Gradle 8.5
> (the version GH Actions installs via `setup-gradle@v4`) and 9.5+.
> Stage 3 shipped 2026-06-11: ErrorProne's native ERROR bugpatterns are
> now fatal tree-wide (see the Stage 3 note below).

## Why ErrorProne

The audit identified zero SpotBugs, ErrorProne, Checkstyle, or
Sonar configuration anywhere in the tree. Of the four,
**ErrorProne** is the cheapest first win:

- It's a **javac plugin** — no separate analysis pass, no separate
  build phase, no new CI infrastructure. Existing `compileJava`
  invocations pick it up.
- It catches a **known, named, documented** set of mistakes
  ([the bugpattern list](https://errorprone.info/bugpatterns)).
  Each finding links to a concrete page explaining the issue,
  the fix, and the suppression options.
- The plugin (`net.ltgt.errorprone`) is mature and widely deployed.

This rec adds ErrorProne in **signal-only** mode (every check at
WARNING, not ERROR) so the existing tree doesn't go red. The
ratchet to ERROR is documented below.

## What changed

- `gradle/errorprone.gradle` (new) — applies the ErrorProne plugin
  to every `java-library` subproject and configures a curated set
  of high-EV checks.
- `build.gradle` (one line) — `apply from: 'gradle/errorprone.gradle'`.

## Curated checks (Stage 1)

The Stage 1 set is the intersection of:

- **High EV** (catches real bugs, low false-positive rate).
- **Localized** (fixable per call site without architecture changes).
- **Documented well by ErrorProne** (linked bugpattern page).

| Check | What it catches |
|---|---|
| `ImmutableEnumChecker` | `enum` value with a mutable field. |
| `MissingOverride` | A method override without `@Override`; surface compile breakage on parent rename. |
| `MutableConstantField` | `static final` collection that turns out to be mutable. |
| `MutableMethodReturnType` | Declared `List`/`Set` return but actual returned type is the mutable impl. |
| `CollectionToArraySafeParameter` | `.toArray(T[])` with the wrong array type. |
| `DefaultCharset` | `String.getBytes()` without an explicit `Charset`; surfaces locale-dependent behaviour. |
| `EqualsHashCode` | Class that overrides one but not the other. |
| `EqualsIncompatibleType` | `equals()` comparing classes that can never be equal. |
| `StringSplitter` | `String.split(...)` using a regex that produces surprising splits. |

## Disabled by design (Stage 1)

| Check | Why deferred |
|---|---|
| `JavaUtilDate` | Hundreds of instances; non-mechanical migration to `java.time.*`. Stage 3. |
| `JdkObsolete` | Many `Hashtable` / `Vector` instances ship in API; can't change without breakage. Investigate per-call in Stage 4. |

The full ratchet:

| Stage | Severity | What lands |
|---|---|---|
| 1 (this PR) | WARNING for the curated set | tree is green, findings are visible |
| 2 | WARNING for `JavaUtilDate` etc | next batch of checks turned on |
| 3 | ERROR for the Stage 1 set | warnings become fatal once their floor is 0 |
| 4 | ERROR for everything | full enforcement |

Stage transitions are PR-gated, each with a recorded warning count
in `docs/testing/errorprone-ratchet-progress.md`.

> **Stage 3 shipped (2026-06-11), in dissolved form.** PR #271 deferred
> "step 6" over a `-Werror` Catch-22: `allErrorsAsWarnings = true`
> demotes ErrorProne errors to javac warnings, which `-Werror` would
> promote right back — together with every `-Xlint` warning. The probe
> that grounded the fix found the Catch-22 dissolves without `-Werror`:
> ErrorProne's **native ERROR severity already fails the build on its
> own**, and a tree-wide forced-recompile probe (124 main + 356
> test/integrationTest compile tasks) measured the default-ERROR backlog
> at **zero**. So `allErrorsAsWarnings` is now simply `false` by default
> (`-PerrorProneLenient` restores the demotion locally; CI never sets
> it). ErrorProne's ~400 default-ERROR bugpatterns are now fatal
> tree-wide. The `-Xlint` *warning* categories stay non-fatal — their
> ratchet is [XLINT_RATCHET.md](XLINT_RATCHET.md)'s separate floor-count
> story, untouched by this flip.

## Suppression policy

When a finding is a true positive but the fix is out of scope for
the current PR, suppress with a one-line `@SuppressWarnings`
naming the bugpattern:

```java
@SuppressWarnings("MutableMethodReturnType")  // tracking: #NNNN
public List<Foo> getFoos() { ... }
```

Suppressions without a tracking-issue link are rejected by code
review (same rule as the [`-Xlint` opt-out](XLINT_RATCHET.md)).

## Composition with -Xlint and SBOM

| Tool | Scope | Stage |
|---|---|---|
| `-Xlint:deprecation,unchecked` (Rec 25) | javac-builtin warnings | Stage 1 in progress |
| ErrorProne (this rec) | pattern-driven static analysis | Stage 1 (this PR) |
| SBOM CycloneDX (Rec 21) | dependency provenance | shipped |

`-Xlint` is the floor; ErrorProne is the next layer. SpotBugs and
Sonar would be additional layers, considered after ErrorProne has
been at Stage 3+ for a release cycle.

## Local check

ErrorProne runs every time `compileJava` runs:

```
gradle :Ghidra:Features:Base:compileJava 2>&1 | grep -A2 '\[ErrorProne\]'
```

Or surface a per-check count:

```
gradle build 2>&1 | grep -oE '\[\w+\]' | sort | uniq -c | sort -rn
```

## Maintenance commitment

- Pin the plugin version (`4.0.1`) at the top of
  `gradle/errorprone.gradle`. Version bumps are their own PR.
- Pin the core version (`2.27.0`) similarly.
- New `subprojects { ... }` patterns that add a `java-library`
  module automatically pick up ErrorProne; no per-module config
  needed.
- The CI build is the canonical run; local dev IDEs may or may
  not surface findings depending on Eclipse/IntelliJ configuration.
