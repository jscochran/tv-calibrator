package tvcal.kit

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

// Guided calibration: the TV plays one pattern at a time (a 10-minute file from the USB stick), the
// camera reads continuously, and the app says in plain words which control to move and which way.

/** The patches each guided step uses. */
data class GuidePlan(
    /** Bright end (Gain) and dark end (Offset) patches. */
    val gain: String,
    val offset: String,
    /** Greys for the bright / middle / dark check. */
    val check: List<String>,
    /** Greys of the before/after quick check (the colours are added to them). */
    val quick: List<String>,
    /** Greys for multipoint fine-tuning. */
    val fine: List<String>,
) {
    /** The before/after check: the quick greys plus the six colours. */
    val quickWithColours: List<String> get() = quick + Patterns.colourIDs

    companion object {
        fun forHDR(hdr: Boolean): GuidePlan =
            if (hdr) {
                GuidePlan(
                    gain = "G65", offset = "G35", check = listOf("G65", "G50", "G35"),
                    quick = Patterns.quickIDs(true).take(6), fine = listOf("G40", "G50", "G60", "G70"),
                )
            } else {
                GuidePlan(
                    gain = "W0", offset = "G30", check = listOf("W0", "G50", "G30"),
                    quick = Patterns.quickIDs(false).take(6), fine = listOf("G20", "G40", "G60", "G80"),
                )
            }
    }
}

/** A colour the TV can't reach with its current primaries: how close it can get. */
data class OutOfReach(
    /** Sliders that would have to go below zero ("R", "G", "B"). */
    val channels: List<String>,
    val bestDE: Double,
)

/**
 * Plain-language state of one pattern: how far off it is, and what to do about it.
 * A builder like the Swift struct: `var` fields, all of immutable types, so copy() is a full value copy.
 */
data class LiveReading(
    var id: String,
    var name: String,
    var kind: PatchKind,
    var Y: Double,
    var xy: Point2,
    var uv: Point2,
    var d65uv: Point2,

    // greys and whites
    /** Red and blue relative to green, minus 1 (0 = on target). */
    var qR: Double? = null,
    var qB: Double? = null,
    var de: Double? = null,
    var ok: Boolean = false,

    // colours
    var tuv: Point2? = null,
    var hue: Double? = null,
    var sat: Double? = null,
    var lum: Double? = null,
    var toward: String? = null,
    /** R/G/B slider menus: which slider moves this colour toward its target (share of the main slider). */
    var rgbHint: Vec3? = null,
    /** The hint uses ideal primaries (the Before check didn't measure all three). */
    var rgbApprox: Boolean = false,
    var outOfReach: OutOfReach? = null,

    // filled in by the live session
    var steady: Boolean = false,
    var averaged: Int = 0,
    var need: Int = 3,
    var jitter: Double = 0.0,
    var latestY: Double = 0.0,
    var mismatch: String? = null,
)

object Guided {
    /** settledAverage's result (a Swift tuple (xyz, count, wobble)). */
    data class SettledAverage(val xyz: Vec3, val count: Int, val wobble: Double)

    fun liveReading(patch: Patch, xyz: Vec3, hdr: Boolean, whiteY: Double?, basis: List<Vec3>?): LiveReading {
        val p = patch
        val xy = ColourMath.xyzToXY(xyz)
        val out = LiveReading(
            id = p.id, name = p.name, kind = p.kind, Y = xyz.y, xy = xy, uv = ColourMath.upvp(xy),
            d65uv = ColourMath.upvp(ColourMath.d65),
        )
        if (p.kind == PatchKind.WHITE || p.kind == PatchKind.GREY) {
            val rgb = ColourMath.xyzToRGB709(xyz)
            val g = if (rgb.y > 0) rgb.y else 1e-9
            val qR = rgb.x / g - 1
            val qB = rgb.z / g - 1
            out.qR = qR
            out.qB = qB
            out.de = ColourMath.whiteDE(xyz)
            out.ok = abs(qR) <= 0.012 && abs(qB) <= 0.012
            return out
        }
        val targets = Patterns.colourTargets(hdr)
        val tg = targets[p.id] ?: return out
        val lvl = Analysis.colourLevel(hdr)
        // (whiteY ?? 0) != 0: IEEE comparison (a NaN white passes, like Swift)
        val wY: Double? = if ((whiteY ?: 0.0) != 0.0) whiteY else (if (hdr) 100.0 else null)
        val m = ColourMath.polar(xy)
        val t = ColourMath.polar(tg.xy)
        val hue = ColourMath.wrap(m.angle - t.angle)
        val sat = (m.r / t.r - 1) * 100
        out.tuv = ColourMath.upvp(tg.xy)
        out.hue = hue
        out.sat = sat
        if (wY != null) {
            val expY = wY * tg.Yrel * lvl
            val Wn = ColourMath.xyYToXYZ(ColourMath.d65, wY)
            out.lum = (xyz.y / expY - 1) * 100
            out.de = ColourMath.de2000(
                ColourMath.xyzToLab(xyz, Wn),
                ColourMath.xyzToLab(ColourMath.xyYToXYZ(tg.xy, expY), Wn),
            )
        }
        val i = Patterns.hueOrder.indexOf(p.id)
        if (i >= 0) {
            for (cand in listOf(Patterns.hueOrder[(i + 1) % 6], Patterns.hueOrder[(i + 5) % 6])) {
                val c = targets[cand]
                if (c != null && (ColourMath.wrap(ColourMath.polar(c.xy).angle - t.angle) > 0) == (hue > 0)) {
                    out.toward = cand
                }
            }
        }
        val de = out.de
        if (de != null) {
            out.ok = de <= 2.0
        } else {
            out.ok = abs(hue) < 2 && abs(sat) < 3
        }
        // Swift `var basis = basis`, replaced by the standard primaries when none were measured
        val bs: List<Vec3>? = if (wY != null && (basis == null || basis.isEmpty())) {
            // no measured primaries: use the standard ones - the direction of each slider hint is
            // still right, only the size is approximate
            out.rgbApprox = true
            listOf("RED", "GREEN", "BLUE").map { ColourMath.xyYToXYZ(targets[it]!!.xy, wY * targets[it]!!.Yrel * lvl) }
        } else {
            basis
        }
        if (bs != null && bs.size == 3 && wY != null) {
            val M: Mat3 = List(3) { r -> List(3) { c -> bs[c][r] } }
            val targetXYZ = ColourMath.xyYToXYZ(tg.xy, wY * tg.Yrel * lvl)
            val cur = ColourMath.solve3(M, xyz)
            val tgt = ColourMath.solve3(M, targetXYZ)
            val peak = sMax(sMax(tgt.x, tgt.y), tgt.z)
            // each change as a share of the colour's main slider, so a tiny trace of green on red
            // doesn't outrank red being 25% too bright
            val hint = (tgt - cur) / sMax(peak, 1e-6)
            if (hint.x.isFinite() && hint.y.isFinite() && hint.z.isFinite()) {
                out.rgbHint = hint
                // A slider can't go below zero. If the target needs a negative amount of a primary, it
                // lies outside what this TV's red, green and blue can mix: say so, and how close it can get.
                val short = (0 until 3).filter { tgt[it] < -0.02 * peak }
                if (short.isNotEmpty()) {
                    val reach = Vec3(sMax(0.0, tgt.x), sMax(0.0, tgt.y), sMax(0.0, tgt.z))
                    val XYZr = ColourMath.matvec(M, reach)
                    val Wn = ColourMath.xyYToXYZ(ColourMath.d65, wY)
                    val best = ColourMath.de2000(ColourMath.xyzToLab(XYZr, Wn), ColourMath.xyzToLab(targetXYZ, Wn))
                    out.outOfReach = OutOfReach(
                        channels = short.map { listOf("R", "G", "B")[it] },
                        bestDE = pyRound(best, 1),
                    )
                }
            }
        }
        return out
    }

    /**
     * Averages the readings since the last real change on the TV.
     *
     * Some TVs (Samsung Micro RGB, many mini-LEDs) wobble from reading to reading. Averaging hides the
     * wobble, but a long average would also hide the effect of a click on the remote. So: judge the
     * normal wobble from reading-to-reading steps, find the point where the average clearly shifted (a
     * setting change) and average only the readings after it.
     * Returns the XYZ, how many readings were averaged, and the wobble as a fraction.
     */
    fun settledAverage(rs: List<Vec3>): SettledAverage {
        val n = rs.size
        if (n <= 0) return SettledAverage(Vec3(0.0, 0.0, 0.0), 0, 0.0)
        val cs: List<Pair<Double, Double>> = rs.map { Pair(it.x / sMax(it.y, 1e-6), it.z / sMax(it.y, 1e-6)) }
        fun comp(c: Pair<Double, Double>, k: Int): Double = if (k == 0) c.first else c.second
        var sig: List<Double> = emptyList()
        for (k in 0 until 2) {
            val d = cs.zipWithNext { a, b -> abs(comp(a, k) - comp(b, k)) }.sorted()
            var scale = seqMax(cs.map { abs(comp(it, k)) }) ?: 0.0
            if (scale == 0.0) scale = 1.0
            sig = sig + sMax(sMax(if (d.isEmpty()) 0.0 else d[d.size / 2] / 0.954, 0.0015 * scale), 1e-6)
        }
        var start = 0
        var best = 0.0
        for (m in 2 until n - 1) {          // last m readings vs the ones before them
            val old = cs.subList(0, n - m)
            val new = cs.subList(n - m, n)
            var t = Double.NEGATIVE_INFINITY
            for (k in 0 until 2) {
                val meanNew = total(new.map { comp(it, k) }) / m.toDouble()
                val meanOld = total(old.map { comp(it, k) }) / old.size.toDouble()
                t = sMax(t, abs(meanNew - meanOld) / (sig[k] * sqrt(1 / m.toDouble() + 1 / old.size.toDouble())))
            }
            if (t > 4.0 && t > best) {
                best = t
                start = n - m
            }
        }
        if (n - start == 1) {
            start = n - 1
        } else if (n >= 2 && start == 0) {
            // a big jump on the very newest reading: show it straight away
            val jump = seqMax((0 until 2).map { abs(comp(cs[n - 1], it) - comp(cs[n - 2], it)) / (sig[it] * 1.414) }) ?: 0.0
            if (jump > 6) start = n - 1
        }
        val tail = (maxOf(start, n - 10) until n).toList()
        val m = tail.size.toDouble()
        val cx = total(tail.map { cs[it].first }) / m
        val cz = total(tail.map { cs[it].second }) / m
        val Y = total(tail.map { rs[it].y }) / m
        val wobble = seqMax((0 until 2).map { sig[it] / sMax(abs(comp(cs[n - 1], it)), 0.05) }) ?: 0.0
        return SettledAverage(Vec3(cx * Y, Y, cz * Y), tail.size, pyRound(wobble, 4))
    }

    /** How many averaged readings it takes to call a reading steady, for how jumpy this TV is. */
    fun readingsNeeded(jitter: Double): Int =
        if (jitter < 0.004) 3 else if (jitter < 0.01) 5 else 8

    /** Does the reading look like the pattern we asked for? Catches the wrong file playing. */
    fun mismatch(patch: Patch, xyz: Vec3, hdr: Boolean, whiteY: Double?): String? {
        val p = patch
        if (xyz.y <= 0) return "The camera sees black."
        val r = ColourMath.polar(ColourMath.xyzToXY(xyz)).r
        val neutral = p.kind == PatchKind.WHITE || p.kind == PatchKind.GREY
        if (neutral && r > 0.05) return "The camera is seeing a colour, not a grey."
        if (p.kind == PatchKind.COLOUR && r < 0.03) return "The camera is seeing a grey, not a colour."
        val wY = whiteY
        val level = p.level
        if (wY != null && wY != 0.0 && neutral && level != null) {
            val exp = if (hdr) ColourMath.pqDecode(level) * wY / 100.0 else wY * level.pow(2.4)
            if (exp > 0 && !(0.4 < xyz.y / exp && xyz.y / exp < 2.5)) {
                return "The brightness doesn't match this pattern."
            }
        }
        return null
    }

    /** Everything the live view needs from the latest readings of one pattern. */
    fun evaluate(patch: Patch, readings: List<Vec3>, hdr: Boolean, whiteY: Double?, basis: List<Vec3>?): LiveReading? {
        val latest = readings.lastOrNull() ?: return null
        val avg = settledAverage(readings)
        val out = liveReading(patch, avg.xyz, hdr, whiteY, basis)
        out.averaged = avg.count
        out.jitter = avg.wobble
        out.need = readingsNeeded(avg.wobble)
        out.steady = out.ok && avg.count >= out.need
        out.latestY = latest.y
        out.mismatch = mismatch(patch, avg.xyz, hdr, whiteY)
        if (out.mismatch != null) {
            out.ok = false
            out.steady = false
        }
        return out
    }

    /** Swift's `reduce(0, +)`: left to right from 0. */
    private fun total(xs: List<Double>): Double {
        var s = 0.0
        for (x in xs) s += x
        return s
    }

    /** Swift's two-argument `max(x, y)`: `y >= x ? y : x` (a NaN first argument stays, a tie gives y). */
    private fun sMax(x: Double, y: Double): Double = if (y >= x) y else x

    /** Swift's `Sequence.max()` on Doubles: keeps the first element, replaced only when `result < e`. */
    private fun seqMax(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        var result = xs[0]
        for (j in 1 until xs.size) if (result < xs[j]) result = xs[j]
        return result
    }
}

// MARK: - The free-running live meter

/** A builder like the Swift struct: `var` fields of immutable types. */
data class LiveAnalysis(
    var kind: Kind,
    var Y: Double,
    var xy: Point2,
    var uv: Point2,
    var d65uv: Point2,
    // white
    var bal: Vec3? = null,
    var de: Double? = null,
    var advice: String? = null,
    // colour
    var name: String? = null,
    var sat: Double? = null,
    var hue: Double? = null,
    var tuv: Point2? = null,
) {
    enum class Kind { WHITE, COLOUR }

    companion object {
        /** Reads whatever is on screen: a white (balance + advice) or the nearest target colour. */
        fun of(xyz: Vec3, hdr: Boolean): LiveAnalysis {
            val xy = ColourMath.xyzToXY(xyz)
            val pol = ColourMath.polar(xy)
            val uv = ColourMath.upvp(xy)
            val out = LiveAnalysis(kind = Kind.WHITE, Y = xyz.y, xy = xy, uv = uv, d65uv = ColourMath.upvp(ColourMath.d65))
            if (pol.r < 0.03) {
                val rgb = ColourMath.xyzToRGB709(xyz)
                val bal = if (xyz.y > 0) rgb / xyz.y else Vec3(1.0, 1.0, 1.0)
                out.bal = bal
                out.de = ColourMath.whiteDE(xyz)
                val vals = listOf(bal.x, bal.y, bal.z)
                // Swift indices.max(by:) / min(by:): the first index wins a tie
                var mx = 0
                for (j in 1 until vals.size) if (vals[mx] < vals[j]) mx = j
                var mn = 0
                for (j in 1 until vals.size) if (vals[j] < vals[mn]) mn = j
                val names = listOf("Red", "Green", "Blue")
                out.advice = if (vals[mx] - vals[mn] <= 0.01) {
                    "White is on target."
                } else {
                    "${names[mx]} is high and ${names[mn]} is low: lower ${names[mx]} or raise ${names[mn]}."
                }
            } else {
                val tg = Patterns.colourTargets(hdr)
                fun dist(id: String): Double {
                    val u = ColourMath.upvp(tg[id]!!.xy)
                    return hypot(uv.x - u.x, uv.y - u.y)
                }
                // Swift colourIDs.min(by:): replaced only when strictly nearer, so the first wins a tie
                var name = Patterns.colourIDs.firstOrNull() ?: "RED"
                for (id in Patterns.colourIDs.drop(1)) if (dist(id) < dist(name)) name = id
                val t = ColourMath.polar(tg[name]!!.xy)
                out.kind = Kind.COLOUR
                out.name = name
                out.sat = (pol.r / t.r - 1) * 100
                out.hue = ColourMath.wrap(pol.angle - t.angle)
                out.tuv = ColourMath.upvp(tg[name]!!.xy)
            }
            return out
        }
    }
}
