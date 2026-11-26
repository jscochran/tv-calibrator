package tvcal.kit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MathsTests {
    @Test
    fun testConversionsMatchPython() {
        val f = dict(Fixture.load("maths"))
        val W = ColourMath.xyYToXYZ(ColourMath.d65, 100.0)
        for ((i, item) in list(f["xyz"]).withIndex()) {
            val c = dict(item)
            val XYZ = vec3(c["XYZ"])
            val xy = ColourMath.xyzToXY(XYZ)
            assertClose(xy, point(c["xy"]), 1e-9, "xy $i")
            assertClose(ColourMath.xyzToLab(XYZ, W), vec3(c["lab"]), 1e-9, "lab $i")
            assertClose(ColourMath.whiteDE(XYZ), num(c["white_de"]), 1e-9, "white dE $i")
            assertClose(ColourMath.upvp(xy), point(c["upvp"]), 1e-9, "u'v' $i")
            val pol = ColourMath.polar(xy)
            val want = list(c["polar"])
            assertClose(pol.r, num(want[0]), 1e-9, "polar r $i")
            assertClose(pol.angle, num(want[1]), 1e-9, "polar angle $i")
            assertClose(ColourMath.xyzToRGB709(XYZ), vec3(c["rgb709"]), 1e-9, "rgb709 $i")
        }
    }

    @Test
    fun testDE2000MatchesPython() {
        val f = dict(Fixture.load("maths"))
        for ((i, item) in list(f["de2000"]).withIndex()) {
            val c = dict(item)
            assertClose(ColourMath.de2000(vec3(c["a"]), vec3(c["b"])), num(c["de"]), 1e-9, "dE2000 $i")
        }
    }

    @Test
    fun testPQAndMatrices() {
        val f = dict(Fixture.load("maths"))
        for (item in list(f["pq"])) {
            val c = dict(item)
            num(c["nits"])?.let { n -> assertClose(ColourMath.pqEncode(n), num(c["enc"]), 1e-9, "PQ encode $n") }
            num(c["enc_in"])?.let { e -> assertClose(ColourMath.pqDecode(e), num(c["nits_out"]), 1e-9, "PQ decode $e") }
        }
        for (item in list(f["wrap"])) {
            val c = list(item)
            assertClose(ColourMath.wrap(num(c[0])!!), num(c[1]), 1e-12, "wrap")
        }
        val m709 = list(f["m709"]).map { vec3(it) }
        val inv = list(f["m709inv"]).map { vec3(it) }
        val p3 = list(f["p3"]).map { vec3(it) }
        val myP3 = ColourMath.rgbToXYZMatrix(ColourMath.Gamut.P3.primaries)
        for (r in 0 until 3) {
            assertClose(Vec3(ColourMath.m709[r][0], ColourMath.m709[r][1], ColourMath.m709[r][2]), m709[r], 1e-12, "M709")
            assertClose(
                Vec3(ColourMath.m709Inverse[r][0], ColourMath.m709Inverse[r][1], ColourMath.m709Inverse[r][2]), inv[r],
                1e-12, "M709 inverse",
            )
            assertClose(Vec3(myP3[r][0], myP3[r][1], myP3[r][2]), p3[r], 1e-12, "P3")
        }
    }

    @Test
    fun testPythonRounding() {
        assertEquals(2.0, pyRound(2.5), 0.0)
        assertEquals(4.0, pyRound(3.5), 0.0)
        assertEquals(-2.0, pyRound(-2.5), 0.0)
        assertEquals(0, AutoCalibration.clicks(Double.NaN))
    }
}

class PatternTests {
    /** Swift `p.kind.rawValue`: the kind's JSON name ("white", "grey", "colour"). */
    private fun kindRaw(k: PatchKind): String = Json.encodeToJsonElement(PatchKind.serializer(), k).jsonPrimitive.content

    private fun comparePatches(mine: List<Patch>, want: List<JsonElement>, label: String) {
        assertEquals("$label count", want.size, mine.size)
        for ((p, item) in mine.zip(want)) {
            val w = dict(item)
            assertEquals(label, str(w["id"]), p.id)
            assertEquals("$label ${p.id} name", str(w["name"]), p.name)
            assertEquals("$label ${p.id} kind", str(w["kind"]), kindRaw(p.kind))
            assertClose(p.level, num(w["level"]), 1e-12, "$label ${p.id} level")
            assertClose(p.hold, num(w["hold"]) ?: Patterns.HOLD, 1e-12, "$label ${p.id} hold")
            w["sig"]?.let { s -> assertClose(p.signal ?: Vec3(-1.0, -1.0, -1.0), vec3(s), 1e-12, "$label ${p.id} signal") }
            w["nits"]?.let { n -> assertClose(p.nits ?: Vec3(-1.0, -1.0, -1.0), vec3(n), 1e-9, "$label ${p.id} nits") }
        }
    }

    @Test
    fun testSequencesMatchTheVideos() {
        val f = dict(Fixture.load("sequences"))
        for (hdr in listOf(false, true)) {
            val w = dict(f[if (hdr) "hdr" else "sdr"])
            val seq = Patterns.sequence(hdr)
            comparePatches(seq.patches, list(w["patches"]), if (hdr) "HDR" else "SDR")
            assertEquals(list(w["offsets"]).mapNotNull { num(it) }, seq.offsets)
            assertEquals(num(w["video_length"])!!.toInt(), Patterns.videoLength(hdr))
            val finder = Patterns.finderSequence(hdr)
            comparePatches(finder.patches, list(w["finder"]), if (hdr) "HDR finder" else "SDR finder")
            assertEquals(list(w["finder_offsets"]).mapNotNull { num(it) }, finder.offsets)
            assertEquals(list(w["quick_ids"]).map { str(it) }, Patterns.quickIDs(hdr))
            assertEquals(list(w["quick_offsets"]).mapNotNull { num(it) }, Patterns.quickSequence(hdr).offsets)
            val sync = dict(w["sync"])
            val low = vec3(dict(sync["low"])[if (hdr) "nits" else "sig"])
            val high = vec3(dict(sync["high"])[if (hdr) "nits" else "sig"])
            assertEquals(low.x, seq.syncLow, 0.0)
            assertEquals(high.x, seq.syncHigh, 0.0)
            val targets = Patterns.colourTargets(hdr)
            for ((name, item) in dict(w["targets"])) {
                val t = dict(item)
                assertClose(targets[name]!!.xy, point(t["xy"]), 1e-12, "target $name")
                assertClose(targets[name]!!.Yrel, num(t["Yrel"]), 1e-12, "target $name Y")
            }
        }
        for ((mode, files) in dict(f["pack_files"])) {
            val m = SignalMode.from(mode)!!
            for ((pid, name) in dict(files)) {
                assertEquals(str(name), Patterns.packFile(m, pid))
            }
        }
    }

    @Test
    fun testMeasurementsRoundTrip() {
        var m = Measurements()
        m = m.with("W0", Vec3(100.0, 105.0, 110.0))
        m = m.withMissed("G5", MissedPatch(name = "5% GREY", readings = listOf(5.9, 6.1), hold = 7.0))
        val data = ProfileJson.encodeToString(Measurements.serializer(), m)
        val back = ProfileJson.decodeFromString(Measurements.serializer(), data)
        assertEquals(m, back)
        val obj = Json.parseToJsonElement(data) as JsonObject
        assertTrue(obj["G5"] is JsonNull)
        assertNotNull(obj["_missed"])
    }
}

class ProfileFormatTests {
    /** The files ProfileServer/build_catalog.py writes must decode in the app. */
    @Test
    fun testServerFilesDecode() {
        val index = ProfileJson.decodeFromJsonElement(ProfileCatalog.serializer(), Fixture.load("server_index"))
        assertEquals(1, index.format)
        assertEquals(2, index.entry("iPhone18,1")?.version)
        val p = ProfileJson.decodeFromJsonElement(CameraProfile.serializer(), Fixture.load("server_profile"))
        assertTrue(p.isValid)
        assertEquals(2.8, p.camera.aperture)
        assertEquals(2, p.stats.tvs.size)
        val xyz = p.xyz(cameraRGB = Vec3(100.0, 100.0, 100.0))
        assertTrue(xyz.y > 0)
    }

    @Test
    fun testCameraSettingsMismatch() {
        val trained = CameraSettings(iso = 32.0, format = "4032x3024 grb4", lens = "Back Camera", aperture = 2.8)
        assertTrue(trained.mismatches(trained).isEmpty())
        assertEquals(
            1,
            trained.mismatches(CameraSettings(iso = 32.0, format = "4032x3024 grb4", lens = "Back Camera", aperture = 1.48)).size,
        )
        assertEquals(1, trained.mismatches(CameraSettings(iso = 50.0, format = "4032x3024 grb4", lens = null, aperture = null)).size)
    }

    /**
     * The app's built-in profiles file (build_catalog.py): an own profile per model, and the standard
     * profile for models that have none.
     */
    @Test
    fun testBuiltInProfiles() {
        val p = ProfileJson.decodeFromJsonElement(CameraProfile.serializer(), Fixture.load("server_profile"))
        val older = p.copy(version = p.version - 1)
        val json = ProfileJson.encodeToString(BuiltInProfiles.serializer(), BuiltInProfiles(standard = p.model, profiles = listOf(older, p)))
        val b = ProfileJson.decodeFromString(BuiltInProfiles.serializer(), json)
        assertEquals(p.version, b.profile(p.model)?.version)
        assertNull(b.profile("iPhone99,9"))
        assertEquals(p.model, b.standardProfile?.model)
        val empty = ProfileJson.decodeFromString(BuiltInProfiles.serializer(), """{"format": 1, "standard": null, "profiles": []}""")
        assertNull(empty.standardProfile)
    }
}
