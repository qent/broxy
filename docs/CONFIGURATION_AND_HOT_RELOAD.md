# Конфигурация, пресеты и hot reload

## Файлы конфигурации

По умолчанию конфигурация хранится в:

`~/.config/broxy/`

Логи приложения сохраняются рядом с конфигурацией:

- `~/.config/broxy/logs/{YYYY-MM-DD}.log`

Ключевые файлы:

- `mcp.json` — список downstream MCP серверов + глобальные параметры таймаутов.
- `preset_<id>.json` — пресеты (allow-list) для фильтрации.

Загрузка/сохранение:

- `core/src/jvmMain/kotlin/io/qent/broxy/core/config/JsonConfigurationRepository.kt`

## mcp.json: структура и валидация

`JsonConfigurationRepository.loadMcpConfig()`:

1) читает `mcp.json`;
2) парсит в `FileMcpRoot`;
3) разворачивает `mcpServers: Map<String, FileMcpServer>` в список `McpServerConfig`;
4) валидирует:
   - корректность транспортов и обязательных полей (`command`/`url`);
   - уникальность `serverId`;
   - отсутствие пустых `id/name`;
   - наличие переменных окружения для плейсхолдеров;
5) выставляет defaults:
   - `requestTimeoutSeconds` (default: 60)
   - `capabilitiesTimeoutSeconds` (default: 30)
   - `capabilitiesRefreshIntervalSeconds` (default: 300)
   - `showTrayIcon` (default: true)
   - `inboundSsePort` (default: 3335, порт локального HTTP Streamable inbound для desktop UI; имя ключа историческое)
   - `defaultPresetId` (optional: preset по умолчанию для STDIO режима; если не задан — broxy стартует с пустыми capabilities)

Код:
- `core/src/jvmMain/kotlin/io/qent/broxy/core/config/JsonConfigurationRepository.kt`

### Поддерживаемые транспорты downstream

Парсинг `transport` (строка) в `TransportConfig`:

- `"stdio"` → `TransportConfig.StdioTransport(command, args)`
- `"http"` → `TransportConfig.HttpTransport(url, headers)`
- `"streamable-http"` (и алиасы) → `TransportConfig.StreamableHttpTransport(url, headers)`
- `"ws"`/`"websocket"` → `TransportConfig.WebSocketTransport(url)`

Важно: `headers` поддерживаются только для HTTP/streamable HTTP.

## Переменные окружения и плейсхолдеры

Плейсхолдеры в `env` поддерживаются в двух форматах:

- `${VAR}`
- `{VAR}`

Реализация:
- `core/src/jvmMain/kotlin/io/qent/broxy/core/config/EnvironmentVariableResolver.kt`

Гарантии:

- при загрузке `mcp.json` `JsonConfigurationRepository` сначала проверяет “отсутствующие переменные” (`missingVars`) и падает с понятной ошибкой;
- для логов окружение санитизируется по ключам: `TOKEN/SECRET/PASSWORD/KEY` → `"***"`.

## preset_<id>.json

Загрузка пресета:

- `JsonConfigurationRepository.loadPreset(id)`:
  - проверяет существование файла;
  - парсит JSON в `Preset`;
  - проверяет соответствие `preset.id` и `id` из имени файла.

Примечание про переименование:

- `id` пресета является частью имени файла (`preset_<id>.json`), поэтому “переименование пресета” технически означает сохранение под новым `id` + удаление старого файла.
- Desktop UI генерирует `id` автоматически (из `name`) и при изменении имени выполняет rename: сохранение под новым `id` + удаление старого файла.

Список пресетов:

- `JsonConfigurationRepository.listPresets()` — читает все `preset_*.json` и пропускает битые файлы с warn-логом.

## Hot reload: ConfigurationWatcher

Файл: `core/src/jvmMain/kotlin/io/qent/broxy/core/config/ConfigurationWatcher.kt`

Назначение: следить за изменениями в `~/.config/broxy` (или другом baseDir) и уведомлять наблюдателей.

Ключевые идеи:

- Используется `WatchService` с `ENTRY_CREATE/MODIFY/DELETE`.
- События дебаунсятся (`debounceMillis`, default 300ms).
- Обработчик различает:
  - `mcp.json` → `onConfigurationChanged(config)`
  - `preset_*.json` → `onPresetChanged(preset)` (если файл существует).

### Ручные триггеры

Для headless/тестов/CLI:

- `triggerConfigReload()`
- `triggerPresetReload(id)`

### emitInitialState

Если `emitInitialState=true`, то watcher “эмитит” первоначальную загрузку через debounce (удобно для UI адаптера).
В CLI watcher запускается с `emitInitialState=false`, потому что конфиг уже загружен до запуска.

## Как hot reload используется в CLI

Файл: `cli/src/main/kotlin/io/qent/broxy/cli/commands/ProxyCommand.kt`

- При изменении `mcp.json`:
  - `ProxyLifecycle.restartWithConfig(config)` — перезапуск downstream соединений + inbound.
- При изменении `preset_*.json`:
  - `ProxyLifecycle.applyPreset(preset)` — обновление filtered capabilities без рестарта inbound; наружные `tools/list`/`prompts/list`/`resources/list` обновятся в рамках уже запущенного STDIO/SSE.
