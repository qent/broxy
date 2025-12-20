package io.qent.broxy.cloud.api

interface CloudLogger {
    fun debug(message: String)

    fun info(message: String)

    fun warn(
        message: String,
        throwable: Throwable? = null,
    )

    fun error(
        message: String,
        throwable: Throwable? = null,
    )
}
