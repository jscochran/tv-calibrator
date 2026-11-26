package tvcal.android.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tvcal.kit.CameraSettings
import tvcal.kit.MeterClock
import tvcal.kit.cfmt
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

// Camera control on Camera2 (port of CameraEngine in Camera/CameraMeter.swift). Design:
// docs/camera-meter-design.md §3 (session and request recipe), §4 (selection), §6 (multi-frame), §7 (white
// level), §11 (threading and lifecycle). Owner: agent (b).

/** Reads the cameras' static facts into [CameraCandidate]s (the fields selection needs; no CAMERA permission). */
object CameraCatalog {
    /** Every id in CameraManager.getCameraIdList(), in that order. Ids whose characteristics can't be read are skipped. */
    fun read(manager: CameraManager): List<CameraCandidate> =
        manager.cameraIdList.mapNotNull { id ->
            try {
                candidate(id, manager.getCameraCharacteristics(id))
            } catch (e: Exception) {
                null
            }
        }

    fun candidate(id: String, c: CameraCharacteristics): CameraCandidate {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val logical = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps
        val rawSize = runCatching {
            c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.RAW_SENSOR)
                ?.maxByOrNull { it.width.toLong() * it.height }
        }.getOrNull()
        val pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposure = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        return CameraCandidate(
            id = id,
            facingBack = c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK,
            raw = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps,
            manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
            readSensorSettings = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS in caps,
            logical = logical,
            physicalIds = if (logical) runCatching { c.physicalCameraIds }.getOrDefault(emptySet()) else emptySet(),
            hardwareLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                ?: CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
            focalLengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.map { clean(it) } ?: emptyList(),
            apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.map { clean(it) } ?: emptyList(),
            pixelArrayWidth = pixels?.width ?: 0,
            pixelArrayHeight = pixels?.height ?: 0,
            rawWidth = rawSize?.width ?: 0,
            rawHeight = rawSize?.height ?: 0,
            cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT),
            isoMin = iso?.lower,
            exposureMinNs = exposure?.lower,
            exposureMaxNs = exposure?.upper,
        )
    }

    /** One log line, e.g. "0: back, logical [2, 5, 6], LEVEL_3, RAW+MANUAL, 5.40 mm f/1.80, 4080x3060 GBRG, ≤ 0.150 s". */
    fun describe(c: CameraCandidate): String {
        val parts = mutableListOf<String>()
        parts += if (c.facingBack) "back" else "not back"
        parts += if (c.logical) {
            "logical [" + c.physicalIds.sortedWith { a, b -> CameraSelection.compareIds(a, b) }.joinToString(", ") + "]"
        } else {
            "physical"
        }
        parts += levelName(c.hardwareLevel)
        parts += listOfNotNull(if (c.raw) "RAW" else null, if (c.manualSensor) "MANUAL" else null)
            .joinToString("+").ifEmpty { "no RAW or MANUAL" }
        val lens = listOfNotNull(
            CameraSelection.mainFocal(c)?.let { "${cfmt(it, 2)} mm" },
            c.apertures.firstOrNull()?.let { "f/${cfmt(it, 2)}" },
        ).joinToString(" ")
        if (lens.isNotEmpty()) parts += lens
        if (c.rawWidth > 0) parts += CameraSelection.formatOf(c)
        c.exposureMaxNs?.let { parts += "≤ ${cfmt(it / 1e9, 3)} s" }
        return "${c.id}: " + parts.joinToString(", ")
    }

    private fun levelName(level: Int): String = when (level) {
        0 -> "LIMITED"
        1 -> "FULL"
        2 -> "LEGACY"
        3 -> "LEVEL_3"
        4 -> "EXTERNAL"
        else -> "level $level"
    }

    /** A Float characteristic as the Double it was written as (5.4f → 5.4, not 5.400000095). */
    internal fun clean(f: Float): Double = f.toString().toDouble()
}

/** One finished shot: the centre-box statistics of [frames] equal frames (Swift capture's (stats, used, at)). */
data class Shot(
    val stats: RawStats,
    /** Mean reported SENSOR_EXPOSURE_TIME of the frames, seconds. */
    val frameExposure: Double,
    val frames: Int,
    /** Middle of the integration, MeterClock seconds. */
    val at: Double,
    /** The effective absolute white level the frames were judged against. */
    val white: Double,
    /**
     * The planned total shutter time (FramePlan.total: the asked time clamped to [min, max] duration), seconds.
     * Swift `used`: the exposure search's limit tests and next guesses use it; per-second numbers use
     * [frameExposure].
     */
    val requested: Double = frames * frameExposure,
) {
    /** Total shutter time (Swift `used`). */
    val duration: Double get() = frames * frameExposure
}

/**
 * Owns the CameraDevice, the CameraCaptureSession, the RAW ImageReader and the two HandlerThreads
 * ("tvcal.camera" for device/session/capture callbacks and the image listener, "tvcal.raw" for box analysis).
 * Public methods may be called from any thread; frame bookkeeping lives on the camera thread. [log] is called
 * on any thread (Camera2Meter hops to Main).
 *
 * The suspend functions run their Camera2 calls on Dispatchers.Default and wait on deferreds completed by the
 * camera thread's callbacks, so nothing ever waits on a camera thread that [close] has quit. [opLock] serialises
 * open, capture and session re-creation (the preview), as the Swift's one camera queue did.
 */
class CameraEngine(private val context: Context, private val log: (String) -> Unit) {
    /** The static facts of the camera last opened (kept after [close], so settings survive the background). */
    private class Info(
        val camera: CameraCandidate,
        val settings: CameraSettings,
        val iso: Int,
        val minDuration: Double,
        val frameMax: Double,
        val exposureMinNs: Long,
        val exposureMaxNs: Long,
        val thresholds: Thresholds,
        val layout: CfaLayout,
        /** SENSOR_BLACK_LEVEL_PATTERN in CFA index order (the fallback for SENSOR_DYNAMIC_BLACK_LEVEL). */
        val staticBlack: List<Double>,
        /** SENSOR_INFO_WHITE_LEVEL (the fallback for SENSOR_DYNAMIC_WHITE_LEVEL). */
        val staticWhite: Double,
        val sensorOrientation: Int,
        val rawSize: Size,
        val previewSize: Size?,
        val minFrameRawNs: Long,
        val minFramePreviewNs: Long,
        /** LENS_INFO_MINIMUM_FOCUS_DISTANCE (closest focus) in diopters; null on fixed-focus lenses. */
        val focus: Double?,
        /** The locked f-number (ApertureLock.choose); null on fixed apertures. */
        val aperture: Double?,
        val oisOff: Boolean,
        val hotPixelOff: Boolean,
        val noiseOff: Boolean,
        val edgeOff: Boolean,
        val aberrationOff: Boolean,
        val shadingOff: Boolean,
        val distortionOff: Boolean,
        val zoomRatio: Boolean,
        val crop: Rect?,
        /** SYNC_MAX_LATENCY is PER_FRAME_CONTROL: every burst request carries its own settings. */
        val perFrameControl: Boolean,
        /** Extra frames thrown away after an exposure change when [perFrameControl] is false. */
        val latencyFrames: Int,
        /** SENSOR_TIMESTAMP is on the elapsedRealtime clock (else already on System.nanoTime's). */
        val realtimeTimestamps: Boolean,
    )

    /** The open device, its session and RAW reader. Created by [openLocked]; torn down on the camera thread. */
    private class Open(val info: Info, val device: CameraDevice, val reader: ImageReader, val white: WhiteLevel) {
        @Volatile var session: CameraCaptureSession? = null
        @Volatile var sessionPreview: PreviewTarget? = null
        /** The active physical camera seen while settling (logical cameras only). */
        @Volatile var physicalId: String? = null
        /** Exposure of the preview's repeating request; 0 = none running. */
        @Volatile var repeatingNs: Long = 0
        /** The last shot's per-frame exposure (for SYNC_MAX_LATENCY). */
        @Volatile var lastExposureNs: Long = 0
        @Volatile var closed = false
        var noExposureLogged = false
        var firstFrameLogged = false
        /** Some frame reported BLACK_LEVEL_LOCK on: from then on, a frame with it off means the level moved. */
        var lockHonoured = false
        var lockIgnoredLogged = false
    }

    /** The viewfinder's surface (and its SurfaceTexture, to size the buffers). */
    private class PreviewTarget(val surface: Surface, val texture: SurfaceTexture?, val ownsSurface: Boolean) {
        fun release() {
            if (ownsSurface) runCatching { surface.release() }
        }
    }

    /** Tag of every RAW capture request: the shot serial; [discard] frames only let new settings take hold. */
    private data class FrameTag(val serial: Long, val discard: Boolean)

    /** One shot in progress. Confined to the camera thread once published as [current]. */
    private inner class ShotRun(
        val o: Open,
        val serial: Long,
        val asked: FrameRequest,
        val request: CaptureRequest,
        val needed: Int,
        val learns: Boolean,
        val sentAt: Double,
        val total: Double,
    ) {
        val done = CompletableDeferred<Shot?>()
        var finished = false
        var accepted = 0
        var failures = 0
        val acc = FrameAccumulator()
        var exposureSum = 0.0
        var firstStartNs = Long.MAX_VALUE
        var lastStartNs = Long.MIN_VALUE
        var lastExposureNs = 0L
        var white = Double.MAX_VALUE

        fun finish(shot: Shot?) {
            if (finished) return
            finished = true
            if (current === this) current = null
            dropWaiting()
            done.complete(shot)
        }
    }

    private class OpenFailure(message: String) : Exception(message)

    private class HandlerExecutor(private val handler: Handler) : Executor {
        override fun execute(command: Runnable) {
            handler.post(command)
        }
    }

    private val manager: CameraManager? = context.getSystemService(CameraManager::class.java)
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opLock = Mutex()

    @Volatile private var info: Info? = null
    @Volatile private var open: Open? = null

    private val threadLock = Any()
    private var cameraThread: HandlerThread? = null
    private var rawThread: HandlerThread? = null
    @Volatile private var cameraHandler: Handler? = null
    @Volatile private var rawHandler: Handler? = null

    /** The meter wants the camera open (open() sets it, close() clears it): retries only run while it's true. */
    @Volatile private var wanted = false
    /** Bumped by close(): an open() in progress that sees a new value gives up and releases what it got. */
    @Volatile private var generation = 0
    @Volatile private var wantedAperture: Double? = null
    @Volatile private var floorNet = 0.0
    @Volatile private var lastWhite = 0.0
    @Volatile private var capturedSizeText = ""
    @Volatile private var wantedPreview: PreviewTarget? = null
    @Volatile private var retryJob: Job? = null
    /** Watches the camera while retrying (§11); guarded by [threadLock]. */
    @Volatile private var availability: CameraManager.AvailabilityCallback? = null
    /** Signalled when the watched camera is free again or camera access priorities changed. */
    private val freed = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var repeatingSequence = -1
    @Volatile private var settleWaiter: Pair<Open, CompletableDeferred<TotalCaptureResult?>>? = null
    @Volatile private var repeatStop: Pair<Int, CountDownLatch>? = null
    private var serials = 0L

    // Camera-thread state: the shot in progress, and RAW images / results waiting for their partner.
    private var current: ShotRun? = null
    private val images = LinkedHashMap<Long, Image>()
    private val results = LinkedHashMap<Long, TotalCaptureResult>()
    private val lostFrames = ArrayDeque<Long>()

    /** A camera device and session are open. */
    val isOpen: Boolean get() = open?.let { !it.closed && it.session != null } == true

    /** The camera in use (after a successful [open]). */
    val camera: CameraCandidate? get() = info?.camera

    /** CameraSelection.settingsFor(camera, lockedAperture); CameraSettings() before [open]. */
    val settings: CameraSettings get() = info?.settings ?: CameraSettings()

    /** The locked ISO (SENSOR_INFO_SENSITIVITY_RANGE.lower). */
    val iso: Int get() = info?.iso ?: 0

    /** max(exposure range lower, 1/8000 s) (Swift minDuration). */
    val minDuration: Double get() = info?.minDuration ?: (1.0 / 8000)

    /** min(exposure range upper, 1/4 s): the longest single frame (S24+ id 0: 0.150 s, Pixel 7: 0.25 s). */
    val frameMax: Double get() = info?.frameMax ?: maxDuration

    /** The longest reading: 1/4 s (Swift maxDuration), taken as several frames when [frameMax] is shorter. */
    val maxDuration: Double get() = DarkLevel.tLong

    /** Delivered RAW buffer size "WxH" (Swift capturedSize), "" before the first frame. */
    val capturedSize: String get() = capturedSizeText

    /** The current effective absolute white level (for log lines). */
    val whiteLevel: Double get() = lastWhite

    /** Thresholds for the open camera (static black / white levels); null before [open]. */
    val thresholds: Thresholds? get() = info?.thresholds

    /** SENSOR_ORIENTATION (degrees) for the preview transform. */
    val sensorOrientation: Int get() = info?.sensorOrientation ?: 90

    /** The preview stream size (4:3, ≤ 1440x1080, a SurfaceTexture output size); null before [open]. */
    val previewSize: Size? get() = info?.previewSize

    /**
     * Called (camera thread) when the device disconnects or errors after opening: the text to log. The engine
     * itself retries opening (§11); the meter only logs.
     */
    var onTrouble: ((String) -> Unit)? = null

    /**
     * Android: called (any thread) when the camera stopped and reopening it failed (three retries with an error
     * other than another app holding the camera, which waits for the camera to be free instead; or the camera
     * was disabled by device policy): the user-visible message. The meter then stops ("running" false).
     */
    var onGaveUp: ((String) -> Unit)? = null

    /**
     * Swift start(): chooses the camera (CameraSelection over CameraCatalog), opens it, creates the session
     * (RAW ImageReader at the largest RAW size, maxImages 4, + the preview surface if attached), sends the
     * settling repeating request (TEMPLATE_MANUAL, §3.2) and waits for the lens to be STATIONARY (≤ 3 s).
     * Returns null on success, else the user-visible message (CameraSelection.NO_*, CameraMeter.PERMISSION,
     * CameraMeter.SETUP_FAILED, "Couldn't set up the camera: <why>"). Already open → null at once.
     */
    suspend fun open(wantedAperture: Double?): String? = withContext(Dispatchers.Default) {
        opLock.withLock {
            wanted = true
            this@CameraEngine.wantedAperture = wantedAperture
            retryJob?.cancel()
            retryJob = null
            if (isOpen) null else openLocked()
        }
    }

    /** Releases the session, the device, the ImageReader and both threads. Safe to call twice. Pending captures get null. */
    fun close() {
        wanted = false
        generation++
        retryJob?.cancel()
        retryJob = null
        unwatchAvailability()
        onCamera { open?.let { teardown(it) } }
        // An open() in progress releases what it got and stops the threads itself when it sees the new generation.
        if (!opening) stopThreads()
    }

    /**
     * Swift capture(duration:learnsWhite:): FramePlan.of(seconds, minDuration, frameMax, maxDuration) frames,
     * each verified (FrameCheck; a rejected frame is retaken, 3 tries), analysed (RawAnalysis.measure) and
     * combined (FrameAccumulator). Null when the camera isn't open, a frame fails 3 times, a capture fails, or
     * nothing arrives within perFrame × frames + 8 s ("The camera didn't answer in time"). learnsWhite = false
     * for photos of the darkness, which must never teach the sensor's ceiling.
     */
    suspend fun capture(seconds: Double, learnsWhite: Boolean = true): Shot? = withContext(Dispatchers.Default) {
        opLock.withLock { captureLocked(seconds, learnsWhite) }
    }

    /** Swift setFloor: WhiteLevel.setFloor on the camera thread (logs what it returns). */
    fun setFloor(levelNet: Double) {
        floorNet = levelNet
        val o = open ?: return
        cameraHandler?.post { if (open === o && !o.closed) o.white.setFloor(levelNet)?.let(log) }
    }

    /**
     * Attaches (or with null, detaches) the viewfinder surface. Re-creates the capture session with or without
     * the preview output; returns once the camera no longer writes to a detached surface (≤ 1 s).
     */
    fun setPreview(surface: Surface?) {
        setPreviewTarget(surface?.let { PreviewTarget(it, null, ownsSurface = false) })
    }

    /**
     * As [setPreview] for a TextureView's SurfaceTexture: the engine sizes its buffers ([previewSize]) before
     * each session is created, and owns (releases) the Surface it makes from it.
     */
    fun setPreview(texture: SurfaceTexture?) {
        setPreviewTarget(texture?.let { PreviewTarget(Surface(it), it, ownsSurface = true) })
    }

    // Opening

    @Volatile private var opening = false

    /** Holds [opLock]. */
    private suspend fun openLocked(): String? {
        val mgr = manager ?: return CameraSelection.NO_BACK
        if (!hasCameraPermission(context)) return CameraMeter.PERMISSION
        val gen = generation
        var device: CameraDevice? = null
        var made: Open? = null
        var ok = false
        opening = true
        try {
            log("Setting up the back main camera")
            val cameras = try {
                CameraCatalog.read(mgr)
            } catch (e: CameraAccessException) {
                return failedText(accessReason(e))
            }
            for (c in cameras) log(CameraCatalog.describe(c))
            val chosen = when (val choice = CameraSelection.choose(cameras)) {
                is CameraChoice.Unsupported -> return choice.message
                is CameraChoice.Use -> {
                    log(choice.reason)
                    choice.camera
                }
            }
            val chars = try {
                mgr.getCameraCharacteristics(chosen.id)
            } catch (e: CameraAccessException) {
                return failedText(accessReason(e))
            }
            // Re-read with the permission granted (some keys read as null without it).
            val candidate = CameraCatalog.candidate(chosen.id, chars)
            val inf = readInfo(candidate, chars) ?: return failedText("its RAW settings couldn't be read")

            val handler = startThreads()
            device = openDevice(mgr, candidate.id, handler)
            if (gen != generation) return CameraMeter.OFF
            val reader = ImageReader.newInstance(inf.rawSize.width, inf.rawSize.height, ImageFormat.RAW_SENSOR, 4)
            val white = WhiteLevel(inf.thresholds)
            lastWhite = inf.staticWhite
            white.setFloor(floorNet)?.let(log)
            val o = Open(inf, device, reader, white)
            made = o
            onCamera { images.clear(); results.clear(); lostFrames.clear() }
            reader.setOnImageAvailableListener({ r -> onImage(o, r) }, handler)
            open = o
            createSession(o)
            if (gen != generation) return CameraMeter.OFF
            info = inf
            logSetup(inf)
            settle(o)
            if (gen != generation) return CameraMeter.OFF
            ok = true
            return null
        } catch (e: OpenFailure) {
            // A close() while opening wins over whatever the device then said.
            return if (gen != generation) CameraMeter.OFF else e.message ?: CameraMeter.SETUP_FAILED
        } catch (e: CameraAccessException) {
            val why = accessReason(e)
            log("Setup failed: $why")
            return if (e.reason == CameraAccessException.CAMERA_DISABLED) DISABLED else failedText(why)
        } catch (e: SecurityException) {
            return CameraMeter.PERMISSION
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Setup failed: ${e.message ?: e.javaClass.simpleName}")
            return failedText(e.message ?: "unknown error")
        } finally {
            opening = false
            if (!ok) {
                val o = made
                if (o != null) onCamera { teardown(o) } else runCatching { device?.close() }
            }
            // A failed open, or a close() that came while opening (it left the threads to us).
            if (open == null && (!ok || gen != generation)) stopThreads()
        }
    }

    /** The camera's static facts for [c] (permission granted), or null when something essential is missing. */
    private fun readInfo(c: CameraCandidate, ch: CameraCharacteristics): Info? {
        val iso = c.isoMin ?: return null
        val expMin = c.exposureMinNs ?: return null
        val expMax = c.exposureMaxNs ?: return null
        val cfa = Cfa.fromCamera2(c.cfa) ?: return null
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        if (c.rawWidth <= 0 || c.rawHeight <= 0) return null
        val rawSize = Size(c.rawWidth, c.rawHeight)
        val previewSize = choosePreviewSize(runCatching { map.getOutputSizes(SurfaceTexture::class.java) }.getOrNull(), rawSize)
        val minFrameRaw = runCatching { map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, rawSize) }.getOrDefault(0L)
        val minFramePreview = previewSize?.let {
            runCatching { map.getOutputMinFrameDuration(SurfaceTexture::class.java, it) }.getOrDefault(0L)
        } ?: 0L

        val pattern = ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val staticBlack = (0 until 4).map { i -> pattern?.getOffsetForIndex(i and 1, i shr 1)?.toDouble() ?: 0.0 }
        val staticWhite = ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.toDouble() ?: return null
        val th = Thresholds(staticBlack.average(), staticWhite)

        // CFA parity (design §3.3): the arrangement describes the sensor's top-left 2x2.
        val pixels = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val pre = ch.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        val layout = when {
            pixels != null && pixels.width == rawSize.width && pixels.height == rawSize.height -> CfaLayout(cfa)
            pre != null && pre.width() == rawSize.width && pre.height() == rawSize.height -> {
                if ((pre.left and 1) != 0 || (pre.top and 1) != 0) {
                    log("RAW photos start at (${pre.left}, ${pre.top}) of the sensor: colour filter parity shifted")
                }
                CfaLayout(cfa, pre.left and 1, pre.top and 1)
            }
            else -> {
                log(
                    "RAW size ${rawSize.width}x${rawSize.height} matches neither the pixel array " +
                        "(${pixels?.width ?: 0}x${pixels?.height ?: 0}) nor the active array " +
                        "(${pre?.width() ?: 0}x${pre?.height() ?: 0}): colour filter parity assumed at (0, 0)",
                )
                CfaLayout(cfa)
            }
        }

        fun has(key: CameraCharacteristics.Key<IntArray>, mode: Int): Boolean = ch.get(key)?.contains(mode) == true
        val latency = ch.get(CameraCharacteristics.SYNC_MAX_LATENCY) ?: CameraMetadata.SYNC_MAX_LATENCY_UNKNOWN
        val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val aperture = ApertureLock.choose(c.apertures, wantedAperture)
        return Info(
            camera = c,
            settings = CameraSelection.settingsFor(c, aperture),
            iso = iso,
            minDuration = max(expMin / 1e9, 1.0 / 8000),
            frameMax = min(expMax / 1e9, DarkLevel.tLong),
            exposureMinNs = expMin,
            exposureMaxNs = expMax,
            thresholds = th,
            layout = layout,
            staticBlack = staticBlack,
            staticWhite = staticWhite,
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            rawSize = rawSize,
            previewSize = previewSize,
            minFrameRawNs = minFrameRaw,
            minFramePreviewNs = minFramePreview,
            focus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                ?.let { CameraCatalog.clean(it) }?.takeIf { it > 0 },
            aperture = aperture,
            oisOff = has(
                CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
            ),
            hotPixelOff = has(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES, CameraMetadata.HOT_PIXEL_MODE_OFF),
            noiseOff = has(
                CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
                CameraMetadata.NOISE_REDUCTION_MODE_OFF,
            ),
            edgeOff = has(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, CameraMetadata.EDGE_MODE_OFF),
            aberrationOff = has(
                CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
                CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF,
            ),
            shadingOff = has(CameraCharacteristics.SHADING_AVAILABLE_MODES, CameraMetadata.SHADING_MODE_OFF),
            distortionOff = has(
                CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES,
                CameraMetadata.DISTORTION_CORRECTION_MODE_OFF,
            ),
            zoomRatio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null,
            crop = active?.let { Rect(0, 0, it.width(), it.height()) },
            perFrameControl = latency == CameraMetadata.SYNC_MAX_LATENCY_PER_FRAME_CONTROL,
            latencyFrames = if (latency > 0) min(latency, 2) else 2,
            realtimeTimestamps = ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
        )
    }

    private fun logSetup(inf: Info) {
        val c = inf.camera
        val aperture = inf.aperture?.let { "aperture locked at f/${cfmt(it, 2)}" }
            ?: "fixed aperture f/${cfmt(c.apertures.firstOrNull() ?: 0.0, 2)}"
        val shutter = "shutter 1/${cfmt(1 / inf.minDuration, 0)}–${cfmt(inf.frameMax, 3)} s"
        val frames = if (inf.frameMax < maxDuration - 1e-6) " (longer shots average several photos)" else ""
        log("RAW ${CameraSelection.formatOf(c)}, ISO ${inf.iso}, $aperture, $shutter$frames")
        val locks = listOfNotNull(
            inf.focus?.let { "focus ${cfmt(it, 1)} D (closest)" } ?: "fixed focus",
            if (inf.oisOff) "OIS off" else "no OIS control",
            "black level lock",
            "white balance, noise reduction and sharpening off",
        )
        log("Locked: " + locks.joinToString(", "))
    }

    /** The largest SurfaceTexture size with the RAW aspect within 1440x1080 (else the closest aspect). */
    private fun choosePreviewSize(sizes: Array<Size>?, raw: Size): Size? {
        if (sizes.isNullOrEmpty()) return null
        val aspect = raw.width.toDouble() / raw.height
        val fits = sizes.filter { it.width <= 1440 && it.height <= 1080 }.ifEmpty { return sizes.minByOrNull { it.width.toLong() * it.height } }
        fun off(s: Size): Double = abs(s.width.toDouble() / s.height - aspect) / aspect
        val same = fits.filter { off(it) <= 0.01 }
        return if (same.isNotEmpty()) {
            same.maxByOrNull { it.width.toLong() * it.height }
        } else {
            fits.sortedWith(compareBy<Size> { (off(it) * 1000).roundToLong() }.thenByDescending { it.width.toLong() * it.height })
                .first()
        }
    }

    private suspend fun openDevice(mgr: CameraManager, id: String, handler: Handler): CameraDevice {
        val gen = generation
        val device = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
            suspendCancellableCoroutine<CameraDevice> { cont ->
                val callback = object : CameraDevice.StateCallback() {
                    private var opened = false

                    override fun onOpened(d: CameraDevice) {
                        opened = true
                        if (cont.isActive && gen == generation) {
                            cont.resume(d)
                        } else {
                            d.close()
                            // close() came while opening (Stop, or the app went to the background): finish now
                            // as OFF instead of waiting out OPEN_TIMEOUT_MS with opLock held.
                            if (cont.isActive) cont.resumeWithException(OpenFailure(CameraMeter.OFF))
                        }
                    }

                    override fun onDisconnected(d: CameraDevice) {
                        if (!opened) {
                            d.close()
                            if (cont.isActive) {
                                cont.resumeWithException(OpenFailure(failedText("disconnected: another app may be using it")))
                            }
                        } else {
                            deviceTrouble(d, null)
                        }
                    }

                    override fun onError(d: CameraDevice, error: Int) {
                        if (!opened) {
                            d.close()
                            if (cont.isActive) {
                                val text = if (error == ERROR_CAMERA_DISABLED) DISABLED else failedText(errorReason(error))
                                cont.resumeWithException(OpenFailure(text))
                            }
                        } else {
                            deviceTrouble(d, error)
                        }
                    }
                }
                try {
                    mgr.openCamera(id, callback, handler)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        }
        return device ?: throw OpenFailure(failedText("the camera didn't answer in time"))
    }

    /** (Re)creates the session with the RAW reader and, when one is attached and sized, the preview. Holds [opLock]. */
    private suspend fun createSession(o: Open) {
        val handler = cameraHandler ?: throw OpenFailure(CameraMeter.SETUP_FAILED)
        val target = wantedPreview
        val ps = o.info.previewSize
        val preview = if (target != null && ps != null && target.surface.isValid) target else null
        preview?.texture?.setDefaultBufferSize(ps!!.width, ps.height)
        val surfaces = listOfNotNull(o.reader.surface, preview?.surface)
        val session = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
            suspendCancellableCoroutine<CameraCaptureSession> { cont ->
                val callback = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cont.isActive) cont.resume(session) else session.close()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (cont.isActive) cont.resumeWithException(OpenFailure(CameraMeter.SETUP_FAILED))
                    }
                }
                try {
                    val config = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        surfaces.map { OutputConfiguration(it) },
                        HandlerExecutor(handler),
                        callback,
                    )
                    o.device.createCaptureSession(config)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        } ?: throw OpenFailure(failedText("the camera didn't answer in time"))
        o.session = session
        o.sessionPreview = preview
        o.repeatingNs = 0
    }

    /** §3.5: the locks at 1/250 s until a result shows the lens stationary at the asked focus (≤ 3 s). */
    private suspend fun settle(o: Open) {
        val handler = cameraHandler ?: return
        val ns = exposureNs(o.info, 1.0 / 250)
        val waiter = CompletableDeferred<TotalCaptureResult?>()
        settleWaiter = o to waiter
        val result = try {
            withTimeoutOrNull(SETTLE_MS) {
                if (o.sessionPreview != null) {
                    startRepeating(o, ns)
                    waiter.await()
                } else {
                    // No viewfinder: short RAW bursts that are thrown away.
                    val discard = buildRequest(o, o.reader.surface, ns, FrameTag(0, discard = true))
                    var got: TotalCaptureResult? = null
                    while (got == null) {
                        o.session?.captureBurst(List(4) { discard }, captureCallback, handler) ?: break
                        got = withTimeoutOrNull(600) { waiter.await() }
                    }
                    got
                }
            }
        } finally {
            settleWaiter = null
        }
        o.lastExposureNs = ns
        if (result == null) {
            log("The lens didn't settle within 3 s")
            return
        }
        if (o.info.camera.logical) o.physicalId = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        o.info.focus?.let { want ->
            val got = result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { CameraCatalog.clean(it) } ?: want
            log("Focus locked at ${cfmt(got, 1)} D")
        }
        o.physicalId?.let { log("Active lens: camera $it") }
    }

    private fun settled(o: Open, r: TotalCaptureResult): Boolean {
        val state = r.get(CaptureResult.LENS_STATE)
        if (state != null && state != CameraMetadata.LENS_STATE_STATIONARY) return false
        val want = o.info.focus ?: return true
        val got = r.get(CaptureResult.LENS_FOCUS_DISTANCE)?.toDouble() ?: return true
        return FrameCheck.focusClose(got, want)
    }

    // Requests

    private fun exposureNs(inf: Info, seconds: Double): Long =
        (seconds * 1e9).roundToLong().coerceIn(inf.exposureMinNs, inf.exposureMaxNs)

    /** Design §3.2: every lock on a TEMPLATE_MANUAL request. */
    private fun buildRequest(o: Open, target: Surface, ns: Long, tag: Any?): CaptureRequest {
        val inf = o.info
        val b = o.device.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL)
        b.addTarget(target)
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
        b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, inf.iso)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
        val minFrame = max(inf.minFrameRawNs, if (o.sessionPreview != null) inf.minFramePreviewNs else 0L)
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(ns, minFrame))
        inf.focus?.let { b.set(CaptureRequest.LENS_FOCUS_DISTANCE, it.toFloat()) }
        inf.aperture?.let { b.set(CaptureRequest.LENS_APERTURE, it.toFloat()) }
        if (inf.oisOff) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        }
        b.set(CaptureRequest.BLACK_LEVEL_LOCK, true)
        if (inf.hotPixelOff) b.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_OFF)
        if (inf.noiseOff) b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
        if (inf.edgeOff) b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
        if (inf.aberrationOff) {
            b.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF)
        }
        if (inf.shadingOff) b.set(CaptureRequest.SHADING_MODE, CameraMetadata.SHADING_MODE_OFF)
        if (inf.distortionOff) {
            b.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_OFF)
        }
        b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_OFF)
        if (inf.zoomRatio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
        } else {
            inf.crop?.let { b.set(CaptureRequest.SCALER_CROP_REGION, it) }
        }
        b.setTag(tag)
        return b.build()
    }

    /** The viewfinder's repeating request at [ns] (so the preview shows what is measured). */
    private fun startRepeating(o: Open, ns: Long) {
        val s = o.session ?: return
        val target = o.sessionPreview ?: return
        val handler = cameraHandler ?: return
        try {
            repeatingSequence = s.setRepeatingRequest(buildRequest(o, target.surface, ns, null), captureCallback, handler)
            o.repeatingNs = ns
        } catch (e: Exception) {
            log("Couldn't update the camera view: ${e.message}")
        }
    }

    // Capturing

    /** Holds [opLock]. */
    private suspend fun captureLocked(seconds: Double, learns: Boolean): Shot? {
        val o = open ?: return null
        if (o.closed || o.session == null) return null
        val handler = cameraHandler ?: return null
        val inf = o.info
        val plan = FramePlan.of(seconds, inf.minDuration, inf.frameMax, maxDuration)
        val ns = exposureNs(inf, plan.perFrame)
        val serial = ++serials
        val request: CaptureRequest
        val discards: List<CaptureRequest>
        try {
            request = buildRequest(o, o.reader.surface, ns, FrameTag(serial, discard = false))
            val extra = if (inf.perFrameControl || ns == o.lastExposureNs) 0 else inf.latencyFrames
            discards = if (extra > 0) {
                val d = buildRequest(o, o.reader.surface, ns, FrameTag(serial, discard = true))
                List(extra) { d }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            log("Photo failed: ${describe(e)}")
            return null
        }
        // The viewfinder follows the exposure being measured (and, without per-frame control, the new settings
        // reach the sensor before the burst).
        val preview = o.sessionPreview
        if (preview != null && preview === wantedPreview && o.repeatingNs != ns) startRepeating(o, ns)

        val run = ShotRun(
            o, serial, FrameRequest(ns, inf.iso, inf.focus, o.physicalId), request, plan.frames, learns,
            sentAt = MeterClock.now(), total = plan.total,
        )
        val posted = handler.post {
            if (open !== o || o.closed) {
                run.finish(null)
            } else {
                current?.finish(null)
                current = run
                submit(run, discards + List(plan.frames) { request })
            }
        }
        if (!posted) return null
        val timeoutMs = ((plan.total + 8.0) * 1000).roundToLong()
        val shot = withTimeoutOrNull(timeoutMs) { run.done.await() }
        if (shot == null && !run.done.isCompleted) {
            log("The camera didn't answer in time")
            if (!handler.post { run.finish(null) }) run.done.complete(null)
        }
        if (shot != null) o.lastExposureNs = ns
        return shot
    }

    /** Camera thread. */
    private fun submit(run: ShotRun, requests: List<CaptureRequest>) {
        val s = run.o.session
        val handler = cameraHandler
        if (s == null || handler == null) {
            run.finish(null)
            return
        }
        try {
            s.captureBurst(requests, captureCallback, handler)
        } catch (e: Exception) {
            log("Photo failed: ${describe(e)}")
            run.finish(null)
        }
    }

    /** Camera thread: a frame of [run] was unusable; take another, up to MAX_TRIES failures per shot. */
    private fun retake(run: ShotRun) {
        if (run.finished) return
        run.failures++
        if (run.failures >= MAX_TRIES) {
            log("No usable photo after $MAX_TRIES tries")
            run.finish(null)
            return
        }
        submit(run, listOf(run.request))
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            settleWaiter?.let { (o, waiter) -> if (!waiter.isCompleted && settled(o, result)) waiter.complete(result) }
            val tag = request.tag as? FrameTag ?: return
            onRawResult(tag, result)
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            val tag = request.tag as? FrameTag ?: return
            val run = current ?: return
            if (tag.discard || tag.serial != run.serial) return
            log("Photo failed: " + if (failure.reason == CaptureFailure.REASON_FLUSHED) "flushed" else "capture error")
            retake(run)
        }

        override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
            val tag = request.tag as? FrameTag ?: return
            lostFrames.addLast(frameNumber)
            while (lostFrames.size > 16) lostFrames.removeFirst()
            results.entries.removeAll { it.value.frameNumber == frameNumber }
            val run = current ?: return
            if (tag.discard || tag.serial != run.serial) return
            log("Photo failed: the RAW buffer was lost")
            retake(run)
        }

        override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
            repeatStopped(sequenceId)
        }

        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
            repeatStopped(sequenceId)
        }
    }

    private fun repeatStopped(sequenceId: Int) {
        val stop = repeatStop ?: return
        if (stop.first == sequenceId) {
            repeatStop = null
            stop.second.countDown()
        }
    }

    /** Camera thread (ImageReader listener). */
    private fun onImage(o: Open, reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (e: IllegalStateException) {
            // Every buffer is held: the waiting ones are orphans of failed captures.
            dropWaiting()
            runCatching { reader.acquireNextImage() }.getOrNull()
        } ?: return
        if (open !== o || o.closed) {
            image.close()
            return
        }
        val ts = image.timestamp
        // Results arrive in frame order: one older than this image without its image lost it.
        results.keys.filter { it < ts }.forEach { results.remove(it) }
        val r = results.remove(ts)
        if (r != null) {
            pair(o, image, r)
        } else {
            images[ts] = image
            while (images.size > 2) images.remove(images.keys.first())?.close()
        }
    }

    /** Camera thread: a RAW capture's result. */
    private fun onRawResult(tag: FrameTag, result: TotalCaptureResult) {
        val o = open ?: return
        if (o.closed) return
        if (result.frameNumber in lostFrames) return
        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        // Images arrive in frame order too: one older than this result without its result was from a failed capture.
        images.keys.filter { it < ts }.forEach { images.remove(it)?.close() }
        val image = images.remove(ts)
        if (image != null) {
            pair(o, image, result)
        } else if (!tag.discard && tag.serial == current?.serial) {
            results[ts] = result
            while (results.size > 4) results.remove(results.keys.first())
        }
    }

    /** Camera thread: an image and its result. */
    private fun pair(o: Open, image: Image, result: TotalCaptureResult) {
        val tag = result.request.tag as? FrameTag
        val run = current
        if (tag == null || tag.discard || run == null || run.serial != tag.serial || run.finished || run.o !== o) {
            image.close()
            return
        }
        frame(run, image, result)
    }

    /** Camera thread: checks a frame against its request, then analyses it on the RAW thread. */
    private fun frame(run: ShotRun, image: Image, result: TotalCaptureResult) {
        val o = run.o
        val inf = o.info
        val report = FrameReport(
            exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
            focusDiopters = if (inf.focus != null) result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.toDouble() else null,
            lensState = result.get(CaptureResult.LENS_STATE),
            blackLevelLock = result.get(CaptureResult.BLACK_LEVEL_LOCK),
            activePhysicalId = if (inf.camera.logical) result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) else null,
            aeMode = result.get(CaptureResult.CONTROL_AE_MODE),
        )
        if (report.blackLevelLock == true) o.lockHonoured = true
        if (report.blackLevelLock == false && !o.lockIgnoredLogged) {
            // Some cameras (Pixel 7) release BLACK_LEVEL_LOCK whenever the exposure changes. Each frame's
            // SENSOR_DYNAMIC_BLACK_LEVEL is subtracted anyway, so the frame is still good.
            o.lockIgnoredLogged = true
            log("This camera releases the black level lock: using each photo's own black level")
        }
        FrameCheck.problem(run.asked, report, lockHonoured = o.lockHonoured)?.let { why ->
            log("Frame rejected: $why")
            image.close()
            retake(run)
            return
        }
        if (o.physicalId == null && report.activePhysicalId != null) o.physicalId = report.activePhysicalId
        if (!o.firstFrameLogged) {
            o.firstFrameLogged = true
            val lock = when (report.blackLevelLock) {
                true -> "black level lock on"
                false -> "black level lock off"
                null -> "black level lock not reported"
            }
            val ois = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)?.let {
                if (it == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF) "OIS off" else "OIS on"
            } ?: "OIS not reported"
            log("First photo: ISO ${report.iso ?: run.asked.iso}, $lock, $ois")
        }
        val expNs = report.exposureNs ?: run.asked.exposureNs.also {
            if (!o.noExposureLogged) {
                o.noExposureLogged = true
                log("The camera doesn't report its shutter time: dividing by the requested one")
            }
        }
        val black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            ?.takeIf { it.size >= 4 }?.map { it.toDouble() } ?: inf.staticBlack
        val frameWhite = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)?.toDouble() ?: inf.staticWhite
        o.white.onFrame(frameWhite, black.average())?.let(log)
        val effective = o.white.current(frameWhite)
        val size = "${image.width}x${image.height}"
        if (size != capturedSizeText) {
            capturedSizeText = size
            log("RAW photos are $size (only the centre 10% is read)")
        }
        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: image.timestamp
        val startNs = if (inf.realtimeTimestamps) ts + (System.nanoTime() - SystemClock.elapsedRealtimeNanos()) else ts
        val floor = o.white.floorNet
        val back = cameraHandler
        val posted = rawHandler?.post {
            val stats = try {
                val plane = image.planes[0]
                RawAnalysis.measure(
                    RawPlane(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride),
                    inf.layout, black, effective, floor, inf.thresholds,
                )
            } catch (e: Exception) {
                null
            } finally {
                runCatching { image.close() }
            }
            back?.post { analysed(run, stats, expNs, startNs, frameWhite, effective) }
        } == true
        if (!posted) {
            image.close()
            run.finish(null)
        }
    }

    /** Camera thread: one frame's statistics are in. */
    private fun analysed(run: ShotRun, stats: RawStats?, expNs: Long, startNs: Long, frameWhite: Double, effective: Double) {
        if (run.finished) return
        val o = run.o
        if (stats == null) {
            log("RAW photo arrived but couldn't be read")
            retake(run)
            return
        }
        o.white.learn(stats, effective, run.learns)?.let(log)
        val white = o.white.current(frameWhite)
        lastWhite = white
        run.acc.add(stats)
        run.accepted++
        run.exposureSum += expNs / 1e9
        run.white = min(run.white, white)
        if (startNs < run.firstStartNs) run.firstStartNs = startNs
        if (startNs >= run.lastStartNs) {
            run.lastStartNs = startNs
            run.lastExposureNs = expNs
        }
        if (run.accepted < run.needed) return
        val combined = run.acc.result()
        if (combined == null) {
            run.finish(null)
            return
        }
        val at = if (run.firstStartNs > 0) {
            (run.firstStartNs.toDouble() + run.lastStartNs.toDouble() + run.lastExposureNs.toDouble()) / 2 / 1e9
        } else {
            run.sentAt + run.total / 2
        }
        run.finish(Shot(combined, run.exposureSum / run.accepted, run.accepted, at, run.white, requested = run.total))
    }

    /** Camera thread: closes images waiting for a result and forgets results waiting for an image. */
    private fun dropWaiting() {
        images.values.forEach { runCatching { it.close() } }
        images.clear()
        results.clear()
    }

    // Preview

    private fun setPreviewTarget(t: PreviewTarget?) {
        val old = wantedPreview
        if (old === t) return
        wantedPreview = t
        val o = open
        if (t == null && o != null && o.sessionPreview != null) {
            // Stop the camera writing to the old surface before the view releases it (≤ 1 s).
            val latch = CountDownLatch(1)
            onCamera(wait = false) {
                val s = o.session
                if (s == null || o.repeatingNs == 0L || o.closed) {
                    latch.countDown()
                } else {
                    repeatStop = repeatingSequence to latch
                    o.repeatingNs = 0
                    try {
                        s.stopRepeating()
                    } catch (e: Exception) {
                        latch.countDown()
                    }
                }
            } ?: latch.countDown()
            latch.await(1, TimeUnit.SECONDS)
        }
        if (o != null && !o.closed) {
            engineScope.launch { reconfigure() }
        }
        if (old != null && (o == null || o.closed || o.sessionPreview !== old)) old.release()
    }

    /** Re-creates the session with the wanted preview (after any capture in progress). */
    private suspend fun reconfigure() {
        opLock.withLock {
            val o = open ?: return
            if (o.closed || o.session == null || o.sessionPreview === wantedPreview) return
            val old = o.sessionPreview
            try {
                runCatching { o.session?.stopRepeating() }
                o.repeatingNs = 0
                createSession(o)
                if (o.sessionPreview != null) startRepeating(o, o.lastExposureNs.takeIf { it > 0 } ?: exposureNs(o.info, 1.0 / 250))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val why = (e as? CameraAccessException)?.let { accessReason(it) } ?: (e.message ?: "unknown error")
                onTrouble?.invoke("The camera stopped (couldn't change the camera view: $why). Starting it again.")
                onCamera { teardown(o) }
                scheduleRetry(why)
            }
            if (old != null && old !== wantedPreview && old !== o.sessionPreview) old.release()
        }
    }

    // Trouble, retries, teardown

    /** Camera thread: the open device disconnected (code null) or reported an error. */
    private fun deviceTrouble(d: CameraDevice, code: Int?) {
        val o = open
        if (o == null || o.device !== d || o.closed) {
            runCatching { d.close() }
            return
        }
        teardown(o)
        if (code == ERROR_CAMERA_DISABLED) {
            onTrouble?.invoke("The camera stopped (the camera is disabled on this phone).")
            wanted = false
            onGaveUp?.invoke(DISABLED)
            return
        }
        val reason = if (code == null) "disconnected: another app may be using it" else errorReason(code)
        onTrouble?.invoke("The camera stopped ($reason). Starting it again.")
        scheduleRetry(reason)
    }

    /**
     * Reopens after 1, 2 and 4 s while the meter still wants the camera (§11). Meanwhile a
     * CameraManager.AvailabilityCallback watches the camera: when it is free again (onCameraAvailable for its id)
     * or camera access priorities change (another app went to the background), the next try starts at once.
     * After the three tries, if another app still holds the camera (in use, disconnected, too many cameras), the
     * engine keeps waiting for the camera to be free and reopens then, as an interrupted iPhone session resumes
     * by itself; any other failure gives up ([onGaveUp]).
     */
    private fun scheduleRetry(reason: String) {
        if (!wanted) return
        retryJob?.cancel()
        unwatchAvailability()
        val watch = info?.camera?.id?.let { watchAvailability(it) }
        retryJob = engineScope.launch {
            // Forget signals from before this round.
            while (freed.tryReceive().isSuccess) Unit
            var last = failedText(reason)
            var tries = 0
            var waitLogged = false
            try {
                while (true) {
                    if (tries < RETRY_SECONDS.size) {
                        // A freed camera cuts the wait short.
                        withTimeoutOrNull(RETRY_SECONDS[tries] * 1000) { freed.receive() }
                    } else {
                        if (last !in BUSY || watch == null || availability !== watch) break
                        if (!waitLogged) {
                            waitLogged = true
                            log("Another app is using the camera: it starts again when the camera is free")
                        }
                        freed.receive()
                        // Let the other app finish letting go (and never spin on a burst of callbacks).
                        delay(FREED_SETTLE_MS)
                        while (freed.tryReceive().isSuccess) Unit
                    }
                    tries++
                    if (!wanted) return@launch
                    val err = opLock.withLock { if (!wanted) CameraMeter.OFF else if (isOpen) null else openLocked() }
                    if (!wanted) return@launch
                    if (err == null) {
                        log("Camera back on")
                        return@launch
                    }
                    last = err
                    if (err == CameraMeter.PERMISSION || err == DISABLED || !err.startsWith("Couldn't")) break
                }
                if (wanted) {
                    wanted = false
                    onGaveUp?.invoke(last)
                }
            } finally {
                // Only this round's callback (a newer round may have registered its own).
                if (watch != null) unwatchAvailability(watch)
            }
        }
    }

    /**
     * Registers an AvailabilityCallback for camera [id] (main looper: the camera thread may be gone) and returns
     * it; null when the meter no longer wants the camera or registering failed.
     */
    private fun watchAvailability(id: String): CameraManager.AvailabilityCallback? {
        val mgr = manager ?: return null
        synchronized(threadLock) {
            if (!wanted) return null
            val callback = object : CameraManager.AvailabilityCallback() {
                override fun onCameraAvailable(cameraId: String) {
                    if (cameraId == id) freed.trySend(Unit)
                }

                override fun onCameraAccessPrioritiesChanged() {
                    freed.trySend(Unit)
                }
            }
            return try {
                mgr.registerAvailabilityCallback(callback, Handler(Looper.getMainLooper()))
                availability = callback
                callback
            } catch (e: Exception) {
                // Without it the three timed retries still run.
                null
            }
        }
    }

    /** Unregisters the AvailabilityCallback ([only] that one, when given). */
    private fun unwatchAvailability(only: CameraManager.AvailabilityCallback? = null) {
        val callback = synchronized(threadLock) {
            val cb = availability
            if (cb == null || (only != null && cb !== only)) return
            availability = null
            cb
        }
        runCatching { manager?.unregisterAvailabilityCallback(callback) }
    }

    /** Camera thread (or the caller's once the thread is gone): releases everything [o] holds. Idempotent. */
    private fun teardown(o: Open) {
        synchronized(o) {
            if (o.closed) return
            o.closed = true
        }
        if (open === o) open = null
        settleWaiter?.let { if (it.first === o) it.second.complete(null) }
        current?.let { if (it.o === o) it.finish(null) }
        dropWaiting()
        repeatStop?.second?.countDown()
        repeatStop = null
        runCatching { o.session?.stopRepeating() }
        runCatching { o.session?.close() }
        o.session = null
        runCatching { o.device.close() }
        // Closing the reader frees the buffers of the images it handed out: do it on the RAW thread, after any
        // analysis still reading one (quitSafely in stopThreads still runs it). Inline when there is no RAW thread.
        val raw = rawHandler
        if (raw == null || !raw.post { runCatching { o.reader.close() } }) runCatching { o.reader.close() }
        val preview = o.sessionPreview
        o.sessionPreview = null
        if (preview != null && preview !== wantedPreview) preview.release()
    }

    // Threads

    private fun startThreads(): Handler = synchronized(threadLock) {
        cameraHandler ?: run {
            val ct = HandlerThread("tvcal.camera").also { it.start() }
            val rt = HandlerThread("tvcal.raw").also { it.start() }
            cameraThread = ct
            rawThread = rt
            rawHandler = Handler(rt.looper)
            Handler(ct.looper).also { cameraHandler = it }
        }
    }

    private fun stopThreads() {
        synchronized(threadLock) {
            cameraHandler = null
            rawHandler = null
            cameraThread?.quitSafely()
            rawThread?.quitSafely()
            cameraThread = null
            rawThread = null
        }
    }

    /**
     * Runs [block] on the camera thread: inline when already there or when there is no camera thread; else posts
     * it and (with [wait]) waits up to 1 s. Returns null when there is no thread to run on and nothing ran.
     */
    private fun onCamera(wait: Boolean = true, block: () -> Unit): Unit? {
        val h = cameraHandler
        if (h == null || Looper.myLooper() == h.looper) {
            block()
            return Unit
        }
        if (!wait) return if (h.post(block)) Unit else null
        val latch = CountDownLatch(1)
        val posted = h.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        if (!posted) {
            block()
            return Unit
        }
        latch.await(1, TimeUnit.SECONDS)
        return Unit
    }

    private companion object {
        const val MAX_TRIES = 3
        const val SETTLE_MS = 3000L
        const val OPEN_TIMEOUT_MS = 10_000L
        val RETRY_SECONDS = listOf(1L, 2L, 4L)
        /** After the camera is freed, wait this long before opening it (the other app is still letting go). */
        const val FREED_SETTLE_MS = 500L
        const val ERROR_CAMERA_DISABLED = 3
        const val DISABLED = "The camera is disabled on this phone (device policy)."

        fun failedText(why: String): String = "Couldn't set up the camera: $why"

        /** Failures that mean another app holds the camera: after the timed retries, wait for it to be free. */
        val BUSY = setOf(
            failedText("the camera is in use by another app"),
            failedText("too many cameras are open"),
            failedText("disconnected: another app may be using it"),
        )

        /** CameraDevice.StateCallback error codes (§11). */
        fun errorReason(code: Int): String = when (code) {
            1 -> "the camera is in use by another app"
            2 -> "too many cameras are open"
            3 -> "the camera is disabled on this phone"
            4 -> "camera device error"
            5 -> "camera service error"
            else -> "error $code"
        }

        fun accessReason(e: CameraAccessException): String = when (e.reason) {
            CameraAccessException.CAMERA_DISABLED -> "the camera is disabled on this phone"
            CameraAccessException.CAMERA_DISCONNECTED -> "disconnected: another app may be using it"
            CameraAccessException.CAMERA_ERROR -> "camera device error"
            CameraAccessException.CAMERA_IN_USE -> "the camera is in use by another app"
            CameraAccessException.MAX_CAMERAS_IN_USE -> "too many cameras are open"
            else -> e.message ?: "camera error ${e.reason}"
        }

        fun describe(e: Exception): String = when (e) {
            is CameraAccessException -> accessReason(e)
            is IllegalStateException -> "the camera session closed"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
