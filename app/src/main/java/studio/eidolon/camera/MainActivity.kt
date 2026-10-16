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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var cameraState by mutableStateOf(CameraState())
    private lateinit var engine: CameraEngine
    private lateinit var orientation: OrientationEventListener
    private var uiStore: UiPreferencesStore? = null
    private var cameraWanted = true
    private var latestPreferences: UiPreferences? = null
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
            EidolonTheme {
                var initial by remember { mutableStateOf<UiPreferences?>(null) }
                LaunchedEffect(Unit) {
                    val (store, value) = withContext(Dispatchers.IO) {
                        val store = UiPreferencesStore(getSharedPreferences("ui", MODE_PRIVATE))
                        store to store.load()
                    }
                    uiStore = store
                    initial = value
                }
                initial?.let { preferences ->
                    EidolonScreen(cameraState, engine, preferences,
                        onPreferences = { latestPreferences = it },
                        persistPreferences = { uiStore?.save(it) },
                        onCameraVisibility = { visible ->
                            cameraWanted = visible
                            if (visible) { engine.start(); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                            else { engine.stop(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                        })
                } ?: Box(Modifier.fillMaxSize().background(Ink))
            }
        }
    }
    override fun onResume() { super.onResume(); if (cameraWanted) engine.start(); orientation.enable() }
    override fun onPause() {
        latestPreferences?.let { uiStore?.save(it) }
        orientation.disable(); engine.stop(); super.onPause()
    }
    override fun onDestroy() { engine.destroy(); super.onDestroy() }
}

private val SettingsSaver = androidx.compose.runtime.saveable.listSaver<CaptureSettings, Any>(
    save = { listOf(it.look.name, it.bloom, it.exposure, it.iso ?: -1, it.shutterNs ?: -1L, it.focus ?: -1f) },
    restore = { CaptureSettings(Look.valueOf(it[0] as String), it[1] as Float, it[2] as Int,
        (it[3] as Int).takeIf { n -> n >= 0 }, (it[4] as Long).takeIf { n -> n >= 0 },
        (it[5] as Float).takeIf { n -> n >= 0 }) }
)

@Composable
private fun EidolonScreen(state: CameraState, engine: CameraEngine, initial: UiPreferences,
    onPreferences: (UiPreferences) -> Unit, persistPreferences: suspend (UiPreferences) -> Unit, onCameraVisibility: (Boolean) -> Unit) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val haptic = LocalHapticFeedback.current
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by rememberSaveable { mutableStateOf(initial.permissionAsked) }
    var settings by rememberSaveable(stateSaver = SettingsSaver) { mutableStateOf(CaptureSettings(look = initial.look, bloom = initial.bloom)) }
    var workspace by rememberSaveable { mutableStateOf(initial.workspace) }
    var grid by rememberSaveable { mutableStateOf(initial.grid) }
    var galleryOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(galleryOpen) { onCameraVisibility(!galleryOpen) }
    var details by remember { mutableStateOf(false) }
    var activeControl by rememberSaveable { mutableStateOf<String?>(null) }
    var feelingOpen by rememberSaveable { mutableStateOf(false) }
    val preferences = UiPreferences(settings.look, settings.bloom, workspace, grid, asked)
    SideEffect { onPreferences(preferences) }
    LaunchedEffect(preferences) {
        delay(250) // Slider gestures persist their final value, not every intermediate frame.
        persistPreferences(preferences)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it; asked = true
        if (it) engine.start()
    }
    LaunchedEffect(settings) { engine.update(settings) }
    DisposableEffect(activity) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                granted = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    fun change(value: CaptureSettings) {
        if (value.look != settings.look) { feelingOpen = false; activeControl = null }
        settings = value; engine.update(value)
    }
    if (galleryOpen) GalleryScreen(onClose = { galleryOpen = false }) else CameraInterface(
        state = state, settings = settings, workspace = workspace, grid = grid,
        granted = granted, settingsRequired = asked && !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA),
        activeControl = activeControl, feelingOpen = feelingOpen, showDetails = details,
        onGallery = { galleryOpen = true },
        onChange = ::change,
        onWorkspace = { mode ->
            workspace = mode; activeControl = null; feelingOpen = false
            if (mode == Workspace.FUN) change(settings.copy(iso = null, shutterNs = null, focus = null, exposure = 0))
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        },
        onGrid = { grid = it },
        onControl = { activeControl = if (activeControl == it) null else it; feelingOpen = false },
        onFeeling = { feelingOpen = !feelingOpen; activeControl = null },
        onDetails = { details = it },
        onPermission = {
            if (asked && !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA))
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            else permission.launch(Manifest.permission.CAMERA)
        },
        onRetry = engine::start,
        onCapture = { haptic.performHapticFeedback(HapticFeedbackType.Confirm); engine.capture() },
        onFlip = {
            change(settings.copy(iso = null, shutterNs = null, focus = null, exposure = 0))
            engine.flip(); activeControl = null
        },
        preview = { AndroidView(factory = { TextureView(it).also(engine::attach) }, modifier = Modifier.fillMaxSize()) },
    )
}
