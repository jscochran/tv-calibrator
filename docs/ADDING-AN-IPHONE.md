# Adding an iPhone model

Each iPhone model needs a **camera profile** before the app can measure with it. A profile turns the
camera's raw sensor numbers into real colour. It's made by measuring the same patches with the phone
and with a professional colorimeter (Calibrite Display Plus HL) on a few TVs.

This is a developer job, done once per model. Users never do it.

## You need

- A Mac with Python 3 (`xcode-select --install`), [ArgyllCMS](https://www.argyllcms.com)
  (`brew install argyll-cms`) and Xcode 27.
- A Calibrite Display Plus HL (or another meter ArgyllCMS's `spotread` supports).
- The new iPhone.
- Two or more TVs, ideally different panel types (OLED and LCD). With two or more, the Mac can
  estimate how accurate the profile will be on a TV it has never seen.

## 1. Put the Lab app on the iPhone

1. Open `TV Calibrator.xcodeproj`.
2. Choose the **TV Calibrator Lab** scheme, select the iPhone, and Run.
3. It installs as **TV Cal Lab**, next to the normal app.

The Lab app is the same app with the pairing code switched on. It reads the camera with exactly the
code App Store users run, so the profile matches what they'll get.

## 2. Start the Mac lab tool

1. Open `mac-lab/TV Calibrator.app`. If macOS blocks it, use `mac-lab/Start in Terminal.command`
   instead.
2. Plug in the Display Plus HL.
3. Add the TV you're using in the sidebar, then open **Train phone profiles** (under Tools).

## 3. Pair the phones

Several iPhones can train at once, each for its own model. On each one:

1. In TV Cal Lab, open **Lab mode**.
2. Pick the Mac from the list and type the 4-digit code shown on the Mac.
3. Start the camera, then cover the lens and measure the darkness.

The Train phone profiles page lists every connected phone and whether its camera is ready.

## 4. Train on a TV

1. Rest the meter on the centre of the screen. Put the phones on stands 15–30 cm from the screen,
   each aimed at a different spot of the big centre patch around the meter (not at the meter). Each
   phone's yellow box must sit fully inside the patch.
2. On the Mac, choose the TV and click **Train the profiles**.
3. Play **Lab - Camera training (SDR).mp4** on the TV from the start (make it with
   `python3 tools/make_sdr_training.py <usb drive>`, on the Mac or the Windows lab PC, and play it from a
   USB stick; it isn't on YouTube). It has 46 patches of 12 seconds, each a band across the full width of the
   screen: greys, colours at three levels, tinted greys, skin tones and mixed colours. Each new patch is measured by the meter and every phone
   at once.
4. The Mac finishes about 45 seconds after the video ends (or click **Finish now**).

If a phone stops answering during the run (its lab app closed, or its screen went off), it's left out
of the rest of the run and the others carry on.

The Mac then shows, for each phone, how closely it matched the meter on this TV, and a table of every
patch as the meter measured it and as each phone reads it. All the raw readings are saved in
`~/tvcal/Training/`. Repeat on the other TVs. Each TV improves each profile, and from the second TV
on the Mac also estimates accuracy on unseen TVs.

**Check the profiles only** measures the same way but scores the existing profiles without changing
them.

**HDR too.** After the SDR run on a TV, choose **Video: HDR10** and play **Lab - Camera training
(HDR10).mp4** (made by `tools/make_hdr_training.py`; play it from a USB stick so the TV switches to
HDR). Its patches are brighter (up to 1000 nits) and more saturated (P3), so the profile also covers what
the phone sees in HDR. The readings go into the same profiles.

Both training videos show each patch as a band across the full width of the screen, so the meter and
each phone get their own spot.

## 5. Publish

1. On the Mac, go to **Camera profiles**. Rename the profile to the phone's marketing name (e.g.
   "iPhone 18 Pro"), then **Export**. The file is saved in `~/tvcal/Camera profiles/`.
2. Optionally, test it first: in TV Cal Lab, **Lab mode › Import a profile file**.
3. Add it to the server folder and publish:

   ```sh
   python3 tools/build_catalog.py ~/tvcal/Camera\ profiles/"iPhone 18 Pro camera profile.json"
   cd push-worker && npx wrangler deploy
   ```

   That publishes it within a minute (see [HOSTING.md](HOSTING.md)), and phones pick it up the next
   time they open the app, with no App Store update. Commit and push `server/` too, so the repo has
   the profile's history.

   The script also copies every published iPhone profile into the app
   (`TV Calibrator/Resources/builtin-profiles.json`), so the next App Store build has it built in.
   That file also names the **standard profile** used by iPhones that have none of their own; change
   it with `--standard <model>`. You can also pass the Mac app's whole `~/tvcal/camera_profiles.json`
   instead of exported files, with `--name "iPhone18,1=iPhone 18 Pro"` if a model has no name yet.

`build_catalog.py` refuses a profile whose estimated error on unseen TVs is above dE 3. If you
retrain a model, it gets a new version number automatically.

**Android phones** are published with the same script and share `server/index.json`. Any model
that doesn't start with `iPhone` (e.g. `samsung/e2q`) counts as Android. It is saved as
`server/profiles/samsung_e2q-v1.json` and goes only into the Android app's built-in file
(`android/app/src/main/assets/builtin-profiles.json`), so the iPhone app never gets an Android
profile or an Android standard profile, and the Android app never gets an iPhone one. See
[android/README.md](../android/README.md#publishing-a-profile).

## Notes

- **Model identifier:** profiles are matched by the phone's hardware model (e.g. `iPhone18,1`),
  shown in the app's Settings. Different sizes of the same generation (Pro and Pro Max) are
  different identifiers, so each needs its own profile. They often share a main camera; if so,
  training one and copying its profile under the other identifier is reasonable, but measure to
  confirm.
- **Locked settings:** a profile records ISO, RAW format, lens and aperture. If an iOS update changes
  any of them, the app stops measuring and asks for a newer profile instead of giving wrong readings.
  Retrain and publish.
- **Aperture:** a profile records the aperture it was trained at and the app locks the same one. The
  Lab build always locks one on variable-aperture iPhones (the profile's, or f/2.8), so every training
  session collects light the same way. The first iPhone 18 Pro profile was trained without a lock, so
  the App Store build leaves the aperture alone for it, as in training; the Mac won't mix sessions with
  and without a locked aperture into one profile.
- **Sensor white level:** some iPhones' RAW data tops out below 14 bits. The app reads the level from
  the photo's DNG data (and learns it from clipped photos), so bright patches are never accepted clipped.
