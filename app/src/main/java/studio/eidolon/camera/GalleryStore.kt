package studio.eidolon.camera

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal data class GalleryPhoto(val id: Long, val uri: String, val name: String, val timestamp: Long,
    val width: Int, val height: Int, val bytes: Long, val rawUri: String? = null) {
    val bloom: Boolean get() = name.contains("_BLOOM")
}

/** Scoped to Eidolon's folder and the app-owned rows available without library permissions. */
internal class GalleryStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val thumbnails = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val reads = Semaphore(2)

    suspend fun photos(): List<GalleryPhoto> = withContext(Dispatchers.IO) {
        val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE, MediaStore.Images.Media.MIME_TYPE)
        val images = mutableListOf<GalleryPhoto>()
        val raw = mutableMapOf<String, String>()
        resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columns,
            "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.IS_PENDING} = 0",
            arrayOf("Pictures/Eidolon/"), "${MediaStore.Images.Media._ID} DESC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val name = cursor.getString(1) ?: continue
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString()
                if (cursor.getString(6) == "image/x-adobe-dng") raw[name.substringBeforeLast('.')] = uri
                else if (cursor.getString(6) == "image/jpeg") images += GalleryPhoto(id, uri, name,
                    cursor.getLong(2) * 1000, cursor.getInt(3), cursor.getInt(4), cursor.getLong(5))
            }
        }
        images.map { it.copy(rawUri = raw[it.name.substringBeforeLast('.')]) }
    }

    suspend fun thumbnail(photo: GalleryPhoto): Bitmap? = withContext(Dispatchers.IO) {
        thumbnails.get(photo.uri) ?: reads.withPermit {
            thumbnails.get(photo.uri) ?: runCatching {
                resolver.loadThumbnail(Uri.parse(photo.uri), Size(300, 300), null)
            }.getOrNull()?.also { thumbnails.put(photo.uri, it) }
        }
    }

    suspend fun image(photo: GalleryPhoto): Bitmap = withContext(Dispatchers.IO) {
        reads.withPermit {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, Uri.parse(photo.uri))) { decoder, info, _ ->
                val scale = minOf(1f, 3072f / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
    }

    suspend fun details(photo: GalleryPhoto): String = withContext(Dispatchers.IO) {
        val exposure = resolver.openInputStream(Uri.parse(photo.uri))?.use { input ->
            val exif = ExifInterface(input)
            val iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
            val seconds = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
            val aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
            listOfNotNull(iso?.let { "ISO $it" }, seconds.takeIf { it > 0 }?.let { CaptureMath.shutterLabel((it * 1e9).toLong()) },
                aperture?.let { "ƒ/$it" }).joinToString(" · ")
        }.orEmpty()
        listOf(photo.name, "${photo.width} × ${photo.height}", "%.1f MB".format(java.util.Locale.US, photo.bytes / 1e6),
            exposure, if (photo.rawUri != null) "RAW + JPEG · showing companion JPEG" else "JPEG").filter { it.isNotBlank() }.joinToString("\n")
    }

    suspend fun delete(photo: GalleryPhoto) = withContext(Dispatchers.IO) {
        // A capture is one gallery item. Delete its RAW companion first so a failed RAW delete
        // leaves the visible JPEG available for retry instead of hiding an orphaned DNG.
        photo.rawUri?.let { resolver.delete(Uri.parse(it), null, null) }
        resolver.delete(Uri.parse(photo.uri), null, null)
        thumbnails.remove(photo.uri)
        Unit
    }

    fun clearThumbnails() = thumbnails.evictAll()
}
