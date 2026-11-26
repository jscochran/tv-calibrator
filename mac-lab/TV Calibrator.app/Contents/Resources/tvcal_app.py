#!/usr/bin/env python3
"""
TV Calibrator - a small local app around tvcal.py.

Runs a web server on this computer (http://127.0.0.1:8765) and opens it in your browser. Devices on
your network can only use it after pairing with the code shown in the app (the iPhone and Android
lab apps do this). Nothing is sent anywhere else. Quit from the app's sidebar.

    python3 tvcal_app.py          # with the meter
    python3 tvcal_app.py --sim    # demo with a simulated TV + meter
    python3 tvcal_app.py --sim --port 8766 --no-browser   # another port, no browser window

Runs on macOS and Windows (see mac-lab/README.md).
"""

import argparse
import collections
import functools
import hashlib
import json
import math
import os
import random
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import webbrowser
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

HERE = os.path.dirname(os.path.abspath(__file__))
print = functools.partial(print, flush=True)      # messages reach the log right away
sys.path.insert(0, HERE)
import tvcal as T  # noqa: E402

PORT = 8765
APP_VERSION = os.environ.get("TVCAL_VERSION", "2026.11.12d")
PID_FILE = os.path.expanduser("~/tvcal/app-{port}.pid")
IDLE_QUIT = 20 * 60          # quit after 20 minutes with no browser tab or phone talking to it
LAST_ACTIVITY = time.time()
APP_DIR = os.path.expanduser("~/tvcal")
SETTINGS = os.path.join(APP_DIR, "_app.json")
SIM = False
LOCK = threading.Lock()        # one thing talks to the meter at a time
PAIR_CODE = f"{secrets.randbelow(10000):04d}"   # shown in the app on the computer; typed on the phone once
WINDOWS = sys.platform == "win32"
COMPUTER = "Mac" if sys.platform == "darwin" else "computer"     # in messages: "the Mac" / "the computer"
_NO_WINDOW = {"creationflags": subprocess.CREATE_NO_WINDOW} if WINDOWS else {}   # helper tools: no console flash
DEFAULT_PHONE = "iPhone" if sys.platform == "darwin" else "phone"   # a phone that sent no model (macOS: as before)


# ======================================================================== settings & state

def load_settings():
    os.makedirs(APP_DIR, exist_ok=True)
    for _ in range(5):
        if not os.path.exists(SETTINGS):
            break
        try:
            with T.io_lock(), open(SETTINGS) as f:
                return json.load(f)
        except (ValueError, OSError):
            time.sleep(0.05)            # caught it mid-write (older copies wrote in place); try again
    return {"tvs": {}, "spotread_args": "-y n"}


def save_settings(s):
    T.write_json_atomic(SETTINGS, s)


def tv_key(name):
    return name + ("-sim" if SIM else "")


def state_for(name, hdr):
    path = T.state_path(tv_key(name), hdr)
    if os.path.exists(path):
        with T.io_lock(), open(path) as f:
            st = json.load(f)
        if st.get("version", 1) < T.STATE_VERSION:
            return path, None
        return path, st
    return path, {"version": T.STATE_VERSION, "rounds": [], "sens": {}}


def discover_tvs(settings):
    """TVs from app settings plus any history files the terminal tool created."""
    names = set(settings["tvs"])
    known = {re.sub(r"[^A-Za-z0-9_-]+", "_", n) for n in names}
    if os.path.isdir(APP_DIR):
        for fn in os.listdir(APP_DIR):
            m = re.match(r"(.+)-(sdr|hdr)\.json$", fn)
            if m and not fn.startswith("_"):
                n = m.group(1)
                if SIM and n.endswith("-sim"):
                    n = n[:-4]
                elif SIM or n.endswith("-sim"):
                    continue
                if n not in known:
                    names.add(n)
    for n in names:
        settings["tvs"].setdefault(n, {"points": 20, "cms": "samsung"})
    return sorted(names, key=str.lower)


# ======================================================================== JSON views of engine data

def report_json(rep, hdr):
    if not rep:
        return None
    greys = [{"id": g["id"], "name": g["name"], "level": g["level"], "Y": g["Y"], "targetY": g["targetY"],
              "bal": g["bal"], "de": g["de"], "clipped": g.get("clipped", False),
              "uv": T.upvp(*T.xyz_to_xy(*g["XYZ"]))} for g in rep["greys"]]
    cols = [{"name": c["name"], "de": c["de"], "sat": c["sat"], "hue": c["hue"], "lum": c["lum"],
             "uv": T.upvp(*c["xy"]), "tuv": T.upvp(*c["txy"])} for c in rep["colours"]]
    gamma = None
    if not hdr and rep.get("white"):
        gs = [math.log(g["Y"] / rep["white"][1]) / math.log(g["level"])
              for g in rep["greys"] if 0.1 <= g["level"] <= 0.9 and g["Y"] > 0]
        gamma = sum(gs) / len(gs) if gs else None
    white_uv = T.upvp(*T.xyz_to_xy(*rep["white"])) if rep.get("white") else None
    pq_track = None
    if hdr:
        trk = [g["Y"] / g["targetY"] - 1 for g in rep["greys"] if g["targetY"] < 300 and g["targetY"] > 0]
        pq_track = 100 * sum(trk) / len(trk) if trk else None
    return {"greys": greys, "colours": cols, "gamma": gamma, "drift": rep.get("drift"), "pq_track": pq_track,
            "warnings": rep["warnings"], "grey_avg": T.grey_avg(rep, hdr) if rep["greys"] else None,
            "colour_avg": T.colour_avg(rep) if rep["colours"] else None,
            "white_Y": rep["white"][1] if rep.get("white") else None, "white_uv": white_uv,
            "d65_uv": T.upvp(*T.D65)}


def tv_view(name, hdr):
    settings = load_settings()
    path, st = state_for(name, hdr)
    if st is None:
        return {"name": name, "hdr": hdr, "old": True}
    rounds = []
    for i, r in enumerate(st["rounds"]):
        rep = T.analyse(r["meas"], hdr)
        rounds.append({"n": i + 1, "time": r.get("time"), "kind": r.get("kind"), "applied": bool(r.get("applied")),
                       "grey": T.grey_avg(rep, hdr) if rep["greys"] else None,
                       "colour": T.colour_avg(rep) if rep["colours"] else None})
    last = prev = None
    if st["rounds"]:
        last = report_json(T.analyse(st["rounds"][-1]["meas"], hdr), hdr)
    if len(st["rounds"]) >= 2:
        prev = report_json(T.analyse(st["rounds"][-2]["meas"], hdr), hdr)
    cfg = settings["tvs"].get(name, {"points": 20, "cms": "samsung"})
    model, prof = profile_and_model(PHONE.model)
    return {"name": name, "hdr": hdr, "rounds": rounds, "last": last, "prev": prev,
            "camera": profile_summary(prof, model, name),
            "pending": st.get("pending"), "settings": cfg,
            "video": "tvcal_auto_HDR.mp4" if hdr else "tvcal_auto_SDR.mp4",
            "video_len": T.video_length(hdr)}


# ======================================================================== per-TV folder: readable log + export

def tv_folder(name):
    safe = re.sub(r"[^A-Za-z0-9 _-]+", "_", name).strip() or "TV"
    d = os.path.join(APP_DIR, "TVs", safe + (" (demo)" if SIM else ""))
    os.makedirs(d, exist_ok=True)
    return d


def _append(name, text):
    with open(os.path.join(tv_folder(name), "log.md"), "a", encoding="utf-8") as f:
        f.write(text)


def _changes_md(changes):
    if not changes:
        return "  (none)\n"
    lines = []
    for k in sorted(changes):
        parts = k.split(":")
        if parts[0] in ("gain", "offset"):
            label = f"White Balance 2 Point  {parts[1]}-{parts[0].title()}"
        elif parts[0] == "p20":
            label = f"White Balance 20 Point  interval {int(parts[2]) // 5} ({parts[2]}%)  {parts[1]}"
        elif parts[0] == "cms":
            label = f"Color Space  {parts[1].title()}  {parts[2]} slider"
        elif parts[0] == "hsl":
            label = f"Color Management  {parts[1].title()}  {dict(hue='Hue', sat='Saturation', lum='Brightness')[parts[2]]}"
        else:
            label = k
        lines.append(f"  - {label}: {changes[k]:+d}")
    return "\n".join(lines) + "\n"


def log_round(name, hdr, rnd, plan, n, discarded=False):
    try:
        rep = plan["rep"]
        setup = rnd.get("setup", {})
        out = [f"\n## {'HDR' if hdr else 'SDR'} round {n}  ({rnd.get('time', '')})"
               + ("  - NOT KEPT" if discarded else "") + "\n",
               f"Patterns: {setup.get('source', '?')}   Meter: {setup.get('meter', '?')}"
               + (f" ({setup.get('phone')})" if setup.get("phone") else "") + "\n"]
        if rep.get("greys"):
            out.append(f"Average greyscale dE {T.grey_avg(rep, hdr):.2f}, colour dE {T.colour_avg(rep):.2f}"
                       + (f", white {rep['white'][1]:.1f} cd/m²" if rep.get("white") else "")
                       + (f", drift {rep['drift']:+.1f}%" if rep.get("drift") is not None else "") + "\n\n")
            out.append("| step | cd/m² | target | R | G | B | dE | x | y |\n|---|---|---|---|---|---|---|---|---|\n")
            for g in rep["greys"]:
                x, y = T.xyz_to_xy(*g["XYZ"])
                out.append(f"| {g['name']} | {g['Y']:.2f} | {g['targetY']:.2f} | {g['bal'][0] * 100:.1f} | "
                           f"{g['bal'][1] * 100:.1f} | {g['bal'][2] * 100:.1f} | {g['de']:.2f} | {x:.4f} | {y:.4f} |\n")
            out.append("\n| colour | dE | saturation | hue | brightness |\n|---|---|---|---|---|\n")
            for c in rep["colours"]:
                out.append(f"| {c['name'].title()} | {c['de']:.2f} | {c['sat']:+.1f}% | {c['hue']:+.1f}° | {c['lum']:+.1f}% |\n")
        for w in rep.get("warnings", []):
            out.append(f"\nWarning: {w}\n")
        if plan.get("message"):
            out.append(f"\n{plan['message']}\n")
        if plan.get("changes"):
            out.append(f"\nAsked for ({plan.get('kind')}): {plan.get('title', '')}\n" + _changes_md(plan["changes"]))
        if plan.get("done"):
            out.append("\nResult: on target / nothing more to change.\n")
        _append(name, "".join(out))
    except Exception as e:      # logging must never break a round
        print("log_round failed:", e)


def log_confirm(name, hdr, n, plan, ok):
    _append(name, f"\n{'SDR' if not hdr else 'HDR'} round {n}: changes "
                  f"{'MADE on the TV' if ok else 'SKIPPED (not recorded)'} at {datetime.now():%Y-%m-%d %H:%M}\n")


def log_bridge(name, pairs, fit, model):
    out = [f"\n## Taught the phone camera ({model}) {datetime.now():%Y-%m-%d %H:%M}\n",
           f"Average dE vs the meter {fit['de_avg']:.2f}, worst {fit['de_max']:.2f}, {fit['n']} patches\n\n",
           "| patch | cd/m² | x | y | camera dE |\n|---|---|---|---|---|\n"]
    for p, de in zip(pairs, fit["des"]):
        out.append(f"| {p['name']} | {p['Y']:.2f} | {p['x']:.4f} | {p['y']:.4f} | {de:.2f} |\n")
    _append(name, "".join(out))


def export_tv(name):
    """One file with everything about this TV, to share for troubleshooting."""
    data = {"exported": datetime.now().isoformat(timespec="seconds"), "tv": name,
            "settings": load_settings().get("tvs", {}).get(name),
            "camera_profiles": {m: profile_summary(p, m, name) for m, p in load_profiles().items()},
            "history": {}}
    for hdr in (False, True):
        path, st = state_for(name, hdr)
        if st and st.get("rounds"):
            data["history"]["hdr" if hdr else "sdr"] = {k: v for k, v in st.items() if k != "sim"}
    fn = os.path.join(tv_folder(name), f"{name} export {datetime.now():%Y-%m-%d %H%M}.json")
    with open(fn, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1)
    return fn


def reveal(path):
    """Show a file (selected) or a folder in Finder / File Explorer."""
    if sys.platform == "darwin":
        subprocess.Popen(["open", "-R", path] if os.path.isfile(path) else ["open", path])
    elif WINDOWS:
        p = os.path.normpath(path)
        try:
            if os.path.isfile(p):
                # one command-line string: explorer wants /select,"path" exactly like this
                subprocess.Popen(f'explorer /select,"{p}"')
            else:
                os.startfile(p)
        except OSError as e:
            print("Couldn't open File Explorer:", e)


# ======================================================================== phones (iPhone: pattern player + remote; any phone: camera)

class Phone:
    """One paired phone (iPhone or Android). It polls for the pattern to show (and confirms when it's on
    the TV) and for camera readings to take. Several can be connected at once (training profiles side by
    side)."""

    def __init__(self, token=""):
        self.token = token
        self.first_seen = 0.0
        self.seq = 0
        self.pattern = {"kind": "black"}
        self.shown = None          # the patch dict the phone last confirmed
        self.ack = 0
        self.last_seen = 0.0
        self.device = ""
        self.external = False
        self.ext_name = ""
        self.cv = threading.Condition()
        self.bad_codes = 0
        # Camera reading requests. Starts from the clock so it keeps rising across restarts of this app:
        # a phone ignores request numbers it has already answered.
        self.mseq = int(time.time()) - 1_700_000_000
        self.waiting = set()
        self.results = {}
        self.cam_ready = False
        self.model = ""          # iPhone: "iPhone18,1"; Android: "manufacturer/device", e.g. "samsung/e2q"
        self.name = ""           # marketing name the phone sent, e.g. "Galaxy S24+" (optional)
        self.os = ""             # e.g. "iOS 26.1" or "Android 16" (optional, for the records)

    def connected(self):
        return time.time() - self.last_seen < 3.0

    def status(self):
        return {"connected": self.connected(), "device": self.device, "external": self.external,
                "ext_name": self.ext_name, "code": PAIR_CODE,
                "camera": self.connected() and self.cam_ready, "model": self.model,
                "label": model_label(self.model, None, self.name) if self.model else "",
                "paired": bool(load_settings().get("phones")), "host": socket.gethostname(), "ip": lan_ip(),
                "port": PORT, "computer": COMPUTER, "discovery": BONJOUR.kind}

    def set(self, pattern, patch=None):
        with self.cv:
            self.seq += 1
            self.pattern = pattern
            self._pending_patch = patch
            return self.seq

    def show_patch(self, p, timeout=12.0, mode=None):
        """Put a patch on the TV and wait for the phone to confirm it's showing."""
        mode = mode or ("hdr10" if "nits" in p else "sdr")
        if SIM and not self.connected():          # demo without a phone: pretend it showed it
            self.set(patch_pattern(p, mode), p)
            self.shown = p
            return
        seq = self.set(patch_pattern(p, mode), p)
        with self.cv:
            ok = self.cv.wait_for(lambda: self.ack >= seq, timeout=timeout)
        if not ok:
            raise RuntimeError("The phone stopped responding. Keep the TV Calibrator app open on the "
                               "phone, with the screen on.")
        self.shown = p

    def ask(self):
        """Ask the phone's camera for a reading; returns the request number for `collect`. Several
        phones asked one after another measure at the same time."""
        if SIM and not self.connected():
            return None
        with self.cv:
            self.mseq += 1
            self.waiting.add(self.mseq)
            return self.mseq

    def collect(self, seq, timeout=25.0):
        """The reply to `ask` (camera RGB, exposure...)."""
        if seq is None:
            return sim_camera_reading()
        with self.cv:
            ok = self.cv.wait_for(lambda: seq in self.results, timeout=timeout)
            self.waiting.discard(seq)
            if not ok:
                raise RuntimeError("The phone camera didn't answer. Keep TV Cal Lab open on the phone "
                                   "with the camera running.")
            return self.results.pop(seq)

    def has(self, seq):
        """Has the reply to `ask` arrived? (The demo answers at once.)"""
        if seq is None:
            return True
        with self.cv:
            return seq in self.results

    def request_reading(self, timeout=25.0):
        """Ask the phone's camera for one reading and wait for it."""
        return self.collect(self.ask(), timeout)

    def deliver(self, seq, data):
        with self.cv:
            if seq in self.waiting:              # a late or unasked-for reading is dropped
                self.results[seq] = data
                self.cv.notify_all()

    def view(self):
        """For the computer's lists of phones."""
        prof = load_profiles().get(self.model) if self.model else None
        return {"key": phone_key(self.token), "device": self.device, "model": self.model,
                "label": model_label(self.model, prof, self.name), "connected": self.connected(),
                "camera": self.connected() and self.cam_ready, "external": self.external,
                "profile": profile_summary(prof, self.model) if prof and prof.get("M") else None}

    def acknowledge(self, seq):
        with self.cv:
            if seq > self.ack:
                self.ack = seq
            self.cv.notify_all()


MODES = {"sdr": False, "hdr10": True, "hdr10p": True, "dv": True}


def patch_pattern(p, mode="sdr"):
    """What the phone should put on the TV. SDR: drawn directly. HDR10 / Dolby Vision: a short
    looping clip (so the TV gets a real HDR or Dolby Vision signal). Always the same centred window."""
    if mode == "hdr10p":
        mode = "hdr10"
    if mode in ("hdr10", "dv") and "nits" in p:
        return {"kind": "clip", "url": f"/clips/{mode}/{p['id']}.mp4", "name": p["name"], "hdr": True,
                "mode": mode}
    if "sig" in p:
        return {"kind": "patch", "rgb": list(p["sig"]), "window": 0.1, "name": p["name"]}
    return {"kind": "black"}


# Marketing names for the model identifiers the phones report (a profile's own name wins). Android
# phones report "manufacturer/device" (e.g. "samsung/e2q") and send their marketing name themselves.
MODEL_NAMES = {
    "iPhone15,2": "iPhone 14 Pro", "iPhone15,3": "iPhone 14 Pro Max", "iPhone15,4": "iPhone 15",
    "iPhone15,5": "iPhone 15 Plus", "iPhone16,1": "iPhone 15 Pro", "iPhone16,2": "iPhone 15 Pro Max",
    "iPhone17,1": "iPhone 16 Pro", "iPhone17,2": "iPhone 16 Pro Max", "iPhone17,3": "iPhone 16",
    "iPhone17,4": "iPhone 16 Plus", "iPhone17,5": "iPhone 16e", "iPhone18,1": "iPhone 17 Pro",
    "iPhone18,2": "iPhone 17 Pro Max", "iPhone18,3": "iPhone 17", "iPhone18,4": "iPhone Air",
    "iPhone19,2": "iPhone 18 Pro",
}


def model_label(model, prof=None, name=""):
    """A phone model's display name: the profile's own name, a known iPhone name, the name the phone
    sent, else the model key itself."""
    return (prof or {}).get("name") or MODEL_NAMES.get(model or "") or name or model or DEFAULT_PHONE


def is_iphone(model):
    return (model or "").startswith("iPhone")


def os_update(model):
    """For messages: "an iOS update" for an iPhone, "an Android update" for an Android model key."""
    return "an iOS update" if is_iphone(model) else "an Android update" if "/" in (model or "") else "a system update"


def model_file_name(model):
    """A model key that's safe in a file name ("samsung/e2q" -> "samsung_e2q"; "iPhone18,1" unchanged)."""
    return re.sub(r"[^A-Za-z0-9,._ +()-]+", "_", model or "").strip(" .") or "phone"


def phone_key(token):
    """A phone's id in job views and Training files: a short hash, never the pairing token itself (a token
    is a live credential for this app's API)."""
    return hashlib.sha256(token.encode()).hexdigest()[:8] if token else ""


PHONES = {}                       # pairing token -> Phone
PHONES_LOCK = threading.Lock()


def phone_for(token):
    with PHONES_LOCK:
        p = PHONES.get(token)
        if p is None:
            p = PHONES[token] = Phone(token)
            rec = load_settings().get("phones", {}).get(token) if token else None
            if rec:                              # the version it sent when pairing (until it sends one again)
                phone_os_from(p, rec)
        return p


def connected_phones():
    with PHONES_LOCK:
        ps = list(PHONES.values())
    return sorted([p for p in ps if p.connected()], key=lambda p: p.first_seen)


class PrimaryPhone:
    """The phone the one-phone features use (pattern player on the TV, live camera meter, automatic
    rounds): one with a TV attached, else the first to connect. Reads and writes go to that phone."""
    _idle = Phone()

    def _pick(self):
        ps = connected_phones()
        for p in ps:
            if p.external:
                return p
        if ps:
            return ps[0]
        with PHONES_LOCK:
            seen = list(PHONES.values())
        return max(seen, key=lambda p: p.last_seen) if seen else PrimaryPhone._idle

    def __getattr__(self, k):
        return getattr(self._pick(), k)

    def __setattr__(self, k, v):
        setattr(self._pick(), k, v)


PHONE = PrimaryPhone()


def training_phones():
    """The phones that measure when training: every connected phone with its camera running (in the
    demo without a phone, a pretend one)."""
    ps = [p for p in connected_phones() if p.cam_ready]
    if not ps and SIM:
        ps = [PHONE]
    return ps


def dev_os(d):
    """The OS version to record for a training phone (read when saving, so it's there even if the phone
    sent it only with its readings). Android only: iPhone sessions keep the shape they always had."""
    return "" if is_iphone(d["model"]) else (d["phone"].os or "")


def phone_os_from(ph, d):
    """Optional "android" / "ios" version a phone sends (query or body), kept for the records."""
    for k, label in (("android", "Android"), ("ios", "iOS")):
        v = d.get(k)
        if v not in (None, ""):
            ph.os = f"{label} {str(v)[:20]}"
            return


def lan_ip():
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("10.255.255.255", 1))      # no packet is sent; just picks the LAN interface
            return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"


class Bonjour:
    """Advertise the app on the local network so the phones can find it (_tvcal._tcp, TXT ip/port/host).
    Uses dns-sd (built into macOS; on Windows it comes with Apple's Bonjour), else python-zeroconf if
    it's installed (pip install zeroconf), else nothing: the phone is then pointed at IP:port by hand,
    which the page shows."""

    def __init__(self):
        self.proc = None
        self.zc = None
        self.info = None
        self.kind = None             # "dns-sd", "zeroconf" or None (not advertised)

    def start(self, port):
        name = f"TV Calibrator on {socket.gethostname().split('.')[0]}"
        host = socket.gethostname().split(".")[0] + ".local"
        if shutil.which("dns-sd"):
            try:
                self.proc = subprocess.Popen(["dns-sd", "-R", name, "_tvcal._tcp", "local", str(port),
                                              f"ip={lan_ip()}", f"port={port}", f"host={host}"],
                                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, **_NO_WINDOW)
                self.kind = "dns-sd"
                return
            except OSError:
                self.proc = None
        try:
            from zeroconf import ServiceInfo, Zeroconf
        except Exception:                         # not installed (optional)
            self.kind = None
            return
        try:
            ip = lan_ip()
            self.info = ServiceInfo("_tvcal._tcp.local.", f"{name}._tvcal._tcp.local.",
                                    addresses=[socket.inet_aton(ip)], port=port,
                                    properties={"ip": ip, "port": str(port), "host": host},
                                    server=f"{host}.")
            self.zc = Zeroconf()
            self.zc.register_service(self.info)
            self.kind = "zeroconf"
        except Exception as e:                    # never let advertising stop the app
            print("Couldn't advertise on the network (zeroconf):", e)
            self._close_zc()
            self.kind = None

    def _close_zc(self):
        zc, info = self.zc, self.info
        self.zc = self.info = None
        if zc:
            try:
                if info:
                    zc.unregister_service(info)
            except Exception:
                pass
            try:
                zc.close()
            except Exception:
                pass

    def stop(self):
        if self.proc:
            self.proc.terminate()
            self.proc = None
        self._close_zc()

    def watch(self, port):
        """If the computer moves to another network, advertise the new address."""
        last = lan_ip()
        while True:
            time.sleep(10)
            now = lan_ip()
            if now != last:
                print(f"Network address changed: {last} -> {now}")
                last = now
                self.stop()
                self.start(port)


BONJOUR = Bonjour()


# ---- the phone camera as a meter

SIM_TV = None                     # demo mode: the simulated TV being measured
SIM_CAM = [[0.020, 0.010, 0.002], [0.006, 0.026, 0.004], [0.001, 0.004, 0.030]]   # pretend sensor response


def sim_camera_reading():
    """Demo mode: what a camera with a made-up spectral response would report."""
    p = PHONE.shown
    if SIM_TV is None or p is None:
        return {"ok": False, "error": "nothing on screen"}
    XYZ = SIM_TV.render(p)
    inv = [list(r) for r in zip(*[T.solve3(SIM_CAM, e) for e in ((1, 0, 0), (0, 1, 0), (0, 0, 1))])]
    rgb = T.matvec(SIM_CAM, XYZ)
    # small colour-dependent error a real camera would have (sensor isn't a perfect colorimeter)
    rgb = [rgb[0] * (1 + 0.02 * (XYZ[2] / max(XYZ[1], 1e-6) - 1.08)), rgb[1], rgb[2]]
    rgb = [v * (1 + random.gauss(0, 0.004)) for v in rgb]
    time.sleep(0.4)
    return {"ok": True, "rgb": rgb, "duration": 1 / 250, "uneven": 0.01, "peak": 0.4,
            "iso": 32, "format": "4032x3024 grb4", "lens": "Back Camera"}


class PhoneMeter:
    """Same interface as SpotreadMeter, but the reading comes from the phone camera,
    converted to XYZ with the bridge matrix learned against the Display Plus HL."""

    def __init__(self, M, camera=None):
        self.M = M
        self.camera = camera or {}
        self.mode = "iphone"

    def now(self):
        return time.monotonic()

    def sleep(self, s):
        if s > 0:
            time.sleep(s)

    def read(self):
        t0 = self.now()
        try:
            r = PHONE.request_reading()
        except RuntimeError:
            return t0, self.now(), None
        if not r.get("ok"):
            return t0, self.now(), None
        diffs = settings_mismatch(self.camera, cam_settings(r))
        if diffs:
            raise RuntimeError("The phone camera is running with different settings than its profile was "
                               f"trained with ({'; '.join(diffs)}). This usually follows {os_update(PHONE.model)}. "
                               "Train the camera again (Camera profiles page: remove the old sessions first).")
        return t0, self.now(), tuple(T.matvec(self.M, r["rgb"]))


PROFILES = os.path.join(APP_DIR, "camera_profiles.json")
PROFILE_DIR = os.path.join(APP_DIR, "Camera profiles")


def load_profiles():
    os.makedirs(APP_DIR, exist_ok=True)
    if os.path.exists(PROFILES):
        with T.io_lock(), open(PROFILES) as f:
            return json.load(f)
    return {}


def save_profiles(p):
    if WINDOWS:                       # swapped in whole, so a reader never sees half a file
        T.write_json_atomic(PROFILES, p)
        return
    with open(PROFILES, "w") as f:
        json.dump(p, f, indent=1)


def _fit(weighted_pairs):
    """Least-squares 3x3 matrix camera RGB -> meter XYZ. Each pair carries a weight; errors are
    relative, so a dim grey counts as much as a bright white."""
    M = []
    for r in range(3):
        A = [[0.0] * 3 for _ in range(3)]
        b = [0.0] * 3
        for c, X, w0 in weighted_pairs:
            w = w0 / max(X[1], 1e-6) ** 2
            for i in range(3):
                b[i] += w * c[i] * X[r]
                for j in range(3):
                    A[i][j] += w * c[i] * c[j]
        M.append(T.solve3(A, b))
    return M


def _des(M, pairs):
    Yw = max(X[1] for c, X in pairs)
    Wn = T.xyY_to_XYZ(*T.D65, Yw)
    return [T.de2000(T.xyz_to_lab(T.matvec(M, c), Wn), T.xyz_to_lab(X, Wn)) for c, X in pairs]


PANELS = {   # the kinds of screen; one camera conversion each (the iPhone app has the same list)
    "woled": "OLED", "qdoled": "QD-OLED", "qdlcd": "Quantum dot LED / mini-LED", "lcd": "LED (LCD)",
    "rgbled": "Micro RGB / RGB mini-LED", "plasma": "Plasma",
}


def tv_panels():
    return {tv: cfg.get("panel") for tv, cfg in load_settings().get("tvs", {}).items()}


NEUTRAL_R = 0.04      # x,y distance from D65 within which the neutral conversion takes over


def _fit_neutral(weighted_pairs):
    """A second conversion from the greys and near-greys only. On a WOLED TV the white subpixel joins in
    for greys, so their light differs from the colours' and one matrix can't fit both (greys were 2.9 dE
    off; this brings them to 0.5). Nil without enough near-greys."""
    near = [(c, X, w) for c, X, w in weighted_pairs if X[1] > 0 and
            math.dist(T.xyz_to_xy(*X), (0.3127, 0.3290)) < NEUTRAL_R]
    return _fit(near) if len(near) >= 8 else None


def _blend(M, Mn, c):
    """The conversion for one reading: the neutral one near grey, the colour one away from it, and a mix
    between (decided by where the colour conversion puts the reading)."""
    if not Mn:
        return M
    X = T.matvec(M, c)
    if X[1] <= 0 or sum(X) <= 0:
        return M
    d = math.dist(T.xyz_to_xy(*X), (0.3127, 0.3290))
    w = min(1.0, max(0.0, (d - NEUTRAL_R / 2) / (NEUTRAL_R / 2)))
    return [[(1 - w) * Mn[i][j] + w * M[i][j] for j in range(3)] for i in range(3)]


def _des2(M, Mn, pairs):
    """Like _des, with the neutral conversion blended in."""
    Yw = max(X[1] for c, X in pairs)
    Wn = T.xyY_to_XYZ(*T.D65, Yw)
    return [T.de2000(T.xyz_to_lab(T.matvec(_blend(M, Mn, c), c), Wn), T.xyz_to_lab(X, Wn)) for c, X in pairs]


def refit_profile(prof):
    """This phone model's conversions from every training session: one per kind of screen (a TV's panel
    type, set on the Train page), plus a general one for screens of an unknown kind. Screens make white
    from different mixes of light, so one conversion can't serve them all (an iPhone trained on a quantum
    dot mini-LED read a micro RGB TV about 11 dE off). Each TV counts equally, however many sessions it
    has. With two TVs of the same kind, it also estimates accuracy on a third, by leaving each out."""
    sessions = [x for x in prof.get("sessions", []) if len(x.get("pairs", [])) >= 6]
    if not sessions:
        prof.pop("M", None)
        prof.pop("panels", None)
        prof["stats"] = {}
        return prof
    per_tv = {}
    for sess in sessions:
        per_tv.setdefault(sess["tv"], []).extend(sess["pairs"])
    known = tv_panels()
    panel_of = {}
    for tv in per_tv:
        panel_of[tv] = next((x.get("panel") for x in sessions if x["tv"] == tv and x.get("panel")), None) \
            or known.get(tv)

    def weighted(tvs):
        out = []
        for tv in tvs:
            pairs = per_tv[tv]
            out += [(c, X, 1.0 / len(pairs)) for c, X in pairs]
        return out

    # Drop readings that clearly don't belong (a frame of the player's menu after the video ended, the
    # phone bumped): fit each kind of screen, leave out pairs far worse than the rest of their TV, refit.
    dropped = 0
    for pn in set(panel_of.values()):
        tvs = [tv for tv in per_tv if panel_of[tv] == pn]
        M0 = _fit(weighted(tvs))
        for tv in tvs:
            d = _des(M0, per_tv[tv])
            lim = max(6.0, 4 * sorted(d)[len(d) // 2])
            keep = [pr for pr, e in zip(per_tv[tv], d) if e <= lim]
            if len(keep) >= 6:
                dropped += len(per_tv[tv]) - len(keep)
                per_tv[tv] = keep

    prof["M"] = _fit(weighted(per_tv))                   # general: any screen of an unknown kind
    panels = {}
    for pn in sorted({p for p in panel_of.values() if p}):
        tvs = sorted(tv for tv in per_tv if panel_of[tv] == pn)
        M = _fit(weighted(tvs))
        Mn = _fit_neutral(weighted(tvs))
        per = {tv: _des2(M, Mn, per_tv[tv]) for tv in tvs}
        entry = {"M": M, "Mn": Mn, "tvs": tvs, "de_avg": sum(sum(d) / len(d) for d in per.values()) / len(per),
                 "unseen": None}
        if len(tvs) >= 2:
            loo = []
            for tv in tvs:
                rest = weighted([t for t in tvs if t != tv])
                loo += _des2(_fit(rest), _fit_neutral(rest), per_tv[tv])
            entry["unseen"] = sum(loo) / len(loo)
        panels[pn] = entry
    prof["panels"] = panels

    stats = {"tvs": {}, "unseen": None, "dropped": dropped}
    for tv, pairs in per_tv.items():
        pe = panels.get(panel_of[tv])
        d = _des2(pe["M"], pe["Mn"], pairs) if pe else _des(prof["M"], pairs)
        stats["tvs"][tv] = {"de_avg": sum(d) / len(d), "de_max": max(d), "n": len(pairs), "panel": panel_of[tv]}
    unseen = [p["unseen"] for p in panels.values() if p["unseen"] is not None]
    stats["unseen"] = sum(unseen) / len(unseen) if unseen else None
    # the general conversion across kinds of screen, for reference
    g = [e for tv in per_tv for e in _des(prof["M"], per_tv[tv])]
    stats["general_de"] = sum(g) / len(g)
    allde = [v["de_avg"] for v in stats["tvs"].values()]
    stats["de_avg"] = sum(allde) / len(allde)
    prof["stats"] = stats
    return prof


def panel_matrix(prof, tv):
    """The conversions (colour, neutral) for this TV's kind of screen, or the general one; None untrained."""
    pe = ((prof or {}).get("panels") or {}).get(tv_panels().get(tv))
    if pe and pe.get("M"):
        return (pe["M"], pe.get("Mn"))
    return ((prof or {})["M"], None) if (prof or {}).get("M") else None


CAM_KEYS = ("iso", "format", "lens", "aperture")   # aperture: iPhone 18 Pro variable aperture; omitted
#                                                     on fixed-aperture lenses (most phones)


def cam_settings(reading):
    return {k: reading.get(k) for k in CAM_KEYS if reading.get(k) is not None}


def settings_mismatch(expected, got):
    """Which locked camera settings differ (ISO, RAW format, lens)? Empty if they match."""
    if not expected or not got:
        return []
    diffs = []
    for k in CAM_KEYS:
        a, b = expected.get(k), got.get(k)
        if k == "aperture" and (a is None) != (b is None):
            # one locked, the other not: an unlocked aperture can let in a different amount of light
            diffs.append(f"aperture: profile {'f/%.1f' % a if a else 'not locked'}, now {'f/%.1f' % b if b else 'not locked'}")
            continue
        if a is None or b is None:
            continue
        tol = {"iso": 0.5, "aperture": 0.05}.get(k)
        if (tol is not None and abs(float(a) - float(b)) > tol) or (tol is None and a != b):
            diffs.append(f"{k}: profile {a}, now {b}")
    return diffs


def profile_and_model(model):
    profs = load_profiles()
    p = profs.get(model)
    if p and p.get("M"):
        return model, p
    if SIM:
        for m, q in profs.items():
            if q.get("M"):
                return m, q
    return model, None


def profile_for(model):
    return profile_and_model(model)[1]


def profile_summary(p, model, tv=None):
    if not p:
        return None
    st = p.get("stats", {})
    out = {"model": model, "name": p.get("name") or model, "tvs": sorted(st.get("tvs", {})),
           "camera": p.get("camera"),
           "de_avg": st.get("de_avg"), "unseen": st.get("unseen"),
           "panels": {pn: {"label": PANELS.get(pn, pn), "de_avg": v.get("de_avg"), "unseen": v.get("unseen"),
                           "tvs": v.get("tvs", [])} for pn, v in (p.get("panels") or {}).items()},
           "sessions": [{"tv": x["tv"], "date": x.get("date"), "n": len(x.get("pairs", []))}
                        for x in p.get("sessions", [])]}
    if tv is not None:
        out["this_tv"] = st.get("tvs", {}).get(tv)
    return out


class SimDirectMeter:
    """Demo mode, iPhone source: reads whatever patch the (real or pretend) phone is showing."""

    def __init__(self, tv):
        self.tv = tv
        self.t = 0.0

    def now(self):
        return time.time()

    def sleep(self, s):
        if s > 0:
            time.sleep(s)

    def read(self):
        t0 = time.time()
        p = PHONE.shown
        XYZ = self.tv.render(p) if p else (0.03, 0.03, 0.035)
        XYZ = tuple(v * (1 + random.gauss(0, 0.002)) for v in XYZ)
        time.sleep(0.25 if XYZ[1] > 5 else 0.8)
        return t0, time.time(), XYZ


# ======================================================================== the meter

def pattern_source():
    """'video' (pattern videos on a USB stick, the default) or 'iphone'."""
    return load_settings().get("source", "video")


class SlowSim(T.SimMeter):
    """Simulated meter that runs at ~10x real time so the app's progress is watchable."""
    FACTOR = 0.1

    def sleep(self, s):
        if s > 0:
            self.t += s
            time.sleep(s * self.FACTOR)

    def read(self):
        t0 = self.t
        r = super().read()
        time.sleep((self.t - t0) * self.FACTOR)
        return r


class MeterHub:
    def __init__(self):
        self.meter = None

    def get(self):
        if SIM:
            return None
        if self.meter is None or (self.meter.mode == "persistent" and
                                  (self.meter.proc is None or self.meter.proc.poll() is not None)):
            if not shutil.which("spotread"):
                # a RuntimeError, so the job that asked ends with this message (not a silent hang)
                raise T.MeterError("ArgyllCMS isn't installed (spotread not found). " + T.install_hint())
            args = load_settings().get("spotread_args", "-y n").split()
            self.meter = T.SpotreadMeter(args)
        return self.meter

    def close(self):
        if self.meter:
            self.meter.close()
        self.meter = None


HUB = MeterHub()


# ======================================================================== round job

class Job:
    def __init__(self):
        self.reset()

    def reset(self):
        self.phase = "idle"
        self.tv = None
        self.hdr = False
        self.readings = []
        self.patches = []
        self.index = -1
        self.error = None
        self.cancel = threading.Event()
        self.started = None
        self.source = "video"
        self.kind = "round"
        self.meter = "hl"
        self.pairs = []
        self.phones = []
        self.skips = []
        self.note = ""
        self.result = None
        self.finish = threading.Event()

    def view(self):
        return {"phase": self.phase, "tv": self.tv, "hdr": self.hdr, "readings": self.readings[-40:],
                "patches": self.patches, "index": self.index, "error": self.error,
                "total": len(self.patches) or len(T.build_sequence(self.hdr)["patches"]), "started": self.started,
                "source": self.source, "kind": self.kind, "meter": self.meter,
                "pairs": self.pairs, "phones": self.phones, "result": self.result,
                "signal": getattr(self, "signal", "sdr"), "skips": self.skips[-30:], "note": getattr(self, "note", ""),
                "check": getattr(self, "check", False)}


JOB = Job()


BRIDGE_IDS = ["W0", "G20", "G35", "G50", "G65", "G80", "G100", "RED", "GREEN", "BLUE", "CYAN", "MAGENTA", "YELLOW"]


TRAINING_DIR = os.path.join(APP_DIR, "Training")


def run_bridge(name, check=False, signal="sdr"):
    """Teach phone cameras on one TV: the meter and every connected phone measure the same patches.
    Each phone's readings go into the profile for its model. check=True measures the same way but only
    scores each phone's existing profile against the meter: nothing is added (a true test, e.g. after an
    app or iOS/Android update, or on a new TV)."""
    global SIM_TV
    JOB.reset()
    JOB.phase, JOB.tv, JOB.hdr, JOB.started, JOB.kind = "measuring", name, False, time.time(), "bridge"
    JOB.check = check
    JOB.signal = signal              # which training video: "sdr" or "hdr10" (noted with the readings)
    panel = tv_panels().get(name)    # the TV's kind of screen: which conversion it trains (or checks)
    profs = load_profiles()
    devs = []
    for i, ph in enumerate(training_phones()):
        model = ph.model or ("iPhone (demo)" if SIM else DEFAULT_PHONE)
        prof = profs.get(model)
        devs.append({"key": phone_key(ph.token) or f"demo{i}", "model": model, "label": model_label(model, prof, ph.name),
                     "name": ph.name, "phone": ph,
                     "pairs": [], "rows": [], "cam": {}, "M": panel_matrix(prof, name) if check else None})
    JOB.phones = [{"key": d["key"], "model": d["model"], "label": d["label"], "skip": bool(check and not d["M"])}
                  for d in devs]
    if not devs:
        JOB.phase, JOB.error = "error", "No phone with its camera running."
        return
    if check and not any(d["M"] for d in devs):
        JOB.phase, JOB.error = "error", "None of the connected phones has a trained profile to check."
        return
    seq = {p["id"]: p for p in T.build_sequence(False)["patches"]}
    direct = SIM or (PHONE.connected() and PHONE.external)
    JOB.source = "iphone" if direct else "video"
    meter_xyz = []                     # per accepted patch: the meter's XYZ
    try:
        with LOCK:
            if SIM:
                path, st = state_for(name, False)
                SIM_TV = T.SimTV(False, (st or {}).get("sim"), seed=sum(map(ord, name)) % 1000)
                hl = SimDirectMeter(SIM_TV)
            else:
                hl = HUB.get()

            def hl_read(n=2):
                vals = []
                for _ in range(n + 2):
                    t0, t1, XYZ = hl.read()
                    if XYZ:
                        vals.append(XYZ)
                    if len(vals) >= n:
                        break
                return [sum(v[k] for v in vals) / len(vals) for k in range(3)] if vals else None

            def same(Xa, Xb):
                """The meter still sees the same patch (looser for dim patches, whose readings are noisier)."""
                if not Xa or not Xb:
                    return False
                Y = max(Xa[1], 1e-6)
                return abs(Xb[1] / Y - 1) <= 0.03 + 0.15 / Y and \
                    math.dist(T.xyz_to_xy(*Xa), T.xyz_to_xy(*Xb)) <= 0.004 + 0.03 / Y

            def drop(d, why):
                """Leave a phone out of the rest of this run (it stopped answering) instead of failing it."""
                if d.get("gone"):
                    return
                d["gone"] = True
                for q in JOB.phones:
                    if q["key"] == d["key"]:
                        q["gone"] = True
                JOB.skips.append({"t": round(time.time() - JOB.started, 1), "Y": None, "x": None, "y": None,
                                  "why": f"{d['label']} {why}, so it was left out of the rest of this run"})

            def drop_gone():
                for d in devs:
                    ph = d["phone"]
                    if ph.token and not ph.connected():
                        drop(d, "stopped polling (its lab app was closed or the screen went off)")

            def show(p):
                """Put the patch on the TV through the pattern phone. If that phone had already stopped (it
                closed its lab app just before Start), drop it and use the next one."""
                while True:
                    ph = PHONE._pick()
                    try:
                        ph.show_patch(p, mode="sdr")
                        return
                    except RuntimeError:
                        if ph.connected() or not connected_phones():
                            raise                         # still polling, or no phone left: a real failure
                        for d in devs:
                            if d["phone"] is ph:
                                drop(d, "stopped responding")

            def take(label, X1):
                """Every phone reads the patch at once. As each phone's reading arrives, the meter reads
                again: that phone's reading counts if the picture hasn't changed. A slow phone can miss a
                patch without the others losing it."""
                drop_gone()
                asked = [(d, d["phone"].ask()) for d in devs if not (check and not d["M"]) and not d.get("gone")]
                got = {}
                deadline = time.time() + 25
                while len(got) < len(asked) and time.time() < deadline:
                    ready = [(d, n) for d, n in asked if d["key"] not in got and d["phone"].has(n)]
                    if not ready:
                        if all(d["key"] in got or (d["phone"].token and not d["phone"].connected())
                               for d, n in asked):
                            break                       # the rest stopped polling: don't wait 25 s for them
                        time.sleep(0.05)
                        continue
                    X2 = hl_read(1)
                    for d, n in ready:
                        try:
                            r = d["phone"].collect(n, timeout=1)
                        except RuntimeError as e:
                            r = {"ok": False, "error": str(e)}
                        got[d["key"]] = (d, r, X2)
                for d, n in asked:
                    if d["key"] not in got:
                        try:
                            d["phone"].collect(n, timeout=0.1)      # stop waiting for it
                        except RuntimeError:
                            pass
                        got[d["key"]] = (d, {"ok": False, "error": "The camera didn't answer in time."}, None)
                okX = [X2 for d, r, X2 in got.values() if r.get("ok") and same(X1, X2)]
                if not okX:
                    why = ("the picture changed while the phones measured" if any(r.get("ok") for d, r, X2 in got.values())
                           else "no phone could read it: " + "; ".join(sorted({str(r.get("error", ""))[:80] for d, r, X2 in got.values()})))
                    x_, y_ = T.xyz_to_xy(*X1) if X1 else (0, 0)
                    JOB.skips.append({"t": round(time.time() - JOB.started, 1), "Y": X1[1] if X1 else None,
                                      "x": x_, "y": y_, "why": why})
                    return False
                Xm = [(a + b) / 2 for a, b in zip(X1, okX[0])]
                x, y = T.xyz_to_xy(*Xm)
                row = {"name": label, "Y": Xm[1], "x": x, "y": y, "phones": {}}
                for d, r, X2 in got.values():
                    if r.get("ok") and not d["cam"]:
                        d["cam"].update(cam_settings(r))
                    if not r.get("ok"):
                        row["phones"][d["key"]] = {"ok": False, "error": str(r.get("error", ""))[:120]}
                        continue
                    if not same(X1, X2):
                        row["phones"][d["key"]] = {"ok": False, "error": "The patch changed before this phone's reading arrived."}
                        continue
                    X = [(a + b) / 2 for a, b in zip(X1, X2)]
                    d["pairs"].append((r["rgb"], X))
                    d["rows"].append(len(JOB.pairs))
                    e = {"ok": True, "uneven": r.get("uneven", 0), "rgb": r["rgb"]}
                    if d["M"]:                                   # live score while checking
                        e["de"] = _des2(d["M"][0], d["M"][1], d["pairs"])[-1]
                    row["phones"][d["key"]] = e
                first = row["phones"].get(devs[0]["key"], {})     # the one-phone panel on a TV's page
                row["de"], row["uneven"] = first.get("de"), first.get("uneven", 0)
                meter_xyz.append(Xm)
                JOB.pairs.append(row)
                return True

            if direct:
                for i, pid in enumerate(BRIDGE_IDS):
                    if JOB.cancel.is_set():
                        raise T.Cancelled()
                    JOB.index = i
                    show(seq[pid])
                    time.sleep(1.2)
                    take(seq[pid]["name"], hl_read(2))
                PHONE.set({"kind": "black"})
            else:
                # Follow the training video: whenever the meter sees a new, steady patch, every
                # camera measures it too.
                JOB.phase = "waiting"
                last = None
                quiet_since = time.time()
                prev = None
                os.makedirs(TRAINING_DIR, exist_ok=True)
                dbg = open(os.path.join(TRAINING_DIR, "last run.log"), "w", encoding="utf-8")

                def note(line):
                    JOB.note = line
                    try:
                        dbg.write(f"{time.time() - JOB.started:7.1f}  {line}\n")
                        dbg.flush()
                    except (OSError, ValueError):
                        pass
                note(f"start: {len(devs)} phone(s): " + ", ".join(d["label"] for d in devs))
                while not JOB.cancel.is_set() and not JOB.finish.is_set():
                    t0, t1, X = hl.read()
                    if not X or X[1] <= 0:
                        note(f"meter: no reading ({X})")
                        continue
                    JOB.readings.append({"t": time.time(), "Y": X[1]})
                    xy = T.xyz_to_xy(*X)
                    # Steady: two readings in a row agree (more loosely for dim patches: an HDR red at a
                    # few nits wobbles more than the old fixed limits allowed, so it was never measured).
                    steady = prev is not None and abs(X[1] / prev[1] - 1) < 0.015 + 0.08 / X[1] and \
                        math.dist(T.xyz_to_xy(*prev), xy) < 0.002 + 0.02 / X[1]
                    new = last is None or abs(X[1] / last[1] - 1) > 0.08 or math.dist(T.xyz_to_xy(*last), xy) > 0.006
                    note(f"meter {X[1]:8.3f} cd/m2  x {xy[0]:.4f} y {xy[1]:.4f}  steady={steady} new={new}")
                    if steady and new and X[1] > 0.5:
                        JOB.phase = "measuring"
                        X = [(a + b) / 2 for a, b in zip(prev, X)]
                        ok = take(f"Patch {len(JOB.pairs) + 1}", X)
                        note(f"take: {'kept' if ok else 'not kept: ' + (JOB.skips[-1]['why'] if JOB.skips else '?')}")
                        if ok:
                            last = X
                            quiet_since = time.time()
                            prev = None
                        else:
                            prev = X
                    else:
                        prev = X
                    if len(JOB.pairs) >= 8 and time.time() - quiet_since > 45:
                        break                                 # video has ended
                note("end")
                dbg.close()
                if JOB.cancel.is_set():
                    raise T.Cancelled()
        if len(JOB.pairs) < 6:
            raise RuntimeError(f"Only {len(JOB.pairs)} patches matched up - need at least 6, including colours. "
                               "Check that the meter and the cameras all see the patch.")

        # greys and whites sit near D65; colours are far from it
        is_col = [math.dist((r["x"], r["y"]), (0.3127, 0.3290)) > 0.03 for r in JOB.pairs]
        profs = load_profiles()
        results = []
        for d in devs:
            res = {"key": d["key"], "model": d["model"], "label": d["label"], "n": len(d["pairs"])}
            results.append(res)
            try:
                if check and not d["M"]:
                    raise RuntimeError("No trained profile for this model yet: train it first.")
                if d.get("gone") and len(d["pairs"]) < 6:
                    raise RuntimeError("It stopped answering during this run, so it was left out.")
                if len(d["pairs"]) < 6:
                    raise RuntimeError(f"Only {len(d['pairs'])} readings. Check that its yellow box sits inside "
                                       "the patch and its camera is running.")
                if check:
                    prof = profs[d["model"]]
                    M, Mn = d["M"]
                    res["settings"] = settings_mismatch(prof.get("camera"), d["cam"])
                    note = ", check only: profile unchanged"
                else:
                    prof = profs.setdefault(d["model"], {"name": MODEL_NAMES.get(d["model"]) or d["name"] or "",
                                                         "sessions": []})
                    if not prof.get("name") and not is_iphone(d["model"]) and d["name"]:
                        prof["name"] = d["name"]           # an Android profile made before names were sent
                    diffs = settings_mismatch(prof.get("camera"), d["cam"])
                    if diffs and prof.get("sessions"):
                        raise RuntimeError("This phone ran with different camera settings than its existing "
                                           f"profile ({'; '.join(diffs)}), so it wasn't mixed in. If "
                                           f"{'iOS' if is_iphone(d['model']) else 'a system update'} changed "
                                           "the camera, remove the old sessions on the Camera profiles page and "
                                           "train again.")
                    if d["cam"] and not prof.get("camera"):
                        prof["camera"] = d["cam"]
                    sess = {"tv": name, "date": datetime.now().isoformat(timespec="minutes"),
                            "signal": signal, "panel": panel, "camera": d["cam"]}
                    if dev_os(d):
                        sess["os"] = dev_os(d)                 # e.g. "Android 16": which build trained it
                    prof["sessions"].append({**sess,
                                             "pairs": [[list(c), list(X)] for c, X in d["pairs"]],
                                             "names": [JOB.pairs[i]["name"] for i in d["rows"]]})
                    refit_profile(prof)
                    pe = (prof.get("panels") or {}).get(panel) or {}
                    M, Mn = pe.get("M") or prof["M"], pe.get("Mn")
                    note = f" ({PANELS.get(panel, 'general')})"
                des = _des2(M, Mn, d["pairs"])
                for i, (c, X), de in zip(d["rows"], d["pairs"], des):
                    e = JOB.pairs[i]["phones"][d["key"]]
                    e["de"] = de
                    E = T.matvec(_blend(M, Mn, c), c)
                    e["x"], e["y"] = T.xyz_to_xy(*E)
                    e["Y"] = E[1]
                cols = [de for i, de in zip(d["rows"], des) if is_col[i]]
                greys = [de for i, de in zip(d["rows"], des) if not is_col[i]]
                res.update({"de_avg": sum(des) / len(des), "de_max": max(des),
                            "grey_avg": sum(greys) / len(greys) if greys else None,
                            "colour_avg": sum(cols) / len(cols) if cols else None,
                            "profile": profile_summary(prof, d["model"], name)})
                log_bridge(name, [JOB.pairs[i] for i in d["rows"]],
                           {"de_avg": res["de_avg"], "de_max": res["de_max"], "n": len(des), "des": des},
                           (prof.get("name") or d["model"]) + note)
            except RuntimeError as e:
                res["error"] = str(e)
        if not check:
            save_profiles(profs)
        for row in JOB.pairs:                         # the one-phone panel shows the first phone's numbers
            row["de"] = row["phones"].get(devs[0]["key"], {}).get("de")
        first = next((r for r in results if "de_avg" in r), {})
        JOB.result = {"check": check, "phones": results,
                      **{k: first.get(k) for k in ("de_avg", "de_max", "n", "grey_avg", "colour_avg", "profile")},
                      "settings": first.get("settings", [])}
        JOB.result["file"] = save_training(name, check, devs, meter_xyz)
        if not first:
            raise RuntimeError("; ".join(f"{r['label']}: {r.get('error')}" for r in results))
        JOB.phase = "done"
    except T.Cancelled:
        JOB.phase = "cancelled"
    except Exception as e:
        JOB.phase, JOB.error = "error", f"{e}"
    finally:
        if direct and not SIM:
            PHONE.set({"kind": "black"})


def save_training(name, check, devs, meter_xyz):
    """Every reading of a training or check run, raw, in ~/tvcal/Training: the meter's XYZ and each phone's
    camera RGB per patch. For comparing phones and refitting later."""
    try:
        os.makedirs(TRAINING_DIR, exist_ok=True)
        safe = re.sub(r"[^A-Za-z0-9 _-]+", "_", name).strip() or "TV"
        sig = " HDR10" if getattr(JOB, "signal", "sdr") == "hdr10" else ""
        base = os.path.join(TRAINING_DIR, f"{datetime.now():%Y-%m-%d %H%M} {safe}{sig}{' check' if check else ''}")
        fn, n = base + ".json", 2
        while os.path.exists(fn):                 # another run in the same minute: keep both
            fn, n = f"{base} ({n}).json", n + 1
        data = {"tv": name, "date": datetime.now().isoformat(timespec="seconds"), "check": check,
                "signal": getattr(JOB, "signal", "sdr"), "panel": tv_panels().get(name),
                "phones": {d["key"]: {"model": d["model"], "label": d["label"], "camera": d["cam"],
                                      **({"os": dev_os(d)} if dev_os(d) else {})} for d in devs},
                "skipped": JOB.skips,
                "patches": [{"name": r["name"], "XYZ": X,
                             "phones": {k: v.get("rgb") for k, v in r["phones"].items() if v.get("ok")}}
                            for r, X in zip(JOB.pairs, meter_xyz)]}
        with open(fn, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=1)
        return fn
    except OSError:
        return None


def run_job(name, hdr, source="video", meter_kind="hl"):
    global SIM_TV
    JOB.reset()
    JOB.phase, JOB.tv, JOB.hdr, JOB.started = "starting", name, hdr, time.time()
    JOB.source, JOB.meter = source, meter_kind
    seq = T.build_sequence(hdr)
    JOB.patches = [{"id": p["id"], "name": p["name"], "kind": p["kind"], "Y": None, "done": False} for p in seq["patches"]]

    def on_event(kind, **kw):
        if kind == "waiting":
            JOB.phase = "waiting"
        elif kind == "reading":
            JOB.readings.append({"t": time.time(), "Y": kw.get("Y")})
        elif kind == "synced":
            JOB.phase = "measuring"
        elif kind == "patch_start":
            JOB.index = kw["index"]
        elif kind == "patch":
            p = JOB.patches[kw["index"]]
            p["done"] = True
            p["Y"] = kw["XYZ"][1] if kw["XYZ"] else None

    settings = load_settings()
    cfg = settings["tvs"].get(name, {"points": 20, "cms": "samsung"})
    path, st = state_for(name, hdr)
    try:
        with LOCK:
            if SIM:
                tv = T.SimTV(hdr, st.get("sim"), seed=sum(map(ord, name)) % 1000)
                SIM_TV = tv
                meter = SimDirectMeter(tv) if source == "iphone" else SlowSim(tv, hdr, play_at=2.0)
            else:
                meter = HUB.get()
                if meter is None:
                    raise RuntimeError("Meter not available.")
            if meter_kind == "iphone":
                prof = profile_for(PHONE.model)
                if not prof:
                    raise RuntimeError("Train the phone camera first (on any TV).")
                meter = PhoneMeter(prof["M"], prof.get("camera"))
            if source == "iphone":
                try:
                    meas = T.sweep_direct(meter, hdr, lambda p: PHONE.show_patch(p, mode="hdr10" if hdr else "sdr"),
                                          on_event=on_event, cancel=JOB.cancel)
                finally:
                    PHONE.set({"kind": "black"})
            else:
                meas = T.sweep(meter, hdr, verbose=False, on_event=on_event, cancel=JOB.cancel)
        st["rounds"].append({"time": datetime.now().isoformat(timespec="seconds"), "meas": meas,
                             "applied": None, "kind": None,
                             "setup": {"source": source, "meter": meter_kind,
                                       "phone": PHONE.model if meter_kind == "iphone" else None}})
        plan = T.plan_round(st, hdr, cfg["points"], cfg["cms"])
        if plan.get("discard"):
            dropped = st["rounds"].pop()
            log_round(name, hdr, dropped, plan, len(st["rounds"]) + 1, discarded=True)
        else:
            log_round(name, hdr, st["rounds"][-1], plan, len(st["rounds"]))
        st["pending"] = {k: plan[k] for k in ("changes", "kind", "title", "done", "message", "banner", "notes")}
        if plan.get("discard"):
            st["pending"]["done"] = False
        if SIM:
            st["sim"] = tv.s
        T.save_state(path, st)
        JOB.phase = "done"
    except T.Cancelled:
        JOB.phase = "cancelled"
    except (T.NoSync, T.MeterError) as e:
        JOB.phase, JOB.error = "error", str(e)
    except Exception as e:  # show anything else in the app instead of dying silently
        JOB.phase, JOB.error = "error", f"{type(e).__name__}: {e}"



# ======================================================================== guided, live calibration
#
# The phone shows one pattern at a time (same centred window, so the meter never moves); the app
# reads the meter continuously and says, in plain words, which control to move and which way.
# A short "before" and "after" check bracket the session.

GUIDE_PLAN = {
    False: {"gain": "W0", "offset": "G30", "check": ["W0", "G50", "G30"],
            "quick": T.QUICK_IDS[False][:6],
            "fine": ["G20", "G40", "G60", "G80"]},
    True: {"gain": "G65", "offset": "G35", "check": ["G65", "G50", "G35"],
           "quick": T.QUICK_IDS[True][:6],
           "fine": ["G40", "G50", "G60", "G70"]},
}
COLOUR_IDS = ["RED", "GREEN", "BLUE", "CYAN", "MAGENTA", "YELLOW"]


def guide_key(name, mode):
    return tv_key(name) + {"dv": "-dv", "hdr10p": "-hdr10p"}.get(mode, "")


def guide_state(name, mode):
    path = T.state_path(guide_key(name, mode), MODES[mode])
    st = None
    if os.path.exists(path):
        with T.io_lock(), open(path) as f:
            st = json.load(f)
    if not st or st.get("version", 1) < T.STATE_VERSION:
        st = {"version": T.STATE_VERSION, "rounds": [], "sens": {}}
    st.setdefault("guided", [])
    return path, st


def patch_by_id(hdr, pid):
    for p in T.build_sequence(hdr)["patches"]:
        if p["id"] == pid:
            return p
    raise KeyError(pid)


def current_meter():
    """The Display Plus HL, or (pro) the iPhone camera with its profile."""
    if SIM:
        return SimDirectMeter(SIM_TV or T.SimTV(False))
    if load_settings().get("meter") == "iphone":
        prof = profile_for(PHONE.model)
        if prof and PHONE.connected() and PHONE.cam_ready:
            return PhoneMeter(prof["M"], prof.get("camera"))
    return HUB.get()


def live_reading(p, XYZ, hdr, white_Y, basis, cms):
    """Plain-language state of one pattern: how far off it is, and what to do about it."""
    x, y = T.xyz_to_xy(*XYZ)
    out = {"id": p["id"], "name": p["name"], "kind": p["kind"], "Y": XYZ[1], "uv": T.upvp(x, y),
           "d65_uv": T.upvp(*T.D65), "x": x, "y": y, "tips": []}
    if p["kind"] in ("white", "grey"):
        rgb = T.xyz_to_rgb709(XYZ)
        g = rgb[1] if rgb[1] > 0 else 1e-9
        q = {"R": rgb[0] / g - 1, "B": rgb[2] / g - 1}
        out.update(q=q, de=T.white_de(XYZ), ok=abs(q["R"]) <= 0.012 and abs(q["B"]) <= 0.012)
    else:
        tg = T.colour_targets(hdr)[p["id"]]
        lvl = 1.0 if hdr else 0.75 ** 2.4
        wY = white_Y or (100.0 if hdr else None)
        rm, am = T.polar((x, y))
        rt, at = T.polar(tg["xy"])
        hue = T.wrap(am - at)
        sat = (rm / rt - 1) * 100
        out.update(tuv=T.upvp(*tg["xy"]), hue=hue, sat=sat, lum=None, de=None)
        if wY:
            expY = wY * tg["Yrel"] * lvl
            Wn = T.xyY_to_XYZ(*T.D65, wY)
            out["lum"] = (XYZ[1] / expY - 1) * 100
            out["de"] = T.de2000(T.xyz_to_lab(XYZ, Wn), T.xyz_to_lab(T.xyY_to_XYZ(*tg["xy"], expY), Wn))
        i = T.HUE_ORDER.index(p["id"])
        toward = None
        for cand in (T.HUE_ORDER[(i + 1) % 6], T.HUE_ORDER[i - 1]):
            if (T.wrap(T.polar(T.colour_targets(hdr)[cand]["xy"])[1] - at) > 0) == (hue > 0):
                toward = cand
        out["toward"] = toward
        out["ok"] = (out["de"] is not None and out["de"] <= 2.0) or \
                    (out["de"] is None and abs(hue) < 2 and abs(sat) < 3)
        if wY and not basis:
            # no measured primaries (the Before check missed one): use the standard ones - the
            # direction of each slider hint is still right, only the size is approximate
            tgs = T.colour_targets(hdr)
            basis = [T.xyY_to_XYZ(*tgs[c]["xy"], wY * tgs[c]["Yrel"] * lvl) for c in ("RED", "GREEN", "BLUE")]
            out["rgb_approx"] = True
        if basis and wY:
            # R/G/B slider menus (Samsung): which slider moves this colour toward its target
            try:
                M = [[basis[c][r] for c in range(3)] for r in range(3)]
                cur = T.solve3(M, XYZ)
                tgt = T.solve3(M, T.xyY_to_XYZ(*tg["xy"], wY * tg["Yrel"] * lvl))
                # each change as a share of the colour's main slider, so a tiny trace of green on red
                # doesn't outrank red being 25% too bright
                diffs = [(tgt[j] - cur[j]) / max(max(tgt), 1e-6) for j in range(3)]
                out["rgb_hint"] = diffs
                # A slider can't go below zero. If the target needs a negative amount of some primary,
                # it lies outside what this TV's current red, green and blue can mix: lowering that
                # slider helps only until it hits zero. Say so, and say how close it can get.
                peak = max(tgt)
                short = [j for j in range(3) if tgt[j] < -0.02 * peak]
                if short:
                    reach = [max(0.0, t) for t in tgt]
                    XYZr = [sum(M[r][c] * reach[c] for c in range(3)) for r in range(3)]
                    Wn = T.xyY_to_XYZ(*T.D65, wY)
                    best = T.de2000(T.xyz_to_lab(XYZr, Wn), T.xyz_to_lab(T.xyY_to_XYZ(*tg["xy"], wY * tg["Yrel"] * lvl), Wn))
                    out["out_of_reach"] = {"channels": ["RGB"[j] for j in short], "best_de": round(best, 1)}
            except Exception:
                pass
    return out


def settled_average(rs):
    """Average the readings since the last real change on the TV.

    Some TVs (Samsung Micro RGB, many mini-LEDs) wobble from reading to reading. Averaging hides
    the wobble, but a long average would also hide the effect of a click on the remote. So: judge
    the normal wobble from reading-to-reading steps, then look for the point where the average
    clearly shifted (a setting change) and average only the readings after it.
    Returns (XYZ, how many readings were averaged, wobble as a fraction)."""
    cs = [(r[0] / max(r[1], 1e-6), r[2] / max(r[1], 1e-6)) for r in rs]
    n = len(cs)
    sig = []
    for k in range(2):
        d = sorted(abs(a[k] - b[k]) for a, b in zip(cs, cs[1:]))
        scale = max(abs(c[k]) for c in cs) or 1.0
        sig.append(max(d[len(d) // 2] / 0.954 if d else 0.0, 0.0015 * scale, 1e-6))
    start = 0
    best = 0.0
    for m in range(2, n - 1):          # last m readings vs the ones before them
        old, new = cs[:n - m], cs[n - m:]
        t = max(abs(sum(c[k] for c in new) / m - sum(c[k] for c in old) / len(old)) /
                (sig[k] * (1 / m + 1 / len(old)) ** 0.5) for k in range(2))
        if t > 4.0 and t > best:
            best, start = t, n - m
    if n - start == 1 or (n >= 2 and start == 0 and max(abs(cs[-1][k] - cs[-2][k]) / (sig[k] * 1.414) for k in range(2)) > 6):
        start = n - 1                  # a big jump on the very newest reading: show it straight away
    tail = list(range(max(start, n - 10), n))
    m = len(tail)
    cx = sum(cs[j][0] for j in tail) / m
    cz = sum(cs[j][1] for j in tail) / m
    Y = sum(rs[j][1] for j in tail) / m
    wobble = max(sig[k] / max(abs(cs[-1][k]), 0.05) for k in range(2))
    return [cx * Y, Y, cz * Y], m, round(wobble, 4)


class Guide:
    def __init__(self):
        self.on = False
        self.tv = None
        self.mode = "sdr"
        self.patch = None
        self.readings = collections.deque(maxlen=14)
        self.since = 0
        self.thread = None
        self.white_Y = None
        self.basis = None
        self.error = None

    def hdr(self):
        return MODES[self.mode]

    def start(self, tv, mode):
        global SIM_TV
        if (tv, mode) != (self.tv, self.mode):
            self.white_Y, self.basis = None, None
            path, st = guide_state(tv, mode)
            last = st["guided"][-1] if st["guided"] else None
            src = (last or {}).get("after") or (last or {}).get("before")
            if src:
                self.white_Y = (src.get("W0") or [0, None])[1]
                if all(src.get(c) for c in ("RED", "GREEN", "BLUE")):
                    self.basis = [src["RED"], src["GREEN"], src["BLUE"]]
        self.tv, self.mode = tv, mode
        if SIM and (SIM_TV is None or SIM_TV.hdr != MODES[mode]):
            SIM_TV = T.SimTV(MODES[mode], None, seed=sum(map(ord, tv)) % 1000)
        if not self.on:
            self.on = True
            self.thread = threading.Thread(target=self.loop, daemon=True)
            self.thread.start()

    def show(self, pid):
        p = patch_by_id(self.hdr(), pid)
        self.patch = None
        self.readings.clear()
        if pattern_source() == "iphone":
            PHONE.show_patch(p, mode=self.mode)
        elif SIM:
            PHONE.shown = p          # demo: pretend the right file is playing
        self.since = time.time()
        self.patch = p

    def stop(self):
        self.on = False
        self.patch = None
        try:
            PHONE.set({"kind": "black"})
        except Exception:
            pass

    def loop(self):
        while self.on:
            p = self.patch
            if p is None or JOB.phase in ("starting", "waiting", "measuring"):
                time.sleep(0.2)
                continue
            try:
                with LOCK:
                    meter = current_meter()
                    started = time.time()            # wall clock: the meter's own clock may differ
                    t0, t1, XYZ = meter.read()
                self.error = None
            except Exception as e:
                self.error = str(e)
                time.sleep(1)
                continue
            if XYZ and self.patch is p and started >= self.since + 0.6:     # ignore readings from the last pattern
                self.readings.append(XYZ)

    def view(self):
        p = self.patch
        if not p or not self.readings:
            return {"on": self.on, "waiting": True, "patch": p and p["id"], "error": self.error}
        XYZ, n_avg, jitter = settled_average(list(self.readings))
        cms = load_settings()["tvs"].get(self.tv, {}).get("cms", "samsung")
        out = live_reading(p, XYZ, self.hdr(), self.white_Y, self.basis, cms)
        # steady: the average is on target, and built from enough readings for how jumpy this TV is
        need = 3 if jitter < 0.004 else 5 if jitter < 0.01 else 8
        out.update(on=self.on, waiting=False, steady=out["ok"] and n_avg >= need, n=len(self.readings),
                   n_avg=n_avg, need=need, jitter=jitter, latest_Y=self.readings[-1][1],
                   cms=cms, error=self.error, mismatch=self.mismatch(p, XYZ))
        if out["mismatch"]:
            out["ok"] = out["steady"] = False
        return out

    def mismatch(self, p, XYZ):
        """Does the reading look like the pattern we asked for? (Catches the wrong file playing.)"""
        if XYZ[1] <= 0:
            return "The meter sees black."
        r = T.polar(T.xyz_to_xy(*XYZ))[0]
        if p["kind"] in ("white", "grey") and r > 0.05:
            return "The meter is seeing a colour, not a grey."
        if p["kind"] == "colour" and r < 0.03:
            return "The meter is seeing a grey, not a colour."
        if self.white_Y and p["kind"] in ("white", "grey"):
            hdr = self.hdr()
            exp = (T.pq_decode(p["level"]) * self.white_Y / 100.0) if hdr else self.white_Y * p["level"] ** 2.4
            if exp > 0 and not (0.4 < XYZ[1] / exp < 2.5):
                return "The brightness doesn't match this pattern."
        return None


GUIDE = Guide()


def run_quick(name, mode, which):
    """The short before/after check: a few greys and the six colours, shown by the phone."""
    global SIM_TV
    hdr = MODES[mode]
    ids = GUIDE_PLAN[hdr]["quick"] + COLOUR_IDS
    JOB.reset()
    JOB.phase, JOB.tv, JOB.hdr, JOB.started, JOB.kind = "starting", name, hdr, time.time(), "quick"
    JOB.source, JOB.meter = "iphone", load_settings().get("meter", "hl")
    pats = [p for p in T.build_sequence(hdr)["patches"] if p["id"] in ids]
    JOB.patches = [{"id": p["id"], "name": p["name"], "kind": p["kind"], "Y": None, "done": False} for p in pats]

    def on_event(kind, **kw):
        if kind == "patch_start":
            JOB.index = kw["index"]
        elif kind == "patch":
            JOB.patches[kw["index"]].update(done=True, Y=kw["XYZ"][1] if kw["XYZ"] else None)

    def on_event_all(kind, **kw):
        if kind == "waiting":
            JOB.phase = "waiting"
        elif kind == "synced":
            JOB.phase = "measuring"
        elif kind == "reading":
            JOB.readings.append({"t": time.time(), "Y": kw.get("Y")})
        on_event(kind, **kw)

    paused = GUIDE.patch
    GUIDE.patch = None
    video = pattern_source() == "video"
    JOB.source = "video" if video else "iphone"
    try:
        if SIM and (SIM_TV is None or SIM_TV.hdr != hdr):
            SIM_TV = T.SimTV(hdr, None, seed=sum(map(ord, name)) % 1000)
        with LOCK:
            if video:
                JOB.phase = "waiting"
                meter = SlowSim(SIM_TV, hdr, play_at=2.0, ids=ids) if SIM else current_meter()
                meas = T.sweep(meter, hdr, verbose=False, on_event=on_event_all, cancel=JOB.cancel, ids=ids)
            else:
                JOB.phase = "measuring"
                meter = current_meter()
                meas = T.sweep_direct(meter, hdr, lambda p: PHONE.show_patch(p, mode=mode),
                                      on_event=on_event_all, cancel=JOB.cancel, ids=ids)
        bad = sanity_warnings(meas, hdr)
        if bad:
            raise RuntimeError("The greys came in out of order, so the meter and the video got out of step. "
                               "Start the measurement, then play the quick-check video from the very beginning.")
        if which == "check":
            JOB.result = report_json(T.analyse(meas, hdr), hdr)
            JOB.phase = "done"
            return
        path, st = guide_state(name, mode)
        now = datetime.now().isoformat(timespec="seconds")
        if which == "before" or not st["guided"] or st["guided"][-1].get("after"):
            st["guided"].append({"started": now, "before": meas, "after": None, "mode": mode})
        else:
            st["guided"][-1]["after"] = meas
            st["guided"][-1]["finished"] = now
        T.save_state(path, st)
        if meas.get("W0"):
            GUIDE.white_Y = meas["W0"][1]
        if all(meas.get(c) for c in ("RED", "GREEN", "BLUE")):
            GUIDE.basis = [meas["RED"], meas["GREEN"], meas["BLUE"]]
        log_guided(name, mode, which, meas)
        JOB.phase = "done"
    except T.Cancelled:
        JOB.phase = "cancelled"
    except Exception as e:
        JOB.phase, JOB.error = "error", str(e)
    finally:
        try:
            PHONE.set({"kind": "black"})
        except Exception:
            pass
        if paused is not None:
            GUIDE.patch = None


def log_guided(name, mode, which, meas):
    try:
        hdr = MODES[mode]
        rep = T.analyse(meas, hdr)
        label = {"sdr": "SDR", "hdr10": "HDR10", "hdr10p": "HDR10+", "dv": "Dolby Vision"}[mode]
        out = [f"\n## Guided {label} - {which} check ({datetime.now():%Y-%m-%d %H:%M})\n",
               f"Greyscale dE {T.grey_avg(rep, hdr):.2f}, colour dE {T.colour_avg(rep):.2f}\n\n",
               "| step | cd/m² | R | G | B | dE |\n|---|---|---|---|---|---|\n"]
        for g in rep["greys"]:
            out.append(f"| {g['name']} | {g['Y']:.1f} | {g['bal'][0] * 100:.1f} | {g['bal'][1] * 100:.1f} | "
                       f"{g['bal'][2] * 100:.1f} | {g['de']:.2f} |\n")
        out.append("\n| colour | dE | saturation | hue | brightness |\n|---|---|---|---|---|\n")
        for c in rep["colours"]:
            out.append(f"| {c['name'].title()} | {c['de']:.2f} | {c['sat']:+.1f}% | {c['hue']:+.1f}° | {c['lum']:+.1f}% |\n")
        for w in rep["warnings"]:
            out.append(f"\nWarning: {w}\n")
        _append(name, "".join(out))
    except Exception as e:
        print("log_guided failed:", e)


def guide_view(name, mode):
    hdr = MODES[mode]
    path, st = guide_state(name, mode)
    sessions = []
    for sess in st["guided"]:
        b = T.analyse(sess["before"], hdr) if sess.get("before") else None
        a = T.analyse(sess["after"], hdr) if sess.get("after") else None
        sessions.append({"started": sess.get("started"), "finished": sess.get("finished"),
                         "before": report_json(b, hdr) if b else None, "after": report_json(a, hdr) if a else None})
    plan = GUIDE_PLAN[hdr]
    names = {p["id"]: p["name"] for p in T.build_sequence(hdr)["patches"]}
    cfg = load_settings()["tvs"].get(name, {"points": 20, "cms": "samsung"})
    files = {pid: T.pack_file(mode, pid) for pid, _ in T.PACK_FILES[hdr]}
    return {"name": name, "mode": mode, "hdr": hdr, "sessions": sessions, "plan": plan,
            "names": names, "colours": COLOUR_IDS, "settings": cfg, "files": files,
            "folder": T.PACK_FOLDER[mode], "source": pattern_source(), "finder": finder_state(name, mode)}


def sanity_warnings(meas, hdr):
    """Readings in the wrong order mean the patterns and the meter got out of step."""
    seq = [p for p in T.build_sequence(hdr)["patches"] if p["kind"] == "grey" and meas.get(p["id"])]
    ys = [meas[p["id"]][1] for p in seq]
    return any(b < a * 0.8 for a, b in zip(ys, ys[1:]))


# ======================================================================== point finder
#
# TVs label their multipoint (10/20-point) controls on their own scale, which often doesn't match
# the video's grey levels (an LG C5 in HDR: the 30% control moves the 40% grey). The finder measures
# a ladder of finely spaced greys, you nudge a few controls by a set amount, it measures again, and
# the bumps in the difference show exactly where each control acts.

FINDER_NUDGE = 10
FINDER_POINTS = [("R", 20), ("B", 40), ("R", 60), ("B", 80)]     # far apart, alternating channels


def finder_grid(cfg):
    return 5 if int(cfg.get("points", 20) or 20) == 20 else 10


def finder_q(meas, hdr):
    out = []
    for p in T.finder_sequence(hdr)["patches"]:
        XYZ = meas.get(p["id"])
        if p["kind"] != "grey" or not XYZ or XYZ[1] <= 0:
            continue
        rgb = T.xyz_to_rgb709(XYZ)
        if rgb[1] <= 0:
            continue
        out.append((p["level"] * 100, rgb[0] / rgb[1], rgb[2] / rgb[1], XYZ[1]))
    return out


def finder_map(base, nudged, hdr, grid):
    """Where each nudged control acts, as a grey level of the video (its own % scale).

    The two passes are minutes apart, and some TVs drift in that time (the Samsung Micro RGB's red
    LEDs lose a few % as they warm up), which shifts every grey the same way. So each channel's
    change is measured against its own typical (median) change, and only bumps that stand out from
    the reading-to-reading noise count. The nudges are all upward, and a higher control always acts
    on a brighter grey, which settles which bump belongs to which control."""
    a, b = finder_q(base, hdr), finder_q(nudged, hdr)
    bl = {round(r[0], 2): r for r in a}
    rows = [(l, R / bl[round(l, 2)][1] - 1, B_ / bl[round(l, 2)][2] - 1, Y, bl[round(l, 2)][3])
            for l, R, B_, Y in b if round(l, 2) in bl]
    # the TV can't get brighter than its peak: greys above it all look the same, so drop them
    top = max((r[4] for r in rows), default=0)
    rows = [r for r in rows if r[4] < 0.97 * top or r is max(rows, key=lambda q: q[4])]
    rows = [r for r in rows if r[4] < 0.97 * top]
    if len(rows) < 6:
        return []
    lv = [r[0] for r in rows]
    # each channel against its own typical change (drift), then the two added: a nudge shows up as a
    # bump at its grey whichever channel moved (some TVs move both)
    dd = []
    for ci in (1, 2):
        d = [r[ci] for r in rows]
        med = sorted(d)[len(d) // 2]
        dd.append([x - med for x in d])
    d = [max(0.0, x) + max(0.0, y) for x, y in zip(*dd)]
    # typical noise: the smaller of the two channel changes at each grey, typically (a bump moves one
    # channel, or both at only a few greys), x2 for the two channels added together
    mad = 2 * 1.48 * sorted(min(abs(x), abs(y)) for x, y in zip(*dd))[len(d) // 2] or 1e-4
    need = [max(0.012, 4 * mad * (1 + 4.0 / max(r[4], 0.5)) ** 0.5) for r in rows]   # dark greys: noisier
    peaks = []
    for i in range(len(d)):
        # a bump on the first or last measured grey may really sit beyond it: can't place it, skip it
        if 0 < i < len(d) - 1 and d[i] >= need[i] and d[i] >= d[i - 1] and d[i] >= d[i + 1]:
            L = lv[i]
            if 0 < i < len(d) - 1:
                den = d[i - 1] - 2 * d[i] + d[i + 1]
                if den < 0:
                    L += max(-0.5, min(0.5, 0.5 * (d[i - 1] - d[i + 1]) / den)) * (lv[i + 1] - lv[i])
            r_, b_ = max(0.0, dd[0][i]), max(0.0, dd[1][i])
            peaks.append({"level": round(L, 1), "size": round(d[i] * 100, 1), "score": min(d[i] / need[i], 4.0),
                          "ch": "R" if r_ >= b_ else "B", "lean": (r_ - b_) / (r_ + b_ or 1)})   # +1 red only, -1 blue only
    # bumps go to controls in order (a brighter control acts on a brighter grey); among the orderings,
    # prefer more bumps explained, then the smaller shift from the labels
    ctrls = sorted(FINDER_POINTS, key=lambda cp: cp[1])
    best = {"score": -1e9, "pairs": []}

    def walk(k, pi, acc, score):
        if k == len(ctrls):
            if score > best["score"]:
                best.update(score=score, pairs=list(acc))
            return
        C, P = ctrls[k]
        walk(k + 1, pi, acc, score)                                  # no bump for this control
        for q in range(pi, len(peaks)):
            pk = peaks[q]
            acc.append({"point": P, "channel": pk["ch"], "level": pk["level"], "size": pk["size"]})
            # which channel moved: a clear red-only bump belongs to a red nudge (ignored when both moved)
            match = pk["lean"] * (1 if C == "R" else -1)
            walk(k + 1, q + 1, acc, score + 10 + pk["score"] - abs(pk["level"] - P) / 10 + 3 * match)
            acc.pop()
    walk(0, 0, [], 0)
    return best["pairs"]


def point_for_level(pairs, L, grid):
    """Which TV control to use for a grey at level L (the video's %), from the measured pairs."""
    pts = sorted((p["level"], p["point"]) for p in pairs)
    if not pts:
        return None
    if len(pts) == 1:
        P = pts[0][1] + (L - pts[0][0])
    else:
        P = None
        for (l0, p0), (l1, p1) in zip(pts, pts[1:]):
            if L <= l1 or (l1, p1) == pts[-1]:
                P = p0 + (p1 - p0) * (L - l0) / ((l1 - l0) or 1)
                break
        if L < pts[0][0]:
            (l0, p0), (l1, p1) = pts[0], pts[1]
            P = p0 + (p1 - p0) * (L - l0) / ((l1 - l0) or 1)
    P = max(grid, min(100, grid * round(P / grid)))
    return int(P)


def finder_state(name, mode):
    path, st = guide_state(name, mode)
    f = st.get("finder") or {}
    hdr = MODES[mode]
    cfg = load_settings()["tvs"].get(name, {})
    grid = finder_grid(cfg)
    if f.get("base") and f.get("nudged"):
        f["map"] = finder_map(f["base"], f["nudged"], hdr, grid)       # re-read saved passes with the current method
    out = {"base": bool(f.get("base")), "nudged": bool(f.get("nudged")), "map": f.get("map"),
           "overrides": f.get("overrides", {}),
           "nudges": [{"channel": c, "point": P, "clicks": FINDER_NUDGE} for c, P in FINDER_POINTS],
           "grid": grid, "file": T.pack_file(mode, "QUICK").replace("00 - Quick check", "00b - Point finder")}
    if f.get("map"):
        levels = {p["id"]: (p["level"] * 100) for p in T.build_sequence(hdr)["patches"] if p["kind"] in ("grey", "white")}
        pairs = list(f["map"])
        # controls the user found by hand count as measured points too, and override that grey outright
        mine = [{"point": P, "level": levels[pid], "channel": "?", "size": 0}
                for pid, P in (f.get("overrides") or {}).items() if pid in levels]
        # the user's findings win: drop measured points that disagree with them (same control, nearly
        # the same grey, or out of order with them)
        def agrees(q):
            return all(q["point"] != m["point"] and abs(q["level"] - m["level"]) > 4 and
                       (q["point"] - m["point"]) * (q["level"] - m["level"]) > 0 for m in mine)
        pairs = sorted([q for q in pairs if agrees(q)] + mine, key=lambda q: q["level"])
        out["suggest"] = {pid: point_for_level(pairs, L, grid) for pid, L in levels.items()}
        out["suggest"].update({k: v for k, v in (f.get("overrides") or {}).items() if k in levels})
    if f.get("overrides") and not f.get("map"):
        out["map"] = []
        out["suggest"] = dict(f["overrides"])
    return out


def run_finder(name, mode, phase):
    global SIM_TV
    hdr = MODES[mode]
    seq = T.finder_sequence(hdr)
    JOB.reset()
    JOB.phase, JOB.tv, JOB.hdr, JOB.started, JOB.kind = "starting", name, hdr, time.time(), "finder"
    JOB.patches = [{"id": p["id"], "name": p["name"], "kind": p["kind"], "Y": None, "done": False} for p in seq["patches"]]

    def on_event(kind, **kw):
        if kind == "waiting":
            JOB.phase = "waiting"
        elif kind == "synced":
            JOB.phase = "measuring"
        elif kind == "reading":
            JOB.readings.append({"t": time.time(), "Y": kw.get("Y")})
        elif kind == "patch_start":
            JOB.index = kw["index"]
        elif kind == "patch":
            JOB.patches[kw["index"]].update(done=True, Y=kw["XYZ"][1] if kw["XYZ"] else None)

    GUIDE.patch = None
    video = pattern_source() == "video"
    try:
        if SIM:
            if SIM_TV is None or SIM_TV.hdr != hdr:
                SIM_TV = T.SimTV(hdr, None, seed=sum(map(ord, name)) % 1000)
            SIM_TV.s["p20_shift"] = 0.10          # demo TV: each control acts 10% above its label
            if phase == "nudged":
                SIM_TV.apply({f"p20:{c}:{P}": FINDER_NUDGE for c, P in FINDER_POINTS})
        with LOCK:
            if video or SIM:
                meter = SlowSim(SIM_TV, hdr, play_at=2.0, ids=seq) if SIM else current_meter()
                meas = T.sweep(meter, hdr, verbose=False, on_event=on_event, cancel=JOB.cancel, seq=seq)
            else:
                if hdr:
                    raise RuntimeError("The point finder needs the pattern video in HDR. Switch to Pattern videos in step 1.")
                meter = current_meter()
                JOB.phase = "measuring"
                meas = T.sweep_direct(meter, hdr, lambda p: PHONE.show_patch(p, mode="sdr"), on_event=on_event,
                                      cancel=JOB.cancel, seq=seq)
        if SIM and phase == "nudged":
            SIM_TV.apply({f"p20:{c}:{P}": -FINDER_NUDGE for c, P in FINDER_POINTS})   # demo: put them back
        meas.pop("_missed", None)
        path, st = guide_state(name, mode)
        f = st.setdefault("finder", {})
        if phase == "base":
            st["finder"] = {"base": meas}
        else:
            f["nudged"] = meas
            cfg = load_settings()["tvs"].get(name, {})
            f["map"] = finder_map(f["base"], meas, hdr, finder_grid(cfg))
            if len(f["map"]) < 2:
                raise RuntimeError("The nudges didn't show up clearly enough to find the controls. Check the four "
                                   "changes were made (+10 each), then measure the nudged pass again.")
            _append(name, f"\n## Point finder ({mode}) {datetime.now():%Y-%m-%d %H:%M}\n" +
                    "".join(f"- TV {p['point']}% control ({p['channel']}) acts on the {p['level']:.1f}% grey "
                            f"(change {p['size']:+.1f}%)\n" for p in f["map"]))
        T.save_state(path, st)
        JOB.phase = "done"
    except T.Cancelled:
        JOB.phase = "cancelled"
    except Exception as e:
        JOB.phase, JOB.error = "error", str(e)


# ======================================================================== live meter

class Live:
    def __init__(self):
        self.on = False
        self.data = None
        self.hdr = False
        self.thread = None
        self.error = None

    def start(self, hdr):
        self.hdr = hdr
        if self.on:
            return
        self.on = True
        self.thread = threading.Thread(target=self.loop, daemon=True)
        self.thread.start()

    def stop(self):
        self.on = False

    def loop(self):
        sim_tv = T.SimTV(False) if SIM else None
        pats = T.build_sequence(False)["patches"]
        i = 0
        while self.on:
            if JOB.phase in ("starting", "waiting", "measuring"):
                time.sleep(0.5)
                continue
            try:
                with LOCK:
                    if SIM:
                        p = pats[(i // 6) % len(pats)]
                        i += 1
                        XYZ = sim_tv.render(p)
                        XYZ = tuple(v * (1 + random.gauss(0, 0.002)) for v in XYZ)
                        time.sleep(0.5)
                    else:
                        meter = HUB.get()
                        t0, t1, XYZ = meter.read()
                self.error = None
            except Exception as e:            # e.g. no spotread: say so and keep trying, don't die
                self.error = str(e)
                time.sleep(2)
                continue
            if XYZ:
                self.data = live_analysis(XYZ, self.hdr)
                self.data["t"] = time.time()


def live_analysis(XYZ, hdr):
    x, y = T.xyz_to_xy(*XYZ)
    r, ang = T.polar((x, y))
    out = {"Y": XYZ[1], "x": x, "y": y, "uv": T.upvp(x, y), "d65_uv": T.upvp(*T.D65)}
    if r < 0.03:
        rgb = T.xyz_to_rgb709(XYZ)
        out.update(kind="white", bal=[c / XYZ[1] for c in rgb] if XYZ[1] > 0 else [1, 1, 1],
                   de=T.white_de(XYZ))
        mx, mn = max(range(3), key=lambda k: out["bal"][k]), min(range(3), key=lambda k: out["bal"][k])
        names = ("Red", "Green", "Blue")
        out["advice"] = ("White is on target." if out["bal"][mx] - out["bal"][mn] <= 0.01 else
                         f"{names[mx]} is high and {names[mn]} is low: lower {names[mx]} or raise {names[mn]}.")
    else:
        tg = T.colour_targets(hdr)
        um, vm = out["uv"]
        name = min(tg, key=lambda n: math.hypot(um - T.upvp(*tg[n]["xy"])[0], vm - T.upvp(*tg[n]["xy"])[1]))
        rt, at = T.polar(tg[name]["xy"])
        out.update(kind="colour", name=name, sat=(r / rt - 1) * 100, hue=T.wrap(ang - at),
                   tuv=T.upvp(*tg[name]["xy"]))
    return out


LIVE = Live()


# ======================================================================== meter check

def meter_check():
    steps = []
    if SIM:
        return {"steps": [{"ok": True, "text": "Demo mode - a simulated meter is used."}]}
    path = shutil.which("spotread")
    if not path:
        return {"steps": [{"ok": False, "text": "ArgyllCMS isn't installed (spotread not found). "
                                                 + T.install_hint()}]}
    steps.append({"ok": True, "text": f"ArgyllCMS found ({path})."})
    try:
        ports = T.spotread_ports()
    except subprocess.TimeoutExpired:
        ports = []
        if WINDOWS:
            steps.append({"ok": False, "text": "spotread froze while looking for meters. If this PC has Bluetooth "
                                               "COM ports, set ARGYLL_EXCLUDE_SERIAL_SCAN to them (e.g. COM5;COM6)."})
    except OSError as e:
        ports = []
        steps.append({"ok": False, "text": f"spotread didn't start: {e}"})
    found = [(n, d) for n, d in ports if T.is_meter_port(d)]       # serial ports (/dev/..., COMn) aren't meters
    meters = [d for n, d in found]
    if not meters:
        steps.append({"ok": False, "text": "No meter found. " + T.no_meter_hint(COMPUTER)})
        return {"steps": steps}
    args = load_settings().get("spotread_args", "-y n").split()
    if WINDOWS and found[0][0] != "1" and not T._has_port_arg(args):
        steps.append({"ok": True, "text": f"Meter found: {meters[0]} (port {found[0][0]}, used automatically)."})
    else:
        steps.append({"ok": True, "text": f"Meter found: {meters[0]}."})
    with LOCK:
        meter = HUB.get()
        times, ys = [], []
        for _ in range(3):
            t0, t1, XYZ = meter.read()
            if XYZ is None:
                steps.append({"ok": False, "text": "The meter didn't return a reading."})
                return {"steps": steps}
            times.append(t1 - t0)
            ys.append(XYZ[1])
    avg = sum(times) / len(times)
    steps.append({"ok": True, "text": f"Readings: {', '.join(f'{y:.1f}' for y in ys)} cd/m², "
                                      f"{avg:.1f}s each ({'fast mode' if meter.mode == 'persistent' else 'slower mode'})."})
    if avg > 4:
        steps.append({"ok": False, "text": "Readings are slow - dark patches will be skipped."})
    return {"steps": steps}


# ======================================================================== HTTP

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype="application/json"):
        data = body if isinstance(body, bytes) else (json.dumps(body) if ctype == "application/json" else body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype + ("; charset=utf-8" if "text" in ctype or "json" in ctype else ""))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _send_clip(self, path):
        m = re.match(r"^/clips/(hdr10|dv)/([A-Z0-9]+)\.mp4$", path)
        fn = os.path.join(HERE, "clips", m.group(1), m.group(2) + ".mp4") if m else None
        if not fn or not os.path.exists(fn):
            return self._send(404, {"error": "no such clip"})
        size = os.path.getsize(fn)
        start, end = 0, size - 1
        rng = self.headers.get("Range")
        if rng:
            mm = re.match(r"bytes=(\d*)-(\d*)", rng)
            if mm:
                if mm.group(1):
                    start = int(mm.group(1))
                    end = int(mm.group(2)) if mm.group(2) else size - 1
                else:
                    start = max(0, size - int(mm.group(2)))
                end = min(end, size - 1)
        with open(fn, "rb") as f:
            f.seek(start)
            data = f.read(end - start + 1)
        self.send_response(206 if rng else 200)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(len(data)))
        if rng:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        self.wfile.write(data)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}") if n else {}

    def _local(self):
        return self.client_address[0] in ("127.0.0.1", "::1", "::ffff:127.0.0.1")

    def _token(self, qs):
        return self.headers.get("X-TVCal-Token") or qs.get("t") or ""

    def _authed(self, qs):
        if self._local():
            return True
        tok = self.headers.get("X-TVCal-Token") or qs.get("t")
        return bool(tok) and tok in load_settings().get("phones", {})

    def do_GET(self):
        global LAST_ACTIVITY
        LAST_ACTIVITY = time.time()
        u = urlparse(self.path)
        qs = {k: v[0] for k, v in parse_qs(u.query).items()}
        if u.path == "/api/version":
            return self._send(200, {"app": "tvcal", "version": APP_VERSION, "pid": os.getpid(),
                                    "healthy": os.path.isdir(HERE)})
        if u.path != "/" and not self._authed(qs):
            return self._send(403, {"error": "Pair this device first."})
        if u.path == "/api/phone/pattern":
            ph = phone_for(self._token(qs))
            if not ph.connected():
                ph.first_seen = time.time()
            ph.last_seen = time.time()
            ph.device = qs.get("device", ph.device)
            ph.external = qs.get("ext") == "1"
            ph.ext_name = qs.get("extname", "")
            ph.cam_ready = qs.get("cam") == "1"
            ph.model = qs.get("model", ph.model)
            ph.name = qs.get("name", ph.name)[:60]               # optional marketing name (Android sends it)
            phone_os_from(ph, qs)
            return self._send(200, {"seq": ph.seq, "pattern": ph.pattern, "measure": ph.mseq})
        if u.path == "/api/profiles":
            return self._send(200, {"profiles": [profile_summary(p, m) for m, p in load_profiles().items()],
                                    "current": PHONE.model})
        if u.path == "/api/phone/status":
            with PHONES_LOCK:
                every = list(PHONES.values())
            return self._send(200, {**PHONE.status(),
                                    "phones": [p.view() for p in sorted(every, key=lambda p: p.first_seen)
                                               if p.connected()]})
        if u.path == "/":
            return self._send(200, page_html(), "text/html")
        if u.path.startswith("/clips/"):
            return self._send_clip(u.path)
        if u.path == "/api/guide":
            return self._send(200, guide_view(qs["name"], qs.get("mode", "sdr")))
        if u.path == "/api/guide/live":
            return self._send(200, GUIDE.view())
        if u.path == "/api/tvs":
            s = load_settings()
            names = discover_tvs(s)
            save_settings(s)
            return self._send(200, {"tvs": [{"name": n, **s["tvs"][n]} for n in names], "sim": SIM,
                                    "pro": bool(s.get("pro")), "meter": s.get("meter", "hl"),
                                    "source": s.get("source", "video")})
        if u.path == "/api/tv":
            return self._send(200, tv_view(qs["name"], qs.get("hdr") == "1"))
        if u.path == "/api/job":
            return self._send(200, JOB.view())
        if u.path == "/api/live":
            return self._send(200, {"on": LIVE.on, "data": LIVE.data, "error": LIVE.error})
        return self._send(404, {"error": "not found"})

    def do_POST(self):
        global LAST_ACTIVITY
        LAST_ACTIVITY = time.time()
        u = urlparse(self.path)
        b = self._body()
        qs = {k: v[0] for k, v in parse_qs(u.query).items()}
        if u.path == "/api/phone/pair":
            global PAIR_CODE
            if str(b.get("code", "")).strip() != PAIR_CODE:
                PHONE.bad_codes += 1
                if PHONE.bad_codes >= 8:                      # too many guesses: new code
                    PAIR_CODE, PHONE.bad_codes = f"{secrets.randbelow(10000):04d}", 0
                return self._send(403, {"error": f"That code doesn't match. Check the code shown in TV Calibrator on the {COMPUTER}."})
            tok = secrets.token_hex(16)
            s = load_settings()
            s.setdefault("phones", {})[tok] = {"device": str(b.get("device", DEFAULT_PHONE))[:80],
                                               "paired": datetime.now().isoformat(timespec="seconds")}
            for k in ("model", "name", "android", "ios"):          # optional, for the records
                if b.get(k):
                    s["phones"][tok][k] = str(b[k])[:80]
            save_settings(s)
            ph = phone_for(tok)                                  # what it sent, until its first poll/reading
            if b.get("name"):
                ph.name = str(b["name"])[:60]
            phone_os_from(ph, b)
            return self._send(200, {"token": tok, "mac": socket.gethostname().split(".")[0]})
        if not self._authed(qs):
            return self._send(403, {"error": "Pair this device first."})
        if u.path == "/api/phone/log":
            with open(os.path.join(APP_DIR, "phone.log"), "a", encoding="utf-8") as f:
                f.write(f"{datetime.now():%Y-%m-%d %H:%M:%S}  {b.get('model', '')}  {str(b.get('line', ''))[:500]}\n")
            return self._send(200, {"ok": True})
        if u.path == "/api/phone/reading":
            ph = phone_for(self._token(qs))
            if b.get("name"):
                ph.name = str(b["name"])[:60]
            phone_os_from(ph, b)
            ph.deliver(int(b.get("seq", 0)), b)
            return self._send(200, {"ok": True})
        if u.path == "/api/bridge/start":
            if JOB.phase in ("starting", "waiting", "measuring"):
                return self._send(409, {"error": "Something is already measuring."})
            if not tv_panels().get(b.get("name")):
                return self._send(409, {"error": "Choose what kind of screen this TV is first."})
            if not training_phones():
                return self._send(409, {"error": "Start the camera in TV Cal Lab on at least one phone first "
                                                 "(Lab mode: Start the camera, then measure the darkness)."})
            sig = "hdr10" if b.get("signal") == "hdr10" else "sdr"
            threading.Thread(target=run_bridge, args=(b["name"], bool(b.get("check")), sig), daemon=True).start()
            return self._send(200, {"ok": True})
        if u.path == "/api/profiles/rename":
            profs = load_profiles()
            if b.get("model") in profs:
                profs[b["model"]]["name"] = str(b.get("name", ""))[:60]
                save_profiles(profs)
            return self._send(200, {"ok": True})
        if u.path == "/api/profiles/delete_session":
            profs = load_profiles()
            p = profs.get(b.get("model"))
            if p and 0 <= int(b.get("index", -1)) < len(p["sessions"]):
                p["sessions"].pop(int(b["index"]))
                if p["sessions"]:
                    refit_profile(p)
                    p["camera"] = p["sessions"][0].get("camera") or p.get("camera")
                else:
                    profs.pop(b["model"])
                save_profiles(profs)
            return self._send(200, {"ok": True})
        if u.path == "/api/profiles/export":
            p = load_profiles().get(b.get("model"))
            if not p:
                return self._send(404, {"error": "No such profile."})
            os.makedirs(PROFILE_DIR, exist_ok=True)
            label = re.sub(r"[^A-Za-z0-9 _,()+-]+", "_", p.get("name") or model_file_name(b["model"])).strip(" .") \
                or model_file_name(b["model"])
            fn = os.path.join(PROFILE_DIR, f"{label} camera profile.json")
            with open(fn, "w", encoding="utf-8") as f:
                json.dump({"tvcal_camera_profile": 1, "model": b["model"], **p}, f, indent=1)
            if self._local():
                reveal(fn)
            return self._send(200, {"ok": True, "file": fn})
        if u.path == "/api/profiles/import":
            d = b.get("profile") or {}
            if not d.get("tvcal_camera_profile") or not d.get("model"):
                return self._send(400, {"error": "That isn't a TV Calibrator camera profile."})
            profs = load_profiles()
            p = profs.setdefault(d["model"], {"name": d.get("name", ""), "sessions": []})
            if not p.get("name"):
                p["name"] = d.get("name", "")
            have = {(x["tv"], x.get("date")) for x in p["sessions"]}
            added = 0
            for sess in d.get("sessions", []):
                if (sess.get("tv"), sess.get("date")) not in have:
                    p["sessions"].append(sess)
                    added += 1
            refit_profile(p)
            save_profiles(profs)
            return self._send(200, {"ok": True, "added": added})
        if u.path == "/api/tv/panel":
            s_ = load_settings()
            t_ = s_.get("tvs", {}).get(b.get("name"))
            if t_ is None:
                return self._send(404, {"error": "No such TV."})
            pn = b.get("panel") if b.get("panel") in PANELS else None
            t_["panel"] = pn
            save_settings(s_)
            profs = load_profiles()                    # its sessions now count for that kind of screen
            for p_ in profs.values():
                refit_profile(p_)
            save_profiles(profs)
            return self._send(200, {"ok": True})
        if u.path == "/api/bridge/finish":
            JOB.finish.set()
            return self._send(200, {"ok": True})
        if u.path == "/api/guide/restart":
            GUIDE.readings.clear()
            GUIDE.since = time.time()
            return self._send(200, {"ok": True})
        if u.path == "/api/guide/show":
            if not SIM and pattern_source() == "iphone" and not PHONE.connected():
                return self._send(409, {"error": "Open TV Calibrator on the iPhone and connect it to the TV first."})
            try:
                GUIDE.start(b["name"], b.get("mode", "sdr"))
                GUIDE.show(b["patch"])
            except Exception as e:
                return self._send(409, {"error": str(e)})
            return self._send(200, {"ok": True})
        if u.path == "/api/finder/measure":
            if JOB.phase in ("starting", "waiting", "measuring"):
                return self._send(409, {"error": "Already measuring."})
            GUIDE.start(b["name"], b.get("mode", "sdr"))
            threading.Thread(target=run_finder, args=(b["name"], b.get("mode", "sdr"), b.get("phase", "base")),
                             daemon=True).start()
            return self._send(200, {"ok": True})
        if u.path == "/api/finder/override":
            path, st = guide_state(b["name"], b.get("mode", "sdr"))
            ov = st.setdefault("finder", {}).setdefault("overrides", {})
            if b.get("point"):
                ov[b["pid"]] = int(b["point"])
            else:
                ov.pop(b["pid"], None)
            T.save_state(path, st)
            return self._send(200, {"ok": True})
        if u.path == "/api/finder/reset":
            path, st = guide_state(b["name"], b.get("mode", "sdr"))
            ov = (st.get("finder") or {}).get("overrides")
            st.pop("finder", None)
            if ov:
                st["finder"] = {"overrides": ov}
            T.save_state(path, st)
            return self._send(200, {"ok": True})
        if u.path == "/api/guide/stop":
            GUIDE.stop()
            return self._send(200, {"ok": True})
        if u.path == "/api/guide/measure":
            if JOB.phase in ("starting", "waiting", "measuring"):
                return self._send(409, {"error": "Already measuring."})
            if not SIM and pattern_source() == "iphone" and not PHONE.connected():
                return self._send(409, {"error": "Open TV Calibrator on the iPhone and connect it to the TV first."})
            GUIDE.start(b["name"], b.get("mode", "sdr"))
            threading.Thread(target=run_quick, args=(b["name"], b.get("mode", "sdr"), b.get("which", "before")),
                             daemon=True).start()
            return self._send(200, {"ok": True})
        if u.path == "/api/settings":
            st_ = load_settings()
            for k in ("meter", "pro", "source"):
                if k in b:
                    st_[k] = b[k]
            save_settings(st_)
            return self._send(200, {"ok": True})
        if u.path == "/api/phone/ack":
            phone_for(self._token(qs)).acknowledge(int(b.get("seq", 0)))
            return self._send(200, {"ok": True})
        if u.path == "/api/phone/show":
            if b.get("what") == "white":
                PHONE.set({"kind": "patch", "rgb": [1, 1, 1], "window": 0.1, "name": "100% white"})
            else:
                PHONE.set({"kind": "black"})
            return self._send(200, {"ok": True})
        if u.path == "/api/phone/forget":
            s = load_settings()
            s["phones"] = {}
            save_settings(s)
            return self._send(200, {"ok": True})
        if u.path == "/api/tvs":
            name = re.sub(r"[^A-Za-z0-9 _-]+", "", b.get("name", "")).strip()
            if not name:
                return self._send(400, {"error": "Give the TV a name."})
            s = load_settings()
            old_ = s["tvs"].get(name, {})
            s["tvs"][name] = {"points": int(b.get("points", 20)), "cms": b.get("cms", "samsung"),
                              "brand": b.get("brand", "other"), "panel": b.get("panel", old_.get("panel"))}
            save_settings(s)
            return self._send(200, {"ok": True, "name": name})
        if u.path == "/api/round/start":
            if JOB.phase in ("starting", "waiting", "measuring"):
                return self._send(409, {"error": "A round is already running."})
            path, st = state_for(b["name"], bool(b.get("hdr")))
            if st is None:
                return self._send(409, {"error": "old"})
            source = b.get("source", "video")
            if source == "iphone":
                if not SIM and not (PHONE.connected() and PHONE.external):
                    return self._send(409, {"error": "The iPhone isn't connected to the TV. Open TV Calibrator on the "
                                                     "phone and connect it to the TV (HDMI adapter or AirPlay)."})
            meter_kind = b.get("meter", "hl")
            if meter_kind == "iphone":
                if not profile_for(PHONE.model):
                    return self._send(409, {"error": "Train the phone camera first (on any TV)."})
                if not SIM and not (PHONE.connected() and PHONE.cam_ready):
                    return self._send(409, {"error": "Start the camera in TV Calibrator on the phone first."})
            threading.Thread(target=run_job, args=(b["name"], bool(b.get("hdr")), source, meter_kind),
                             daemon=True).start()
            return self._send(200, {"ok": True})
        if u.path == "/api/round/cancel":
            JOB.cancel.set()
            return self._send(200, {"ok": True})
        if u.path == "/api/round/confirm":
            hdr = bool(b.get("hdr"))
            path, st = state_for(b["name"], hdr)
            if not st or not st.get("pending"):
                return self._send(409, {"error": "Nothing to confirm."})
            plan = st.pop("pending")
            sim_tv = T.SimTV(hdr, st.get("sim")) if SIM else None
            T.record_round(st, plan, bool(b.get("ok")), sim_tv)
            log_confirm(b["name"], hdr, len(st["rounds"]), plan, bool(b.get("ok")))
            if sim_tv:
                st["sim"] = sim_tv.s
            T.save_state(path, st)
            JOB.reset()
            return self._send(200, {"ok": True})
        if u.path == "/api/tv/reset":
            path = T.state_path(tv_key(b["name"]), bool(b.get("hdr")))
            if os.path.exists(path):
                os.remove(path)
            return self._send(200, {"ok": True})
        if u.path == "/api/tv/export":
            fn = export_tv(b["name"])
            if self._local():
                reveal(fn)
            return self._send(200, {"ok": True, "file": fn})
        if u.path == "/api/tv/folder":
            d = tv_folder(b["name"])
            if self._local():
                reveal(d)
            return self._send(200, {"ok": True, "folder": d})
        if u.path == "/api/tv/settings":
            s = load_settings()
            s["tvs"].setdefault(b["name"], {})
            s["tvs"][b["name"]].update({k: b[k] for k in ("points", "cms", "brand") if k in b})
            save_settings(s)
            return self._send(200, {"ok": True})
        if u.path == "/api/live/start":
            LIVE.start(bool(b.get("hdr")))
            return self._send(200, {"ok": True})
        if u.path == "/api/live/stop":
            LIVE.stop()
            return self._send(200, {"ok": True})
        if u.path == "/api/check":
            return self._send(200, meter_check())
        if u.path == "/api/quit" and self._local():
            self._send(200, {"ok": True})
            threading.Thread(target=shutdown, daemon=True).start()
            return
        return self._send(404, {"error": "not found"})


SERVER = None


def shutdown():
    time.sleep(0.3)
    GUIDE.stop()
    LIVE.stop()
    HUB.close()
    BONJOUR.stop()
    SERVER.shutdown()


def port_in_use(port):
    """True if something holds the port - including a stuck program that never accepts."""
    if WINDOWS:
        # Windows takes about 2 s to refuse a connection to a closed local port, so try to take the port
        # instead (exclusively, the way the server does): that answers at once.
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            try:
                s.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
                s.bind(("0.0.0.0", port))
                return False
            except OSError:
                return True
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(1.0)
        try:
            s.connect(("127.0.0.1", port))
            return True
        except ConnectionRefusedError:
            return False
        except OSError:          # timed out: something is there but stuck
            return True


_PAGE = None


def page_html():
    """The page, read once and kept: macOS can run the app from a temporary copy that vanishes
    (App Translocation), which used to leave a running copy that couldn't load its own page."""
    global _PAGE
    if _PAGE is None:
        with open(os.path.join(HERE, "tvcal_app.html"), encoding="utf-8") as f:
            _PAGE = f.read()
    return _PAGE


def running_instance(port):
    """Ask whatever is on the port who it is. Returns its /api/version reply, or None if it
    doesn't answer properly (stuck, crashed half-way, or not TV Calibrator)."""
    import urllib.request
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/version", timeout=3) as r:
            d = json.load(r)
            return d if d.get("app") == "tvcal" else None
    except Exception:
        return None


def _command_line(pid):
    """A process's command line ("" if unknown)."""
    if not WINDOWS:
        return subprocess.run(["ps", "-p", str(pid), "-o", "command="], capture_output=True, text=True).stdout
    try:
        import psutil                                   # optional
        return " ".join(psutil.Process(pid).cmdline())
    except ImportError:
        pass
    except Exception:
        return ""
    try:
        return subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command",
                               f"(Get-CimInstance Win32_Process -Filter 'ProcessId={int(pid)}').CommandLine"],
                              capture_output=True, text=True, errors="replace", timeout=20, **_NO_WINDOW).stdout
    except subprocess.TimeoutExpired:
        return ""


def _is_tvcal(pid):
    try:
        cmd = _command_line(pid)
        return "tvcal_app.py" in cmd
    except OSError:
        return False


def listening_pids(port):
    """The processes listening on a TCP port (macOS: lsof; Windows: netstat)."""
    pids = set()
    try:
        if WINDOWS:
            out = subprocess.run(["netstat", "-ano", "-p", "TCP"], capture_output=True, text=True,
                                 errors="replace", timeout=20, **_NO_WINDOW).stdout
            for line in out.splitlines():
                f = line.split()
                # "TCP  0.0.0.0:8765  0.0.0.0:0  LISTENING  1234"; the state word is translated on
                # non-English Windows, but a listening socket's remote address always ends in ":0"
                if len(f) >= 5 and f[0].upper().startswith("TCP") and f[1].rsplit(":", 1)[-1] == str(port) \
                        and f[2].endswith(":0") and f[-1].isdigit():
                    pids.add(int(f[-1]))
        else:
            out = subprocess.run(["lsof", "-ti", f"tcp:{port}", "-sTCP:LISTEN"], capture_output=True, text=True).stdout
            pids.update(int(x) for x in out.split())
    except (OSError, ValueError, subprocess.TimeoutExpired):
        pass
    pids.discard(0)
    return pids


def old_instance_pids(port, info, listening=None):
    """The processes of an older or stuck copy of TV Calibrator on the port (empty if it's another program)."""
    pids = set()
    if info and info.get("pid"):
        pids.add(int(info["pid"]))
    try:
        with open(PID_FILE.format(port=port)) as f:
            pids.add(int(f.read().strip()))
    except (OSError, ValueError):
        pass
    pids |= listening_pids(port) if listening is None else listening   # whatever listens, if it's ours
    return {p for p in pids if p != os.getpid() and _is_tvcal(p)}


def stop_old_instance(port, info, pids=None):
    """Stop an older or stuck copy of TV Calibrator so this one can start."""
    import signal
    if pids is None:
        pids = old_instance_pids(port, info)
    if not pids:
        return not port_in_use(port)    # nothing of ours to stop: don't wait for a port that won't free up
    # Windows has no SIGKILL; os.kill(pid, SIGTERM) already ends the process there (TerminateProcess)
    for sig in (signal.SIGTERM, getattr(signal, "SIGKILL", signal.SIGTERM)):
        for p in pids:
            try:
                os.kill(p, sig)
            except OSError:
                pass
        for _ in range(30):
            if not port_in_use(port):
                return True
            time.sleep(0.1)
    return not port_in_use(port)


def idle_watchdog():
    """Nobody has had the page open (and no phone has talked to it) for a while: quit, so old
    copies don't pile up in the background."""
    while True:
        time.sleep(30)
        busy = JOB.phase in ("starting", "waiting", "measuring") or GUIDE.patch is not None
        if not busy and not LIVE.on and time.time() - LAST_ACTIVITY > IDLE_QUIT:
            print("No one has used TV Calibrator for a while - stopping.")
            shutdown()
            return


class Server(ThreadingHTTPServer):
    """On Windows, SO_REUSEADDR would let a second program bind the same port (another server already on
    8765, or a second copy of this one) and both would half-work. Take the port exclusively instead."""
    allow_reuse_address = not WINDOWS
    daemon_threads = True

    def server_bind(self):
        if WINDOWS and hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        super().server_bind()


def free_port_hint(port):
    """", or start with --port N." naming a port that is free right now ("." if none nearby is)."""
    free = next((p for p in range(port + 1, port + 50) if not port_in_use(p)), None)
    return f", or start with --port {free}." if free else "."


def main():
    global SIM, SERVER, PORT
    ap = argparse.ArgumentParser()
    ap.add_argument("--sim", action="store_true", help="demo with a simulated TV and meter")
    ap.add_argument("--no-browser", action="store_true")
    ap.add_argument("--port", type=int, default=PORT)
    a = ap.parse_args()
    SIM = a.sim
    url = f"http://127.0.0.1:{a.port}/"
    if port_in_use(a.port):
        info = running_instance(a.port)
        if info and info.get("version") == APP_VERSION and info.get("healthy", True):
            print("TV Calibrator is already running - opening it.")
            if not a.no_browser:
                webbrowser.open(url)
            return
        listening = listening_pids(a.port)
        pids = old_instance_pids(a.port, info, listening)
        if info or pids:
            why = ("an older version of TV Calibrator" if info.get("version") != APP_VERSION else
                   "a copy of TV Calibrator whose files have moved") if info else \
                "a copy of TV Calibrator that isn't responding"
            print(f"Stopping {why}...")
        if not stop_old_instance(a.port, info, pids):
            who = f" (process {', '.join(map(str, sorted(listening)))})" if listening else ""
            print(f"Port {a.port} is used by another program{who}. Close it" + free_port_hint(a.port))
            sys.exit(1)
    PORT = a.port
    page_html()                      # load the page now, while the app's files are certainly there
    try:
        SERVER = Server(("0.0.0.0", a.port), Handler)
    except OSError as e:
        print(f"Couldn't use port {a.port} ({e}). Close the program using it" + free_port_hint(a.port))
        sys.exit(1)
    try:
        os.makedirs(os.path.dirname(PID_FILE), exist_ok=True)
        with open(PID_FILE.format(port=a.port), "w") as f:
            f.write(str(os.getpid()))
    except OSError:
        pass
    import signal
    signal.signal(signal.SIGTERM, lambda *_: threading.Thread(target=shutdown, daemon=True).start())
    threading.Thread(target=idle_watchdog, daemon=True).start()
    BONJOUR.start(a.port)
    threading.Thread(target=BONJOUR.watch, args=(a.port,), daemon=True).start()
    print(f"TV Calibrator running at {url}" + ("  (demo mode)" if SIM else ""))
    if BONJOUR.kind is None:
        print(f"Phones won't find this {COMPUTER} by themselves (no Bonjour / zeroconf). In the phone's lab app, "
              f"enter {lan_ip()}:{a.port} and the pairing code {PAIR_CODE}.")
    if WINDOWS:
        print("If a phone can't connect: allow Python through Windows Defender Firewall on Private networks, "
              "and set this Wi-Fi network to Private.")
    print("Quit from the app's sidebar, or press Control-C here.")
    if not a.no_browser:
        threading.Timer(0.6, lambda: webbrowser.open(url)).start()
    try:
        SERVER.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        LIVE.stop()
        HUB.close()
        BONJOUR.stop()
        try:
            with open(PID_FILE.format(port=PORT)) as f:
                ours = f.read().strip() == str(os.getpid())
            if ours:                       # removed after closing it: Windows can't delete an open file
                os.remove(PID_FILE.format(port=PORT))
        except OSError:
            pass
        print("TV Calibrator stopped.")


if __name__ == "__main__":
    main()
