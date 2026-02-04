@file:Suppress("FunctionNaming")

package io.qent.broxy.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import io.qent.broxy.ui.adapter.models.UiServerIcon

@Composable
expect fun rememberServerIconPainter(icon: UiServerIcon): Painter
