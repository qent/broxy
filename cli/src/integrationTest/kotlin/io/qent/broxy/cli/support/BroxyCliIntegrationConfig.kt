package io.qent.broxy.cli.support

import io.qent.broxy.core.utils.FilteredLogger
import io.qent.broxy.core.utils.LogLevel

internal object BroxyCliIntegrationConfig {
    const val PRESET_ID = "test"
    const val TEST_TIMEOUT_MILLIS = 10_000L
    const val TEST_SERVER_HOME_PROPERTY = "broxy.testMcpServerHome"
    const val TEST_SERVER_COMMAND_PLACEHOLDER = "__TEST_MCP_SERVER_COMMAND__"
    const val TEST_SERVER_HTTP_URL_PLACEHOLDER = "__TEST_MCP_SERVER_HTTP_URL__"
    const val STDIO_SERVER_ID = "test-arithmetic"
    const val HTTP_SERVER_ID = "test-arithmetic-http"
    const val TEST_SERVER_HTTP_HOST = "127.0.0.1"
    const val TEST_SERVER_HTTP_PATH = "/mcp"
    const val ADD_TOOL_NAME = "add"
    const val SUBTRACT_TOOL_NAME = "subtract"
    const val HELLO_PROMPT = "hello"
    const val BYE_PROMPT = "bye"
    const val RESOURCE_ALPHA = "test://resource/alpha"
    const val RESOURCE_BETA = "test://resource/beta"
    const val PROMPT_ARGUMENT_PLACEHOLDER = "integration-test"
    const val CLI_LOG_LEVEL = "info"
    const val CAPABILITIES_DELAY_MILLIS = 150L
    const val CAPABILITIES_REQUEST_TIMEOUT_MILLIS = 1000L
    const val CAPABILITIES_TIMEOUT_MILLIS = 9_000L
    const val CAPABILITIES_WARMUP_TIMEOUT_MILLIS = 60_000L
    const val CONNECT_ATTEMPTS = 60
    const val CONNECT_DELAY_MILLIS = 150L
    const val HTTP_SERVER_ATTEMPTS = 50
    const val HTTP_SERVER_DELAY_MILLIS = 100L
    const val HTTP_INBOUND_PATH = "/mcp"

    val EXPECTED_TOOLS = setOf(
        "$STDIO_SERVER_ID:$ADD_TOOL_NAME",
        "$HTTP_SERVER_ID:$SUBTRACT_TOOL_NAME"
    )
    val EXPECTED_PROMPTS = setOf(HELLO_PROMPT, BYE_PROMPT)
    val EXPECTED_RESOURCES = setOf(RESOURCE_ALPHA, RESOURCE_BETA)
    val TEST_LOGGER = FilteredLogger(LogLevel.WARN)

    fun log(message: String) {
        println("[BroxyIT] $message")
    }
}
