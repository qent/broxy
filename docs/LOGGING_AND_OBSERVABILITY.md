# Логирование и наблюдаемость

## Логгер: интерфейс и реализации

Интерфейс:
- `core/src/commonMain/kotlin/io/qent/broxy/core/utils/Logger.kt`

Реализации:

- `ConsoleLogger` — пишет в stdout (удобно для локального дебага).
- `FilteredLogger(minLevel, delegate)` — фильтрация по уровню (используется в CLI).
- `CollectingLogger(delegate)` — пишет в delegate и публикует события через `SharedFlow<LogEvent>`.
  - файл: `core/src/commonMain/kotlin/io/qent/broxy/core/utils/CollectingLogger.kt`

### Важный нюанс STDIO режима

Для STDIO inbound stdout является частью MCP протокола, поэтому для CLI используется логгер в stderr:

- `cli/src/main/kotlin/io/qent/broxy/cli/support/StderrLogger.kt`
- `cli/src/main/kotlin/io/qent/broxy/cli/commands/ProxyCommand.kt` → `FilteredLogger(min, StderrLogger)`

## JSON-логирование (структурированные события)

Файл:
- `core/src/commonMain/kotlin/io/qent/broxy/core/utils/JsonLogging.kt`

Формат события:

```json
{
  "timestamp": "2025-..-..T..:..:..Z",
  "event": "event.name",
  "payload": { ... }
}
```

API:

- `Logger.debugJson(event) { ... }`
- `Logger.infoJson(event) { ... }`
- `Logger.warnJson(event, throwable) { ... }`
- `Logger.errorJson(event, throwable) { ... }`

## Ключевые точки логирования по флоу

### LLM → facade → downstream → facade → LLM

Файлы:

- `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/SdkServerFactory.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/RequestDispatcher.kt`

События:

- `llm_to_facade.request` — входящий `tools/call` (имя/аргументы/meta).
- `facade_to_downstream.request` — “после резолвинга” (resolvedServerId, downstreamTool).
- `downstream.response` / `downstream.response.error` — результат от downstream.
- `facade_to_llm.response` / `facade_to_llm.error` — ответ/ошибка, которые уйдут клиенту.
- `proxy.tool.denied` — отказ по allow-list пресета.
- `facade_to_llm.decode_failed` — downstream payload не декодируется как `CallToolResult`.

### STDIO downstream: сырой JSON-RPC

Файл:
- `core/src/jvmMain/kotlin/io/qent/broxy/core/mcp/clients/StdioMcpClient.kt`

`LoggingTransport` пишет строки вроде:

- `STDIO tools/list request id=...`
- `STDIO raw tools/list response: { ... }`
- `STDIO raw tools/list_changed notification: { ... }`

Это полезно для диагностики “почему список инструментов не обновляется”.

### Remote WebSocket: сводка JSON-RPC

Файл:
- `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/remote/ws/ProxyWebSocketTransport.kt`

`describeJsonRpcPayload(...)` пишет:

- тип сообщения (request/response/notification/error),
- id/method,
- параметры (`name/uri`, ключи arguments/meta),
- для capabilities — кол-во tools/prompts/resources и preview имён.

См. также: `docs/websocket-preset-capabilities.md`.

## Рекомендации по трассировке для AI-агентов

1) Для диагностики вызова инструмента ищите в логах цепочку:
   - `llm_to_facade.request` → `facade_to_downstream.request` → `downstream.response` → `facade_to_llm.response`.

2) При отказах по пресету:
   - ищите `proxy.tool.denied` и проверяйте `allowedPrefixedTools` в текущем пресете.

3) При “пустых capabilities”:
   - проверьте логи `DefaultMcpServerConnection.getCapabilities(...)` (успех/ошибка/кеш);
   - для STDIO используйте `STDIO raw ...` события;
   - помните, что `KtorMcpClient.fetchCapabilities()` может вернуть пустые списки по таймауту отдельных операций.

