# Localization

## Purpose

Document localization entrypoints and rules for adding/maintaining UI languages.

## When to read

- When adding a new UI language.
- When changing string-provider wiring across UI/tray.

## Source-of-truth files

- `ui/src/commonMain/kotlin/io/qent/broxy/ui/strings/AppStrings.kt`

## Behavior contract

All UI text should be sourced from `LocalStrings`/`AppStrings`, not hardcoded literals.

Broxy UI strings live in `ui/src/commonMain/kotlin/io/qent/broxy/ui/strings/AppStrings.kt`.
The UI reads them via the `LocalStrings` composition local, and the desktop tray uses the same
`AppStrings` instance passed from `DesktopApp`.

## Adding a language

1. Add a new `AppLanguage` enum entry (for example `Russian("ru")`).
2. Create a new `object` that implements `AppStrings` with translated values.
3. Register the object in `AppStringsProvider.forLanguage`.
4. Select the language in `DesktopApp` (currently derived from `Locale.getDefault()`).

## Usage guidelines

- In composables, read `val strings = LocalStrings.current` and use it for UI text.
- For non-composable helpers, pass `AppStrings` as a parameter instead of embedding literals.
- Internal detection tokens (for example port-in-use matching) live in `AppTextTokens` and should remain stable.
