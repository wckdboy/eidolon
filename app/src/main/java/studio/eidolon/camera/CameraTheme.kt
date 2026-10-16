package studio.eidolon.camera

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val Ink = Color(0xFF080A0C)
internal val SurfaceInk = Color(0xFF15191D)
internal val EdgeInk = Color(0xFF2B3137)
internal val Paper = Color(0xFFF4F6F7)
internal val Muted = Color(0xFFA3ADB6)
internal val Acid = Color(0xFFD6FF75)
internal val Peach = Color(0xFFFFAF91)

@Composable
internal fun EidolonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Acid, background = Ink, surface = SurfaceInk,
        onPrimary = Ink, onBackground = Paper, onSurface = Paper, onSurfaceVariant = Muted), content = content)
}
