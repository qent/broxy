# CLI mode

Broxy CLI exposes a single command: `broxy proxy`.

## Flags, defaults, and aliases

- `--config-dir`: config directory (default `~/.config/broxy`).
- `--preset-id`: required preset id (loads `preset_<id>.json`).
- `--inbound`: inbound transport (default `stdio`).
  - `stdio` aliases: `local`
  - `http` aliases: `remote`, `sse`
- `--url`: Streamable HTTP listen URL (default `http://localhost:3335/mcp`), used only when inbound is HTTP.
- `--log-level`: `debug|info|warn|error` (default `info`).

## Runtime and hot reload

- CLI loads `mcp.json` + `preset_<id>.json`, builds `ProxyLifecycle`, and starts the inbound server.
- UI-only settings stored in `ui.json` are ignored in CLI mode.
- `ConfigurationWatcher` observes changes:
  - `mcp.json` -> `ProxyLifecycle.updateServers(...)`
  - `preset_*.json` -> `ProxyLifecycle.applyPreset(...)`
- Inbound server stays up; the SDK server is resynced in place.

See `docs/inbound_transports.md` for inbound transport behavior (Streamable HTTP + `/sse`) and
`docs/logging_and_observability.md` for logging rules.

## Logging (CLI contract)

- STDIO inbound must keep stdout clean. CLI logs to stderr and daily log files under
  `~/.config/broxy/logs/` (same base dir as config).
- Log filtering uses the `--log-level` option.
- CLI logging is constructed by `CliLoggerFactory` (stderr + daily file logger).

## Related headless runtime

The packaged Desktop app uses a separate headless STDIO entrypoint in
`headless-runtime/src/main/kotlin/io/qent/broxy/headless/HeadlessEntrypointJvm.kt`.
CLI behavior is unchanged and remains in `cli/`.
