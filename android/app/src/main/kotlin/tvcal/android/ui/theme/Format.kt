package tvcal.android.ui.theme

import tvcal.kit.cfmt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Number and date text. Every number shown to the user goes through cfmt (C/Swift "%.Nf" rounding);
// never String.format or "%.1f".format.

/** Swift fmt(v, digits): "%.<digits>f", or "–" for nil or a non-finite value. */
fun fmt(v: Double?, digits: Int = 1): String {
    if (v == null || !v.isFinite()) return "–"
    return cfmt(v, digits)
}

/** Swift signed(v, digits): "%+.<digits>f", or "–" for nil or a non-finite value. */
fun signed(v: Double?, digits: Int = 1): String {
    if (v == null || !v.isFinite()) return "–"
    return cfmt(v, digits, plus = true)
}

/** "-1.1" -> "−1.1": a real minus sign (U+2212) for big readings. */
fun typographicMinus(s: String): String = s.replace("-", "−")

/** Swift `date.formatted(date: .abbreviated, time: .omitted)`, e.g. "25 Nov 2026" (device locale). */
fun dateAbbreviated(i: Instant): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(i)

/** Swift `date.formatted(date: .long, time: .omitted)`, e.g. "25 November 2026". */
fun dateLong(i: Instant): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withZone(ZoneId.systemDefault()).format(i)

/** Swift `date.formatted(date: .abbreviated, time: .shortened)`, e.g. "25 Nov 2026, 14:05". */
fun dateTimeShort(i: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(i)
