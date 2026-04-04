package io.qent.broxy.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

private object ClientIconResourceMarker

@Composable
actual fun rememberClientIconPainter(iconId: String): Painter? {
    val bytes = remember(iconId) { loadIconBytes(iconId) } ?: return null
    val bitmap = remember(bytes) { Image.makeFromEncoded(bytes).toComposeImageBitmap() }
    return remember(bitmap) { BitmapPainter(bitmap, filterQuality = FilterQuality.High) }
}

private fun loadIconBytes(iconId: String): ByteArray? {
    val resourcePath = "/icons/clients/$iconId.png"
    val stream = ClientIconResourceMarker::class.java.getResourceAsStream(resourcePath) ?: return null
    return stream.use { it.readBytes() }
}
