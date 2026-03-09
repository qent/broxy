package io.qent.broxy.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.qent.broxy.ui.adapter.models.UiServerIcon
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private object ServerIconResourceMarker

@Composable
actual fun rememberServerIconPainter(icon: UiServerIcon): Painter =
    when (icon) {
        is UiServerIcon.Asset -> rememberAssetPainter(icon.id)
        is UiServerIcon.Custom -> rememberFilePainter(icon.path)
        UiServerIcon.Default -> ColorPainter(Color.Transparent)
    }

@Composable
private fun rememberAssetPainter(iconId: String): Painter {
    val bytes = remember(iconId) { loadIconBytes(iconId) }
    val bitmap = remember(bytes) { Image.makeFromEncoded(bytes).toComposeImageBitmap() }
    return remember(bitmap) { BitmapPainter(bitmap, filterQuality = FilterQuality.High) }
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

private fun loadIconBytes(iconId: String): ByteArray {
    val resourcePath = "/icons/servers/$iconId.png"
    val stream =
        ServerIconResourceMarker::class.java.getResourceAsStream(resourcePath)
            ?: error("Server icon resource not found: $resourcePath")
    return stream.use { it.readBytes() }
}

private fun loadFileBytes(iconPath: String): ByteArray? {
    val resolved = resolveIconPath(iconPath) ?: return null
    return runCatching { Files.readAllBytes(resolved) }.getOrNull()
}

private fun resolveIconPath(iconPath: String): Path? {
    val trimmed = iconPath.trim()
    if (trimmed.isEmpty()) return null
    val path = Paths.get(trimmed)
    val resolved = if (path.isAbsolute) path else defaultConfigDir().resolve(path)
    return resolved.normalize()
}

private fun defaultConfigDir(): Path = Paths.get(System.getProperty("user.home"), ".config", "broxy")
