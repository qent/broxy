package io.qent.broxy.core.config

import io.qent.broxy.core.utils.Logger

object ConfigTestLogger : Logger {
    override fun debug(message: String) {}

    override fun info(message: String) {}

    override fun warn(
        message: String,
        throwable: Throwable?,
    ) {}

    override fun error(
        message: String,
        throwable: Throwable?,
    ) {}
}
