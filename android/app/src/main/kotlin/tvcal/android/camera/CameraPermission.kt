package tvcal.android.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

// The CAMERA runtime permission (Swift AVCaptureDevice.requestAccess in CameraMeter.start). Design §12.
// Owner: agent (b). Working glue already: the Camera setup screen's "Start the camera" goes through it.

fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * Returns an onClick for "Start the camera": with the permission, [start] at once; without it, asks first and
 * then calls [start] whatever the answer (CameraMeter.start checks again and shows CameraMeter.PERMISSION when
 * denied, as the iPhone app does).
 */
@Composable
fun rememberCameraStarter(start: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest = rememberUpdatedState(start)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> latest.value() }
    return remember(context, launcher) {
        {
            if (hasCameraPermission(context)) latest.value() else launcher.launch(Manifest.permission.CAMERA)
        }
    }
}
