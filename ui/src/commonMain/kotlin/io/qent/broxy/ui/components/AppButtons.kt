import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.theme.AppTheme

/**
 * Shared button colors and wrappers to keep button styling consistent across the app.
 */
object AppButtonDefaults {
    private val DangerRed = Color(0xFFDC2626)

    @Composable
    fun primaryColors(): ButtonColors =
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f),
        )

    @Composable
    fun secondaryColors(): ButtonColors =
        ButtonDefaults.outlinedButtonColors(
            // Transparent/Surface
            containerColor = MaterialTheme.colorScheme.surface,
            // Accent color text
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = MaterialTheme.colorScheme.surface,
            disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
        )

    @Composable
    fun dangerColors(): ButtonColors =
        ButtonDefaults.buttonColors(
            containerColor = DangerRed,
            contentColor = Color.White,
            disabledContainerColor = DangerRed.copy(alpha = 0.5f),
            disabledContentColor = Color.White.copy(alpha = 0.7f),
        )
}

@Composable
fun AppPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = AppTheme.shapes.button,
        colors = AppButtonDefaults.primaryColors(),
        content = content,
    )
}

@Composable
fun AppSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = AppTheme.shapes.button,
        colors = AppButtonDefaults.secondaryColors(),
        border =
            BorderStroke(
                1.dp,
                if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            ),
        content = content,
    )
}

@Composable
fun AppDangerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = AppTheme.shapes.button,
        colors = AppButtonDefaults.dangerColors(),
        content = content,
    )
}
