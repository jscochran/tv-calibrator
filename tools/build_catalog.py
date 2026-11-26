#!/usr/bin/env python3
"""
Builds the camera-profile server for the TV Calibrator iPhone and Android apps.

Each phone model is trained with the lab tool (mac-lab/, with the Display Plus HL, on as many TVs as
you can) and the phone's Lab build, then exported (Camera profiles > Export). This script adds those
exported files to the repo's server/ folder, which the push worker publishes:

    server/
      index.json                      which models are supported, with checksums (both platforms)
      profiles/iPhone18,1-v1.json     one file per model and version
      profiles/samsung_e2q-v1.json    an Android model, "samsung/e2q" (only the file name is
                                      sanitised; the profile's "model" field keeps the "/")
      patterns.json                   where the pattern videos are (edited by hand)

It also writes every published profile into the apps themselves, so the next build works offline and
only downloads profiles that are newer than it. Each app gets only its own platform's models:

    iPhone   TV Calibrator/Resources/builtin-profiles.json        (--builtin)
    Android  android/app/src/main/assets/builtin-profiles.json    (--android-builtin)

A model whose identifier starts with "iPhone" (e.g. iPhone18,1) is an iPhone model; any other
(Build.MANUFACTURER/Build.DEVICE, e.g. samsung/e2q) is an Android model, unless the export has a
"platform" field ("ios" or "android"). Each built-in file also names its standard profile: the one
phones of that platform without their own profile fall back on.

Usage (from the repo folder):
    python3 tools/build_catalog.py ~/tvcal/Camera\\ profiles/*.json
    python3 tools/build_catalog.py ~/tvcal/camera_profiles.json --standard iPhone18,1 --name "iPhone18,1=iPhone 18 Pro"
    python tools\\build_catalog.py "%USERPROFILE%\\tvcal\\camera_profiles.json" --name "samsung/e2q=Galaxy S24+" --standard samsung/e2q

Inputs are files exported from the lab tool (Camera profiles > Export) or its whole
~/tvcal/camera_profiles.json. A model whose matrix changed gets a new version number, and phones
download it the next time they check. Models not passed in are kept. Then publish it with
`npx wrangler deploy` in push-worker/ and commit. See docs/ADDING-AN-IPHONE.md and android/README.md.
Self-test: python tools/tests/test_build_catalog.py
"""
import argparse
import hashlib
import json
import os
import re
import sys
from datetime import datetime, timezone

FORMAT = 1
MIN_APP_FORMAT = 1
PLATFORMS = ("ios", "android")
EXAMPLE_NAME = {"ios": "iPhone 18 Pro", "android": "Galaxy S24+"}


def platform_of(model, explicit=None):
    """"ios" or "android": the explicit value when there is one, else iPhone identifiers are iOS."""
    if explicit:
        return explicit
    return "ios" if model.startswith("iPhone") else "android"


def safe_name(model):
    """The model as a file name: "iPhone18,1" stays as it is, "samsung/e2q" becomes "samsung_e2q"."""
    return re.sub(r"[^A-Za-z0-9,._-]+", "_", model)


def load_exports(path):
    """One exported profile, or every trained model in the lab tool's camera_profiles.json."""
    with open(path, encoding="utf-8") as f:
        d = json.load(f)
    if isinstance(d, dict) and d.get("tvcal_camera_profile"):
        return [convert(path, d)]
    if isinstance(d, dict) and d and all(isinstance(v, dict) and "sessions" in v for v in d.values()):
        trained = [convert(path, {"model": m, **v}) for m, v in d.items() if v.get("M")]
        skipped = [m for m, v in d.items() if not v.get("M")]
        if skipped:
            print(f"! {path}: skipping untrained model(s): {', '.join(skipped)}")
        return trained
    sys.exit(f"{path}: not a TV Calibrator camera profile export or camera_profiles.json")


def convert(path, d):
    if not d.get("model"):
        sys.exit(f"{path}: profile has no model identifier")
    if d.get("platform") is not None and d["platform"] not in PLATFORMS:
        sys.exit(f"{path}: platform must be one of {', '.join(PLATFORMS)}, not {d['platform']!r}")
    M = d.get("M")
    if not (isinstance(M, list) and len(M) == 3 and all(isinstance(r, list) and len(r) == 3 for r in M)):
        sys.exit(f"{path}: no trained matrix - train this phone on at least one TV first")
    cam = d.get("camera") or {}
    if not cam:
        sys.exit(f"{path}: the profile has no locked camera settings (iso/format/lens); retrain it")
    st = d.get("stats") or {}
    tvs = sorted((st.get("tvs") or {}).keys())
    name = (d.get("name") or "").strip()
    panels = {}
    for pn, v in (d.get("panels") or {}).items():
        if isinstance(v.get("M"), list) and len(v["M"]) == 3:
            panels[pn] = {"matrix": v["M"], "deAvg": v.get("de_avg")}
            if isinstance(v.get("Mn"), list) and len(v["Mn"]) == 3:
                panels[pn]["neutral"] = v["Mn"]
            if len(v.get("tvs", [])) < 2:
                print(f"! {d['model']}: {pn} trained on {len(v.get('tvs', []))} TV - a second {pn} TV would "
                      "estimate accuracy on unseen ones")
    if not panels:
        print(f"! {d['model']}: no conversion per kind of screen - set each TV's screen type on the lab "
              "tool's Train page")
    return {
        "format": FORMAT,
        "model": d["model"],
        "name": name,
        "version": 0,
        "matrix": M,
        "camera": {k: cam[k] for k in ("iso", "format", "lens", "aperture") if cam.get(k) is not None},
        "stats": {"deAvg": st.get("de_avg"), "unseen": st.get("unseen"), "tvs": tvs},
        "trained": max((s.get("date") or "" for s in d.get("sessions", [])), default="") or None,
        "panels": panels,
        "_platform": platform_of(d["model"], d.get("platform")),   # removed before the profile is written
    }


def inv3(M):
    a, b, c = M
    det = a[0] * (b[1] * c[2] - b[2] * c[1]) - a[1] * (b[0] * c[2] - b[2] * c[0]) + a[2] * (b[0] * c[1] - b[1] * c[0])
    cof = [[(b[1] * c[2] - b[2] * c[1]), -(a[1] * c[2] - a[2] * c[1]), (a[1] * b[2] - a[2] * b[1])],
           [-(b[0] * c[2] - b[2] * c[0]), (a[0] * c[2] - a[2] * c[0]), -(a[0] * b[2] - a[2] * b[0])],
           [(b[0] * c[1] - b[1] * c[0]), -(a[0] * c[1] - a[1] * c[0]), (a[0] * b[1] - a[1] * b[0])]]
    return [[v / det for v in row] for row in cof]


def mul3(A, B):
    return [[sum(A[i][k] * B[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


DERIVED_PENALTY = 2.0   # measured: a conversion carried over from another phone was about 2 dE worse


def derive_panels(profiles):
    """Kinds of screen a model wasn't trained on, but another model was: carry that model's conversion over
    through a kind of screen both were trained on (the phones' cameras relate the same way on any screen).
    Marked derived, with a higher expected error."""
    own = {p["model"]: {k: v for k, v in p["panels"].items() if not v.get("derived")} for p in profiles}
    for p in profiles:
        for q in profiles:
            if q is p:
                continue
            common = [k for k in own[p["model"]] if k in own[q["model"]]]
            if not common:
                continue
            c = min(common, key=lambda k: (own[p["model"]][k].get("deAvg") or 9) + (own[q["model"]][k].get("deAvg") or 9))
            Q = mul3(inv3(own[q["model"]][c]["matrix"]), own[p["model"]][c]["matrix"])   # p's camera -> q's
            for k, v in own[q["model"]].items():
                if k in p["panels"]:
                    continue
                p["panels"][k] = {"matrix": mul3(v["matrix"], Q),
                                  "deAvg": (v.get("deAvg") or 1.0) + DERIVED_PENALTY, "derived": True}
                if v.get("neutral"):
                    p["panels"][k]["neutral"] = mul3(v["neutral"], Q)
                print(f"  {p['model']}: {k} carried over from {q['model']} (via {c})")


def entry_platform(e):
    return platform_of(e["model"], e.get("platform"))


def write_builtin(path, plat, entries, out, standard):
    """One app's built-in copy: that platform's published profiles, plus which one is its standard."""
    label = {"ios": "iPhone", "android": "Android"}[plat]
    builtin = {"format": 1, "standard": None, "profiles": []}
    current = None
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            current = json.load(f)
        builtin["standard"] = current.get("standard")
    if standard:
        builtin["standard"] = standard
    for e in sorted(entries.values(), key=lambda e: e["model"]):
        if entry_platform(e) != plat:
            continue
        with open(os.path.join(out, e["file"]), encoding="utf-8") as f:
            builtin["profiles"].append(json.load(f))
    models = [q["model"] for q in builtin["profiles"]]
    if builtin["standard"] not in models:
        if builtin["standard"]:
            print(f"! {label} standard profile {builtin['standard']} isn't published; pass --standard")
        builtin["standard"] = models[0] if len(models) == 1 else None
    if builtin == current:
        print(f"{path}: unchanged ({len(models)} {label} profile(s), standard: {builtin['standard'] or 'none'}).")
        return
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:     # LF on Windows too, as committed
        json.dump(builtin, f, indent=1, sort_keys=True)
    print(f"Wrote {path}: {len(models)} built-in {label} profile(s), standard: {builtin['standard'] or 'none'}.")


def main(argv=None):
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description="Build the TV Calibrator camera-profile server folder.")
    ap.add_argument("exports", nargs="+", help="camera profile files exported from the lab tool")
    ap.add_argument("--out", default=os.path.join(here, "..", "server"),
                    help="server folder to update (default: the repo's server/)")
    ap.add_argument("--builtin", default=os.path.join(here, "..", "TV Calibrator", "Resources", "builtin-profiles.json"),
                    help="the iPhone app's built-in profiles file to rewrite "
                         "(default: TV Calibrator/Resources/builtin-profiles.json)")
    ap.add_argument("--android-builtin",
                    default=os.path.join(here, "..", "android", "app", "src", "main", "assets", "builtin-profiles.json"),
                    help="the Android app's built-in profiles file to rewrite "
                         "(default: android/app/src/main/assets/builtin-profiles.json)")
    ap.add_argument("--platform", choices=("all",) + PLATFORMS, default="all",
                    help="which app's built-in file to rewrite (default: all; server/index.json is always shared)")
    ap.add_argument("--standard", action="append", default=[], metavar="MODEL",
                    help="model whose profile phones of its platform without their own use (default: keep the "
                         "current one); pass once per platform, e.g. --standard iPhone18,1 --standard samsung/e2q")
    ap.add_argument("--name", action="append", default=[], metavar="MODEL=NAME",
                    help='marketing name for a model whose profile has none, e.g. "iPhone18,1=iPhone 18 Pro" '
                         'or "samsung/e2q=Galaxy S24+"')
    ap.add_argument("--max-unseen-de", type=float, default=3.0,
                    help="refuse profiles whose estimated dE on an unseen TV is above this (default 3)")
    a = ap.parse_args(argv)

    os.makedirs(os.path.join(a.out, "profiles"), exist_ok=True)
    index_path = os.path.join(a.out, "index.json")
    old = {}
    if os.path.exists(index_path):
        with open(index_path, encoding="utf-8") as f:
            for e in json.load(f).get("profiles", []):
                old[e["model"]] = e

    bad = [n for n in a.name if "=" not in n]
    if bad:
        sys.exit(f"--name needs MODEL=NAME, e.g. \"samsung/e2q=Galaxy S24+\" (got {bad[0]!r})")
    names = {k.strip(): v for k, v in (n.split("=", 1) for n in a.name)}
    exports = [p for path in a.exports for p in load_exports(path)]
    platforms = {p["model"]: p.pop("_platform") for p in exports}
    for p in exports:
        if p["model"] in names:
            p["name"] = names[p["model"]].strip()
        if not p["name"]:
            print(f"! {p['model']}: no marketing name - pass --name \"{p['model']}="
                  f"{EXAMPLE_NAME[platforms[p['model']]]}\" or rename it in the lab tool")

    # Two models must never share a file name ("samsung/e2q" and "samsung_e2q" would).
    by_file = {}
    for m in list(old) + [p["model"] for p in exports]:
        other = by_file.setdefault(safe_name(m), m)
        if other != m:
            sys.exit(f"models {other!r} and {m!r} would share the profile file name {safe_name(m)!r}")

    derive_panels(exports)
    entries = dict(old)
    for p in exports:
        unseen = p["stats"]["unseen"]
        if unseen is not None and unseen > a.max_unseen_de:
            print(f"! {p['model']}: estimated dE {unseen:.2f} on unseen TVs is above {a.max_unseen_de} - not published")
            continue
        prev = old.get(p["model"])
        version = 1
        if prev:
            version = prev["version"]
            prev_path = os.path.join(a.out, prev["file"])
            same = False
            if os.path.exists(prev_path):
                with open(prev_path, encoding="utf-8") as f:
                    q = json.load(f)
                same = q.get("matrix") == p["matrix"] and q.get("camera") == p["camera"] and q.get("name") == p["name"] \
                    and q.get("panels") == p["panels"]
            if not same:
                version += 1
        p["version"] = version
        rel = f"profiles/{safe_name(p['model'])}-v{version}.json"
        data = json.dumps(p, indent=1, sort_keys=True).encode("utf-8")
        with open(os.path.join(a.out, rel), "wb") as f:
            f.write(data)
        entries[p["model"]] = {
            "model": p["model"], "name": p["name"], "platform": platforms[p["model"]], "version": version,
            "file": rel, "sha256": hashlib.sha256(data).hexdigest(),
            "deAvg": p["stats"]["deAvg"], "unseen": unseen, "tvs": len(p["stats"]["tvs"]),
        }
        print(f"{p['model']:<14} {p['name'] or '(no name)':<22} {platforms[p['model']]:<8} v{version}  "
              f"unseen dE {unseen if unseen is not None else '-'}  {len(p['stats']['tvs'])} TV(s)")
    for e in entries.values():
        e.setdefault("platform", platform_of(e["model"]))

    index = {"format": FORMAT, "minAppFormat": MIN_APP_FORMAT,
             "updated": datetime.now(timezone.utc).isoformat(timespec="seconds"),
             "profiles": sorted(entries.values(), key=lambda e: e["model"])}
    with open(index_path, "w", encoding="utf-8", newline="\n") as f:   # LF on Windows too, as committed
        json.dump(index, f, indent=1)
    print(f"\nWrote {index_path} with {len(entries)} model(s).")

    # Phones check each file's SHA-256, so the files must stay byte for byte as written. On Windows,
    # git's core.autocrlf rewrites them with CRLF line endings on checkout.
    crlf = False
    for e in sorted(entries.values(), key=lambda e: e["model"]):
        path = os.path.join(a.out, e["file"])
        if not os.path.exists(path):
            print(f"! {e['model']}: {e['file']} is missing")
            continue
        with open(path, "rb") as f:
            data = f.read()
        if hashlib.sha256(data).hexdigest() != e["sha256"]:
            lf = hashlib.sha256(data.replace(b"\r\n", b"\n")).hexdigest() == e["sha256"]
            crlf = crlf or lf
            print(f"! {e['model']}: {e['file']} doesn't match its SHA-256 in index.json"
                  + (" (its line endings were changed to CRLF)" if lf else "") + " - phones would reject it")
    if crlf:
        print("! Don't deploy this server folder as it is. The repo's .gitattributes (server/** -text) stops git "
              "converting line endings there; check the files out again: git rm --cached -r -q server && "
              "git checkout -- server")

    # --standard goes to the built-in file of the model's own platform.
    standards = {}
    for m in a.standard:
        plat = entry_platform(entries[m]) if m in entries else platform_of(m)
        if plat in standards and standards[plat] != m:
            sys.exit(f"--standard {standards[plat]} and --standard {m} are both {plat} models; pass one per platform")
        standards[plat] = m

    targets = {"ios": a.builtin, "android": a.android_builtin}
    for plat in PLATFORMS:
        if a.platform in ("all", plat):
            write_builtin(targets[plat], plat, entries, a.out, standards.get(plat))
        elif plat in standards:
            print(f"! --standard {standards[plat]} ignored: --platform {a.platform} doesn't rewrite the {plat} built-in file")
    print("Commit and push to publish. Ship a new app build to update the built-in profiles.")


if __name__ == "__main__":
    main()
