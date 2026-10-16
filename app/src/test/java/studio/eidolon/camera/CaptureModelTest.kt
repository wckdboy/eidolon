package studio.eidolon.camera

import org.junit.Assert.*
import org.junit.Test

class CaptureModelTest {
    @Test fun bloomStrengthDoesNotChangeSensorControls() {
        val settings = CaptureSettings(look = Look.BLOOM)
        assertTrue(settings.sameSensorControls(settings.copy(bloom = 0.1f)))
        assertFalse(settings.sameSensorControls(settings.copy(look = Look.PURE)))
        assertFalse(settings.sameSensorControls(settings.copy(iso = 400)))
        assertFalse(settings.sameSensorControls(settings.copy(shutterNs = 20_000_000)))
        assertFalse(settings.sameSensorControls(settings.copy(exposure = 1)))
        assertFalse(settings.sameSensorControls(settings.copy(focus = 2f)))
    }

    @Test fun missingOrMismatchedTimestampsNeverPublishAPair() {
        assertFalse(CaptureMath.matchingFrames(null, null, null, false))
        assertFalse(CaptureMath.matchingFrames(7, 8, 7, true))
        assertFalse(CaptureMath.matchingFrames(7, 7, 8, true))
        assertFalse(CaptureMath.matchingFrames(7, 7, null, true))
        assertTrue(CaptureMath.matchingFrames(7, 7, null, false))
        assertTrue(CaptureMath.matchingFrames(7, 7, 7, true))
    }

    @Test fun zeroBloomPreservesEveryChannel() {
        assertArrayEquals(floatArrayOf(1f,0f,0f,0f,0f, 0f,1f,0f,0f,0f,
            0f,0f,1f,0f,0f, 0f,0f,0f,1f,0f), BloomColor.matrix(0f), 0.00001f)
    }
    @Test fun bloomClampsIntensityAndPreservesAlpha() {
        assertArrayEquals(BloomColor.matrix(1f), BloomColor.matrix(10f), 0f)
        assertArrayEquals(BloomColor.matrix(0f), BloomColor.matrix(-1f), 0f)
        assertEquals(1f, BloomColor.matrix(0.6f)[18], 0f)
    }
    @Test fun rotationHandlesBothSensorOrientationsAndFacings() {
        assertEquals(90, CaptureMath.jpegRotation(90, 0, false))
        assertEquals(180, CaptureMath.jpegRotation(90, 90, false))
        assertEquals(0, CaptureMath.jpegRotation(90, 90, true))
        assertEquals(0, CaptureMath.jpegRotation(270, 90, false))
        assertEquals(180, CaptureMath.jpegRotation(270, 90, true))
    }
}
