package io.qent.broxy.core.mcp.clients

import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapabilityFetcherTest {
    @Test
    fun fetch_returns_empty_lists_on_timeout() =
        runTest {
            val fetcher = CapabilityFetcher(NoopLogger)
            val client =
                object : SdkClientFacade {
                    override suspend fun getTools(): List<ToolDescriptor> = listOf(ToolDescriptor("t1", "tool"))

                    override suspend fun getResources(): List<ResourceDescriptor> {
                        delay(10)
                        return listOf(ResourceDescriptor("r1", "uri://r1", "res"))
                    }

                    override suspend fun getPrompts(): List<PromptDescriptor> {
                        delay(10)
                        return listOf(PromptDescriptor("p1", "prompt"))
                    }

                    override suspend fun callTool(
                        name: String,
                        arguments: kotlinx.serialization.json.JsonObject,
                    ) = null

                    override suspend fun getPrompt(
                        name: String,
                        arguments: Map<String, String>?,
                    ) = error("unused")

                    override suspend fun readResource(uri: String) = error("unused")

                    override suspend fun close() = Unit
                }

            val (tools, resources, prompts) = fetcher.fetch(client, timeoutMillis = 1)

            assertEquals(1, tools.size)
            assertTrue(resources.isEmpty())
            assertTrue(prompts.isEmpty())
        }

    @Test
    fun fetch_returns_default_on_exception() =
        runTest {
            val fetcher = CapabilityFetcher(NoopLogger)
            val client =
                object : SdkClientFacade {
                    override suspend fun getTools(): List<ToolDescriptor> = error("boom")

                    override suspend fun getResources(): List<ResourceDescriptor> = emptyList()

                    override suspend fun getPrompts(): List<PromptDescriptor> = emptyList()

                    override suspend fun callTool(
                        name: String,
                        arguments: kotlinx.serialization.json.JsonObject,
                    ) = null

                    override suspend fun getPrompt(
                        name: String,
                        arguments: Map<String, String>?,
                    ) = error("unused")

                    override suspend fun readResource(uri: String) = error("unused")

                    override suspend fun close() = Unit
                }

            val (tools, resources, prompts) = fetcher.fetch(client, timeoutMillis = 5_000)

            assertTrue(tools.isEmpty())
            assertTrue(resources.isEmpty())
            assertTrue(prompts.isEmpty())
        }

    private object NoopLogger : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(
            message: String,
            throwable: Throwable?,
        ) = Unit

        override fun error(
            message: String,
            throwable: Throwable?,
        ) = Unit
    }
}
