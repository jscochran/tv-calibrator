package tvcal.kit

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** X, Y, Z (or R, G, B): three numbers. Encodes as a JSON array [a, b, c], like the Swift SIMD3. */
@Serializable(with = Vec3Serializer::class)
data class Vec3(val x: Double, val y: Double, val z: Double) {
    constructor(a: List<Double>) : this(a[0], a[1], a[2])

    operator fun get(i: Int): Double = when (i) {
        0 -> x
        1 -> y
        2 -> z
        else -> throw IndexOutOfBoundsException("Vec3 index $i")
    }

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun times(o: Vec3) = Vec3(x * o.x, y * o.y, z * o.z)
    operator fun div(s: Double) = Vec3(x / s, y / s, z / s)
    operator fun div(o: Vec3) = Vec3(x / o.x, y / o.y, z / o.z)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    fun toList(): List<Double> = listOf(x, y, z)
    fun sum(): Double = x + y + z
    fun max(): Double = maxOf(x, y, z)
    fun min(): Double = minOf(x, y, z)
    fun map(f: (Double) -> Double) = Vec3(f(x), f(y), f(z))

    /** Copy with one component replaced (Swift's `v[i] = value`). */
    fun with(i: Int, value: Double): Vec3 = when (i) {
        0 -> copy(x = value)
        1 -> copy(y = value)
        2 -> copy(z = value)
        else -> throw IndexOutOfBoundsException("Vec3 index $i")
    }

    companion object {
        val zero = Vec3(0.0, 0.0, 0.0)
        fun repeating(v: Double) = Vec3(v, v, v)
    }
}

operator fun Double.times(v: Vec3) = v * this

object Vec3Serializer : KSerializer<Vec3> {
    private val delegate = ListSerializer(Double.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Vec3) = delegate.serialize(encoder, value.toList())
    override fun deserialize(decoder: Decoder): Vec3 = Vec3(delegate.deserialize(decoder))
}

/** A 3x3 matrix, row-major: m[row][column]. */
typealias Mat3 = List<List<Double>>

/** A point on a 2D chart: CIE xy or u'v'. Encodes as {"x":..,"y":..} like the Swift struct. */
@Serializable
data class Point2(val x: Double, val y: Double)

/**
 * Python's round(): halves go to the even neighbour. The engine's click counts use it, so the port
 * must too, or a 2.5-click change would come out as 3 here and 2 in the lab tool.
 */
fun pyRound(x: Double): Double = Math.rint(x)

/** Python's round(x, digits): rounds the exact binary value, half to even (round(0.15, 1) == 0.1). */
fun pyRound(x: Double, digits: Int): Double {
    if (!x.isFinite()) return x
    return BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
}

/**
 * Swift's `x.rounded()` (schoolbook: halves away from zero, -2.5 -> -3). Kotlin's round() goes to even
 * and Math.round goes toward +infinity, so neither matches.
 */
fun swiftRounded(x: Double): Double {
    if (!x.isFinite()) return x
    return if (x < 0) -kotlin.math.floor(-x + 0.5) else kotlin.math.floor(x + 0.5)
}

/**
 * Swift's `Swift.min(a, b)` / `max`: plain comparison, so a NaN first argument wins and a NaN second
 * argument loses (Kotlin's minOf/maxOf return NaN if either is NaN).
 */
fun swiftMin(a: Double, b: Double): Double = if (b < a) b else a
fun swiftMax(a: Double, b: Double): Double = if (b > a) b else a

fun clamp(x: Double, lo: Double, hi: Double): Double = min(max(x, lo), hi)
fun clamp(x: Int, lo: Int, hi: Int): Int = min(max(x, lo), hi)

/** C fmod: the remainder has the sign of the dividend. */
fun fmod(a: Double, n: Double): Double = a % n

/**
 * C/Swift `String(format: "%.<d>f")`: the exact binary value rounded half-to-even, "-0" kept for
 * small negatives, nan/inf as C prints them. Java's own formatter rounds half-up, so it can't be used
 * where the text must match the Swift app and the Python lab tool.
 */
fun cfmt(x: Double, digits: Int, plus: Boolean = false): String {
    if (x.isNaN()) return "nan"
    if (x.isInfinite()) return if (x > 0) (if (plus) "+inf" else "inf") else "-inf"
    val s = BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).abs().toPlainString()
    val negative = x < 0 || (x == 0.0 && 1.0 / x < 0)
    return if (negative) "-$s" else if (plus) "+$s" else s
}

/** C `%g` (6 significant digits, trailing zeros removed), e.g. 22.5 -> "22.5", 20.0 -> "20". */
fun gfmt(x: Double): String {
    if (x == 0.0) return "0"
    if (!x.isFinite()) return cfmt(x, 0)
    val e = kotlin.math.floor(kotlin.math.log10(abs(x))).toInt()
    if (e < -4 || e >= 6) {
        val s = String.format(java.util.Locale.ROOT, "%.5e", x)
        val (m, exp) = s.split("e")
        val mant = m.trimEnd('0').trimEnd('.')
        val ev = exp.toInt()
        return mant + "e" + (if (ev < 0) "-" else "+") + abs(ev).toString().padStart(2, '0')
    }
    return BigDecimal(x).round(MathContext(6, RoundingMode.HALF_EVEN)).stripTrailingZeros().toPlainString()
}
