package tvcal.android.camera

import tvcal.kit.CameraProfile
import tvcal.kit.MeasureError
import tvcal.kit.Meter
import tvcal.kit.MeterClock
import tvcal.kit.MeterReading

/**
 * The camera as a Meter for the engine: each reading is the RAW camera signal turned into XYZ with this
 * phone model's profile. Until the Camera2 meter lands (P4) every read() fails with
 * MeasureError.Message(CameraMeter.UNAVAILABLE); P4 ports the Swift read() (Too bright → throw; no photo →
 * sleep 0.3 s and return a reading with xyz = null; settings mismatch → throw; else profile.xyz(rgb, panel)).
 */
class CameraXYZMeter(
    val camera: CameraMeter,
    val profile: CameraProfile,
    /** Refuse to measure if the camera's settings differ from the profile's. Off for the standard profile. */
    val checkSettings: Boolean = true,
    /** The kind of screen being measured (a key in the profile's `panels`); null uses the general conversion. */
    val panel: String? = null,
) : Meter {
    /** The light in a reading is captured a little before the reading comes back. */
    override val integration: Double get() = 0.25

    override fun now(): Double = MeterClock.now()

    override suspend fun sleep(seconds: Double) = MeterClock.sleep(seconds)

    override suspend fun read(): MeterReading = throw MeasureError.Message(CameraMeter.UNAVAILABLE)
}
