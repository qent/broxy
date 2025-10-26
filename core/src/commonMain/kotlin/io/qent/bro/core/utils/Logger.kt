package io.qent.bro.core.utils

interface Logger {
    fun debug(message: String)
    fun info(message: String)
    fun warn(message: String, throwable: Throwable? = null)
    fun error(message: String, throwable: Throwable? = null)
}

object ConsoleLogger : Logger {
    override fun debug(message: String) { println("[DEBUG] $message") }
    override fun info(message: String) { println("[INFO] $message") }
    override fun warn(message: String, throwable: Throwable?) {
        println("[WARN] $message" + (throwable?.let { ": ${it.message}" } ?: ""))
    }
    override fun error(message: String, throwable: Throwable?) {
        println("[ERROR] $message" + (throwable?.let { ": ${it.message}" } ?: ""))
    }
}

