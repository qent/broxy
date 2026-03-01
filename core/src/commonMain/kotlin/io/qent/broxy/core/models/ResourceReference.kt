package io.qent.broxy.core.models

import kotlinx.serialization.Serializable

@Serializable
data class ResourceReference(
    val serverId: String,
    val resourceKey: String,
    val enabled: Boolean = true,
)
