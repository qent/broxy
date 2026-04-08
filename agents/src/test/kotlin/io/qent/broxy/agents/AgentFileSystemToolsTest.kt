package io.qent.broxy.agents

import io.qent.broxy.agents.runtime.filesystem.AgentFileSystemSandbox
import io.qent.broxy.agents.runtime.filesystem.AgentFileSystemTools
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentFileSystemToolsTest {
    @Test
    fun fsInspect_list_returnsDirectoryItems() {
        withWorkspace { workspace ->
            val fileA = workspace.resolve("a.txt")
            val fileB = workspace.resolve("b.txt")
            Files.writeString(fileA, "alpha")
            Files.writeString(fileB, "beta")
            val tools = createTools(workspace)

            val result =
                tools.execute(
                    "fsInspect",
                    buildJsonObject {
                        put("operation", JsonPrimitive("list"))
                        put("path", JsonPrimitive("."))
                    },
                )
            val payload = parsePayload(result.payload)

            assertTrue(result.ok)
            val items = payload.data("items").jsonArray
            assertEquals(2, items.size)
            assertEquals("a.txt", items[0].jsonObject["name"]?.jsonPrimitive?.content)
            assertEquals("b.txt", items[1].jsonObject["name"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun fsRead_binaryFile_returnsUnifiedBinaryError() {
        withWorkspace { workspace ->
            val binary = workspace.resolve("data.bin")
            Files.write(binary, byteArrayOf(0, 1, 2, 3))
            val tools = createTools(workspace)

            val result =
                tools.execute(
                    "fsRead",
                    buildJsonObject {
                        put("filePath", JsonPrimitive("data.bin"))
                        put("mode", JsonPrimitive("head"))
                    },
                )
            val payload = parsePayload(result.payload)

            assertFalse(result.ok)
            assertFalse(payload.ok())
            assertEquals("binary_file_not_supported", payload.code())
        }
    }

    @Test
    fun fsRead_outsideWorkspace_returnsTraversalError() {
        withWorkspace { workspace ->
            val outside = workspace.parent.resolve("outside.txt")
            Files.writeString(outside, "outside")
            val tools = createTools(workspace)

            val result =
                tools.execute(
                    "fsRead",
                    buildJsonObject {
                        put("filePath", JsonPrimitive("../outside.txt"))
                        put("mode", JsonPrimitive("head"))
                    },
                )
            val payload = parsePayload(result.payload)

            assertFalse(result.ok)
            assertEquals("path_outside_workspace", payload.code())
        }
    }

    @Test
    fun fsSearch_content_reportsLineNumbers() {
        withWorkspace { workspace ->
            val source = workspace.resolve("notes.txt")
            Files.writeString(
                source,
                """
                first line
                second line with token
                third line
                """.trimIndent(),
            )
            val tools = createTools(workspace)

            val result =
                tools.execute(
                    "fsSearch",
                    buildJsonObject {
                        put("mode", JsonPrimitive("content"))
                        put("contentRegex", JsonPrimitive("token"))
                    },
                )
            val payload = parsePayload(result.payload)

            assertTrue(result.ok)
            val matches = payload.data("contentMatches").jsonArray
            assertEquals(1, matches.size)
            assertEquals(2, matches[0].jsonObject["lineNumber"]?.jsonPrimitive?.int)
            assertEquals("notes.txt", matches[0].jsonObject["filePath"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun fsEdit_overwrite_withEmptyText_createsEmptyTextFile() {
        withWorkspace { workspace ->
            val tools = createTools(workspace)

            val editResult =
                tools.execute(
                    "fsEdit",
                    buildJsonObject {
                        put("operation", JsonPrimitive("overwrite"))
                        put("path", JsonPrimitive("memory.md"))
                        put("text", JsonPrimitive(""))
                    },
                )
            val editPayload = parsePayload(editResult.payload)

            assertTrue(editResult.ok)
            assertTrue(editPayload.ok())
            assertEquals(0, editPayload.data("newFileSizeBytes").jsonPrimitive.int)
            assertEquals("", Files.readString(workspace.resolve("memory.md")))
        }
    }

    @Test
    fun fsEdit_withNullByteText_returnsBinaryPayloadError() {
        withWorkspace { workspace ->
            val tools = createTools(workspace)

            val editResult =
                tools.execute(
                    "fsEdit",
                    buildJsonObject {
                        put("operation", JsonPrimitive("overwrite"))
                        put("path", JsonPrimitive("memory.md"))
                        put("text", JsonPrimitive("\u0000"))
                    },
                )
            val payload = parsePayload(editResult.payload)

            assertFalse(editResult.ok)
            assertFalse(payload.ok())
            assertEquals("binary_file_not_supported", payload.code())
        }
    }

    @Test
    fun fsEdit_overwrite_withExistingBinaryFile_returnsBinaryError() {
        withWorkspace { workspace ->
            val target = workspace.resolve("memory.md")
            val binary = byteArrayOf(0, 1, 2)
            Files.write(target, binary)
            val tools = createTools(workspace)

            val editResult =
                tools.execute(
                    "fsEdit",
                    buildJsonObject {
                        put("operation", JsonPrimitive("overwrite"))
                        put("path", JsonPrimitive("memory.md"))
                        put("text", JsonPrimitive("fixed text"))
                    },
                )
            val payload = parsePayload(editResult.payload)

            assertFalse(editResult.ok)
            assertFalse(payload.ok())
            assertEquals("binary_file_not_supported", payload.code())
            assertContentEquals(binary, Files.readAllBytes(target))
        }
    }

    @Test
    fun fsEdit_replaceRange_rewritesExpectedBlock() {
        withWorkspace { workspace ->
            val source = workspace.resolve("doc.txt")
            Files.writeString(
                source,
                """
                one
                two
                three
                four
                """.trimIndent(),
            )
            val tools = createTools(workspace)

            val result =
                tools.execute(
                    "fsEdit",
                    buildJsonObject {
                        put("operation", JsonPrimitive("replace_range"))
                        put("path", JsonPrimitive("doc.txt"))
                        put("text", JsonPrimitive("TWO\nTHREE"))
                        put("startLine", JsonPrimitive(2))
                        put("endLine", JsonPrimitive(3))
                    },
                )
            val payload = parsePayload(result.payload)

            assertTrue(result.ok)
            assertTrue(payload.ok())
            assertEquals(2, payload.data("changedStartLine").jsonPrimitive.int)
            assertEquals(3, payload.data("changedEndLine").jsonPrimitive.int)
            assertEquals("one\nTWO\nTHREE\nfour", Files.readString(source))
        }
    }

    private fun withWorkspace(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("broxy-fs-tools-test")
        try {
            block(root)
        } finally {
            root.toFile().walkBottomUp().forEach { file ->
                if (file.exists()) {
                    file.delete()
                }
            }
            root.deleteIfExists()
        }
    }

    private fun createTools(workspacePath: Path): AgentFileSystemTools {
        val workspace =
            AgentFileSystemSandbox.prepare(
                AgentFileSystemSettings(
                    path = workspacePath.toAbsolutePath().normalize().toString(),
                    access = AgentFileSystemAccess.READ_WRITE,
                ),
            )
        return AgentFileSystemTools(workspace)
    }

    private fun parsePayload(payload: String): ParsedPayload {
        val jsonObject = Json.parseToJsonElement(payload).jsonObject
        return ParsedPayload(jsonObject)
    }

    private class ParsedPayload(
        private val value: JsonObject,
    ) {
        fun ok(): Boolean = value["ok"]?.jsonPrimitive?.boolean ?: false

        fun code(): String = value["code"]?.jsonPrimitive?.content.orEmpty()

        fun data(key: String): kotlinx.serialization.json.JsonElement = value["data"]!!.jsonObject[key]!!
    }
}
