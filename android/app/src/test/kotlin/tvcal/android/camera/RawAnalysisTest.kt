package tvcal.android.camera

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tvcal.android.camera.SyntheticBayer.Companion.IPHONE
import tvcal.android.camera.SyntheticBayer.Companion.PIXEL7
import tvcal.android.camera.SyntheticBayer.Companion.S24
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Design §16.1 (a): RAW analysis on synthetic Bayer frames, the rescaled thresholds, and the meter's pure
// decisions (frame plan, accumulator, white level, exposure search, dark check, DarkLevel).
class RawAnalysisTest {
    private val eps = 1e-9
    private val minD = 1.0 / 8000

    private fun measure(g: SyntheticBayer, th: Thresholds, white: Double = th.white, floorNet: Double = 0.0): RawStats =
        RawAnalysis.measure(g.plane(), g.layout, g.black, white, floorNet, th)!!

    private fun assertList(expected: List<Double>, actual: List<Double>, delta: Double = eps) {
        assertEquals("size", expected.size, actual.size)
        expected.indices.forEach { assertEquals("[$it] of $actual", expected[it], actual[it], delta) }
    }

    private fun stats(
        mean: List<Double>,
        clipped: Boolean = false,
        maxRaw: Double = 0.0,
        plateau: Boolean = false,
        quarters: List<Double> = listOf(0.0, 0.0, 0.0, 0.0),
        black: Double = 256.0,
    ) = RawStats(mean, clipped, unevenOf(quarters), maxRaw, plateau, quarters, black)

    private fun grey(v: Double) = listOf(v, v, v)

    // MARK: CFA

    @Test fun cfaFromCamera2Codes() {
        assertEquals(Cfa.RGGB, Cfa.fromCamera2(0))
        assertEquals(Cfa.GRBG, Cfa.fromCamera2(1))
        assertEquals(Cfa.GBRG, Cfa.fromCamera2(2))
        assertEquals(Cfa.BGGR, Cfa.fromCamera2(3))
        assertNull(Cfa.fromCamera2(4))      // RGB
        assertNull(Cfa.fromCamera2(5))      // MONO
        assertNull(Cfa.fromCamera2(6))      // NIR
        assertNull(Cfa.fromCamera2(-1))
        assertNull(Cfa.fromCamera2(null))
    }

    @Test fun cfaLayoutHandExamples() {
        val g = CfaLayout(Cfa.GBRG)
        assertEquals("GBRG", g.label)
        assertEquals(1, g.channel(0, 0))
        assertEquals(2, g.channel(1, 0))
        assertEquals(0, g.channel(0, 1))
        assertEquals(1, g.channel(1, 1))
        assertEquals(3, g.index(1, 1))
        assertEquals(0, g.index(2, 2))
        // The buffer starts one column into the sensor: (0, 0) is the sensor's (1, 0), blue.
        assertEquals(1, CfaLayout(Cfa.GBRG, 1, 0).index(0, 0))
        assertEquals(2, CfaLayout(Cfa.GBRG, 1, 0).channel(0, 0))
        assertEquals(0, CfaLayout(Cfa.GBRG, 0, 1).channel(0, 0))
        assertEquals(3, CfaLayout(Cfa.GBRG, 1, 1).index(0, 0))
        assertEquals(1, CfaLayout(Cfa.GBRG, 1, 1).channel(0, 0))
        assertEquals(2, CfaLayout(Cfa.RGGB, 1, 1).channel(0, 0))
        assertEquals(0, CfaLayout(Cfa.BGGR, 1, 1).channel(0, 0))
        assertEquals(0, CfaLayout(Cfa.GRBG, 1, 0).channel(0, 0))
    }

    @Test fun cfaLayoutAllPatternsAndOffsets() {
        for (cfa in Cfa.entries) for (ox in 0..1) for (oy in 0..1) {
            val layout = CfaLayout(cfa, ox, oy)
            for (y in 0 until 4) for (x in 0 until 4) {
                val i = ((y + oy) % 2) * 2 + (x + ox) % 2
                val expected = when (cfa.name[i]) {
                    'R' -> 0
                    'G' -> 1
                    else -> 2
                }
                assertEquals("$cfa ($ox,$oy) index at ($x,$y)", i, layout.index(x, y))
                assertEquals("$cfa ($ox,$oy) colour at ($x,$y)", expected, layout.channel(x, y))
            }
        }
    }

    // MARK: Plane reading

    @Test fun planeIsReadLittleEndianWhateverTheBufferOrder() {
        val bytes = byteArrayOf(
            0x02, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0x77, 0x77,          // row 0 + 2 padding bytes
            0x34, 0x12, 0x00, 0x80.toByte(), 0x77, 0x77,                   // row 1
        )
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.position(3)                                                  // someone else's read position
        val plane = RawPlane(buffer, width = 2, height = 2, rowStride = 6)
        assertEquals(258, plane.at(0, 0))
        assertEquals(65535, plane.at(1, 0))                                 // unsigned
        assertEquals(0x1234, plane.at(0, 1))
        assertEquals(0x8000, plane.at(1, 1))
        assertEquals(3, buffer.position())                                  // untouched
        assertEquals(ByteOrder.BIG_ENDIAN, buffer.order())
    }

    @Test fun planeHonoursPixelStride() {
        val bytes = byteArrayOf(0x02, 0x01, 0xAA.toByte(), 0xAA.toByte(), 0x05, 0x00, 0xAA.toByte(), 0xAA.toByte())
        val plane = RawPlane(ByteBuffer.wrap(bytes), width = 2, height = 1, rowStride = 8, pixelStride = 4)
        assertEquals(258, plane.at(0, 0))
        assertEquals(5, plane.at(1, 0))
    }

    @Test fun paddedRowsAndPixelStrideGiveTheSameStats() {
        fun frame(padding: Int, pixelStride: Int) = SyntheticBayer(
            240, 200, Cfa.BGGR, black = listOf(255.0, 256.0, 257.0, 258.0), signal = listOf(800.0, 1600.0, 400.0),
            noise = 10.0, seed = 3, padding = padding, pixelStride = pixelStride,
        )
        val plain = measure(frame(0, 2), S24)
        assertEquals(plain, measure(frame(14, 2), S24))
        assertEquals(plain, measure(frame(6, 4), S24))
        assertEquals(plain, measure(frame(1, 2), S24))                     // odd padding: rows start on odd bytes
    }

    // MARK: Box

    @Test fun centreBoxGeometry() {
        assertEquals(CentreBox(1836, 1376, 408, 306), CentreBox.of(4080, 3060))    // Galaxy S24+
        assertEquals(CentreBox(1836, 1382, 408, 306), CentreBox.of(4080, 3072))    // Pixel 7
        assertEquals(CentreBox(1836, 1376, 408, 306), CentreBox.of(4081, 3061))
        assertEquals(CentreBox(450, 350, 100, 76), CentreBox.of(1001, 777))
        assertEquals(CentreBox(3628, 2722, 806, 604), CentreBox.of(8064, 6048))    // iPhone 48 MP
        val b = CentreBox.of(1001, 777)
        assertEquals(0, b.x0 % 2)
        assertEquals(0, b.y0 % 2)
        assertEquals(0, b.width % 2)
        assertEquals(0, b.height % 2)
    }

    @Test fun fullSizeFramesReadOnlyTheBox() {
        // Everything outside the box is at the 16-bit ceiling, so reading one photosite outside it would show.
        // The box is 306 rows, so its quarter split (153 rows) cuts a 2x2 row: each half has 77 rows of one
        // parity and 76 of the other, and per-index black levels must be subtracted with per-index counts.
        data class Phone(val w: Int, val h: Int, val th: Thresholds, val black: List<Double>, val signal: List<Double>)
        for (p in listOf(
            Phone(4080, 3060, S24, listOf(255.0, 256.0, 257.0, 258.0), listOf(700.0, 1500.0, 400.0)),
            Phone(4080, 3072, PIXEL7, listOf(63.0, 64.0, 65.0, 66.0), listOf(200.0, 400.0, 100.0)),
        )) {
            val g = SyntheticBayer(p.w, p.h, Cfa.GBRG, black = p.black, signal = p.signal, outside = 65535)
            val s = measure(g, p.th)
            assertList(p.signal, s.mean)
            // Brightest: green at the higher of the two green indices' black (GBRG greens are indices 0 and 3).
            assertEquals(p.signal[1] + p.black[3], s.maxRaw, eps)
            assertEquals(p.black.average(), s.black, eps)
            val even = (p.signal[1] + p.signal[2]) / 2       // GB rows (y0 is even)
            val odd = (p.signal[0] + p.signal[1]) / 2        // RG rows
            val top = (77 * even + 76 * odd) / 153
            val bottom = (76 * even + 77 * odd) / 153
            assertList(listOf(top, top, bottom, bottom), s.quarters)
            assertEquals((bottom - top) / ((top + bottom) / 2), s.uneven, eps)
        }
    }

    // MARK: Means

    @Test fun flatMeansOnEveryCfaAndParityOffset() {
        val signal = listOf(1000.0, 2000.0, 500.0)
        for (cfa in Cfa.entries) for (ox in 0..1) for (oy in 0..1) {
            val g = SyntheticBayer(200, 160, cfa, ox, oy, black = listOf(255.0, 256.0, 257.0, 258.0), signal = signal)
            val s = measure(g, S24)
            assertList(signal, s.mean)
            assertList(List(4) { (1000.0 + 2 * 2000.0 + 500.0) / 4 }, s.quarters)
            assertEquals(0.0, s.uneven, eps)
            assertEquals(256.5, s.black, eps)
        }
    }

    @Test fun theParityOffsetMatters() {
        // A GBRG sensor whose buffer starts one column in, read as if it started on the CFA origin: the box's
        // "greens" are really blue and red, its "red" and "blue" really green.
        val g = SyntheticBayer(200, 160, Cfa.GBRG, offsetX = 1, signal = listOf(1000.0, 2000.0, 500.0))
        val wrong = RawAnalysis.measure(g.plane(), CfaLayout(Cfa.GBRG), g.black, S24.white, 0.0, S24)!!
        assertList(listOf(2000.0, 750.0, 2000.0), wrong.mean)
    }

    @Test fun darknessReadsZeroWithBlackPedestals() {
        for ((th, black) in listOf(
            S24 to listOf(255.0, 256.0, 257.0, 256.0),
            PIXEL7 to listOf(64.0, 63.0, 64.0, 65.0),
            IPHONE to listOf(0.0, 0.0, 0.0, 0.0),
        )) {
            val s = measure(SyntheticBayer(200, 200, Cfa.RGGB, black = black), th)
            assertList(grey(0.0), s.mean)
            assertFalse(s.clipped)
            assertFalse(s.plateau)
            assertEquals(0.0, s.uneven, eps)
        }
    }

    @Test fun noisyMeansAreClose() {
        val g = SyntheticBayer(400, 400, Cfa.GRBG, black = listOf(256.0, 256.0, 256.0, 256.0),
            signal = listOf(1200.0, 2400.0, 600.0), noise = 15.0, seed = 7)
        val s = measure(g, S24)
        // 1600 photosites (400 R, 800 G, 400 B): the mean's noise is under 1 count.
        assertList(listOf(1200.0, 2400.0, 600.0), s.mean, 3.0)
        assertFalse(s.clipped)
        assertFalse(s.plateau)
    }

    @Test fun badInputsGiveNull() {
        val small = SyntheticBayer(19, 19)
        assertNull(RawAnalysis.measure(small.plane(), small.layout, small.black, 4095.0, 0.0, S24))
        val g = SyntheticBayer(200, 200)
        assertNull(RawAnalysis.measure(g.plane(), g.layout, listOf(256.0, 256.0, 256.0), 4095.0, 0.0, S24))
        assertNull(RawAnalysis.measure(g.plane(), g.layout, listOf(256.0, Double.NaN, 256.0, 256.0), 4095.0, 0.0, S24))
        // A plane that claims more rows than its buffer holds.
        val p = g.plane()
        assertNull(RawAnalysis.measure(RawPlane(p.buffer, 200, 400, p.rowStride), g.layout, g.black, 4095.0, 0.0, S24))
        assertNotNull(RawAnalysis.measure(p, g.layout, g.black, 4095.0, 0.0, S24))
    }

    // MARK: Thresholds

    @Test fun thresholdsForTheTargetPhones() {
        assertEquals(3839.0, S24.range, eps)
        assertEquals(0.234328, S24.scale, 1e-6)
        assertEquals(234.328, S24.minWhiteNet, 1e-3)
        assertEquals(234.328, S24.peakFloorNet, 1e-3)
        assertEquals(468.657, S24.plateauTopNet, 1e-3)
        assertEquals(468.657, S24.darkStrongNet, 1e-3)
        assertEquals(28.119, S24.darkLeakNet, 1e-3)
        assertEquals(4018, S24.clipLevel(4095.0))
        assertEquals(4018.22, S24.learnBelow(4095.0), 1e-9)

        assertEquals(959.0, PIXEL7.range, eps)
        assertEquals(0.058536, PIXEL7.scale, 1e-6)
        assertEquals(58.536, PIXEL7.minWhiteNet, 1e-3)
        assertEquals(117.073, PIXEL7.plateauTopNet, 1e-3)
        assertEquals(7.024, PIXEL7.darkLeakNet, 1e-3)
        assertEquals(1003, PIXEL7.clipLevel(1023.0))
        assertEquals(1003.82, PIXEL7.learnBelow(1023.0), 1e-9)
    }

    @Test fun iPhoneScaleMatchesTheSwiftConstants() {
        assertEquals(1.0, IPHONE.scale, eps)
        assertEquals(16055, IPHONE.clipLevel(16383.0))       // Swift UInt16(16383 * 0.98)
        assertEquals(980, IPHONE.clipLevel(500.0))           // Swift max(white, 1000)
        assertEquals(120.0, IPHONE.darkLeakNet, eps)
        assertEquals(2000.0, IPHONE.plateauTopNet, eps)
        assertEquals(16383 * 0.98, IPHONE.learnBelow(16383.0), 1e-9)
    }

    @Test fun clipLevelClampsAnUnbelievableWhite() {
        // 300 is only 44 above black: the believable minimum (black + 234.3) is used instead.
        assertEquals(485, S24.clipLevel(300.0))
        assertEquals(64229, S24.clipLevel(70000.0))          // 16-bit ceiling: 256 + 0.98 × 65279
    }

    // MARK: Clipping and plateau

    private fun spotAtBoxCorner(g: SyntheticBayer, value: Int): (Int, Int) -> Int? {
        val b = g.box
        return { x, y -> if (x == b.x0 + 1 && y == b.y0 + 1) value else null }
    }

    @Test fun clipLevelEdgeS24() {
        val base = SyntheticBayer(200, 200, signal = grey(1000.0))
        val below = measure(SyntheticBayer(200, 200, signal = grey(1000.0), spot = spotAtBoxCorner(base, 4017)), S24)
        assertFalse(below.clipped)
        assertFalse(below.plateau)
        assertEquals(4017.0, below.maxRaw, eps)
        val at = measure(SyntheticBayer(200, 200, signal = grey(1000.0), spot = spotAtBoxCorner(base, 4018)), S24)
        assertTrue(at.clipped)
        assertFalse(at.plateau)
    }

    @Test fun clipLevelEdgePixel7() {
        val base = SyntheticBayer(200, 200, black = List(4) { 64.0 }, signal = grey(300.0))
        fun with(v: Int) = measure(
            SyntheticBayer(200, 200, black = List(4) { 64.0 }, signal = grey(300.0), spot = spotAtBoxCorner(base, v)),
            PIXEL7,
        )
        assertFalse(with(1002).clipped)
        assertTrue(with(1003).clipped)
    }

    @Test fun clipLevelFollowsTheEffectiveWhite() {
        // A learned white of 3900: clip level 256 + 0.98 × 3644 = 3827.
        val base = SyntheticBayer(200, 200, signal = grey(1000.0))
        val g = SyntheticBayer(200, 200, signal = grey(1000.0), spot = spotAtBoxCorner(base, 3830))
        assertFalse(measure(g, S24).clipped)
        assertTrue(measure(g, S24, white = 3900.0).clipped)
    }

    /** 400x400: the box is 40x40 = 1600 photosites at (180, 180). */
    private fun plateauFrame(k: Int, value: Int = 4000): SyntheticBayer {
        val b = CentreBox.of(400, 400)
        return SyntheticBayer(
            400, 400, signal = grey(1500.0), noise = 20.0, seed = 5,
            spot = { x, y ->
                val inBox = x >= b.x0 && x < b.x0 + b.width && y >= b.y0 && y < b.y0 + b.height
                if (inBox && (y - b.y0) * b.width + (x - b.x0) < k) value else null
            },
        )
    }

    @Test fun aPlateauBelowTheClipLevelIsClipping() {
        val s = measure(plateauFrame(24), S24)          // 1.5% of the box at 4000 (clip level 4018)
        assertTrue(s.plateau)
        assertTrue(s.clipped)
        assertEquals(4000.0, s.maxRaw, eps)
    }

    @Test fun aThinTailIsNotAPlateau() {
        val few = measure(plateauFrame(8), S24)         // 0.5%
        assertFalse(few.plateau)
        assertFalse(few.clipped)
        val noise = measure(plateauFrame(0), S24)       // the noise's own brightest photosites
        assertFalse(noise.plateau)
        assertFalse(noise.clipped)
    }

    @Test fun aDarkFrameIsNotAPlateau() {
        // Every photosite at the same level (100% at the top value), but that top is the black level.
        assertFalse(measure(SyntheticBayer(400, 400), S24).plateau)
        val noisy = measure(SyntheticBayer(400, 400, noise = 4.0, seed = 11), S24)
        assertFalse(noisy.plateau)
        assertFalse(noisy.clipped)
        assertTrue(noisy.maxRaw - 256 < 30)
    }

    @Test fun aPlateauNeedsATopAbove2000Scaled() {
        assertFalse(measure(SyntheticBayer(400, 400, signal = grey(400.0)), S24).plateau)    // < 468.7
        val flat = measure(SyntheticBayer(400, 400, signal = grey(500.0)), S24)
        assertTrue(flat.plateau)
        assertTrue(flat.clipped)
        // ... and above twice the dark floor.
        assertFalse(measure(SyntheticBayer(400, 400, signal = grey(500.0)), S24, floorNet = 300.0).plateau)
        // Pixel 7: 2000·s = 117.1.
        val px = List(4) { 64.0 }
        assertFalse(measure(SyntheticBayer(400, 400, black = px, signal = grey(100.0)), PIXEL7).plateau)
        assertTrue(measure(SyntheticBayer(400, 400, black = px, signal = grey(130.0)), PIXEL7).plateau)
    }

    @Test fun plateauOnA14BitSensorAsInSwift() {
        // iPhone 15 Pro: a ceiling below 14 bits, every bright photosite at the same clipped value.
        val g = SyntheticBayer(400, 400, black = List(4) { 0.0 }, signal = grey(20000.0), ceiling = 15800)
        val s = measure(g, IPHONE)
        assertTrue(s.plateau)
        assertTrue(s.clipped)
        assertEquals(15800.0, s.maxRaw, eps)
    }

    // MARK: Uneven

    @Test fun unevenLeftRightStep() {
        val xMid = CentreBox.of(400, 400).let { it.x0 + it.width / 2 }
        val g = SyntheticBayer(400, 400, black = listOf(255.0, 256.0, 257.0, 258.0),
            signalAt = { x, _, _ -> if (x < xMid) 1000.0 else 1100.0 })
        val s = measure(g, S24)
        assertList(listOf(1000.0, 1100.0, 1000.0, 1100.0), s.quarters)
        assertEquals(100.0 / 1050.0, s.uneven, eps)
    }

    @Test fun unevenTopBottomStep() {
        val yMid = CentreBox.of(400, 400).let { it.y0 + it.height / 2 }
        val g = SyntheticBayer(400, 400, Cfa.RGGB, 1, 1, black = listOf(255.0, 256.0, 257.0, 258.0),
            signalAt = { _, y, _ -> if (y < yMid) 1200.0 else 1000.0 })
        val s = measure(g, S24)
        assertList(listOf(1200.0, 1200.0, 1000.0, 1000.0), s.quarters)
        assertEquals(200.0 / 1100.0, s.uneven, eps)
    }

    @Test fun unevenLinearGradient() {
        // Net signal 2000 × (1 + 0.1·u), u from −0.5 to 0.5 across the box: halves at 1950 and 2050, uneven 0.05.
        val b = CentreBox.of(2000, 400)
        val g = SyntheticBayer(2000, 400, signalAt = { x, _, _ ->
            2000.0 * (1 + 0.1 * ((x - b.x0 + 0.5) / b.width - 0.5))
        })
        assertEquals(0.05, measure(g, S24).uneven, 1e-3)
    }

    // MARK: Multi-frame

    @Test fun accumulatorAveragesFrames() {
        val acc = FrameAccumulator()
        assertEquals(0, acc.frames)
        assertNull(acc.result())
        val s1 = stats(grey(0.0).mapIndexed { i, _ -> 10.0 + 10 * i }, maxRaw = 500.0,
            quarters = listOf(10.0, 20.0, 10.0, 20.0), black = 256.0)
        acc.add(s1)
        assertEquals(s1, acc.result())
        val s2 = stats(listOf(30.0, 40.0, 50.0), clipped = true, maxRaw = 700.0, plateau = true,
            quarters = listOf(20.0, 10.0, 20.0, 10.0), black = 258.0)
        acc.add(s2)
        assertEquals(2, acc.frames)
        val r = acc.result()!!
        assertList(listOf(20.0, 30.0, 40.0), r.mean)
        assertList(List(4) { 15.0 }, r.quarters)
        assertEquals(0.0, r.uneven, eps)        // from the averaged quarters, not the frames' own (0.67 each)
        assertTrue(r.clipped)
        assertTrue(r.plateau)
        assertEquals(700.0, r.maxRaw, eps)
        assertEquals(257.0, r.black, eps)
    }

    @Test fun accumulatorOnMeasuredFrames() {
        val xMid = CentreBox.of(400, 400).let { it.x0 + it.width / 2 }
        val a = measure(SyntheticBayer(400, 400, signalAt = { x, _, _ -> if (x < xMid) 1000.0 else 1100.0 }), S24)
        val b = measure(SyntheticBayer(400, 400, signalAt = { x, _, _ -> if (x < xMid) 1100.0 else 1000.0 }), S24)
        assertTrue(a.uneven > 0.09)
        val acc = FrameAccumulator()
        acc.add(a)
        acc.add(b)
        val r = acc.result()!!
        assertList(grey(1050.0), r.mean)
        assertEquals(0.0, r.uneven, eps)
    }

    @Test fun framePlans() {
        fun plan(s: Double, frameMax: Double = 0.150) = FramePlan.of(s, minD, frameMax, 0.25)
        assertEquals(2, plan(0.25).frames)
        assertEquals(0.125, plan(0.25).perFrame, eps)
        assertEquals(0.25, plan(0.25).total, eps)
        assertEquals(2, plan(0.16).frames)
        assertEquals(0.08, plan(0.16).perFrame, eps)
        assertEquals(1, plan(0.150).frames)                 // exactly the frame max: one frame
        assertEquals(0.150, plan(0.150).perFrame, eps)
        assertEquals(1, plan(0.10).frames)
        assertEquals(0.10, plan(0.10).perFrame, eps)
        assertEquals(FramePlan(2, 0.125), plan(5.0))        // clamped at the max
        assertEquals(FramePlan(1, minD), plan(1e-6))        // clamped at the min
        assertEquals(FramePlan(1, 0.25), plan(0.25, frameMax = 0.25))      // Pixel 7
        val many = plan(0.25, frameMax = 0.01)
        assertEquals(FramePlan.MAX_FRAMES, many.frames)
        assertEquals(0.25 / 16, many.perFrame, eps)
        assertEquals(FramePlan(2, 0.15), FramePlan.of(0.3, minD, 0.15, 1.0))
    }

    // MARK: White level

    @Test fun whiteLevelAcceptsTheReportedWhiteAndLogsOnce() {
        val w = WhiteLevel(S24)
        assertEquals("Sensor white level 4095, black level 256 (from the camera's RAW data)", w.onFrame(4095.0, 256.0))
        assertNull(w.onFrame(4095.0, 256.0))
        assertEquals(4095.0, w.current(4095.0), eps)
        assertNull(w.learned)
    }

    @Test fun whiteLevelRejectsAnUnbelievableWhite() {
        val w = WhiteLevel(S24)
        assertEquals("Sensor white level 300 doesn't look right: using 4095", w.onFrame(300.0, 256.0))
        assertNull(w.onFrame(300.0, 256.0))
        assertEquals(4095.0, w.current(300.0), eps)
        // A good frame later still gets its line.
        assertEquals("Sensor white level 4095, black level 256 (from the camera's RAW data)", w.onFrame(4095.0, 256.0))
    }

    @Test fun whiteLevelRejectsAWhiteTooCloseToTheFloor() {
        val w = WhiteLevel(S24)
        assertNull(w.setFloor(1500.0))
        assertEquals(1500.0, w.floorNet, eps)
        assertEquals("Sensor white level 4000 doesn't look right: using 4095", w.onFrame(4000.0, 256.0))
        assertEquals(4095.0, w.current(4000.0), eps)
    }

    @Test fun whiteLevelLearnsFromAPlateau() {
        val w = WhiteLevel(S24)
        w.onFrame(4095.0, 256.0)
        val clippedLow = stats(grey(3000.0), clipped = true, maxRaw = 3900.0, plateau = true)
        assertNull(w.learn(clippedLow, 4095.0, learns = false))         // a photo of the darkness
        assertNull(w.learned)
        assertNull(w.learn(clippedLow.copy(plateau = false), 4095.0, learns = true))
        assertNull(w.learn(clippedLow.copy(maxRaw = 4050.0), 4095.0, learns = true))   // at the real ceiling
        assertEquals("Sensor white level 3900 (learned from a clipped photo)", w.learn(clippedLow, 4095.0, true))
        assertEquals(3900.0, w.learned!!, eps)
        assertEquals(3900.0, w.current(4095.0), eps)
        assertEquals(3800.0, w.current(3800.0), eps)                    // a lower reported white still wins
    }

    @Test fun setFloorForgetsALearnedWhiteTooCloseToIt() {
        val w = WhiteLevel(S24)
        w.onFrame(4095.0, 256.0)
        w.learn(stats(grey(400.0), clipped = true, maxRaw = 700.0, plateau = true), 4095.0, true)
        assertEquals(700.0, w.learned!!, eps)
        assertNull(w.setFloor(2.0))                                     // 444 > 3 × 2
        assertEquals(700.0, w.learned!!, eps)
        assertEquals("Sensor white level 700 is too close to the dark level 200.0: ignoring it", w.setFloor(200.0))
        assertNull(w.learned)
        assertEquals(200.0, w.floorNet, eps)
        assertEquals(4095.0, w.current(4095.0), eps)                    // back to the reported level
        assertNull(w.setFloor(0.0))
    }

    // MARK: Exposure search

    private fun step(
        mean: List<Double>,
        frameExposure: Double,
        frames: Int = 1,
        clipped: Boolean = false,
        base: List<Double> = grey(0.0),
        th: Thresholds = S24,
        whiteNet: Double = th.range,
        requested: Double = frames * frameExposure,
    ) = ExposureSearch.step(stats(mean, clipped), requested, frames, base, whiteNet, th, minD, 0.25)

    private fun assertNext(expected: Double, s: ExposureStep) {
        assertTrue("$s", s is ExposureStep.Next)
        assertEquals(expected, (s as ExposureStep.Next).seconds, 1e-12)
    }

    @Test fun exposureTooBrightAtTheShortestShutter() {
        assertEquals(ExposureStep.TooBright, step(grey(100.0), minD, clipped = true))
        assertEquals(ExposureStep.TooBright, step(listOf(0.0, 3500.0, 0.0), minD))
    }

    @Test fun exposureLimitsUseTheRequestedTimeNotTheReportedOne() {
        // The HAL rounds 1/8000 s to whole sensor lines and reports it 4% long: still too bright.
        assertEquals(ExposureStep.TooBright, step(grey(100.0), minD * 1.04, clipped = true, requested = minD))
        // A dim patch at the maximum (2 × 0.125 s asked) whose frames report 2% short: accepted, not retried.
        val dim = step(listOf(0.0, 0.01 * 3839, 0.0), 0.1225, frames = 2, requested = 0.25)
        assertTrue("$dim", dim is ExposureStep.Accept)
        // Next guesses follow the requested time too.
        assertNext(0.0025, step(grey(100.0), 0.0104, clipped = true, requested = 0.01))
    }

    @Test fun exposureShortensByFour() {
        assertNext(0.0025, step(grey(100.0), 0.01, clipped = true))
        assertNext(0.005, step(listOf(0.0, 3000.0, 0.0), 0.02))         // peak 0.78
        assertNext(0.0625, step(grey(100.0), 0.125, frames = 2, clipped = true))
    }

    @Test fun exposureLengthens() {
        assertNext(0.064, step(listOf(0.0, 1.0, 0.0), 0.004))           // ×16 cap
        assertNext(0.032, step(listOf(0.0, 0.05 * 3839, 0.0), 0.004))   // peak 0.05 → ×8 (target 0.4)
        assertNext(0.25, step(listOf(0.0, 0.05 * 3839, 0.0), 0.1))      // capped at the max
    }

    @Test fun exposureAcceptsAtTheMax() {
        val s = step(listOf(0.0, 0.01 * 3839, 0.0), 0.125, frames = 2)
        assertTrue(s is ExposureStep.Accept)
        assertEquals(0.02, (s as ExposureStep.Accept).peak, 1e-12)
    }

    @Test fun exposurePeakIsTheEquivalentSingleExposure() {
        val perFrame = listOf(0.0, 0.2 * 3839, 0.0)
        assertEquals(0.2, (step(perFrame, 0.1) as ExposureStep.Accept).peak, 1e-12)
        val two = step(perFrame, 0.1, frames = 2) as ExposureStep.Accept
        assertEquals(0.4, two.peak, 1e-12)
        assertList(perFrame, two.net)                                    // net stays per frame
        // 0.4 per frame over two frames fills like 0.8: shorten.
        assertNext(0.05, step(listOf(0.0, 0.4 * 3839, 0.0), 0.1, frames = 2))
    }

    @Test fun exposureSubtractsTheDarkAndFloorsTheDenominator() {
        val s = step(listOf(5.0, 100.0, 5.0), 0.02, base = grey(10.0), whiteNet = 100.0) as ExposureStep.Accept
        assertList(listOf(0.0, 90.0, 0.0), s.net)
        assertEquals(90.0 / S24.peakFloorNet, s.peak, 1e-12)
        val px = step(listOf(0.0, 0.5 * 959, 0.0), 0.02, th = PIXEL7) as ExposureStep.Accept
        assertEquals(0.5, px.peak, 1e-12)
    }

    // MARK: Dark check

    @Test fun darkAccepted() {
        val c = stats(listOf(0.3, 0.4, 0.2))
        val a = stats(listOf(0.5, 0.6, 0.4))
        val b = stats(listOf(1.5, 1.6, 1.4))
        val camera = "Back camera 0 4080x3060 GBRG"
        assertEquals(
            DarkOutcome.Accepted(DarkLevel(a.mean, b.mean, longAt = 0.150, camera = camera)),
            DarkCheck.evaluate(c, a, b, 0.150, S24, camera),
        )
        assertEquals(
            DarkOutcome.Accepted(DarkLevel(a.mean, b.mean, longAt = null, camera = camera)),
            DarkCheck.evaluate(c, a, b, 0.25, S24, camera),
        )
        val nearly = DarkCheck.evaluate(c, a, b, 0.2505, S24, null) as DarkOutcome.Accepted
        assertNull(nearly.level.longAt)
        assertNull(nearly.level.camera)
    }

    @Test fun darkLeakIsNormalisedToTheIPhoneInterval() {
        val c = stats(grey(0.0))
        val a = stats(grey(0.0))
        val b = stats(grey(16.0))
        // At 1/4 s, 16 counts is under 120·s = 28.1.
        assertTrue(DarkCheck.evaluate(c, a, b, 0.25, S24, null) is DarkOutcome.Accepted)
        // At 0.150 s the same 16 counts is ×1.857 = 29.7 over 1/30 → 1/4 s: a leak.
        val r = DarkCheck.evaluate(c, a, b, 0.150, S24, null) as DarkOutcome.Rejected
        assertEquals(16 * (0.25 - 1.0 / 30) / (0.150 - 1.0 / 30), r.leak, 1e-9)
        assertEquals(1.857, r.leak / 16, 1e-3)
        assertEquals(0.0, r.flood, eps)
        assertFalse(r.strong)
    }

    @Test fun darkFloodAndStrong() {
        val c = stats(grey(0.0))
        val mild = DarkCheck.evaluate(c, stats(grey(30.0)), stats(grey(30.0)), 0.150, S24, null) as DarkOutcome.Rejected
        assertEquals(30.0, mild.flood, eps)
        assertFalse(mild.strong)
        val strong = DarkCheck.evaluate(c, stats(grey(500.0)), stats(grey(500.0)), 0.150, S24, null) as DarkOutcome.Rejected
        assertTrue(strong.strong)
        // Pixel 7: 120·s = 7.0.
        assertTrue(DarkCheck.evaluate(c, c, stats(grey(6.0)), 0.25, PIXEL7, null) is DarkOutcome.Accepted)
        assertTrue(DarkCheck.evaluate(c, c, stats(grey(8.0)), 0.25, PIXEL7, null) is DarkOutcome.Rejected)
        // The brightest colour decides.
        assertTrue(DarkCheck.evaluate(c, c, stats(listOf(0.0, 0.0, 30.0)), 0.25, S24, null) is DarkOutcome.Rejected)
    }

    // MARK: DarkLevel

    @Test fun darkLevelAt() {
        val d = DarkLevel(grey(1.0), grey(3.0))
        assertList(grey(1.0), d.at(1.0 / 30))
        assertList(grey(3.0), d.at(0.25))
        assertList(grey(1.0 + 2 * (0.5 - 1.0 / 30) / (0.25 - 1.0 / 30)), d.at(0.5))     // extrapolates
        val s24 = DarkLevel(grey(1.0), grey(3.0), longAt = 0.150)
        assertList(grey(3.0), s24.at(0.150))
        assertList(grey(1.0 + 2 * (0.125 - 1.0 / 30) / (0.150 - 1.0 / 30)), s24.at(0.125))
    }

    @Test fun darkLevelJson() {
        val old = Json.decodeFromString(DarkLevel.serializer(), """{"short":[1.5,2,3],"long":[4,5,6.25]}""")
        assertEquals(DarkLevel(listOf(1.5, 2.0, 3.0), listOf(4.0, 5.0, 6.25)), old)
        assertNull(old.longAt)
        assertNull(old.camera)
        val plain = Json.encodeToString(DarkLevel.serializer(), old)
        assertFalse(plain, plain.contains("longAt"))
        assertFalse(plain, plain.contains("camera"))
        assertEquals(old, Json.decodeFromString(DarkLevel.serializer(), plain))
        val full = DarkLevel(grey(0.5), grey(0.7), longAt = 0.15, camera = "Back camera 0 4080x3060 GBRG")
        assertEquals(full, Json.decodeFromString(DarkLevel.serializer(), Json.encodeToString(DarkLevel.serializer(), full)))
    }
}
