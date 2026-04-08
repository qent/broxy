package io.qent.broxy.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.qent.broxy.ui.adapter.icons.RemoteServerIconCache
import io.qent.broxy.ui.adapter.models.UiServerIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val remoteIconCache = RemoteServerIconCache()

@Composable
actual fun rememberServerIconPainter(icon: UiServerIcon): Painter =
    when (icon) {
        is UiServerIcon.Custom -> rememberFilePainter(icon.path)
        is UiServerIcon.Remote -> rememberRemotePainter(icon.url)
        UiServerIcon.Default -> ColorPainter(Color.Transparent)
    }

@Composable
private fun rememberFilePainter(iconPath: String): Painter {
    val bytes = remember(iconPath) { loadFileBytes(iconPath) }
    val bitmap =
        remember(bytes) {
            bytes?.let { Image.makeFromEncoded(it).toComposeImageBitmap() }
        }
    return if (bitmap == null) {
        ColorPainter(Color.Transparent)
    } else {
        remember(bitmap) { BitmapPainter(bitmap, filterQuality = FilterQuality.High) }
    }
}

@Composable
private fun rememberRemotePainter(url: String): Painter {
    val bytes by
        produceState<ByteArray?>(initialValue = null, key1 = url) {
            value =
                withContext(Dispatchers.IO) {
                    loadRemoteBytes(url)
                }
        }
    val bitmap =
        remember(bytes) {
            bytes?.let { Image.makeFromEncoded(it).toComposeImageBitmap() }
        }
    return if (bitmap == null) {
        ColorPainter(Color.Transparent)
    } else {
        remember(bitmap) { BitmapPainter(bitmap, filterQuality = FilterQuality.High) }
    }
}

private fun loadFileBytes(iconPath: String): ByteArray? {
    val resolved = resolveIconPath(iconPath) ?: return null
    return runCatching { Files.readAllBytes(resolved) }.getOrNull()
}

private fun loadRemoteBytes(url: String): ByteArray? = remoteIconCache.load(url)

private fun resolveIconPath(iconPath: String): Path? {
    val trimmed = iconPath.trim()
    if (trimmed.isEmpty()) return null
    val path = Paths.get(trimmed)
    val resolved = if (path.isAbsolute) path else defaultConfigDir().resolve(path)
    return resolved.normalize()
}

private fun defaultConfigDir(): Path = Paths.get(System.getProperty("user.home"), ".config", "broxy")
