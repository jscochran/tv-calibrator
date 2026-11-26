package tvcal.android.camera

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tvcal.kit.CameraProfile
import tvcal.kit.CameraSettings
import tvcal.kit.MeasureError
import tvcal.kit.PanelMatrix
import tvcal.kit.Vec3

/** CameraXYZMeter (design §14), with a fake camera that answers measure() with a fixed result. */
class CameraXYZMeterTest {
    private class FakeCamera(var answer: Pair<CameraReading?, String?>) : CameraMeter {
        var measures = 0
        override val available = true
        override val running = true
        override val busy = false
        override val message = CameraMeter.READY_POINT
        override val dark: DarkLevel? = DarkLevel(listOf(0.0, 0.0, 0.0), listOf(0.0, 0.0, 0.0))
        override val lastReading: CameraReading? = null
        override val logLines: List<String> = emptyList()
        override var onLog: ((String) -> Unit)? = null
        override val ready = true
        override val settings = CameraSettings(iso = 25.0, format = "4080x3060 GBRG", lens = "Back camera 0")
        override val capturedSize = "4080x3060"
        override fun log(line: String) {}
        override suspend fun start(aperture: Double?) {}
        override fun stop() {}
        override suspend fun measureDark() {}
        override suspend fun measure(): Pair<CameraReading?, String?> {
            measures++
            return answer
        }
    }

    private val trainedWith = CameraSettings(iso = 25.0, format = "4080x3060 GBRG", lens = "Back camera 0")
    private val m = listOf(listOf(1.0, 0.5, 0.0), listOf(0.0, 2.0, 0.0), listOf(0.25, 0.0, 3.0))
    private val woled = listOf(listOf(2.0, 0.0, 0.0), listOf(0.0, 2.0, 0.0), listOf(0.0, 0.0, 2.0))
    private val profile = CameraProfile(
        model = "samsung/e2q", name = "Galaxy S24+", version = 1, matrix = m, camera = trainedWith,
        panels = mapOf("woled" to PanelMatrix(matrix = woled)),
    )

    private fun reading(settings: CameraSettings = trainedWith, rgb: List<Double> = listOf(100.0, 200.0, 40.0)) =
        CameraReading(rgb = rgb, duration = 0.05, uneven = 0.0, peak = 0.4, settings = settings, capturedAt = 1.0)

    @Test fun integrationIsAQuarterSecond() {
        assertEquals(0.25, CameraXYZMeter(FakeCamera(null to CameraMeter.NO_PHOTO), profile).integration, 0.0)
    }

    @Test fun readingThroughTheGeneralMatrix() = runTest {
        val meter = CameraXYZMeter(FakeCamera(reading() to null), profile)
        val r = meter.read()
        assertVec(Vec3(100.0 + 100.0, 400.0, 25.0 + 120.0), r.xyz)
        assertTrue(r.end >= r.start)
    }

    @Test fun aPanelMatrixIsUsed() = runTest {
        val r = CameraXYZMeter(FakeCamera(reading() to null), profile, panel = "woled").read()
        assertVec(Vec3(200.0, 400.0, 80.0), r.xyz)
        // A panel the profile doesn't know uses the general matrix.
        val other = CameraXYZMeter(FakeCamera(reading() to null), profile, panel = "qdoled").read()
        assertVec(Vec3(200.0, 400.0, 145.0), other.xyz)
    }

    @Test fun tooBrightThrows() = runTest {
        val meter = CameraXYZMeter(FakeCamera(null to CameraMeter.TOO_BRIGHT), profile)
        try {
            meter.read()
            fail("expected a MeasureError")
        } catch (e: MeasureError.Message) {
            assertEquals(CameraMeter.TOO_BRIGHT, e.s)
        }
    }

    @Test fun anotherProblemIsAMissedReadingAfterAPause() = runTest {
        val cam = FakeCamera(null to CameraMeter.NO_PHOTO)
        val meter = CameraXYZMeter(cam, profile)
        val before = testScheduler.currentTime
        val r = meter.read()
        assertNull(r.xyz)
        assertTrue("paused ${testScheduler.currentTime - before} ms", testScheduler.currentTime - before >= 300)
        assertEquals(1, cam.measures)

        // No problem text at all: also a missed reading.
        cam.answer = null to null
        assertNull(meter.read().xyz)
    }

    @Test fun settingsMismatchThrowsTheAndroidMessage() = runTest {
        val now = CameraSettings(iso = 50.0, format = "4080x3060 GBRG", lens = "Back camera 56")
        val meter = CameraXYZMeter(FakeCamera(reading(settings = now) to null), profile)
        try {
            meter.read()
            fail("expected a MeasureError")
        } catch (e: MeasureError.Message) {
            assertEquals(
                "The camera is running with different settings than its profile was trained with " +
                    "(ISO: profile 25, now 50; lens: profile Back camera 0, now Back camera 56). " +
                    "This can follow an Android update: check for a newer profile in Settings.",
                e.s,
            )
        }
    }

    @Test fun noSettingsCheckWhenOff() = runTest {
        val now = CameraSettings(iso = 50.0, format = "4080x3072 GBRG", lens = "Back camera 2")
        val r = CameraXYZMeter(FakeCamera(reading(settings = now) to null), profile, checkSettings = false).read()
        assertNotNull(r.xyz)
    }

    private fun assertVec(expected: Vec3, got: Vec3?) {
        assertNotNull(got)
        for (i in 0 until 3) assertEquals("[$i]", expected[i], got!![i], 1e-12)
    }
}
