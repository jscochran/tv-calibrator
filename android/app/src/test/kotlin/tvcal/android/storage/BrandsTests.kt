package tvcal.android.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tvcal.kit.ColourMenu
import tvcal.kit.ColourResult
import tvcal.kit.Point2
import tvcal.kit.Report
import tvcal.kit.SignalMode
import tvcal.kit.Vec3

class BrandsTests {
    @Test fun allTwelveBrands() {
        assertEquals(
            listOf("lg", "samsung", "sony", "tcl", "hisense", "panasonic", "philips", "vizio", "toshiba", "sharp", "roku", "other"),
            Brand.entries.map { it.rawValue },
        )
        assertEquals(
            listOf("LG", "Samsung", "Sony", "TCL", "Hisense", "Panasonic", "Philips", "Vizio", "Toshiba", "Sharp", "Roku TV", "Other"),
            Brand.entries.map { it.label },
        )
        for (b in Brand.entries) {
            assertEquals(b, Brand.from(b.rawValue))
            assertEquals(b.rawValue, b.id)
            assertEquals("Gain", b.gainName)
        }
        assertNull(Brand.from("LG"))
    }

    @Test fun whiteBalancePaths() {
        val expected = mapOf(
            Brand.LG to "Picture › Advanced Settings › Colour › White Balance (set it to 2 Point)",
            Brand.SAMSUNG to "Picture › Expert Settings › White Balance (2 Point)",
            Brand.SONY to "Picture › Advanced settings › Colour › White balance",
            Brand.TCL to "Picture › Advanced (or Expert) settings › White balance",
            Brand.HISENSE to "Picture › Picture Mode Settings › White Balance",
            Brand.PANASONIC to "Picture › Advanced Settings › White Balance (2p)",
            Brand.PHILIPS to "Picture › Advanced › Colour › Colour temperature › Custom",
            Brand.VIZIO to "Picture › Picture Mode Edit › Color Calibration › Color Temperature",
            Brand.TOSHIBA to "Picture › Advanced settings › White balance",
            Brand.SHARP to "Picture › Expert settings › White balance",
            Brand.ROKU to "★ button › Advanced picture settings › Expert settings › White balance",
            Brand.OTHER to "the White Balance menu (often under Picture › Advanced or Expert settings)",
        )
        assertEquals(Brand.entries.toSet(), expected.keys)
        for ((b, path) in expected) assertEquals(b.name, path, b.whiteBalancePath)
    }

    @Test fun colourMenuPaths() {
        val expected = mapOf(
            Brand.LG to "Picture › Advanced Settings › Colour › Colour Management System",
            Brand.SAMSUNG to "Picture › Expert Settings › Color Space Settings › Custom",
            Brand.SONY to "Picture › Advanced settings › Colour › Advanced colour adjustment",
            Brand.TCL to "Picture › Advanced settings › Colour tuner",
            Brand.HISENSE to "Picture › Picture Mode Settings › Color Tuner",
            Brand.PANASONIC to "Picture › Advanced Settings › Colour Management",
            Brand.PHILIPS to "Picture › Advanced › Colour › Colour control",
            Brand.VIZIO to "Picture › Picture Mode Edit › Color Calibration › Color Tuner",
            Brand.TOSHIBA to "Picture › Advanced settings › Colour tuner",
            Brand.SHARP to "Picture › Expert settings › Colour tuner",
            Brand.ROKU to "★ button › Advanced picture settings › Expert settings › Color tuning",
            Brand.OTHER to "the colour management menu (often called Colour Tuner, CMS or Colour Space)",
        )
        assertEquals(Brand.entries.toSet(), expected.keys)
        for ((b, path) in expected) assertEquals(b.name, path, b.colourMenuPath)
    }

    @Test fun controlNames() {
        assertEquals("Bias", Brand.SONY.offsetName)
        assertEquals("Cutoff", Brand.PANASONIC.offsetName)
        for (b in Brand.entries - setOf(Brand.SONY, Brand.PANASONIC)) assertEquals("Offset", b.offsetName)
        assertEquals(CmsNames("Tint", "Saturation", "Luminance"), Brand.LG.cmsNames)
        assertEquals("Tint / Saturation / Luminance", Brand.LG.cmsMenuLabel)
        assertEquals("Hue / Saturation / Lightness", Brand.SONY.cmsMenuLabel)
        assertEquals("Hue / Saturation / Brightness", Brand.VIZIO.cmsMenuLabel)
        assertEquals("Hue / Saturation / Luminance", Brand.TCL.cmsMenuLabel)
        assertEquals("Hue / Saturation / Luminance", Brand.OTHER.cmsMenuLabel)
    }

    @Test fun usualMenus() {
        assertEquals(ColourMenu.RGB_SLIDERS, Brand.SAMSUNG.colourMenu)
        for (b in Brand.entries - Brand.SAMSUNG) assertEquals(ColourMenu.HSL, b.colourMenu)
        val twenty = setOf(Brand.LG, Brand.SAMSUNG, Brand.HISENSE)
        val ten = setOf(Brand.SONY, Brand.PANASONIC, Brand.VIZIO, Brand.TCL)
        for (b in Brand.entries) {
            val want = when (b) {
                in twenty -> Multipoint.TWENTY
                in ten -> Multipoint.TEN
                else -> Multipoint.NONE
            }
            assertEquals(b.name, want, b.multipoint)
        }
    }

    @Test fun pictureModes() {
        assertEquals("Samsung TVs don't support Dolby Vision: calibrate HDR10 or HDR10+ instead", Brand.SAMSUNG.pictureMode(SignalMode.DV))
        assertEquals("Filmmaker Mode (or Movie / Cinema)", Brand.SAMSUNG.pictureMode(SignalMode.SDR))
        assertEquals("Dolby Vision Filmmaker Mode (or Cinema Home)", Brand.LG.pictureMode(SignalMode.DV))
        assertEquals("Professional (or Custom)", Brand.SONY.pictureMode(SignalMode.HDR10P))
        assertEquals("Dolby Vision Dark (or Dolby Vision Cinema)", Brand.SONY.pictureMode(SignalMode.DV))
        assertEquals("Dolby Vision Dark", Brand.VIZIO.pictureMode(SignalMode.DV))
        assertEquals("Calibrated Dark", Brand.VIZIO.pictureMode(SignalMode.HDR10))
        assertEquals("Dolby Vision Dark", Brand.ROKU.pictureMode(SignalMode.DV))
        assertEquals("Movie", Brand.ROKU.pictureMode(SignalMode.SDR))
        assertEquals("Filmmaker Mode (or Theater Night)", Brand.HISENSE.pictureMode(SignalMode.SDR))
        assertEquals("Filmmaker Mode (or Movie / Cinema)", Brand.HISENSE.pictureMode(SignalMode.HDR10))
        assertEquals("Movie (or Filmmaker Mode)", Brand.SHARP.pictureMode(SignalMode.SDR))
        assertEquals("Dolby Vision Dark (or Dolby Vision Cinema)", Brand.PANASONIC.pictureMode(SignalMode.DV))
        assertEquals("Filmmaker Mode (or Movie / Cinema)", Brand.OTHER.pictureMode(SignalMode.HDR10P))
        for (b in Brand.entries) for (m in SignalMode.entries) assertTrue(b.pictureMode(m).isNotEmpty())
    }

    @Test fun setupLists() {
        assertEquals(
            listOf(
                "Picture mode: Filmmaker Mode (or Movie / Cinema)",
                "Energy Saving and AI Brightness: Off",
                "Gamma: BT.1886, Colour Temperature: Warm 50",
            ),
            Brand.LG.setup(SignalMode.SDR),
        )
        assertEquals(
            listOf(
                "Energy saving, Adaptive Brightness and Contrast Enhancer: Off",
                "HDR Tone Mapping: Static, ST.2084: 0",
                "Readings jumping around? Turn off Brightness Optimisation and set Local Dimming to Low",
                "HDR10+ mode: the plain setting, not Adaptive / Dynamic",
            ),
            Brand.SAMSUNG.extras(SignalMode.HDR10P),
        )
        assertEquals(
            listOf(
                "Dynamic contrast, Eco / energy saving and the light sensor: Off",
                "Any dynamic tone mapping: Off",
                "Local dimming: Low or Medium",
            ),
            Brand.TCL.extras(SignalMode.HDR10),
        )
        assertEquals(
            listOf("Dynamic contrast, Eco / energy saving and the light sensor: Off", "Colour temperature: Warm (or Low)"),
            Brand.TOSHIBA.extras(SignalMode.SDR),
        )
        assertEquals(listOf("Auto Brightness Control: Off, Contrast Control: Off"), Brand.VIZIO.extras(SignalMode.DV))
        assertEquals(listOf("Dynamic contrast and Action smoothing: Off"), Brand.ROKU.extras(SignalMode.HDR10))
        assertEquals(
            listOf("Ambilight, the light sensor and Dynamic contrast: Off", "HDR Plus / tone mapping: Off or Standard"),
            Brand.PHILIPS.extras(SignalMode.DV),
        )
    }

    @Test fun multipointAndColourMenuLabels() {
        assertEquals(listOf(20, 11, 0), Multipoint.entries.map { it.rawValue })
        assertEquals(Multipoint.TEN, Multipoint.from(11))
        assertNull(Multipoint.from(10))
        assertEquals("20 points (5%, 10% … 100%)", Multipoint.TWENTY.label)
        assertEquals("10 points (10%, 20% … 100%)", Multipoint.TEN.label)
        assertEquals("None (2-point only)", Multipoint.NONE.label)
        assertEquals("Red / Green / Blue sliders", ColourMenu.RGB_SLIDERS.label)
        assertEquals("Hue / Saturation / Luminance", ColourMenu.HSL.label)
        assertEquals("None", ColourMenu.OFF.label)
        assertEquals("0", StoreJson.encodeToString(Multipoint.serializer(), Multipoint.NONE))
        assertEquals(Multipoint.TWENTY, StoreJson.decodeFromString(Multipoint.serializer(), "20"))
    }

    @Test fun panelTypes() {
        assertEquals(listOf("woled", "qdoled", "qdlcd", "lcd", "rgbled", "plasma", "unknown"), PanelType.entries.map { it.rawValue })
        assertNull(PanelType.UNKNOWN.profileKey)
        for (p in PanelType.entries - PanelType.UNKNOWN) assertEquals(p.rawValue, p.profileKey)
        assertEquals("OLED", PanelType.WOLED.label)
        assertEquals("Not sure", PanelType.UNKNOWN.label)
        assertEquals("Readings will be approximate", PanelType.UNKNOWN.detail)
        for (p in PanelType.entries) assertNotNull(p.detail)
        assertEquals(PanelType.QDLCD, PanelType.from("qdlcd"))
    }

    // MARK: GamutCheck

    private fun colour(name: String, sat: Double, lum: Double = 0.0) = ColourResult(
        name = name, xyz = Vec3(0.0, 0.0, 0.0), target = Vec3(0.0, 0.0, 0.0), xy = Point2(0.3, 0.3),
        txy = Point2(0.3, 0.3), sat = sat, hue = 0.0, lum = lum, de = 0.0,
    )

    private fun report(vararg c: ColourResult) = Report(colours = c.toList())

    @Test fun gamutCheckInRange() {
        assertNull(GamutCheck.note(report(), SignalMode.SDR, Brand.LG))
        // Exactly at the thresholds is still in range.
        assertNull(GamutCheck.note(report(colour("RED", 10.0, 40.0), colour("GREEN", 10.0, 40.0), colour("BLUE", -12.0)), SignalMode.SDR, Brand.LG))
        // Only one primary out.
        assertNull(GamutCheck.note(report(colour("RED", 25.0), colour("GREEN", 2.0), colour("BLUE", -20.0)), SignalMode.SDR, Brand.LG))
        // Secondaries don't count.
        assertNull(GamutCheck.note(report(colour("CYAN", 30.0), colour("YELLOW", 30.0), colour("RED", 30.0)), SignalMode.SDR, Brand.LG))
    }

    @Test fun gamutCheckStronger() {
        val note = GamutCheck.note(report(colour("RED", 10.1), colour("green", 0.0, 40.5), colour("BLUE", 0.0)), SignalMode.SDR, Brand.LG)
        assertEquals(
            "Your TV is showing stronger colours than SDR uses. That's usually a setting, not something the colour " +
                "sliders can fix. Check that the picture mode is Filmmaker or Cinema and Picture › Advanced Settings › " +
                "Colour › Colour Gamut is Auto Detect. Playing from an Apple TV or another box? Set it to match the " +
                "video's dynamic range (Apple TV: Settings › Video and Audio › Match Content › Match Dynamic Range), so " +
                "the TV gets the video as it is.",
            note,
        )
    }

    @Test fun gamutCheckWeaker() {
        val note = GamutCheck.note(report(colour("RED", -12.5), colour("GREEN", -13.0), colour("BLUE", 0.0)), SignalMode.HDR10P, Brand.TCL)!!
        assertTrue(note.startsWith("Your TV is showing weaker colours than HDR10+ uses."))
        assertTrue(note.contains("and the Colour Gamut (or Colour Space) setting is Auto. "))
        // Stronger wins when both apply.
        val both = GamutCheck.note(
            report(colour("RED", 11.0), colour("GREEN", 11.0), colour("BLUE", -13.0), colour("Blue", -13.0)),
            SignalMode.DV, Brand.SAMSUNG,
        )!!
        assertTrue(both.startsWith("Your TV is showing stronger colours than Dolby Vision uses."))
        assertTrue(both.contains("Picture › Expert Settings › Color Space is Auto."))
        assertEquals("Live Colour is Off", Brand.SONY.gamutSetting)
    }
}
