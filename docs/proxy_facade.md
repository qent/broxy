# Proxy facade and request routing (ProxyMcpServer + MCP SDK)

## What the facade is in broxy

The facade is the combined stack of:

1) Inbound transport (STDIO or Streamable HTTP) that accepts MCP JSON-RPC messages and returns responses.
2) MCP SDK `Server`, built by:
    - `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/SdkServerFactory.kt` -> `buildSdkServer(proxy)`
3) `ProxyMcpServer`, which filters capabilities by preset and routes calls downstream.

The purpose is to expose `ProxyMcpServer` as a standard MCP server without mixing wire adapters with
routing and filtering logic.

## Key classes

- Inbound:
    - `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/InboundServers.kt`
    - `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/SdkServerFactory.kt`
- Proxy:
    - `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/ProxyMcpServer.kt`
    - `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/RequestDispatcher.kt`
    - `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/NamespaceManager.kt`
    - `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/ToolFilter.kt`

## Namespace contract: `serverId:toolName`

### Why the prefix is required

Multiple downstream servers can expose the same tool name. broxy avoids collisions by requiring
prefixed tool names:

- inbound tool name: `serverId:toolName`
- downstream tool invocation: `toolName` (prefix removed)

Implementation:

- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/DefaultNamespaceManager.parsePrefixedToolName(...)`
- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/DefaultNamespaceManager.prefixToolName(...)`

### Format errors

If the name does not match `serverId:tool`, `parsePrefixedToolName` throws `IllegalArgumentException`.
Clients that omit the prefix are rejected.

## Layer 1: InboundServer -> MCP SDK Server

### STDIO inbound

- `InboundServerFactory.create(...)` returns `StdioInboundServer`.
- `StdioInboundServer.start()` builds a `StdioServerTransport` and creates an SDK session:
  `server.createSession(transport)`.

File: `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/InboundServers.kt`

### Streamable HTTP inbound

`KtorStreamableHttpInboundServer` starts an embedded Ktor server (Netty):

- `POST .../mcp` accepts MCP JSON-RPC over HTTP in JSON-only Streamable HTTP mode and returns
  `application/json` for requests (`JSONRPCResponse`).
- `mcp-session-id` is passed via a header (not query) and enables multi-session handling.
- `GET` returns `405 Method Not Allowed` (SSE is not supported).
- `DELETE` closes the session and removes it from the registry.

Multi-session registry:

- `InboundStreamableHttpRegistry` stores `sessionId -> ServerSession`.

File: `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/InboundServers.kt`

## Layer 2: MCP SDK Server -> ProxyMcpServer

### Register tools/prompts/resources

`buildSdkServer(proxy)` syncs the SDK `Server` with the current filtered capabilities and registers:

- tools: `server.addTool(...)` / `server.addTools(...)`
- prompts: `server.addPrompt(...)` / `server.addPrompts(...)`
- resources: `server.addResource(...)` / `server.addResources(...)`

File: `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/SdkServerFactory.kt`

### Runtime capability updates on preset change

When `ProxyController.applyPreset(...)` is called broxy:

1) recomputes the filtered view (capabilities + allow list + routing maps) in `ProxyMcpServer`;
2) re-syncs the running SDK `Server` via `syncSdkServer(...)`.

Inbound (STDIO or Streamable HTTP) does not restart; `tools/list`, `prompts/list`, and `resources/list`
reflect the new preset within the same session.

### Runtime updates on downstream enable/disable

When the downstream set changes, `ProxyController.updateServers(...)`:

1) diffs enabled servers and reuses existing per-server runtimes;
2) updates downstream connections in `ProxyMcpServer` (without recreating inbound);
3) removes capabilities for disabled servers and refreshes capabilities only for added/edited servers;
4) re-syncs the SDK `Server` via `syncSdkServer(...)`.

Each downstream server runs on its own single-thread dispatcher so lifecycle work is isolated.
The inbound facade stays up, but published capabilities change.

## Layer 3: ProxyMcpServer -> RequestDispatcher -> downstream

### ProxyMcpServer responsibilities

`ProxyMcpServer` maintains:

- current preset and filtered view (capabilities + allow list + routing maps);
- prompt/resource routing maps derived from the filtered view;
- delegation of inbound requests to `RequestDispatcher`.

File: `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/ProxyMcpServer.kt`

Key methods:

- `start(preset, transport)` - stores the preset and marks the proxy running; capability
  refresh happens asynchronously as servers connect.
- `refreshFilteredCapabilities()` - fetches downstream capabilities and applies the preset filter.
- `callTool(toolName, arguments)` - delegates to `dispatcher.dispatchToolCall(...)`.
- `getPrompt(name, arguments)` - delegates to `dispatcher.dispatchPrompt(...)`.
- `readResource(uri)` - delegates to `dispatcher.dispatchResource(...)`.

`getPrompt` and `readResource` also validate that the requested item exists in the filtered view.

### DefaultRequestDispatcher: checks and routing

File: `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/RequestDispatcher.kt`

Core logic:

1) Allow list enforcement:
    - `allowedPrefixedTools()` returns a set.
    - If the set is non-empty and `request.name` is not in it, the call is rejected.
    - In proxy mode, `ProxyMcpServer` sets `allowAllWhenNoAllowedTools = false`, so an empty
      allow list denies all tool calls.
2) Namespace parsing:
    - `namespace.parsePrefixedToolName(name)` -> `(serverId, tool)`
3) Downstream lookup:
    - `servers.firstOrNull { it.serverId == serverId }`
4) Tool call without prefix:
    - `server.callTool(tool, request.arguments)`
5) Prompts/resources:
    - First use the maps computed by the preset filter.
    - If missing, fall back to scanning capabilities via `MultiServerClient.fetchAllCapabilities()`.

### Batch calls

`dispatchBatch(requests)` runs tool calls in parallel with `async/awaitAll`. This is not MCP batch
on the wire; it is a convenience for internal use.

## Result decoding and fallback

`SdkServerFactory` attempts to decode downstream responses into MCP SDK types:

- `decodeCallToolResult(json, element)`
- `decodePromptResult(json, element)`

If the payload is incompatible:

- the content blocks are normalized (adds missing `type` where it can be inferred);
- otherwise `fallbackCallToolResult(raw)` produces a `CallToolResult` with text content
  and a best-effort `structuredContent` object.

File: `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/SdkServerFactory.kt`

## Facade logging (key events)

Structured JSON events (via `Logger.infoJson/warnJson/errorJson`):

- `llm_to_facade.request` - inbound `tools/call` (name/arguments/meta).
- `facade_to_downstream.request` - resolved serverId/tool for downstream.
- `downstream.response` / `downstream.response.error` - downstream result.
- `facade_to_llm.response` / `facade_to_llm.error` - response/error returned to the client.
- `proxy.tool.denied` - tool call denied by preset allow list.
- `facade_to_llm.decode_failed` - downstream payload could not be decoded as `CallToolResult`.

Files:

- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/RequestDispatcher.kt`
- `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/SdkServerFactory.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/utils/JsonLogging.kt`
