# Logging and observability

## Logger interface and implementations

Interface:

- `core/src/commonMain/kotlin/io/qent/broxy/core/utils/Logger.kt`

Implementations:

- `ConsoleLogger` - stdout (useful for local debug).
- `StdErrLogger` - stderr (use when STDIO is occupied by MCP protocol).
    - `core/src/commonMain/kotlin/io/qent/broxy/core/utils/StdErrLogger.kt`
- `DailyFileLogger(baseDir)` - `${baseDir}/logs/YYYY-MM-DD.log` (one file per day).
    - `core/src/jvmMain/kotlin/io/qent/broxy/core/utils/DailyFileLogger.kt`
- `FilteredLogger(minLevel, delegate)` - level filtering (CLI uses this).
- `CollectingLogger(delegate)` - delegates + publishes events via `SharedFlow<LogEvent>`.
    - `core/src/commonMain/kotlin/io/qent/broxy/core/utils/CollectingLogger.kt`
- `CompositeLogger(delegates)` - fan-out to multiple loggers.

### STDIO mode nuance

In STDIO mode stdout is part of the MCP protocol. CLI writes logs to stderr:

- `core/src/commonMain/kotlin/io/qent/broxy/core/utils/StdErrLogger.kt`
- `cli/src/main/kotlin/io/qent/broxy/cli/support/CliLoggerFactory.kt`
- `cli/src/main/kotlin/io/qent/broxy/cli/commands/ProxyCommandRunner.kt`

Headless UI mode uses `StdErrLogger` and also writes a daily file log.

### HTTP inbound logging

Streamable HTTP inbound uses Ktor `CallLogging`. ANSI colors are disabled to avoid
native Jansi initialization issues in packaged macOS builds.

## File logs

Logs are written next to the configuration directory:

- path: `${configDir}/logs/`
- file: `YYYY-MM-DD.log` (one file per day)
- line format: `YYYY-MM-DD HH:mm:ss.SSS LEVEL message`
    - newlines in messages are escaped as `\n` to keep one log per line.

## JSON logging (structured events)

File:

- `core/src/commonMain/kotlin/io/qent/broxy/core/utils/JsonLogging.kt`

Event format:

```json
{
  "timestamp": "2025-..-..T..:..:..Z",
  "event": "event.name",
  "payload": {
    "...": "..."
  }
}
```

API:

- `Logger.infoJson(event) { ... }`
- `Logger.warnJson(event, throwable) { ... }`
- `Logger.errorJson(event, throwable) { ... }`

### Redaction

JSON logs redact sensitive fields before serialization. `LogRedactor` replaces values whose keys
match `token|secret|password|key` (case-insensitive), including nested objects/arrays. Redaction is
applied to logged arguments, response payloads (downstream and facade), and decode-failed payloads.

### Log levels

- `info` = normal request/response path.
- `warn` = recoverable situation (fallbacks, policy denials, retryable conditions).
- `error` = request or operation failure.

Release UI builds strip `Logger.debug(...)` calls via Proguard so debug logs do not ship in packaged releases.

## Minimal JSON event profile (baseline)

The following JSON events and payload fields are required. Payloads may include additional fields,
but these keys must remain stable. Some fallback tool paths emit a reduced payload (noted below).
All request/response events include `requestType` and `name` (mirrors tool/prompt/resource fields).
Tool downstream events include `downstreamName` (same as `downstreamTool`).

- `llm_to_facade.request`
  - `toolName` (string)
  - `arguments` (object or null)
  - `meta` (object, optional)
- `facade_to_downstream.request`
  - `toolName` (string)
  - `resolvedServerId` (string)
  - `downstreamTool` (string)
  - `downstreamName` (string)
  - `arguments` (object)
- `downstream.response`
  - `toolName` (string)
  - `resolvedServerId` (string)
  - `downstreamTool` (string)
  - `downstreamName` (string)
  - `response` (json)
- `downstream.response.error`
  - `toolName` (string)
  - `resolvedServerId` (string)
  - `downstreamTool` (string)
  - `downstreamName` (string)
  - `errorMessage` (string)
- `facade_to_llm.response`
  - `toolName` (string)
  - `response` (json)
  - `targetServerId` (string, only for prefixed tool calls)
  - `downstreamTool` (string, only for prefixed tool calls)
  - `downstreamName` (string, only for prefixed tool calls)
- `facade_to_llm.error`
  - `toolName` (string)
  - `errorMessage` (string)
  - `targetServerId` (string, only for prefixed tool calls)
  - `downstreamTool` (string, only for prefixed tool calls)
  - `downstreamName` (string, only for prefixed tool calls)
- `proxy.tool.denied`
  - `toolName` (string)
  - `reason` (string)
- `facade_to_llm.decode_failed`
  - `toolName` (string)
  - `targetServerId` (string)
  - `downstreamTool` (string)
  - `rawResponse` (json)

### Prompt JSON events

- `llm_to_facade.prompt.request`
  - `promptName` (string)
  - `arguments` (object, optional)
- `facade_to_downstream.prompt.request`
  - `promptName` (string)
  - `resolvedServerId` (string)
  - `downstreamName` (string)
  - `arguments` (object, optional)
- `downstream.prompt.response`
  - `promptName` (string)
  - `resolvedServerId` (string)
  - `downstreamName` (string)
  - `response` (json)
- `downstream.prompt.response.error`
  - `promptName` (string)
  - `resolvedServerId` (string)
  - `downstreamName` (string)
  - `errorMessage` (string)
- `facade_to_llm.prompt.response`
  - `promptName` (string)
  - `response` (json)
- `facade_to_llm.prompt.error`
  - `promptName` (string)
  - `errorMessage` (string)

### Resource JSON events

- `llm_to_facade.resource.request`
  - `resourceUri` (string)
- `facade_to_downstream.resource.request`
  - `resourceUri` (string)
  - `resolvedServerId` (string)
  - `downstreamName` (string)
- `downstream.resource.response`
  - `resourceUri` (string)
  - `resolvedServerId` (string)
  - `downstreamName` (string)
  - `response` (json)
- `downstream.resource.response.error`
  - `resourceUri` (string)
  - `resolvedServerId` (string)
  - `downstreamName` (string)
  - `errorMessage` (string)
- `facade_to_llm.resource.response`
  - `resourceUri` (string)
  - `response` (json)
- `facade_to_llm.resource.error`
  - `resourceUri` (string)
  - `errorMessage` (string)

## Key logging points

### LLM -> facade -> downstream -> facade -> LLM

Files:

- `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/SdkServerFactory.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/RequestDispatcher.kt`

Events:

- `llm_to_facade.request` - inbound `tools/call` (name/arguments/meta).
- `facade_to_downstream.request` - resolved server/tool.
- `downstream.response` / `downstream.response.error` - downstream result.
- `facade_to_llm.response` / `facade_to_llm.error` - response/error sent to client.
- `proxy.tool.denied` - tool denied by preset allow list.
- `facade_to_llm.decode_failed` - downstream payload failed to decode.

Prompt/resource requests follow the same pattern with `.prompt.` and `.resource.` event names.

### STDIO downstream: raw JSON-RPC

File:

- `core/src/jvmMain/kotlin/io/qent/broxy/core/mcp/clients/StdioMcpClient.kt`

`LoggingTransport` emits:

- `STDIO tools/list request id=...`
- `STDIO raw tools/list response: { ... }`
- `STDIO raw tools/list_changed notification: { ... }`

These help diagnose missing or stale capabilities.

### OAuth (debug)

OAuth flows emit debug logs for each stage: discovery, dynamic registration, browser launch,
callback receipt, token exchange, and capability refresh start/end. Use these to measure
latency between authorization and capability fetch.

### HTTP client timing (debug)

Ktor MCP clients emit per-request timing logs with HTTP method, sanitized URL (no query),
status code, and elapsed milliseconds. These help pinpoint slow listTools/listResources
calls and OAuth metadata/token latencies.

## Tracing guidance

1) For tool call diagnostics, look for:
    - `llm_to_facade.request` -> `facade_to_downstream.request` -> `downstream.response` -> `facade_to_llm.response`.

2) For preset denials:
    - check `proxy.tool.denied` and the current `allowedPrefixedTools`.

3) For empty capabilities:
    - check `DefaultMcpServerConnection.getCapabilities(...)` logs;
    - for STDIO, use `STDIO raw ...` lines;
    - remember that `KtorMcpClient.fetchCapabilities()` returns empty lists on per-operation timeouts.
