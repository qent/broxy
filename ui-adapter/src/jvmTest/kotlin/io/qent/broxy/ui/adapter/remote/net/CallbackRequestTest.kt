package io.qent.broxy.ui.adapter.remote.net

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import org.junit.Test

class CallbackRequestTest {

    @Test
    fun encodesRedirectUriForCallbackExchange() {
        val json = Json { encodeDefaults = true }
        val payload = json.parseToJsonElement(
            json.encodeToString(
                CallbackRequest(
                    code = "abc",
                    state = "state-1",
                    redirectUri = "http://127.0.0.1:8765/oauth/callback"
                )
            )
        ).jsonObject

        assertEquals("http://127.0.0.1:8765/oauth/callback", payload["redirect_uri"]?.jsonPrimitive?.content)
        assertEquals("mcp", payload["audience"]?.jsonPrimitive?.content)
    }
}
