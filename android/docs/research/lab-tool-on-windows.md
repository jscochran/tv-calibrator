# Running the Mac lab tool on Windows and pairing an Android phone

Paths are relative to `tv-calibrator/`. `A` = `mac-lab/TV Calibrator.app/Contents/Resources/tvcal_app.py`, `T` = `.../Resources/tvcal.py`, `L` = `TV Calibrator/Lab/LabLink.swift`. I did not edit any files.

**What I checked on this PC:**
- **Startup bug:** a connection to a closed localhost port takes **2.03 s** to be refused. `port_in_use()` gives up after 1.0 s, so it says the port is busy when it is free. Without a fix the lab tool never starts (details below).
- **Port 8765 is already taken** by PID 17812, a separate `"C:\Program Files\Python314\python.exe" -m http.server 8765 --bind 127.0.0.1`. Stop it, or start the tool with `--port 8766`.
- **Python is 3.14.3.** `signal.SIGKILL` does not exist, and `HTTPServer.allow_reuse_address` is True.
- **Not installed yet:** `zeroconf`, `psutil` and ArgyllCMS (`spotread` is not on PATH).

## 1. macOS/POSIX-specific pieces and their Windows replacements

There is no `fcntl`, `termios`, `select`, `pty` or `os.fork` anywhere. spotread output is already read by a thread and a queue (T:260-266), which works on Windows.

| Piece | Where | Effect on Windows | Replacement |
|---|---|---|---|
| `.app` launcher: bash, Homebrew `PATH`, `osascript` alert, `rsync` App Translocation copy, log trim | `MacOS/TV Calibrator` | Cannot run | New `mac-lab/Start on Windows.cmd`: `mkdir "%USERPROFILE%\tvcal"`, `set PYTHONUTF8=1`, add Argyll `bin` to PATH if not set globally, `py -3 "%~dp0TV Calibrator.app\Contents\Resources\tvcal_app.py" %*`. Optional: redirect to `%USERPROFILE%\tvcal\app.log`. The folder named `.app` is just a folder on Windows. |
| `Start in Terminal.command` | whole file | Cannot run | The same `.cmd` |
| `port_in_use()` uses `settimeout(1.0)` and treats a timeout as "stuck program" | A:2328-2338 | **Fatal.** A free port reads as busy. `running_instance()` returns None, the tool tries to "stop a copy that isn't responding", finds nothing, and exits with "Port 8765 is used by another program". | On win32 use a timeout of 3.0 s or more. Keep 1.0 s on Mac. |
| `ThreadingHTTPServer(("0.0.0.0", port))` inherits `allow_reuse_address=True` | A:2440 | On Windows `SO_REUSEADDR` lets two processes bind the same port. That matters here, because http.server is already on 8765. | Subclass with `allow_reuse_address = sys.platform != "win32"`. On win32 also set `socket.SO_EXCLUSIVEADDRUSE` in `server_bind`. |
| `ps -p PID -o command=` | A:2366-2371 | `FileNotFoundError` is caught and returns False, so old copies are never stopped | win32: `powershell -NoProfile -Command "(Get-CimInstance Win32_Process -Filter 'ProcessId=N').CommandLine"` (checked here). `psutil.Process(pid).cmdline()` also works if installed. Do not use `wmic`, which Windows 11 removed. |
| `lsof -ti tcp:PORT -sTCP:LISTEN` | A:2385-2389 | Caught (OSError) and skipped | win32: run `netstat -ano -p TCP` and parse `LISTENING` rows with local `:PORT` (last column is the PID). Alternatives: `psutil.net_connections()` or PowerShell `Get-NetTCPConnection -LocalPort N -State Listen`. |
| `signal.SIGKILL` | A:2391 | **AttributeError crash** whenever an old instance is found | `(signal.SIGTERM, getattr(signal, "SIGKILL", signal.SIGTERM))`. `os.kill(pid, SIGTERM)` on Windows calls TerminateProcess. |
| `signal.signal(SIGTERM, …)` | A:2449 | Allowed on Windows; nothing ever sends it | Keep. Quitting from the page and Ctrl-C both work. |
| `reveal()` only acts when `sys.platform == "darwin"` (`open -R` / `open`) | A:253-255; callers A:2127, 2287, 2292 | Does nothing (path still returned in JSON) | Files: `subprocess.Popen(["explorer", "/select,", os.path.normpath(p)])`. Folders: `os.startfile(p)`. |
| Bonjour via `dns-sd -R name _tvcal._tcp local PORT ip= port= host=` and the `watch()` re-advertise thread | A:464-500, 2451-2452, 2324, 2464 | `shutil.which("dns-sd")` is None unless Apple's Bonjour for Windows is installed, so advertising is silently skipped. If it is installed, Apple's Windows `dns-sd.exe` takes the same syntax. | Keep dns-sd first so the Mac is unchanged. If it is missing, optionally `import zeroconf` (`pip install zeroconf`) and register `ServiceInfo("_tvcal._tcp.local.", f"{name}._tvcal._tcp.local.", addresses=[socket.inet_aton(ip)], port=port, properties={"ip": ip, "port": str(port), "host": host})`. `stop()`/`start()` become unregister/register. If neither exists, the page already shows `IP:port` and the pairing code (`tvcal_app.html`:365-373, 940), so the phone can be pointed at the IP by hand. |
| `~/tvcal` for data, PID file, settings, profiles, `Training/`, `TVs/` | A:40,43; T:1404 | `expanduser` gives `C:\Users\jscoc\tvcal`, which is outside OneDrive. Works as is. | Keep. Edge case: a TV called `CON`/`NUL`/`COM1`, or a name ending in a space, would break `tv_folder()` (A:158). Rare. |
| `write_json_atomic`: temp file `{path}.{pid}.tmp`, then `os.replace` | T:1421-1426 (settings, state) | `os.replace` fails with `PermissionError [WinError 5]` while another handler thread has the target open. That is likely: `_authed()` → `load_settings()` runs on every phone poll (about every 120 ms) (A:2006), and `GET /api/tvs` saves settings on every call (A:2049). Threads in the same process also share the temp name. | Temp name `f"{path}.{os.getpid()}.{threading.get_ident()}.tmp"`. Retry `os.replace` on PermissionError (for example 20 × 50 ms), and/or put a module lock around settings read and write. `save_profiles` (A:569) writes in place, so it is not affected. |
| `open(..., "w")` without an encoding | T:1424; A:570, 2125 | OK: `json.dump` writes ASCII. `print` uses `cd/m²` and `°` (T:531 etc.), which cp1252 can encode. | `PYTHONUTF8=1` in the launcher, to be safe |
| `ARGYLL_EXCLUDE_SERIAL_SCAN` set to macOS Bluetooth tty paths | T:243-244, T:1329, A:1917 | Harmless on Windows (no such ports) | On Windows Bluetooth shows up as COM ports. If `spotread -?` hangs, set the variable to them, for example `COM5;COM6`. |
| Meter detection: parse `-?` output with `^\s*(\d+)\s*=\s*'([^']+)'` and drop names starting with `/dev/` | A:1921-1924; T:1337-1338 | Windows serial ports are listed as `'COMn'`. They pass the filter, so "Meter found: COM3" can be a false positive. spotread defaults to `-c 1`, which may be a COM port. | Also drop `^(\\\\\.\\)?COM\d+$`. If the first real instrument is not port 1, use `-c N` (append automatically, or set `spotread_args` in `_app.json`). |
| Install hints `brew install argyll-cms`, "plug into the Mac" | A:1913-1914, 1926; T:1323, 1342 | Wrong text | Per-platform message: "Download ArgyllCMS for Windows (64-bit), unzip, add its bin folder to PATH". Also: quit the Calibrite PROFILER tray app. |
| spotread via `Popen(["spotread", "-x", ...], text=True, bufsize=1)`, stdin `" \n"` / `"q\n"` | T:255-257, 302, 278 | Works: CreateProcess searches PATH and adds `.exe`. Argyll requires "the character and return… written to stdin in a single operation" on MSWin. With text mode plus line buffering, `" \n"` goes out as one write of `" \r\n"`. | Keep. If the tool is launched with `pythonw`, add `creationflags=subprocess.CREATE_NO_WINDOW` on win32 to avoid console flashes. |
| `lan_ip()` (UDP connect trick) | A:455-461 | Works. It can pick a VPN, Hyper-V or WSL adapter. | Keep. The user compares it with `ipconfig`. |
| `idle_watchdog`, `webbrowser.open`, `ThreadingHTTPServer`, `urllib` | | Work on Windows | — |
| Windows Defender Firewall | | The first bind on `0.0.0.0` prompts for python.exe. If the Wi-Fi is set to Public, or the prompt is dismissed, the phone cannot connect. | The user (not the script) allows python.exe on **Private** networks, or as admin runs `netsh advfirewall firewall add rule name="TV Calibrator lab" dir=in action=allow protocol=TCP localport=8765 profile=private`. zeroconf also needs inbound UDP 5353; the per-program allow covers it. |

## 2. spotread on Windows and the Calibrite Display Plus HL

**How the tool runs spotread:**
- **Fast mode:** one long-running `spotread -x <args>`, default `-y n`, with `ARGYLL_NOT_INTERACTIVE=1` (T:250-273).
  - A reading is triggered by writing `" \n"`.
  - The result is the first line matching `XYZ:\s*(num)\s+(num)\s+(num)` within 20 s (T:292-316). The first reading at startup is allowed 25 s.
  - Shutdown writes `"q\n"` (T:278).
- **Fallback:** `spotread -O -x <args>` once per reading, 20 s timeout, using the last `XYZ:` match (T:318-335).
- **Arguments:** taken from `_app.json` `spotread_args`, split on whitespace (A:836-837).
- **Meter check:** `spotread -?` with a 30 s timeout, then three timed readings (A:1907-1945).

**Windows driver:** ArgyllCMS's Windows install page says the i1 Display Pro and ColorMunki Display "appear as an HID" device and use Windows' own HID driver, so no Argyll libusb driver is needed. Its instruments page lists "i1 DisplayPro, DisplayPro Plus… Calibrite ColorChecker Display/Pro/Plus [Other branded i1d3's are also reported to work]". It also notes that on MSWindows the maker's tray app may have to be shut down before Argyll can open the meter.

**Display Plus HL specifically:** Argyll's documentation does not name it, and nothing I found confirms support officially.
- A DisplayCAL forum answer says HL units use the i1 Display Pro retail/OEM unlock codes and so are supported. It hedges with "AFAIK".
- A user report says Argyll 3.1.0 detected an HL as "i1 DisplayPro".
- Argyll 3.0.0 made the i1d3 driver handle the Rev. B "0x83" error robustly.
- The project already uses the HL with Homebrew Argyll on the Mac (`docs/ADDING-AN-IPHONE.md`:5,11-13).

**Recommendation:** ArgyllCMS **3.5.0** (current, 4 Feb 2026), Windows 64-bit zip. Treat **3.1.0** as the minimum; this is not confirmed. Unzip it, add `...\Argyll_V3.5.0\bin` to PATH, quit Calibrite PROFILER, then check with `spotread -?` (the HL should be listed, probably as an i1 DisplayPro) and `spotread -O -x -y n`. Because the HL is an i1d3, `-y n` and the other built-in display types work as on the Mac.

Sources: [Argyll changes](https://www.argyllcms.com/doc/ChangesSummary.html), [Installing on MSWindows](https://www.argyllcms.com/doc/Installing_MSWindows.html), [Instruments](https://www.argyllcms.com/doc/instruments.html), [Environment (ARGYLL_NOT_INTERACTIVE, EXCLUDE_SERIAL_SCAN)](https://www.argyllcms.com/doc/Environment.html), [DisplayCAL hub: Display Plus HL](https://hub.displaycal.net/forums/topic/calibrite-display-plus-hl/), [AVS thread](https://www.avsforum.com/threads/calibrite-display-plus-hl-display-pro-hl-and-display-sl.3278872/) (not readable: paywall redirect), [displaycal.org Argyll page](https://displaycal.org/argyllcms/) (says "Display Pro/Plus/HL", but unreliable).

## 3. iPhone-specific parts of the protocol and of profile training/export

**Already model-agnostic, works as is:**
- The model is any string, from the `model` query parameter (A:2027). It is the key in `camera_profiles.json` (A:909, 1092), and `parse_qs` decodes `samsung%2FSM-S926U` correctly.
- Fitting, panels and stats (A:574-712) are pure maths.
- The export filename is sanitised: `/` becomes `_` (A:2123).
- Export JSON is `{"tvcal_camera_profile":1,"model":…,**p}` (A:2126). Import (A:2130-2146) is generic.
- Camera settings: `CAM_KEYS=("iso","format","lens","aperture")` (A:723). `cam_settings` keeps only non-None values (A:726-727). `settings_mismatch` compares exactly, with ISO ±0.5 and aperture ±0.05 (A:730-746). A string like `"4080x3060 RGGB"` and a lens like `"Back camera 0"` work, provided they are stable from reading to reading.
- **Aperture:** a lens with a fixed aperture should **omit** `aperture`. The rule "one locked, the other not" (A:737-740) flags a mismatch only when presence differs. This matches Kotlin `CameraSettings.aperture` ("null on fixed-aperture lenses"). If an Android lens really has a variable aperture, send it on every reading.
- **Placeholders:** the iPhone sends `iso: 0`, `format: ""`, `lens: ""` when a value is unknown (L:233-235). The Python side stores those and compares them. The Android side should omit unknown keys rather than send 0 or "".
- **`"ios"` field** (L:227): ignored by Python. `deliver()` stores the body, and only `ok`, `rgb`, `uneven`, `error` and the CAM_KEYS are used (A:986-1000, 549-554). Android may send `"android": Build.VERSION.RELEASE`, or nothing.
- **`device`** (iPhone: `UIDevice.current.name`) is display text only (A:2023, 2076).

**Needs to change:**
- **Model names:** `MODEL_NAMES` only knows iPhone identifiers (A:388-395).
  - `model_label` falls back to `"iPhone"` (A:399).
  - `run_bridge` uses `"iPhone"` / `"iPhone (demo)"` when the model is empty (A:908).
  - A new profile's name is `MODEL_NAMES.get(model, "")` (A:1092), so an Android profile has no marketing name. `build_catalog` then warns, and the UI shows the raw `samsung/SM-S926U`.
  - Fix: accept an optional `name` query parameter in `/api/phone/pattern`, store it as `ph.name`, and use `MODEL_NAMES.get(m) or ph.name or ""`. Change the fallback to `"phone"`. iPhone results stay the same.
- **User-facing text:** "iPhone", "iOS update" and "Mac" appear in A:313-314, 335-336, 551-553, 915, 918, 1082-1098, 1208, 2073, 2095-2096, 2169, 2207, 2252-2259, and in `tvcal_app.html` (57 "iPhone" mentions, e.g. 1051 "the paired iPhone", 1066). Making them generic is cosmetic.
- **Demo values** `format "4032x3024 grb4"`, `lens "Back Camera"` (A:522): `--sim` only. Leave them.
- **Internal value `"iphone"`** for source/meter mode (A:532, 807, 1205, 1210, 2250-2255): this is a page↔server value, not part of the phone protocol. Keep it.

**`tools/build_catalog.py` problems:**
- **Output path (L184) — fails:** `rel = f"profiles/{p['model']}-v{version}.json"`. For `samsung/SM-S926U` this is `profiles/samsung/SM-S926U-v1.json`. The subfolder is never created (only `profiles/` is, L149), so `open` raises FileNotFoundError. Model names with spaces (e.g. `Google/Pixel 8 Pro`) would also give URLs that need encoding.
  - Fix: build the filename from `re.sub(r"[^A-Za-z0-9,._-]+", "_", model)`. iPhone names like `iPhone18,1` come out unchanged.
  - Clients follow `entry.file` (ProfileService.swift:171), so the index stays valid.
- **Built-in file location:** `--builtin` defaults to the iOS `TV Calibrator/Resources/builtin-profiles.json` (L136-139). Android needs its own path, e.g. `android/app/src/main/assets/builtin-profiles.json`.
- **Standard (fallback) profile:** the built-in file includes **every** model, and `standard` falls back to `models[0]` (L206-217). An iPhone profile must never become the Android fallback, and the reverse.
  - Add `--platform ios|android`: Android = models containing `/`. It filters what goes into `--builtin`, and `--standard` is checked within that platform.
  - `server/index.json` can stay shared.
- **Required camera settings:** `convert` requires the `camera` dict to be non-empty (L61-62) and copies only non-None CAM_KEYS (L84). This works for Android if the phone sends iso/format/lens.
- **Text:** iPhone wording in the docstring and messages (L3-21, 59, 140-142, 161). `--name "samsung/SM-S926U=Galaxy S24+"` already works, because the split is on the first `=`.

## 4. The `/api/phone/*` protocol (Python side)

**Server:** `0.0.0.0:8765` (`--port` to change). Plain HTTP, JSON. Responses are `Content-Type: application/json; charset=utf-8` with `Cache-Control: no-store` (A:1954-1961).

**Authentication** (A:1996-2006):
- Requests from 127.0.0.1 or ::1 are always allowed.
- Anything else needs header `X-TVCal-Token: <tok>` or query `?t=<tok>`, and the token must be in `_app.json["phones"]`.
- Otherwise the reply is **403** `{"error":"Pair this device first."}`. GET `/` and `/api/version` are exempt. POST `/api/phone/pair` is checked before the auth test.

**Endpoints:**
- **POST `/api/phone/pair`** (A:2067-2079)
  - Body `{"code":"NNNN","device":"<name>"}`. The code is compared after `str().strip()` with `PAIR_CODE`, 4 random digits per run (A:47).
  - **200** `{"token":"<32 hex>","mac":"<hostname>"}`. The token is saved with `{device, paired}`.
  - **403** `{"error":"That code doesn't match…"}`. After 8 wrong codes a new code is generated.
- **GET `/api/phone/pattern`** (A:2018-2028). This is the heartbeat.
  - Query: `t`, `device`, `ext` (`"1"` means a TV is attached as pattern player; Android lab sends `0`), `extname`, `cam` (`"1"` means the camera is ready), `model`.
  - Updates `last_seen`, and `first_seen` when the phone was not connected.
  - **200** `{"seq":int,"pattern":{…},"measure":int}`. Pattern is one of:
    - `{"kind":"black"}`
    - `{"kind":"patch","rgb":[r,g,b],"window":0.1,"name":…}`
    - `{"kind":"clip","url":"/clips/{hdr10|dv}/{ID}.mp4","name","hdr":true,"mode"}` (A:374-384). Fetching a clip also needs the token; it supports Range requests (A:1963-1990).
- **POST `/api/phone/ack?t=`** `{"seq":int}`: raises the phone's `ack` to `seq` (A:2219-2221). **200** `{"ok":true}`.
- **POST `/api/phone/reading?t=`** (A:2086-2088): **200** `{"ok":true}`. Dropped unless `seq` is still awaited (A:350-354). Body:
  - `{"seq":int,"ok":bool,"model","rgb":[3 floats],"duration","uneven","peak","iso","format","lens","aperture"?}`, or `"error":str` when `ok` is false.
- **POST `/api/phone/log?t=`** `{"line","model"}`: appends to `~/tvcal/phone.log`, truncated to 500 characters.
- **Browser-only:** `GET /api/phone/status`, `POST /api/phone/show`, `POST /api/phone/forget`. Forget clears every token, so the phones' next poll gets 403, they forget their pairing and browse again (L183-185).

**Timing and sequencing:**
- **Connected / training:** a phone counts as connected while its last poll is under 3.0 s old (A:285-286). Training uses connected phones with `cam=1` (A:446-452).
- **Phone polling:** about every 0.12 s while OK, 1 s after an error, 3 s request timeout. Phone-side constants, L49/199/205.
- **Patterns:** `show_patch` bumps `seq` and waits up to 12 s for `ack ≥ seq`, else "stopped responding" (A:302-315). In direct mode it then waits 1.2 s before reading (A:1016).
- **Readings:** `ask()` bumps `measure`. It starts at `int(time()) - 1_700_000_000`, so it keeps rising across restarts.
  - The phone measures when `measure > lastMeasure` and it is not busy (L194-198).
  - The server waits up to 25 s overall (A:957). A late reply counts only if the meter's re-read shows the patch unchanged (A:943-949, 994).
  - `collect()` uses a 25 s default and 1 s inside `take()`.
- **Idle shutdown:** after 20 minutes without requests (A:41, 2404-2413). Phone polls count as activity.
- **Discovery TXT record:** `ip`, `port`, `host` (A:476-477). LabLink tries `ip` first, then `host` (L99-110).

## 5. Minimal patch plan (each step guarded by `sys.platform == "win32"` or by a feature check, so the Mac is unchanged)

1. **A `port_in_use`:** timeout of 3 s or more on win32. Without this the tool cannot start.
2. **A `main`:** server subclass with `allow_reuse_address = sys.platform != "win32"` plus `SO_EXCLUSIVEADDRUSE` on win32.
3. **A `stop_old_instance` / `_is_tvcal`:** `getattr(signal, "SIGKILL", SIGTERM)`. On win32, `netstat -ano` instead of `lsof`, and PowerShell `Get-CimInstance` for the command line (use psutil if it is importable).
4. **T `write_json_atomic`:** per-thread temp name, and retry `os.replace` on PermissionError.
5. **A `reveal`:** `os.startfile` for folders, `explorer /select,` for files.
6. **A Bonjour:** keep dns-sd. If it is missing, use `zeroconf` when installed. Otherwise do nothing; the IP is entered by hand from the page.
7. **A `meter_check` and T `check`:** ignore `COMn` ports, per-platform install hint, and suggest or auto-append `-c N`.
8. **New `mac-lab/Start on Windows.cmd`:** `PYTHONUTF8=1`, optional Argyll PATH line, `py -3 …\tvcal_app.py %*`. Update the `mac-lab/README.md` setup text: ArgyllCMS 3.5.0 Win64, PATH, quit Calibrite PROFILER, allow python.exe on Private networks, free port 8765.
9. **A, phone generalisation:** optional `name` query parameter stored on `Phone`. Name fallback `MODEL_NAMES.get(m) or ph.name or ""`. Generic default label `"phone"`. Neutral wording in errors and HTML (optional).
10. **`tools/build_catalog.py`:** safe profile filename for models containing `/` or spaces. `--platform` filter so the built-in file and `standard` are per platform. Document the Android `--builtin` path.
11. **Android lab client (mirror of `LabLink.swift`):**
    - Same endpoints and query parameters, with `model` = `"${Build.MANUFACTURER}/${Build.MODEL}"`.
    - Omit unknown `iso`/`format`/`lens`, and omit `aperture` on fixed lenses.
    - URL-encode query values.
    - Discover with NsdManager `_tvcal._tcp` (read TXT `ip`/`port`/`host`), with manual IP:port entry as a fallback.
    - The lab manifest already has cleartext traffic and the multicast permission (`android/app/src/lab/AndroidManifest.xml`).
