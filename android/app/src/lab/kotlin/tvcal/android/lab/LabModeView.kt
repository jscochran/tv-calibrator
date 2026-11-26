package tvcal.android.lab

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tvcal.android.LocalApp
import tvcal.android.camera.CameraMeter
import tvcal.android.camera.rememberCameraStarter
import tvcal.android.profiles.ProfileImport
import tvcal.android.profiles.ProfileService
import tvcal.android.ui.components.Card
import tvcal.android.ui.components.Eyebrow
import tvcal.android.ui.components.KeepScreenOn
import tvcal.android.ui.components.LinkButton
import tvcal.android.ui.components.PrimaryButton
import tvcal.android.ui.components.ScreenColumn
import tvcal.android.ui.components.ScreenScaffold
import tvcal.android.ui.components.SecondaryButton
import tvcal.android.ui.theme.Fonts
import tvcal.android.ui.theme.Symbols
import tvcal.android.ui.theme.Theme
import tvcal.android.ui.theme.secondary
import tvcal.kit.cfmt

// Port of Lab/LabModeView.swift (lab flavor only). The standard build has no pairing, no local-network access and
// no profile import: its LabScreen is an empty stub (src/standard).

/** The Lab route's screen (Swift LabEntry → LabModeView(lab: app.lab, camera: app.camera)). */
@Composable
fun LabScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val lab = remember(app) { Lab.link(app) }
    LabModeView(lab = lab, camera = app.camera, profiles = app.profiles, onBack = onBack)
}

/**
 * Lab mode: pair with the lab tool on a computer and answer its camera readings while it trains this phone
 * model against the Display Plus HL.
 */
@Composable
fun LabModeView(lab: LabLink, camera: CameraMeter, profiles: ProfileService, onBack: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf<FoundComputer?>(null) }
    var address by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Swift .onAppear { lab.start() } / .onDisappear { lab.stop() }, plus the app's own lifecycle: the iPhone app
    // is suspended in the background, so its polls stop and the lab tool drops it. Here the process keeps running
    // (screen off, the profile file picker, another app), so stop following the computer, browsing and the
    // multicast lock on ON_STOP (the camera is released then too) and start again on ON_START.
    LifecycleStartEffect(lab) {
        lab.start()
        onStopOrDispose { lab.stop() }
    }
    // The lab tool gives up on a phone that stops polling: keep the screen on while training.
    KeepScreenOn()

    val startCamera = rememberCameraStarter { app.scope.launch { app.model.startCamera() } }

    ScreenScaffold(title = "Lab mode", onLeading = onBack) {
        ScreenColumn(spacing = 12.dp) {
            Text(
                "On the computer: open TV Calibrator (the lab tool), turn on Pro tools, and use “Train phone " +
                    "profiles” on each TV (the more TVs, the better the profile for TVs it has never seen). Then " +
                    "export the profile from Camera profiles and publish it with build_catalog.py.",
                style = Fonts.captionBrand.secondary(),
            )

            Card(spacing = 12.dp) {
                Eyebrow("Camera")
                if (camera.ready) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Symbols.checkmarkCircle, contentDescription = null, tint = Theme.green, modifier = Modifier.size(20.dp))
                        Text("Camera ready", style = Fonts.bodyBrand, color = Theme.green)
                    }
                } else if (!camera.available) {
                    Text(camera.unavailableReason, style = Fonts.captionBrand, color = Theme.limit)
                } else {
                    Text("Start the camera and measure the darkness first.", style = Fonts.bodyBrand.secondary())
                    if (camera.message.isNotEmpty() && camera.message != CameraMeter.OFF) {
                        Text(camera.message, style = Fonts.captionBrand.secondary())
                    }
                    SecondaryButton("Start the camera", onClick = startCamera, enabled = !camera.running)
                    SecondaryButton(
                        "Measure darkness (cover the lens)",
                        onClick = { app.scope.launch { camera.measureDark() } },
                        enabled = camera.running && !camera.busy,
                    )
                }
            }

            LabProfileImport(profiles)

            if (lab.isPaired) {
                Card(spacing = 12.dp) {
                    Eyebrow("Computer")
                    InfoRow("Paired with", lab.macName)
                    InfoRow("Status", if (lab.connected) "Connected" else "Not reachable")
                    InfoRow("Readings sent", cfmt(lab.readingsSent.toDouble(), 0))
                    if (lab.lastError.isNotEmpty()) {
                        Text(lab.lastError, style = Fonts.captionBrand, color = Theme.limit)
                    }
                    // Swift Button(role: .destructive)
                    Box(
                        Modifier
                            .heightIn(min = 44.dp)
                            .clickable(role = Role.Button) {
                                lab.forget()
                                chosen = null
                                problem = null
                            },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text("Forget this computer", style = Fonts.bodyStrong, color = Theme.red)
                    }
                }
            } else {
                Card(spacing = 12.dp) {
                    Eyebrow("Computers nearby")
                    if (lab.found.isEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Theme.textSecondary, strokeWidth = 2.dp)
                            Text("Looking for TV Calibrator on your Wi-Fi…", style = Fonts.bodyBrand.secondary())
                        }
                    }
                    for (c in lab.found) {
                        val selected = chosen?.id == c.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable {
                                    chosen = c
                                    address = ""
                                    problem = null
                                }
                                .semantics(mergeDescendants = true) {},
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (selected) Symbols.circleSelected else Symbols.circle,
                                contentDescription = null,
                                tint = Theme.tealBright,
                                modifier = Modifier.size(20.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = Fonts.bodyBrand, color = Color.White)
                                Text("${c.ip}:${c.port}", style = Fonts.small.secondary())
                            }
                        }
                    }
                    Text("Or type the computer's address", style = Fonts.captionBrand, color = Color.White)
                    LabField(
                        value = address,
                        onValueChange = {
                            address = it.trim()
                            if (it.isNotBlank()) chosen = null
                            problem = null
                        },
                        placeholder = "e.g. 10.0.1.67 or 10.0.1.67:8766",
                        keyboard = KeyboardType.Uri,
                    )
                    Text(
                        "The pairing code and the computer's address are in the lab tool's sidebar, under Phones. " +
                            "A Windows computer doesn't show up nearby: type its address.",
                        style = Fonts.captionBrand.secondary(),
                    )
                }

                val target = chosen?.name ?: address.takeIf { it.isNotBlank() }
                if (target != null) {
                    Card(spacing = 12.dp) {
                        Eyebrow("Pair with $target")
                        LabField(
                            value = code,
                            onValueChange = { text -> code = text.filter { it.isDigit() }.take(4) },
                            placeholder = "4-digit code",
                            keyboard = KeyboardType.NumberPassword,
                        )
                        PrimaryButton(
                            if (busy) "Pairing…" else "Pair",
                            onClick = {
                                busy = true
                                val computer = chosen
                                val typed = address
                                val pin = code
                                scope.launch {
                                    problem = try {
                                        if (computer != null) lab.pair(computer, pin) else lab.pair(typed, pin)
                                    } finally {
                                        busy = false
                                    }
                                    if (problem == null) code = ""
                                }
                            },
                            enabled = code.length == 4 && !busy,
                        )
                        problem?.let { Text(it, style = Fonts.captionBrand, color = Theme.limit) }
                    }
                }
            }
        }
    }
}

/** Lab-only: load a profile exported from the lab tool to test it before publishing. */
@Composable
private fun LabProfileImport(profiles: ProfileService) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importError by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importError = try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw ProfileImport.Problem("Couldn't read that file.")
                profiles.importProfile(bytes)
                null
            } catch (e: ProfileImport.Problem) {
                e.text
            } catch (e: Exception) {
                e.message ?: "Couldn't read that file."
            }
        }
    }
    Card(spacing = 8.dp) {
        Eyebrow("Test a profile")
        LinkButton("Import a profile file…", onClick = {
            importError = null
            // JSON files often come through as octet-stream or text from file managers and cloud drives.
            picker.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "text/json"))
        })
        if (profiles.isImported) {
            LinkButton("Stop using the imported profile", onClick = { app.scope.launch { profiles.clearImported() } })
        }
        importError?.let { Text(it, style = Fonts.captionBrand, color = Theme.limit) }
        Text(
            "A profile exported from the lab tool (Camera profiles › Export), before it's published.",
            style = Fonts.captionBrand.secondary(),
        )
    }
}

/** Swift LabeledContent: the label on the left, the value on the right. */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Fonts.bodyBrand, color = Color.White, modifier = Modifier.weight(1f))
        Text(value, style = Fonts.bodyBrand.secondary())
    }
}

/** A one-line text field in the app's style (as TVSetupFlow's name field). */
@Composable
private fun LabField(value: String, onValueChange: (String) -> Unit, placeholder: String, keyboard: KeyboardType) {
    val focus = LocalFocusManager.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        textStyle = Fonts.brand(16f).copy(color = Color.White),
        singleLine = true,
        cursorBrush = SolidColor(Theme.tealBright),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        interactionSource = interaction,
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .background(Theme.card, shape)
                    .border(1.dp, if (focused) Theme.tealBright else Theme.hairline, shape)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, style = Fonts.brand(16f), color = Theme.textSecondary, maxLines = 1)
                }
                inner()
            }
        },
    )
}
