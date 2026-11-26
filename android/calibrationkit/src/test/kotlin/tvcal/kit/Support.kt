package tvcal.kit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.fail
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Fixtures are written by tools/make_fixtures.py from the lab tool's Python engine, and shared with
 * the Swift tests (CalibrationKit/Tests/CalibrationKitTests/Fixtures).
 */
object Fixture {
    private val dir: File by lazy {
        File(System.getProperty("fixtures") ?: error("The 'fixtures' system property isn't set (run via Gradle)."))
    }

    fun load(name: String): JsonElement = Json.parseToJsonElement(File(dir, "$name.json").readText())

    fun text(name: String): String = File(dir, "$name.json").readText()
}

fun num(e: JsonElement?): Double? = when (e) {
    null, JsonNull -> null
    is JsonPrimitive -> if (e.isString) null else e.doubleOrNull
    else -> null
}

fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
fun bool(e: JsonElement?): Boolean? = (e as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
fun dict(e: JsonElement?): JsonObject = e as? JsonObject ?: JsonObject(emptyMap())
fun list(e: JsonElement?): List<JsonElement> = e as? JsonArray ?: emptyList()
fun vec3(e: JsonElement?): Vec3 = list(e).let { Vec3(num(it[0])!!, num(it[1])!!, num(it[2])!!) }
fun point(e: JsonElement?): Point2 = list(e).let { Point2(num(it[0])!!, num(it[1])!!) }

/** Equal within a relative tolerance (and an absolute one near zero). null == null; NaN matches null. */
fun assertClose(a0: Double?, b0: Double?, tol: Double = 1e-6, message: String = "") {
    val a = if (a0?.isNaN() == true) null else a0
    val b = if (b0?.isNaN() == true) null else b0
    when {
        a == null && b == null -> return
        a != null && b != null -> {
            if (abs(a - b) > tol * max(1.0, max(abs(a), abs(b)))) fail("$message: $a vs $b")
        }
        else -> fail("$message: $a vs $b")
    }
}

fun assertClose(a: Vec3, b: Vec3, tol: Double = 1e-6, message: String = "") {
    for (i in 0 until 3) assertClose(a[i], b[i], tol, "$message[$i]")
}

fun assertClose(a: Point2, b: Point2, tol: Double = 1e-6, message: String = "") {
    assertClose(a.x, b.x, tol, "$message.x")
    assertClose(a.y, b.y, tol, "$message.y")
}
