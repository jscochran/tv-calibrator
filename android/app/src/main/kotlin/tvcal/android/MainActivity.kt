package tvcal.android

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import tvcal.android.ui.nav.AppRoot
import tvcal.android.ui.theme.TVCalTheme

/**
 * The one activity (Swift TVCalibratorApp's WindowGroup): edge to edge with light system-bar icons on the
 * dark theme, LocalApp provided, then [AppRoot] (the navigation and the intro overlay).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // The intro's base colour (#030F11) behind the first frame, so the hand-over is seamless.
        window.setBackgroundDrawable(ColorDrawable(0xFF030F11.toInt()))
        val app = application as TVCalApp
        setContent {
            // Swift isIdleTimerDisabled while the camera runs: a window flag, independent of KeepScreenOn's view flag.
            val cameraOn = app.camera.running
            LaunchedEffect(cameraOn) {
                if (cameraOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            CompositionLocalProvider(LocalApp provides app) {
                TVCalTheme {
                    AppRoot()
                }
            }
        }
    }

    // The camera is released while the app is in the background and reopened when it comes back (design §11).
    override fun onStart() {
        super.onStart()
        (application as TVCalApp).camera.onAppForeground()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) (application as TVCalApp).camera.onAppBackground()
    }
}
