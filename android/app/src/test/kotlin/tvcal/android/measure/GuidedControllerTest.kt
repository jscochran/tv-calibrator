package tvcal.android.measure

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tvcal.android.storage.GuideStep
import tvcal.android.storage.TVRecord
import tvcal.android.storage.upper
import tvcal.kit.Analysis
import tvcal.kit.Patterns
import tvcal.kit.PointFinder
import tvcal.kit.SignalMode
import java.io.File

class GuidedControllerTest {
    @Test
    fun goMarksDoneOnceMovesAndSaves() = runTest {
        val (app, _) = testModel(backgroundScope)
        val tv = app.addDemoTV()
        val g = GuidedController(app, tv, SignalMode.SDR)
        assertEquals(GuideStep.SETUP, g.progress.step)

        g.message = "old"
        g.go(GuideStep.BEFORE, markDone = GuideStep.SETUP)
        assertEquals(GuideStep.BEFORE, g.progress.step)
        assertEquals(listOf(GuideStep.SETUP), g.progress.done)
        assertNull(g.message)

        g.go(GuideStep.BRIGHT, markDone = GuideStep.SETUP)
        assertEquals(listOf(GuideStep.SETUP), g.progress.done)
        g.markLimit("gain:R")
        g.markLimit("gain:R")
        assertEquals(listOf("gain:R"), g.progress.limits)

        // Saved per TV and mode.
        val again = GuidedController(app, tv, SignalMode.SDR)
        assertEquals(GuideStep.BRIGHT, again.progress.step)
        assertEquals(listOf("gain:R"), again.progress.limits)
        assertEquals(GuideStep.SETUP, GuidedController(app, tv, SignalMode.HDR10).progress.step)
        // reload switches TV/mode state.
        again.reload(tv, SignalMode.HDR10)
        assertEquals(SignalMode.HDR10, again.mode)
        assertEquals(GuideStep.SETUP, again.progress.step)
    }

    @Test
    fun goStopsLiveReadingsOutsideTheLiveSteps() = runTest {
        val (app, _) = testModel(backgroundScope)
        val g = GuidedController(app, app.addDemoTV(), SignalMode.SDR)
        g.showLive(g.plan.gain)
        assertNotNull(g.live.patch)
        assertNotNull(g.shown.patch)
        g.go(GuideStep.DARK)
        assertNotNull(g.live.patch)
        g.go(GuideStep.CHECK)
        assertNull(g.live.patch)
        assertNull(g.shown.patch)
    }

    @Test
    fun finishColourWalksTheColoursThenGoesToAfter() = runTest {
        val (app, _) = testModel(backgroundScope)
        val g = GuidedController(app, app.addDemoTV(), SignalMode.SDR)
        g.setColour("RED")
        g.finishColour("RED", done = true)
        assertEquals(listOf("RED"), g.progress.colours)
        assertEquals("GREEN", g.progress.colour)
        // Skipped (not done): moves on without marking it.
        g.finishColour("GREEN", done = false)
        assertEquals("BLUE", g.progress.colour)
        for (c in listOf("BLUE", "CYAN", "MAGENTA", "YELLOW")) g.finishColour(c, done = true)
        // GREEN was skipped, so it comes round again.
        assertEquals("GREEN", g.progress.colour)
        assertEquals(GuideStep.SETUP, g.progress.step)
        g.finishColour("GREEN", done = true)
        assertNull(g.progress.colour)
        assertEquals(GuideStep.AFTER, g.progress.step)
        assertTrue(GuideStep.COLOURS in g.progress.done)
        assertEquals(Patterns.colourIDs.toSet(), g.progress.colours.toSet())
    }

    @Test
    fun newSessionStartsFromBeforeAndKeepsHistory() = runTest {
        val (app, _) = testModel(backgroundScope)
        val g = GuidedController(app, app.addDemoTV(), SignalMode.SDR)
        g.go(GuideStep.COLOURS, markDone = GuideStep.CHECK)
        g.setFinePid("G50")
        g.newSession()
        assertEquals(GuideStep.BEFORE, g.progress.step)
        assertEquals(listOf(GuideStep.SETUP), g.progress.done)
        assertNull(g.progress.finePid)
        assertEquals(1, g.state.sessions.size)
        assertNull(g.lastSession?.before)
        assertNull(g.state.check)
        assertNull(g.checkReport)
        g.newSession()
        assertEquals(2, g.state.sessions.size)
    }

    @Test
    fun finderOverrides() = runTest {
        val (app, _) = testModel(backgroundScope)
        val g = GuidedController(app, app.addDemoTV(), SignalMode.SDR)
        assertFalse(g.finderReady)
        assertEquals(5, g.grid)
        g.setOverride("G20", 25)
        assertEquals(mapOf("G20" to 25), g.state.finder.overrides)
        assertTrue(g.finderReady)
        g.setOverride("G20", null)
        assertTrue(g.state.finder.overrides.isEmpty())
        g.skipFinder()
        assertTrue(g.finderSkipped)
        g.resetFinder()
        assertTrue(g.finderSkipped)
        assertNull(g.state.finder.base)
    }

    @Test
    fun quickBeforeAndAfterOnTheDemoTV() = runTest {
        val (app, reminders) = testModel(backgroundScope)
        val tv = app.addDemoTV()
        val g = GuidedController(app, tv, SignalMode.SDR)

        g.measureQuick(QuickWhich.BEFORE)
        assertEquals(SweepRunner.Phase.Done, g.runner.phase)
        assertFalse(g.runner.running)
        val before = g.latestBefore
        assertNotNull(before)
        val rep = Analysis.analyse(before!!, hdr = false)
        assertTrue(rep.greys.isNotEmpty())
        assertEquals(6, rep.colours.size)
        assertTrue(Analysis.greyAverage(rep, hdr = false).isFinite())
        assertTrue(g.runner.patches.isNotEmpty() && g.runner.patches.all { it.done })
        assertTrue(g.runner.recent.size in 1..40)
        assertEquals(1, g.state.sessions.size)

        val log = File(app.store.folder(tv), "log.md").readText()
        assertTrue(log, log.contains("## Guided SDR - before check ("))
        assertTrue(log, log.contains("| step | cd/m² | R | G | B | dE |"))

        g.measureQuick(QuickWhich.AFTER)
        val s = g.lastSession!!
        assertNotNull(s.after)
        assertNotNull(s.finished)
        assertEquals(before, s.before)
        assertTrue(GuideStep.AFTER in g.progress.done)
        // Measuring After again starts a new session with the same Before.
        g.measureQuick(QuickWhich.AFTER)
        assertEquals(2, g.state.sessions.size)
        assertEquals(before, g.lastSession!!.before)
        // No reminders for demo screens.
        assertTrue(reminders.scheduled.isEmpty())

        g.measureQuick(QuickWhich.CHECK)
        assertNotNull(g.state.check)
        assertNotNull(g.checkReport)
        // A fresh controller re-analyses the saved check.
        assertNotNull(GuidedController(app, tv, SignalMode.SDR).checkReport)
    }

    @Test
    fun newSessionIsFilledByTheNextBefore() = runTest {
        val (app, _) = testModel(backgroundScope)
        val g = GuidedController(app, app.addDemoTV(), SignalMode.SDR)
        g.newSession()
        g.measureQuick(QuickWhich.BEFORE)
        assertEquals(1, g.state.sessions.size)
        assertNotNull(g.latestBefore)
    }

    @Test
    fun realTVWithoutACameraShowsTheBlocker() = runTest {
        val (app, reminders) = testModel(backgroundScope)
        val tv = TVRecord(name = "Living room")
        app.add(tv)
        val g = GuidedController(app, tv, SignalMode.SDR)
        g.measureQuick(QuickWhich.BEFORE)
        assertNotNull(g.message)
        assertEquals(app.cameraBlocker, g.message)
        assertEquals(SweepRunner.Phase.Idle, g.runner.phase)
        assertTrue(g.state.sessions.isEmpty())
        g.message = null
        g.showLive(g.plan.gain)
        assertEquals(app.cameraBlocker, g.message)
        assertNull(g.live.patch)
        app.delete(tv)
        assertEquals(listOf(tv.id.upper), reminders.cancelled)
        assertTrue(app.tvs.none { it.id == tv.id })
    }

    @Test
    fun pointFinderOnTheDemoTVUndoesItsNudges() = runTest {
        val (app, _) = testModel(backgroundScope)
        val tv = app.addDemoTV()
        val g = GuidedController(app, tv, SignalMode.SDR)
        g.measureFinder(nudged = false)
        assertNotNull(g.state.finder.base)
        assertTrue(g.state.finder.base!!.missed.isEmpty())
        assertNull(g.state.finder.nudged)
        g.measureFinder(nudged = true)
        assertNotNull(g.state.finder.nudged)
        // Either the controls were found, or the user is told to measure the nudged pass again.
        assertTrue(g.finderMap.size >= 2 || g.message != null)
        val ctrl = app.simTV(tv, hdr = false).s.ctrl
        for (n in PointFinder.nudges) assertEquals(0.0, ctrl["p20:${n.channel}:${n.point}"] ?: 0.0, 0.0)
    }
}
