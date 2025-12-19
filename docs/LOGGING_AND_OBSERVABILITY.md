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

- `cli/src/main/kotlin/io/qent/broxy/cli/support/StderrLogger.kt`
- `cli/src/main/kotlin/io/qent/broxy/cli/commands/ProxyCommand.kt` -> `FilteredLogger(min, StderrLogger)`

Headless UI mode uses `StdErrLogger` and also writes a daily file log.

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

- `Logger.debugJson(event) { ... }`
- `Logger.infoJson(event) { ... }`
- `Logger.warnJson(event, throwable) { ... }`
- `Logger.errorJson(event, throwable) { ... }`

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

### STDIO downstream: raw JSON-RPC

File:

- `core/src/jvmMain/kotlin/io/qent/broxy/core/mcp/clients/StdioMcpClient.kt`

`LoggingTransport` emits:

- `STDIO tools/list request id=...`
- `STDIO raw tools/list response: { ... }`
- `STDIO raw tools/list_changed notification: { ... }`

These help diagnose missing or stale capabilities.

### Remote WebSocket: JSON-RPC summaries

File:

- `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/remote/ws/ProxyWebSocketTransport.kt`

`describeJsonRpcPayload(...)` logs:

- message type (request/response/notification/error)
- id/method
- params keys (name/uri, argument/meta keys)
- capability counts and schema field previews

See also: `docs/websocket_preset_capabilities.md`.

## Tracing guidance

1) For tool call diagnostics, look for:
    - `llm_to_facade.request` -> `facade_to_downstream.request` -> `downstream.response` -> `facade_to_llm.response`.

2) For preset denials:
    - check `proxy.tool.denied` and the current `allowedPrefixedTools`.

3) For empty capabilities:
    - check `DefaultMcpServerConnection.getCapabilities(...)` logs;
    - for STDIO, use `STDIO raw ...` lines;
    - remember that `KtorMcpClient.fetchCapabilities()` returns empty lists on per-operation timeouts.
