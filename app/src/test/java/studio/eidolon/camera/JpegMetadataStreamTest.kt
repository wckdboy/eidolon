package studio.eidolon.camera

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class JpegMetadataStreamTest {
    private val app1 = byteArrayOf(-1, -31, 0, 10, 69, 120, 105, 102, 0, 0, 1, 2)
    private val jpeg = byteArrayOf(-1, -40, -1, -32, 0, 4, 0, 1, -1, -38, 0, 2, 7, 8, -1, -39)

    @Test fun insertsMetadataOnceWithEveryHeaderFragmentation() {
        for (split in 0..jpeg.size) {
            val output = ByteArrayOutputStream()
            val stream = JpegMetadataStream(output, app1)
            stream.write(jpeg, 0, split)
            stream.write(jpeg, split, jpeg.size - split)
            stream.flush()
            assertArrayEquals(jpeg.take(2).toByteArray() + app1 + jpeg.drop(2).toByteArray(), output.toByteArray())
            assertArrayEquals(app1, JpegMetadataStream.exifSegment(output.toByteArray()))
        }
    }

    @Test fun singleByteWritesAndNonExifApp1AreHandled() {
        val output = ByteArrayOutputStream()
        val stream = JpegMetadataStream(output, app1)
        jpeg.forEach { stream.write(it.toInt()) }
        assertArrayEquals(app1, JpegMetadataStream.exifSegment(output.toByteArray()))
        val other = byteArrayOf(-1, -31, 0, 4, 5, 6)
        assertArrayEquals(app1, JpegMetadataStream.exifSegment(jpeg.take(2).toByteArray() + other + app1 + jpeg.drop(2).toByteArray()))
    }

    @Test fun rejectsInvalidHeadersAndTruncatedMetadata() {
        assertThrows(IllegalArgumentException::class.java) { JpegMetadataStream(ByteArrayOutputStream(), app1).write(0) }
        assertThrows(IllegalArgumentException::class.java) { JpegMetadataStream.exifSegment(byteArrayOf(-1, -40, -1, -31, 0, 12, 1)) }
        assertThrows(IllegalStateException::class.java) { JpegMetadataStream.exifSegment(jpeg) }
    }
}
