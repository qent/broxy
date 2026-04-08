package io.qent.broxy.agents.runtime.filesystem

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal class AgentFileSystemTextGuard(
    private val binaryProbeBytes: Int = DEFAULT_BINARY_PROBE_BYTES,
) {
    fun rejectBinaryTextPayload(text: String?) {
        if (text?.contains('\u0000') == true) {
            throw AgentFileSystemException(
                code = "binary_file_not_supported",
                message = "Binary content is not supported for fsEdit text input",
            )
        }
    }

    fun ensureTextFile(path: Path) {
        val probe =
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(binaryProbeBytes)
                val read = input.read(buffer)
                if (read <= 0) {
                    ByteArray(0)
                } else {
                    buffer.copyOf(read)
                }
            }
        if (probe.any { it == 0.toByte() }) {
            throw AgentFileSystemException(
                code = "binary_file_not_supported",
                message = "Binary file is not supported: ${path.toAbsolutePath()}",
            )
        }
        runCatching {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(probe))
        }.getOrElse {
            throw AgentFileSystemException(
                code = "binary_file_not_supported",
                message = "Binary file is not supported: ${path.toAbsolutePath()}",
            )
        }
    }

    fun readUtf8(path: Path): String =
        runCatching {
            Files.readString(path, StandardCharsets.UTF_8)
        }.getOrElse { error ->
            if (error is CharacterCodingException) {
                throw AgentFileSystemException(
                    code = "binary_file_not_supported",
                    message = "Binary file is not supported: ${path.toAbsolutePath()}",
                )
            }
            throw AgentFileSystemException(
                code = "io_error",
                message = "Failed to read file: ${path.toAbsolutePath()}",
                cause = error,
            )
        }

    fun readUtf8Lines(path: Path): List<String> = readUtf8(path).lineSequence().toList()

    fun writeUtf8(
        path: Path,
        content: String,
    ) {
        runCatching {
            Files.writeString(path, content, StandardCharsets.UTF_8)
        }.getOrElse { error ->
            throw AgentFileSystemException(
                code = "io_error",
                message = "Failed to write file: ${path.toAbsolutePath()}",
                cause = error,
            )
        }
    }

    private companion object {
        private const val DEFAULT_BINARY_PROBE_BYTES = 8_192
    }
}
