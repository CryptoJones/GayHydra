# i18n developer guide

For code contributors. If you're translating UI strings, see [`CONTRIBUTING-TRANSLATIONS.md`](CONTRIBUTING-TRANSLATIONS.md) instead.

## The one rule

> **Don't hardcode UI strings in Docking-framework code.** Use `I18n.tr("docking.<scope>.<element>.<slug>")` and add the key to `Ghidra/Framework/Docking/src/main/resources/docking/messages.properties`.

`:Docking:i18nLint` enforces this on every push. If you forget, CI catches you.

## When the rule applies

The PoC currently covers the **Docking framework only**. Other modules — Project, Features/Base, Features/Decompiler — are still all-English; don't worry about i18n there yet. Each module gets opted in by:

1. Adding `<module>/src/main/resources/<module>/messages.properties`.
2. Running `scripts/i18n-sweep.sh <module-src-root>` to produce candidate TSV.
3. Hand-reviewing the TSV (keys mostly auto-generate sensibly; fix collisions and bad naming).
4. Running `scripts/i18n-apply.py --tsv reviewed.tsv --bundle <module>/.../messages.properties`.
5. Copying `i18n-lint.gradle` into the module and wiring `apply from: "i18n-lint.gradle"` in its `build.gradle`.
6. Adding the lint step for the new module to `.github/workflows/build-ghidra.yml`.

Decompiler **output** (the pseudo-C code printed from `printc.cc`) is explicitly **not** localized — it's source-code-like and stays English regardless of UI locale.

## How to use `I18n.tr`

```java
import generic.i18n.I18n;

// Simple lookup:
JButton ok = new JButton(I18n.tr("docking.find_dialog.button.next"));

// With MessageFormat arguments:
String msg = I18n.tr("docking.search.results.found", count, searchTerm);

// Repeated lookups on a hot path: cache the bundle:
ResourceBundle b = I18n.bundle("docking");
b.getString("docking.find_dialog.button.next");
```

If the key is missing, `tr()` returns `???docking.find_dialog.button.next???` rather than throwing. The placeholder makes the missing key obvious in screenshots and bug reports.

## Key naming

Convention:

```
<module>.<scope>.<element>[.<slug>]
docking.action.close.label
docking.action.close.tooltip
docking.find_dialog.button.find_all
docking.password_change_dialog.label.new_password
core.button.ok
core.dialog.error.title
```

- `<module>` — `docking` for Docking, `core` for the shared bundle that fronts every module.
- `<scope>` — class-purpose-derived: `find_dialog`, `action`, `menu`. **Not the filename.** Renames don't churn keys.
- `<element>` — `label`, `button`, `tooltip`, `title`, `body`, `menu`.
- `<slug>` — lowercased literal, non-alphanumerics → underscore, capped ~40 chars. Disambiguates when one class has many `label`s.

If you find yourself inventing a fifth segment, the second segment is probably wrong — refactor the scope.

## When to use the `core.*` bundle

Use `core.button.ok`, `core.button.cancel`, `core.button.apply`, etc. for strings that appear in literally every dialog. Translating "Cancel" 200 times across modules is what `core.*` exists to prevent. The Crowdin project pre-translates `core.*` once and every consumer benefits.

If your candidate string isn't already in `core/messages.properties`, prefer **adding it to the module's bundle** for now. Promote to `core` only when a second module starts using the same key.

## The pseudo-locale

`-Dghidra.locale=en-XA` swaps every string in for a Latin-diacritic version wrapped in `[!! ... !!]`. `Close` becomes `[!! Çłöşé !!]`. Any unbracketed text under that locale is a literal that escaped the sweep — file a bug.

Regenerate the pseudo bundles after editing a source bundle:

```
./gradlew :Gui:generatePseudoLocale       # core.*
./gradlew :Docking:generatePseudoLocale   # docking.*
```

The pseudo bundles are committed for now (visual-QA convenience). PR 4's docs include a TODO to move them to a build-only artifact.

## What NOT to wrap in `I18n.tr`

- **`setAccessibleName(...)` strings.** Screen-reader testing expects locale-stable identifiers.
- **Log messages.** `Msg.error(...)` / `logger.info(...)` / `throw new IOException("...")` — these are diagnostic, not UI, and they end up in bug reports that maintainers read in English.
- **Identifier-like strings.** Class names, file extensions, button names that are also action IDs.
- **Decompiler output.** Pseudo-C stays English.
- **Test strings.** Tests assert against the English source bundle; localizing tests makes them locale-dependent.

When in doubt, leave it English. The i18n lint will flag patterns that look UI-shaped; if it false-positives, drop a `// i18n-allow` comment on the line and the lint will skip it.

## String-concat patterns

`tr()` accepts MessageFormat args:

```java
// Wrong (breaks under Spanish/Russian word order):
"Found " + count + " matches in " + scope

// Right:
I18n.tr("docking.search.results.found", count, scope)
// docking.search.results.found=Found {0} matches in {1}
```

For quantity-dependent text, use `ChoiceFormat` in the source value:

```
docking.search.results.count={0,choice,0#no results|1#1 result|1<{0} results}
```

ICU MessageFormat (which handles Russian's one/few/many plurals natively) is out of scope for the PoC; if you hit a string that genuinely needs it, leave a `TODO` and we'll wire it in a follow-up.

## What `:Docking:i18nLint` catches

```
:Docking:i18nLint — 1 hardcoded UI literal(s) found.
  Rewrite each as I18n.tr("docking.<scope>.<element>.<slug>")
  and add the key to src/main/resources/docking/messages.properties.
  See docs/i18n/DEVELOPER-GUIDE.md.
  Hits:
    Ghidra/Framework/Docking/src/main/java/docking/widgets/FindDialog.java:140 (constructor): new JLabel("My New Label")
```

The lint runs as part of `:check` (so `./gradlew check` exercises it) and in CI via `.github/workflows/build-ghidra.yml`.

To bypass for a legitimate non-translatable literal, append `// i18n-allow` to the line:

```java
private static final String INTERNAL = "GhidraTool";  // i18n-allow: action ID, not UI
```

Don't be cute with the escape hatch.

## Adding new locales

The PoC ships **en**, **es**, **ru**, **zh-CN**, **ko**. To add a new one:

1. Add it to Crowdin's project target locales (Aaron does this in the Crowdin web UI).
2. Translators do the work.
3. Crowdin's bot opens a "New translations from Crowdin" PR with `messages_<locale>.properties` files.
4. After merge, the `LocalePlugin` combo automatically picks up the new locale (it classpath-scans `messages_*.properties`).

No code change is required to add a locale.

---

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
