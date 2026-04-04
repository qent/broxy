package io.qent.broxy.core.config

import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.repository.ConfigurationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class ConfigurationWatcherFileEventsTest {
    @Test
    fun file_events_trigger_reload_callbacks() =
        runBlocking {
            val tempDir = Files.createTempDirectory("broxy-watch")
            val repo = FakeRepo()
            val scope = CoroutineScope(Dispatchers.IO)
            val watcher =
                ConfigurationWatcher(
                    baseDir = tempDir,
                    repo = repo,
                    scope = scope,
                    debounceMillis = 25,
                    emitInitialState = false,
                )
            val events = Channel<String>(Channel.UNLIMITED)
            val observer =
                object : ConfigurationObserver {
                    override fun onConfigurationChanged(config: McpServersConfig) {
                        events.trySend("config")
                    }

                    override fun onPresetChanged(preset: Preset) {
                        events.trySend("preset")
                    }
                }

            watcher.addObserver(observer)
            watcher.start()

            Files.writeString(tempDir.resolve("mcp.json"), "{}")
            Files.writeString(tempDir.resolve("preset_demo.json"), "{}")

            val received =
                withTimeout(2_000) {
                    val results = mutableListOf<String>()
                    while (results.size < 2) {
                        results += events.receive()
                    }
                    results
                }

            assertTrue("config" in received)
            assertTrue("preset" in received)

            watcher.close()
            scope.cancel()
        }

    private class FakeRepo : ConfigurationRepository {
        override fun loadMcpConfig(): McpServersConfig = McpServersConfig(servers = emptyList())

        override fun saveMcpConfig(config: McpServersConfig) = Unit

        override fun loadPreset(id: String): Preset = Preset(id = id, name = id)

        override fun savePreset(preset: Preset) = Unit

        override fun listPresets(): List<Preset> = emptyList()

        override fun deletePreset(id: String) = Unit
    }
}
