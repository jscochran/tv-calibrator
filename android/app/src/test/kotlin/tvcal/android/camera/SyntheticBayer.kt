package tvcal.android.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.roundToInt

/**
 * Builds synthetic RAW_SENSOR planes for the RawAnalysis tests (design §16.1): a W×H Bayer frame with a CFA and
 * parity offset, a black level per 2x2 CFA index, a net signal per colour (or per photosite), seeded Gaussian
 * noise, clipping at [ceiling], row padding and a pixel stride, written little-endian into a buffer whose own
 * order is big-endian (so a reader that trusts the buffer's order fails).
 *
 * The colour of each photosite is worked out here from the CFA name, not with CfaLayout/Cfa.channel, so the
 * tests don't check the analysis against itself.
 */
class SyntheticBayer(
    val width: Int,
    val height: Int,
    val cfa: Cfa = Cfa.GBRG,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    /** Absolute black level per CFA index (sensor parity), as SENSOR_DYNAMIC_BLACK_LEVEL lists them. */
    val black: List<Double> = listOf(256.0, 256.0, 256.0, 256.0),
    /** Net signal per colour R, G, B (counts above black). */
    val signal: List<Double> = listOf(0.0, 0.0, 0.0),
    /** Overrides [signal]: net signal of photosite (x, y) of colour ch. */
    val signalAt: ((x: Int, y: Int, ch: Int) -> Double)? = null,
    /** Gaussian noise sigma (counts), seeded. */
    val noise: Double = 0.0,
    val seed: Long = 1,
    /** Values are clipped to 0..ceiling (the sensor's white level). */
    val ceiling: Int = 65535,
    /** Absolute values forced at chosen photosites (clipped spots, hot pixels). */
    val spot: ((x: Int, y: Int) -> Int?)? = null,
    /** When set, every photosite outside the centre box reads this (so a box read in the wrong place shows). */
    val outside: Int? = null,
    /** Extra bytes at the end of each row, filled with 0xFF. */
    val padding: Int = 0,
    /** Bytes per sample (2, or more with 0xFF filler bytes after each sample). */
    val pixelStride: Int = 2,
) {
    val layout: CfaLayout get() = CfaLayout(cfa, offsetX, offsetY)
    val rowStride: Int get() = width * pixelStride + padding
    val box: CentreBox = CentreBox.of(width, height)

    /** 2x2 index in sensor parity, computed independently of CfaLayout. */
    fun indexOf(x: Int, y: Int): Int = ((y + offsetY) % 2) * 2 + ((x + offsetX) % 2)

    /** Colour of photosite (x, y) from the CFA's name: R 0, G 1, B 2. */
    fun colourOf(x: Int, y: Int): Int = when (cfa.name[indexOf(x, y)]) {
        'R' -> 0
        'G' -> 1
        else -> 2
    }

    private fun inBox(x: Int, y: Int): Boolean {
        val b = box
        return x >= b.x0 && x < b.x0 + b.width && y >= b.y0 && y < b.y0 + b.height
    }

    /** The values, row by row (noise drawn in that order, so a seed always gives the same frame). */
    fun values(): IntArray {
        val random = Random(seed)
        val fill = outside
        val out = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val forced = spot?.invoke(x, y)
                val v = when {
                    forced != null -> forced
                    fill != null && !inBox(x, y) -> fill
                    else -> {
                        val ch = colourOf(x, y)
                        val s = signalAt?.invoke(x, y, ch) ?: signal[ch]
                        val n = if (noise > 0) random.nextGaussian() * noise else 0.0
                        (black[indexOf(x, y)] + s + n).roundToInt()
                    }
                }
                out[y * width + x] = v.coerceIn(0, ceiling)
            }
        }
        return out
    }

    fun plane(): RawPlane {
        val v = values()
        val buffer = ByteBuffer.allocate(rowStride * height).order(ByteOrder.BIG_ENDIAN)
        for (i in 0 until buffer.capacity()) buffer.put(i, 0xFF.toByte())
        for (y in 0 until height) {
            for (x in 0 until width) {
                val at = y * rowStride + x * pixelStride
                val s = v[y * width + x]
                buffer.put(at, (s and 0xFF).toByte())
                buffer.put(at + 1, (s ushr 8).toByte())
            }
        }
        return RawPlane(buffer, width, height, rowStride, pixelStride)
    }

    companion object {
        /** Galaxy S24+ main camera: 12-bit, black 256, white 4095. */
        val S24 = Thresholds(256.0, 4095.0)
        /** Pixel 7 main camera: 10-bit, black 64, white 1023. */
        val PIXEL7 = Thresholds(64.0, 1023.0)
        /** An iPhone-like 14-bit sensor with no pedestal. */
        val IPHONE = Thresholds(0.0, 16383.0)
    }
}
