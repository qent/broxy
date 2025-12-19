# UI capabilities: cache, statuses, and background refresh

This document describes the UI-oriented capability subsystem (for display and validation). It is
separate from the proxy preset filtering pipeline.

## Where it is used

`AppStore` in ui-adapter maintains:

- the server list and connection statuses;
- capability snapshots for UI (tool/prompt/resource counts and argument summaries);
- background refresh based on the configured interval.

UI (Compose Desktop) uses these snapshots to display compact summaries in:

- server list cards (enabled + available servers);
- preset summary rows.

Files:

- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/store/AppStore.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/capabilities/CapabilityRefresher.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/capabilities/CapabilityCache.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/capabilities/ServerStatusTracker.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/capabilities/CapabilitySnapshots.kt`

## Layer separation: UI snapshots vs proxy capabilities

Important distinction:

1) `core.mcp.ServerCapabilities` - raw MCP capabilities (ToolDescriptor/PromptDescriptor/ResourceDescriptor)
   used by `ProxyMcpServer` for filtering and routing.

2) `core.capabilities.ServerCapsSnapshot` - UI-friendly summary:
   - simplified `ToolSummary/PromptSummary/ResourceSummary`;
   - argument lists derived from JSON Schema (best-effort);
   - includes `serverId` and `name` for display.

UI snapshots never participate in routing; they are for inspection and display only.

## CapabilityRefresher: orchestrator

File: `core/src/commonMain/kotlin/io/qent/broxy/core/capabilities/CapabilityRefresher.kt`

Dependencies:

- `capabilityFetcher: (McpServerConfig, timeoutSeconds) -> Result<ServerCapabilities>`.
  - JVM UI implementation uses `DefaultMcpServerConnection(...).getCapabilities(forceRefresh=true)`:
    `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/services/ToolServiceJvm.kt`.
- `capabilityCache: CapabilityCache` - snapshot + timestamp.
- `statusTracker: ServerStatusTracker` - transient UI statuses.
- `serversProvider()` - current server list from store snapshot.
- `capabilitiesTimeoutProvider()` - timeout from config.
- `publishUpdate()` - callback to rebuild UI state.
- `refreshIntervalMillis()` - interval from config (UI enforces minimum 30s).

### When the cache is refreshed

`refreshEnabledServers(force)`:

- filters to `serversProvider().filter { enabled }`;
- skips servers that are not due (`CapabilityCache.shouldRefresh(...)`) unless `force=true`;
- refreshes in parallel via `fetchAndCacheCapabilities(...)`.

On `AppStore.start()`:

- `refreshEnabledServers(force=true)`;
- then `restartBackgroundJob(enableBackgroundRefresh)`.

### Background job

`restartBackgroundJob(enabled)`:

- cancels the previous job;
- when enabled, runs a loop:
  - `delay(refreshIntervalMillis())`
  - `refreshEnabledServers(force=false)`

### Status tracking

Before a refresh:

- `statusTracker.setAll(targetIds, Connecting)`

After results:

- disabled -> `Disabled`
- snapshot exists -> `Available`
- no snapshot -> `Error`

`connectingSince(...)` is used by the UI to show a running timer while a server is connecting.

## Snapshot conversion details

File: `core/src/commonMain/kotlin/io/qent/broxy/core/capabilities/CapabilitySnapshots.kt`

### Tool arguments from JSON Schema

Algorithm:

- read `ToolDescriptor.inputSchema.properties` and `required`;
- infer type labels from:
  - `type`, `items`, `anyOf/oneOf/allOf`, `enum`, `format`;
- build `CapabilityArgument(name, type, required)` entries.

This is best-effort; complex schemas may produce empty argument lists.

### Resource arguments from URI

If `ResourceDescriptor.uri` contains `{placeholder}`, each placeholder becomes a
`CapabilityArgument(name=..., required=true)`.

## Relationship to proxy runtime

UI snapshots and proxy runtime are separate but share configuration:

- UI updates timeouts via:
  - `ProxyLifecycle.updateCallTimeout(...)`
  - `ProxyLifecycle.updateCapabilitiesTimeout(...)`

`CapabilityRefresher` uses `capabilitiesTimeoutSeconds` from store snapshots for
server validation and background refresh.
