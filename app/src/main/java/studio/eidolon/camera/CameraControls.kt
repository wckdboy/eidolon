package studio.eidolon.camera

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToInt

@Composable
internal fun ProControls(state: CameraState, settings: CaptureSettings, selected: String?, accent: Color, onSelect: (String) -> Unit, onChange: (CaptureSettings) -> Unit) {
    val info = state.info
    val manual = settings.iso != null || settings.shutterNs != null
    fun supported(control: String) = when (control) {
        "EV" -> !manual && info.evMin < info.evMax
        "FOCUS" -> info.focusMax > 0
        else -> info.manual
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("EV", "ISO", "SHUTTER", "FOCUS").forEach { control ->
                val enabled = !state.busy && supported(control)
                Column(Modifier.weight(1f).heightIn(min = 58.dp).clip(RoundedCornerShape(14.dp))
                    .glass(RoundedCornerShape(14.dp), settings.look == Look.BLOOM)
                    .background(if (selected == control) accent.copy(alpha = 0.10f) else Color.Transparent)
                    .border(1.dp, if (selected == control) accent.copy(alpha = 0.42f) else EdgeInk, RoundedCornerShape(14.dp))
                    .selectable(selected = selected == control, enabled = enabled, role = Role.Tab) { onSelect(control) }
                    .padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    MicroText(control, if (enabled) if (selected == control) accent else Muted else Muted.copy(alpha = 0.35f))
                    Text(when (control) {
                        "EV" -> if (manual) "—" else "%+.1f".format(java.util.Locale.US, settings.exposure * info.evStep)
                        "ISO" -> settings.iso?.toString() ?: "Auto"
                        "SHUTTER" -> settings.shutterNs?.let(CaptureMath::shutterLabel) ?: "Auto"
                        else -> if (settings.focus == null) "Auto" else "%.1f D".format(java.util.Locale.US, settings.focus)
                    }, color = if (enabled) Paper else Muted.copy(alpha = 0.35f), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        if (selected != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (supported(selected)) {
                    val value = when (selected) {
                        "EV" -> (settings.exposure - info.evMin).toFloat() / (info.evMax - info.evMin)
                        "ISO" -> normalized((settings.iso ?: state.iso).toDouble(), info.isoMin.toDouble(), info.isoMax.toDouble())
                        "SHUTTER" -> normalized((settings.shutterNs ?: state.shutterNs).toDouble(), info.shutterMin.toDouble(), info.shutterMax.toDouble())
                        else -> (settings.focus ?: 0f) / info.focusMax
                    }.coerceIn(0f, 1f)
                    val spokenValue = when (selected) {
                        "EV" -> "%+.1f EV".format(java.util.Locale.US, settings.exposure * info.evStep)
                        "ISO" -> "${settings.iso ?: state.iso} ISO" + if (settings.iso == null) ", automatic" else ""
                        "SHUTTER" -> (settings.shutterNs ?: state.shutterNs).let { ns ->
                            if (ns <= 0) "Automatic shutter" else "${CaptureMath.shutterLabel(ns).removeSuffix("s")} seconds" +
                                if (settings.shutterNs == null) ", automatic" else ""
                        }
                        else -> settings.focus?.let { "%.1f diopters".format(java.util.Locale.US, it) } ?: "Automatic focus"
                    }
                    Box(Modifier.weight(1f)) {
                        PrecisionSlider(value, "$selected adjustment", spokenValue, accent, !state.busy,
                            steps = if (selected == "EV") (info.evMax - info.evMin - 1).coerceAtLeast(0) else 0) { v ->
                            onChange(when (selected) {
                                "EV" -> settings.copy(exposure = (info.evMin + v * (info.evMax - info.evMin)).roundToInt())
                                "ISO" -> settings.copy(iso = exponential(v, info.isoMin.toDouble(), info.isoMax.toDouble()).roundToInt(), shutterNs = settings.shutterNs ?: state.shutterNs.coerceIn(info.shutterMin, info.shutterMax))
                                "SHUTTER" -> settings.copy(shutterNs = exponential(v, info.shutterMin.toDouble(), info.shutterMax.toDouble()).toLong(), iso = settings.iso ?: state.iso.coerceIn(info.isoMin, info.isoMax))
                                else -> settings.copy(focus = v * info.focusMax)
                            })
                        }
                    }
                } else Text("Use Auto for EV", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(enabled = !state.busy, onClick = { onChange(settings.copy(iso = null, shutterNs = null, focus = null, exposure = 0)) }) {
                    Text("Reset", color = accent, fontSize = 12.sp)
                }
            }
        }
    }
}

private fun normalized(value: Double, min: Double, max: Double): Float =
    if (max <= min) 0f else (ln(value.coerceIn(min, max) / min) / ln(max / min)).toFloat()
private fun exponential(value: Float, min: Double, max: Double): Double = min * exp(value * ln(max / min))

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PrecisionSlider(value: Float, label: String, spokenValue: String, accent: Color, enabled: Boolean, steps: Int = 0, onChange: (Float) -> Unit) {
    Slider(value = value, onValueChange = onChange, enabled = enabled, steps = steps,
        modifier = Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = label; stateDescription = spokenValue },
        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = EdgeInk),
        thumb = { Box(Modifier.size(16.dp).background(if (enabled) accent else Muted, CircleShape)) },
        track = { slider -> SliderDefaults.Track(sliderState = slider, modifier = Modifier.height(3.dp),
            colors = SliderDefaults.colors(activeTrackColor = accent, inactiveTrackColor = EdgeInk),
            enabled = enabled, drawStopIndicator = null, thumbTrackGapSize = 4.dp) })
}

@Composable internal fun MicroText(text: String, color: Color) {
    Text(text, color = color, fontSize = 10.sp, letterSpacing = 0.7.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
}

@Composable internal fun Capability(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp); Text(value, color = Acid, fontSize = 12.sp)
    }
    HorizontalDivider(color = EdgeInk)
}

@Composable internal fun GalleryButton(uri: String?, enabled: Boolean = true, onOpen: () -> Unit) {
    val context = LocalContext.current
    val thumbnail by produceState<android.graphics.Bitmap?>(null, uri) {
        value = null
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(Uri.parse(uri), android.util.Size(120, 120), null) }.getOrNull()
        }
    }
    Box(Modifier.size(52.dp).glass(RoundedCornerShape(16.dp))
        .semantics { contentDescription = "Open gallery" }
        .clickable(enabled = enabled, role = Role.Button, onClick = onOpen), contentAlignment = Alignment.Center) {
        thumbnail?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
            ?: Glyph("gallery", Muted)
    }
}

@Composable internal fun Glyph(kind: String, color: Color, modifier: Modifier = Modifier,
    description: String? = when (kind) { "grid" -> "Toggle composition grid"; "flip" -> "Switch camera"; "info" -> "About capture modes"; else -> null }) {
    Canvas(modifier.size(22.dp).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)) {
        val w = size.width; val h = size.height; val stroke = 1.4.dp.toPx()
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(w*x,h*y), Offset(w*x2,h*y2), stroke, StrokeCap.Round)
        when (kind) {
            "mark", "pure" -> {
                drawCircle(color, w*0.39f, style = Stroke(stroke))
                if (kind == "mark") { line(.37f,.5f,.63f,.5f); line(.5f,.37f,.5f,.63f) }
                else drawCircle(color, w*.07f)
            }
            "close" -> { line(.25f,.25f,.75f,.75f); line(.25f,.75f,.75f,.25f) }
            "back" -> { line(.75f,.5f,.2f,.5f); line(.45f,.25f,.2f,.5f); line(.45f,.75f,.2f,.5f) }
            "arrow" -> { line(.15f,.5f,.85f,.5f); line(.6f,.25f,.85f,.5f); line(.6f,.75f,.85f,.5f) }
            "grid" -> { line(.33f,.1f,.33f,.9f); line(.67f,.1f,.67f,.9f); line(.1f,.33f,.9f,.33f); line(.1f,.67f,.9f,.67f) }
            "info" -> { drawCircle(color, w*.4f, style = Stroke(stroke)); line(.5f,.48f,.5f,.7f); drawCircle(color, stroke*.65f, Offset(w*.5f,h*.3f)) }
            "bloom" -> { for (n in 0..7) { val a = n * Math.PI / 4; line((.5 + .18*kotlin.math.cos(a)).toFloat(),(.5 + .18*kotlin.math.sin(a)).toFloat(),(.5 + .43*kotlin.math.cos(a)).toFloat(),(.5 + .43*kotlin.math.sin(a)).toFloat()) } }
            "flip" -> { drawArc(color, 30f, 270f, false, Offset(w*.13f,h*.13f), androidx.compose.ui.geometry.Size(w*.74f,h*.74f), style = Stroke(stroke)); line(.72f,.1f,.72f,.35f); line(.72f,.35f,.95f,.3f) }
            "gallery" -> { drawRoundRect(color, Offset(w*.12f,h*.12f), androidx.compose.ui.geometry.Size(w*.76f,h*.76f), androidx.compose.ui.geometry.CornerRadius(w*.1f), style = Stroke(stroke)); line(.22f,.72f,.43f,.48f); line(.43f,.48f,.62f,.66f); line(.62f,.66f,.75f,.56f); drawCircle(color,w*.06f,Offset(w*.67f,h*.33f)) }
        }
    }
}
