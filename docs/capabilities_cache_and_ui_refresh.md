# UI capabilities: cache, statuses, and background refresh

## Purpose

Define UI capability snapshot caching, status derivation, and refresh orchestration separate from proxy filtering.

## When to read

- When changing capability refresh timing/status semantics.
- When changing persisted snapshot format or storage paths.
- When changing interaction between proxy runtime updates and UI refresh loops.

## Source-of-truth files

- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/capabilities/CapabilityRefresher.kt`
- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/capabilities/CapabilityCache.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/capabilities/PersistedCapabilitySnapshots.kt`
- `core/src/jvmMain/kotlin/io/qent/broxy/core/capabilities/FilePersistedCapabilityCacheStore.kt`

## Behavior contract

UI capability snapshots are read-only presentation caches and do not directly participate in request routing.

This document describes the persisted capability snapshot subsystem used by the UI and by JVM
preset-management inspection fallback. It is separate from the proxy preset filtering pipeline.

## Where it is used

`AppStore` in ui-adapter maintains:

- the server list and connection statuses;
- capability snapshots for UI (tool/prompt/resource counts and argument summaries);
- background refresh based on the configured interval.

JVM preset-management backend (`JvmPresetManagementBackend`) also reads the same persisted snapshot
format when live runtime capabilities are unavailable (`capabilities_source = "cached"`).

UI (Compose Desktop) uses these snapshots to display compact summaries in:

- server list cards (enabled + available servers);
- preset summary rows (including orange warning/tint for partial unavailability and reduced card
  opacity when all preset capabilities are unavailable at runtime, including references to disabled
  or removed servers).
- preset/agent capability editors: the selector and selected-capabilities cards read cached snapshots
  for all configured servers (enabled and disabled). Disabled servers are shown only when a cached
  snapshot exists and are visually marked as `Disabled`.
- server capability details screen (`ServerCapabilitiesScreen`) where tool/prompt/resource text
  (name, arguments, description) is selectable for copy.
- these snapshots are read-only UI/runtime availability data and do not rewrite saved preset capability
  references when a preset is opened and saved.

Server list cards also show a transport label derived from the transport config. STDIO uses "STDIO",
except when the command resolves to `docker`, which is shown as "Docker" to highlight the Docker-based
STDIO launch.
Both server and preset list cards support drag-and-drop reordering in the UI; this affects list
ordering only and does not change capability snapshot content.
Both list types use the same horizontal card padding for the reorder handle area, so drag indicators
align consistently from the left edge and relative to the following content block.

Files:

- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/store/AppStore.kt`
- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/capabilities/CapabilityRefresher.kt`
- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/capabilities/CapabilityCache.kt`
- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/capabilities/ServerStatusTracker.kt`
- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/capabilities/CapabilitySnapshots.kt`
- `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/capabilities/FileCapabilityCachePersistence.kt`

## Persistent cache storage

UI capability snapshots are persisted to the system cache directory (not `~/.config/broxy`).
JVM builds resolve the cache root via `AppCacheDir` and store JSON entries under a
`capabilities/` subfolder, e.g.:

- macOS: `~/Library/Caches/broxy/capabilities/`
- Linux: `${XDG_CACHE_HOME:-~/.cache}/broxy/capabilities/`
- Windows: `%LOCALAPPDATA%\\broxy\\Cache\\capabilities\\`

Shared schema and file store:

- schema DTOs and conversions: `core/src/commonMain/kotlin/io/qent/broxy/core/capabilities/PersistedCapabilitySnapshots.kt`
- JVM file store: `core/src/jvmMain/kotlin/io/qent/broxy/core/capabilities/FilePersistedCapabilityCacheStore.kt`
- ui-adapter bridge: `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/capabilities/FileCapabilityCachePersistence.kt`

## Layer separation: snapshot model vs proxy capabilities

Important distinction:

1) `core.mcp.ServerCapabilities` - raw MCP capabilities (ToolDescriptor/PromptDescriptor/ResourceDescriptor)
   used by `ProxyMcpServer` for filtering and routing.

2) `ui-adapter.capabilities.ServerCapsSnapshot` - UI-friendly summary:
    - simplified `ToolSummary/PromptSummary/ResourceSummary`;
    - argument lists derived from JSON Schema (best-effort);
    - includes `serverId` and `name` for display.

Snapshots never participate in request routing. They are used for inspection/display and, for
preset-management tools, read-only fallback descriptions when live capabilities are missing.

## CapabilityRefresher: orchestrator

File: `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/capabilities/CapabilityRefresher.kt`

Dependencies:

- `capabilityFetcher: (McpServerConfig, timeoutSeconds, retryCount, AuthorizationStatusListener?) -> Result<ServerCapabilities>`.
    - JVM UI implementation uses `DefaultMcpServerConnection(...).getCapabilities(forceRefresh=true)`:
      `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/services/ToolServiceJvm.kt`.
- `capabilityCache: CapabilityCache` - snapshot + timestamp.
- `statusTracker: ServerStatusTracker` - transient UI statuses.
- `serversProvider()` - current server list from store snapshot.
- `capabilitiesTimeoutProvider()` - timeout from config.
- `connectionRetryCountProvider()` - retry count from config.
- `publishUpdate()` - callback to rebuild UI state.
- `refreshIntervalMillis()` - interval from config (UI enforces minimum 30s).

### When the cache is refreshed

`refreshEnabledServers(force)`:

- filters to `serversProvider().filter { enabled }`;
- skips servers that are not due (`CapabilityCache.shouldRefresh(...)`) unless `force=true`;
- refreshes in parallel via `fetchAndCacheCapabilities(...)`.
- refresh jobs are supervised so a single server failure/cancellation does not cancel the rest.
- refreshes are de-duplicated per server; if a refresh is already active, new refreshes for that server are skipped.
- cancels any in-flight refreshes when a server is disabled or removed to stop further reconnect attempts.

Editor selectors do not trigger refreshes for disabled servers. They only read existing cache entries
via `AppStore.listSelectableServerCaps()` / `CapabilityRefresher.listCachedServerCaps(...)`, so
disabled servers appear in selection UI only when cached capabilities already exist.

On `AppStore.start()`:

- attempts to start inbound proxy (force restart) to ensure the SDK server is active;
- if proxy capability updates are not active, calls `refreshEnabledServers(force=false)` (missing or stale entries are refreshed, cached entries stay);
- then `restartBackgroundJob(enableBackgroundRefresh)`.

Manual refresh:

- the server list includes a per-server refresh action (between the enable switch and edit button);
- `Intents.refreshServerCapabilities(serverId)` forces a refresh for that server. When the proxy is
  running, the refresh is delegated to `ProxyMcpServer.refreshServerCapabilities` and forces a
  downstream re-fetch (bypasses connection cache); otherwise `CapabilityRefresher.refreshServersById(force=true)`
  is used. While a manual refresh is in flight and a cached snapshot exists, the server list pulses
  the capabilities summary to show the in-progress request.

### Background job

`restartBackgroundJob(enabled)`:

- cancels the previous job;
- when enabled, runs a loop:
    - `delay(refreshIntervalMillis())`
    - `refreshEnabledServers(force=false)`
- `AppStore.stop()` disables the background job to prevent refreshes after shutdown.

### Status tracking

Before a refresh:

- `statusTracker.setAll(targetIds, Connecting)`

As each server finishes:

- disabled -> `Disabled`
- fetch error -> `Error` (stores the latest error message for UI display, cached snapshots remain)
- snapshot exists -> `Available`
- no snapshot -> `Error`

`connectingSince(...)` is used by the UI to show a running timer while a server is connecting.

UI status derivation treats `Error` as highest priority. Cached snapshots take precedence over
transient `Authorization`/`Connecting` updates so the list does not show authorization/connect timers
when a snapshot is already available. `Authorization` and `Connecting` are shown only when no cached
snapshot exists (initial connect); refresh activity for cached servers is indicated by the refresh
affordance instead of swapping the status.

For OAuth-capable HTTP/WS servers with no cached snapshot, the UI shows `Authorization` while OAuth
is in progress, then switches to `Connecting` once authorization completes and capabilities are
being fetched. In the desktop UI popup flow, OAuth first waits for explicit user permission to open
the browser (`Continue in Browser`) and, when several servers request OAuth at once, pending
requests wait in a FIFO popup queue until the active server flow completes. Headless/CLI flows are
bounded by the configured `authorizationTimeoutSeconds`.

## UI toggle flow (enable/disable)

When a server is toggled from the UI:

- Enable: the switch flips on immediately and stays interactive. If a cached snapshot exists, the
  card stays `Available` (no `Authorization`/`Connecting` states) until a refresh is due or the user
  forces a refresh. If no cache exists, the UI shows `Authorization` when OAuth starts (if required),
  then `Connecting` with the timer starting from the connection attempt until capabilities arrive,
  and finally `Available`.
- Disable: the switch flips off immediately and stays interactive. Any in-flight refresh or
  authorization flow for the server is cancelled, timers stop, and the card renders as `Disabled`.
  Rapid on/off sequences are allowed; background updates are serialized so the latest toggle wins.

## UI add server flow

- When a new server is added, the UI inserts its card into the server list immediately from the
  updated config snapshot.
- If no cached capabilities exist, the card shows `Connecting` (with a timer) while the initial
  capability fetch and/or proxy startup is in progress, then switches to `Available` once
  capabilities arrive.

## Snapshot conversion details

File: `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/capabilities/CapabilitySnapshots.kt`

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

`ProxyRuntimeFacade.capabilityUpdates` emits raw `ServerCapabilities` keyed by serverId.
`CapabilityRefresher` maps those to `ServerCapsSnapshot` before updating the cache and UI state.

When the proxy is running, UI snapshots are updated from proxy capability updates
instead of triggering direct capability fetches. This avoids spawning duplicate
downstream connections and keeps UI data in sync with `ProxyMcpServer` refreshes.
Proxy capability/status updates are also applied while the proxy is starting so
initial refresh failures are visible in the server list.
Background refresh is disabled while proxy capability updates are active,
including while the proxy is starting, and manual refresh falls back to UI
polling only when the proxy is not running.

Proxy capability updates are incremental: the raw `ServerCapabilities` map contains
only servers present in the payload, and UI caches are updated for those entries
after mapping to snapshots. Missing servers keep their previous status/cached data
(typically `Connecting` during startup when no snapshot exists) so slow servers do
not briefly show `Error` while other servers are still refreshing. If a refresh
cycle fails for a server, the proxy emits a status update so the UI switches to
`Error` even when cached capabilities exist. OAuth status updates (`Authorization`
then `Connecting`) surface only when the UI has no cached snapshot; otherwise the
card remains `Available` and the refresh affordance signals background work.
