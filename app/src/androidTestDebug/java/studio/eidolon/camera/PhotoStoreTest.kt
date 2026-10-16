package studio.eidolon.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val created = mutableListOf<Uri>()

    @After fun cleanUp() { created.forEach { context.contentResolver.delete(it, null, null) } }

    private fun fixture(): ByteArray {
        val file = File.createTempFile("test-image", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(100, 110, 120))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it) }
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, "6")
                setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, "400")
                setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.008")
                saveAttributes()
            }
            return file.readBytes()
        } finally { bitmap.recycle(); file.delete() }
    }

    private fun save(bytes: ByteArray, settings: CaptureSettings): ByteArray {
        val uri = PhotoStore(context).save(bytes, null, settings).also(created::add)
        return context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    }

    @Test fun purePreservesOriginalJpegBytes() {
        val input = fixture()
        assertArrayEquals(input, save(input, CaptureSettings()))
    }

    @Test fun zeroBloomPreservesOriginalWithoutLossyRecompression() {
        val input = fixture()
        assertArrayEquals(input, save(input, CaptureSettings(look = Look.BLOOM, bloom = 0f)))
    }

    @Test fun bloomChangesPixelsButPreservesExposureAndOrientation() {
        val input = fixture()
        val output = save(input, CaptureSettings(look = Look.BLOOM, bloom = 1f))
        val original = BitmapFactory.decodeByteArray(input, 0, input.size)
        val rendered = BitmapFactory.decodeByteArray(output, 0, output.size)
        try {
            assertEquals(original.width, rendered.width)
            assertEquals(original.height, rendered.height)
            assertNotEquals(original.getPixel(20, 20), rendered.getPixel(20, 20))
        } finally { original.recycle(); rendered.recycle() }
        val exif = ByteArrayInputStream(output).use { ExifInterface(it) }
        assertEquals(6, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertEquals(64, exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0))
        assertEquals(48, exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0))
        assertEquals(64, exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0))
        assertEquals(48, exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0))
        assertFalse(exif.hasThumbnail())
        assertEquals(400, exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0))
        assertEquals(0.008, exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0), 0.00001)
    }

    @Suppress("DEPRECATION")
    private fun photoCount(): Int = context.contentResolver.query(
        MediaStore.setIncludePending(MediaStore.Images.Media.EXTERNAL_CONTENT_URI), arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.RELATIVE_PATH} = ?", arrayOf("Pictures/Eidolon/"), null
    )!!.use { it.count }

    @Test fun failedRawWriteRollsBackTheEntirePair() {
        val before = photoCount()
        assertThrows(IOException::class.java) {
            PhotoStore(context).save(fixture(), { throw IOException("Simulated storage failure") }, CaptureSettings())
        }
        assertEquals(before, photoCount())
    }

    @Test fun failedBloomDecodeRollsBackPendingPhoto() {
        val before = photoCount()
        assertThrows(IllegalArgumentException::class.java) {
            PhotoStore(context).save(byteArrayOf(1, 2, 3), null, CaptureSettings(look = Look.BLOOM))
        }
        assertEquals(before, photoCount())
    }
}
