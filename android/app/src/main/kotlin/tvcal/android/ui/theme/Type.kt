package tvcal.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Port of the type scale in Views/DesignSystem.swift (Figma 03 Colour and type styles). Sizes are in sp,
// so they follow the user's font size the way Dynamic Type scales the iPhone's. The typeface is the system
// font; Swift's SF Pro Rounded readings become the system font with tabular digits ("tnum").

object Fonts {
    /** The app's typeface (the system font) at a design size. */
    fun brand(size: Float, weight: FontWeight = FontWeight.Normal): TextStyle =
        TextStyle(fontSize = size.sp, fontWeight = weight)

    /** 28 regular: screen headlines ("See it the way it was made."). */
    val display = brand(28f)
    /** 25 regular: walkthrough page titles. */
    val pageTitle = brand(25f)
    /** 22 semibold: a step's title ("Measure your starting point"). */
    val stepTitle = brand(22f, FontWeight.SemiBold)
    /** 23 regular: card headings ("1 SDR"). */
    val cardTitle = brand(23f)
    /** 18 semibold: section headings ("Your screens"). */
    val section = brand(18f, FontWeight.SemiBold)
    /** 17 semibold: emphasised titles, video names. */
    val headlineBrand = brand(17f, FontWeight.SemiBold)
    /** 15: body text; semibold for names and links. */
    val bodyBrand = brand(15f)
    val bodyStrong = brand(15f, FontWeight.SemiBold)
    /** 14: format card descriptions. */
    val callout14 = brand(14f)
    /** 13: secondary text; semibold for eyebrows and small links. */
    val captionBrand = brand(13f)
    val captionStrong = brand(13f, FontWeight.SemiBold)
    /** 12: small labels; bold for format badges. */
    val small = brand(12f)
    val badge = brand(12f, FontWeight.Bold)
    /** 11 and 10: step numbers, format progress labels. */
    val tiny = brand(11f)
    val micro = brand(10f)

    /** Live readings, readable from across the room (numeric scale: 56 / 40 / 30): semibold, tabular digits. */
    fun reading(size: Float = 40f): TextStyle = brand(size, FontWeight.SemiBold).monospacedDigit()

    // The Swift View style modifiers, as complete styles (colour included).

    /** Swift displayStyle(): 28 pt, tracking -0.6, line spacing -2, white. */
    val displayStyle = display.copy(letterSpacing = (-0.6).sp, lineHeight = 31.sp, color = Color.White)
    /** Swift pageTitleStyle(): 25 pt, tracking -0.6, white. */
    val pageTitleStyle = pageTitle.copy(letterSpacing = (-0.6).sp, color = Color.White)
    /** Swift stepTitleStyle(): 22 pt semibold, tracking -0.4, white (wraps as needed). */
    val stepTitleStyle = stepTitle.copy(letterSpacing = (-0.4).sp, color = Color.White)
}

/** Swift `.monospacedDigit()`. */
fun TextStyle.monospacedDigit(): TextStyle = copy(fontFeatureSettings = "tnum")

/** Swift `.secondaryText()`: the secondary text colour. */
fun TextStyle.secondary(): TextStyle = copy(color = Theme.textSecondary)

fun TextStyle.white(): TextStyle = copy(color = Color.White)

fun TextStyle.colour(c: Color): TextStyle = copy(color = c)

/** Swift `.tracking(t)` (points -> sp). */
fun TextStyle.tracking(t: Float): TextStyle = copy(letterSpacing = t.sp)

/**
 * Swift `.lineSpacing(extra)`: the font's natural line height (about 1.2 x the size) plus [extra] points.
 */
fun TextStyle.lineSpacing(extra: Float): TextStyle = copy(lineHeight = (fontSize.value * 1.2f + extra).sp)
