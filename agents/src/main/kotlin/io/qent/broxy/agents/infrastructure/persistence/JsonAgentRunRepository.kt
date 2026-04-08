package io.qent.broxy.agents.infrastructure.persistence

import io.qent.broxy.agents.AgentRunDetails
import io.qent.broxy.agents.AgentRunRepository
import io.qent.broxy.agents.AgentRunSummary
import io.qent.broxy.core.utils.ConfigurationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

class JsonAgentRunRepository(
    private val baseDir: Path = Paths.get(System.getProperty("user.home"), ".config", "broxy"),
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        },
) : AgentRunRepository {
    private val storage = agentStorageLayout(baseDir)
    private val lock = Any()

    override fun listRuns(): List<AgentRunSummary> =
        synchronized(lock) {
            loadIndexUnsafe().sortedWith(runComparator())
        }

    override fun loadRun(runId: String): AgentRunDetails {
        val file = storage.runFile(runId)
        if (!file.exists() || !file.isRegularFile()) {
            throw ConfigurationException("Run '$runId' not found at ${file.toAbsolutePath()}")
        }
        val details = decodeRun(file)
        if (details.summary.runId != runId) {
            throw ConfigurationException(
                "Run file '${file.name}' id '${details.summary.runId}' does not match requested run id '$runId'",
            )
        }
        return details
    }

    override fun saveRun(details: AgentRunDetails) {
        synchronized(lock) {
            val runFile = storage.runFile(details.summary.runId)
            try {
                ensureStorageDirectories()
                Files.writeString(
                    runFile,
                    json.encodeToString(AgentRunDetails.serializer(), details),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                )
            } catch (e: IOException) {
                throw ConfigurationException("Failed to save run '${details.summary.runId}': ${e.message}", e)
            }

            val updatedIndex =
                loadIndexUnsafe()
                    .filterNot { it.runId == details.summary.runId }
                    .plus(details.summary)
                    .sortedWith(runComparator())
            writeIndexUnsafe(updatedIndex)
        }
    }

    private fun loadIndexUnsafe(): List<AgentRunSummary> {
        val file = storage.runsIndexFile
        if (!file.exists() || !file.isRegularFile()) {
            return emptyList()
        }
        val text =
            try {
                Files.readString(file)
            } catch (e: IOException) {
                throw ConfigurationException("Failed to read ${file.name}: ${e.message}", e)
            }
        return try {
            json.decodeFromString(ListSerializer(AgentRunSummary.serializer()), text)
        } catch (e: SerializationException) {
            throw ConfigurationException("Invalid run index file '${file.name}': ${e.message}", e)
        }
    }

    private fun writeIndexUnsafe(index: List<AgentRunSummary>) {
        val file = storage.runsIndexFile
        try {
            ensureStorageDirectories()
            Files.writeString(
                file,
                json.encodeToString(ListSerializer(AgentRunSummary.serializer()), index),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        } catch (e: IOException) {
            throw ConfigurationException("Failed to save run index '${file.name}': ${e.message}", e)
        }
    }

    private fun decodeRun(path: Path): AgentRunDetails {
        val text =
            try {
                Files.readString(path)
            } catch (e: IOException) {
                throw ConfigurationException("Failed to read ${path.name}: ${e.message}", e)
            }
        return try {
            json.decodeFromString(AgentRunDetails.serializer(), text)
        } catch (e: SerializationException) {
            throw ConfigurationException("Invalid run file '${path.name}': ${e.message}", e)
        }
    }

    private fun ensureStorageDirectories() {
        if (!Files.exists(storage.rootDir)) {
            Files.createDirectories(storage.rootDir)
        }
        if (!Files.exists(storage.runsDir)) {
            Files.createDirectories(storage.runsDir)
        }
    }
}

private fun runComparator(): Comparator<AgentRunSummary> =
    compareByDescending<AgentRunSummary> { it.startedAtEpochMillis }
        .thenByDescending { it.finishedAtEpochMillis }
        .thenBy { it.runId }
