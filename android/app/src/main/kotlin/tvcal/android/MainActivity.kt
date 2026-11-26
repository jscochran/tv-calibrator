package tvcal.android

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
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
        setContent {
            CompositionLocalProvider(LocalApp provides (application as TVCalApp)) {
                TVCalTheme {
                    AppRoot()
                }
            }
        }
    }
}
