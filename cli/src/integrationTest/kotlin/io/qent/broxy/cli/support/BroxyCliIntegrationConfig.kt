package io.qent.broxy.cli.support

import io.qent.broxy.core.utils.FilteredLogger
import io.qent.broxy.core.utils.LogLevel

internal object BroxyCliIntegrationConfig {
    const val PRESET_ID = "test"
    const val TEST_TIMEOUT_MILLIS = 5_000L
    const val TEST_SERVER_HOME_PROPERTY = "broxy.testMcpServerHome"
    const val TEST_SERVER_COMMAND_PLACEHOLDER = "__TEST_MCP_SERVER_COMMAND__"
    const val TEST_SERVER_HTTP_URL_PLACEHOLDER = "__TEST_MCP_SERVER_HTTP_URL__"
    const val TEST_SERVER_SSE_URL_PLACEHOLDER = "__TEST_MCP_SERVER_SSE_URL__"
    const val TEST_SERVER_WS_URL_PLACEHOLDER = "__TEST_MCP_SERVER_WS_URL__"
    const val STDIO_SERVER_ID = "test-stdio"
    const val HTTP_SERVER_ID = "test-http"
    const val SSE_SERVER_ID = "test-sse"
    const val WS_SERVER_ID = "test-ws"
    const val TEST_SERVER_HTTP_HOST = "127.0.0.1"
    const val TEST_SERVER_HTTP_PATH = "/mcp"
    const val ADD_TOOL_NAME = "add_stdio"
    const val SUBTRACT_TOOL_NAME = "subtract_http"
    const val MULTIPLY_TOOL_NAME = "multiply_sse"
    const val DIVIDE_TOOL_NAME = "divide_ws"
    const val HELLO_STDIO_PROMPT = "hello_stdio"
    const val HELLO_HTTP_PROMPT = "hello_http"
    const val HELLO_SSE_PROMPT = "hello_sse"
    const val HELLO_WS_PROMPT = "hello_ws"
    const val RESOURCE_STDIO = "test://resource/stdio"
    const val RESOURCE_HTTP = "test://resource/http"
    const val RESOURCE_SSE = "test://resource/sse"
    const val RESOURCE_WS = "test://resource/ws"
    const val TOOL_INPUT_A = 8
    const val TOOL_INPUT_B = 2
    const val PROMPT_ARGUMENT_PLACEHOLDER = "integration-test"
    const val CLI_LOG_LEVEL = "info"
    const val CAPABILITIES_DELAY_MILLIS = 150L
    const val CAPABILITIES_REQUEST_TIMEOUT_MILLIS = 500L
    const val CAPABILITIES_TIMEOUT_MILLIS = 4_000L
    const val CAPABILITIES_WARMUP_TIMEOUT_MILLIS = 10_000L

    // Allow extra startup time: CLI performs upstream capability sync before exposing inbound HTTP.
    const val CONNECT_ATTEMPTS = 120
    const val CONNECT_DELAY_MILLIS = 250L
    const val HTTP_SERVER_ATTEMPTS = 50
    const val HTTP_SERVER_DELAY_MILLIS = 100L
    const val HTTP_INBOUND_PATH = "/mcp"
    const val HTTP_INBOUND_ATTEMPTS = 3

    data class ToolExpectation(
        val operation: String,
        val expectedResult: Double,
    )

    val TOOL_EXPECTATIONS =
        mapOf(
            "$STDIO_SERVER_ID:$ADD_TOOL_NAME" to ToolExpectation(operation = "addition", expectedResult = 10.0),
            "$HTTP_SERVER_ID:$SUBTRACT_TOOL_NAME" to ToolExpectation(operation = "subtraction", expectedResult = 6.0),
            "$SSE_SERVER_ID:$MULTIPLY_TOOL_NAME" to ToolExpectation(operation = "multiplication", expectedResult = 16.0),
            "$WS_SERVER_ID:$DIVIDE_TOOL_NAME" to ToolExpectation(operation = "division", expectedResult = 4.0),
        )
    val PROMPT_EXPECTATIONS =
        mapOf(
            HELLO_STDIO_PROMPT to "Hello stdio $PROMPT_ARGUMENT_PLACEHOLDER!",
            HELLO_HTTP_PROMPT to "Hello http $PROMPT_ARGUMENT_PLACEHOLDER!",
            HELLO_SSE_PROMPT to "Hello sse $PROMPT_ARGUMENT_PLACEHOLDER!",
            HELLO_WS_PROMPT to "Hello ws $PROMPT_ARGUMENT_PLACEHOLDER!",
        )
    val RESOURCE_EXPECTATIONS =
        mapOf(
            RESOURCE_STDIO to "STDIO resource content",
            RESOURCE_HTTP to "HTTP resource content",
            RESOURCE_SSE to "SSE resource content",
            RESOURCE_WS to "WebSocket resource content",
        )
    val EXPECTED_TOOLS = TOOL_EXPECTATIONS.keys
    val EXPECTED_PROMPTS = PROMPT_EXPECTATIONS.keys
    val EXPECTED_RESOURCES = RESOURCE_EXPECTATIONS.keys
    val TEST_LOGGER = FilteredLogger(LogLevel.WARN)

    fun log(message: String) {
        println("[BroxyIT] $message")
    }
}
