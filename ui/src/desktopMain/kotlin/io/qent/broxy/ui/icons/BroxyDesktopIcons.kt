package io.qent.broxy.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

private const val APP_ICON_RESOURCE_PATH = "/icons/broxy.png"
private const val TRAY_ICON_RESOURCE_PATH = "/icons/broxy_tray.png"
private const val TRAY_STATUS_DOT_DIAMETER_RATIO = 0.24f
private const val TRAY_STATUS_DOT_INSET_RATIO = 0.05f
private const val TRAY_STATUS_DOT_MIN_SIZE = 8

private object IconResourceMarker

private val appIconBytes: ByteArray by lazy { loadIconBytes(APP_ICON_RESOURCE_PATH) }
private val appIconBitmap: ImageBitmap by lazy { Image.makeFromEncoded(appIconBytes).toComposeImageBitmap() }
private val baseAppIconImage: BufferedImage by lazy { readBufferedImage(appIconBytes, APP_ICON_RESOURCE_PATH) }
private val trayIconBytes: ByteArray by lazy { loadIconBytes(TRAY_ICON_RESOURCE_PATH) }
private val baseTrayIconImage: BufferedImage by lazy { readBufferedImage(trayIconBytes, TRAY_ICON_RESOURCE_PATH) }

@Composable
fun rememberApplicationIconPainter(): Painter = remember { BitmapPainter(appIconBitmap, filterQuality = FilterQuality.High) }

fun createApplicationIconImage(size: Int): BufferedImage = resizeBufferedImage(baseAppIconImage, size)

fun createTrayIconImage(
    size: Int,
    statusDotColor: Color? = null,
): BufferedImage {
    val base = resizeBufferedImage(baseTrayIconImage, size)
    if (statusDotColor == null) return base
    return addStatusDot(base, statusDotColor)
}

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

private fun addStatusDot(
    source: BufferedImage,
    color: Color,
): BufferedImage {
    val image = copyBufferedImage(source)
    val graphics = image.createGraphics()
    val minSide = minOf(image.width, image.height)
    val diameter = (minSide * TRAY_STATUS_DOT_DIAMETER_RATIO).toInt().coerceAtLeast(TRAY_STATUS_DOT_MIN_SIZE)
    val inset = (minSide * TRAY_STATUS_DOT_INSET_RATIO).toInt().coerceAtLeast(1)
    val x = (image.width - diameter - inset).coerceAtLeast(0)
    val y = (image.height - diameter - inset).coerceAtLeast(0)
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    graphics.color = color
    graphics.fillOval(x, y, diameter, diameter)
    graphics.dispose()
    return image
}
