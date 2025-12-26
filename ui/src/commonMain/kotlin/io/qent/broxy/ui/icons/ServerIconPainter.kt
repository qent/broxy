package io.qent.broxy.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

@Composable
expect fun rememberServerIconPainter(iconId: String): Painter
