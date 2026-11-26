package tvcal.android.camera

import tvcal.kit.CameraProfile
import tvcal.kit.MeasureError
import tvcal.kit.Meter
import tvcal.kit.MeterClock
import tvcal.kit.MeterReading
import tvcal.kit.Vec3

/**
 * The camera as a Meter for the engine: each reading is the RAW camera signal turned into XYZ with this
 * phone model's profile (port of Camera/CameraXYZMeter.swift; design §14). Owner: agent (c).
 */
class CameraXYZMeter(
    val camera: CameraMeter,
    val profile: CameraProfile,
    /** Refuse to measure if the camera's settings differ from the profile's. Off for standard and approximate. */
    val checkSettings: Boolean = true,
    /** The kind of screen being measured (a key in the profile's `panels`); null uses the general conversion. */
    val panel: String? = null,
) : Meter {
    /** The light in a reading is captured a little before the reading comes back. */
    override val integration: Double get() = 0.25

    override fun now(): Double = MeterClock.now()

    override suspend fun sleep(seconds: Double) = MeterClock.sleep(seconds)

    /**
     * Swift read(): t0, camera.measure(), t1. No reading: a problem starting "Too bright" → throw
     * MeasureError.Message(problem); else sleep 0.3 s and return MeterReading(t0, now(), null). checkSettings and
     * profile.camera.mismatches(reading.settings) non-empty → throw "The camera is running with different settings
     * than its profile was trained with (<diffs joined "; ">). This can follow an Android update: check for a newer
     * profile in Settings.". Else MeterReading(t0, t1, profile.xyz(Vec3(rgb), panel)).
     */
    override suspend fun read(): MeterReading {
        val t0 = now()
        val (reading, problem) = camera.measure()
        val t1 = now()
        if (reading == null) {
            if (problem != null && problem.startsWith("Too bright")) throw MeasureError.Message(problem)
            // No photo (the camera was interrupted, or isn't ready): report a missed reading, after a short
            // pause so callers that keep trying don't spin.
            MeterClock.sleep(0.3)
            return MeterReading(t0, now(), null)
        }
        val diffs = if (checkSettings) profile.camera.mismatches(reading.settings) else emptyList()
        if (diffs.isNotEmpty()) {
            throw MeasureError.Message(
                "The camera is running with different settings than its profile was trained with (" +
                    diffs.joinToString("; ") + "). This can follow an Android update: " +
                    "check for a newer profile in Settings."
            )
        }
        val rgb = Vec3(reading.rgb[0], reading.rgb[1], reading.rgb[2])
        val xyz = profile.xyz(rgb, panel)
        debugLog(rgb, xyz, reading)
        return MeterReading(t0, t1, xyz)
    }

    /**
     * Logcat (tag "tvcal", debug builds) for checking a profile by hand: the camera signal and its ratios, the XYZ
     * and chromaticity. Read with: adb logcat -s tvcal
     */
    private fun debugLog(rgb: Vec3, xyz: Vec3, reading: CameraReading) {
        if (!tvcal.android.BuildConfig.DEBUG) return
        val s = xyz.x + xyz.y + xyz.z
        // android.util.Log is a stub in JVM unit tests: skip it there.
        if (System.getProperty("java.vendor")?.contains("Android") != true &&
            System.getProperty("java.vm.name")?.contains("Dalvik") != true &&
            System.getProperty("java.runtime.name")?.contains("Android") != true
        ) return
        android.util.Log.d(
            "tvcal",
            "reading rgb/s=${f(rgb.x)} ${f(rgb.y)} ${f(rgb.z)} r/g=${f(rgb.x / rgb.y)} b/g=${f(rgb.z / rgb.y)} " +
                "xyz=${f(xyz.x)} ${f(xyz.y)} ${f(xyz.z)} xy=${f(xyz.x / s)} ${f(xyz.y / s)} " +
                "dur=${f(reading.duration)} peak=${f(reading.peak)} uneven=${f(reading.uneven)} " +
                "profile=${profile.model} v${profile.version} panel=$panel matrix=${profile.matrix}",
        )
    }

    private fun f(v: Double): String = tvcal.kit.gfmt(v)
}
