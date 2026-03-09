package io.qent.broxy.ui.adapter.store.internal

internal fun httpEndpointFor(port: Int): String = "http://localhost:${clampPort(port)}/mcp"
