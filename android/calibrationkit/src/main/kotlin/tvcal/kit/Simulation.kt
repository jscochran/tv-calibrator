package tvcal.kit

import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// A made-up TV and meter, used by the tests and by the app's demo mode (so the app can be tried
// without a TV). Ported from the Python engine's SimTV / SimMeter.

/** Swift.max(x, y) is `y >= x ? y : x`: NaN and signed-zero ties resolve exactly as in Swift. */
private fun sMax(x: Double, y: Double): Double = if (y >= x) y else x

/** Swift.min(x, y) is `y < x ? y : x`. */
private fun sMin(x: Double, y: Double): Double = if (y < x) y else x

/**
 * Small, seedable random numbers (SplitMix64), so a demo TV is the same every time.
 * Swift struct with mutating methods -> a class with private mutable state (never shared between holders).
 * ULong arithmetic wraps like Swift's &+ / &*.
 */
class SeededRandom(seed: ULong) {
    private var state: ULong = seed + 0x9E3779B97F4A7C15uL

    fun next(): ULong {
        state += 0x9E3779B97F4A7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return z xor (z shr 31)
    }

    /** 0 ..< 1 */
    fun unit(): Double = (next() shr 11).toDouble() / (1L shl 53).toDouble()

    fun uniform(a: Double, b: Double): Double = a + (b - a) * unit()

    fun gauss(mu: Double, sigma: Double): Double {
        val u1 = sMax(unit(), 1e-12)
        val u2 = unit()
        return mu + sigma * sqrt(-2 * ln(u1)) * cos(2 * PI * u2)
    }
}

/**
 * A TV with white-balance, gamma and colour errors that responds to menu changes roughly the way a
 * real one does (not exactly like the engine's model). A reference type (Swift final class).
 */
class SimTV(val hdr: Boolean, state: State? = null, seed: ULong = 7uL) {
    /** Immutable: change it by assigning `s = s.copy(...)`. */
    @Serializable
    data class State(
        val peak: Double,
        val gainErr: List<Double>,
        val offErr: List<Double>,
        val gamma: List<Double>,
        val colErr: Map<String, List<Double>>,
        val ctrl: Map<String, Double>,
        /** Each multipoint control acts this far (0-1) above its label. */
        val p20Shift: Double,
    )

    var s: State

    internal val kTrue = 0.0075
    internal val gainStep = listOf(0.0045, 0.0040, 0.0055)
    internal val offStep = listOf(0.0030, 0.0035, 0.0110)        // blue offset ~3x stronger, like the Micro RGB
    internal val p20Step = listOf(0.0070, 0.0050, 0.0040)
    /** Hue/sat/lum menus in the demo: degrees, % and % per click. */
    internal val hslStep = mapOf("hue" to 1.2, "sat" to 0.9, "lum" to 1.1)

    init {
        s = state ?: run {
            val rnd = SeededRandom(seed)
            // Draws happen in Patterns.colours order, then field by field, as Swift evaluates them.
            val col = LinkedHashMap<String, List<Double>>()
            for ((n, _) in Patterns.colours) {
                col[n] = listOf(rnd.uniform(-0.06, 0.06), rnd.uniform(-0.06, 0.06), rnd.uniform(-0.06, 0.06))
            }
            State(
                peak = if (hdr) 700.0 else 160.0,
                gainErr = listOf(rnd.uniform(-0.06, 0.0), rnd.uniform(-0.02, 0.0), rnd.uniform(-0.06, 0.0)),
                offErr = listOf(rnd.uniform(-0.03, 0.03), rnd.uniform(-0.03, 0.03), rnd.uniform(-0.03, 0.03)),
                gamma = listOf(2.4 + rnd.uniform(-0.12, 0.12), 2.4 + rnd.uniform(-0.12, 0.12), 2.4 + rnd.uniform(-0.12, 0.12)),
                colErr = col.toMap(),
                ctrl = emptyMap(),
                p20Shift = 0.0,
            )
        }
    }

    /** Menu changes, in clicks. Menus stop at +/-50. */
    fun apply(changes: Map<String, Int>) {
        for ((k, v) in changes) {
            s = s.copy(ctrl = s.ctrl + (k to clamp((s.ctrl[k] ?: 0.0) + v.toDouble(), -50.0, 50.0)))
        }
    }

    /**
     * Power limiter: if the brightest channel at full white would exceed the panel's max, everything
     * is scaled down (so raising all gains together does nothing).
     */
    internal fun limit(): Double {
        // Swift Sequence.max() keeps the first element unless a later one compares greater.
        val vals = (0 until 3).map { chanRaw(it, 1.0, null) }
        var m = vals[0]
        for (i in 1 until vals.size) if (m < vals[i]) m = vals[i]
        return if (m > 1) 1 / m else 1.0
    }

    internal fun chan(c: Int, level: Double, keyLevel: Double? = null): Double =
        chanRaw(c, level, keyLevel) * limit()

    internal fun chanRaw(c: Int, level: Double, keyLevel: Double?): Double {
        val C = listOf("R", "G", "B")[c]
        val kl = keyLevel ?: level
        val g = s.ctrl["gain:$C"] ?: 0.0
        val o = s.ctrl["offset:$C"] ?: 0.0
        // multipoint: tent interpolation between 5% points, spilling into neighbours
        var p = 0.0
        for ((key, v) in s.ctrl) {
            if (!key.startsWith("p20:$C:")) continue
            // Swift's split(separator:) drops empty pieces.
            val parts = key.split(":").filter { it.isNotEmpty() }
            if (parts.size != 3) continue
            val lvInt = parts[2].toDoubleOrNull() ?: continue
            val lv = lvInt / 100
            p += v * sMax(0.0, 1 - abs(kl - (lv + s.p20Shift)) / 0.09)
        }
        val base = level.pow(s.gamma[c])
        var value = base * (1 + s.gainErr[c] + g * gainStep[c]) * (1 + p * p20Step[c])
        value += (s.offErr[c] + o * offStep[c]) * 0.02 * (1 - level).pow(2) * (1 - exp(-30 * level))
        return sMax(value, 0.0)
    }

    /** What a meter on the centre of the screen sees for a patch. */
    fun render(patch: Patch): Vec3 {
        val peak = s.peak
        if (patch.kind == PatchKind.WHITE || patch.kind == PatchKind.GREY) {
            var rgb = Vec3(0.0, 0.0, 0.0)
            val nits = patch.nits?.x
            if (hdr && nits != null) {
                val lvl = if (nits < peak) (nits / peak).pow(1 / 2.4) else 1.0
                for (c in 0 until 3) rgb = rgb.with(c, sMin(chan(c, sMin(lvl, 1.0), patch.level), 1.02) * peak)
            } else {
                for (c in 0 until 3) rgb = rgb.with(c, chan(c, patch.level ?: 0.0) * peak)
            }
            return ColourMath.matvec(ColourMath.m709, rgb)
        }
        // colour: native primaries a bit wider than the target, plus per-colour errors + menu sliders
        val name = patch.id
        val target = patch.rgb ?: Vec3(0.0, 0.0, 0.0)
        val lvl = if (hdr) (100 / peak).pow(1 / 2.4) else 0.75
        var mix = Vec3(0.0, 0.0, 0.0)
        for ((j, C) in listOf("R", "G", "B").withIndex()) {
            val cms = (s.ctrl["cms:$name:$C"] ?: 0.0) * kTrue
            mix = mix.with(j, sMax(0.0, target[j] + (s.colErr[name]?.get(j) ?: 0.0) + cms) * chan(j, lvl) * peak)
        }
        val native: List<Point2> = if (hdr) ColourMath.Gamut.P3.primaries
            else listOf(Point2(0.655, 0.325), Point2(0.290, 0.615), Point2(0.150, 0.058))
        var XYZ = ColourMath.matvec(ColourMath.rgbToXYZMatrix(native), mix)
        // demo extra: hue / saturation / brightness sliders move the colour around D65
        val h = s.ctrl["hsl:$name:hue"] ?: 0.0
        val sa = s.ctrl["hsl:$name:sat"] ?: 0.0
        val lu = s.ctrl["hsl:$name:lum"] ?: 0.0
        if (h != 0.0 || sa != 0.0 || lu != 0.0) {
            val xy = ColourMath.xyzToXY(XYZ)
            val w = ColourMath.d65
            val ang = h * (hslStep["hue"] ?: 1.0) * PI / 180
            val scale = 1 + sa * (hslStep["sat"] ?: 1.0) / 100
            val dx = (xy.x - w.x) * scale
            val dy = (xy.y - w.y) * scale
            val nx = w.x + dx * cos(ang) - dy * sin(ang)
            val ny = w.y + dx * sin(ang) + dy * cos(ang)
            XYZ = ColourMath.xyYToXYZ(Point2(nx, ny), XYZ.y * (1 + lu * (hslStep["lum"] ?: 1.0) / 100))
        }
        return XYZ
    }

    /** A sync flash at a drive level (SDR signal, or HDR nits converted to this TV's drive). */
    internal fun renderSync(value: Double): Vec3 {
        val lvl = if (hdr) (value / s.peak).pow(1 / 2.4) else value
        val rgb = Vec3(chan(0, lvl) * s.peak, chan(1, lvl) * s.peak, chan(2, lvl) * s.peak)
        return ColourMath.matvec(ColourMath.m709, rgb)
    }
}

/**
 * Virtual clock + a pattern video playing + a SimTV. Runs much faster than real time unless
 * `realTime` is set (the demo uses 0.1: ten times faster than real, so progress is watchable).
 */
class SimMeter(
    internal val tv: SimTV,
    sequence: PatternSequence,
    internal val playAt: Double = 3.0,
    internal val realTime: Double = 0.0,
    seed: ULong = 1uL,
) : Meter {
    internal val seq: PatternSequence = sequence
    internal var t = 0.0
    internal var rnd = SeededRandom(seed)

    override val integration: Double get() = 0.25

    override fun now(): Double = t

    /** Advances the virtual clock; yields (or sleeps seconds * realTime when realTime > 0). */
    override suspend fun sleep(seconds: Double) {
        if (seconds > 0) {
            t += seconds
            if (realTime > 0) MeterClock.sleep(seconds * realTime) else yield()
        }
    }

    internal sealed class Showing {
        data object Nothing : Showing()
        data class Sync(val level: Double) : Showing()
        data class Patch(val patch: tvcal.kit.Patch) : Showing()
    }

    internal fun showing(time: Double): Showing {
        var v = time - playAt
        if (v < Patterns.LEAD) return Showing.Nothing
        v -= Patterns.LEAD
        if (v < 3 * Patterns.SYNC_HOLD) {
            val which = (v / Patterns.SYNC_HOLD).toInt()
            return Showing.Sync(if (which == 1) seq.syncHigh else seq.syncLow)
        }
        v -= 3 * Patterns.SYNC_HOLD
        for ((p, off) in seq.patches.zip(seq.offsets)) {
            if (off <= v && v < off + p.hold) return Showing.Patch(p)
        }
        return Showing.Nothing
    }

    internal fun render(s: Showing): Vec3 = when (s) {
        is Showing.Nothing -> Vec3(0.03, 0.03, 0.035)
        is Showing.Sync -> tv.renderSync(s.level)
        is Showing.Patch -> tv.render(s.patch)
    }

    override suspend fun read(): MeterReading {
        val t0 = t
        val Y = render(showing(t0 + 0.3)).y
        val dur = 0.9 + 0.25 * rnd.unit() + (if (Y < 5) 1.6 / sMax(Y, 0.05).pow(0.5) else 0.0)
        t += dur
        if (realTime > 0) MeterClock.sleep(dur * realTime) else yield()
        // a real meter integrates over a short window, so readings across a change are mixed
        val win = 0.5
        var sum = Vec3(0.0, 0.0, 0.0)
        for (k in 0 until 5) {
            sum += render(showing(t - win + win * (k.toDouble() + 0.5) / 5))
        }
        val common = 1 + rnd.gauss(0.0, 0.003)                             // brightness noise
        val XYZ = sum / 5.0 * common * (1 + rnd.gauss(0.0, 0.0006))       // small colour noise
        return MeterReading(start = t0, end = t, xyz = XYZ)
    }
}

/** The pattern the app is currently asking for, readable from any thread (the demo meter uses it). */
class ShownPatch {
    private val lock = Any()
    private var value: Patch? = null

    var patch: Patch?
        get() = synchronized(lock) { value }
        set(newValue) {
            synchronized(lock) { value = newValue }
        }
}

/**
 * Demo mode for the guided steps: reads whichever patch the app is asking for (as if the right file
 * were playing), in real time.
 */
class SimLiveMeter(internal val tv: SimTV, internal val shown: ShownPatch) : Meter {
    internal var rnd = SeededRandom(99uL)

    internal fun showing(): Patch? = shown.patch

    override val integration: Double get() = 0.1

    override fun now(): Double = MeterClock.now()

    override suspend fun sleep(seconds: Double) {
        MeterClock.sleep(seconds)
    }

    override suspend fun read(): MeterReading {
        val t0 = now()
        val p = showing()
        var XYZ = p?.let { tv.render(it) } ?: Vec3(0.03, 0.03, 0.035)
        XYZ = XYZ * (1 + rnd.gauss(0.0, 0.002))
        XYZ = XYZ.copy(x = XYZ.x * (1 + rnd.gauss(0.0, 0.0015)))
        XYZ = XYZ.copy(z = XYZ.z * (1 + rnd.gauss(0.0, 0.0015)))
        MeterClock.sleep(if (XYZ.y > 5) 0.35 else 0.8)
        return MeterReading(start = t0, end = now(), xyz = XYZ)
    }
}
