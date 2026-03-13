package io.qent.broxy.core.mcp.auth

import io.qent.broxy.core.utils.Logger

class CapturingLogger : Logger {
    val messages = mutableListOf<String>()

    override fun debug(message: String) {
        messages += message
    }

    override fun info(message: String) {
        messages += message
    }

    override fun warn(
        message: String,
        throwable: Throwable?,
    ) {
        messages += message
        throwable?.message?.let { messages += it }
    }

    override fun error(
        message: String,
        throwable: Throwable?,
    ) {
        messages += message
        throwable?.message?.let { messages += it }
    }
}
