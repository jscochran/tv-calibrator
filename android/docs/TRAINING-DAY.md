# Training day: camera profiles for Android phones

How to train camera profiles for Android phones with a Calibrite Display Plus HL on a Windows PC.
These are the steps that were rehearsed end to end on 2026-11-26 with a Galaxy S24+ and a Pixel 7,
using the lab tool's simulated meter. The real meter is the only new part.
The Mac steps are in [../../docs/ADDING-AN-IPHONE.md](../../docs/ADDING-AN-IPHONE.md).

## What you need

- **The meter:** a Calibrite Display Plus HL, plugged **straight into the PC** (not a hub).
- **ArgyllCMS 3.5.0** unzipped as `%USERPROFILE%\Argyll_V3.5.0`. The launcher finds it there, so there's
  no PATH to set. Quit Calibrite PROFILER if it's installed: it holds on to the meter.
- **The phones**, charged, with the lab app **"TV Cal Lab for Android"** installed. Install it from
  `android/` with `.\gradlew.bat :app:installLabDebug`; it installs alongside the normal app.
  The PC and the phones must be on the **same Wi-Fi**.
- **Training videos on a USB stick:** `Lab - Camera training (SDR).mp4` and
  `Lab - Camera training (HDR10).mp4`. Make them with `python tools/make_sdr_training.py <usb>` and
  `python tools/make_hdr_training.py <usb>`. Play them from the TV's own media player, so the TV
  switches to HDR for the HDR10 one.
- **Two or more TVs per screen type**, ideally an OLED and an LCD. With only one TV of a type, the
  tool can't estimate accuracy on TVs it hasn't seen, and nothing stops a bad profile from being
  published.
- **A dark room.**

## 1. Start the lab tool and check the meter

1. Double-click `mac-lab/Start on Windows.cmd`. The page opens at `http://127.0.0.1:8765`.
   - If another program already uses port 8765, run `"Start on Windows.cmd" --port 8766`.
   - **Keep the page open all session.** The tool closes itself after 20 minutes with no page open
     and no phone in Lab mode.
2. Check the meter: tick **Pro tools** at the bottom of the sidebar, then open **Tools › Check the
   meter**, or run
   `python tvcal.py --tv "Meter check" --check` from `mac-lab/TV Calibrator.app/Contents/Resources`.
   Both **"ArgyllCMS installed"** and **"Meter detected"** should pass. The meter usually shows up as
   an i1 DisplayPro.
3. The first time a phone connects, Windows may ask whether Python may use the network. Allow it on
   **Private** networks, and make sure your Wi-Fi is set to Private.

## 2. Pair each phone

On each phone:

1. Open **TV Cal Lab for Android**, then **Lab mode** (the last item on the home screen).
2. Tap **Start the camera**.
3. Lay the phone **screen-up on a table, back camera pressed flat against it**, and tap **Measure
   darkness (cover the lens)**. A fingertip isn't dark enough.
4. Type the PC's address and the 4-digit code. Both are in the lab tool's sidebar under **Phones**,
   for example `10.0.1.67:8765`. Tap **Pair**.
   - Windows can't announce the PC on the network, so the "Computers nearby" list stays empty. That's
     expected.

The lab tool's sidebar should now list each phone as connected, with its camera ready:
- Galaxy S24+ (`samsung/e2q`)
- Pixel 7 (`google/panther`)

## 3. Train on a TV

1. In the lab tool's sidebar, add the TV and set **what kind of screen** it is. The type matters: each
   type gets its own conversion.
2. Rest the meter on the **centre of the screen**.
3. Stand the phones 15–30 cm from the screen, each aimed at a **different spot of the big centre patch
   around the meter**, not at the meter itself. Each phone's yellow box must sit fully inside the
   patch. Keep the lab apps open on **Lab mode**.
4. Open **Train phone profiles**, choose the TV, and click **Train the profiles**.
5. Play **`Lab - Camera training (SDR).mp4`** on the TV from the start. It runs 46 patches of 12 s,
   about 10 minutes, and the tool finishes about 45 s after the video ends.
6. **Don't touch the phones until it's done.** A back swipe takes a phone out of Lab mode, and the tool
   then leaves it out of the rest of the run ("stopped polling").
7. **HDR too:** set the **Video** dropdown to **HDR10**, click Train again, and play `Lab - Camera training (HDR10).mp4`
   from the USB stick. Check the TV shows its HDR badge.

Repeat on the other TVs. Each one improves each phone's profile, and from the second TV of a screen type
on, the tool estimates the accuracy on TVs it hasn't seen.

## 4. Check, export, test

1. Check the results under **Camera profiles**. The tool shows each phone's dE against the meter, per
   screen type, and the estimate for unseen TVs. `build_catalog.py` refuses an unseen-TV dE above 3.
2. Rename each profile to the phone's marketing name ("Galaxy S24+", "Pixel 7"), then **Export**. The
   files land in `%USERPROFILE%\tvcal\Camera profiles\`.
3. Optional: try a profile before publishing it. In the lab app, go to **Lab mode › Import a profile
   file**, then use the Live meter on a known white.

## 5. Publish

From the repo:

```powershell
python tools/build_catalog.py --platform android --standard samsung/e2q "$env:USERPROFILE\tvcal\Camera profiles\Galaxy S24+ camera profile.json" "$env:USERPROFILE\tvcal\Camera profiles\Pixel 7 camera profile.json"
```

This writes `server/profiles/samsung_e2q-v1.json` and `google_panther-v1.json`, updates
`server/index.json`, and rewrites the Android app's built-in profiles
(`android/app/src/main/assets/builtin-profiles.json`). `--standard` sets the last-resort profile for
Android phones that have no profile of their own. The app picks, in order: an imported profile, the
phone's own (built in or downloaded), an approximate one made from the camera's own DNG colour data, and
only then the standard one. So the standard profile is used by phones whose colour data is a generic
sRGB placeholder (like the Pixel 7 before it has its own profile) or missing, and briefly on a first start
until the camera permission is granted. Brightness (cd/m²) from the approximate and standard profiles is
only an estimate, about ±50%.

Commit and push. Phones download new profiles from the fork's `server/` folder (GitHub raw) the next time
the app opens. Rebuild the standard app to include them built in. Retraining a model later bumps its
version automatically.

## If something goes wrong

| Problem | Fix |
|---|---|
| Meter not detected | Plug it straight into the PC, quit Calibrite PROFILER, check again. |
| Phone says "No answer from …" | Allow Python through Windows Defender Firewall on Private networks; check the phone is on the same Wi-Fi. |
| A phone was left out ("stopped polling") | Its lab app left Lab mode or its screen went off. Reopen Lab mode; it reconnects by itself, then run again. |
| Lab tool gone | It closes after 20 idle minutes; start it again. Pairings are kept. |
| Phone camera "not ready" | Measure the darkness again in Lab mode (screen-up, camera flat on the table). |
| Pixel 7 shows no approximate profile | Expected: its camera reports generic colour data, so it needs a trained profile. |
