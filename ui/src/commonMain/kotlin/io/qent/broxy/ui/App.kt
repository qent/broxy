package io.qent.broxy.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.qent.broxy.ui.strings.LocalStrings

@Composable
fun BroxyApp() {
    val strings = LocalStrings.current
    MaterialTheme {
        Surface {
            Text(strings.appGreeting)
        }
    }
}
