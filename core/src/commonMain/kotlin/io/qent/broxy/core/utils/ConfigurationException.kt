package io.qent.broxy.core.utils

class ConfigurationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
