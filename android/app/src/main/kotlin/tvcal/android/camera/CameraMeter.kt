package tvcal.android.camera

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tvcal.android.app.PrefKeys
import tvcal.android.app.Prefs
import tvcal.kit.CameraSettings
import tvcal.kit.cfmt
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.min

// The public surface of Camera/CameraMeter.swift, and its Camera2 implementation (Camera2Meter).
// Design: docs/camera-meter-design.md §2, §9–§12. Owner: agent (b). The interface, CameraReading, DarkLevel and
// the message texts are shared API: change them only together with every caller.

/** One reading: dark-corrected sensor signal per second of exposure, and how it was taken. */
data class CameraReading(
    /** Dark-corrected sensor signal per second of exposure, [R, G, B]. */
    val rgb: List<Double>,
    /** Total shutter time of the reading (frames × frameExposure), seconds. */
    val duration: Double,
    val uneven: Double,
    /** Brightest channel as a share of full scale (equivalent single exposure of [duration]). */
    val peak: Double,
    val settings: CameraSettings,
    /** When the light was captured (MeterClock seconds, middle of the integration). */
    val capturedAt: Double,
    /** Android: number of equal frames averaged (1 unless [duration] is longer than the camera's max exposure). */
    val frames: Int = 1,
    /** Android: the reported shutter time of each frame (what [rgb] is divided by). */
    val frameExposure: Double = duration,
)

/** Sensor baseline measured with the lens covered at a short and a long shutter time. Pref "darkLevel". */
@Serializable
data class DarkLevel(
    /** At 1/30 s. Android: net counts (the residual left after the camera's black level). */
    val short: List<Double>,
    /** At [longAt] (1/4 s on the iPhone). */
    val long: List<Double>,
    /** Android: the long shot's per-frame shutter time when it isn't 1/4 s (cameras whose max exposure is shorter). */
    val longAt: Double? = null,
    /** Android: CameraSettings.lens + " " + format of the camera it was measured on; a different camera discards it. */
    val camera: String? = null,
) {
    companion object {
        const val tShort = 1.0 / 30
        const val tLong = 0.25
    }

    /** Baseline for any per-frame shutter time (straight line through the two measurements; extrapolates). */
    fun at(t: Double): List<Double> {
        val tl = longAt ?: tLong
        val f = (t - tShort) / (tl - tShort)
        return (0 until 3).map { short[it] + (long[it] - short[it]) * f }
    }
}

object ApertureLock {
    /** The f-number the Lab build locks on variable-aperture lenses when the profile records none. */
    const val defaultFNumber = 2.8

    /**
     * The f-number to lock, or null to leave the aperture alone: null when [wanted] is null or the lens has one
     * aperture (LENS_INFO_AVAILABLE_APERTURES size < 2); else the available aperture nearest to [wanted].
     */
    fun choose(apertures: List<Double>, wanted: Double?): Double? {
        if (wanted == null || apertures.size < 2) return null
        return apertures.minByOrNull { abs(it - wanted) }
    }
}

/**
 * The camera as the app sees it (Swift `CameraMeter`, an ObservableObject). Implementations keep their
 * observable fields in Compose snapshot state. Only one measurement uses the camera at a time (a fair
 * Mutex inside the implementation).
 */
interface CameraMeter {
    /** False on phones without a usable camera (and in UnavailableCameraMeter). The UI and AppModel check it first. */
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

    /** Why [available] is false (shown on the home card and as the camera blocker). */
    val unavailableReason: String get() = UNAVAILABLE

    fun log(line: String)

    /** Starts the camera. aperture: the f-number to lock, null leaves it alone. */
    suspend fun start(aperture: Double? = null)

    /** Message becomes "The camera is off.". */
    fun stop()

    /** With the lens covered: measures and saves the dark level (sets [message] either way). */
    suspend fun measureDark()

    /** One reading: (reading, null) or (null, problem text). */
    suspend fun measure(): Pair<CameraReading?, String?>

    /** The activity is visible again (MainActivity.onStart): reopen a camera that was running. */
    fun onAppForeground() {}

    /** The activity went to the background (MainActivity.onStop): release the camera, keep [running]. */
    fun onAppBackground() {}

    companion object {
        const val UNAVAILABLE = "The Android camera meter isn't ready yet - try the demo TV."
        const val OFF = "The camera is off."
        // The Swift texts (iPhone → phone). Design §12.
        const val PERMISSION =
            "TV Calibrator needs the camera to measure the TV. Allow it in Settings > Apps > TV Calibrator > Permissions > Camera."
        const val STARTING = "Starting the camera…"
        const val COVER = "Cover the lens and measure the darkness once."
        const val READY_TO_MEASURE = "Ready to measure."
        const val MEASURING_DARK = "Measuring with the lens covered…"
        const val NO_PHOTO_RETRY = "Couldn't take a photo. Try again."
        const val DARK_STRONG =
            "Still seeing light. Lay the phone screen-up on a table, so the camera on the back is pressed flat against it, and try again."
        const val DARK_LEAK =
            "Some light is still getting in. A fingertip isn't enough: lay the phone screen-up on a table, so the camera on the back is pressed flat against it, and try again."
        const val READY_POINT = "Ready. Point the box at the centre of the TV."
        const val NOT_READY = "The camera isn't ready."
        const val NO_PHOTO = "The phone couldn't take a photo."
        const val TOO_BRIGHT = "Too bright for the camera. Move the phone a little further from the TV."
        const val NO_EXPOSURE = "Couldn't find a good exposure for this patch."
        const val SETUP_FAILED = "Couldn't set up the camera."
    }
}

/** Creates the app's camera meter (TVCalApp.camera). */
object CameraMeters {
    /**
     * The Camera2 meter when some back camera has RAW + MANUAL_SENSOR (facing and capabilities only: no permission
     * needed); otherwise the stub, whose message names what the phone lacks (CameraSelection.NO_*) and offers the
     * demo.
     */
    fun create(context: Context, prefs: Prefs): CameraMeter {
        val app = context.applicationContext
        val problem = try {
            val manager = app.getSystemService(CameraManager::class.java)
                ?: return UnavailableCameraMeter(unsupported(CameraSelection.NO_BACK))
            CameraSelection.capabilityProblem(CameraCatalog.read(manager))
        } catch (e: Exception) {
            return UnavailableCameraMeter(unsupported("This phone's cameras couldn't be read."))
        }
        return if (problem == null) Camera2Meter(app, prefs) else UnavailableCameraMeter(unsupported(problem))
    }

    /** The home card / camera blocker text for a phone the meter can't use (design §12). */
    fun unsupported(problem: String): String = "$problem You can still try the demo."
}

/**
 * The real meter on Camera2 (Swift CameraMeter). Compose state on Main; the [engine] does all camera work on
 * its own threads. Loads the saved DarkLevel at init and passes max(short) to engine.setFloor.
 * CameraMeters.create only makes one when some back camera has RAW + MANUAL_SENSOR.
 */
class Camera2Meter(private val context: Context, private val prefs: Prefs) : CameraMeter {
    internal val engine: CameraEngine = CameraEngine(context) { line -> postLog(line) }

    override val available: Boolean get() = true
    override val unavailableReason: String get() = CameraMeter.UNAVAILABLE
    override var running: Boolean by mutableStateOf(false)
        private set
    override var busy: Boolean by mutableStateOf(false)
        private set
    override var message: String by mutableStateOf(CameraMeter.OFF)
        private set
    override var dark: DarkLevel? by mutableStateOf(null)
        private set
    override var lastReading: CameraReading? by mutableStateOf(null)
        private set
    override var logLines: List<String> by mutableStateOf(emptyList())
        private set
    override var onLog: ((String) -> Unit)? = null
    override val ready: Boolean get() = running && dark != null

    /** Follows the engine, which only knows them after the first start: [settingsVersion] makes Compose re-read. */
    override val settings: CameraSettings get() {
        settingsVersion
        return engine.settings
    }
    override val capturedSize: String get() {
        settingsVersion
        return engine.capturedSize
    }
    private var settingsVersion by mutableStateOf(0)

    /** Swift `duration`: the last good total shutter time, the next reading's first guess. */
    private var duration = 1.0 / 250

    /** Swift acquire/release: one measurement at a time, first come first served. */
    private val inUse = Mutex()

    /** Background → foreground: reopen the camera that [running] says should be on. */
    private var suspended = false

    /** The f-number asked for at the last start (the foreground reopen asks for it again). */
    private var aperture: Double? = null

    /** MainActivity is started (onAppForeground / onAppBackground). */
    private var foreground = true

    /** A start() is waiting for the engine to open. */
    private var starting = false

    /** Bumped by stop(): a start() or foreground reopen in progress sees it and releases the camera. */
    private var stops = 0

    /** Bumped by each foreground reopen: only the latest one acts on its result. */
    private var reopens = 0

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true }
    private val stamp = DateTimeFormatter.ofPattern("HH:mm:ss")

    init {
        prefs.string(PrefKeys.DARK_LEVEL)?.let { text ->
            runCatching { json.decodeFromString(DarkLevel.serializer(), text) }.getOrNull()
                ?.takeIf { it.short.size == 3 && it.long.size == 3 }
        }?.let { d ->
            dark = d
            engine.setFloor(d.short.max())
        }
        engine.onTrouble = { line -> postLog(line) }
        engine.onGaveUp = { why ->
            onMain {
                if (running) {
                    engine.close()
                    running = false
                    suspended = false
                    message = why
                }
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** Engine log lines arrive on camera threads: hop to Main, then [log]. */
    private fun postLog(line: String) {
        onMain { log(line) }
    }

    override fun log(line: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { log(line) }
            return
        }
        logLines = (logLines + "${LocalTime.now().format(stamp)}  $line").takeLast(200)
        onLog?.invoke("camera: $line")
    }

    /** CameraSettings.lens + " " + format: the camera a DarkLevel was measured on. */
    private fun cameraKey(): String {
        val s = engine.settings
        return "${s.lens ?: ""} ${s.format ?: ""}".trim()
    }

    override suspend fun start(aperture: Double?): Unit = withContext(Dispatchers.Main.immediate) {
        if (running) return@withContext
        if (!hasCameraPermission(context)) {
            message = CameraMeter.PERMISSION
            return@withContext
        }
        message = CameraMeter.STARTING
        this@Camera2Meter.aperture = aperture
        val stopsAtStart = stops
        starting = true
        val error = try {
            engine.open(aperture)
        } finally {
            starting = false
        }
        settingsVersion++
        if (stops != stopsAtStart) {
            // Stopped while starting: stop() has set the message; make sure nothing stays open.
            engine.close()
            return@withContext
        }
        if (!foreground) {
            // The app went to the background while starting (onAppBackground closed the engine, or it opened
            // just after): release it now and reopen it in onAppForeground, as for a running camera.
            engine.close()
            if (error != null && error != CameraMeter.OFF) {
                message = error
                return@withContext
            }
            running = true
            suspended = true
            message = if (dark == null) CameraMeter.COVER else CameraMeter.READY_TO_MEASURE
            log("The app is in the background: camera released")
            return@withContext
        }
        if (error != null) {
            message = error
            return@withContext
        }
        running = true
        suspended = false
        val measuredOn = dark?.camera
        if (measuredOn != null && measuredOn != cameraKey()) {
            log("The saved darkness was measured on another camera ($measuredOn): measure it again")
            dark = null
            engine.setFloor(0.0)
        }
        message = if (dark == null) CameraMeter.COVER else CameraMeter.READY_TO_MEASURE
    }

    override fun stop() {
        stops++
        suspended = false
        engine.close()
        running = false
        message = CameraMeter.OFF
    }

    override suspend fun measureDark(): Unit = withContext(Dispatchers.Main.immediate) { measureDarkLocked() }

    /**
     * With the lens covered: the sensor's residual at the shortest shutter time, 1/30 s and min(1/4 s, the
     * longest frame), one frame each and never teaching the white level (design §8).
     */
    private suspend fun measureDarkLocked() {
        inUse.withLock<Unit> {
            busy = true
            try {
                message = CameraMeter.MEASURING_DARK
                val c = engine.capture(engine.minDuration, learnsWhite = false)
                val a = engine.capture(DarkLevel.tShort, learnsWhite = false)
                val b = engine.capture(min(DarkLevel.tLong, engine.frameMax), learnsWhite = false)
                val th = engine.thresholds
                if (a == null || b == null || c == null || th == null) {
                    message = CameraMeter.NO_PHOTO_RETRY
                    return
                }
                when (val outcome = DarkCheck.evaluate(c.stats, a.stats, b.stats, b.frameExposure, th, cameraKey())) {
                    is DarkOutcome.Rejected -> {
                        log(
                            "Dark rejected: brightest colour reads ${cfmt(c.stats.mean.max(), 0)} at 1/${cfmt(1 / c.duration, 0)} s, " +
                                "${cfmt(a.stats.mean.max(), 0)} at 1/${cfmt(1 / a.duration, 0)} s, " +
                                "${cfmt(b.stats.mean.max(), 0)} at 1/${cfmt(1 / b.duration, 0)} s " +
                                "(white level ${cfmt(engine.whiteLevel, 0)})",
                        )
                        message = if (outcome.strong) CameraMeter.DARK_STRONG else CameraMeter.DARK_LEAK
                    }
                    is DarkOutcome.Accepted -> {
                        val d = outcome.level
                        dark = d
                        engine.setFloor(d.short.max())
                        prefs.setString(PrefKeys.DARK_LEVEL, json.encodeToString(DarkLevel.serializer(), d))
                        val s = d.short
                        val l = d.long
                        log(
                            "Dark at 1/${cfmt(1 / a.duration, 0)} s: ${cfmt(s[0], 1)} ${cfmt(s[1], 1)} ${cfmt(s[2], 1)}; " +
                                "at 1/${cfmt(1 / b.duration, 0)} s: ${cfmt(l[0], 1)} ${cfmt(l[1], 1)} ${cfmt(l[2], 1)}",
                        )
                        message = CameraMeter.READY_POINT
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    override suspend fun measure(): Pair<CameraReading?, String?> =
        withContext(Dispatchers.Main.immediate) { measureLocked() }

    /** One reading: finds a shutter time that exposes the patch well, then reports the signal (design §9). */
    private suspend fun measureLocked(): Pair<CameraReading?, String?> =
        inUse.withLock {
            val d = dark
            if (!ready || d == null) return null to CameraMeter.NOT_READY
            busy = true
            try {
                val th = engine.thresholds ?: return null to CameraMeter.NOT_READY
                var seconds = duration
                repeat(ExposureSearch.MAX_SHOTS) {
                    val shot = engine.capture(seconds)
                    if (shot == null) {
                        log("Measurement failed: no usable photo")
                        return null to CameraMeter.NO_PHOTO
                    }
                    val base = d.at(shot.frameExposure)
                    val step = ExposureSearch.step(
                        shot.stats, shot.requested, shot.frames, base, shot.white - shot.stats.black, th,
                        engine.minDuration, engine.maxDuration,
                    )
                    when (step) {
                        ExposureStep.TooBright -> return null to CameraMeter.TOO_BRIGHT
                        is ExposureStep.Next -> {
                            seconds = step.seconds
                        }
                        is ExposureStep.Accept -> {
                            duration = shot.duration
                            val reading = CameraReading(
                                rgb = step.net.map { it / shot.frameExposure },
                                duration = shot.duration,
                                uneven = shot.stats.uneven,
                                peak = step.peak,
                                settings = engine.settings,
                                capturedAt = shot.at,
                                frames = shot.frames,
                                frameExposure = shot.frameExposure,
                            )
                            lastReading = reading
                            settingsVersion++
                            return reading to null
                        }
                    }
                }
                null to CameraMeter.NO_EXPOSURE
            } finally {
                busy = false
            }
        }

    override fun onAppForeground() {
        foreground = true
        if (!suspended) return
        suspended = false
        val stopsAtOpen = stops
        val reopen = ++reopens
        scope.launch {
            val error = engine.open(aperture)
            // A newer reopen (background and back again meanwhile) handles the outcome.
            if (reopen != reopens) return@launch
            if (stops != stopsAtOpen) {
                // Stopped while reopening (the open may have finished after stop's close); a start() since then
                // owns the engine.
                if (!running && !starting) engine.close()
                return@launch
            }
            if (!running) {
                engine.close()
                return@launch
            }
            if (suspended || !foreground) {
                // Back in the background while reopening: onAppBackground released it (or will reopen later).
                engine.close()
                suspended = true
                return@launch
            }
            if (error == null) {
                log("Camera back on")
            } else {
                running = false
                message = error
            }
        }
    }

    override fun onAppBackground() {
        foreground = false
        if (starting) {
            // start() sees [foreground] and finishes as suspended.
            engine.close()
            return
        }
        if (!running || suspended) return
        suspended = true
        engine.close()
        log("The app is in the background: camera released")
    }
}

/**
 * Phones the meter can't use (no back camera, no RAW, no manual sensor): reports the camera as not available,
 * with [reason] (CameraMeters.unsupported(CameraSelection.NO_*)) as the message. JVM tests use the default.
 * Never throws.
 */
class UnavailableCameraMeter(private val reason: String = CameraMeter.UNAVAILABLE) : CameraMeter {
    override val available: Boolean = false
    override val running: Boolean = false
    override val busy: Boolean = false
    override var message: String by mutableStateOf(reason)
        private set
    override val unavailableReason: String get() = reason
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
        message = reason
    }

    override fun stop() {
        message = reason
    }

    override suspend fun measureDark() {
        message = reason
    }

    override suspend fun measure(): Pair<CameraReading?, String?> = null to reason
}
