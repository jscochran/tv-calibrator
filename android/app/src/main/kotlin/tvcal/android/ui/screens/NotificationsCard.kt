package tvcal.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import tvcal.android.LocalApp
import tvcal.android.app.Notifications
import tvcal.android.ui.components.Card
import tvcal.android.ui.components.Eyebrow
import tvcal.android.ui.components.LinkButton
import tvcal.android.ui.components.SecondaryButton
import tvcal.android.ui.theme.Fonts
import tvcal.android.ui.theme.Symbols
import tvcal.android.ui.theme.secondary

// Port of NotificationsCard in App/Notifications.swift (the UI part).

/**
 * Shown under the After results (real TVs) while app.notifications.status is NOT_DETERMINED and the card
 * hasn't been dismissed (prefs.boolState(Notifications.cardDismissedKey, false)): eyebrow "Stay calibrated",
 * "Get a reminder to re-check <tvName>", the text (Android has no video pushes: drop the "plus a heads-up when
 * Slidebean posts a new TV video" part), "Turn on notifications" (bell; permissionToRequest() → launcher →
 * turnOn(granted); when true, scheduleRecheck(tvID, tvName)) and "Not now". refreshStatus() on start.
 * [tvID] is the TV's uppercase UUID (tv.id.upper).
 */
@Composable
fun NotificationsCard(tvID: String, tvName: String, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val notifications = app.notifications
    val dismissedState = remember { app.prefs.boolState(Notifications.cardDismissedKey, false) }
    var dismissed by dismissedState
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (notifications.turnOn(granted)) notifications.scheduleRecheck(tvID, tvName)
    }
    // Swift `.task { await notifications.refreshStatus() }`.
    LaunchedEffect(Unit) { notifications.refreshStatus() }

    if (notifications.status == Notifications.Status.NOT_DETERMINED && !dismissed) {
        Card(modifier, spacing = 12.dp) {
            Eyebrow("Stay calibrated")
            Text("Get a reminder to re-check $tvName", style = Fonts.headlineBrand, color = Color.White)
            // Swift: "In six months, plus a heads-up when Slidebean posts a new TV video. …" — no video
            // pushes on Android, so only the reminder is promised.
            Text(
                if (Notifications.pushConfigured) {
                    "In six months, plus a heads-up when Slidebean posts a new TV video. Turn either off in Settings."
                } else {
                    "In six months. Turn it off in Settings."
                },
                style = Fonts.bodyBrand.secondary(),
            )
            SecondaryButton(
                "Turn on notifications",
                onClick = {
                    val needed = notifications.permissionToRequest()
                    if (needed != null) {
                        permission.launch(needed)
                    } else if (notifications.turnOn(true)) {
                        notifications.scheduleRecheck(tvID, tvName)
                    }
                },
                icon = Symbols.bell,
            )
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                LinkButton("Not now", onClick = { dismissed = true })
            }
        }
    }
}
