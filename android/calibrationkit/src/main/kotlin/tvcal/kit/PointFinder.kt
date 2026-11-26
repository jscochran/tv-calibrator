package tvcal.kit

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.sqrt

// Point finder: TVs label their multipoint (10/20-point) controls on their own scale, which often
// doesn't match the video's grey levels (an LG C5 in HDR: the 30% control moves the 40% grey).
// The finder measures a ladder of finely spaced greys, the user nudges four controls by +10, it
// measures again, and the bumps in the difference show where each control really acts.

/** Where one TV control acts, as a grey level of the video (its own % scale). */
@Serializable
data class FinderPoint(
    /** The TV control's own label, e.g. 40 for the "40%" control. */
    val point: Int,
    /** "R", "B", or "?" for a point the user set by hand. */
    val channel: String,
    val level: Double,
    /** Size of the bump, %. */
    val size: Double,
)

object PointFinder {
    const val NUDGE = 10

    /** One nudged control (a Swift labelled tuple (channel, point)). */
    data class Nudge(val channel: String, val point: Int)

    /** Far apart, alternating channels. */
    val nudges: List<Nudge> = listOf(Nudge("R", 20), Nudge("B", 40), Nudge("R", 60), Nudge("B", 80))

    /** The menu's step: 5 for 20-point menus, 10 for 10/11-point menus. */
    fun grid(points: Int): Int = if (points == 20 || points == 0) 5 else 10

    internal data class QRow(val level: Double, val r: Double, val b: Double, val Y: Double)

    internal fun q(meas: Measurements, hdr: Boolean): List<QRow> =
        Patterns.finderSequence(hdr).patches.mapNotNull { p ->
            if (p.kind != PatchKind.GREY) return@mapNotNull null
            val XYZ = meas[p.id] ?: return@mapNotNull null
            if (!(XYZ.y > 0.0)) return@mapNotNull null
            val level = p.level ?: return@mapNotNull null
            val rgb = ColourMath.xyzToRGB709(XYZ)
            if (!(rgb.y > 0.0)) return@mapNotNull null
            QRow(level = level * 100, r = rgb.x / rgb.y, b = rgb.z / rgb.y, Y = XYZ.y)
        }

    private data class Row(val level: Double, val dR: Double, val dB: Double, val Y: Double, val baseY: Double)

    private data class Peak(val level: Double, val size: Double, val score: Double, val ch: String, val lean: Double)

    /** Swift `Array<Double>.max()`: keeps the running max unless a later element compares greater. */
    private fun swiftArrayMax(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        var result = xs[0]
        for (e in xs) if (result < e) result = e
        return result
    }

    /** Swift tuple `<` / Double `<` as a sort comparator (equal when neither is less). */
    private fun lessCompare(a: Double, b: Double): Int = if (a < b) -1 else if (b < a) 1 else 0

    /**
     * Where each nudged control acts.
     *
     * The two passes are minutes apart and some TVs drift in that time, which shifts every grey the
     * same way: each channel's change is measured against its own typical (median) change, and only
     * bumps that stand out from the noise count. A higher control always acts on a brighter grey,
     * which settles which bump belongs to which control.
     */
    fun map(base: Measurements, nudged: Measurements, hdr: Boolean): List<FinderPoint> {
        val a = q(base, hdr)
        val b = q(nudged, hdr)
        val bl = HashMap<Double, QRow>()
        for (r in a) bl[pyRound(r.level, 2)] = r
        var rows: List<Row> = b.mapNotNull { r ->
            val o = bl[pyRound(r.level, 2)] ?: return@mapNotNull null
            Row(level = r.level, dR = r.r / o.r - 1, dB = r.b / o.b - 1, Y = r.Y, baseY = o.Y)
        }
        // the TV can't get brighter than its peak: greys above it all look the same, so drop them
        val top = swiftArrayMax(rows.map { it.baseY }) ?: 0.0
        rows = rows.filter { it.baseY < 0.97 * top }
        if (rows.size < 6) return emptyList()
        val lv = rows.map { it.level }
        // each channel against its own typical change (drift), then the two added: a nudge shows up as a
        // bump at its grey whichever channel moved
        val dd = ArrayList<List<Double>>()
        for (ch in 0 until 2) {
            val d = rows.map { if (ch == 0) it.dR else it.dB }
            val med = d.sorted()[d.size / 2]
            dd.add(d.map { it - med })
        }
        val d = dd[0].zip(dd[1]).map { (x, y) -> swiftMax(0.0, x) + swiftMax(0.0, y) }
        // typical noise: the smaller of the two channel changes at each grey, x2 for the two channels added
        var mad = 2 * 1.48 * dd[0].zip(dd[1]).map { (x, y) -> swiftMin(abs(x), abs(y)) }.sorted()[d.size / 2]
        if (mad == 0.0) mad = 1e-4
        val need = rows.map { swiftMax(0.012, 4 * mad * sqrt(1 + 4.0 / swiftMax(it.baseY, 0.5))) }   // dark greys: noisier

        val peaks = ArrayList<Peak>()
        if (d.size >= 3) {
            for (i in 1 until d.size - 1) {
                if (!(d[i] >= need[i] && d[i] >= d[i - 1] && d[i] >= d[i + 1])) continue
                // a bump on the first or last measured grey may sit beyond it: can't place it, so skipped
                var L = lv[i]
                val den = d[i - 1] - 2 * d[i] + d[i + 1]
                if (den < 0) {
                    L += clamp(0.5 * (d[i - 1] - d[i + 1]) / den, -0.5, 0.5) * (lv[i + 1] - lv[i])
                }
                val r = swiftMax(0.0, dd[0][i])
                val bb = swiftMax(0.0, dd[1][i])
                val sum = r + bb
                peaks.add(Peak(level = pyRound(L, 1), size = pyRound(d[i] * 100, 1), score = swiftMin(d[i] / need[i], 4.0),
                               ch = if (r >= bb) "R" else "B", lean = (r - bb) / (if (sum != 0.0) sum else 1.0)))   // +1 red only, -1 blue only
            }
        }
        // bumps go to controls in order (a brighter control acts on a brighter grey); among the
        // orderings, prefer more bumps explained, then the smaller shift from the labels
        val ctrls = nudges.sortedBy { it.point }
        var bestScore = -1e9
        var bestPairs: List<FinderPoint> = emptyList()
        val acc = ArrayList<FinderPoint>()
        fun walk(k: Int, pi: Int, score: Double) {
            if (k == ctrls.size) {
                if (score > bestScore) {
                    bestScore = score
                    bestPairs = acc.toList()   // Swift array assignment copies
                }
                return
            }
            val C = ctrls[k].channel
            val P = ctrls[k].point
            walk(k + 1, pi, score)                                     // no bump for this control
            if (pi < peaks.size) {
                for (qi in pi until peaks.size) {
                    val pk = peaks[qi]
                    acc.add(FinderPoint(point = P, channel = pk.ch, level = pk.level, size = pk.size))
                    // which channel moved: a clear red-only bump belongs to a red nudge
                    val match = pk.lean * (if (C == "R") 1.0 else -1.0)
                    walk(k + 1, qi + 1, score + 10 + pk.score - abs(pk.level - P.toDouble()) / 10 + 3 * match)
                    acc.removeAt(acc.size - 1)
                }
            }
        }
        walk(0, 0, 0.0)
        return bestPairs
    }

    private data class LP(val l: Double, val p: Double)

    /** Which TV control to use for a grey at level L (the video's %), from the measured points. */
    fun pointForLevel(pairs: List<FinderPoint>, level: Double, grid: Int): Int? {
        val L = level
        val pts = pairs.map { LP(l = it.level, p = it.point.toDouble()) }
            .sortedWith(Comparator { x, y -> lessCompare(x.l, y.l).let { c -> if (c != 0) c else lessCompare(x.p, y.p) } })
        val first = pts.firstOrNull() ?: return null
        var P: Double
        if (pts.size == 1) {
            P = first.p + (L - first.l)
        } else {
            P = first.p
            for (j in 0 until pts.size - 1) {
                val (l0, p0) = pts[j]
                val (l1, p1) = pts[j + 1]
                if (L <= l1 || j + 1 == pts.size - 1) {
                    P = p0 + (p1 - p0) * (L - l0) / (if ((l1 - l0) != 0.0) (l1 - l0) else 1.0)
                    break
                }
            }
            if (L < first.l) {
                val (l0, p0) = pts[0]
                val (l1, p1) = pts[1]
                P = p0 + (p1 - p0) * (L - l0) / (if ((l1 - l0) != 0.0) (l1 - l0) else 1.0)
            }
        }
        val g = grid.toDouble()
        if (!P.isFinite()) return null
        return clamp(g * pyRound(P / g), g, 100.0).toInt()
    }

    /**
     * For each grey of the pattern pack: the TV control to use. Controls the user found by hand
     * count as measured points too, and override that grey outright.
     */
    fun suggestions(map: List<FinderPoint>, overrides: Map<String, Int>, hdr: Boolean, grid: Int): Map<String, Int> {
        // Dictionary(uniquingKeysWith: { x, _ in x }): the first patch with an id wins
        val levels = LinkedHashMap<String, Double>()
        for (p in Patterns.sequence(hdr).patches) {
            if (p.kind == PatchKind.GREY || p.kind == PatchKind.WHITE) {
                if (!levels.containsKey(p.id)) levels[p.id] = p.percent
            }
        }
        if (map.isEmpty()) return overrides.toMap()
        val mine: List<FinderPoint> = overrides.keys.sorted().mapNotNull { pid ->
            val L = levels[pid] ?: return@mapNotNull null
            val P = overrides[pid] ?: return@mapNotNull null
            FinderPoint(point = P, channel = "?", level = L, size = 0.0)
        }
        // the user's findings win: drop measured points that disagree with them
        fun agrees(q: FinderPoint): Boolean =
            mine.all { m ->
                q.point != m.point && abs(q.level - m.level) > 4 && (q.point - m.point).toDouble() * (q.level - m.level) > 0.0
            }
        val pairs = (map.filter { agrees(it) } + mine).sortedWith(Comparator { x, y -> lessCompare(x.level, y.level) })
        val out = LinkedHashMap<String, Int>()
        for ((pid, L) in levels) {
            // Swift `out[pid] = nil` leaves the key out
            val v = pointForLevel(pairs, level = L, grid = grid)
            if (v != null) out[pid] = v else out.remove(pid)
        }
        for ((pid, P) in overrides) {
            if (levels[pid] != null) out[pid] = P
        }
        return out
    }
}
