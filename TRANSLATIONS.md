# Translations

ARMSX3 provides localization scaffolding for Android and the desktop Qt UI.
Both use the same list of 43 locales inherited from ARMSX2. The single source
of truth is `translations/languages.json`:

`af-ZA`, `ar-SA`, `az-AZ`, `bg-BG`, `ca-ES`, `cs-CZ`, `da-DK`, `de-DE`,
`el-GR`, `en`, `en-US`, `es-419`, `es-ES`, `fa-IR`, `fi-FI`, `fr-FR`,
`gn-PY`, `he-IL`, `hi-IN`, `hr-HR`, `hu-HU`, `id-ID`, `it-IT`, `ja-JP`,
`ka-GE`, `ko-KR`, `lt-LT`, `lv-LV`, `nl-NL`, `no-NO`, `pl-PL`, `pt-BR`,
`pt-PT`, `qu-PE`, `ro-RO`, `ru-RU`, `sr-SP`, `sv-SE`, `tr-TR`, `uk-UA`,
`vi-VN`, `zh-CN`, and `zh-TW`.

Catalogs that have not been translated use the English source text. This is
intentional: the locale and update workflow can be prepared before translators
start working. A missing translation must never be replaced by a machine
translation without review.

Locales whose JSON catalog is fully translated are marked with `"complete":
true` in `translations/languages.json`. The synchronization check requires those
catalogs to contain every canonical key from `I18n.kt`, rejects unknown keys,
and validates formatting placeholders. Partial catalogs may omit keys and use
the normal English fallback.

## Updating catalogs

Run the synchronizer from the repository root:

```sh
python3 tools/update_translations.py
```

Useful alternatives:

```sh
python3 tools/update_translations.py --android
python3 tools/update_translations.py --qt
python3 tools/update_translations.py --check
```

`--check` does not modify files and exits with a non-zero status when catalogs
are outdated or format placeholders do not match.

The default command still updates Android and the generated graphical picker
when Qt Linguist is not installed, and prints a warning before skipping `.ts`
and `.qm` generation. An explicit `--qt` treats missing Qt tools as an error.

## Android

English source strings are stored in:

- `android/armsx3-app/app/src/main/res/values/strings.xml`
- `android/armsx3-ui/app/src/main/res/values/strings.xml`

Translations live in the corresponding `values-<locale>/strings.xml`
directories. Edit the translated text but do not change its `name`. Keep format
arguments such as `%1$s`, `%2$d`, and line breaks such as `\n` intact.

The main Compose interface also provides an in-app live language picker. Its
catalogs are stored in `android/armsx3-ui/app/src/main/assets/i18n/<locale>.json`
and its generated locale metadata is in `GeneratedLanguages.kt`. To add a
language, add its metadata to `translations/languages.json` and run the update
script. It generates the Kotlin picker list, the Qt locale list, Android resource
directory and JSON catalog automatically. Missing JSON keys fall back to the
canonical English map embedded in `I18n.kt`, so partially translated languages
remain usable. Do not edit generated locale lists manually.

When adding UI text, put it in the module's English `strings.xml` first and
reference it from Kotlin, Java, Compose, or XML. Text written directly in code
or layouts cannot be synchronized or translated by this workflow.

The update script preserves existing translated entries, adds new English
entries, removes entries no longer present in the source, restores source order,
and validates format arguments.

## Qt desktop

The locale list used by the application is generated from
`translations/languages.json` into `bin/translations/languages.txt`. Editable catalogs are generated under
`translations/qt/` as `.ts` files. Runtime catalogs are generated under
`bin/translations/` as `.qm` files.

Install the Qt 6 Linguist tools before updating Qt catalogs. Depending on the
distribution, the package may be called `qt6-tools-dev-tools`, `qt6-linguist`,
or a similar name. The required commands are:

- `lupdate`, which extracts current source text and updates `.ts` catalogs.
- Qt Linguist, an optional graphical editor for translating `.ts` files.
- `lrelease`, which compiles `.ts` catalogs into runtime `.qm` files.

Do not edit `.qm` files directly. Edit the matching `.ts` file and run the
synchronizer again. When a `.qm` translation is unavailable, ARMSX3 retains the
selected locale and displays the original English source strings.
