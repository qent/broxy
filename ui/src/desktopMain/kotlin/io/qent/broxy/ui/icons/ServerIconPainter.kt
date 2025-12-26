package io.qent.broxy.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

private object ServerIconResourceMarker

@Composable
actual fun rememberServerIconPainter(iconId: String): Painter {
    val bytes = remember(iconId) { loadIconBytes(iconId) }
    val bitmap = remember(bytes) { Image.makeFromEncoded(bytes).toComposeImageBitmap() }
    return remember(bitmap) { BitmapPainter(bitmap, filterQuality = FilterQuality.High) }
}

private fun loadIconBytes(iconId: String): ByteArray {
    val resourcePath = "/icons/servers/$iconId.png"
    val stream =
        ServerIconResourceMarker::class.java.getResourceAsStream(resourcePath)
            ?: error("Server icon resource not found: $resourcePath")
    return stream.use { it.readBytes() }
}
