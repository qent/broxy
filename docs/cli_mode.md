# CLI mode

## Purpose

Define CLI startup, flags, and hot-reload behavior for `broxy proxy`.

## When to read

- When changing CLI options/defaults.
- When changing CLI runtime lifecycle or watcher integration.
- When changing CLI logging behavior.

## Source-of-truth files

- `cli/src/main/kotlin/io/qent/broxy/cli/commands/ProxyCommand.kt`
- `cli/src/main/kotlin/io/qent/broxy/cli/commands/ProxyCommandRunner.kt`
- `core/src/jvmMain/kotlin/io/qent/broxy/core/config/ConfigurationWatcher.kt`

## Behavior contract

CLI keeps inbound running during config/preset reload and applies updates through `ProxyLifecycle`.

Broxy CLI exposes one command:

- `broxy proxy`

## `broxy proxy` flags, defaults, and aliases

- `--config-dir`: config directory (default `~/.config/broxy`).
- `--preset-id`: required preset id. Supports both file-backed presets (`preset_<id>.json`) and
  built-ins (`__empty__`, `__all_enabled__`, `__preset_management__`).
- `--inbound`: inbound transport (default `stdio`).
  - `stdio` aliases: `local`
  - `http` aliases: `remote`, `sse`
- `--url`: Streamable HTTP listen URL (default `http://localhost:3335/mcp`), used only when inbound is HTTP.
- `--log-level`: `debug|info|warn|error` (default `info`).

## Runtime and hot reload (`broxy proxy`)

- CLI loads `config.json`, resolves `mcpFilePath`, then loads server definitions from the target `mcp.json`.
- UI-only settings from `ui.json` are ignored in CLI mode.
- `ConfigurationWatcher` observes:
  - `config.json`
  - active MCP servers file resolved from `mcpFilePath`
  - `preset_*.json`
- Inbound server stays up; SDK capabilities are resynced in place.

When started with `--preset-id __preset_management__`, CLI exposes the fixed management-only MCP
tool surface:

- `get_preset_creation_algorithm`
- `list_server_names`
- `get_server_description`
- `list_preset_names`
- `get_preset_description`
- `create_preset`

No downstream tools/prompts/resources are published in this mode.

## Logging (CLI contract)

- STDIO inbound must keep stdout clean.
- CLI logs to stderr and daily log files under `~/.config/broxy/logs/`.
- Log filtering uses `--log-level`.
- CLI logging is constructed by `CliLoggerFactory` (stderr + daily file logger).

## Related headless runtime

The packaged Desktop app uses a separate headless STDIO entrypoint in
`headless-runtime/src/main/kotlin/io/qent/broxy/headless/HeadlessEntrypointJvm.kt`.
CLI behavior remains in `cli/`.
