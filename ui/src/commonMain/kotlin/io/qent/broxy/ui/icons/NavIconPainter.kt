package io.qent.broxy.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp

@Composable
expect fun rememberNavIconPainter(
    iconId: String,
    size: Dp,
    scale: Float = 1f,
): Painter?
