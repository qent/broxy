# WebSocket Preset Capability Notifications

## Purpose

Defines the remote WebSocket payload contract used by Broxy cloud integration to notify remote
clients about preset selection/composition changes without restarting sessions.

## When to read

- When changing remote WebSocket envelope schemas.
- When changing preset-change notification timing or gating conditions.
- When updating remote connector behavior between ui-adapter and bro-cloud.

## Source-of-truth files

- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/remote/RemotePresetChange.kt`
- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/store/internal/ProxyRuntime.kt`
- `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/remote/BroCloudRemoteConnectorAdapter.kt`
- `bro-cloud/src/main/kotlin/io/qent/broxy/ui/adapter/remote/ws/RemoteWsClient.kt`
- `bro-cloud/src/main/kotlin/io/qent/broxy/ui/adapter/remote/ws/ProxyWebSocketTransport.kt`

## Behavior contract

The remote transport uses MCP JSON-RPC wrapped in a Broxy envelope. Preset changes are emitted as
`broxy/preset_changed` notifications with stable params.

## Envelope Shapes

### Inbound envelope (remote backend -> local transport)

```json
{
  "session_identifier": "<session-id>",
  "message": { "jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {} }
}
```

Fields:

- `session_identifier`: identifies the WS-bridged MCP session.
- `message`: raw MCP JSON-RPC payload.

### Outbound envelope (local transport -> remote backend)

```json
{
  "session_identifier": "<session-id>",
  "target_server_identifier": "<server-identifier>",
  "message": { "jsonrpc": "2.0", "id": 1, "result": {} }
}
```

Fields:

- `session_identifier`: active MCP-over-WS session.
- `target_server_identifier`: remote target binding.
- `message`: raw MCP JSON-RPC payload.

## Preset Change Notification

Method:

- `broxy/preset_changed`

Notification params:

- `change_type` (required): `selection` or `composition`
- `preset_id` (optional): active preset id, when present

Reference constants:

- method: `PRESET_CHANGED_METHOD`
- session prefix: `PRESET_CHANGE_SESSION_PREFIX = "preset-change:"`

Preset-change payloads are sent with synthetic session identifiers shaped as:

- `preset-change:<serverIdentifier>:<uuid>`

## Emission Conditions

Preset change notifications are emitted only when all conditions hold:

- remote connector mode is `Allowed` (not manually disconnected/logged out),
- proxy is running (or just became running),
- adapter mode is disabled (`adapterMode=false`).

Emission trigger points:

- proxy start/restart path in `ProxyRuntime.ensureInboundRunning(...)`,
- runtime preset apply path in `ProxyRuntime.ensureInboundRunning(...)`,
- adapter/runtime settings updates that call `notifyPresetChanged(..., composition)`.

No emission occurs while disconnected/logged-out or when adapter mode is enabled.

## Change Type Semantics

- `selection`: active preset selection changed.
- `composition`: capability composition may have changed while keeping current selection
  (for example runtime re-sync/settings affecting exposed capability surface).

## Related Docs

- `docs/remote_auth_and_websocket.md`
- `docs/proxy_facade.md`
- `docs/inbound_transports.md`
