package studio.eidolon.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val Ink = Color(0xFF090909)
internal val SurfaceInk = Color(0xFF191919)
internal val EdgeInk = Color(0xFF333333)
internal val Paper = Color(0xFFF4F6F7)
internal val Muted = Color(0xFFAAAAAF)
internal val Acid = Color(0xFFF4F4F6)
internal val Peach = Color(0xFFFFAF91)

@Composable
internal fun EidolonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Acid, background = Ink, surface = SurfaceInk,
        onPrimary = Ink, onBackground = Paper, onSurface = Paper, onSurfaceVariant = Muted), content = content)
}

internal val BloomViolet = Color(0xFFCEB7FF)
internal val BloomPink = Color(0xFFFF77AA)
internal val BloomBlue = Color(0xFF80DFFF)

/** A translucent, lit surface; no per-frame backdrop blur or offscreen camera copies. */
internal fun androidx.compose.ui.Modifier.glass(shape: androidx.compose.ui.graphics.Shape, vivid: Boolean = false): androidx.compose.ui.Modifier =
    this.clip(shape).background(androidx.compose.ui.graphics.Brush.linearGradient(
        if (vivid) listOf(BloomViolet.copy(alpha = 0.20f), BloomPink.copy(alpha = 0.08f), BloomBlue.copy(alpha = 0.12f))
        else listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.035f), Color.White.copy(alpha = 0.065f))))
        .border(0.75.dp, androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.07f))), shape)
