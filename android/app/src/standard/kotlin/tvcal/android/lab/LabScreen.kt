package tvcal.android.lab

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

// The standard build has no Lab mode (Swift `#else struct LabEntry { EmptyView() }`): no pairing with the lab
// tool, no local-network access, no profile import. Nothing in the standard build navigates here (every entry
// point checks AppModel.isLabBuild); if something ever did, it goes straight back.

@Composable
fun LabScreen(onBack: () -> Unit) {
    LaunchedEffect(Unit) { onBack() }
}
