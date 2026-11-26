package tvcal.kit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuidedTests {
    @Test
    fun testSettledAverageMatchesPython() {
        val f = dict(Fixture.load("guided"))
        for ((i, item) in list(f["settled"]).withIndex()) {
            val c = dict(item)
            val rs = list(c["readings"]).map { vec3(it) }
            val out = Guided.settledAverage(rs)
            assertClose(out.xyz, vec3(c["xyz"]), 1e-9, "settled $i XYZ")
            assertEquals("settled $i count", num(c["n"])!!.toInt(), out.count)
            assertClose(out.wobble, num(c["wobble"]), 1e-9, "settled $i wobble")
        }
    }

    @Test
    fun testLiveReadingMatchesPython() {
        val f = dict(Fixture.load("guided"))
        for ((i, item) in list(f["live"]).withIndex()) {
            val c = dict(item)
            val hdr = bool(c["hdr"]) ?: false
            val p = Patterns.patch(hdr, str(c["patch_id"])!!)!!
            val b = c["basis"]
            val basis: List<Vec3>? = if (b == null || b is JsonNull) null else list(b).map { vec3(it) }
            val r = Guided.liveReading(p, vec3(c["XYZ"]), hdr, num(c["white_Y"]), basis)
            val w = dict(c["out"])
            val label = "live $i ${p.id}"
            assertClose(r.uv, point(w["uv"]), 1e-9, label)
            assertEquals("$label ok", bool(w["ok"]), r.ok)
            assertClose(r.de, num(w["de"]), 1e-9, "$label dE")
            val q = w["q"] as? JsonObject
            if (q != null) {
                assertClose(r.qR, num(q["R"]), 1e-9, "$label qR")
                assertClose(r.qB, num(q["B"]), 1e-9, "$label qB")
            }
            if (p.kind == PatchKind.COLOUR) {
                assertClose(r.hue, num(w["hue"]), 1e-9, "$label hue")
                assertClose(r.sat, num(w["sat"]), 1e-9, "$label sat")
                assertClose(r.lum, num(w["lum"]), 1e-9, "$label lum")
                assertEquals("$label toward", str(w["toward"]), r.toward)
                assertEquals("$label approx", bool(w["rgb_approx"]) ?: false, r.rgbApprox)
                val h = w["rgb_hint"]
                if (h != null && h !is JsonNull) {
                    assertClose(r.rgbHint ?: Vec3(Double.NaN, Double.NaN, Double.NaN), vec3(h), 1e-9, "$label hint")
                } else {
                    assertNull("$label hint", r.rgbHint)
                }
                val o = w["out_of_reach"] as? JsonObject
                if (o != null) {
                    // Swift `o["channels"] as? [String]`: nil unless every element is a string
                    val want = (o["channels"] as? JsonArray)
                        ?.map { str(it) }
                        ?.let { cs -> if (cs.all { it != null }) cs.map { it!! } else null }
                    assertEquals("$label out of reach", want, r.outOfReach?.channels)
                    assertClose(r.outOfReach?.bestDE, num(o["best_de"]), 1e-9, "$label best dE")
                } else {
                    assertNull("$label out of reach", r.outOfReach)
                }
            }
        }
    }

    @Test
    fun testMismatchMatchesPython() {
        val f = dict(Fixture.load("guided"))
        for ((i, item) in list(f["mismatch"]).withIndex()) {
            val c = dict(item)
            val hdr = bool(c["hdr"]) ?: false
            val p = Patterns.patch(hdr, str(c["patch_id"])!!)!!
            val got = Guided.mismatch(p, vec3(c["XYZ"]), hdr, num(c["white_Y"]))
            val want = str(c["out"])
            assertEquals("mismatch $i ${p.id}: $got vs $want", want == null, got == null)
        }
    }

    @Test
    fun testLiveAnalysisMatchesPython() {
        val f = dict(Fixture.load("guided"))
        for (item in list(f["live_analysis"])) {
            val c = dict(item)
            val r = LiveAnalysis.of(vec3(c["XYZ"]), bool(c["hdr"]) ?: false)
            val w = dict(c["out"])
            assertEquals(str(w["kind"]), if (r.kind == LiveAnalysis.Kind.WHITE) "white" else "colour")
            if (r.kind == LiveAnalysis.Kind.WHITE) {
                assertEquals(str(w["advice"]), r.advice)
                assertClose(r.de, num(w["de"]), 1e-9)
            } else {
                assertEquals(str(w["name"]), r.name)
                assertClose(r.hue, num(w["hue"]), 1e-9)
                assertClose(r.sat, num(w["sat"]), 1e-9)
            }
        }
    }
}
