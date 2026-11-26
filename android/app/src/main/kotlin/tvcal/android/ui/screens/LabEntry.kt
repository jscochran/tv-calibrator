package tvcal.android.ui.screens

import androidx.compose.runtime.Composable
import tvcal.android.lab.LabScreen

// The Lab route's entry (Swift LabEntry → LabModeView). LabScreen comes from the flavor's source set:
// src/lab/kotlin/tvcal/android/lab/LabModeView.kt (Lab mode) or src/standard/kotlin/tvcal/android/lab/LabScreen.kt
// (an empty stub). Reached only from the lab build (AppModel.isLabBuild: the home tile, Settings › About's
// "Lab mode" row, and five taps on the version there).

@Composable
fun LabEntry(onBack: () -> Unit) {
    LabScreen(onBack = onBack)
}
