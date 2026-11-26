package tvcal.android.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.ColorSpaceTransform
import tvcal.kit.CameraProfile
import tvcal.kit.Mat3

// The thin Android adapter for ProvisionalProfile (design §13.6): reads the meter camera's colour keys into
// CameraColourData, and serves ProfileService as its ApproximateSource. Owner: agent (c).

object CameraColourReader {
    /**
     * Reads SENSOR_REFERENCE_ILLUMINANT1/2, SENSOR_COLOR_TRANSFORM1/2, SENSOR_FORWARD_MATRIX1/2,
     * SENSOR_CALIBRATION_TRANSFORM1/2, SENSOR_INFO_SENSITIVITY_RANGE, LENS_INFO_AVAILABLE_APERTURES,
     * SENSOR_BLACK_LEVEL_PATTERN and SENSOR_INFO_WHITE_LEVEL of [camera]. Null when illuminant 1 or colour
     * transform 1 is missing (they need the CAMERA permission on Android 10+).
     *
     * The f-number and the recorded settings follow what the engine locks when it starts without a recorded
     * aperture (AppModel.startCamera with no profile: ApertureLock.defaultFNumber): a fixed lens → its only
     * aperture and settings aperture null; a variable one → the locked aperture, recorded in the settings so
     * later starts lock the same one (the scale K depends on it).
     */
    fun read(c: CameraCharacteristics, camera: CameraCandidate): CameraColourData? {
        val illuminant1 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: return null
        val colorTransform1 = matrix(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)) ?: return null
        // SENSOR_REFERENCE_ILLUMINANT2 is a Byte key; 0 means "no second calibration".
        val illuminant2 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()?.takeIf { it > 0 }
        val colorTransform2 = if (illuminant2 == null) null else matrix(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2))

        val iso = (camera.isoMin ?: c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.lower)?.toDouble()
            ?: return null
        val apertures = camera.apertures.ifEmpty {
            c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.map { it.toDouble() } ?: emptyList()
        }
        val locked = ApertureLock.choose(apertures, ApertureLock.defaultFNumber)
        val fNumber = locked ?: apertures.firstOrNull() ?: return null

        val pattern = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN) ?: return null
        val black = (0 until 4).map { i -> pattern.getOffsetForIndex(i and 1, i shr 1).toDouble() }.average()
        val white = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.toDouble() ?: return null

        return CameraColourData(
            illuminant1 = illuminant1,
            illuminant2 = if (colorTransform2 == null) null else illuminant2,
            colorTransform1 = colorTransform1,
            colorTransform2 = colorTransform2,
            forwardMatrix1 = matrix(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)),
            forwardMatrix2 = if (colorTransform2 == null) null else matrix(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)),
            calibration1 = matrix(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)),
            calibration2 = if (colorTransform2 == null) null else matrix(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)),
            iso = iso,
            fNumber = fNumber,
            black = black,
            white = white,
            settings = CameraSelection.settingsFor(camera, locked),
        )
    }

    /**
     * A ColorSpaceTransform as a row-major Mat3 (Rational → Double). Null when missing, or all zeros (some HALs
     * fill absent matrices with zeros).
     */
    fun matrix(t: ColorSpaceTransform?): Mat3? {
        if (t == null) return null
        // ColorSpaceTransform.getElement(column, row).
        val m = List(3) { r -> List(3) { col -> t.getElement(col, r).toDouble() } }
        return if (m.all { row -> row.all { it == 0.0 } }) null else m
    }
}

/**
 * ProfileService's approximate source on the phone: CameraCatalog + CameraSelection pick the meter's camera
 * (the same one the engine opens); NeedsPermission without CAMERA; Unavailable when the phone has no usable
 * camera or colour data; else ProvisionalProfile.build. The first Ready profile is cached.
 */
class AndroidApproximateSource(
    private val context: Context,
    /** DeviceModel.identifier, e.g. "samsung/e2q". */
    private val model: String,
    /** DeviceModel.name, e.g. "SM-S926U1". */
    private val deviceName: String,
) : ApproximateSource {
    @Volatile
    private var cached: CameraProfile? = null

    override fun load(): ApproximateResult {
        cached?.let { return ApproximateResult.Ready(it) }
        return try {
            val manager = context.getSystemService(CameraManager::class.java)
                ?: return ApproximateResult.Unavailable("no camera service")
            val candidates = CameraCatalog.read(manager)
            if (!hasCameraPermission(context)) {
                // Keys in getKeysNeedingPermission() read as null now, so only facing + capabilities count
                // (as Camera2Meter.available); the real choice is made once the permission is granted.
                if (candidates.any { it.facingBack && it.raw && it.manualSensor }) return ApproximateResult.NeedsPermission
                val why = (CameraSelection.choose(candidates) as? CameraChoice.Unsupported)?.message ?: "no usable camera"
                return ApproximateResult.Unavailable(why)
            }
            // With the permission the catalog is complete: the same choice and settings as the engine's.
            val camera = when (val choice = CameraSelection.choose(candidates)) {
                is CameraChoice.Unsupported -> return ApproximateResult.Unavailable(choice.message)
                is CameraChoice.Use -> choice.camera
            }
            val chars = manager.getCameraCharacteristics(camera.id)
            val data = CameraColourReader.read(chars, camera) ?: return ApproximateResult.Unavailable("no colour data")
            if (ProvisionalProfile.isGeneric(data)) {
                return ApproximateResult.Unavailable("the camera reports generic (sRGB) colour data, not its own")
            }
            val profile = ProvisionalProfile.build(data, model, deviceName)
                ?: return ApproximateResult.Unavailable("no colour data")
            cached = profile
            ApproximateResult.Ready(profile)
        } catch (e: SecurityException) {
            ApproximateResult.NeedsPermission
        } catch (e: Exception) {
            // CameraAccessException, IllegalArgumentException (an id that went away), …
            ApproximateResult.Unavailable(e.message ?: e.javaClass.simpleName)
        }
    }
}
