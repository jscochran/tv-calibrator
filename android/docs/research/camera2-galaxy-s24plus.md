# Galaxy S24+ (SM-S926x) as a RAW TV colour meter: Camera2 research

**Confidence:** I found no public `CameraCharacteristics` dump for the S24+. The sources only describe the S24 family and similar Samsung phones in general terms, so every number in this report has to be confirmed on the phone. Run `adb shell dumpsys media.camera > s24p.txt`, or the "Camera2 API Probe" app, once on each variant. Items below are marked **[verified]** (source found), **[likely]** (Samsung or Camera2 pattern plus indirect evidence) or **[check on device]**.

---

## 1. Camera2 support on the S24+ main camera

**Hardware and sensor**
- **[verified]** The main camera is a 50 MP Samsung ISOCELL GN3: 1/1.56", 1.0 µm pixels, f/1.8, 24 mm equivalent, OIS. It is the same sensor as the S23/S23+ (GSMArena review; Samsung camera spec page; Notebookcheck).
- **[verified]** Open Camera's author tested an SM-S926B. Camera2 "mostly works". All rear cameras are reachable, chosen by zoom, or directly since Open Camera 1.54. https://opencamera.org.uk/devices.html

**Hardware level and capabilities**
- **[likely]** Samsung S-series rear main cameras report FULL or LEVEL_3, with RAW, MANUAL_SENSOR and MANUAL_POST_PROCESSING. No source states this for the S926 specifically. Check `INFO_SUPPORTED_HARDWARE_LEVEL` and `REQUEST_AVAILABLE_CAPABILITIES` (RAW=3, MANUAL_SENSOR=1, MANUAL_POST_PROCESSING=2).
- Samsung community threads show ultrawide, front and macro cameras reported as LIMITED on other models. The main camera is the one usually exposed fully. https://r1.community.samsung.com/t5/support/camera2api-support-for-all-devices/td-p/39042600

**RAW output sizes**
- **[likely]** RAW_SENSOR is offered only at the binned 4080×3060 size (12.5 MP, 2×2 Tetrapixel binning, a normal Bayer CFA).
- Samsung has historically not given third-party Camera2 apps the full-resolution remosaic mode:
  - S22 Ultra: the Samsung app produces 12000×9000, but Camera2 returns at most 4000×3000. https://forum.developer.samsung.com/t/get-image-has-12000x9000-with-camera2-api/22240
  - S24 users report that third-party apps cannot use full resolution. https://eu.community.samsung.com/t5/galaxy-s24-series/access-to-full-camera-resolutions/td-p/10973373
- Full resolution would need `ULTRA_HIGH_RESOLUTION_SENSOR` plus `SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION`. That RAW may be quad-Bayer or remosaiced (see `SENSOR_INFO_BINNING_FACTOR`). https://gitlab.e.foundation/e/os/android_frameworks_base/-/commit/8eac699c6e69a9a2bd5eced48e5abbfe69410b56
- **Use the binned 12.5 MP RAW anyway.** It has a real Bayer CFA, no remosaic interpolation, better SNR, and a smaller buffer.

**CFA, black and white levels, lens shading**
- **CFA [check on device]:** read `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT`. GN3 binned output is a standard Bayer pattern, commonly GRBG or RGGB. Don't hardcode it.
- **Black and white levels [likely]:**
  - `SENSOR_BLACK_LEVEL_PATTERN` and `SENSOR_INFO_WHITE_LEVEL` are mandatory for RAW-capable devices.
  - `SENSOR_DYNAMIC_BLACK_LEVEL` and `SENSOR_DYNAMIC_WHITE_LEVEL` (API 29+) are usually present on modern HALs. Check that the result key list contains them.
  - Expect a 10-bit range (white 1023, black around 64) or a 12-bit range (4095, around 256). One Samsung developer reported Camera2 RAW data spanning about 255–4095 while Pro-mode DNGs span 0–4095. https://forum.developer.samsung.com/t/data-range-and-number-of-bits-of-raw-images-dng/23207/2
- **Lens shading [check on device]:** read `SENSOR_INFO_LENS_SHADING_APPLIED`. False is typical, meaning RAW is uncorrected and the map comes in `STATISTICS_LENS_SHADING_CORRECTION_MAP`. Because the matrix is trained with the same fixed centre box, any shading is absorbed into the calibration. Just never move the box.

**Samsung's own DNGs are not raw**
- Expert RAW and Pro-mode DNGs on the S23/S24 are linear (already demosaiced), sometimes noise-reduced, and JPEG or JPEG-XL compressed.
  - https://community.adobe.com/t5/camera-raw-discussions/samsung-s24-ultra-phones-don-t-do-raw-anymore-it-s-not-adobes-fault/m-p/14853506
  - https://github.com/imagemagick/ImageMagick/issues/8387
- This does **not** apply to Camera2 RAW_SENSOR from your own app. Third-party DNGs (GCam, Open Camera) from S23 phones are CFA mosaics. https://github.com/pch/rawmakase/issues/1289
- Never calibrate from Samsung-app DNGs.

**Exposure, ISO and focus**
- **ISO [check on device]:** Samsung Pro mode's manual ISO is 50–3200 on the S24 series. https://eu.community.samsung.com/t5/galaxy-s24-series/playing-with-pro-mode-s24u/td-p/12506118/page/2
  - Expect `SENSOR_INFO_SENSITIVITY_RANGE` to start at 50, but read it. Use its lower bound as "minimum ISO".
  - `SENSOR_MAX_ANALOG_SENSITIVITY` shows where digital gain begins.
- **Exposure time [check on device]:** Samsung caps `SENSOR_INFO_EXPOSURE_TIME_RANGE` for third-party apps well below Pro mode's 30 s. Reports include about 0.5 s on an A52s and "no more than about 1 s" on Galaxy phones generally.
  - https://forum.developer.samsung.com/t/camera-long-exposure-time/3227
  - https://www.sourceforge.net/p/opencamera/discussion/general/thread/215b71d5d4
  - This is fine for measuring a TV.
- **Aperture:** fixed. `LENS_INFO_AVAILABLE_APERTURES` = [1.8].
- **Focus [check on device]:**
  - `LENS_INFO_MINIMUM_FOCUS_DISTANCE` is in diopters (1/m); 0 means fixed focus.
  - `LENS_INFO_FOCUS_DISTANCE_CALIBRATION` may be UNCALIBRATED, meaning the units are not physical. Setting `LENS_FOCUS_DISTANCE` = minimum still gives the closest focus.
  - Dual-pixel AF lenses repeat their position well.
- **OIS:** the S24+ main camera has OIS. Set `LENS_OPTICAL_STABILIZATION_MODE_OFF` if it is listed in `LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION`. OIS shifts the image by only a few pixels, so its effect on a 10% centre-box mean is negligible either way. Turning it off just removes one variable.

**Camera ids (logical vs physical)**
- Samsung exposes rear id "0" as a **logical multi-camera**. On an S23 Ultra, camera 0 held physical ids 2, 5, 6 and 7 (ultra-wide, wide and two tele). https://groups.google.com/a/android.com/g/camerax-developers/c/BsXJGFjGFQg
- An S24 owner reported logical id 0 with physical ids 1, 2 and 3. https://forum.developer.samsung.com/t/where-do-i-find-the-camera-lens-ids-for-various-galaxy-phones/37971
- An S23U firmware study lists rear ids 0, 2, 20, 21, 23, 52, 54, 56 and 58, front ids 1 and 3, and secure-only ids 4 and 90. https://github.com/dtechterminal/s23-ultra-tweaks/blob/main/docs/findings.md
- **Recommendation:**
  - Choose the main camera at runtime: LENS_FACING_BACK, with the focal length nearest the "wide" lens (about 5.4–5.7 mm actual focal length), or the largest `SENSOR_INFO_PIXEL_ARRAY_SIZE`.
  - If id 0 is logical (has `LOGICAL_MULTI_CAMERA`), lock `CONTROL_ZOOM_RATIO` = 1.0. Then check `CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` on every frame, because the HAL could switch lenses for close focus or low light.
  - Better still: if the wide physical id appears in `getCameraIdList()`, open it directly. Otherwise target it with `OutputConfiguration.setPhysicalCameraId()`. https://developer.android.com/media/camera/camera2/multi-camera
- **Black level lock:** `BLACK_LEVEL_LOCK` is supported on FULL devices. Confirm the CaptureResult echoes true; the HAL may drop the lock after an ISO change.

## 2. Measurement-grade RAW recipe (CaptureRequest)

**CaptureRequest settings**

| Key | Value |
|---|---|
| `CONTROL_MODE` | `OFF` (all 3A off). Fallback: `CONTROL_AE_MODE_OFF` + `CONTROL_AF_MODE_OFF` + `CONTROL_AWB_MODE_OFF`. |
| `SENSOR_SENSITIVITY` | `sensitivityRange.lower` (e.g. 50) |
| `SENSOR_EXPOSURE_TIME` | ns. Use a whole number of TV refresh periods (n×16.67 ms at 60 Hz, n×8.33 ms at 120 Hz, n×20 ms at 50 Hz) to average out PWM, black-frame insertion and rolling scan. |
| `SENSOR_FRAME_DURATION` | ≥ max(exposure, `streamConfigMap.getOutputMinFrameDuration(RAW_SENSOR, size)`) |
| `LENS_FOCUS_DISTANCE` | `LENS_INFO_MINIMUM_FOCUS_DISTANCE` (closest focus) |
| `LENS_OPTICAL_STABILIZATION_MODE` | `OFF` if available |
| `BLACK_LEVEL_LOCK` | `true` |
| `CONTROL_ZOOM_RATIO` (API 30+) / `SCALER_CROP_REGION` | 1.0 / full active array |
| `HOT_PIXEL_MODE` | `OFF` (supported when RAW-capable) |
| `NOISE_REDUCTION_MODE`, `EDGE_MODE`, `COLOR_CORRECTION_ABERRATION_MODE`, `DISTORTION_CORRECTION_MODE`, `SHADING_MODE` | `OFF`. These are documented as not affecting RAW_SENSOR; set them OFF anyway for safety and to keep the preview consistent. |
| `STATISTICS_LENS_SHADING_MAP_MODE` | `ON` (only for logging the map) |
| `CONTROL_POST_RAW_SENSITIVITY_BOOST` | 100 (no effect on RAW, keeps results clean) |
| `TONEMAP_*`, `COLOR_CORRECTION_*` | Ignored for RAW. Leave defaults. |

**Streams**
- Use a small PRIVATE or YUV preview plus `ImageReader(RAW_SENSOR, 4080×3060, maxImages 4+)`. PRIV preview + RAW at max size is a guaranteed combination for RAW-capable devices.

**Settling**
- Send the manual request as a repeating request for about 5–10 frames (`SYNC_MAX_LATENCY` says how many frames it takes) and wait for `LENS_STATE` = STATIONARY.
- Then capture a burst of N RAW frames with the same request. Pair each `Image` with its `TotalCaptureResult` by `SENSOR_TIMESTAMP` == `image.timestamp`.

**Check each frame's CaptureResult and drop the frame if any check fails**
- `SENSOR_EXPOSURE_TIME` and `SENSOR_SENSITIVITY` equal the requested values. The HAL may clamp or round, so divide by the **reported** exposure.
- `LENS_FOCUS_DISTANCE` is about the requested value, and `LENS_STATE` = STATIONARY.
- `BLACK_LEVEL_LOCK` = true. If it went false, the black level changed.
- For a logical camera, `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` = the main id.
- `SENSOR_DYNAMIC_BLACK_LEVEL` (float[4], in CFA order) and `SENSOR_DYNAMIC_WHITE_LEVEL`. Fall back to `SENSOR_BLACK_LEVEL_PATTERN` and `SENSOR_INFO_WHITE_LEVEL` from the characteristics.

**Reading the RAW plane**
- `planes[0]`: pixelStride = 2, rowStride in **bytes** (may be padded).
- Each sample is uint16, little-endian. Set `buffer.order(ByteOrder.LITTLE_ENDIAN)`, because ByteBuffer defaults to big-endian, and mask with `& 0xFFFF`.
- Format spec: https://developer.android.com/reference/android/graphics/ImageFormat#RAW_SENSOR

**CFA parity and black level order**
- CFA parity is defined at the top-left of the active array. If `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE.left` or `.top` is odd relative to the RAW buffer, flip the parity.
- The dynamic black level's index *n* refers to the *n*th channel in the CFA name, which is the same as the row-major 2×2 index `(y&1)*2 + (x&1)`.

**Saturation and dark frames**
- Reject or shorten the exposure if any pixel in the box is ≥ about 0.95 × white level. Aim for a peak channel mean at roughly 25–70% of (white − black).
- Optionally capture a dark frame (lens capped, same exposure and ISO) to remove the dark-current residual left after optical-black subtraction.
- Android CTS Camera ITS does exactly this kind of RAW photometry (`test_raw_sensitivity`, `test_dng_noise_model`, black-level and linearity tests). Its Python sources are a good reference. https://source.android.com/docs/compatibility/cts/camera-its-tests

## 3. CameraX vs Camera2: use Camera2

- CameraX 1.5 (Nov 2025) can save **DNG files** (`OUTPUT_FORMAT_RAW` / `RAW_JPEG`). It does not hand you the RAW plane, and its manual sensor control only goes through Camera2Interop. https://android-developers.googleblog.com/2025/11/introducing-camerax-15-powerful-video.html
- Camera2 gives you per-frame results, physical-id targeting, `BLACK_LEVEL_LOCK` and direct buffer access.
- Google's camera engineer in the camerax-developers group says to use Camera2 RAW, not YUV, for absolute luminance and XYZ. https://groups.google.com/a/android.com/g/camerax-developers/c/YS39NOuWD5s

```kotlin
// --- pick main rear camera ---
fun pickMain(cm: CameraManager): String = cm.cameraIdList.filter { id ->
    val c = cm.getCameraCharacteristics(id)
    c[CameraCharacteristics.LENS_FACING] == CameraMetadata.LENS_FACING_BACK &&
    c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]!!.contains(
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
}.maxBy { cm.getCameraCharacteristics(it)[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE]!!.width }
// (then if LOGICAL_MULTI_CAMERA, inspect physicalCameraIds by focal length; verify per frame)

// --- manual RAW request ---
fun rawRequest(dev: CameraDevice, c: CameraCharacteristics, rawSurf: Surface, previewSurf: Surface, expNs: Long) =
  dev.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL).apply {
    addTarget(rawSurf); addTarget(previewSurf)
    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
    set(CaptureRequest.SENSOR_SENSITIVITY, c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]!!.lower)
    set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
    val size = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
        .getOutputSizes(ImageFormat.RAW_SENSOR).maxBy { it.width * it.height }
    val minFd = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
        .getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, size)
    set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(expNs, minFd))
    set(CaptureRequest.LENS_FOCUS_DISTANCE, c[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0f)
    set(CaptureRequest.BLACK_LEVEL_LOCK, true)
    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
    set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_OFF)
    set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
    set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
    if (Build.VERSION.SDK_INT >= 30) set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
  }.build()
// session.setRepeatingRequest(req, cb, h) for ~10 frames, then session.captureBurst(List(n){req}, cb, h)

// --- centre-box means (black-subtracted, per second) ---
data class Rgb(val r: Double, val g: Double, val b: Double)
fun centreMeans(img: Image, res: CaptureResult, c: CameraCharacteristics, frac: Double = 0.316): Rgb {
    val p = img.planes[0]; val buf = p.buffer.order(ByteOrder.LITTLE_ENDIAN)
    val w = img.width; val h = img.height; val rs = p.rowStride; val ps = p.pixelStride // ps == 2
    val bw = ((w * frac).toInt() and 1.inv()); val bh = ((h * frac).toInt() and 1.inv()) // ~10% area
    val x0 = ((w - bw) / 2) and 1.inv(); val y0 = ((h - bh) / 2) and 1.inv()
    val cfa = c[CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT]!!
    // channel at 2x2 index (row-major): 0=R,1=G,2=B
    val map = when (cfa) { 0 -> intArrayOf(0,1,1,2); 1 -> intArrayOf(1,0,2,1)   // RGGB, GRBG
                           2 -> intArrayOf(1,2,0,1); else -> intArrayOf(2,1,1,0) } // GBRG, BGGR
    val bl = res[CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL]
        ?: c[CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN]!!.let { pt -> FloatArray(4) { pt.getOffsetForIndex(it and 1, it shr 1).toFloat() } }
    val wl = res[CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL] ?: c[CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL]!!
    val sum = DoubleArray(3); val cnt = LongArray(3); var sat = 0
    for (y in y0 until y0 + bh) { val row = y * rs
        for (x in x0 until x0 + bw) {
            val v = buf.getShort(row + x * ps).toInt() and 0xFFFF
            val i = ((y and 1) shl 1) or (x and 1)
            if (v >= wl * 0.95) sat++
            sum[map[i]] += v - bl[i]; cnt[map[i]]++ } }
    require(sat == 0) { "saturated: shorten exposure" }
    val t = res[CaptureResult.SENSOR_EXPOSURE_TIME]!! / 1e9      // use REPORTED exposure
    return Rgb(sum[0]/cnt[0]/t, sum[1]/cnt[1]/t, sum[2]/cnt[2]/t)  // then XYZ = M * rgb
}
```

`frac` = 0.316 per side gives about 10% of the area. Add the parity offset from `PRE_CORRECTION_ACTIVE_ARRAY_SIZE` if it is odd. Average 4–16 frames.

## 4. Exynos vs Snapdragon variants and the profile key

**Chip split [verified]**
- Snapdragon 8 Gen 3 for Galaxy: US, Canada, China, Taiwan, Hong Kong.
- Exynos 2400: Europe, UK, India, Korea and the rest of the world.
- https://m.gsmarena.com/samsung_galaxy_s24_and_s24_exynos_snapdragon_split_confirmed-news-61246.php
- https://androidauthority.com/samsung-galaxy-s24-snapdragon-vs-exynos-countries-3402659

**Sensor [verified on Exynos, likely on Snapdragon]**
- The main sensor is ISOCELL GN3 on both, and nothing points to a regional difference.
- https://nanoreview.net/en/phone-compare/samsung-galaxy-s24-plus-exynos-vs-samsung-galaxy-s24-exynos
- https://www.phonearena.com/phones/Samsung-Galaxy-S24%2B_id12114
- The sensor's spectral response and IR-cut filter should match, so the **colour** part of the matrix should transfer.

**What can differ between variants**
- The ISP and camera HAL are completely different (Samsung's Exynos HAL vs Qualcomm Spectra/CHI). That can change:
  - RAW bit depth and white level, and the black level value and how it updates
  - the ISO-to-analog-gain mapping. "ISO 50" may not be the same gain, which changes the **absolute** luminance scale.
  - `SENSOR_INFO_LENS_SHADING_APPLIED`
  - camera id numbering and logical-camera behaviour
  - clamping of exposure, ISO or focus ranges
- Module-to-module variation also typically adds a few percent to luminance scale.
- **Recommendation:** keep separate profiles for e2s and e2q (or at least separate luminance gains). Let a profile be shared only after one cross-check measurement agrees, e.g. ΔY < 2% and Δu′v′ < 0.002.

**Profile key [verified mapping]**

| Variant | `Build.MODEL` | `Build.DEVICE` |
|---|---|---|
| Exynos | SM-S926B, SM-S926N | `e2s` |
| Snapdragon | SM-S926U, SM-S926U1, SM-S926W, SM-S9260 | `e2q` |

Source (community codename table): https://community.e.foundation/t/samsung-s24-fe-sm-s721u/80921/5

- `Build.MANUFACTURER` = "samsung" on both.
- `Build.MODEL` can carry carrier and region suffixes, so match on the prefix `SM-S926` and do not hardcode exact strings.
- `Build.PRODUCT` varies by region (e.g. `e2sxxx`), so don't use it.
- Suggested key: `"${MANUFACTURER.lowercase()}/${Build.DEVICE}"` → `samsung/e2s`, `samsung/e2q`.
  - Optionally also store `Build.SOC_MODEL` (API 31+; expect the Exynos 2400 part, e.g. s5e9945, vs SM8650) and the selected camera id plus pixel array size, as a sanity check.
  - For S24 or Ultra, the codenames are e1s/e1q and e3q.

## 5. Open-source and reference projects

No open-source app does RAW-based display colorimetry end to end. These are the closest building blocks:
- **Open Camera** (GPL, Camera2 manual ISO/exposure/focus, DNG, tested on S24+): https://opencamera.org.uk/devices.html and https://sourceforge.net/projects/opencamera/
- **Google Camera2Raw sample** (RAW plus DngCreator): https://github.com/gabrielformiga/android-Camera2Raw (mirror; now under github.com/android/camera-samples)
- **FreeDcam** (Camera2 manual controls and RAW): https://github.com/Theliel/FreeDcam
- **RawCam** (Camera2 RAW, manual focus and exposure): https://github.com/a1024/RawCam
- **MotionCam** (RAW pipeline, Halide). It was open source and is now proprietary; forks survive: https://github.com/TadiT7/motioncam
- **SPECTACLE** (Burggraaff et al.): smartphone RAW calibration for linearity, bias, dark current, ISO gain, flat-field and spectral response, with a `pyspectacle` Python module. It is the best methodological reference for black level, linearity and flat-field.
  - Paper: https://arxiv.org/abs/1906.04155
  - Python module: https://pypi.org/project/pyspectacle/
- **UCL study** on mapping smartphone RAW to XYZ. It warns that dcraw's default matrices behave poorly on phones, which supports per-model trained matrices: https://discovery.ucl.ac.uk/10094305/1/NixonPlosOne2020.pdf
- **Android CTS Camera ITS** (Google's RAW photometry test scripts): https://source.android.com/docs/compatibility/cts/camera-its-tests
- **Auralisoft Camera Colorimeter.** It is closed source, but it is an existing Android phone-as-colorimeter tool covering sRGB only.

**Fallback for phones without a trained profile:** use the HAL's `SENSOR_FORWARD_MATRIX1/2` and `SENSOR_CALIBRATION_TRANSFORM1/2` (camera → XYZ D50) as a starting matrix.

## Other sources
- Camera2 CaptureRequest reference: https://developer.android.com/reference/android/hardware/camera2/CaptureRequest
- Camera2 CaptureResult reference: https://developer.android.com/reference/android/hardware/camera2/CaptureResult
- CameraCharacteristics: https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics
- Samsung S24 camera spec page: https://www.samsung.com/levant/support/mobile-devices/what-is-the-specification-of-the-cameras-in-the-new-galaxy-s24-series
- S24/S24+ keep the S23 sensors: https://www.notebookcheck.net/New-Samsung-Galaxy-S24-and-S24-do-not-feature-camera-sensor-upgrades.795131.0.html

No files were written or edited.