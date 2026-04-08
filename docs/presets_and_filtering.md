# Presets and capability filtering (tools/prompts/resources)

## Purpose

Define canonical preset semantics and how Broxy computes filtered capability views and routing maps.

## When to read

- When changing preset JSON structure or built-ins.
- When changing filtering behavior (`tools`, `prompts`, `resources`).
- When changing runtime preset switching behavior in UI/CLI/inbound sessions.

## Source-of-truth files

- `core/src/commonMain/kotlin/io/qent/broxy/core/models/Preset.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/ToolFilter.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/ProxyMcpServer.kt`
- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/store/internal/PresetIntentsHandler.kt`

## Behavior contract

Tools are explicit allow-list entries. For prompts/resources, `null` means unrestricted (within in-scope
servers) and an empty list means deny all.

## Terms

- Downstream capabilities: the raw `tools/resources/prompts` exposed by each downstream MCP server.
- Filtered capabilities: the view published by Broxy after preset filtering.
- Preset: a declarative allow list and scope definition for tools, prompts, and resources.

## Preset model

File: `core/src/commonMain/kotlin/io/qent/broxy/core/models/Preset.kt`

```kotlin
data class Preset(
    val id: String,
    val name: String,
    val tools: List<ToolReference> = emptyList(),
    val agentTools: List<AgentToolReference> = emptyList(),
    val prompts: List<PromptReference>? = null,
    val resources: List<ResourceReference>? = null
)
```

References:

- `ToolReference(serverId, toolName, enabled)` - `core/src/commonMain/kotlin/io/qent/broxy/core/models/ToolReference.kt`
- `PromptReference(serverId, promptName, enabled)` -
  `core/src/commonMain/kotlin/io/qent/broxy/core/models/PromptReference.kt`
- `ResourceReference(serverId, resourceKey, enabled)` -
  `core/src/commonMain/kotlin/io/qent/broxy/core/models/ResourceReference.kt`
- `AgentToolReference(agentId, enabled)` -
  `core/src/commonMain/kotlin/io/qent/broxy/core/models/AgentToolReference.kt`

Notes:

- If `prompts` or `resources` are omitted in JSON, they deserialize as `null`, which means
  "do not restrict" (include all items from in-scope servers).
- If `prompts` or `resources` are present but empty, the filter restricts them to none.
- If `agentTools` is omitted in JSON, it deserializes as `emptyList()` (backward compatible).
- `Preset.empty()` produces empty lists for tools/prompts/resources and therefore exposes no capabilities.

### Preset semantics matrix

| Field shape | Meaning | Effective exposure |
| --- | --- | --- |
| `tools = []` | no explicit tools | no tools exposed |
| `prompts = null` | unrestricted prompts | all prompts from in-scope servers |
| `prompts = []` | explicit deny | no prompts exposed |
| `resources = null` | unrestricted resources | all resources from in-scope servers |
| `resources = []` | explicit deny | no resources exposed |
| built-in `__empty__` | forced empty preset | no tools/prompts/resources |
| built-in `__all_enabled__` | pass-through enabled servers | all capabilities from enabled servers |
| built-in `__preset_management__` | management-only facade mode | fixed management tools only |

## Built-in presets

Broxy reserves a few preset ids that are not backed by `preset_*.json` files:

- `__empty__` ("No preset") - always returns empty capabilities.
- `__all_enabled__` ("All enabled servers") - exposes tools/prompts/resources from every enabled
  downstream server (the enabled subset from config/runtime, not all servers in the config file).
- `__preset_management__` ("AI Preset management") - enables a fixed management-only MCP surface
  (`get_preset_creation_algorithm`, `list_server_names`, `get_server_description`,
  `list_preset_names`, `get_preset_description`, `create_preset`) and does not expose downstream
  tools/prompts/resources or adapter-mode tools.
  In desktop UI, a `🤘` Agentic Mode toggle for this preset enables an extended tool surface:
  `list_catalog_server_names`, `install_catalog_server`, `get_catalog_server_install_status`,
  `set_server_enabled`.

The UI treats these as fixed presets for selection, and the core filter bypasses allow-list checks
for `__all_enabled__` by building a full pass-through view with prefixed tool names.
Built-ins are resolved through `BuiltInPresetResolver` and are recognized consistently in UI runtime
switching, inbound preset-pinned routes (`/mcp/{presetId}`, `/sse/{presetId}`), and CLI startup.

## Filtering behavior (DefaultToolFilter)

File: `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/ToolFilter.kt`

Inputs:

- `all: Map<String, ServerCapabilities>` - all downstream capabilities keyed by `serverId`.
- `preset: Preset`.

Outputs:

- `FilterResult`:
    - `capabilities: ServerCapabilities` - filtered view (tools/prompts/resources).
    - `allowedPrefixedTools: Set<String>` - allow list for `tools/call` enforcement.
    - `missingTools` - tools referenced in preset but missing downstream.
    - `promptServerByName: Map<promptName, serverId>` - routing for `prompts/get`.
    - `resourceServerByUri: Map<uriOrName, serverId>` - routing for `resources/read`.

### Step 1: group desired entities

The filter builds:

- `desiredByServer` from `preset.tools.filter { enabled }` -> `groupBy(serverId)`.
- `desiredPromptsByServer` from `preset.prompts?.filter { enabled }` -> `groupBy(serverId)`.
- `desiredResourcesByServer` from `preset.resources?.filter { enabled }` -> `groupBy(serverId)`.

### Step 2: determine in-scope servers

`inScopeServers` is the union of server ids referenced by tools/prompts/resources.

Important:

- If tools are empty and prompts/resources are null or empty, `inScopeServers` is empty and the
  filtered capabilities view is empty.

### Step 3: tools - strict allow list + prefixing

For each `ToolReference(serverId, toolName)`:

1) check `toolName` exists in downstream capabilities;
2) if missing, add to `missingTools` and log a warning;
3) if present, copy the descriptor and rewrite `name` to `"$facadeServerId_${tool.name}"`, where
   `facadeServerId = serverId.removePrefix("io.qent.broxy/")`.

At the same time:

- `allowedPrefixedTools += "$facadeServerId_${tool.name}"`

This ensures:

- no name collisions;
- strict tool allow list (no tool appears unless referenced in the preset).

### Step 4: prompts/resources - null vs allow list

Restrict flags:

- `restrictPrompts = preset.prompts != null`
- `restrictResources = preset.resources != null`

Semantics:

- If `preset.prompts == null`, prompts are included in full (but only for in-scope servers).
- If `preset.prompts != null`, prompts are included only if present in `promptAllowList`.

Resources behave the same:

- allow list key is `(uri ?: name)` compared to `ResourceReference.resourceKey`.

These exact null-vs-empty rules are also used by preset-management inspection (`get_preset_description`):

- tools are always strict explicit allow-list only;
- `prompts = null` / `resources = null` means unrestricted within in-scope servers;
- `prompts = []` / `resources = []` means none;
- non-empty prompt/resource lists are explicit allow-lists.

### Step 5: routing maps

After selection, the filter populates:

- `promptServerByName[prompt.name] = serverId` (first win via `putIfAbsent`)
- `resourceServerByUri[uriOrName] = serverId` (first win via `putIfAbsent`)

If the same prompt/resource name exists on multiple servers, the first in the iteration order wins.

## Applying presets in ProxyMcpServer

File: `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/ProxyMcpServer.kt`

Runtime fields:

- `currentPreset`
- filtered view state (capabilities + allow list + routing maps)

Key methods:

- `refreshFilteredCapabilities()`:
    1) fetches downstream caps in parallel using a supervisor scope so one server failure does not
       cancel other fetches;
    2) applies `presetEngine.apply(all, preset)` -> `FilterResult`;
    3) updates the filtered view state (capabilities + allow list + routing maps);
    4) logs missing tools.

- `applyPreset(preset)`:
    - updates `currentPreset`;
    - re-filters using cached downstream capabilities immediately;
    - if no cached capabilities exist yet, performs a synchronous refresh to populate the view.

To force a downstream re-fetch when switching presets, call `refreshFilteredCapabilities()` explicitly
after `applyPreset(...)`.

## Enforcement: denying disallowed tools

Even if the client sees a tool in `tools/list`, enforcement happens in `tools/call`:

- `DefaultRequestDispatcher.dispatchToolCall(...)` checks `allowedPrefixedTools`.
- In proxy mode `allowAllWhenNoAllowedTools = false`, so an empty allow list denies all tool calls.

File: `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/RequestDispatcher.kt`

## Runtime preset switching: behavior and limits

### UI (AppStore)

- When loaded configuration has no `defaultPresetId` and no file-backed presets, AppStore activates
  `__preset_management__` so `AI Preset management` is active by default.
- `PresetIntentsHandler.selectProxyPreset(presetId)` (delegated via `AppStoreIntents`) applies the preset
  to the running proxy (no inbound restart).
  On success it updates `mcp.json` (`defaultPresetId`) and the UI shows the active preset only. If the
  apply fails, the UI keeps displaying the current active preset and shows a toast with the failure reason.
- In the Presets list UI, clicking a preset card selects it as the active preset. Editing remains an
  explicit action on the edit icon/button.
- When the active preset is `__preset_management__` and no file-backed presets exist, the Presets
  screen empty state shows the active mode and explains that presets can be created through a
  connected AI agent.
- Agentic Mode (`🤘`) is session-only UI state (not persisted in `ui.json`).
  When `__preset_management__` is active and the proxy is running, toggling Agentic Mode triggers
  `refreshFilteredCapabilities()` so `/mcp/__preset_management__` and `/sse/__preset_management__`
  resync their management tool surface without inbound restart.
- Preset cards now flag unavailable server references inline: if a preset contains enabled tool/prompt/resource
  references for servers that are currently disabled or removed from config, the card shows a warning
  ("Some capabilities are disabled"), tints affected capability type icon/count
  (tools/prompts/resources) in orange (`#D97706`) for partial unavailability.
  When no capabilities are available for the preset at runtime, the card keeps capability and warning
  text in the default color and is rendered with reduced opacity instead of a red highlight.
- The global header preset selector shows the same disabled-capability signal for the active preset:
  when the active preset references any disabled or missing server, an orange status dot is rendered next to
  the preset name (`#D97706`).
- In the global header preset selector dropdown, built-in presets (`No preset`, `All enabled servers`,
  `AI Preset management`) are separated from user-defined presets by a visual divider line.
- The built-in preset-management label is dynamic in desktop UI based on Agentic Mode (`🤘`):
  `AI Preset management` when disabled and `Agentic Mode` when enabled; this applies in both the
  global header preset selector and the tray menu.
- If the active preset has no available capabilities at runtime (for example, every capability
  reference is `enabled=false`, or all enabled references point only to disabled/missing servers), the status
  dot is bright dark red (`#C62828`) instead of orange.
- On macOS, the tray (status-bar) icon mirrors this signal: a dot is drawn in the icon's
  bottom-right corner, orange for partial unavailability and bright dark red when no capabilities are available.
- In preset editing (`PresetEditorScreen`), capability text is selectable both in the server capability
  selection form (`PresetSelector`) and in the selected capabilities cards (name, arguments, description).
- Preset editing uses cached capability snapshots for all configured servers (enabled and disabled) via
  `AppStore.listSelectableServerCaps()`. Disabled servers are rendered with a `Disabled` badge in the
  selection list, and selected capabilities from disabled servers remain visible in the summary cards with
  reduced opacity plus the same badge.
- In manual preset creation, selecting the server-level checkbox ("all capabilities for server") auto-fills
  the preset name with that server name only when the name field is still empty.
- In preset editing with an active capability search filter, the server-level checkbox applies only to
  currently visible capabilities (filtered tools/prompts/resources). Hidden capabilities are left unchanged.
- Opening and saving a preset without explicit capability changes must not rewrite its capability lists.
  Capability references from disabled, missing, or uncached servers remain in the preset file.
- Preset saving preserves `enabled=false` references. Preset capability content changes only when the user
  explicitly toggles capability selection in the editor.

Files:

- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/store/internal/AppStoreIntents.kt`
- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/store/internal/PresetIntentsHandler.kt`
- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/store/internal/ProxyRuntime.kt`

### CLI (ConfigurationWatcher)

Preset file change triggers:

- `ProxyLifecycle.applyPreset(preset)` -> `ProxyController.applyPreset(...)` -> `ProxyMcpServer.applyPreset(...)`.

Inbound is not recreated; the SDK `Server` is re-synced, so `tools/list`, `prompts/list`, and
`resources/list` update without a process restart.

Files:

- `cli/src/main/kotlin/io/qent/broxy/cli/commands/ProxyCommand.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/runtime/ProxyLifecycle.kt`

## Session-scoped preset binding for HTTP/SSE

HTTP inbound now supports both dynamic default routes and preset-pinned routes:

- default routes: `/mcp` and `/sse`
- preset-pinned routes: `/mcp/{presetId}` and `/sse/{presetId}`

Semantics:

- sessions opened through `/mcp` or `/sse` keep the historical behavior and always reflect the
  current active preset after `applyPreset(...)`;
- sessions opened through `/mcp/{presetId}` or `/sse/{presetId}` resolve the preset once when the
  session is created and then keep serving that pinned preset for the whole session lifetime;
- changing the active preset from the UI or CLI does not affect already-open preset-pinned sessions;
- downstream capability refresh still updates both kinds of sessions, but each session re-filters the
  refreshed raw capabilities through its own preset view.

Resolution rules:

- built-in preset ids `__empty__` and `__all_enabled__` are accepted on pinned routes;
- file-backed presets are loaded through `JsonConfigurationRepository.loadPreset(...)`;
- unknown preset ids return `404 Not Found` before a session is created.

Session safety:

- each inbound session stores both its `sessionId` and its route binding (`/mcp`, `/mcp/dev`,
  `/sse`, `/sse/dev`, and so on);
- if a client reuses a `sessionId` through a different route or a different preset id, Broxy returns
  `409 Conflict` instead of rebinding the session.

Implementation notes:

- the default routes reuse the main running `ProxyMcpServer`;
- preset-pinned routes create lightweight additional `ProxyMcpServer` instances that reuse the same
  downstream connection objects and the same raw capability snapshots, while keeping their own
  preset/filter state.

## Preset-level agent tools in UI

Preset editor includes an `Agents` block (after MCP servers):

- selectable list of available agents with checkbox + description;
- non-expandable cards (same visual language as MCP server cards);
- selected missing refs are preserved in draft/save payload even when target agent is unavailable.

Agent editor includes the same `Agents` block (after `Capabilities Source`):

- current edited agent is excluded from selectable list;
- for source mode `Capabilities Source = preset`, effective refs are:
  `preset.agentTools + agent.agentTools` (dedupe by `agentId`);
- unresolved refs remain persisted and are not dropped during edit/save.
