package tvcal.android.camera

import tvcal.kit.cfmt
import kotlin.math.abs
import kotlin.math.max

// Every RAW frame's CaptureResult is checked against what was asked for (design §3.4). Pure Kotlin: the engine
// copies the CaptureRequest / TotalCaptureResult values into these classes. Owner: agent (b).

/** What a RAW capture asked for. */
data class FrameRequest(
    val exposureNs: Long,
    val iso: Int,
    /** LENS_FOCUS_DISTANCE asked (diopters); null on fixed-focus lenses. */
    val focusDiopters: Double?,
    /** The active physical camera seen while settling (logical cameras only), null otherwise. */
    val physicalId: String?,
)

/** What its CaptureResult reported (null = key missing from the result). */
data class FrameReport(
    val exposureNs: Long?,
    val iso: Int?,
    val focusDiopters: Double?,
    /** LENS_STATE: 0 STATIONARY, 1 MOVING. */
    val lensState: Int?,
    val blackLevelLock: Boolean?,
    val activePhysicalId: String?,
    /** CONTROL_AE_MODE (0 = OFF). */
    val aeMode: Int?,
)

object FrameCheck {
    /** Reported shutter time may differ from the request by this share (HAL rounding to whole sensor lines). */
    const val EXPOSURE_TOLERANCE = 0.10

    /** Reported focus may differ by this share of the request (or 0.1 diopter, whichever is larger). */
    const val FOCUS_TOLERANCE = 0.05

    /**
     * Null when the frame is usable, else a short reason for the log ("exposure 0.0081 s, asked 0.0040 s",
     * "ISO 50, asked 25", "black level lock released", "lens moving", "focus 8.2 D, asked 10.0 D",
     * "lens switched to 4 (was 2)", "auto exposure on"). Missing keys are not failures: without
     * SENSOR_EXPOSURE_TIME the engine divides by the requested exposure (and logs that once).
     */
    fun problem(asked: FrameRequest, got: FrameReport, lockHonoured: Boolean = true): String? {
        val exposure = got.exposureNs
        if (exposure != null &&
            abs(exposure - asked.exposureNs).toDouble() > asked.exposureNs * EXPOSURE_TOLERANCE * (1 + 1e-9)
        ) {
            return "exposure ${seconds(exposure)} s, asked ${seconds(asked.exposureNs)} s"
        }
        if (got.iso != null && got.iso != asked.iso) return "ISO ${got.iso}, asked ${asked.iso}"
        // BLACK_LEVEL_LOCK is not a reason to drop a frame: cameras may release it whenever the exposure changes
        // (Camera2 docs; the Pixel 7 does, on every exposure-search step), and every frame's own
        // SENSOR_DYNAMIC_BLACK_LEVEL is subtracted anyway. The engine only logs it. [lockHonoured] is kept for
        // callers; it no longer changes the answer.
        if (got.aeMode != null && got.aeMode != 0) return "auto exposure on"
        if (got.lensState == 1) return "lens moving"
        val wantFocus = asked.focusDiopters
        val focus = got.focusDiopters
        if (wantFocus != null && focus != null && !focusClose(focus, wantFocus)) {
            return "focus ${cfmt(focus, 1)} D, asked ${cfmt(wantFocus, 1)} D"
        }
        val was = asked.physicalId
        val now = got.activePhysicalId
        if (was != null && now != null && now != was) return "lens switched to $now (was $was)"
        return null
    }

    /** Reported focus [got] is within max(5% of [asked], 0.1 D) of the request (also used while settling). */
    fun focusClose(got: Double, asked: Double): Boolean =
        abs(got - asked) <= max(abs(asked) * FOCUS_TOLERANCE, 0.1) + 1e-9

    /** Seconds with 4 decimals, 6 below 1 ms: "0.0040", "0.000125". */
    private fun seconds(ns: Long): String = cfmt(ns / 1e9, if (ns < 1_000_000) 6 else 4)
}
