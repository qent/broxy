package io.qent.broxy.core.mcp.clients

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities as SdkServerCapabilities

class RealSdkClientFacadeTest {
    @Test
    fun skips_list_prompts_when_server_reports_no_prompts() =
        runBlocking {
            val client = Client(Implementation(name = "test", version = "0"))
            setServerCapabilities(client, SdkServerCapabilities())
            val facade = RealSdkClientFacade(client)

            val prompts = facade.getPrompts()

            assertTrue(prompts.isEmpty())
        }

    private fun setServerCapabilities(
        client: Client,
        capabilities: SdkServerCapabilities,
    ) {
        val field = Client::class.java.getDeclaredField("serverCapabilities")
        field.isAccessible = true
        field.set(client, capabilities)
    }
}
