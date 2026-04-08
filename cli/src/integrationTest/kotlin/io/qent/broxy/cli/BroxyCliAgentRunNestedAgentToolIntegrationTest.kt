package io.qent.broxy.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.qent.broxy.agents.AgentFileSystemAccess
import io.qent.broxy.cli.support.AgentRunInputFiles
import io.qent.broxy.cli.support.BroxyCliIntegrationConfig
import io.qent.broxy.cli.support.buildAgentRunCommand
import io.qent.broxy.cli.support.runAgentCliCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.pathString
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val OUTER_AGENT_ID = "agent-a"
private const val INNER_AGENT_ID = "agent-b"
private const val AGENT_TOOL_SERVER_ID = "broxyagenttools"
private const val OUTER_AGENT_MARKER = "OUTER_AGENT_MARKER"
private const val INNER_AGENT_MARKER = "INNER_AGENT_MARKER"
private const val SCENARIO_NESTED_SUCCESS = "nested-agent-success"
private const val SCENARIO_NESTED_CYCLE = "nested-agent-cycle"
private const val INNER_SUCCESS_RESPONSE = "inner-agent-ok"
private const val OUTER_SUCCESS_RESPONSE = "outer-agent-ok"

internal class BroxyCliAgentRunNestedAgentToolIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun runAgent_nestedAgentTool_returnsChildStringResponse() {
        val tempDir = Files.createTempDirectory("broxy-agent-nested-success-it")
        val backend = NestedAgentToolTestBackend()
        try {
            val input =
                writeNestedAgentRunFiles(
                    root = tempDir,
                    llmBaseUrl =
                        backend.baseUrl(
                            scenario = SCENARIO_NESTED_SUCCESS,
                        ),
                    cycleMode = false,
                )
            val result = runAgentCliCommand(buildAgentRunCommand(input))
            val payload = json.parseToJsonElement(result.stdout).jsonObject

            assertEquals(0, result.exitCode, "CLI stderr:\n${result.stderr}")
            assertEquals("SUCCESS", payload.getValue("status").jsonPrimitive.content)
            assertEquals("LANGCHAIN", payload.getValue("runtime").jsonPrimitive.content)
            assertEquals(OUTER_SUCCESS_RESPONSE, payload.getValue("response").jsonPrimitive.content)
            val toolCalls = payload.getValue("toolCalls").jsonArray
            assertEquals(1, toolCalls.size)
            assertEquals(
                AGENT_TOOL_SERVER_ID,
                toolCalls
                    .first()
                    .jsonObject
                    .getValue("serverId")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "agent_$INNER_AGENT_ID",
                toolCalls
                    .first()
                    .jsonObject
                    .getValue("toolName")
                    .jsonPrimitive.content,
            )
        } finally {
            backend.close()
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun runAgent_nestedAgentToolCycle_returnsFailedStatus() {
        val tempDir = Files.createTempDirectory("broxy-agent-nested-cycle-it")
        val backend = NestedAgentToolTestBackend()
        try {
            val input =
                writeNestedAgentRunFiles(
                    root = tempDir,
                    llmBaseUrl =
                        backend.baseUrl(
                            scenario = SCENARIO_NESTED_CYCLE,
                        ),
                    cycleMode = true,
                )
            val result = runAgentCliCommand(buildAgentRunCommand(input))
            val payload = json.parseToJsonElement(result.stdout).jsonObject

            assertEquals(1, result.exitCode, "CLI stderr:\n${result.stderr}")
            assertEquals("FAILED", payload.getValue("status").jsonPrimitive.content)
            assertTrue(
                payload
                    .getValue("errorMessage")
                    .jsonPrimitive.content
                    .contains("cycle", ignoreCase = true),
                "Expected cycle-related failure, got: ${payload.getValue("errorMessage").jsonPrimitive.content}",
            )
            val toolCalls = payload.getValue("toolCalls").jsonArray
            assertTrue(toolCalls.isNotEmpty())
        } finally {
            backend.close()
            tempDir.toFile().deleteRecursively()
        }
    }
}

private fun writeNestedAgentRunFiles(
    root: Path,
    llmBaseUrl: String,
    cycleMode: Boolean,
): AgentRunInputFiles {
    val mcpFile = root.resolve("mcp_agent.json")
    val rootAgentFile = root.resolve("$OUTER_AGENT_ID.md")
    val childAgentFile = root.resolve("$INNER_AGENT_ID.md")
    val settingsFile = root.resolve("agents_settings.json")
    val secretsFile = root.resolve("agents_secrets.json")
    val stateDir = root.resolve("state")
    val workspace = root.resolve("workspace")
    val metadataDir = root.resolve("metadata")
    Files.createDirectories(stateDir)
    Files.createDirectories(workspace)
    Files.createDirectories(metadataDir)

    Files.writeString(
        mcpFile,
        """
        {
          "requestTimeoutSeconds": 60,
          "capabilitiesTimeoutSeconds": 30,
          "mcpServers": {}
        }
        """.trimIndent(),
    )

    Files.writeString(
        rootAgentFile,
        """
        ---
        name: Outer Agent
        description: Outer nested agent integration test.
        ---
        $OUTER_AGENT_MARKER
        """.trimIndent(),
    )

    Files.writeString(
        metadataDir.resolve("agent_$OUTER_AGENT_ID.json"),
        """
        {
          "agentTools": [
            {
              "agentId": "$INNER_AGENT_ID",
              "enabled": true
            }
          ],
          "manualLaunchDefaults": {
            "prompt": "outer default",
            "runtime": "LANGCHAIN",
            "llm": {
              "provider": "OPENAI",
              "model": "gpt-4o-mini",
              "temperature": 0.0
            },
            "fileSystem": {
              "path": "${workspace.pathString}",
              "access": "${AgentFileSystemAccess.NONE.name}"
            }
          }
        }
        """.trimIndent(),
    )

    val childAgentTools =
        if (cycleMode) {
            """
            [
              {
                "agentId": "$OUTER_AGENT_ID",
                "enabled": true
              }
            ]
            """.trimIndent()
        } else {
            "[]"
        }
    Files.writeString(
        childAgentFile,
        """
        ---
        name: Inner Agent
        description: Inner nested agent integration test.
        ---
        $INNER_AGENT_MARKER
        """.trimIndent(),
    )

    Files.writeString(
        metadataDir.resolve("agent_$INNER_AGENT_ID.json"),
        """
        {
          "agentTools": $childAgentTools,
          "manualLaunchDefaults": {
            "prompt": "inner default",
            "runtime": "LANGCHAIN",
            "llm": {
              "provider": "OPENAI",
              "model": "gpt-4o-mini",
              "temperature": 0.0
            },
            "fileSystem": {
              "path": "${workspace.pathString}",
              "access": "${AgentFileSystemAccess.NONE.name}"
            }
          }
        }
        """.trimIndent(),
    )

    Files.writeString(
        settingsFile,
        """
        {
          "openAi": {
            "baseUrl": "$llmBaseUrl"
          }
        }
        """.trimIndent(),
    )

    Files.writeString(
        secretsFile,
        """
        {
          "values": {
            "openai_api_key": "test-openai-key"
          }
        }
        """.trimIndent(),
    )

    return AgentRunInputFiles(
        mcpConfig = mcpFile,
        agentConfig = rootAgentFile,
        agentSettings = settingsFile,
        agentSecrets = secretsFile,
        stateDir = stateDir,
    )
}

private fun prefixedToolName(targetAgentId: String): String = "${AGENT_TOOL_SERVER_ID}_agent_$targetAgentId"

private class NestedAgentToolTestBackend(
    private val host: String = BroxyCliIntegrationConfig.TEST_SERVER_HTTP_HOST,
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val server = HttpServer.create(InetSocketAddress(host, 0), 0)

    init {
        server.createContext("/") { exchange ->
            handleRequest(exchange)
        }
        server.start()
    }

    fun baseUrl(scenario: String): String = "http://$host:${server.address.port}/v1/$scenario"

    override fun close() {
        server.stop(0)
    }

    private fun handleRequest(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            respond(exchange, 405, """{"error":"method_not_allowed"}""")
            return
        }
        val requestBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        val request = json.parseToJsonElement(requestBody).jsonObject
        val scenario = scenarioFromPath(exchange.requestURI)
        val outerTool = prefixedToolName(INNER_AGENT_ID)
        val innerTool = prefixedToolName(OUTER_AGENT_ID)
        val systemPrompt = extractSystemPrompt(request)
        val toolMessages = extractToolMessages(request)
        val model = request["model"]?.jsonPrimitive?.content ?: "test-model"

        when (scenario) {
            SCENARIO_NESTED_SUCCESS -> {
                when {
                    systemPrompt.contains(OUTER_AGENT_MARKER) && toolMessages.isEmpty() -> {
                        respond(exchange, 200, toolCall(model, outerTool, "call-outer-1").toString())
                    }
                    systemPrompt.contains(INNER_AGENT_MARKER) -> {
                        respond(exchange, 200, text(model, INNER_SUCCESS_RESPONSE).toString())
                    }
                    systemPrompt.contains(OUTER_AGENT_MARKER) &&
                        toolMessages.lastOrNull().orEmpty().contains(INNER_SUCCESS_RESPONSE) -> {
                        respond(exchange, 200, text(model, OUTER_SUCCESS_RESPONSE).toString())
                    }
                    else -> respond(exchange, 200, text(model, "unexpected-$scenario").toString())
                }
            }
            SCENARIO_NESTED_CYCLE -> {
                when {
                    systemPrompt.contains(OUTER_AGENT_MARKER) && toolMessages.isEmpty() -> {
                        respond(exchange, 200, toolCall(model, outerTool, "call-outer-cycle-1").toString())
                    }
                    systemPrompt.contains(INNER_AGENT_MARKER) && toolMessages.isEmpty() -> {
                        respond(exchange, 200, toolCall(model, innerTool, "call-inner-cycle-1").toString())
                    }
                    else -> respond(exchange, 500, """{"error":{"message":"Agent tool cycle detected"}}""")
                }
            }
            else -> respond(exchange, 200, text(model, "unexpected").toString())
        }
    }

    private fun extractSystemPrompt(request: JsonObject): String =
        request["messages"]
            ?.jsonArray
            ?.firstOrNull { message ->
                message.jsonObject["role"]?.jsonPrimitive?.content == "system"
            }?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.content
            .orEmpty()

    private fun extractToolMessages(request: JsonObject): List<String> =
        request["messages"]
            ?.jsonArray
            ?.mapNotNull { message ->
                if (message.jsonObject["role"]?.jsonPrimitive?.content != "tool") {
                    return@mapNotNull null
                }
                message.jsonObject["content"]?.jsonPrimitive?.content
            }.orEmpty()

    private fun scenarioFromPath(uri: URI): String =
        uri.path
            .split("/")
            .firstOrNull { it == SCENARIO_NESTED_SUCCESS || it == SCENARIO_NESTED_CYCLE }
            ?: SCENARIO_NESTED_SUCCESS

    private fun text(
        model: String,
        content: String,
    ): JsonObject =
        buildJsonObject {
            put("id", JsonPrimitive("chatcmpl-nested"))
            put("object", JsonPrimitive("chat.completion"))
            put("created", JsonPrimitive(Instant.now().epochSecond))
            put("model", JsonPrimitive(model))
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("index", JsonPrimitive(0))
                            put(
                                "message",
                                buildJsonObject {
                                    put("role", JsonPrimitive("assistant"))
                                    put("content", JsonPrimitive(content))
                                },
                            )
                            put("finish_reason", JsonPrimitive("stop"))
                        },
                    )
                },
            )
            put("usage", usage())
        }

    private fun toolCall(
        model: String,
        toolName: String,
        callId: String,
    ): JsonObject =
        buildJsonObject {
            put("id", JsonPrimitive("chatcmpl-nested-tool"))
            put("object", JsonPrimitive("chat.completion"))
            put("created", JsonPrimitive(Instant.now().epochSecond))
            put("model", JsonPrimitive(model))
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("index", JsonPrimitive(0))
                            put(
                                "message",
                                buildJsonObject {
                                    put("role", JsonPrimitive("assistant"))
                                    put("content", JsonPrimitive(""))
                                    put(
                                        "tool_calls",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("id", JsonPrimitive(callId))
                                                    put("type", JsonPrimitive("function"))
                                                    put(
                                                        "function",
                                                        buildJsonObject {
                                                            put("name", JsonPrimitive(toolName))
                                                            put("arguments", JsonPrimitive("""{"input":"nested-input"}"""))
                                                        },
                                                    )
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                            put("finish_reason", JsonPrimitive("tool_calls"))
                        },
                    )
                },
            )
            put("usage", usage())
        }

    private fun usage(): JsonObject =
        buildJsonObject {
            put("prompt_tokens", JsonPrimitive(0))
            put("completion_tokens", JsonPrimitive(0))
            put("total_tokens", JsonPrimitive(0))
        }

    private fun respond(
        exchange: HttpExchange,
        code: Int,
        payload: String,
    ) {
        val body = payload.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.use { output ->
            output.write(body)
        }
    }
}
