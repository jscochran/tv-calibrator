#!/usr/bin/env python3
"""
Self-test for tools/build_catalog.py: builds into a temporary copy of server/ and of both apps'
built-in profile files (the real ones are never touched), with fake Android and iPhone exports, and
checks file names, the "model" field, SHA-256 checksums, version numbers, the unseen-dE gate and
that each built-in file only gets its own platform's models.

    python tools/tests/test_build_catalog.py        (standard library only; -v for each check)
"""
import copy
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
SCRIPT = os.path.join(REPO, "tools", "build_catalog.py")
sys.path.insert(0, os.path.join(REPO, "tools"))
import build_catalog  # noqa: E402

M1 = [[0.0009, 0.0001, 0.0003], [0.0002, 0.0006, -0.00004], [0.000001, -0.0003, 0.0018]]


def export(model, M, unseen=1.4, name="", **extra):
    """A file as the lab tool's Camera profiles > Export writes it."""
    return {"tvcal_camera_profile": 1, "model": model, "name": name, "M": M,
            "camera": {"iso": 50, "format": "4080x3060 GBRG", "lens": "Back camera 0"},
            "stats": {"de_avg": 0.9, "unseen": unseen, "tvs": {"TV A": {"de_avg": 0.8}, "TV B": {"de_avg": 1.0}}},
            "sessions": [{"tv": "TV A", "date": "2026-11-20T10:00"}, {"tv": "TV B", "date": "2026-11-21T10:00"}],
            "panels": {"woled": {"M": M, "Mn": M, "de_avg": 0.8, "tvs": ["TV A", "TV B"], "unseen": unseen}},
            **extra}


def scaled(M, k):
    return [[v * k for v in row] for row in M]


class BuildCatalogTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="tvcal-catalog-")
        self.server = os.path.join(self.tmp, "server")
        shutil.copytree(os.path.join(REPO, "server"), self.server)
        self.ios = os.path.join(self.tmp, "ios-builtin.json")
        shutil.copy(os.path.join(REPO, "TV Calibrator", "Resources", "builtin-profiles.json"), self.ios)
        self.android = os.path.join(self.tmp, "android-builtin.json")
        shutil.copy(os.path.join(REPO, "android", "app", "src", "main", "assets", "builtin-profiles.json"), self.android)
        with open(os.path.join(self.server, "index.json"), encoding="utf-8") as f:
            self.index0 = {e["model"]: e for e in json.load(f)["profiles"]}
        with open(self.ios, encoding="utf-8") as f:
            self.ios_standard0 = json.load(f)["standard"]

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def write(self, name, data):
        path = os.path.join(self.tmp, name)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f)
        return path

    def run_build(self, *args, ok=True):
        env = dict(os.environ, PYTHONIOENCODING="utf-8")
        r = subprocess.run([sys.executable, SCRIPT, "--out", self.server, "--builtin", self.ios,
                            "--android-builtin", self.android, *args],
                           capture_output=True, text=True, encoding="utf-8", env=env)
        if ok:
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        else:
            self.assertNotEqual(r.returncode, 0, r.stdout + r.stderr)
        return r.stdout + r.stderr

    def load(self, path):
        with open(path, encoding="utf-8") as f:
            return json.load(f)

    def index(self):
        return {e["model"]: e for e in self.load(os.path.join(self.server, "index.json"))["profiles"]}

    def check_checksums(self, idx):
        for e in idx.values():
            with open(os.path.join(self.server, e["file"]), "rb") as f:
                data = f.read()
            if e["model"] in self.index0:
                # Committed files: git's core.autocrlf may have checked them out with CRLF (the script warns).
                data = data.replace(b"\r\n", b"\n")
            self.assertEqual(hashlib.sha256(data).hexdigest(), e["sha256"], e["file"])
            p = json.loads(data)
            self.assertEqual(p["model"], e["model"])
            self.assertEqual(p["version"], e["version"])
            self.assertNotIn("_platform", p)

    def test_safe_name(self):
        self.assertEqual(build_catalog.safe_name("iPhone18,1"), "iPhone18,1")
        self.assertEqual(build_catalog.safe_name("samsung/e2q"), "samsung_e2q")
        self.assertEqual(build_catalog.safe_name("Google/Pixel 8 Pro"), "Google_Pixel_8_Pro")
        self.assertEqual(build_catalog.platform_of("iPhone19,2"), "ios")
        self.assertEqual(build_catalog.platform_of("google/panther"), "android")
        self.assertEqual(build_catalog.platform_of("iPad16,3", "ios"), "ios")

    def test_android_and_iphone(self):
        s24 = self.write("s24.json", export("samsung/e2q", M1))
        pixel = self.write("pixel.json", export("google/panther", scaled(M1, 1.1), unseen=4.2, name="Pixel 7"))
        iphone = self.write("iphone.json", export("iPhone99,9", scaled(M1, 0.5), name="iPhone Test"))
        ipad = self.write("ipad.json", export("iPad16,3", scaled(M1, 0.7), name="iPad Test", platform="ios"))
        args = [s24, pixel, iphone, ipad, "--name", "samsung/e2q=Galaxy S24+", "--standard", "samsung/e2q"]
        out = self.run_build(*args)

        idx = self.index()
        e = idx["samsung/e2q"]
        self.assertEqual(e["file"], "profiles/samsung_e2q-v1.json")
        self.assertEqual((e["version"], e["name"], e["platform"]), (1, "Galaxy S24+", "android"))
        self.assertFalse(os.path.isdir(os.path.join(self.server, "profiles", "samsung")))
        p = self.load(os.path.join(self.server, e["file"]))
        self.assertEqual(p["model"], "samsung/e2q")
        self.assertEqual(p["camera"], {"iso": 50, "format": "4080x3060 GBRG", "lens": "Back camera 0"})
        # The unseen-dE gate: 4.2 > 3 is refused.
        self.assertNotIn("google/panther", idx)
        self.assertIn("google/panther: estimated dE 4.20 on unseen TVs is above 3.0 - not published", out)
        self.assertFalse(os.path.exists(os.path.join(self.server, "profiles", "google_panther-v1.json")))
        self.assertEqual(idx["iPhone99,9"]["file"], "profiles/iPhone99,9-v1.json")
        self.assertEqual(idx["iPad16,3"]["platform"], "ios")
        # Models not passed in are kept as they were, and marked iOS.
        for m, old in self.index0.items():
            self.assertEqual({k: v for k, v in idx[m].items() if k != "platform"}, old)
            self.assertEqual(idx[m]["platform"], "ios")
        self.check_checksums(idx)

        ios = self.load(self.ios)
        ios_models = [q["model"] for q in ios["profiles"]]
        self.assertEqual(sorted(ios_models), sorted(list(self.index0) + ["iPhone99,9", "iPad16,3"]))
        self.assertEqual(ios["standard"], self.ios_standard0)
        android = self.load(self.android)
        self.assertEqual([q["model"] for q in android["profiles"]], ["samsung/e2q"])
        self.assertEqual(android["standard"], "samsung/e2q")
        self.assertEqual(android["profiles"][0], p)

        # Same inputs again: nothing gets a new version, and the files are byte for byte the same.
        self.run_build(*args)
        idx2 = self.index()
        for m in idx:
            self.assertEqual((idx2[m]["version"], idx2[m]["sha256"]), (idx[m]["version"], idx[m]["sha256"]), m)

        # Retrained S24+ (new matrix), Android built-in only: v2, v1 kept, iOS built-in untouched.
        with open(self.ios, "rb") as f:
            ios_bytes = f.read()
        s24b = self.write("s24b.json", export("samsung/e2q", scaled(M1, 1.05), name="Galaxy S24+"))
        self.run_build(s24b, "--platform", "android")
        idx3 = self.index()
        self.assertEqual((idx3["samsung/e2q"]["version"], idx3["samsung/e2q"]["file"]), (2, "profiles/samsung_e2q-v2.json"))
        self.assertTrue(os.path.exists(os.path.join(self.server, "profiles", "samsung_e2q-v1.json")))
        self.assertEqual(idx3["iPhone99,9"], idx2["iPhone99,9"])
        self.check_checksums(idx3)
        with open(self.ios, "rb") as f:
            self.assertEqual(f.read(), ios_bytes)
        android = self.load(self.android)
        self.assertEqual([(q["model"], q["version"]) for q in android["profiles"]], [("samsung/e2q", 2)])

    def test_two_android_models_need_a_standard(self):
        s24 = self.write("s24.json", export("samsung/e2q", M1, name="Galaxy S24+"))
        pixel = self.write("pixel.json", export("google/panther", scaled(M1, 1.1), unseen=1.8, name="Pixel 7"))
        self.run_build(s24, pixel)
        android = self.load(self.android)
        self.assertEqual([q["model"] for q in android["profiles"]], ["google/panther", "samsung/e2q"])
        self.assertIsNone(android["standard"])
        self.run_build(s24, pixel, "--standard", "google/panther", "--standard", "iPhone19,2")
        self.assertEqual(self.load(self.android)["standard"], "google/panther")
        self.assertEqual(self.load(self.ios)["standard"], "iPhone19,2")

    def test_crlf_warning(self):
        s24 = self.write("s24.json", export("samsung/e2q", M1, name="Galaxy S24+"))
        self.run_build(s24)
        path = os.path.join(self.server, "profiles", "samsung_e2q-v1.json")
        with open(path, "rb") as f:
            data = f.read()
        with open(path, "wb") as f:
            f.write(data.replace(b"\n", b"\r\n"))   # what core.autocrlf does on checkout
        iphone = self.write("iphone.json", export("iPhone99,9", scaled(M1, 0.5), name="iPhone Test"))
        out = self.run_build(iphone)
        self.assertIn("samsung/e2q: profiles/samsung_e2q-v1.json doesn't match its SHA-256 in index.json "
                      "(its line endings were changed to CRLF)", out)
        self.assertIn("Don't deploy this server folder as it is", out)
        with open(os.path.join(self.server, "index.json"), "rb") as f:
            self.assertNotIn(b"\r\n", f.read())          # written with LF on Windows too, as committed

    def test_bad_input(self):
        s24 = self.write("s24.json", export("samsung/e2q", M1))
        clash = self.write("clash.json", export("samsung_e2q", M1))
        self.assertIn("would share the profile file name", self.run_build(s24, clash, ok=False))
        self.assertIn("--name needs MODEL=NAME", self.run_build(s24, "--name", "Galaxy S24+", ok=False))
        bad = copy.deepcopy(export("samsung/e2q", M1, platform="windows"))
        self.assertIn("platform must be one of", self.run_build(self.write("bad.json", bad), ok=False))
        self.assertIn("pass one per platform",
                      self.run_build(s24, "--standard", "samsung/e2q", "--standard", "google/panther", ok=False))


if __name__ == "__main__":
    unittest.main()
