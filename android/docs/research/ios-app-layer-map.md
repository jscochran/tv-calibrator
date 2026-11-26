# Port map: TV Calibrator iPhone app layer → Android (Kotlin + Compose)

Read-only recon. No files were edited. Paths below are relative to `tv-calibrator/`.
Already in the repo: `android/app` exists (package `tvcal.android`, `MainActivity.kt` only, flavors `standard`/`lab` with `BuildConfig.LAB_MODE`, minSdk 29, compileSdk 36, Compose and serialization plugins). Swift `#if LAB_MODE` maps to `BuildConfig.LAB_MODE`, or to the `src/lab` source set.

## 1. Swift files (lines / bytes)

| File | What it does | CalibrationKit used | iOS APIs → Android |
|---|---|---|---|
| App/TVCalibratorApp.swift (39/1.5k) | Entry point. Shows IntroView over RootView for 1.7 s, then fades it out over 0.6 s. Runs `profiles.refresh()` and `links.refresh()` in the background. Dark scheme, teal tint. | – | `@main App` → Activity + `setContent`. `.task` → `LaunchedEffect`. |
| App/AppModel.swift (117/4.4k) | Root state: TV list, TVStore, CameraMeter, ProfileService, LinksService, LabLink (lab only), demo SimTV cache. `addDemoTV` creates "Demo Screen" / "Demo Screen N" (samsung, twenty, rgbSliders, isDemo). `simTV`: seed = (uuid byte0<<16 \| byte1<<8 \| byte2), cache key "\<uuid\>-\<hdr\>". On first creation it sets `s.p20Shift = 0.10` and saves. `demoApply`. `cameraBlocker`: profile nil → `profiles.summary`; not running → "Start the camera (Camera setup)."; no dark → "Measure the camera's darkness once (Camera setup).". `startCamera` aperture choice: profile.camera.aperture if set; else `ApertureLock.defaultFNumber` (2.8) when Lab build or no profile; else nil. `videoMeter`: demo → `SimMeter(simTV, seq, playAt 2.0, realTime 0.1, seed = unixSeconds % 100000)`; real → `CameraXYZMeter(checkSettings: !isStandard, panel: tv.panel.profileKey)`. `liveMeter`: demo → `SimLiveMeter(simTV, shown)`. | SimTV(.State, p20Shift, apply), SimMeter, SimLiveMeter, ShownPatch, PatternSequence, Meter | `ObservableObject` → app-scoped singleton or ViewModel with `StateFlow`/`mutableStateOf`. |
| App/Chime.swift (48/2k) | Success sound plus haptic, once per key (`"<tv>\|<mode>\|<kind>\|<pid>\|<chan>"`), after 2 on-target readings in a row. Key resets on a miss. In memory only. `play()` is gated by the `feedback.sounds` pref (default true). | – | AVAudioPlayer(success.wav, vol 0.7, `.ambient` + mixWithOthers) → SoundPool/MediaPlayer with USAGE_ASSISTANCE_SONIFICATION. UINotificationFeedbackGenerator(.success) → `HapticFeedbackConstants.CONFIRM` / VibrationEffect. |
| App/Notifications.swift (204/9.2k) | Opt-in local "re-check" reminder 182 days after an After check: id `recheck-<tvID>`, title "Time to re-check \<name\>", body "TV pictures drift as the panel ages. A quick check takes a few minutes.". Also APNs video pushes: POST `<server>/register` or `/unregister` with `{token, env}`. NotificationsCard is shown once after a finished calibration while status is notDetermined and the card hasn't been dismissed. Tapping a push opens `userInfo.url` (https only). | – | UNUserNotificationCenter → WorkManager `OneTimeWorkRequest` (delay 182 d, unique work `recheck-<id>`) + NotificationManager + POST_NOTIFICATIONS (API 33+). APNs has no Android equivalent: the push worker is APNs-only (`push-worker/`), so drop video pushes (`pushConfigured = false`) unless FCM is added server-side. `openNotificationSettingsURLString` → `Settings.ACTION_APP_NOTIFICATION_SETTINGS`. |
| Camera/CameraMeter.swift (632/31k) | RAW camera meter: RawStats/RawAnalysis, CameraEngine, ApertureLock, CameraReading, DarkLevel, CameraMeter. Full spec in §4. | CameraSettings, clamp | AVFoundation/CoreVideo/ImageIO → Camera2 (RAW_SENSOR ImageReader, manual sensor). `isIdleTimerDisabled` → `FLAG_KEEP_SCREEN_ON`. |
| Camera/CameraXYZMeter.swift (97/4.5k) | Implements `Meter` (see §4). CameraPreview: aspect-fit layer. CameraViewfinder: 3:4 frame, 14 dp corners, #30383A fill while off, yellow 1.5 dp box at 10%×10% in the centre. | Meter, MeterReading, MeterClock, MeasureError, CameraProfile.xyz(cameraRGB:panel:), CameraSettings.mismatches, Vec3 | AVCaptureVideoPreviewLayer → SurfaceView/TextureView preview in the same Camera2 session, letterboxed to the sensor aspect. |
| Lab/LabLink.swift (250/10k) | Lab build only. Bonjour discovery of the Mac, pairing, 120 ms polling, answering reading requests (§5). | – (CameraMeter) | Network.NWBrowser → `NsdManager` (`_tvcal._tcp`, read the TXT `ip`/`port`/`host`) + WifiManager.MulticastLock. URLSession (3 s timeout, ephemeral) → HttpURLConnection or OkHttp. UIDevice.name → `Settings.Global.DEVICE_NAME` or Build.MODEL. Needs cleartext HTTP (network-security-config in the lab flavor only). |
| Lab/LabModeView.swift (122/5.3k) | Form with sections: instructions; camera (Start / "Measure darkness (cover the lens)"); "Test a profile" (import JSON / stop using it); Mac paired (name, Connected / Not reachable, readings sent, last error, Forget) or Macs nearby list → 4-digit code field → Pair. Calls `lab.start()` on appear and `stop()` on disappear. | – | `.fileImporter(.json)` → `ActivityResultContracts.OpenDocument("application/json")`. |
| Measure/Controllers.swift (257/10k) | GuidedController, one per TV × mode: GuideState load/save, `go(step, markDone)` (stops live readings unless the step is bright/dark/colours/fine), markLimit, setColour, finishColour (next unfinished colour, or go(.after, markDone .colours)), setFinePid, newSession, `measureQuick(.before/.after/.check)` (session bookkeeping; schedules the recheck reminder on After for real TVs; appends to log.md), showLive/stopLive, demoPress, point finder (`measureFinder(nudged:)` applies demo nudges ±`PointFinder.nudge` with keys `p20:<ch>:<pt>`; clears `meas.missed`; message if map.count < 2), overrides, `_skip` key. `Log` writes log.md tables using "%.2f", "%.1f", "%+.1f" and stamp "yyyy-MM-dd HH:mm". | Analysis.analyse/greyAverage/colourAverage, Patterns.quickSequence/packFile/finderFile/finderSequence/patch/colourIDs, GuidePlan.forHDR(.quickWithColours/.gain/.offset/.fine), PointFinder.map/grid/suggestions/nudges/nudge, FinderPoint, Measurements, Report | Combine forwarding → StateFlow. Use `cfmt` for every number. |
| Measure/Runners.swift (200/6.6k) | SweepRunner: runs `Sweep.sweep` with an event stream; phases idle/waiting/measuring/done/cancelled/failed; `recent` keeps the last 40 Y values; checkOrder → `Sweep.outOfOrder` → failed(outOfOrder text). LiveSession: loop of `meter.read()`; keeps a reading only if xyz≠nil, the patch is unchanged, and `started >= since + 0.6`; window of the last 14; `Guided.evaluate(patch, readings, hdr, whiteY, basis)`; on error waits 1 s. Idle poll every 0.2 s. FreeMeter: loop → `LiveAnalysis.of(xyz, hdr)`. | Sweep.sweep, Sweep.outOfOrder, SweepEvent, MeasureError, Guided.evaluate, LiveReading, LiveAnalysis, Patch, PatchKind, MeterClock | AsyncStream/Task → Flow/Channel + coroutine Job. CancellationError → CancellationException. |
| Profiles/ProfileService.swift (305/15k) | Config.server, DeviceModel, ProfileService, ProfileImport (§6). | CameraProfile, ProfileCatalog, BuiltInProfiles, CameraSettings, ProfileStats | CryptoKit SHA256 → MessageDigest. uname → Build.* (§6). |
| Profiles/PatternLinks.swift (111/5.5k) | PatternLinks JSON (format, channel, help, newsletter, explainer, instagram, playlists{sdr,hdr10}, downloads{hdr10p,dv}, videos{file title→URL}). Lenient decode. Only https URLs are accepted. Instagram and newsletter have built-in fallback URLs. `SignalMode.onYouTube` = sdr\|hdr10; `patternSource`. LinksService: load cache (AppSupport/patterns.json), else bundled Resources/patterns.json; refresh from `<server>/patterns.json` (200 only) and write it to the cache. | SignalMode | Bundle resource → `assets/` or `res/raw`. `Link` → Intent.ACTION_VIEW. |
| Storage/TVStore.swift (261/11k) | Models TVRecord, GuideStep, GuideProgress, GuidedSession, FinderData, GuideState(.reference) and the JSON file store (§3). | Measurements, SimTV.State, SignalMode, Vec3, ColourMenu | FileManager → `context.filesDir`. JSONEncoder (pretty, sortedKeys, ISO 8601) → kotlinx Json with custom Date/UUID serializers. |
| Storage/Brands.swift (261/13k) | Brand enum (12): labels, menu paths, offsetName (Sony "Bias", Panasonic "Cutoff"), default colourMenu and multipoint, pictureMode, extras, setup, gamutSetting, cmsNames. Multipoint raw Ints 20/11/0. ColourMenu.label. colourSwatch. PanelType (woled, qdoled, qdlcd, lcd, rgbled, plasma, unknown; profileKey nil for unknown). GamutCheck.note (over: sat>10 or lum>40 on ≥2 primaries; under: sat<−12 on ≥2). | SignalMode, ColourMenu, Report | Pure Kotlin, port verbatim (all strings). |
| Views/DesignSystem.swift (443/18k) | Theme colours (bg #05171A, surface #0D3B42, tealBright #40B8B0, accent #FFD133, pressed #DAB025, green #4DCC73, limit #FFB36B, red #F2544D, blue #548CFA, HDR10+ #9E85FA, DV #ED73BF, card white 6%, hairline 9%, secondary text 60%, previewFill #30383A). Mode tints, isAdvanced, order, blurb. Type scale (28/25/22sb/23/18sb/17sb/15/14/13/12/11/10, rounded monospaced readings), Dynamic Type scaling. `fmt`/`signed` ("%.Nf" / "%+.Nf", "–" for nil or non-finite). Buttons, Card, IconBubble, ModeBadge, Eyebrow, TipRow, PageDots, ProgressTrack. `bottomAction` and the PinnedAction preference mechanism (a child declares the main action; the host pins it at the bottom). | SignalMode | PreferenceKey → CompositionLocal holder or a hoisted state slot. `fmt`/`signed` must use `cfmt`. UIFontMetrics → `sp` units. |
| Views/Components.swift (731/32k) | ScoreTile (bands grey 1/2/4/7, colour 1/3/6/10; word, tint), BalanceBar, HueWheel shading, WhiteTarget (Canvas), GamutChart (Canvas, CIE u′v′), GreyBalanceChart (Swift Charts: RuleMark/LineMark), ResultScores, ResultWarnings, ResultCharts, ReportSummary, MarginNote. | Report, GreyResult, ColourResult, Point2, Patterns targets | `import Charts` → hand-drawn Compose Canvas. SwiftUI Canvas → Compose Canvas. |
| Views/ReportVisuals.swift (356/15k) | DeGauge (animated spring), ScoreGaugeRow, ReportHero, ReportPanel, ScoreInfo (expandable), TVPlayArt, OrderStrip. | Report, Analysis | Compose animation. |
| Views/Playback.swift (284/12k) | PlayFileCard: video link = links.video(file) ?? playlist, or the download URL for advanced modes; compact variant with a patch swatch. MeasuringCard (waiting/measuring/done list, live Y, Sparkline). | Patch, PatternSequence, SweepRunner | Link → ACTION_VIEW. |
| Views/IntroView.swift (34/1.4k) | Logo splash: #030F11 with a radial #082A2C glow; logo fades and scales in over 0.9 s. | – | Compose overlay (or the SplashScreen API plus overlay). |
| Views/RootView.swift (638/27k) | Route enum, NavigationStack, Home (TVListView), TVCard (FormatChip stages; score word of the latest After, SDR first), ToolTile, CameraStatusContent (phases checking/unavailable/setUp/darkness/standard/ready), ManageScreensView, delete alert. | Analysis, SignalMode | navigation-compose. `.sheet` → ModalBottomSheet or full-screen dialog destinations. `contextMenu`/`swipeActions` → long-press menu / SwipeToDismissBox. |
| Views/TVHomeView.swift (483/20k) | Screen home: latest report card, ModeSelector (SDR/HDR10 cards + advanced chips), StepBar → StepNavigatorSheet, demo note, ⋯ menu (Screen settings, Export history, Delete), AdvancedFormatSheet (first use of HDR10+/DV per TV), ShareSheet. Mode is persisted per TV. On leaving (unless into the steps), cancels the runner and live readings. | Analysis, Report, SignalMode | UIActivityViewController → ACTION_SEND + FileProvider. |
| Views/TVSetupFlow.swift (383/15k) | Add/Edit a screen as a 4-page Walkthrough: name+brand grid (picking a brand sets the default multipoint/colour menu unless "known"), PanelChoice, MultipointChoice (+ "Not sure"), ColourMenuChoice. MenuQuestionCard asks later if a menu was "not sure". | ColourMenu | TextField → OutlinedTextField (words capitalisation). |
| Views/Walkthrough.swift (408/18k) | Paged walkthrough (swipe when \|dx\| > 1.5·\|dy\|, min 30; Next/Back; dots; eyebrow "\<section\> · i of n"; canAdvance; finish), plus illustrations PlacementArt, DarkRoomArt, FormatOrderArt, WhiteBalanceArt, ColourMenuArt, WalkArtPanel. | SignalMode | HorizontalPager or a custom AnimatedContent. |
| Views/WelcomeView.swift (171/8k) | "How it works" sheet: optional explainer video page, then 3 pages. VideoPreview opens the URL. | – | ACTION_VIEW. |
| Views/GuidedView.swift (847/37k) | GuidedView (step switch, camera-blocker message + "Set up the camera" → CameraSetupSheet), GuidedScreen (title, pinned host, Cancel while running, idle timer), GuidedContextHeader, StepBar, SetupStep (5–6 pages), PatternLinkButton, PatternSourceCard, GuidedQuickReady, GuidedWrongVideo, QuickStep (Before/After; After plays the chime on done), NewsletterCard (`untangled.opened`), CheckStep (on target = \|r−g\| and \|b−g\| ≤ 0.015; SDR greys filtered to level ≥ 0.099; verdict <1/<2/<3; redo targets level ≥ 0.55 bright, ≤ 0.4 dark, else fine), `shortName`. | Analysis, GreyResult, Patterns, GuidePlan, Report | – |
| Views/LiveSteps.swift (1050/47k) | AverageLine (jitter ≥ 0.004), TipList, ChannelBalanceRow (tolerance 0.012, feeds Chime), WhiteBalanceStep (gain/offset/fine; clicks = clamp(round(\|e\|/0.008), 1, 10); "bars still" notice after 60 s with Δ ≤ 0.006), BigWhiteTarget, ColourChip, ColoursStep (HSL or RGB controls, "Can't get closer", unfinished dialog), FineStep, FineTuneView (override picker stride grid…100), FinderStatusRow, PointFinderView (base pass, then nudged pass), DemoRemote (±1 presses). | LiveReading, Guided, PointFinder, Patterns, GamutChart | `confirmationDialog` → AlertDialog. |
| Views/ResultsView.swift (204/8k) | Before→After report, headline (worse by > 0.3 → "Not quite there yet."), newsletter, warnings, "Grey by grey" → ResultsChartsView, Share: renders ShareSummaryCard at 393×660 @3x. | Analysis, Report | ImageRenderer + ShareLink → draw the composable to a Bitmap (GraphicsLayer.toImageBitmap), save to cache, ACTION_SEND image/png via FileProvider. |
| Views/CameraSetupView.swift (609/26k) | Camera setup stages start → darkness → placement (Test reading) → ready, plus a camera log (last 40 lines, Measure darkness again / Test again / Stop the camera). LiveMeterView: SDR/HDR picker, FreeMeter, white or colour displays. | LiveAnalysis, CameraXYZMeter | – |
| Views/SettingsView.swift (216/9.6k) | Cards: camera profile (model · label, TVs matched, accuracy, aperture, "Check for a newer profile", last checked), pattern videos links, notifications and sounds toggles, about (version "v (build)", Instagram, Lab mode row in the lab build). | CameraProfile | Bundle version → BuildConfig.VERSION_NAME/VERSION_CODE. |

Resources: `builtin-profiles.json` (format 1, standard "iPhone19,2"; iPhone16,1 v3 and iPhone19,2 v4 with aperture 2.8, both ISO 55, "8064x6048 bgg4", "Back Camera", panels qdlcd/rgbled/woled), `patterns.json`, `success.wav`. Assets: AppIcon, AppIconLab, BrandChart.png, SlidebeanLogo.png, LaunchBackground, AccentColor.
Config: Info.plist (launch colour only). Info-Lab.plist: name "TV Cal Lab", NSLocalNetworkUsageDescription, NSBonjourServices `_tvcal._tcp`, NSAllowsLocalNetworking, still-camera. `ServerConfig.example.plist` holds key `Server`. Entitlements: aps-environment.

## 2. Screen and flow graph

- **Launch**: Intro overlay (1.7 s + 0.6 s fade) over **Home**. If `!welcome.seen && tvs.isEmpty`, the **Welcome** sheet opens after 2.1 s. Its Finish or Close sets `welcome.seen`.
- **Home** (TVListView):
  - Gear or Settings tile → **Settings** → (lab) **Lab mode**.
  - Camera status row → **Camera setup**.
  - TV card → **Screen home(tv)**. Long-press → Delete alert.
  - "Manage Screens" → **Manage Screens** sheet: edit → **TVSetupFlow**(tv) sheet; delete → alert; Add → TVSetupFlow sheet.
  - "Add a Screen" → **TVSetupFlow**(nil) sheet. On add, push Screen home.
  - "Try the demo" (only if no demo TV) → creates the demo TV and pushes Screen home.
  - "How it works" → Welcome sheet.
  - "Read any colour on screen" → **Live meter**. If the camera is blocked, a link goes to Camera setup.
  - (lab) "Lab mode" tile → **Lab mode**.
- **Screen home(tv)**:
  - Report card → `go(.after or .before)` and push **Guided screen**.
  - Mode cards: HDR10+/DV show **AdvancedFormatSheet** the first time (`advanced-ok-<id>`).
  - StepBar → **Jump to a step** sheet → `go(step)` → push Guided screen.
  - Pinned "Start with X / Continue X / View X results" → push Guided screen.
  - ⋯ menu: Screen settings → TVSetupFlow(tv); Export history → share JSON; Delete → confirm → pop, then delete 0.5 s later.
- **Guided screen** (one route; content switches on `progress.step`):
  1. Setup: Walkthrough (format, video source + playlist/download link, TV settings list, dim the room, place the phone, and for real TVs a camera page → **CameraSetupSheet**). Finish → Before.
  2. Before: ready → Measure (about 2 min) → MeasuringCard (Cancel pinned) → results (GamutCheck note, ReportSummary) or "wrong video" → "Next: fix the white balance".
  3. Bright end: WhiteBalanceStep(gain) → "Next: dark end".
  4. Dark end: WhiteBalanceStep(offset) → "Next: check".
  5. Check: measure → summary grid with per-grey Redo bright/dark/Fine-tune → "Continue to colours". Optional **Fine**: MenuQuestionCard if multipoint is unknown; multipoint none → "Back to the check"; PointFinderView (base pass + nudged pass, or Skip) → FineTuneView per grey → back to Check.
  6. Colours: MenuQuestionCard if the colour menu is unknown; 6 chips, Next/Finish colour, "Can't get closer", unfinished dialog → After.
  7. After: measure → ResultsView (+ NotificationsCard, NewsletterCard, limits note), "Grey by grey" → **Results charts** (push), Share image, "Start a new session" → Before.
  - Compact header "Step n of 7" → Jump sheet. Blocker message → "Set up the camera" → CameraSetupSheet. On closing it, a live step restarts.
- **Camera setup**: Start the camera → Measure the darkness → Test reading → Done.
- **Lab mode**: see §5.

## 3. Persisted data

UserDefaults → SharedPreferences (or DataStore):

| Key | Content |
|---|---|
| `darkLevel` | JSON `{"short":[r,g,b],"long":[r,g,b]}` |
| `feedback.sounds` | Bool, default true |
| `notify.videos`, `notify.recheck` | Bool, default true |
| `notify.cardDismissed` | Bool |
| `notify.token` | push token hex |
| `welcome.seen` | Bool |
| `untangled.opened` | Bool |
| `mode-<UUID>` | SignalMode raw |
| `advanced-ok-<UUID>` | Bool |
| `lab.host`, `lab.port` (0→8765), `lab.token`, `lab.macName` | Lab pairing |

Files under Application Support → `filesDir`:

- `TVs/<UUID uppercase>/tv.json`: TVRecord `{id, name, brand(raw), multipoint(20\|11\|0), colourMenu("samsung"\|"hsl"\|"off"), multipointKnown, colourMenuKnown, panel, isDemo, created}`.
- `TVs/<UUID>/guide-<sdr\|hdr10\|hdr10p\|dv>.json`: GuideState `{sessions:[{started, before?, after?, finished?}], check?, finder:{base?, nudged?, overrides{pid:Int, "_skip":0}}, progress:{step, done[], colours[], limits[], colour?, finePid?}}`.
- `TVs/<UUID>/sim-sdr.json`, `sim-hdr.json`: SimTV.State.
- `TVs/<UUID>/log.md`: appended text.
- Encoding: prettyPrinted + sortedKeys + ISO 8601 dates (no fractional seconds). Every decoder is lenient: a missing or bad field takes its default.
- `loadTVs` deletes folders without tv.json and sorts by `created`.
- `Profiles/catalog.json`, `Profiles/current.json` (downloaded own profile), `Profiles/imported.json` (lab).
- `patterns.json` (links cache).
- Export: temp file `"<name with / → -> - TV Calibrator export.json"` = `{exported, app, tv, phone, profile, guided{mode: GuideState}}`. Only modes with sessions or a finder base are included.

## 4. Camera meter spec (for Camera2)

**Locks** (set at start, never changed):
- Back main wide camera (Android: the back LOGICAL camera with RAW + MANUAL_SENSOR; pin one physical lens).
- Focus locked at the closest position (iOS lensPosition 0.0; Android AF_MODE_OFF + LENS_FOCUS_DISTANCE = LENS_INFO_MINIMUM_FOCUS_DISTANCE).
- White balance locked (preview only).
- ISO = activeFormat.minISO (SENSOR_INFO_SENSITIVITY_RANGE.lower).
- Exposure limits: `minDuration = max(format min, 1/8000)`, `maxDuration = min(format max, 0.25)`.
- Aperture: `ApertureLock.choose`. With wanted=nil → nil. Otherwise only if the variable range (hi−lo) > 0.05 → clamp(wanted, lo, hi). Android: LENS_INFO_AVAILABLE_APERTURES with more than one value → nearest; else nil. `reportedAperture` is logged.
- Only the shutter time varies (AE off, SENSOR_EXPOSURE_TIME; set SENSOR_FRAME_DURATION ≥ exposure).
- `settings = CameraSettings(iso, format: "<maxW>x<maxH> <fourcc>", lens: localizedName, aperture: lockedAperture)`. Android needs its own stable strings, e.g. "WxH rggb" from the largest RAW size + CFA, and lens "Back Camera" or the camera id. Profiles compare these exactly.
- `capturedSize` = actual RAW buffer "WxH", logged on change.
- On a runtime error the session restarts.

**RawAnalysis.measure(buffer, white = 16383, floor = 0)**:
- CFA cell index `(y%2)*2 + (x%2)` → channel 0 R / 1 G / 2 B:
  - RGGB [0,1,1,2]
  - GRBG [1,0,2,1]
  - GBRG [1,2,0,1]
  - BGGR [2,1,1,0]
  - anything else → nil. Android: SENSOR_INFO_COLOR_FILTER_ARRANGEMENT 0–3.
- Read UInt16 per photosite via rowBytes (Android plane rowStride, little-endian 16-bit).
- Box (integer maths, `&~1` clears bit 0):
  - `boxW = (W/10)&~1`, `boxH = (H/10)&~1`
  - `x0 = (W/2 − boxW/2)&~1`, `y0 = (H/2 − boxH/2)&~1`
- `clipLevel = UInt16(min(max(white, 1000), 16383) * 0.98)` (truncated). Any raw ≥ clipLevel → clipped.
- Per-channel sum and count → `mean[3]`. Track `maxRaw` and a histogram over 65536 values.
- Quarters: qy = 0 if y < y0+boxH/2 else 2; plus 0 if x < x0+boxW/2 else 1. Each quarter mixes all CFA colours. `uneven = (max qMean − min qMean)/avg(qMeans)` if avg > 0, else 0.
- Plateau:
  - `top = maxRaw`, `band = max(2, top*3/1000)` (integer)
  - `nearTop = Σ hist[max(0, top−band)...top]`, `total = boxW*boxH`
  - `plateau = top > max(2000, floor*2) && total > 0 && nearTop/total > 0.01`
  - plateau ⇒ clipped
- Returns `RawStats{mean, clipped, uneven, maxRaw, plateau}`.

**White level**:
- Default 16383.
- Once per launch, on the first successful photo: DNG WhiteLevel (scalar, or the min of an array), accepted if `w > 1000 && w ≤ 16383 && w > floor*3`. Android: SENSOR_INFO_WHITE_LEVEL, with the same acceptance test.
- Learned from a clipped photo: if the capture allows learning (`learnsWhite`, false for dark shots), `stats.plateau`, and `maxRaw < white*0.98` → `white = maxRaw`.
- `setFloor(level)`: floor = level. If `level > 0 && white < level*3` → reset white to 16383 and log it.

**capture(duration, learnsWhite)**:
- `d = clamp(duration, min, max)`. Set the custom exposure (with aperture if locked). On completion, shoot one RAW photo (shutter sound suppressed).
- `at = systemUptime at shoot + d/2`. Callback exactly once (Ticket).
- Timeout `d + 8 s` → nil ("The camera didn't answer in time").
- `didFinishCapture` without a photo → nil.
- A photo whose id ≠ pendingID (late) is ignored.

**DarkLevel**:
- `{short (at 1/30 s), long (at 1/4 s)}`.
- `at(t) = short + (long − short)·(t − 1/30)/(0.25 − 1/30)`, per channel, linear (extrapolates outside the range).
- Persisted under `darkLevel`. At init: `engine.setFloor(max(short))`.

**CameraMeter**:
- FIFO mutex `acquire`/`release` (kotlinx Mutex is fair).
- Published state: running, busy, message, dark, lastReading, logLines (keeps the last 200, each "HH:mm:ss  line"; `onLog("camera: "+line)` for Lab).
- `duration` starts at 1/250 s and persists between readings.
- `ready = running && dark != nil`.
- `start(aperture)`:
  - camera permission denied → message "TV Calibrator needs the camera… Settings > Privacy & Security > Camera."
  - otherwise "Starting the camera…"; on error the error text; on success running, keep screen on, message "Cover the lens and measure the darkness once." or "Ready to measure.".
- `stop()`: message "The camera is off.".

**measureDark()** (busy, message "Measuring with the lens covered…"):
- Three shots with learnsWhite=false: c at minDuration, a at 1/30, b at 1/4.
- Any nil → "Couldn't take a photo. Try again.".
- `leak = max_ch(b−a)`, `flood = max_ch(a−c)`.
- If `leak > 120 || flood > 120`: log the rejection. Message is "Still seeing light. Lay the phone screen-up…" if max > 2000, else "Some light is still getting in. A fingertip isn't enough: …". Return.
- Else `dark = DarkLevel(a.mean, b.mean)`, setFloor(max short), save, log, message "Ready. Point the box at the centre of the TV.".

**measure()** → `(CameraReading?, String?)`:
- Not ready → "The camera isn't ready.".
- Up to 7 shots:
  - Nil stats → "The iPhone couldn't take a photo.".
  - `base = dark.at(used)`, `net = max(mean − base, 0)`, `peak = max(net)/max(white − max(base), 1000)`.
  - If clipped or peak > 0.75:
    - if `used ≤ min*1.01` → "Too bright for the camera. Move the phone a little further from the TV."
    - else `seconds = used/4`, continue.
  - If `peak < 0.15 && used < max*0.99` → `seconds = min(used·min(0.4/max(peak, 0.002), 16), max)`, continue.
  - Else `duration = used` and return `CameraReading{rgb = net/used, duration = used, uneven, peak, settings, capturedAt = at}`.
- After 7 → "Couldn't find a good exposure for this patch.".

**CameraXYZMeter** (Meter):
- `integration = 0.25`; `now()`/`sleep` = MeterClock (uptime; Android `SystemClock.elapsedRealtimeNanos`/1e9).
- `read()`:
  - t0, `camera.measure()`, t1.
  - If nil: a problem starting "Too bright" → throw `MeasureError.message`; else sleep 0.3 s and return `MeterReading(t0, now, nil)`.
  - If checkSettings and `profile.camera.mismatches(settings)` is non-empty → throw "The camera is running with different settings than its profile was trained with (…). This can follow an iOS update: check for a newer profile in Settings.".
  - Return `MeterReading(t0, t1, profile.xyz(Vec3(rgb), panel))`.
- Setup test reading: uneven > 0.05 → "box isn't evenly lit".

## 5. Lab protocol (phone ↔ Mac `tvcal_app.py`, port 8765)

**Discovery**:
- The Mac runs `dns-sd -R "TV Calibrator on <host>" _tvcal._tcp local 8765 ip=<lan ip> port=8765 host=<host>.local` and re-advertises if its IP changes.
- The phone lists results that have a TXT `ip` (name, ip, port default 8765, hostName), sorted by name.

**Pairing** (Mac shows a 4-digit code):
- `POST http://<ip>:<port>/api/phone/pair` with `{"code","device"}`.
  - 200 → `{"token": hex32, "mac": hostname}`.
  - 403 → `{"error": "That code doesn't match…"}`. After 8 bad codes the Mac makes a new code.
- The phone tries the TXT ip, then host. It only falls through to the next address on "unreachable".
- On success: save host, port, token, macName, stop browsing, start polling. Failure text "Couldn't reach the Mac. Check that TV Calibrator is open on it and both are on the same Wi-Fi.".

**Auth**: every other route needs `?t=<token>` (or header `X-TVCal-Token`), otherwise 403 `{"error":"Pair this device first."}`.

**Poll**:
- `GET /api/phone/pattern?t=&ext=0&device=&cam=(ready?1:0)&model=<DeviceModel>` → `{"seq":Int, "pattern":{kind…}, "measure":Int}`.
- 403 → forget (clear keys, browse again), wait 1 s.
- OK → connected. Next poll in **120 ms**; on error or no pairing, **1 s**. Timeout error text: "No answer from host:port. Check the Mac's firewall…".
- If `seq != lastSeq` → `POST /api/phone/ack?t=` with `{"seq"}`. The Mac blocks up to 12 s on the ack in show_patch. The pattern itself is ignored with ext=0.
- If `measure > lastMeasure && !measuring` → in a new task, `camera.measure()` then `POST /api/phone/reading?t=` with:
  - `{"seq":m, "ok":Bool, "model", "ios": systemVersion, "rgb":[3], "duration", "uneven", "peak", "iso", "format", "lens", "aperture"?}`
  - or `{…, "ok":false, "error"}`. readingsSent++.
  - The Mac waits up to 25 s, drops unasked or late readings, and uses `ok`, `rgb`, `uneven` and CAM_KEYS (iso, format, lens, aperture). Its mismatch check treats aperture None vs set as a mismatch.
- lastSeq and lastMeasure start at −1. The Mac's `mseq` starts at unix−1.7e9, so it keeps rising across restarts.
- Mac "connected" means last_seen < 3 s.

**Log**: `POST /api/phone/log?t=` with `{"line":"camera: …","model"}`, only while connected (camera.onLog). Appended to ~/tvcal/phone.log.

**Mac-only routes** (not used by the phone): /api/phone/status, /show, /forget.

**Profile import** (lab): ProfileImport.parse accepts either:
- a CameraProfile JSON (valid), or
- the Mac export `{"tvcal_camera_profile":1, model, name, "M":3×3, camera{iso,format,lens,aperture}, stats{de_avg, unseen, tvs{name:…}}}` → version 0, tvs = sorted keys.

It must match this device's model; saved as imported.json.

## 6. Profile service

- **Config.server**: ServerConfig.plist `Server`, https only, no "REPLACE-WITH", trailing "/" added; nil means offline. `catalogFormat = 1`.
- **DeviceModel**: uname machine ("iPhone16,1"). Android: needs a scheme, e.g. Build.MODEL or MANUFACTURER+MODEL. It must be filename-safe, because the server path is `profiles/<model>-v<n>.json`.
- **Choose** (no network), in order:
  1. imported.json (valid)
  2. own profile: max of built-in `profile(for: model)` and current.json (same model, valid; higher version wins, ties → built-in)
  3. `builtIn.standardProfile` (source .standard)
  4. nil
- Status after choosing: ready if a profile was found, else checking.
- **refresh()**:
  - skipped if imported
  - no server → notConfigured or ready
  - GET `index.json` (200) → ProfileCatalog. If `minAppFormat > 1` → offline("…needs a newer version of the app…") when there is no profile.
  - Save catalog.json, set lastChecked.
  - No entry for this model → unsupported or ready.
  - Own version ≥ entry.version → ready.
  - Else GET `entry.file` relative to server (200). SHA-256 hex must equal `entry.sha256.lowercased()`. Decode, check model and isValid, save current.json, choose().
  - Any error → offline("Couldn't reach the profile server: …") if there is no profile, else ready.
- **Statuses and texts**: summary, profileLabel ("\<name\> v\<n\> (built in\|downloaded\|imported\|standard profile)"), `marginDE(panel)` = nil without a profile, 3 if standard, unknown panel, or no deAvg, else `max(1, round(de·4)/2)`; marginNote.
- **checkSettings is off for the standard profile.**
- **Android without a server**:
  - Built-in profiles alone are iPhone matrices. On Android RAW they are meaningless, because sensor scale and spectral response differ.
  - Recommendation: keep the logic verbatim. Ship an Android `builtin-profiles.json` that has no profiles and no standard until Android models are trained. Alternatively keep the iPhone standard profile but label it "very approximate"; that is a product decision.
  - Point `Config.server` at a BuildConfig field. The GitHub raw base `https://raw.githubusercontent.com/<fork-owner>/tv-calibrator/<branch>/server/` works: index.json, profiles/*.json and patterns.json resolve relatively, the SHA matches committed bytes, and the cache is about 5 min.
  - Push register/unregister (APNs) does not apply.

## 7. Recommended Android layout and port order

`android/app/src/main/kotlin/tvcal/android/`:

| Package | Contents |
|---|---|
| `app/` | AppModel, Chime, Notifications + RecheckWorker, Prefs (UserDefaults keys) |
| `camera/` | RawAnalysis (pure, JVM-tested), CameraEngine (Camera2), ApertureLock, CameraMeter, DarkLevel, CameraReading, CameraXYZMeter, CameraPreview |
| `measure/` | GuidedController, Log, SweepRunner, LiveSession, FreeMeter |
| `profiles/` | Config, DeviceModel, ProfileService, ProfileImport, PatternLinks, LinksService |
| `storage/` | TVRecord, GuideStep/Progress/Session, FinderData, GuideState, TVStore, Brands, Multipoint, PanelType, GamutCheck |
| `ui/theme/` | DesignSystem |
| `ui/components/` | Components, ReportVisuals, Playback, Walkthrough |
| `ui/screens/` | Home, ManageScreens, TVSetupFlow, TVHome, Guided steps, LiveSteps, Results, CameraSetup, LiveMeter, Settings, Welcome, Intro |
| `ui/nav/` | Routes |

Lab code goes in `src/lab/kotlin/tvcal/android/lab/` (LabLink, LabModeScreen, LabProfileImport), with a stub in `src/standard`. Lab manifest: cleartext network-security-config, CHANGE_WIFI_MULTICAST_STATE. Main manifest: CAMERA, INTERNET, POST_NOTIFICATIONS. Dependencies to add: navigation-compose, work-runtime-ktx.

**Port order**:
- P0: finish CalibrationKit. The app needs Measuring (Meter, MeterReading, MeterClock, MeasureError, Sweep, SweepEvent, Measurements, MissedPatch), Analysis, Guided (GuidePlan, LiveReading, LiveAnalysis), PointFinder, Simulation (SimTV.State codable, SimMeter, SimLiveMeter, ShownPatch). AutoCalibration is unused by the app but still has to be ported.
- P1: storage, brands, profiles and links, all JVM-testable. Test JSON round-trips against iOS-shaped files.
- P2: theme, navigation, Home / Manage / Setup flow / Screen home / Settings / Welcome / Intro, plus the whole guided flow on the **demo TV**, which needs no camera.
- P3: results, charts (Canvas versions of the Swift Charts), share image, export, chime, keep-screen-on.
- P4: RawAnalysis (unit tests with synthetic Bayer data), Camera2 engine, CameraMeter, Camera setup, Live meter.
- P5: Lab mode (NSD, pairing, polling, readings, profile import), then train Android profiles on the Mac.
- P6: re-check reminders via WorkManager. Decide whether to drop video pushes.

**Risky items**:
1. The meter's constants assume 14-bit RAW (16383, 1000, 2000, leak/flood 120 and 2000, peak denominator ≥ 1000). Many Android sensors are 10- or 12-bit (white 1023/4095, black pedestal about 64/256). Plateau detection never fires below 2000, and 120 counts is a large share of a 10-bit range. **Decide**: keep the constants verbatim, or scale them by white/16383. This needs an explicit ruling.
2. No Android profiles yet, and the standard iPhone profile is invalid on Android. The device-model key scheme is undecided, including Samsung regional variants.
3. Camera2 support varies: RAW + MANUAL_SENSOR capability, logical vs physical camera, 0.25 s exposure needs SENSOR_INFO_MAX_FRAME_DURATION ≥ 0.25 s, fixed-focus devices (minimum focus distance 0), AE/AF really off, and per-capture latency. Sweep sync depends on `integration = 0.25` and on read timing.
4. String formatting everywhere (`fmt`/`signed`, log.md tables) must use `cfmt`. Swift `.rounded()` (e.g. marginDE, clicks) must use `swiftRounded`.
5. Value semantics: GuideState, GuideProgress and TVRecord are structs mutated in place. Use immutable data classes with copy().
6. Swift Charts (GreyBalanceChart), the ImageRenderer share card, and the PinnedAction preference pattern need Compose redesigns.
7. NSD TXT parsing, multicast lock, and resolve quirks before API 34.
8. APNs pushes have no Android path without changes to the push worker.

Key source paths: `TV Calibrator/Camera/CameraMeter.swift`, `TV Calibrator/Camera/CameraXYZMeter.swift`, `TV Calibrator/Lab/LabLink.swift`, `TV Calibrator/Profiles/ProfileService.swift`, `TV Calibrator/Storage/TVStore.swift`, `mac-lab/TV Calibrator.app/Contents/Resources/tvcal_app.py` (Phone class at about line 260, routes at lines 2018–2230), `server/index.json`, `android/app/build.gradle.kts`.