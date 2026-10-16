package studio.eidolon.camera

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Pure presentation: camera ownership and permission requests live in the Activity. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CameraInterface(
    state: CameraState = CameraState(), settings: CaptureSettings = CaptureSettings(),
    workspace: Workspace = Workspace.FUN, grid: Boolean = false, granted: Boolean = true,
    settingsRequired: Boolean = false, activeControl: String? = null, feelingOpen: Boolean = false,
    showDetails: Boolean = false,
    onChange: (CaptureSettings) -> Unit = {}, onWorkspace: (Workspace) -> Unit = {},
    onGrid: (Boolean) -> Unit = {}, onControl: (String) -> Unit = {}, onFeeling: () -> Unit = {},
    onDetails: (Boolean) -> Unit = {}, onPermission: () -> Unit = {}, onRetry: () -> Unit = {},
    onCapture: () -> Unit = {}, onGallery: () -> Unit = {}, onFlip: () -> Unit = {}, preview: @Composable () -> Unit = {},
) {
    val accent by animateColorAsState(if (settings.look == Look.PURE) Paper else BloomViolet, tween(140), label = "intent")
    val vivid = settings.look == Look.BLOOM
    val glow by animateColorAsState(if (vivid) Color(0xFF382040) else Color(0xFF222224), tween(240), label = "glass light")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink, Ink, glow))), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxSize().safeDrawingPadding()) {
            CameraHeader(workspace, !state.busy, accent, vivid, onWorkspace, { onDetails(true) })
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                val ratio = state.info.aspectRatio
                val frameWidth = minOf(maxWidth, maxHeight * ratio)
                Box(Modifier.width(frameWidth).aspectRatio(ratio).clip(RoundedCornerShape(18.dp)).background(SurfaceInk)) {
                    if (granted) preview()
                    if (granted && state.ready && grid) CompositionGrid()
                    if (granted && state.ready) FrameReadout(state, settings.look, workspace, grid, accent, onGrid)
                    if (!granted) PermissionPane(accent, settingsRequired, onPermission)
                    else if (!state.ready) CameraUnavailable(state, accent, onRetry)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.glass(CircleShape, vivid)
                        .selectableGroup().padding(3.dp)) {
                        Look.entries.forEach { look ->
                            IntentTab(look, settings.look == look, accent, !state.busy) {
                                onChange(settings.copy(look = look))
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (settings.look == Look.BLOOM) {
                        TextButton(onClick = onFeeling, enabled = !state.busy,
                            colors = ButtonDefaults.textButtonColors(contentColor = accent)) {
                            Text("${(settings.bloom * 100).roundToInt()}%", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(if (feelingOpen) "−" else "+", fontSize = 20.sp)
                        }
                    } else if (LocalDensity.current.fontScale < 1.4f) MicroText("SENSOR FIRST", Muted)
                }
                if (settings.look == Look.BLOOM && feelingOpen) {
                    PrecisionSlider(settings.bloom, "Bloom intensity", "${(settings.bloom * 100).roundToInt()} percent", accent, !state.busy) {
                        onChange(settings.copy(bloom = it))
                    }
                }
                if (workspace == Workspace.PRO && state.ready) {
                    ProControls(state, settings, activeControl, accent, onControl, onChange)
                }
                Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        GalleryButton(state.savedUri, enabled = !state.busy, onOpen = onGallery)
                    }
                    Shutter(state.ready, state.busy, state.saving, vivid, accent, onCapture)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = onFlip, enabled = state.ready && !state.busy && state.info.cameraCount > 1,
                            modifier = Modifier.size(52.dp).glass(CircleShape, vivid)) {
                            Glyph("flip", if (state.ready && state.info.cameraCount > 1) Paper else Muted.copy(alpha = 0.4f))
                        }
                    }
                }
                val status = when {
                    state.saving > 0 && !state.error -> "${state.saving} ${if (state.saving == 1) "photo" else "photos"} developing" + if (!state.busy && state.ready) " · keep shooting" else ""
                    state.busy || (state.ready && state.error) || (state.ready && state.message.startsWith("Saved")) -> state.message
                    state.ready && settings.look == Look.PURE && !state.info.raw -> "RAW unavailable · capturing JPEG"
                    else -> ""
                }
                Text(status, color = if (state.error) Peach else Muted, fontSize = 11.sp, maxLines = 2,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(bottom = 8.dp))
            }
        }
    }
    if (showDetails) CaptureDetails(state, onDismiss = { onDetails(false) })
}

@Composable
private fun CameraHeader(workspace: Workspace, enabled: Boolean, accent: Color, vivid: Boolean, onWorkspace: (Workspace) -> Unit, onInfo: () -> Unit) {
    val showWordmark = LocalDensity.current.fontScale < 1.4f
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Glyph("mark", accent, Modifier.size(25.dp).semantics { contentDescription = "Eidolon" })
            if (showWordmark) Text("eidolon", color = Paper, fontSize = 20.sp, letterSpacing = (-0.8).sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 9.dp))
        }
        Row(Modifier.glass(CircleShape, vivid).selectableGroup().padding(3.dp)) {
            Workspace.entries.forEach { mode ->
                val selected = mode == workspace
                Box(Modifier.width(56.dp).height(48.dp).clip(CircleShape)
                    .background(if (selected) Paper else Color.Transparent)
                    .selectable(selected, enabled, Role.Tab) { onWorkspace(mode) }, contentAlignment = Alignment.Center) {
                    Text(if (mode == Workspace.FUN) "Fun" else "Pro", color = if (selected) Ink else Muted,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        IconButton(onClick = onInfo, modifier = Modifier.size(48.dp)) { Glyph("info", Muted, Modifier.size(20.dp)) }
    }
}

@Composable
private fun IntentTab(look: Look, selected: Boolean, accent: Color, enabled: Boolean, onClick: () -> Unit) {
    val surface by animateColorAsState(if (selected) accent else Color.Transparent, tween(120), label = "selected intent")
    Row(Modifier.heightIn(min = 48.dp).clip(CircleShape).background(surface)
        .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        if (LocalDensity.current.fontScale < 1.4f)
            Glyph(if (look == Look.PURE) "pure" else "bloom", if (selected) Ink else Muted, Modifier.size(15.dp))
        Text(if (look == Look.PURE) "Pure" else "Bloom", color = if (selected) Ink else Muted,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BoxScope.FrameReadout(state: CameraState, look: Look, workspace: Workspace, grid: Boolean, accent: Color, onGrid: (Boolean) -> Unit) {
    val format = if (look == Look.PURE && state.info.raw) "RAW + JPEG" else "JPEG"
    Row(Modifier.align(Alignment.TopStart).padding(12.dp)
        .background(Ink.copy(alpha = 0.65f), CircleShape).glass(CircleShape, look == Look.BLOOM)
        .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(4.dp).background(accent, CircleShape))
        Spacer(Modifier.width(7.dp))
        MicroText(format, Paper)
    }
    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (workspace == Workspace.PRO) {
            Column(Modifier.weight(1f)) {
                Box(Modifier.background(Ink.copy(alpha = 0.65f), CircleShape).glass(CircleShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp)) {
                    MicroText("${state.iso} ISO  ·  ${CaptureMath.shutterLabel(state.shutterNs)}", Paper)
                }
            }
        } else Spacer(Modifier.weight(1f))
        IconToggleButton(checked = grid, onCheckedChange = onGrid, modifier = Modifier.size(48.dp)
            .background(Ink.copy(alpha = 0.55f), CircleShape).glass(CircleShape, look == Look.BLOOM)) {
            Glyph("grid", if (grid) accent else Paper, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun CompositionGrid() {
    Canvas(Modifier.fillMaxSize()) {
        for (n in 1..2) {
            drawLine(Color.White.copy(alpha = 0.35f), Offset(size.width * n / 3, 0f), Offset(size.width * n / 3, size.height), 0.5.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, size.height * n / 3), Offset(size.width, size.height * n / 3), 0.5.dp.toPx())
        }
    }
}

@Composable
private fun Shutter(ready: Boolean, busy: Boolean, saving: Int, vivid: Boolean, accent: Color, onCapture: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(90), label = "shutter press")
    Box(Modifier.size(84.dp).graphicsLayer { scaleX = scale; scaleY = scale }
        .border(1.dp, if (ready) Paper.copy(alpha = 0.65f) else EdgeInk, CircleShape)
        .semantics { contentDescription = if (busy) "Saving photo" else "Take photo" }
        .clip(CircleShape).clickable(interactionSource = interaction, indication = null,
            enabled = ready && !busy, role = Role.Button, onClick = onCapture), contentAlignment = Alignment.Center) {
        Box(Modifier.size(70.dp).background(Brush.linearGradient(
            if (!ready) listOf(EdgeInk, EdgeInk) else if (vivid) listOf(BloomBlue, BloomViolet, BloomPink) else listOf(Color.White, Color(0xFFD1D1D5))), CircleShape), contentAlignment = Alignment.Center) {
            Glyph("mark", if (ready) Ink else Muted, Modifier.size(22.dp))
        }
        if (busy || saving > 0) CircularProgressIndicator(Modifier.size(84.dp), color = accent, strokeWidth = 2.dp)
    }
}

@Composable
private fun PermissionPane(accent: Color, settingsRequired: Boolean, onPermission: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.Center) {
        MicroText("MEET EIDOLON", accent)
        Spacer(Modifier.height(24.dp))
        Text("Reality.\nYour way.", color = Paper, fontSize = 40.sp, lineHeight = 43.sp,
            letterSpacing = (-1.8).sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(18.dp))
        Text("Pure keeps it honest.\nBloom makes it yours.", color = Muted, fontSize = 15.sp, lineHeight = 23.sp)
        Spacer(Modifier.height(28.dp))
        Button(onClick = onPermission, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Paper, contentColor = Ink)) {
            Text(if (settingsRequired) "Open camera settings" else "Open camera", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Glyph("arrow", Ink, Modifier.size(18.dp))
        }
        Text("On-device. Always yours.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun CameraUnavailable(state: CameraState, accent: Color, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.error) Glyph("mark", Peach, Modifier.size(38.dp))
        else CircularProgressIndicator(Modifier.size(24.dp), color = accent, strokeWidth = 2.dp)
        Spacer(Modifier.height(20.dp))
        Text(if (state.error) "A moment, interrupted." else "Opening your window.", color = Paper,
            fontSize = 20.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        if (state.error) {
            Text(state.message, color = Muted, fontSize = 13.sp, lineHeight = 20.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 16.dp))
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Paper, contentColor = Ink)) { Text("Try again") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureDetails(state: CameraState, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceInk, contentColor = Paper) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 36.dp)) {
            MicroText("THE CAMERA, EXPLAINED", Muted)
            Text("Two ways to see.", fontSize = 30.sp, letterSpacing = (-1).sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp, bottom = 22.dp))
            Text("Pure", color = Acid, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("Sensor DNG plus a companion JPEG when supported. Requests sharpening and noise reduction off where the camera allows it.",
                color = Muted, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp))
            Text("Bloom", color = BloomViolet, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("Warm color and gentle contrast, with adjustable intensity. Grades the JPEG; at 0% the original is preserved.",
                color = Muted, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp, bottom = 24.dp))
            Capability("Sensor RAW / DNG", if (state.ready) if (state.info.raw) "Available" else "Unavailable" else "Not connected")
            Capability("Noise reduction off", if (state.ready && state.info.noiseOff) "Supported" else "Not confirmed")
            Capability("Sharpening off", if (state.ready && state.info.edgeOff) "Supported" else "Not confirmed")
            Capability("Manual exposure", if (state.ready && state.info.manual) "Supported" else "Not confirmed")
            Text("The JPEG and preview use the phone’s rendering pipeline. Hardware may still process RAW. Bloom preview and capture can differ.\n\nSaved to Pictures/Eidolon. No accounts, tracking, or uploads.",
                color = Muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 24.dp))
        }
    }
}
