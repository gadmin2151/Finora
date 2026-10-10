#!/usr/bin/env python3
"""Create an unpublished static update manifest only from a verified signed APK."""
import argparse
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

PACKAGE = "work.gadmin.finora"
CERTIFICATE = "3a3c14d603cc9cfeff5092ece5101acc60512787e90f1330292d98e4f45ee6c0"

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--release-notes", default="")
    parser.add_argument("--build-tools", type=Path, default=Path(os.environ.get("ANDROID_HOME", str(Path.home()/"Library/Android/sdk")))/"build-tools/36.0.0")
    args = parser.parse_args()
    verified = subprocess.check_output([str(args.build_tools/"apksigner"), "verify", "--print-certs", str(args.apk)], text=True)
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})", verified)
    if certificates != [CERTIFICATE]:
        parser.error("APK must have exactly the existing Finora release signer")
    badging = subprocess.check_output([str(args.build_tools/"aapt"), "dump", "badging", str(args.apk)], text=True)
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    sdk = re.search(r"sdkVersion:'(\d+)'", badging)
    if not package or package[1] != PACKAGE or not sdk or not re.fullmatch(r"\d+\.\d+\.\d+", package[3]):
        parser.error("Invalid package/version/SDK metadata")
    if len(args.release_notes) > 4000 or not 26 <= int(sdk[1]) <= 37:
        parser.error("Invalid release notes or minimum SDK")
    data = args.apk.read_bytes()
    if not 0 < len(data) <= 512 * 1024 * 1024:
        parser.error("APK size is outside the updater limit")
    manifest = dict(schemaVersion=1, packageName=PACKAGE, versionCode=int(package[2]), versionName=package[3], minSdk=int(sdk[1]), apkUrl=f"https://finora.gadmin.work/assets/mobile/finora-{package[3]}.apk", sizeBytes=len(data), sha256=hashlib.sha256(data).hexdigest(), signingCertificateSha256=CERTIFICATE, releaseNotes=args.release_notes)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # Local output only. Never uploads or overwrites an existing manifest.
    with args.output.open("x", encoding="utf-8") as output:
        json.dump(manifest, output, ensure_ascii=False, indent=2)
        output.write("\n")
    print(f"Unpublished update manifest: {args.output}")

if __name__ == "__main__":
    main()
