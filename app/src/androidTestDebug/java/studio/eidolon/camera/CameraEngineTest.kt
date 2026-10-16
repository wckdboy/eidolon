package studio.eidolon.camera

import android.Manifest
import android.content.ContentUris
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraEngineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun awaitState(queue: LinkedBlockingQueue<CameraState>, predicate: (CameraState) -> Boolean): CameraState {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        var last: CameraState? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            last = state
            assertFalse(state.message, state.error)
            if (predicate(state)) return state
        }
        error("Camera did not reach expected state. Last: $last")
    }

    private fun photos(): Set<Uri> = context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.RELATIVE_PATH} = ?", arrayOf("Pictures/Eidolon/"), null
    )!!.use { cursor -> buildSet {
        while (cursor.moveToNext()) add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)))
    } }

    @Test fun rawSaveSurvivesCameraClosureAndReopeningAllowsBloomCapture() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val before = photos()
        val prefs = context.getSharedPreferences("camera", android.content.Context.MODE_PRIVATE)
        val previousPhoto = prefs.getString("lastPhoto", null)
        try {
            ActivityScenario.launch(CameraHarnessActivity::class.java).use { scenario ->
                lateinit var queue: LinkedBlockingQueue<CameraState>
                scenario.onActivity { queue = it.states }
                val ready = awaitState(queue) { it.ready }
                scenario.onActivity { it.closeWhenSaving = true; it.engine.capture() }
                val saved = awaitState(queue) { !it.busy && it.message.startsWith("Saved") }
                val firstPhotos = photos() - before
                assertEquals(if (ready.info.raw) 2 else 1, firstPhotos.size)
                val jpeg = Uri.parse(saved.savedUri!!)
                context.contentResolver.openInputStream(jpeg)!!.use {
                    val bitmap = BitmapFactory.decodeStream(it)
                    assertNotNull("Saved JPEG must decode", bitmap)
                    bitmap.recycle()
                }
                if (ready.info.raw) {
                    val raw = (firstPhotos - jpeg).single()
                    context.contentResolver.openInputStream(raw)!!.use {
                        assertTrue("DNG must contain image dimensions", ExifInterface(it).getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0) > 0)
                    }
                }
                queue.clear()
                scenario.onActivity { it.engine.start() }
                awaitState(queue) { it.ready }
                scenario.onActivity {
                    it.engine.update(CaptureSettings(look = Look.BLOOM, bloom = 0.7f))
                    it.engine.capture()
                }
                val bloom = awaitState(queue) { !it.busy && it.message == "Saved · Bloom JPEG" }
                assertNotEquals(saved.savedUri, bloom.savedUri)
                assertEquals(firstPhotos.size + 1, (photos() - before).size)
            }
        } finally {
            (photos() - before).forEach { context.contentResolver.delete(it, null, null) }
            prefs.edit().putString("lastPhoto", previousPhoto).commit()
        }
    }

    @Test fun repeatedCapturesAfterRapidAdjustmentsAndLensSwitching() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val before = photos()
        val prefs = context.getSharedPreferences("camera", android.content.Context.MODE_PRIVATE)
        val previousPhoto = prefs.getString("lastPhoto", null)
        val timings = mutableListOf<String>()
        try {
            ActivityScenario.launch(CameraHarnessActivity::class.java).use { scenario ->
                lateinit var queue: LinkedBlockingQueue<CameraState>
                scenario.onActivity { queue = it.states }
                var ready = awaitState(queue) { it.ready }
                var previousUri = ready.savedUri
                var expectedFiles = 0
                repeat(8) { shot ->
                    if (shot > 0 && shot % 2 == 0 && ready.info.cameraCount > 1) {
                        queue.clear()
                        scenario.onActivity { it.engine.flip() }
                        awaitState(queue) { !it.ready }
                        ready = awaitState(queue) { it.ready }
                    }
                    val look = if (shot % 2 == 0) Look.PURE else Look.BLOOM
                    queue.clear()
                    val started = SystemClock.elapsedRealtime()
                    scenario.onActivity { activity ->
                        // Exercise coalescing: many changes immediately followed by capture.
                        repeat(100) { step ->
                            activity.engine.update(CaptureSettings(look = look, bloom = step / 100f,
                                exposure = if (step % 2 == 0) ready.info.evMin else ready.info.evMax))
                        }
                        activity.engine.update(CaptureSettings(look = look, bloom = 0.7f))
                        activity.engine.capture()
                    }
                    val saved = awaitState(queue) { !it.busy && it.savedUri != previousUri && it.message.startsWith("Saved") }
                    timings += "$shot,${look.name},${ready.info.raw},${SystemClock.elapsedRealtime() - started}"
                    previousUri = saved.savedUri
                    context.contentResolver.openInputStream(Uri.parse(saved.savedUri!!))!!.use {
                        val bitmap = BitmapFactory.decodeStream(it)
                        assertNotNull("Shot $shot must decode", bitmap)
                        bitmap.recycle()
                    }
                    expectedFiles += if (look == Look.PURE && ready.info.raw) 2 else 1
                    assertEquals("Each shot must publish exactly its expected files", expectedFiles, (photos() - before).size)
                }
                val output = "shot,look,raw_capable,adjustments_to_saved_ms\n" + timings.joinToString("\n") + "\n"
                android.util.Log.i("EidolonPerformance", output)
            }
        } finally {
            (photos() - before).forEach { context.contentResolver.delete(it, null, null) }
            prefs.edit().putString("lastPhoto", previousPhoto).commit()
        }
    }
}
