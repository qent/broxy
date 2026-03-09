package io.qent.broxy.ui.adapter.models

sealed interface UiServerIcon {
    object Default : UiServerIcon

    data class Asset(
        val id: String,
    ) : UiServerIcon

    data class Custom(
        val path: String,
    ) : UiServerIcon
}
