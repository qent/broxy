# WebSocket payloads for preset capabilities

Этот документ фиксирует, какие данные мы отправляем на бэкэнд по WebSocket при работе в удалённом режиме, и как
описываются инструменты, промпты и ресурсы активного пресета.

## Транспорт и оболочка

- Подключение: `wss://broxy.run/ws/{serverIdentifier}` с заголовками `Authorization: Bearer <jwt>` и
  `Sec-WebSocket-Protocol: mcp`. URL и токен формирует `RemoteConnectorImpl`.
- Каждая текстовая рамка — JSON с обёрткой для мультисессий:

```json
// От бэкэнда к прокси
{ "session_identifier": "uuid", "message": { /* MCP JSON-RPC */ } }

// От прокси к бэкэнду
{
  "session_identifier": "uuid",
  "target_server_identifier": "server-id",
  "message": { /* MCP JSON-RPC */ }
}
```

`message` всегда содержит стандартные MCP JSON-RPC сообщения, сериализованные через `McpJson`.

## Передача возможностей текущего пресета

- После `initialize` бэкэнд запрашивает `tools/list`, `prompts/list`, `resources/list`.
- Ответ формирует `ProxyMcpServer` на основе активного пресета: в списке только разрешённые сущности, имена инструментов
  уже префиксованы `serverId:tool`.
- Формат ответа (пример):

```json
{
  "jsonrpc": "2.0",
  "id": "1",
  "result": {
    "tools": [
      {
        "name": "s1:search",
        "description": "Search",
        "input_schema": {
          "type": "object",
          "properties": {
            "query": { "type": "string" },
            "top_k": { "type": "integer" }
          },
          "required": ["query"]
        },
        "output_schema": {
          "type": "object",
          "properties": {
            "results": { "type": "array" }
          }
        },
        "annotations": { /* MCP annotations, если есть */ }
      }
    ],
    "prompts": [
      { "name": "hello", "description": "Greet user", "arguments": [] }
    ],
    "resources": [
      { "name": "doc", "uri": "docs/{id}", "description": "Doc page", "mime_type": "text/html" }
    ]
  }
}
```

- `input_schema` и `output_schema` — JSON Schema из downstream-сервера; прокси их не обрезает.
- `resources.uri` может содержать плейсхолдеры `{param}`; `prompts.arguments` и `tools.input_schema.required` показывают
  обязательные поля.

## Вызовы инструментов, промптов и ресурсов

- Вызов инструмента:

```json
{
  "jsonrpc": "2.0",
  "id": "42",
  "method": "tools/call",
  "params": {
    "name": "s1:search",
    "arguments": { "query": "kotlin", "top_k": 5 },
    "meta": { /* RequestMeta */ }
  }
}
```

- Ответ инструмента:

```json
{
  "jsonrpc": "2.0",
  "id": "42",
  "result": {
    "content": [
      { "type": "text", "text": "Result preview" }
    ],
    "structured_content": { "results": [{ "title": "…" }] },
    "meta": { "trace_id": "…" },
    "is_error": false
  }
}
```

- Промпт и ресурс используют те же поля в `params`: `name` + `arguments` для `prompts/get`, `uri` для `resources/read`;
  ответы от downstream транслируются без изменений.

## Логирование и проверки

- `ProxyWebSocketTransport.describeJsonRpcPayload` логирует сводку по каждому JSON-RPC сообщению: количество
  инструментов/промптов/ресурсов, имена и поля `input_schema`/`output_schema`, целевой инструмент и ключи аргументов,
  типы контента и ключи `structured_content` в ответах.
- Тесты `ui-adapter/src/jvmTest/kotlin/io/qent/broxy/ui/adapter/remote/ws/ProxyWebSocketTransportTest.kt` фиксируют:
    - наличие схем и счётчиков сущностей в `tools/list` ответах;
    - отражение ключей аргументов запроса и структуры ответа `tools/call`.
- Таким образом, по логам видно, что на бэкэнд уходит полный снимок возможностей текущего пресета с описанием входных и
  выходных данных каждого инструмента.
