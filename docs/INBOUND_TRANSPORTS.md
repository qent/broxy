# Inbound транспорты: STDIO и HTTP Streamable

## Что такое inbound транспорт

Inbound — это “как broxy принимает входящие MCP JSON-RPC запросы от клиента”.

В JVM реализации поддерживаются:

- `STDIO` (локальный режим, удобно для IDE/агентов);
- `HTTP Streamable` (удалённый режим для клиентов, которым нужен HTTP endpoint).

Файлы:

- `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/InboundServers.kt`
- `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/SdkServerFactory.kt`

## InboundServerFactory

`InboundServerFactory.create(transport, proxy, logger)`:

- `TransportConfig.StdioTransport` → `StdioInboundServer`
- `TransportConfig.StreamableHttpTransport` → `KtorStreamableHttpInboundServer`
- `TransportConfig.HttpTransport` → backward compatible alias (исторически был SSE inbound; теперь трактуется как Streamable HTTP inbound)
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

## HTTP Streamable inbound

### URL парсинг и нормализация

`KtorStreamableHttpInboundServer` принимает `url` (например `http://localhost:3335/mcp`) и извлекает:

- host / port / path через `URI(url)`;
- если path пустой → default `/mcp`;
- если path заканчивается на `/` → отрезается trailing slash;
- route segments вычисляются через `normalizePath(...)`.

Файлы:
- `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/InboundServers.kt`

### Один endpoint: `POST /mcp`

В `mountStreamableHttpRoute(...)` определены обработчики:

1) `post { ... }`:
   - читает MCP JSON-RPC сообщение из body (ожидается `Content-Type: application/json`);
   - определяет `sessionId` по заголовку `mcp-session-id` (если заголовка нет — создаётся новая сессия);
   - создаёт MCP SDK `ServerSession` через `server.createSession(transport)` и кладёт её в registry по `sessionId`;
   - для `JSONRPCRequest` возвращает `application/json` с `JSONRPCResponse` (т.е. “JSON-only” режим Streamable HTTP);
   - для уведомлений возвращает `200 OK` без тела.

2) `get { ... }`:
   - возвращает `405 Method Not Allowed` (SSE stream не используется в текущей реализации).

3) `delete { ... }`:
   - удаляет `sessionId` из registry и закрывает MCP SDK сессию (`204 No Content`).

Важно: такой “JSON-only” Streamable HTTP режим совместим с `mcpStreamableHttp(...)` клиентом MCP Kotlin SDK.

### Мультисессии

`InboundStreamableHttpRegistry` (ConcurrentHashMap):

- хранит `sessionId -> ServerSession`;
- `remove(sessionId)` закрывает сессию и удаляет из registry.

## Адаптер к MCP SDK (buildSdkServer)

Outbound API для MCP клиентов формирует SDK `Server`:

- синхронизирует зарегистрированные tools/prompts/resources с `proxy.getCapabilities()` и может пересинхронизироваться при смене пресета без рестарта inbound;
- перенаправляет `callTool/getPrompt/readResource` в `ProxyMcpServer`.

Подробности — `docs/PROXY_FACADE.md`.

## CLI параметры для inbound

Файл: `cli/src/main/kotlin/io/qent/broxy/cli/commands/ProxyCommand.kt`

- `--inbound stdio|http` (алиасы: `local|remote|sse`)
- `--url http://localhost:3335/mcp` (для `http` inbound)

## Desktop UI: auto HTTP inbound

В desktop UI режиме локальный HTTP Streamable inbound поднимается автоматически при старте приложения и останавливается вместе с процессом.

- Порт задаётся в `mcp.json` ключом `inboundSsePort` (default `3335`). (Историческое имя ключа сохранено для совместимости.)
- При изменении порта через UI сервер автоматически перезапускается.
- Если порт занят, старт inbound завершится ошибкой (UI показывает статус “порт занят”).
