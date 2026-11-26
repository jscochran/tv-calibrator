package tvcal.android.camera

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.min

// The live camera picture inside CameraViewfinder (Swift CameraPreview, a UIViewRepresentable). Design §10.
// Owner: agent (b).

/**
 * A TextureView (AndroidView) showing the engine's preview stream: on surface available → the engine sizes the
 * SurfaceTexture (engine.previewSize) and attaches it (engine.setPreview); on destroyed → engine.setPreview(null),
 * then return true. Aspect-fit (never cropped), so the 3:4 frame matches the sensor and CameraViewfinder's yellow
 * box marks exactly the measured centre 10%.
 *
 * Rotation: Camera2 already rotates a SurfaceTexture preview stream by SENSOR_ORIENTATION (to the device's
 * natural orientation), so the view only undoes the display rotation (0 in this portrait-only activity) and uses
 * the sensor orientation to know which way round the picture's aspect is.
 */
@Composable
fun CameraPreview(camera: Camera2Meter, modifier: Modifier = Modifier) {
    val engine = camera.engine
    // Re-run the transform when the camera (re)starts: the preview size is known once it has opened.
    val running = camera.running
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        engine.previewSize?.let { texture.setDefaultBufferSize(it.width, it.height) }
                        engine.setPreview(texture)
                        fitPreview(this@apply, engine)
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                        fitPreview(this@apply, engine)
                    }

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        engine.setPreview(null as SurfaceTexture?)
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                }
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> fitPreview(v as TextureView, engine) }
            }
        },
        update = { view ->
            if (running) fitPreview(view, engine)
        },
    )
}

/** Aspect-fits the preview buffer into the view, undoing the display rotation. */
private fun fitPreview(view: TextureView, engine: CameraEngine) {
    val size = engine.previewSize ?: return
    val vw = view.width.toFloat()
    val vh = view.height.toFloat()
    if (vw <= 0f || vh <= 0f) return
    // The picture in the device's natural orientation (the stream is already rotated by the sensor orientation).
    val sideways = engine.sensorOrientation % 180 != 0
    val cw = (if (sideways) size.height else size.width).toFloat()
    val ch = (if (sideways) size.width else size.height).toFloat()
    val degrees = when (view.display?.rotation ?: Surface.ROTATION_0) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
    // The TextureView stretches the buffer over the view: undo that, centre, rotate, then fit.
    val m = Matrix()
    m.setScale(cw / vw, ch / vh)
    m.postTranslate(-cw / 2, -ch / 2)
    if (degrees != 0) m.postRotate(-degrees.toFloat())
    val dw = if (degrees % 180 != 0) ch else cw
    val dh = if (degrees % 180 != 0) cw else ch
    val s = min(vw / dw, vh / dh)
    m.postScale(s, s)
    m.postTranslate(vw / 2, vh / 2)
    view.setTransform(m)
}
