# Unit testing guidelines

## Purpose

Define repository testing expectations, entrypoints, and quality checks for unit/integration coverage.

## When to read

- Before adding/changing tests.
- Before final validation of behavior-changing changes.

## Source-of-truth files

- `build.gradle.kts`
- `cli/build.gradle.kts`
- `test-mcp-server/build.gradle.kts`

## Behavior contract

Behavior changes should be covered by deterministic tests and validated by required Gradle checks.

This project uses lightweight, fast unit tests focused on contract behavior. Keep tests deterministic,
isolated, and cheap to run.

What to test first (critical path):

- Core MCP flows: server connection, capabilities, tool routing.
- Proxy filtering and routing: preset filtering, allow list enforcement, prompt/resource routing.
- Agent runtime flows: schedule overlap policy, standalone run persistence (`runs_index.json` + `run_<id>.json`), and repository consistency.
- Agent tool flows: nested `agent -> agent` execution, unresolved refs behavior, and cycle detection.
- Agent filesystem flows: workspace sandbox policy, traversal blocking, and access-level tool exposure.
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

- JVM unit tests live under:
  - `core/src/jvmTest/kotlin/...`
  - `server-registry/src/jvmTest/kotlin/...`
  - `ui-adapter/src/jvmTest/kotlin/...`
- Agent unit tests live under `agents/src/test/kotlin/...`.
- Name tests by behavior, not method name:
  `filters_and_prefixes_with_mappings`, `connect_and_capabilities_and_callTool_with_mockito`.

Running tests:

- `./gradlew testAll` runs all tests across modules (unit + integration).
- `./gradlew allTests` is an alias for `testAll`.
- `./gradlew :agents:test` runs agent module unit tests.
- `./gradlew :agents-codex:test --tests "*CodexCliExecutorLiveIntegrationTest*" -Dbroxy.codex.live=true`
  runs an opt-in live Codex CLI integration check (real `codex exec`; requires local Codex auth).
- `./gradlew :cli:integrationTest` runs CLI integration tests (proxy STDIO/HTTP + agent one-shot flows).
- `./gradlew :test-mcp-server:selfCheck` runs the test MCP server self-check (STDIO, Streamable HTTP, HTTP SSE, WebSocket).

CLI tests:

- `./gradlew :cli:test`
- `./gradlew :cli:integrationTest`
- CLI integration tests require system properties:
  - `broxy.cliJar` (path to the `broxy-cli` shadowJar; provided by `:cli:shadowJar`)
  - `broxy.testMcpServerHome` (install dir with `bin/test-mcp-server`; provided by `:test-mcp-server:installDist`)
  - These are set automatically by the Gradle `:cli:integrationTest` task.
- Agent CLI integration coverage (`BroxyCliAgentRunLangChainIntegrationTest`) is included in
  `:cli:integrationTest`:
  - uses `broxy agent run` (one-shot);
  - covers LangChain runtime only;
  - uses an embedded OpenAI-compatible test backend for `/chat/completions`, scripted by endpoint URL
    scenario arguments;
  - verifies a behavioral matrix for `capabilities × filesystem`:
    - no capabilities + no filesystem access + plain text response;
    - capabilities enabled + no filesystem access + downstream tool allowed;
    - no capabilities + no filesystem access + downstream tool blocked by preset allow list;
    - no capabilities + no filesystem access + filesystem tool blocked by preset allow list;
    - no capabilities + `READ_ONLY` filesystem + local `fsRead` allowed;
    - capabilities enabled + `READ_ONLY` filesystem + mixed downstream + filesystem tool sequence;
  - asserts structured one-shot output (`status`, `runtime`, `response`, `errorMessage`) and strict
    `toolCalls` order/composition (`serverId`, `toolName`, `step`).
- Nested agent-tool CLI integration coverage (`BroxyCliAgentRunNestedAgentToolIntegrationTest`) is included in
  `:cli:integrationTest` and is mandatory for `LANGCHAIN`:
  - positive: outer agent calls inner agent tool and consumes returned string (`A -> B`);
  - negative: cycle (`A -> B -> A`) returns `FAILED` with non-zero exit code and cycle-related error message.

Static analysis and coverage:

- `./gradlew ktlintCheck` enforces Kotlin formatting.
- `./gradlew detekt` runs static analysis (SARIF/HTML/XML reports).
- `./gradlew detektNoFullyQualifiedNames` runs the custom `NoFullyQualifiedNames` rule for every module
  (non-test sources only).
- `./gradlew detektNoUnreferencedDeclarations` runs the custom `UnreferencedDeclaration` rule for every module
  (non-test sources only). It checks `internal/public` declarations using symbol-aware resolution
  (`BindingContext` targets for calls/name references/callable references) and supports allowlists via
  `config/detekt/no_unreferenced_declarations.yml`.
- `./gradlew check` includes `detektNoFullyQualifiedNames` in each module, so fully-qualified name violations
  fail regular verification builds.
- `./gradlew check` does not include `detektNoUnreferencedDeclarations` by default.
- Set `-PenableDetektNoUnreferencedDeclarations=true` to include
  `detektNoUnreferencedDeclarations` in `check`.
- `./gradlew :ui-adapter:checkUiAdapterCoreBoundary` verifies the ui-adapter core import boundary
  (runs via `:ui-adapter:check` and root `check`).
- `./gradlew koverXmlReport` generates coverage XML (plus HTML via `koverHtmlReport`).

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
- Catalog registry: `CatalogInstallPlannerTest`, `GithubCatalogRepositoryTest`.
- Agents: `DefaultAgentServiceTest`, `JsonAgentRepositoryTest`, `JsonAgentRunRepositoryTest`, `CronScheduleValidatorTest`,
  `ClaudeSubagentMarkdownCodecTest`, `ClaudeCompatibilityTest`, `ScopedMcpConnectionsFactoryTest`,
  `AgentSecretsStoreJvmTest`, `LangChain4jAgentExecutorTest`, `AgentFileSystemToolsTest`,
  `AgentToolsMcpConnectionTest`.

Adding new tests:

- Cover both happy and failure paths for core flows.
- For filesystem tools, cover:
  - `NONE`/`READ_ONLY`/`READ_WRITE` exposure differences;
  - missing non-default workspace failure;
  - binary file rejection (`binary_file_not_supported`);
  - path traversal rejection (`path_outside_workspace`);
  - unified response envelope (`ok/data` or `ok=false/code/message`).
- Keep tests independent; no external network or disk I/O (loopback/embedded servers are OK for inbound tests).
- Prefer verifying public outcomes; verify interactions only for critical delegation/guard logic.
