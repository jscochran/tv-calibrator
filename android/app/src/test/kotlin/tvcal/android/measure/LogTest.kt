package tvcal.android.measure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tvcal.kit.ColourResult
import tvcal.kit.GreyResult
import tvcal.kit.Point2
import tvcal.kit.Report
import tvcal.kit.SignalMode
import tvcal.kit.Vec3
import java.time.LocalDateTime

class LogTest {
    private val zero = Vec3(0.0, 0.0, 0.0)

    private fun grey(name: String, level: Double, Y: Double, bal: Vec3, de: Double) = GreyResult(
        id = "G", level = level, name = name, xyz = zero, Y = Y, targetY = Y, bal = bal, rgb = zero, de = de,
        clipped = false,
    )

    private val report = Report(
        greys = listOf(
            // 0.125 and 0.375 are exact in binary: C's %.2f rounds them half-to-even (0.12, 0.38).
            grey("50% GREY", 0.5, 37.125, Vec3(1.0126, 0.99, 0.98765), 0.125),
            grey("90% GREY", 0.9, 120.0, Vec3(1.0, 1.0, 1.0), 0.375),
        ),
        colours = listOf(
            ColourResult(
                name = "RED", xyz = zero, target = zero, xy = Point2(0.0, 0.0), txy = Point2(0.0, 0.0),
                sat = 2.25, hue = -0.04, lum = 0.0, de = 1.005,
            ),
        ),
        warnings = listOf("White drifted 3% during the run."),
    )

    @Test
    fun stampIsMinutePrecisionLocalTime() {
        assertEquals("2026-03-04 05:06", Log.stamp(LocalDateTime.of(2026, 3, 4, 5, 6, 59)))
    }

    @Test
    fun tableFormatsLikeTheSwift() {
        val t = Log.table(report, hdr = false)
        val lines = t.split("\n")
        assertTrue(lines[0], lines[0].startsWith("Greyscale dE "))
        assertTrue(lines[0], lines[0].contains(", colour dE 1.00"))     // 1.005 is just below in binary
        assertTrue(t, t.contains("| step | cd/m² | R | G | B | dE |\n|---|---|---|---|---|---|\n"))
        assertTrue(t, t.contains("| 50% GREY | 37.12 | 101.3 | 99.0 | 98.8 | 0.12 |\n"))
        assertTrue(t, t.contains("| 90% GREY | 120.00 | 100.0 | 100.0 | 100.0 | 0.38 |\n"))
        assertTrue(t, t.contains("\n| colour | dE | saturation | hue | brightness |\n|---|---|---|---|---|\n"))
        assertTrue(t, t.contains("| Red | 1.00 | +2.2% | -0.0° | +0.0% |\n"))
        assertTrue(t, t.endsWith("\nWarning: White drifted 3% during the run.\n"))
    }

    @Test
    fun quickHeader() {
        val q = Log.quick(SignalMode.HDR10P, QuickWhich.AFTER, report)
        assertTrue(q, Regex("^\n## Guided HDR10\\+ - after check \\(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}\\)\n").containsMatchIn(q))
        assertTrue(Log.quick(SignalMode.SDR, QuickWhich.CHECK, report).startsWith("\n## Guided SDR - check check ("))
    }

    @Test
    fun capitalized() {
        assertEquals("Red", Log.capitalized("RED"))
        assertEquals("Magenta", Log.capitalized("magenta"))
    }
}
