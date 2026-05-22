# Translating GayHydra

Thanks for translating! GayHydra's localization is a fork-distinguishing investment — the upstream Ghidra has no l10n at all, and your work changes that.

This guide is for **translators**. If you're a code contributor adding new UI strings, see [`DEVELOPER-GUIDE.md`](DEVELOPER-GUIDE.md) instead.

## What's translatable today

The localization PoC currently covers the **Docking framework only** — roughly 135 UI strings backing labels, buttons, tooltips, and dialog titles. Everything else (Decompiler windows, processor-specific dialogs, help pages) is still English-only.

Decompiler output (the pseudo-C code) is **intentionally** English-only and will stay that way; translating reverse-engineering output complicates collaboration across language boundaries.

## How translations get into the repo

We use **Crowdin** ([crowdin.com](https://crowdin.com), free for open-source projects). The workflow:

1. The English source bundles live in the repo:
   - `Ghidra/Framework/Docking/src/main/resources/docking/messages.properties`
   - `Ghidra/Framework/Gui/src/main/resources/core/messages.properties`
2. Crowdin reads both files from the GitHub mirror via the Crowdin GitHub App.
3. You translate in the Crowdin web UI — no git knowledge required.
4. Crowdin batches approved translations into a "New translations from Crowdin" pull request against `master` on GitHub. A maintainer reviews and merges.
5. The Codeberg mirror picks up the same content via the project's `dual-remote-pr` reconcile step. You don't need a Codeberg account.

Target locales for the PoC: **Spanish (es)**, **Russian (ru)**, **Chinese Simplified (zh-CN)**, **Korean (ko)**. RTL languages (Arabic, Hebrew, Persian) are deferred — they need a proper RTL pass that the PoC doesn't budget for.

## License

GayHydra is **Apache-2.0**. By submitting a translation through Crowdin you are agreeing — per Crowdin's OSS Terms of Service — that your translation is licensed under the project's license. This means anyone can use, redistribute, and re-license-as-Apache-2.0 your translations.

If that doesn't work for you, don't submit translations.

## Key naming convention

You don't write keys — code contributors do — but it helps to recognize them:

```
docking.find_dialog.label.next         → "Next"
docking.find_dialog.label.previous     → "Previous"
core.button.ok                         → "OK"
core.dialog.error.title                → "Error"
```

- The first segment is the **module** (`docking`, `core`).
- The second is the **scope** — usually a class purpose (`find_dialog`, `password_dialog`).
- The third is the **element type** (`label`, `button`, `tooltip`, `title`).
- The fourth (optional) is the **literal slug**.

Keys are sorted alphabetically in the source bundle. Reordering or renaming a key is a code-contributor concern; translators should just translate the values.

## What to watch out for

### MessageFormat placeholders

Some strings contain `{0}`, `{1}`, etc. — these get substituted at runtime. **Don't translate the placeholder itself**, just keep it intact. Example:

```
Source:  Found {0} matches in {1}
Spanish: Se encontraron {0} coincidencias en {1}
Russian: Найдено {0} совпадений в {1}
```

The position of `{0}` and `{1}` can move to fit your language's word order; just don't rename them.

### Plurals

Strings with quantity-dependent forms use Java's `ChoiceFormat` syntax:

```
{0,choice,0#no results|1#1 result|1<{0} results}
```

This is a "0 → 'no results', 1 → '1 result', 2+ → '{0} results'" decision. For languages with more plural categories than English (Russian: one/few/many; Polish: similar), please flag the string in Crowdin — we'll either expand the `ChoiceFormat` or wire ICU MessageFormat in a follow-up.

### Length

UI strings rendered in fixed-width labels can clip if the translation is much longer. We use a pseudo-locale (`en-XA`) with ~40% length expansion to catch the worst offenders, but real translations sometimes still surprise us. If a string feels too tight, leave a comment in Crowdin and we'll widen the layout.

### Don't translate

- **Accessibility names** (`setAccessibleName(...)` strings) stay English by design — screen-reader testing infrastructure expects locale-stable identifiers.
- **Log messages and exception text** are out of scope for translation.
- **Decompiler output** is English-only by policy.
- **Identifiers / file paths / URLs** inside translatable strings stay as-is.

## Help, I don't know how to translate X

Crowdin has built-in comment threads on every string. Ask the original author or another translator. Other useful resources:

- [Ghidra's glossary on the wiki](https://github.com/NationalSecurityAgency/ghidra/wiki/Glossary) — upstream terminology.
- [The GayHydra plan that established this PoC](../../scripts/i18n-sweep.sh) — context on why each pattern was chosen.

If the translation is genuinely ambiguous, leaving the source string untranslated is better than guessing. Crowdin will mark it "needs translation" and we'll route it to a native reviewer.

## Recognition

Translators get credited in `MAINTAINERS.md` under a `## Translators` section once each language ships its first complete bundle. If you'd prefer to stay anonymous, just say so in Crowdin — we'll respect that.

---

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
