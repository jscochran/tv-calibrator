package tvcal.kit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Camera profiles: what turns a phone model's raw sensor numbers into real colour (XYZ). Each one is
// trained with the lab tool against a Display Plus HL, on several TVs, and published in server/profiles.
// A profile only holds for the camera settings it was trained with. Same JSON format as the iPhone app.

/** JSON settings shared by the profile files, the catalog and the fixtures. */
val ProfileJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    prettyPrint = true
}

/** The locked camera settings a profile was trained with. */
@Serializable
data class CameraSettings(
    val iso: Double? = null,
    /** RAW format, e.g. "4032x3024 grb4" (iPhone) or "4080x3060 RGGB" (Android). */
    val format: String? = null,
    /** e.g. "Back Camera" (iPhone) or "Back camera 0" (Android). */
    val lens: String? = null,
    /** f-number. Only on lenses with a variable aperture; null on fixed-aperture lenses. */
    val aperture: Double? = null,
) {
    /** Which settings differ from what the profile expects. Empty if they match. */
    fun mismatches(got: CameraSettings): List<String> {
        val diffs = mutableListOf<String>()
        if (iso != null && got.iso != null && abs(iso - got.iso) > 0.5) {
            diffs += "ISO: profile ${cfmt(iso, 0)}, now ${cfmt(got.iso, 0)}"
        }
        if (format != null && got.format != null && format != got.format) {
            diffs += "RAW format: profile $format, now ${got.format}"
        }
        if (lens != null && got.lens != null && lens != got.lens) diffs += "lens: profile $lens, now ${got.lens}"
        if (aperture != null && got.aperture != null && abs(aperture - got.aperture) > 0.05) {
            diffs += "aperture: profile f/${cfmt(aperture, 2)}, now f/${cfmt(got.aperture, 2)}"
        }
        return diffs
    }
}

/** How well a profile matched the Display Plus HL in training. */
@Serializable
data class ProfileStats(
    /** Average dE against the HL on the training TVs. */
    val deAvg: Double? = null,
    /** Estimated average dE on a TV it has never seen (leave-one-TV-out). */
    val unseen: Double? = null,
    /** TVs it was trained on. */
    val tvs: List<String> = emptyList(),
)

/**
 * The conversion for one type of screen (panel technology, e.g. "woled", "qdlcd", "rgbled"). Screens
 * make white from different mixes of light, and a camera isn't a perfect colorimeter, so one
 * conversion can't serve them all.
 */
@Serializable
data class PanelMatrix(
    /** Camera RGB -> XYZ for this type of screen. */
    val matrix: Mat3,
    /**
     * Camera RGB -> XYZ for greys and near-greys, when they need their own (WOLED's white subpixel).
     * Blended with `matrix` between 0.02 and 0.04 from D65 in x, y.
     */
    val neutral: Mat3? = null,
    val deAvg: Double? = null,
    /** True when worked out from another model trained on this type, not measured with this one. */
    val derived: Boolean? = null,
)

private fun Mat3.is3x3(): Boolean = size == 3 && all { it.size == 3 }

/** One phone model's profile, as published on the profile server. */
@Serializable
data class CameraProfile(
    val format: Int = 1,
    /** Hardware model identifier, e.g. "iPhone18,1" or, on Android, "samsung/SM-S926U". */
    val model: String,
    /** Marketing name, e.g. "Galaxy S24+". */
    val name: String,
    /** Increases each time the profile is retrained. */
    val version: Int,
    /** Camera RGB (dark-corrected sensor signal per second) -> XYZ. */
    val matrix: Mat3,
    val camera: CameraSettings = CameraSettings(),
    val stats: ProfileStats = ProfileStats(),
    val trained: String? = null,
    /** One conversion per type of screen this model knows; `matrix` is the general one for any other. */
    val panels: Map<String, PanelMatrix>? = null,
) {
    /** The conversion for a type of screen, when this profile has one. */
    fun panel(key: String?): PanelMatrix? {
        if (key == null) return null
        val p = panels?.get(key) ?: return null
        return if (p.matrix.is3x3()) p else null
    }

    /** Camera RGB -> XYZ on a given type of screen (the general conversion when it isn't known). */
    fun xyz(cameraRGB: Vec3, panel: String?): Vec3 {
        val p = panel(panel) ?: return ColourMath.matvec(matrix, cameraRGB)
        val colour = ColourMath.matvec(p.matrix, cameraRGB)
        val n = p.neutral
        if (n == null || !n.is3x3()) return colour
        val s = colour.x + colour.y + colour.z
        if (!(colour.y > 0 && s > 0)) return colour
        val dx = colour.x / s - 0.3127
        val dy = colour.y / s - 0.3290
        val d = sqrt(dx * dx + dy * dy)
        val w = min(1.0, max(0.0, (d - 0.02) / 0.02))      // 0 near grey (neutral), 1 for colours
        val neutral = ColourMath.matvec(n, cameraRGB)
        return neutral * (1 - w) + colour * w
    }

    val isValid: Boolean get() = matrix.is3x3() && matrix.all { row -> row.all { it.isFinite() } }

    /** Camera RGB -> XYZ. */
    fun xyz(cameraRGB: Vec3): Vec3 = ColourMath.matvec(matrix, cameraRGB)

    /** "Excellent" ... for the unseen-TV estimate (or the training average). */
    val accuracyWord: String
        get() {
            val de = stats.unseen ?: stats.deAvg ?: return "Not rated"
            return if (de < 1) "Excellent" else if (de < 2) "Very good" else if (de < 3) "Good" else "Approximate"
        }
}

/** The profile server's index: which phone models are supported. */
@Serializable
data class ProfileCatalog(
    val format: Int,
    val updated: String,
    /** The oldest app catalog format that can read this one. */
    val minAppFormat: Int? = null,
    val profiles: List<Entry>,
) {
    @Serializable
    data class Entry(
        val model: String,
        val name: String,
        val version: Int,
        /** Path of the profile file, relative to the catalog. */
        val file: String,
        /** SHA-256 of the profile file, hex. */
        val sha256: String,
        val deAvg: Double? = null,
        val unseen: Double? = null,
        val tvs: Int? = null,
    )

    fun entry(model: String): Entry? = profiles.firstOrNull { it.model == model }
}

/**
 * The profiles built into the app (builtin-profiles.json, written by tools/build_catalog.py).
 * `standard` names the profile used on phones that have no profile of their own yet.
 */
@Serializable
data class BuiltInProfiles(
    val format: Int = 1,
    val standard: String? = null,
    val profiles: List<CameraProfile> = emptyList(),
) {
    /** This model's own profile, if one is built in. */
    fun profile(model: String): CameraProfile? =
        profiles.filter { it.model == model && it.isValid }.maxByOrNull { it.version }

    /** The profile to use when a model has none of its own. */
    val standardProfile: CameraProfile? get() = standard?.let { profile(it) }
}
