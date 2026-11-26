package tvcal.android.measure

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import tvcal.kit.Guided
import tvcal.kit.LiveAnalysis
import tvcal.kit.LiveReading
import tvcal.kit.MeasureError
import tvcal.kit.Measurements
import tvcal.kit.Meter
import tvcal.kit.MeterClock
import tvcal.kit.Patch
import tvcal.kit.PatchKind
import tvcal.kit.PatternSequence
import tvcal.kit.Sweep
import tvcal.kit.SweepEvent
import tvcal.kit.Vec3

/** Swift `error.localizedDescription`: MeasureError's own text, else the exception's message. */
private fun describe(e: Throwable): String = e.message ?: e.toString()

/**
 * Follows a pattern video with the camera: waits for the sync flashes, then measures each patch.
 * Observable progress for the measuring card. The sweep runs in [scope] (app scope, Main.immediate), so
 * cancelling the caller of [run] doesn't stop it; only [cancel] does.
 */
class SweepRunner(private val scope: CoroutineScope) {
    sealed class Phase {
        data object Idle : Phase()
        data object Waiting : Phase()
        data object Measuring : Phase()
        data object Done : Phase()
        data object Cancelled : Phase()
        data class Failed(val text: String) : Phase()
    }

    data class PatchProgress(
        val id: String,
        val name: String,
        val kind: PatchKind,
        val Y: Double? = null,
        val done: Boolean = false,
    )

    var phase: Phase by mutableStateOf(Phase.Idle)
        private set
    var patches: List<PatchProgress> by mutableStateOf(emptyList())
        private set
    var index: Int by mutableStateOf(-1)
        private set
    /** Recent brightness readings (last 40), for the little live graph. */
    var recent: List<Double> by mutableStateOf(emptyList())
        private set
    var latestY: Double? by mutableStateOf(null)
        private set

    private var task: Deferred<Measurements?>? = null

    /**
     * Which run may still change the published state. A new run (or [reset]) makes an older sweep's late
     * events and its final phase harmless; [cancel] alone keeps it, so the cancelled run reports Cancelled.
     */
    private var generation = 0

    val running: Boolean get() = phase == Phase.Waiting || phase == Phase.Measuring

    /**
     * Measures a video (Sweep.sweep with an event callback). Returns null if cancelled or failed (the phase
     * says why: Cancelled, or Failed(error text; MeasureError.OutOfOrder.errorDescription when checkOrder
     * and Sweep.outOfOrder)).
     */
    suspend fun run(meter: Meter, sequence: PatternSequence, checkOrder: Boolean = false): Measurements? {
        cancel()
        val gen = ++generation
        phase = Phase.Waiting
        index = -1
        recent = emptyList()
        latestY = null
        patches = sequence.patches.map { PatchProgress(id = it.id, name = it.name, kind = it.kind) }
        // Swift hops the events through an AsyncStream onto the main actor; here the sweep runs on the scope's
        // (main) dispatcher, so its callback is already on the right thread and is handled directly, in order.
        val work = scope.async {
            try {
                val meas = Sweep.sweep(meter, sequence, onEvent = { e -> if (gen == generation) handle(e) })
                if (checkOrder && Sweep.outOfOrder(meas, hdr = sequence.hdr)) {
                    if (gen == generation) phase = Phase.Failed(MeasureError.OutOfOrder.errorDescription)
                    return@async null
                }
                if (gen == generation) phase = Phase.Done
                meas
            } catch (e: CancellationException) {
                if (gen == generation) phase = Phase.Cancelled
                null
            } catch (e: Exception) {
                if (gen == generation) phase = Phase.Failed(describe(e))
                null
            }
        }
        task = work
        return try {
            work.await()
        } catch (e: CancellationException) {
            // The sweep was cancelled (a cancelled Deferred throws on await). Rethrow only if our caller was
            // cancelled too; otherwise report it like Swift: nil.
            currentCoroutineContext().ensureActive()
            null
        }
    }

    fun cancel() {
        task?.cancel()
        task = null
    }

    /** cancel(), phase = Idle. */
    fun reset() {
        generation++
        cancel()
        phase = Phase.Idle
    }

    private fun handle(e: SweepEvent) {
        when (e) {
            SweepEvent.Waiting -> phase = Phase.Waiting
            is SweepEvent.Reading -> {
                latestY = e.y
                val y = e.y
                if (y != null) recent = (recent + y).takeLast(40)
            }
            SweepEvent.Synced -> phase = Phase.Measuring
            is SweepEvent.PatchStart -> index = e.index
            is SweepEvent.Patch -> {
                if (e.index < patches.size) {
                    patches = patches.toMutableList().also {
                        it[e.index] = it[e.index].copy(done = true, Y = e.xyz?.y)
                    }
                }
            }
        }
    }
}

/** Reads one pattern continuously for the live guided steps, and says how far off it is. */
class LiveSession(
    private val scope: CoroutineScope,
    /** Seconds, monotonic (Swift MeterClock.now()); tests pass the virtual clock. */
    private val clock: () -> Double = MeterClock::now,
) {
    var reading: LiveReading? by mutableStateOf(null)
        private set
    var error: String? by mutableStateOf(null)
        private set
    var patch: Patch? by mutableStateOf(null)
        private set

    private var readings: List<Vec3> = emptyList()
    private var since = 0.0
    private var hdr = false
    private var whiteY: Double? = null
    private var basis: List<Vec3>? = null
    private var meter: Meter? = null
    private var loop: Job? = null

    /**
     * Starts (or switches to) reading a pattern. The loop keeps a reading only if xyz != null, the patch is
     * unchanged and it started >= since + 0.6 s; window of the last 14; Guided.evaluate. Errors: text in
     * [error], retry after 1 s. Idle poll every 0.2 s.
     */
    fun show(p: Patch, hdr: Boolean, whiteY: Double?, basis: List<Vec3>?, meter: Meter) {
        this.patch = p
        this.hdr = hdr
        this.whiteY = whiteY
        this.basis = basis
        this.meter = meter
        readings = emptyList()
        reading = null
        error = null
        since = clock()
        if (loop == null) {
            loop = scope.launch { runLoop() }
        }
    }

    /** Use after changing a setting, to see its effect sooner. */
    fun restartAverage() {
        readings = emptyList()
        since = clock()
    }

    /** Cancels the loop; patch and reading become null. */
    fun stop() {
        loop?.cancel()
        loop = null
        patch = null
        reading = null
    }

    private suspend fun runLoop() {
        while (true) {
            currentCoroutineContext().ensureActive()
            val p = patch
            val m = meter
            if (p == null || m == null) {
                delay(200)
                continue
            }
            val started = clock()
            try {
                val r = m.read()
                // A read that doesn't suspend cancellably (the camera) can finish after stop(): drop it.
                currentCoroutineContext().ensureActive()
                error = null
                // ignore readings that began before this pattern was asked for
                val xyz = r.xyz
                if (xyz != null && patch?.id == p.id && started >= since + 0.6) {
                    readings = (readings + xyz).takeLast(14)
                    reading = Guided.evaluate(patch = p, readings = readings, hdr = hdr, whiteY = whiteY, basis = basis)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = describe(e)
                delay(1000)
            }
        }
    }
}

/** The free-running live meter (no pattern needed). */
class FreeMeter(private val scope: CoroutineScope) {
    var analysis: LiveAnalysis? by mutableStateOf(null)
        private set
    var error: String? by mutableStateOf(null)
        private set

    private var loop: Job? = null

    /** Loop: meter.read() → LiveAnalysis.of(xyz, hdr); errors shown in [error], retry after 1 s. */
    fun start(meter: Meter, hdr: Boolean) {
        stop()
        loop = scope.launch {
            while (true) {
                currentCoroutineContext().ensureActive()
                try {
                    val r = meter.read()
                    currentCoroutineContext().ensureActive()
                    val xyz = r.xyz
                    if (xyz != null) analysis = LiveAnalysis.of(xyz, hdr)
                    error = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = describe(e)
                    delay(1000)
                }
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }
}
