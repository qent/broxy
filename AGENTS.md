# AGENTS.md - Руководство по разработке MCP Proxy

## Архитектурные принципы

### 1. Clean Architecture
Проект следует принципам чистой архитектуры с четким разделением на слои:

- **Domain Layer (core)**: Бизнес-логика, не зависящая от фреймворков и платформ
- **Data Layer (core)**: Репозитории, работа с данными, MCP клиенты/серверы
- **Presentation Layer (ui/cli)**: UI компоненты, ViewModels, CLI команды

### 2. SOLID принципы

#### Single Responsibility Principle (SRP)
- Каждый класс отвечает за одну конкретную задачу
- Разделение concerns: UI логика отделена от бизнес-логики
- Модули имеют четкие границы ответственности

#### Open/Closed Principle (OCP)
- Использование интерфейсов для расширяемости
- Sealed classes для типобезопасных расширений
- Plugin-подобная архитектура для транспортов

#### Liskov Substitution Principle (LSP)
- Все реализации интерфейсов полностью соответствуют контракту
- Наследники не нарушают поведение базовых классов

#### Interface Segregation Principle (ISP)
- Мелкие, специализированные интерфейсы вместо больших
- Клиенты зависят только от нужных им методов

#### Dependency Inversion Principle (DIP)
- Зависимость от абстракций, а не от конкретных реализаций
- Dependency Injection для управления зависимостями
- Core модуль не зависит от UI/CLI модулей

### 3. Низкая связанность (Low Coupling)
- Минимальные зависимости между модулями
- Использование событий/callbacks для коммуникации
- Избегание циклических зависимостей

## Структура проекта

```
mcp-proxy/
├── core/                      # Платформенно-независимая логика
│   ├── src/
│   │   ├── commonMain/       # Общий код для всех платформ
│   │   │   ├── models/       # Data classes, sealed classes
│   │   │   ├── repository/   # Интерфейсы репозиториев
│   │   │   ├── mcp/          # MCP клиент/сервер логика
│   │   │   ├── proxy/        # Прокси логика
│   │   │   └── utils/        # Утилиты
│   │   ├── jvmMain/          # JVM-специфичный код
│   │   └── nativeMain/       # Native-специфичный код
│   └── build.gradle.kts
├── ui/                        # Compose Multiplatform UI
│   ├── src/
│   │   ├── commonMain/       # Общий UI код
│   │   │   ├── screens/      # Экраны приложения
│   │   │   ├── components/   # Переиспользуемые компоненты
│   │   │   ├── theme/        # Material 3 тема
│   │   │   └── viewmodels/   # ViewModels
│   │   ├── desktopMain/      # Desktop-специфичный UI
│   │   └── resources/        # Ресурсы (иконки, строки)
│   └── build.gradle.kts
├── cli/                       # CLI модуль
│   ├── src/
│   │   └── main/
│   │       └── kotlin/
│   │           └── commands/ # CLI команды
│   └── build.gradle.kts
└── build.gradle.kts          # Root build файл
```

## Паттерны проектирования

### 1. Repository Pattern
```kotlin
interface ConfigurationRepository {
    suspend fun loadMcpConfig(): Result<McpServersConfig>
    suspend fun saveMcpConfig(config: McpServersConfig): Result<Unit>
    suspend fun loadPreset(id: String): Result<Preset>
    suspend fun savePreset(preset: Preset): Result<Unit>
}
```

### 2. Factory Pattern
```kotlin
object McpClientFactory {
    fun create(config: TransportConfig): McpClient = when (config) {
        is StdioTransport -> StdioMcpClient(config)
        is HttpTransport -> HttpMcpClient(config)
        is WebSocketTransport -> WebSocketMcpClient(config)
    }
}
```

### 3. Observer Pattern
```kotlin
interface ConfigurationObserver {
    fun onConfigurationChanged(config: McpServersConfig)
    fun onPresetChanged(preset: Preset)
}
```

### 4. Strategy Pattern
Для различных стратегий фильтрации и маршрутизации инструментов.

### 5. Proxy Pattern
Основной паттерн приложения - проксирование MCP запросов.

## Работа с MCP

### Основные концепции
1. **Client**: Подключается к MCP серверу, запрашивает capabilities
2. **Server**: Предоставляет tools, resources, prompts
3. **Transport**: Способ коммуникации (STDIO, HTTP+SSE, WebSocket)
4. **Capabilities**: Возможности сервера (tools, resources, prompts)

### Best Practices для MCP

#### 1. Управление жизненным циклом
```kotlin
class McpServerConnection(
    private val config: McpServerConfig,
    private val client: McpClient
) : AutoCloseable {
    private var isConnected = false
    
    suspend fun connect(): Result<Unit> {
        // Подключение с retry логикой
    }
    
    override fun close() {
        // Graceful shutdown
    }
}
```

#### 2. Обработка ошибок
```kotlin
sealed class McpError : Exception() {
    data class ConnectionError(override val message: String) : McpError()
    data class TransportError(override val message: String) : McpError()
    data class ProtocolError(override val message: String) : McpError()
    data class TimeoutError(override val message: String) : McpError()
}
```

#### 3. Кэширование capabilities
```kotlin
class CapabilitiesCache {
    private val cache = mutableMapOf<String, ServerCapabilities>()
    private val timestamps = mutableMapOf<String, Long>()
    
    fun get(serverId: String): ServerCapabilities? {
        // Проверка TTL и возврат из кэша
    }
    
    fun put(serverId: String, capabilities: ServerCapabilities) {
        // Сохранение с timestamp
    }
}
```

## Тестирование

### 1. Unit тесты
- Тестирование каждого класса в изоляции
- Использование mock объектов для зависимостей
- Покрытие edge cases и error scenarios

```kotlin
class PresetEngineTest {
    @Test
    fun `should filter tools based on preset`() {
        // Given
        val preset = Preset(/* ... */)
        val tools = listOf(/* ... */)
        
        // When
        val filtered = PresetEngine.filter(tools, preset)
        
        // Then
        assertEquals(expected, filtered)
    }
}
```

### 2. Integration тесты
- Тестирование взаимодействия компонентов
- Использование test doubles для внешних сервисов
- Проверка полных workflows

### 3. UI тесты
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

## Обработка ошибок

### 1. Result type для операций
```kotlin
suspend fun loadConfig(): Result<Config> = runCatching {
    // Операция, которая может fail
}.onFailure { exception ->
    logger.error("Failed to load config", exception)
}
```

### 2. Graceful degradation
- При недоступности сервера - продолжать работу с остальными
- При ошибке загрузки пресета - использовать default
- При ошибке сохранения - уведомить пользователя, но не крашить приложение

### 3. Логирование
```kotlin
private val logger = LoggerFactory.getLogger(this::class.java)

fun processRequest(request: Request) {
    logger.debug("Processing request: ${request.id}")
    try {
        // обработка
        logger.info("Request processed successfully: ${request.id}")
    } catch (e: Exception) {
        logger.error("Failed to process request: ${request.id}", e)
        throw e
    }
}
```

## Конфигурация и безопасность

### 1. Переменные окружения
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

### 2. Валидация конфигурации
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

### 3. Безопасное логирование
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

## Производительность

### 1. Корутины и параллелизм
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

### 2. Lazy loading
```kotlin
class ToolRegistry {
    private val tools by lazy {
        loadToolsFromServers()
    }
    
    fun getTools(): List<Tool> = tools
}
```

### 3. Кэширование
- Кэширование capabilities серверов
- Кэширование результатов фильтрации
- Invalidation при изменении конфигурации

## Code Style и соглашения

### 1. Naming conventions
- Классы: PascalCase (`McpServerConfig`)
- Функции и переменные: camelCase (`loadConfig`)
- Константы: UPPER_SNAKE_CASE (`DEFAULT_TIMEOUT`)
- Пакеты: lowercase (`com.mcpproxy.core`)

### 2. Структура класса
```kotlin
class ExampleClass(
    private val dependency: Dependency  // Constructor parameters
) {
    companion object {
        // Constants
        private const val CONSTANT = "value"
    }
    
    // Properties
    private val property = "value"
    
    // Initialization
    init {
        // Init block
    }
    
    // Public functions
    fun publicFunction() {
        // Implementation
    }
    
    // Private functions
    private fun privateFunction() {
        // Implementation
    }
}
```

### 3. Документация
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
     * @throws McpConnectionException if connection fails after retries
     */
    suspend fun start(): Result<Unit> {
        // Implementation
    }
}
```

## Расширяемость

### 1. Plugin architecture для транспортов
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

### 2. Custom фильтры
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

## Checklist для code review

- [ ] Код следует архитектурным принципам (SOLID, Clean Architecture)
- [ ] Нет циклических зависимостей между модулями
- [ ] Все публичные API задокументированы
- [ ] Обработка ошибок использует Result type
- [ ] Асинхронный код использует корутины правильно
- [ ] Нет утечек памяти (закрытие ресурсов)
- [ ] Тесты покрывают основную функциональность
- [ ] Конфигурация валидируется при загрузке
- [ ] Чувствительные данные не логируются
- [ ] UI компоненты переиспользуемые и тестируемые

## Unit-тесты с Mockito

- Конструкторная инъекция зависимостей
  - Все зависимости, которые нужно подменять в тестах (клиенты, кэши, фабрики), передаются через конструктор.
  - Не используем глобальные singletons/hook-объекты и не модифицируем внутренние поля в тестах.

- Использование Mockito-Kotlin
  - Создание моков: `val dep: Dep = mock()`.
  - Стаббинг обычных функций: `whenever(dep.method(arg)).thenReturn(value)`.
  - Стаббинг suspend-функций выполняем внутри `runBlocking { ... }` или с использованием расширений Mockito-Kotlin: `whenever(dep.suspending()).thenReturn(value)` внутри `runBlocking`.
  - Последовательные ответы: `whenever(dep.call()).thenReturn(v1, v2, v3)`.
  - Верификация: `verify(dep).method(arg)`, счётчик: `verify(dep, times(2)).call()`.

- Структура тестов (AAA)
  - Arrange: подготовка данных и моков явно через конструкторы.
  - Act: один чёткий вызов тестируемого метода.
  - Assert: минимально необходимая верификация результата и взаимодействий.

- Работа с корутинами
  - Для тестов корутин используем `runBlocking` либо `kotlinx-coroutines-test` для виртуального времени.
  - Избегаем `Thread.sleep`; для таймингов берём `kotlinx-coroutines-test` или минимальные TTL и `delay`.

- Что не делаем
  - Не меняем внутренние поля объектов из тестов (никаких `set*ForTests`).
  - Не мокируем простые data-классы; используем реальные значения.
  - Не тестируем реализацию через глобальные состояния.

Пример

```kotlin
@Test
fun caches_and_falls_back_to_cached_caps() = runBlocking {
    val client: McpClient = mock()
    val caps1 = ServerCapabilities(tools = listOf(ToolDescriptor("t1")))
    whenever(client.connect()).thenReturn(Result.success(Unit))
    whenever(client.fetchCapabilities()).thenReturn(
        Result.success(caps1),
        Result.failure(IllegalStateException("boom"))
    )

    val config = McpServerConfig("s1", "Srv", TransportConfig.HttpTransport("http://"))
    val conn = DefaultMcpServerConnection(config, client = client)

    conn.connect()
    assertTrue(conn.getCapabilities().isSuccess)       // put to cache
    assertTrue(conn.getCapabilities(forceRefresh = true).isSuccess) // fallback to cache
}
```
