# Mac lab tool

The original TV Calibrator for Mac: a small local web app around `tvcal.py`, working with a
Calibrite Display Plus HL through ArgyllCMS. In this project it's the **lab tool for making camera
profiles** for new iPhone models (see [../docs/ADDING-AN-IPHONE.md](../docs/ADDING-AN-IPHONE.md)).
It can also calibrate a TV directly with the meter.

- Requirements:
  - Python 3: `xcode-select --install`
  - ArgyllCMS: `brew install argyll-cms`
- Run it by opening `TV Calibrator.app`. It starts a server on `http://127.0.0.1:8765` and opens it
  in the browser. If macOS blocks the app, run `Start in Terminal.command` instead.
- Demo without a meter: `python3 "TV Calibrator.app/Contents/Resources/tvcal_app.py" --sim`.
- Data lives in `~/tvcal/`: history per TV, `camera_profiles.json`, and exported profiles in
  `Camera profiles/`.
- `Contents/Resources/clips/` holds 2-second HDR10 and Dolby Vision clips. They're used when an
  iPhone is the pattern source over HDMI.

`tvcal.py` also works on its own from the terminal. Its header lists the options.

Despite the folder's name, the tool also trains **Android** phones (TV Cal Lab for Android, built from
`../android`). Their model keys look like `samsung/e2q` or `google/panther`; the phone sends its
marketing name ("Galaxy S24+"), which becomes the profile's name. Exported files replace `/` with `_`.

The two **training videos** are made with ffmpeg, on the Mac or on Windows: `python3
../tools/make_sdr_training.py <usb drive>` ("Lab - Camera training (SDR).mp4") and `python3
../tools/make_hdr_training.py <usb drive>` ("Lab - Camera training (HDR10).mp4"). Play them from a USB
stick. Each run's raw readings go to `~/tvcal/Training/` (`last run.log` there traces the latest run).

## On Windows

The same files run on Windows 10/11 with Python 3 (standard library only). The `.app` folder is just a
folder there.

1. **Python 3**: from python.org (tick "Add python.exe to PATH"), or `winget install Python.Python.3.14`.
2. **ArgyllCMS 3.5.0, Windows 64-bit** zip from [argyllcms.com](https://www.argyllcms.com/) (3.1.0 or
   later). Unzip it, for example to `C:\Argyll_V3.5.0`, and add its `bin` folder to PATH (or set it in
   `Start on Windows.cmd`, which also finds `C:\Argyll_V*` by itself). The Display Plus HL uses
   Windows' own HID driver: no extra driver is needed.
3. **Quit Calibrite PROFILER** (its icon in the taskbar's notification area) before measuring: it holds
   the meter. Check with `spotread -?` (the meter should be listed, probably as an i1 DisplayPro) and
   `spotread -O -x -y n`. If the meter isn't port 1, the tool adds `-c N` by itself.
4. **Start** by double-clicking `Start on Windows.cmd`. It sets `PYTHONUTF8=1` and passes options
   through, for example `"Start on Windows.cmd" --sim` (demo) or `--port 8766 --no-browser`.
5. **Firewall**: the first start asks whether Python may use the network. Allow it on **Private**
   networks, and make sure the Wi-Fi is set to Private, or the phones can't connect. (As admin, the
   rule can also be added with
   `netsh advfirewall firewall add rule name="TV Calibrator lab" dir=in action=allow protocol=TCP localport=8765 profile=private`.)
6. **Port 8765** must be free. If another program has it, the tool says which process and a free port
   to start with instead (for example `--port 8766`).
7. **Pairing**: Windows has no Bonjour, so phones usually can't find the computer by themselves. The
   sidebar (and the console) show this computer's address, e.g. `192.168.1.20:8765`, and the pairing
   code: enter both in the phone's lab app. If Apple's Bonjour (`dns-sd`) or `pip install zeroconf` is
   present, the tool advertises itself as on the Mac. Compare the address with `ipconfig` if the PC
   has VPN or Hyper-V adapters.

Data lives in `%USERPROFILE%\tvcal\`, as on the Mac. "Show file" buttons open File Explorer.
