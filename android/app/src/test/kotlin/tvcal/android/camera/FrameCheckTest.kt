package tvcal.android.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design §3.4 and §16.1 (b): every RAW frame's CaptureResult against its request. */
class FrameCheckTest {
    private val asked = FrameRequest(exposureNs = 4_000_000, iso = 25, focusDiopters = 10.0, physicalId = "2")
    private val good = FrameReport(
        exposureNs = 4_000_000, iso = 25, focusDiopters = 10.0, lensState = 0, blackLevelLock = true,
        activePhysicalId = "2", aeMode = 0,
    )

    @Test
    fun aMatchingFrameIsUsable() {
        assertNull(FrameCheck.problem(asked, good))
    }

    @Test
    fun missingKeysAreNotFailures() {
        val none = FrameReport(null, null, null, null, null, null, null)
        assertNull(FrameCheck.problem(asked, none))
        assertNull(FrameCheck.problem(asked.copy(focusDiopters = null, physicalId = null), good))
    }

    @Test
    fun exposureWithinTenPercent() {
        assertNull(FrameCheck.problem(asked, good.copy(exposureNs = 4_400_000)))
        assertNull(FrameCheck.problem(asked, good.copy(exposureNs = 3_600_000)))
        assertEquals(
            "exposure 0.0044 s, asked 0.0040 s",
            FrameCheck.problem(asked, good.copy(exposureNs = 4_400_001)),
        )
        assertTrue(FrameCheck.problem(asked, good.copy(exposureNs = 3_599_999))!!.startsWith("exposure"))
        // Short exposures print with more digits.
        val short = FrameRequest(125_000, 25, null, null)
        assertEquals(
            "exposure 0.000250 s, asked 0.000125 s",
            FrameCheck.problem(short, good.copy(exposureNs = 250_000)),
        )
    }

    @Test
    fun isoMustMatch() {
        assertEquals("ISO 50, asked 25", FrameCheck.problem(asked, good.copy(iso = 50)))
    }

    @Test
    fun blackLevelLockReleased() {
        // Released locks are logged, not rejected: the frame's own dynamic black level is subtracted.
        assertNull(FrameCheck.problem(asked, good.copy(blackLevelLock = false)))
    }

    @Test
    fun blackLevelLockNeverHeldIsFine() {
        // A Pixel 7 reports the lock off on every frame: nothing was ever locked, so nothing was released.
        assertNull(FrameCheck.problem(asked, good.copy(blackLevelLock = false), lockHonoured = false))
    }

    @Test
    fun lensMovingAndFocus() {
        assertEquals("lens moving", FrameCheck.problem(asked, good.copy(lensState = 1)))
        // max(5% of 10 D, 0.1 D) = 0.5 D.
        assertNull(FrameCheck.problem(asked, good.copy(focusDiopters = 9.5)))
        assertNull(FrameCheck.problem(asked, good.copy(focusDiopters = 10.5)))
        assertEquals("focus 9.4 D, asked 10.0 D", FrameCheck.problem(asked, good.copy(focusDiopters = 9.4)))
        // Near infinity the 0.1 D floor applies.
        val far = asked.copy(focusDiopters = 1.0)
        assertNull(FrameCheck.problem(far, good.copy(focusDiopters = 1.1)))
        assertEquals("focus 1.2 D, asked 1.0 D", FrameCheck.problem(far, good.copy(focusDiopters = 1.2)))
    }

    @Test
    fun lensSwitchAndAutoExposure() {
        assertEquals("lens switched to 4 (was 2)", FrameCheck.problem(asked, good.copy(activePhysicalId = "4")))
        assertEquals("auto exposure on", FrameCheck.problem(asked, good.copy(aeMode = 1)))
    }
}
