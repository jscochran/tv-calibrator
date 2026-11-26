package tvcal.android.camera

import tvcal.kit.CameraProfile
import tvcal.kit.CameraSettings
import tvcal.kit.ColourMath
import tvcal.kit.Mat3
import tvcal.kit.Point2
import tvcal.kit.ProfileStats
import tvcal.kit.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

// The approximate profile for phones without a trained one (design §13): the camera's own DNG colour data
// (Camera2 SENSOR_* colour keys) turned into a CameraProfile, with an absolute scale from the photometric
// exposure equation. Pure Kotlin (CameraColourReader.kt reads CameraCharacteristics into CameraColourData).
// ProfileService uses it last: imported > own > standard > approximate. Owner: agent (c).

/**
 * The DNG colour data of the meter's camera, as plain numbers. Matrices are row-major 3x3 (Camera2
 * ColorSpaceTransform). Illuminants are Camera2 SENSOR_REFERENCE_ILLUMINANT values (EXIF LightSource: 17 A,
 * 21 D65, 23 D50, …).
 */
data class CameraColourData(
    val illuminant1: Int,
    val illuminant2: Int?,
    /** SENSOR_COLOR_TRANSFORM1/2: XYZ → reference camera space (DNG ColorMatrix1/2). */
    val colorTransform1: Mat3,
    val colorTransform2: Mat3?,
    /** SENSOR_FORWARD_MATRIX1/2: white-balanced reference camera → XYZ D50 (DNG ForwardMatrix1/2). */
    val forwardMatrix1: Mat3?,
    val forwardMatrix2: Mat3?,
    /** SENSOR_CALIBRATION_TRANSFORM1/2: reference camera → this unit's camera (DNG CameraCalibration1/2). */
    val calibration1: Mat3?,
    val calibration2: Mat3?,
    /** The locked ISO (SENSOR_INFO_SENSITIVITY_RANGE.lower). */
    val iso: Double,
    /** The lens f-number (LENS_INFO_AVAILABLE_APERTURES, or the locked one). */
    val fNumber: Double,
    /** Static black (mean of SENSOR_BLACK_LEVEL_PATTERN) and white (SENSOR_INFO_WHITE_LEVEL), absolute counts. */
    val black: Double,
    val white: Double,
    /** CameraSelection.settingsFor(the meter's camera): must equal what the engine reports. */
    val settings: CameraSettings,
)

/** What an approximate-profile source can offer right now. */
sealed interface ApproximateResult {
    data class Ready(val profile: CameraProfile) : ApproximateResult

    /** The camera's colour keys need the CAMERA permission (Android 10+), not granted yet. */
    data object NeedsPermission : ApproximateResult

    /** No usable camera or colour data; [why] is for the log. */
    data class Unavailable(val why: String) : ApproximateResult
}

/** Where ProfileService gets the approximate profile (AndroidApproximateSource on the phone, fakes in tests). */
fun interface ApproximateSource {
    fun load(): ApproximateResult
}

object ProvisionalProfile {
    /** ISO 12232 saturation-based speed: H_sat = 78 / S (lux·s at the focal plane). */
    const val SATURATION_CONSTANT = 78.0

    /** Lens factor q = (π/4)·T·v·cos⁴θ, the ISO 12232 / 2720 value at the image centre. */
    const val LENS_Q = 0.65

    /** Correlated colour temperature of D65 (K): the white the camera neutral is computed for. */
    const val D65_CCT = 6504.0

    /** Version of every approximate profile (trained ones start at 1). */
    const val VERSION = 0

    /** The margin of error ProfileService shows for an approximate profile, dE. */
    const val MARGIN_DE = 4.0

    /** "<device> (approximate: phone's own colour data)". */
    fun name(deviceName: String): String = "$deviceName (approximate: phone's own colour data)"

    /**
     * DNG SDK illuminant temperatures (K) for a Camera2/EXIF illuminant code (dng_camera_profile.cpp,
     * IlluminantToTemperature); null for unknown codes.
     */
    fun illuminantTemperature(code: Int): Double? = when (code) {
        17, 3 -> 2850.0                          // standard A, tungsten
        24 -> 3200.0                             // ISO studio tungsten
        23 -> 5000.0                             // D50
        20, 1, 9, 4, 18 -> 5500.0                // D55, daylight, fine weather, flash, standard B
        21, 19, 10 -> 6500.0                     // D65, standard C, cloudy weather
        22, 11 -> 7500.0                         // D75, shade
        12 -> (5700.0 + 7100.0) * 0.5            // daylight fluorescent: 6400
        13 -> (4600.0 + 5500.0) * 0.5            // day white fluorescent: 5050
        14, 2 -> (3800.0 + 4500.0) * 0.5         // cool white fluorescent, fluorescent: 4150
        15 -> (3250.0 + 3800.0) * 0.5            // white fluorescent: 3525
        16 -> (2600.0 + 3250.0) * 0.5            // warm white fluorescent (EXIF; no Camera2 constant): 2925
        else -> null
    }

    /**
     * DNG inverse-CCT interpolation weight of calibration 1: g = (1/t − 1/t2) / (1/t1 − 1/t2), clamped to [0, 1];
     * 1 when there is no second calibration (t2 null) or t1 == t2.
     */
    fun weight(t1: Double, t2: Double?, t: Double = D65_CCT): Double {
        if (t2 == null || t1 == t2) return 1.0
        val g = (1 / t - 1 / t2) / (1 / t1 - 1 / t2)
        return if (g.isNaN()) 1.0 else g.coerceIn(0.0, 1.0)
    }

    /** XYZ of D50 and D65 at Y = 1 (D65 from ColourMath.d65, xy 0.3127 0.3290; D50 0.3457 0.3585). */
    val d50: Vec3 get() = ColourMath.xyYToXYZ(Point2(0.3457, 0.3585), 1.0)
    val d65: Vec3 get() = ColourMath.xyYToXYZ(ColourMath.d65, 1.0)

    /** The standard Bradford cone-response matrix. */
    private val bradfordCone: Mat3 = listOf(
        listOf(0.8951, 0.2664, -0.1614),
        listOf(-0.7502, 1.7135, 0.0367),
        listOf(0.0389, -0.0685, 1.0296),
    )

    /** Bradford chromatic adaptation from white [from] to white [to] (XYZ). */
    fun bradford(from: Vec3, to: Vec3): Mat3 {
        val m = bradfordCone
        val gain = ColourMath.matvec(m, to) / ColourMath.matvec(m, from)
        return mul(mul(ColourMath.inverse(m), diag(gain)), m)
    }

    /** The calibration-1 weight for [data] at D65 (1 without a usable second calibration). */
    private fun weightOf(data: CameraColourData): Double {
        val ill2 = data.illuminant2
        if (data.colorTransform2 == null || ill2 == null) return 1.0
        val t1 = illuminantTemperature(data.illuminant1)
        val t2 = illuminantTemperature(ill2)
        return when {
            t1 == null && t2 == null -> 1.0
            t1 == null -> 0.0                    // only calibration 2 says which light it is for
            t2 == null -> 1.0
            else -> weight(t1, t2)
        }
    }

    /** The interpolated XYZ → camera matrix CC·CM, and CC (identity when missing). */
    private fun interpolated(data: CameraColourData): Pair<Mat3, Mat3> {
        val g = weightOf(data)
        val cm = blend(data.colorTransform1, data.colorTransform2 ?: data.colorTransform1, g)
        val cc = blend(data.calibration1 ?: identity, data.calibration2 ?: identity, g)
        return mul(cc, cm) to cc
    }

    /**
     * DNG §6: CM = g·CM1 + (1−g)·CM2, CC likewise (identity when missing); XYZtoCamera = CC·CM; camera neutral
     * n = XYZtoCamera·d65, divided by its largest component. Null if any component ≤ 0 or not finite.
     */
    fun cameraNeutral(data: CameraColourData): Vec3? {
        if (!finite(data.colorTransform1)) return null
        data.colorTransform2?.let { if (!finite(it)) return null }
        data.calibration1?.let { if (!finite(it)) return null }
        data.calibration2?.let { if (!finite(it)) return null }
        val (xyzToCamera, _) = interpolated(data)
        if (!finite(xyzToCamera)) return null
        val raw = ColourMath.matvec(xyzToCamera, d65)
        val top = raw.max()
        if (!(top > 0) || !top.isFinite()) return null
        val n = raw / top
        return if (n.toList().all { it > 0 && it.isFinite() }) n else null
    }

    /**
     * True when the camera's colour data is a placeholder rather than a characterisation of its sensor: the D65
     * camera neutral comes out as (nearly) equal red, green and blue. A real Bayer sensor's green photosites
     * always collect much more daylight than its red and blue ones (r/g and b/g typically 0.4–0.8), so equal
     * values mean the HAL reported generic matrices. The Pixel 7 does: its SENSOR_COLOR_TRANSFORM2 is the sRGB
     * XYZ→RGB matrix and its forward matrices are sRGB→XYZ(D50). Such data can't make an approximate profile
     * (it read a near-neutral monitor white as cyan, x 0.296 y 0.371), so the phone needs a trained one.
     */
    fun isGeneric(data: CameraColourData): Boolean {
        val n = cameraNeutral(data) ?: return false
        val rg = n.x / n.y
        val bg = n.z / n.y
        return rg in GENERIC_BAND && bg in GENERIC_BAND
    }

    /** Neutral ratios inside this band count as "equal" (see [isGeneric]). */
    private val GENERIC_BAND = 0.93..1.07

    /**
     * Relative camera → XYZ (D65) matrix, scaled so the camera neutral maps to Y = 1. With forward matrices:
     * Bradford(D50→D65) · FM · diag(1 / (CC⁻¹·n)) · CC⁻¹. Without: (CC·CM)⁻¹, rescaled. Null when singular.
     */
    fun cameraToXYZ(data: CameraColourData): Mat3? {
        val n = cameraNeutral(data) ?: return null
        val (xyzToCamera, cc) = interpolated(data)
        val fm = forwardMatrix(data)
        val m: Mat3 = if (fm != null) {
            // DNG §6.3 (ForwardMatrix): reference neutral r = CC⁻¹·n; CameraToXYZ_D50 = FM·diag(1/r)·CC⁻¹.
            val ccInv = inverseOrNull(cc) ?: return null
            val r = ColourMath.matvec(ccInv, n)
            if (!r.toList().all { it > 0 && it.isFinite() }) return null
            val m50 = mul(mul(fm, diag(Vec3(1 / r.x, 1 / r.y, 1 / r.z))), ccInv)
            mul(bradford(d50, d65), m50)
        } else {
            // No forward matrix: the inverse of XYZ → camera is already unadapted XYZ.
            inverseOrNull(xyzToCamera) ?: return null
        }
        val y = ColourMath.matvec(m, n).y
        if (!(y > 0) || !y.isFinite()) return null
        val scaled = m.map { row -> row.map { it / y } }
        return if (finite(scaled) && inverseOrNull(scaled) != null) scaled else null
    }

    /**
     * The interpolated forward matrix, or null for the colour-matrix path: one calibration → FM1; two → both FMs
     * are needed (the DNG spec uses forward matrices only when every calibration has one). An all-zero matrix
     * counts as missing.
     */
    private fun forwardMatrix(data: CameraColourData): Mat3? {
        val fm1 = data.forwardMatrix1?.takeIf { finite(it) && !isZero(it) } ?: return null
        if (data.colorTransform2 == null || data.illuminant2 == null) return fm1
        val fm2 = data.forwardMatrix2?.takeIf { finite(it) && !isZero(it) } ?: return null
        return blend(fm1, fm2, weightOf(data))
    }

    /**
     * cd/m² per (net count per second) at Y_rel = 1: K = 78·N² / (q·ISO·(white − black)).
     * S24+ (f/1.8, ISO 25, 3839): 0.0040511. Pixel 7 (f/1.85, ISO 47, 959): 0.0091119.
     */
    fun luminanceScale(iso: Double, fNumber: Double, range: Double): Double =
        SATURATION_CONSTANT * fNumber * fNumber / (LENS_Q * iso * range)

    /**
     * The profile: model [model], name name([deviceName]), version 0, matrix = luminanceScale × cameraToXYZ,
     * camera = data.settings, stats = ProfileStats() (not rated), no panels, trained null. Null when the colour
     * data can't make a usable (finite, invertible) matrix.
     */
    fun build(data: CameraColourData, model: String, deviceName: String): CameraProfile? {
        val range = data.white - data.black
        if (!(data.iso > 0) || !(data.fNumber > 0) || !(range > 0)) return null
        if (!data.iso.isFinite() || !data.fNumber.isFinite() || !range.isFinite()) return null
        val k = luminanceScale(data.iso, data.fNumber, range)
        if (!(k > 0) || !k.isFinite()) return null
        val rel = cameraToXYZ(data) ?: return null
        val profile = CameraProfile(
            format = 1,
            model = model,
            name = name(deviceName),
            version = VERSION,
            matrix = rel.map { row -> row.map { it * k } },
            camera = data.settings,
            stats = ProfileStats(),
            trained = null,
            panels = null,
        )
        return if (profile.isValid) profile else null
    }

    // ---- 3x3 helpers (row-major Mat3) ----

    private val identity: Mat3 = listOf(listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))

    internal fun mul(a: Mat3, b: Mat3): Mat3 =
        List(3) { r -> List(3) { c -> a[r][0] * b[0][c] + a[r][1] * b[1][c] + a[r][2] * b[2][c] } }

    private fun diag(v: Vec3): Mat3 = listOf(listOf(v.x, 0.0, 0.0), listOf(0.0, v.y, 0.0), listOf(0.0, 0.0, v.z))

    /** g·a + (1 − g)·b. */
    private fun blend(a: Mat3, b: Mat3, g: Double): Mat3 =
        if (g >= 1.0) a else if (g <= 0.0) b else List(3) { r -> List(3) { c -> g * a[r][c] + (1 - g) * b[r][c] } }

    private fun finite(m: Mat3): Boolean = m.size == 3 && m.all { row -> row.size == 3 && row.all { it.isFinite() } }

    private fun isZero(m: Mat3): Boolean = m.all { row -> row.all { it == 0.0 } }

    /** The inverse, or null when [m] is singular (|det| tiny next to its rows' sizes) or not finite. */
    internal fun inverseOrNull(m: Mat3): Mat3? {
        if (!finite(m)) return null
        val det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
            m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
            m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
        val scale = m.fold(1.0) { acc, row -> acc * sqrt(row.sumOf { it * it }) }
        if (!det.isFinite() || !(scale > 0) || abs(det) <= 1e-12 * scale) return null
        val inv = ColourMath.inverse(m)
        return if (finite(inv)) inv else null
    }
}
