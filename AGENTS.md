# agents.md - development guide for broxy

## Documentation (mandatory for AI agents)

Required rules for any agent:

1. Before starting work, read relevant documents under `docs/` (minimum: `docs/readme.md`, then related sections).
2. After finishing a task that changes behavior, contracts, or data flows, update the corresponding `docs/*.md`
   files and the link list if needed.
3. Before finishing any task, run all required checks and fix every finding. Generated code must meet the
   highest quality standards for this project (formatting, static analysis, tests, and coverage).
4. All documentation files under `docs/` must use lowercase filenames (rename legacy uppercase files as needed).

Key documents:

- `docs/readme.md` - index and reading order.
- `docs/architecture.md` - architecture, modules, end-to-end flows.
- `docs/proxy_facade.md` - proxy facade, routing, `serverId:tool` namespace, SDK sync.
- `docs/downstream_mcp_connections.md` - downstream connections, timeouts, retry/backoff, capabilities cache.
- `docs/presets_and_filtering.md` - presets, filtering, prompt/resource routing, runtime switching.
- `docs/configuration_and_hot_reload.md` - `mcp.json`, `preset_*.json`, env placeholders, `ConfigurationWatcher`.
- `docs/inbound_transports.md` - inbound STDIO/Streamable HTTP and SDK adapter.
- `docs/remote_auth_and_websocket.md` - OAuth, registration, tokens, WebSocket envelope.
- `docs/capabilities_cache_and_ui_refresh.md` - UI capability cache, background refresh, statuses.
- `docs/logging_and_observability.md` - logging formats and trace events.
- `docs/testing.md` - unit testing practices.
- `docs/test_mcp_server_status.md` - self-check for the test MCP server.
- `docs/websocket_preset_capabilities.md` - WebSocket payloads for preset capabilities.

## Architectural principles

### 1) Clean Architecture

The project follows Clean Architecture with clear separation of layers:

- Domain layer (core): business logic independent of frameworks or platforms
- Data layer (core): repositories, data access, MCP clients/servers
- Presentation layer (ui/ui-adapter/cli): UI state, adapters, CLI commands

### 2) SOLID

Single Responsibility Principle:

- each class has a single responsibility
- UI logic is separated from business logic
- modules have clear boundaries

Open/Closed Principle:

- use interfaces for extensibility
- sealed classes for type-safe extensions
- plugin-like transport architecture

Liskov Substitution Principle:

- implementations must conform to interface contracts
- subclasses must preserve base behavior

Interface Segregation Principle:

- small, focused interfaces
- clients depend only on what they use

Dependency Inversion Principle:

- depend on abstractions, not concrete implementations
- dependency injection for wiring
- core does not depend on UI/CLI modules

### 3) Low coupling

- minimize dependencies between modules
- use events/callbacks for communication
- avoid cyclic dependencies

## Project structure

```
broxy/
|-- core/                      # platform-independent logic
|   |-- src/
|   |   |-- commonMain/       # shared code
|   |   |   |-- models/       # data classes, sealed classes
|   |   |   |-- repository/   # repository interfaces
|   |   |   |-- mcp/          # MCP client/server logic
|   |   |   |-- proxy/        # proxy logic
|   |   |   `-- utils/        # utilities
|   |-- jvmMain/              # JVM-specific code
|   `-- nativeMain/           # native-specific code
|-- ui-adapter/                # presentation adapter (UDF/MVI, Flow)
|   |-- src/
|   |   |-- commonMain/       # shared view-models, interfaces, core type aliases
|   |   `-- jvmMain/          # JVM implementations (ProxyController, ToolService)
|   `-- build.gradle.kts
|-- ui/                        # Compose Multiplatform UI (thin layer)
|   |-- src/
|   |   |-- commonMain/       # UI composables
|   |   |   |-- screens/      # app screens
|   |   |   |-- components/   # reusable components
|   |   |   |-- theme/        # Material 3 theme
|   |   |   `-- viewmodels/   # AppState and UI models only
|   |   |-- desktopMain/      # desktop-specific UI
|   |   `-- resources/        # resources (icons, strings)
|   `-- build.gradle.kts
|-- cli/                       # CLI module
|   |-- src/
|   |   `-- main/
|   |       `-- kotlin/
|   |           `-- commands/ # CLI commands
|   `-- build.gradle.kts
`-- build.gradle.kts           # root build file
```

## Thin UI with UDF + Compose

- Unidirectional data flow: UI events -> intents -> ui-adapter -> `StateFlow` updates -> UI renders via
  `collectAsState()`.
- MVI entities:
    - State: immutable UI data classes, exposed as `StateFlow`.
    - Intent: user/system actions; UI calls adapter methods, does not mutate state directly.
    - Effect: one-off events (toast/snackbar/navigation) via `SharedFlow` or channels.
- UI module:
    - contains Composables, theme, and simple UI models only.
    - does not import `core`.
    - no IO side effects; use `Flow` subscriptions and callbacks.
    - no `GlobalScope`; side effects only via `LaunchedEffect` / `rememberCoroutineScope`.
- ui-adapter:
    - depends on `core`.
    - encapsulates presentation logic, IO, orchestration, caches, lifecycle.
    - exports stores on `StateFlow`, services (ProxyController, ToolService), repository providers.
    - no Compose dependencies: no `MutableState`, `SnapshotStateList`, etc.
- State collection in UI:
    - always use `collectAsState()` from `StateFlow`.
    - no direct state mutation in UI; use intents only.
- Error handling:
    - public adapter methods return `Result<T>` and log failures.
    - UI displays errors (snackbar/dialog), no domain logic in UI.
- Testing:
    - ViewModel/store tests in `ui-adapter` with mocks.
    - UI tests render from state only (no real IO).

## Design patterns

### 1) Repository pattern

```kotlin
interface ConfigurationRepository {
    suspend fun loadMcpConfig(): Result<McpServersConfig>
    suspend fun saveMcpConfig(config: McpServersConfig): Result<Unit>
    suspend fun loadPreset(id: String): Result<Preset>
    suspend fun savePreset(preset: Preset): Result<Unit>
}
```

### 2) Factory pattern

```kotlin
object McpClientFactory {
    fun create(config: TransportConfig): McpClient = when (config) {
        is StdioTransport -> StdioMcpClient(config)
        is HttpTransport -> HttpMcpClient(config)
        is WebSocketTransport -> WebSocketMcpClient(config)
    }
}
```

### 3) Observer pattern

```kotlin
interface ConfigurationObserver {
    fun onConfigurationChanged(config: McpServersConfig)
    fun onPresetChanged(preset: Preset)
}
```

### 4) Strategy pattern

Used for filtering and routing strategies.

### 5) Proxy pattern

The core pattern of the project: MCP proxying.

## Working with MCP

### Core concepts

1) Client: connects to MCP server, requests capabilities
2) Server: provides tools, resources, prompts
3) Transport: communication method (STDIO, HTTP SSE, Streamable HTTP, WebSocket)
4) Capabilities: server features (tools, resources, prompts)

### Best practices

#### 1) Lifecycle management

```kotlin
class McpServerConnection(
    private val config: McpServerConfig,
    private val client: McpClient
) : AutoCloseable {
    private var isConnected = false

    suspend fun connect(): Result<Unit> {
        // connect with retry/backoff
    }

    override fun close() {
        // graceful shutdown
    }
}
```

#### 2) Error handling

```kotlin
sealed class McpError : Exception() {
    data class ConnectionError(override val message: String) : McpError()
    data class TransportError(override val message: String) : McpError()
    data class ProtocolError(override val message: String) : McpError()
    data class TimeoutError(override val message: String) : McpError()
}
```

#### 3) Capabilities cache

```kotlin
class CapabilitiesCache {
    private val cache = mutableMapOf<String, ServerCapabilities>()
    private val timestamps = mutableMapOf<String, Long>()

    fun get(serverId: String): ServerCapabilities? {
        // TTL check and return cached value
    }

    fun put(serverId: String, capabilities: ServerCapabilities) {
        // store with timestamp
    }
}
```

#### 4) Timeouts and retry/backoff

- `DefaultMcpServerConnection` implements exponential backoff, max retries, and `withTimeout`.
- `updateCallTimeout` and `updateCapabilitiesTimeout` keep RPC timeouts and capability fetch timeouts in sync.
- Each RPC uses a short-lived connection: create client -> connect -> call -> disconnect.
- `getCapabilities` falls back to cached data when available.

#### 5) Multi-server batching

- `MultiServerClient` performs parallel capability fetches and prefix handling.
- `DefaultRequestDispatcher` handles routing, batch calls, and strict tool allow lists.

## Configuration and observation

- `JsonConfigurationRepository` reads/writes `mcp.json` and `preset_*.json`, validates transports,
  resolves `${ENV}` and `{ENV}` placeholders via `EnvironmentVariableResolver`, and logs sanitized configs.
  Supported global keys: `requestTimeoutSeconds`, `capabilitiesTimeoutSeconds`,
  `capabilitiesRefreshIntervalSeconds`, `showTrayIcon`, `inboundSsePort`, `defaultPresetId`.
- JSON schemas in `core/src/commonMain/resources/schemas/` define config contracts; update them when
  structure changes.
- `ConfigurationWatcher` watches config files, debounces changes, and notifies observers. CLI and UI
  use it for hot reload without restarts.
- Manual triggers (`triggerConfigReload/triggerPresetReload`) are available for tests/headless flows.

## Logging and observability

- `Logger` and `CollectingLogger` (`core/src/commonMain/kotlin/io/qent/broxy/core/utils/Logger.kt` and
  `CollectingLogger.kt`) define the logging interface.
- `CollectingLogger` uses a `SharedFlow` with a replay buffer of 200 events.
- JSON logging (`JsonLogging.kt`) adds `timestamp`, `event`, and payload. This is used in
  `RequestDispatcher` and `SdkServerFactory` to trace LLM -> proxy -> downstream flows.
- CLI uses `FilteredLogger` to limit stdout noise; STDIO mode logs to stderr.

## Namespace and routing

- `DefaultNamespaceManager` enforces `serverId:tool` prefixes. Calls without prefix are invalid.
- `DefaultToolFilter` and `DefaultPresetEngine` apply presets and build routing tables for prompts/resources.
- `ToolRegistry` indexes tools with a 1-minute TTL for search/autocomplete.

## Platform adapters

- MCP clients are abstracted via `McpClientProvider` and `defaultMcpClientProvider()` with JVM
  implementation in `McpClientFactoryJvm`. `StdioMcpClient` and `KtorMcpClient` cover STDIO/SSE/
  Streamable HTTP/WebSocket downstream transports.
- Inbound traffic is served by `InboundServerFactory` and its JVM implementations
  (`core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/InboundServers.kt`):
  STDIO and Streamable HTTP (JSON-only). HTTP SSE inbound is not supported; `HttpTransport` is
  treated as Streamable HTTP for backward compatibility.
- `buildSdkServer` (`core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/SdkServerFactory.kt`) adapts
  proxy capabilities to MCP SDK and applies fallback decoding.

## UI adapter and AppStore

- `AppStore` implements UDF/MVI with coroutines: it stores config, manages `StateFlow<UIState>`,
  caches capability snapshots, refreshes them on `capabilitiesRefreshIntervalSeconds` (default 300s,
  UI enforces min 30s), and proxies intents (refresh, CRUD, start/stop proxy, timeouts, tray).
- `ProxyController` is an `expect` interface; JVM implementation `ProxyControllerJvm` wires downstream
  connections and inbound server, and propagates timeouts.
- `fetchServerCapabilities` is used by UI to validate servers; JVM implementation uses one attempt
  with user-configured timeouts.
- UI (`ui/src/commonMain/...`) is declarative: screens collect `UIState` and call `Intents`.

## CLI mode

- `broxy proxy` (`cli/src/main/kotlin/io/qent/broxy/cli/commands/ProxyCommand.kt`) starts the proxy,
  applies a preset, and runs `ConfigurationWatcher` for hot reload.
- CLI uses `DefaultMcpServerConnection`, `ProxyMcpServer`, `InboundServerFactory`; config changes
  rebuild downstream and inbound with graceful shutdown.
- Flags: `--inbound`, `--url`, `--log-level`, `--config-dir`, `--preset-id` (required).

## Build and dependencies

- Modules: `core`, `ui-adapter`, `ui`, `cli` (see `settings.gradle.kts`).
- Key dependencies: MCP Kotlin SDK (`io.modelcontextprotocol:kotlin-sdk`), Ktor server/client,
  Compose Multiplatform `material3`, Clikt CLI. Versions are centralized in `gradle.properties`.
- `cli` is built via the `shadowJar` fat-jar task, producing `broxy-cli` output.
- `./gradlew build` runs module tests and produces artifacts.

## Testing

- Tests live in `core/src/jvmTest` and `ui-adapter/src/jvmTest`.
- Use `kotlinx-coroutines-test` for coroutine tests; avoid `Thread.sleep`.
- UI adapter tests should cover `AppStore` and `ProxyController` with `ConfigurationRepository` mocks.

### 1) Unit tests

- test each class in isolation
- use mocks for dependencies
- cover edge cases and error scenarios

```kotlin
class PresetEngineTest {
    @Test
    fun `should filter tools based on preset`() {
        // Given
        val preset = Preset(/* ... */)
        val all = mapOf<String, ServerCapabilities>(/* ... */)

        // When
        val filtered = DefaultToolFilter().filter(all, preset)

        // Then
        assertEquals(expected, filtered)
    }
}
```

### 2) Integration tests

- test interactions between components
- use test doubles for external services
- validate complete workflows

### 3) UI tests

```kotlin
@Test
fun serverCard_displaysCorrectInfo() = runComposeUiTest {
    setContent {
        ServerCard(
            server = testServer,
            onEdit = {},
            onDelete = {}
        )
    }

    onNodeWithText("Test Server").assertExists()
    onNodeWithText("Connected").assertExists()
}
```

## Error handling

### 1) Result type for operations

```kotlin
suspend fun loadConfig(): Result<Config> = runCatching {
    // operation that can fail
}.onFailure { exception ->
    logger.error("Failed to load config", exception)
}
```

### 2) Graceful degradation

- if a server is unavailable, continue with others
- if preset load fails, fall back to default
- if save fails, notify the user but do not crash

### 3) Logging

```kotlin
private val logger = LoggerFactory.getLogger(this::class.java)

fun processRequest(request: Request) {
    logger.debug("Processing request: ${request.id}")
    try {
        // processing
        logger.info("Request processed successfully: ${request.id}")
    } catch (e: Exception) {
        logger.error("Failed to process request: ${request.id}", e)
        throw e
    }
}
```

## Configuration and security

### 1) Environment variables

```kotlin
object EnvironmentResolver {
    private val pattern = Pattern.compile("\\$\\{([^}]+)\\}")

    fun resolve(value: String): String {
        val matcher = pattern.matcher(value)
        return matcher.replaceAll { match ->
            val envVar = match.group(1)
            System.getenv(envVar) ?: throw ConfigurationException("Missing env var: $envVar")
        }
    }
}
```

### 2) Configuration validation

```kotlin
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val transport: TransportConfig
) {
    init {
        require(id.isNotBlank()) { "Server ID cannot be blank" }
        require(name.isNotBlank()) { "Server name cannot be blank" }
    }
}
```

### 3) Safe logging

```kotlin
fun logConfig(config: McpServerConfig) {
    val sanitized = config.copy(
        env = config.env.mapValues { (key, value) ->
            if (key.contains("TOKEN") || key.contains("SECRET")) {
                "***"
            } else {
                value
            }
        }
    )
    logger.info("Loaded config: $sanitized")
}
```

## Performance

### 1) Coroutines and parallelism

```kotlin
class MultiServerClient {
    suspend fun fetchAllCapabilities(): Map<String, ServerCapabilities> = coroutineScope {
        servers.map { server ->
            async {
                server.id to server.getCapabilities()
            }
        }.awaitAll().toMap()
    }
}
```

### 2) Lazy loading

```kotlin
class ToolRegistry {
    private val tools by lazy {
        loadToolsFromServers()
    }

    fun getTools(): List<Tool> = tools
}
```

### 3) Caching

- cache server capabilities
- cache filtered results
- invalidate cache on configuration changes

## Code style and conventions

### 1) Naming

- classes: PascalCase (`McpServerConfig`)
- functions/variables: camelCase (`loadConfig`)
- constants: UPPER_SNAKE_CASE (`DEFAULT_TIMEOUT`)
- packages: lowercase (`io.qent.broxy.core`)

### 2) Class structure

```kotlin
class ExampleClass(
    private val dependency: Dependency  // constructor parameters
) {
    companion object {
        // constants
        private const val CONSTANT = "value"
    }

    // properties
    private val property = "value"

    // initialization
    init {
        // init block
    }

    // public functions
    fun publicFunction() {
        // implementation
    }

    // private functions
    private fun privateFunction() {
        // implementation
    }
}
```

### 3) Documentation

```kotlin
/**
 * Manages MCP server connections and lifecycle.
 *
 * @property config Configuration for the MCP server
 * @property client MCP client instance
 */
class McpServerManager(
    private val config: McpServerConfig,
    private val client: McpClient
) {
    /**
     * Starts the MCP server connection.
     *
     * @return Result indicating success or failure
     */
    suspend fun start(): Result<Unit> {
        // Implementation
    }
}
```

## Extensibility

### 1) Transport plugin architecture

```kotlin
interface TransportPlugin {
    val name: String
    fun createClient(config: TransportConfig): McpClient
    fun createServer(config: TransportConfig): McpServer
}

object TransportRegistry {
    private val plugins = mutableMapOf<String, TransportPlugin>()

    fun register(plugin: TransportPlugin) {
        plugins[plugin.name] = plugin
    }

    fun getPlugin(name: String): TransportPlugin? = plugins[name]
}
```

### 2) Custom filters

```kotlin
interface ToolFilter {
    fun filter(tools: List<Tool>, context: FilterContext): List<Tool>
}

class CompositeFilter(
    private val filters: List<ToolFilter>
) : ToolFilter {
    override fun filter(tools: List<Tool>, context: FilterContext): List<Tool> {
        return filters.fold(tools) { acc, filter ->
            filter.filter(acc, context)
        }
    }
}
```

## Code review checklist

- [ ] Code follows architectural principles (SOLID, Clean Architecture)
- [ ] No cyclic dependencies between modules
- [ ] Public APIs are documented
- [ ] Error handling uses `Result`
- [ ] Async code uses coroutines correctly
- [ ] Resources are closed (no leaks)
- [ ] Tests cover key functionality
- [ ] Configuration is validated on load
- [ ] Sensitive data is not logged
- [ ] UI components are reusable and testable
- [ ] `./gradlew build` succeeds
- [ ] `./gradlew testAll` succeeds

## Unit tests with Mockito

- Constructor injection only:
    - dependencies to mock are passed via constructors;
    - no global singletons or test hooks.
- Mockito-Kotlin usage:
    - create mocks: `val dep: Dep = mock()`
    - stub sync methods: `whenever(dep.method(arg)).thenReturn(value)`
    - stub suspend methods inside `runBlocking` or Mockito-Kotlin extensions
    - sequential answers: `whenever(dep.call()).thenReturn(v1, v2, v3)`
    - verify: `verify(dep).method(arg)`, with `times(n)` for counts
- Test structure (AAA):
    - Arrange: create inputs and mocks
    - Act: one clear method call
    - Assert: minimal verification of outcomes/interactions
- Coroutines:
    - use `runBlocking` or `kotlinx-coroutines-test`
    - avoid `Thread.sleep`
- Avoid:
    - mutating internal fields
    - mocking simple data classes
    - testing through global state

Example:

```kotlin
@Test
fun `should update server list when config changes`() = runTest {
    // Arrange
    val repo = mock<ConfigurationRepository>()
    val store = AppStore(...)

    // Act
    val result = store.loadConfig()

    // Assert
    assertTrue(result.isSuccess)
}
```

## Build and test requirement (for releases)

When preparing a release or major change:

1) Run full build: `./gradlew build`.
2) Run all tests: `./gradlew testAll`.
3) Run CLI integration tests: `./gradlew :cli:integrationTest`.
