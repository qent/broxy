package io.qent.broxy.ui.screens

import io.qent.broxy.ui.adapter.models.UiAgent
import io.qent.broxy.ui.strings.AppStrings

internal fun resolveAgentFailedStatusLabel(
    agent: UiAgent,
    strings: AppStrings,
): String? {
    val failedRecord = agent.latestFailedRun ?: return null
    val errorText = failedRecord.errorMessage?.trim()?.takeIf { it.isNotBlank() }
    return if (errorText == null) {
        strings.runHistoryStatusFailed
    } else {
        "${strings.runHistoryStatusFailed}: $errorText"
    }
}
