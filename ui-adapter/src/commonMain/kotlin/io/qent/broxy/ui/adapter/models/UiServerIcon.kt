package io.qent.broxy.ui.adapter.models

sealed interface UiServerIcon {
    object Default : UiServerIcon

    data class Custom(
        val path: String,
    ) : UiServerIcon

    data class Remote(
        val url: String,
    ) : UiServerIcon
}
