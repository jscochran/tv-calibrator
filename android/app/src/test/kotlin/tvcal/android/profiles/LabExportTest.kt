package tvcal.android.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tvcal.kit.CameraSettings

/**
 * Real exports from the lab tool (mac-lab, run on Windows with --sim) after a rehearsal training run with the
 * Galaxy S24+ and Pixel 7 lab apps, 2026-11-26 (resources/lab-exports). The matrices are meaningless (the meter
 * was simulated); what matters is that the lab tool's file loads in the app, and that the camera settings it
 * recorded are exactly what the app's settings check compares against on each phone.
 */
class LabExportTest {
    private fun export(name: String): ByteArray =
        javaClass.getResourceAsStream("/lab-exports/$name.json")!!.use { it.readBytes() }

    @Test
    fun galaxyS24PlusExportLoads() {
        val p = ProfileImport.parse(export("samsung_e2q"))
        assertEquals("samsung/e2q", p.model)
        assertEquals("Galaxy S24+", p.name)
        assertTrue(p.isValid)
        assertNull("fixed aperture: none recorded", p.camera.aperture)
        // What the S24+'s Camera2 meter reports (camera log: logical camera 0, ISO 25, RAW 4080x3060 GBRG).
        val running = CameraSettings(iso = 25.0, format = "4080x3060 GBRG", lens = "Back camera 0", aperture = null)
        assertEquals(emptyList<String>(), p.camera.mismatches(running))
    }

    @Test
    fun pixel7ExportLoads() {
        val p = ProfileImport.parse(export("google_panther"))
        assertEquals("google/panther", p.model)
        assertEquals("Pixel 7", p.name)
        assertTrue(p.isValid)
        assertNull(p.camera.aperture)
        val running = CameraSettings(iso = 47.0, format = "4080x3072 GBRG", lens = "Back camera 0", aperture = null)
        assertEquals(emptyList<String>(), p.camera.mismatches(running))
    }
}
