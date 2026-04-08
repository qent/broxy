# Agents and Scheduling

## Overview

Broxy includes a dedicated `agents/` module for agentic execution with a hybrid runtime
(`LangChain4j` + `Codex CLI`) and a dedicated `agents-codex/` module for the Codex runtime adapter.
An agent stores:

- system prompt
- optional short description (30-36 words, generated or manual)
- copied capability preset (tools/prompts/resources, no runtime link to a preset file)
- optional agent-tool references (`agentTools`) to call other agents as tools
- optional schedule (one cron per agent) with runtime launch settings
- latest manual launch defaults (prompt/runtime/llm/codex/filesystem)

Run history is stored in a dedicated standalone runs subsystem (separate files, not embedded into agent config).

The UI exposes this through the `Agents` section and a dedicated `Agent Settings` rail entry
(brain/gear icon).
Agents list order is user-controlled via drag-and-drop and persisted in `orderIndex`.

CLI also exposes one-shot execution via `broxy agent run` (jar mode), which runs a single agent
execution and exits. CLI does not support agent scheduling/daemon mode.

## Module boundaries

- `agents/` contains domain contracts, persistence, scheduling, and runtime dispatch.
- service API in `agents/` is split by responsibility:
  - `AgentCatalogService`,
  - `AgentExecutionService` (`AgentRunCommand`, `AgentScheduleCommand`),
  - `AgentProviderService`,
  - `AgentLifecycleService`,
  - `AgentDescriptionService` (`AgentDescriptionGenerationCommand`),
  - `AgentService` facade (composition of the interfaces above).
- `agents-codex/` contains Codex runtime integration (`CodexCliExecutor`, isolated MCP endpoint,
  and port allocator).
- `ui-adapter/` integrates the module via `AgentGateway` and keeps UDF/MVI orchestration.
- `ui/` renders agent list/editor/launch forms and agent settings provider forms.
- `cli/` provides one-shot headless execution (`broxy agent run`) with explicit config file arguments.
- `core/` remains unchanged as the source of MCP config, capability filtering, and dispatcher contracts.

## Persistence

Agent files are stored in a dedicated subdirectory: `~/.config/broxy/agents`:

- `<id>.md` - Claude-compatible subagent definition (YAML frontmatter + markdown body prompt).
- `metadata/agent_<id>.json` - Broxy sidecar metadata (`tools`, `agentTools`, `prompts`, `resources`,
  `orderIndex`, `schedule`, `manualLaunchDefaults`).
- `runs/run_<runId>.json` - full structured run details.
- `runs_index.json` - compact run summaries sorted by `startedAtEpochMillis` (desc).
- `agents_settings.json` - non-secret provider settings (`baseUrl` overrides, global Codex enable flag,
  per-provider model cache, Codex CLI global defaults, app-level AI feature runtime settings, and
  optional `agentsDirectoryPath` for external Claude-agent folders).
- `agents_secrets.json` - fallback secret storage when system secure storage is unavailable.

Legacy agent files in `~/.config/broxy` are ignored; no migration is performed automatically.

`<id>.md` frontmatter fields used by Broxy:

- required: `name`, `description`
- runtime-used: `tools`, `disallowedTools`, `permissionMode` (advisory-only), `mcpServers`
- preserved round-trip but ignored at runtime: unsupported Claude fields (`model`, `maxTurns`, `background`,
  `isolation`, `memory`, `hooks`, `skills`, and unknown custom keys)
- markdown body is mapped to Broxy `systemPrompt`
- canonical Broxy `agentId` is filename stem (`code-reviewer.md` -> `code-reviewer`)

`metadata/agent_<id>.json` stores Broxy-only fields:

- `tools`, `agentTools`, `prompts`, `resources`
- `orderIndex`
- `schedule` (`cron`, `prompt`, `timezoneId`, `runtime`, `llm`, `codex`, `fileSystem`)
- `manualLaunchDefaults` (`prompt`, `runtime`, `llm`, `codex`, `fileSystem`)

Directory resolution:

- if `agents_settings.json.agentsDirectoryPath` is empty/missing, Broxy reads/writes `<id>.md` in
  `~/.config/broxy/agents`;
- if `agentsDirectoryPath` is set, Broxy reads/writes `<id>.md` in that external directory, while sidecar
  metadata/runs/settings/secrets stay under `~/.config/broxy/agents`.

`agentTools` reference contract:

- entry shape: `AgentToolReference(agentId, enabled)` (`core/.../AgentToolReference.kt`)
- input/output contract at runtime: string -> string (`input` argument in tool call, string response payload)
- references to missing/deleted agents remain in config; they are preserved on load/save and not auto-removed

`runs/run_<runId>.json` stores:

- `summary` (`runId`, `agentId`, `agentName`, `trigger`, `status`, `runtime`, `prompt`, `response`, `errorMessage`,
  `startedAtEpochMillis`, `finishedAtEpochMillis`)
- `systemPrompt`, launch `llm`, launch `codex`, launch `fileSystem`
- `dialogue[]` with structured entries (`SYSTEM`/`USER`/`ASSISTANT`/`TOOL`)
- `actions[]` with structured runtime entries (`PREPARING_RUN`, `LOADING_CAPABILITIES`, `LLM_*`, `TOOL_CALL`,
  `TOOL_RESULT`, `RUNTIME_EVENT`)

Runs are persisted for terminal outcomes (`SUCCESS`, `FAILED`, `SKIPPED`). Retention is currently unlimited.

`agents_settings.json` Codex fields:

- `enableCodexProvider`: global feature gate for Codex runs.
- `codex.command`: executable used for runs (default `codex`). Relative commands are resolved
  against user shell `PATH`; absolute path is recommended for GUI launches.
- `codex.portRangeStart`, `codex.portRangeEnd`: localhost port range for per-run isolated MCP endpoint.
- `modelCache.codex`: cached Codex model ids for launch-form dropdown.
- `modelCache.codexFetchedAtEpochMillis`: timestamp of the last successful Codex model refresh.
- runtime default model for Codex launch forms and persisted defaults: `gpt-5.1-codex-mini`.
- runtime default reasoning effort for Codex launch forms and persisted defaults: `HIGH`.

`agents_settings.json` app-level AI feature fields:

- `aiFeatures.enabled`: master switch for app AI features (description generation and future features).
- `aiFeatures.runtime`: runtime used for app AI features (`LANGCHAIN` or `CODEX_CLI`).
- `aiFeatures.llm`: LangChain feature runtime config (`provider`, `model`, `temperature`).
- `aiFeatures.codex`: Codex feature runtime config (`model`, `reasoningEffort`, `webSearch`).
- `codex.command` is reused for both agent runs and app AI features when `aiFeatures.runtime=CODEX_CLI`.
- Agent Settings UI maps AI runtime selector options as:
  - `Disabled` -> `aiFeatures.enabled=false` (runtime value is preserved in storage);
  - `LangChain` -> `aiFeatures.enabled=true`, `aiFeatures.runtime=LANGCHAIN`;
  - `Codex CLI` -> `aiFeatures.enabled=true`, `aiFeatures.runtime=CODEX_CLI`.
- Agent Settings UI does not expose `codex.command` in the AI features card; it remains stored in
  `agents_settings.json` and is reused by both agent runs and app AI features.
- Agent Settings UI saves `aiFeatures.codex.webSearch=false` for app AI features.

Codex launch-form model list:

- source: terminal Codex CLI (`codex.command`) via `app-server` JSON-RPC `model/list`;
- cache TTL is 24 hours (`modelCache.codexFetchedAtEpochMillis`);
- refresh button in the Codex model field forces an immediate reload;
- on reload failure, UI keeps cached values (when available) and still allows manual model input.

Filesystem launch settings:

- `fileSystem.path`: workspace root path for local file tools.
- `fileSystem.access`: `NONE`, `READ_ONLY`, or `READ_WRITE`.
- Launch form behavior: when runtime is `CODEX_CLI`, the filesystem selector shows only
  `READ_ONLY` and `READ_WRITE` (Codex CLI always has read access in sandbox mode). Legacy
  `NONE` values are normalized to `READ_ONLY` for Codex launches.

## Secrets

`AgentSecretsStore` uses:

1. system secure storage (macOS Keychain / Linux secret-tool) when available;
2. file fallback (`agents_secrets.json`) otherwise.

API keys are never written to `agents_settings.json`.

## Execution flow

`DefaultAgentService` dispatches execution via `HybridAgentExecutor`:

- `AgentRuntime.LANGCHAIN` -> `LangChain4jAgentExecutor`
- `AgentRuntime.CODEX_CLI` -> `CodexCliExecutor`
- `CODEX_CLI` is rejected when `enableCodexProvider=false` with error
  `Codex provider is disabled in Agent Settings`.

### Agent tools (`agent -> agent`)

Agent-tool execution is implemented as a virtual MCP server (`serverId=broxyagenttools`) wired into
the same tool pipeline as downstream MCP servers:

- each enabled and currently resolvable `agentTools` entry is exposed as a virtual tool
  (`agent_<targetAgentId>`, deduplicated and sanitized);
- unresolved references (deleted/missing target agent configs) are skipped from runtime capabilities
  but remain persisted in config;
- tool arguments require string field `input`;
- nested run request is built from the target agent defaults
  (`systemPrompt`, `runtime`, `llm/codex`, `fileSystem`, copied capability refs);
- nesting depth is unbounded; cycle blocking uses an invocation stack (`agentInvocationStack`);
- cycle detection returns deterministic error:
  `Agent tool cycle detected: <agentA -> agentB -> ... -> agentA>`.

Nested requests for `LANGCHAIN` and `CODEX_CLI` use the same virtual-MCP path.
`LANGCHAIN` tool failures are surfaced in CLI JSON output with cycle messages when detected.

### Agent description generation

`DefaultAgentService.generateAgentDescription(...)` implements the first app-level AI feature:

1. loads `agents_settings.json` and checks `aiFeatures.enabled=true`;
2. resolves runtime/model from `aiFeatures` (`LANGCHAIN` or `CODEX_CLI`);
3. builds capability context text from selected refs plus cached snapshot metadata
   (tool/prompt/resource descriptions and arguments);
4. executes a fixed prompt through the configured runtime;
5. validates strict output contract:
   - English text only;
   - one plain sentence;
   - 30-36 words;
6. if validation fails, performs one rewrite retry; if retry still fails, returns failure.

Structured events are emitted for this flow:

- `agent.description.generation.started`
- `agent.description.generation.succeeded`
- `agent.description.generation.failed`

### Agent auto-generation from short request

`DefaultAgentService.generateAgent(...)` implements app-level AI agent creation from a plain user request:

1. resolves runtime/model config from request-local `aiFeaturesOverride` (when provided by UI); otherwise falls back
   to `agents_settings.json.aiFeatures`.
2. validates AI runtime gates from the resolved config (`enabled`, runtime selection, and
   `enableCodexProvider` for `CODEX_CLI`);
3. receives cache-first capability context from ui-adapter for all configured servers; servers without cached
   snapshots are still included with empty `tools/prompts/resources` lists;
4. stage 1 selects relevant servers from the full configured server pool;
5. stage 2 runs per selected server and picks minimal `tools/prompts/resources`;
6. stage 3 performs cross-server dedupe and returns final JSON payload:
   - `agentName`
   - optional `description`
   - English `systemPrompt`
   - final `tools/prompts/resources` refs
7. unknown or stale refs are filtered against available server capabilities; generation fails when:
   - final system prompt is blank, or
   - final capability selection is empty.

Prompt templates for all three stages plus the system-prompt-writing template are stored as editable resources:

- `agents/src/main/resources/prompts/agent_generation_server_selection.md`
- `agents/src/main/resources/prompts/agent_generation_server_capabilities.md`
- `agents/src/main/resources/prompts/agent_generation_finalize.md`
- `agents/src/main/resources/prompts/agent_system_prompt_template.md`

`AppStore.startGenerateAgentFromRequest()` orchestrates this flow in ui-adapter:

- accepts request-local AI config from `Generate Agent` form (seeded from Agent Settings on screen open);
- enforces single-flight generation (one active request globally);
- preserves generation state in `StateFlow` across UI navigation;
- autosaves generated draft via `AgentGateway.upsertAgent(...)`;
- generates unique saved id from AI name (`slug` + numeric suffix);
- publishes completion with saved agent id so UI can open Edit mode for the saved agent.

### LangChain4j runtime

`LangChain4jAgentExecutor`:

1. reads current `mcp.json` from `ConfigurationRepository`;
2. resolves effective MCP server scope from:
   - agent-owned capability refs (`tools/prompts/resources`) and
   - Claude frontmatter `mcpServers` IDs;
   inline Claude `mcpServers` configs override same-id entries from `mcp.json` for the current run only.
3. builds short-lived downstream MCP connections only for servers that are both enabled in effective MCP config
   and present in `usedServerIds`;
4. restores persisted OAuth state (token/registration/metadata) for scoped HTTP/Streamable HTTP/WebSocket
   servers and persists updates after each connection session;
5. fetches capabilities in parallel for the scoped server set;
6. applies `DefaultToolFilter` using the agent-owned capability copy;
7. prepares local filesystem workspace from launch settings:
   - workspace path is normalized to absolute;
   - `/tmp/broxy/agents` is auto-created when missing;
   - missing non-default path fails the run;
   - path traversal outside workspace is blocked.
8. builds local filesystem tools:
   - `NONE`: no local tools;
   - `READ_ONLY`: `fsInspect`, `fsRead`, `fsSearch`;
   - `READ_WRITE`: `fsInspect`, `fsRead`, `fsSearch`, `fsEdit`.
   - `fsEdit` accepts empty text payloads (for example, creating an empty file with `overwrite`);
   - null-byte payloads are rejected as `binary_file_not_supported`.
   - `overwrite` does not convert existing binary files to text; existing binary files return `binary_file_not_supported`.
9. builds a typed agent via `AgenticServices.agentBuilder(...)` (`langchain4j-agentic`) and runs
   the built-in tool-execution loop with a `ToolProvider` bridge to `DefaultRequestDispatcher`
   and the local filesystem tool provider.
10. emits structured trace entries through `AgentExecutionRequest` callbacks:
    - dialogue: `system`, `user`, `assistant`, `tool`;
    - actions: operation updates, tool call payloads, tool result payloads/errors.

Claude compatibility notes in LangChain runtime:

- `tools/disallowedTools` are mapped best-effort to Broxy filesystem access (`NONE/READ_ONLY/READ_WRITE`);
  incompatible combinations are downgraded with runtime warnings.
- `permissionMode` is advisory-only: it is logged/diagnosed but does not change actual sandbox behavior.

Filesystem implementation boundary:

- `AgentFileSystemTools` is a thin facade.
- Tool logic is split into dedicated handlers (`inspect`, `read`, `search`, `edit`) under
  `agents/.../runtime/filesystem/`.
- Shared concerns are extracted into focused helpers: JSON args parsing, payload encoding,
  path metadata helpers, UTF-8/binary guard, and line-edit engine.

`langchain4j-agentic` is used as a beta/experimental runtime dependency, so minor API changes are possible
between upstream releases.

Supported providers:

- OpenAI (`OpenAiChatModel`)
- Anthropic (`AnthropicChatModel`)
- LM Studio via OpenAI-compatible local endpoint (`OpenAiChatModel`)

Endpoint overrides come from `agents_settings.json`.
All providers are configured with an explicit LangChain4j JDK HTTP client builder to avoid runtime
`ServiceLoader` dependency on stripped release artifacts.
LLM HTTP calls also inherit `ignoreHttpsCertificateErrors` from `mcp.json`, including endpoint overrides.
Manual launches always use LLM settings from the launch form. Scheduled launches use `schedule.llm`.
Schedule runtime settings are strict: `runtime`, `llm`, and `fileSystem` are required in persisted schedule payloads.
For manual launches, `DefaultAgentService` persists the normalized launch parameters (`prompt`, `provider`, `model`,
`temperature`, `fileSystem`) to `manualLaunchDefaults` before starting execution.
After each terminal run outcome (`SUCCESS`/`FAILED`/`SKIPPED`), `DefaultAgentService` writes full run details to
`runs/run_<runId>.json` and updates `runs_index.json`; run summaries are emitted via
`AgentExecutionUpdate.Finished(run)`.

### CLI one-shot execution (`broxy agent run`)

`broxy agent run` executes exactly one run and exits:

1. loads `mcp` config from `--mcp-config` and agent definition from `--agent-config`;
2. optionally loads provider settings (`--agent-settings`) and file secrets (`--agents-secrets`);
3. resolves launch parameters by precedence:
   `CLI overrides > manualLaunchDefaults > schedule > built-in defaults`;
4. executes one request through `HybridAgentExecutor` (runtime can be `LANGCHAIN` or `CODEX_CLI`);
5. returns terminal outcome via process exit code and `--output text|json`.

Scheduling is intentionally unsupported in CLI mode: no cron registration, no background scheduler process.

For nested `agentTools`, CLI resolves referenced agents by sibling files in the same directory as
`--agent-config` (`<id>.md`).

### Codex CLI runtime (`agents-codex/`)

`CodexCliExecutor`:

1. emits `LoadingCapabilities` and starts `AgentRunMcpIsolator`;
2. `AgentRunMcpIsolator` resolves used server IDs from capability refs plus Claude `mcpServers`,
   applies inline `mcpServers` overrides for this run,
   creates short-lived downstream connections for enabled scoped servers, applies filtering via preset,
   starts a temporary inbound Streamable HTTP endpoint, and returns URL `http://127.0.0.1:<port>/mcp`.
   For HTTP/Streamable HTTP/WebSocket downstream servers, it restores OAuth state snapshots from the
   shared Broxy secure storage (`serverId + resourceUrl`) before connection and persists updated
   snapshots after downstream sessions complete;
3. port is allocated from `agents_settings.json` range (`codex.portRangeStart..codex.portRangeEnd`)
   by process-local `AgentPortRangeAllocator`;
4. prepares a deterministic user-auth context for the child process:
   - sets `HOME` and `CODEX_HOME` to the current user profile (`~/.codex`);
   - removes inherited `OPENAI_API_KEY` / `CODEX_API_KEY` (session-only policy);
   - removes inherited `OPENAI_BASE_URL` and re-adds it only for an explicit non-default override from Agent settings;
   - validates workspace path before preflight/exec:
     - path is normalized to absolute;
     - missing default path (`/tmp/broxy/agents`) is auto-created;
     - missing non-default path fails the run;
     - existing non-directory path fails the run.
5. runs preflight checks in the same environment:
   - `codex --version`;
   - `codex login status`;
   - fails fast (without interactive flow) when session is unavailable, with recovery hint to run
     `codex login` manually in terminal;
   - logs safe `auth.json` metadata (`last_refresh`, access-token expiry, file fingerprint) for diagnostics;
   - note: `codex login status` confirms that a user session exists, but does not guarantee the stored refresh token is still reusable.
6. starts `codex exec --json` with run config:
   `--model`, `--sandbox`, `--cd`, `--config approval_policy="never"`,
   `--config model_reasoning_effort=...`, `--config plan_mode_reasoning_effort=...`,
   `--config web_search=...`, `--config mcp_servers.broxy.url=...`,
   optional `--config sandbox_workspace_write.network_access=false`, and `--skip-git-repo-check`;
   reasoning effort always comes from the launch form (`LOW`/`MEDIUM`/`HIGH`) so Broxy does not inherit
   incompatible values such as `xhigh` from the user's global `~/.codex/config.toml`;
7. builds a single launch prompt string from agent `systemPrompt` + run `prompt`, passes it as the
   trailing `codex exec` argument, and parses JSONL from stdout;
8. maps tool and model events to existing `AgentExecutionOperation` updates and structured trace entries
   (`agent_message`, `mcp_tool_call`, `mcp_tool_result`);
   after `mcp_tool_result`, runtime emits `LlmThinking` for the current step so UI leaves
   `Running tool ...` while Codex/LLM continues processing the tool output;
   unknown/extra event fields are ignored safely;
   non-primitive/shape-drifted fields are tolerated without terminating the run;
   `mcp_tool_call.error` is accepted as either a string or an object (`{ "message": ... }`) and
   is propagated to trace `TOOL_RESULT.errorMessage` when available;
   malformed JSONL lines or events with incompatible non-critical shapes are skipped.
9. retries only the specific auth error `refresh token was already used` and only after Broxy observes that
   `~/.codex/auth.json` changed externally during a short wait window;
   if the file does not change, Broxy stops immediately with a normalized stale-session/race-condition message
   instead of repeating the same failing request.
10. guarantees child-process teardown (`waitFor` + forced termination fallback) even on parse/runtime failures,
    preventing hanging `codex` processes between runs.
11. always tears down isolated inbound MCP server and releases the port in `finally`;
12. does not mutate global `~/.codex/config.toml` during agent runs.

## Rename and delete behavior for agent references

- delete agent: target `<id>.md` plus `metadata/agent_<id>.json` are removed; references in other agents/presets are
  not rewritten.
- rename agent id (`oldId -> newId`) in UI flow:
  1) save updated agent under new id;
  2) migrate `agentTools` references in all presets;
  3) migrate `agentTools` references in all agents;
  4) delete old `<oldId>.md` and `metadata/agent_<oldId>.json`.

Codex runtime boundary:

- `CodexCliExecutor` is an orchestration facade.
- command/env assembly lives in `CodexCommandEnvironmentBuilder`;
- preflight probes live in `CodexPreflightChecker`;
- JSONL event parsing/mapping lives in `CodexJsonlEventMapper`;
- child process lifecycle/teardown is isolated in `CodexProcessRunner`;
- auth snapshot parsing + wait-for-change logic lives in `CodexAuthStateInspector`;
- retry classification/policy logic lives in `CodexAuthRetryPolicy`.

Codex runtime always uses `--cd <workspace>` and does not add Broxy local filesystem tools
(`fsInspect/fsRead/fsSearch/fsEdit`) into Codex tool list.

Maintenance note:

- runtime behavior is intentionally aligned with TypeScript `@openai/codex-sdk`;
- for implementation updates, use MCP `context7` docs for `@openai/codex-sdk` as the primary reference,
  and verify discrepancies against official OpenAI Codex docs.

## Agent runtime logs

Agent execution emits structured JSON events to the daily file log (`~/.config/broxy/logs/YYYY-MM-DD.log`):

- run lifecycle:
  - `agent.run.started`
  - `agent.run.operation`
  - `agent.run.finished`
  - `agent.run.skipped`
  - `agent.run.finished` is runtime-aware: for `LANGCHAIN` it logs provider/model/temperature from launch LLM config; for
    `CODEX_CLI` it logs `provider=CODEX_CLI`, Codex `model`, and `reasoningEffort`.
- Codex runtime specifics:
  - `agent.codex.exec.preflight` (version/login-status checks in run environment + safe `auth.json` metadata)
  - `agent.codex.exec.started`
  - `agent.codex.exec.auth_retry` (auth.json fingerprints/metadata before failure, after failure, and after the wait window; includes whether a retry was actually scheduled)
  - `agent.codex.exec.finished`
  - `agent.codex.exec.failed` (`failureKind` includes `preflight`, `refresh_token_reused`, `execution`)
- LLM/provider:
  - `agent.llm.model.selected` (provider/model/temperature, endpoint override flag)
  - `agent.llm.connection.succeeded`
  - `agent.llm.connection.failed`
  - `agent.llm.request` / `agent.llm.response` / `agent.llm.request.failed`
- tool loop:
  - `agent.tool.call.request`
  - `agent.tool.call.succeeded`
  - `agent.tool.call.failed`
  - filesystem tool calls add `toolOrigin=filesystem`, `workspacePath`, and `fsAccess`
- execution envelope:
  - `agent.execution.started`
  - `agent.execution.finished`
  - `agent.execution.failed`
  - `agent.execution.max_tool_steps`
  - `agent.fs.workspace.ready`
  - `agent.fs.workspace.failed`

## Scheduling

`CronAgentScheduler` uses UNIX cron (`cron-utils`, 5 fields).

- One schedule per agent.
- Schedule contains its own prompt and launch runtime settings (`runtime`, `llm`, `codex`, `fileSystem`).
- Schedule is validated on save (cron + timezone); invalid values are rejected before persistence.
- Saving schedule with `runtime=CODEX_CLI` is blocked when `enableCodexProvider=false`.
- Scheduled triggers go through the same runtime gate as manual launches.
- Overlap policy: if the agent is already running, the trigger is skipped and a `SKIPPED` record is written.
- Missed runs are not replayed after downtime.

The UI passes local system timezone when saving a schedule.

## UI integration

`Agents` screen:

- list + search
- root list shows one `+` FAB:
  - opens `Generate Agent` sub-screen (AI auto-creation flow) when app-level AI runtime is ready
    (`aiFeatures.enabled=true`; `LANGCHAIN`: non-empty model plus saved API key for `OPENAI`/`ANTHROPIC`,
    key not required for `LM_STUDIO`; `CODEX_CLI`: `enableCodexProvider=true` and non-empty `codex.command`);
  - otherwise opens manual create form directly.
- `Generate Agent` sub-screen behavior:
  - header includes right-aligned `Skip` secondary action that opens manual create form;
  - multiline request textarea + local AI runtime/model block (same visual controls as Agent Settings AI features);
  - local AI block title/subtitle are generation-specific (`Generation settings` + request-focused hint);
  - local AI block values are seeded from Agent Settings on screen open and are not persisted globally;
  - runtime options in this screen are `LangChain` and `Codex CLI` (no `Disabled`);
  - generate action in header is enabled only when request and local AI config are valid;
  - while generation runs, textarea/button are locked and button shows stage progress
    (`Selecting servers` -> `Selecting capabilities` -> `Finalizing agent`);
  - leaving Agents screen does not cancel/reset generation;
  - reopening the sub-screen during an active generation restores current progress and locked controls;
  - on successful autosave, sub-screen closes and UI opens Edit mode for the saved agent id.
- card subtitle shows selected capabilities summary and optional human-readable schedule marker
- actions: edit, run/schedule, delete (with confirmation)
- click on agent card body opens a read-only details subview with back arrow header
- details subview shows:
  - description block between header and system prompt;
  - system prompt in a scrollable container (`max-height: 400dp`) before capability cards.
- details subview resolves capabilities from the cached server snapshots (including disabled servers); capabilities
  from disabled servers are rendered semi-transparent and marked with the `Disabled` server badge
- card title row includes a status block next to the agent name:
  - compact horizontal spacing is used between name and status block (`xs / 2`)
  - while running: a live elapsed timer (`mm:ss`/`h:mm:ss`) refreshed every second plus the current runtime operation
  - runtime status text uses all available row width and is truncated only by the actual card width constraints
  - runtime operations include:
    - `Preparing run...`
    - `Loading server capabilities...`
    - rotating generic LLM request phrases (5 variants, switched every 2-4 seconds)
    - rotating generic LLM thinking phrases (5 variants, switched every 2-4 seconds) while waiting for model response after request delivery
    - rotating generic LLM response generation phrases (5 variants, switched every 2-4 seconds)
    - `Running tool <toolName> on server <serverId>`
  - manual launch shows timer + `Preparing run...` immediately, before backend update events arrive
  - after a failed latest run: latest failure message from `runs_index.json` until the next run
- primary action icon switches:
  - `Play` for manual launch when no schedule exists,
  - `Schedule` for agents that already have a cron (opens schedule editor),
  - `Stop` while running.
- `Stop` requires confirmation and then cancels the current run job.

`Runs` screen:

- separate top navigation item (`Runs`) after `Agents`
- list of all runs from `runs_index.json`, sorted by `startedAtEpochMillis` desc
- row shows agent name, run status, runtime, trigger, timestamp, and prompt/response/error snippets
- click on a run opens an in-screen detail subview with back arrow header
- detail subview renders structured sections:
  - `Dialogue` (system/user/assistant/tool entries)
  - `Actions` (runtime operations, tool calls, tool results, payloads/errors)
- raw JSON blocks are not rendered in UI
- on desktop, completed runs can emit a system notification (success = response snippet, failure/skip = error snippet).
- on macOS, Broxy sends notifications via native UserNotifications only (`UNUserNotificationCenter`) through JNI bridge
  (`broxy_notifications_bridge.m` + `MacOsNotificationNativeBridge.kt`).
- on macOS, Broxy can issue up to three native authorization requests per app session
  (`UNUserNotificationCenter.requestAuthorization(...)`) while notifications are not yet authorized.
- on macOS, Broxy sets `UNUserNotificationCenterDelegate` and foreground presentation options so run-finished
  notifications remain visible while the app is focused.
- on macOS, notifications are skipped when Broxy is not launched from a `.app` bundle (for example IDE/Gradle run)
  to avoid UserNotifications runtime crashes (`bundleProxyForCurrentProcess is nil`).
- on macOS, no fallback to deprecated `NSUserNotification*` APIs is used.
- on Windows, Broxy sends notifications via native toast notifications (`Windows.UI.Notifications`).
- on Linux, Broxy sends notifications via freedesktop notifications (`notify-send` with actions).

Create/edit agent uses one shared editor:

- name + description + system prompt fields are rendered directly in the main editor content
  (no nested `Agent` section card)
- description block behavior:
  - located between `Name` and `System prompt`;
  - when no description: action title/subtitle + `Generate` button on the right;
  - when description exists: full text + `Generate` button on the right;
  - `Generate` is available only in create/edit screens (not in details);
  - button is disabled when `aiFeatures.enabled=false` with inline hint to enable AI features in Agent Settings;
  - generation uses current unsaved draft state (name, system prompt, effective capability selection).
- capabilities source dropdown + `Custom capabilities`
- model/provider/temperature block is removed from agent editor
- capability search uses the same floating translucent bottom-center search bar as other searchable screens
- selected capabilities are always shown in dedicated tools/prompts/resources cards (same visual style as preset editor)
- capability picker block (servers with selectable capabilities) is shown only for `Custom capabilities`
- in `Custom capabilities`, disabled servers are still shown when a cached capability snapshot exists;
  they are marked with a `Disabled` badge. Selected capabilities from disabled servers remain visible
  in the summary cards below, rendered semi-transparent with the same badge next to server names.
- when a saved preset is selected, capability cards show preset capabilities without server-level picker block

Launch form:

- opens as an in-body sub-screen inside `Agents` (not a modal popup)
- header uses a back arrow on the left and `Cancel` + `Launch/Schedule` actions on the right
- `Cancel` and back close the form and return to the agent list

- prompt input
- workspace selector is shown directly under the prompt input:
  - editable workspace path field supports manual input;
  - access dropdown: `No FS`, `Read-only`, `Read-write`.
  - directory picker is opened only from the folder icon in the field;
  - workspace and server icon selection both use the shared desktop `SystemPicker` component in `ui-adapter`;
  - when the user edits the path, UI validates directory existence on each update and shows an inline error
    when a non-default path is missing;
  - on macOS, Broxy uses an owner-aware native system directory picker (`FileDialog`) on the EDT with
    temporary `apple.awt.fileDialogForDirectories=true` and synchronized property restoration;
  - if native picker initialization fails on macOS, Broxy falls back to Swing `JFileChooser` (directory-only);
  - provided initial path is resolved to the nearest existing parent directory (file input uses its parent).
- runtime selector is shown in a dedicated `Runtime` card:
  - options: `LangChain` and `Codex CLI`;
  - when `enableCodexProvider=false`, Codex option is disabled and form runtime is forced to `LangChain`;
  - inline hint explains that Codex is disabled in Agent Settings.
- when runtime is `Codex CLI`, launch form shows a dedicated Codex card with:
  - `model`;
  - reasoning-effort selector (`Low`, `Medium`, `High`) rendered in the same row to the right of the model field;
  - `web search`.
- Codex launch defaults are fixed by runtime policy (not shown in UI):
  - `approval policy`: `never`;
  - `skip git repo check`: always enabled;
  - `additional directories`: not used;
  - `sandbox`: derived from file system access (`Read-write -> workspace-write`, `No FS`/`Read-only -> read-only`);
  - `sandbox_workspace_write.network_access`: always disabled when sandbox is `workspace-write`.
- provider/model/temperature controls are grouped inside a dedicated launch-form card (`Provider`):
  - this card is shown only for `LangChain` runtime;
  - provider selector (`OpenAI`, `Anthropic`, `LM Studio`) uses the same compact dropdown size/typography as schedule pattern
- model field is bound to provider model cache from `agents_settings.json` and supports manual typing plus dropdown selection from the expand icon
  - expand icon uses toggle behavior: first click opens the model list, second click closes it
  - model list is opened only by the expand icon; focusing/typing in the model field and the refresh action do not open the list
- changing provider immediately swaps the available model list, updates the selected model for that provider, and triggers a non-forced model reload
- refresh icon is embedded into the model selector (left of the expand/collapse icon) and forces provider model cache reload
- model selector occupies flexible row width with an inline `Model` label; temperature keeps an inline `Temperature` label and a compact fixed-width single-line field
- scheduling controls are grouped inside a dedicated launch-form card (`Schedule`), aligned with other cards in the app.
- schedule card shows helper text `Regular task run settings`.
- schedule mode is controlled by an always-visible pattern selector in the card header row.
  selector uses doubled width relative to the `Theme` control (2x theme control width), stays pinned to the top-right,
  and does not shift the schedule helper text position when switching between `Disabled` and active modes.
- selector options include `Disabled`, pattern presets (`Every N minutes`, `Every N hours`, `Daily`, `Weekdays`,
  `Weekly`, `Monthly`), and `Advanced cron`.
  - `Disabled`: schedule controls are hidden and primary action is `Launch`.
  - any non-`Disabled` option: schedule controls are visible and primary action is `Schedule`.
- if an agent already has a schedule and selector is set to `Disabled`, `Launch` first clears the schedule and then starts
  a manual run; if schedule clear fails, manual run is not started.
- schedule controls include:
  - weekly day checkboxes are displayed in one horizontal row (Mon-Sun);
  - raw cron input in advanced mode;
  - inline validation and `Next runs` preview (3 upcoming executions).
- if persisted cron is unsupported by the simple preset parser, launch form opens in advanced mode and preserves the
  original cron string without normalization.
- schedule delete action
- defaults for manual launches:
  - when no schedule is set, prompt/runtime/provider/model/temperature/codex/workspace/access are initialized from per-agent
    `manualLaunchDefaults`
  - when a schedule exists, schedule prompt/cron/runtime settings remain the primary defaults for schedule editing
  - workspace/access also prefer `schedule.fileSystem` when schedule exists.
- manual and scheduled submit paths are server-validated; Codex launches are rejected with
  `Codex provider is disabled in Agent Settings` when global gate is off.

Agent Settings (brain/gear rail icon):

- rail icon composition is fixed: left half from `cyber_brain`, right half from `Outlined.Settings`, clipped at `x >= 480` in `agent_settings.svg`
- OpenAI, Anthropic, and LM Studio blocks
- provider cards are single-row: fixed-width title/subtitle column on the left and input fields on the right
  (`endpoint + api key` for OpenAI/Anthropic, `endpoint` only for LM Studio)
- `Enable Codex provider` switch (global feature gate for runtime `CODEX_CLI`)
- `AI features` card:
  - top-right runtime selector is always visible with options `Disabled`, `LangChain`, `Codex CLI`;
  - selector stays pinned in header row and does not shift when card content height changes;
  - `Disabled`: runtime fields are hidden;
  - `LANGCHAIN`: one-row `provider/model/temperature`;
  - `CODEX_CLI`: one-row `model/reasoning`;
  - model fields are editable and include refresh + expandable model list;
  - `codex.command` and `webSearch` are not editable in this card; `webSearch` is saved as `false`.
- `Agent run notifications` switch (default enabled, persisted in `ui.json` as `agentRunNotificationsEnabled`)
- endpoint override defaults:
  - OpenAI: `https://api.openai.com/v1`
  - Anthropic: `https://api.anthropic.com`
  - LM Studio: `http://127.0.0.1:1234/v1`
- strict endpoint validation (`http(s)` + host) with inline error message
- API key fields are shown only for OpenAI/Anthropic and show `********` when a key is already stored
- LM Studio has no token field (no auth required)
- LM Studio model list and run calls force HTTP/1.1 (no `h2c` upgrade header) for local compatibility
- provider changes are applied with the Agent Settings FAB save action

Settings (gear rail icon):

- keeps MCP runtime and general desktop UI settings (ports/timeouts/retries, HTTPS handling, tray icon,
  logs, theme, adapter mode, prompt/resource tool fallback)
- no longer includes agent provider or agent notification controls
