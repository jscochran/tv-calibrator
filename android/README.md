# TV Calibrator for Android

An Android port of [TV Calibrator by Slidebean](https://github.com/slidebean/tv-calibrator), changed
from the original iPhone app. Licensed like the original under
[CC BY-NC 4.0](../LICENSE): credit "TV Calibrator by Slidebean", non-commercial only. This build is
for personal sideloading. The name and icon are Slidebean's and are not for a published app.

The phone's main camera is the meter. It reads RAW sensor data from the centre of a test pattern
playing on the TV, and a per-model **camera profile** turns those numbers into real colour (XYZ). The
first Android profile will be trained for the **Galaxy S24+** against a Calibrite Display Plus HL,
then published in [`../server/profiles/`](../server/profiles) for anyone with the same phone.

## Layout

| Path | What it is |
|---|---|
| `calibrationkit/` | The calibration engine in plain Kotlin/JVM: a line-by-line port of `../CalibrationKit` (Swift). No Android code, so its tests run on any computer. |
| `app/` | The Android app (Kotlin, Jetpack Compose). Flavors: `standard` ("TV Calibrator for Android") and `lab` ("TV Cal Lab for Android", pairs with the lab tool to train profiles). |
| `app/src/main/kotlin/tvcal/android/` | `storage/`, `profiles/`, `app/`, `measure/` (guided controller, sweep and live runners), `camera/` (an interface: the real Camera2 meter lands in P4), `ui/` (theme, components, screens, navigation). Each Kotlin file mirrors one Swift file in `../TV Calibrator/`. |
| `docs/research/` | Background for the port: the iPhone app layer mapped to Android, Camera2 RAW on the Galaxy S24+, and running the lab tool on Windows. |

## Building

Needs JDK 17 and the Android SDK (`local.properties` with `sdk.dir`, which Android Studio writes).

```powershell
cd android
.\gradlew.bat :calibrationkit:test          # engine tests against the Python fixtures
.\gradlew.bat :app:assembleStandardDebug    # app-standard-debug.apk
.\gradlew.bat :app:assembleLabDebug         # the lab build
.\gradlew.bat :app:installStandardDebug     # install on a phone connected with USB debugging
```

## Engine tests

`calibrationkit` reads the same fixtures as the Swift tests
(`../CalibrationKit/Tests/CalibrationKitTests/Fixtures`, recorded from the Python engine by
`../tools/make_fixtures.py`). All 21 tests pass: colour maths, pattern timelines, sync detection,
analysis, guided hints, the point finder and the automatic rounds.

Kotlin rounds and formats numbers differently from Swift and Python. The port uses helpers in
`Basics.kt` (`pyRound`, `swiftRounded`, `swiftMin`/`swiftMax`, `cfmt`, `gfmt`) wherever results or
text must match exactly. Use them, not `round()` or `String.format`.

## Roadmap

| Phase | Work | Needs |
|---|---|---|
| P0 ✅ | Engine port (`calibrationkit`) with all fixture tests passing | — |
| P1 ✅ | Storage (TVs, guide progress), TV brand menu paths, profile loading (built-in + this fork's `server/`), pattern links. 96 JVM tests, JSON-compatible with the iPhone app's files. | — |
| P2 ✅ | Theme, navigation and every screen. The whole guided flow runs on the **demo TV** (simulated readings, no camera). Checked on a Pixel 7 and the S24+. | — |
| P3 ✅ | Results charts, share image, history export, chime, keep-screen-on, re-check reminder. | — |
| P4 | **Camera meter on Camera2**: main camera chosen by id, RAW_SENSOR (binned 4080×3060), minimum ISO, closest focus, OIS off, black-level lock, per-frame result checks, centre-box Bayer means, dark level, white level. Camera setup screen and live meter. | The S24+ on USB debugging |
| P5 | **Lab mode**: Android lab client (`/api/phone/*`, NSD discovery + manual IP), lab tool patches for **Windows** and non-iPhone models, `build_catalog.py` support for Android model keys. | ArgyllCMS 3.x on Windows |
| P6 | **Train the Galaxy S24+ profile** on two or more TVs (ideally OLED + LCD), check it, publish to `server/profiles/`, build it into the app. | Display Plus HL |
| P7 | Re-check reminders (WorkManager). Video push notifications are iPhone-only (APNs) and are dropped. | — |

### Decisions so far

- **Test phones:** a Galaxy S24+ SM-S926U1 (`samsung/e2q`) and a Pixel 7 (`google/panther`), both
  trained in the same lab session. Measured camera specs are in
  [docs/research/s24plus-camera-probe.md](docs/research/s24plus-camera-probe.md).
- **Profile model key:** `samsung/e2q` (Snapdragon S24+, SM-S926U/U1/W/0) and `samsung/e2s` (Exynos,
  SM-S926B/N), from `Build.MANUFACTURER`/`Build.DEVICE`. Both variants use the same ISOCELL GN3
  sensor but different camera HALs (black level, ISO gain), so each gets its own profile unless a
  cross-check shows they agree.
- **Camera API:** plain Camera2, not CameraX (CameraX can't hand over the RAW plane with manual
  control).
- **RAW format:** the binned 12.5 MP RAW_SENSOR stream. Samsung doesn't give third-party apps the
  50 MP mode, and the binned stream has a true Bayer pattern and better noise.
- **Sensor bit depth:** the iPhone meter's thresholds assume 14-bit RAW (white 16383). Android
  sensors are usually 10 or 12-bit, so the Android meter scales those thresholds by its white level
  (to be confirmed on the phone in P4).

## Credit

TV Calibrator, its engine, pattern videos, lab tool and documentation are by
[Slidebean](https://github.com/slidebean/tv-calibrator) (CC BY-NC 4.0). This folder adds the Android
port.
