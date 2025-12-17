# Подключение downstream MCP-серверов (McpServerConnection + McpClient)

## Задача слоя downstream

Downstream слой отвечает за “подключиться → выполнить операцию → отключиться” к конкретному MCP серверу и вернуть `Result<T>` наверх, не утягивая наружу детали транспорта.

Основная абстракция:

- `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/McpServerConnection.kt` — интерфейс соединения.
- `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/DefaultMcpServerConnection.kt` — реализация с retry/backoff и кешем capabilities.

Клиентский транспорт:

- `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/McpClient.kt` — интерфейс клиента.
- `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/McpClientFactory.kt` — фабрика через provider.
- `core/src/jvmMain/kotlin/io/qent/broxy/core/mcp/McpClientFactoryJvm.kt` — JVM provider: STDIO / SSE / Streamable HTTP / WebSocket.

## TransportConfig и mapping в клиентов

Модель:
- `core/src/commonMain/kotlin/io/qent/broxy/core/models/TransportConfig.kt`

JVM mapping (provider):
- `core/src/jvmMain/kotlin/io/qent/broxy/core/mcp/McpClientFactoryJvm.kt`

Таблица:

| TransportConfig | Downstream клиент | Реализация |
|---|---|---|
| `StdioTransport` | процесс + stdio transport | `StdioMcpClient` |
| `HttpTransport` | HTTP SSE | `KtorMcpClient(Mode.Sse)` |
| `StreamableHttpTransport` | streamable HTTP | `KtorMcpClient(Mode.StreamableHttp)` |
| `WebSocketTransport` | WebSocket | `KtorMcpClient(Mode.WebSocket)` |

Важно: inbound транспорт (то, “как broxy слушает”) ограничен STDIO/HTTP Streamable, а downstream транспортов больше (включая SSE и WS).

## DefaultMcpServerConnection: модель “короткой сессии”

Файл: `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/DefaultMcpServerConnection.kt`

Ключевые свойства:

- На **каждую операцию** создаётся новый `McpClient` (`newClient()`), выполняется `connect()` и после операции — `disconnect()`.
- Это снижает риски “висячих” соединений и упрощает lifecycle; статус соединения отражает последнюю попытку.

### Статусы

- `ServerStatus.Starting` — перед connect.
- `ServerStatus.Running` — после успешного connect.
- `ServerStatus.Error(message)` — если операция провалилась.
- `ServerStatus.Stopped` — после disconnect.

Тип: `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/ServerStatus.kt`

### Таймауты (2 уровня)

В `DefaultMcpServerConnection` есть три значения:

- `callTimeoutMillis` — общий таймаут на `callTool/getPrompt/readResource` (оборачивается `withTimeout`).
- `capabilitiesTimeoutMillis` — используется клиентом для list-операций (подробнее ниже).
- `connectTimeoutMillis` — таймаут на `client.connect()`.

Методы обновления:

- `updateCallTimeout(millis)`
- `updateCapabilitiesTimeout(millis)` (также синхронизирует `connectTimeoutMillis`)

В runtime эти значения прокидываются из `ProxyLifecycle.updateCallTimeout(...)` и `updateCapabilitiesTimeout(...)` (см. `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/runtime/ProxyLifecycle.kt` и JVM-реализацию `ProxyControllerJvm`).

### Retry/backoff на connect

`connectClient(client)`:

- `maxRetries` попыток (по умолчанию 5).
- backoff: `ExponentialBackoff` (`core/src/commonMain/kotlin/io/qent/broxy/core/utils/Backoff.kt`) с `delay(...)`.
- connect обёрнут в `withTimeout(connectTimeoutMillis)`.

Ошибки таймаута и подключения типизированы:

- `McpError.TimeoutError`
- `McpError.ConnectionError`

Файл ошибок: `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/errors/McpError.kt`

## Кеш capabilities: CapabilitiesCache (per-server)

Файл: `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/CapabilitiesCache.kt`

Особенности:

- TTL по умолчанию 5 минут (можно задать через `cacheTtlMs` в `DefaultMcpServerConnection`).
- Потокобезопасность через `Mutex`.
- `getCapabilities(forceRefresh = false)`:
  - сначала пытается вернуть кеш;
  - иначе делает fetch и кладёт в кеш;
  - при ошибке пытается вернуть “протухший” кеш как fallback, чтобы прокси мог продолжать работу.

Эта логика критична для стабильности прокси: отсутствие caps у одного сервера не должно “ронять” весь агрегатор.

## KtorMcpClient (HTTP SSE / Streamable HTTP / WebSocket)

Файл: `core/src/jvmMain/kotlin/io/qent/broxy/core/mcp/clients/KtorMcpClient.kt`

Семантика таймаутов:

- На connect создаётся `HttpClient(CIO)` + `HttpTimeout` с `request/socket/connectTimeoutMillis = connectTimeoutMillis`.
- Получение capabilities (`fetchCapabilities`) вызывает три операции:
  - `getTools()`, `getResources()`, `getPrompts()`
  - каждая обёрнута в `withTimeout(capabilitiesTimeoutMillis)`
  - при таймауте/ошибке возвращается **пустой список**, а не ошибка (логируется `warn`).

Это сделано намеренно: “частично доступные capabilities” предпочтительнее полного фейла (например, если `resources/list` висит, но tools работают).

## StdioMcpClient (процесс + STDIO transport)

Файл: `core/src/jvmMain/kotlin/io/qent/broxy/core/mcp/clients/StdioMcpClient.kt`

### Запуск процесса и окружение

- `ProcessBuilder(listOf(command) + args)` + `pb.environment()` дополняется `env`.
- `env` предварительно проходит через `EnvironmentVariableResolver.resolveMap(...)` (поддержка `${VAR}` и `{VAR}`).
- В логах окружение санитизируется (см. `EnvironmentVariableResolver.logResolvedEnv(...)`).

Файл: `core/src/jvmMain/kotlin/io/qent/broxy/core/config/EnvironmentVariableResolver.kt`

### Handshake и таймаут

Подключение выполняется в `async(Dispatchers.IO)`:

- создаётся `StdioClientTransport(source, sink)` и оборачивается в `LoggingTransport`;
- создаётся `Client(Implementation(...))`;
- выполняется `sdk.connect(transport)`;
- затем `withTimeout(connectTimeout)` ждём завершения handshake.

При таймауте:

- процесс форсированно уничтожается (`destroyForcibly()`),
- кидается `McpError.TimeoutError("STDIO connect timed out ...")`.

### stderr логирование

Отдельный поток читает `proc.errorStream` и пишет строки как `logger.warn("[STDERR][cmd] ...")`.
Это важно: многие MCP сервера печатают диагностические сообщения в stderr.

### LoggingTransport: трассировка MCP сообщений

`LoggingTransport` логирует:

- `tools/list`, `resources/list`, `prompts/list` запросы (по `method`);
- соответствующие ответы (по `id`);
- уведомления `*_list_changed`.

Это облегчает диагностику “почему capabilities не приходят” для STDIO серверов.

## MultiServerClient: параллельные запросы

Файл: `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/MultiServerClient.kt`

Назначение:

- `fetchAllCapabilities()` — параллельно спрашивает `server.getCapabilities()` у всех соединений.
- `listPrefixedTools(allCaps)` — утилита для построения списка инструментов с `serverId:` префиксом.

В `RequestDispatcher` используется для fallback-скана prompts/resources.
