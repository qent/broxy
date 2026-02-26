@file:Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod", "MagicNumber")

package io.qent.broxy.ui.liquidglass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

@Composable
@Suppress("LongParameterList")
fun GlassSurface(
    modifier: Modifier = Modifier,
    variant: GlassSurfaceVariant = GlassSurfaceVariant.Regular,
    shape: Shape,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    enabled: Boolean = true,
    border: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val config = LocalGlassConfig.current
    val tokens = config.tokens

    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()

    val baseAlpha =
        when {
            !config.glassEnabled -> 1f
            config.reduceTransparency -> tokens.reducedTransparencyAlpha
            variant == GlassSurfaceVariant.Clear -> tokens.clearAlpha
            else -> tokens.regularAlpha
        }

    val targetAlpha =
        when {
            !enabled -> baseAlpha * 0.8f
            pressed -> (baseAlpha + tokens.pressedBoost).coerceAtMost(1f)
            hovered -> (baseAlpha + tokens.hoverBoost).coerceAtMost(1f)
            else -> baseAlpha
        }

    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = if (config.reduceMotion) tween(0) else tween(tokens.animationMillis),
        label = "glassSurfaceAlpha",
    )

    val borderStroke =
        border ?: BorderStroke(
            width = tokens.strokeWidth,
            color =
                MaterialTheme.colorScheme.outline.copy(
                    alpha = if (config.glassEnabled && !config.reduceTransparency) 0.45f else 0.82f,
                ),
        )

    val interactionModifier =
        if (onClick == null) {
            Modifier
        } else {
            Modifier
                .hoverable(interactionSource = interactionSource, enabled = enabled)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
        }

    Surface(
        modifier = modifier.then(interactionModifier),
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = animatedAlpha),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = borderStroke,
        tonalElevation = 0.dp,
        shadowElevation = if (config.glassEnabled) tokens.shadowElevation else 0.dp,
    ) {
        Box(modifier = Modifier.padding(contentPadding), content = content)
    }
}
