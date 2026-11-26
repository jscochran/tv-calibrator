package tvcal.kit

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlin.time.Duration.Companion.nanoseconds

// MARK: - Measurements

/** A patch that got no reading (too dark for the meter, or the meter too slow). */
@Serializable
data class MissedPatch(
    val name: String,
    /** How long each reading attempt took, in seconds. */
    @SerialName("readings_s") val readings: List<Double>,
    /** Seconds the patch was on screen. */
    @SerialName("hold_s") val hold: Double,
)

/**
 * One round's readings: XYZ per patch id. Encodes like the Mac app's history files:
 * {"W0": [X, Y, Z], "G5": null, ..., "_missed": {...}}.
 *
 * Immutable (Swift value type): `m[id]` reads; `with(id, xyz)` / `withMissed(id, patch)` return a changed copy
 * (Swift's `m[id] = xyz` / `m.missed[id] = patch`).
 */
@Serializable(with = MeasurementsSerializer::class)
data class Measurements(
    val values: Map<String, Vec3> = emptyMap(),
    val missed: Map<String, MissedPatch> = emptyMap(),
) {
    operator fun get(id: String): Vec3? = values[id]

    /** Swift `m[id] = xyz` (null removes the value). */
    fun with(id: String, xyz: Vec3?): Measurements =
        if (xyz == null) copy(values = values - id) else copy(values = values + (id to xyz))

    /** Swift `m.missed[id] = patch`. */
    fun withMissed(id: String, patch: MissedPatch): Measurements = copy(missed = missed + (id to patch))
}

/**
 * The Swift custom Codable: values as [x, y, z] arrays, a null for each missed patch that has no value, and
 * "_missed" (only when not empty). Decoding skips nulls and arrays that aren't 3 long, like Swift.
 */
object MeasurementsSerializer : KSerializer<Measurements> {
    private val missedSerializer = MapSerializer(String.serializer(), MissedPatch.serializer())
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Measurements) {
        val enc = encoder as? JsonEncoder ?: throw SerializationException("Measurements can only be written as JSON")
        val obj = buildJsonObject {
            for ((k, v) in value.values) {
                put(k, JsonArray(listOf(JsonPrimitive(v.x), JsonPrimitive(v.y), JsonPrimitive(v.z))))
            }
            for (k in value.missed.keys) {
                if (value.values[k] == null) put(k, JsonNull)
            }
            if (value.missed.isNotEmpty()) {
                put("_missed", enc.json.encodeToJsonElement(missedSerializer, value.missed))
            }
        }
        enc.encodeJsonElement(obj)
    }

    override fun deserialize(decoder: Decoder): Measurements {
        val dec = decoder as? JsonDecoder ?: throw SerializationException("Measurements can only be read from JSON")
        val obj = dec.decodeJsonElement() as? JsonObject ?: throw SerializationException("Measurements: expected an object")
        val values = LinkedHashMap<String, Vec3>()
        var missed: Map<String, MissedPatch> = emptyMap()
        for ((k, e) in obj) {
            if (k == "_missed") {
                missed = dec.json.decodeFromJsonElement(missedSerializer, e)
                continue
            }
            when (e) {
                JsonNull -> {}
                is JsonArray -> {
                    val v = e.map { x ->
                        val p = x as? JsonPrimitive
                        if (p == null || p.isString) throw SerializationException("Measurements[$k]: expected numbers")
                        p.doubleOrNull ?: throw SerializationException("Measurements[$k]: expected numbers")
                    }
                    if (v.size == 3) values[k] = Vec3(v[0], v[1], v[2])
                }
                else -> throw SerializationException("Measurements[$k]: expected an array")
            }
        }
        return Measurements(values, missed)
    }
}

// MARK: - Meters

/** One reading: when it started and ended (on the meter's own clock, seconds) and what it saw. */
data class MeterReading(val start: Double, val end: Double, val xyz: Vec3?)

/** Anything that measures the TV: the phone camera (with its profile), or a simulation. (Swift protocol.) */
interface Meter {
    /** Seconds on the meter's clock (monotonic). Simulations use a virtual clock. */
    fun now(): Double
    suspend fun sleep(seconds: Double)
    suspend fun read(): MeterReading
    /** How long before a reading's end the light it reports was captured (the sync uses this). */
    val integration: Double
}

/** Real-time clock helpers for meters that measure real light. */
object MeterClock {
    /** Monotonic seconds (Swift: ProcessInfo.systemUptime). */
    fun now(): Double = System.nanoTime() / 1e9

    /** Swift Task.sleep: does nothing for seconds <= 0. */
    suspend fun sleep(seconds: Double) {
        if (seconds > 0) {
            delay((seconds * 1_000_000_000).toLong().nanoseconds)
        }
    }
}

/** Swift `enum MeasureError: Error`. Equality: NoSync/OutOfOrder are singletons, Message compares its text. */
sealed class MeasureError : Exception() {
    data object NoSync : MeasureError()
    data object OutOfOrder : MeasureError()
    data class Message(val s: String) : MeasureError()

    /** Swift LocalizedError.errorDescription. */
    val errorDescription: String
        get() = when (this) {
            NoSync ->
                "No sync flashes seen. Check the camera is pointed at the centre of the screen and the video is playing from the start."
            OutOfOrder ->
                "The greys came in out of order, so the camera and the video got out of step. Start the measurement, then play the video from the very beginning."
            is Message -> s
        }

    override val message: String get() = errorDescription
}

/** Progress while a video is being measured. */
sealed class SweepEvent {
    data object Waiting : SweepEvent()
    data class Reading(val y: Double?) : SweepEvent()
    data object Synced : SweepEvent()
    data class PatchStart(val index: Int, val id: String, val name: String) : SweepEvent()
    data class Patch(val index: Int, val id: String, val name: String, val xyz: Vec3?) : SweepEvent()
}

// MARK: - Following a pattern video

object Sweep {
    internal fun median(v: List<Double>): Double {
        val s = v.sorted()
        return s[s.size / 2]
    }

    internal fun neutral(v: Vec3, limit: Double = 0.05): Boolean =
        v.y > 0 && ColourMath.polar(ColourMath.xyzToXY(v)).r < limit

    /** A run of similar readings. Immutable: replace runs[last] with copy(ys = ys + y, ...). */
    internal data class Run(val ys: List<Double>, val ts: List<Double>, val xyz: List<Vec3>)

    /**
     * Waits for the sync and returns the time (meter clock) the patch sequence starts.
     *
     * The video goes: black, grey, WHITE, grey, then the first (bright) patch. This looks for
     * bright -> grey (~6 s) -> bright, all three neutral (not a colour). Readings that straddle a
     * change are skipped, and anything shown before PLAY is ignored.
     * Throws MeasureError.NoSync after `timeout` meter seconds; checks for coroutine cancellation each reading.
     */
    suspend fun findSync(meter: Meter, timeout: Double = 240.0, onEvent: ((SweepEvent) -> Unit)? = null): Double {
        onEvent?.invoke(SweepEvent.Waiting)
        val tStart = meter.now()
        var runs: List<Run> = emptyList()
        while (meter.now() - tStart < timeout) {
            currentCoroutineContext().ensureActive()
            val r = meter.read()
            val XYZ = r.xyz
            if (XYZ == null) {
                onEvent?.invoke(SweepEvent.Reading(null))
                continue
            }
            val t = r.end - meter.integration
            val y = swiftMax(XYZ.y, 1e-4)
            onEvent?.invoke(SweepEvent.Reading(XYZ.y))
            val last = runs.lastOrNull()
            runs = if (last != null && 1 / 1.6 < y / median(last.ys) && y / median(last.ys) < 1.6) {
                runs.dropLast(1) + last.copy(ys = last.ys + y, ts = last.ts + t, xyz = last.xyz + XYZ)
            } else {
                runs + Run(ys = listOf(y), ts = listOf(t), xyz = listOf(XYZ))
            }
            if (runs.size > 10) runs = runs.drop(runs.size - 10)
            // drop single-reading runs that sit between their neighbours (a reading caught mid-change)
            val clean = ArrayList<Run>()
            for ((i, run) in runs.withIndex()) {
                if (i > 0 && i < runs.size - 1 && run.ys.size == 1) {
                    val a = median(runs[i - 1].ys)
                    val b = run.ys[0]
                    val c = median(runs[i + 1].ys)
                    if (swiftMin(a, c) < b && b < swiftMax(a, c)) continue
                }
                clean.add(run)
            }
            if (clean.size < 3) continue
            val H1 = clean[clean.size - 3]
            val L = clean[clean.size - 2]
            val H2 = clean[clean.size - 1]
            val h1 = median(H1.ys)
            val l = median(L.ys)
            val h2 = median(H2.ys)
            // the sync's grey is a mid grey, not black: bright/grey ratio ~4-20, never ~1000
            if (!(2.0 <= h1 / l && h1 / l <= 40.0 && 1.6 <= h2 / l && h2 / l <= 40.0 && 0.3 < h1 / h2 && h1 / h2 < 3.5)) {
                continue
            }
            // all three must be greys/whites, not colour patches
            if (!listOf(H1, L, H2).all { neutral(it.xyz[it.xyz.size / 2]) }) continue
            // The grey started between the last bright reading and the first grey one, and ended
            // between the last grey reading and the first bright one: work with ranges.
            val fallLo = H1.ts[H1.ts.size - 1]
            val fallHi = L.ts[0]
            val riseLo = L.ts[L.ts.size - 1]
            val riseHi = H2.ts[0]
            val tol = 0.6
            if (!(riseLo - fallHi - tol <= Patterns.SYNC_HOLD && Patterns.SYNC_HOLD <= riseHi - fallLo + tol)) continue
            val lo = swiftMax(riseLo, fallLo + Patterns.SYNC_HOLD)
            val hi = swiftMin(riseHi, fallHi + Patterns.SYNC_HOLD)
            val est = if (lo <= hi) (lo + hi) / 2 else (riseLo + riseHi) / 2
            onEvent?.invoke(SweepEvent.Synced)
            return est
        }
        throw MeasureError.NoSync
    }

    /** Measures every patch of a pattern video, following its timeline from the sync. */
    suspend fun sweep(meter: Meter, sequence: PatternSequence, onEvent: ((SweepEvent) -> Unit)? = null): Measurements {
        val seq = sequence
        val tSeq = findSync(meter, onEvent = onEvent)
        val offsets = seq.offsets
        var meas = Measurements()
        for ((i, p) in seq.patches.withIndex()) {
            val start = tSeq + offsets[i]
            val end = start + p.hold
            onEvent?.invoke(SweepEvent.PatchStart(index = i, id = p.id, name = p.name))
            while (meter.now() < start + 1.2) {
                currentCoroutineContext().ensureActive()
                meter.sleep(swiftMin(0.25, start + 1.2 - meter.now()))
            }
            val vals = ArrayList<Vec3>()
            val tries = ArrayList<Double>()
            while (meter.now() < end - 1.0) {
                currentCoroutineContext().ensureActive()
                val r = meter.read()
                tries.add(pyRound(r.end - r.start, 1))
                onEvent?.invoke(SweepEvent.Reading(r.xyz?.y))
                val XYZ = r.xyz
                if (XYZ == null || !(r.start >= start + 0.5)) continue
                if (r.end - meter.integration <= end - 0.15) {
                    vals.add(XYZ)
                }
                // bright patches: one more reading if there's clearly time
                if (r.end + (r.end - r.start) > end - 0.6) break
            }
            meas = if (vals.isEmpty()) {
                meas.withMissed(p.id, MissedPatch(name = p.name, readings = tries.toList(), hold = p.hold))
            } else {
                meas.with(p.id, vals.fold(Vec3(0.0, 0.0, 0.0)) { acc, v -> acc + v } / vals.size.toDouble())
            }
            onEvent?.invoke(SweepEvent.Patch(index = i, id = p.id, name = p.name, xyz = meas[p.id]))
        }
        return meas
    }

    /** Readings in the wrong order mean the video and the meter got out of step. */
    fun outOfOrder(meas: Measurements, hdr: Boolean): Boolean {
        val ys = Patterns.sequence(hdr = hdr).patches.filter { it.kind == PatchKind.GREY }.mapNotNull { meas[it.id]?.y }
        return ys.zip(ys.drop(1)).any { (a, b) -> b < a * 0.8 }
    }
}
