package tvcal.android.storage

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import tvcal.kit.ColourMenu
import tvcal.kit.Report
import tvcal.kit.SignalMode

// Port of Storage/Brands.swift: menu names and setup advice per TV brand. Copy every string verbatim.

/** Menu names and setup advice per TV brand. JSON: the raw value ("lg", "samsung", ...). */
@Serializable
enum class Brand(val rawValue: String) {
    @SerialName("lg") LG("lg"),
    @SerialName("samsung") SAMSUNG("samsung"),
    @SerialName("sony") SONY("sony"),
    @SerialName("tcl") TCL("tcl"),
    @SerialName("hisense") HISENSE("hisense"),
    @SerialName("panasonic") PANASONIC("panasonic"),
    @SerialName("philips") PHILIPS("philips"),
    @SerialName("vizio") VIZIO("vizio"),
    @SerialName("toshiba") TOSHIBA("toshiba"),
    @SerialName("sharp") SHARP("sharp"),
    @SerialName("roku") ROKU("roku"),
    @SerialName("other") OTHER("other");

    val id: String get() = rawValue

    val label: String
        get() = when (this) {
            LG -> "LG"
            SAMSUNG -> "Samsung"
            SONY -> "Sony"
            TCL -> "TCL"
            HISENSE -> "Hisense"
            PANASONIC -> "Panasonic"
            PHILIPS -> "Philips"
            VIZIO -> "Vizio"
            TOSHIBA -> "Toshiba"
            SHARP -> "Sharp"
            ROKU -> "Roku TV"
            OTHER -> "Other"
        }

    /** What the brand calls the bright-end and dark-end white-balance controls. */
    val gainName: String get() = "Gain"
    val offsetName: String
        get() = when (this) {
            SONY -> "Bias"
            PANASONIC -> "Cutoff"
            else -> "Offset"
        }

    val whiteBalancePath: String
        get() = when (this) {
            LG -> "Picture › Advanced Settings › Colour › White Balance (set it to 2 Point)"
            SAMSUNG -> "Picture › Expert Settings › White Balance (2 Point)"
            SONY -> "Picture › Advanced settings › Colour › White balance"
            TCL -> "Picture › Advanced (or Expert) settings › White balance"
            HISENSE -> "Picture › Picture Mode Settings › White Balance"
            PANASONIC -> "Picture › Advanced Settings › White Balance (2p)"
            PHILIPS -> "Picture › Advanced › Colour › Colour temperature › Custom"
            VIZIO -> "Picture › Picture Mode Edit › Color Calibration › Color Temperature"
            TOSHIBA -> "Picture › Advanced settings › White balance"
            SHARP -> "Picture › Expert settings › White balance"
            ROKU -> "★ button › Advanced picture settings › Expert settings › White balance"
            OTHER -> "the White Balance menu (often under Picture › Advanced or Expert settings)"
        }

    val colourMenuPath: String
        get() = when (this) {
            LG -> "Picture › Advanced Settings › Colour › Colour Management System"
            SAMSUNG -> "Picture › Expert Settings › Color Space Settings › Custom"
            SONY -> "Picture › Advanced settings › Colour › Advanced colour adjustment"
            TCL -> "Picture › Advanced settings › Colour tuner"
            HISENSE -> "Picture › Picture Mode Settings › Color Tuner"
            PANASONIC -> "Picture › Advanced Settings › Colour Management"
            PHILIPS -> "Picture › Advanced › Colour › Colour control"
            VIZIO -> "Picture › Picture Mode Edit › Color Calibration › Color Tuner"
            TOSHIBA -> "Picture › Advanced settings › Colour tuner"
            SHARP -> "Picture › Expert settings › Colour tuner"
            ROKU -> "★ button › Advanced picture settings › Expert settings › Color tuning"
            OTHER -> "the colour management menu (often called Colour Tuner, CMS or Colour Space)"
        }

    /** The usual colour menu type for the brand (the user confirms it). */
    val colourMenu: ColourMenu get() = if (this == SAMSUNG) ColourMenu.RGB_SLIDERS else ColourMenu.HSL

    /** The usual multipoint white balance for the brand (the user confirms it). */
    val multipoint: Multipoint
        get() = when (this) {
            LG, SAMSUNG, HISENSE -> Multipoint.TWENTY
            SONY, PANASONIC, VIZIO, TCL -> Multipoint.TEN
            PHILIPS, TOSHIBA, SHARP, ROKU, OTHER -> Multipoint.NONE
        }

    /** The calibrated picture mode, per format. Cases in the Swift switch's order (the first match wins). */
    fun pictureMode(mode: SignalMode): String = when {
        this == SAMSUNG && mode == SignalMode.DV ->
            "Samsung TVs don't support Dolby Vision: calibrate HDR10 or HDR10+ instead"
        this == LG && mode == SignalMode.DV -> "Dolby Vision Filmmaker Mode (or Cinema Home)"
        this == SONY && (mode == SignalMode.SDR || mode == SignalMode.HDR10 || mode == SignalMode.HDR10P) ->
            "Professional (or Custom)"
        this == VIZIO && mode == SignalMode.DV -> "Dolby Vision Dark"
        this == VIZIO -> "Calibrated Dark"
        this == ROKU -> if (mode == SignalMode.DV) "Dolby Vision Dark" else "Movie"
        this == PANASONIC && mode == SignalMode.DV -> "Dolby Vision Dark (or Dolby Vision Cinema)"
        this == HISENSE && mode == SignalMode.SDR -> "Filmmaker Mode (or Theater Night)"
        this == SHARP && mode == SignalMode.SDR -> "Movie (or Filmmaker Mode)"
        mode == SignalMode.DV -> "Dolby Vision Dark (or Dolby Vision Cinema)"
        mode == SignalMode.SDR -> "Filmmaker Mode (or Movie / Cinema)"
        else -> "Filmmaker Mode (or Movie / Cinema)"   // (_, .hdr10), (_, .hdr10p)
    }

    /** Picture settings to make before measuring (after the picture mode). */
    fun extras(mode: SignalMode): List<String> {
        val items = mutableListOf<String>()
        when (this) {
            LG -> {
                items += "Energy Saving and AI Brightness: Off"
                if (mode == SignalMode.SDR) items += "Gamma: BT.1886, Colour Temperature: Warm 50"
                if (mode.isHDR) items += "Dynamic Tone Mapping: Off"
            }
            SAMSUNG -> {
                items += "Energy saving, Adaptive Brightness and Contrast Enhancer: Off"
                if (mode == SignalMode.SDR) items += "Colour Tone: Warm2, Gamma: BT.1886"
                if (mode.isHDR) items += "HDR Tone Mapping: Static, ST.2084: 0"
                items += "Readings jumping around? Turn off Brightness Optimisation and set Local Dimming to Low"
            }
            SONY -> {
                items += "Light sensor, Live colour and Advanced contrast enhancer: Off"
                if (mode == SignalMode.SDR) items += "Colour temperature: Expert 1"
                if (mode.isHDR) items += "HDR tone mapping: Off"
            }
            TCL, HISENSE, TOSHIBA, SHARP -> {
                items += "Dynamic contrast, Eco / energy saving and the light sensor: Off"
                if (mode == SignalMode.SDR) items += "Colour temperature: Warm (or Low)"
                if (mode.isHDR) items += "Any dynamic tone mapping: Off"
                if (this == HISENSE || this == TCL) items += "Local dimming: Low or Medium"
            }
            PANASONIC -> {
                items += "Ambient sensor and Dynamic Contrast: Off"
                if (mode == SignalMode.SDR) items += "Colour Temperature: Warm2"
                if (mode.isHDR) items += "HDR Brightness Enhancer / Dynamic HDR Effect: Off"
            }
            PHILIPS -> {
                items += "Ambilight, the light sensor and Dynamic contrast: Off"
                if (mode == SignalMode.SDR) items += "Colour temperature: Warm"
                if (mode.isHDR) items += "HDR Plus / tone mapping: Off or Standard"
            }
            VIZIO -> {
                items += "Auto Brightness Control: Off, Contrast Control: Off"
                if (mode == SignalMode.SDR) items += "Color Temperature: Normal (Vizio's is the film standard)"
            }
            ROKU -> {
                items += "Dynamic contrast and Action smoothing: Off"
                if (mode == SignalMode.SDR) items += "Color temperature: Warm"
            }
            OTHER -> {
                items += "Energy saving, light sensors and contrast boosters: Off"
                if (mode == SignalMode.SDR) items += "Colour temperature: Warm"
                if (mode.isHDR) items += "Dynamic tone mapping: Off"
            }
        }
        if (mode == SignalMode.HDR10P) items += "HDR10+ mode: the plain setting, not Adaptive / Dynamic"
        return items.toList()
    }

    /** Everything to set before measuring, as one list (picture mode first). */
    fun setup(mode: SignalMode): List<String> = listOf("Picture mode: ${pictureMode(mode)}") + extras(mode)

    /** Where the TV's colour gamut setting is, and what it should be. */
    val gamutSetting: String
        get() = when (this) {
            LG -> "Picture › Advanced Settings › Colour › Colour Gamut is Auto Detect"
            SAMSUNG -> "Picture › Expert Settings › Color Space is Auto"
            SONY -> "Live Colour is Off"
            else -> "the Colour Gamut (or Colour Space) setting is Auto"
        }

    /**
     * What this brand calls the colour menu's three controls (Swift tuple `cmsNames`): LG says Tint and
     * Luminance, Sony Lightness; most others say Hue, Saturation and Luminance.
     */
    val cmsNames: CmsNames
        get() = when (this) {
            LG -> CmsNames("Tint", "Saturation", "Luminance")
            SONY -> CmsNames("Hue", "Saturation", "Lightness")
            VIZIO -> CmsNames("Hue", "Saturation", "Brightness")
            else -> CmsNames("Hue", "Saturation", "Luminance")
        }

    /** "Tint / Saturation / Luminance". */
    val cmsMenuLabel: String get() = cmsNames.let { "${it.hue} / ${it.saturation} / ${it.brightness}" }

    companion object {
        fun from(raw: String): Brand? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** Swift `(hue: String, saturation: String, brightness: String)`. */
data class CmsNames(val hue: String, val saturation: String, val brightness: String)

/** Multipoint white balance menus. JSON: the Int raw value 20 / 11 / 0. */
@Serializable(with = MultipointSerializer::class)
enum class Multipoint(val rawValue: Int) {
    TWENTY(20), TEN(11), NONE(0);

    val id: Int get() = rawValue
    val label: String
        get() = when (this) {
            TWENTY -> "20 points (5%, 10% … 100%)"
            TEN -> "10 points (10%, 20% … 100%)"
            NONE -> "None (2-point only)"
        }

    companion object {
        fun from(raw: Int): Multipoint? = entries.firstOrNull { it.rawValue == raw }
    }
}

object MultipointSerializer : KSerializer<Multipoint> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("tvcal.Multipoint", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: Multipoint) = encoder.encodeInt(value.rawValue)
    override fun deserialize(decoder: Decoder): Multipoint {
        val raw = decoder.decodeInt()
        return Multipoint.from(raw) ?: throw IllegalArgumentException("Unknown multipoint $raw")
    }
}

/** Swift `extension ColourMenu { var label }`. */
val ColourMenu.label: String
    get() = when (this) {
        ColourMenu.RGB_SLIDERS -> "Red / Green / Blue sliders"
        ColourMenu.HSL -> "Hue / Saturation / Luminance"
        ColourMenu.OFF -> "None"
    }

/** Swatch colours (sRGB 0-1) for the six colour patches, keyed by colour id. */
val colourSwatch: Map<String, Triple<Double, Double, Double>> = mapOf(
    "RED" to Triple(0.90, 0.27, 0.24), "GREEN" to Triple(0.24, 0.78, 0.44), "BLUE" to Triple(0.25, 0.47, 0.91),
    "CYAN" to Triple(0.25, 0.82, 0.85), "MAGENTA" to Triple(0.84, 0.36, 0.82), "YELLOW" to Triple(0.91, 0.85, 0.29),
)

/**
 * The kind of screen: each makes white and colours from a different mix of light, and the camera needs a
 * matching conversion. JSON: the raw value.
 */
@Serializable
enum class PanelType(val rawValue: String) {
    @SerialName("woled") WOLED("woled"),
    @SerialName("qdoled") QDOLED("qdoled"),
    @SerialName("qdlcd") QDLCD("qdlcd"),
    @SerialName("lcd") LCD("lcd"),
    @SerialName("rgbled") RGBLED("rgbled"),
    @SerialName("plasma") PLASMA("plasma"),
    @SerialName("unknown") UNKNOWN("unknown");

    val id: String get() = rawValue
    val label: String
        get() = when (this) {
            WOLED -> "OLED"
            QDOLED -> "QD-OLED"
            QDLCD -> "Quantum dot LED or mini-LED"
            LCD -> "LED (LCD)"
            RGBLED -> "Micro RGB or RGB mini-LED"
            PLASMA -> "Plasma"
            UNKNOWN -> "Not sure"
        }

    /** Examples, to help pick. */
    val detail: String?
        get() = when (this) {
            WOLED -> "LG OLED, Panasonic, Philips, most Sony OLEDs"
            QDOLED -> "Samsung S90 and S95, Sony A95"
            QDLCD -> "QLED, Neo QLED, QNED, Bravia 7 and 9, TCL and Hisense mini-LED"
            LCD -> "Other LED TVs and most monitors"
            RGBLED -> "Hisense, Samsung and Sony RGB backlights"
            PLASMA -> "Panasonic, Pioneer, Samsung, LG plasma"
            UNKNOWN -> "Readings will be approximate"
        }

    /** The key in a camera profile's `panels`, null when not known. */
    val profileKey: String? get() = if (this == UNKNOWN) null else rawValue

    companion object {
        fun from(raw: String): PanelType? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * Colours far outside their targets, all the same way, mean a setting (the TV's colour gamut, or the
 * player's output) rather than something the colour sliders can fix.
 */
object GamutCheck {
    /** A note for the Before results and the Colours step, or null when the colours are in range. */
    fun note(report: Report, mode: SignalMode, brand: Brand): String? {
        val primaries = report.colours.filter { it.name.uppercase() in PRIMARIES }
        // Stronger: well outside the target, or far brighter than it next to the TV's own white.
        val over = primaries.count { it.sat > 10 || it.lum > 40 }
        val under = primaries.count { it.sat < -12 }
        if (!(over >= 2 || under >= 2)) return null
        val how = if (over >= 2) "stronger" else "weaker"
        return "Your TV is showing $how colours than ${mode.label} uses. That's usually a setting, not something " +
            "the colour sliders can fix. Check that the picture mode is Filmmaker or Cinema and ${brand.gamutSetting}. " +
            "Playing from an Apple TV or another box? Set it to match the video's dynamic range (Apple TV: Settings › " +
            "Video and Audio › Match Content › Match Dynamic Range), so the TV gets the video as it is."
    }

    private val PRIMARIES = setOf("RED", "GREEN", "BLUE")
}
