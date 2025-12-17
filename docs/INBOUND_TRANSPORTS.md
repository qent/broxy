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

Важно про жизненный цикл:

- В текущем MCP Kotlin SDK `server.connect(transport)` может **не блокировать** поток до закрытия сессии (соединение продолжает жить, пока процесс жив и transport не закрыт).
- Поэтому процесс должен оставаться живым: либо “держать” основной поток (как делает CLI), либо явно ждать завершения STDIO сессии через `transport.onClose { ... }`.
  - См. `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/headless/HeadlessEntrypointJvm.kt` — headless STDIO режим ожидает закрытия transport, чтобы не завершать процесс сразу после запуска.

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
   - создаёт `SseServerTransport(endpointPath, this)` (endpoint для `POST` сообщений);
   - создаёт `ServerSession` через `server.createSession(transport)` и регистрирует её в `InboundSseRegistry` по `sessionId`;
   - строит SDK `Server` через `serverFactory()`;
   - **держит handler живым** до закрытия сессии (иначе Ktor закроет SSE соединение и клиенты начнут переподключаться).

2) `post { ... }`:
   - читает `sessionId` из query параметра `sessionId`;
   - находит `ServerSession` в registry и достаёт её `transport`;
   - вызывает `transport.handlePostMessage(call)` (MCP SDK helper).

Таким образом, клиент:

- держит SSE соединение для входящих сообщений от сервера;
- отправляет свои JSON-RPC запросы POST’ом с `sessionId`.

### Мультисессии

`InboundSseRegistry` (ConcurrentHashMap):

- хранит `sessionId -> ServerSession` (а `SseServerTransport` берётся из `session.transport`);
- `remove(sessionId)` удаляет;
- используется в `post` обработчике.

## Адаптер к MCP SDK (buildSdkServer)

Outbound API для MCP клиентов формирует SDK `Server`:

- синхронизирует зарегистрированные tools/prompts/resources с `proxy.getCapabilities()` и может пересинхронизироваться при смене пресета без рестарта inbound;
- перенаправляет `callTool/getPrompt/readResource` в `ProxyMcpServer`.

Подробности — `docs/PROXY_FACADE.md`.

## CLI параметры для inbound

Файл: `cli/src/main/kotlin/io/qent/broxy/cli/commands/ProxyCommand.kt`

- `--inbound stdio|http` (алиасы: `local|remote|sse`)
- `--url http://0.0.0.0:3335/mcp` (для `http` inbound)

## Desktop UI: auto SSE inbound

В desktop UI режиме локальный HTTP SSE inbound поднимается автоматически при старте приложения и останавливается вместе с процессом.

- Порт задаётся в `mcp.json` ключом `inboundSsePort` (default `3335`).
- При изменении порта через UI сервер автоматически перезапускается.
- Если порт занят, старт inbound завершится ошибкой (UI показывает статус “порт занят”).
