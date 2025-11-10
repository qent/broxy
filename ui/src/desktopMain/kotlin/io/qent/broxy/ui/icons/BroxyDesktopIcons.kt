package io.qent.broxy.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter
import java.awt.AlphaComposite
import java.awt.Color as AwtColor
import java.awt.RenderingHints
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import kotlin.math.min

private const val LETTER_B_VIEWPORT = 64f

private sealed interface GlyphShapeSpec {
    data class Rect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) : GlyphShapeSpec

    data class RoundRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val radius: Float
    ) : GlyphShapeSpec
}

private val positiveShapes = listOf(
    GlyphShapeSpec.Rect(left = 12f, top = 6f, right = 26f, bottom = 58f),
    GlyphShapeSpec.RoundRect(left = 22f, top = 6f, right = 56f, bottom = 30f, radius = 12f),
    GlyphShapeSpec.RoundRect(left = 22f, top = 30f, right = 56f, bottom = 58f, radius = 14f)
)

private val negativeShapes = listOf(
    GlyphShapeSpec.RoundRect(left = 30f, top = 14f, right = 48f, bottom = 26f, radius = 7f),
    GlyphShapeSpec.RoundRect(left = 30f, top = 38f, right = 48f, bottom = 50f, radius = 7f)
)

private val letterBPath: Path by lazy { buildLetterBPath() }

@Composable
fun rememberLetterBPainter(isDarkTheme: Boolean): Painter {
    val color = if (isDarkTheme) Color.White else Color.Black
    return remember(color) { LetterBPainter(color) }
}

fun createLetterBImage(fillColor: AwtColor, size: Int): BufferedImage {
    val targetSize = size.coerceAtLeast(1)
    val image = BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    graphics.composite = AlphaComposite.Clear
    graphics.fillRect(0, 0, targetSize, targetSize)
    graphics.composite = AlphaComposite.SrcOver
    graphics.color = fillColor
    graphics.fill(buildLetterBArea(targetSize.toDouble()))
    graphics.dispose()
    return image
}

fun letterBAwtColor(isDarkTheme: Boolean): AwtColor = if (isDarkTheme) AwtColor.WHITE else AwtColor.BLACK

private fun buildLetterBPath(): Path {
    val positiveUnion = positiveShapes
        .map { it.toComposePath() }
        .reduce { acc, path -> Path.combine(PathOperation.Union, acc, path) }
    return negativeShapes.fold(positiveUnion) { acc, spec ->
        Path.combine(PathOperation.Difference, acc, spec.toComposePath())
    }
}

private fun GlyphShapeSpec.toComposePath(): Path {
    return Path().apply {
        when (this@toComposePath) {
            is GlyphShapeSpec.Rect -> addRect(Rect(left, top, right, bottom))
            is GlyphShapeSpec.RoundRect -> addRoundRect(
                RoundRect(left, top, right, bottom, radius, radius)
            )
        }
    }
}

private fun buildLetterBArea(targetSize: Double): Area {
    val scale = targetSize / LETTER_B_VIEWPORT
    val area = Area()
    positiveShapes.forEach { shape -> area.add(shape.toArea(scale)) }
    negativeShapes.forEach { shape -> area.subtract(shape.toArea(scale)) }
    return area
}

private fun GlyphShapeSpec.toArea(scale: Double): Area {
    return when (this) {
        is GlyphShapeSpec.Rect -> {
            val rect = Rectangle2D.Double(
                left * scale,
                top * scale,
                (right - left) * scale,
                (bottom - top) * scale
            )
            Area(rect)
        }
        is GlyphShapeSpec.RoundRect -> {
            val rect = RoundRectangle2D.Double(
                left * scale,
                top * scale,
                (right - left) * scale,
                (bottom - top) * scale,
                radius * 2 * scale,
                radius * 2 * scale
            )
            Area(rect)
        }
    }
}

private class LetterBPainter(private val fillColor: Color) : Painter() {
    override val intrinsicSize: Size = Size.Unspecified

    override fun DrawScope.onDraw() {
        val minDimension = min(size.width, size.height)
        if (minDimension <= 0f) return
        val scale = minDimension / LETTER_B_VIEWPORT
        val dx = (size.width - LETTER_B_VIEWPORT * scale) / 2f
        val dy = (size.height - LETTER_B_VIEWPORT * scale) / 2f
        withTransform({
            translate(dx, dy)
            scale(scale, scale)
        }) {
            drawPath(letterBPath, color = fillColor)
        }
    }
}
