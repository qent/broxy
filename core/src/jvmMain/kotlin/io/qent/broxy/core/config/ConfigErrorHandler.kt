package io.qent.broxy.core.config

import io.qent.broxy.core.utils.ConfigurationException
import io.qent.broxy.core.utils.Logger

internal class ConfigErrorHandler(
    private val logger: Logger,
) {
    fun fail(
        message: String,
        cause: Throwable? = null,
    ): Nothing {
        logger.error(message, cause)
        throw ConfigurationException(message, cause)
    }
}
