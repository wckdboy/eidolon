package studio.eidolon.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.Executors
import kotlin.math.abs

/** Camera2 owns capture. The UI never fabricates support or labels a JPEG as RAW. */
class CameraEngine(private val context: Context,
    private val savePhoto: (ByteArray, ((java.io.OutputStream) -> Unit)?, CaptureSettings) -> android.net.Uri = PhotoStore(context)::save,
    private val onState: (CameraState) -> Unit) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("EidolonCamera").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "EidolonWriter")
    }
    private var state = CameraState()
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var rawOwner: LeasedResource<ImageReader>? = null
    private var characteristics: CameraCharacteristics? = null
    private var generation = 0
    private var cameraIndex = 0
    private var active = false
    private var opening = false
    private var pending: Pending? = null
    private var savingCount = 0
    private val saveCapacity = if (context.getSystemService(android.app.ActivityManager::class.java).isLowRamDevice) 1 else 2
    private fun occupied() = pending != null || savingCount >= saveCapacity
    private var lastMetering = 0L
    private var captureTimeout: Runnable? = null
    @Volatile private var destroyed = false
    private val repeatUpdate = Runnable { repeat() }
    private val lookUpdate = Runnable { applyPreviewLookNow() }
    private val preferences by lazy { context.getSharedPreferences("camera", Context.MODE_PRIVATE) }

    init {
        handler.post { publish(state.copy(savedUri = preferences.getString("lastPhoto", null))) }
    }
    @Volatile private var view: TextureView? = null
    @Volatile private var settings = CaptureSettings()
    @Volatile private var orientation = 0

    private data class Pending(
        val settings: CaptureSettings, val wantsRaw: Boolean, val rotation: Int,
        var jpeg: ByteArray? = null, var jpegTimestamp: Long? = null,
        var raw: Image? = null, var result: TotalCaptureResult? = null,
    )

    fun attach(texture: TextureView) {
        view = texture
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) { handler.post { open() } }
            override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) { handler.post { transform() } }
            override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean {
                // Camera output must be disconnected before releasing its SurfaceTexture.
                if (!handler.post { closeCamera(); t.release() }) t.release()
                return false
            }
            override fun onSurfaceTextureUpdated(t: SurfaceTexture) = Unit
        }
        applyPreviewLook()
        if (texture.isAvailable) handler.post { open() }
    }

    fun start() { handler.post { active = true; open() } }
    fun stop() { handler.post { active = false; closeCamera() } }
    fun destroy() {
        destroyed = true
        main.removeCallbacks(lookUpdate)
        handler.post { active = false; closeCamera(); writer.shutdown(); thread.quitSafely() }
    }
    fun syncLibraryPhoto(uri: String?) { handler.post {
        // A gallery query can overlap publication; the writer owns the latest URI until it drains.
        if (savingCount > 0 || state.savedUri == uri) return@post
        preferences.edit().putString("lastPhoto", uri).apply()
        publish(state.copy(savedUri = uri))
    } }
    fun setOrientation(degrees: Int) { orientation = degrees }
    fun update(value: CaptureSettings) {
        val previous = settings
        if (value == previous) return
        settings = value
        if (previous.look != value.look || previous.bloom != value.bloom) applyPreviewLook()
        if (!previous.sameSensorControls(value)) {
            handler.removeCallbacks(repeatUpdate)
            handler.postDelayed(repeatUpdate, 16)
        }
    }
    fun flip() { handler.post {
        if (state.busy) return@post
        cameraIndex++
        closeCamera()
        settings = settings.copy(iso = null, shutterNs = null, focus = null, exposure = 0)
        open()
    } }

    private fun publish(value: CameraState) {
        if (state == value) return
        state = value
        if (!destroyed) main.post { if (!destroyed) onState(value) }
    }
    private fun applyPreviewLook() {
        main.removeCallbacks(lookUpdate)
        main.post(lookUpdate)
    }
    private fun applyPreviewLookNow() {
        if (destroyed) return
        val paint = if (settings.look == Look.BLOOM && settings.bloom > 0f) Paint().apply {
            colorFilter = ColorMatrixColorFilter(BloomColor.matrix(settings.bloom))
        } else null
        view?.setLayerType(if (paint == null) android.view.View.LAYER_TYPE_NONE else android.view.View.LAYER_TYPE_HARDWARE, paint)
    }

    private fun cameras(): List<String> = manager.cameraIdList.sortedBy {
        if (manager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK) 0 else 1
    }

    @Suppress("MissingPermission")
    private fun open() {
        if (!active || opening || device != null || view?.isAvailable != true) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            val ids = cameras()
            check(ids.isNotEmpty()) { "No camera found on this device." }
            val id = ids[cameraIndex % ids.size]
            val c = manager.getCameraCharacteristics(id)
            characteristics = c
            val caps = c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES] ?: intArrayOf()
            val iso = c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]
            val shutter = c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]
            val ev = c[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE]
            val info = CameraInfo(
                raw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW),
                manual = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
                focusMax = if (c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES]?.contains(CaptureRequest.CONTROL_AF_MODE_OFF) == true)
                    c[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0f else 0f,
                isoMin = iso?.lower ?: 100, isoMax = iso?.upper ?: 1600,
                shutterMin = shutter?.lower ?: 125_000, shutterMax = minOf(shutter?.upper ?: 100_000_000, 1_000_000_000L),
                evMin = ev?.lower ?: 0, evMax = ev?.upper ?: 0,
                evStep = c[CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP]?.toFloat() ?: 0f,
                noiseOff = c[CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES]?.contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF) == true,
                edgeOff = c[CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES]?.contains(CaptureRequest.EDGE_MODE_OFF) == true,
                cameraCount = ids.size,
            )
            publish(state.copy(info = info, ready = false, error = false, message = "Opening your window…"))
            opening = true
            val token = ++generation
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (token != generation || !active) { camera.close(); return }
                    opening = false; device = camera
                    configure(token, info.raw)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (token == generation) fail("Camera disconnected. Tap Retry.")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (token == generation) fail("Camera unavailable ($error). Close other camera apps and retry.")
                }
            }, handler)
        } catch (e: Exception) { fail(e.message ?: "Could not open the camera.") }
    }

    private var previewSize = android.util.Size(1440, 1080)

    @Suppress("DEPRECATION")
    private fun configure(token: Int, wantRaw: Boolean) {
        try {
            val camera = device ?: return
            val c = characteristics ?: return
            val map = requireNotNull(c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP])
            jpegReader?.close(); rawOwner?.close(); rawOwner = null; rawReader = null; surface?.release()
            // Prefer 4:3 and cap JPEG to 12 MP to keep Bloom working memory bounded.
            val jpegSizes = map.getOutputSizes(ImageFormat.JPEG).toList()
            val choices = jpegSizes.filter { it.width.toLong() * it.height <= 12_500_000 }
                .ifEmpty { listOf(jpegSizes.minBy { it.width.toLong() * it.height }) }
            val jpegSize = choices.sortedWith(compareBy<android.util.Size> { abs(it.width.toFloat() / it.height - 4f / 3f) > 0.03f }
                .thenByDescending { it.width.toLong() * it.height }).first()
            val previewSizes = map.getOutputSizes(SurfaceTexture::class.java).toList()
            previewSize = previewSizes.filter { it.width <= 1920 && it.height <= 1440 }
                .ifEmpty { previewSizes }.minBy {
                    abs(it.width.toFloat() / it.height - jpegSize.width.toFloat() / jpegSize.height) * 10000 + abs(it.width - 1440)
                }
            val texture = view?.surfaceTexture ?: return
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            surface = Surface(texture)
            jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
                    image.use {
                        val job = pending ?: return@use
                        if (token != generation) return@use
                        val buffer = it.planes[0].buffer
                        job.jpeg = ByteArray(buffer.remaining()).also(buffer::get)
                        job.jpegTimestamp = it.timestamp
                    }
                    finishCapture()
                }, handler)
            }
            rawReader = if (wantRaw) map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }?.let { size ->
                ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2).apply {
                    setOnImageAvailableListener({ reader ->
                        val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
                        val job = pending
                        if (token != generation || job == null || !job.wantsRaw) image.close()
                        else { job.raw?.close(); job.raw = image; finishCapture() }
                    }, handler)
                }
            } else null
            rawOwner = rawReader?.let { LeasedResource(it) }
            val outputs = listOfNotNull(surface, jpegReader?.surface, rawReader?.surface)
            camera.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (token != generation) { s.close(); return }
                    session = s
                    val raw = rawReader != null
                    publish(state.copy(ready = true, error = false, info = state.info.copy(raw = raw, aspectRatio = jpegSize.height.toFloat() / jpegSize.width),
                        message = if (raw) "Sensor RAW is ready." else "JPEG ready · RAW unavailable on this camera."))
                    transform(); repeat()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    s.close()
                    if (token != generation) return
                    if (wantRaw) configure(token, false) else fail("This camera cannot start a capture session. Tap Retry.")
                }
            }, handler)
        } catch (e: Exception) {
            if (wantRaw) configure(token, false) else fail(e.message ?: "Camera setup failed.")
        }
    }

    private fun transform() {
        val c = characteristics ?: return
        val sensor = c[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90
        main.post {
            val texture = view ?: return@post
            if (texture.width == 0 || texture.height == 0) return@post
            val rotation = when (texture.display?.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
            val relative = CaptureMath.jpegRotation(sensor, -rotation, c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT)
            val w = texture.width.toFloat(); val h = texture.height.toFloat()
            val swap = relative % 180 != 0
            val bufferW = if (swap) previewSize.height.toFloat() else previewSize.width.toFloat()
            val bufferH = if (swap) previewSize.width.toFloat() else previewSize.height.toFloat()
            val scale = maxOf(w / bufferW, h / bufferH)
            val matrix = Matrix()
            // TextureView already applies sensor orientation at display rotation zero.
            matrix.setScale(bufferW * scale / w, bufferH * scale / h, w / 2, h / 2)
            if (rotation != 0) matrix.postRotate(-rotation.toFloat(), w / 2, h / 2)
            texture.setTransform(matrix)
        }
    }

    private fun apply(builder: CaptureRequest.Builder, value: CaptureSettings) {
        val c = characteristics ?: return
        val info = state.info
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        val afModes = c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
        val manualFocus = value.focus != null && info.focusMax > 0 && afModes.contains(CaptureRequest.CONTROL_AF_MODE_OFF)
        if (manualFocus) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, value.focus.coerceIn(0f, info.focusMax))
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE,
                if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                else afModes.firstOrNull() ?: CaptureRequest.CONTROL_AF_MODE_OFF)
        }
        if (info.manual && (value.iso != null || value.shutterNs != null)) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, (value.iso ?: state.iso.takeIf { it > 0 } ?: 100).coerceIn(info.isoMin, info.isoMax))
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, (value.shutterNs ?: state.shutterNs.takeIf { it > 0 } ?: 16_666_666).coerceIn(info.shutterMin, info.shutterMax))
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, value.exposure.coerceIn(info.evMin, info.evMax))
        }
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        if (value.look == Look.PURE) {
            if (info.noiseOff) builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            if (info.edgeOff) builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            if (c[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES]?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF) == true)
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }
    }

    private fun repeat() {
        val camera = device ?: return
        val target = surface ?: return
        val s = session ?: return
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(target); apply(this, settings) }.build()
            s.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    if (this@CameraEngine.session !== session) return
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastMetering > 400) {
                        lastMetering = now
                        publish(state.copy(iso = result[CaptureResult.SENSOR_SENSITIVITY] ?: 0, shutterNs = result[CaptureResult.SENSOR_EXPOSURE_TIME] ?: 0))
                    }
                }
            }, handler)
        } catch (e: Exception) { fail(e.message ?: "Preview interrupted.") }
    }

    fun capture() { handler.post {
        if (!state.ready || state.busy) return@post
        val camera = device ?: return@post
        val c = characteristics ?: return@post
        val value = settings
        val rotation = CaptureMath.jpegRotation(c[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90,
            orientation, c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT)
        val job = Pending(value, value.look == Look.PURE && rawReader != null, rotation)
        pending = job
        publish(state.copy(busy = true, error = false, message = "Holding this moment…"))
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(requireNotNull(jpegReader).surface)
                if (job.wantsRaw) addTarget(requireNotNull(rawReader).surface)
                apply(this, value)
                set(CaptureRequest.JPEG_ORIENTATION, rotation)
                set(CaptureRequest.JPEG_QUALITY, 97.toByte())
            }.build()
            requireNotNull(session).capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    if (pending !== job) return
                    job.result = result; finishCapture()
                }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                    if (pending === job) captureFailed("Capture failed. Try again.")
                }
            }, handler)
            captureTimeout = Runnable { if (pending === job) {
                captureFailed("Capture timed out. Reopening the camera…")
                closeCamera(); open()
            } }.also { handler.postDelayed(it, 8000) }
        } catch (e: Exception) { captureFailed(e.message ?: "Capture failed.") }
    } }

    private fun finishCapture() {
        val job = pending ?: return
        val jpeg = job.jpeg ?: return
        val result = job.result ?: return
        if (job.wantsRaw && job.raw == null) return
        val timestamp = result[CaptureResult.SENSOR_TIMESTAMP]
        if (!CaptureMath.matchingFrames(timestamp, job.jpegTimestamp, job.raw?.timestamp, job.wantsRaw)) {
            captureFailed("Capture frames did not match. Try again."); return
        }
        val c = requireNotNull(characteristics)
        val image = job.raw
        val lease = if (image != null) requireNotNull(rawOwner).acquire() else null
        pending = null
        clearCaptureTimeout()
        savingCount++
        publish(state.copy(busy = occupied(), saving = savingCount, message = "Saving…"))
        try {
            writer.execute {
                val outcome = runCatching {
                    val writeRaw: ((java.io.OutputStream) -> Unit)? = image?.let { rawImage ->
                        { output ->
                            DngCreator(c, result).use { dng ->
                                dng.setOrientation(when (job.rotation) { 90 -> 6; 180 -> 3; 270 -> 8; else -> 1 })
                                dng.writeImage(output, rawImage)
                            }
                        }
                    }
                    savePhoto(jpeg, writeRaw, job.settings).also { uri ->
                        preferences.edit().putString("lastPhoto", uri.toString()).apply()
                    }
                }
                // Release the reader before advertising queue capacity, including failed writes.
                runCatching { image?.close() }
                runCatching { lease?.close() }
                handler.post {
                    savingCount--
                    outcome.onSuccess { uri ->
                        // A completed older save must not hide a newer capture/camera failure.
                        publish(state.copy(busy = occupied(), saving = savingCount, savedUri = uri.toString(),
                            message = if (state.error || pending != null) state.message else if (image != null) "Saved · DNG + JPEG"
                                else "Saved · ${job.settings.look.name.lowercase().replaceFirstChar { it.uppercase() }} JPEG"))
                    }.onFailure { failure ->
                        publish(state.copy(busy = occupied(), saving = savingCount, error = true, message = "Couldn’t save: ${failure.message ?: "storage unavailable"}"))
                    }
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            savingCount--
            runCatching { image?.close() }; runCatching { lease?.close() }
            captureFailed("Couldn’t save: ${e.message}")
        }
    }

    private fun clearCaptureTimeout() {
        captureTimeout?.let(handler::removeCallbacks)
        captureTimeout = null
    }
    private fun captureFailed(message: String) {
        clearCaptureTimeout()
        pending?.raw?.close(); pending = null
        publish(state.copy(busy = occupied(), saving = savingCount, error = true, message = message))
    }
    private fun fail(message: String) {
        closeCamera()
        publish(state.copy(ready = false, error = true, message = message))
    }
    private fun closeCamera() {
        handler.removeCallbacks(repeatUpdate)
        generation++; opening = false
        if (pending != null) captureFailed("Capture interrupted before saving.")
        session?.close(); session = null
        device?.close(); device = null
        jpegReader?.close(); jpegReader = null
        rawOwner?.close(); rawOwner = null; rawReader = null
        surface?.release(); surface = null
        publish(state.copy(ready = false))
    }
}
