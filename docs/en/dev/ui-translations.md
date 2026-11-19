# UI Translations

The Admin, Browse and login pages are written in English. `login/assets/ui-i18n.js` (served from
`admin-ui`) translates them in place: it walks the DOM, replaces text nodes and the `aria-label`,
`placeholder` and `title` attributes, and watches later changes with a `MutationObserver`. Elements
inside `[data-i18n-skip]` are left untouched.

## Languages

| Code | Language | Dictionary |
| --- | --- | --- |
| `en` | English | source text, no dictionary |
| `zh-CN` | Simplified Chinese | inline in `ui-i18n.js` (`zh`) |
| `fr`, `de`, `es`, `it`, `pt-BR`, `nl`, `pl`, `ru`, `uk`, `ja`, `ko`, `tr` | French, German, Spanish, Italian, Brazilian Portuguese, Dutch, Polish, Russian, Ukrainian, Japanese, Korean, Turkish | `login/assets/i18n/<code>.js`, loaded on demand |

Administrators choose the default language under **UI settings**. `browser` follows the browser
preferences: an exact match first, then the primary language (`fr-CA` → `fr`, `pt-PT` → `pt-BR`,
`zh-TW` → `zh-CN`), and English otherwise. Only the dictionary of the active language is downloaded.

The dictionaries other than Chinese were machine translated. Corrections are welcome: edit the
value in the language file and keep the English key unchanged.

## How a string is translated

1. Exact match of the whitespace-normalised text in the active dictionary.
2. Leading symbols (`✓ Saved`) are kept and the rest is translated.
3. Error prefixes listed in `prefixTranslations` (`Save failed: <detail>`) use the `{label}: {detail}`
   template of the language for the separator.
4. Dynamic strings go through `PATTERNS`: each entry has the original regular expression and an
   English template (`Page {1} / {2}`). Dictionaries translate the template; `{1}`, `{2}`… are the
   captured groups, and groups listed in `translate` are translated first.

Strings without a translation stay in English.

## Adding or changing a string

Add the English text as a key to `zh` in `ui-i18n.js` and to every `i18n/<code>.js` file. For a new
dynamic string, add a `PATTERNS` entry plus the template to every dictionary. The admin-ui test
`ui-i18n-languages.test.js` (run by `UiI18nLanguagesJavascriptTest`) fails when a dictionary misses
a key of the Chinese reference or changes its placeholders.

## Adding a language

1. Add the code to `LANGUAGES` in `ui-i18n.js` and to `UiSettingsDao.LANGUAGES`.
2. Add a Flyway migration (MySQL and PostgreSQL) that recreates the
   `ck_ui_settings_default_language` constraint with the new code.
3. Create `login/assets/i18n/<code>.js` calling `window.kkrepoI18n?.registerDictionary("<code>", {...})`
   with every key, and add the language under its own name to the `#ui-default-language` select of
   the Admin page (`lang` and `data-i18n-skip` keep the label untranslated).
4. Bump `DICTIONARY_VERSION` in `ui-i18n.js` whenever a dictionary file changes, so browsers fetch
   the new version.
