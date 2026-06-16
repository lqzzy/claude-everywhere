package com.remote.claude.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// d8 "translucent liquid" light warm-tone palette (ported from the old app/src/theme.ts).
object C {
    val ink = Color(0xFF2E2823)
    val dim = Color(0xFFA0958A)
    val subtle = Color(0xFF6F6053)
    val accent = Color(0xFFC96442)
    val accent2 = Color(0xFFE58263) // bright end of the gradient
    val ok = Color(0xFF5A8A5A)
    val warn = Color(0xFFC98A22)
    val err = Color(0xFFC0392B)

    val bg = Color(0xFFFFF8F3)
    // glass
    val glassBg = Color(0x6BFFFFFF)       // ~rgba(255,255,255,.42)
    val glassBgStrong = Color(0x99FFFFFF) // ~.6
    val glassBorder = Color(0xE6FFFFFF)   // ~.9

    // markdown / diff
    val inlBg = Color(0x1AC96442)
    val inlText = Color(0xFFB0512F)
    val del = Color(0xFFB0413E)
    val delBg = Color(0x1AC8463C)
    val add = Color(0xFF3F7A3F)
    val addBg = Color(0x2150A05A)
}

// Coral gradient (user bubble / send button / orb)
val Coral = listOf(Color(0xFFE58263), Color(0xFFC96442))

@Composable
fun ClaudeRemoteTheme(content: @Composable () -> Unit) {
    val scheme = lightColorScheme(
        primary = C.accent,
        background = C.bg,
        surface = C.bg,
        onPrimary = Color.White,
        onBackground = C.ink,
        onSurface = C.ink,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
