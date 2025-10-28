package io.qent.bro.core.utils

/**
 * Logger implementation that writes to stderr. Use when STDIO is occupied
 * by the MCP protocol to avoid corrupting the message stream.
 */
object StdErrLogger : Logger {
    private fun errln(text: String) {
        System.err.println(text)
    }

    override fun debug(message: String) { errln("[DEBUG] $message") }
    override fun info(message: String) { errln("[INFO] $message") }
    override fun warn(message: String, throwable: Throwable?) {
        errln("[WARN] $message" + (throwable?.let { ": ${it.message}" } ?: ""))
    }
    override fun error(message: String, throwable: Throwable?) {
        errln("[ERROR] $message" + (throwable?.let { ": ${it.message}" } ?: ""))
    }
}

