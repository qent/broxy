package io.qent.broxy.ui.adapter.data

import java.nio.file.Path
import java.nio.file.Paths

internal fun defaultConfigDir(): Path = Paths.get(System.getProperty("user.home"), ".config", "broxy")
