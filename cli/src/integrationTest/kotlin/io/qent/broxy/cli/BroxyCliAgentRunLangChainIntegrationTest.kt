package io.qent.broxy.cli

import io.qent.broxy.agents.AgentFileSystemAccess
import io.qent.broxy.cli.support.AGENT_RUN_FILESYSTEM_SERVER_ID
import io.qent.broxy.cli.support.AGENT_RUN_FS_READ_TOOL_NAME
import io.qent.broxy.cli.support.AGENT_RUN_NO_CAPS_FS_NONE_DOWNSTREAM_BLOCKED_RESPONSE
import io.qent.broxy.cli.support.AGENT_RUN_NO_CAPS_FS_NONE_FS_BLOCKED_RESPONSE
import io.qent.broxy.cli.support.AGENT_RUN_NO_CAPS_FS_NONE_TEXT_RESPONSE
import io.qent.broxy.cli.support.AGENT_RUN_NO_CAPS_FS_READ_ONLY_FS_ALLOWED_RESPONSE
import io.qent.broxy.cli.support.AGENT_RUN_SCENARIO_NO_CAPS_FS_NONE_DOWNSTREAM_BLOCKED
import io.qent.broxy.cli.support.AGENT_RUN_SCENARIO_NO_CAPS_FS_NONE_FS_BLOCKED
import io.qent.broxy.cli.support.AGENT_RUN_SCENARIO_NO_CAPS_FS_NONE_TEXT
import io.qent.broxy.cli.support.AGENT_RUN_SCENARIO_NO_CAPS_FS_READ_ONLY_FS_ALLOWED
import io.qent.broxy.cli.support.AGENT_RUN_SCENARIO_WITH_CAPS_FS_NONE_DOWNSTREAM_ALLOWED
import io.qent.broxy.cli.support.AGENT_RUN_SCENARIO_WITH_CAPS_FS_READ_ONLY_MIXED_SEQUENCE
import io.qent.broxy.cli.support.AGENT_RUN_TEST_SERVER_ID
import io.qent.broxy.cli.support.AGENT_RUN_TEST_TOOL_NAME
import io.qent.broxy.cli.support.AGENT_RUN_UNKNOWN_SERVER_ID
import io.qent.broxy.cli.support.AGENT_RUN_WITH_CAPS_FS_NONE_DOWNSTREAM_ALLOWED_RESPONSE
import io.qent.broxy.cli.support.AGENT_RUN_WITH_CAPS_FS_READ_ONLY_MIXED_SEQUENCE_RESPONSE
import io.qent.broxy.cli.support.AgentCapabilityProfile
import io.qent.broxy.cli.support.AgentRunInputProfile
import io.qent.broxy.cli.support.BroxyCliIntegrationConfig
import io.qent.broxy.cli.support.BroxyCliIntegrationFiles
import io.qent.broxy.cli.support.BroxyCliProcesses
import io.qent.broxy.cli.support.OpenAiAgentTestBackend
import io.qent.broxy.cli.support.RunningProcess
import io.qent.broxy.cli.support.buildAgentRunCommand
import io.qent.broxy.cli.support.runAgentCliCommand
import io.qent.broxy.cli.support.writeAgentRunInputFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.test.assertEquals

internal class BroxyCliAgentRunLangChainIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @ParameterizedTest
    @MethodSource("scenarios")
    fun runAgent_oneShotLangChain_supportsScriptedLlmScenarios(scenario: ScenarioCase) {
        val tempDir = Files.createTempDirectory("broxy-agent-run-it")
        val llmBackend = OpenAiAgentTestBackend()
        val downstream = startHttpDownstreamServer()
        try {
            val llmBaseUrl = llmBackend.baseUrlForScenario(scenario.name)
            val input =
                writeAgentRunInputFiles(
                    root = tempDir,
                    downstreamUrl = downstream.url,
                    llmBaseUrl = llmBaseUrl,
                    profile = scenario.inputProfile,
                )

            val result = runAgentCliCommand(buildAgentRunCommand(input))

            assertEquals(0, result.exitCode, "CLI stderr:\n${result.stderr}")
            val payload = json.parseToJsonElement(result.stdout).jsonObject
            assertEquals("SUCCESS", payload.getValue("status").jsonPrimitive.content)
            assertEquals("LANGCHAIN", payload.getValue("runtime").jsonPrimitive.content)
            assertEquals(scenario.expectedResponse, payload.getValue("response").jsonPrimitive.content)
            assertEquals("null", payload.getValue("errorMessage").toString())
            assertEquals(scenario.expectedToolCalls, parseToolCalls(payload))
        } finally {
            downstream.process.close()
            llmBackend.close()
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun startHttpDownstreamServer(): DownstreamServerHandle {
        val port = nextFreePort()
        val path = BroxyCliIntegrationConfig.TEST_SERVER_HTTP_PATH
        val url = "http://${BroxyCliIntegrationConfig.TEST_SERVER_HTTP_HOST}:$port$path"
        val command =
            buildList {
                add(BroxyCliIntegrationFiles.resolveTestServerCommand())
                add("--mode")
                add("http")
                add("--host")
                add(BroxyCliIntegrationConfig.TEST_SERVER_HTTP_HOST)
                add("--port")
                add(port.toString())
                add("--path")
                add(path)
            }
        val process = BroxyCliProcesses.startTestServerProcess(command)
        waitForHttpServer(BroxyCliIntegrationConfig.TEST_SERVER_HTTP_HOST, port, process)
        return DownstreamServerHandle(url = url, process = process)
    }

    private fun waitForHttpServer(
        host: String,
        port: Int,
        process: RunningProcess,
    ) {
        repeat(BroxyCliIntegrationConfig.HTTP_SERVER_ATTEMPTS) {
            if (isPortOpen(host, port)) {
                return
            }
            Thread.sleep(BroxyCliIntegrationConfig.HTTP_SERVER_DELAY_MILLIS)
        }
        error("Downstream test MCP server did not start on $host:$port. Logs:\n${process.logs()}")
    }

    private fun isPortOpen(
        host: String,
        port: Int,
    ): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 200)
            }
        }.isSuccess

    private fun nextFreePort(): Int = ServerSocket(0).use { it.localPort }

    private data class DownstreamServerHandle(
        val url: String,
        val process: RunningProcess,
    )

    data class ExpectedToolCall(
        val serverId: String,
        val toolName: String,
        val step: Int,
    )

    data class ScenarioCase(
        val name: String,
        val inputProfile: AgentRunInputProfile,
        val expectedResponse: String,
        val expectedToolCalls: List<ExpectedToolCall>,
    )

    private fun parseToolCalls(payload: JsonObject): List<ExpectedToolCall> =
        payload
            .getValue("toolCalls")
            .jsonArray
            .map { toolCall ->
                val entry = toolCall.jsonObject
                ExpectedToolCall(
                    serverId = entry.getValue("serverId").jsonPrimitive.content,
                    toolName = entry.getValue("toolName").jsonPrimitive.content,
                    step = entry.getValue("step").jsonPrimitive.int,
                )
            }

    private companion object {
        @JvmStatic
        fun scenarios(): List<ScenarioCase> =
            listOf(
                ScenarioCase(
                    name = AGENT_RUN_SCENARIO_NO_CAPS_FS_NONE_TEXT,
                    inputProfile =
                        AgentRunInputProfile(
                            capabilityProfile = AgentCapabilityProfile.WITHOUT_CAPABILITIES,
                            fileSystemAccess = AgentFileSystemAccess.NONE,
                        ),
                    expectedResponse = AGENT_RUN_NO_CAPS_FS_NONE_TEXT_RESPONSE,
                    expectedToolCalls = emptyList(),
                ),
                ScenarioCase(
                    name = AGENT_RUN_SCENARIO_WITH_CAPS_FS_NONE_DOWNSTREAM_ALLOWED,
                    inputProfile =
                        AgentRunInputProfile(
                            capabilityProfile = AgentCapabilityProfile.WITH_DOWNSTREAM_TOOL,
                            fileSystemAccess = AgentFileSystemAccess.NONE,
                        ),
                    expectedResponse = AGENT_RUN_WITH_CAPS_FS_NONE_DOWNSTREAM_ALLOWED_RESPONSE,
                    expectedToolCalls =
                        listOf(
                            ExpectedToolCall(
                                serverId = AGENT_RUN_TEST_SERVER_ID,
                                toolName = AGENT_RUN_TEST_TOOL_NAME,
                                step = 1,
                            ),
                        ),
                ),
                ScenarioCase(
                    name = AGENT_RUN_SCENARIO_NO_CAPS_FS_NONE_DOWNSTREAM_BLOCKED,
                    inputProfile =
                        AgentRunInputProfile(
                            capabilityProfile = AgentCapabilityProfile.WITHOUT_CAPABILITIES,
                            fileSystemAccess = AgentFileSystemAccess.NONE,
                        ),
                    expectedResponse = AGENT_RUN_NO_CAPS_FS_NONE_DOWNSTREAM_BLOCKED_RESPONSE,
                    expectedToolCalls =
                        listOf(
                            ExpectedToolCall(
                                serverId = AGENT_RUN_TEST_SERVER_ID,
                                toolName = AGENT_RUN_TEST_TOOL_NAME,
                                step = 1,
                            ),
                        ),
                ),
                ScenarioCase(
                    name = AGENT_RUN_SCENARIO_NO_CAPS_FS_NONE_FS_BLOCKED,
                    inputProfile =
                        AgentRunInputProfile(
                            capabilityProfile = AgentCapabilityProfile.WITHOUT_CAPABILITIES,
                            fileSystemAccess = AgentFileSystemAccess.NONE,
                        ),
                    expectedResponse = AGENT_RUN_NO_CAPS_FS_NONE_FS_BLOCKED_RESPONSE,
                    expectedToolCalls =
                        listOf(
                            ExpectedToolCall(
                                serverId = AGENT_RUN_UNKNOWN_SERVER_ID,
                                toolName = AGENT_RUN_FS_READ_TOOL_NAME,
                                step = 1,
                            ),
                        ),
                ),
                ScenarioCase(
                    name = AGENT_RUN_SCENARIO_NO_CAPS_FS_READ_ONLY_FS_ALLOWED,
                    inputProfile =
                        AgentRunInputProfile(
                            capabilityProfile = AgentCapabilityProfile.WITHOUT_CAPABILITIES,
                            fileSystemAccess = AgentFileSystemAccess.READ_ONLY,
                        ),
                    expectedResponse = AGENT_RUN_NO_CAPS_FS_READ_ONLY_FS_ALLOWED_RESPONSE,
                    expectedToolCalls =
                        listOf(
                            ExpectedToolCall(
                                serverId = AGENT_RUN_FILESYSTEM_SERVER_ID,
                                toolName = AGENT_RUN_FS_READ_TOOL_NAME,
                                step = 1,
                            ),
                        ),
                ),
                ScenarioCase(
                    name = AGENT_RUN_SCENARIO_WITH_CAPS_FS_READ_ONLY_MIXED_SEQUENCE,
                    inputProfile =
                        AgentRunInputProfile(
                            capabilityProfile = AgentCapabilityProfile.WITH_DOWNSTREAM_TOOL,
                            fileSystemAccess = AgentFileSystemAccess.READ_ONLY,
                        ),
                    expectedResponse = AGENT_RUN_WITH_CAPS_FS_READ_ONLY_MIXED_SEQUENCE_RESPONSE,
                    expectedToolCalls =
                        listOf(
                            ExpectedToolCall(
                                serverId = AGENT_RUN_TEST_SERVER_ID,
                                toolName = AGENT_RUN_TEST_TOOL_NAME,
                                step = 1,
                            ),
                            ExpectedToolCall(
                                serverId = AGENT_RUN_FILESYSTEM_SERVER_ID,
                                toolName = AGENT_RUN_FS_READ_TOOL_NAME,
                                step = 2,
                            ),
                        ),
                ),
            )
    }
}
