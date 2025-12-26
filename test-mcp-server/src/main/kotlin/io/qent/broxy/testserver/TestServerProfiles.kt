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
    val promptNoArgsName: String,
    val promptNoArgsDescription: String,
    val promptNoArgsText: String,
    val resourceUri: String,
    val resourceName: String,
    val resourceDescription: String,
    val resourceText: String,
    val resourceTemplateUri: String,
    val resourceTemplateName: String,
    val resourceTemplateDescription: String,
    val resourceTemplateText: String,
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
            promptNoArgsName = "hello_stdio_plain",
            promptNoArgsDescription = "Says hello without arguments via STDIO",
            promptNoArgsText = "Hello stdio!",
            resourceUri = "test://resource/stdio",
            resourceName = "stdio",
            resourceDescription = "STDIO sample text",
            resourceText = "STDIO resource content",
            resourceTemplateUri = "test://resource/stdio/{id}",
            resourceTemplateName = "stdio-template",
            resourceTemplateDescription = "STDIO template text",
            resourceTemplateText = "STDIO template resource content",
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
            promptNoArgsName = "hello_http_plain",
            promptNoArgsDescription = "Says hello without arguments via Streamable HTTP",
            promptNoArgsText = "Hello http!",
            resourceUri = "test://resource/http",
            resourceName = "http",
            resourceDescription = "HTTP sample text",
            resourceText = "HTTP resource content",
            resourceTemplateUri = "test://resource/http/{id}",
            resourceTemplateName = "http-template",
            resourceTemplateDescription = "HTTP template text",
            resourceTemplateText = "HTTP template resource content",
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
            promptNoArgsName = "hello_sse_plain",
            promptNoArgsDescription = "Says hello without arguments via HTTP SSE",
            promptNoArgsText = "Hello sse!",
            resourceUri = "test://resource/sse",
            resourceName = "sse",
            resourceDescription = "SSE sample text",
            resourceText = "SSE resource content",
            resourceTemplateUri = "test://resource/sse/{id}",
            resourceTemplateName = "sse-template",
            resourceTemplateDescription = "SSE template text",
            resourceTemplateText = "SSE template resource content",
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
            promptNoArgsName = "hello_ws_plain",
            promptNoArgsDescription = "Says hello without arguments via WebSocket",
            promptNoArgsText = "Hello ws!",
            resourceUri = "test://resource/ws",
            resourceName = "ws",
            resourceDescription = "WebSocket sample text",
            resourceText = "WebSocket resource content",
            resourceTemplateUri = "test://resource/ws/{id}",
            resourceTemplateName = "ws-template",
            resourceTemplateDescription = "WebSocket template text",
            resourceTemplateText = "WebSocket template resource content",
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
