#!/usr/bin/env python3
"""
tvcal.py - measure-then-prescribe TV calibration, synced to the auto pattern video.

Each run = one "round":
  1. Start the tool, rest the meter on the centre of the screen, press play on
     tvcal_auto_SDR.mp4 (or tvcal_auto_HDR.mp4 with --hdr).
  2. The tool finds the sync flashes, then measures every grey and colour by itself.
  3. It prints a report and the exact menu changes to make. Make them, then run again.

Round 1 measures the TV as it is and asks for a small set of "learning" changes so it
can find out how much one click moves things on THIS TV. From round 2 on it gives real
corrections, and keeps refining its model with every round. Usually 3-4 rounds total.

Usage:
    python3 tvcal.py --tv q60t                 # SDR, Samsung-style menus
    python3 tvcal.py --tv q60t --hdr           # HDR10 (separate model & history)
    python3 tvcal.py --tv tcl --cms hsl        # TCL: hue/sat/lum colour menu
    python3 tvcal.py --tv q60t --points 0      # no 20-point menu, 2-point only
    python3 tvcal.py --tv q60t --check         # test meter + sync before a real round
    python3 tvcal.py --tv q60t --report        # re-print last round's report
    python3 tvcal.py --tv q60t --reset         # forget history, start over
    python3 tvcal.py --tv demo --sim --rounds 4   # full simulation, no meter/TV

History is kept in ~/tvcal/<tv>-sdr.json (or -hdr.json).
Needs ArgyllCMS `spotread` on PATH. No Python packages needed.
"""

import argparse
import contextlib
import json
import math
import os
import random
import re
import shlex
import subprocess
import sys
import threading
import time
from datetime import datetime

D65 = (0.3127, 0.3290)
VERSION = 1

# ======================================================================== maths

def xyz_to_xy(X, Y, Z):
    s = X + Y + Z
    return (X / s, Y / s) if s > 0 else (0.0, 0.0)


def xyY_to_XYZ(x, y, Y):
    return (x * Y / y, Y, (1 - x - y) * Y / y)


def solve3(M, v):
    (a, b, c), (d, e, f), (g, h, i) = M
    det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
    inv = ((e * i - f * h, c * h - b * i, b * f - c * e),
           (f * g - d * i, a * i - c * g, c * d - a * f),
           (d * h - e * g, b * g - a * h, a * e - b * d))
    return [sum(inv[r][k] * v[k] for k in range(3)) / det for r in range(3)]


def rgb_to_xyz_matrix(prim, white=D65):
    cols = [(x / y, 1.0, (1 - x - y) / y) for x, y in prim]
    P = [[cols[c][r] for c in range(3)] for r in range(3)]
    xw, yw = white
    S = solve3(P, (xw / yw, 1.0, (1 - xw - yw) / yw))
    return [[P[r][c] * S[c] for c in range(3)] for r in range(3)]


def matvec(M, v):
    return [sum(M[r][c] * v[c] for c in range(3)) for r in range(3)]


GAMUTS = {
    "709": ((0.640, 0.330), (0.300, 0.600), (0.150, 0.060)),
    "p3": ((0.680, 0.320), (0.265, 0.690), (0.150, 0.060)),
    "2020": ((0.708, 0.292), (0.170, 0.797), (0.131, 0.046)),
}
M709 = rgb_to_xyz_matrix(GAMUTS["709"])
M709_INV = [list(r) for r in zip(*[solve3(M709, e) for e in ((1, 0, 0), (0, 1, 0), (0, 0, 1))])]


def xyz_to_rgb709(XYZ):
    return matvec(M709_INV, XYZ)


def xyz_to_lab(XYZ, white):
    def f(t):
        return t ** (1 / 3) if t > (6 / 29) ** 3 else t / (3 * (6 / 29) ** 2) + 4 / 29
    fx, fy, fz = (f(XYZ[i] / white[i]) for i in range(3))
    return 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)


def de2000(lab1, lab2):
    L1, a1, b1 = lab1
    L2, a2, b2 = lab2
    C1, C2 = math.hypot(a1, b1), math.hypot(a2, b2)
    Cb = (C1 + C2) / 2
    G = 0.5 * (1 - math.sqrt(Cb ** 7 / (Cb ** 7 + 25 ** 7)))
    a1p, a2p = (1 + G) * a1, (1 + G) * a2
    C1p, C2p = math.hypot(a1p, b1), math.hypot(a2p, b2)
    h1p = math.degrees(math.atan2(b1, a1p)) % 360
    h2p = math.degrees(math.atan2(b2, a2p)) % 360
    dLp, dCp = L2 - L1, C2p - C1p
    if C1p * C2p == 0:
        dhp = 0
    elif abs(h2p - h1p) <= 180:
        dhp = h2p - h1p
    elif h2p - h1p > 180:
        dhp = h2p - h1p - 360
    else:
        dhp = h2p - h1p + 360
    dHp = 2 * math.sqrt(C1p * C2p) * math.sin(math.radians(dhp / 2))
    Lbp, Cbp = (L1 + L2) / 2, (C1p + C2p) / 2
    if C1p * C2p == 0:
        hbp = h1p + h2p
    elif abs(h1p - h2p) <= 180:
        hbp = (h1p + h2p) / 2
    elif h1p + h2p < 360:
        hbp = (h1p + h2p + 360) / 2
    else:
        hbp = (h1p + h2p - 360) / 2
    T = (1 - 0.17 * math.cos(math.radians(hbp - 30)) + 0.24 * math.cos(math.radians(2 * hbp))
         + 0.32 * math.cos(math.radians(3 * hbp + 6)) - 0.20 * math.cos(math.radians(4 * hbp - 63)))
    dth = 30 * math.exp(-(((hbp - 275) / 25) ** 2))
    Rc = 2 * math.sqrt(Cbp ** 7 / (Cbp ** 7 + 25 ** 7))
    Sl = 1 + 0.015 * (Lbp - 50) ** 2 / math.sqrt(20 + (Lbp - 50) ** 2)
    Sc, Sh = 1 + 0.045 * Cbp, 1 + 0.015 * Cbp * T
    Rt = -math.sin(math.radians(2 * dth)) * Rc
    return math.sqrt((dLp / Sl) ** 2 + (dCp / Sc) ** 2 + (dHp / Sh) ** 2 + Rt * (dCp / Sc) * (dHp / Sh))


def white_de(XYZ):
    """White-point error only (luminance normalised)."""
    Y = XYZ[1]
    if Y <= 0:
        return float("nan")
    n = [v * 100 / Y for v in XYZ]
    W = xyY_to_XYZ(*D65, 100.0)
    return de2000(xyz_to_lab(n, W), (100.0, 0.0, 0.0))


def upvp(x, y):
    d = -2 * x + 12 * y + 3
    return 4 * x / d, 9 * y / d


def polar(xy):
    u, v = upvp(*xy)
    uw, vw = upvp(*D65)
    return math.hypot(u - uw, v - vw), math.degrees(math.atan2(v - vw, u - uw))


def wrap(a):
    return (a + 180) % 360 - 180


def pq_encode(nits):
    m1, m2 = 2610 / 16384, 2523 / 4096 * 128
    c1, c2, c3 = 3424 / 4096, 2413 / 4096 * 32, 2392 / 4096 * 32
    y = max(0.0, min(1.0, nits / 10000)) ** m1
    return ((c1 + c2 * y) / (1 + c3 * y)) ** m2


def pq_decode(e):
    m1, m2 = 2610 / 16384, 2523 / 4096 * 128
    c1, c2, c3 = 3424 / 4096, 2413 / 4096 * 32, 2392 / 4096 * 32
    p = e ** (1 / m2)
    return 10000 * (max(p - c1, 0) / (c2 - c3 * p)) ** (1 / m1)


# ======================================================================== the pattern sequence
# Shared with the video generator: the video IS this list, in this order.

LEAD = 10          # seconds of black before the sync
SYNC_HOLD = 6      # low / high / low, seconds each
HOLD = 7           # seconds per measured patch
HDR_COLOUR_HOLD = 12   # HDR colours: very saturated light can make the meter integrate for several seconds


def patch_hold(p):
    return p.get("hold", HOLD)


def patch_offsets(seq):
    """Start time of each patch, in seconds after the sync."""
    out, t = [], 0.0
    for p in seq["patches"]:
        out.append(t)
        t += patch_hold(p)
    return out
COLOURS = [("RED", (1, 0, 0)), ("GREEN", (0, 1, 0)), ("BLUE", (0, 0, 1)),
           ("CYAN", (0, 1, 1)), ("MAGENTA", (1, 0, 1)), ("YELLOW", (1, 1, 0))]
HUE_ORDER = ["RED", "YELLOW", "GREEN", "CYAN", "BLUE", "MAGENTA"]


def build_sequence(hdr):
    """Each patch: id, kind (white/grey/colour), level (signal 0-1 or PQ 0-1),
    name, and either 'sig' (SDR R'G'B') or 'nits' (HDR linear RGB in the colour's own gamut)."""
    pats = []
    if not hdr:
        sync = {"low": {"sig": (0.30,) * 3}, "high": {"sig": (1.0,) * 3}}
        pats.append({"id": "W0", "kind": "white", "level": 1.0, "name": "100% WHITE", "sig": (1.0,) * 3})
        for p in range(5, 101, 5):
            pats.append({"id": f"G{p}", "kind": "grey", "level": p / 100, "name": f"{p}% GREY",
                         "sig": (p / 100,) * 3})
        for n, c in COLOURS:
            pats.append({"id": n, "kind": "colour", "level": 0.75, "name": f"{n} 75%",
                         "sig": tuple(0.75 * v for v in c), "rgb": c})
        pats.append({"id": "W1", "kind": "white", "level": 1.0, "name": "100% WHITE (drift check)",
                     "sig": (1.0,) * 3})
    else:
        sync = {"low": {"nits": (25.0,) * 3}, "high": {"nits": (200.0,) * 3}}
        pats.append({"id": "W0", "kind": "white", "level": pq_encode(100), "name": "100 nit WHITE",
                     "nits": (100.0,) * 3})
        for p in range(25, 76, 5):
            n = pq_decode(p / 100)
            pats.append({"id": f"G{p}", "kind": "grey", "level": p / 100,
                         "name": f"PQ {p}% GREY ({n:.0f} nits)", "nits": (n,) * 3})
        for name, c in COLOURS:
            pats.append({"id": name, "kind": "colour", "level": None, "name": f"P3 {name}",
                         "nits": tuple(100.0 * v for v in c), "rgb": c, "hold": HDR_COLOUR_HOLD})
        pats.append({"id": "W1", "kind": "white", "level": pq_encode(100),
                     "name": "100 nit WHITE (drift check)", "nits": (100.0,) * 3})
    return {"sync": sync, "patches": pats}


def video_length(hdr):
    return LEAD + 3 * SYNC_HOLD + int(sum(patch_hold(p) for p in build_sequence(hdr)["patches"]))


# ======================================================================== meters

WINDOWS = sys.platform == "win32"


def spotread_env():
    env = dict(os.environ)
    env.setdefault("ARGYLL_EXCLUDE_SERIAL_SCAN",
                   "/dev/cu.Bluetooth-Incoming-Port;/dev/tty.Bluetooth-Incoming-Port;/dev/cu.debug-console")
    return env


def spotread_ports(env=None, timeout=30):
    """The ports `spotread -?` lists, as (number, name). Raises subprocess.TimeoutExpired if it hangs."""
    out = subprocess.run(["spotread", "-?"], capture_output=True, text=True, timeout=timeout,
                         env=env or spotread_env(), errors="replace")
    return re.findall(r"^\s*(\d+)\s*=\s*'([^']+)'", out.stdout + out.stderr, re.M)


def is_meter_port(name):
    """A real instrument, not a serial port: macOS lists those as /dev/..., Windows as COMn."""
    return not name.startswith("/dev/") and not re.match(r"^(\\\\\.\\)?COM\d+$", name, re.I)


def install_hint():
    if WINDOWS:
        return ("Download ArgyllCMS 3.5.0 for Windows 64-bit from argyllcms.com, unzip it (for example to "
                "C:\\Argyll_V3.5.0), add its bin folder to PATH (or set it in 'Start on Windows.cmd'), "
                "then start TV Calibrator again.")
    return "Install it with: brew install argyll-cms"


def no_meter_hint(computer="Mac"):
    if WINDOWS:
        return ("Unplug and replug it straight into this computer (not a hub), and quit Calibrite PROFILER "
                "(right-click its icon in the taskbar's notification area, then Quit).")
    return f"Unplug and replug it straight into the {computer}, and quit Calibrite PROFILER."


def _has_port_arg(args):
    return any(a == "-c" or (a.startswith("-c") and a[2:].isdigit()) for a in args)


SPOTREAD_MISSING = "spotread not found - install ArgyllCMS and make sure it's on your PATH."


class MeterError(RuntimeError):
    """The meter can't be used (spotread missing). A RuntimeError, not SystemExit, so the app's worker
    threads report it; the command line turns it into an exit message (main)."""


class SpotreadMeter:
    """Talks to ArgyllCMS spotread. Keeps one spotread running and asks it for readings
    (fast); if that doesn't work, falls back to starting spotread for every reading (slow)."""

    def __init__(self, extra_args, persistent=True):
        self.args = list(extra_args)
        self.env = spotread_env()
        if WINDOWS and not _has_port_arg(self.args):
            # Windows lists COM ports before USB/HID instruments, so the meter is often not port 1
            # (spotread's default). Pick the first real instrument.
            try:
                meters = [n for n, d in spotread_ports(self.env) if is_meter_port(d)]
                if meters and meters[0] != "1":
                    self.args += ["-c", meters[0]]
            except (OSError, subprocess.TimeoutExpired):
                pass
        self.proc = None
        self.mode = "one-shot"
        if persistent:
            self._start_persistent()

    def _start_persistent(self):
        import threading, queue
        env = dict(self.env)
        env["ARGYLL_NOT_INTERACTIVE"] = "1"
        try:
            self.proc = subprocess.Popen(["spotread", "-x"] + self.args, stdin=subprocess.PIPE,
                                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                         text=True, bufsize=1, env=env)
        except FileNotFoundError:
            raise MeterError(SPOTREAD_MISSING)
        self.q = queue.Queue()

        def pump():
            for line in self.proc.stdout:
                self.q.put(line)
            self.q.put(None)
        threading.Thread(target=pump, daemon=True).start()
        self.mode = "persistent"
        t0, t1, XYZ = self._read_persistent(timeout=25)
        if XYZ is None:
            self.close()
            self.mode = "one-shot"
        import atexit
        atexit.register(self.close)

    def close(self):
        if self.proc and self.proc.poll() is None:
            try:
                self.proc.stdin.write("q\n")
                self.proc.stdin.flush()
                self.proc.wait(timeout=2)
            except Exception:
                self.proc.kill()
        self.proc = None

    def now(self):
        return time.monotonic()

    def sleep(self, s):
        if s > 0:
            time.sleep(s)

    def _read_persistent(self, timeout=20):
        import queue
        while True:                      # drop any late answer from an earlier, timed-out reading
            try:
                if self.q.get_nowait() is None:
                    return self.now(), self.now(), None
            except queue.Empty:
                break
        t0 = self.now()
        try:
            self.proc.stdin.write(" \n")
            self.proc.stdin.flush()
        except Exception:
            return t0, self.now(), None
        while self.now() - t0 < timeout:
            try:
                line = self.q.get(timeout=max(0.05, timeout - (self.now() - t0)))
            except queue.Empty:
                break
            if line is None:
                break
            m = re.search(r"XYZ:\s*([-\d.eE+]+)\s+([-\d.eE+]+)\s+([-\d.eE+]+)", line)
            if m:
                return t0, self.now(), tuple(float(v) for v in m.groups())
        return t0, self.now(), None

    def read(self):
        if self.mode == "persistent" and self.proc and self.proc.poll() is None:
            r = self._read_persistent()
            if r[2] is not None:
                return r
        t0 = self.now()
        try:
            out = subprocess.run(["spotread", "-O", "-x"] + self.args, capture_output=True, text=True,
                                 timeout=20, env=self.env)
        except FileNotFoundError:
            raise MeterError(SPOTREAD_MISSING)
        except subprocess.TimeoutExpired:
            return t0, self.now(), None
        t1 = self.now()
        m = None
        for m in re.finditer(r"XYZ:\s*([-\d.eE+]+)\s+([-\d.eE+]+)\s+([-\d.eE+]+)", out.stdout + out.stderr):
            pass
        return t0, t1, (tuple(float(v) for v in m.groups()) if m else None)


class SimTV:
    """A made-up TV with white-balance, gamma and colour errors that responds to menu
    changes roughly the way a real one does (not exactly like the tool's model)."""

    def __init__(self, hdr, state=None, seed=7):
        rnd = random.Random(seed)
        self.hdr = hdr
        self.s = state or {
            "peak": 160.0 if not hdr else 700.0,
            "gain_err": [rnd.uniform(-0.06, 0.0), rnd.uniform(-0.02, 0.0), rnd.uniform(-0.06, 0.0)],
            "off_err": [rnd.uniform(-0.03, 0.03) for _ in range(3)],
            "gamma": [2.4 + rnd.uniform(-0.12, 0.12) for _ in range(3)],
            "col_err": {n: [rnd.uniform(-0.06, 0.06) for _ in range(3)] for n, _ in COLOURS},
            "ctrl": {},
        }
        self.k_true = 0.0075
        self.gain_step = [0.0045, 0.0040, 0.0055]
        self.off_step = [0.0030, 0.0035, 0.0110]        # blue offset ~3x stronger, like the Micro RGB
        self.p20_step = [0.0070, 0.0050, 0.0040]

    def apply(self, changes):
        for k, v in changes.items():
            self.s["ctrl"][k] = max(-50, min(50, self.s["ctrl"].get(k, 0) + v))   # menus stop at +/-50

    def _limit(self):
        """Power limiter: if the brightest channel at full white would exceed the panel's max,
        everything is scaled down (so raising all gains together does nothing)."""
        m = max(self._chan_raw(c, 1.0) for c in range(3))
        return 1.0 / m if m > 1.0 else 1.0

    def _chan(self, c, level, key_level=None):
        return self._chan_raw(c, level, key_level) * self._limit()

    def _chan_raw(self, c, level, key_level=None):
        C = "RGB"[c]
        kl = level if key_level is None else key_level
        shift = self.s.get("p20_shift", 0.0)
        ctrl = self.s["ctrl"]
        g = ctrl.get(f"gain:{C}", 0)
        o = ctrl.get(f"offset:{C}", 0)
        # 20-point: tent interpolation between 5% points
        p = 0.0
        for key, v in ctrl.items():
            if key.startswith(f"p20:{C}:"):
                lv = int(key.split(":")[2]) / 100
                p += v * max(0.0, 1 - abs(kl - (lv + shift)) / 0.09)      # spills into neighbours
        base = level ** self.s["gamma"][c]
        val = base * (1 + self.s["gain_err"][c] + g * self.gain_step[c]) * (1 + p * self.p20_step[c])
        val += (self.s["off_err"][c] + o * self.off_step[c]) * 0.02 * (1 - level) ** 2 * (1 - math.exp(-30 * level))
        return max(val, 0.0)

    def render(self, patch):
        peak = self.s["peak"]
        if patch.get("sync_level") is not None:
            lvl = patch["sync_level"]
            rgb = [self._chan(c, lvl) * peak for c in range(3)]
            return matvec(M709, rgb)
        if patch["kind"] in ("white", "grey"):
            if self.hdr:
                nits = patch["nits"][0]
                lvl = (nits / peak) ** (1 / 2.4) if nits < peak else 1.0
                rgb = [min(self._chan(c, min(lvl, 1.0), patch["level"]), 1.02) * peak for c in range(3)]
            else:
                rgb = [self._chan(c, patch["level"]) * peak for c in range(3)]
            return matvec(M709, rgb)
        # colour: native primaries a bit wider than target, plus per-colour errors + CMS sliders
        name = patch["id"]
        target_rgb = patch["rgb"]
        lvl = 0.75 if not self.hdr else (100 / peak) ** (1 / 2.4)
        chans = [self._chan(c, lvl) * peak for c in range(3)]
        coef = []
        ctrl = self.s["ctrl"]
        for j, C in enumerate("RGB"):
            cms = ctrl.get(f"cms:{name}:{C}", 0) * self.k_true
            coef.append(max(0.0, target_rgb[j] + self.s["col_err"][name][j] + cms))
        native = GAMUTS["p3"] if self.hdr else ((0.655, 0.325), (0.290, 0.615), (0.150, 0.058))
        M = rgb_to_xyz_matrix(native)
        return matvec(M, [coef[j] * chans[j] for j in range(3)])


class SimMeter:
    """Virtual clock + fake video playback + SimTV. Runs much faster than real time."""

    def __init__(self, tv, hdr, play_at=3.0, slow=0.0, preroll_nits=0.0, ids=None):
        self.tv, self.hdr = tv, hdr
        self.slow, self.preroll = slow, preroll_nits
        self.t = 0.0
        self.play_at = play_at
        self.seq = quick_sequence(hdr, ids) if ids else build_sequence(hdr)
        if isinstance(ids, dict):            # a whole sequence passed in (e.g. the point finder)
            self.seq = ids

    def now(self):
        return self.t

    def sleep(self, s):
        if s > 0:
            self.t += s

    def _showing(self, t):
        v = t - self.play_at
        if v < 0 and self.preroll:
            return {"preroll": True}
        if v < LEAD:
            return None
        v -= LEAD
        if v < 3 * SYNC_HOLD:
            which = ["low", "high", "low"][int(v // SYNC_HOLD)]
            sp = self.seq["sync"][which]
            if self.hdr:
                lvl = (sp["nits"][0] / self.tv.s["peak"]) ** (1 / 2.4)
            else:
                lvl = sp["sig"][0]
            return {"sync_level": lvl}
        v -= 3 * SYNC_HOLD
        pats = self.seq["patches"]
        for p, off in zip(pats, patch_offsets(self.seq)):
            if off <= v < off + patch_hold(p):
                return p
        return None

    def _render(self, p):
        if p and p.get("preroll"):
            return xyY_to_XYZ(*D65, self.preroll)
        return self.tv.render(p) if p else (0.03, 0.03, 0.035)

    def read(self):
        t0 = self.t
        p = self._showing(t0 + 0.3)
        Y = self._render(p)[1]
        dur = 0.9 + self.slow + 0.25 * random.random() + (1.6 / max(Y, 0.05) ** 0.5 if Y < 5 else 0)
        self.t += dur
        # a real meter integrates over a short window, so readings across a change are mixed
        win = 0.5
        samples = [self._render(self._showing(self.t - win + win * (k + 0.5) / 5)) for k in range(5)]
        XYZ = tuple(sum(sm[i] for sm in samples) / 5 for i in range(3))
        common = 1 + random.gauss(0, 0.003)                     # brightness noise
        XYZ = tuple(v * common * (1 + random.gauss(0, 0.0006)) for v in XYZ)   # small colour noise
        return t0, self.t, XYZ


# ======================================================================== sweep

INTEG = 0.25   # spotread integrates in roughly the last quarter second of its run


def _med(v):
    v = sorted(v)
    return v[len(v) // 2]


def _neutral(XYZ, limit=0.05):
    return XYZ[1] > 0 and polar(xyz_to_xy(*XYZ))[0] < limit


class Cancelled(Exception):
    pass


class NoSync(Exception):
    pass


def _emit(on_event, kind, **kw):
    if on_event:
        on_event(kind, **kw)


def find_sync(meter, timeout=240, verbose=True, on_event=None, cancel=None):
    """Wait for the sync and return the time the patch sequence starts.

    The video goes: black, grey, WHITE, grey, then the 100% white first patch.
    We look for WHITE -> grey (~6 s) -> white, all three neutral (not a colour).
    Readings that straddle a change (part bright, part dark) are skipped, and
    anything shown before PLAY is ignored."""
    if verbose:
        print("Waiting for the sync flashes... press PLAY on the video now.", flush=True)
    _emit(on_event, "waiting")
    t_start = meter.now()
    runs = []          # runs of similar brightness: {"ys", "ts", "xyz"}
    last_print = -99
    while meter.now() - t_start < timeout:
        if cancel and cancel.is_set():
            raise Cancelled()
        t0, t1, XYZ = meter.read()
        if XYZ is None:
            if verbose:
                print("  (no reading from the meter)", flush=True)
            _emit(on_event, "reading", Y=None)
            continue
        t, y = t1 - INTEG, max(XYZ[1], 1e-4)
        _emit(on_event, "reading", Y=XYZ[1], XYZ=XYZ)
        if verbose and meter.now() - last_print > 1.5:
            print(f"  seeing {y:9.2f} cd/m²", flush=True)
            last_print = meter.now()
        if runs and 1 / 1.6 < y / _med(runs[-1]["ys"]) < 1.6:
            runs[-1]["ys"].append(y)
            runs[-1]["ts"].append(t)
            runs[-1]["xyz"].append(XYZ)
        else:
            runs.append({"ys": [y], "ts": [t], "xyz": [XYZ]})
        runs = runs[-10:]
        # drop single-reading runs that sit between their neighbours (a reading caught mid-change)
        clean = []
        for i, r in enumerate(runs):
            if 0 < i < len(runs) - 1 and len(r["ys"]) == 1:
                a, b, c = _med(runs[i - 1]["ys"]), r["ys"][0], _med(runs[i + 1]["ys"])
                if min(a, c) < b < max(a, c):
                    continue
            clean.append(r)
        if len(clean) < 3:
            continue
        H1, L, H2 = clean[-3], clean[-2], clean[-1]          # H2 has just started
        h1, l, h2 = _med(H1["ys"]), _med(L["ys"]), _med(H2["ys"])
        # the sync's grey is a mid grey, not black: bright/grey ratio ~4-20, never ~1000
        # (HDR on some TVs squeezes this gap a lot, so the bar is modest; the timing and
        # "all three are neutral" checks below do the real work of ruling out other patches.)
        if not (2.0 <= h1 / l <= 40 and 1.6 <= h2 / l <= 40 and 0.3 < h1 / h2 < 3.5):
            continue
        # all three must be greys/whites, not colour patches
        if not all(_neutral(r["xyz"][len(r["xyz"]) // 2]) for r in (H1, L, H2)):
            continue
        # The grey started somewhere between the last white reading and the first grey one,
        # and ended between the last grey reading and the first white one. With a slow meter
        # those gaps are wide, so work with ranges instead of point estimates.
        fall_lo, fall_hi = H1["ts"][-1], L["ts"][0]
        rise_lo, rise_hi = L["ts"][-1], H2["ts"][0]
        tol = 0.6
        if not (rise_lo - fall_hi - tol <= SYNC_HOLD <= rise_hi - fall_lo + tol):
            continue
        lo = max(rise_lo, fall_lo + SYNC_HOLD)
        hi = min(rise_hi, fall_hi + SYNC_HOLD)
        est = (lo + hi) / 2 if lo <= hi else (rise_lo + rise_hi) / 2
        if verbose:
            print("  Sync found. Measuring...", flush=True)
        _emit(on_event, "synced")
        return est
    raise NoSync("No sync flashes seen. Check the meter is on the centre of the screen and the video "
                 "is playing from the start.")


# The pattern pack (video files for the TV's USB player). Shared by the video generator and the app,
# so the file names the app asks for always match the files.
QUICK_IDS = {False: ["W0", "G20", "G40", "G60", "G80", "G100", "RED", "GREEN", "BLUE", "CYAN", "MAGENTA", "YELLOW"],
             True: ["W0", "G30", "G40", "G50", "G60", "G70", "RED", "GREEN", "BLUE", "CYAN", "MAGENTA", "YELLOW"]}
PACK_FILES = {
    False: [("QUICK", "00 - Quick check (play from the start)"), ("W0", "01 - White (bright end)"),
            ("G30", "02 - Grey 30 (dark end)"), ("G50", "03 - Grey 50 (middle)"), ("G20", "04 - Grey 20"),
            ("G40", "05 - Grey 40"), ("G60", "06 - Grey 60"), ("G80", "07 - Grey 80"), ("RED", "08 - Red"),
            ("GREEN", "09 - Green"), ("BLUE", "10 - Blue"), ("CYAN", "11 - Cyan"), ("MAGENTA", "12 - Magenta"),
            ("YELLOW", "13 - Yellow")],
    True: [("QUICK", "00 - Quick check (play from the start)"), ("G65", "01 - Grey 65 (bright end)"),
           ("G35", "02 - Grey 35 (dark end)"), ("G50", "03 - Grey 50 (middle)"), ("G40", "04 - Grey 40"),
           ("G60", "05 - Grey 60"), ("G70", "06 - Grey 70"), ("RED", "07 - Red"), ("GREEN", "08 - Green"),
           ("BLUE", "09 - Blue"), ("CYAN", "10 - Cyan"), ("MAGENTA", "11 - Magenta"), ("YELLOW", "12 - Yellow")],
}
PACK_PREFIX = {"sdr": "SDR", "hdr10": "HDR10", "hdr10p": "HDR10+", "dv": "DV"}
PACK_FOLDER = {"sdr": "SDR", "hdr10": "HDR10", "hdr10p": "HDR10+", "dv": "Dolby Vision"}


def pack_file(mode, pid):
    """File name (without .mp4) of the pattern-pack video for a patch, e.g. 'SDR 02 - Grey 30 (dark end)'."""
    for key, label in PACK_FILES[mode != "sdr"]:
        if key == pid:
            return f"{PACK_PREFIX[mode]} {label}"
    return None


FINDER_HOLD = 5


def finder_sequence(hdr):
    """The point-finder video: the same sync flashes, then finely spaced greys (5 s each).
    Comparing two passes - before and after nudging some of the TV's multipoint controls - shows
    exactly which grey each TV point really acts on."""
    seq = build_sequence(hdr)
    # starts with white, like the other videos: the sync needs a bright patch right after the flashes
    pats = [{"id": "FW", "kind": "white", "level": 1.0 if not hdr else pq_encode(100), "name": "WHITE",
             **({"sig": (1.0,) * 3} if not hdr else {"nits": (100.0,) * 3}), "hold": FINDER_HOLD}]
    if not hdr:
        for p in range(10, 101, 5):
            pats.append({"id": f"F{p}", "kind": "grey", "level": p / 100, "name": f"{p}% GREY", "sig": (p / 100,) * 3,
                         "hold": FINDER_HOLD})
    else:
        for i in range(0, 25):
            pq = 20 + 2.5 * i
            n = pq_decode(pq / 100)
            pats.append({"id": f"F{int(pq * 10)}", "kind": "grey", "level": pq / 100,
                         "name": f"PQ {pq:g}% GREY ({n:.0f} nits)", "nits": (n,) * 3, "hold": FINDER_HOLD})
    return {"sync": seq["sync"], "patches": pats}


def quick_sequence(hdr, ids):
    """The auto sequence trimmed to some patches (same order, same timing rules) - used by the
    one-minute quick-check videos."""
    seq = build_sequence(hdr)
    return {"sync": seq["sync"], "patches": [p for p in seq["patches"] if p["id"] in ids]}


def sweep(meter, hdr, verbose=True, on_event=None, cancel=None, ids=None, seq=None):
    seq = seq or (quick_sequence(hdr, ids) if ids else build_sequence(hdr))
    t_seq = find_sync(meter, verbose=verbose, on_event=on_event, cancel=cancel)
    offsets = patch_offsets(seq)
    misses = {}
    meas = {}
    n = len(seq["patches"])
    for i, p in enumerate(seq["patches"]):
        start = t_seq + offsets[i]
        end = start + patch_hold(p)
        _emit(on_event, "patch_start", index=i, id=p["id"], name=p["name"])
        while meter.now() < start + 1.2:
            if cancel and cancel.is_set():
                raise Cancelled()
            meter.sleep(min(0.25, start + 1.2 - meter.now()))
        vals = []
        tries = []
        while meter.now() < end - 1.0:
            if cancel and cancel.is_set():
                raise Cancelled()
            t0, t1, XYZ = meter.read()
            tries.append(round(t1 - t0, 1))
            _emit(on_event, "reading", Y=XYZ[1] if XYZ else None)
            if XYZ is None or t0 < start + 0.5:
                continue
            if t1 - INTEG <= end - 0.15:
                vals.append(XYZ)
            # bright patches: one more reading if there's clearly time
            if t1 + (t1 - t0) > end - 0.6:
                break
        if vals:
            meas[p["id"]] = [sum(v[k] for v in vals) / len(vals) for k in range(3)]
            y = meas[p["id"]][1]
            if verbose:
                print(f"  [{i + 1:2d}/{n}] {p['name']:<28} {y:9.3f} cd/m²", flush=True)
        else:
            meas[p["id"]] = None
            misses[p["id"]] = {"name": p["name"], "readings_s": tries, "hold_s": patch_hold(p)}
            if verbose:
                print(f"  [{i + 1:2d}/{n}] {p['name']:<28}   (no reading - too dark or too slow)", flush=True)
        _emit(on_event, "patch", index=i, id=p["id"], name=p["name"], XYZ=meas[p["id"]])
    if misses:
        meas["_missed"] = misses
    return meas


def sweep_direct(meter, hdr, show, on_event=None, cancel=None, settle=1.0, ids=None, seq=None):
    """Measure every patch with the patches shown on demand (iPhone as the pattern source).
    show(patch) puts the patch on the TV and returns once the phone confirms it's on screen.
    No sync flashes needed, and dark patches get as long as the meter needs."""
    seq = seq or build_sequence(hdr)
    pats = [p for p in seq["patches"] if ids is None or p["id"] in ids]
    meas = {}
    _emit(on_event, "synced")
    for i, p in enumerate(pats):
        if cancel and cancel.is_set():
            raise Cancelled()
        _emit(on_event, "patch_start", index=i, id=p["id"], name=p["name"])
        show(p)
        wait_until = meter.now() + (settle * 2 if i == 0 else settle)
        while meter.now() < wait_until:
            if cancel and cancel.is_set():
                raise Cancelled()
            meter.sleep(min(0.2, wait_until - meter.now()))
        vals = []
        for attempt in range(4):
            t0, t1, XYZ = meter.read()
            _emit(on_event, "reading", Y=XYZ[1] if XYZ else None)
            if XYZ is not None:
                vals.append(XYZ)
                if XYZ[1] < 5 or len(vals) >= 2:     # dark: one (slow) reading is enough
                    break
        meas[p["id"]] = [sum(v[k] for v in vals) / len(vals) for k in range(3)] if vals else None
        _emit(on_event, "patch", index=i, id=p["id"], name=p["name"], XYZ=meas[p["id"]])
    return meas


# ======================================================================== analysis

def colour_targets(hdr):
    M = rgb_to_xyz_matrix(GAMUTS["p3" if hdr else "709"])
    out = {}
    for n, c in COLOURS:
        X, Y, Z = matvec(M, c)
        out[n] = {"xy": xyz_to_xy(X, Y, Z), "Yrel": Y}
    return out


def analyse(meas, hdr):
    seq = build_sequence(hdr)
    W = meas.get("W0")
    rep = {"greys": [], "colours": [], "white": W, "warnings": []}
    if not W:
        rep["warnings"].append("No 100% white reading - can't analyse this round.")
        return rep
    Yw = W[1]
    if meas.get("W1"):
        drift = (meas["W1"][1] / Yw - 1) * 100
        rep["drift"] = drift
        if abs(drift) > 3:
            rep["warnings"].append(f"Brightness drifted {drift:+.1f}% during the run (TV not warmed up, "
                                   "or auto-brightness/energy saving still on).")
    for pid, m in (meas.get("_missed") or {}).items():
        took = max(m["readings_s"]) if m["readings_s"] else None
        rep["warnings"].append(
            f"{m['name']}: no reading. " + (f"The meter's readings took up to {took:.1f}s on it, against "
            f"{m['hold_s']}s on screen." if took else "The meter didn't return anything while it was on screen."))
    prev_Y = 0
    for p in seq["patches"]:
        if p["kind"] != "grey":
            continue
        XYZ = meas.get(p["id"])
        if not XYZ:
            continue
        rgb = xyz_to_rgb709(XYZ)
        Y = XYZ[1]
        if Y < prev_Y * 0.95 and not hdr:
            rep["warnings"].append(f"{p['name']} is darker than the step before it - timing may be off, "
                                   "or the TV is clipping/tone-mapping.")
        prev_Y = Y
        if hdr:
            tgt = pq_decode(p["level"])
        else:
            tgt = Yw * p["level"] ** 2.4
        clipped = bool(hdr and tgt > 200 and Y < 0.85 * tgt)
        rep["greys"].append({"id": p["id"], "level": p["level"], "name": p["name"], "XYZ": XYZ, "Y": Y,
                             "targetY": tgt, "bal": [c / Y for c in rgb], "rgb": rgb, "de": white_de(XYZ),
                             "clipped": clipped})
    clip = [g for g in rep["greys"] if g["clipped"]]
    if clip:
        rep["peak"] = max(g["Y"] for g in rep["greys"])
        rep["warnings"].append(f"The TV tops out around {rep['peak']:.0f} cd/m² - steps above that "
                               f"({', '.join(g['name'].split(' (')[0] for g in clip)}) are left out of the corrections.")
    tg = colour_targets(hdr)
    # HDR: judge colours against the TV's own 100-nit white patch, so an overall HDR brightness
    # boost (reported separately as PQ tracking) doesn't swamp the colour scores.
    whiteY = (Yw if Yw > 0 else 100.0) if hdr else Yw
    lvl = 1.0 if hdr else 0.75 ** 2.4
    Wn = xyY_to_XYZ(*D65, whiteY)
    for n, _ in COLOURS:
        XYZ = meas.get(n)
        if not XYZ:
            continue
        t = tg[n]
        expY = whiteY * t["Yrel"] * lvl
        T = xyY_to_XYZ(*t["xy"], expY)
        x, y = xyz_to_xy(*XYZ)
        rm, am = polar((x, y))
        rt, at = polar(t["xy"])
        rep["colours"].append({"name": n, "XYZ": XYZ, "target": T, "xy": (x, y), "txy": t["xy"],
                               "sat": (rm / rt - 1) * 100, "hue": wrap(am - at),
                               "lum": (XYZ[1] / expY - 1) * 100,
                               "de": de2000(xyz_to_lab(XYZ, Wn), xyz_to_lab(T, Wn))})
    return rep


# ======================================================================== learning & prescribing
#
# Green is the anchor: the tool only moves Red and Blue, and measures them RELATIVE
# to green (R/G and B/G). That's how calibrators usually work, and it keeps working
# even if the TV quietly rescales overall brightness (power limiting, peak boost),
# which is what broke the previous version on the Micro RGB.
#
# Learning takes two short rounds:
#   after round 1:  Red and Blue GAIN  -10   (down, so the TV can't be at a ceiling)
#   after round 2:  gains back, Red and Blue OFFSET +10
# From round 3 on: corrections, capped at +/-8 clicks per control per round, and the
# tool rescales its numbers every round from what actually happened.

STATE_VERSION = 3
CH = ("R", "B")               # channels the tool adjusts; green stays put
CH_IDX = {"R": 0, "B": 2}
TWO_MAX = 8                   # max clicks per 2-point control per round
P20_MAX = 4                   # max clicks per 20-point control per round (x2 once learned)
P20_GUESS = 0.006             # first guess: one 20-point click ~ 0.6% on that step
REL_MIN, REL_MAX = 0.002, 0.04   # sane range for "% change per click" (gain / offset)


def gain_probe(cms):
    ch = {"gain:R": -10, "gain:B": -10}
    if cms == "samsung":
        ch["cms:YELLOW:B"] = 10
    return ch


def offset_probe():
    return {"gain:R": 10, "gain:B": 10, "offset:R": 10, "offset:B": 10}


def grey_map(rep):
    return {g["id"]: g for g in rep["greys"]}


def colour_basis(meas, hdr):
    """The measured red, green and blue patches; a primary that couldn't be measured is replaced
    by its ideal target (so one missing reading doesn't switch off all colour corrections)."""
    W = meas.get("W0")
    if not W:
        return None
    tg = colour_targets(hdr)
    lvl = 1.0 if hdr else 0.75 ** 2.4
    P = []
    for c in ("RED", "GREEN", "BLUE"):
        if meas.get(c):
            P.append(meas[c])
        else:
            P.append(xyY_to_XYZ(*tg[c]["xy"], W[1] * tg[c]["Yrel"] * lvl))
    return [[P[c][r] for c in range(3)] for r in range(3)]


def colour_coefs(meas, name, hdr=False):
    """Express a colour as amounts of the red, green and blue patches."""
    M = colour_basis(meas, hdr)
    C = meas.get(name)
    if not M or not C:
        return None
    return solve3(M, C)


def qvals(rep):
    """Per usable grey step: (R/G, B/G) balance ratios, and the step's level."""
    q, lv = {}, {}
    for g in rep["greys"]:
        if g.get("clipped") or g["bal"][1] <= 0:
            continue
        q[g["id"]] = {"R": g["bal"][0] / g["bal"][1], "B": g["bal"][2] / g["bal"][1]}
        lv[g["id"]] = g["level"]
    return q, lv


def _qchange(rep_a, rep_b):
    qa, lv = qvals(rep_a)
    qb, _ = qvals(rep_b)
    return {k: {C: qb[k][C] / qa[k][C] - 1 for C in CH} for k in qa if k in qb}, lv


def _smooth(d, levels, lo, hi):
    keys = sorted(d, key=lambda k: levels[k])
    vals = [d[k] for k in keys]
    out = {}
    for i, k in enumerate(keys):
        win = sorted(vals[max(0, i - 1):i + 2])
        out[k] = min(max(win[len(win) // 2], lo), hi)
    return out


def round_kind(r):
    return r.get("kind") if r.get("applied") else None


def learn(state, hdr):
    rounds = state["rounds"]
    sens = state.setdefault("sens", {})
    if len(rounds) < 2:
        return
    prev, cur = rounds[-2], rounds[-1]
    kind = round_kind(prev)
    if not kind:
        return
    A, B = analyse(prev["meas"], hdr), analyse(cur["meas"], hdr)
    dq, lv = _qchange(A, B)
    applied = prev["applied"]
    notes = state.setdefault("notes", [])

    if kind == "probe_gain":
        sens["rel_g"] = {}
        for C in CH:
            d = {k: v[C] / applied[f"gain:{C}"] for k, v in dq.items()}
            top = [d[k] for k in d if lv[k] >= 0.7]
            if top and sum(top) / len(top) < REL_MIN:
                notes.append(f"{C}-Gain barely changed the picture during learning - using a safe default.")
            sens["rel_g"][C] = _smooth(d, lv, REL_MIN * 0.5, REL_MAX)
        if "cms:YELLOW:B" in applied:
            ca, cb = colour_coefs(prev["meas"], "YELLOW", hdr), colour_coefs(cur["meas"], "YELLOW", hdr)
            if ca and cb:
                sens["k"] = min(max((cb[2] - ca[2]) / applied["cms:YELLOW:B"], 0.002), 0.03)
        return

    if kind == "probe_offset":
        base = rounds[-3] if len(rounds) >= 3 else prev
        dq0, lv0 = _qchange(analyse(base["meas"], hdr), B)
        sens["rel_o"] = {}
        for C in CH:
            d = {k: v[C] / 10 for k, v in dq0.items()}
            sens["rel_o"][C] = _smooth(d, lv0, 0.0, REL_MAX)
        sens["go_scale"] = {C: 1.0 for C in CH}
        sens["rel20"] = {C: P20_GUESS for C in CH}
        sens["k_scale"] = 1.0
        sens["learned"] = True
        return

    if not sens.get("learned"):
        return
    for C in CH:
        num = den = n20 = d20 = 0.0
        g, o = applied.get(f"gain:{C}", 0), applied.get(f"offset:{C}", 0)
        for k, v in dq.items():
            lvl = round(lv[k] * 100)
            pred = (g * sens["rel_g"][C].get(k, REL_MIN) + o * sens["rel_o"][C].get(k, 0)) * sens["go_scale"][C]
            c20 = applied.get(f"p20:{C}:{lvl}", 0)
            if c20 == 0 and abs(pred) > 0.004:
                num += pred * v[C]
                den += pred * pred
            if c20:
                n20 += c20 * (v[C] - pred)
                d20 += c20 * c20
                tr = sens.setdefault("p20_track", {}).setdefault(f"{C}:{lvl}", [0, 0.0])
                tr[0] += c20
                tr[1] += v[C] - pred
        if den > 0:
            sens["go_scale"][C] = min(max(sens["go_scale"][C] * num / den, 0.3), 3.0)
        if d20 >= 4:
            est = n20 / d20
            if est > 0.0005:
                sens["rel20"][C] = min(max(0.5 * sens["rel20"][C] + 0.5 * est, 0.002), 0.03)
                sens["p20_learned"] = True
    # A 20-point control that's had 10+ clicks in total but barely moved its own step is acting
    # somewhere else (the TV's points don't sit where the app thinks), so stop using 20-point.
    dead = [key for key, (clicks, resp) in sens.get("p20_track", {}).items()
            if abs(clicks) >= 10 and abs(resp) < 0.25 * abs(clicks) * sens["rel20"][key[0]]]
    if dead:
        sens["p20_dead"] = sorted(set(sens.get("p20_dead", []) + dead))
    # colour menus with hue / saturation / brightness sliders: learn what one click does
    hsl_learn(sens, prev, cur, applied, hdr)
    if "k" in sens:
        n = d = 0.0
        for name, _ in COLOURS:
            ca, cb = colour_coefs(prev["meas"], name, hdr), colour_coefs(cur["meas"], name, hdr)
            if not ca or not cb:
                continue
            for j, C in enumerate("RGB"):
                clicks = applied.get(f"cms:{name}:{C}", 0)
                if clicks and name not in ("RED", "GREEN", "BLUE"):
                    pred = clicks * sens["k"] * sens["k_scale"]
                    n += pred * (cb[j] - ca[j])
                    d += pred * pred
        if d > 0:
            sens["k_scale"] = min(max(sens["k_scale"] * n / d, 0.3), 3.0)


HSL_AXES = ("hue", "sat", "lum")
HSL_GUESS = {"hue": 1.0, "sat": 1.0, "lum": 1.0}   # first guess: 1 click ~ 1 degree / 1 %


def _colour_errors(meas, hdr):
    return {c["name"]: c for c in analyse(meas, hdr)["colours"]}


def hsl_learn(sens, prev, cur, applied, hdr):
    """For each axis, compare how far the colours actually moved with the clicks made.
    The sign is learned too, so it doesn't matter which way the TV's sliders run."""
    if not any(k.startswith("hsl:") for k in applied):
        return
    a, b = _colour_errors(prev["meas"], hdr), _colour_errors(cur["meas"], hdr)
    k = sens.setdefault("hsl_k", dict(HSL_GUESS))
    for axis in HSL_AXES:
        n = d = 0.0
        for name in a:
            clicks = applied.get(f"hsl:{name}:{axis}", 0)
            if clicks and name in b:
                moved = b[name][axis] - a[name][axis]
                n += clicks * moved
                d += clicks * clicks
        if d >= 4:
            est = n / d
            if abs(est) > 0.1:
                old = k[axis]
                blended = 0.5 * old + 0.5 * est if old * est > 0 else est   # sign flip: trust the data
                k[axis] = max(-6.0, min(6.0, blended))
        sens.setdefault("hsl_learned", []).append(axis) if d >= 4 else None


def hsl_prescribe(sens, rep):
    """Clicks on hue / saturation / brightness per colour, damped and capped."""
    k = sens.setdefault("hsl_k", dict(HSL_GUESS))
    learned = set(sens.get("hsl_learned", []))
    out = {}
    limits = {"hue": 2.0, "sat": 3.0, "lum": 5.0}
    for c in rep["colours"]:
        if c["de"] < 2.0:
            continue
        for axis in HSL_AXES:
            err = c[axis]
            if abs(err) < limits[axis]:
                continue
            cap = 6 if axis in learned else 3
            raw = -0.7 * err / k[axis]
            clicks = int(round(raw)) or (1 if raw > 0 else -1)
            out[f"hsl:{c['name']}:{axis}"] = max(-cap, min(cap, clicks))
    return out


def lstsq2(rows):
    """rows: (a, b, y, w) -> minimise sum w (a x + b z + y)^2 (+ a small preference for small moves)."""
    saa = sum(w * a * a for a, b, y, w in rows)
    sab = sum(w * a * b for a, b, y, w in rows)
    sbb = sum(w * b * b for a, b, y, w in rows)
    say = sum(w * a * y for a, b, y, w in rows)
    sby = sum(w * b * y for a, b, y, w in rows)
    lam = 0.02 * (saa + sbb) / 2 + 1e-12
    det = (saa + lam) * (sbb + lam) - sab * sab
    x = (-say * (sbb + lam) + sby * sab) / det
    z = (-sby * (saa + lam) + say * sab) / det
    return x, z


def prescribe(state, rep, hdr, points, cms, prev_rep=None):
    sens = state["sens"]
    changes = {}
    q, lv = qvals(rep)
    min_lv = 0.30 if hdr else 0.10
    usable = [k for k in q if lv[k] >= min_lv - 1e-9]
    # ---- 2-point on Red and Blue, relative to green
    twopt = {}
    for C in CH:
        rows = []
        for k in usable:
            rg = sens["rel_g"][C].get(k, REL_MIN) * sens["go_scale"][C]
            ro = sens["rel_o"][C].get(k, 0) * sens["go_scale"][C]
            w = 0.5 if lv[k] < 0.2 else (1.5 if lv[k] <= (0.40 if not hdr else 0.45) else 1.0)
            rows.append((q[k][C] * rg, q[k][C] * ro, q[k][C] - 1, w))
        gx, ox = lstsq2(rows) if rows else (0, 0)
        twopt[C] = (max(-TWO_MAX, min(TWO_MAX, 0.8 * gx)), max(-TWO_MAX, min(TWO_MAX, 0.8 * ox)))
    max2 = max(abs(v) for pair in twopt.values() for v in pair)

    def rms(after):
        tot = n = 0
        for k in usable:
            for C in CH:
                v = q[k][C]
                if after:
                    gx, ox = twopt[C]
                    v *= 1 + (gx * sens["rel_g"][C].get(k, REL_MIN) + ox * sens["rel_o"][C].get(k, 0)) \
                        * sens["go_scale"][C]
                tot += (v - 1) ** 2
                n += 1
        return math.sqrt(tot / n) if n else 0
    helps = rms(True) < 0.75 * rms(False)
    stage = "2pt" if ((max2 >= 1.5 and helps) or not points) else "20pt"
    prev_stage = state["rounds"][-2].get("stage") if len(state["rounds"]) >= 2 else None
    if (points and stage == "2pt" and prev_stage == "2pt" and prev_rep
            and grey_avg(rep, hdr) > 0.85 * grey_avg(prev_rep, hdr)):
        stage = "20pt"
    if stage == "2pt":
        for C, (gx, ox) in twopt.items():
            if round(gx):
                changes[f"gain:{C}"] = int(round(gx))
            if round(ox):
                changes[f"offset:{C}"] = int(round(ox))
    elif points and len(sens.get("p20_dead", [])) >= 2:
        stage = "2pt"          # 20-point isn't landing where expected on this TV: don't keep guessing
    elif points:
        cap = P20_MAX * (2 if sens.get("p20_learned") else 1)
        dead = set(sens.get("p20_dead", []))
        for k in q:
            lvl = round(lv[k] * 100)
            if lvl < (25 if hdr else 10):
                continue
            if points == 11 and lvl % 10:
                continue
            for C in CH:
                if f"{C}:{lvl}" in dead:
                    continue
                err = q[k][C] - 1
                if abs(err) > 0.02:
                    raw = -err / (sens["rel20"][C] * q[k][C]) * 0.8
                    clicks = int(round(raw)) or (1 if raw > 0 else -1)
                    changes[f"p20:{C}:{lvl}"] = max(-cap, min(cap, clicks))
    # ---- colours
    notes = []
    if cms == "samsung":
        if "k" not in sens:
            sens["k"] = 0.004      # never learned (e.g. a primary wasn't measured): start cautious
            sens.setdefault("k_scale", 1.0)
            notes.append("Colour corrections are starting from an estimate for this TV; they'll sharpen "
                         "with each round.")
        k = sens["k"] * sens.get("k_scale", 1)
        meas = state["rounds"][-1]["meas"]
        missing = [c.title() for c in ("RED", "GREEN", "BLUE") if not meas.get(c)]
        if missing:
            notes.append(f"{', '.join(missing)} couldn't be measured this round, so its own slider isn't "
                         "adjusted and the other colours use its ideal value instead.")
        M = colour_basis(meas, hdr)
        if M:
            for col in rep["colours"]:
                if col["de"] < 2.0:
                    continue
                cur = solve3(M, col["XYZ"])
                tgt = solve3(M, col["target"])
                for j, C in enumerate("RGB"):
                    clicks = int(round(0.6 * (tgt[j] - cur[j]) / k))
                    if clicks:
                        changes[f"cms:{col['name']}:{C}"] = max(-8, min(8, clicks))
    elif cms == "hsl":
        hsl = hsl_prescribe(sens, rep)
        changes.update(hsl)
        if hsl and not sens.get("hsl_learned"):
            notes.append("Colour changes start small: the app learns from the next round how much one click of "
                         "hue, saturation and brightness moves on this TV, and which way.")
    if len(sens.get("p20_dead", [])) >= 2:
        notes.append("The 20-point controls on this TV don't move the steps the app expects (for example, many "
                     "clicks at one point barely changed that step). The app sticks to 2-point here; fine-tune "
                     "by hand with Live meter if you need to.")
    return stage, changes, notes


# ======================================================================== presentation

def bar(err_pct, width=20, span=10.0):
    pos = int(round(err_pct / span * (width / 2)))
    pos = max(-width // 2, min(width // 2, pos))
    cells = [" "] * (width + 1)
    mid = width // 2
    cells[mid] = "|"
    lo, hi = sorted((mid, mid + pos))
    for i in range(lo, hi + 1):
        if i != mid:
            cells[i] = "#"
    return "".join(cells)


def colour_avg(rep):
    c = [x["de"] for x in rep["colours"]]
    return sum(c) / len(c) if c else 0.0


def grey_avg(rep, hdr):
    g = [x["de"] for x in rep["greys"] if (hdr or x["level"] >= 0.099) and not x.get("clipped")]
    return sum(g) / len(g) if g else float("nan")


def grey_max(rep, hdr):
    g = [x["de"] for x in rep["greys"] if (hdr or x["level"] >= 0.099) and not x.get("clipped")]
    return max(g) if g else float("nan")


def print_report(rep, hdr, cms):
    print("\n" + "=" * 78)
    print("GREYSCALE  (RGB balance: 100 = on D65.  White-point dE: <1 invisible, <3 good)")
    print("=" * 78)
    hdr_lbl = "target" if not hdr else "target(nits)"
    print(f"{'step':<22}{'cd/m²':>9}{hdr_lbl:>13}{'R':>7}{'G':>7}{'B':>7}{'dE':>7}")
    for g in rep["greys"]:
        b = [v * 100 for v in g["bal"]]
        de = g["de"]
        flag = " (clipped)" if g.get("clipped") else ("" if de < 1 else (" ~" if de < 3 else " !"))
        print(f"{g['name'][:21]:<22}{g['Y']:9.2f}{g['targetY']:13.2f}{b[0]:7.1f}{b[1]:7.1f}{b[2]:7.1f}{de:7.2f}{flag}")
    if rep["greys"]:
        avg = grey_avg(rep, hdr)
        print(f"{'average grey dE (10% and up)' if not hdr else 'average grey dE':<58}{avg:7.2f}")
        if not hdr:
            gam = [math.log(g["Y"] / rep["white"][1]) / math.log(g["level"])
                   for g in rep["greys"] if 0.1 <= g["level"] <= 0.9 and g["Y"] > 0]
            if gam:
                gm = sum(gam) / len(gam)
                print(f"\nAverage gamma {gm:.2f} (target 2.40)." +
                      ("" if abs(gm - 2.4) < 0.08 else
                       f"  -> {'lower' if gm > 2.4 else 'raise'} the TV's Gamma setting one step and re-run."))
        else:
            trk = [(g["Y"] / g["targetY"] - 1) * 100 for g in rep["greys"] if g["targetY"] < 300]
            if trk:
                t = sum(trk) / len(trk)
                print(f"\nPQ tracking below 300 nits: {t:+.0f}% on average." +
                      ("" if abs(t) < 8 else
                       f"  -> {'lower' if t > 0 else 'raise'} the ST.2084 / HDR brightness setting one step."))
    print("\n" + "=" * 78)
    print(f"COLOURS  ({'P3 at 100-nit white' if hdr else 'Rec.709 at 75%'})")
    print("=" * 78)
    print(f"{'colour':<10}{'dE':>7}{'saturation':>13}{'hue':>9}{'brightness':>13}")
    for c in rep["colours"]:
        hue_dir = ""
        if abs(c["hue"]) >= 1:
            i = HUE_ORDER.index(c["name"])
            nb = HUE_ORDER[(i + 1) % 6] if c["hue"] > 0 else HUE_ORDER[i - 1]
            tg = colour_targets(hdr)
            # direction check using actual neighbour angles
            a_t = polar(tg[c["name"]]["xy"])[1]
            for cand in (HUE_ORDER[(i + 1) % 6], HUE_ORDER[i - 1]):
                if (wrap(polar(tg[cand]["xy"])[1] - a_t) > 0) == (c["hue"] > 0):
                    nb = cand
            hue_dir = f" ->{nb.lower()}"
        flag = "" if c["de"] < 3 else " !"
        print(f"{c['name']:<10}{c['de']:7.2f}{c['sat']:+12.1f}%{c['hue']:+8.1f}°{c['lum']:+12.1f}%{hue_dir}{flag}")
    if rep["colours"]:
        print(f"{'average':<10}{sum(c['de'] for c in rep['colours']) / len(rep['colours']):7.2f}")
    if cms == "hsl":
        print("\n(Colour menu with hue/saturation/brightness: move each colour opposite to the numbers above.)")
    if "drift" in rep:
        print(f"\nBrightness drift start->end: {rep['drift']:+.1f}%")
    for w in rep["warnings"]:
        print("! " + w)


USE_COLOUR = sys.stdout.isatty() and os.environ.get("NO_COLOR") is None
CH_COL = {"R": "\033[91m", "G": "\033[92m", "B": "\033[94m"}
BOLD, DIM, YEL, CYAN, RST = "\033[1m", "\033[2m", "\033[93m", "\033[96m", "\033[0m"
CMS_ORDER = ["RED", "GREEN", "BLUE", "YELLOW", "CYAN", "MAGENTA"]


def paint(text, code):
    return f"{code}{text}{RST}" if USE_COLOUR else text


def _cell(v, C, width=8):
    if not v:
        return paint(f"{'·':>{width}}", DIM)
    txt = f"{v:+d}"
    code = CH_COL[C] + (BOLD if abs(v) >= 5 else "")
    return paint(f"{txt:>{width}}", code)


def _header(first, width_first):
    cols = "".join(paint(f"{n:>8}", CH_COL[C]) for C, n in (("R", "Red"), ("G", "Green"), ("B", "Blue")))
    return f"    {first:<{width_first}}{cols}"


def print_changes(changes, hdr, points, title, banner=CYAN):
    """Menu-by-menu tables in the order the TV shows them: R, G, B across."""
    line = "=" * 78
    print("\n" + paint(line, banner))
    print(paint(title, BOLD + banner))
    print(paint(line, banner))
    if not changes:
        print("  Nothing to change.")
        return
    n_menus = 0
    # 2-point
    two = {k: v for k, v in changes.items() if k.split(":")[0] in ("gain", "offset")}
    if two:
        n_menus += 1
        print("\n  " + paint("WHITE BALANCE  >  2 Point", BOLD))
        print(_header("", 14))
        for kind, label in (("offset", "Offset"), ("gain", "Gain")):
            if any(k.startswith(kind + ":") for k in two):
                print(f"    {label:<14}" + "".join(_cell(two.get(f"{kind}:{C}", 0), C) for C in "RGB"))
    # 20-point
    p20 = {k: v for k, v in changes.items() if k.startswith("p20:")}
    if p20:
        n_menus += 1
        name = "20 Point" if points == 20 else "Multipoint"
        print("\n  " + paint(f"WHITE BALANCE  >  {name}", BOLD))
        print(_header("Interval", 14))
        for lv in sorted({int(k.split(":")[2]) for k in p20}):
            lab = f"{lv // 5:>2}  ({lv}%)" if points == 20 else f"{lv}%"
            print(f"    {lab:<14}" + "".join(_cell(p20.get(f"p20:{C}:{lv}", 0), C) for C in "RGB"))
    # hue / saturation / brightness colour menu
    hsl = {k: v for k, v in changes.items() if k.startswith("hsl:")}
    if hsl:
        n_menus += 1
        print("\n  " + paint("COLOR MANAGEMENT SYSTEM", BOLD))
        print(f"    {'Colour':<14}{'Hue':>8}{'Sat':>8}{'Bright':>8}")
        for col in CMS_ORDER:
            if any(k.startswith(f"hsl:{col}:") for k in hsl):
                cells = "".join(f"{(f'{v:+d}' if v else '·'):>8}" for v in
                                (hsl.get(f"hsl:{col}:{a}", 0) for a in ("hue", "sat", "lum")))
                print(f"    {col.title():<14}{cells}")
    # colour space
    cms = {k: v for k, v in changes.items() if k.startswith("cms:")}
    if cms:
        n_menus += 1
        print("\n  " + paint("COLOR SPACE  >  Custom", BOLD))
        print(_header("Colour", 14))
        for col in CMS_ORDER:
            if any(k.startswith(f"cms:{col}:") for k in cms):
                print(f"    {col.title():<14}" + "".join(_cell(cms.get(f"cms:{col}:{C}", 0), C) for C in "RGB"))
    n = sum(1 for v in changes.values() if v)
    print(f"\n  {n} change{'s' if n != 1 else ''} in {n_menus} menu{'s' if n_menus != 1 else ''}.  "
          "Values are clicks FROM YOUR CURRENT SETTINGS (+3 = three clicks up, · = leave alone).")
    if any(abs(v) >= 10 for k, v in changes.items() if not k.startswith(("gain", "offset"))):
        print("  If a slider hits its limit, leave it at the limit - the next round adjusts.")



# ======================================================================== self-test

def check(spotread_args, hdr):
    """Step-by-step test: is spotread installed, is the meter detected, does it read,
    how fast, and does the sync lock onto the video."""
    import shutil
    ok = lambda m: print(f"  \033[32mPASS\033[0m  {m}", flush=True)
    bad = lambda m: print(f"  \033[31mFAIL\033[0m  {m}", flush=True)
    warn = lambda m: print(f"  \033[33mNOTE\033[0m  {m}", flush=True)

    print("1. ArgyllCMS installed")
    path = shutil.which("spotread")
    if not path:
        bad("spotread not found. Install ArgyllCMS (brew install argyll-cms)." if not WINDOWS else
            "spotread not found. " + install_hint())
        return
    ok(f"spotread found at {path}")

    print("2. Meter detected")
    try:
        ports = spotread_ports()
    except subprocess.TimeoutExpired:
        bad("spotread froze while looking for meters (the Bluetooth-port problem?)." + (
            " Set ARGYLL_EXCLUDE_SERIAL_SCAN to your Bluetooth COM ports, e.g. COM5;COM6." if WINDOWS else ""))
        return
    meters = [(n, d) for n, d in ports if is_meter_port(d)]
    for n, d in ports:
        print(f"        port {n}: {d}")
    if not meters:
        bad("No meter in the list. Unplug/replug it, plug straight into the Mac, quit Calibrite PROFILER."
            if not WINDOWS else "No meter in the list. " + no_meter_hint())
        return
    if WINDOWS and not _has_port_arg(spotread_args):
        ok(f"meter found: {meters[0][1]}" + ("" if meters[0][0] == "1" else f"  (port {meters[0][0]}, used automatically)"))
    else:
        ok(f"meter found: {meters[0][1]}" + ("" if meters[0][0] == "1" else
           f"  (not port 1 - add  --spotread-args \"-y n -c {meters[0][0]}\")"))

    print("3. Taking 3 readings (meter on the TV, any bright picture)")
    meter = SpotreadMeter(spotread_args)
    if meter.mode == "persistent":
        ok("fast mode: one spotread stays running between readings")
    else:
        warn("fast mode didn't start - using one spotread per reading (slower)")
    times, ys = [], []
    for i in range(3):
        t0, t1, XYZ = meter.read()
        if XYZ is None:
            bad(f"reading {i + 1} returned nothing within 20 s.")
            return
        times.append(t1 - t0)
        ys.append(XYZ[1])
        print(f"        reading {i + 1}: {XYZ[1]:9.2f} cd/m²  in {t1 - t0:.1f}s")
    ok("the meter reads")
    avg = sum(times) / len(times)
    if avg <= 2.5:
        ok(f"speed {avg:.1f}s per reading - fine for the auto video")
    elif avg <= 4.0:
        warn(f"speed {avg:.1f}s per reading - workable, dark patches may be skipped")
    else:
        bad(f"speed {avg:.1f}s per reading - too slow for the auto video's {HOLD}s patches")
    if max(ys) < 1:
        warn("very low readings - is the meter facing the screen, on a bright area?")

    print("4. Sync test")
    vid = "tvcal_auto_HDR.mp4" if hdr else "tvcal_auto_SDR.mp4"
    try:
        input(f"   Put the meter on the centre, get {vid} ready at 0:00, then press Enter here and press PLAY. ")
    except EOFError:
        return
    seq = build_sequence(hdr)["patches"]
    try:
        t_seq = find_sync(meter, timeout=120)
    except NoSync:
        bad("no sync seen within 2 minutes - was the video playing, with the meter on the centre?")
        return
    ok("sync found")
    got = []
    for i, p in enumerate(seq[:3]):
        start = t_seq + patch_offsets(build_sequence(hdr))[i]
        meter.sleep(start + 1.2 - meter.now())
        t0, t1, XYZ = meter.read()
        y = XYZ[1] if XYZ else float("nan")
        got.append(y)
        print(f"        expected {p['name']:<22} measured {y:9.3f} cd/m²")
    if got[0] > 5 * max(got[1], got[2], 1e-3) and got[1] <= got[2] * 1.2:
        ok("timing locked: white, then the two darkest greys, as expected. You're ready - stop the video.")
    else:
        bad("readings don't match the pattern order - paste this output to Claude.")



# ======================================================================== state

def state_path(tv, hdr):
    d = os.path.expanduser("~/tvcal")
    os.makedirs(d, exist_ok=True)
    safe = re.sub(r"[^A-Za-z0-9_-]+", "_", tv)
    return os.path.join(d, f"{safe}-{'hdr' if hdr else 'sdr'}.json")


def load_state(path):
    if os.path.exists(path):
        with open(path) as f:
            st = json.load(f)
        if st.get("version", 1) < STATE_VERSION:
            sys.exit("This TV's history is from an older version of the tool. Start it over with --reset.\n"
                     "The TV's settings can stay as they are - the tool works from wherever the TV is now.")
        return st
    return {"version": STATE_VERSION, "rounds": [], "sens": {}}


# Windows can't swap in a new file while another thread has the old one open, so on Windows the app's
# own readers and writers of its JSON files take turns (tvcal_app reads under this lock too).
IO_LOCK = threading.RLock()


def io_lock():
    return IO_LOCK if WINDOWS else contextlib.nullcontext()


def write_json_atomic(path, data):
    """Write to a temporary file, then swap it in: a reader never sees a half-written file.
    The temporary name is per thread (the app saves from several). On Windows the swap is also retried
    for a moment: a virus scanner or indexer can briefly hold either file open."""
    tmp = f"{path}.{os.getpid()}.{threading.get_ident()}.tmp"
    with open(tmp, "w") as f:
        json.dump(data, f, indent=1)
    if not WINDOWS:
        os.replace(tmp, path)
        return
    with IO_LOCK:
        for attempt in range(60):
            try:
                os.replace(tmp, path)
                return
            except PermissionError:
                if attempt == 59:
                    try:
                        os.remove(tmp)
                    except OSError:
                        pass
                    raise
                time.sleep(0.05)


def save_state(path, st):
    write_json_atomic(path, st)


# ======================================================================== main

def next_step(st):
    """What should happen after this round: 'probe_gain', 'probe_offset' or 'correct'."""
    done = [round_kind(r) for r in st["rounds"][:-1]]
    if "probe_gain" not in done:
        return "probe_gain"
    if "probe_offset" not in done:
        return "probe_offset"
    return "correct"


def describe_setup(setup):
    src = {"video": "the video file", "iphone": "the iPhone showing the patches"}.get(setup.get("source"), "?")
    met = {"hl": "the Display Plus HL", "iphone": "the iPhone camera"}.get(setup.get("meter"), "?")
    return f"{src} and {met}"


def plan_round(st, hdr, points, cms):
    """After a measurement has been appended to st['rounds']: learn, analyse and decide
    what to ask for next. No printing, no input - shared by the terminal tool and the app."""
    # Rounds are only comparable if they used the same pattern source and the same meter:
    # the iPhone sends full-range video and its camera reads differently from the HL, so
    # mixing them would make the app "learn" differences that the TV menus didn't cause.
    cur = st["rounds"][-1]
    prev = st["rounds"][-2] if len(st["rounds"]) >= 2 else None
    if prev and prev.get("applied") and prev.get("setup") and cur.get("setup") \
            and prev["setup"] != cur["setup"]:
        rep = analyse(cur["meas"], hdr)
        return {"rep": rep, "changes": {}, "kind": None, "title": "", "done": False, "banner": "undo",
                "notes": [], "discard": True,
                "message": f"This round was measured with {describe_setup(cur['setup'])}, but the last one used "
                           f"{describe_setup(prev['setup'])}. Run it again with {describe_setup(prev['setup'])} "
                           "so the app can compare like with like. (This round wasn't kept.)"}
    # A big jump in white means something other than the calibration controls changed
    # (brightness, picture mode, energy saving, light sensor). Comparing across that would
    # make the app chase a moving target, so don't keep the round.
    if prev and prev.get("applied") and cur["meas"].get("W0") and prev["meas"].get("W0"):
        ratio = cur["meas"]["W0"][1] / max(prev["meas"]["W0"][1], 1e-6)
        if ratio > 1.25 or ratio < 0.8:
            rep = analyse(cur["meas"], hdr)
            return {"rep": rep, "changes": {}, "kind": None, "title": "", "done": False, "banner": "undo",
                    "notes": [], "discard": True,
                    "message": f"White is now {cur['meas']['W0'][1]:.0f} cd/m², but it was "
                               f"{prev['meas']['W0'][1]:.0f} cd/m² last round. The white balance and colour "
                               "controls can't do that, so another setting changed: picture mode, brightness, "
                               "peak brightness, energy saving or the light sensor. Put it back and run the round "
                               "again, or start over if you meant to change it. (This round wasn't kept.)"}
    learn(st, hdr)
    rep = analyse(st["rounds"][-1]["meas"], hdr)
    plan = {"rep": rep, "changes": {}, "kind": None, "title": "", "done": False, "message": "",
            "banner": "correct", "notes": list(st.get("notes", []))}
    if not rep["greys"]:
        plan["message"] = "No usable greyscale readings this round."
        return plan
    step = next_step(st)
    if step == "probe_gain":
        plan.update(kind="probe_gain", changes=gain_probe(cms), banner="learn",
                    title="Learning round 1 of 2 - make exactly these changes, then run again",
                    message="Test nudges: the tool measures how Red and Blue GAIN respond on this TV "
                            "(green stays put).")
        return plan
    if step == "probe_offset":
        plan.update(kind="probe_offset", changes=offset_probe(), banner="learn",
                    title="Learning round 2 of 2 - make exactly these changes, then run again",
                    message="This puts the gains back where they were and nudges the OFFSETS instead.")
        return plan
    prev_rep = analyse(st["rounds"][-2]["meas"], hdr) if len(st["rounds"]) >= 2 else None
    prev = st["rounds"][-2] if len(st["rounds"]) >= 2 else {}
    if (prev_rep and round_kind(prev) == "correct"
            and (grey_avg(rep, hdr) > max(2.0, 1.25 * grey_avg(prev_rep, hdr) + 0.3)
                 or colour_avg(rep) > max(2.5, 1.25 * colour_avg(prev_rep) + 0.5))):
        plan.update(kind="undo", changes={k: -v for k, v in prev["applied"].items()}, banner="undo",
                    title="Last round made things worse - undo it with these, then run again")
        return plan
    stage, changes, cnotes = prescribe(st, rep, hdr, points, cms, prev_rep)
    plan["notes"] = plan["notes"] + cnotes
    st["rounds"][-1]["stage"] = stage
    # Two 20-point rounds in a row that didn't improve the greyscale: we're chasing noise.
    fine = [i for i, r in enumerate(st["rounds"][:-1]) if r.get("stage") == "20pt" and round_kind(r) == "correct"]
    if stage == "20pt" and len(fine) >= 2:
        before = analyse(st["rounds"][fine[-2]]["meas"], hdr)
        if grey_avg(rep, hdr) > 0.9 * grey_avg(before, hdr):
            changes = {k: v for k, v in changes.items() if not k.startswith("p20:")}
            plan["notes"].append(f"The last 20-point rounds didn't improve the greyscale (dE "
                                 f"{grey_avg(before, hdr):.1f} -> {grey_avg(rep, hdr):.1f}), so the app has stopped "
                                 "adjusting it: further changes would be chasing measurement noise or the TV's own "
                                 "processing.")
    # Same for colours: two rounds of colour changes that didn't help -> stop changing colours.
    col_rounds = [i for i, r in enumerate(st["rounds"][:-1]) if round_kind(r) == "correct"
                  and any(k.startswith(("cms:", "hsl:")) for k in (r.get("applied") or {}))]
    if len(col_rounds) >= 2 and any(k.startswith(("cms:", "hsl:")) for k in changes):
        before = analyse(st["rounds"][col_rounds[-2]]["meas"], hdr)
        if colour_avg(rep) > 0.9 * colour_avg(before):
            changes = {k: v for k, v in changes.items() if not k.startswith(("cms:", "hsl:"))}
            plan["notes"].append(f"The last colour changes didn't improve the colours (dE {colour_avg(before):.1f} -> "
                                 f"{colour_avg(rep):.1f}), so the app has stopped adjusting them.")
    avg_g, max_g = grey_avg(rep, hdr), grey_max(rep, hdr)
    avg_c = colour_avg(rep)
    max_c = max((c["de"] for c in rep["colours"]), default=0)
    good = avg_g < 1.2 and max_g < 2.5 and avg_c < 2.0 and max_c < 3.0
    if good or not changes:
        plan["done"] = True
        plan["message"] = ("Done - greyscale and colours are on target." if good else
                           f"That's as close as this TV's controls get (grey dE {avg_g:.1f}, colour dE {avg_c:.1f}).")
        return plan
    title = ("Corrections - 2-point white balance" if stage == "2pt" else
             "Corrections - fine-tune with the 20-point controls")
    if any(k.startswith("cms:") for k in changes):
        title += " + colours"
    plan.update(kind="correct", changes=changes, title=title, stage=stage)
    return plan


def record_round(st, plan, ok, sim_tv=None):
    if ok and plan["changes"]:
        st["rounds"][-1]["applied"] = plan["changes"]
        st["rounds"][-1]["kind"] = plan["kind"]
        if plan["kind"] == "undo":
            for C in CH:
                st["sens"]["rel20"][C] = min(st["sens"]["rel20"][C] * 2, 0.03)
                st["sens"]["go_scale"][C] = min(st["sens"]["go_scale"][C] * 2, 3.0)
        if sim_tv:
            sim_tv.apply(plan["changes"])


def run_round(st, meter, hdr, points, cms, auto_yes=False, sim_tv=None):
    rnum = len(st["rounds"]) + 1
    print(f"\n### Round {rnum} " + ("(HDR)" if hdr else "(SDR)"))
    try:
        meas = sweep(meter, hdr)
    except NoSync as e:
        sys.exit(str(e) + "  Test with:  python3 tvcal.py --tv x --check")
    st["rounds"].append({"time": datetime.now().isoformat(timespec="seconds"), "meas": meas,
                         "applied": None, "kind": None})
    plan = plan_round(st, hdr, points, cms)
    print_report(plan["rep"], hdr, cms)
    if plan["done"]:
        print("\n" + paint(plan["message"], BOLD + "\033[92m"))
        return st, {}
    if not plan["changes"]:
        print(plan["message"])
        return st, {}
    banner = {"learn": YEL, "undo": "\033[91m"}.get(plan["banner"], CYAN)
    print_changes(plan["changes"], hdr, points, plan["title"].upper() if plan["kind"] == "undo" else plan["title"],
                  banner=banner)
    if plan["message"]:
        print("  " + plan["message"])
    for n in plan["notes"]:
        print("  " + paint("Note: " + n, YEL))
    if auto_yes:
        ok = True
    else:
        ans = input("\nMake the changes, then type y + Enter (or just Enter to skip recording them): ")
        ok = ans.strip().lower().startswith("y")
    record_round(st, plan, ok, sim_tv)
    print("Recorded. Replay the video from the start and run the tool again for the next round." if ok else
          "Not recorded - the next round will treat the TV as unchanged.")
    return st, (plan["changes"] if ok else {})


def main():
    ap = argparse.ArgumentParser(description="Measure-then-prescribe TV calibration synced to the pattern video.")
    ap.add_argument("--tv", required=True, help="a name for this TV, e.g. q60t, micro-rgb, tcl")
    ap.add_argument("--hdr", action="store_true", help="use the HDR10 video / HDR targets")
    ap.add_argument("--points", type=int, choices=(20, 11, 0), default=20,
                    help="multipoint white balance on this TV: 20 (Samsung), 11, or 0 for none")
    ap.add_argument("--cms", choices=("samsung", "hsl", "off"), default="samsung",
                    help="colour menu type: samsung = R/G/B sliders per colour, hsl = hue/sat/lum, off")
    ap.add_argument("--spotread-args", default="-y n", help='passed to spotread (default "-y n")')
    ap.add_argument("--check", action="store_true", help="test the meter, its speed and the video sync, then exit")
    ap.add_argument("--report", action="store_true", help="re-print the last round and exit")
    ap.add_argument("--reset", action="store_true", help="forget this TV's history")
    ap.add_argument("--sim", action="store_true", help="simulated TV + meter (no hardware)")
    ap.add_argument("--rounds", type=int, default=1, help="with --sim: run this many rounds")
    a = ap.parse_args()

    if a.check:
        check(shlex.split(a.spotread_args), a.hdr)
        return
    path = state_path(a.tv + ("-sim" if a.sim else ""), a.hdr)
    if a.reset and os.path.exists(path):
        os.remove(path)
        print(f"History cleared ({path}).")
        if not a.sim:
            return
    st = load_state(path)

    if a.report:
        if not st["rounds"]:
            sys.exit("No rounds yet.")
        print_report(analyse(st["rounds"][-1]["meas"], a.hdr), a.hdr, a.cms)
        return

    if a.sim:
        tv = SimTV(a.hdr, st.get("sim"))
        random.seed(1)
        for _ in range(a.rounds):
            meter = SimMeter(tv, a.hdr)
            st, ch = run_round(st, meter, a.hdr, a.points, a.cms, auto_yes=True, sim_tv=tv)
            st["sim"] = tv.s
            save_state(path, st)
            if not ch and st.get("sens", {}).get("learned"):
                break
        return

    print(f"TV: {a.tv}   mode: {'HDR10' if a.hdr else 'SDR'}   history: {path}")
    print(f"Video: {'tvcal_auto_HDR.mp4' if a.hdr else 'tvcal_auto_SDR.mp4'} "
          f"({video_length(a.hdr) // 60}:{video_length(a.hdr) % 60:02d} long). Rest the meter on the centre.")
    meter = SpotreadMeter(shlex.split(a.spotread_args))
    print(f"Meter mode: {meter.mode}" + ("  (fast)" if meter.mode == "persistent" else "  (slower fallback)"))
    # quick timing check
    t0, t1, XYZ = meter.read()
    if XYZ is None:
        sys.exit("The meter didn't return a reading. Check it with: spotread -O -x -y n")
    if t1 - t0 > 4.5:
        print(f"! The meter took {t1 - t0:.1f}s for one reading - dark patches may be missed.")
    try:
        st, _ = run_round(st, meter, a.hdr, a.points, a.cms)
    except KeyboardInterrupt:
        print("\nStopped - this round wasn't saved.")
        return
    save_state(path, st)


def _main_cli():
    try:
        main()
    except MeterError as e:
        sys.exit(str(e))


if __name__ == "__main__":
    _main_cli()
