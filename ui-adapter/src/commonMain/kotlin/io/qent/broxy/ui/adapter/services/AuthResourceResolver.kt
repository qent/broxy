package io.qent.broxy.ui.adapter.services

import io.qent.broxy.ui.adapter.models.UiMcpServerConfig

expect fun resolveAuthResourceUrl(config: UiMcpServerConfig): String?
