package io.qent.broxy.cli.commands

import com.github.ajalt.clikt.core.CliktError
import io.qent.broxy.core.models.TransportConfig
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ProxyCommandParsingTest {
    @Test
    @Timeout(10)
    fun `inbound aliases map to stdio and http`() {
        val stdio = parseOptions("--preset-id", "dev", "--inbound", "local")
        val http = parseOptions("--preset-id", "dev", "--inbound", "remote")

        assertEquals(InboundMode.STDIO, stdio.inbound)
        assertEquals(InboundMode.HTTP, http.inbound)
    }

    @Test
    fun `log level parses to enum`() {
        val options = parseOptions("--preset-id", "dev", "--log-level", "warn")

        assertEquals(LogLevelOption.WARN, options.logLevel)
    }

    @Test
    fun `http inbound uses default url when missing`() {
        val options = parseOptions("--preset-id", "dev", "--inbound", "http")

        val transport = options.toInboundTransport()
        val httpTransport = assertIs<TransportConfig.StreamableHttpTransport>(transport)
        assertEquals(DEFAULT_STREAMABLE_HTTP_URL, httpTransport.url)
    }

    @Test
    fun `stdio inbound maps to stdio transport`() {
        val options = parseOptions("--preset-id", "dev", "--inbound", "stdio")

        val transport = options.toInboundTransport()
        assertIs<TransportConfig.StdioTransport>(transport)
    }

    @Test
    fun `invalid inbound value fails parsing`() {
        assertFailsWith<CliktError> {
            parseOptions("--preset-id", "dev", "--inbound", "ftp")
        }
    }

    @Test
    fun `invalid log level fails parsing`() {
        assertFailsWith<CliktError> {
            parseOptions("--preset-id", "dev", "--log-level", "verbose")
        }
    }

    @Test
    fun `http inbound validates url`() {
        assertFailsWith<CliktError> {
            parseOptions("--preset-id", "dev", "--inbound", "http", "--url", "not a url")
        }
    }

    @Test
    fun `http inbound validates url regardless of option order`() {
        assertFailsWith<CliktError> {
            parseOptions("--preset-id", "dev", "--url", "not a url", "--inbound", "http")
        }
    }

    @Test
    fun `stdio ignores url validation`() {
        val options = parseOptions("--preset-id", "dev", "--inbound", "stdio", "--url", "not a url")

        assertEquals(InboundMode.STDIO, options.inbound)
    }

    private fun parseOptions(vararg args: String): CliOptions {
        val runner = CapturingRunner()
        val command = ProxyCommand(runner)
        command.parse(args.toList())
        return requireNotNull(runner.captured)
    }

    private class CapturingRunner : ProxyCommandRunner() {
        var captured: CliOptions? = null

        override fun run(options: CliOptions) {
            captured = options
        }
    }
}
