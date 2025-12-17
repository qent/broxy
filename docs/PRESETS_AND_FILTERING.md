# Пресеты и фильтрация capabilities (tools/prompts/resources)

## Термины

- **Downstream capabilities** — “сырой” список `tools/resources/prompts`, который отдаёт каждый downstream MCP сервер.
- **Filtered capabilities** — capabilities, опубликованные наружу broxy после применения пресета.
- **Preset** — декларативный allow-list (и частично “scope”) для tools/prompts/resources.

## Модель Preset

Файл: `core/src/commonMain/kotlin/io/qent/broxy/core/models/Preset.kt`

```kotlin
data class Preset(
  val id: String,
  val name: String,
  val description: String,
  val tools: List<ToolReference> = emptyList(),
  val prompts: List<PromptReference>? = null,
  val resources: List<ResourceReference>? = null
)
```

Ссылки:

- `ToolReference(serverId, toolName, enabled)` — `core/src/commonMain/kotlin/io/qent/broxy/core/models/ToolReference.kt`
- `PromptReference(serverId, promptName, enabled)` — `core/src/commonMain/kotlin/io/qent/broxy/core/models/PromptReference.kt`
- `ResourceReference(serverId, resourceKey, enabled)` — `core/src/commonMain/kotlin/io/qent/broxy/core/models/ResourceReference.kt`

## Как работает фильтрация (DefaultToolFilter)

Файл: `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/ToolFilter.kt`

Вход:

- `all: Map<String, ServerCapabilities>` — capabilities всех downstream (key = `serverId`).
- `preset: Preset`.

Выход:

- `FilterResult`:
  - `capabilities: ServerCapabilities` — отфильтрованный view (tools/prompts/resources).
  - `allowedPrefixedTools: Set<String>` — allow-list для enforcement на `tools/call`.
  - `missingTools` — список ссылок, которые есть в preset, но отсутствуют downstream.
  - `promptServerByName: Map<promptName, serverId>` — маршрутизация `prompts/get`.
  - `resourceServerByUri: Map<uriOrName, serverId>` — маршрутизация `resources/read`.

### Шаг 1: группировка желаемых сущностей

Фильтр строит:

- `desiredByServer` из `preset.tools.filter { enabled }` → groupBy serverId.
- `desiredPromptsByServer` из `preset.prompts?.filter { enabled }` → groupBy serverId.
- `desiredResourcesByServer` из `preset.resources?.filter { enabled }` → groupBy serverId.

### Шаг 2: определение in-scope серверов

`inScopeServers` — объединение serverId из tools/prompts/resources.

Важная деталь:

- если `preset.tools` пуст и `preset.prompts/resources` пусты (или `null`), то `inScopeServers` будет пуст → отфильтрованные capabilities будут пустыми.

### Шаг 3: tools — строгий allow-list + префиксация

Для каждой `ToolReference(serverId, toolName)`:

1) проверяется наличие `toolName` в capabilities соответствующего сервера;
2) если отсутствует → добавляется в `missingTools`;
3) если присутствует → берётся `ToolDescriptor` downstream, и его `name` переписывается в `"$serverId:${tool.name}"`.

Одновременно:

- `allowedPrefixedTools += "$serverId:${tool.name}"`

Это обеспечивает:

- отсутствие коллизий по имени;
- строгий allow-list (downstream tool без ссылки в пресете не попадёт наружу).

### Шаг 4: prompts/resources — поведение зависит от `null`

Флаг “ограничивать ли” определяется так:

- `restrictPrompts = preset.prompts != null`
- `restrictResources = preset.resources != null`

Семантика:

- Если `preset.prompts == null`, то prompts **не ограничиваются allow-list’ом** и берутся целиком (НО только с in-scope серверов).
- Если `preset.prompts != null`, то prompts включаются **только** из allow-list (`promptAllowList`).

Аналогично для resources:

- allow-list задаётся по ключу `(uri ?: name)` и сравнивается с `ResourceReference.resourceKey`.

### Шаг 5: таблицы маршрутизации

После выбора prompts/resources фильтр строит маппинги:

- `promptServerByName[prompt.name] = serverId` (через `putIfAbsent`)
- `resourceServerByUri[uriOrName] = serverId` (через `putIfAbsent`)

Это важно: если одинаковое имя prompt/resource встречается на нескольких серверах, победит “первый” в итерации по `inScopeServers`.

## Применение пресета в ProxyMcpServer

Файл: `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/ProxyMcpServer.kt`

Основные поля runtime:

- `currentPreset`
- `filteredCaps`
- `allowedTools`
- `promptServerByName`
- `resourceServerByUri`

Ключевые методы:

- `refreshFilteredCapabilities()`:
  1) `fetchAllDownstreamCapabilities()` — параллельный сбор caps;
  2) `presetEngine.apply(all, preset)` → `FilterResult`;
  3) сохранение `filteredCaps/allowedTools/...`;
  4) логирование `missingTools`.

- `applyPreset(preset)`:
  - обновляет `currentPreset`;
  - вызывает `refreshFilteredCapabilities()` (через `runBlocking`).

## Enforcement: запрет вызовов запрещённых tool

Даже если внешний клиент “видит” tool в `tools/list`, реальная защита делается на этапе `tools/call`:

- `DefaultRequestDispatcher.dispatchToolCall(...)`:
  - в режиме прокси `ProxyMcpServer` используется strict enforcement: пустой allow-list означает **deny all** (чтобы `tools/call` не работал при отсутствии активного пресета).

Файл: `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/RequestDispatcher.kt`

## Runtime переключение пресета: как это устроено и ограничения

### UI (AppStore)

Сценарий выбора пресета:

- `AppStoreIntents.selectProxyPreset(presetId)`:
  - меняет `selectedPresetId`;
  - сохраняет `defaultPresetId` в `mcp.json`;
  - применяет пресет к уже запущенному прокси без рестарта inbound.

Файлы:
- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/store/internal/AppStoreIntents.kt`
- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/store/internal/ProxyRuntime.kt`

### CLI (ConfigurationWatcher)

CLI watcher на изменение preset вызывает:

- `ProxyLifecycle.applyPreset(preset)` → `ProxyController.applyPreset(...)` → `ProxyMcpServer.applyPreset(...)`

Это не пересоздаёт inbound сервер: running STDIO/SSE продолжает работать, а MCP SDK `Server` пересинхронизируется с текущими filtered capabilities, поэтому `tools/list`/`prompts/list`/`resources/list` обновятся без рестарта процесса.

Файлы:
- `cli/src/main/kotlin/io/qent/broxy/cli/commands/ProxyCommand.kt`
- `core/src/commonMain/kotlin/io/qent/broxy/core/proxy/runtime/ProxyLifecycle.kt`
