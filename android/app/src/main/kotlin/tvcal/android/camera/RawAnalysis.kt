package tvcal.android.camera

import tvcal.kit.cfmt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Reading the RAW sensor data (port of the RawStats / RawAnalysis part of Camera/CameraMeter.swift), plus
// the meter's pure decisions (exposure search, dark check, white level, multi-frame integration) so they
// can be tested on the JVM with synthetic Bayer frames. Pure Kotlin: no android.* imports.
// Design: docs/camera-meter-design.md §3, §5–§9. Owner: agent (a).

/** Bayer colour filter arrangements, by Camera2 SENSOR_INFO_COLOR_FILTER_ARRANGEMENT code (0–3). */
enum class Cfa(val code: Int, private val cell: IntArray) {
    RGGB(0, intArrayOf(0, 1, 1, 2)),
    GRBG(1, intArrayOf(1, 0, 2, 1)),
    GBRG(2, intArrayOf(1, 2, 0, 1)),
    BGGR(3, intArrayOf(2, 1, 1, 0));

    /** Colour (0 red, 1 green, 2 blue) of 2x2 cell index i = (y % 2) * 2 + (x % 2), in sensor parity. */
    fun channel(index: Int): Int = cell[index]

    companion object {
        /** Null for RGB (4), MONO (5), NIR (6) or a missing value: the meter needs a Bayer sensor. */
        fun fromCamera2(code: Int?): Cfa? = if (code == null) null else entries.firstOrNull { it.code == code }
    }
}

/**
 * How the RAW buffer's pixels map onto the CFA. [offsetX]/[offsetY] (0 or 1) shift the parity when the buffer
 * doesn't start on the sensor's CFA origin (design §3.3).
 */
data class CfaLayout(val cfa: Cfa, val offsetX: Int = 0, val offsetY: Int = 0) {
    /** 2x2 index in the sensor's CFA order of buffer pixel (x, y); also the SENSOR_DYNAMIC_BLACK_LEVEL index. */
    fun index(x: Int, y: Int): Int = ((y + offsetY) and 1) * 2 + ((x + offsetX) and 1)

    /** Colour (0 R, 1 G, 2 B) of buffer pixel (x, y). */
    fun channel(x: Int, y: Int): Int = cfa.channel(index(x, y))

    /** e.g. "GBRG" (the CFA name used in CameraSettings.format). */
    val label: String get() = cfa.name
}

/**
 * One RAW_SENSOR plane: 16-bit little-endian samples. [rowStride] and [pixelStride] are in bytes (Image.Plane),
 * so padded rows are skipped. The buffer's own byte order is ignored (always read little-endian).
 */
class RawPlane(
    val buffer: ByteBuffer,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val pixelStride: Int = 2,
) {
    /** A private view in little-endian order; absolute reads only, so the shared buffer's position is untouched. */
    private val le: ByteBuffer = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)

    /** The unsigned 16-bit sample at (x, y). */
    fun at(x: Int, y: Int): Int = le.getShort(y * rowStride + x * pixelStride).toInt() and 0xFFFF

    /** Bytes the buffer must hold for sample (x, y) to be readable. */
    internal fun endOf(x: Int, y: Int): Long = y.toLong() * rowStride + x.toLong() * pixelStride + 2

    /** Readable bytes in the buffer (absolute indices 0 until this). */
    internal val limit: Int get() = le.limit()
}

/** The measured box: 10% of the width and height in the centre, aligned to whole 2x2 cells (Swift `&~1`). */
data class CentreBox(val x0: Int, val y0: Int, val width: Int, val height: Int) {
    companion object {
        /** boxW = (W/10)&~1, boxH = (H/10)&~1, x0 = (W/2 − boxW/2)&~1, y0 = (H/2 − boxH/2)&~1. */
        fun of(width: Int, height: Int): CentreBox {
            val boxW = (width / 10) and 1.inv()
            val boxH = (height / 10) and 1.inv()
            val x0 = (width / 2 - boxW / 2) and 1.inv()
            val y0 = (height / 2 - boxH / 2) and 1.inv()
            return CentreBox(x0, y0, boxW, boxH)
        }
    }
}

/**
 * The Swift meter's constants, which assume 14-bit RAW (full scale 16383), rescaled to this camera's signal
 * range (design §5). [black] = mean of SENSOR_BLACK_LEVEL_PATTERN, [white] = SENSOR_INFO_WHITE_LEVEL (static,
 * so the thresholds don't move with per-frame black/white levels). Counts are "net" (black subtracted) unless
 * a name says absolute.
 */
data class Thresholds(val black: Double, val white: Double) {
    /** white − black: S24+ 3839, Pixel 7 959. */
    val range: Double get() = white - black

    /** range / 16383: S24+ 0.23433, Pixel 7 0.05854. */
    val scale: Double get() = range / IPHONE_FULL_SCALE

    /** Swift max(white, 1000): the lowest believable white level, net (S24+ 234.3, Pixel 58.5). */
    val minWhiteNet: Double get() = 1000 * scale

    /** Swift plateau `top > 2000`, net (S24+ 468.7, Pixel 117.1). */
    val plateauTopNet: Double get() = 2000 * scale

    /** Swift peak denominator floor 1000, net (S24+ 234.3, Pixel 58.5). */
    val peakFloorNet: Double get() = 1000 * scale

    /** Swift dark leak/flood limit 120, net, over the iPhone's 1/30 → 1/4 s interval (S24+ 28.1, Pixel 7.0). */
    val darkLeakNet: Double get() = 120 * scale

    /** Swift "still seeing light" limit 2000, net (S24+ 468.7, Pixel 117.1). */
    val darkStrongNet: Double get() = 2000 * scale

    /**
     * Absolute clip level for an absolute white level w (Swift `UInt16(min(max(w, 1000), 16383) * 0.98)`):
     * floor(black + 0.98 × (clamp(w, black + minWhiteNet, 65535) − black)). S24+ 4018, Pixel 1003.
     */
    fun clipLevel(white: Double): Int {
        val w = min(max(white, black + minWhiteNet), 65535.0)
        return floor(black + CLIP_SHARE * (w - black)).toInt()
    }

    /** A plateau teaches a new white level only below this (Swift `maxRaw < white * 0.98`), absolute. */
    fun learnBelow(white: Double): Double = white - (1 - CLIP_SHARE) * (white - black)

    companion object {
        /** The iPhone meter's 14-bit full scale. */
        const val IPHONE_FULL_SCALE = 16383.0
        const val CLIP_SHARE = 0.98
        /** More than 1% of the box within a hair of the top value means it's clipped. */
        const val PLATEAU_SHARE = 0.01
    }
}

/**
 * One frame's (or one integration's) numbers from the centre box (Swift RawStats, plus [quarters] and [black]).
 * [mean] and [quarters] are black-subtracted (per CFA channel, the frame's own black levels), so they are
 * slightly negative or ~0 in darkness; [maxRaw] is absolute.
 */
data class RawStats(
    /** Average R, G, B photosite value in the centre box, black subtracted (counts). */
    val mean: List<Double>,
    /** Photosites at the sensor's ceiling (any ≥ clip level, or a plateau). */
    val clipped: Boolean,
    /** Brightness spread across the box's four quarters (0 = even), from [quarters]. */
    val uneven: Double,
    /** The brightest photosite in the box, absolute counts. */
    val maxRaw: Double,
    /** Many photosites pile up at that brightest value: the sensor's real ceiling. */
    val plateau: Boolean,
    /** Black-subtracted mean of each quarter (all colours): top-left, top-right, bottom-left, bottom-right. */
    val quarters: List<Double>,
    /** Mean of the four black levels subtracted. */
    val black: Double,
)

/** Swift: (max − min) / average of the quarter means, 0 when the average isn't positive. */
internal fun unevenOf(quarters: List<Double>): Double {
    val avg = quarters.sum() / quarters.size
    return if (avg > 0) (quarters.max() - quarters.min()) / avg else 0.0
}

object RawAnalysis {
    /** Smallest plane measured (smaller ones give a box of a few photosites). */
    private const val MIN_SIZE = 20

    /**
     * Swift RawAnalysis.measure on an Android RAW plane. [black]: four black levels in CFA index order
     * (SENSOR_DYNAMIC_BLACK_LEVEL, else SENSOR_BLACK_LEVEL_PATTERN); [white]: this frame's effective absolute
     * white level (WhiteLevel.current); [floorNet]: the dark residual (0 until measured). Null if the plane is
     * smaller than 20x20 or the black list doesn't have 4 values.
     */
    fun measure(
        plane: RawPlane,
        layout: CfaLayout,
        black: List<Double>,
        white: Double,
        floorNet: Double,
        th: Thresholds,
    ): RawStats? {
        if (plane.width < MIN_SIZE || plane.height < MIN_SIZE) return null
        if (black.size != 4 || black.any { !it.isFinite() } || !white.isFinite()) return null
        if (plane.pixelStride < 2 || plane.rowStride < 2) return null
        val box = CentreBox.of(plane.width, plane.height)
        val x1 = box.x0 + box.width
        val y1 = box.y0 + box.height
        // The last photosite read must be inside the buffer (Image planes may omit the last row's padding).
        if (plane.endOf(x1 - 1, y1 - 1) > plane.limit) return null

        // Sums and counts per 2x2 CFA index (so each photosite's own black level can be subtracted), overall
        // and per quarter. The box height (306 on both target phones) cuts the quarters mid-cell, so the
        // quarter counts per index aren't equal and are kept separately.
        val sum = LongArray(4)
        val count = LongArray(4)
        val qSum = LongArray(16)       // quarter * 4 + index
        val qCount = LongArray(16)
        val clipLevel = th.clipLevel(white)
        var clipped = false
        var maxRaw = 0
        val histogram = IntArray(65536)
        val xMid = box.x0 + box.width / 2
        val yMid = box.y0 + box.height / 2

        for (y in box.y0 until y1) {
            val qy = if (y < yMid) 0 else 2
            val parity = ((y + layout.offsetY) and 1) * 2
            for (x in box.x0 until x1) {
                val raw = plane.at(x, y)
                if (raw >= clipLevel) clipped = true
                if (raw > maxRaw) maxRaw = raw
                histogram[raw]++
                val i = parity + ((x + layout.offsetX) and 1)
                sum[i] += raw.toLong()
                count[i]++
                val q = (qy + if (x < xMid) 0 else 1) * 4 + i
                qSum[q] += raw.toLong()
                qCount[q]++
            }
        }

        val chSum = DoubleArray(3)
        val chCount = DoubleArray(3)
        for (i in 0 until 4) {
            val c = layout.cfa.channel(i)
            chSum[c] += sum[i] - count[i] * black[i]
            chCount[c] += count[i].toDouble()
        }
        val mean = (0 until 3).map { if (chCount[it] > 0) chSum[it] / chCount[it] else 0.0 }
        val quarters = (0 until 4).map { q ->
            var s = 0.0
            var n = 0L
            for (i in 0 until 4) {
                s += qSum[q * 4 + i] - qCount[q * 4 + i] * black[i]
                n += qCount[q * 4 + i]
            }
            if (n > 0) s / n else 0.0
        }
        val blackMean = black.sum() / 4

        // A real patch's brightest photosites are a thin tail of the noise; at the sensor's ceiling they
        // pile up. More than 1% of the box within a hair of the top value means it's clipped.
        val top = maxRaw
        val band = max(2, top * 3 / 1000)
        var nearTop = 0L
        for (v in max(0, top - band)..top) nearTop += histogram[v]
        val total = box.width.toLong() * box.height
        val plateau = (top - blackMean) > max(th.plateauTopNet, 2 * floorNet) &&
            total > 0 && nearTop.toDouble() / total > Thresholds.PLATEAU_SHARE
        if (plateau) clipped = true
        return RawStats(
            mean = mean,
            clipped = clipped,
            uneven = unevenOf(quarters),
            maxRaw = top.toDouble(),
            plateau = plateau,
            quarters = quarters,
            black = blackMean,
        )
    }
}

/**
 * Combines the frames of one multi-frame shot (design §6): mean and quarters averaged, clipped/plateau if any
 * frame was, maxRaw the highest, uneven recomputed from the averaged quarters.
 */
class FrameAccumulator {
    private val all = mutableListOf<RawStats>()

    val frames: Int get() = all.size

    fun add(stats: RawStats) {
        all.add(stats)
    }

    /** Null before the first frame. */
    fun result(): RawStats? {
        if (all.isEmpty()) return null
        if (all.size == 1) return all[0]
        val n = all.size.toDouble()
        val mean = (0 until 3).map { c -> all.sumOf { it.mean[c] } / n }
        val quarters = (0 until 4).map { q -> all.sumOf { it.quarters[q] } / n }
        return RawStats(
            mean = mean,
            clipped = all.any { it.clipped },
            uneven = unevenOf(quarters),
            maxRaw = all.maxOf { it.maxRaw },
            plateau = all.any { it.plateau },
            quarters = quarters,
            black = all.sumOf { it.black } / n,
        )
    }
}

/** How a requested shutter time is taken: [frames] equal frames of [perFrame] seconds each (design §6). */
data class FramePlan(val frames: Int, val perFrame: Double) {
    val total: Double get() = frames * perFrame

    companion object {
        /** At most this many frames per shot. */
        const val MAX_FRAMES = 16

        /**
         * d = clamp(seconds, minDuration, maxDuration); N = ceil(d / frameMax − 1e-9) (at least 1, at most
         * MAX_FRAMES); perFrame = d / N.
         */
        fun of(seconds: Double, minDuration: Double, frameMax: Double, maxDuration: Double): FramePlan {
            // Swift order min(max(d, lo), hi): never throws, even if a camera reports lo > hi.
            val d = min(max(seconds, minDuration), maxDuration)
            val n = if (frameMax > 0) ceil(d / frameMax - 1e-9).toInt().coerceIn(1, MAX_FRAMES) else 1
            return FramePlan(n, d / n)
        }
    }
}

/**
 * The sensor's white level (Swift CameraEngine whiteLevel / floorLevel / setWhite / setFloor), design §7.
 * Confined to the camera thread. Every method returns the log line to write, or null.
 */
class WhiteLevel(val th: Thresholds) {
    /** Learned from a clipped photo (absolute); null until then. */
    var learned: Double? = null
        private set

    /** The dark residual (net counts) once the darkness has been measured; 0 until then. */
    var floorNet: Double = 0.0
        private set

    /** The last frame's mean black level (absolute); the static one until the first frame. */
    private var frameBlack = th.black
    private var reported = false
    private var rejected = false

    private fun believable(w: Double, black: Double): Boolean {
        val net = w - black
        return w.isFinite() && net > th.minWhiteNet && net > 3 * floorNet
    }

    /**
     * Each frame's reported white level (SENSOR_DYNAMIC_WHITE_LEVEL, else SENSOR_INFO_WHITE_LEVEL) and mean black.
     * Accepted when (w − black) > th.minWhiteNet and (w − black) > 3 × floorNet, else the static white is used.
     * Returns "Sensor white level W, black level B (from the camera's RAW data)" the first time only (or the
     * "doesn't look right" line when rejected).
     */
    fun onFrame(frameWhite: Double, black: Double): String? {
        if (black.isFinite()) frameBlack = black
        return if (believable(frameWhite, frameBlack)) {
            if (reported) {
                null
            } else {
                reported = true
                "Sensor white level ${cfmt(frameWhite, 0)}, black level ${cfmt(frameBlack, 0)} " +
                    "(from the camera's RAW data)"
            }
        } else {
            if (rejected) {
                null
            } else {
                rejected = true
                "Sensor white level ${cfmt(frameWhite, 0)} doesn't look right: using ${cfmt(th.white, 0)}"
            }
        }
    }

    /** The effective absolute white level for a frame: min(accepted frame white, learned). */
    fun current(frameWhite: Double): Double {
        val w = if (believable(frameWhite, frameBlack)) frameWhite else th.white
        return learned?.let { min(w, it) } ?: w
    }

    /**
     * Swift: `pendingLearns, stats.plateau, maxRaw < white × 0.98` → learned = maxRaw, "Sensor white level N
     * (learned from a clipped photo)". [white] is the frame's effective white.
     */
    fun learn(stats: RawStats, white: Double, learns: Boolean): String? {
        if (!learns || !stats.plateau || !(stats.maxRaw < th.learnBelow(white))) return null
        learned = stats.maxRaw
        return "Sensor white level ${cfmt(stats.maxRaw, 0)} (learned from a clipped photo)"
    }

    /**
     * Swift setFloor: floorNet = level; if level > 0 and (white − black) < 3 × level, forget [learned] and
     * return "Sensor white level W is too close to the dark level L: ignoring it".
     */
    fun setFloor(levelNet: Double): String? {
        floorNet = if (levelNet.isFinite()) levelNet else 0.0
        val w = learned ?: return null
        if (floorNet > 0 && (w - frameBlack) < 3 * floorNet) {
            learned = null
            return "Sensor white level ${cfmt(w, 0)} is too close to the dark level ${cfmt(floorNet, 1)}: ignoring it"
        }
        return null
    }
}

/** One step of the exposure search (Swift CameraMeter.measure loop body), design §9. */
sealed interface ExposureStep {
    /** Good exposure: [net] = dark-corrected signal per frame (counts), [peak] = equivalent single-exposure fill. */
    data class Accept(val net: List<Double>, val peak: Double) : ExposureStep

    /** Try again at [seconds] (total). */
    data class Next(val seconds: Double) : ExposureStep

    /** Clipped at the shortest shutter time. */
    data object TooBright : ExposureStep
}

object ExposureSearch {
    /** Swift: 7 shots at most. */
    const val MAX_SHOTS = 7

    /**
     * net = max(mean − base, 0); peak = max(net) / max(whiteNet − max(base), th.peakFloorNet) × frames;
     * clipped or peak > 0.75 → TooBright if used ≤ minDuration × 1.01, else Next(used / 4);
     * peak < 0.15 and used < maxDuration × 0.99 → Next(min(used × min(0.4 / max(peak, 0.002), 16), maxDuration));
     * else Accept. base = dark.at(frameExposure); whiteNet = white − stats.black.
     *
     * used = [requested], the shot's planned total (FramePlan.total: the asked time clamped to [minDuration,
     * maxDuration]), as in the Swift, where `used` is the clamped request. Not the reported total: the HAL rounds
     * the exposure to whole sensor lines (FrameCheck allows ±10%), so the reported time at a limit can miss the
     * 1% margins and the search would repeat the same clamped shot. The reported per-frame exposure stays the
     * divisor for the per-second numbers and the dark level (the caller's job).
     */
    fun step(
        stats: RawStats,
        requested: Double,
        frames: Int,
        base: List<Double>,
        whiteNet: Double,
        th: Thresholds,
        minDuration: Double,
        maxDuration: Double,
    ): ExposureStep {
        val used = requested
        val net = (0 until 3).map { max(stats.mean[it] - base[it], 0.0) }
        val peak = net.max() / max(whiteNet - base.max(), th.peakFloorNet) * frames
        if (stats.clipped || peak > 0.75) {
            if (used <= minDuration * 1.01) return ExposureStep.TooBright
            return ExposureStep.Next(used / 4)
        }
        if (peak < 0.15 && used < maxDuration * 0.99) {
            return ExposureStep.Next(min(used * min(0.4 / max(peak, 0.002), 16.0), maxDuration))
        }
        return ExposureStep.Accept(net, peak)
    }
}

/** What measuring the darkness found (Swift measureDark), design §8. */
sealed interface DarkOutcome {
    data class Accepted(val level: DarkLevel) : DarkOutcome

    /** [strong]: max(leak, flood) > th.darkStrongNet ("Still seeing light…"). Values are net counts. */
    data class Rejected(val leak: Double, val flood: Double, val strong: Boolean) : DarkOutcome
}

object DarkCheck {
    /** A long shot within this of 1/4 s is the iPhone's own (DarkLevel.longAt stays null). */
    private const val LONG_AT_TOLERANCE = 0.001

    /**
     * c at minDuration, a at 1/30 s, b at [longAt] (= min(1/4 s, frame max), one frame each).
     * leak = max_ch(b − a) × (1/4 − 1/30) / (longAt − 1/30) (normalised to the iPhone's interval);
     * flood = max_ch(a − c). Rejected if leak or flood > th.darkLeakNet; else
     * Accepted(DarkLevel(a.mean, b.mean, longAt = longAt unless within 1 ms of 1/4 s, camera = [camera])).
     */
    fun evaluate(c: RawStats, a: RawStats, b: RawStats, longAt: Double, th: Thresholds, camera: String?): DarkOutcome {
        val interval = longAt - DarkLevel.tShort
        // A camera whose longest frame isn't longer than 1/30 s has no interval to judge a leak over: compare
        // the two shots as they are rather than amplifying their noise.
        val factor = if (interval > 1e-6) (DarkLevel.tLong - DarkLevel.tShort) / interval else 1.0
        val leak = (0 until 3).maxOf { b.mean[it] - a.mean[it] } * factor
        val flood = (0 until 3).maxOf { a.mean[it] - c.mean[it] }
        if (leak > th.darkLeakNet || flood > th.darkLeakNet) {
            return DarkOutcome.Rejected(leak, flood, strong = max(leak, flood) > th.darkStrongNet)
        }
        val at = if (abs(longAt - DarkLevel.tLong) <= LONG_AT_TOLERANCE) null else longAt
        return DarkOutcome.Accepted(DarkLevel(a.mean, b.mean, longAt = at, camera = camera))
    }
}
