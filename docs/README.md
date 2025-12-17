# Документация для AI-агентов (Codex) — broxy

Эта папка содержит подробные описания ключевых подсистем broxy: архитектуры, прокси-фасада, подключений downstream MCP-серверов, пресетов/фильтрации capabilities, конфигурации/хот-релоада, inbound-транспортов (STDIO/HTTP SSE), а также авторизации и удалённого режима через WebSocket с бэкэндом `broxy.run`.

Рекомендуемый порядок чтения:

1. [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) — слои/модули и сквозные потоки данных.
2. [`docs/PROXY_FACADE.md`](PROXY_FACADE.md) — фасад прокси, маршрутизация запросов и контракт префиксованных инструментов.
3. [`docs/DOWNSTREAM_MCP_CONNECTIONS.md`](DOWNSTREAM_MCP_CONNECTIONS.md) — подключение downstream MCP-серверов (STDIO/HTTP/SSE/Streamable HTTP/WebSocket), таймауты, retry/backoff, кеш capabilities.
4. [`docs/PRESETS_AND_FILTERING.md`](PRESETS_AND_FILTERING.md) — модель пресета и фильтрация capabilities (tools/prompts/resources), маршрутизация prompt/resource.
5. [`docs/CONFIGURATION_AND_HOT_RELOAD.md`](CONFIGURATION_AND_HOT_RELOAD.md) — `mcp.json`, `preset_*.json`, переменные окружения и `ConfigurationWatcher`.
6. [`docs/INBOUND_TRANSPORTS.md`](INBOUND_TRANSPORTS.md) — inbound (STDIO + HTTP SSE) и адаптер к MCP SDK.
7. [`docs/REMOTE_AUTH_AND_WEBSOCKET.md`](REMOTE_AUTH_AND_WEBSOCKET.md) — OAuth, регистрация в бэкэнде и удалённый транспорт через WebSocket.
8. [`docs/CAPABILITIES_CACHE_AND_UI_REFRESH.md`](CAPABILITIES_CACHE_AND_UI_REFRESH.md) — фоновая загрузка capabilities для UI, кеш/статусы/обновления.
9. [`docs/LOGGING_AND_OBSERVABILITY.md`](LOGGING_AND_OBSERVABILITY.md) — форматы логов, ключевые события и рекомендации по трассировке.

Также см. существующий документ о payload’ах WebSocket:
- [`docs/websocket-preset-capabilities.md`](websocket-preset-capabilities.md)

