# Galaxy S24+ camera probe (measured on the phone)

From `adb shell dumpsys media.camera` on the project's own S24+, 2026-11-25. This replaces the
"[check on device]" items in [camera2-galaxy-s24plus.md](camera2-galaxy-s24plus.md).

- **Phone:** SM-S926U1 (US unlocked, Snapdragon 8 Gen 3 for Galaxy, `ro.soc.model` SM8650).
  `Build.DEVICE` = `e2q`, product `e2quew`. Android 16, build S926U1UES6DZI2.
- **Profile key:** `samsung/e2q`.

## The main camera (ISOCELL GN3, 5.4 mm f/1.8)

| Item | Value | Notes |
|---|---|---|
| App-facing id | **0** | Logical multi-camera (physical ids 2, 5, 6). Its own characteristics describe the main lens. |
| Standalone ids with the same lens | 20, 23, 56 | 56 is LEVEL_3, not logical, and allows **0.267 s** exposures. Whether third-party apps can open it is to be checked in P4 (`getCameraIdList()`). |
| Hardware level | LEVEL_3 | RAW, MANUAL_SENSOR, MANUAL_POST_PROCESSING, READ_SENSOR_SETTINGS, BURST_CAPTURE |
| RAW_SENSOR size | **4080×3060** | Binned 2×2 (binning factor 2,2). No 50 MP RAW. |
| CFA | **GBRG** | |
| White level | **4095** (12-bit) | |
| Black level | **256** on all four channels | `android.blackLevel.lock` is available. |
| ISO range | **25–3200** | Max analog ISO 403. The meter locks ISO 25. |
| Exposure range | 50 µs–**0.150 s** on id 0. 24 µs–**0.267 s** on ids 23/56. | |
| Aperture | f/1.8 fixed | |
| Focal length | 5.4 mm | |
| Minimum focus distance | 10 diopters (10 cm) | APPROXIMATE calibration. |
| OIS | available (off/on) | The meter turns it off. |
| Lens shading applied to RAW | **TRUE** | It's a fixed correction for a fixed centre box, so the profile absorbs it. |

## Other back cameras (not used)

- id 2: ultra-wide, 2.2 mm f/2.2, fixed focus, 4000×3000 RGGB, 10-bit.
- id 21/52: 3× tele, 7.0 mm f/2.4, 3648×2736 GBRG, 10-bit.

## Second test phone: Pixel 7

Same probe on the project's Pixel 7: Android 16, Tensor G2 (`ro.soc.model` GS201), build
CP1A.260305.018. Profile key **`google/panther`**.

| Item | Value | Notes |
|---|---|---|
| App-facing id | **0** | Logical (physical ids 2, 4, 3). Main lens 6.81 mm f/1.85 (Samsung GN1). |
| Hardware level | FULL | RAW, MANUAL_SENSOR, MANUAL_POST_PROCESSING |
| RAW_SENSOR sizes | **4080×3072** (also 4080×2288, 2032×1536) | |
| CFA | **GBRG** | |
| White / black level | **1023 / 64** (10-bit) | 959 usable counts: noisier than the S24+, so the meter averages more. |
| ISO range | 47–11906 | The meter locks ISO 47. |
| Exposure range | 26 µs–**8.3 s** | No exposure ceiling problem. |
| Minimum focus distance | 9.52 diopters (~10.5 cm) | |
| OIS | available | |
| Lens shading applied to RAW | **FALSE** | Raw is uncorrected. Fine with a fixed centre box. |

The format string for profiles is `"4080x3072 GBRG"`. Having both phones also tests the meter on two
different camera HALs (Samsung on Qualcomm, and Google's own) and two bit depths.

## What this changes for the Android meter (P4)

1. **12-bit, not 14-bit.** The iPhone meter's thresholds assume a ceiling of 16383, so scale them by
   the white level: 4095 − 256 black = 3839 usable counts.
2. **Exposure ceiling.** The iPhone meter's dark level uses 1/30 s and 1/4 s, and the sweep's meter
   integration is 0.25 s. Camera id 0 tops out at 0.150 s. Either open the standalone main camera
   (56) if apps may, or keep exposures ≤ 0.15 s and average several frames to integrate 0.25 s.
3. **Black level 256 with lock.** Subtract the per-frame `SENSOR_DYNAMIC_BLACK_LEVEL`, and keep
   the measured dark level as a residual on top, as the iPhone meter does.
4. **Format string for profiles:** `"4080x3060 GBRG"`. **Lens:** `"Back camera 0"` (or the id
   actually used).
