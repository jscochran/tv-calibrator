package tvcal.android.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tvcal.kit.CameraSettings

/** Design §4 and §16.1 (b): which camera the meter picks, with the measured S24+ and Pixel 7 tables. */
class CameraSelectionTest {
    private fun cam(
        id: String,
        back: Boolean = true,
        raw: Boolean = true,
        manual: Boolean = true,
        logical: Boolean = false,
        physical: Set<String> = emptySet(),
        level: Int = 1,
        focal: List<Double> = listOf(5.4),
        apertures: List<Double> = listOf(1.8),
        pixels: Pair<Int, Int> = 4080 to 3060,
        rawSize: Pair<Int, Int> = 4080 to 3060,
        cfa: Int? = 2,
        iso: Int? = 25,
        expMinNs: Long? = 50_000,
        expMaxNs: Long? = 150_000_000,
    ) = CameraCandidate(
        id = id, facingBack = back, raw = raw, manualSensor = manual, readSensorSettings = true, logical = logical,
        physicalIds = physical, hardwareLevel = level, focalLengths = focal, apertures = apertures,
        pixelArrayWidth = pixels.first, pixelArrayHeight = pixels.second, rawWidth = rawSize.first,
        rawHeight = rawSize.second, cfa = cfa, isoMin = iso, exposureMinNs = expMinNs, exposureMaxNs = expMaxNs,
    )

    // Galaxy S24+ (samsung/e2q), from the probe: id 0 logical LEVEL_3, ≤ 0.150 s; 1 front; 2 ultra-wide.
    private val s24Main = cam("0", logical = true, physical = setOf("2", "5", "6"), level = 3)
    private val s24Front = cam("1", back = false, focal = listOf(3.0), rawSize = 3648 to 2736)
    private val s24Wide = cam(
        "2", level = 3, focal = listOf(2.2), apertures = listOf(2.2), pixels = 4000 to 3000,
        rawSize = 4000 to 3000, cfa = 0,
    )
    private val s24Twin23 = cam("23", level = 3, expMinNs = 24_000, expMaxNs = 267_000_000)
    private val s24Twin56 = cam("56", level = 3, expMinNs = 24_000, expMaxNs = 267_000_000)

    private fun chosen(cameras: List<CameraCandidate>): String {
        val c = CameraSelection.choose(cameras)
        assertTrue("expected a camera, got $c", c is CameraChoice.Use)
        return (c as CameraChoice.Use).camera.id
    }

    private fun unsupported(cameras: List<CameraCandidate>): String {
        val c = CameraSelection.choose(cameras)
        assertTrue("expected unsupported, got $c", c is CameraChoice.Unsupported)
        return (c as CameraChoice.Unsupported).message
    }

    @Test
    fun s24PlusUsesLogicalZeroWhenTheTwinsAreHidden() {
        assertEquals("0", chosen(listOf(s24Main, s24Front, s24Wide)))
    }

    @Test
    fun s24PlusPrefersAListedStandaloneTwinWithLongerExposures() {
        assertEquals("56", chosen(listOf(s24Main, s24Front, s24Wide, s24Twin56)))
        // Two equal twins: the lower numeric id.
        assertEquals("23", chosen(listOf(s24Main, s24Front, s24Wide, s24Twin56, s24Twin23)))
        // Better hardware level wins over the id.
        assertEquals("56", chosen(listOf(s24Main, s24Front, s24Twin56, s24Twin23.copy(hardwareLevel = 1))))
    }

    @Test
    fun twinMustMatchTheLensAndGainEnoughExposure() {
        // Same lens but under 1.2x the exposure: stays on 0.
        assertEquals("0", chosen(listOf(s24Main, s24Twin56.copy(exposureMaxNs = 170_000_000))))
        // Just over 1.2x qualifies.
        assertEquals("56", chosen(listOf(s24Main, s24Twin56.copy(exposureMaxNs = 181_000_000))))
        // Another focal length, aperture or RAW size isn't a twin.
        assertEquals("0", chosen(listOf(s24Main, s24Twin56.copy(focalLengths = listOf(7.0)))))
        assertEquals("0", chosen(listOf(s24Main, s24Twin56.copy(apertures = listOf(2.4)))))
        assertEquals("0", chosen(listOf(s24Main, s24Twin56.copy(rawWidth = 8160, rawHeight = 6120))))
        // A logical twin isn't one either.
        assertEquals("0", chosen(listOf(s24Main, s24Twin56.copy(logical = true))))
        // Focal lengths within 0.05 mm match.
        assertEquals("56", chosen(listOf(s24Main, s24Twin56.copy(focalLengths = listOf(5.43)))))
    }

    @Test
    fun aPhysicalPrimaryIsNeverSwappedForATwin() {
        val physicalMain = s24Main.copy(logical = false, physicalIds = emptySet())
        assertEquals("0", chosen(listOf(physicalMain, s24Twin56)))
    }

    @Test
    fun pixel7UsesZero() {
        val main = cam(
            "0", logical = true, physical = setOf("2", "3", "4"), level = 1, focal = listOf(6.81),
            apertures = listOf(1.85), pixels = 4080 to 3072, rawSize = 4080 to 3072, iso = 47,
            expMinNs = 26_000, expMaxNs = 8_300_000_000,
        )
        val front = cam("1", back = false, focal = listOf(2.74))
        assertEquals("0", chosen(listOf(main, front)))
        assertEquals("4080x3072 GBRG", CameraSelection.formatOf(main))
    }

    @Test
    fun anIneligibleFirstBackCameraFallsBackToTheLargestSensor() {
        val firstNoManual = cam("0", manual = false)
        val small = cam("3", pixels = 4000 to 3000, rawSize = 4000 to 3000)
        val big = cam("5", pixels = 8160 to 6120, rawSize = 8160 to 6120)
        val bigToo = cam("4", pixels = 8160 to 6120, rawSize = 8160 to 6120)
        assertEquals("4", chosen(listOf(firstNoManual, small, big, bigToo)))
    }

    @Test
    fun unsupportedPhonesSayWhatIsMissing() {
        assertEquals(CameraSelection.NO_BACK, unsupported(listOf(s24Front)))
        assertEquals(CameraSelection.NO_BACK, unsupported(emptyList()))
        assertEquals(CameraSelection.NO_RAW, unsupported(listOf(s24Front, cam("0", raw = false))))
        assertEquals(CameraSelection.NO_MANUAL, unsupported(listOf(cam("0", manual = false), s24Front)))
        // RAW + MANUAL but monochrome (5): not a Bayer sensor.
        assertEquals(CameraSelection.NO_RAW, unsupported(listOf(cam("0", cfa = 5))))
    }

    @Test
    fun capabilityProblemNeedsOnlyFacingAndCapabilities() {
        // Before the CAMERA permission some keys may read as null: availability doesn't depend on them.
        assertNull(CameraSelection.capabilityProblem(listOf(cam("0", cfa = null, iso = null, expMaxNs = null))))
        assertEquals(CameraSelection.NO_MANUAL, CameraSelection.capabilityProblem(listOf(cam("0", manual = false))))
    }

    @Test
    fun eligibility() {
        assertTrue(CameraSelection.eligible(s24Main))
        assertFalse(CameraSelection.eligible(s24Front))
        for (code in listOf(4, 5, 6, null)) assertFalse(CameraSelection.eligible(cam("0", cfa = code)))
        assertFalse(CameraSelection.eligible(cam("0", rawSize = 0 to 0)))
        assertFalse(CameraSelection.eligible(cam("0", iso = null)))
        assertFalse(CameraSelection.eligible(cam("0", expMaxNs = null)))
    }

    @Test
    fun hardwareRanks() {
        val order = listOf(2, 4, 0, 1, 3).map(CameraSelection::hardwareRank)
        assertEquals(listOf(0, 1, 2, 3, 4), order)
    }

    @Test
    fun formatLensAndSettings() {
        assertEquals("4080x3060 GBRG", CameraSelection.formatOf(s24Main))
        assertEquals("4000x3000 RGGB", CameraSelection.formatOf(s24Wide))
        assertEquals("Back camera 0", CameraSelection.lensOf(s24Main))
        assertEquals(
            CameraSettings(iso = 25.0, format = "4080x3060 GBRG", lens = "Back camera 0", aperture = null),
            CameraSelection.settingsFor(s24Main, null),
        )
        assertEquals(2.8, CameraSelection.settingsFor(s24Main, 2.8).aperture!!, 0.0)
        assertEquals(5.4, CameraSelection.mainFocal(s24Main)!!, 0.0)
        assertNull(CameraSelection.mainFocal(cam("9", focal = emptyList())))
    }

    @Test
    fun apertureLock() {
        assertNull(ApertureLock.choose(listOf(1.8), 2.8))
        assertNull(ApertureLock.choose(listOf(1.5, 2.4), null))
        assertEquals(2.4, ApertureLock.choose(listOf(1.5, 2.4), 2.8)!!, 0.0)
        assertEquals(1.5, ApertureLock.choose(listOf(1.5, 2.4), 1.6)!!, 0.0)
    }
}
