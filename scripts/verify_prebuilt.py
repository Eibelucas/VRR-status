#!/usr/bin/env python3
"""Publish a locally signed APK without ever uploading its private signing key."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from create_update_manifest import create

source, output = map(Path, sys.argv[1:])
manifest = json.loads((source / "update.json").read_text())
name = manifest["apkAssetName"]
if not re.fullmatch(r"Mitfahrt-\d+\.\d+\.\d+\.apk", name):
    raise ValueError("Invalid APK asset name")
sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
tools = sdk / "build-tools/36.0.0"
apk = source / name
cert_output = subprocess.check_output([str(tools / "apksigner"), "verify", "--print-certs", str(apk)], text=True)
cert = re.search(r"^Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})$", cert_output, re.M)
trusted = Path("release-signing.sha256").read_text().strip()
if not cert or cert.group(1) != trusted:
    raise ValueError("APK signing certificate does not match the installed app")
actual = create(apk, f"v{manifest['versionName']}", output, tools / "aapt2")
if actual != manifest:
    raise ValueError("APK identity, size or digest does not match update.json")
print("Verified signing certificate, APK identity and SHA-256")

watch_manifest = source / "watch-update.json"
if watch_manifest.exists():
    watch = json.loads(watch_manifest.read_text())
    watch_name = watch["apkAssetName"]
    if not re.fullmatch(r"Mitfahrt-Wear-\d+\.\d+\.\d+\.apk", watch_name):
        raise ValueError("Invalid watch APK asset name")
    if (watch["versionCode"], watch["versionName"]) != (manifest["versionCode"], manifest["versionName"]):
        raise ValueError("Phone and watch release versions differ")
    watch_apk = source / watch_name
    watch_cert_output = subprocess.check_output([str(tools / "apksigner"), "verify", "--print-certs", str(watch_apk)], text=True)
    watch_cert = re.search(r"^Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})$", watch_cert_output, re.M)
    if not watch_cert or watch_cert.group(1) != trusted:
        raise ValueError("Watch signing certificate differs from phone")
    if create(watch_apk, f"v{watch['versionName']}", output, tools / "aapt2", wear=True) != watch:
        raise ValueError("Watch APK identity, size or digest does not match its manifest")
    print("Verified watch device type, shared signing certificate and SHA-256")
