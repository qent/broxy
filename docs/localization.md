# Localization

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
