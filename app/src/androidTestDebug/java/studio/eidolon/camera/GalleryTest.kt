package studio.eidolon.camera

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.media.ExifInterface
import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import android.view.TextureView
import android.view.View
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GalleryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun fixture(width: Int, height: Int): ByteArray {
        val file = File.createTempFile("gallery-fixture", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(0xff285464.toInt(), 0xffd8a47c.toInt(), 0xff325c48.toInt()), null, Shader.TileMode.CLAMP) })
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it) }
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, "6")
                setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, "200")
                saveAttributes()
            }
            return file.readBytes()
        } finally { bitmap.recycle(); file.delete() }
    }

    @Test fun fullResolutionBloomOpensOrientedBoundedViewerAndDeletes() = runBlocking {
        val store = GalleryStore(context)
        val bytes = fixture(4000, 3000)
        val created = mutableListOf<Uri>()
        try {
            repeat(3) { index ->
                val started = SystemClock.elapsedRealtime()
                val uri = PhotoStore(context).save(bytes, null, CaptureSettings(look = Look.BLOOM)).also(created::add)
                android.util.Log.i("EidolonPerformance", "12MP_bloom_sample=$index save_ms=${SystemClock.elapsedRealtime() - started}")
                val photo = store.photos().single { it.uri == uri.toString() }
                assertTrue(photo.bloom)
                context.contentResolver.openInputStream(uri)!!.use {
                    val exif = ExifInterface(it)
                    assertEquals(4000, exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0))
                    assertEquals(3000, exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0))
                }
                assertNotNull(store.thumbnail(photo))
                assertSame(store.thumbnail(photo), store.thumbnail(photo))
                val image = store.image(photo)
                assertEquals(2304, image.width)
                assertEquals(3072, image.height)
                image.recycle()
                assertEquals("200", store.details(photo).iso)
                store.delete(photo)
                created.remove(uri)
                assertFalse(store.photos().any { it.id == photo.id })
            }
        } finally {
            store.clearThumbnails()
            created.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        }
    }

    @Test fun galleryViewerDetailsDeleteAndReturnToLiveCamera() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        }
        val store = GalleryStore(context)
        val preferences = context.getSharedPreferences("camera", android.content.Context.MODE_PRIVATE)
        val previousPhoto = preferences.getString("lastPhoto", null)
        val pureUri = PhotoStore(context).save(fixture(64, 48), null, CaptureSettings())
        val uri = PhotoStore(context).save(fixture(64, 48), null, CaptureSettings(look = Look.BLOOM))
        val photo = runBlocking { store.photos().single { it.uri == uri.toString() } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Open gallery").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Open gallery").performClick()
                compose.onNodeWithText("Library").assertExists()
                scenario.onActivity { assertFalse("Gallery must detach the live preview", hasTexture(it.window.decorView)) }
                compose.onNodeWithText("Bloom").performClick()
                val label = "Open Bloom photo ${photo.name}"
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription(label).performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Photo").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Photo").performTouchInput { click() }
                compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Photo details").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithContentDescription("Photo").performTouchInput { click() }
                compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Photo details").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Photo").performTouchInput { doubleClick() }
                compose.onNodeWithContentDescription("Photo").performTouchInput { doubleClick() }
                compose.onNodeWithContentDescription("Photo").performTouchInput { swipeLeft() }
                compose.waitUntil(5_000) { compose.onAllNodesWithText("2 /", substring = true).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Photo").performTouchInput { swipeRight() }
                compose.waitUntil(5_000) { compose.onAllNodesWithText("1 /", substring = true).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Photo details").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("200").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Done").performClick()
                compose.onNodeWithText("Delete").performClick()
                compose.onNodeWithText("Keep").performClick()
                assertTrue(runBlocking { store.photos().any { it.id == photo.id } })
                compose.onNodeWithText("Delete").performClick()
                compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Library").fetchSemanticsNodes().isNotEmpty() }
                assertFalse(runBlocking { store.photos().any { it.id == photo.id } })
                compose.onNodeWithText("Bloom").assertIsSelected()
                compose.waitUntil(5_000) { preferences.getString("lastPhoto", null) == pureUri.toString() }
                compose.onNodeWithContentDescription("Back to camera").performClick()
                compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Take photo").fetchSemanticsNodes().any {
                    !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                } }
                scenario.onActivity { assertTrue(hasTexture(it.window.decorView)) }
            }
        } finally {
            listOf(uri, pureUri).forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            store.clearThumbnails()
            preferences.edit().putString("lastPhoto", previousPhoto).commit()
        }
    }

    private fun hasTexture(view: View): Boolean = view is TextureView ||
        (view is ViewGroup && (0 until view.childCount).any { hasTexture(view.getChildAt(it)) })
}
