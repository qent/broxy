# Server icons

## Purpose

Define server icon resolution order, matcher policy, cache behavior, and custom icon overrides.

## When to read

- When changing auto-icon matching rules.
- When changing icon cache/storage behavior.
- When changing custom icon UI behavior.

## Source-of-truth files

- `ui-adapter/src/commonMain/resources/server_icons.json`
- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/icons/ServerIconResolver.kt`
- `ui/src/commonMain/kotlin/io/qent/broxy/ui/screens/ServerEditorScreen.kt`

## Behavior contract

Custom `iconPath` always overrides registry/default icon resolution.

Broxy resolves server icons in two steps:

1. local rule-based matching in `server_icons.json`;
2. registry icon lookup by matched `registryId`.

Users can also assign custom icons from the desktop UI. Custom icons are stored on disk and
override registry-based icon lookup. Custom icons can be cleared from the UI; clearing resets the
icon to registry/default resolution and removes the stored icon file if it is no longer referenced.

## Auto-detection source and matching

For non-custom servers, Broxy resolves `UiServerIcon.Remote` from catalog entries:

1. `ServerIconResolver` applies ordered rules from `server_icons.json` to server config fields.
2. The first matched rule returns `registryId`.
3. Broxy normalizes registry IDs on both sides (for example, `io.qent.broxy/context7` -> `context7`).
4. Broxy finds a catalog server by normalized `registryId` and takes its `icons[].src`.
5. If nothing can be resolved, Broxy falls back to `UiServerIcon.Default`.

Rules support the same field selectors as before: `id`, `name`, `transport`, `command`, `args`, `url`,
`headers`, `headers.key`, `headers.value`, `env`, `env.key`, `env.value`.

Rule format:

- `registryId`: target server ID from registry (required)
- `allOf`: list of regex checks (all conditions must match)

## Snapshot coverage and matcher policy

`server_icons.json` is synced with the current GitHub catalog snapshot (`qent/broxy-registry`, `index.json`)
and includes matchers for all bundled catalog server IDs plus compatibility entries for legacy servers.

Recent matcher updates:

- added matchers for `deepwiki`, `desktop-commander`, and `gitlab`;
- added remote URL matchers for `huggingface`, `mapbox`, `paypal`, and `wix`;
- fixed `firebase` stdio matcher to validate two separate args (`firebase-tools@...` and `mcp`) instead of a
  single combined regex.

Bundled matcher policy:

- remote `streamable-http`: `transport=^http$` + exact `url` regex (`/?$` allows optional trailing slash);
- remote `sse`: `transport=^sse$` + exact `url` regex (`/?$` allows optional trailing slash);
- `stdio`: `transport=^stdio$` + unique package/image token regex in `args`.
- for variable-host remotes, rules match a stable URL suffix path (for example, Elastic
  `/api/agent_builder/mcp`) instead of a fixed host.

Bundled rules intentionally do not rely on `headers` or `env` because those fields are commonly user-specific.

## UI behavior in server lists/forms

- Server icon badges are clickable when icon picking is available.
- For custom icons, hovering the badge shows a remove (`X`) action in the top-right corner.
- For non-custom icons (`UiServerIcon.Default` and registry-based `UiServerIcon.Remote`), hovering the
  badge shows an image action in the same top-right corner to indicate that a custom icon can be
  selected and will override the current icon.
- When a server matches a registry rule and the matched catalog metadata contains `websiteUrl` or
  `repository.url`, Broxy shows an external-link icon near the server title in:
  - the server capabilities header (`ServerCapabilitiesScreen`).
  - the server editor name field (`ServerEditorScreen`), to the left of the server icon badge.
- The external link target uses `websiteUrl` first and falls back to `repository.url`.
- When hovering an external-link icon, Broxy shows a bottom overlay bar with a semi-transparent
  background and the full target URL text (single line with ellipsis when needed).
- For matched servers, `ServerCapabilitiesScreen` also shows the catalog server description between
  the header row and capability cards.

## Where the files live

- Rule definitions: `ui-adapter/src/commonMain/resources/server_icons.json`
- Custom icon storage (desktop UI): `~/.config/broxy/icons/` (stored next to `logs/`)
- Shared registry/server icon cache (desktop UI): `${AppCacheDir}/server-icons/`
  - macOS: `~/Library/Caches/broxy/server-icons/`
  - Linux: `${XDG_CACHE_HOME:-~/.cache}/broxy/server-icons/`
  - Windows: `%LOCALAPPDATA%\\broxy\\Cache\\server-icons\\`
  - Files are named as `sha256(url)` with an image-like extension (`.png`, `.webp`, `.img`, etc.).
  - Registry screen and server auto-icons use the same cache.
  - On render, Broxy checks the cache first and downloads from the remote URL only when the icon is missing.

## Custom icon settings

- `mcp.json` supports `iconPath` on each server entry.
- `iconPath` is a relative path under the config directory (for example `icons/my-icon.png`).
- When present, `iconPath` wins over registry-based auto-detection.

## Adding or changing icons

1. Update `icons[].src` in the registry entry (`qent/broxy-registry`).
2. Add/update a matching rule in `server_icons.json` with `registryId`.
3. Refresh catalog in Broxy (automatic startup metadata check via `HEAD index.json` or manual refresh in UI).
4. Existing cached icon files are reused by URL hash; changed URLs populate new cache files.
