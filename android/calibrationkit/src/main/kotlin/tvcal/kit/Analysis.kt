package tvcal.kit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** One grey step of a round. */
@Serializable
data class GreyResult(
    val id: String,
    val level: Double,
    val name: String,
    val xyz: Vec3,
    val Y: Double,
    val targetY: Double,
    /** R, G, B relative to brightness: (1, 1, 1) is exactly on D65. */
    val bal: Vec3,
    val rgb: Vec3,
    /** White-point error (dE2000, brightness ignored). */
    val de: Double,
    /** HDR: above what this TV can reach, so left out of corrections. */
    val clipped: Boolean,
) {
    /** Red and blue relative to green, minus 1 (0 = on target). What the white-balance bars show. */
    val redVsGreen: Double get() = if (bal.y > 0) bal.x / bal.y - 1 else 0.0
    val blueVsGreen: Double get() = if (bal.y > 0) bal.z / bal.y - 1 else 0.0
}

/** One colour of a round. */
@Serializable
data class ColourResult(
    val name: String,
    val xyz: Vec3,
    val target: Vec3,
    val xy: Point2,
    val txy: Point2,
    /** Saturation error, %. */
    val sat: Double,
    /** Hue error, degrees (u'v' angle around D65). */
    val hue: Double,
    /** Brightness error, %. */
    val lum: Double,
    val de: Double,
) {
    val id: String get() = name

    fun value(axis: HSLAxis): Double = when (axis) {
        HSLAxis.HUE -> hue
        HSLAxis.SAT -> sat
        HSLAxis.LUM -> lum
    }
}

/** Swift `enum HSLAxis: String, CaseIterable` (allCases = entries, in this order). */
@Serializable
enum class HSLAxis(val rawValue: String) {
    @SerialName("hue") HUE("hue"),
    @SerialName("sat") SAT("sat"),
    @SerialName("lum") LUM("lum");

    companion object {
        fun from(raw: String): HSLAxis? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** The analysis of one set of measurements. Immutable: analyse() builds it from local lists. */
@Serializable
data class Report(
    val greys: List<GreyResult> = emptyList(),
    val colours: List<ColourResult> = emptyList(),
    val white: Vec3? = null,
    val warnings: List<String> = emptyList(),
    /** Brightness change of white from the start of the run to the end, %. */
    val drift: Double? = null,
    /** HDR: the TV's highest measured brightness, when some greys are above it. */
    val peak: Double? = null,
)

object Analysis {
    /** The SDR colour patches are 75% signal; their target brightness is relative to white. */
    internal fun colourLevel(hdr: Boolean): Double = if (hdr) 1.0 else 0.75.pow(2.4)

    fun analyse(meas: Measurements, hdr: Boolean): Report {
        val seq = Patterns.sequence(hdr)
        val warnings = mutableListOf<String>()
        val W = meas["W0"]
        if (W == null) {
            warnings.add("No 100% white reading - can't analyse this round.")
            return Report(warnings = warnings.toList())
        }
        var drift: Double? = null
        val Yw = W.y
        val W1 = meas["W1"]
        if (W1 != null) {
            val d = (W1.y / Yw - 1) * 100
            drift = d
            if (abs(d) > 3) {
                warnings.add("Brightness drifted ${cfmt(d, 1, plus = true)}% during the run (TV not warmed up, " +
                    "or auto-brightness/energy saving still on).")
            }
        }
        // missed patches, in the video's order
        for (p in seq.patches) {
            val m = meas.missed[p.id] ?: continue
            val took = swiftMaxOf(m.readings) ?: 0.0
            warnings.add("${m.name}: no reading. " + (if (took != 0.0)
                "The camera's readings took up to ${cfmt(took, 1)}s on it, against ${gfmt(m.hold)}s on screen."
            else "The camera didn't return anything while it was on screen."))
        }
        val greys = mutableListOf<GreyResult>()
        var prevY = 0.0
        for (p in seq.patches) {
            if (p.kind != PatchKind.GREY) continue
            val XYZ = meas[p.id] ?: continue
            val level = p.level ?: continue
            val rgb = ColourMath.xyzToRGB709(XYZ)
            val Y = XYZ.y
            if (Y < prevY * 0.95 && !hdr) {
                warnings.add("${p.name} is darker than the step before it - timing may be off, " +
                    "or the TV is clipping/tone-mapping.")
            }
            prevY = Y
            val tgt = if (hdr) ColourMath.pqDecode(level) else Yw * level.pow(2.4)
            val clipped = hdr && tgt > 200 && Y < 0.85 * tgt
            greys.add(GreyResult(id = p.id, level = level, name = p.name, xyz = XYZ, Y = Y, targetY = tgt,
                bal = rgb / Y, rgb = rgb, de = ColourMath.whiteDE(XYZ), clipped = clipped))
        }
        var peak: Double? = null
        val clip = greys.filter { it.clipped }
        if (clip.isNotEmpty()) {
            val pk = swiftMaxOf(greys.map { it.Y }) ?: 0.0
            peak = pk
            val names = clip.joinToString(", ") { it.name.split(" (")[0] }
            warnings.add("The TV tops out around ${cfmt(pk, 0)} cd/m² - steps above that " +
                "($names) are left out of the corrections.")
        }
        val tg = Patterns.colourTargets(hdr)
        // HDR: judge colours against the TV's own 100-nit white, so an overall HDR brightness
        // boost (reported separately as PQ tracking) doesn't swamp the colour scores.
        val whiteY = if (hdr) (if (Yw > 0) Yw else 100.0) else Yw
        val lvl = colourLevel(hdr)
        val Wn = ColourMath.xyYToXYZ(ColourMath.d65, whiteY)
        val colours = mutableListOf<ColourResult>()
        for ((n, _) in Patterns.colours) {
            val XYZ = meas[n] ?: continue
            val t = tg[n] ?: continue
            val expY = whiteY * t.Yrel * lvl
            val T = ColourMath.xyYToXYZ(t.xy, expY)
            val xy = ColourMath.xyzToXY(XYZ)
            val m = ColourMath.polar(xy)
            val tp = ColourMath.polar(t.xy)
            colours.add(ColourResult(
                name = n, xyz = XYZ, target = T, xy = xy, txy = t.xy,
                sat = (m.r / tp.r - 1) * 100, hue = ColourMath.wrap(m.angle - tp.angle), lum = (XYZ.y / expY - 1) * 100,
                de = ColourMath.de2000(ColourMath.xyzToLab(XYZ, white = Wn), ColourMath.xyzToLab(T, white = Wn))))
        }
        return Report(greys = greys.toList(), colours = colours.toList(), white = W, warnings = warnings.toList(),
            drift = drift, peak = peak)
    }

    /** Swift `[Double].max()`: the running value is replaced only by a greater one (Kotlin's maxOrNull returns NaN if any is NaN). */
    private fun swiftMaxOf(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        var r = xs[0]
        for (i in 1 until xs.size) r = swiftMax(r, xs[i])
        return r
    }

    /** Swift `reduce(0, +)`: a plain left-to-right sum. */
    private fun sumOf(xs: List<Double>): Double = xs.fold(0.0) { a, b -> a + b }

    // summaries

    internal fun usableGreys(rep: Report, hdr: Boolean): List<GreyResult> =
        rep.greys.filter { (hdr || it.level >= 0.099) && !it.clipped }

    /** Average greyscale dE (SDR: 10% and up). NaN if nothing to average. */
    fun greyAverage(rep: Report, hdr: Boolean): Double {
        val g = usableGreys(rep, hdr).map { it.de }
        return if (g.isEmpty()) Double.NaN else sumOf(g) / g.size.toDouble()
    }

    /** NaN if nothing to take the max of. */
    fun greyMax(rep: Report, hdr: Boolean): Double =
        swiftMaxOf(usableGreys(rep, hdr).map { it.de }) ?: Double.NaN

    /** 0 if there are no colours. */
    fun colourAverage(rep: Report): Double {
        val c = rep.colours.map { it.de }
        return if (c.isEmpty()) 0.0 else sumOf(c) / c.size.toDouble()
    }

    /** SDR: average gamma between 10% and 90%. */
    fun gamma(rep: Report): Double? {
        val W = rep.white ?: return null
        val gs = rep.greys.filter { it.level >= 0.1 && it.level <= 0.9 && it.Y > 0 }
            .map { ln(it.Y / W.y) / ln(it.level) }
        return if (gs.isEmpty()) null else sumOf(gs) / gs.size.toDouble()
    }

    /** HDR: how far brightness is from the PQ target below 300 nits, % on average. */
    fun pqTracking(rep: Report): Double? {
        val trk = rep.greys.filter { it.targetY < 300 && it.targetY > 0 }.map { it.Y / it.targetY - 1 }
        return if (trk.isEmpty()) null else 100 * sumOf(trk) / trk.size.toDouble()
    }

    /** "Excellent", "Very good"... for a dE. "" for null or NaN. */
    fun scoreWord(de: Double?): String {
        if (de == null || de.isNaN()) return ""
        return if (de < 1) "Excellent" else if (de < 2) "Very good" else if (de < 3) "Good"
        else if (de < 5) "Noticeably off" else "Clearly off"
    }

    /** Which neighbouring colour a hue error leans toward. */
    fun hueToward(name: String, hue: Double, hdr: Boolean): String? {
        if (!(abs(hue) >= 1)) return null
        val i = Patterns.hueOrder.indexOf(name)
        if (i < 0) return null
        val tg = Patterns.colourTargets(hdr)
        val me = tg[name] ?: return null
        val at = ColourMath.polar(me.xy).angle
        var toward: String? = null
        for (cand in listOf(Patterns.hueOrder[(i + 1) % 6], Patterns.hueOrder[(i + 5) % 6])) {
            val c = tg[cand] ?: continue
            if ((ColourMath.wrap(ColourMath.polar(c.xy).angle - at) > 0) == (hue > 0)) {
                toward = cand
            }
        }
        return toward
    }
}
