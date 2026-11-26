package tvcal.android.measure

import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tvcal.kit.Analysis
import tvcal.kit.GuidePlan
import tvcal.kit.MeasureError
import tvcal.kit.Meter
import tvcal.kit.MeterReading
import tvcal.kit.Patterns
import tvcal.kit.ShownPatch
import tvcal.kit.SimLiveMeter
import tvcal.kit.SimMeter
import tvcal.kit.SimTV

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
        runner.cancel()
        assertNull(result.await())
        assertEquals(SweepRunner.Phase.Cancelled, runner.phase)
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
