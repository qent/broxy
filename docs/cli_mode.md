# CLI mode

## Purpose

Define CLI startup, flags, and hot-reload behavior for `broxy proxy` and one-shot agent execution.

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

Broxy CLI exposes two command groups:

- `broxy proxy` - run proxy inbound transports (long-running process with hot reload).
- `broxy agent run` - run one agent once (one-shot, no scheduler/daemon mode).

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

## `broxy agent run` (one-shot)

Runs one agent without UI and exits after terminal outcome. Scheduling is intentionally unsupported in CLI.

Required flags:

- `--mcp-config <file>`: path to `mcp.json` (or compatible JSON file).
- `--agent-config <file>`: path to Claude subagent markdown file (`<id>.md`).

Optional input/config flags:

- `--agent-settings <file>`: path to `agents_settings.json`.
- `--agents-secrets <file>`: path to `agents_secrets.json` (`values.openai_api_key`, `values.anthropic_api_key`).
- `--state-dir <dir>`: runtime state/logs/OAuth base dir (default `~/.config/broxy`).

Optional launch overrides:

- `--prompt`
- `--runtime` (`langchain|codex|codex-cli`)
- `--provider` (`openai|anthropic|lm-studio|lmstudio`)
- `--model`
- `--temperature`
- `--workspace`
- `--fs-access` (`none|read-only|read-write`)
- `--codex-model`
- `--codex-reasoning` (`low|medium|high`)
- `--codex-web-search` (`true|false`)
- `--timeout-seconds` (default `300`)
- `--output` (`text|json`, default `text`)
- `--log-level` (`debug|info|warn|error`, default `info`)

Launch parameter precedence:

`CLI overrides > agent.manualLaunchDefaults > agent.schedule > built-in defaults`.

CLI markdown loading behavior:

- canonical `agentId` is the `<id>` file stem from `--agent-config`;
- Broxy-specific launch defaults/capability refs are loaded from sidecar metadata
  `metadata/agent_<id>.json` in the same storage root as `--agent-settings` (or default `--state-dir/agents`);
- nested `agentTools` resolve sibling markdown files (`<targetId>.md`) in the same directory as root `--agent-config`.

Prompt is required after resolution; blank prompt fails fast.

Secrets contract for `LANGCHAIN` providers requiring API keys:

- OpenAI: `BROXY_AGENT_OPENAI_API_KEY` env override, fallback to `values.openai_api_key` in `--agents-secrets`.
- Anthropic: `BROXY_AGENT_ANTHROPIC_API_KEY` env override, fallback to `values.anthropic_api_key` in `--agents-secrets`.

`LM_STUDIO` does not require API key.

Output/exit contract:

- `text`: success -> assistant response on stdout; failure -> error on stderr.
- `json`: structured payload on stdout (`status`, `runtime`, `response`, `errorMessage`, `toolCalls`, `durationMillis`).
- Exit code `0` for success, non-zero for any failure.

## Logging (CLI contract)

- STDIO inbound must keep stdout clean.
- CLI logs to stderr and daily log files under `~/.config/broxy/logs/`.
- Log filtering uses `--log-level`.
- CLI logging is constructed by `CliLoggerFactory` (stderr + daily file logger).

## Related headless runtime

The packaged Desktop app uses a separate headless STDIO entrypoint in
`headless-runtime/src/main/kotlin/io/qent/broxy/headless/HeadlessEntrypointJvm.kt`.
CLI behavior remains in `cli/`.
