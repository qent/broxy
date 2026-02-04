package io.qent.broxy.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import kotlin.math.max
import kotlin.math.roundToInt

private object NavIconResourceMarker

private const val NAV_ICON_PROBE_SIZE = 256
private const val NAV_ICON_PADDING_SCALE = 0.98f

private data class SvgBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val probeSize: Int,
) {
    val width: Int = (right - left + 1).coerceAtLeast(1)
    val height: Int = (bottom - top + 1).coerceAtLeast(1)
}

@Composable
actual fun rememberNavIconPainter(
    iconId: String,
    size: Dp,
    scale: Float,
): Painter? {
    val density = LocalDensity.current
    val sizePx =
        remember(
            iconId,
            size,
            density,
        ) {
            with(density) { size.toPx().roundToInt().coerceAtLeast(1) }
        }
    val svgDom = remember(iconId) { loadSvgDom(iconId) } ?: return null
    val bounds = remember(iconId) { measureSvgBounds(svgDom, NAV_ICON_PROBE_SIZE) }
    val bitmap = remember(iconId, sizePx, scale, bounds) { renderSvgToBitmap(svgDom, sizePx, scale, bounds) }
    return remember(bitmap) { BitmapPainter(bitmap, filterQuality = FilterQuality.High) }
}

private fun loadSvgDom(iconId: String): SVGDOM? {
    val resourcePath = "/icons/nav/$iconId.svg"
    val bytes =
        NavIconResourceMarker::class.java.getResourceAsStream(resourcePath)?.use { it.readBytes() }
            ?: return null
    return SVGDOM(Data.makeFromBytes(bytes))
}

private fun renderSvgToBitmap(
    svgDom: SVGDOM,
    sizePx: Int,
    scale: Float,
    bounds: SvgBounds?,
): ImageBitmap {
    val safeSize = sizePx.coerceAtLeast(1)
    val safeScale = scale.coerceAtLeast(0.1f)
    val image =
        Surface.makeRasterN32Premul(safeSize, safeSize).use { surface ->
            surface.canvas.clear(0x00000000)
            if (bounds == null) {
                svgDom.setContainerSize(safeSize.toFloat(), safeSize.toFloat())
                svgDom.render(surface.canvas)
            } else {
                val scaleToFit =
                    safeSize * NAV_ICON_PADDING_SCALE * safeScale /
                        max(bounds.width.toFloat(), bounds.height.toFloat())
                val offsetX = (safeSize - bounds.width * scaleToFit) / 2f - bounds.left * scaleToFit
                val offsetY = (safeSize - bounds.height * scaleToFit) / 2f - bounds.top * scaleToFit
                svgDom.setContainerSize(bounds.probeSize.toFloat(), bounds.probeSize.toFloat())
                surface.canvas.save()
                surface.canvas.translate(offsetX, offsetY)
                surface.canvas.scale(scaleToFit, scaleToFit)
                svgDom.render(surface.canvas)
                surface.canvas.restore()
            }
            surface.makeImageSnapshot()
        }
    return image.toComposeImageBitmap()
}

private fun measureSvgBounds(
    svgDom: SVGDOM,
    probeSize: Int,
): SvgBounds? {
    val safeProbeSize = probeSize.coerceAtLeast(1)
    val image =
        Surface.makeRasterN32Premul(safeProbeSize, safeProbeSize).use { surface ->
            surface.canvas.clear(0x00000000)
            svgDom.setContainerSize(safeProbeSize.toFloat(), safeProbeSize.toFloat())
            svgDom.render(surface.canvas)
            surface.makeImageSnapshot()
        }
    val info = ImageInfo(safeProbeSize, safeProbeSize, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
    val pixels =
        Bitmap.makeFromImage(image).use { bitmap ->
            bitmap.readPixels(info, info.minRowBytes, 0, 0)
        } ?: return null
    var minX = safeProbeSize
    var minY = safeProbeSize
    var maxX = -1
    var maxY = -1
    val rowBytes = info.minRowBytes
    for (y in 0 until safeProbeSize) {
        val rowStart = y * rowBytes
        for (x in 0 until safeProbeSize) {
            val alpha = pixels[rowStart + x * 4 + 3].toInt() and 0xFF
            if (alpha != 0) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
    }
    return if (maxX < 0 || maxY < 0) {
        null
    } else {
        SvgBounds(minX, minY, maxX, maxY, safeProbeSize)
    }
}
