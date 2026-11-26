package tvcal.android.camera

import tvcal.kit.CameraSettings
import tvcal.kit.cfmt
import kotlin.math.abs

// Which camera the meter uses (design §4). Pure Kotlin: CameraCatalog (in CameraEngine.kt) fills the
// candidates from CameraManager; this file decides. Deterministic per device, so the Lab build that trains a
// profile and the app that uses it pick the same camera. Owner: agent (b).

/** One camera id's static facts (CameraCharacteristics), as plain data. Missing values are null / empty / 0. */
data class CameraCandidate(
    val id: String,
    val facingBack: Boolean,
    /** REQUEST_AVAILABLE_CAPABILITIES contains RAW (3). */
    val raw: Boolean,
    /** … MANUAL_SENSOR (1). */
    val manualSensor: Boolean,
    /** … READ_SENSOR_SETTINGS (5): results report exposure and ISO. */
    val readSensorSettings: Boolean,
    /** … LOGICAL_MULTI_CAMERA (11). */
    val logical: Boolean,
    val physicalIds: Set<String>,
    /** INFO_SUPPORTED_HARDWARE_LEVEL raw value (LIMITED 0, FULL 1, LEGACY 2, LEVEL_3 3, EXTERNAL 4). */
    val hardwareLevel: Int,
    /** LENS_INFO_AVAILABLE_FOCAL_LENGTHS, mm. */
    val focalLengths: List<Double>,
    /** LENS_INFO_AVAILABLE_APERTURES (f-numbers). */
    val apertures: List<Double>,
    val pixelArrayWidth: Int,
    val pixelArrayHeight: Int,
    /** Largest RAW_SENSOR output size (by area); 0 x 0 when there is none. */
    val rawWidth: Int,
    val rawHeight: Int,
    /** SENSOR_INFO_COLOR_FILTER_ARRANGEMENT. */
    val cfa: Int?,
    /** SENSOR_INFO_SENSITIVITY_RANGE.lower. */
    val isoMin: Int?,
    val exposureMinNs: Long?,
    val exposureMaxNs: Long?,
)

sealed interface CameraChoice {
    /** [reason]: one log line saying why this id (e.g. "main lens, logical camera 0"). */
    data class Use(val camera: CameraCandidate, val reason: String) : CameraChoice

    /** [message]: the user-visible reason (NO_BACK / NO_RAW / NO_MANUAL). */
    data class Unsupported(val message: String) : CameraChoice
}

object CameraSelection {
    const val NO_BACK = "This phone has no back camera."
    const val NO_RAW = "This phone can't take RAW photos, which the camera meter needs."
    const val NO_MANUAL =
        "This phone's camera can't be set by hand (shutter time and ISO), which the camera meter needs."

    /** A twin must allow at least this much longer exposures than the primary to be preferred. */
    const val TWIN_EXPOSURE_GAIN = 1.2

    /** Back-facing, RAW + MANUAL_SENSOR, a Bayer CFA, a RAW size, ISO and exposure ranges. */
    fun eligible(c: CameraCandidate): Boolean =
        c.facingBack && c.raw && c.manualSensor && cfaName(c.cfa) != null &&
            c.rawWidth > 0 && c.rawHeight > 0 &&
            c.isoMin != null && c.exposureMinNs != null && (c.exposureMaxNs ?: 0L) > 0L

    /**
     * What [CameraMeter.available] needs (design §12): facing and capabilities only, which need no CAMERA
     * permission. Null when some back camera has RAW + MANUAL_SENSOR, else NO_BACK / NO_RAW / NO_MANUAL.
     */
    fun capabilityProblem(cameras: List<CameraCandidate>): String? {
        val back = cameras.filter { it.facingBack }
        return when {
            back.isEmpty() -> NO_BACK
            back.none { it.raw } -> NO_RAW
            back.none { it.raw && it.manualSensor } -> NO_MANUAL
            else -> null
        }
    }

    /**
     * Design §4: primary = the first back-facing id in list order if eligible, else the eligible camera with the
     * largest pixel array (ties → lowest numeric id). A "twin" = another eligible, non-logical id with the same
     * main focal length (±0.05 mm), aperture and RAW size. If the primary is logical and a twin allows
     * ≥ TWIN_EXPOSURE_GAIN × its max exposure, use the twin (best hardware level, then longest exposure, then
     * lowest numeric id). [cameras] in CameraManager.getCameraIdList() order.
     */
    fun choose(cameras: List<CameraCandidate>): CameraChoice {
        capabilityProblem(cameras)?.let { return CameraChoice.Unsupported(it) }
        val back = cameras.filter { it.facingBack }
        val eligible = back.filter { eligible(it) }
        // RAW + MANUAL_SENSOR, but not a Bayer sensor (monochrome, RGB) or no usable RAW size / ranges.
        if (eligible.isEmpty()) return CameraChoice.Unsupported(NO_RAW)

        val first = back.first()
        val primary = if (eligible(first)) {
            first
        } else {
            eligible.sortedWith(
                compareByDescending<CameraCandidate> { it.pixelArrayWidth.toLong() * it.pixelArrayHeight }
                    .thenComparator { a, b -> compareIds(a.id, b.id) },
            ).first()
        }
        val primaryWhy = if (primary === first) "the first back camera" else "the back camera with the largest sensor"

        if (primary.logical) {
            val focal = mainFocal(primary)
            val maxNs = primary.exposureMaxNs ?: 0L
            val twins = eligible.filter { t ->
                t.id != primary.id && !t.logical && focal != null &&
                    mainFocal(t)?.let { abs(it - focal) <= 0.05 } == true &&
                    sameAperture(t.apertures.firstOrNull(), primary.apertures.firstOrNull()) &&
                    t.rawWidth == primary.rawWidth && t.rawHeight == primary.rawHeight &&
                    (t.exposureMaxNs ?: 0L) >= TWIN_EXPOSURE_GAIN * maxNs
            }.sortedWith(
                compareByDescending<CameraCandidate> { hardwareRank(it.hardwareLevel) }
                    .thenByDescending { it.exposureMaxNs ?: 0L }
                    .thenComparator { a, b -> compareIds(a.id, b.id) },
            )
            twins.firstOrNull()?.let { t ->
                return CameraChoice.Use(
                    t,
                    "Using camera ${t.id}: the same lens as $primaryWhy (${primary.id}) on its own, with shutter " +
                        "times up to ${cfmt((t.exposureMaxNs ?: 0L) / 1e9, 3)} s instead of ${cfmt(maxNs / 1e9, 3)} s",
                )
            }
        }
        val kind = if (primary.logical) "logical camera" else "camera"
        return CameraChoice.Use(primary, "Using $kind ${primary.id}: $primaryWhy, main lens at zoom 1")
    }

    /** The main lens's focal length: the only one, or the first listed (zoom ratio 1 on a logical camera). */
    fun mainFocal(c: CameraCandidate): Double? = c.focalLengths.firstOrNull()

    /** LEGACY 0 < EXTERNAL 1 < LIMITED 2 < FULL 3 < LEVEL_3 4. */
    fun hardwareRank(level: Int): Int = when (level) {
        2 -> 0 // LEGACY
        4 -> 1 // EXTERNAL
        0 -> 2 // LIMITED
        1 -> 3 // FULL
        3 -> 4 // LEVEL_3
        else -> -1
    }

    /** "<rawW>x<rawH> <CFA>", e.g. "4080x3060 GBRG". */
    fun formatOf(c: CameraCandidate): String = "${c.rawWidth}x${c.rawHeight} ${cfaName(c.cfa) ?: "?"}"

    /** "Back camera <id>". */
    fun lensOf(c: CameraCandidate): String = "Back camera ${c.id}"

    /** CameraSettings(iso = isoMin, format = formatOf, lens = lensOf, aperture = locked f-number or null). */
    fun settingsFor(c: CameraCandidate, lockedAperture: Double?): CameraSettings =
        CameraSettings(iso = c.isoMin?.toDouble(), format = formatOf(c), lens = lensOf(c), aperture = lockedAperture)

    /** The Bayer pattern's name for a SENSOR_INFO_COLOR_FILTER_ARRANGEMENT code (0–3), else null. */
    fun cfaName(code: Int?): String? = Cfa.entries.firstOrNull { it.code == code }?.name

    /** Numeric ids in numeric order ("2" < "10"), then anything else by text. */
    fun compareIds(a: String, b: String): Int {
        val na = a.toIntOrNull()
        val nb = b.toIntOrNull()
        return when {
            na != null && nb != null -> na.compareTo(nb)
            na != null -> -1
            nb != null -> 1
            else -> a.compareTo(b)
        }
    }

    private fun sameAperture(a: Double?, b: Double?): Boolean =
        if (a == null || b == null) a == b else abs(a - b) <= 0.01
}
