# Capabilities в UI: кеш, статусы и фоновое обновление

Этот документ описывает UI-ориентированную подсистему capabilities (для отображения и валидации), которая живёт отдельно от “прокси-фильтрации” пресета.

## Где используется

В UI-adapter `AppStore` поддерживает:

- список серверов и их статусы подключения;
- “снимки capabilities” для UI (кол-во tools/prompts/resources, аргументы по JSON Schema);
- фоновые обновления по интервалу.

В UI (Compose Desktop) эти снимки используются для компактного отображения summary по tools/prompts/resources:

- в списке серверов — для enabled + `Available` серверов в строке с типом подключения;
- в списке пресетов — в строке описания пресета.

Файлы:

- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/store/AppStore.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/capabilities/CapabilityRefresher.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/capabilities/CapabilityCache.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/capabilities/ServerStatusTracker.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/capabilities/CapabilitySnapshots.kt`

## Разделение уровней: UI snapshots vs proxy capabilities

Важно различать:

1) `core.mcp.ServerCapabilities` — “сырые” MCP capabilities (ToolDescriptor/PromptDescriptor/ResourceDescriptor), используются в `ProxyMcpServer` для публикации наружу и фильтрации.

2) `core.capabilities.ServerCapsSnapshot` — UI-friendly summary:
   - упрощённые `ToolSummary/PromptSummary/ResourceSummary`;
   - список аргументов и типов (best-effort из JSON Schema);
   - хранит `serverId` и `name`.

UI snapshots не участвуют в `tools/call` маршрутизации: это только отображение/инспекция.

## CapabilityRefresher: основной orchestrator

Файл: `core/src/commonMain/kotlin/io/qent/broxy/core/capabilities/CapabilityRefresher.kt`

Входные зависимости:

- `capabilityFetcher: CapabilityFetcher` — функция `(McpServerConfig, timeoutSeconds) -> Result<ServerCapabilities>`.
  - в UI на JVM реализована через `DefaultMcpServerConnection(...).getCapabilities(forceRefresh=true)`:
    - `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/services/ToolServiceJvm.kt`
- `capabilityCache: CapabilityCache` — хранит snapshot + timestamp.
- `statusTracker: ServerStatusTracker` — transient статусы для UI.
- `serversProvider()` — текущий список серверов (из store snapshot).
- `capabilitiesTimeoutProvider()` — таймаут из конфигурации.
- `publishUpdate()` — callback, чтобы `AppStore` пересобрал `UIState`.
- `refreshIntervalMillis()` — интервал обновления (из конфигурации).

### Когда обновляется кеш

`refreshEnabledServers(force)`:

- выбирает только `serversProvider().filter { enabled }`;
- пропускает те, у которых не истёк интервал (`CapabilityCache.shouldRefresh(...)`), если `force=false`;
- параллельно обновляет capabilities через `fetchAndCacheCapabilities(...)`.

При старте `AppStore`:

- выполняется `refreshEnabledServers(force=true)`;
- затем включается background job (`restartBackgroundJob(enableBackgroundRefresh)`).

### Background job

`restartBackgroundJob(enabled)`:

- отменяет старую job;
- если включено, запускает цикл:
  - `delay(refreshIntervalMillis())`
  - `refreshEnabledServers(force=false)`

### Статусы

До выполнения запросов:

- `statusTracker.setAll(targetIds, Connecting)`

После:

- если сервер disabled → `Disabled`;
- если snapshot есть (из fetch или кеша) → `Available`;
- иначе → `Error`.

UI отображает статусы из `CapabilityCache` и `ServerStatusTracker` в `StoreSnapshot.toUiState(...)`.

## Преобразование MCP capabilities в UI snapshot

Файл: `core/src/commonMain/kotlin/io/qent/broxy/core/capabilities/CapabilitySnapshots.kt`

### Tool arguments из JSON Schema

Алгоритм:

- берём `ToolDescriptor.inputSchema`;
- читаем `properties` и `required`;
- для каждого property пытаемся извлечь тип:
  - `type`, `items`, `anyOf/oneOf/allOf`, `enum`, `format` → `schemaTypeLabel()`

Это best-effort: если schema сложная или не содержит `properties`, аргументы будут пустыми.

### Resource arguments из URI

Если `ResourceDescriptor.uri` содержит `{placeholder}`, то placeholder превращается в `CapabilityArgument(name=..., required=true)`.

## Связь с proxy runtime

UI store и proxy runtime независимы, но связаны через конфигурацию:

- при обновлении таймаутов UI вызывает:
  - `ProxyLifecycle.updateCallTimeout(...)`
  - `ProxyLifecycle.updateCapabilitiesTimeout(...)`

CapabilityRefresher использует `capabilitiesTimeoutSeconds` из store snapshot для UI-проверок/валидаторов.
