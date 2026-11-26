package tvcal.android.storage

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tvcal.kit.Measurements
import tvcal.kit.SignalMode
import tvcal.kit.SimTV
import tvcal.kit.Vec3
import java.io.File
import java.time.Instant

class TVStoreTests {
    @get:Rule val tmp = TemporaryFolder()

    private val fixed: Instant = Instant.parse("2025-11-25T10:00:00Z")
    private fun store() = TVStore(File(tmp.root, "TVs"), now = { fixed })

    private fun tv(name: String, created: String) = TVRecord(name = name, created = Instant.parse(created))

    @Test fun createsRootAndFolders() {
        val s = store()
        assertTrue(s.root.isDirectory)
        val t = tv("A", "2025-01-01T00:00:00Z")
        val f = s.folder(t)
        assertTrue(f.isDirectory)
        assertEquals(t.id.upper, f.name)
        assertEquals(s.root, f.parentFile)
    }

    @Test fun saveAndLoadOrderedByCreated() {
        val s = store()
        val c = tv("Third", "2025-03-01T00:00:00Z")
        val a = tv("First", "2025-01-01T00:00:00Z")
        val b = tv("Second", "2025-02-01T00:00:00Z")
        listOf(c, a, b).forEach { s.save(it) }
        assertEquals(listOf(a, b, c), s.loadTVs())
        // The saved file is sorted, pretty JSON.
        val text = File(s.folder(a), "tv.json").readText()
        assertTrue(text.startsWith("{\n  \"brand\""))
        // Saving again replaces the file.
        val renamed = a.copy(name = "Renamed")
        s.save(renamed)
        assertEquals(listOf(renamed, b, c), s.loadTVs())
    }

    @Test fun loadTidiesFoldersWithoutTvJson() {
        val s = store()
        val keep = tv("Keep", "2025-01-01T00:00:00Z")
        s.save(keep)
        // Left over from a deleted screen: a folder with only a guide file, an empty folder, a stray file.
        val orphan = File(s.root, "11111111-2222-3333-4444-555555555555").apply { mkdirs() }
        File(orphan, "guide-sdr.json").writeText("{}")
        val empty = File(s.root, "EMPTY").apply { mkdirs() }
        val stray = File(s.root, ".DS_Store").apply { writeText("x") }
        // A folder whose tv.json can't be read is skipped, but kept.
        val broken = File(s.root, "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE").apply { mkdirs() }
        File(broken, "tv.json").writeText("""{"name": "no id"}""")

        assertEquals(listOf(keep), s.loadTVs())
        assertFalse(orphan.exists())
        assertFalse(empty.exists())
        assertFalse(stray.exists())
        assertTrue(broken.exists())
        assertTrue(File(s.root, keep.id.upper).exists())
    }

    @Test fun loadEmptyRoot() {
        assertEquals(emptyList<TVRecord>(), store().loadTVs())
        // A missing root reads as no TVs.
        val gone = TVStore(File(tmp.root, "TVs"))
        gone.root.deleteRecursively()
        assertEquals(emptyList<TVRecord>(), gone.loadTVs())
    }

    @Test fun deleteRemovesFolder() {
        val s = store()
        val t = tv("Gone", "2025-01-01T00:00:00Z")
        s.save(t)
        s.appendLog(t, "hello\n")
        s.delete(t)
        assertFalse(File(s.root, t.id.upper).exists())
        assertEquals(emptyList<TVRecord>(), s.loadTVs())
    }

    @Test fun guideStatePerMode() {
        val s = store()
        val t = tv("TV", "2025-01-01T00:00:00Z")
        assertEquals(GuideState(), s.guideState(t, SignalMode.SDR))
        val st = GuideState(
            progress = GuideProgress(step = GuideStep.DARK, done = listOf(GuideStep.SETUP)),
            finder = FinderData(overrides = mapOf("_skip" to 0)),
        )
        s.save(st, t, SignalMode.HDR10P)
        assertTrue(File(s.folder(t), "guide-hdr10p.json").exists())
        assertEquals(st, s.guideState(t, SignalMode.HDR10P))
        assertEquals(GuideState(), s.guideState(t, SignalMode.SDR))
        // An unreadable file reads as a fresh state.
        File(s.folder(t), "guide-dv.json").writeText("not json")
        assertEquals(GuideState(), s.guideState(t, SignalMode.DV))
    }

    @Test fun simStateRoundTrip() {
        val s = store()
        val t = tv("Demo", "2025-01-01T00:00:00Z").copy(isDemo = true)
        assertNull(s.simState(t, false))
        val sim = SimTV(false)
        sim.apply(mapOf("gain_r" to 3))
        s.saveSim(sim.s, t, false)
        assertTrue(File(s.folder(t), "sim-sdr.json").exists())
        assertEquals(sim.s, s.simState(t, false))
        assertNull(s.simState(t, true))
        File(s.folder(t), "sim-hdr.json").writeText("""{"peak": 700}""")
        assertNull(s.simState(t, true))
    }

    @Test fun appendLogAppends() {
        val s = store()
        val t = tv("Log", "2025-01-01T00:00:00Z")
        s.appendLog(t, "# One\n")
        s.appendLog(t, "Two – ✓\n")
        assertEquals("# One\nTwo – ✓\n", File(s.folder(t), "log.md").readText(Charsets.UTF_8))
    }

    @Test fun exportIncludesOnlyUsedModes() {
        val s = store()
        val t = tv("Living/Room", "2025-01-01T00:00:00Z")
        s.save(t)
        val m = Measurements(mapOf("W0" to Vec3(1.0, 2.0, 3.0)))
        s.save(GuideState(sessions = listOf(GuidedSession(started = fixed, before = m))), t, SignalMode.SDR)
        s.save(GuideState(finder = FinderData(base = m)), t, SignalMode.HDR10)
        s.save(GuideState(progress = GuideProgress(step = GuideStep.BEFORE)), t, SignalMode.DV)
        val out = File(tmp.root, "shared")
        val file = s.export(t, null, "0.1", "samsung/e2q", out)
        assertNotNull(file)
        file!!
        assertEquals("Living-Room - TV Calibrator export.json", file.name)
        assertEquals(out, file.parentFile)
        val o = StoreJson.parseToJsonElement(file.readText()).jsonObject
        assertEquals(listOf("app", "exported", "guided", "phone", "tv"), o.keys.toList())   // no profile: left out
        assertEquals("0.1", o["app"]!!.jsonPrimitive.content)
        assertEquals("2025-11-25T10:00:00Z", o["exported"]!!.jsonPrimitive.content)
        assertEquals("samsung/e2q", o["phone"]!!.jsonPrimitive.content)
        assertEquals(setOf("sdr", "hdr10"), o["guided"]!!.jsonObject.keys)
        assertEquals(t, StoreDecoding.tvRecord(o["tv"]!!))
    }
}
