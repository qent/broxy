# CLI mode

Broxy CLI exposes one command:

- `broxy proxy`

## `broxy proxy` flags, defaults, and aliases

- `--config-dir`: config directory (default `~/.config/broxy`).
- `--preset-id`: required preset id (loads `preset_<id>.json`).
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

## Logging (CLI contract)

- STDIO inbound must keep stdout clean.
- CLI logs to stderr and daily log files under `~/.config/broxy/logs/`.
- Log filtering uses `--log-level`.
- CLI logging is constructed by `CliLoggerFactory` (stderr + daily file logger).

## Related headless runtime

The packaged Desktop app uses a separate headless STDIO entrypoint in
`headless-runtime/src/main/kotlin/io/qent/broxy/headless/HeadlessEntrypointJvm.kt`.
CLI behavior remains in `cli/`.
