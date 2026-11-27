package tvcal.android.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/** CameraLog: the last 200 lines, a line repeated straight after itself kept once. */
class CameraLogTest {
    @Test fun keepsTheLastLinesWithTheirTime() {
        val log = CameraLog(limit = 3)
        log.add("10:00:00", "a")
        log.add("10:00:01", "b")
        log.add("10:00:02", "c")
        assertEquals(listOf("10:00:01  b", "10:00:02  c", "10:00:03  d"), log.add("10:00:03", "d"))
        assertEquals(200, CameraLog.LIMIT)
    }

    @Test fun repeatsCollapseIntoOneLine() {
        val log = CameraLog()
        log.add("10:00:00", "Setting up the back main camera")
        log.add("10:00:01", "The camera stopped (disconnected: another app may be using it). Starting it again.")
        // A failed reading about every 0.3 s for minutes: one line, however many there are.
        repeat(600) { i -> log.add("10:0${1 + i / 200}:${(i % 60).toString().padStart(2, '0')}", "Measurement failed: no usable photo") }
        assertEquals(
            listOf(
                "10:00:00  Setting up the back main camera",
                "10:00:01  The camera stopped (disconnected: another app may be using it). Starting it again.",
                "10:01:00  Measurement failed: no usable photo (600 times, the last at 10:03:59)",
            ),
            log.lines,
        )
        // Something else, then the failure again: a new line.
        log.add("10:04:00", "Camera back on")
        log.add("10:04:01", "Measurement failed: no usable photo")
        assertEquals("10:04:00  Camera back on", log.lines[3])
        assertEquals("10:04:01  Measurement failed: no usable photo", log.lines[4])
        assertEquals(
            "10:04:01  Measurement failed: no usable photo (2 times, the last at 10:04:02)",
            log.add("10:04:02", "Measurement failed: no usable photo").last(),
        )
        assertEquals(5, log.lines.size)
    }
}
