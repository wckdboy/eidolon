package studio.eidolon.camera

import java.io.OutputStream

/** Adds a prepared EXIF APP1 segment after SOI without buffering or rewriting the JPEG. */
internal class JpegMetadataStream(private val output: OutputStream, private val app1: ByteArray) : OutputStream() {
    private var headerBytes = 0
    override fun write(value: Int) {
        if (headerBytes < 2) {
            require(value and 255 == if (headerBytes == 0) 255 else 216) { "Invalid JPEG header" }
            output.write(value)
            if (++headerBytes == 2) output.write(app1)
        } else output.write(value)
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        var start = offset
        val end = offset + length
        while (headerBytes < 2 && start < end) write(bytes[start++].toInt() and 255)
        if (start < end) output.write(bytes, start, end - start)
    }
    override fun flush() = output.flush()

    companion object {
        fun exifSegment(jpeg: ByteArray): ByteArray {
            require(jpeg.size >= 4 && jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte())
            var offset = 2
            while (offset + 4 <= jpeg.size) {
                require(jpeg[offset] == 0xff.toByte())
                val marker = jpeg[offset + 1].toInt() and 255
                if (marker == 0xda || marker == 0xd9) break
                val length = ((jpeg[offset + 2].toInt() and 255) shl 8) or (jpeg[offset + 3].toInt() and 255)
                require(length >= 2 && offset + 2 + length <= jpeg.size)
                if (marker == 0xe1 && length >= 8 &&
                    jpeg.copyOfRange(offset + 4, offset + 10).contentEquals(byteArrayOf(69,120,105,102,0,0))) {
                    return jpeg.copyOfRange(offset, offset + 2 + length)
                }
                offset += 2 + length
            }
            error("Missing EXIF segment")
        }
    }
}
