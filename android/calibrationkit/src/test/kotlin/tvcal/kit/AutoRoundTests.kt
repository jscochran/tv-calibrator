package tvcal.kit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AutoRoundTests {
    /** Every simulated round from the Python engine: same state in, same plan and learning out. */
    @Test
    fun testPlanRoundMatchesPython() {
        for (item in list(Fixture.load("plan"))) {
            val c = dict(item)
            val label = str(c["label"]) ?: "?"
            val hdr = bool(c["hdr"]) ?: false
            val points = (num(c["points"]) ?: 20.0).toInt()
            val cms = ColourMenu.from(str(c["cms"]) ?: "off") ?: ColourMenu.OFF
            val st = ProfileJson.decodeFromJsonElement(AutoState.serializer(), c["state_before"]!!)
            val plan = AutoCalibration.planRound(st, hdr = hdr, points = points, cms = cms)
            val want = dict(c["plan"])

            assertEquals("$label kind", str(want["kind"]), plan.kind?.rawValue)
            val wantChanges = dict(want["changes"]).mapValues { (num(it.value) ?: 0.0).toInt() }
            assertEquals("$label changes", wantChanges, plan.changes)
            assertEquals("$label done", bool(want["done"]) ?: false, plan.done)
            assertEquals("$label discard", bool(want["discard"]) ?: false, plan.discard)
            assertEquals("$label stage", str(want["stage"]), plan.stage?.rawValue)
            assertEquals("$label banner", str(want["banner"]) ?: "correct", plan.banner.rawValue)
            assertEquals("$label title", str(want["title"]) ?: "", plan.title)
            assertEquals("$label notes ${plan.notes}", list(want["notes"]).size, plan.notes.size)

            // what was learned
            val after = ProfileJson.decodeFromJsonElement(AutoState.serializer(), c["state_after"]!!)
            val a = st.sens
            val b = after.sens
            assertEquals("$label learned", b.learned, a.learned)
            assertEquals("$label p20 learned", b.p20Learned, a.p20Learned)
            assertEquals("$label p20 dead", b.p20Dead, a.p20Dead)
            assertEquals("$label hsl learned", b.hslLearned.toSet(), a.hslLearned.toSet())
            assertClose(a.k, b.k, 1e-9, "$label k")
            assertClose(a.kScale, b.kScale, 1e-9, "$label k scale")
            for (C in listOf("R", "B")) {
                assertClose(a.goScale?.get(C), b.goScale?.get(C), 1e-9, "$label go scale $C")
                assertClose(a.rel20?.get(C), b.rel20?.get(C), 1e-9, "$label rel20 $C")
                val mineKeys = (a.relG?.get(C) ?: emptyMap()).keys
                val wantKeys = (b.relG?.get(C) ?: emptyMap()).keys
                assertEquals("$label relG keys", wantKeys.toSet(), mineKeys.toSet())
                for ((k, v) in b.relG?.get(C) ?: emptyMap()) assertClose(a.relG?.get(C)?.get(k), v, 1e-9, "$label relG $C $k")
                for ((k, v) in b.relO?.get(C) ?: emptyMap()) assertClose(a.relO?.get(C)?.get(k), v, 1e-9, "$label relO $C $k")
            }
            for ((axis, v) in b.hslK ?: emptyMap()) assertClose(a.hslK?.get(axis), v, 1e-9, "$label hsl $axis")
            for ((key, v) in b.p20Track) {
                assertClose(a.p20Track[key]?.firstOrNull(), v.firstOrNull(), 1e-9, "$label track $key clicks")
                assertClose(a.p20Track[key]?.lastOrNull(), v.lastOrNull(), 1e-9, "$label track $key response")
            }
            assertEquals("$label round stage", after.rounds.lastOrNull()?.stage, st.rounds.lastOrNull()?.stage)
            assertEquals("$label state notes", after.notes.size, st.notes.size)
        }
    }

    /** The simulator end to end: learning, corrections, and the TV getting close to target. */
    @Test
    fun testSimulatedCalibrationConverges() = runBlocking<Unit> {
        for ((hdr, cms) in listOf(false to ColourMenu.RGB_SLIDERS, false to ColourMenu.HSL, true to ColourMenu.RGB_SLIDERS)) {
            val tv = SimTV(hdr = hdr, seed = 11uL)
            val st = AutoState()
            var first: Double? = null
            var last = Double.NaN
            for (r in 0 until 10) {
                val meter = SimMeter(tv = tv, sequence = Patterns.sequence(hdr), seed = (r + 1).toULong())
                val meas = Sweep.sweep(meter, sequence = Patterns.sequence(hdr))
                st.rounds = st.rounds + AutoRound(time = "r$r", meas = meas, setup = RoundSetup(phone = "sim"))
                val plan = AutoCalibration.planRound(st, hdr = hdr, points = 20, cms = cms)
                last = Analysis.greyAverage(plan.report, hdr)
                if (first == null) first = last
                if (plan.done) break
                AutoCalibration.recordRound(st, plan = plan.pending, ok = true)
                tv.apply(plan.changes)
            }
            assertTrue("HDR $hdr $cms: greyscale dE ended at $last", last < 2.0)
            assertTrue("HDR $hdr $cms: it should improve", last < (first ?: 0.0))
        }
    }

    @Test
    fun testSyncIsFoundAndEveryPatchMeasured() = runBlocking<Unit> {
        for (hdr in listOf(false, true)) {
            val tv = SimTV(hdr = hdr, seed = 3uL)
            val seq = Patterns.quickSequence(hdr)
            val meas = Sweep.sweep(SimMeter(tv = tv, sequence = seq, playAt = 7.0), sequence = seq)
            for (p in seq.patches) {
                assertNotNull("HDR $hdr: ${p.id} missing", meas[p.id])
                val m = meas[p.id]
                if (m != null) assertClose(m.y, tv.render(p).y, 0.03, "HDR $hdr: ${p.id} brightness")
            }
            assertFalse(Sweep.outOfOrder(meas, hdr))
        }
    }

    @Test
    fun testNoSyncWithoutAVideo() = runBlocking<Unit> {
        val tv = SimTV(hdr = false)
        val meter = SimMeter(
            tv = tv, sequence = PatternSequence(hdr = false, syncLow = 0.3, syncHigh = 1.0, patches = emptyList()),
            playAt = 1e9,
        )
        try {
            Sweep.findSync(meter, timeout = 60.0)
            fail("found a sync in black")
        } catch (e: Exception) {
            assertEquals(MeasureError.NoSync, e as? MeasureError)
        }
    }
}
