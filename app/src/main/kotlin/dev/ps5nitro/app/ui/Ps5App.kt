package dev.ps5nitro.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import dev.ps5nitro.app.Session

private val TABS = listOf(
    "🔌" to "Connect",
    "🎮" to "Game",
    "🔍" to "Scanner",
    "📋" to "Watch",
    "🧭" to "Trace",
    "🧩" to "Cheats",
)

@Composable
fun Ps5App() {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF4FC3F7),
            secondary = Color(0xFF80CBC4),
            background = Color(0xFF10151C),
            surface = Color(0xFF10151C),
        ),
    ) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    TABS.forEachIndexed { i, (icon, label) ->
                        NavigationBarItem(
                            selected = Session.tab == i,
                            onClick = { Session.tab = i },
                            icon = { Text(icon) },
                            label = { Text(label, maxLines = 1, fontSize = 11.sp) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
                StatusBanner()
                Box(Modifier.weight(1f)) {
                    when (Session.tab) {
                        0 -> ConnectScreen()
                        1 -> GameScreen()
                        2 -> ScanScreen()
                        3 -> WatchScreen()
                        4 -> TraceScreen()
                        else -> CheatScreen()
                    }
                }
            }
        }
    }
}
