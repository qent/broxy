package io.qent.broxy.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.skia.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

private const val APP_ICON_RESOURCE_PATH = "/icons/broxy.png"
private const val TRAY_ICON_RESOURCE_PATH = "/icons/broxy_tray.png"

private object IconResourceMarker

private val appIconBytes: ByteArray by lazy { loadIconBytes(APP_ICON_RESOURCE_PATH) }
private val appIconBitmap: ImageBitmap by lazy { Image.makeFromEncoded(appIconBytes).asImageBitmap() }
private val baseAppIconImage: BufferedImage by lazy { readBufferedImage(appIconBytes, APP_ICON_RESOURCE_PATH) }
private val trayIconBytes: ByteArray by lazy { loadIconBytes(TRAY_ICON_RESOURCE_PATH) }
private val baseTrayIconImage: BufferedImage by lazy { readBufferedImage(trayIconBytes, TRAY_ICON_RESOURCE_PATH) }

@Composable
fun rememberApplicationIconPainter(): Painter {
    return remember { BitmapPainter(appIconBitmap, filterQuality = FilterQuality.High) }
}

fun createApplicationIconImage(size: Int): BufferedImage = resizeBufferedImage(baseAppIconImage, size)

fun createTrayIconImage(size: Int): BufferedImage = resizeBufferedImage(baseTrayIconImage, size)

private fun loadIconBytes(resourcePath: String): ByteArray {
    val stream =
        IconResourceMarker::class.java.getResourceAsStream(resourcePath)
            ?: error("Icon resource not found: $resourcePath")
    return stream.use { it.readBytes() }
}

private fun readBufferedImage(
    bytes: ByteArray,
    resourcePath: String,
): BufferedImage {
    ByteArrayInputStream(bytes).use { input ->
        return ImageIO.read(input)
            ?: error("Unable to decode icon resource: $resourcePath")
    }
}

private fun resizeBufferedImage(
    source: BufferedImage,
    size: Int,
): BufferedImage {
    val targetSize = size.coerceAtLeast(1)
    if (targetSize == source.width && source.width == source.height) {
        return copyBufferedImage(source)
    }
    if (targetSize == source.width) {
        return copyBufferedImage(source)
    }
    return scaleBufferedImage(source, targetSize)
}

private fun copyBufferedImage(source: BufferedImage): BufferedImage {
    val copy = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = copy.createGraphics()
    graphics.drawImage(source, 0, 0, null)
    graphics.dispose()
    return copy
}

private fun scaleBufferedImage(
    source: BufferedImage,
    size: Int,
): BufferedImage {
    val scaled = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val graphics = scaled.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    graphics.drawImage(source, 0, 0, size, size, null)
    graphics.dispose()
    return scaled
}
