package io.qent.broxy.testserver

internal enum class ToolOperation(val label: String) {
    ADD("addition"),
    SUBTRACT("subtraction"),
    MULTIPLY("multiplication"),
    DIVIDE("division"),
    ;

    fun apply(
        a: Double,
        b: Double,
    ): Double =
        when (this) {
            ADD -> a + b
            SUBTRACT -> a - b
            MULTIPLY -> a * b
            DIVIDE -> a / b
        }
}

internal data class ToolTestArgs(
    val a: Double,
    val b: Double,
)

internal data class ModeProfile(
    val mode: ServerCliOptions.Mode,
    val displayName: String,
    val toolName: String,
    val toolDescription: String,
    val toolOperation: ToolOperation,
    val promptName: String,
    val promptDescription: String,
    val promptPrefix: String,
    val resourceUri: String,
    val resourceName: String,
    val resourceDescription: String,
    val resourceText: String,
)

internal object TestServerProfiles {
    const val PROMPT_ARGUMENT_NAME = "name"
    val TOOL_TEST_ARGS = ToolTestArgs(a = 8.0, b = 2.0)

    val STDIO =
        ModeProfile(
            mode = ServerCliOptions.Mode.STDIO,
            displayName = "stdio",
            toolName = "add_stdio",
            toolDescription = "Adds two numbers via STDIO",
            toolOperation = ToolOperation.ADD,
            promptName = "hello_stdio",
            promptDescription = "Says hello via STDIO",
            promptPrefix = "Hello stdio",
            resourceUri = "test://resource/stdio",
            resourceName = "stdio",
            resourceDescription = "STDIO sample text",
            resourceText = "STDIO resource content",
        )

    val STREAMABLE_HTTP =
        ModeProfile(
            mode = ServerCliOptions.Mode.HTTP_STREAMABLE,
            displayName = "http",
            toolName = "subtract_http",
            toolDescription = "Subtracts two numbers via Streamable HTTP",
            toolOperation = ToolOperation.SUBTRACT,
            promptName = "hello_http",
            promptDescription = "Says hello via Streamable HTTP",
            promptPrefix = "Hello http",
            resourceUri = "test://resource/http",
            resourceName = "http",
            resourceDescription = "HTTP sample text",
            resourceText = "HTTP resource content",
        )

    val HTTP_SSE =
        ModeProfile(
            mode = ServerCliOptions.Mode.HTTP_SSE,
            displayName = "sse",
            toolName = "multiply_sse",
            toolDescription = "Multiplies two numbers via HTTP SSE",
            toolOperation = ToolOperation.MULTIPLY,
            promptName = "hello_sse",
            promptDescription = "Says hello via HTTP SSE",
            promptPrefix = "Hello sse",
            resourceUri = "test://resource/sse",
            resourceName = "sse",
            resourceDescription = "SSE sample text",
            resourceText = "SSE resource content",
        )

    val WS =
        ModeProfile(
            mode = ServerCliOptions.Mode.WS,
            displayName = "ws",
            toolName = "divide_ws",
            toolDescription = "Divides two numbers via WebSocket",
            toolOperation = ToolOperation.DIVIDE,
            promptName = "hello_ws",
            promptDescription = "Says hello via WebSocket",
            promptPrefix = "Hello ws",
            resourceUri = "test://resource/ws",
            resourceName = "ws",
            resourceDescription = "WebSocket sample text",
            resourceText = "WebSocket resource content",
        )

    val allProfiles = listOf(STDIO, STREAMABLE_HTTP, HTTP_SSE, WS)

    fun forMode(mode: ServerCliOptions.Mode): ModeProfile =
        when (mode) {
            ServerCliOptions.Mode.STDIO -> STDIO
            ServerCliOptions.Mode.HTTP_STREAMABLE -> STREAMABLE_HTTP
            ServerCliOptions.Mode.HTTP_SSE -> HTTP_SSE
            ServerCliOptions.Mode.WS -> WS
        }
}
