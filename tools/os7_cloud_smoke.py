#!/usr/bin/env python3
"""Smoke-test an installed OS7 preview on an emulator without target instrumentation.

Usage: python3 tools/os7_cloud_smoke.py --output emulator-evidence/os7-ui
Requires adb and an already booted emulator with the existing preview APK installed.
Never installs an APK, grants permissions, or downloads/selects models.
"""
from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "ai.nanosearch.launcher.os7preview"
ACTIVITY_PREFIX = "ai.nanosearch.launcher."
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


class Smoke:
    def __init__(self, args):
        self.adb = [args.adb] + (["-s", args.serial] if args.serial else [])
        self.output = Path(args.output)
        self.output.mkdir(parents=True, exist_ok=True)
        self.wait_seconds = args.wait_seconds
        self.steps = []
        self.last_xml = ""
        self.width, self.height = 1080, 2400
        self.original = {}
        self.ready = False

    def run_adb(self, *args, timeout=60, binary=False):
        result = subprocess.run(self.adb + list(args), stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=timeout, check=False)
        if result.returncode:
            raise AssertionError(f"adb {' '.join(args)} failed ({result.returncode}): "
                                 f"{result.stderr.decode(errors='replace').strip()}")
        return result.stdout if binary else result.stdout.decode(errors="replace")

    def shell(self, *args, **kwargs):
        return self.run_adb("shell", *args, **kwargs)

    def write(self, name, text):
        (self.output / name).write_text(text, encoding="utf-8")

    def step(self, label):
        self.steps.append({"step": label, "utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())})
        print(label, flush=True)

    def activity(self):
        raw = self.shell("dumpsys", "activity", "activities")
        resumed = [line.strip() for line in raw.splitlines()
                   if re.search(r"(?:topResumedActivity|mResumedActivity|ResumedActivity)\s*[:=]", line)]
        return "\n".join(resumed), raw

    @staticmethod
    def is_activity(resumed, name):
        return bool(re.search(re.escape(PACKAGE) + r"/(?:" + re.escape(ACTIVITY_PREFIX)
                              + r"|\.)" + re.escape(name) + r"(?:\s|\})", resumed))

    def hierarchy(self):
        target = "/data/local/tmp/os7-cloud-smoke.xml"
        # Remove an old dump so a failed UIAutomator call cannot masquerade as current UI.
        self.shell("rm", "-f", target)
        result = self.shell("uiautomator", "dump", "--compressed", target, timeout=65)
        if "ERROR:" in result:
            raise AssertionError(f"UIAutomator could not dump current UI: {result.strip()}")
        self.last_xml = self.shell("cat", target)
        return ET.fromstring(self.last_xml)

    @staticmethod
    def find(root, *, text=None, desc=None):
        for node in root.iter("node"):
            if text is not None and node.get("text") != text:
                continue
            if desc is not None and node.get("content-desc") != desc:
                continue
            if node.get("package") == PACKAGE:
                return node
        return None

    def wait_screen(self, activity, *, text=None, desc=None):
        deadline, last = time.monotonic() + self.wait_seconds, "No activity observed"
        while time.monotonic() < deadline:
            try:
                resumed, _ = self.activity()
                last = f"resumed={resumed!r}"
                if self.is_activity(resumed, activity):
                    root = self.hierarchy()
                    if text is None and desc is None or self.find(root, text=text, desc=desc) is not None:
                        return root
                    last += f"; missing text={text!r}, description={desc!r}"
            except (subprocess.TimeoutExpired, ET.ParseError, AssertionError) as error:
                last = str(error)
            time.sleep(1)
        raise AssertionError(f"Timed out waiting for {activity}, text={text!r}, description={desc!r}: {last}")

    def tap(self, node, label):
        if node is None:
            raise AssertionError(f"Cannot tap missing {label}")
        match = BOUNDS.fullmatch(node.get("bounds", ""))
        if not match or node.get("enabled") == "false":
            raise AssertionError(f"{label} has invalid bounds or is disabled: {node.attrib}")
        left, top, right, bottom = map(int, match.groups())
        if right <= left or bottom <= top:
            raise AssertionError(f"{label} is not visible: {node.attrib}")
        self.shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def home(self):
        self.shell("input", "keyevent", "KEYCODE_HOME")
        root = self.wait_screen("MainActivity", desc="Home menu")
        if self.find(root, desc="All panel, three rows") is None:
            raise AssertionError("MainActivity resumed but OS7 All-panel controls are missing")
        if self.find(root, text="Step 1 of 4") is not None:
            raise AssertionError("Onboarding intercepted Home")
        return root

    def settings(self):
        root = self.home()
        self.tap(self.find(root, desc="Home menu"), "Home menu")
        root = self.wait_screen("MainActivity", text="Options")
        self.tap(self.find(root, text="Options"), "Options")
        return self.wait_screen("SettingsActivity", text="Settings")

    def models(self):
        root = self.wait_screen("SettingsActivity", text="On-device models")
        self.tap(self.find(root, text="On-device models"), "On-device models")
        return self.wait_screen("ModelsActivity", text="Models")

    def capture(self, name):
        self.hierarchy()
        self.write(f"{name}.xml", self.last_xml)
        png = self.run_adb("exec-out", "screencap", "-p", binary=True)
        if not png.startswith(b"\x89PNG\r\n\x1a\n"):
            raise AssertionError(f"Screenshot {name} is not a PNG")
        (self.output / f"{name}.png").write_bytes(png)
        self.write(f"{name}-activity.txt", self.activity()[1])

    def swipe(self, up=True):
        high, low = self.height // 4, self.height * 3 // 4
        self.shell("input", "swipe", str(self.width // 2), str(low if up else high),
                   str(self.width // 2), str(high if up else low), "400")
        time.sleep(1.5)  # Give the software-rendered emulator time to finish the fling.

    @staticmethod
    def summarize_frames(raw):
        columns, samples = [], []
        for line in raw.splitlines():
            values = [value.strip() for value in line.split(",")]
            if values[0] == "Flags":
                columns = values
            elif columns and values[0].isdigit():
                try:
                    row = dict(zip(columns, values))
                    flags, start, end = (int(row[key]) for key in ("Flags", "IntendedVsync", "FrameCompleted"))
                    if flags == 0 and 0 < start <= end < 2**63 - 1:
                        samples.append((end - start) / 1_000_000)
                except (KeyError, ValueError):
                    continue
        samples.sort()
        def percentile(p):
            return samples[max(0, math.ceil(len(samples) * p) - 1)] if samples else None
        return {"recordedValidFrames": len(samples), "medianFrameMs": percentile(.50),
                "p95FrameMs": percentile(.95), "p99FrameMs": percentile(.99),
                "framesOver16_67Ms": sum(value > 1000 / 60 for value in samples),
                "framesOver11_11Ms": sum(value > 1000 / 90 for value in samples),
                "framesOver8_33Ms": sum(value > 1000 / 120 for value in samples),
                "frameDurationsMs": samples,
                "limitations": "Software emulator and bounded gfxinfo history, not sustained FPS or a before/after-build benchmark. No performance pass threshold."}

    def scroll_sample(self, name, activity, up_count):
        self.write(f"{name}-gfx-reset.txt", self.shell("dumpsys", "gfxinfo", PACKAGE, "reset"))
        self.write(f"{name}-gfx-before.txt", self.shell("dumpsys", "gfxinfo", PACKAGE, "framestats"))
        started = time.monotonic()
        try:
            for _ in range(up_count):
                self.swipe()
            self.wait_screen(activity)
        finally:
            raw = self.shell("dumpsys", "gfxinfo", PACKAGE, "framestats")
            self.write(f"{name}-gfx-after.txt", raw)
            summary = self.summarize_frames(raw)
            summary.update(scope=name, sampleWallSeconds=time.monotonic() - started)
            self.write(f"{name}-frames.json", json.dumps(summary, indent=2))

    def prepare(self):
        if self.shell("getprop", "ro.kernel.qemu").strip() != "1":
            raise AssertionError("Refusing to change Home/display on a non-emulator device")
        if not self.shell("pm", "path", PACKAGE).startswith("package:"):
            raise AssertionError(f"The existing {PACKAGE} APK is not installed")
        self.original = {"size": self.shell("wm", "size"), "density": self.shell("wm", "density"),
                         "font": self.shell("settings", "get", "system", "font_scale").strip()}
        self.ready = True
        self.write("original-display.json", json.dumps(self.original, indent=2))
        self.write("set-home.txt", self.shell("cmd", "package", "set-home-activity", f"{PACKAGE}/{ACTIVITY_PREFIX}MainActivity"))
        self.write("launch.txt", self.shell("am", "start", "-W", "-n", f"{PACKAGE}/{ACTIVITY_PREFIX}MainActivity", timeout=90))
        deadline = time.monotonic() + self.wait_seconds
        while time.monotonic() < deadline:
            resumed, _ = self.activity()
            if self.is_activity(resumed, "SetupActivity"):
                root = self.wait_screen("SetupActivity", text="Skip setup")
                self.tap(self.find(root, text="Skip setup"), "Skip setup")
                self.step("Skipped onboarding through its UI, without granting access or downloading models")
                break
            if self.is_activity(resumed, "MainActivity"):
                root = self.hierarchy()
                if self.find(root, desc="Home menu") is not None:
                    break
            time.sleep(1)
        self.home()
        self.capture("initial-home")

    def execute(self):
        self.prepare()
        for name, width, font in (("normal", 1080, "1.0"), ("narrow", 900, "1.0"), ("large-text", 1080, "1.3")):
            self.step(f"Testing {name}: {width}x2400, density 420, font {font}")
            self.width = width
            self.shell("wm", "size", f"{width}x2400")
            self.shell("wm", "density", "420")
            self.shell("settings", "put", "system", "font_scale", font)
            time.sleep(3)
            self.settings()
            self.capture(f"{name}-settings-top")
            self.scroll_sample(f"{name}-settings-scroll", "SettingsActivity", 3)
            self.capture(f"{name}-settings-bottom")
            # Return to the top via real scrolling, then enter Models through its actual row.
            for _ in range(4):
                self.swipe(up=False)
            self.models()
            self.capture(f"{name}-models-top")
            self.scroll_sample(f"{name}-models-scroll", "ModelsActivity", 4)
            self.capture(f"{name}-models-lower")
            for index in range(2):
                self.swipe()
                self.capture(f"{name}-models-lower-{index + 1}")
            self.shell("input", "keyevent", "KEYCODE_BACK")
            self.wait_screen("SettingsActivity", text="Settings")
            self.capture(f"{name}-back-to-settings")
            self.models()
            self.home()
            self.capture(f"{name}-home-from-models")
            self.step(f"PASS {name}: Settings/Models scroll, Back to Settings, system Home to MainActivity")
        # Repeat navigation without changing configuration, to expose stale task/back-stack bugs.
        for iteration in range(3):
            self.settings()
            self.models()
            self.shell("input", "keyevent", "KEYCODE_BACK")
            self.wait_screen("SettingsActivity", text="Settings")
            self.models()
            self.home()
            self.capture(f"repeated-home-{iteration}")
            self.step(f"PASS repeated navigation {iteration + 1}")

    def finish(self, failure):
        for filename, args in (("logcat.txt", ("logcat", "-d", "-v", "threadtime")),
                               ("final-activities.txt", ("shell", "dumpsys", "activity", "activities")),
                               ("display.txt", ("shell", "dumpsys", "display"))):
            try:
                self.write(filename, self.run_adb(*args, timeout=60))
            except Exception as error:
                self.write(filename + ".error", str(error))
        if failure:
            try:
                self.capture("failure")
            except Exception as error:
                self.write("failure-capture-error.txt", str(error))
        if self.ready:
            for prop in ("size", "density"):
                match = re.search(r"Override " + prop + r":\s*(\S+)", self.original[prop], re.IGNORECASE)
                try:
                    self.shell("wm", prop, match.group(1) if match else "reset")
                except Exception as error:
                    self.write(f"restore-{prop}-error.txt", str(error))
            try:
                font = self.original["font"]
                self.shell("settings", "delete", "system", "font_scale") if font in ("", "null") else self.shell("settings", "put", "system", "font_scale", font)
            except Exception as error:
                self.write("restore-font-error.txt", str(error))
        self.write("result.json", json.dumps({"passed": failure is None, "failure": failure,
                   "package": PACKAGE, "steps": self.steps, "instrumentationUsed": False}, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--output", default="emulator-evidence/os7-ui")
    parser.add_argument("--wait-seconds", type=int, default=120)
    args = parser.parse_args()
    smoke, failure = Smoke(args), None
    try:
        smoke.execute()
    except Exception as error:
        failure = f"{type(error).__name__}: {error}"
        print(f"FAIL: {failure}", file=sys.stderr, flush=True)
    finally:
        smoke.finish(failure)
    if failure:
        return 1
    print("PASS: OS7 external UI smoke; screenshots/XML/activity/frame evidence saved", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
