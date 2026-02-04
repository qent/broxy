# Unit testing guidelines

This project uses lightweight, fast unit tests focused on contract behavior. Keep tests deterministic,
isolated, and cheap to run.

What to test first (critical path):

- Core MCP flows: server connection, capabilities, tool routing.
- Proxy filtering and routing: preset filtering, allow list enforcement, prompt/resource routing.
- Client adapters (HTTP/SSE, WebSocket, STDIO): capability mapping, tool/prompt/resource calls.
- Inbound Streamable HTTP: POST request/response (JSON-only mode).
- Inbound SSE: connect via `/sse` and validate tool/prompt/resource flows.
- Caching and backoff: TTL behavior, backoff boundaries (no sleeps).

Style and patterns:

- AAA structure: Arrange data/mocks, Act once, Assert results and essential interactions.
- Constructor injection only. Do not mutate private internals from tests.
- Mockito-Kotlin for doubles: `val dep: Dep = mock()`, `whenever(dep.suspending()).thenReturn(value)` inside
  `runBlocking {}`.
- Prefer fakes (for example `FakeSdkClientFacade`) for happy paths. Use mocks for error branches and verification.
- Avoid `Thread.sleep`. For time-sensitive code (TTL), use minimal TTL + `delay`, or
  `kotlinx-coroutines-test` virtual time.

Locations and naming:

- JVM unit tests live under `core/src/jvmTest/kotlin/...` and `ui-adapter/src/jvmTest/kotlin/...`.
- Name tests by behavior, not method name:
  `filters_and_prefixes_with_mappings`, `connect_and_capabilities_and_callTool_with_mockito`.

Running tests:

- `./gradlew testAll` runs all tests across modules (unit + integration).
- `./gradlew allTests` is an alias for `testAll`.
- `./gradlew :cli:integrationTest` runs CLI integration tests (STDIO + Streamable HTTP).
- `./gradlew :test-mcp-server:selfCheck` runs the test MCP server self-check (STDIO, Streamable HTTP, HTTP SSE, WebSocket).

CLI tests:

- `./gradlew :cli:test`
- `./gradlew :cli:integrationTest`
- CLI integration tests require system properties:
  - `broxy.cliJar` (path to the `broxy-cli` shadowJar; provided by `:cli:shadowJar`)
  - `broxy.testMcpServerHome` (install dir with `bin/test-mcp-server`; provided by `:test-mcp-server:installDist`)
  - These are set automatically by the Gradle `:cli:integrationTest` task.

Static analysis and coverage:

- `./gradlew ktlintCheck` enforces Kotlin formatting.
- `./gradlew detekt` runs static analysis (SARIF/HTML/XML reports).
- `./gradlew :ui-adapter:checkUiAdapterCoreBoundary` verifies the ui-adapter core import boundary
  (runs via `:ui-adapter:check` and root `check`).
- `./gradlew koverXmlReport` generates coverage XML (plus HTML via `koverHtmlReport`).
- `:ui` detekt config resolves `commonMain` with the `desktopMain` classpath and wires
  `:ui:detekt` to `detektMetadataMain` so custom rules report fully-qualified references.

Helpful test utilities:

- `FakeSdkClientFacade` for client adapters.
- `SdkConnector` to inject a fake facade into clients.

Examples in this repo:

- Default server connection cache/refresh fallback: `DefaultMcpServerConnectionTest`.
- Multi-server routing and parsing: `MultiServerClientTest`.
- Proxy filtering and routing: `DefaultToolFilterTest`, `RequestRouterTest`, `ProxyMcpServerTest`.
- Client adapters: `KtorMcpClientStreamableHttpTest`, `KtorMcpClientWebSocketTest`,
  `KtorMcpClientAuthFlowTest`, `StdioMcpClientTest`.
- Utilities: `ExponentialBackoffTest`, `CapabilitiesCacheTest`.

Adding new tests:

- Cover both happy and failure paths for core flows.
- Keep tests independent; no external network or disk I/O (loopback/embedded servers are OK for inbound tests).
- Prefer verifying public outcomes; verify interactions only for critical delegation/guard logic.
