package io.qent.broxy.agents

import io.qent.broxy.agents.runtime.models.CodexCliModelCatalog
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodexCliModelCatalogTest {
    @Test
    fun listModels_fetchesAllPages_filtersHidden_andUsesFallbackModelField() =
        runTest {
            val tempDir = Files.createTempDirectory("codex-model-catalog")
            val script = tempDir.resolve("fake-codex.sh")
            val requestsFile = tempDir.resolve("requests.log")
            try {
                Files.writeString(
                    script,
                    """
                    #!/bin/sh
                    if [ "${'$'}1" != "app-server" ]; then
                      echo "unexpected command" >&2
                      exit 3
                    fi
                    line_no=0
                    while IFS= read -r line; do
                      line_no=$((line_no + 1))
                      printf '%s\n' "${'$'}line" >> "${requestsFile.toAbsolutePath()}"
                      case "${'$'}line_no" in
                        1)
                          echo '{"id":1,"result":{"userAgent":"fake"}}'
                          ;;
                        2)
                          ;;
                        3)
                          echo '{"id":2,"result":{"data":[{"id":" gpt-5.3-codex "},{"model":"legacy-model"},{"id":"skip-hidden","hidden":true}],"nextCursor":"cursor-1"}}'
                          ;;
                        4)
                          echo '{"id":3,"result":{"data":[{"id":"legacy-model"},{"id":"gpt-5.2-codex"}],"nextCursor":null}}'
                          ;;
                      esac
                    done
                    """.trimIndent(),
                )
                script.toFile().setExecutable(true)

                val catalog = CodexCliModelCatalog()
                val result = catalog.listModels(script.toAbsolutePath().toString())

                assertTrue(result.isSuccess)
                assertEquals(
                    listOf("gpt-5.2-codex", "gpt-5.3-codex", "legacy-model"),
                    result.getOrThrow(),
                )
                val requests = Files.readAllLines(requestsFile)
                assertTrue(requests.size >= 4)
                assertTrue(requests[2].contains("\"method\":\"model/list\""))
                assertTrue(requests[2].contains("\"includeHidden\":false"))
                assertTrue(requests[2].contains("\"limit\":100"))
                assertTrue(requests[3].contains("\"cursor\":\"cursor-1\""))
            } finally {
                cleanupDirectory(tempDir)
            }
        }

    @Test
    fun listModels_returnsFailureWhenAppServerReturnsErrorPayload() =
        runTest {
            val tempDir = Files.createTempDirectory("codex-model-catalog-error")
            val script = tempDir.resolve("fake-codex.sh")
            try {
                Files.writeString(
                    script,
                    """
                    #!/bin/sh
                    if [ "${'$'}1" != "app-server" ]; then
                      echo "unexpected command" >&2
                      exit 3
                    fi
                    line_no=0
                    while IFS= read -r line; do
                      line_no=$((line_no + 1))
                      case "${'$'}line_no" in
                        1)
                          echo '{"id":1,"result":{"userAgent":"fake"}}'
                          ;;
                        2)
                          ;;
                        3)
                          echo '{"id":2,"error":{"message":"failed-list"}}'
                          ;;
                      esac
                    done
                    """.trimIndent(),
                )
                script.toFile().setExecutable(true)

                val catalog = CodexCliModelCatalog()
                val result = catalog.listModels(script.toAbsolutePath().toString())

                assertTrue(result.isFailure)
                assertTrue(
                    result
                        .exceptionOrNull()
                        ?.message
                        .orEmpty()
                        .contains("failed-list"),
                )
            } finally {
                cleanupDirectory(tempDir)
            }
        }
}

private fun cleanupDirectory(path: Path) {
    if (!Files.exists(path)) {
        return
    }
    Files.walk(path).sorted(Comparator.reverseOrder()).forEach { target -> Files.deleteIfExists(target) }
}
