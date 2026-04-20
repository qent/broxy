package io.qent.broxy.core.utils

import java.util.Locale

internal fun isWindows(): Boolean = System.getProperty("os.name")?.lowercase(Locale.ROOT)?.contains("win") == true

internal fun isMac(): Boolean = System.getProperty("os.name")?.lowercase(Locale.ROOT)?.contains("mac") == true
