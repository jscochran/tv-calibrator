package tvcal.kit

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisTests {
    @Test
    fun testAnalyseMatchesPython() {
        for (item in list(Fixture.load("analyse"))) {
            val c = dict(item)
            val label = str(c["label"]) ?: "?"
            val hdr = bool(c["hdr"]) ?: false
            val meas = ProfileJson.decodeFromJsonElement(Measurements.serializer(), c["meas"]!!)
            val rep = Analysis.analyse(meas, hdr)
            val want = dict(c["report"])
            val greys = list(want["greys"])
            assertEquals("$label greys", greys.size, rep.greys.size)
            for ((g, wi) in rep.greys.zip(greys)) {
                val w = dict(wi)
                assertEquals(label, str(w["id"]), g.id)
                assertClose(g.Y, num(w["Y"]), 1e-9, "$label ${g.id} Y")
                assertClose(g.targetY, num(w["targetY"]), 1e-9, "$label ${g.id} target")
                assertClose(g.bal, vec3(w["bal"]), 1e-9, "$label ${g.id} balance")
                assertClose(g.de, num(w["de"]), 1e-9, "$label ${g.id} dE")
                assertEquals("$label ${g.id} clipped", bool(w["clipped"]), g.clipped)
            }
            val cols = list(want["colours"])
            assertEquals("$label colours", cols.size, rep.colours.size)
            for ((col, wi) in rep.colours.zip(cols)) {
                val w = dict(wi)
                assertEquals(label, str(w["name"]), col.name)
                assertClose(col.sat, num(w["sat"]), 1e-9, "$label ${col.name} sat")
                assertClose(col.hue, num(w["hue"]), 1e-9, "$label ${col.name} hue")
                assertClose(col.lum, num(w["lum"]), 1e-9, "$label ${col.name} lum")
                assertClose(col.de, num(w["de"]), 1e-9, "$label ${col.name} dE")
                assertClose(col.target, vec3(w["target"]), 1e-9, "$label ${col.name} target")
            }
            assertEquals("$label warnings: ${rep.warnings}", list(want["warnings"]).size, rep.warnings.size)
            assertClose(rep.drift, num(want["drift"]), 1e-9, "$label drift")
            assertClose(rep.peak, num(want["peak"]), 1e-9, "$label peak")
            assertClose(Analysis.greyAverage(rep, hdr), num(want["grey_avg"]), 1e-9, "$label grey avg")
            assertClose(Analysis.greyMax(rep, hdr), num(want["grey_max"]), 1e-9, "$label grey max")
            assertClose(Analysis.colourAverage(rep), num(want["colour_avg"]), 1e-9, "$label colour avg")
        }
    }
}
