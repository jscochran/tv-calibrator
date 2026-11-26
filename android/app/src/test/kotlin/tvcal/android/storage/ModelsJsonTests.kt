package tvcal.android.storage

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tvcal.kit.ColourMenu
import tvcal.kit.Measurements
import tvcal.kit.MissedPatch
import tvcal.kit.Vec3
import java.time.Instant
import java.util.UUID

class ModelsJsonTests {
    private fun parse(text: String) = StoreJson.parseToJsonElement(text)

    private val t0: Instant = Instant.parse("2025-10-14T20:31:07Z")
    private val t1: Instant = Instant.parse("2025-10-14T21:02:45Z")

    private fun meas(white: Double = 151.2): Measurements = Measurements(
        values = mapOf(
            "W0" to Vec3(143.9, white, 164.7),
            "G50" to Vec3(30.1, 31.6, 34.2),
            "RED" to Vec3(62.3, 32.1, 2.9),
            "GREEN" to Vec3(54.0, 108.2, 18.1),
            "BLUE" to Vec3(27.4, 10.9, 144.0),
        ),
        missed = mapOf("G5" to MissedPatch("5% grey", listOf(0.52, 0.61), 2.0)),
    )

    // MARK: dates and ids

    @Test fun isoDatesAreWholeSecondsUtc() {
        assertEquals("2025-10-14T20:31:07Z", isoString(Instant.parse("2025-10-14T20:31:07.987Z")))
        assertEquals(t0, parseIso("2025-10-14T20:31:07Z"))
        assertEquals(Instant.parse("2025-10-14T20:31:07.500Z"), parseIso("2025-10-14T20:31:07.500Z"))
        assertEquals(t0, parseIso("2025-10-14T22:31:07+02:00"))
        assertNull(parseIso("yesterday"))
    }

    @Test fun uuidsAreUppercase() {
        val id = UUID.fromString("6f1c2a9e-3b7d-4e21-9c55-0a8b7d2e4f13")
        assertEquals("6F1C2A9E-3B7D-4E21-9C55-0A8B7D2E4F13", id.upper)
        val tv = TVRecord(id = id, name = "x", created = t0)
        val o = parse(encodeSorted(TVRecord.serializer(), tv)).jsonObject
        assertEquals("6F1C2A9E-3B7D-4E21-9C55-0A8B7D2E4F13", o["id"]!!.jsonPrimitive.content)
    }

    // MARK: round trips

    @Test fun tvRecordRoundTrip() {
        val tv = TVRecord(
            name = "Bedroom", brand = Brand.SAMSUNG, multipoint = Multipoint.TEN, colourMenu = ColourMenu.RGB_SLIDERS,
            multipointKnown = false, colourMenuKnown = false, panel = PanelType.QDOLED, isDemo = true, created = t0,
        )
        val text = encodeSorted(TVRecord.serializer(), tv)
        assertEquals(tv, StoreDecoding.tvRecord(parse(text)))
        val o = parse(text).jsonObject
        assertEquals(
            listOf("brand", "colourMenu", "colourMenuKnown", "created", "id", "isDemo", "multipoint",
                "multipointKnown", "name", "panel"),
            o.keys.toList(),
        )
        assertEquals("samsung", o["brand"]!!.jsonPrimitive.content)
        assertEquals("samsung", o["colourMenu"]!!.jsonPrimitive.content)
        assertEquals("11", o["multipoint"]!!.jsonPrimitive.content)
        assertEquals("qdoled", o["panel"]!!.jsonPrimitive.content)
        assertEquals("2025-10-14T20:31:07Z", o["created"]!!.jsonPrimitive.content)
        // Pretty, 2-space indent.
        assertTrue(text.startsWith("{\n  \"brand\""))
    }

    @Test fun guideStateRoundTrip() {
        val st = GuideState(
            sessions = listOf(
                GuidedSession(started = t0, before = meas(), after = meas(149.0), finished = t1),
                GuidedSession(started = t1, before = meas(150.0)),
            ),
            check = meas(152.0),
            finder = FinderData(base = meas(), nudged = meas(148.0), overrides = mapOf("_skip" to 0, "G50" to -3, "G90" to 4)),
            progress = GuideProgress(
                step = GuideStep.COLOURS,
                done = listOf(GuideStep.SETUP, GuideStep.BEFORE, GuideStep.BRIGHT),
                colours = listOf("RED", "GREEN"),
                limits = listOf("dark", "BLUE"),
                colour = "BLUE",
                finePid = "G50",
            ),
        )
        val text = encodeSorted(GuideState.serializer(), st)
        val back = StoreDecoding.guideState(parse(text))
        assertEquals(st, back)
        assertEquals(0, back.finder.overrides["_skip"])
        val o = parse(text).jsonObject
        assertEquals(listOf("check", "finder", "progress", "sessions"), o.keys.toList())
        // Missed patches: a null value plus "_missed".
        val w = o["check"]!!.jsonObject
        assertTrue(w.containsKey("G5"))
        assertTrue(w.containsKey("_missed"))
    }

    @Test fun nilOptionalsAreLeftOut() {
        val text = encodeSorted(GuideState.serializer(), GuideState())
        val o = parse(text).jsonObject
        assertFalse(o.containsKey("check"))
        assertEquals(setOf("overrides"), o["finder"]!!.jsonObject.keys)
        val p = o["progress"]!!.jsonObject
        assertFalse(p.containsKey("colour"))
        assertFalse(p.containsKey("finePid"))
        assertEquals("setup", p["step"]!!.jsonPrimitive.content)
        val s = encodeSorted(GuidedSession.serializer(), GuidedSession(started = t0))
        assertEquals(setOf("started"), parse(s).jsonObject.keys)
    }

    // MARK: lenient decoding

    @Test fun tvRecordLenientFields() {
        val tv = StoreDecoding.tvRecord(parse("""
            {"id": "6F1C2A9E-3B7D-4E21-9C55-0A8B7D2E4F13", "name": "Old TV", "brand": "grundig",
             "multipoint": 15, "colourMenu": 3, "panel": "crt", "created": "2024-01-02T03:04:05Z"}
        """))
        assertNotNull(tv)
        tv!!
        assertEquals(Brand.OTHER, tv.brand)
        assertEquals(Multipoint.TWENTY, tv.multipoint)
        assertEquals(ColourMenu.HSL, tv.colourMenu)
        assertEquals(PanelType.UNKNOWN, tv.panel)
        assertTrue(tv.multipointKnown)
        assertTrue(tv.colourMenuKnown)
        assertFalse(tv.isDemo)
        assertEquals(Instant.parse("2024-01-02T03:04:05Z"), tv.created)
    }

    @Test fun tvRecordMissingFieldsTakeDefaults() {
        val before = Instant.now().minusSeconds(1)
        val tv = StoreDecoding.tvRecord(parse("""{"id": "6f1c2a9e-3b7d-4e21-9c55-0a8b7d2e4f13", "name": "TV", "isDemo": null}"""))!!
        assertEquals(UUID.fromString("6F1C2A9E-3B7D-4E21-9C55-0A8B7D2E4F13"), tv.id)
        assertEquals(Brand.OTHER, tv.brand)
        assertEquals(Multipoint.TWENTY, tv.multipoint)
        assertFalse(tv.isDemo)
        assertTrue(!tv.created.isBefore(before))
    }

    @Test fun tvRecordStrictFieldsFailTheRecord() {
        val id = "\"id\": \"6F1C2A9E-3B7D-4E21-9C55-0A8B7D2E4F13\""
        assertNull(StoreDecoding.tvRecord(parse("""{"name": "TV"}""")))
        assertNull(StoreDecoding.tvRecord(parse("""{$id}""")))
        assertNull(StoreDecoding.tvRecord(parse("""{"id": "not-a-uuid", "name": "TV"}""")))
        assertNull(StoreDecoding.tvRecord(parse("""{$id, "name": 5}""")))
        // Swift `try c.decodeIfPresent(Bool.self) ?? true`: a wrong type fails the whole record.
        assertNull(StoreDecoding.tvRecord(parse("""{$id, "name": "TV", "multipointKnown": "yes"}""")))
        assertNull(StoreDecoding.tvRecord(parse("""{$id, "name": "TV", "isDemo": 1}""")))
        assertNull(StoreDecoding.tvRecord(parse("""{$id, "name": "TV", "created": "last week"}""")))
        assertNull(StoreDecoding.tvRecord(parse("""[1, 2]""")))
    }

    @Test fun guideProgressLenient() {
        val p = StoreDecoding.guideProgress(parse("""
            {"step": "warp", "done": ["setup", "teleport"], "colours": ["RED"], "limits": 7, "colour": 5,
             "finePid": "G20", "extra": true}
        """))
        assertEquals(GuideStep.SETUP, p.step)
        assertEquals(emptyList<GuideStep>(), p.done)            // one bad element loses the array
        assertEquals(listOf("RED"), p.colours)
        assertEquals(emptyList<String>(), p.limits)
        assertNull(p.colour)
        assertEquals("G20", p.finePid)
        assertEquals(GuideProgress(), StoreDecoding.guideProgress(parse("\"nope\"")))
    }

    @Test fun guidedSessionLenient() {
        val before = Instant.now().minusSeconds(1)
        val s = StoreDecoding.guidedSession(parse("""
            {"started": "soon", "before": {"W0": [1, 2, 3]}, "after": {"W0": "bright"}, "finished": 12}
        """))
        assertTrue(!s.started.isBefore(before))
        assertEquals(Vec3(1.0, 2.0, 3.0), s.before!!["W0"])
        assertNull(s.after)
        assertNull(s.finished)
    }

    @Test fun finderDataLenient() {
        val f = StoreDecoding.finderData(parse("""{"base": {"W0": [1, 2, 3]}, "overrides": {"G50": "two"}}"""))
        assertNotNull(f.base)
        assertNull(f.nudged)
        assertEquals(emptyMap<String, Int>(), f.overrides)
        val g = StoreDecoding.finderData(parse("""{"overrides": {"_skip": 0, "G50": 2.0}}"""))
        assertEquals(mapOf("_skip" to 0, "G50" to 2), g.overrides)
        assertEquals(FinderData(), StoreDecoding.finderData(parse("[]")))
    }

    @Test fun guideStateLenient() {
        val st = StoreDecoding.guideState(parse("""
            {"sessions": [{"started": "2025-10-14T20:31:07Z"}, 3], "check": [1], "finder": "x",
             "progress": {"step": "check", "done": ["setup"]}}
        """))
        assertEquals(emptyList<GuidedSession>(), st.sessions)    // one non-object loses the list
        assertNull(st.check)
        assertEquals(FinderData(), st.finder)
        assertEquals(GuideStep.CHECK, st.progress.step)
        assertEquals(listOf(GuideStep.SETUP), st.progress.done)
        assertEquals(GuideState(), StoreDecoding.guideState(parse("42")))
        assertEquals(GuideState(), StoreDecoding.guideState(parse("{}")))
    }

    // MARK: files as the iPhone app writes them (JSONEncoder, .prettyPrinted + .sortedKeys, .iso8601)

    private val iosTV = """
        {
          "brand" : "lg",
          "colourMenu" : "hsl",
          "colourMenuKnown" : true,
          "created" : "2025-10-14T20:31:07Z",
          "id" : "6F1C2A9E-3B7D-4E21-9C55-0A8B7D2E4F13",
          "isDemo" : false,
          "multipoint" : 20,
          "multipointKnown" : false,
          "name" : "Living room C5",
          "panel" : "woled"
        }
    """.trimIndent()

    private val iosGuide = """
        {
          "check" : {
            "G50" : [
              30.12,
              31.6,
              34.25
            ],
            "W0" : [
              143.9,
              151.2,
              164.7
            ]
          },
          "finder" : {
            "base" : {
              "G10" : [
                1.1,
                1.2,
                1.3
              ],
              "G5" : null,
              "_missed" : {
                "G5" : {
                  "hold_s" : 2,
                  "name" : "5% grey",
                  "readings_s" : [
                    0.52,
                    0.61
                  ]
                }
              }
            },
            "overrides" : {
              "G50" : -2,
              "_skip" : 0
            }
          },
          "progress" : {
            "colour" : "GREEN",
            "colours" : [
              "RED"
            ],
            "done" : [
              "setup",
              "before",
              "bright",
              "dark",
              "check"
            ],
            "limits" : [
              "BLUE"
            ],
            "step" : "colours"
          },
          "sessions" : [
            {
              "before" : {
                "BLUE" : [
                  27.4,
                  10.9,
                  144
                ],
                "GREEN" : [
                  54,
                  108.2,
                  18.1
                ],
                "RED" : [
                  62.3,
                  32.1,
                  2.9
                ],
                "W0" : [
                  143.9,
                  151.2,
                  164.7
                ]
              },
              "started" : "2025-10-14T20:31:07Z"
            },
            {
              "after" : {
                "W0" : [
                  140,
                  149,
                  160
                ]
              },
              "finished" : "2025-10-14T21:02:45Z",
              "started" : "2025-10-14T21:00:00Z"
            }
          ]
        }
    """.trimIndent()

    @Test fun decodesIphoneTvJson() {
        val tv = StoreDecoding.tvRecord(parse(iosTV))!!
        assertEquals(UUID.fromString("6F1C2A9E-3B7D-4E21-9C55-0A8B7D2E4F13"), tv.id)
        assertEquals("Living room C5", tv.name)
        assertEquals(Brand.LG, tv.brand)
        assertEquals(Multipoint.TWENTY, tv.multipoint)
        assertEquals(ColourMenu.HSL, tv.colourMenu)
        assertFalse(tv.multipointKnown)
        assertTrue(tv.colourMenuKnown)
        assertEquals(PanelType.WOLED, tv.panel)
        assertFalse(tv.isDemo)
        assertEquals(t0, tv.created)
        // And writing it back gives the same fields and values.
        assertEquals(parse(iosTV), parse(encodeSorted(TVRecord.serializer(), tv)))
    }

    @Test fun decodesIphoneGuideJson() {
        val st = StoreDecoding.guideState(parse(iosGuide))
        assertEquals(2, st.sessions.size)
        assertEquals(t0, st.sessions[0].started)
        assertEquals(Vec3(27.4, 10.9, 144.0), st.sessions[0].before!!["BLUE"])
        assertNull(st.sessions[0].after)
        assertNull(st.sessions[0].finished)
        assertEquals(t1, st.sessions[1].finished)
        assertEquals(Vec3(140.0, 149.0, 160.0), st.sessions[1].after!!["W0"])
        assertEquals(Vec3(30.12, 31.6, 34.25), st.check!!["G50"])
        val base = st.finder.base!!
        assertEquals(Vec3(1.1, 1.2, 1.3), base["G10"])
        assertNull(base["G5"])
        assertEquals(2.0, base.missed["G5"]!!.hold, 0.0)
        assertEquals(mapOf("G50" to -2, "_skip" to 0), st.finder.overrides)
        assertEquals(GuideStep.COLOURS, st.progress.step)
        assertEquals(
            listOf(GuideStep.SETUP, GuideStep.BEFORE, GuideStep.BRIGHT, GuideStep.DARK, GuideStep.CHECK),
            st.progress.done,
        )
        assertEquals(listOf("RED"), st.progress.colours)
        assertEquals(listOf("BLUE"), st.progress.limits)
        assertEquals("GREEN", st.progress.colour)
        assertNull(st.progress.finePid)
        // The reference comes from the newest session: its After has W0 but no primaries, so the basis
        // falls back to the older session's Before.
        val ref = st.reference
        assertEquals(149.0, ref.whiteY!!, 0.0)
        assertEquals(listOf(Vec3(62.3, 32.1, 2.9), Vec3(54.0, 108.2, 18.1), Vec3(27.4, 10.9, 144.0)), ref.basis)
        // Re-encoding keeps every key the iPhone wrote.
        val again = parse(encodeSorted(GuideState.serializer(), st)) as JsonObject
        assertEquals((parse(iosGuide) as JsonObject).keys, again.keys)
        assertEquals(st, StoreDecoding.guideState(again))
    }

    // MARK: GuideStep and reference

    @Test fun guideStepOrder() {
        assertEquals(GuideStep.BEFORE, GuideStep.SETUP.next)
        assertEquals(GuideStep.AFTER, GuideStep.COLOURS.next)
        assertNull(GuideStep.AFTER.next)
        assertNull(GuideStep.FINE.next)
        assertFalse(GuideStep.stepper.contains(GuideStep.FINE))
        assertEquals("Fine-tune", GuideStep.FINE.label)
        assertEquals("Bright end", GuideStep.BRIGHT.label)
        assertEquals(GuideStep.DARK, GuideStep.from("dark"))
        assertNull(GuideStep.from("Dark"))
    }

    @Test fun referenceEmpty() {
        val r = GuideState().reference
        assertNull(r.whiteY)
        assertNull(r.basis)
    }
}
