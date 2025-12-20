package io.qent.broxy.core.config

import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.repository.ConfigurationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigurationWatcherTest {
    @Test
    fun triggerReload_notifies_observers_after_debounce() =
        runTest {
            val tempDir = Files.createTempDirectory("broxy-config")
            val config = McpServersConfig(servers = emptyList())
            val preset = Preset(id = "p1", name = "Preset")
            Files.writeString(tempDir.resolve("preset_${preset.id}.json"), "{}")

            val repo = FakeRepo(config = config, preset = preset)
            val watcher =
                ConfigurationWatcher(
                    baseDir = tempDir,
                    repo = repo,
                    scope = this,
                    debounceMillis = 0,
                    emitInitialState = false,
                )
            val events = mutableListOf<String>()
            val observer =
                object : ConfigurationObserver {
                    override fun onConfigurationChanged(config: McpServersConfig) {
                        events += "config"
                    }

                    override fun onPresetChanged(preset: Preset) {
                        events += "preset:${preset.id}"
                    }
                }

            watcher.addObserver(observer)
            watcher.triggerConfigReload()
            watcher.triggerPresetReload("p1")
            advanceUntilIdle()

            assertEquals(listOf("config", "preset:p1"), events)
            watcher.close()
        }
}

private class FakeRepo(
    private val config: McpServersConfig,
    private val preset: Preset,
) : ConfigurationRepository {
    override fun loadMcpConfig(): McpServersConfig = config

    override fun saveMcpConfig(config: McpServersConfig) {}

    override fun loadPreset(id: String): Preset = preset

    override fun savePreset(preset: Preset) {}

    override fun listPresets(): List<Preset> = listOf(preset)

    override fun deletePreset(id: String) {}
}
