package io.qent.broxy.registry.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GithubCatalogRepositoryTest {
    @Test
    fun refreshCatalog_uses_head_metadata_and_downloads_only_when_last_modified_changes() =
        runTest {
            val indexLastModifiedEpoch = AtomicLong(Instant.parse("2026-03-01T10:15:30Z").toEpochMilli())
            val indexHeadRequests = AtomicInteger(0)
            val indexGetRequests = AtomicInteger(0)
            val serverGetRequests = AtomicInteger(0)
            val serverJson =
                AtomicReference(
                    serverPayload(description = "Initial description"),
                )

            val httpServer =
                HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                    createContext("/index.json") { exchange ->
                        handleIndex(
                            exchange = exchange,
                            lastModifiedEpochMillis = indexLastModifiedEpoch.get(),
                            onHead = { indexHeadRequests.incrementAndGet() },
                            onGet = { indexGetRequests.incrementAndGet() },
                        )
                    }
                    createContext("/servers/time.json") { exchange ->
                        handleServer(
                            exchange = exchange,
                            payload = serverJson.get(),
                            onGet = { serverGetRequests.incrementAndGet() },
                        )
                    }
                    start()
                }
            val baseUrl = "http://${httpServer.address.hostString}:${httpServer.address.port}"
            val cacheDir = createTempDirectory("github-catalog-repository-test")

            try {
                val repository =
                    GithubCatalogRepository(
                        cacheDir = cacheDir,
                        rawBaseUrl = baseUrl,
                    )

                val firstRefresh = repository.refreshCatalog().getOrThrow()
                assertNotNull(firstRefresh)
                assertEquals(indexLastModifiedEpoch.get(), firstRefresh.updatedAtEpochMillis)
                assertEquals(1, indexHeadRequests.get())
                assertEquals(1, indexGetRequests.get())
                assertEquals(1, serverGetRequests.get())

                val secondRefresh = repository.refreshCatalog().getOrThrow()
                assertNull(secondRefresh)
                assertEquals(2, indexHeadRequests.get())
                assertEquals(1, indexGetRequests.get())
                assertEquals(1, serverGetRequests.get())

                indexLastModifiedEpoch.set(Instant.parse("2026-03-02T11:00:00Z").toEpochMilli())
                serverJson.set(serverPayload(description = "Updated description"))

                val thirdRefresh = repository.refreshCatalog().getOrThrow()
                assertNotNull(thirdRefresh)
                assertEquals(indexLastModifiedEpoch.get(), thirdRefresh.updatedAtEpochMillis)
                assertTrue(thirdRefresh.servers.any { it.description == "Updated description" })
                assertEquals(3, indexHeadRequests.get())
                assertEquals(2, indexGetRequests.get())
                assertEquals(2, serverGetRequests.get())
            } finally {
                runCatching { httpServer.stop(0) }
                runCatching { cacheDir.toFile().deleteRecursively() }
            }
        }

    @Test
    fun refreshCatalog_downloads_when_cache_missing_even_if_last_modified_has_not_changed() =
        runTest {
            val indexLastModifiedEpoch = Instant.parse("2026-03-01T10:15:30Z").toEpochMilli()
            val indexHeadRequests = AtomicInteger(0)
            val indexGetRequests = AtomicInteger(0)
            val serverGetRequests = AtomicInteger(0)

            val httpServer =
                HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                    createContext("/index.json") { exchange ->
                        handleIndex(
                            exchange = exchange,
                            lastModifiedEpochMillis = indexLastModifiedEpoch,
                            onHead = { indexHeadRequests.incrementAndGet() },
                            onGet = { indexGetRequests.incrementAndGet() },
                        )
                    }
                    createContext("/servers/time.json") { exchange ->
                        handleServer(
                            exchange = exchange,
                            payload = serverPayload(description = "Cache bootstrap"),
                            onGet = { serverGetRequests.incrementAndGet() },
                        )
                    }
                    start()
                }
            val baseUrl = "http://${httpServer.address.hostString}:${httpServer.address.port}"
            val cacheDir = createTempDirectory("github-catalog-repository-test")

            try {
                val repository =
                    GithubCatalogRepository(
                        cacheDir = cacheDir,
                        rawBaseUrl = baseUrl,
                    )

                val firstRefresh = repository.refreshCatalog().getOrThrow()
                assertNotNull(firstRefresh)
                assertEquals(1, indexHeadRequests.get())
                assertEquals(1, indexGetRequests.get())
                assertEquals(1, serverGetRequests.get())

                // New repository instance imitates next app start with existing cache.
                val reloadedRepository =
                    GithubCatalogRepository(
                        cacheDir = cacheDir,
                        rawBaseUrl = baseUrl,
                    )
                val secondRefresh = reloadedRepository.refreshCatalog().getOrThrow()
                assertNull(secondRefresh)
                assertEquals(2, indexHeadRequests.get())
                assertEquals(1, indexGetRequests.get())
                assertEquals(1, serverGetRequests.get())
            } finally {
                runCatching { httpServer.stop(0) }
                runCatching { cacheDir.toFile().deleteRecursively() }
            }
        }

    private fun serverPayload(description: String): String =
        """
        {
          "name": "io.qent.broxy/time",
          "title": "Time",
          "description": "$description",
          "version": "1.0.0",
          "remotes": [
            {
              "type": "streamable-http",
              "url": "https://example.com/mcp"
            }
          ]
        }
        """.trimIndent()

    private fun handleIndex(
        exchange: HttpExchange,
        lastModifiedEpochMillis: Long,
        onHead: () -> Unit,
        onGet: () -> Unit,
    ) {
        exchange.responseHeaders.add("Last-Modified", toHttpDate(lastModifiedEpochMillis))
        when (exchange.requestMethod.uppercase()) {
            "HEAD" -> {
                onHead()
                exchange.sendResponseHeaders(200, -1)
            }

            "GET" -> {
                onGet()
                writeJson(
                    exchange = exchange,
                    payload =
                        """
                        {
                          "schemaVersion": 1,
                          "servers": [
                            {
                              "id": "io.qent.broxy/time",
                              "path": "servers/time.json"
                            }
                          ]
                        }
                        """.trimIndent(),
                )
            }

            else -> exchange.sendResponseHeaders(405, -1)
        }
        exchange.close()
    }

    private fun handleServer(
        exchange: HttpExchange,
        payload: String,
        onGet: () -> Unit,
    ) {
        when (exchange.requestMethod.uppercase()) {
            "GET" -> {
                onGet()
                writeJson(exchange, payload)
            }

            else -> exchange.sendResponseHeaders(405, -1)
        }
        exchange.close()
    }

    private fun writeJson(
        exchange: HttpExchange,
        payload: String,
    ) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { stream ->
            stream.write(bytes)
        }
    }

    private fun toHttpDate(epochMillis: Long): String =
        DateTimeFormatter
            .RFC_1123_DATE_TIME
            .format(Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC))
}
