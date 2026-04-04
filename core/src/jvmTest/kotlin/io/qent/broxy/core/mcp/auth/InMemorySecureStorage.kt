package io.qent.broxy.core.mcp.auth

internal class InMemorySecureStorage : SecureStorage {
    override val isAvailable: Boolean = true
    private val data = mutableMapOf<String, String>()

    override fun read(key: String): String? = data[key]

    override fun write(
        key: String,
        value: String,
    ) {
        data[key] = value
    }

    override fun delete(key: String) {
        data.remove(key)
    }
}
