# AGENTS.md - Руководство по разработке broxy

## Архитектурные принципы

### 1. Clean Architecture
Проект следует принципам чистой архитектуры с четким разделением на слои:

- **Domain Layer (core)**: Бизнес-логика, не зависящая от фреймворков и платформ
- **Data Layer (core)**: Репозитории, работа с данными, MCP клиенты/серверы
- **Presentation Layer (ui/ui-adapter/cli)**: Compose UI, адаптер презентации (формирование состояния), CLI команды

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
broxy/
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
├── ui-adapter/                # Адаптер презентации (UDF/MVI, Flow)
│   ├── src/
│   │   ├── commonMain/       # Общие view-models, интерфейсы, алиасы core типов
│   │   └── jvmMain/          # JVM-реализации (ProxyController, ToolService)
│   └── build.gradle.kts
├── ui/                        # Compose Multiplatform UI (тонкий слой)
│   ├── src/
│   │   ├── commonMain/       # Общий UI код (чистые Composable)
│   │   │   ├── screens/      # Экраны приложения
│   │   │   ├── components/   # Переиспользуемые компоненты
│   │   │   ├── theme/        # Material 3 тема
│   │   │   └── viewmodels/   # Только AppState и UI-модели
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

## Тонкий UI на UDF + Compose

- Единое направление данных (UDF): события от UI → интенты → адаптер (ui-adapter) → обновление `StateFlow` состояния → UI отображает через `collectAsState()`.
- MVI: явно моделируем три сущности
  - State: неизменяемые data-классы UI-состояния (без ссылок на core), публикуются как `StateFlow`.
  - Intent: действия пользователя/системы; UI вызывает методы/диспетчер адаптера, не мутирует состояние напрямую.
  - Effect: одноразовые события (tost/snackbar/навигация) — как `SharedFlow`/каналы.
- UI-модуль
  - Содержит только Composable-функции, тему, простые UI-модели и `AppState` для локальных визуальных настроек.
  - Не импортирует `core`. Любые доменные типы приходят из `ui-adapter` (через алиасы/модели).
  - Не содержит побочных эффектов работы с сетью/файлами — только подписки на `Flow` и вызовы коллбеков.
  - Никаких `GlobalScope`; эффекты — в `LaunchedEffect`/`rememberCoroutineScope` только для вызова адаптера.
- ui-adapter
  - Зависит от `core`. Инкапсулирует всю логику презентации, IO, orchestration, кеши и жизненный цикл.
  - Экспортирует: view-models/сторы на `StateFlow`, сервисы (ProxyController, ToolService), провайдеры репозиториев.
  - Предоставляет UI-модели без утечек domain-типов; допустимы `typealias` для прозрачной инкапсуляции.
  - Не зависит от Compose: никаких `MutableState`, `SnapshotStateList` и пр. Только Kotlin/Coroutines.
- Коллекция состояния в UI
  - Всегда `collectAsState()` из `StateFlow`.
  - Никаких `.value =` в UI — изменения только через интенты/методы адаптера.
- Обработка ошибок
  - Все публичные методы адаптера возвращают `Result<T>` и логируют.
  - UI отображает ошибки (snackbar/диалоги), не решает доменную логику.
- Тестирование
  - ViewModel/Store тестируется на уровне `ui-adapter` с подменой зависимостей (Mockito).
  - UI тестируется как чистый рендер по состоянию (скриншоты/Compose tests), без реального IO.


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

#### 4. Управление таймаутами и повторными попытками
- `core/src/commonMain/kotlin/io/qent/broxy/core/mcp/DefaultMcpServerConnection.kt` реализует экспоненциальный backoff, ограничение числа попыток и обёртку `withTimeout`. Методы `updateCallTimeout` и `updateCapabilitiesTimeout` позволяют синхронизировать таймауты RPC-вызовов и загрузки capabilities с пользовательскими настройками.
- При сбое получения capabilities соединение возвращается к кэшу и логирует ошибку через `Logger`, не прерывая работу прокси (`Result`-контракты на всех публичных методах).

#### 5. Пакетная работа с несколькими серверами
- `MultiServerClient` (`core/src/commonMain/kotlin/io/qent/broxy/core/mcp/MultiServerClient.kt`) инкапсулирует параллельные запросы к downstream-серверам, поддерживает преобразование префиксованных имён инструментов и используется как в фильтрации (`ProxyMcpServer`), так и в `ToolRegistry`.
- `DefaultRequestDispatcher` (`core/src/commonMain/kotlin/io/qent/broxy/core/proxy/RequestDispatcher.kt`) обеспечивает маршрутизацию batch-вызовов, prompt/resource запросов и строгую проверку разрешённых инструментов.

## Конфигурация и наблюдение

- `JsonConfigurationRepository` (`core/src/jvmMain/kotlin/io/qent/broxy/core/config/JsonConfigurationRepository.kt`) читает/пишет `mcp.json` и `preset_*.json`, валидирует транспорты, разрешает `${ENV}` и `{ENV}` плейсхолдеры через `EnvironmentVariableResolver`, проверяет наличие обязательных переменных окружения и логирует безопасные копии конфигураций. Поддерживаются глобальные ключи `requestTimeoutSeconds` и `capabilitiesTimeoutSeconds` для управления таймаутами вызовов и загрузки capabilities.
- JSON-схемы в `core/src/commonMain/resources/schemas/` фиксируют контракт конфигурации и пресетов; при изменениях структуры обновляйте схемы и документацию.
- `ConfigurationWatcher` (`core/src/jvmMain/kotlin/io/qent/broxy/core/config/ConfigurationWatcher.kt`) отслеживает изменения файлов, применяет debounce, отправляет события наблюдателям (`ConfigurationObserver`). CLI и UI адаптер используют его для хот-релоада конфигурации без перезапуска процесса.
- Для тестов и headless-режима предусмотрены ручные триггеры `triggerConfigReload/triggerPresetReload`, что упрощает имитацию событий файловой системы.

## Логирование и телеметрия

- `Logger` и `CollectingLogger` (`core/src/commonMain/kotlin/io/qent/broxy/core/utils/Logger.kt`, `CollectingLogger.kt`) формируют единый интерфейс логов. UI подписывается на `CollectingLogger.events`, отображая сообщения и ограничивая буфер (`AppStore` хранит максимум 500 записей).
- JSON-формат логов (`core/src/commonMain/kotlin/io/qent/broxy/core/utils/JsonLogging.kt`) стандартизирует события: `infoJson`, `warnJson`, `errorJson` добавляют timestamp, event name и payload. Это используется в `RequestDispatcher`, `ProxyMcpServer`, `SdkServerFactory` для трассировки цепочки LLM → прокси → downstream.
- Для CLI поддерживается установка минимального уровня логирования через `FilteredLogger` (`core/src/commonMain/kotlin/io/qent/broxy/core/utils/Logger.kt`), чтобы не засорять STDOUT.

## Маршрутизация и пространства имён

- `DefaultNamespaceManager` (`core/src/commonMain/kotlin/io/qent/broxy/core/proxy/NamespaceManager.kt`) отвечает за префиксы `serverId:tool`. Входящие вызовы без префикса считаются некорректными.
- `DefaultToolFilter`/`DefaultPresetEngine` (`core/src/commonMain/kotlin/io/qent/broxy/core/proxy/ToolFilter.kt`, `PresetEngine.kt`) применяют пресеты: формируют список доступных инструментов, маршрутизируют prompts/resources, логируют отсутствующие инструменты.
- `ToolRegistry` (`core/src/commonMain/kotlin/io/qent/broxy/core/proxy/ToolRegistry.kt`) строит индекс инструментов с TTL и предоставляет поиск. Используйте его для UI-автодополнений и инспекции пресетов.

## Платформенные адаптеры

- MCP клиенты абстрагированы через `McpClientProvider` (`core/src/commonMain/kotlin/io/qent/broxy/core/mcp/McpClientProvider.kt`) и `defaultMcpClientProvider()` с реализацией для JVM (`core/src/jvmMain/kotlin/io/qent/broxy/core/mcp/McpClientFactoryJvm.kt`). `StdioMcpClient` и `KtorMcpClient` покрывают STDIO/SSE/StreamableHttp/WebSocket транспорты.
- Входящий трафик обслуживается `InboundServerFactory` и его реализациями (`core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/InboundServers.kt`): STDIO использует SDK transport, HTTP(SSE) — встраиваемый Ktor Netty. Другие транспорты поддерживаются только на downstream стороне.
- `buildSdkServer` (`core/src/jvmMain/kotlin/io/qent/broxy/core/proxy/inbound/SdkServerFactory.kt`) проксирует прокси в MCP SDK, декодирует ответы, применяет fallback-логику и логирует события. Unit-тест `core/src/jvmTest/kotlin/io/qent/broxy/core/proxy/inbound/SdkServerFactoryTest.kt` фиксирует ожидаемое поведение.

## UI-адаптер и AppStore

- `AppStore` (`ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/store/AppStore.kt`) реализует UDF/MVI на корутинах: хранит конфигурацию, управляет `StateFlow<UIState>`, кэширует capabilities (TTL 5 минут), ограничивает количество логов и проксирует интенты (refresh, CRUD серверов/пресетов, запуск/остановка прокси, управление таймаутом и треем).
- `ProxyController` (`ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/proxy/ProxyController.kt`) — `expect`-интерфейс; JVM-реализация (`ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/proxy/ProxyControllerJvm.kt`) создаёт downstream соединения, inbound сервер и управляет таймаутами.
- `fetchServerCapabilities` (`ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/services/ToolService.kt`) используется UI для валидации серверов; JVM-версия (`ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/services/ToolServiceJvm.kt`) уважает пользовательский таймаут capabilities и выполняет одну попытку.
- UI (`ui/src/commonMain/...`) остаётся декларативным слоем: `MainWindow`/`ServersScreen` подписываются на `UIState`, диалоги вызывают интенты из `Intents`.

## CLI режим

- Основная команда `broxy proxy` (`cli/src/main/kotlin/io/qent/broxy/cli/commands/ProxyCommand.kt`) поднимает прокси с указанным inbound, применяет пресет и запускает `ConfigurationWatcher` для хот-релоада.
- CLI использует те же `DefaultMcpServerConnection`, `ProxyMcpServer`, `InboundServerFactory`; при изменении конфигурации пересоздаёт downstream и inbound, выполняя graceful shutdown предыдущих соединений.
- Флаги `--inbound`, `--url`, `--log-level`, `--config-dir`, `--preset-id` должны поддерживаться и в документе (с учётом дефолтов STDIO/портов).

## Сборка и зависимости

- Модули подключены через Gradle KMP: `core`, `ui-adapter`, `ui`, `cli` (см. `settings.gradle.kts`). `core` и `ui-adapter` — multiplatform, `ui` — Compose Multiplatform Desktop, `cli` — JVM.
- Ключевые зависимости: MCP Kotlin SDK (`io.modelcontextprotocol:kotlin-sdk`), Ktor server/client, Compose Multiplatform `material3`, Clikt CLI. Версии централизованы в `gradle.properties`.
- `cli` собирается через ShadowJar и публикует `broxy-cli` fat-jar; при обновлении зависимостей и плагинов отражайте это здесь.
- `./gradlew clean build` запускает ShadowJar и модульные тесты; не забывайте, что UI зависит от `ui-adapter`, поэтому изменения в адаптере автоматически тянут пересборку UI.

## Тестирование

- Текущие тестовые наборы размещены в `core/src/jvmTest` и `ui-adapter/src/jvmTest`; пример — `core/src/jvmTest/kotlin/io/qent/broxy/core/proxy/inbound/SdkServerFactoryTest.kt`.
- Для корутин используем `kotlinx-coroutines-test` (уже подключён в Gradle), избегаем `Thread.sleep`, переключаем диспетчеры через `runTest`.
- UI-адаптер следует покрывать тестами `AppStore` и `ProxyController` с моками `ConfigurationRepository` и `ProxyController`, используя Mockito-Kotlin, как прописано ниже.

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
- Пакеты: lowercase (`io.qent.broxy.core`)

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
- [ ] Выполнена сборка проекта (`./gradlew build`) без ошибок
- [ ] Пройдены все имеющиеся unit-тесты (см. раздел ниже)

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

## Обязательная проверка билда и тестов

- В конце КАЖДОЙ задачи обязательно:
  - Выполнить полную сборку Gradle.
  - Запустить все доступные unit‑тесты во всех модулях.
- Задача считается выполненной ТОЛЬКО при условии успешной сборки и прохождения всех unit‑тестов.

Рекомендуемые команды (используйте то, что применимо к изменённым модулям):

```bash
# Полная сборка (включает тесты через задачу check)
./gradlew clean build

# Агрегированный запуск модульных тестов (core JVM, CLI)
./gradlew testAll

# Альтернатива: ручной запуск известных задач по модулям
./gradlew :core:jvmTest :cli:test

# Таргетно: юнит‑тесты core для JVM
./gradlew :core:jvmTest

# Диагностика при падениях (опционально)
./gradlew :core:jvmTest --no-daemon --stacktrace
```
