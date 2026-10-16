package studio.eidolon.camera

import kotlin.math.roundToInt

enum class Look { PURE, BLOOM }
enum class Workspace { FUN, PRO }

data class CaptureSettings(
    val look: Look = Look.PURE,
    val bloom: Float = 0.6f,
    val exposure: Int = 0,
    val iso: Int? = null,
    val shutterNs: Long? = null,
    val focus: Float? = null,
)

/** Bloom intensity changes only rendering; they must not restart camera requests. */
fun CaptureSettings.sameSensorControls(other: CaptureSettings): Boolean =
    copy(bloom = other.bloom) == other

data class CameraInfo(
    val raw: Boolean = false,
    val manual: Boolean = false,
    val focusMax: Float = 0f,
    val isoMin: Int = 100,
    val isoMax: Int = 1600,
    val shutterMin: Long = 125_000,
    val shutterMax: Long = 100_000_000,
    val evMin: Int = 0,
    val evMax: Int = 0,
    val evStep: Float = 0f,
    val noiseOff: Boolean = false,
    val edgeOff: Boolean = false,
    val cameraCount: Int = 0,
    val aspectRatio: Float = 3f / 4f,
)

data class CameraState(
    val ready: Boolean = false,
    val busy: Boolean = false,
    val info: CameraInfo = CameraInfo(),
    val message: String = "Your eye. Your reality.",
    val error: Boolean = false,
    val savedUri: String? = null,
    val iso: Int = 0,
    val shutterNs: Long = 0,
)

/** A restrained, warm color rendering shared by preview and saved Bloom JPEGs.
 * Pure never passes through this transform. Values are row-major RGBA, 0–255 bias.
 */
object BloomColor {
    fun matrix(amount: Float): FloatArray {
        val a = amount.coerceIn(0f, 1f)
        val saturation = 1f + 0.18f * a
        val contrast = 1f - 0.06f * a
        val luma = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
        return FloatArray(20).also { m ->
            for (row in 0..2) {
                for (col in 0..2) {
                    m[row * 5 + col] = ((1f - saturation) * luma[col] +
                        if (row == col) saturation else 0f) * contrast
                }
                m[row * 5 + 4] = (1f - contrast) * 127.5f + floatArrayOf(4f, 1f, -4f)[row] * a
            }
            m[18] = 1f
        }
    }
}

/** Bounds latency and memory without silently claiming high-resolution support. */
object CaptureMath {
    fun matchingFrames(sensor: Long?, jpeg: Long?, raw: Long?, wantsRaw: Boolean): Boolean =
        sensor != null && sensor == jpeg && (!wantsRaw || sensor == raw)

    fun jpegRotation(sensor: Int, deviceDegrees: Int, front: Boolean): Int =
        (sensor + if (front) -deviceDegrees else deviceDegrees).let { (it + 360) % 360 }

    fun shutterLabel(ns: Long): String = when {
        ns <= 0 -> "AUTO"
        ns >= 1_000_000_000L -> "%.1fs".format(java.util.Locale.US, ns / 1e9)
        ns >= 250_000_000L -> "%.2fs".format(java.util.Locale.US, ns / 1e9)
        else -> "1/${(1e9 / ns).roundToInt().coerceAtLeast(1)}"
    }
}
