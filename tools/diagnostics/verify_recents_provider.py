#!/usr/bin/env python3
"""Diagnostic oracle for an already configured Quickstep provider (Issue #554)."""
import argparse
import json
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default="app.lawnchair")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    out = pathlib.Path(args.output)
    out.mkdir(parents=True, exist_ok=True)
    adb = [args.adb, "-s", args.serial]

    def run(*command, binary=False):
        return subprocess.run(adb + list(command), check=True, capture_output=True,
                              text=not binary, timeout=30).stdout

    def save(name, content):
        path = out / name
        if isinstance(content, bytes):
            path.write_bytes(content)
        else:
            path.write_text(content)

    def wait_for(command, predicate, timeout=10):
        deadline = time.monotonic() + timeout
        while True:
            value = command()
            if predicate(value):
                return value
            if time.monotonic() >= deadline:
                raise AssertionError("State did not reach expected value: " + value[-2000:])
            time.sleep(0.2)

    preflight = run("shell", "cmd", "overlay", "lookup", "android",
                    "android:string/config_recentsComponentName")
    save("provider.txt", preflight)
    if not preflight.strip().startswith(args.package + "/"):
        raise AssertionError("Expected configured provider: " + args.package)
    service = run("shell", "dumpsys", "activity", "service",
                  args.package + "/com.android.quickstep.TouchInteractionService")
    save("preflight-service.txt", service)
    if "mSystemUiProxy=null" in service or "SERVICE " not in service:
        raise AssertionError("Provider is not bound")
    save("fingerprint.txt", run("shell", "getprop", "ro.build.fingerprint"))
    save("package.txt", run("shell", "dumpsys", "package", args.package))
    width, height = map(int, re.findall(r"(\d+)x(\d+)", run("shell", "wm", "size"))[-1])
    activity = lambda: run("shell", "dumpsys", "activity", "activities")
    window = lambda: run("shell", "dumpsys", "window")
    run("shell", "input", "keyevent", "KEYCODE_HOME")
    wait_for(activity, lambda s: re.search(r"topResumedActivity=.*" + re.escape(args.package), s))
    run("logcat", "-c")
    result = {}
    try:
        run("shell", "am", "start", "-a", "android.settings.SETTINGS")
        wait_for(activity, lambda s: re.search(r"topResumedActivity=.*com.android.settings/", s))
        wait_for(window, lambda s: re.search(r"mCurrentFocus=.*com.android.settings/", s))
        x = str(width // 2)
        run("shell", "input", "touchscreen", "motionevent", "DOWN", x, str(height - 12))
        for y in (height - 60, height * 4 // 5, height * 3 // 5):
            run("shell", "input", "touchscreen", "motionevent", "MOVE", x, str(y))
        # A pause is the gesture input (swipe-and-hold), not the success oracle.
        time.sleep(0.8)
        run("shell", "input", "touchscreen", "motionevent", "UP", x, str(height * 3 // 5))
        launcher_dump = lambda: run("shell", "dumpsys", "activity", args.package + "/.LawnchairLauncher")
        state = wait_for(launcher_dump, lambda s: re.search(r"mState:\s*Overview\b", s))
        save("overview-activity.txt", state)
        save("overview.png", run("exec-out", "screencap", "-p", binary=True))
        run("shell", "uiautomator", "dump", "/data/local/tmp/recents-provider.xml")
        ui = run("shell", "cat", "/data/local/tmp/recents-provider.xml")
        save("overview.xml", ui)
        nodes = ET.fromstring(ui).iter("node")
        card = next((n for n in nodes if n.get("package") == args.package
                     and n.get("clickable") == "true"
                     and ("Settings" in n.get("content-desc", "")
                          or (n.get("resource-id", "").startswith(args.package + ":id/task_view")
                              and any(child.get("text") == "Settings" for child in n.iter("node"))))), None)
        if card is None:
            raise AssertionError("No clickable Settings task card in overview")
        bounds = list(map(int, re.findall(r"\d+", card.get("bounds", ""))))
        if len(bounds) != 4:
            raise AssertionError("Invalid task card bounds")
        run("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2),
            str((bounds[1] + bounds[3]) // 2))
        after = wait_for(activity, lambda s: re.search(r"topResumedActivity=.*com.android.settings/", s))
        save("after-activities.txt", after)
        save("after.png", run("exec-out", "screencap", "-p", binary=True))
        result["overview_and_task_return"] = True
    except (AssertionError, subprocess.SubprocessError) as e:
        result["overview_and_task_return"] = False
        result["failure"] = str(e)
    finally:
        log = run("logcat", "-d", "-b", "main,system,crash", "-v", "threadtime")
        save("logcat.txt", log)
        signature = r"BadParcelableException: Parcel data not fully consumed|Bundle length is not aligned by 4"
        errors = [line for line in log.splitlines() if re.search(signature, line)]
        save("parcel-signatures.txt", "\n".join(errors) + "\n")
        result["parcel_exception_count"] = len(errors)
        result["takeover_diagnostic_count"] = log.count("No matching remote found to takeover")
        result["bal_blocks"] = [line for line in log.splitlines()
                                if re.search(r"Background activity (launch|start) blocked|BAL.*block", line, re.I)]
        launcher_fatals = re.findall(
            r"FATAL EXCEPTION[^\n]*\n[^\n]*Process: " + re.escape(args.package) + r"(?:,|:)", log)
        result["launcher_fatal_count"] = len(launcher_fatals)
        result["pass"] = result.get("overview_and_task_return", False) and not errors and not launcher_fatals
        save("result.json", json.dumps(result, indent=2) + "\n")
        print(json.dumps(result, indent=2))
    return 0 if result["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
