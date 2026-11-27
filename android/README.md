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

## Training videos

Profile training plays a lab video on the TV while the lab tool follows it with the meter: whenever
the meter sees a new, steady patch (over 0.5 cd/m², 8% brighter or dimmer or 0.006 away in x, y),
the meter and every connected phone measure it. There are no sync flashes, and no patch list to match.
The tool stops 45 s after the last patch. Make both videos with ffmpeg on PATH (Python standard library
only), then play them from a USB stick:

```powershell
python tools\make_sdr_training.py D:\        # "Lab - Camera training (SDR).mp4", about 1.5 MB, 10 min 22 s
python tools\make_hdr_training.py D:\        # "Lab - Camera training (HDR10).mp4", about 4 MB, 12 min 40 s
```

| | SDR (`make_sdr_training.py`) | HDR10 (`make_hdr_training.py`) |
|---|---|---|
| Format | 1920×1080, 24p, H.264 High 4.0, 8-bit 4:2:0, BT.709 primaries/transfer/matrix, limited range | 1920×1080, 24p, HEVC Main 10, BT.2020, PQ, limited range, mastering display P3 D65 1000 nits, MaxCLL 1000/400 |
| Timeline | 10 s black, 46 patches × 12 s, 60 s black | 10 s black, 46 patches × 15 s, 60 s black |
| Patches | 11 greys (15–100%), red/green/blue/cyan/magenta/yellow at 100, 75 and 50%, 6 tinted greys at 75% + 3 at 40%, orange, light and dark skin, purple, sky, lime, pink, teal | 11 greys (10–1000 nits), the six colours in P3 at 100 and 300-nit white and in Rec.709 at 100, 9 tinted greys, the same 8 mixed colours |

Each patch is a band across the full width of the screen (rows 180–900) on black, so the meter and
each phone can aim at their own spot. Greys and colours alternate so that each patch differs clearly from
the one before. Each script also writes `… patches.txt` with the start time, signal, Y, x, y and codes of every
patch. `make_sdr_training.py` replaces the export from the Mac app's SDR video studio. `--list` writes
only the patch list.

**Newer ffmpeg (seen with 9.0):** if the raw input has no colour tags, ffmpeg assumes BT.601 and converts it to the
output's tags. `make_sdr_training.py` tags its input, and its colour patches decode to the exact codes.
`make_hdr_training.py` doesn't yet: with ffmpeg 9, greys stay exact but colour patches decode a few
codes off (for example P3 red is 322/380/619 instead of 318/374/628). Training still works because the meter
and the phones measure the same light. Adding the same `-color_*` options before `-i` fixes it.

## Publishing a profile

Train the phone with the lab tool as in [../docs/ADDING-AN-IPHONE.md](../docs/ADDING-AN-IPHONE.md),
using the lab build, then publish from the repo folder (an exported profile file works too):

```powershell
python tools\build_catalog.py "$env:USERPROFILE\tvcal\camera_profiles.json" `
    --name "samsung/e2q=Galaxy S24+" --name "google/panther=Pixel 7" --standard samsung/e2q
cd push-worker; npx wrangler deploy
```

- A model key that doesn't start with `iPhone` is an Android model (an export can also say
  `"platform": "android"`). `samsung/e2q` is written to `server/profiles/samsung_e2q-v1.json`. The
  profile's `model` field keeps the `/`. `server/index.json` is shared with the iPhone app.
- Android models go only into `app/src/main/assets/builtin-profiles.json`, and iPhone models only
  into the iPhone app's file. `--platform android` rewrites only the Android file. `--standard` sets
  the profile that Android phones without their own fall back on when their camera's own colour data
  isn't usable (see Decisions). Pass it once per platform.
- Profiles with an estimated error above dE 3 on unseen TVs are refused, as for iPhones.
- If the script warns that a file doesn't match its SHA-256 because its line endings changed to
  CRLF, git's `core.autocrlf` rewrote it. Don't deploy until that is fixed, or phones reject the file.
- Self-test: `python tools\tests\test_build_catalog.py`.

## Training day

The step-by-step for training Android camera profiles with the Display Plus HL on Windows is in
[docs/TRAINING-DAY.md](docs/TRAINING-DAY.md).

## Roadmap

| Phase | Work | Needs |
|---|---|---|
| P0 ✅ | Engine port (`calibrationkit`) with all fixture tests passing | — |
| P1 ✅ | Storage (TVs, guide progress), TV brand menu paths, profile loading (built-in + this fork's `server/`), pattern links. 96 JVM tests, JSON-compatible with the iPhone app's files. | — |
| P2 ✅ | Theme, navigation and every screen. The whole guided flow runs on the **demo TV** (simulated readings, no camera). Checked on a Pixel 7 and the S24+. | — |
| P3 ✅ | Results charts, share image, history export, chime, keep-screen-on, re-check reminder. | — |
| P4 ✅ | **Camera meter on Camera2** ([design](docs/camera-meter-design.md)): main camera through logical id 0, RAW_SENSOR, minimum ISO, closest focus, OIS off, per-frame result checks, centre-box Bayer means with each frame's own black level, dark level, white level, multi-frame shots past the exposure limit (S24+: 0.15 s). Approximate profile from the phone's own DNG colour data until a trained one exists, refused when that data is a generic placeholder (Pixel 7). Tested on both phones: darkness, test reading, live meter. | — |
| P5 ✅ | **Lab mode**: lab tool runs on **Windows** and accepts Android models; Android lab client (manual address + NSD, pairing, polling, readings); `build_catalog.py` for Android keys; SDR/HDR training-video generators. Rehearsed end to end on 2026-11-26 with the tool's simulated meter: both phones paired, answered every reading, got trained profiles; the exports load in the app (`LabExportTest`) and publish with `build_catalog.py`. | — |
| P6 | **Train the Galaxy S24+ (`samsung/e2q`) and Pixel 7 (`google/panther`) profiles** together on two or more TVs (ideally OLED + LCD), check them, publish to `server/profiles/`, build them into the app. | Display Plus HL |
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
- **Profile order** (`profiles/ProfileService.kt`): imported (Lab) > the model's own (built in or
  downloaded, newer wins) > **approximate** from the phone's own DNG colour data
  (`camera/ProvisionalProfile.kt`, only when that data is real, not a generic sRGB placeholder as on the
  Pixel 7) > **standard** (`--standard`) > none. This differs from the iPhone app, which has no
  approximate step: another model's trained matrix is for a different sensor, so the phone's own data is
  the better guess. Until the CAMERA permission is granted the approximate profile can't be read, so the
  standard one (if published) stands in and is replaced when the camera starts. Neither checks the
  camera settings, and both show that brightness (cd/m²) is only an estimate, about ±50%
  (`ProfileService.brightnessApproximate`).
- **Backup:** Auto Backup keeps TV history and settings but leaves out what belongs to one phone
  (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`): `Profiles/imported.json`,
  `Profiles/current.json` (downloaded again) and the `device` prefs file, which holds the dark level and
  the lab pairing (`Prefs.DEVICE_KEYS`; older installs move them there on first start).
- **Camera log:** Camera setup › Camera log › "Share the log" sends the last 200 lines as text. A line
  repeated straight after itself (such as a failed reading every 0.3 s while the camera is interrupted) is
  kept once, as "… (n times, the last at HH:mm:ss)", so the setup lines and the reason the camera stopped
  aren't pushed out (`CameraLog`). Each line also goes to logcat with the tag `tvcal` (`adb logcat -s tvcal`).

## Credit

TV Calibrator, its engine, pattern videos, lab tool and documentation are by
[Slidebean](https://github.com/slidebean/tv-calibrator) (CC BY-NC 4.0). This folder adds the Android
port.
