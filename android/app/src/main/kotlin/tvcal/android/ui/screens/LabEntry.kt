package tvcal.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tvcal.android.ui.components.IconBubble
import tvcal.android.ui.components.ScreenScaffold
import tvcal.android.ui.theme.Fonts
import tvcal.android.ui.theme.Symbols
import tvcal.android.ui.theme.secondary

// The lab build's entry (Swift LabEntry → LabModeView). Lab mode itself is P5: until then, a placeholder.
// Reached only from the lab build (AppModel.isLabBuild: the home tile and Settings › About).

@Composable
fun LabEntry(onBack: () -> Unit) {
    ScreenScaffold(title = "Lab mode", onLeading = onBack) {
        Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconBubble(Symbols.flask, size = 64.dp)
                Text("Lab mode is coming in a later build", style = Fonts.headlineBrand, textAlign = TextAlign.Center)
                Text(
                    "It will pair with the lab tool on a computer to train a camera profile for this phone.",
                    style = Fonts.captionBrand.secondary(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
