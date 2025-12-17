# Фасад прокси и маршрутизация запросов (ProxyMcpServer + MCP SDK)

## Что такое “фасад” в broxy

Под “фасадом” здесь понимается связка:

1) **Inbound транспорт** (STDIO или HTTP SSE), который принимает MCP JSON-RPC сообщения от клиента (LLM/IDE/агента) и отдаёт ответы.

2) **MCP SDK Server**, построенный функцией:
- `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/SdkServerFactory.kt` → `buildSdkServer(proxy: ProxyMcpServer, ...)`

3) **ProxyMcpServer**, который фильтрует capabilities по пресету и маршрутизирует вызовы вниз:
- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/ProxyMcpServer.kt`

Цель фасада: сделать `ProxyMcpServer` “MCP-совместимым сервером” для внешнего мира, не смешивая wire-адаптеры с business-логикой фильтрации и роутинга.

## Карта ключевых классов

- Inbound:
  - `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/InboundServers.kt`
  - `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/SdkServerFactory.kt`
- Proxy:
  - `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/ProxyMcpServer.kt`
  - `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/RequestDispatcher.kt`
  - `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/NamespaceManager.kt`
  - `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/ToolFilter.kt`

## Контракт namespace: `serverId:toolName`

### Зачем префикс нужен

В нескольких downstream серверах могут существовать инструменты с одинаковыми именами. Чтобы избежать коллизий, broxy использует namespace:

- входящее имя инструмента: `serverId:toolName`;
- downstream инструмент вызывается без префикса (`toolName`).

Реализация:
- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/DefaultNamespaceManager.parsePrefixedToolName(...)`
- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/DefaultNamespaceManager.prefixToolName(...)`

### Ошибка формата

Если имя не соответствует формату (нет `:` или он в неправильном месте), `parsePrefixedToolName` кидает `IllegalArgumentException`. Это важно: для большинства клиентов “без префикса” вызов считается некорректным и будет отклонён.

## Слой 1: InboundServer → MCP SDK Server

### STDIO inbound

- `InboundServerFactory.create(...)` возвращает `StdioInboundServer`.
- В `StdioInboundServer.start()` создаётся `StdioServerTransport` и подключается `Server.connect(...)`.

Файл: `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/InboundServers.kt`

### HTTP SSE inbound

`KtorInboundServer` поднимает embedded Ktor (Netty):

- `GET .../mcp` (SSE) держит соединение и создаёт `SseServerTransport`;
- `POST .../mcp?sessionId=...` отправляет сообщения в соответствующую SSE-сессию.

Реализация мультисессий:
- `InboundSseRegistry` хранит `sessionId -> SseServerTransport`.

Файл: `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/InboundServers.kt`

## Слой 2: MCP SDK Server → ProxyMcpServer

### Регистрация tools/prompts/resources

`buildSdkServer(proxy)` делает “снимок” текущих отфильтрованных capabilities:

- `val caps = proxy.getCapabilities()`

И регистрирует:

- tools: `server.addTool(name = td.name, ...) { req -> ... }`
- prompts: `server.addPrompt(prompt) { req -> ... }`
- resources: `server.addResource(uri = ..., ...) { _ -> ... }`

Файл: `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/SdkServerFactory.kt`

### Важное ограничение: capabilities snapshot

`buildSdkServer` регистрирует список tools/prompts/resources **однократно**, на момент построения сервера.

Если позже вызвать:
- `ProxyMcpServer.applyPreset(...)` (или `refreshFilteredCapabilities()`),

то внутри `ProxyMcpServer` обновятся allow-list и маршрутизация, но **список capabilities, который отдаёт MCP SDK Server в `tools/list`/`prompts/list`/`resources/list`, не обновится**, потому что SDK server не пересоздаётся автоматически.

Практический смысл:

- **защита**: `DefaultRequestDispatcher` всё равно проверит `allowedPrefixedTools` и отклонит запрещённые вызовы (см. ниже);
- **UX**: внешние клиенты могут видеть “устаревший” список инструментов до перезапуска inbound/процесса.

Рекомендация для runtime: если важно “обновлять список capabilities”, делайте restart inbound (через `ProxyLifecycle.start(...)`) вместо `applyPreset(...)`.

## Слой 3: ProxyMcpServer → RequestDispatcher → downstream

### ProxyMcpServer как “ядро”

`ProxyMcpServer` отвечает за:

- хранение текущего пресета и filtered view;
- вычисление `filteredCaps`, `allowedTools`, `promptServerByName`, `resourceServerByUri`;
- делегирование входящих запросов `RequestDispatcher`.

Файл: `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/ProxyMcpServer.kt`

Ключевые методы:

- `start(preset, transport)` — сохраняет пресет и делает best-effort `refreshFilteredCapabilities()` (с `runBlocking`).
- `refreshFilteredCapabilities()` — скачивает capabilities всех downstream и применяет фильтр/пресет.
- `callTool(toolName, arguments)` — делегирует в `dispatcher.dispatchToolCall(...)`.
- `getPrompt(name, arguments)` — делегирует в `dispatcher.dispatchPrompt(...)`.
- `readResource(uri)` — делегирует в `dispatcher.dispatchResource(...)`.

### DefaultRequestDispatcher: проверки и роутинг

Файл: `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/RequestDispatcher.kt`

Ключевая логика:

1) **Enforcement allow-list**:

- `allowedPrefixedTools()` возвращает Set.
- Если set не пустой и `request.name` не входит в него → ошибка (`Tool '...' is not allowed by current preset`).

2) **Разбор namespace**:

- `namespace.parsePrefixedToolName(name)` → `(serverId, tool)`

3) **Поиск downstream**:

- `servers.firstOrNull { it.serverId == serverId }`

4) **Вызов без префикса**:

- `server.callTool(tool, request.arguments)`

5) **Prompts/resources**:

Сначала использует маппинг, вычисленный при фильтрации:

- `promptServerResolver(name)` → serverId?
- `resourceServerResolver(uri)` → serverId?

Если резолвер отсутствует или вернул `null`, выполняется fallback “скан по capabilities”:

- `multi.fetchAllCapabilities()`
- поиск prompt/resource в capabilities каждого downstream.

### Batch вызовы

`dispatchBatch(requests)` обрабатывает параллельно (`async/awaitAll`) и делегирует в `dispatchToolCall(...)`. Это не “MCP batch” на wire-уровне, а утилита для внутренних сценариев.

## Преобразование результатов: decode/fallback

`SdkServerFactory` пытается декодировать ответ downstream в типы MCP SDK:

- `decodeCallToolResult(json, element)`
- `decodePromptResult(json, element)`

Если downstream прислал несовместимую структуру:

- выполняется нормализация content-блоков (добавление `type`, если он отсутствует);
- иначе используется `fallbackCallToolResult(raw)`, который превращает произвольный JSON в:
  - `CallToolResult(content = [TextContent(text = raw.toString())], structuredContent = rawObjectOrWrapped, ...)`

Файл: `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/SdkServerFactory.kt`

## Логирование фасада (ключевые события)

Набор событий (в JSON-формате, через `Logger.infoJson/warnJson/errorJson`):

- `llm_to_facade.request` — входящий `tools/call` (имя/аргументы/meta).
- `facade_to_downstream.request` — запрос после резолвинга serverId/tool.
- `downstream.response` / `downstream.response.error` — результат от downstream.
- `facade_to_llm.response` / `facade_to_llm.error` — ответ/ошибка, которые вернутся клиенту.
- `proxy.tool.denied` — отказ по пресету (allow-list).
- `facade_to_llm.decode_failed` — downstream прислал payload, который не декодируется как `CallToolResult`.

Файлы:
- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/RequestDispatcher.kt`
- `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/SdkServerFactory.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/utils/JsonLogging.kt`

