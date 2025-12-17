# Архитектура broxy (Clean Architecture + прокси MCP)

## Цель проекта

`broxy` — прокси для Model Context Protocol (MCP), который:

- подключается к нескольким downstream MCP-серверам (STDIO/HTTP(SSE)/Streamable HTTP/WebSocket);
- агрегирует их capabilities (tools/prompts/resources);
- применяет пресет (allow-list) и публикует **отфильтрованный** набор capabilities наружу;
- маршрутизирует входящие RPC (`tools/call`, `prompts/get`, `resources/read`) в правильный downstream.

## Модули и слои

### `core/` (domain + data + runtime wiring)

Содержит платформенно-независимую бизнес-логику прокси и модели:

- Прокси и маршрутизация:
  - `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/ProxyMcpServer.kt`
  - `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/RequestDispatcher.kt`
  - `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/ToolFilter.kt`
  - `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/NamespaceManager.kt`
- Подключения downstream MCP:
  - `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/DefaultMcpServerConnection.kt`
  - `core/src/jvmMain/kotlin/io/qent/broxy/core/mcp/clients/StdioMcpClient.kt`
  - `core/src/jvmMain/kotlin/io/qent/broxy/core/mcp/clients/KtorMcpClient.kt`
- Конфигурация и хот-релоад:
  - `core/src/jvmMain/kotlin/io/qent/broxy/core/config/JsonConfigurationRepository.kt`
  - `core/src/jvmMain/kotlin/io/qent/broxy/core/config/ConfigurationWatcher.kt`
  - `core/src/jvmMain/kotlin/io/qent/broxy/core/config/EnvironmentVariableResolver.kt`
- Runtime wiring (JVM):
  - `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/runtime/ProxyLifecycle.kt`
  - `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/runtime/ProxyControllerJvm.kt`
  - `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/InboundServers.kt`
  - `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/SdkServerFactory.kt`

### `ui-adapter/` (presentation adapter, UDF/MVI)

Слой презентации без Compose-зависимостей: состояния, интенты, фоновые джобы.

- Store и intents:
  - `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/store/AppStore.kt`
  - `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/store/internal/AppStoreIntents.kt`
  - `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/store/internal/ProxyRuntime.kt`
- Remote режим (OAuth + WebSocket):
  - `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/remote/RemoteConnectorImpl.kt`
  - `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/remote/ws/RemoteWsClient.kt`
  - `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/remote/ws/ProxyWebSocketTransport.kt`

### `ui/` (Compose Desktop, “тонкий UI”)

UI рендерит `UIState` и вызывает интенты (без прямого импорта `core`).

### `cli/` (CLI режим)

CLI поднимает прокси и watcher для хот-релоада:

- `cli/src/main/kotlin/io/qent/broxy/cli/commands/ProxyCommand.kt`

## Сквозные потоки данных (end-to-end)

### 1) Запуск прокси (CLI или UI)

1. Загружается конфигурация (`mcp.json`) и пресет (`preset_<id>.json`).
2. Собирается runtime:
   - downstream соединения: `DefaultMcpServerConnection` (по одному на сервер).
   - прокси-ядро: `ProxyMcpServer`.
   - inbound server: STDIO или HTTP SSE (`InboundServerFactory`).
3. На старте `ProxyMcpServer.start(...)` вычисляет отфильтрованные capabilities (см. `refreshFilteredCapabilities()`).
4. Inbound-адаптер строит MCP SDK `Server` через `buildSdkServer(proxy)` и публикует наружу `tools/list`, `prompts/list`, `resources/list` и обработчики `callTool/getPrompt/readResource`.

### 2) Вызов инструмента (LLM → broxy → downstream)

**Ключевой контракт**: входящее имя инструмента **обязано** быть префиксовано: `serverId:toolName`.

```mermaid
sequenceDiagram
  participant LLM as LLM client (MCP)
  participant Inbound as Inbound (SDK Server)
  participant Proxy as ProxyMcpServer
  participant Disp as RequestDispatcher
  participant DS as Downstream McpServerConnection

  LLM->>Inbound: tools/call {name: "s1:search", arguments: {...}}
  Inbound->>Proxy: callTool("s1:search", args)
  Proxy->>Disp: dispatchToolCall("s1:search")
  Disp->>Disp: validate allowedPrefixedTools
  Disp->>Disp: parse serverId/tool via NamespaceManager
  Disp->>DS: callTool("search", args)
  DS->>DS: connect -> call -> disconnect
  DS-->>Disp: Result<JsonElement>
  Disp-->>Proxy: Result<JsonElement>
  Proxy-->>Inbound: Result<JsonElement>
  Inbound-->>LLM: CallToolResult (decoded/fallback)
```

### 3) Обновление конфигурации/пресета (hot reload)

В CLI и (частично) в UI используется `ConfigurationWatcher`:

- на изменение `mcp.json` → `onConfigurationChanged(...)` → `ProxyLifecycle.restartWithConfig(...)`;
- на изменение `preset_*.json` → `onPresetChanged(...)` → `ProxyLifecycle.applyPreset(...)` (только пересчёт allow-list в прокси; подробности и ограничения — в `docs/PRESETS_AND_FILTERING.md`).

### 4) Remote режим (UI): авторизация + WebSocket

UI-adapter выполняет OAuth и регистрирует “сервер-идентификатор” на `broxy.run`, затем поднимает WebSocket, в котором проксируются MCP JSON-RPC сообщения (см. `docs/REMOTE_AUTH_AND_WEBSOCKET.md`).

