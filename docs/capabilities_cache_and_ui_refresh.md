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
- refresh jobs are supervised so a single server failure/cancellation does not cancel the rest.
- cancels any in-flight refreshes when a server is disabled or removed to stop further reconnect attempts.

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

As each server finishes:

- disabled -> `Disabled`
- snapshot exists -> `Available`
- no snapshot -> `Error` (stores the latest error message for UI display)

`connectingSince(...)` is used by the UI to show a running timer while a server is connecting.

UI status derivation treats `Connecting` from `ServerStatusTracker` as higher priority than cached
snapshots so refresh-in-progress still shows the timer even if an older snapshot exists.

## UI toggle flow (enable/disable)

When a server is toggled from the UI:

- Enable: the switch flips on immediately, the status becomes `Connecting`, and the timer starts at
  the moment of the toggle. The switch is disabled while the server is connecting, and the timer
  keeps running (without reset) until capabilities arrive. Once capabilities are available, the
  status becomes `Available` and the switch is re-enabled.
- Disable: the switch flips off immediately and the card is rendered as disabled. The switch stays
  disabled while the disconnect/update is in flight, then becomes interactive again once the
  server is fully stopped.

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

When the proxy is running, UI snapshots are updated from proxy capability updates
instead of triggering direct capability fetches. This avoids spawning duplicate
downstream connections and keeps UI data in sync with `ProxyMcpServer` refreshes.
Background refresh is disabled while proxy capability updates are active, and
manual refresh falls back to UI polling only when the proxy is not running.

Proxy capability updates are incremental: UI caches are updated only for servers
present in the snapshot payload. Missing servers keep their previous status/cached
data (typically `Connecting` during startup) so slow servers do not briefly show
`Error` while other servers are still refreshing. If a refresh cycle fails for a
server that has no cached capabilities, the proxy emits a status update so the UI
switches to `Error` after retries are exhausted.
