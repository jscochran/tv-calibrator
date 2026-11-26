package tvcal.kit

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

// Automatic rounds: measure the whole video, then prescribe exact menu changes.
//
// Green is the anchor: only Red and Blue move, measured RELATIVE to green (R/G and B/G). That keeps
// working even if the TV quietly rescales overall brightness (power limiting, peak boost).
//
// Learning takes two short rounds:
//   after round 1:  Red and Blue GAIN  -10   (down, so the TV can't be at a ceiling)
//   after round 2:  gains back, Red and Blue OFFSET +10
// From round 3 on: corrections, capped per control per round, and the model rescales itself every
// round from what actually happened.
//
// Change keys are strings shared with the Mac app's history files:
//   "gain:R", "offset:B", "p20:R:40" (multipoint), "cms:YELLOW:B" (R/G/B sliders), "hsl:RED:hue".

// MARK: - Types

/** Which colour menu the TV has. */
@Serializable
enum class ColourMenu(val rawValue: String) {
    /** Red, Green and Blue sliders for each colour (Samsung's Color Space > Custom). */
    @SerialName("samsung") RGB_SLIDERS("samsung"),
    /** Hue, Saturation and Brightness per colour (LG, Sony, TCL...). */
    @SerialName("hsl") HSL("hsl"),
    @SerialName("off") OFF("off");

    companion object {
        fun from(raw: String): ColourMenu? = entries.firstOrNull { it.rawValue == raw }
    }
}

@Serializable
enum class RoundKind(val rawValue: String) {
    @SerialName("probe_gain") PROBE_GAIN("probe_gain"),
    @SerialName("probe_offset") PROBE_OFFSET("probe_offset"),
    @SerialName("correct") CORRECT("correct"),
    @SerialName("undo") UNDO("undo");

    companion object {
        fun from(raw: String): RoundKind? = entries.firstOrNull { it.rawValue == raw }
    }
}

@Serializable
enum class Stage(val rawValue: String) {
    @SerialName("2pt") TWO_POINT("2pt"),
    @SerialName("20pt") MULTI_POINT("20pt");

    companion object {
        fun from(raw: String): Stage? = entries.firstOrNull { it.rawValue == raw }
    }
}

@Serializable
enum class Banner(val rawValue: String) {
    @SerialName("learn") LEARN("learn"),
    @SerialName("undo") UNDO("undo"),
    @SerialName("correct") CORRECT("correct");

    companion object {
        fun from(raw: String): Banner? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** What was used to measure: rounds are only comparable with the same source, meter and phone. */
@Serializable
data class RoundSetup(
    val source: String = "video",
    val meter: String = "iphone",
    val phone: String? = null,
)

/** One measured round. Immutable: change one in a list by replacing it with copy(...). */
@Serializable
data class AutoRound(
    val time: String,
    val meas: Measurements,
    /** The changes the user confirmed they made after this round (null: not recorded). */
    val applied: Map<String, Int>? = null,
    val kind: RoundKind? = null,
    val setup: RoundSetup? = null,
    val stage: Stage? = null,
)

/**
 * What the engine has learned about how this TV's controls respond.
 * Immutable: update with copy(...). Decoding treats a missing OR null key as the default (Swift decodeIfPresent).
 */
@Serializable(with = SensitivitySerializer::class)
data class Sensitivity(
    /** % change of R/G (or B/G) per Gain click, per grey id. */
    val relG: Map<String, Map<String, Double>>? = null,
    /** Same for Offset. */
    val relO: Map<String, Map<String, Double>>? = null,
    val goScale: Map<String, Double>? = null,
    /** Multipoint: % change per click on its own grey. */
    val rel20: Map<String, Double>? = null,
    val kScale: Double? = null,
    /** R/G/B colour sliders: change in a colour's primary amount per click. */
    val k: Double? = null,
    val learned: Boolean = false,
    /** "R:40" -> [total clicks, total response]: spots multipoint controls that act elsewhere. */
    val p20Track: Map<String, List<Double>> = emptyMap(),
    val p20Dead: List<String> = emptyList(),
    val p20Learned: Boolean = false,
    /** Hue/sat/lum colour menus: degrees or % per click (sign learned too). */
    val hslK: Map<String, Double>? = null,
    val hslLearned: List<String> = emptyList(),
)

@Serializable
private class SensitivitySurrogate(
    @SerialName("rel_g") val relG: Map<String, Map<String, Double>>? = null,
    @SerialName("rel_o") val relO: Map<String, Map<String, Double>>? = null,
    @SerialName("go_scale") val goScale: Map<String, Double>? = null,
    @SerialName("rel20") val rel20: Map<String, Double>? = null,
    @SerialName("k_scale") val kScale: Double? = null,
    @SerialName("k") val k: Double? = null,
    @SerialName("learned") val learned: Boolean? = null,
    @SerialName("p20_track") val p20Track: Map<String, List<Double>>? = null,
    @SerialName("p20_dead") val p20Dead: List<String>? = null,
    @SerialName("p20_learned") val p20Learned: Boolean? = null,
    @SerialName("hsl_k") val hslK: Map<String, Double>? = null,
    @SerialName("hsl_learned") val hslLearned: List<String>? = null,
)

/** Swift CodingKeys (snake_case) and decodeIfPresent defaults. */
object SensitivitySerializer : KSerializer<Sensitivity> {
    override val descriptor: SerialDescriptor = SensitivitySurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Sensitivity) {
        encoder.encodeSerializableValue(
            SensitivitySurrogate.serializer(),
            SensitivitySurrogate(
                value.relG, value.relO, value.goScale, value.rel20, value.kScale, value.k, value.learned,
                value.p20Track, value.p20Dead, value.p20Learned, value.hslK, value.hslLearned,
            ),
        )
    }

    override fun deserialize(decoder: Decoder): Sensitivity {
        val s = decoder.decodeSerializableValue(SensitivitySurrogate.serializer())
        return Sensitivity(
            relG = s.relG, relO = s.relO, goScale = s.goScale, rel20 = s.rel20, kScale = s.kScale, k = s.k,
            learned = s.learned ?: false, p20Track = s.p20Track ?: emptyMap(), p20Dead = s.p20Dead ?: emptyList(),
            p20Learned = s.p20Learned ?: false, hslK = s.hslK, hslLearned = s.hslLearned ?: emptyList(),
        )
    }
}

/** The changes waiting for the user to make (and confirm) after a round. */
@Serializable
data class PendingPlan(
    val changes: Map<String, Int>,
    val kind: RoundKind? = null,
    val title: String,
    val done: Boolean,
    val message: String,
    val banner: Banner,
    val notes: List<String>,
)

/**
 * One TV's automatic-round history for SDR or HDR.
 *
 * Swift passes it `inout`; here it is the one mutable holder: planRound / recordRound / learn / prescribe
 * mutate the object passed in by REASSIGNING its var fields (new lists/maps, copy() of rounds and sens).
 * Every field holds an immutable value, so st.copy() is a full value copy.
 * Decoding: a missing/null "version" is 1 (an AutoState() made in code gets STATE_VERSION).
 */
@Serializable(with = AutoStateSerializer::class)
data class AutoState(
    var version: Int = AutoCalibration.STATE_VERSION,
    var rounds: List<AutoRound> = emptyList(),
    var sens: Sensitivity = Sensitivity(),
    var notes: List<String> = emptyList(),
    var pending: PendingPlan? = null,
)

@Serializable
private class AutoStateSurrogate(
    val version: Int? = null,
    val rounds: List<AutoRound>? = null,
    val sens: Sensitivity? = null,
    val notes: List<String>? = null,
    val pending: PendingPlan? = null,
)

object AutoStateSerializer : KSerializer<AutoState> {
    override val descriptor: SerialDescriptor = AutoStateSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: AutoState) {
        encoder.encodeSerializableValue(
            AutoStateSurrogate.serializer(),
            AutoStateSurrogate(value.version, value.rounds, value.sens, value.notes, value.pending),
        )
    }

    override fun deserialize(decoder: Decoder): AutoState {
        val s = decoder.decodeSerializableValue(AutoStateSurrogate.serializer())
        return AutoState(
            version = s.version ?: 1,
            rounds = s.rounds ?: emptyList(),
            sens = s.sens ?: Sensitivity(),
            notes = s.notes ?: emptyList(),
            pending = s.pending,
        )
    }
}

/** The result of planning after a round. A builder like the Swift struct: var fields of immutable types. */
data class Plan(
    var report: Report,
    var changes: Map<String, Int> = emptyMap(),
    var kind: RoundKind? = null,
    var title: String = "",
    var done: Boolean = false,
    var message: String = "",
    var banner: Banner = Banner.CORRECT,
    var notes: List<String> = emptyList(),
    /** The round wasn't kept (something other than the calibration controls changed). */
    var discard: Boolean = false,
    var stage: Stage? = null,
) {
    val pending: PendingPlan
        get() = PendingPlan(
            changes = changes, kind = kind, title = title, done = if (discard) false else done, message = message,
            banner = banner, notes = notes,
        )
}

/** Swift `String.capitalized` for the colour names ("RED" -> "Red"): each word's first letter up, the rest down. */
private fun autoCapitalized(s: String): String =
    s.split(" ").joinToString(" ") { w -> w.lowercase(Locale.ROOT).replaceFirstChar { it.uppercaseChar() } }

// MARK: - Engine

object AutoCalibration {
    const val STATE_VERSION = 3
    internal val channels = listOf("R", "B")          // green stays put
    internal const val TWO_MAX = 8                     // max clicks per 2-point control per round
    internal const val P20_MAX = 4                     // max clicks per multipoint control per round (x2 once learned)
    internal const val P20_GUESS = 0.006               // first guess: one multipoint click ~ 0.6% on that step
    internal const val REL_MIN = 0.002                 // sane range for "% change per click" (gain / offset)
    internal const val REL_MAX = 0.04
    internal val hslGuess: Map<String, Double> = mapOf("hue" to 1.0, "sat" to 1.0, "lum" to 1.0)

    /** Click counts from a model value: Python's int(round(x)), but never crashing on NaN/infinity. */
    internal fun clicks(x: Double): Int {
        if (!x.isFinite()) return 0
        return clamp(pyRound(x), -1_000_000.0, 1_000_000.0).toInt()
    }

    fun gainProbe(cms: ColourMenu): Map<String, Int> {
        val ch = linkedMapOf("gain:R" to -10, "gain:B" to -10)
        if (cms == ColourMenu.RGB_SLIDERS) ch["cms:YELLOW:B"] = 10
        return ch.toMap()
    }

    fun offsetProbe(): Map<String, Int> = mapOf("gain:R" to 10, "gain:B" to 10, "offset:R" to 10, "offset:B" to 10)

    internal fun roundKind(r: AutoRound): RoundKind? = if (r.applied?.isEmpty() == false) r.kind else null

    /** Swift `Sequence.max()` for Doubles: keeps the first, replaced only when `result < e` (null when empty). */
    private fun seqMax(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        var result = xs[0]
        for (e in xs) if (result < e) result = e
        return result
    }

    // greyscale ratios

    internal data class QRow(val id: String, val level: Double, val r: Double, val b: Double) {
        fun v(C: String): Double = if (C == "R") r else b
    }

    /** Per usable grey: R/G and B/G, in grey order. */
    internal fun qvals(rep: Report): List<QRow> = rep.greys.mapNotNull { g ->
        if (g.clipped || !(g.bal.y > 0)) null
        else QRow(id = g.id, level = g.level, r = g.bal.x / g.bal.y, b = g.bal.z / g.bal.y)
    }

    /** How much R/G and B/G changed from one report to the next (as a fraction), per grey. */
    internal fun qchange(a: Report, b: Report): List<QRow> {
        // Dictionary(uniquingKeysWith: { x, _ in x }): the first row for an id wins
        val qb = LinkedHashMap<String, QRow>()
        for (row in qvals(b)) qb.putIfAbsent(row.id, row)
        return qvals(a).mapNotNull { qa ->
            val q = qb[qa.id] ?: return@mapNotNull null
            QRow(id = qa.id, level = qa.level, r = q.r / qa.r - 1, b = q.b / qa.b - 1)
        }
    }

    /** smooth()'s input rows (a Swift tuple (id, level, value)). */
    internal data class LevelValue(val id: String, val level: Double, val value: Double)

    /** Median of each value and its neighbours (by level, ties by input order), clamped. */
    internal fun smooth(d: List<LevelValue>, lo: Double, hi: Double): Map<String, Double> {
        // Swift sorts by the tuple (level, offset) with `<` (IEEE comparison, so 0.0 == -0.0)
        val sorted = d.withIndex().sortedWith(Comparator { a, b ->
            when {
                a.value.level < b.value.level -> -1
                b.value.level < a.value.level -> 1
                else -> a.index.compareTo(b.index)
            }
        }).map { it.value }
        val vals = sorted.map { it.value }
        val out = LinkedHashMap<String, Double>()
        for ((i, e) in sorted.withIndex()) {
            val win = vals.subList(maxOf(0, i - 1), minOf(vals.size, i + 2)).sorted()
            out[e.id] = clamp(win[win.size / 2], lo, hi)
        }
        return out.toMap()
    }

    // colours

    /**
     * The measured red, green and blue as matrix columns; a primary that couldn't be measured is
     * replaced by its ideal target (so one missing reading doesn't switch off all colour corrections).
     */
    fun colourBasis(meas: Measurements, hdr: Boolean): Mat3? {
        val W = meas["W0"] ?: return null
        val tg = Patterns.colourTargets(hdr)
        val lvl = Analysis.colourLevel(hdr)
        val P: List<Vec3> = listOf("RED", "GREEN", "BLUE").map { c ->
            meas[c] ?: ColourMath.xyYToXYZ(tg[c]!!.xy, W.y * tg[c]!!.Yrel * lvl)
        }
        return List(3) { r -> List(3) { c -> P[c][r] } }
    }

    /** A colour as amounts of the measured red, green and blue. */
    internal fun colourCoefs(meas: Measurements, name: String, hdr: Boolean): Vec3? {
        val M = colourBasis(meas, hdr) ?: return null
        val C = meas[name] ?: return null
        return ColourMath.solve3(M, C)
    }

    // learning

    /** Swift `learn(_ st: inout AutoState)`: mutates st (st.sens, st.notes). */
    internal fun learn(st: AutoState, hdr: Boolean) {
        val rounds = st.rounds
        if (rounds.size < 2) return
        val prev = rounds[rounds.size - 2]
        val cur = rounds[rounds.size - 1]
        val kind = roundKind(prev) ?: return
        val applied = prev.applied ?: return
        val A = Analysis.analyse(prev.meas, hdr)
        val B = Analysis.analyse(cur.meas, hdr)
        val dq = qchange(A, B)
        var sens = st.sens
        try { // Swift: defer { st.sens = sens }
            when (kind) {
                RoundKind.PROBE_GAIN -> {
                    val relG = LinkedHashMap<String, Map<String, Double>>()
                    for (C in channels) {
                        val g = (applied["gain:$C"] ?: 0).toDouble()
                        val d = dq.map { LevelValue(id = it.id, level = it.level, value = it.v(C) / g) }
                        val top = d.filter { it.level >= 0.7 }.map { it.value }
                        if (top.isNotEmpty() && top.fold(0.0) { s, x -> s + x } / top.size.toDouble() < REL_MIN) {
                            st.notes = st.notes + "$C-Gain barely changed the picture during learning - using a safe default."
                        }
                        relG[C] = smooth(d, lo = REL_MIN * 0.5, hi = REL_MAX)
                    }
                    sens = sens.copy(relG = relG.toMap())
                    val yb = applied["cms:YELLOW:B"]
                    if (yb != null) {
                        val ca = colourCoefs(prev.meas, "YELLOW", hdr)
                        if (ca != null) {
                            val cb = colourCoefs(cur.meas, "YELLOW", hdr)
                            if (cb != null) sens = sens.copy(k = clamp((cb.z - ca.z) / yb.toDouble(), 0.002, 0.03))
                        }
                    }
                    return
                }
                RoundKind.PROBE_OFFSET -> {
                    val base = if (rounds.size >= 3) rounds[rounds.size - 3] else prev
                    val dq0 = qchange(Analysis.analyse(base.meas, hdr), B)
                    val relO = LinkedHashMap<String, Map<String, Double>>()
                    for (C in channels) {
                        relO[C] = smooth(dq0.map { LevelValue(id = it.id, level = it.level, value = it.v(C) / 10) }, lo = 0.0, hi = REL_MAX)
                    }
                    sens = sens.copy(
                        relO = relO.toMap(),
                        goScale = mapOf("R" to 1.0, "B" to 1.0),
                        rel20 = mapOf("R" to P20_GUESS, "B" to P20_GUESS),
                        kScale = 1.0,
                        learned = true,
                    )
                    return
                }
                RoundKind.CORRECT, RoundKind.UNDO -> {}
            }

            if (!sens.learned) return
            val relG = sens.relG ?: return
            val relO = sens.relO ?: return
            val goScale = LinkedHashMap(sens.goScale ?: return)
            val rel20 = LinkedHashMap(sens.rel20 ?: return)
            for (C in channels) {
                var num = 0.0
                var den = 0.0
                var n20 = 0.0
                var d20 = 0.0
                val g = (applied["gain:$C"] ?: 0).toDouble()
                val o = (applied["offset:$C"] ?: 0).toDouble()
                for (row in dq) {
                    val lvl = clicks(row.level * 100)
                    val pred = (g * (relG[C]?.get(row.id) ?: REL_MIN) + o * (relO[C]?.get(row.id) ?: 0.0)) * (goScale[C] ?: 1.0)
                    val c20 = (applied["p20:$C:$lvl"] ?: 0).toDouble()
                    if (c20 == 0.0 && abs(pred) > 0.004) {
                        num += pred * row.v(C)
                        den += pred * pred
                    }
                    if (c20 != 0.0) {
                        n20 += c20 * (row.v(C) - pred)
                        d20 += c20 * c20
                        val key = "$C:$lvl"
                        val tr = (sens.p20Track[key] ?: listOf(0.0, 0.0)).toMutableList()
                        tr[0] = tr[0] + c20
                        tr[1] = tr[1] + (row.v(C) - pred)
                        sens = sens.copy(p20Track = sens.p20Track + (key to tr.toList()))
                    }
                }
                if (den > 0) {
                    goScale[C] = clamp((goScale[C] ?: 1.0) * num / den, 0.3, 3.0)
                }
                if (d20 >= 4) {
                    val est = n20 / d20
                    if (est > 0.0005) {
                        rel20[C] = clamp(0.5 * (rel20[C] ?: P20_GUESS) + 0.5 * est, 0.002, 0.03)
                        sens = sens.copy(p20Learned = true)
                    }
                }
            }
            sens = sens.copy(goScale = goScale.toMap(), rel20 = rel20.toMap())
            // A multipoint control that's had 10+ clicks in total but barely moved its own step is acting
            // somewhere else (the TV's points don't sit where the app thinks), so stop using it.
            val dead = sens.p20Track.filter { entry ->
                val total = if (entry.value.isNotEmpty()) entry.value[0] else 0.0
                val resp = if (entry.value.size > 1) entry.value[1] else 0.0
                abs(total) >= 10 && abs(resp) < 0.25 * abs(total) * (rel20[entry.key.take(1)] ?: P20_GUESS)
            }.map { it.key }
            if (dead.isNotEmpty()) {
                sens = sens.copy(p20Dead = (sens.p20Dead + dead).toSet().sorted())
            }
            // colour menus with hue / saturation / brightness sliders: learn what one click does
            sens = hslLearn(sens, prev = prev, cur = cur, applied = applied, hdr = hdr)
            val k = sens.k
            if (k != null) {
                var n = 0.0
                var d = 0.0
                val kScale = sens.kScale ?: 1.0
                for ((name, _) in Patterns.colours) {
                    val ca = colourCoefs(prev.meas, name, hdr) ?: continue
                    val cb = colourCoefs(cur.meas, name, hdr) ?: continue
                    for ((j, C) in listOf("R", "G", "B").withIndex()) {
                        val cl = (applied["cms:$name:$C"] ?: 0).toDouble()
                        if (cl != 0.0 && name !in listOf("RED", "GREEN", "BLUE")) {
                            val pred = cl * k * kScale
                            n += pred * (cb[j] - ca[j])
                            d += pred * pred
                        }
                    }
                }
                if (d > 0) {
                    sens = sens.copy(kScale = clamp(kScale * n / d, 0.3, 3.0))
                }
            }
        } finally {
            st.sens = sens
        }
    }

    /**
     * For each axis, compare how far the colours moved with the clicks made. The sign is learned
     * too, so it doesn't matter which way the TV's sliders run.
     * Swift `inout sens` -> returns the updated Sensitivity.
     */
    internal fun hslLearn(sens: Sensitivity, prev: AutoRound, cur: AutoRound, applied: Map<String, Int>, hdr: Boolean): Sensitivity {
        if (applied.keys.none { it.startsWith("hsl:") }) return sens
        val a = Analysis.analyse(prev.meas, hdr).colours
        val b = LinkedHashMap<String, ColourResult>()
        for (c in Analysis.analyse(cur.meas, hdr).colours) b.putIfAbsent(c.name, c)   // first wins
        val k = LinkedHashMap(sens.hslK ?: hslGuess)
        var learned = sens.hslLearned
        for (axis in HSLAxis.entries) {
            var n = 0.0
            var d = 0.0
            for (ca in a) {
                val cl = (applied["hsl:${ca.name}:${axis.rawValue}"] ?: 0).toDouble()
                val cb = b[ca.name]
                if (cl != 0.0 && cb != null) {
                    val moved = cb.value(axis) - ca.value(axis)
                    n += cl * moved
                    d += cl * cl
                }
            }
            if (d >= 4) {
                val est = n / d
                if (abs(est) > 0.1) {
                    val old = k[axis.rawValue] ?: 1.0
                    val blended = if (old * est > 0) 0.5 * old + 0.5 * est else est   // sign flip: trust the data
                    k[axis.rawValue] = clamp(blended, -6.0, 6.0)
                }
                if (axis.rawValue !in learned) learned = learned + axis.rawValue
            }
        }
        return sens.copy(hslK = k.toMap(), hslLearned = learned)
    }

    /**
     * Clicks on hue / saturation / brightness per colour, damped and capped.
     * Swift `inout sens` -> returns (updated Sensitivity, clicks).
     */
    internal fun hslPrescribe(sens: Sensitivity, rep: Report): Pair<Sensitivity, Map<String, Int>> {
        val k = sens.hslK ?: hslGuess
        val newSens = sens.copy(hslK = k)
        val learned = newSens.hslLearned.toSet()
        val limits = mapOf(HSLAxis.HUE to 2.0, HSLAxis.SAT to 3.0, HSLAxis.LUM to 5.0)
        val out = LinkedHashMap<String, Int>()
        for (c in rep.colours) {
            if (c.de < 2.0) continue
            for (axis in HSLAxis.entries) {
                val err = c.value(axis)
                if (abs(err) < limits[axis]!!) continue
                val cap = if (axis.rawValue in learned) 6 else 3
                val raw = -0.7 * err / (k[axis.rawValue] ?: 1.0)
                var cl = clicks(raw)
                if (cl == 0) cl = if (raw > 0) 1 else -1
                out["hsl:${c.name}:${axis.rawValue}"] = clamp(cl, -cap, cap)
            }
        }
        return Pair(newSens, out.toMap())
    }

    /** One lstsq2 row (a Swift tuple (a, b, y, w)). */
    internal data class LsRow(val a: Double, val b: Double, val y: Double, val w: Double)

    /** rows: (a, b, y, w) -> minimise sum w (a x + b z + y)^2 (+ a small preference for small moves). */
    internal fun lstsq2(rows: List<LsRow>): Pair<Double, Double> {
        var saa = 0.0
        var sab = 0.0
        var sbb = 0.0
        var say = 0.0
        var sby = 0.0
        for ((a, b, y, w) in rows) {
            saa += w * a * a
            sab += w * a * b
            sbb += w * b * b
            say += w * a * y
            sby += w * b * y
        }
        val lam = 0.02 * (saa + sbb) / 2 + 1e-12
        val det = (saa + lam) * (sbb + lam) - sab * sab
        val x = (-say * (sbb + lam) + sby * sab) / det
        val z = (-sby * (saa + lam) + say * sab) / det
        return Pair(x, z)
    }

    // prescribing

    /** Swift `inout st` (mutates st.sens); returns (stage, changes, notes). */
    internal fun prescribe(
        st: AutoState, rep: Report, hdr: Boolean, points: Int, cms: ColourMenu, prevRep: Report?,
    ): Triple<Stage, Map<String, Int>, List<String>> {
        var sens = st.sens
        try { // Swift: defer { st.sens = sens }
            val changes = LinkedHashMap<String, Int>()
            val q = qvals(rep)
            val minLv = if (hdr) 0.30 else 0.10
            val usable = q.filter { it.level >= minLv - 1e-9 }
            val relG = sens.relG ?: emptyMap()
            val relO = sens.relO ?: emptyMap()
            val go = sens.goScale ?: mapOf("R" to 1.0, "B" to 1.0)
            val rel20 = sens.rel20 ?: mapOf("R" to P20_GUESS, "B" to P20_GUESS)

            // 2-point on Red and Blue, relative to green
            val twopt = LinkedHashMap<String, Pair<Double, Double>>()
            for (C in channels) {
                val rows = ArrayList<LsRow>()
                for (row in usable) {
                    val rg = (relG[C]?.get(row.id) ?: REL_MIN) * (go[C] ?: 1.0)
                    val ro = (relO[C]?.get(row.id) ?: 0.0) * (go[C] ?: 1.0)
                    val w = if (row.level < 0.2) 0.5 else (if (row.level <= (if (hdr) 0.45 else 0.40)) 1.5 else 1.0)
                    val v = row.v(C)
                    rows.add(LsRow(v * rg, v * ro, v - 1, w))
                }
                val (gx, ox) = if (rows.isEmpty()) Pair(0.0, 0.0) else lstsq2(rows)
                val lim = TWO_MAX.toDouble()
                // Swift min/max (a NaN collapses to the limit, as in Swift)
                twopt[C] = Pair(swiftMax(-lim, swiftMin(lim, 0.8 * gx)), swiftMax(-lim, swiftMin(lim, 0.8 * ox)))
            }
            val max2 = seqMax(channels.flatMap { listOf(abs(twopt[it]!!.first), abs(twopt[it]!!.second)) }) ?: 0.0

            fun rms(after: Boolean): Double {
                var tot = 0.0
                var n = 0
                for (row in usable) {
                    for (C in channels) {
                        var v = row.v(C)
                        if (after) {
                            val (gx, ox) = twopt[C]!!
                            v *= 1 + (gx * (relG[C]?.get(row.id) ?: REL_MIN) + ox * (relO[C]?.get(row.id) ?: 0.0)) * (go[C] ?: 1.0)
                        }
                        tot += (v - 1) * (v - 1)
                        n += 1
                    }
                }
                return if (n > 0) sqrt(tot / n.toDouble()) else 0.0
            }
            val helps = rms(true) < 0.75 * rms(false)
            var stage = if ((max2 >= 1.5 && helps) || points == 0) Stage.TWO_POINT else Stage.MULTI_POINT
            val prevStage = if (st.rounds.size >= 2) st.rounds[st.rounds.size - 2].stage else null
            if (points != 0 && stage == Stage.TWO_POINT && prevStage == Stage.TWO_POINT && prevRep != null &&
                Analysis.greyAverage(rep, hdr) > 0.85 * Analysis.greyAverage(prevRep, hdr)
            ) {
                stage = Stage.MULTI_POINT
            }
            if (stage == Stage.TWO_POINT) {
                for (C in channels) {
                    val (gx, ox) = twopt[C]!!
                    if (clicks(gx) != 0) changes["gain:$C"] = clicks(gx)
                    if (clicks(ox) != 0) changes["offset:$C"] = clicks(ox)
                }
            } else if (points != 0 && sens.p20Dead.size >= 2) {
                stage = Stage.TWO_POINT          // multipoint isn't landing where expected on this TV: don't keep guessing
            } else if (points != 0) {
                val cap = P20_MAX * (if (sens.p20Learned) 2 else 1)
                val dead = sens.p20Dead.toSet()
                for (row in q) {
                    val lvl = clicks(row.level * 100)
                    if (lvl < (if (hdr) 25 else 10)) continue
                    if (points == 11 && lvl % 10 != 0) continue
                    for (C in channels) {
                        if ("$C:$lvl" in dead) continue
                        val err = row.v(C) - 1
                        if (abs(err) > 0.02) {
                            val raw = -err / ((rel20[C] ?: P20_GUESS) * row.v(C)) * 0.8
                            var cl = clicks(raw)
                            if (cl == 0) cl = if (raw > 0) 1 else -1
                            changes["p20:$C:$lvl"] = clamp(cl, -cap, cap)
                        }
                    }
                }
            }

            // colours
            val notes = ArrayList<String>()
            when (cms) {
                ColourMenu.RGB_SLIDERS -> {
                    if (sens.k == null) {
                        sens = sens.copy(k = 0.004)      // never learned (e.g. a primary wasn't measured): start cautious
                        if (sens.kScale == null) sens = sens.copy(kScale = 1.0)
                        notes.add("Colour corrections are starting from an estimate for this TV; they'll sharpen with each round.")
                    }
                    val k = (sens.k ?: 0.004) * (sens.kScale ?: 1.0)
                    val meas = st.rounds[st.rounds.size - 1].meas
                    val missing = listOf("RED", "GREEN", "BLUE").filter { meas[it] == null }.map { autoCapitalized(it) }
                    if (missing.isNotEmpty()) {
                        notes.add("${missing.joinToString(", ")} couldn't be measured this round, so its own slider isn't " +
                            "adjusted and the other colours use its ideal value instead.")
                    }
                    val M = colourBasis(meas, hdr)
                    if (M != null) {
                        for (col in rep.colours) {
                            if (col.de < 2.0) continue
                            val cur = ColourMath.solve3(M, col.xyz)
                            val tgt = ColourMath.solve3(M, col.target)
                            for ((j, C) in listOf("R", "G", "B").withIndex()) {
                                val cl = clicks(0.6 * (tgt[j] - cur[j]) / k)
                                if (cl != 0) changes["cms:${col.name}:$C"] = clamp(cl, -8, 8)
                            }
                        }
                    }
                }
                ColourMenu.HSL -> {
                    val (newSens, hsl) = hslPrescribe(sens, rep)
                    sens = newSens
                    changes.putAll(hsl)
                    if (hsl.isNotEmpty() && sens.hslLearned.isEmpty()) {
                        notes.add("Colour changes start small: the app learns from the next round how much one click of " +
                            "hue, saturation and brightness moves on this TV, and which way.")
                    }
                }
                ColourMenu.OFF -> {}
            }
            if (sens.p20Dead.size >= 2) {
                notes.add("The 20-point controls on this TV don't move the steps the app expects (for example, many " +
                    "clicks at one point barely changed that step). The app sticks to 2-point here; fine-tune " +
                    "by hand with the Live meter if you need to.")
            }
            return Triple(stage, changes.toMap(), notes.toList())
        } finally {
            st.sens = sens
        }
    }

    // rounds

    /** What should happen after the latest round. */
    internal fun nextStep(st: AutoState): RoundKind {
        val done = st.rounds.dropLast(1).map { roundKind(it) }
        if (RoundKind.PROBE_GAIN !in done) return RoundKind.PROBE_GAIN
        if (RoundKind.PROBE_OFFSET !in done) return RoundKind.PROBE_OFFSET
        return RoundKind.CORRECT
    }

    internal fun describeSetup(s: RoundSetup): String {
        val src = mapOf("video" to "the pattern video", "iphone" to "the iPhone showing the patches")[s.source] ?: "?"
        var met = mapOf("hl" to "the Display Plus HL", "iphone" to "the iPhone camera")[s.meter] ?: "?"
        val phone = s.phone
        if (s.meter == "iphone" && phone != null && phone.isNotEmpty()) met += " ($phone)"
        return "$src and $met"
    }

    /**
     * After a measurement has been appended to `st.rounds`: learn, analyse and decide what to ask for next.
     * Swift `inout st`: mutates st.
     */
    fun planRound(st: AutoState, hdr: Boolean, points: Int, cms: ColourMenu): Plan {
        val cur = st.rounds[st.rounds.size - 1]
        val prev: AutoRound? = if (st.rounds.size >= 2) st.rounds[st.rounds.size - 2] else null
        // Rounds are only comparable if they used the same pattern source, meter and phone.
        if (prev != null && prev.applied?.isEmpty() == false) {
            val ps = prev.setup
            val cs = cur.setup
            if (ps != null && cs != null && ps != cs) {
                val plan = Plan(report = Analysis.analyse(cur.meas, hdr))
                plan.banner = Banner.UNDO
                plan.discard = true
                plan.message = "This round was measured with ${describeSetup(cs)}, but the last one used " +
                    "${describeSetup(ps)}. Run it again with ${describeSetup(ps)} so the app can compare like with like. " +
                    "(This round wasn't kept.)"
                return plan
            }
        }
        // A big jump in white means something other than the calibration controls changed.
        if (prev != null && prev.applied?.isEmpty() == false) {
            val w = cur.meas["W0"]
            val pw = prev.meas["W0"]
            if (w != null && pw != null) {
                val ratio = w.y / swiftMax(pw.y, 1e-6)
                if (ratio > 1.25 || ratio < 0.8) {
                    val plan = Plan(report = Analysis.analyse(cur.meas, hdr))
                    plan.banner = Banner.UNDO
                    plan.discard = true
                    plan.message = "White is now ${cfmt(w.y, 0)} cd/m², but it was ${cfmt(pw.y, 0)} " +
                        "cd/m² last round. The white balance and colour controls can't do that, so another setting changed: " +
                        "picture mode, brightness, peak brightness, energy saving or the light sensor. Put it back and run the " +
                        "round again, or start over if you meant to change it. (This round wasn't kept.)"
                    return plan
                }
            }
        }
        learn(st, hdr)
        val rep = Analysis.analyse(st.rounds[st.rounds.size - 1].meas, hdr)
        val plan = Plan(report = rep)
        plan.notes = st.notes
        if (rep.greys.isEmpty()) {
            plan.message = "No usable greyscale readings this round."
            return plan
        }
        when (nextStep(st)) {
            RoundKind.PROBE_GAIN -> {
                plan.kind = RoundKind.PROBE_GAIN
                plan.changes = gainProbe(cms)
                plan.banner = Banner.LEARN
                plan.title = "Learning round 1 of 2 - make exactly these changes, then run again"
                plan.message = "Test nudges: the app measures how Red and Blue GAIN respond on this TV (green stays put)."
                return plan
            }
            RoundKind.PROBE_OFFSET -> {
                plan.kind = RoundKind.PROBE_OFFSET
                plan.changes = offsetProbe()
                plan.banner = Banner.LEARN
                plan.title = "Learning round 2 of 2 - make exactly these changes, then run again"
                plan.message = "This puts the gains back where they were and nudges the OFFSETS instead."
                return plan
            }
            RoundKind.CORRECT, RoundKind.UNDO -> {}
        }
        val prevRep = if (st.rounds.size >= 2) Analysis.analyse(st.rounds[st.rounds.size - 2].meas, hdr) else null
        val prevApplied = prev?.applied
        if (prevRep != null && prev != null && roundKind(prev) == RoundKind.CORRECT && prevApplied != null &&
            (Analysis.greyAverage(rep, hdr) > swiftMax(2.0, 1.25 * Analysis.greyAverage(prevRep, hdr) + 0.3) ||
                Analysis.colourAverage(rep) > swiftMax(2.5, 1.25 * Analysis.colourAverage(prevRep) + 0.5))
        ) {
            plan.kind = RoundKind.UNDO
            plan.changes = prevApplied.mapValues { -it.value }
            plan.banner = Banner.UNDO
            plan.title = "Last round made things worse - undo it with these, then run again"
            return plan
        }
        val prescribed = prescribe(st, rep, hdr = hdr, points = points, cms = cms, prevRep = prevRep)
        val stage = prescribed.first
        var changes = prescribed.second
        plan.notes = plan.notes + prescribed.third
        st.rounds = st.rounds.dropLast(1) + st.rounds[st.rounds.size - 1].copy(stage = stage)
        // Two multipoint rounds in a row that didn't improve the greyscale: we're chasing noise.
        val earlier = st.rounds.dropLast(1)
        val fine = earlier.indices.filter { earlier[it].stage == Stage.MULTI_POINT && roundKind(earlier[it]) == RoundKind.CORRECT }
        if (stage == Stage.MULTI_POINT && fine.size >= 2) {
            val before = Analysis.analyse(st.rounds[fine[fine.size - 2]].meas, hdr)
            if (Analysis.greyAverage(rep, hdr) > 0.9 * Analysis.greyAverage(before, hdr)) {
                changes = changes.filterKeys { !it.startsWith("p20:") }
                plan.notes = plan.notes + ("The last 20-point rounds didn't improve the greyscale (dE " +
                    "${cfmt(Analysis.greyAverage(before, hdr), 1)} -> " +
                    "${cfmt(Analysis.greyAverage(rep, hdr), 1)}), so the app has stopped " +
                    "adjusting it: further changes would be chasing measurement noise or the TV's own processing.")
            }
        }
        // Same for colours: two rounds of colour changes that didn't help -> stop changing colours.
        fun isColourKey(k: String): Boolean = k.startsWith("cms:") || k.startsWith("hsl:")
        val colRounds = earlier.indices.filter {
            roundKind(earlier[it]) == RoundKind.CORRECT && (earlier[it].applied ?: emptyMap()).keys.any { k -> isColourKey(k) }
        }
        if (colRounds.size >= 2 && changes.keys.any { isColourKey(it) }) {
            val before = Analysis.analyse(st.rounds[colRounds[colRounds.size - 2]].meas, hdr)
            if (Analysis.colourAverage(rep) > 0.9 * Analysis.colourAverage(before)) {
                changes = changes.filterKeys { !isColourKey(it) }
                plan.notes = plan.notes + ("The last colour changes didn't improve the colours (dE " +
                    "${cfmt(Analysis.colourAverage(before), 1)} -> " +
                    "${cfmt(Analysis.colourAverage(rep), 1)}), so the app has stopped adjusting them.")
            }
        }
        val avgG = Analysis.greyAverage(rep, hdr)
        val maxG = Analysis.greyMax(rep, hdr)
        val avgC = Analysis.colourAverage(rep)
        val maxC = seqMax(rep.colours.map { it.de }) ?: 0.0
        val good = avgG < 1.2 && maxG < 2.5 && avgC < 2.0 && maxC < 3.0
        if (good || changes.isEmpty()) {
            plan.done = true
            plan.message = if (good) "Done - greyscale and colours are on target."
            else "That's as close as this TV's controls get (grey dE ${cfmt(avgG, 1)}, colour dE ${cfmt(avgC, 1)})."
            return plan
        }
        var title = if (stage == Stage.TWO_POINT) "Corrections - 2-point white balance"
        else "Corrections - fine-tune with the 20-point controls"
        if (changes.keys.any { it.startsWith("cms:") }) title += " + colours"
        plan.kind = RoundKind.CORRECT
        plan.changes = changes
        plan.title = title
        plan.stage = stage
        return plan
    }

    /** The user made the changes (ok) or skipped them. Swift `inout st`: mutates st. */
    fun recordRound(st: AutoState, plan: PendingPlan, ok: Boolean) {
        if (!ok || plan.changes.isEmpty() || st.rounds.isEmpty()) return
        st.rounds = st.rounds.dropLast(1) + st.rounds[st.rounds.size - 1].copy(applied = plan.changes.toMap(), kind = plan.kind)
        if (plan.kind == RoundKind.UNDO) {
            val r20 = st.sens.rel20
            val g = st.sens.goScale
            if (r20 != null && g != null) {
                val rel20 = LinkedHashMap(r20)
                val go = LinkedHashMap(g)
                for (C in channels) {
                    rel20[C] = swiftMin((rel20[C] ?: P20_GUESS) * 2, 0.03)
                    go[C] = swiftMin((go[C] ?: 1.0) * 2, 3.0)
                }
                st.sens = st.sens.copy(rel20 = rel20.toMap(), goScale = go.toMap())
            }
        }
    }
}

// MARK: - Showing changes as menu tables

/** One row of a menu table: a label and clicks per column. */
data class ChangeRow(val id: String, val label: String, val values: List<Int>)

data class ChangeMenu(
    val id: String,
    val title: String,
    val subtitle: String,
    val columns: List<String>,
    val rows: List<ChangeRow>,
)

object ChangeTables {
    /** Groups change keys into the TV's menus, in the order the TV shows them. */
    fun menus(changes: Map<String, Int>, points: Int): List<ChangeMenu> {
        val out = ArrayList<ChangeMenu>()
        val rgb = listOf("Red", "Green", "Blue")
        fun v(k: String): Int = changes[k] ?: 0
        // 2-point
        val two = ArrayList<ChangeRow>()
        for ((kind, label) in listOf("offset" to "Offset", "gain" to "Gain")) {
            if (changes.keys.none { it.startsWith("$kind:") }) continue
            two.add(ChangeRow(id = kind, label = label, values = listOf("R", "G", "B").map { v("$kind:$it") }))
        }
        if (two.isNotEmpty()) {
            out.add(ChangeMenu(id = "2pt", title = "White Balance", subtitle = "2 Point", columns = rgb, rows = two.toList()))
        }
        // multipoint
        val levels = changes.keys.filter { it.startsWith("p20:") }.mapNotNull { key ->
            // Swift split(separator:) drops empty pieces
            val parts = key.split(":").filter { it.isNotEmpty() }
            if (parts.size == 3) parts[2].toIntOrNull() else null
        }.toSet().sorted()
        if (levels.isNotEmpty()) {
            val rows = levels.map { lv ->
                ChangeRow(
                    id = "p$lv", label = if (points == 20) "${lv / 5}  ($lv%)" else "$lv%",
                    values = listOf("R", "G", "B").map { v("p20:$it:$lv") },
                )
            }
            out.add(
                ChangeMenu(
                    id = "20pt", title = "White Balance", subtitle = if (points == 20) "20 Point" else "Multipoint",
                    columns = rgb, rows = rows,
                ),
            )
        }
        // colour space with R/G/B sliders
        val cms = Patterns.menuOrder.filter { c -> changes.keys.any { it.startsWith("cms:$c:") } }
        if (cms.isNotEmpty()) {
            out.add(
                ChangeMenu(
                    id = "cms", title = "Color Space", subtitle = "Custom", columns = rgb,
                    rows = cms.map { c ->
                        ChangeRow(id = c, label = autoCapitalized(c), values = listOf("R", "G", "B").map { v("cms:$c:$it") })
                    },
                ),
            )
        }
        // hue / saturation / brightness
        val hsl = Patterns.menuOrder.filter { c -> changes.keys.any { it.startsWith("hsl:$c:") } }
        if (hsl.isNotEmpty()) {
            out.add(
                ChangeMenu(
                    id = "hsl", title = "Color Management", subtitle = "per colour",
                    columns = listOf("Hue", "Saturation", "Brightness"),
                    rows = hsl.map { c ->
                        ChangeRow(id = c, label = autoCapitalized(c), values = listOf("hue", "sat", "lum").map { v("hsl:$c:$it") })
                    },
                ),
            )
        }
        return out.toList()
    }
}
