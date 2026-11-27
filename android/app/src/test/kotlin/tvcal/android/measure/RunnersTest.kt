package tvcal.android.measure

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tvcal.android.camera.CameraMeter
import tvcal.android.camera.CameraXYZMeter
import tvcal.android.camera.UnavailableCameraMeter
import tvcal.kit.Analysis
import tvcal.kit.CameraProfile
import tvcal.kit.GuidePlan
import tvcal.kit.MeasureError
import tvcal.kit.Meter
import tvcal.kit.MeterReading
import tvcal.kit.Patterns
import tvcal.kit.ShownPatch
import tvcal.kit.SimLiveMeter
import tvcal.kit.SimMeter
import tvcal.kit.SimTV
import tvcal.kit.Vec3

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RunnersTest {
    private val quick = Patterns.quickSequence(hdr = false, ids = GuidePlan.forHDR(false).quickWithColours)

    /** The demo TV the app makes (multipoint controls 10% above their labels). */
    private fun demoTV(hdr: Boolean = false) = SimTV(hdr = hdr, seed = 0x123456uL).also { it.s = it.s.copy(p20Shift = 0.10) }

    private fun TestScope.virtualSeconds(): Double = testScheduler.currentTime / 1000.0

    @Test
    fun sweepRunnerMeasuresAQuickVideo() = runTest {
        val runner = SweepRunner(backgroundScope)
        val meter = SimMeter(tv = demoTV(), sequence = quick, playAt = 2.0, realTime = 0.1, seed = 42uL)
        val meas = runner.run(meter, quick, checkOrder = true)
        assertNotNull(meas)
        assertEquals(SweepRunner.Phase.Done, runner.phase)
        assertEquals(quick.patches.size, runner.patches.size)
        assertTrue(runner.patches.all { it.done && it.Y != null })
        assertEquals(quick.patches.size - 1, runner.index)
        assertTrue(runner.recent.size in 1..40)
        assertNotNull(runner.latestY)
        // The demo TV never misses a reading and has no camera to be off.
        assertEquals(0, runner.missedReadings)
        assertNull(runner.camera)
        val rep = Analysis.analyse(meas!!, hdr = false)
        assertEquals(6, rep.colours.size)
        assertTrue(rep.greys.isNotEmpty())
        runner.reset()
        assertEquals(SweepRunner.Phase.Idle, runner.phase)
    }

    @Test
    fun cancelReturnsNullAndSaysCancelled() = runTest {
        val runner = SweepRunner(backgroundScope)
        val meter = SimMeter(tv = demoTV(), sequence = quick, playAt = 2.0, realTime = 0.1, seed = 1uL)
        val result = backgroundScope.async { runner.run(meter, quick) }
        advanceTimeBy(1_000)          // 10 virtual meter seconds: still waiting for the sync
        runCurrent()
        assertTrue(runner.running)
        assertEquals(SweepRunner.Phase.Waiting, runner.phase)
        assertEquals(0, runner.missedReadings)
        assertNull(runner.cameraProblem)
        runner.cancel()
        assertNull(result.await())
        assertEquals(SweepRunner.Phase.Cancelled, runner.phase)
    }

    /** A meter on the test clock: each read takes 1 s and returns the next scripted XYZ (null = missed), then null. */
    private fun TestScope.scriptedMeter(script: List<Vec3?>): Meter = object : Meter {
        private var reads = 0
        override fun now(): Double = virtualSeconds()
        override suspend fun sleep(seconds: Double) = delay((seconds * 1000).toLong())
        override suspend fun read(): MeterReading {
            val t0 = now()
            delay(1_000)
            return MeterReading(t0, now(), script.getOrNull(reads++))
        }
        override val integration: Double get() = 0.25
    }

    @Test
    fun sweepRunnerCountsMissedReadingsInARow() = runTest {
        val grey = Vec3(30.0, 31.5, 34.0)
        val runner = SweepRunner(backgroundScope)
        val meter = scriptedMeter(listOf(null, null, grey, null, null, null, grey))
        val result = backgroundScope.async { runner.run(meter, quick) }

        advanceTimeBy(2_500)             // 2 misses: not yet worth a message
        runCurrent()
        assertEquals(SweepRunner.Phase.Waiting, runner.phase)
        assertEquals(2, runner.missedReadings)
        assertNull(runner.cameraProblem)

        advanceTimeBy(1_000)             // a good reading resets the count
        runCurrent()
        assertEquals(0, runner.missedReadings)
        assertEquals(31.5, runner.latestY!!, 0.0)
        assertNull(runner.cameraProblem)

        advanceTimeBy(3_000)             // 3 misses in a row
        runCurrent()
        assertEquals(3, runner.missedReadings)
        assertNull(runner.latestY)
        assertEquals(SweepRunner.NO_READINGS, runner.cameraProblem)
        assertNull(runner.camera)        // not a camera meter: only the misses count

        advanceTimeBy(1_000)             // readings again
        runCurrent()
        assertEquals(0, runner.missedReadings)
        assertNull(runner.cameraProblem)

        runner.cancel()
        assertNull(result.await())
        assertNull(runner.cameraProblem) // not running
    }

    @Test
    fun sweepRunnerSaysWhenTheCameraIsOff() = runTest {
        val profile = CameraProfile(
            model = "test/device", name = "Test", version = 1,
            matrix = listOf(listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0)),
        )
        val runner = SweepRunner(backgroundScope)
        val camera = UnavailableCameraMeter("The camera was interrupted.")
        val result = backgroundScope.async { runner.run(CameraXYZMeter(camera, profile), quick) }
        advanceTimeBy(100)
        runCurrent()
        assertEquals(SweepRunner.Phase.Waiting, runner.phase)
        assertEquals(camera, runner.camera)
        assertEquals("The camera is off. The camera was interrupted.", runner.cameraProblem)
        runner.cancel()
        assertNull(result.await())
        // The next run starts its count again.
        val again = backgroundScope.async { runner.run(scriptedMeter(listOf(Vec3(1.0, 1.0, 1.0))), quick) }
        advanceTimeBy(100)
        runCurrent()
        assertEquals(0, runner.missedReadings)
        assertNull(runner.camera)
        runner.cancel()
        assertNull(again.await())
    }

    @Test
    fun cameraProblemTexts() {
        // No camera (demo TV): only missed readings count.
        assertNull(SweepRunner.cameraProblem(cameraRunning = null, cameraMessage = null, missed = 2))
        assertEquals(SweepRunner.NO_READINGS, SweepRunner.cameraProblem(null, null, missed = 3))
        // A running camera: the same.
        assertNull(SweepRunner.cameraProblem(true, CameraMeter.READY_POINT, missed = 0))
        assertEquals(SweepRunner.NO_READINGS, SweepRunner.cameraProblem(true, CameraMeter.READY_POINT, missed = 7))
        assertEquals(
            "The camera isn't taking photos. If another app is using the camera, close it.",
            SweepRunner.NO_READINGS,
        )
        // Off wins over the misses; the camera's own message is added unless it says the same.
        assertEquals("The camera is off.", SweepRunner.cameraProblem(false, CameraMeter.OFF, missed = 5))
        assertEquals("The camera is off.", SweepRunner.cameraProblem(false, "", missed = 0))
        assertEquals("The camera is off.", SweepRunner.cameraProblem(false, null, missed = 0))
        assertEquals(
            "The camera is off. ${CameraMeter.PERMISSION}",
            SweepRunner.cameraProblem(false, CameraMeter.PERMISSION, missed = 0),
        )
    }

    @Test
    fun aMeterErrorFails() = runTest {
        val runner = SweepRunner(backgroundScope)
        val broken = object : Meter {
            override fun now(): Double = 0.0
            override suspend fun sleep(seconds: Double) {}
            override suspend fun read(): MeterReading = throw MeasureError.Message("No camera.")
            override val integration: Double get() = 0.25
        }
        assertNull(runner.run(broken, quick))
        assertEquals(SweepRunner.Phase.Failed("No camera."), runner.phase)
    }

    @Test
    fun liveSessionReadsTheShownPatch() = runTest {
        val tv = demoTV()
        val shown = ShownPatch()
        val live = LiveSession(backgroundScope, clock = { virtualSeconds() })
        val p = Patterns.patch(hdr = false, id = GuidePlan.forHDR(false).gain)!!
        shown.patch = p
        live.show(p, hdr = false, whiteY = null, basis = null, meter = SimLiveMeter(tv = tv, shown = shown))
        assertEquals(p, live.patch)
        assertNull(live.reading)
        advanceTimeBy(15_000)
        runCurrent()
        assertNull(live.error)
        val r = live.reading
        assertNotNull(r)

        live.restartAverage()
        advanceTimeBy(15_000)
        runCurrent()
        assertNotNull(live.reading)

        live.stop()
        assertNull(live.patch)
        assertNull(live.reading)
        advanceTimeBy(5_000)
        runCurrent()
        assertNull(live.reading)
    }

    @Test
    fun liveSessionShowsMeterErrors() = runTest {
        val live = LiveSession(backgroundScope, clock = { virtualSeconds() })
        val broken = object : Meter {
            override fun now(): Double = 0.0
            override suspend fun sleep(seconds: Double) {}
            override suspend fun read(): MeterReading = throw MeasureError.Message("Too bright.")
            override val integration: Double get() = 0.25
        }
        live.show(Patterns.patch(hdr = false, id = "W0")!!, hdr = false, whiteY = null, basis = null, meter = broken)
        runCurrent()
        assertEquals("Too bright.", live.error)
        live.stop()
    }

    @Test
    fun freeMeterAnalysesWhateverItReads() = runTest {
        val shown = ShownPatch().also { it.patch = Patterns.patch(hdr = false, id = "W0") }
        val free = FreeMeter(backgroundScope)
        free.start(SimLiveMeter(tv = demoTV(), shown = shown), hdr = false)
        advanceTimeBy(2_000)
        runCurrent()
        assertNotNull(free.analysis)
        assertNull(free.error)
        free.stop()
    }
}
