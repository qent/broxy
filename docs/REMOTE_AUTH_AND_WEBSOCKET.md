# Remote режим: OAuth, регистрация и WebSocket-транспорт с бэкэндом

## Назначение remote режима

Remote режим позволяет “прикрепить” локальный broxy (который уже умеет ходить в downstream MCP-сервера) к удалённому бэкэнду `broxy.run`, чтобы бэкэнд мог проксировать MCP JSON-RPC сессии через WebSocket.

Ключевая идея:

- локально строится MCP SDK `Server` поверх `ProxyMcpServer` (как и для inbound);
- затем этот `Server` подключается к `ProxyWebSocketTransport`, который отправляет/принимает MCP JSON-RPC сообщения, завернутые в envelope бэкэнда.

## Компоненты remote подсистемы

- Контроллер remote режима (стейт-машина + OAuth + токены + WS):
  - `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/remote/RemoteConnectorImpl.kt`
- WebSocket клиент:
  - `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/remote/ws/RemoteWsClient.kt`
- Транспорт-адаптер MCP SDK ↔ WS envelope:
  - `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/remote/ws/ProxyWebSocketTransport.kt`
- Хранилище токенов:
  - `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/remote/storage/RemoteConfigStore.kt`
  - `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/remote/storage/SecureStore.kt`
- OAuth callback сервер:
  - `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/remote/auth/LoopbackCallbackServer.kt`

## RemoteConnectorImpl: состояние и интерфейс

Интерфейс:
- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/remote/RemoteConnector.kt`

Состояние в UI:
- `UiRemoteConnectionState` (ui-adapter models) хранит:
  - `serverIdentifier`
  - `email`
  - `hasCredentials`
  - `status` (`UiRemoteStatus`)
  - `message`

### serverIdentifier

- default вычисляется в `defaultRemoteServerIdentifier()`:
  - `broxy-<host>-<os>` → нормализация, max 48 символов.
  - файл: `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/remote/RemoteDefaultsJvm.kt`

Из UI можно изменить `serverIdentifier`:

- `RemoteConnectorImpl.updateServerIdentifier(value)`:
  - нормализует (буквы/цифры + `-_.`, прочее → `-`);
  - сбрасывает UI в `NotAuthorized`;
  - дисконнектит WS;
  - очищает токены и сохраняет “пустую” конфигурацию.

Это важно: смена идентификатора эквивалентна “новому устройству/инстансу”.

## OAuth flow (beginAuthorization)

Файл: `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/remote/RemoteConnectorImpl.kt`

Константы:

- `BASE_URL = "https://broxy.run"`
- `WS_BASE_URL = "wss://broxy.run"`
- `WS_PATH = "/ws"`
- `REDIRECT_URI = "http://127.0.0.1:8765/oauth/callback"` (см. `LoopbackCallbackServer.DEFAULT_PORT`)

Последовательность:

1) UI вызывает `RemoteConnector.beginAuthorization()`.
2) `GET https://broxy.run/auth/mcp/login?redirect_uri=<REDIRECT_URI>` → `LoginResponse(authorization_url, state)`.
3) Проверка, что `authorization_url` содержит **тот же** `redirect_uri` (защита от рассинхронизации/устаревшего бэкэнда).
4) Открытие браузера (`Desktop.getDesktop().browse(...)`) на `authorization_url`.
5) Запуск loopback HTTP сервера на `127.0.0.1:8765` и ожидание callback:
   - `LoopbackCallbackServer.awaitCallback(expectedState)`
   - парсит query `code` и `state`;
   - проверяет `state == expectedState`.
6) Обмен code → access token:
   - `POST https://broxy.run/auth/mcp/callback` с JSON body `CallbackRequest(code, state, audience="mcp", redirect_uri=REDIRECT_URI)`
   - ответ: `TokenResponse(access_token, token_type, expires_at, scope)`
   - проверки:
     - `token_type` должен быть `bearer`
     - `scope` должен быть `mcp`
7) Регистрация serverIdentifier на бэкэнде:
   - `POST https://broxy.run/auth/mcp/register` с `Authorization: Bearer <access_token>`
   - body: `RegisterRequest(serverIdentifier, name, capabilities={prompts/tools/resources=true})`
   - ответ: `RegisterResponse(server_identifier, status, jwt_token)` — отдельный JWT для WebSocket.
8) Сохранение конфигурации (см. ниже) и, если локальный прокси уже запущен, подключение WebSocket.

### Email extraction (best-effort)

`RemoteConnectorImpl.extractEmail(token)`:

- берёт payload JWT (2-я часть `.`), base64url decode;
- пытается прочитать поле `email`.

Используется только для UI отображения; фейл не критичен.

## Хранение токенов и безопасность

`RemoteConfigStore` разделяет данные:

1) `remote.json` (не секреты):
   - `serverIdentifier`
   - `email`
   - `accessTokenExpiresAt`
   - `wsTokenExpiresAt`

2) `SecureStore` (секреты):
   - `remote.access_token`
   - `remote.ws_token`

По умолчанию используется `FileSecureStore` в:

`~/.config/broxy/secrets/`

и пытается выставить POSIX permissions `0600` (owner read/write).

Файлы:
- `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/remote/storage/RemoteConfigStore.kt`
- `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/remote/storage/SecureStore.kt`

В логах токены редактируются (“redact”):

- `abcdef...wxyz (len chars)`

см. `RemoteConnectorImpl.redactToken(...)`.

## Автоподключение и refresh токенов

На `RemoteConnectorImpl.start()`:

- загружается cached config;
- если токены протухли → конфиг чистится;
- если есть креды и локальный прокси уже запущен → `connectWithCachedTokens(auto=true)`.

`connectWithCachedTokens(auto)` выбирает:

1) Если есть валидный `wsToken` → сразу `connectWebSocket(wsToken)`.
2) Иначе, если access token валиден → повторно делает `registerServer(accessToken)` для получения нового `wsToken`, сохраняет его и подключается.
3) Иначе → требует повторной авторизации (logout/clear).

Валидация expiry:

- `isExpired(expiry) = now() > expiry - 60s` (минутный “skew”).

## WebSocket протокол: URL, заголовки, envelope

### Подключение

`RemoteConnectorImpl.connectWebSocket(jwt)` строит URL:

- `wss://broxy.run/ws/{serverIdentifier}`

`RemoteWsClient.connect()` подключается с заголовками:

- `Authorization: Bearer <jwt>`
- `Sec-WebSocket-Protocol: mcp`

Файл: `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/remote/ws/RemoteWsClient.kt`

### Envelope сообщений

Сериализация выполняется через `McpJson` (MCP SDK json).

Вход (от бэкэнда):

```json
{ "session_identifier": "uuid", "message": { /* MCP JSON-RPC */ } }
```

Выход (к бэкэнду):

```json
{
  "session_identifier": "uuid",
  "target_server_identifier": "server-id",
  "message": { /* MCP JSON-RPC */ }
}
```

Структуры:
- `McpProxyRequestPayload`
- `McpProxyResponsePayload`

Файл: `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/remote/ws/ProxyWebSocketTransport.kt`

### “MCP поверх WebSocket”: где живёт SDK Server

При connect:

1) `proxyProvider()` должен вернуть активный `ProxyMcpServer` (иначе ошибка).
2) создаётся `ProxyWebSocketTransport(serverIdentifier, sender=...)`.
3) строится MCP SDK `Server` через `buildSdkServer(proxy)`.
4) запускается `server.createSession(transport)` (отдельная SDK-сессия).
5) параллельно запускается reader loop:
   - читает `Frame.Text`;
   - декодирует `McpProxyRequestPayload`;
   - декодирует `JSONRPCMessage` из `message`;
   - вызывает `transport.handleIncoming(message, session_identifier)`.

Важная деталь синхронизации:

- `ProxyWebSocketTransport` хранит `sessionIdentifier` и использует `Mutex`, чтобы корректно отправлять ответы в ту же сессию.

### Обработка авторизационных ошибок

`RemoteWsClient.connect()` ловит `ClientRequestException`:

- если status `401/403`:
  - вызывает `onAuthFailure("WebSocket unauthorized (...)")`
  - выставляет статус `WsOffline/Error` в UI.

RemoteConnectorImpl по умолчанию не удаляет токены при такой ошибке, но переводит UI в `Error`. Это стоит учитывать при изменениях протокола/серверной стороны.

## Логи и диагностика WS-трафика

`ProxyWebSocketTransport.describeJsonRpcPayload(...)` формирует человекочитаемую сводку:

- тип JSON-RPC сообщения (request/response/notification/error)
- id/method/params ключи
- для `tools/list`/capabilities: счётчики, имена, поля схем.

Это используется в логах `RemoteWsClient` для каждой входящей/исходящей рамки.

Также см. существующий документ:
- `docs/websocket-preset-capabilities.md`

