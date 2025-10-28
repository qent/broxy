package io.qent.bro.ui.adapter.models

data class UiLogEntry(
    val timestampMillis: Long,
    val level: UiLogLevel,
    val message: String,
    val throwableMessage: String? = null
)

enum class UiLogLevel { DEBUG, INFO, WARN, ERROR }
