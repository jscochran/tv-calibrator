package tvcal.kit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The signal the TV is calibrated for. Each one has its own folder of pattern videos. */
@Serializable
enum class SignalMode(val rawValue: String) {
    @SerialName("sdr") SDR("sdr"),
    @SerialName("hdr10") HDR10("hdr10"),
    @SerialName("hdr10p") HDR10P("hdr10p"),
    @SerialName("dv") DV("dv");

    val id: String get() = rawValue
    val isHDR: Boolean get() = this != SDR
    val label: String
        get() = when (this) {
            SDR -> "SDR"; HDR10 -> "HDR10"; HDR10P -> "HDR10+"; DV -> "Dolby Vision"
        }

    /** What the TV shows as its badge when the right signal arrives. */
    val badge: String
        get() = when (this) {
            SDR -> "SDR"; HDR10 -> "HDR"; HDR10P -> "HDR10+"; DV -> "Dolby Vision"
        }

    /** File-name prefix of the pattern videos, e.g. "DV 03 - Grey 50 (middle).mp4". */
    val packPrefix: String
        get() = when (this) {
            SDR -> "SDR"; HDR10 -> "HDR10"; HDR10P -> "HDR10+"; DV -> "DV"
        }
    val packFolder: String
        get() = when (this) {
            SDR -> "SDR"; HDR10 -> "HDR10"; HDR10P -> "HDR10+"; DV -> "Dolby Vision"
        }

    companion object {
        fun from(raw: String): SignalMode? = entries.firstOrNull { it.rawValue == raw }
    }
}

@Serializable
enum class PatchKind {
    @SerialName("white") WHITE,
    @SerialName("grey") GREY,
    @SerialName("colour") COLOUR,
}

/** One measured pattern: a centred window (10% of the screen) of a grey, white or colour. */
@Serializable
data class Patch(
    val id: String,
    val kind: PatchKind,
    /** SDR: signal level 0-1. HDR greys/whites: PQ code value 0-1. HDR colours: null. */
    val level: Double?,
    val name: String,
    /** SDR: R'G'B' signal 0-1. */
    val signal: Vec3? = null,
    /** HDR: linear RGB in nits, in the colour's own gamut (P3). */
    val nits: Vec3? = null,
    /** Colours: which primaries are on (1/0). */
    val rgb: Vec3? = null,
    /** Seconds on screen in the videos. */
    val hold: Double = Patterns.HOLD,
) {
    /** Grey/white level as the video's own percentage (SDR signal % or PQ %), e.g. 50 for "G50". */
    val percent: Double get() = (level ?: 0.0) * 100
}

/** A pattern video's timeline: black, three sync flashes (grey, white, grey), then the patches. */
data class PatternSequence(
    val hdr: Boolean,
    /** The sync flashes: SDR signal levels, or HDR nits. */
    val syncLow: Double,
    val syncHigh: Double,
    val patches: List<Patch>,
) {
    /** Start time of each patch, in seconds after the sync. */
    val offsets: List<Double>
        get() {
            val out = ArrayList<Double>(patches.size)
            var t = 0.0
            for (p in patches) {
                out += t
                t += p.hold
            }
            return out
        }

    /** Same order and timing rules, only some patches (the quick-check videos). */
    fun keeping(ids: List<String>): PatternSequence = copy(patches = patches.filter { it.id in ids })
}

object Patterns {
    /** Seconds of black before the sync. */
    const val LEAD = 10.0
    /** Low / high / low sync flashes, seconds each. */
    const val SYNC_HOLD = 6.0
    /** Seconds per measured patch. */
    const val HOLD = 7.0
    /** HDR colours: very saturated light can make a meter integrate for several seconds. */
    const val HDR_COLOUR_HOLD = 12.0
    /** Point-finder greys. */
    const val FINDER_HOLD = 5.0

    val colours: List<Pair<String, Vec3>> = listOf(
        "RED" to Vec3(1.0, 0.0, 0.0), "GREEN" to Vec3(0.0, 1.0, 0.0), "BLUE" to Vec3(0.0, 0.0, 1.0),
        "CYAN" to Vec3(0.0, 1.0, 1.0), "MAGENTA" to Vec3(1.0, 0.0, 1.0), "YELLOW" to Vec3(1.0, 1.0, 0.0),
    )
    val colourIDs = listOf("RED", "GREEN", "BLUE", "CYAN", "MAGENTA", "YELLOW")
    val hueOrder = listOf("RED", "YELLOW", "GREEN", "CYAN", "BLUE", "MAGENTA")
    /** The order colour menus list them in. */
    val menuOrder = listOf("RED", "GREEN", "BLUE", "YELLOW", "CYAN", "MAGENTA")

    private fun f0(x: Double) = cfmt(x, 0)

    /** The full automatic-round sequence (the long tvcal_auto video). */
    fun sequence(hdr: Boolean): PatternSequence {
        val pats = mutableListOf<Patch>()
        if (!hdr) {
            pats += Patch("W0", PatchKind.WHITE, 1.0, "100% WHITE", signal = Vec3.repeating(1.0))
            for (p in 5..100 step 5) {
                val l = p / 100.0
                pats += Patch("G$p", PatchKind.GREY, l, "$p% GREY", signal = Vec3.repeating(l))
            }
            for ((n, c) in colours) {
                pats += Patch(n, PatchKind.COLOUR, 0.75, "$n 75%", signal = c * 0.75, rgb = c)
            }
            pats += Patch("W1", PatchKind.WHITE, 1.0, "100% WHITE (drift check)", signal = Vec3.repeating(1.0))
            return PatternSequence(hdr = false, syncLow = 0.30, syncHigh = 1.0, patches = pats)
        }
        pats += Patch("W0", PatchKind.WHITE, ColourMath.pqEncode(100.0), "100 nit WHITE", nits = Vec3.repeating(100.0))
        for (p in 25..75 step 5) {
            val n = ColourMath.pqDecode(p / 100.0)
            pats += Patch("G$p", PatchKind.GREY, p / 100.0, "PQ $p% GREY (${f0(n)} nits)", nits = Vec3.repeating(n))
        }
        for ((n, c) in colours) {
            pats += Patch(n, PatchKind.COLOUR, null, "P3 $n", nits = c * 100.0, rgb = c, hold = HDR_COLOUR_HOLD)
        }
        pats += Patch(
            "W1", PatchKind.WHITE, ColourMath.pqEncode(100.0), "100 nit WHITE (drift check)",
            nits = Vec3.repeating(100.0),
        )
        return PatternSequence(hdr = true, syncLow = 25.0, syncHigh = 200.0, patches = pats)
    }

    fun patch(hdr: Boolean, id: String): Patch? = sequence(hdr).patches.firstOrNull { it.id == id }

    /** Length of the automatic-round video in seconds. */
    fun videoLength(hdr: Boolean): Int =
        (LEAD + 3 * SYNC_HOLD).toInt() + sequence(hdr).patches.sumOf { it.hold }.toInt()

    /** The point-finder video: the same sync, then finely spaced greys (5 s each). */
    fun finderSequence(hdr: Boolean): PatternSequence {
        val base = sequence(hdr)
        val pats = mutableListOf(
            if (hdr) {
                Patch("FW", PatchKind.WHITE, ColourMath.pqEncode(100.0), "WHITE", nits = Vec3.repeating(100.0), hold = FINDER_HOLD)
            } else {
                Patch("FW", PatchKind.WHITE, 1.0, "WHITE", signal = Vec3.repeating(1.0), hold = FINDER_HOLD)
            },
        )
        if (!hdr) {
            for (p in 10..100 step 5) {
                val l = p / 100.0
                pats += Patch("F$p", PatchKind.GREY, l, "$p% GREY", signal = Vec3.repeating(l), hold = FINDER_HOLD)
            }
        } else {
            for (i in 0 until 25) {
                val pq = 20 + 2.5 * i
                val n = ColourMath.pqDecode(pq / 100)
                pats += Patch(
                    "F${(pq * 10).toInt()}", PatchKind.GREY, pq / 100,
                    "PQ ${gfmt(pq)}% GREY (${f0(n)} nits)", nits = Vec3.repeating(n), hold = FINDER_HOLD,
                )
            }
        }
        return base.copy(patches = pats)
    }

    /** The patches in the one-to-two-minute quick-check videos. */
    fun quickIDs(hdr: Boolean): List<String> =
        if (hdr) listOf("W0", "G30", "G40", "G50", "G60", "G70", "RED", "GREEN", "BLUE", "CYAN", "MAGENTA", "YELLOW")
        else listOf("W0", "G20", "G40", "G60", "G80", "G100", "RED", "GREEN", "BLUE", "CYAN", "MAGENTA", "YELLOW")

    fun quickSequence(hdr: Boolean, ids: List<String>? = null): PatternSequence =
        sequence(hdr).keeping(ids ?: quickIDs(hdr))

    /** The pattern pack on the USB stick: (patch id or "QUICK", file label), in file order. */
    fun packFiles(hdr: Boolean): List<Pair<String, String>> {
        if (!hdr) {
            return listOf(
                "QUICK" to "00 - Quick check (play from the start)", "W0" to "01 - White (bright end)",
                "G30" to "02 - Grey 30 (dark end)", "G50" to "03 - Grey 50 (middle)", "G20" to "04 - Grey 20",
                "G40" to "05 - Grey 40", "G60" to "06 - Grey 60", "G80" to "07 - Grey 80", "RED" to "08 - Red",
                "GREEN" to "09 - Green", "BLUE" to "10 - Blue", "CYAN" to "11 - Cyan", "MAGENTA" to "12 - Magenta",
                "YELLOW" to "13 - Yellow",
            )
        }
        return listOf(
            "QUICK" to "00 - Quick check (play from the start)", "G65" to "01 - Grey 65 (bright end)",
            "G35" to "02 - Grey 35 (dark end)", "G50" to "03 - Grey 50 (middle)", "G40" to "04 - Grey 40",
            "G60" to "05 - Grey 60", "G70" to "06 - Grey 70", "RED" to "07 - Red", "GREEN" to "08 - Green",
            "BLUE" to "09 - Blue", "CYAN" to "10 - Cyan", "MAGENTA" to "11 - Magenta", "YELLOW" to "12 - Yellow",
        )
    }

    /** File name (without .mp4) of the pattern video for a patch, e.g. "SDR 02 - Grey 30 (dark end)". */
    fun packFile(mode: SignalMode, id: String): String? =
        packFiles(mode.isHDR).firstOrNull { it.first == id }?.let { "${mode.packPrefix} ${it.second}" }

    /** The point-finder video for a mode, e.g. "SDR 00b - Point finder (play from the start)". */
    fun finderFile(mode: SignalMode): String = "${mode.packPrefix} 00b - Point finder (play from the start)"

    /** The long automatic-round video. */
    fun autoFile(hdr: Boolean): String = if (hdr) "tvcal_auto_HDR" else "tvcal_auto_SDR"

    data class ColourTarget(val xy: Point2, val Yrel: Double)

    /** Colour targets: chromaticity and brightness relative to white (Rec.709 for SDR, P3 for HDR). */
    fun colourTargets(hdr: Boolean): Map<String, ColourTarget> {
        val m = ColourMath.rgbToXYZMatrix((if (hdr) ColourMath.Gamut.P3 else ColourMath.Gamut.REC709).primaries)
        return colours.associate { (n, c) ->
            val v = ColourMath.matvec(m, c)
            n to ColourTarget(ColourMath.xyzToXY(v), v.y)
        }
    }
}
