package io.qent.broxy.core.proxy.inbound

import io.qent.broxy.core.models.Preset

internal typealias InboundPresetResolver = (String) -> Result<Preset>

internal class InboundPresetNotFoundException(
    presetId: String,
) : IllegalArgumentException("Preset '$presetId' was not found")

internal class InboundSessionBindingConflictException(
    message: String,
) : IllegalStateException(message)

internal data class InboundSessionBinding(
    val routePath: String,
    val presetId: String? = null,
) {
    val isForced: Boolean
        get() = !presetId.isNullOrBlank()
}

internal fun bindingConflictMessage(
    existing: InboundSessionBinding,
    requested: InboundSessionBinding,
): String = "Session bound to '${existing.routePath}' cannot be used with '${requested.routePath}'"
