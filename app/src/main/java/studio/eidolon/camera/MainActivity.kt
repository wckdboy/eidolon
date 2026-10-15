package studio.eidolon.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToInt

private val Ink = Color(0xFF111310)
private val Paper = Color(0xFFF1F0E8)
private val Muted = Color(0xFF969B90)
private val Acid = Color(0xFFDFFF8A)
private val Peach = Color(0xFFFFB798)

class MainActivity : ComponentActivity() {
    private var cameraState by mutableStateOf(CameraState())
    private lateinit var engine: CameraEngine
    private lateinit var orientation: OrientationEventListener
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine = CameraEngine(applicationContext) { cameraState = it }
        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(value: Int) {
                if (value != ORIENTATION_UNKNOWN) engine.setOrientation(((value + 45) / 90 * 90) % 360)
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Acid, background = Ink, surface = Ink, onSurface = Paper)) {
                EidolonScreen(cameraState, engine)
            }
        }
    }
    override fun onResume() { super.onResume(); engine.start(); orientation.enable() }
    override fun onPause() { orientation.disable(); engine.stop(); super.onPause() }
    override fun onDestroy() { engine.destroy(); super.onDestroy() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EidolonScreen(state: CameraState, engine: CameraEngine) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val haptic = LocalHapticFeedback.current
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var settings by remember { mutableStateOf(CaptureSettings()) }
    var workspace by rememberSaveable { mutableStateOf(Workspace.FUN) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var activeControl by rememberSaveable { mutableStateOf("EV") }
    val accent by animateColorAsState(if (settings.look == Look.PURE) Acid else Peach, label = "mood")
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it; asked = true
        if (it) engine.start()
    }
    // Refresh permission after returning from Android Settings.
    DisposableEffect(activity) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                granted = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    fun change(value: CaptureSettings) { settings = value; engine.update(value) }
    Column(Modifier.fillMaxSize().background(Ink).safeDrawingPadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(70.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Glyph("mark", Paper, Modifier.size(24.dp))
                Spacer(Modifier.width(9.dp))
                Text("eidolon", color = Paper, fontSize = 27.sp, letterSpacing = (-1).sp, fontWeight = FontWeight.Medium)
            }
            Row(Modifier.clip(CircleShape).background(Color(0xFF242722)).padding(4.dp)) {
                Workspace.entries.forEach { mode ->
                    Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 12.sp,
                        color = if (workspace == mode) Ink else Muted,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(CircleShape).background(if (workspace == mode) Paper else Color.Transparent)
                            .clickable(enabled = !state.busy, role = Role.Tab) {
                                workspace = mode
                                if (mode == Workspace.FUN) change(settings.copy(iso = null, shutterNs = null, focus = null))
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }.padding(horizontal = 17.dp, vertical = 9.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { details = true }, modifier = Modifier.size(44.dp)) { Glyph("info", Muted) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        val ratio = state.info.aspectRatio
        val frameWidth = minOf(maxWidth, maxHeight * ratio)
        Box(Modifier.width(frameWidth).aspectRatio(ratio).clip(RoundedCornerShape(24.dp)).background(Color(0xFF20271F))) {
            if (granted) AndroidView(factory = { TextureView(it).also(engine::attach) }, modifier = Modifier.fillMaxSize())
            if (state.ready && grid) Canvas(Modifier.fillMaxSize()) {
                for (n in 1..2) {
                    drawLine(Color.White.copy(alpha = 0.24f), Offset(size.width * n / 3, 0f), Offset(size.width * n / 3, size.height), 1.dp.toPx())
                    drawLine(Color.White.copy(alpha = 0.24f), Offset(0f, size.height * n / 3), Offset(size.width, size.height * n / 3), 1.dp.toPx())
                }
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.6f)))))
            if (!granted) {
                Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LensArt(accent)
                    Spacer(Modifier.height(28.dp))
                    Text("See it your way.", color = Paper, fontSize = 29.sp, letterSpacing = (-1).sp)
                    Spacer(Modifier.height(12.dp))
                    Text("Reality, untouched.\nOr a little more extraordinary.", color = Muted, fontSize = 14.sp,
                        lineHeight = 22.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = {
                        if (asked && !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        } else permission.launch(Manifest.permission.CAMERA)
                    }, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink)) {
                        Text(if (asked && !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) "Allow camera in Settings" else "Open your camera",
                            modifier = Modifier.padding(6.dp), fontWeight = FontWeight.SemiBold)
                    }
                    Text("Just your camera. Everything stays on device.", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 14.dp))
                }
            } else if (!state.ready) {
                Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!state.error) CircularProgressIndicator(color = accent, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    Text(state.message, color = Paper, modifier = Modifier.padding(16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    if (state.error) Button(onClick = { engine.start() }) { Text("Retry") }
                }
            }
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(CircleShape).background(Ink.copy(alpha = 0.65f)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).background(accent, CircleShape))
                    Spacer(Modifier.width(7.dp))
                    MicroText(if (!state.ready) "STILL / 01" else if (settings.look == Look.PURE && state.info.raw) "RAW + JPEG" else "JPEG", Paper)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { grid = !grid }, modifier = Modifier.size(44.dp).background(Ink.copy(alpha = 0.65f), CircleShape)) {
                    Glyph("grid", if (grid) accent else Paper)
                }
            }
            if (state.ready) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
                    if (workspace == Workspace.PRO) {
                        ProControls(state, settings, activeControl, accent, onSelect = { activeControl = it }, onChange = ::change)
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                            Column {
                                MicroText(if (settings.look == Look.PURE) "LESS, BUT TRUE." else "LET THE WORLD BLOOM.", Paper)
                                Text(if (settings.look == Look.PURE) "A moment. As it is." else "A little more wonder.", color = Paper.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                            MicroText("${state.iso} ISO\n${CaptureMath.shutterLabel(state.shutterNs)}", Paper)
                        }
                    }
                }
            }
        }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Look.entries.forEach { look ->
                val selected = settings.look == look
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .background(if (selected) accent.copy(alpha = 0.1f) else Color.Transparent)
                    .border(1.dp, if (selected) accent.copy(alpha = 0.4f) else Color(0xFF2A2E27), RoundedCornerShape(16.dp))
                    .clickable(enabled = !state.busy, role = Role.Tab) { change(settings.copy(look = look)); haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Glyph(if (look == Look.PURE) "pure" else "bloom", if (selected) accent else Muted, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (look == Look.PURE) "Pure" else "Bloom", color = if (selected) accent else Paper, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        if (selected) Box(Modifier.size(5.dp).background(accent, CircleShape))
                    }
                    Text(if (look == Look.PURE) "Reality, with less." else "Reality, with feeling.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
                if (look == Look.PURE) Spacer(Modifier.width(10.dp))
            }
        }
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            if (settings.look == Look.BLOOM) {
                MicroText("FEELING", Muted)
                Slider(value = settings.bloom, onValueChange = { change(settings.copy(bloom = it)) }, enabled = !state.busy,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp), colors = sliderColors(accent))
                MicroText("${(settings.bloom * 100).roundToInt()}%", accent)
            } else {
                Box(Modifier.weight(1f).height(1.dp).background(Color(0xFF2A2E27)))
                MicroText("  KEEP THE IMPERFECTIONS  ", Muted)
                Box(Modifier.weight(1f).height(1.dp).background(Color(0xFF2A2E27)))
            }
        }
        Row(Modifier.fillMaxWidth().height(91.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceAround) {
            GalleryButton(state.savedUri)
            Box(Modifier.size(84.dp).border(1.5.dp, accent.copy(alpha = if (state.ready) 1f else 0.3f), CircleShape).padding(6.dp)
                .clip(CircleShape).background(if (state.ready) accent else accent.copy(alpha = 0.2f))
                .semantics { contentDescription = "Take photo" }
                .clickable(enabled = state.ready && !state.busy, role = Role.Button) {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm); engine.capture()
                }, contentAlignment = Alignment.Center) {
                if (state.busy) CircularProgressIndicator(Modifier.size(27.dp), color = Ink, strokeWidth = 2.dp)
                else Glyph("mark", Ink.copy(alpha = 0.65f), Modifier.size(27.dp))
            }
            IconButton(onClick = {
                change(settings.copy(iso = null, shutterNs = null, focus = null, exposure = 0)); engine.flip()
            }, enabled = state.ready && !state.busy && state.info.cameraCount > 1,
                modifier = Modifier.size(52.dp).background(Color(0xFF232720), CircleShape)) {
                Glyph("flip", if (state.info.cameraCount > 1) Paper else Muted.copy(alpha = 0.3f))
            }
        }
        Text(if (granted) state.message else "MADE FOR THE WAY YOU SEE", color = if (state.error) Peach else Muted,
            fontSize = 10.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(top = 8.dp, bottom = 7.dp))
    }
    if (details) ModalBottomSheet(onDismissRequest = { details = false }, containerColor = Color(0xFF1D211B), contentColor = Paper) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 26.dp).padding(bottom = 40.dp)) {
            MicroText("THE EIDOLON PHILOSOPHY", accent)
            Text("Two ways to see.", fontSize = 32.sp, letterSpacing = (-1).sp, modifier = Modifier.padding(vertical = 16.dp))
            Text("Pure keeps what the sensor saw. Bloom brings out what the moment felt like.", color = Muted, fontSize = 16.sp, lineHeight = 24.sp)
            Spacer(Modifier.height(24.dp))
            Capability("Sensor RAW / DNG", if (state.ready) if (state.info.raw) "Available" else "Unavailable" else "Camera not open")
            Capability("Noise reduction off", if (state.ready && state.info.noiseOff) "Supported" else "Not confirmed")
            Capability("Sharpening off", if (state.ready && state.info.edgeOff) "Supported" else "Not confirmed")
            Capability("Manual exposure", if (state.ready && state.info.manual) "Supported" else "Not confirmed")
            Spacer(Modifier.height(20.dp))
            Text("Pure saves a sensor DNG plus a companion JPEG when available. The JPEG and viewfinder still use the phone’s rendering pipeline. Hardware may apply processing even to RAW.\n\nBloom currently adds warm color, gentle contrast and richer saturation. Its preview is an approximation of the saved JPEG.\n\nPhotos stay in Pictures/Eidolon. No accounts, uploads or internet permission.", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(24.dp))
            MicroText("EIDOLON / EARLY LIGHT / 0.1", accent)
        }
    }
}

@Composable
private fun ProControls(state: CameraState, settings: CaptureSettings, selected: String, accent: Color, onSelect: (String) -> Unit, onChange: (CaptureSettings) -> Unit) {
    val info = state.info
    val manual = settings.iso != null || settings.shutterNs != null
    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Ink.copy(alpha = 0.88f)).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("EV", "ISO", "S", "MF").forEach { control ->
                val enabled = !state.busy && when (control) { "EV" -> !manual && info.evMin < info.evMax; "MF" -> info.focusMax > 0; else -> info.manual }
                Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .background(if (selected == control) accent.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(control) }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    MicroText(control, if (enabled) if (selected == control) accent else Paper else Muted.copy(alpha = 0.4f))
                    Text(when (control) {
                        "EV" -> if (manual) "—" else "%+.1f".format(java.util.Locale.US, settings.exposure * info.evStep)
                        "ISO" -> settings.iso?.toString() ?: "AUTO"
                        "S" -> settings.shutterNs?.let(CaptureMath::shutterLabel) ?: "AUTO"
                        else -> if (settings.focus == null) "AUTO" else "%.1f D".format(java.util.Locale.US, settings.focus)
                    }, color = if (enabled) Paper else Muted.copy(alpha = 0.4f), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        val supported = when (selected) { "EV" -> !manual && info.evMin < info.evMax; "MF" -> info.focusMax > 0; else -> info.manual }
        if (supported) {
            val value = when (selected) {
                "EV" -> (settings.exposure - info.evMin).toFloat() / (info.evMax - info.evMin)
                "ISO" -> normalized((settings.iso ?: state.iso).toDouble(), info.isoMin.toDouble(), info.isoMax.toDouble())
                "S" -> normalized((settings.shutterNs ?: state.shutterNs).toDouble(), info.shutterMin.toDouble(), info.shutterMax.toDouble())
                else -> (settings.focus ?: 0f) / info.focusMax
            }.coerceIn(0f, 1f)
            Slider(value = value, enabled = !state.busy, colors = sliderColors(accent), onValueChange = { v ->
                onChange(when (selected) {
                    "EV" -> settings.copy(exposure = (info.evMin + v * (info.evMax - info.evMin)).roundToInt())
                    "ISO" -> settings.copy(iso = exponential(v, info.isoMin.toDouble(), info.isoMax.toDouble()).roundToInt(), shutterNs = settings.shutterNs ?: state.shutterNs.coerceIn(info.shutterMin, info.shutterMax))
                    "S" -> settings.copy(shutterNs = exponential(v, info.shutterMin.toDouble(), info.shutterMax.toDouble()).toLong(), iso = settings.iso ?: state.iso.coerceIn(info.isoMin, info.isoMax))
                    else -> settings.copy(focus = v * info.focusMax)
                })
            })
        } else Text(if (selected == "EV" && manual) "EV is available with automatic exposure." else "This camera doesn’t expose this control.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            MicroText("${state.iso} ISO · ${CaptureMath.shutterLabel(state.shutterNs)}", Muted)
            Text("RESET AUTO", color = accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clip(CircleShape).clickable(enabled = !state.busy) { onChange(settings.copy(iso = null, shutterNs = null, focus = null, exposure = 0)) }.padding(10.dp))
        }
    }
}

private fun normalized(value: Double, min: Double, max: Double): Float =
    if (max <= min) 0f else (ln(value.coerceIn(min, max) / min) / ln(max / min)).toFloat()
private fun exponential(value: Float, min: Double, max: Double): Double = min * exp(value * ln(max / min))

@Composable private fun sliderColors(accent: Color) = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Color(0xFF41473B))

@Composable private fun MicroText(text: String, color: Color) {
    Text(text, color = color, fontSize = 9.sp, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
}

@Composable private fun Capability(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp); Text(value, color = Acid, fontSize = 12.sp)
    }
    HorizontalDivider(color = Color(0xFF34392F))
}

@Composable private fun GalleryButton(uri: String?) {
    val context = LocalContext.current
    val thumbnail by produceState<android.graphics.Bitmap?>(null, uri) {
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(Uri.parse(uri), android.util.Size(120, 120), null) }.getOrNull()
        }
    }
    Box(Modifier.size(50.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF232720))
        .border(1.dp, Color(0xFF3B4233), RoundedCornerShape(13.dp))
        .semantics { contentDescription = if (uri == null) "No photos yet" else "Open last photo" }
        .clickable(enabled = uri != null, role = Role.Button) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                .onFailure { android.widget.Toast.makeText(context, "Saved in Pictures/Eidolon. Install a photo viewer to open it here.", android.widget.Toast.LENGTH_LONG).show() }
        }, contentAlignment = Alignment.Center) {
        thumbnail?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
            ?: Glyph("gallery", Muted)
    }
}

@Composable private fun LensArt(accent: Color) {
    Canvas(Modifier.size(130.dp)) {
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.12f), Color.Transparent)), size.width / 2)
        drawCircle(accent.copy(alpha = 0.15f), size.width * 0.48f, style = Stroke(1.dp.toPx()))
        drawCircle(accent.copy(alpha = 0.45f), size.width * 0.35f, style = Stroke(1.dp.toPx()))
        drawCircle(accent, size.width * 0.19f, style = Stroke(1.5.dp.toPx()))
        drawLine(accent, Offset(center.x - 6.dp.toPx(), center.y), Offset(center.x + 6.dp.toPx(), center.y), 1.dp.toPx())
        drawLine(accent, Offset(center.x, center.y - 6.dp.toPx()), Offset(center.x, center.y + 6.dp.toPx()), 1.dp.toPx())
        drawCircle(accent, 3.dp.toPx(), Offset(center.x + size.width * 0.34f, center.y - size.width * 0.34f))
    }
}

@Composable private fun Glyph(kind: String, color: Color, modifier: Modifier = Modifier.size(22.dp)) {
    val description = when (kind) { "grid" -> "Toggle composition grid"; "flip" -> "Switch camera"; "info" -> "About capture modes"; else -> null }
    Canvas(modifier.then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)) {
        val w = size.width; val h = size.height; val stroke = 1.4.dp.toPx()
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(w*x,h*y), Offset(w*x2,h*y2), stroke, StrokeCap.Round)
        when (kind) {
            "mark", "pure" -> {
                drawCircle(color, w*0.39f, style = Stroke(stroke))
                if (kind == "mark") { line(.37f,.5f,.63f,.5f); line(.5f,.37f,.5f,.63f) }
                else drawCircle(color, w*.07f)
            }
            "grid" -> { line(.33f,.1f,.33f,.9f); line(.67f,.1f,.67f,.9f); line(.1f,.33f,.9f,.33f); line(.1f,.67f,.9f,.67f) }
            "info" -> { drawCircle(color, w*.4f, style = Stroke(stroke)); line(.5f,.48f,.5f,.7f); drawCircle(color, stroke*.65f, Offset(w*.5f,h*.3f)) }
            "bloom" -> { for (n in 0..7) { val a = n * Math.PI / 4; line((.5 + .18*kotlin.math.cos(a)).toFloat(),(.5 + .18*kotlin.math.sin(a)).toFloat(),(.5 + .43*kotlin.math.cos(a)).toFloat(),(.5 + .43*kotlin.math.sin(a)).toFloat()) } }
            "flip" -> { drawArc(color, 30f, 270f, false, Offset(w*.13f,h*.13f), androidx.compose.ui.geometry.Size(w*.74f,h*.74f), style = Stroke(stroke)); line(.72f,.1f,.72f,.35f); line(.72f,.35f,.95f,.3f) }
            "gallery" -> { drawRoundRect(color, Offset(w*.12f,h*.12f), androidx.compose.ui.geometry.Size(w*.76f,h*.76f), androidx.compose.ui.geometry.CornerRadius(w*.1f), style = Stroke(stroke)); line(.22f,.72f,.43f,.48f); line(.43f,.48f,.62f,.66f); line(.62f,.66f,.75f,.56f); drawCircle(color,w*.06f,Offset(w*.67f,h*.33f)) }
        }
    }
}
