package io.qent.broxy.ui.screens

import io.qent.broxy.ui.strings.EnglishStrings
import kotlin.test.Test
import kotlin.test.assertEquals

class ClientsScreenLogicTest {
    @Test
    fun `http and sse copy payloads use current inbound port`() {
        assertEquals(
            "http://localhost:4444/mcp",
            httpConnectionInfoCopyPayload(inboundPort = 4444),
        )
        assertEquals(
            "http://localhost:4444/sse",
            sseConnectionInfoCopyPayload(inboundPort = 4444),
        )
    }

    @Test
    fun `stdio copy payloads are exact two-line command parts`() {
        val payloads = stdioConnectionInfoCopyPayloads()

        assertEquals(
            listOf(
                "/Applications/broxy.app/Contents/MacOS/broxy",
                "--stdio-proxy",
            ),
            payloads,
        )
    }

    @Test
    fun `connection cards carry matching copy payloads`() {
        val cards = connectionInfoCards(inboundPort = 4444, strings = EnglishStrings)
        val byTitle = cards.associateBy { it.title }

        assertEquals(
            listOf("http://localhost:4444/mcp"),
            byTitle.getValue(EnglishStrings.connectionInfoHttpTitle).copyPayloads,
        )
        assertEquals(
            listOf("/Applications/broxy.app/Contents/MacOS/broxy", "--stdio-proxy"),
            byTitle.getValue(EnglishStrings.connectionInfoStdioTitle).copyPayloads,
        )
        assertEquals(
            listOf("http://localhost:4444/sse"),
            byTitle.getValue(EnglishStrings.connectionInfoSseTitle).copyPayloads,
        )
    }
}
