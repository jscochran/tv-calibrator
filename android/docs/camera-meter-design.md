# Camera meter on Camera2: design (P4)

The Android port of `TV Calibrator/Camera/CameraMeter.swift` and `CameraXYZMeter.swift`. The phone's main
camera becomes the TV colour meter: RAW Bayer frames, centre-box photosite means, black and dark corrected,
per second of exposure, turned into XYZ by the phone model's camera profile.

Inputs: [the port map §4](research/ios-app-layer-map.md), [the Camera2 recipe](research/camera2-galaxy-s24plus.md),
[the measured phone specs](research/s24plus-camera-probe.md) and the two Swift files, which are the reference.
Where this document says "Swift", it means those files. Where the Android meter has to differ, it says why.

Two target phones are used for every worked number:

| | Galaxy S24+ (`samsung/e2q`) | Pixel 7 (`google/panther`) |
|---|---|---|
| Camera id 0 | logical (2, 5, 6), LEVEL_3 | logical (2, 3, 4), FULL |
| RAW_SENSOR | 4080×3060 GBRG | 4080×3072 GBRG |
| Black / white | 256 / 4095 (12-bit) | 64 / 1023 (10-bit) |
| ISO range (locked) | 25–3200 (**25**) | 47–11906 (**47**) |
| Exposure range | 50 µs–**0.150 s** | 26 µs–8.3 s |
| Lens | 5.4 mm f/1.8, min focus 10 D, OIS | 6.81 mm f/1.85, min focus 9.52 D, OIS |
| Lens shading in RAW | applied | not applied |

---

## 1. Files and ownership

The skeleton is in `app/src/main/kotlin/tvcal/android/camera/`. Bodies marked `TODO("p4")` are the work.
Three agents work in parallel. Each one edits only its own files.

| Agent | Files (main) | Tests (`app/src/test/kotlin/tvcal/android/...`) |
|---|---|---|
| **(a) RAW analysis**, pure Kotlin | `camera/RawAnalysis.kt` | `camera/RawAnalysisTest.kt`, `camera/SyntheticBayer.kt` (test helper) |
| **(b) Camera2** | `camera/CameraEngine.kt`, `camera/CameraMeter.kt` (adds `Camera2Meter`, flips `CameraMeters.create`), `camera/CameraSelection.kt`, `camera/FrameCheck.kt`, `camera/CameraPreview.kt`, `camera/CameraPermission.kt` | `camera/CameraSelectionTest.kt`, `camera/FrameCheckTest.kt` |
| **(c) Profile** | `camera/ProvisionalProfile.kt`, `camera/CameraColourReader.kt`, `camera/CameraXYZMeter.kt`, `profiles/ProfileService.kt` | `camera/ProvisionalProfileTest.kt`, `camera/CameraXYZMeterTest.kt`, `profiles/ProfileServiceTest.kt` (add cases) |

These shared files were changed with the skeleton and are already wired. Nobody needs to edit them:

- `TVCalApp.kt`: `camera = CameraMeters.create(this, prefs)`. `profiles` gets `attachApproximate(AndroidApproximateSource(...))`.
- `MainActivity.kt`: `onStart` → `camera.onAppForeground()`. `onStop` → `camera.onAppBackground()`, except
  on configuration changes. The window's `FLAG_KEEP_SCREEN_ON` follows `camera.running` (Swift `isIdleTimerDisabled`).
- `app/AppModel.kt`:
  - `cameraBlocker` uses `camera.unavailableReason`.
  - `startCamera()` calls `profiles.reloadApproximate()` when `camera.available`.
  - Meters take `checkSettings = profiles.checksSettings`.
- `ui/screens/CameraSetupView.kt`:
  - "Start the camera" goes through `rememberCameraStarter`. It is enabled when there is a profile, or when
    `profiles.approximateNeedsPermission` is true.
  - `CameraViewfinder` shows `CameraPreview(camera)` for a running `Camera2Meter`.
  - Meters use `checksSettings`.
- `ui/screens/RootView.kt`: the camera status card treats an approximate profile like the standard one
  (title "Approximate profile"). "Unavailable" shows `camera.unavailableReason`.

Interfaces are frozen. Agents use the shared types as declared and don't change them: `CameraMeter`,
`CameraReading`, `DarkLevel`, the message constants in `CameraMeter.Companion`, `RawStats`, `Thresholds`,
`CameraCandidate`, `CameraColourData`, `ApproximateSource` and `ApproximateResult`. Dependencies run one way:

- (b) calls (a): `RawAnalysis`, `FrameAccumulator`, `FramePlan`, `WhiteLevel`, `ExposureSearch`, `DarkCheck`.
- (c) calls (b): `CameraCatalog`, `CameraSelection`.
- At run time, `AppModel.startCamera` calls (c)'s `reloadApproximate`, but only when `camera.available`.
  Until (b) flips `CameraMeters.create`, `available` is false.

**Landing order:** land (a) and (c) before (b) flips `CameraMeters.create`. Until then the app runs exactly as
before, with the demo TV and the stub camera.

## 2. Swift → Android map

| Swift | Android | Notes |
|---|---|---|
| `RawStats` | `RawStats` (RawAnalysis.kt) | Adds `quarters` and `black`. `mean` is **black-subtracted** (§7). |
| `RawAnalysis.measure(buffer, white, floor)` | `RawAnalysis.measure(plane, layout, black[4], white, floorNet, th)` | `RawPlane` wraps `Image.planes[0]`. `CfaLayout` comes from `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT`. |
| pixel format → `cell` | `Cfa.fromCamera2(code)`, `CfaLayout.channel/index` | 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR. Anything else → unsupported. |
| `RawAnalysis.fullScale` 16383 | `Thresholds` (rescaled, §5) | |
| `CameraEngine` (session, output, queue) | `CameraEngine` | CameraDevice, CameraCaptureSession, the RAW ImageReader, threads "tvcal.camera" and "tvcal.raw" |
| `engine.start(done)` | `suspend fun open(wantedAperture): String?` | null = ok, else the message |
| `engine.stop()` | `close()` | Android releases everything (§11). The Swift keeps the device configured. |
| `engine.capture(duration, learnsWhite, done)` → `(stats, used, at)` | `suspend fun capture(seconds, learnsWhite): Shot?` | `Shot(stats, frameExposure, frames, at, white, requested)`. `duration = frames × frameExposure` (reported); `requested = FramePlan.total` (the clamped request: Swift `used`, §9). |
| `Ticket`, `pendingID`, timeout `d + 8 s` | request tag = shot serial; stale frames ignored; `withTimeoutOrNull(perFrame × frames + 8 s)` | |
| `photoOutput(didFinishProcessingPhoto:)` | `ImageReader` listener + `onCaptureCompleted`, paired by `SENSOR_TIMESTAMP` | §3.4 |
| `photoOutput(didFinishCaptureFor:)` without a photo | `onCaptureFailed` / `onCaptureBufferLost` → retake the frame | |
| `dngWhiteLevel(photo)` once per launch | `SENSOR_DYNAMIC_WHITE_LEVEL` per frame, falling back to `SENSOR_INFO_WHITE_LEVEL` | `WhiteLevel.onFrame` (§7) |
| `whiteLevel`, `setWhite`, learned from a plateau | `WhiteLevel.learned`, `WhiteLevel.learn` | |
| `floorLevel`, `setFloor` | `WhiteLevel.floorNet`, `setFloor`, `engine.setFloor` | |
| `iso`, `minDuration`, `maxDuration` | `iso`, `minDuration`, `frameMax`, `maxDuration` (always 1/4 s) | §6 |
| `formatDescription` "8064x6048 bgg4" | `CameraSelection.formatOf` → "4080x3060 GBRG" | |
| `lensDescription` (localizedName) | `CameraSelection.lensOf` → "Back camera 0" | |
| `ApertureLock.choose/setExposure` | `ApertureLock.choose(apertures, wanted)` + `LENS_APERTURE` in every request | Fixed apertures → null (both target phones). |
| runtime-error notification → restart | `StateCallback.onDisconnected/onError` → reopen (§11) | |
| `NotificationCenter` / `@MainActor` | Compose state on Main; the engine on its own threads | |
| `CameraReading` | `CameraReading` (+ `frames`, `frameExposure`) | |
| `DarkLevel{short,long}` | `DarkLevel{short,long,longAt?,camera?}` | §8. JSON still `{"short":[..],"long":[..]}` when both extras are null. |
| `CameraMeter` (ObservableObject) | `interface CameraMeter` + `class Camera2Meter` | Fair `Mutex` = Swift acquire/release. |
| `AVCaptureDevice.requestAccess` | `rememberCameraStarter` + `hasCameraPermission` | §12 |
| `CameraXYZMeter` | `CameraXYZMeter` | §14 |
| `CameraPreview` / `CameraViewfinder` | `CameraPreview(camera: Camera2Meter)` inside `CameraViewfinder` | §10 |

`Camera2Meter` state is all Compose snapshot state, set on Main:

- `running`, `busy`, `message`, `dark`, `lastReading`, `logLines` (the last 200 lines, "HH:mm:ss  line"),
  and `onLog("camera: " + line)`.
- `duration` (starts at 1/250 s, then the last good reading's total shutter time).
- `suspended`: the app went to the background while running.

## 3. Camera2 session and request recipe

### 3.1 Streams and session

- **RAW:** `ImageReader.newInstance(rawW, rawH, ImageFormat.RAW_SENSOR, maxImages = 4)`, at the largest
  RAW_SENSOR size by area: S24+ 4080×3060, Pixel 7 4080×3072.
- **Preview:** a PRIVATE `Surface` from the viewfinder's `TextureView`. Its size is the largest
  SurfaceTexture output with the RAW aspect, at most 1440×1080. It is only there while the viewfinder is on
  screen. PRIV preview + RAW at maximum size is a guaranteed stream combination on RAW-capable devices.
- **Session:** `createCaptureSession(SessionConfiguration(SESSION_REGULAR, outputs, executor, cb))` on the
  camera thread.
  - The output list is RAW only, or RAW + preview.
  - `setPreview(surface)` re-creates the session. That happens only when the Camera setup screen appears or
    leaves. The engine serialises it with captures on the camera thread.
  - `onConfigureFailed` → "Couldn't set up the camera."

### 3.2 Capture requests

Every request comes from `createCaptureRequest(TEMPLATE_MANUAL)`:

| Key | Value |
|---|---|
| `CONTROL_MODE` | OFF |
| `CONTROL_AE_MODE`, `CONTROL_AF_MODE`, `CONTROL_AWB_MODE` | OFF, OFF, OFF |
| `FLASH_MODE` | OFF |
| `SENSOR_SENSITIVITY` | `SENSOR_INFO_SENSITIVITY_RANGE.lower`: S24+ 25, Pixel 47 |
| `SENSOR_EXPOSURE_TIME` | `FramePlan.perFrame` in ns, `(s × 1e9).roundToLong()` |
| `SENSOR_FRAME_DURATION` | max(exposure, `getOutputMinFrameDuration(RAW_SENSOR, size)`, the same for the preview size if attached) |
| `LENS_FOCUS_DISTANCE` | `LENS_INFO_MINIMUM_FOCUS_DISTANCE`, the closest focus (iPhone lensPosition 0): S24+ 10 D, Pixel 9.52 D. Skipped when 0 (fixed focus). |
| `LENS_APERTURE` | `ApertureLock.choose(...)` when not null |
| `LENS_OPTICAL_STABILIZATION_MODE` | OFF, if listed in `LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION` |
| `BLACK_LEVEL_LOCK` | true |
| `HOT_PIXEL_MODE` | OFF |
| `NOISE_REDUCTION_MODE`, `EDGE_MODE`, `COLOR_CORRECTION_ABERRATION_MODE`, `SHADING_MODE`, `DISTORTION_CORRECTION_MODE` (API 28+) | OFF, where each is listed as available |
| `STATISTICS_LENS_SHADING_MAP_MODE` | OFF |
| `CONTROL_ZOOM_RATIO` (API 30+) | 1.0 |
| `SCALER_CROP_REGION` | the full active array (API 29) |
| `CONTROL_CAPTURE_INTENT` | MANUAL (the template's) |
| tag | the shot serial (`setTag`) |

- **Targets:** RAW captures target the RAW surface only. The repeating request (§3.5) targets the preview only.
- **Same settings everywhere:** the Lab build and the app use the same code. RAW_SENSOR is documented as
  unaffected by NR, edge and shading, but setting them OFF makes every phone run the same way. On the S24+,
  lens shading is applied to RAW whatever `SHADING_MODE` says (`SENSOR_INFO_LENS_SHADING_APPLIED` = true). The
  centre box is fixed and the profile is trained through the same pipeline, so the profile absorbs it.

### 3.3 Reading the RAW plane

- **Plane layout:** `image.planes[0]` holds 16-bit samples. `pixelStride` (2) and `rowStride` are in bytes,
  and rows may be padded. `RawPlane.at(x, y) = buffer.getShort(y·rowStride + x·pixelStride).toInt() and 0xFFFF`,
  read little-endian: use `buffer.duplicate().order(LITTLE_ENDIAN)`, never the shared buffer's own order.
- **CFA:** from `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT`, never hardcoded. Both target phones are GBRG.
- **CFA parity:** the arrangement describes the sensor's top-left 2×2.
  - If the RAW size equals `SENSOR_INFO_PIXEL_ARRAY_SIZE`, the offset is (0, 0).
  - Otherwise, if it equals `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE`, the offset is (left & 1, top & 1)
    of that rectangle.
  - Otherwise the offset is (0, 0), and the start-up log says the size matched neither.
  - Then `CfaLayout.index(x, y) = ((y + oy) & 1)·2 + ((x + ox) & 1)` and `channel = cfa.channel(index)`.
  - The on-device colour check (§16.2) confirms the result.
- **Black levels:** `SENSOR_DYNAMIC_BLACK_LEVEL` (`float[4]`) from the frame's result, indexed in CFA order, the
  same index as above. If it's missing, use `SENSOR_BLACK_LEVEL_PATTERN.getOffsetForIndex(i & 1, i >> 1)`.
- **White level:** `SENSOR_DYNAMIC_WHITE_LEVEL`, else `SENSOR_INFO_WHITE_LEVEL` (§7).

### 3.4 Pairing and per-frame checks

Images and results are paired on the camera thread:

1. The ImageReader listener runs on the camera thread and calls `acquireNextImage()`.
2. `onCaptureCompleted` arrives with a TotalCaptureResult.
3. They are paired by `image.timestamp == result[SENSOR_TIMESTAMP]`.
4. A pair whose request tag isn't the current shot's serial is closed and ignored. This is the Swift
   `pendingID` rule for late photos.
5. A paired frame is checked by `FrameCheck.problem(FrameRequest, FrameReport)`:
   - **Exposure:** reported `SENSOR_EXPOSURE_TIME` within ±10% of the request. The HAL rounds to sensor lines,
     and the meter divides by the **reported** value anyway. A frame outside that range used stale settings.
   - **ISO:** `SENSOR_SENSITIVITY` = the locked ISO.
   - **Black level:** `BLACK_LEVEL_LOCK` = true in the result. False means the black level changed.
   - **Focus:** `LENS_STATE` = STATIONARY, and `LENS_FOCUS_DISTANCE` within max(5%, 0.1 D) of the request.
   - **Lens:** on logical cameras, `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` = the id seen while settling.
     Otherwise the log says "lens switched to X (was Y)".
   - **Auto exposure:** `CONTROL_AE_MODE` = OFF.
   - **Missing keys** are not failures. Without an exposure time, the engine divides by the requested one and
     logs that once.
6. A rejected frame is logged ("Frame rejected: <reason>"), closed and retaken. After 3 tries the shot fails
   (null).
7. An accepted frame goes to the "tvcal.raw" thread: `RawAnalysis.measure`, then `image.close()`. The stats
   and the reported exposure come back to the camera thread for `FrameAccumulator` and white-level learning.

### 3.5 Settling

At `open()`:

1. Send a repeating request (preview only if a preview is attached; otherwise a 4-frame RAW burst that is
   thrown away) at 1/250 s with every lock above.
2. Wait until a result shows `LENS_STATE` STATIONARY with the focus distance in tolerance, at most 3 s.
3. Record the active physical id (logical cameras only).
4. Log "Focus locked at 10.0 D" (cfmt 1 decimal).

The repeating request then follows the last capture's exposure, so the preview shows what is measured, as on
the iPhone.

Exposure latency (`SYNC_MAX_LATENCY`):

- **PER_FRAME_CONTROL (0)**, which FULL and LEVEL_3 report: each burst request carries its own settings.
- **Otherwise:** when the exposure changes, first update the repeating request, then prepend `latency` extra
  RAW frames to the burst and discard them. FrameCheck still catches anything that slips through.

### 3.6 Timestamps

`SENSOR_TIMESTAMP` is the start of the exposure.

- **Clock:** with `SENSOR_INFO_TIMESTAMP_SOURCE` = REALTIME it is on the `elapsedRealtimeNanos` clock.
  Convert it with `ts + (System.nanoTime() − SystemClock.elapsedRealtimeNanos())`, sampled when the frame
  arrives. With UNKNOWN it is already on the monotonic clock (`System.nanoTime`), which is `MeterClock`'s.
- **A shot's time:** `Shot.at = (firstStart + lastStart + lastExposure) / 2 / 1e9`, the middle of the
  integration, as Swift's `started + d/2`.
- **Fallback:** without timestamps, the MeterClock time when the burst was sent + total/2.

## 4. Camera selection (`CameraSelection.choose`)

Candidates come from `CameraCatalog.read(manager)`: every id in `getCameraIdList()`, in order. The selection
reads only facing, capabilities, hardware level, focal lengths, apertures, pixel array, RAW sizes, CFA, ISO and
exposure ranges.

Facing and capabilities need no permission. Keys returned by `getKeysNeedingPermission()` read as null before
CAMERA is granted. So `available` uses facing + capabilities only, and the engine re-reads everything at open.

1. **Eligible:**
   - back-facing
   - RAW (3) **and** MANUAL_SENSOR (1)
   - a Bayer CFA (0–3)
   - a RAW_SENSOR size
   - ISO and exposure ranges
2. **Primary:** the first back-facing id in list order if it is eligible. That is the CDD's primary back
   camera, normally "0". Otherwise, the eligible camera with the largest pixel array (ties → lowest numeric id).
3. **Twin:** another eligible, **non-logical** id whose main focal length matches the primary's (±0.05 mm),
   with the same first aperture and the same RAW size. The main focal length is the single entry, or the
   first listed (zoom ratio 1).
4. **Choice:** if the primary is logical and a twin allows at least 1.2× its max exposure, the meter uses the
   twin. Twins are ranked by hardware level, then max exposure, then lowest numeric id. Otherwise it uses the
   primary.
5. **Unsupported:**
   - no back camera → `NO_BACK`
   - back cameras but none with RAW → `NO_RAW`
   - RAW but no MANUAL_SENSOR → `NO_MANUAL`

**Why prefer the twin:**

- A standalone physical camera can't switch lenses behind the meter's back.
- A ≥ 0.25 s single frame matches the iPhone pipeline exactly (no multi-frame shots, a full 1/30 → 1/4 s dark
  interval).

The choice is deterministic for a given `getCameraIdList()`, so the Lab build and the app pick the same id.

The cost: if a firmware update hides or shows a twin, `lens` changes ("Back camera 56" vs "Back camera 0").
A trained profile then reports a settings mismatch and tells the user to check for a newer profile. That is the
iPhone behaviour after an iOS update.

At start the engine logs every candidate (`CameraCatalog.describe`) and the reason for its choice.

**Expected choices:**

- **S24+:** "0", the logical camera, main GN3 at zoom 1, unless ids 23/56 appear in `getCameraIdList()`.
  Samsung normally hides ids ≥ 20 from third-party apps. If they do appear and qualify, the meter uses "23"
  (lower id, same rank). **Check this on the device** (§16.2).
- **Pixel 7:** "0", logical, main 6.81 mm. There's no twin and no exposure ceiling problem.

**Recorded settings** (`CameraSelection.settingsFor`):

- `iso` = the locked ISO
- `format` = "4080x3060 GBRG" (S24+) or "4080x3072 GBRG" (Pixel 7)
- `lens` = "Back camera 0"
- `aperture` = null (fixed lenses)

`capturedSize` is "WxH" of the delivered buffer. It is logged on change: "RAW photos are 4080x3060 (only the
centre 10% is read)".

## 5. Thresholds and their rescaling

The Swift constants assume 14-bit RAW with full scale 16383. Android sensors here are 10- or 12-bit, with a
black pedestal. The meter works in **net counts** (black subtracted) and scales every count constant by the
camera's signal range:

    B0 = mean(SENSOR_BLACK_LEVEL_PATTERN), W0 = SENSOR_INFO_WHITE_LEVEL, R = W0 − B0, s = R / 16383

These are static values, so the thresholds don't move from frame to frame.

- S24+: R = 3839, **s = 0.234328**.
- Pixel 7: R = 959, **s = 0.058536**.

Shares and ratios (0.98, 0.75, 0.15, 0.4, 0.002, ×16, ÷4, 1%) are unitless and stay as they are.

| Swift check | Swift constant | Android rule | S24+ | Pixel 7 |
|---|---|---|---|---|
| clip level | `UInt16(min(max(white,1000),16383)·0.98)` = 16055 | `clipLevel(w) = floor(B0 + 0.98·(clamp(w, B0 + 1000s, 65535) − B0))`, absolute | **4018** | **1003** |
| believable white (DNG / learned / reset) | w > 1000 | (w − B̄) > 1000·s | 234.3 | 58.5 |
| plateau minimum top | top > max(2000, floor·2) | (top − B̄) > max(2000·s, 2·floorNet) | 468.7 (abs 724.7) | 117.1 (abs 181.1) |
| plateau band | max(2, top·3/1000) on absolute top | unchanged (relative to the value) | 12 at 4095 | 3 at 1023 |
| plateau share | 1% of box | unchanged | | |
| learn white from a plateau | maxRaw < white·0.98 | maxRaw < white − 0.02·(white − B̄) | < 4018.2 | < 1003.8 |
| white vs dark sanity | w > floor·3 | (w − B̄) > 3·floorNet | | |
| peak denominator floor | max(white − max(base), 1000) | max((white − B̄) − max(base), 1000·s) | 234.3 | 58.5 |
| exposure targets | peak > 0.75 shorten ÷4; < 0.15 lengthen ×min(0.4/max(peak, 0.002), 16) | unchanged (shares) | | |
| too bright | used ≤ min·1.01 | unchanged | | |
| dark leak / flood | 120 | 120·s on the leak normalised to 1/30 → 1/4 s (§8) | 28.1 | 7.0 |
| "still seeing light" | 2000 | 2000·s | 468.7 | 117.1 |
| uneven box (setup) | > 0.05 | unchanged, computed on black-subtracted quarters | | |

**Clipping on the plateau scale.** A dark frame's top photosite sits a few to ~20 counts above black (5σ of
read noise). That is far below 2000·s on both phones, so a dark frame is never taken for a ceiling.

**floorNet.** On the iPhone, `floor` included the pedestal. On Android, `floorNet` is the dark residual left
after the black level, about 0–2 counts. So the `floor` terms are kept for parity but practically never decide
anything.

**Uneven.** The iPhone computed quarter means including its pedestal, which shrinks the ratio a little.
Android uses the black-subtracted signal. For a well-exposed test patch (peak 0.15–0.75), the difference is
under ~20% of the ratio. The 0.05 limit stays.

## 6. Multi-frame integration

- **Two limits:**
  - `frameMax = min(exposure upper, 1/4 s)`: S24+ id 0 **0.150 s**, Pixel **0.25 s**.
  - `maxDuration = 1/4 s`: the Swift `min(format max, 0.25)` on every phone that allows 1/4 s.
- **The plan:** `minDuration = max(exposure lower, 1/8000 s)` = 125 µs on both phones.
  `FramePlan.of(seconds, minDuration, frameMax, maxDuration)` does:
  - d = clamp(seconds, min, max)
  - N = ceil(d / frameMax − 1e-9), from 1 to 16
  - perFrame = d / N
  - On the S24+, 0.25 s → 2 × 0.125 s, and 0.16 s → 2 × 0.08 s. On the Pixel, always 1 frame.
- **Capture:** the N frames go out as one `captureBurst` of identical requests. Each frame is checked and
  analysed on its own; a failed frame is retaken.
- **Combining frames** (`FrameAccumulator`):
  - `mean` and `quarters`: the mean of the frames' values (equal box sizes).
  - `uneven`: recomputed from the averaged quarters.
  - `maxRaw`: the maximum.
  - `clipped` and `plateau`: true if any frame was.
  - `black`: the mean.
- **Normalisation:** `frameExposure` = the mean of the frames' **reported** exposures, and
  `rgb = net / frameExposure`. That is the same as total counts / total time.
- **Dark level:** taken at the per-frame exposure, `base = dark.at(frameExposure)`, because dark current
  builds up per frame.
- **Reporting:**
  - `CameraReading.duration` = N × frameExposure, the requested duration as one reading.
  - `frames` = N, and `frameExposure` is recorded too.
  - `peak` is the **equivalent single-exposure fill**, per-frame peak × N, so the exposure search behaves
    exactly as on the iPhone (§9). Clipping is still judged per frame, on the real sensor.
  - `CameraXYZMeter.integration` stays 0.25 s.

## 7. Black and white levels

- **Black (per frame):**
  - The four `SENSOR_DYNAMIC_BLACK_LEVEL` values, else the static pattern, are subtracted per CFA index from
    every photosite's contribution to `mean` and `quarters`.
  - `RawStats.black` = their mean.
  - `BLACK_LEVEL_LOCK` keeps the HAL from re-estimating the black level between frames. A frame whose result
    shows the lock released is rejected (§3.4).
  - The measured dark level is a **residual** on top, as the probe recommends.
- **White (per frame):** `SENSOR_DYNAMIC_WHITE_LEVEL`, else `W0`. `WhiteLevel.onFrame(w, B̄)`:
  - accepts w when (w − B̄) > 1000·s and (w − B̄) > 3·floorNet. Otherwise it uses `W0`, logs
    "Sensor white level W doesn't look right: using W0" once, and treats the frame as one at `W0`.
  - logs "Sensor white level 4095, black level 256 (from the camera's RAW data)" on the first frame. That
    replaces the Swift's one-off DNG read.
- **Effective white:** `WhiteLevel.current(w) = min(accepted w, learned ?: ∞)`. It goes into
  `RawAnalysis.measure` and into the peak denominator.
- **Learning:** `WhiteLevel.learn(stats, white, learns)`, only when `learnsWhite`.
  - The rule: if `stats.plateau` and `maxRaw < white − 0.02·(white − B̄)`, then `learned = maxRaw`.
  - It logs "Sensor white level N (learned from a clipped photo)".
  - Learning is per frame, kept until the engine closes (Swift: per launch).
- **Reset:** `setFloor(floorNet)`: if floorNet > 0 and (white − B̄) < 3·floorNet, forget `learned` and log
  "Sensor white level W is too close to the dark level L: ignoring it". The Swift goes back to 16383; Android
  goes back to the reported level.

## 8. Dark level

`DarkLevel` is stored as a JSON string under pref `darkLevel`:

- `short`, `long`: net counts, R G B.
- `longAt`: null when the long shot was 1/4 s.
- `camera`: lens + " " + format, e.g. "Back camera 0 4080x3060 GBRG".

`at(t)` is linear in the **per-frame** exposure between `tShort = 1/30` and `longAt ?: 1/4`, and extrapolates
outside that range (as the Swift).

`measureDark()` (busy, message `MEASURING_DARK`), all shots with `learnsWhite = false`:

| Shot | Swift | Android | S24+ | Pixel 7 |
|---|---|---|---|---|
| c | minDuration | minDuration, 1 frame | 125 µs | 125 µs |
| a | 1/30 s | 1/30 s, 1 frame | 1/30 | 1/30 |
| b | 1/4 s | **min(1/4 s, frameMax), 1 frame**, `longAt` = reported exposure | 0.150 s | 0.25 s (`longAt` null) |

- **Why b is one frame at frameMax:** readings never use a per-frame exposure above `frameMax`. Measuring the
  long dark there covers every reading without extrapolating upwards, and one frame measures dark current
  honestly. Averaged short frames would only measure the dark at their own shutter time.
- **Any nil shot** → `NO_PHOTO_RETRY`.
- **Leak and flood** (`DarkCheck.evaluate`):
  - leak = max_ch(b − a) × (1/4 − 1/30) / (longAt − 1/30). On the S24+ that factor is ×1.857: light leaking
    in is judged as if the shot had been 1/4 s.
  - flood = max_ch(a − c).
  - Rejected if leak > 120·s or flood > 120·s.
  - Log: "Dark rejected: brightest colour reads %.0f at 1/%.0f s, %.0f at 1/%.0f s, %.0f at 1/%.0f s (white
    level %.0f)", written with cfmt.
  - The message is `DARK_STRONG` if max(leak, flood) > 2000·s, else `DARK_LEAK`.
- **Accepted:**
  - `dark = DarkLevel(a.mean, b.mean, longAt, camera)`, saved.
  - `engine.setFloor(max(short))`.
  - Log "Dark at 1/30 s: r g b; at 1/7 s: r g b" (cfmt 1 decimal; the long shot is labelled 1/round(1/longAt)).
  - Message `READY_POINT`.
- **At init:** the saved dark is loaded and passed to `engine.setFloor(max(short))`.
- **At start:** if `dark.camera` is set and differs from this camera, the meter drops `dark` (but not the
  pref). It logs "The saved darkness was measured on another camera (<old>): measure it again", and the message
  is `COVER`.

## 9. Measure and the exposure search

`measure()` holds the mutex, sets `busy`, and needs `ready` (else `NOT_READY`). Then:

```
seconds = duration                              // 1/250 s at first, then the last good total
repeat ExposureSearch.MAX_SHOTS (7):
    shot = engine.capture(seconds) ?: { log "Measurement failed: no usable photo"; return NO_PHOTO }
    base = dark.at(shot.frameExposure)
    step = ExposureSearch.step(shot.stats, shot.requested, shot.frames, base,
                               whiteNet = shot.white − shot.stats.black, th, minDuration, maxDuration)
    TooBright → return TOO_BRIGHT
    Next(s)   → seconds = s
    Accept(net, peak) → duration = shot.duration
                        reading = CameraReading(rgb = net / frameExposure, duration = shot.duration,
                                  uneven = stats.uneven, peak, settings, capturedAt = shot.at,
                                  frames = shot.frames, frameExposure = shot.frameExposure)
                        lastReading = reading; return reading
return NO_EXPOSURE
```

`ExposureSearch.step` is the Swift loop body, with used = `shot.requested`, the plan's total (the asked time
clamped to [min, max], as the Swift's `used`). It is not the reported total: the HAL rounds the exposure to
whole sensor lines and FrameCheck accepts ±10%, so at 1/8000 s a frame reported 4% long would miss the 1%
margin, never return TooBright and end in `NO_EXPOSURE`; likewise a 2 × 0.125 s shot reported a little short
would keep asking for 0.25 s. The reported per-frame exposure stays the divisor for `rgb` and for `dark.at`.

- net = max(mean − base, 0)
- peak = max(net) / max(whiteNet − max(base), 1000·s) × frames
- clipped or peak > 0.75: TooBright if used ≤ min·1.01, else Next(used / 4)
- peak < 0.15 and used < max·0.99: Next(min(used·min(0.4/max(peak, 0.002), 16), max))
- else Accept

Worked numbers. A 100 cd/m² white saturates the S24+ in about 0.155 s (§13.5):

- 1/250 s gives peak ≈ 0.026, so the next try is 0.062 s, peak ≈ 0.40: accepted.
- A 0.5 cd/m² near-black ends at 0.25 s, taken as 2 × 0.125 s, and is accepted at the maximum.

## 10. Preview

`CameraPreview(camera: Camera2Meter)` is an `AndroidView { TextureView }`:

- **Surface available:** `setDefaultBufferSize(previewSize)`, then `engine.setPreview(Surface(st))`.
- **Surface destroyed:** `engine.setPreview(null)` (blocks ≤ 1 s until the session no longer uses it), then
  return true.
- **Transform:**
  - rotate by `SENSOR_ORIENTATION` (90° on both phones; the activity is portrait-only) about the centre
  - scale to **aspect-fit**, never cropped
  - The 3:4 viewfinder then shows the whole sensor, and the yellow 10%×10% box marks exactly the measured
    box. Rotating swaps width and height, but the box is 10% of each.
- **Colour:** the preview runs with AWB off, so its colours are odd. It only shows framing and exposure
  (as the iPhone's locked white balance).

`CameraViewfinder` already shows it under the box while `camera.running`. The accessibility texts are
unchanged.

## 11. Threading, lifecycle, errors

**Threads:**

- `HandlerThread("tvcal.camera")`: CameraDevice / session / capture callbacks, the ImageReader listener, and
  every piece of engine state. `cameraDispatcher = Handler(looper).asCoroutineDispatcher()`, and the engine's
  suspend functions run `withContext(cameraDispatcher)`.
- `HandlerThread("tvcal.raw")`: `RawAnalysis.measure`, about 125 k photosites, ~1 ms.
- **Main:** all Compose state. Engine log lines go `postLog` → Main → `log(line)`.

**Box geometry:** the box is 408×306 on both phones, at (1836, 1376) on the S24+ and (1836, 1382) on the
Pixel. That is 124,848 photosites: R 31,212, G 62,424, B 31,212. Its height is 306, so the quarter split at
+153 cuts through a 2×2 row. Quarter sums therefore keep their own per-CFA-index counts for black subtraction.

**Lifecycle:**

- `start` → `engine.open` → threads, device, reader, session, settle.
- `stop()` → `engine.close()`: stop repeating, close the session, the device and the reader, and quit both
  threads. Then `running = false` and the message is `OFF`.
- `onAppBackground()`: if running, `engine.close()` and `suspended = true`. `running` and `dark` are kept, as
  with an interrupted iPhone session.
- `onAppForeground()`: if `suspended`, reopen (log "Camera back on"). If that fails: `running = false`,
  message = the error. A measurement while closed fails fast: `NO_PHOTO`, or a missed reading for the sweep.

**Errors:**

- **`onDisconnected`:** log "The camera stopped (disconnected: another app may be using it). Starting it
  again." Retry `open` after 1, 2 and 4 s while running and in the foreground.
- **`onError(code)`:** log "The camera stopped (<reason>). Starting it again.", then the same retries. The
  reasons:
  - 1 → "the camera is in use by another app"
  - 2 → "too many cameras are open"
  - 4 → "camera device error"
  - 5 → "camera service error"
  - 3 is CAMERA_DISABLED: no retry. Message "The camera is disabled on this phone (device policy)."
- **Waiting for the camera:** while retrying, a `CameraManager.AvailabilityCallback` (main looper) watches the
  chosen id. `onCameraAvailable(id)` or `onCameraAccessPrioritiesChanged()` starts the next try at once.
- **After 3 failed retries:** if another app still holds the camera (the last error is "in use by another
  app", "too many cameras are open" or "disconnected: another app may be using it"), log "Another app is using
  the camera: it starts again when the camera is free" and keep waiting. Each callback leads, after 0.5 s, to
  another try, and success logs "Camera back on". `running` stays true meanwhile, as an interrupted iPhone
  session resumes by itself, and readings fail fast (`NO_PHOTO` / a missed sweep reading). Any other failure,
  or no callback: `running = false`, message "Couldn't set up the camera: <why>". `close()` (stop, background)
  cancels the retries and unregisters the callback.
- **`close()` while opening:** `onOpened` closes the device and the open finishes at once as `OFF` (never the
  10 s open timeout), so `start()` finishes as suspended in the background, or as stopped.
- **`CameraAccessException`:** opening, the session or a capture → "Couldn't set up the camera: <message>".
  Its reasons are named as above.
- **`SecurityException`:** → `PERMISSION`.
- **`onCaptureFailed`:** log "Photo failed: <reason>" (0 ERROR → "capture error", 1 FLUSHED → "flushed") and
  retake the frame.
- **Timeout:** "The camera didn't answer in time", shot null.

Keep-screen-on is on while `running` (MainActivity).

## 12. Permissions and texts

**Permission:**

- The manifest already declares CAMERA and `android.hardware.camera`.
- "Start the camera" goes through `rememberCameraStarter`: with the permission it calls `start()` at once.
  Without it, it asks first, then calls `start()` whatever the answer.
- `Camera2Meter.start` checks `hasCameraPermission`. When denied, the message is `PERMISSION`. The setup
  screen shows it as a LIMIT tip, because it doesn't end in "…".
- After "don't ask again", the launcher returns false at once, so the same message appears.

**User-visible texts** (`CameraMeter.Companion`, Swift wording, iPhone → phone):

| Constant | Text |
|---|---|
| OFF | The camera is off. |
| PERMISSION | TV Calibrator needs the camera to measure the TV. Allow it in Settings > Apps > TV Calibrator > Permissions > Camera. |
| STARTING | Starting the camera… |
| COVER / READY_TO_MEASURE | Cover the lens and measure the darkness once. / Ready to measure. |
| MEASURING_DARK | Measuring with the lens covered… |
| NO_PHOTO_RETRY | Couldn't take a photo. Try again. |
| DARK_STRONG / DARK_LEAK | (Swift texts, verbatim) |
| READY_POINT | Ready. Point the box at the centre of the TV. |
| NOT_READY / NO_PHOTO | The camera isn't ready. / The phone couldn't take a photo. |
| TOO_BRIGHT / NO_EXPOSURE | Too bright for the camera. Move the phone a little further from the TV. / Couldn't find a good exposure for this patch. |
| SETUP_FAILED | Couldn't set up the camera. (+ ": <why>") |
| CameraSelection.NO_BACK / NO_RAW / NO_MANUAL | This phone has no back camera. / This phone can't take RAW photos, which the camera meter needs. / This phone's camera can't be set by hand (shutter time and ISO), which the camera meter needs. |

**Availability:**

- `Camera2Meter.available` = an eligible camera exists, from facing + capabilities only, with no permission.
- When it is false, `unavailableReason` = the selection's message + " You can still try the demo."
- Log lines follow the Swift ones, numbers formatted with `cfmt`:
  - "Setting up the back main camera"
  - "RAW 4080x3060 GBRG, ISO 25, fixed aperture f/1.80, shutter 1/8000–0.150 s"

## 13. Provisional profile (phone's own colour data)

### 13.1 Inputs

`CameraColourData` holds the chosen camera's colour data:

- the reference illuminants (`SENSOR_REFERENCE_ILLUMINANT1/2`)
- `SENSOR_COLOR_TRANSFORM1/2` (CMᵢ: XYZ → reference camera)
- `SENSOR_FORWARD_MATRIX1/2` (FMᵢ: white-balanced reference camera → XYZ D50)
- `SENSOR_CALIBRATION_TRANSFORM1/2` (CCᵢ: reference → this unit's camera)
- ISO, f-number, black B0 and white W0
- the settings

These colour keys need the CAMERA permission on Android 10+, which is why `ApproximateResult.NeedsPermission`
exists.

### 13.2 Interpolation for a D65 white

- The temperatures are the DNG SDK's: A/tungsten 2850, ISO studio tungsten 3200, D50 5000,
  D55/daylight/fine weather/flash/B 5500, D65/C/cloudy 6500, D75/shade 7500, daylight fluorescent 6400,
  day white fluorescent 5050, cool white/fluorescent 4150, white fluorescent 3525.
- The white is D65, so T = 6504 K.
- g = (1/T − 1/T2) / (1/T1 − 1/T2), clamped to [0, 1]. g = 1 with one calibration.
- With the usual pair (A, D65), g = 0: calibration 2 is used.
- X = g·X1 + (1 − g)·X2 for CM, CC and FM. A missing CC is the identity.

### 13.3 Camera neutral

XYZ_D65 = (0.95046, 1, 1.08906), from xy 0.3127 / 0.3290 (`ColourMath.d65`).

n = (CC·CM)·XYZ_D65, divided by max(n). Every component must be positive and finite.

### 13.4 Camera → XYZ (relative)

- **With FM** (DNG §6.3):
  - r = CC⁻¹·n
  - D = diag(1/r)
  - M50 = FM·D·CC⁻¹
  - M_rel = Bradford(D50 → D65)·M50
  - Bradford: the standard M_BFD = [[0.8951, 0.2664, −0.1614], [−0.7502, 1.7135, 0.0367], [0.0389, −0.0685,
    1.0296]], and CAT = M_BFD⁻¹·diag(M_BFD·W_D65 / M_BFD·W_D50)·M_BFD.
  - Whites: D50 xy (0.3457, 0.3585) → (0.96429, 1, 0.82510); D65 as above.
  - Expect ≈ Lindbloom's [[0.95558, −0.02304, 0.06316], [−0.02829, 1.00994, 0.02101], [0.01230, −0.02048,
    1.32991]] (±1e-3).
- **Without FM:** M_rel = (CC·CM)⁻¹. That is already unadapted XYZ.
- **Both paths:** rescale so (M_rel·n).Y = 1. The neutral that just fills its strongest channel then reads
  Y_rel = 1, and M_rel·n ∝ XYZ_D65 (a D65 light reads as D65).

### 13.5 Absolute scale (photometric exposure equation)

ISO 12232 saturation-based speed: the focal-plane exposure at saturation is H_sat = 78 / ISO (lx·s). The
focal-plane exposure for a scene luminance L is H = q·L·t / N², with q = (π/4)·T·v·cos⁴θ = **0.65**.

So the saturating luminance is L_sat = 78·N² / (q·ISO·t). A neutral of luminance L gives normalised camera
values n·L/L_sat, where normalised means counts·t / R. Therefore:

    L = L_sat · Y_rel(counts·t/R) = [78·N² / (q·ISO·R)] · Y_rel(counts per second)      (t cancels)

    K = 78·N² / (q·ISO·R)   [cd/m² per (net count / s)]

| Phone | Numbers | K | Saturation |
|---|---|---|---|
| S24+ | 78·1.8² / (0.65·25·3839) | **0.0040511** | 100 cd/m² saturates in 0.155 s |
| Pixel 7 | 78·1.85² / (0.65·47·959) | **0.0091119** | 100 cd/m² saturates in 0.087 s |

The profile matrix is **M = K·M_rel**. It is applied to `CameraReading.rgb` (net counts per second), like a
trained one.

**Expected error:**

- **Luminance:** within about ×1.5 (±0.6 stop). Phone RAW "ISO" is often a standard-output or REI rating with
  headroom rather than saturation-based. Lens transmission and the exact q vary, and on the S24+ lens shading
  is applied in RAW.
- **Chromaticity of greys:** typically 0.005–0.01 in xy.
- **Display primaries:** 3–6 dE. The DNG matrices are fitted to daylight-lit reflective colours, not to
  narrow-band TV primaries.
- Hence `marginDE` = **4** (`ProvisionalProfile.MARGIN_DE`), and `checkSettings` is off.

### 13.6 The profile and the adapter

`ProvisionalProfile.build(data, model, deviceName)` returns:

```
CameraProfile(format = 1, model = DeviceModel.identifier, name = "<Build.MODEL> (approximate: phone's own colour data)",
              version = 0, matrix = K·M_rel, camera = data.settings, stats = ProfileStats(), trained = null, panels = null)
```

It returns null if anything is missing, singular or not finite.

`CameraColourReader.read(c, candidate)` converts `ColorSpaceTransform` (Rational) to `Mat3`.

`AndroidApproximateSource.load()`:

1. `CameraCatalog.read` + `CameraSelection.choose`. Unsupported → `Unavailable(message)`.
2. No CAMERA permission → `NeedsPermission`.
3. Read the characteristics, then `build`. Null → `Unavailable("no colour data")`.
4. `Ready(profile)`, cached after the first success.

The settings come from `CameraSelection.settingsFor(candidate, null)`, so they equal the engine's.

### 13.7 ProfileService integration (agent (c))

- **Precedence:** imported > own (built-in / downloaded) > standard > **approximate**. Trained profiles always
  win.
- **Already in the skeleton:**
  - `Source.APPROXIMATE`
  - `isApproximate`
  - `checksSettings` = !isStandard && !isApproximate
  - `approximateNeedsPermission`
  - `attachApproximate(source)`, which stores the source and runs `choose()`
- **`choose()`** (to do): after standard, `approximateSource?.load()`:
  - Ready → `use(profile, APPROXIMATE)`
  - NeedsPermission → `approximateNeedsPermission = true`
  - else false
  - `status` keeps the Swift rule: Ready when there is a profile.
- **`reloadApproximate()`** (to do): re-run the approximate step only when no trained or standard profile is
  in use. When the result is a new profile, set it with status Ready. Never move an Unsupported, Offline or
  NotConfigured status back to Checking.
- **`refresh()`** is unchanged. A downloaded own profile then wins through `choose()`. With the approximate
  profile in use, a catalog without this model leaves status Ready.
- **Texts** (fill the `TODO("p4")` branches):
  - `profileLabel`: `p.name` alone (no "v0").
  - `summary` (Ready): "This phone (<model>) doesn't have its own camera profile yet, so it uses an approximate
    one made from its camera's own colour data. Readings are approximate. When a profile for this model is
    ready, it downloads automatically."
  - `summary` when there is no profile and `approximateNeedsPermission`, overriding Unsupported, NotConfigured
    and Checking: "This phone (<model>) doesn't have its own camera profile yet. Start the camera to use an
    approximate one made from its camera's own colour data."
  - `marginDE`: 4.0 for approximate, whatever the panel.
  - `marginNote`: "This phone has no camera profile of its own yet and uses an approximate one made from its
    camera's own colour data, so its readings are approximate: differences under about 4 dE may not be real."

## 14. CameraXYZMeter

A straight port: integration 0.25 s; `now`/`sleep` from MeterClock. `read()`:

1. t0, `camera.measure()`, t1.
2. **No reading:** a problem starting "Too bright" → `throw MeasureError.Message(problem)`. Otherwise
   `MeterClock.sleep(0.3)` and return `MeterReading(t0, now(), null)`.
3. **Settings check:** with `checkSettings` and `profile.camera.mismatches(settings)` non-empty, throw "The
   camera is running with different settings than its profile was trained with (<diffs joined by "; ">). This
   can follow an Android update: check for a newer profile in Settings."
4. **Otherwise:** `MeterReading(t0, t1, profile.xyz(Vec3(rgb), panel))`.

## 15. App integration summary

- **AppModel:**
  - `cameraBlocker`: no profile → `profiles.summary`; `!available` → `unavailableReason`; not running; no dark.
  - `startCamera()`: aperture rule unchanged (fixed lenses ignore it). After start, `reloadApproximate()`.
- **Camera setup screen:**
  - The flow is unchanged: START → DARKNESS → PLACEMENT → READY.
  - Start is enabled with a profile, or when only the permission stands between the phone and its approximate
    profile.
  - The "Locked: back main camera, ISO 25, fixed aperture, focus closest, RAW photos 4080x3060" text works as
    is.
- **Home camera card:**
  - No profile → UNAVAILABLE with the summary, which includes the "Start the camera…" text above.
  - Approximate → the STANDARD phase, titled "Approximate profile" ("Readings approximate. You can still get
    started.").
- **Live meter / sweeps:** `CameraXYZMeter` with `checksSettings`, unchanged otherwise.

## 16. Test plan

### 16.1 JVM tests (`./gradlew.bat :app:testStandardDebugUnitTest`)

**Helper (a): `SyntheticBayer`** builds a `RawPlane` from:

- W×H, a CFA and an optional parity offset
- 4 black levels and per-channel signal levels
- Gaussian noise with a fixed seed
- optional row padding (`rowStride > 2W`)
- optional gradient and clipped spots
- little-endian

**(a) RawAnalysisTest:**

- CFA: `Cfa.fromCamera2` for 0–6. `CfaLayout.index/channel` for all 4 patterns × 4 parity offsets.
- Plane reading: little-endian (bytes 0x02 0x01 → 258), padded rows ignored, pixelStride honoured.
- `CentreBox.of`: 4080×3060 → (1836, 1376, 408, 306); 4080×3072 → (1836, 1382, 408, 306); odd sizes.
- Means: exact channel means for flat frames on every CFA, per-index black subtracted (e.g. black 255/256/257/256).
- `Thresholds` numbers for both phones (table §5): clipLevel 4018 / 1003. A photosite at 4017 is not clipped;
  one at 4018 is.
- Plateau:
  - 1.5% of the box at the top value → plateau and clipped.
  - a 0.5% thin tail → not.
  - a dark frame (top ≈ black + 20) → not.
  - a plateau below 2000·s → not.
- Uneven: a left/right gradient gives the expected ratio; a flat frame gives 0.
- `FrameAccumulator`: 2 frames average, clipped if any, uneven from the averaged quarters.
- `FramePlan`: 0.25/0.15 → 2 × 0.125; 0.10 → 1 × 0.10; clamps at min/max; ≤ 16 frames.
- `WhiteLevel`: accept / reject the reported white, first-frame log once, learn from a plateau, never learn
  when `learns = false`, `setFloor` reset.
- `ExposureSearch`: too bright at min, ÷4, ×16 cap, 0.4 target, accept at max, the equivalent peak with 2
  frames.
- `DarkCheck`: accept, leak (with the S24+ factor 1.857), flood, strong vs mild, `longAt` null at 0.25.
- `DarkLevel.at` with and without `longAt`. JSON round trip: old `{"short","long"}` decodes; nulls are omitted.

**(b) CameraSelectionTest:**

- **S24+ table:** id 0 logical LEVEL_3 0.150 s, id 2 ultra-wide, front id 1 → "0". With id 56 (non-logical,
  same lens, 0.267 s) listed → "56". With 23 and 56 → "23".
- **Pixel 7:** → "0".
- **Unsupported:** no RAW → `NO_RAW`; RAW without MANUAL → `NO_MANUAL`; only a front camera → `NO_BACK`;
  non-Bayer CFA → not eligible.
- **Format strings:** "4080x3060 GBRG", "Back camera 0".

**(b) FrameCheckTest:** each rejection reason, the tolerance edges (±10% exposure, 5% / 0.1 D focus), and
missing keys accepted.

**(c) ProvisionalProfileTest:**

- **Bradford:** D50 → D65 against Lindbloom (1e-3). The identity for equal whites.
- **Weights:** the A/D65 pair → 0; D50/D65 → between 0 and 1; one calibration → 1.
- **Synthetic camera:** pick M_true (camera → XYZ), set CM = M_true⁻¹ (the illuminant D65 alone) and
  FM = M50 from the DNG definition. Then both paths recover M_true up to scale, and a D65 light → xy (0.3127,
  0.3290) within 1e-4.
- **Scale K:** 0.0040511 and 0.0091119 (1e-6).
- **`build`:** model, name, version 0, settings copied, `isValid`; null on a singular CM or a non-finite input.

**(c) CameraXYZMeterTest** (fake CameraMeter):

- "Too bright…" → throws.
- Another problem → xyz null after ≥ 0.3 s (`runTest` virtual time).
- Mismatch → the Android message.
- `checkSettings` false → no throw.
- A panel matrix is used.

**(c) ProfileServiceTest additions** (fake `ApproximateSource`):

- Approximate only when nothing else exists. Imported, built-in, downloaded and standard all beat it.
- `NeedsPermission` → `approximateNeedsPermission` and the summary text. `reloadApproximate` after "granting"
  → Ready.
- `profileLabel`, `summary`, `marginDE` (4.0) and `marginNote` for approximate. `checksSettings` false.
- `refresh()` downloads the model's profile → replaces the approximate one.

### 16.2 On-device checks (S24+ and Pixel 7, `adb logcat` + the in-app camera log)

1. **Candidates and ids:** the start log lists the candidates. On the S24+, record whether 23/56 are in
   `getCameraIdList()` and which id is chosen. Confirm RAW size, CFA, black and white, ISO and exposure range
   against the probe table.
2. **Locks:** the first frames log focus locked, OIS off, black level lock true, no "Frame rejected" in steady
   state, and a constant active physical id.
3. **CFA order:** full-screen red, green and blue patches give R, G and B as the largest channel. If R and B
   swap, the parity offset is wrong.
4. **Dark:** phone face-down on a table → accepted; residual < ~2 counts (S24+) / < ~1 (Pixel). A fingertip
   over the lens → `DARK_LEAK`. Lens uncovered in a lit room → `DARK_STRONG`.
5. **Linearity:** a steady grey patch measured at forced shutter times 1/250 … frameMax gives `rgb` per second
   constant within 1%.
6. **Multi-frame (S24+):** a dim patch read at 0.25 s (2 × 0.125) vs one 0.150 s frame gives the same `rgb`
   within 1%. `CameraReading.frames` = 2.
7. **Exposure search:** bright HDR white → short exposure, no clipping. Phone very close → `TOO_BRIGHT` at
   125 µs.
8. **Timing:** a sweep's sync detection still passes on the demo patterns played on a real TV (the `at` clock
   conversion).
9. **Lifecycle:** home → another camera app opens the camera; back → "Camera back on" and measuring works.
   Stop → the camera is released (green dot off).
10. **Permission:** deny → `PERMISSION`; "don't ask again" → the same; grant → starts. The home card on a
    profile-less phone shows the "Start the camera…" summary, then "Approximate profile".
11. **Approximate profile:** compare with the Display Plus HL on one TV (white Y and xy; R, G, B primaries).
    Record the luminance ratio and dE in this document to confirm or adjust K and `MARGIN_DE`.
12. **Unsupported phone (if available):** no RAW → the home card shows `NO_RAW` + "You can still try the demo."
13. **Background while starting:** press Home during "Starting the camera…": the camera is released (green dot
    off) and reopens on return ("Camera back on").

## 17. As built: deviations from this design

Recorded at integration (all three parts compiled and their JVM tests pass):

- **(a) RawAnalysis:** `measure` returns null (not a throw) for non-finite black/white levels, strides below 2 or a
  short buffer. `clipLevel` / `learnBelow` use the static black B0; the plateau gate and the believable-white check
  use the frame's mean black. `WhiteLevel` logs each line once; `setFloor` only forgets a learned white (no 16383
  to fall back to) and prints the dark level to 1 decimal. `DarkCheck` uses a leak factor of 1 when `longAt` ≤
  1/30 s. `FramePlan` clamps in the Swift order. At the 16-frame cap `perFrame` can exceed `frameMax` (frameMax
  under ~15.6 ms only); the engine clamps the requested exposure to the camera's range and divides by the reported
  one, so the reading is just shorter than asked.
- **(b) Engine:** suspend functions run on Dispatchers.Default and wait on deferreds completed by camera-thread
  callbacks (nothing waits on a thread `close()` quit). `onGaveUp` tells the meter that retries failed.
  `setPreview(SurfaceTexture?)` lets the engine size the buffers before each session. The preview is not rotated
  by SENSOR_ORIENTATION (Camera2 already rotates SurfaceTexture streams). Latency discard frames are capped at 2.
  The RAW ImageReader is closed on the RAW thread, after any analysis still reading one of its images.
- **(b) Meter:** a `stop()` or a trip to the background while `start()` or a foreground reopen is waiting for the
  engine releases the camera (stop) or finishes as suspended (background), so the camera is never left open in
  the background.
- **(c) Provisional:** a variable-aperture lens records `ApertureLock.choose(apertures, defaultFNumber)` as both
  the f-number in K and `settings.aperture` (what AppModel locks with no profile). The NeedsPermission summary also
  overrides Ready-with-no-profile. The approximate profile shows the same warnings as the standard one in Settings
  and on the camera setup screen.
