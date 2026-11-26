package tvcal.android.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tvcal.kit.CameraSettings
import tvcal.kit.ColourMath
import tvcal.kit.Mat3
import tvcal.kit.ProfileStats
import tvcal.kit.Vec3

/**
 * The approximate profile maths (design §13). Expected numbers were computed independently with numpy 2.4
 * (a straight transcription of the DNG 1.6 spec §6, "Mapping Camera Color Space to CIE XYZ Space"):
 *
 *     xyz = lambda x, y: np.array([x/y, 1, (1-x-y)/y]); D65 = xyz(.3127, .3290); D50 = xyz(.3457, .3585)
 *     MB = Bradford cone matrix; brad = lambda f, t: inv(MB) @ diag((MB@t)/(MB@f)) @ MB
 *     g = clip((1/6504 - 1/t2) / (1/t1 - 1/t2), 0, 1)        # inverse-CCT weight of calibration 1
 *     CM = g*CM1 + (1-g)*CM2; CC = g*CC1 + (1-g)*CC2; FM = g*FM1 + (1-g)*FM2
 *     n = CC@CM@D65; n /= n.max()
 *     r = inv(CC)@n; M = brad(D50, D65) @ FM @ diag(1/r) @ inv(CC); M /= (M@n)[1]      # with FM
 *     M = inv(CC@CM); M /= (M@n)[1]                                                   # without FM
 *
 * for the hand-made phone-like data set in [handMade] (calibration 1 = A, calibration 2 = D65).
 */
class ProvisionalProfileTest {
    private val eye: Mat3 = listOf(listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))
    private val s24Settings = CameraSettings(iso = 25.0, format = "4080x3060 GBRG", lens = "Back camera 0", aperture = null)

    private fun mul(a: Mat3, b: Mat3): Mat3 =
        List(3) { r -> List(3) { c -> (0 until 3).sumOf { k -> a[r][k] * b[k][c] } } }

    private fun diag(v: Vec3): Mat3 = listOf(listOf(v.x, 0.0, 0.0), listOf(0.0, v.y, 0.0), listOf(0.0, 0.0, v.z))

    private fun assertMat(expected: Mat3, got: Mat3?, tol: Double) {
        assertNotNull(got)
        for (r in 0 until 3) for (c in 0 until 3) {
            assertEquals("[$r][$c]", expected[r][c], got!![r][c], tol)
        }
    }

    private fun assertVec(expected: Vec3, got: Vec3?, tol: Double) {
        assertNotNull(got)
        for (i in 0 until 3) assertEquals("[$i]", expected[i], got!![i], tol)
    }

    private fun data(
        cm1: Mat3,
        ill1: Int = 21,
        cm2: Mat3? = null,
        ill2: Int? = null,
        fm1: Mat3? = null,
        fm2: Mat3? = null,
        cc1: Mat3? = null,
        cc2: Mat3? = null,
        iso: Double = 25.0,
        fNumber: Double = 1.8,
        black: Double = 256.0,
        white: Double = 4095.0,
    ) = CameraColourData(ill1, ill2, cm1, cm2, fm1, fm2, cc1, cc2, iso, fNumber, black, white, s24Settings)

    // A hand-made, phone-like DNG set: two calibrations (A, D65), per-unit calibration, forward matrices.
    private val cmA = listOf(listOf(0.9, -0.35, -0.05), listOf(-0.45, 1.3, 0.12), listOf(-0.08, 0.22, 0.62))
    private val cmD65 = listOf(listOf(0.75, -0.2, -0.08), listOf(-0.5, 1.35, 0.17), listOf(-0.1, 0.25, 0.55))
    private val ccA = diag(Vec3(1.03, 1.0, 0.96))
    private val ccD65 = diag(Vec3(1.02, 1.0, 0.98))
    private val fmA = listOf(listOf(0.62, 0.20, 0.14), listOf(0.24, 0.86, -0.10), listOf(0.04, -0.20, 0.99))
    private val fmD65 = listOf(listOf(0.66, 0.16, 0.14), listOf(0.27, 0.82, -0.09), listOf(0.03, -0.15, 0.95))

    private fun handMade(withFM: Boolean = true) = data(
        cm1 = cmA, ill1 = 17, cm2 = cmD65, ill2 = 21,
        fm1 = if (withFM) fmA else null, fm2 = if (withFM) fmD65 else null,
        cc1 = ccA, cc2 = ccD65,
    )

    // ---- whites and Bradford ----

    // The two phones' real Camera2 colour data (dumpsys media.camera, camera 0, 2026-11-25).
    private fun m(vararg v: Double, d: Double): Mat3 = listOf(
        listOf(v[0] / d, v[1] / d, v[2] / d), listOf(v[3] / d, v[4] / d, v[5] / d), listOf(v[6] / d, v[7] / d, v[8] / d),
    )

    private val pixel7 = data(
        ill1 = 17, cm1 = m(29074.0, -20121.0, -5107.0, -10717.0, 21800.0, -77.0, 1593.0, -3745.0, 33707.0, d = 1e4),
        ill2 = 21, cm2 = m(32405.0, -15371.0, -4985.0, -9693.0, 18760.0, 416.0, 556.0, -2040.0, 10572.0, d = 1e4),
        fm1 = m(4362.0, 3850.0, 1431.0, 2226.0, 7168.0, 606.0, 139.0, 971.0, 7141.0, d = 1e4),
        fm2 = m(4361.0, 3851.0, 1431.0, 2225.0, 7169.0, 606.0, 139.0, 971.0, 7141.0, d = 1e4),
        cc1 = diag(Vec3(1.0, 1.0, 4111.0 / 4131)), cc2 = diag(Vec3(4580.0 / 4590, 1.0, 7295.0 / 7314)),
    )

    private val galaxyS24Plus = data(
        ill1 = 21, cm1 = m(849.0, -235.0, -98.0, -447.0, 1312.0, 125.0, -105.0, 276.0, 450.0, d = 1024.0),
        ill2 = 17, cm2 = m(1920.0, -1192.0, -86.0, -171.0, 1194.0, 50.0, 67.0, 127.0, 467.0, d = 1024.0),
        fm1 = m(655.0, 250.0, 82.0, 226.0, 920.0, -122.0, 10.0, -376.0, 1212.0, d = 1024.0),
        fm2 = m(422.0, 340.0, 225.0, 19.0, 972.0, 33.0, -209.0, -892.0, 1946.0, d = 1024.0),
        cc1 = diag(Vec3(996.0 / 1024, 1.0, 1030.0 / 1024)), cc2 = diag(Vec3(996.0 / 1024, 1.0, 1030.0 / 1024)),
    )

    @Test fun pixel7ColourDataIsGeneric() {
        // Its D65 colour matrix is the sRGB matrix: the neutral comes out as R = G = B.
        assertEquals(true, ProvisionalProfile.isGeneric(pixel7))
    }

    @Test fun galaxyS24PlusColourDataIsReal() {
        // r/g 0.442, b/g 0.655 at D65: a real sensor's response.
        assertEquals(false, ProvisionalProfile.isGeneric(galaxyS24Plus))
        assertEquals(false, ProvisionalProfile.isGeneric(handMade()))
    }

    @Test fun whites() {
        assertVec(Vec3(0.9504559271, 1.0, 1.0890577508), ProvisionalProfile.d65, 1e-9)
        assertVec(Vec3(0.9642956764, 1.0, 0.8251046025), ProvisionalProfile.d50, 1e-9)
    }

    @Test fun bradfordD50ToD65() {
        val b = ProvisionalProfile.bradford(ProvisionalProfile.d50, ProvisionalProfile.d65)
        // Lindbloom's published D50 → D65 Bradford matrix (his whites differ slightly from ours: 1e-3).
        assertMat(
            listOf(
                listOf(0.9555766, -0.0230393, 0.0631636),
                listOf(-0.0282895, 1.0099416, 0.0210077),
                listOf(0.0122982, -0.0204830, 1.3299098),
            ),
            b, 1e-3,
        )
        // numpy, with exactly our whites.
        assertMat(
            listOf(
                listOf(0.9554734215, -0.0230984549, 0.0632592432),
                listOf(-0.0283697093, 1.0099953981, 0.0210414412),
                listOf(0.0123140149, -0.0205076493, 1.3303659262),
            ),
            b, 1e-9,
        )
        assertVec(ProvisionalProfile.d65, ColourMath.matvec(b, ProvisionalProfile.d50), 1e-12)
    }

    @Test fun bradfordIsIdentityForEqualWhites() {
        assertMat(eye, ProvisionalProfile.bradford(ProvisionalProfile.d65, ProvisionalProfile.d65), 1e-12)
    }

    // ---- illuminants and weights ----

    @Test fun illuminantTemperatures() {
        assertEquals(2850.0, ProvisionalProfile.illuminantTemperature(17)!!, 0.0)    // A
        assertEquals(2850.0, ProvisionalProfile.illuminantTemperature(3)!!, 0.0)     // tungsten
        assertEquals(3200.0, ProvisionalProfile.illuminantTemperature(24)!!, 0.0)    // ISO studio tungsten
        assertEquals(5000.0, ProvisionalProfile.illuminantTemperature(23)!!, 0.0)    // D50
        assertEquals(5500.0, ProvisionalProfile.illuminantTemperature(20)!!, 0.0)    // D55
        assertEquals(5500.0, ProvisionalProfile.illuminantTemperature(1)!!, 0.0)     // daylight
        assertEquals(6500.0, ProvisionalProfile.illuminantTemperature(21)!!, 0.0)    // D65
        assertEquals(6500.0, ProvisionalProfile.illuminantTemperature(10)!!, 0.0)    // cloudy
        assertEquals(7500.0, ProvisionalProfile.illuminantTemperature(22)!!, 0.0)    // D75
        assertEquals(6400.0, ProvisionalProfile.illuminantTemperature(12)!!, 0.0)    // daylight fluorescent
        assertEquals(5050.0, ProvisionalProfile.illuminantTemperature(13)!!, 0.0)    // day white fluorescent
        assertEquals(4150.0, ProvisionalProfile.illuminantTemperature(14)!!, 0.0)    // cool white fluorescent
        assertEquals(3525.0, ProvisionalProfile.illuminantTemperature(15)!!, 0.0)    // white fluorescent
        assertNull(ProvisionalProfile.illuminantTemperature(0))
        assertNull(ProvisionalProfile.illuminantTemperature(99))
    }

    @Test fun weights() {
        assertEquals(0.0, ProvisionalProfile.weight(2850.0, 6500.0), 0.0)          // A / D65 at 6504 K → D65
        assertEquals(0.0, ProvisionalProfile.weight(5000.0, 6500.0), 0.0)          // D50 / D65: 6504 K is past D65
        val mid = ProvisionalProfile.weight(5000.0, 6500.0, 5500.0)
        assertTrue(mid > 0 && mid < 1)
        assertEquals(0.4880136986, ProvisionalProfile.weight(2850.0, 6500.0, 4000.0), 1e-9)   // numpy
        assertEquals(1.0, ProvisionalProfile.weight(2850.0, null), 0.0)
        assertEquals(1.0, ProvisionalProfile.weight(6500.0, 6500.0), 0.0)
        assertEquals(1.0, ProvisionalProfile.weight(6500.0, 2850.0), 0.0)          // reversed order
    }

    // ---- the hand-made set against numpy ----

    @Test fun handMadeNeutral() {
        assertVec(Vec3(0.4096865886, 1.0, 0.697093295), ProvisionalProfile.cameraNeutral(handMade()), 1e-9)
    }

    @Test fun handMadeWithForwardMatrices() {
        assertMat(
            listOf(
                listOf(1.5283215743, 0.1244181496, 0.2810203029),
                listOf(0.6213255655, 0.8203163895, -0.1073961568),
                listOf(0.1037173494, -0.2143527168, 1.8177370881),
            ),
            ProvisionalProfile.cameraToXYZ(handMade()), 1e-8,
        )
    }

    @Test fun handMadeWithoutForwardMatrices() {
        assertMat(
            listOf(
                listOf(1.5391250277, 0.2018452536, 0.1693486255),
                listOf(0.5672775102, 0.9071822789, -0.2002433072),
                listOf(0.0219875004, -0.3756564443, 2.088251632),
            ),
            ProvisionalProfile.cameraToXYZ(handMade(withFM = false)), 1e-8,
        )
    }

    @Test fun reversedIlluminantOrderUsesTheD65Calibration() {
        // Calibration 1 = D65, calibration 2 = A: the D65 one is used, the same result as above.
        val swapped = data(cm1 = cmD65, ill1 = 21, cm2 = cmA, ill2 = 17, fm1 = fmD65, fm2 = fmA, cc1 = ccD65, cc2 = ccA)
        assertMat(ProvisionalProfile.cameraToXYZ(handMade())!!, ProvisionalProfile.cameraToXYZ(swapped), 1e-12)
    }

    @Test fun forwardMatricesNeedEveryCalibration() {
        // Two calibrations but only one forward matrix: the colour-matrix path.
        val oneFM = handMade(withFM = false).copy(forwardMatrix1 = fmA)
        assertMat(ProvisionalProfile.cameraToXYZ(handMade(withFM = false))!!, ProvisionalProfile.cameraToXYZ(oneFM), 1e-12)
    }

    // ---- a synthetic camera: both paths recover the true matrix ----

    /** The true camera → XYZ (D65) matrix of a made-up unit, and its per-unit calibration. */
    private val mTrue = listOf(listOf(0.6, 0.25, 0.1), listOf(0.25, 0.8, -0.05), listOf(0.02, 0.1, 1.0))
    private val ccUnit = diag(Vec3(1.04, 1.0, 0.95))

    /** CM (XYZ → reference camera) = CC⁻¹·M_true⁻¹ for a D65-only calibration. */
    private fun syntheticCM(): Mat3 = mul(ColourMath.inverse(ccUnit), ColourMath.inverse(mTrue))

    /** FM from the DNG definition, so that FM·diag(1/r)·CC⁻¹ = Bradford(D65 → D50)·M_true. */
    private fun syntheticFM(): Mat3 {
        val n0 = ColourMath.matvec(ColourMath.inverse(mTrue), ProvisionalProfile.d65)
        val n = n0 / n0.max()
        val r = ColourMath.matvec(ColourMath.inverse(ccUnit), n)
        val toD50 = ProvisionalProfile.bradford(ProvisionalProfile.d65, ProvisionalProfile.d50)
        return mul(mul(mul(toD50, mTrue), ccUnit), diag(r))
    }

    private fun recovers(d: CameraColourData) {
        val n = ProvisionalProfile.cameraNeutral(d)!!
        val m = ProvisionalProfile.cameraToXYZ(d)!!
        val y = ColourMath.matvec(mTrue, n).y
        assertMat(mTrue.map { row -> row.map { it / y } }, m, 1e-9)
        val white = ColourMath.matvec(m, n)
        assertEquals(1.0, white.y, 1e-12)
        val xy = ColourMath.xyzToXY(white)
        assertEquals(0.3127, xy.x, 1e-4)
        assertEquals(0.3290, xy.y, 1e-4)
    }

    @Test fun syntheticCameraWithoutForwardMatrix() {
        recovers(data(cm1 = syntheticCM(), cc1 = ccUnit))
    }

    @Test fun syntheticCameraWithForwardMatrix() {
        val fm = syntheticFM()
        // The DNG convention: FM maps the white-balanced neutral (1, 1, 1) to D50.
        val one = ColourMath.matvec(fm, Vec3(1.0, 1.0, 1.0))
        assertVec(ProvisionalProfile.d50 * (one.y), one, 1e-9)
        recovers(data(cm1 = syntheticCM(), fm1 = fm, cc1 = ccUnit))
    }

    // ---- scale and build ----

    @Test fun luminanceScale() {
        assertEquals(0.0040511, ProvisionalProfile.luminanceScale(25.0, 1.8, 3839.0), 1e-6)   // S24+
        assertEquals(0.0091119, ProvisionalProfile.luminanceScale(47.0, 1.85, 959.0), 1e-6)   // Pixel 7
        assertEquals(0.004051054962229747, ProvisionalProfile.luminanceScale(25.0, 1.8, 3839.0), 1e-15)
        assertEquals(0.009111885164067182, ProvisionalProfile.luminanceScale(47.0, 1.85, 959.0), 1e-15)
    }

    @Test fun buildsTheProfile() {
        val p = ProvisionalProfile.build(handMade(), "samsung/e2q", "SM-S926U1")!!
        assertEquals("samsung/e2q", p.model)
        assertEquals("SM-S926U1 (approximate: phone's own colour data)", p.name)
        assertEquals(0, p.version)
        assertEquals(1, p.format)
        assertEquals(s24Settings, p.camera)
        assertEquals(ProfileStats(), p.stats)
        assertNull(p.trained)
        assertNull(p.panels)
        assertTrue(p.isValid)
        // numpy: K (S24+) × M_rel.
        assertMat(
            listOf(
                listOf(0.0061913147, 0.0005040248, 0.0011384287),
                listOf(0.002517024, 0.0033231468, -0.0004350677),
                listOf(0.0004201647, -0.0008683546, 0.0073637529),
            ),
            p.matrix, 1e-10,
        )
        // A D65 neutral that just fills its strongest channel in 0.155 s reads about 100 cd/m² (§13.5).
        val n = ProvisionalProfile.cameraNeutral(handMade())!!
        val rgb = n * (3839.0 / 0.155)
        assertEquals(78 * 1.8 * 1.8 / (0.65 * 25 * 0.155), p.xyz(rgb).y, 1e-9)
    }

    @Test fun buildRejectsUnusableData() {
        val singular = listOf(listOf(1.0, 2.0, 3.0), listOf(2.0, 4.0, 6.0), listOf(0.0, 1.0, 1.0))
        assertNull(ProvisionalProfile.build(data(cm1 = singular), "m", "d"))
        val nan = listOf(listOf(Double.NaN, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))
        assertNull(ProvisionalProfile.build(data(cm1 = nan), "m", "d"))
        assertNull(ProvisionalProfile.build(handMade().copy(white = 256.0), "m", "d"))       // no range
        assertNull(ProvisionalProfile.build(handMade().copy(iso = 0.0), "m", "d"))
        assertNull(ProvisionalProfile.build(handMade().copy(fNumber = Double.NaN), "m", "d"))
        // A neutral with a non-positive channel can't be white balanced.
        val negative = listOf(listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, -1.0))
        assertNull(ProvisionalProfile.cameraNeutral(data(cm1 = negative)))
        assertNull(ProvisionalProfile.build(data(cm1 = negative), "m", "d"))
        // A singular calibration transform with forward matrices.
        val zeroRow = listOf(listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 0.0))
        assertNull(ProvisionalProfile.build(data(cm1 = cmD65, fm1 = fmD65, cc1 = zeroRow), "m", "d"))
    }

    @Test fun identityCameraIsUsable() {
        // CM = identity: the camera "sees" XYZ; the result is the identity scaled to Y = 1 at the D65 neutral.
        val p = ProvisionalProfile.build(data(cm1 = eye, fNumber = 1.85, iso = 47.0, black = 64.0, white = 1023.0), "google/panther", "Pixel 7")
        assertNotNull(p)
        val n = ProvisionalProfile.cameraNeutral(data(cm1 = eye))!!
        assertVec(ProvisionalProfile.d65 / ProvisionalProfile.d65.max(), n, 1e-12)
        val k = ProvisionalProfile.luminanceScale(47.0, 1.85, 959.0)
        val y = n.y
        assertMat(eye.map { row -> row.map { it * k / y } }, p!!.matrix, 1e-15)
    }
}
