#!/usr/bin/env python3
"""Builds "Lab - Camera training (HDR10).mp4": the lab video for training iPhone camera profiles on HDR
patches (brighter and more saturated than SDR). Lab use only, not for YouTube.

    python3 tools/make_hdr_training.py out_dir/

HDR10: 1920x1080, 24p, HEVC Main 10, BT.2020 primaries, PQ, mastering metadata (P3 D65, 1000 nits).
Each patch is a band across the full width of the screen (rows 180-900 of 1080), on black, so the
meter and several phones can each aim at their own spot. 10 s black, 46 patches of 15 s, 60 s black
(the Mac finishes 45 s after the last patch). Exact code values don't matter for training: the meter
and the phones measure the same light at the same time.
"""
import math
import os
import subprocess
import sys

W, H, BAND = 1920, 1080, (180, 900)
HOLD, LEAD, TAIL, FPS = 15, 10, 60, 24

# linear RGB -> XYZ for each set of primaries (D65)
def rgb2xyz(prim):
    (xr, yr), (xg, yg), (xb, yb) = prim
    xw, yw = 0.3127, 0.3290
    P = [[xr / yr, xg / yg, xb / yb], [1, 1, 1], [(1 - xr - yr) / yr, (1 - xg - yg) / yg, (1 - xb - yb) / yb]]
    Wt = [xw / yw, 1, (1 - xw - yw) / yw]
    S = solve(P, Wt)
    return [[P[i][j] * S[j] for j in range(3)] for i in range(3)]


def solve(A, b):
    import copy
    M = [row[:] + [b[i]] for i, row in enumerate(copy.deepcopy(A))]
    for c in range(3):
        p = max(range(c, 3), key=lambda r: abs(M[r][c]))
        M[c], M[p] = M[p], M[c]
        for r in range(3):
            if r != c:
                f = M[r][c] / M[c][c]
                M[r] = [a - f * bb for a, bb in zip(M[r], M[c])]
    return [M[i][3] / M[i][i] for i in range(3)]


def inv(A):
    cols = [solve(A, e) for e in ([1, 0, 0], [0, 1, 0], [0, 0, 1])]
    return [[cols[j][i] for j in range(3)] for i in range(3)]


mv = lambda M, v: [sum(M[i][j] * v[j] for j in range(3)) for i in range(3)]
REC709 = ((0.640, 0.330), (0.300, 0.600), (0.150, 0.060))
P3 = ((0.680, 0.320), (0.265, 0.690), (0.150, 0.060))
REC2020 = ((0.708, 0.292), (0.170, 0.797), (0.131, 0.046))
TO2020 = {k: [[sum(inv(rgb2xyz(REC2020))[i][m] * rgb2xyz(p)[m][j] for m in range(3)) for j in range(3)] for i in range(3)]
          for k, p in (("709", REC709), ("p3", P3))}


def pq(nits):
    m1, m2, c1, c2, c3 = 0.1593017578125, 78.84375, 0.8359375, 18.8515625, 18.6875
    y = max(nits, 0) / 10000
    return ((c1 + c2 * y ** m1) / (1 + c3 * y ** m1)) ** m2


def patch(space, lin, white_nits):
    """`lin`: linear RGB 0..1 in `space` (709 or p3); 1.0 = white_nits. -> (name-free) BT.2020 nits."""
    return [max(v, 0) * white_nits for v in mv(TO2020[space], lin)]


def xyY(nits2020):
    X = mv(rgb2xyz(REC2020), nits2020)
    s = sum(X) or 1e-9
    return X[1], X[0] / s, X[1] / s


g = lambda v: max(v, 0) ** 2.4                     # SDR signal -> linear, for the everyday colours
PRIM = [("Red", (1, 0, 0)), ("Green", (0, 1, 0)), ("Blue", (0, 0, 1)), ("Cyan", (0, 1, 1)), ("Magenta", (1, 0, 1)),
        ("Yellow", (1, 1, 0))]
# No greys below 10 nits: TVs often show HDR well below its nominal level, and the dimmest patches then
# fall under what the meter and the phones read reliably.
patches = [(f"Grey {n} nits", [n, n, n]) for n in (10, 15, 25, 40, 60, 100, 150, 250, 400, 600, 1000)]
patches += [(f"P3 {n} (100-nit white)", patch("p3", c, 100)) for n, c in PRIM]
patches += [(f"P3 {n} (300-nit white)", patch("p3", c, 300)) for n, c in PRIM]
patches += [(f"709 {n} (100-nit white)", patch("709", c, 100)) for n, c in PRIM]
tints = [("Warm", (1.15, 1, 1)), ("Green", (1, 1.15, 1)), ("Blue", (1, 1, 1.15)), ("Cyan", (0.9, 1.05, 1.05)),
         ("Magenta", (1.05, 0.9, 1.05)), ("Yellow", (1.05, 1.05, 0.9))]
patches += [(f"{n} grey 100 nits", patch("709", c, 100)) for n, c in tints]
patches += [(f"{n} grey 10 nits", patch("709", c, 10)) for n, c in tints[:3]]
mixed = [("Orange", (1, 0.5, 0)), ("Light skin", (0.9, 0.7, 0.6)), ("Dark skin", (0.55, 0.38, 0.28)),
         ("Purple", (0.5, 0, 1)), ("Sky", (0.4, 0.7, 1)), ("Lime", (0.6, 1, 0.2)), ("Pink", (1, 0.4, 0.7)),
         ("Teal", (0, 0.55, 0.5))]
patches += [(n, patch("709", [g(v) for v in c], 150)) for n, c in mixed]

# Greys and colours alternate, so each patch clearly differs from the one before (the Mac spots a new
# patch when the brightness changes by more than 8% or the colour by more than 0.006 in x, y).
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


def frame(nits):
    """One 1920x1080 yuv420p10le frame: the band in this colour (BT.2020 non-constant luminance, limited
    range), black elsewhere."""
    R, G, B = (pq(v) for v in nits)
    Y = 0.2627 * R + 0.6780 * G + 0.0593 * B
    cb, cr = (B - Y) / 1.8814, (R - Y) / 1.4746
    y10, cb10, cr10 = (round(64 + 876 * Y), round(512 + 896 * cb), round(512 + 896 * cr))
    clip = lambda v: min(max(v, 4), 1019)
    y10, cb10, cr10 = clip(y10), clip(cb10), clip(cr10)
    le = lambda v: bytes((v & 255, v >> 8))
    top, bot = BAND
    yrow_b, yrow_c = le(64) * W, le(y10) * W
    Yp = yrow_b * top + yrow_c * (bot - top) + yrow_b * (H - bot)
    def chroma(v):
        rb, rc = le(512) * (W // 2), le(v) * (W // 2)
        return rb * (top // 2) + rc * ((bot - top) // 2) + rb * ((H - bot) // 2)
    return Yp + chroma(cb10) + chroma(cr10)


X265 = ("colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:range=limited:hdr10=1:repeat-headers=1:"
        "master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,50):"
        "max-cll=1000,400:keyint=48:min-keyint=48:log-level=error")


def segment(path, nits, seconds, work):
    raw = os.path.join(work, "f.yuv")
    with open(raw, "wb") as f:
        f.write(frame(nits))
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "yuv420p10le", "-s", f"{W}x{H}",
                    "-r", str(FPS),
                    # Tag the raw input too: recent ffmpeg treats untagged rawvideo as BT.601 and converts
                    # it, which shifts the colour patches' codes (greys are unaffected).
                    "-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc",
                    "-color_range", "tv",
                    "-stream_loop", "-1", "-i", raw, "-t", str(seconds),
                    "-c:v", "libx265", "-preset", "ultrafast", "-crf", "12", "-pix_fmt", "yuv420p10le",
                    "-x265-params", X265, "-tag:v", "hvc1",
                    "-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc",
                    "-color_range", "tv", path], check=True)


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "."
    work = os.path.join(out_dir, "hdr-training-work")
    os.makedirs(work, exist_ok=True)
    segs = [("black", [0, 0, 0], LEAD)] + [(n, c, HOLD) for n, c in order] + [("black end", [0, 0, 0], TAIL)]
    names = []
    for i, (n, c, s) in enumerate(segs):
        p = os.path.join(work, f"s{i:02d}.mp4")
        segment(p, c, s, work)
        names.append(p)
        print(f"{i + 1}/{len(segs)} {n}", flush=True)
    lst = os.path.join(work, "list.txt")
    with open(lst, "w") as f:
        f.writelines(f"file '{p}'\n" for p in names)
    out = os.path.join(out_dir, "Lab - Camera training (HDR10).mp4")
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy",
                    "-movflags", "+faststart", out], check=True)
    with open(os.path.join(out_dir, "Lab - Camera training (HDR10) patches.txt"), "w") as f:
        t = LEAD
        for i, (n, c) in enumerate(order):
            Y, x, y = xyY(c)
            f.write(f"{t // 60}:{t % 60:02d}  {i + 1:2d}/{len(order)}  {n:<28} {Y:7.1f} nits  x {x:.4f}  y {y:.4f}\n")
            t += HOLD
    print(out)


if __name__ == "__main__":
    main()
