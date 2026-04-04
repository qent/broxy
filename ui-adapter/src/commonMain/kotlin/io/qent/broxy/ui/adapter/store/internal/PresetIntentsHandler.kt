package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.ui.adapter.models.UiPreset
import io.qent.broxy.ui.adapter.models.UiPresetCore
import io.qent.broxy.ui.adapter.models.UiPresetDraft
import io.qent.broxy.ui.adapter.models.UiProxyStatus
import io.qent.broxy.ui.adapter.store.toPresetCore
import io.qent.broxy.ui.adapter.store.toUiPresetSummary
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PresetIntentsHandler(
    private val context: IntentExecutionContext,
    private val configGateway: StoreConfigGateway,
) {
    fun addOrUpdatePreset(preset: UiPreset) {
        context.scope.launch {
            val previousSnapshot = context.state.snapshot
            val previousPresets = previousSnapshot.presets
            val existingIndex = previousPresets.indexOfFirst { it.id == preset.id }
            val orderIndex = if (existingIndex >= 0) existingIndex else previousPresets.size
            val updated = previousPresets.toMutableList()
            val idx = updated.indexOfFirst { it.id == preset.id }
            if (idx >= 0) updated[idx] = preset else updated += preset
            context.state.updateSnapshot { copy(presets = updated) }
            val result =
                withContext(context.ioDispatcher) {
                    configGateway.savePreset(
                        UiPresetCore(
                            id = preset.id,
                            name = preset.name,
                            tools = emptyList(),
                            prompts = null,
                            resources = null,
                            orderIndex = orderIndex,
                        ),
                    )
                }
            if (result.isFailure) {
                revertPresetsOnFailure(
                    context = context,
                    operation = "addOrUpdatePreset",
                    previousSnapshot = previousSnapshot,
                    failure = result.exceptionOrNull(),
                    defaultMessage = "Failed to save preset",
                )
            }
            context.publishReady()
        }
    }

    fun upsertPreset(draft: UiPresetDraft) {
        context.scope.launch {
            val originalId = draft.originalId?.trim()?.takeIf { it.isNotBlank() }
            val trimmedId = draft.id.trim()
            val normalizedDraft = if (trimmedId == draft.id) draft else draft.copy(id = trimmedId)
            val previousSnapshot = context.state.snapshot
            val previousConfig = context.state.snapshotConfig()
            val isRename = originalId != null && originalId != normalizedDraft.id
            val renameId = if (isRename) requireNotNull(originalId) else null
            val existingIndex =
                when {
                    renameId != null -> previousSnapshot.presets.indexOfFirst { it.id == renameId }
                    else -> previousSnapshot.presets.indexOfFirst { it.id == normalizedDraft.id }
                }
            val orderIndex = if (existingIndex >= 0) existingIndex else previousSnapshot.presets.size
            val preset = normalizedDraft.toPresetCore().copy(orderIndex = orderIndex)

            val saveResult =
                withContext(context.ioDispatcher) {
                    configGateway.savePreset(preset)
                }
            if (saveResult.isFailure) {
                val msg = logFailure(context.logger, "upsertPreset", saveResult.exceptionOrNull(), "Failed to save preset")
                context.state.setError(msg)
            }

            val updatedPresets = previousSnapshot.presets.toMutableList()
            val summary = preset.toUiPresetSummary()
            if (renameId != null) {
                val oldIndex = updatedPresets.indexOfFirst { it.id == renameId }
                val existingByNewId = updatedPresets.indexOfFirst { it.id == summary.id }
                if (existingByNewId >= 0) {
                    updatedPresets[existingByNewId] = summary
                } else {
                    if (oldIndex >= 0) {
                        updatedPresets.removeAt(oldIndex)
                    }
                    val insertIndex = if (oldIndex >= 0) oldIndex else updatedPresets.size
                    updatedPresets.add(insertIndex.coerceAtMost(updatedPresets.size), summary)
                }
                updatedPresets.removeAll { it.id == renameId }
            } else {
                val idx = updatedPresets.indexOfFirst { it.id == summary.id }
                if (idx >= 0) updatedPresets[idx] = summary else updatedPresets += summary
            }

            var defaultPresetId = previousSnapshot.defaultPresetId
            if (saveResult.isSuccess && renameId != null) {
                val wasDefault = previousSnapshot.defaultPresetId == renameId
                if (wasDefault) {
                    val configSave =
                        withContext(context.ioDispatcher) {
                            configGateway.updateDefaultPresetId(previousConfig, preset.id)
                        }
                    if (configSave.isFailure) {
                        val msg =
                            logFailure(
                                context.logger,
                                "upsertPreset(renameDefault,id=$renameId)",
                                configSave.exceptionOrNull(),
                                "Failed to update default preset",
                            )
                        context.state.setError(msg)
                        context.state.updateSnapshot { copy(presets = updatedPresets) }
                        context.publishReady()
                        return@launch
                    }
                    defaultPresetId = preset.id
                }

                val deleteResult =
                    withContext(context.ioDispatcher) {
                        configGateway.deletePreset(renameId)
                    }
                if (deleteResult.isFailure) {
                    val msg =
                        logFailure(
                            context.logger,
                            "upsertPreset(deleteOld,id=$renameId)",
                            deleteResult.exceptionOrNull(),
                            "Failed to remove old preset",
                        )
                    context.state.setError(msg)
                }
                updatedPresets.removeAll { it.id == renameId }
            }

            context.state.updateSnapshot { copy(presets = updatedPresets, defaultPresetId = defaultPresetId) }
            val shouldRestart =
                saveResult.isSuccess &&
                    (
                        previousSnapshot.activeProxyPresetId == preset.id ||
                            (renameId != null && previousSnapshot.activeProxyPresetId == renameId)
                    )
            context.publishReady()
            if (shouldRestart) {
                val reloadId =
                    if (renameId != null && previousSnapshot.activeProxyPresetId == renameId) {
                        preset.id
                    } else {
                        null
                    }
                val reloadResult = context.proxyRuntime.ensureInboundRunning(presetIdOverride = reloadId, forceReloadPreset = true)
                if (reloadResult.isFailure) {
                    val msg = failureMessage(reloadResult.exceptionOrNull(), "Failed to apply preset")
                    context.pushToast(msg)
                }
            }
        }
    }

    fun removePreset(id: String) {
        context.scope.launch {
            val previous = context.state.snapshot
            val updated = previous.presets.filterNot { it.id == id }
            context.state.updateSnapshot { withPresets(updated) }
            val result =
                withContext(context.ioDispatcher) {
                    configGateway.deletePreset(id)
                }
            if (result.isFailure) {
                revertPresetsOnFailure(
                    context = context,
                    operation = "removePreset",
                    previousSnapshot = previous,
                    failure = result.exceptionOrNull(),
                    defaultMessage = "Failed to delete preset",
                )
            }
            context.publishReady()
            if (previous.defaultPresetId == id) {
                val saveResult =
                    withContext(context.ioDispatcher) {
                        configGateway.updateDefaultPresetId(context.state.snapshotConfig(), null)
                    }
                if (saveResult.isFailure) {
                    logFailure(
                        context.logger,
                        "removePreset(clearDefault,id=$id)",
                        saveResult.exceptionOrNull(),
                        "Failed to clear default preset",
                    )
                }
            }
            val reloadId =
                if (previous.activeProxyPresetId == id) {
                    UiPresetCore.EMPTY_PRESET_ID
                } else {
                    null
                }
            val reloadResult = context.proxyRuntime.ensureInboundRunning(presetIdOverride = reloadId, forceReloadPreset = true)
            if (reloadResult.isFailure) {
                val msg = failureMessage(reloadResult.exceptionOrNull(), "Failed to apply preset")
                context.pushToast(msg)
            }
        }
    }

    fun reorderPresets(presetIds: List<String>) {
        context.scope.launch {
            val previousSnapshot = context.state.snapshot
            val previousPresets = previousSnapshot.presets
            val reordered =
                reorderByIds(previousPresets, presetIds) { it.id }
                    ?: run {
                        logFailure(
                            context.logger,
                            "reorderPresets",
                            IllegalArgumentException("Invalid preset reorder request"),
                            "Failed to reorder presets",
                        )
                        return@launch
                    }
            if (reordered == previousPresets) return@launch
            context.state.updateSnapshot { copy(presets = reordered) }
            context.publishReady()
            val result =
                withContext(context.ioDispatcher) {
                    configGateway.reorderPresets(presetIds)
                }
            if (result.isFailure) {
                revertPresetsOnFailure(
                    context = context,
                    operation = "reorderPresets",
                    previousSnapshot = previousSnapshot,
                    failure = result.exceptionOrNull(),
                    defaultMessage = "Failed to save preset order",
                )
            }
            context.publishReady()
        }
    }

    fun selectProxyPreset(presetId: String?) {
        context.scope.launch {
            val isRunning = context.state.snapshot.proxyStatus is UiProxyStatus.Running
            val currentActive = context.state.snapshot.activeProxyPresetId
            if (isRunning && currentActive == presetId) return@launch
            val applyResult =
                context.proxyRuntime.ensureInboundRunning(
                    presetIdOverride = presetId,
                    forceReloadPreset = true,
                )
            if (applyResult.isFailure) {
                val msg = failureMessage(applyResult.exceptionOrNull(), "Failed to apply preset")
                context.pushToast(msg)
                return@launch
            }
            val previousConfig = context.state.snapshotConfig()
            val saveResult =
                withContext(context.ioDispatcher) {
                    configGateway.updateDefaultPresetId(previousConfig, presetId)
                }
            if (saveResult.isFailure) {
                val msg =
                    logFailure(
                        context.logger,
                        "selectProxyPreset(saveDefault)",
                        saveResult.exceptionOrNull(),
                        "Failed to save default preset",
                    )
                context.pushToast(msg)
                return@launch
            }
            context.state.updateSnapshot { copy(defaultPresetId = presetId) }
            context.publishReady()
        }
    }
}
