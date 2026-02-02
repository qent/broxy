# UI-adapter integration tests (AppStore + StateFlow)

## Goal
Implement a minimal end-to-end (E2E) test flow for `ui-adapter` that mirrors the **DEFAULT_SCENARIO**
from CLI integration tests, but drives the app through `AppStore` + `StateFlow` instead of UI.
Top-level intent: same scenario coverage as `BroxyCliScenarioIntegrationTest`, with UI actions
performed via `Intents` and `AppStore` methods.

## Scope (must match CLI DEFAULT_SCENARIO at a high level)
- Start downstream test MCP servers (stdio/http/sse/ws).
- Load the same `mcp.json` and `preset_test.json` content as CLI integration tests (adapted to temp paths).
- Start proxy via `AppStore.start()`.
- Verify filtered capabilities match preset (tools/prompts/resources).
- (Optional but recommended for true E2E parity) Call tool/prompt/resource via inbound HTTP client
  to verify end-to-end behavior, matching CLI’s `McpClientInteractions`.
- Validate UI state transitions via `StateFlow<UIState>` and `Intents`.

## Non-goals
- No Compose UI or UI automation.
- No remote/BroCloud flows.
- No persistence to user config directories.

## Constraints and references
- Reference CLI integration test flow:
  - `cli/src/integrationTest/kotlin/io/qent/broxy/cli/BroxyCliScenarioIntegrationTest.kt`
  - `cli/src/integrationTest/kotlin/io/qent/broxy/cli/support/*`
- Use the same test server configs and presets:
  - `cli/src/integrationTest/resources/integration/mcp.json`
  - `cli/src/integrationTest/resources/integration/preset_test.json`
- Require system property:
  - `broxy.testMcpServerHome` (same as CLI IT)

---

## Step-by-step implementation plan (for Codex agent)

### 1) Decide test source set and wiring
1.1. Add a dedicated integration test source set for `ui-adapter` (recommended to mirror CLI):
   - `ui-adapter/src/integrationTest/kotlin/...`
   - `ui-adapter/src/integrationTest/resources/integration/...`
1.2. Update `ui-adapter/build.gradle.kts`:
   - Create `integrationTest` source set (jvm only).
   - Add dependencies: `kotlin("test")`, `kotlinx-coroutines-test`, `io.modelcontextprotocol:kotlin-sdk-core`,
     `io.ktor:ktor-client-*` if needed for inbound client tests.
   - Register test task (e.g., `integrationTest`) and wire into `check` and root `testAll` if required.
1.3. Alternative (minimal change): keep tests in `ui-adapter/src/jvmTest/...` and mark them
   as integration (naming convention). Prefer the dedicated source set for parity and isolation.

### 2) Duplicate or factor shared CLI test helpers
2.1. **Minimal approach**: copy the needed support classes into `ui-adapter` integration test package.
   - Copy/port (with package rename):
     - `BroxyCliIntegrationConfig` → `UiAdapterIntegrationConfig`
     - `BroxyCliIntegrationFiles` → `UiAdapterIntegrationFiles`
     - `BroxyCliTestEnvironment` → `UiAdapterTestEnvironment` (only parts needed)
   - Keep constants aligned with CLI defaults (IDs, names, timeouts).
2.2. **Optional refactor**: extract CLI support into a shared test-support module.
   - This is larger than minimal; only do if requested.

### 3) Add integration resource templates for ui-adapter
3.1. Create `ui-adapter/src/integrationTest/resources/integration/mcp.json`
   by copying from `cli/.../mcp.json`.
3.2. Create `ui-adapter/src/integrationTest/resources/integration/preset_test.json`
   by copying from `cli/.../preset_test.json`.
3.3. Keep placeholders identical:
   - `__TEST_MCP_SERVER_COMMAND__`
   - `__TEST_MCP_SERVER_HTTP_URL__`
   - `__TEST_MCP_SERVER_SSE_URL__`
   - `__TEST_MCP_SERVER_WS_URL__`

### 4) Implement environment setup utilities (ui-adapter IT support)
4.1. Test server launcher:
   - Start test MCP servers in modes: `http`, `sse`, `ws`, plus stdio command.
   - Reuse CLI logic from `BroxyCliTestEnvironment.startTestServers()`.
4.2. Config writer:
   - Write `mcp.json` and copy `preset_test.json` into a temp directory.
   - Substitute placeholders using the real test server command + URLs.
4.3. Inbound port allocation:
   - Add `nextFreePort()` helper (reuse CLI approach with `ServerSocket(0)`).
4.4. Cleanup:
   - Ensure process teardown and temp dir deletion in `@AfterAll` or try/finally.

### 5) AppStore bootstrap wiring (real dependencies, no UI)
5.1. Build `JsonConfigurationRepository` with `baseDir = tempConfigDir`.
5.2. Provide a `CollectingLogger` with a no-op sink or filtered logger (avoid noisy output).
5.3. Create `ProxyController` with `createProxyController(logger, configDir = tempConfigDir.pathString)`.
5.4. Wrap in `ProxyLifecycle` and build `AppStore` with:
   - `UiSettingsRepository.Noop`
   - `ServerIconRepository.Noop`
   - `RemoteConnector = NoOpRemoteConnector`
   - `CapabilityCachePersistence.Noop`
   - `aiClientConnectors = emptyList()`
   - `enableBackgroundRefresh = false` (for determinism)
5.5. Call `store.start()` and await `UIState.Ready`.

### 6) StateFlow waits + assertions (UI-level parity)
6.1. Await `UIState.Ready` with timeout (e.g., 15s).
6.2. Assert:
   - `proxyStatus == UiProxyStatus.Running`
   - `servers` include all expected IDs: `test-stdio`, `test-http`, `test-sse`, `test-ws`
   - (Optional) `inboundHttpPort` matches the chosen port
6.3. Capabilities verification through ui-adapter API:
   - Call `store.listEnabledServerCaps()` with retry (mirror CLI `awaitFilteredCapabilities`).
   - For each server, assert tools/prompts/resources sets match expected values
     (same as CLI constants but **without serverId prefix**).

### 7) Optional full E2E parity via inbound client
7.1. Create `KtorMcpClient` in Streamable HTTP mode:
   - URL: `http://127.0.0.1:<inboundPort>/mcp`
7.2. Reuse CLI `McpClientInteractions` logic, but keep in ui-adapter IT package:
   - `awaitFilteredCapabilities()`
   - `callExpectedTools()`
   - `fetchExpectedPrompts()`
   - `readExpectedResources()`
   - `assertExpectedToolResults()`
   - `assertPromptPersonalizedResponses()`
   - `assertResourceContentsMatch()`
7.3. This validates AppStore → ProxyRuntime → inbound → downstream end-to-end.

### 8) Minimal UI-intent action (UI-driven change)
8.1. Use `state.intents.refresh()` and verify:
   - `UIState` stays `Ready`
   - capabilities list remains correct after refresh
8.2. (Optional) Use `state.intents.toggleServer("test-ws", false)`
   - Verify `listEnabledServerCaps()` no longer includes WS capabilities.

### 9) Teardown
9.1. Call `store.stop()` in `@AfterAll`.
9.2. Stop test MCP server processes.
9.3. Delete temp config directory.

### 10) Required checks
10.1. Run:
   - `./gradlew :ui-adapter:test` (or new `:ui-adapter:integrationTest`)
   - `./gradlew testAll` if wiring was updated
   - Ensure `:ui-adapter:checkUiAdapterCoreBoundary` still passes

---

## Acceptance criteria
- Test starts/stops without manual UI.
- Capabilities loaded via `AppStore` match CLI DEFAULT_SCENARIO expectations.
- `UIState` transitions observed (Loading → Ready, proxy Running).
- (If optional inbound client step included) tool/prompt/resource results match CLI expectations.

## Notes / gotchas
- `AppStore` always starts inbound Streamable HTTP; allocate a free port to avoid collisions.
- UI snapshots are **per-server** (unprefixed names), while inbound client uses **prefixed** names.
- Do not use user config directories; only temp dirs.
- Keep background refresh disabled unless explicitly testing it.
