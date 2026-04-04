# Core dependency and layer audit

This document captures a lightweight dependency map across core packages and flags
cross-layer coupling or mixed responsibilities to target in future refactors.

## Package dependency map (current)

- `config`
  - depends on: `models`, `utils`, `mcp.auth` (OAuth state storage)
  - owns: JSON IO, env placeholders, validation, hot reload
- `mcp`
  - depends on: `models`, `utils`, `config` (env resolution for stdio), `mcp.auth`
  - owns: client/server connection contracts, downstream clients, caches
- `proxy`
  - depends on: `models`, `mcp`, `utils`
  - owns: filtering, namespace rules, request dispatch
- `inbound`
  - depends on: `proxy`, `mcp` (SDK types), `utils`
  - owns: streamable HTTP/SSE routing, SDK server sync/decoding
- `runtime`
  - depends on: `proxy`, `mcp`, `inbound`, `utils`
  - owns: lifecycle, scheduling, per-server isolation
- `utils`
  - depends on: stdlib + Ktor/IO helpers where applicable
  - owns: logging, backoff, path and command resolution

## Refactor targets (cross-layer or mixed responsibilities)

1) `core/src/jvmMain/kotlin/io/qent/broxy/core/mcp/clients/StdioMcpClient.kt`
   - Uses `config.EnvironmentVariableResolver` from the config layer; consider moving env resolution
     into `utils` or the mcp layer so downstream clients do not depend on config internals.
2) `core/src/jvmMain/kotlin/io/qent/broxy/core/config/JsonConfigurationRepository.kt`
   - Depends on `mcp.auth.OAuthStateStore`; consider moving the storage interface under config or
     introducing an abstraction to avoid config -> mcp coupling.
3) `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/runtime/ProxyRuntimeSdkFacade.kt`
   - Depends directly on inbound SDK wiring (`buildSdkServer`, `syncSdkServer`); consider moving the
     adapter into inbound or a dedicated facade module to reduce runtime -> inbound coupling.
4) `core/src/jvmMain/kotlin/io/qent/broxy/core/config/ConfigMapper.kt`
   - Previously mixed defaults, validation, env resolution, and raw snapshot merging; continue
     decomposing into focused helpers (Phase 1 target).
5) `core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/SdkServerFactory.kt`
   - Owns both SDK wiring and fallback tool creation; consider extracting fallback tool construction
     into a proxy-facing helper to keep inbound focused on transport/SDK glue.
6) `core/src/jvmMain/kotlin/io/qent/broxy/core/mcp/clients/KtorMcpClient.kt`
   - Contains OAuth flow orchestration alongside transport logic; consider splitting OAuth handling
     into a dedicated auth coordinator to keep client responsibilities narrower.
