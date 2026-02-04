package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.utils.Logger

private const val APPSTORE_LOG_PREFIX = "[AppStore]"

internal fun logInfo(
    logger: Logger,
    action: String,
    message: String,
) {
    logger.info("$APPSTORE_LOG_PREFIX $action: $message")
}

internal fun logFailure(
    logger: Logger,
    action: String,
    failure: Throwable?,
    defaultMessage: String,
): String {
    val message = failureMessage(failure, defaultMessage)
    logger.info("$APPSTORE_LOG_PREFIX $action failed: $message")
    return message
}

internal fun failureMessage(
    failure: Throwable?,
    defaultMessage: String,
): String {
    val message = failure?.message?.trim()
    return if (message.isNullOrEmpty()) defaultMessage else message
}
