#!/usr/bin/env python3
"""Create the APK + update.json consumed by the Android update screen."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


def create(apk: Path, tag: str, output: Path, aapt2: Path, wear: bool = False) -> dict:
    badging = subprocess.check_output([str(aapt2), "dump", "badging", str(apk)], text=True)
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    sdk = re.search(r"(?:minSdkVersion|sdkVersion):'(\d+)'", badging)
    if not package or not sdk:
        raise ValueError("APK metadata could not be read")
    application_id, code, version = package.groups()
    if application_id != "de.luca.mitfahrt" or not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("Unexpected app or version")
    if tag != f"v{version}":
        raise ValueError("Git tag must match the APK version")
    if wear != ("uses-feature: name='android.hardware.type.watch'" in badging):
        raise ValueError("APK device type does not match the requested phone/watch variant")
    output.mkdir(parents=True, exist_ok=True)
    name = f"Mitfahrt-{'Wear-' if wear else ''}{version}.apk"
    target = output / name
    shutil.copyfile(apk, target)
    checksum = hashlib.sha256(target.read_bytes()).hexdigest()
    manifest = dict(applicationId=application_id, versionCode=int(code), versionName=version,
                    minSdk=int(sdk.group(1)), apkAssetName=name, size=target.stat().st_size, sha256=checksum)
    (output / ("watch-update.json" if wear else "update.json")).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (output / ("SHA256SUMS-Wear.txt" if wear else "SHA256SUMS.txt")).write_text(f"{checksum}  {name}\n", encoding="utf-8")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--output", type=Path, default=Path("dist"))
    parser.add_argument("--aapt2", type=Path)
    parser.add_argument("--wear", action="store_true")
    args = parser.parse_args()
    sdk_root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    tool = args.aapt2 or (Path(sdk_root) / "build-tools/36.0.0/aapt2" if sdk_root else None)
    if tool is None:
        parser.error("Set ANDROID_HOME or pass --aapt2")
    result = create(args.apk, args.tag, args.output, tool, args.wear)
    print(f"Prepared version {result['versionName']} ({result['versionCode']})")
