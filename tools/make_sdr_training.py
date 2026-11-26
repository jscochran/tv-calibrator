#!/usr/bin/env python3
"""Builds "Lab - Camera training (SDR).mp4": the lab video for training phone camera profiles on SDR
patches. It replaces the export from the Mac app's SDR video studio. Lab use only, not for YouTube.

    python3 tools/make_sdr_training.py out_dir/             # the video + a patch list
    python3 tools/make_sdr_training.py out_dir/ --list      # only the patch list, no video
    python3 tools/make_sdr_training.py out_dir/ --keep-work # keep the per-patch segments

SDR: 1920x1080, 24p, H.264 High, BT.709 primaries, transfer and matrix, limited (video) range,
8-bit 4:2:0, no audio. Laid out like the HDR10 one (tools/make_hdr_training.py): each patch is a band
across the full width of the screen (rows 180-900 of 1080), on black, so the meter and several phones
can each aim at their own spot. 10 s black, 46 patches of 12 s, 60 s black (the lab tool finishes 45 s
after the last patch). No sync flashes: the lab tool follows the video with the meter, taking a reading
whenever it sees a new, steady patch. Exact code values don't matter for training (the meter and the
phones measure the same light at the same time), but each patch is written with exact Y'CbCr codes.

The 46 patches mirror the HDR10 set in SDR signal values (R'G'B' 0-1, Rec.709):
  11 greys           15-100% signal (none darker: on a dim TV they fall under 0.5 cd/m2, the meter floor)
  18 colours         red, green, blue, cyan, magenta, yellow at 100%, 75% and 50% signal
   9 tinted greys    six tints at 75% grey, three at 40%
   8 mixed colours   orange, light skin, dark skin, purple, sky, lime, pink, teal
"""
import argparse
import math
import os
import shutil
import subprocess
import sys

W, H, BAND = 1920, 1080, (180, 900)
HOLD, LEAD, TAIL, FPS = 12, 10, 60, 24
# HOLD 12 s as the original design. Each patch needs ~2-4 meter readings plus the phones' answers; if a run's
# "Training/last run.log" or skips show "the picture changed while the phones measured" on dim patches
# (Grey 15%, Blue 50%, Grey 20%) with Android phones, raise it to 15 like the HDR10 video.
NAME = "Lab - Camera training (SDR)"


# linear RGB -> XYZ for Rec.709 primaries (D65), to describe each patch and keep neighbours distinct
def rgb2xyz(prim):
    (xr, yr), (xg, yg), (xb, yb) = prim
    xw, yw = 0.3127, 0.3290
    P = [[xr / yr, xg / yg, xb / yb], [1, 1, 1], [(1 - xr - yr) / yr, (1 - xg - yg) / yg, (1 - xb - yb) / yb]]
    Wt = [xw / yw, 1, (1 - xw - yw) / yw]
    S = solve(P, Wt)
    return [[P[i][j] * S[j] for j in range(3)] for i in range(3)]


def solve(A, b):
    M = [row[:] + [b[i]] for i, row in enumerate(A)]
    for c in range(3):
        p = max(range(c, 3), key=lambda r: abs(M[r][c]))
        M[c], M[p] = M[p], M[c]
        for r in range(3):
            if r != c:
                f = M[r][c] / M[c][c]
                M[r] = [a - f * bb for a, bb in zip(M[r], M[c])]
    return [M[i][3] / M[i][i] for i in range(3)]


mv = lambda M, v: [sum(M[i][j] * v[j] for j in range(3)) for i in range(3)]
REC709 = ((0.640, 0.330), (0.300, 0.600), (0.150, 0.060))
M709 = rgb2xyz(REC709)

lin = lambda v: max(v, 0) ** 2.4                    # SDR signal -> linear (BT.1886, black at 0)
enc = lambda v: min(max(v, 0), 1) ** (1 / 2.4)      # linear -> SDR signal, clipped to 100%


def xyY(sig):
    """Signal R'G'B' -> (Y as % of the TV's white, x, y)."""
    X = mv(M709, [lin(v) * 100 for v in sig])
    s = sum(X) or 1e-9
    return X[1], X[0] / s, X[1] / s


PRIM = [("Red", (1, 0, 0)), ("Green", (0, 1, 0)), ("Blue", (0, 0, 1)), ("Cyan", (0, 1, 1)), ("Magenta", (1, 0, 1)),
        ("Yellow", (1, 1, 0))]
patches = [(f"Grey {p}%", (p / 100,) * 3) for p in (15, 20, 25, 30, 40, 50, 60, 70, 80, 90, 100)]
for level in (100, 75, 50):
    patches += [(f"{n} {level}%", tuple(level / 100 * v for v in c)) for n, c in PRIM]
# The same tints as the HDR10 video (multipliers on linear light), on a 75% and a 40% grey.
tints = [("Warm", (1.15, 1, 1)), ("Green", (1, 1.15, 1)), ("Blue", (1, 1, 1.15)), ("Cyan", (0.9, 1.05, 1.05)),
         ("Magenta", (1.05, 0.9, 1.05)), ("Yellow", (1.05, 1.05, 0.9))]
tinted = lambda level, t: tuple(enc(m * lin(level)) for m in t)
patches += [(f"{n} grey 75%", tinted(0.75, t)) for n, t in tints]
patches += [(f"{n} grey 40%", tinted(0.40, t)) for n, t in tints[:3]]
mixed = [("Orange", (1, 0.5, 0)), ("Light skin", (0.9, 0.7, 0.6)), ("Dark skin", (0.55, 0.38, 0.28)),
         ("Purple", (0.5, 0, 1)), ("Sky", (0.4, 0.7, 1)), ("Lime", (0.6, 1, 0.2)), ("Pink", (1, 0.4, 0.7)),
         ("Teal", (0, 0.55, 0.5))]
patches += mixed
assert len(patches) == 46, len(patches)


# Greys and colours alternate, so each patch clearly differs from the one before (the lab tool spots a
# new patch when the brightness changes by more than 8% or the colour by more than 0.006 in x, y).
def differs(a, b):
    Ya, xa, ya = xyY(a[1]); Yb, xb, yb = xyY(b[1])
    return abs(Yb / Ya - 1) > 0.12 or math.dist((xa, ya), (xb, yb)) > 0.012


greys = [p for p in patches if p[0].startswith("Grey ")]
rest = [p for p in patches if p not in greys]
order = []
while greys or rest:
    want = greys if (len(order) % 4 == 3 and greys) or not rest else rest
    other = rest if want is greys else greys
    pick = next((p for p in want + other if not order or differs(order[-1], p)), None)
    assert pick, "no patch differs enough from " + order[-1][0]
    (greys if pick in greys else rest).remove(pick)
    order.append(pick)
assert len(order) == len(patches)


def codes(sig):
    """Signal R'G'B' 0-1 -> BT.709 limited-range 8-bit Y', Cb, Cr codes."""
    R, G, B = (min(max(v, 0), 1) for v in sig)
    Y = 0.2126 * R + 0.7152 * G + 0.0722 * B
    cb, cr = (B - Y) / 1.8556, (R - Y) / 1.5748
    clip = lambda v: min(max(v, 1), 254)
    return clip(round(16 + 219 * Y)), clip(round(128 + 224 * cb)), clip(round(128 + 224 * cr))


def frame(sig):
    """One 1920x1080 yuv420p frame: the band in this colour, black (code 16) elsewhere."""
    y8, cb8, cr8 = codes(sig)
    top, bot = BAND
    Yp = bytes([16]) * (W * top) + bytes([y8]) * (W * (bot - top)) + bytes([16]) * (W * (H - bot))
    def chroma(v):
        cw = W // 2
        return bytes([128]) * (cw * (top // 2)) + bytes([v]) * (cw * ((bot - top) // 2)) + \
            bytes([128]) * (cw * ((H - bot) // 2))
    return Yp + chroma(cb8) + chroma(cr8)


X264 = "colorprim=bt709:transfer=bt709:colormatrix=bt709:fullrange=off:keyint=48:min-keyint=48:log-level=error"


def segment(path, sig, seconds, work):
    raw = os.path.join(work, "f.yuv")
    with open(raw, "wb") as f:
        f.write(frame(sig))
    # The raw input carries the same colour tags as the output. Untagged, newer ffmpeg (seen with 9.0)
    # assumes BT.601 and converts the frames to BT.709, shifting every colour patch's codes.
    tags = ["-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709", "-color_range", "tv"]
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", f"{W}x{H}",
                    "-framerate", str(FPS), *tags, "-stream_loop", "-1", "-i", raw, "-t", str(seconds),
                    "-c:v", "libx264", "-preset", "medium", "-crf", "12", "-profile:v", "high", "-level", "4.0",
                    "-pix_fmt", "yuv420p", "-x264-params", X264, *tags, "-an", path], check=True)


def write_list(out_dir):
    path = os.path.join(out_dir, f"{NAME} patches.txt")
    with open(path, "w", encoding="utf-8") as f:
        f.write(f"{NAME}: {len(order)} patches of {HOLD} s after {LEAD} s of black, then {TAIL} s of black.\n")
        f.write("Y is % of the TV's white (gamma 2.4); codes are BT.709 limited-range 8-bit Y' Cb Cr.\n\n")
        t = LEAD
        for i, (n, c) in enumerate(order):
            Y, x, y = xyY(c)
            yc, cb, cr = codes(c)
            rgb = " ".join(f"{v:.3f}" for v in c)
            f.write(f"{t // 60}:{t % 60:02d}  {i + 1:2d}/{len(order)}  {n:<20} R'G'B' {rgb}  "
                    f"Y {Y:6.2f}%  x {x:.4f}  y {y:.4f}  codes {yc:3d} {cb:3d} {cr:3d}\n")
            t += HOLD
    return path


def main():
    ap = argparse.ArgumentParser(description=f'Make "{NAME}.mp4" for training phone camera profiles.')
    ap.add_argument("out_dir", nargs="?", default=".")
    ap.add_argument("--list", action="store_true", help="only write the patch list")
    ap.add_argument("--keep-work", action="store_true", help="keep the per-patch segments")
    a = ap.parse_args()
    os.makedirs(a.out_dir, exist_ok=True)
    lst_txt = write_list(a.out_dir)
    if a.list:
        print(lst_txt)
        return
    if not shutil.which("ffmpeg"):
        sys.exit("ffmpeg isn't on PATH")
    work = os.path.join(a.out_dir, "sdr-training-work")
    os.makedirs(work, exist_ok=True)
    segs = [("black", (0, 0, 0), LEAD)] + [(n, c, HOLD) for n, c in order] + [("black end", (0, 0, 0), TAIL)]
    names = []
    for i, (n, c, s) in enumerate(segs):
        name = f"s{i:02d}.mp4"
        segment(os.path.join(work, name), c, s, work)
        names.append(name)
        print(f"{i + 1}/{len(segs)} {n}", flush=True)
    lst = os.path.join(work, "list.txt")
    with open(lst, "w", encoding="utf-8") as f:          # relative names: no quoting trouble with paths
        f.writelines(f"file '{p}'\n" for p in names)
    out = os.path.join(a.out_dir, f"{NAME}.mp4")
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy",
                    "-movflags", "+faststart", out], check=True)
    if not a.keep_work:
        shutil.rmtree(work, ignore_errors=True)
    print(out, f"({LEAD + HOLD * len(order) + TAIL} s)")
    print(lst_txt)


if __name__ == "__main__":
    main()
