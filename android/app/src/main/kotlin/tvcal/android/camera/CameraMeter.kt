package tvcal.android.camera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import tvcal.kit.CameraSettings

// The public surface of Camera/CameraMeter.swift. The real Camera2 meter is P4; until then the app uses
// UnavailableCameraMeter, so every real-TV step shows the camera-blocker message instead of crashing.

/** One reading: dark-corrected sensor signal per second of exposure, and how it was taken. */
data class CameraReading(
    /** Dark-corrected sensor signal per second of exposure, [R, G, B]. */
    val rgb: List<Double>,
    val duration: Double,
    val uneven: Double,
    /** Brightest channel as a share of full scale. */
    val peak: Double,
    val settings: CameraSettings,
    /** When the light was captured (MeterClock seconds). */
    val capturedAt: Double,
)

/** Sensor baseline measured with the lens covered at a short and a long shutter time. Pref "darkLevel". */
@Serializable
data class DarkLevel(
    /** At 1/30 s. */
    val short: List<Double>,
    /** At 1/4 s. */
    val long: List<Double>,
) {
    companion object {
        const val tShort = 1.0 / 30
        const val tLong = 0.25
    }

    /** Baseline for any shutter time (straight line through the two measurements; extrapolates). */
    fun at(t: Double): List<Double> {
        val f = (t - tShort) / (tLong - tShort)
        return (0 until 3).map { short[it] + (long[it] - short[it]) * f }
    }
}

object ApertureLock {
    /** The f-number the Lab build locks on variable-aperture lenses when the profile records none. */
    const val defaultFNumber = 2.8
}

/**
 * The camera as the app sees it (Swift `CameraMeter`, an ObservableObject). Implementations keep their
 * observable fields in Compose snapshot state. Only one measurement uses the camera at a time (a fair
 * Mutex inside the implementation).
 */
interface CameraMeter {
    /** False until the Camera2 meter exists (P4). The UI and AppModel.cameraBlocker check it first. */
    val available: Boolean
    val running: Boolean
    val busy: Boolean
    /** Status text for the camera setup screen, e.g. "The camera is off.". */
    val message: String
    val dark: DarkLevel?
    val lastReading: CameraReading?
    /** Last 200 lines, each "HH:mm:ss  line". */
    val logLines: List<String>
    /** Extra listener for log lines (Lab mode forwards them as "camera: <line>"). */
    var onLog: ((String) -> Unit)?

    /** running && dark != null. */
    val ready: Boolean
    /** The locked settings readings are taken with (compared with the profile's). */
    val settings: CameraSettings
    /** Actual RAW buffer size "WxH" (Swift camera.engine.capturedSize); "" before the first photo. */
    val capturedSize: String

    fun log(line: String)

    /** Starts the camera. aperture: the f-number to lock, null leaves it alone. */
    suspend fun start(aperture: Double? = null)

    /** Message becomes "The camera is off.". */
    fun stop()

    /** With the lens covered: measures and saves the dark level (sets [message] either way). */
    suspend fun measureDark()

    /** One reading: (reading, null) or (null, problem text). */
    suspend fun measure(): Pair<CameraReading?, String?>

    companion object {
        const val UNAVAILABLE = "The Android camera meter isn't ready yet - try the demo TV."
        const val OFF = "The camera is off."
    }
}

/** Until P4: reports the camera as not available. Never throws. */
class UnavailableCameraMeter : CameraMeter {
    override val available: Boolean = false
    override val running: Boolean = false
    override val busy: Boolean = false
    override var message: String by mutableStateOf(CameraMeter.UNAVAILABLE)
        private set
    override val dark: DarkLevel? = null
    override val lastReading: CameraReading? = null
    override var logLines: List<String> by mutableStateOf(emptyList())
        private set
    override var onLog: ((String) -> Unit)? = null
    override val ready: Boolean get() = false
    override val settings: CameraSettings = CameraSettings()
    override val capturedSize: String = ""

    override fun log(line: String) {
        logLines = (logLines + line).takeLast(200)
        onLog?.invoke("camera: $line")
    }

    override suspend fun start(aperture: Double?) {
        message = CameraMeter.UNAVAILABLE
    }

    override fun stop() {
        message = CameraMeter.UNAVAILABLE
    }

    override suspend fun measureDark() {
        message = CameraMeter.UNAVAILABLE
    }

    override suspend fun measure(): Pair<CameraReading?, String?> = null to CameraMeter.UNAVAILABLE
}
