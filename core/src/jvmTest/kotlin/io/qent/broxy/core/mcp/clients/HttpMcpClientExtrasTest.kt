package io.qent.broxy.core.mcp.clients

import io.modelcontextprotocol.kotlin.sdk.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.ReadResourceResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertTrue

class KtorMcpClientExtrasTest {
    @Test
    fun getPrompt_and_readResource_with_mockito() {
        runBlocking {
            val facade: SdkClientFacade = mock()
            whenever(facade.getPrompt("p1", null)).thenReturn(GetPromptResult(description = "d", messages = emptyList(), meta = JsonObject(emptyMap())))
            whenever(facade.readResource("u1")).thenReturn(ReadResourceResult(contents = emptyList(), meta = JsonObject(emptyMap())))

            val client = KtorMcpClient(
                mode = KtorMcpClient.Mode.StreamableHttp,
                url = "http://localhost",
                headersMap = emptyMap(),
                connector = SdkConnector { facade }
            )

            val conn = client.connect()
            assertTrue(conn.isSuccess)

            val pr = client.getPrompt("p1")
            assertTrue(pr.isSuccess)
            verify(facade).getPrompt("p1", null)

            val rr = client.readResource("u1")
            assertTrue(rr.isSuccess)
            verify(facade).readResource("u1")
        }
    }
}
