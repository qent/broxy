package io.qent.broxy.ui.adapter.data

import io.qent.broxy.ui.adapter.models.UiSettings

interface UiSettingsRepository {
    fun loadUiSettings(): UiSettings

    fun saveUiSettings(settings: UiSettings)

    companion object {
        val Noop: UiSettingsRepository =
            object : UiSettingsRepository {
                override fun loadUiSettings(): UiSettings = UiSettings()

                override fun saveUiSettings(settings: UiSettings) {}
            }
    }
}
