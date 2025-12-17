# Inbound транспорты: STDIO и HTTP SSE

## Что такое inbound транспорт

Inbound — это “как broxy принимает входящие MCP JSON-RPC запросы от клиента”.

В JVM реализации поддерживаются:

- `STDIO` (локальный режим, удобно для IDE/агентов);
- `HTTP SSE` (удалённый режим для клиентов, которым нужен HTTP endpoint).

Файлы:

- `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/InboundServers.kt`
- `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/SdkServerFactory.kt`

## InboundServerFactory

`InboundServerFactory.create(transport, proxy, logger)`:

- `TransportConfig.StdioTransport` → `StdioInboundServer`
- `TransportConfig.HttpTransport` → `KtorInboundServer`
- другие типы inbound не поддерживаются (ошибка).

Важно: downstream поддерживает больше транспортов, чем inbound.

## STDIO inbound

`StdioInboundServer.start()`:

1) берёт `System.in`/`System.out`;
2) строит `StdioServerTransport` (MCP SDK);
3) строит MCP SDK `Server` через `buildSdkServer(proxy)`;
4) делает `server.connect(transport)` в `runBlocking`.

Особенность:

- STDIO режим требует, чтобы stdout был “чистым” для MCP протокола. Поэтому в CLI используется `StderrLogger` (логи в stderr).

См. также:
- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/runtime/ProxyController.kt` → `createStdioProxyController(...)` (специализированный factory).

## HTTP SSE inbound

### URL парсинг и нормализация

`KtorInboundServer` принимает `url` (например `http://0.0.0.0:3335/mcp`) и извлекает:

- host / port / path через `URI(url)`;
- если path пустой → default `/mcp`;
- если path заканчивается на `/` → отрезается trailing slash;
- route segments вычисляются через `normalizePath(...)`.

Файлы:
- `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/InboundServers.kt`

### SSE endpoint + POST endpoint

В `mountMcpRoute(...)` определены два обработчика:

1) `sse { ... }`:
   - создаёт `SseServerTransport(endpointSegments, this)`;
   - регистрирует transport в `InboundSseRegistry` по `sessionId`;
   - строит SDK `Server` через `serverFactory()`;
   - делает `server.connect(transport)`.

2) `post { ... }`:
   - читает `sessionId` из query параметра `sessionId`;
   - находит transport в registry;
   - вызывает `transport.handlePostMessage(call)` (MCP SDK helper).

Таким образом, клиент:

- держит SSE соединение для входящих сообщений от сервера;
- отправляет свои JSON-RPC запросы POST’ом с `sessionId`.

### Мультисессии

`InboundSseRegistry` (ConcurrentHashMap):

- `add(transport)` добавляет по `transport.sessionId`;
- `remove(sessionId)` удаляет;
- используется в `post` обработчике.

## Адаптер к MCP SDK (buildSdkServer)

Outbound API для MCP клиентов формирует SDK `Server`:

- регистрирует tools/prompts/resources из `proxy.getCapabilities()`;
- перенаправляет `callTool/getPrompt/readResource` в `ProxyMcpServer`.

Подробности — `docs/PROXY_FACADE.md`.

## CLI параметры для inbound

Файл: `cli/src/main/kotlin/io/qent/broxy/cli/commands/ProxyCommand.kt`

- `--inbound stdio|http` (алиасы: `local|remote|sse`)
- `--url http://0.0.0.0:3335/mcp` (для `http` inbound)

