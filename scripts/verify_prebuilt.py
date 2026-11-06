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
