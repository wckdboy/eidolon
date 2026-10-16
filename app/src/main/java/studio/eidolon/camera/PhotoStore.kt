package studio.eidolon.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.io.File
import java.io.ByteArrayOutputStream
import android.os.SystemClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhotoStore(private val context: Context) {
    /** Publish the pair only after both files have been written successfully. */
    fun save(jpeg: ByteArray, writeRaw: ((OutputStream) -> Unit)?, settings: CaptureSettings): Uri {
        val started = SystemClock.elapsedRealtime()
        val stem = "EIDOLON_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}"
        val pending = mutableListOf<Uri>()
        try {
            fun insert(extension: String, mime: String): Uri {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "${stem}_${settings.look.name}.$extension")
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Eidolon")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                return requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                    .also { pending += it }
            }
            val photo = insert("jpg", "image/jpeg")
            if (settings.look == Look.BLOOM && settings.bloom > 0f) renderBloom(jpeg, settings.bloom, photo)
            else requireNotNull(context.contentResolver.openOutputStream(photo)).use { it.write(jpeg) }
            writeRaw?.let { write ->
                val uri = insert("dng", "image/x-adobe-dng")
                requireNotNull(context.contentResolver.openOutputStream(uri)).use(write)
            }
            pending.forEach { uri ->
                check(context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1) { "Photo publication failed" }
            }
            Log.d("EidolonProcessing", "${settings.look}: save_ms=${SystemClock.elapsedRealtime() - started}")
            return photo
        } catch (e: Exception) {
            pending.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            throw e
        }
    }

    private fun bloomMetadata(bytes: ByteArray, width: Int, height: Int): ByteArray {
        val original = ByteArrayInputStream(bytes).use { ExifInterface(it) }
        val file = File.createTempFile("eidolon-exif-", ".jpg", context.cacheDir)
        try {
            file.writeBytes(metadataTemplate)
            ExifInterface(file).apply {
                listOf(ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_EXPOSURE_TIME,
                    ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_ISO_SPEED_RATINGS,
                    ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH).forEach { tag ->
                    original.getAttribute(tag)?.let { setAttribute(tag, it) }
                }
                setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
                setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
                setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
                setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
                setAttribute(ExifInterface.TAG_SOFTWARE, "Eidolon Bloom")
                saveAttributes()
            }
            return JpegMetadataStream.exifSegment(file.readBytes())
        } finally { file.delete() }
    }

    companion object {
        private val metadataTemplate: ByteArray by lazy {
            val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            try { ByteArrayOutputStream().use { pixel.compress(Bitmap.CompressFormat.JPEG, 90, it); it.toByteArray() } }
            finally { pixel.recycle() }
        }
    }

    private fun renderBloom(bytes: ByteArray, strength: Float, output: Uri) {
        var input: Bitmap? = null
        var result: Bitmap? = null
        try {
            input = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            result = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
            Canvas(result).drawBitmap(input, 0f, 0f, Paint().apply {
                colorFilter = ColorMatrixColorFilter(BloomColor.matrix(strength))
            })
            // Release the decoded source before streaming the JPEG with its prepared metadata.
            input.recycle(); input = null
            val metadata = bloomMetadata(bytes, result.width, result.height)
            val encodedAt = SystemClock.elapsedRealtime()
            requireNotNull(context.contentResolver.openOutputStream(output)).buffered(64 * 1024).use {
                check(result.compress(Bitmap.CompressFormat.JPEG, 97, JpegMetadataStream(it, metadata)))
            }
            Log.d("EidolonProcessing", "Bloom encode_ms=${SystemClock.elapsedRealtime() - encodedAt}")

        }
        finally { input?.recycle(); result?.recycle() }
    }
}
