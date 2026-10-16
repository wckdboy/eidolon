package studio.eidolon.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhotoStore(private val context: Context) {
    /** Publish the pair only after both files have been written successfully. */
    fun save(jpeg: ByteArray, writeRaw: ((OutputStream) -> Unit)?, settings: CaptureSettings): Uri {
        val stem = "EIDOLON_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}"
        val pending = mutableListOf<Uri>()
        var rendered: File? = null
        try {
            rendered = if (settings.look == Look.BLOOM && settings.bloom > 0f) renderBloom(jpeg, settings.bloom) else null
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
            requireNotNull(context.contentResolver.openOutputStream(photo)).use { out ->
                if (rendered != null) rendered.inputStream().use { it.copyTo(out) } else out.write(jpeg)
            }
            writeRaw?.let { write ->
                val uri = insert("dng", "image/x-adobe-dng")
                requireNotNull(context.contentResolver.openOutputStream(uri)).use(write)
            }
            pending.forEach { uri ->
                check(context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1) { "Photo publication failed" }
            }
            return photo
        } catch (e: Exception) {
            pending.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            throw e
        } finally { rendered?.delete() }
    }

    private fun renderBloom(bytes: ByteArray, strength: Float): File {
        val output = File.createTempFile("bloom", ".jpg", context.cacheDir)
        var input: Bitmap? = null
        var result: Bitmap? = null
        try {
            input = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            result = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
            Canvas(result).drawBitmap(input, 0f, 0f, Paint().apply {
                colorFilter = ColorMatrixColorFilter(BloomColor.matrix(strength))
            })
            // Release the decoded source before JPEG encoding and EXIF rewriting.
            input.recycle(); input = null
            output.outputStream().use { check(result.compress(Bitmap.CompressFormat.JPEG, 97, it)) }
            val original = ByteArrayInputStream(bytes).use { ExifInterface(it) }
            val edited = ExifInterface(output)
            listOf(ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_ISO_SPEED_RATINGS,
                ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
                ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH).forEach { tag ->
                original.getAttribute(tag)?.let { edited.setAttribute(tag, it) }
            }
            edited.setAttribute(ExifInterface.TAG_SOFTWARE, "Eidolon Bloom 0.1")
            edited.saveAttributes()
            return output
        } catch (e: Exception) { output.delete(); throw e }
        finally { input?.recycle(); result?.recycle() }
    }
}
