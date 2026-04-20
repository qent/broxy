package io.qent.broxy.ui.presets

import io.qent.broxy.ui.strings.AppStrings

internal fun resolvePresetManagementLabel(
    strings: AppStrings,
    agenticModeEnabled: Boolean,
): String = if (agenticModeEnabled) strings.agenticMode else strings.presetManagement
