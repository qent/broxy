# Server icons

Broxy resolves server icons automatically from the server configuration. The rules are defined as
regular-expression checks in JSON and mapped to PNG assets with transparent backgrounds.

Users can also assign custom icons from the desktop UI. Custom icons are stored on disk and
override the rule-based icon lookup. Custom icons can be cleared from the UI; clearing resets the
icon to rule-based/default resolution and removes the stored icon file if it is no longer referenced.

## Where the files live

- Rule definitions: `ui-adapter/src/commonMain/resources/server_icons.json`
- Icon assets: `ui/src/desktopMain/resources/icons/servers/*.png`
- Custom icon storage (desktop UI): `~/.config/broxy/icons/` (stored next to `logs/`)

## Custom icon settings

- `mcp.json` supports `iconPath` on each server entry.
- `iconPath` is a relative path under the config directory (for example `icons/my-icon.png`).
- When present, `iconPath` wins over any rule matches from `server_icons.json`.

## Rule format

`server_icons.json` contains an ordered list of rules. The first matching rule wins; if none match,
the UI falls back to the default MCP icon from the sidebar.

Each rule has:

- `icon`: asset name without extension (for example `brave` -> `brave.png`)
- `allOf`: list of field checks, all of which must match

Each check has:

- `field`: configuration field selector
- `pattern`: regular expression; matching uses substring search (`Regex.containsMatchIn`)

Supported fields:

- `id`, `name`, `transport`
- `command`, `args`, `url`
- `headers`, `headers.key`, `headers.value`
- `env`, `env.key`, `env.value`

`headers` and `env` apply to keys + values; `.key` and `.value` restrict the match.

## Adding a new icon

1. Add a PNG file with a transparent background to `ui/src/desktopMain/resources/icons/servers/`.
2. Add a rule to `ui-adapter/src/commonMain/resources/server_icons.json` that maps the desired
   configuration fields to the new icon name.
3. Keep the rule order in mind: earlier rules take precedence.
