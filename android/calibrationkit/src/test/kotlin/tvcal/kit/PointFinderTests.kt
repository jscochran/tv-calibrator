package tvcal.kit

import org.junit.Assert.assertEquals
import org.junit.Test

class PointFinderTests {
    @Test
    fun testFinderMapMatchesPython() {
        val f = dict(Fixture.load("finder"))
        for ((i, item) in list(f["cases"]).withIndex()) {
            val c = dict(item)
            val hdr = bool(c["hdr"]) ?: false
            val grid = num(c["grid"])!!.toInt()
            val base = ProfileJson.decodeFromJsonElement(Measurements.serializer(), c["base"]!!)
            val nudged = ProfileJson.decodeFromJsonElement(Measurements.serializer(), c["nudged"]!!)
            val map = PointFinder.map(base = base, nudged = nudged, hdr = hdr)
            val want = list(c["map"]).map { dict(it) }
            assertEquals("finder $i points", want.size, map.size)
            for ((m, w) in map.zip(want)) {
                assertEquals("finder $i", num(w["point"])!!.toInt(), m.point)
                assertEquals("finder $i channel", str(w["channel"]), m.channel)
                assertClose(m.level, num(w["level"]), 1e-9, "finder $i level")
                assertClose(m.size, num(w["size"]), 1e-9, "finder $i size")
            }
            val sugg = PointFinder.suggestions(map = map, overrides = emptyMap(), hdr = hdr, grid = grid)
            for ((pid, v) in dict(c["suggest"])) {
                assertEquals("finder $i suggestion $pid", num(v)?.toInt(), sugg[pid])
            }
        }
    }

    @Test
    fun testPointForLevelMatchesPython() {
        val f = dict(Fixture.load("finder"))
        for (item in list(f["point_for_level"])) {
            val c = dict(item)
            val pairs = list(c["pairs"]).map { p ->
                val d = dict(p)
                FinderPoint(point = num(d["point"])!!.toInt(), channel = "R", level = num(d["level"])!!, size = 1.0)
            }
            val got = PointFinder.pointForLevel(pairs, level = num(c["L"])!!, grid = num(c["grid"])!!.toInt())
            assertEquals("L ${num(c["L"])!!} grid ${num(c["grid"])!!}", num(c["out"])?.toInt(), got)
        }
    }

    @Test
    fun testOverridesWin() {
        val map = listOf(
            FinderPoint(point = 20, channel = "R", level = 30.0, size = 6.0),
            FinderPoint(point = 40, channel = "B", level = 50.0, size = 4.0),
        )
        val s = PointFinder.suggestions(map = map, overrides = mapOf("G50" to 45), hdr = false, grid = 5)
        assertEquals(45, s["G50"])
    }
}
