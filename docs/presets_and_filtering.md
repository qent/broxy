# Presets and capability filtering (tools/prompts/resources)

## Terms

- Downstream capabilities: the raw `tools/resources/prompts` exposed by each downstream MCP server.
- Filtered capabilities: the view published by broxy after preset filtering.
- Preset: a declarative allow list and scope definition for tools, prompts, and resources.

## Preset model

File: `core/src/commonMain/kotlin/io/qent/broxy/core/models/Preset.kt`

```kotlin
data class Preset(
    val id: String,
    val name: String,
    val tools: List<ToolReference> = emptyList(),
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

Notes:

- If `prompts` or `resources` are omitted in JSON, they deserialize as `null`, which means
  "do not restrict" (include all items from in-scope servers).
- If `prompts` or `resources` are present but empty, the filter restricts them to none.
- `Preset.empty()` produces empty lists for tools/prompts/resources and therefore exposes no capabilities.

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
3) if present, copy the descriptor and rewrite `name` to `"$serverId:${tool.name}"`.

At the same time:

- `allowedPrefixedTools += "$serverId:${tool.name}"`

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

### Step 5: routing maps

After selection, the filter populates:

- `promptServerByName[prompt.name] = serverId` (first win via `putIfAbsent`)
- `resourceServerByUri[uriOrName] = serverId` (first win via `putIfAbsent`)

If the same prompt/resource name exists on multiple servers, the first in the iteration order wins.

## Applying presets in ProxyMcpServer

File: `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/ProxyMcpServer.kt`

Runtime fields:

- `currentPreset`
- `filteredCaps`
- `allowedTools`
- `promptServerByName`
- `resourceServerByUri`

Key methods:

- `refreshFilteredCapabilities()`:
    1) fetches downstream caps in parallel;
    2) applies `presetEngine.apply(all, preset)` -> `FilterResult`;
    3) updates `filteredCaps/allowedTools/...`;
    4) logs missing tools.

- `applyPreset(preset)`:
    - updates `currentPreset`;
    - calls `refreshFilteredCapabilities()` via `runBlocking`.

## Enforcement: denying disallowed tools

Even if the client sees a tool in `tools/list`, enforcement happens in `tools/call`:

- `DefaultRequestDispatcher.dispatchToolCall(...)` checks `allowedPrefixedTools`.
- In proxy mode `allowAllWhenNoAllowedTools = false`, so an empty allow list denies all tool calls.

File: `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/RequestDispatcher.kt`

## Runtime preset switching: behavior and limits

### UI (AppStore)

- `AppStoreIntents.selectProxyPreset(presetId)` updates `selectedPresetId`, persists it to `mcp.json`,
  and applies the preset to the running proxy without restarting inbound.

Files:

- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/store/internal/AppStoreIntents.kt`
- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/store/internal/ProxyRuntime.kt`

### CLI (ConfigurationWatcher)

Preset file change triggers:

- `ProxyLifecycle.applyPreset(preset)` -> `ProxyController.applyPreset(...)` -> `ProxyMcpServer.applyPreset(...)`.

Inbound is not recreated; the SDK `Server` is re-synced, so `tools/list`, `prompts/list`, and
`resources/list` update without a process restart.

Files:

- `cli/src/main/kotlin/io/qent/broxy/cli/commands/ProxyCommand.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/runtime/ProxyLifecycle.kt`
