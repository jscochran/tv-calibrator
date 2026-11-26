package tvcal.android.ui.screens

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import tvcal.android.LocalApp
import tvcal.android.app.AppInfo
import tvcal.android.app.AppModel
import tvcal.android.app.Chime
import tvcal.android.app.Notifications
import tvcal.android.profiles.ProfileService
import tvcal.android.ui.components.Card
import tvcal.android.ui.components.Eyebrow
import tvcal.android.ui.components.LinkButton
import tvcal.android.ui.components.ScreenColumn
import tvcal.android.ui.components.ScreenScaffold
import tvcal.android.ui.components.openUrl
import tvcal.android.ui.theme.Fonts
import tvcal.android.ui.theme.Symbols
import tvcal.android.ui.theme.Theme
import tvcal.android.ui.theme.dateTimeShort
import tvcal.android.ui.theme.fmt
import tvcal.android.ui.theme.secondary
import tvcal.kit.SignalMode

// Port of Views/SettingsView.swift. Text says "phone" where the Swift says "iPhone".

/**
 * Settings (Figma "14 Settings"), pushed (SettingsRoute), each section a Card:
 * - Camera profile: "This phone" (profiles.model · profileLabel), the standard-profile warning, TVs matched,
 *   accuracy, aperture ("f/" + fmt(ap, 1)), "Check for a newer profile" (app.scope.launch { refresh() }),
 *   "Last checked <dateTimeShort>", the explainer.
 * - Pattern videos: playlists, downloads, explainer, help (openUrl).
 * - Notifications and sounds (app.notifications): AUTHORIZED → "Re-check reminders" Switch (videos toggle only
 *   when Notifications.pushConfigured, which is false); DENIED → "Notifications are off" + "Open Settings"
 *   (notifications.settingsIntent()); else "Turn on notifications" (permissionToRequest() → launcher →
 *   turnOn(granted)). "Sounds" Switch = prefs.boolState(Chime.enabledKey, true). refreshStatus() on start/resume.
 * - About: "TV Calibrator by Slidebean" + "Version <AppInfo.version> (<AppInfo.build>)", Instagram, "Lab mode"
 *   (lab build only → [onOpenLab], detail "Train with the computer"), "Measurements stay on this phone; …".
 */
@Composable
fun SettingsView(profiles: ProfileService, onBack: () -> Unit, onOpenLab: () -> Unit) {
    ScreenScaffold(title = "Settings", onLeading = onBack) {
        ScreenColumn(spacing = 12.dp) {
            ProfileSection(profiles)
            VideosSection()
            NotificationsSection()
            AboutSection(onOpenLab)
        }
    }
}

// MARK: Camera profile

@Composable
private fun ProfileSection(profiles: ProfileService) {
    val app = LocalApp.current
    val p = profiles.profile
    Card(spacing = 12.dp) {
        Eyebrow("Camera profile")
        SettingsRow(
            "This phone",
            detail = if (p == null) profiles.model else "${profiles.model} · ${profiles.profileLabel}",
            symbol = Symbols.camera,
        )
        if (p != null) {
            if (profiles.isStandard || profiles.isApproximate) {
                Text(profiles.summary, style = Fonts.captionBrand, color = Theme.limit)
            }
            val tvs = p.stats.tvs.size
            SettingsRow(
                "TVs matched",
                detail = "Matched on $tvs TV${if (tvs == 1) "" else "s"}",
                symbol = Symbols.checkmark,
            )
            val unseen = p.stats.unseen
            val deAvg = p.stats.deAvg
            if (unseen != null) {
                SettingsRow("Accuracy: ${p.accuracyWord}", detail = "dE ${fmt(unseen, 2)} on a new TV", symbol = Symbols.info)
            } else if (deAvg != null) {
                SettingsRow("Accuracy: ${p.accuracyWord}", detail = "dE ${fmt(deAvg, 2)} in training", symbol = Symbols.info)
            }
            val ap = p.camera.aperture
            if (ap != null) {
                SettingsRow("Aperture", detail = "f/" + fmt(ap, 1), symbol = Symbols.cameraAperture)
            }
        } else {
            Text(profiles.summary, style = Fonts.captionBrand.secondary())
        }
        Column {
            LinkButton("Check for a newer profile", onClick = { app.scope.launch { profiles.refresh() } })
            val checked = profiles.lastChecked
            if (checked != null) {
                Text("Last checked ${dateTimeShort(checked)}", style = Fonts.small.secondary())
            }
        }
        Text(
            "Each phone model's camera is matched to a professional colorimeter, so the readings mean the same thing on every phone. Profiles come built into the app; new models and updates download automatically. A phone without its own profile uses the standard one.",
            style = Fonts.captionBrand.secondary(),
        )
    }
}

// MARK: Pattern videos

@Composable
private fun VideosSection() {
    val app = LocalApp.current
    val context = LocalContext.current
    val links = app.links.links
    Card(spacing = 12.dp) {
        Eyebrow("Pattern videos")
        for (m in listOf(SignalMode.SDR, SignalMode.HDR10)) {
            val url = links.playlist(m)
            if (url != null) {
                SettingsRow(
                    "${m.label} playlist",
                    Modifier.clickable { openUrl(context, url) },
                    detail = "YouTube",
                    symbol = Symbols.openExternal,
                )
            }
        }
        for (m in listOf(SignalMode.HDR10P, SignalMode.DV)) {
            val url = links.download(m)
            if (url != null) {
                SettingsRow(
                    "${m.label} patterns",
                    Modifier.clickable { openUrl(context, url) },
                    detail = "USB download",
                    symbol = Symbols.arrowDownCircle,
                )
            }
        }
        val explainer = links.explainerURL
        if (explainer != null) {
            SettingsRow(
                "How calibration works",
                Modifier.clickable { openUrl(context, explainer) },
                detail = "The Slidebean video",
                symbol = Symbols.playRectangle,
            )
        }
        val help = links.helpURL
        if (help != null) {
            SettingsRow("Help and videos", Modifier.clickable { openUrl(context, help) }, symbol = Symbols.questionCircle)
        }
        Text(
            "SDR and HDR10 patterns play from YouTube in the TV's own YouTube app. HDR10+ and Dolby Vision patterns are a download, played from a USB stick or a media app.",
            style = Fonts.captionBrand.secondary(),
        )
    }
}

// MARK: Notifications and sounds

@Composable
private fun NotificationsSection() {
    val app = LocalApp.current
    val context = LocalContext.current
    val notifications = app.notifications
    val soundsState = remember { app.prefs.boolState(Chime.enabledKey, true) }
    var sounds by soundsState
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifications.turnOn(granted)
    }
    // Swift `.task { refreshStatus() }`; also on coming back from the system settings.
    LifecycleResumeEffect(Unit) {
        notifications.refreshStatus()
        onPauseOrDispose { }
    }

    Card(spacing = 12.dp) {
        Eyebrow("Notifications and sounds")
        when (notifications.status) {
            Notifications.Status.AUTHORIZED -> {
                ToggleRow(
                    "Re-check reminders",
                    "Six months after calibrating a screen",
                    checked = notifications.recheck,
                    onChange = { notifications.recheck = it },
                )
                if (Notifications.pushConfigured) {
                    ToggleRow(
                        "New Slidebean videos",
                        "When a new video goes up on YouTube",
                        checked = notifications.videos,
                        onChange = { notifications.videos = it },
                    )
                }
            }
            Notifications.Status.DENIED -> {
                SettingsRow(
                    "Notifications are off",
                    detail = "Turn them on for TV Calibrator in the phone's Settings",
                    symbol = Symbols.bellSlash,
                )
                LinkButton("Open Settings", onClick = {
                    try {
                        context.startActivity(notifications.settingsIntent())
                    } catch (_: ActivityNotFoundException) {
                    }
                })
            }
            Notifications.Status.NOT_DETERMINED -> {
                // Verbatim from Swift, which shows this line whether or not video pushes are configured.
                SettingsRow(
                    "Notifications",
                    detail = "Re-check reminders and new Slidebean videos",
                    symbol = Symbols.bell,
                )
                LinkButton("Turn on notifications", onClick = {
                    val needed = notifications.permissionToRequest()
                    if (needed != null) permission.launch(needed) else notifications.turnOn(true)
                })
            }
        }
        ToggleRow(
            "Sounds",
            "A chime when a setting reaches its target",
            checked = sounds,
            onChange = { sounds = it },
        )
    }
}

/** Swift `Toggle(isOn:) { SettingsRow(title, detail) }`. */
@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsRow(title, Modifier.weight(1f), detail = detail)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Theme.tealBright,
                checkedBorderColor = Theme.tealBright,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Theme.hairline,
                uncheckedBorderColor = Theme.hairline,
            ),
        )
    }
}

// MARK: About

@Composable
private fun AboutSection(onOpenLab: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val links = app.links.links
    Card(spacing = 12.dp) {
        SettingsRow(
            "TV Calibrator by Slidebean",
            detail = "Version ${AppInfo.version} (${AppInfo.build})",
            symbol = Symbols.info,
        )
        val ig = links.instagramURL
        if (ig != null) {
            SettingsRow(
                "Questions or feedback?",
                Modifier.clickable { openUrl(context, ig) },
                detail = "Send us a message on Instagram",
                symbol = Symbols.bubble,
            )
        }
        if (AppModel.isLabBuild) {
            SettingsRow("Lab mode", Modifier.clickable(onClick = onOpenLab), detail = "Train with the computer", symbol = Symbols.flask)
        }
        Text(
            "Measurements stay on this phone; the app only downloads camera profiles.",
            style = Fonts.captionBrand.secondary(),
        )
    }
}

/** A settings row: a label over a secondary line, with a teal icon on the right (min 48 dp). */
@Composable
private fun SettingsRow(title: String, modifier: Modifier = Modifier, detail: String? = null, symbol: ImageVector? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = Fonts.bodyBrand, color = Color.White)
            if (detail != null) Text(detail, style = Fonts.captionBrand.secondary())
        }
        if (symbol != null) {
            Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                Icon(symbol, contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(21.dp))
            }
        }
    }
}
