#!/usr/bin/env python3
"""Build a verified APK; signing material is private and never part of the image/repo."""

import argparse
import hashlib
import json
import os
import secrets
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def run(command, env):
    subprocess.run(command, cwd=ROOT / "android", env=env, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--init-keystore",
        action="store_true",
        help="Create a new signing key only if none exists",
    )
    args = parser.parse_args()
    env = os.environ.copy()
    java_home = Path(env.get("JAVA_HOME", ""))
    keytool = java_home / "bin/keytool"
    if not keytool.is_file():
        parser.error("Set JAVA_HOME to a JDK 21 installation")
    secret_dir = ROOT / ".secrets"
    signing = secret_dir / "android-signing.json"
    keystore = secret_dir / "finora-android.p12"
    if not env.get("FINORA_KEYSTORE"):
        if not signing.exists():
            if not args.init_keystore:
                parser.error(
                    "No signing key: run with --init-keystore once, then back up .secrets/"
                )
            if keystore.exists():
                parser.error(
                    "Existing keystore found without its configuration; restore its password"
                )
            secret_dir.mkdir(mode=0o700, exist_ok=True)
            password = secrets.token_urlsafe(36)
            env.update(FINORA_STORE_PASSWORD=password, FINORA_KEY_PASSWORD=password)
            run(
                [
                    str(keytool),
                    "-genkeypair",
                    "-alias",
                    "finora",
                    "-keyalg",
                    "RSA",
                    "-keysize",
                    "3072",
                    "-validity",
                    "10000",
                    "-storetype",
                    "PKCS12",
                    "-keystore",
                    str(keystore),
                    "-storepass:env",
                    "FINORA_STORE_PASSWORD",
                    "-keypass:env",
                    "FINORA_KEY_PASSWORD",
                    "-dname",
                    "CN=Finora Android",
                ],
                env,
            )
            keystore.chmod(0o600)
            with signing.open("x") as stream:
                os.chmod(signing, 0o600)
                json.dump(
                    {"store_password": password, "key_password": password}, stream
                )
        config = json.loads(signing.read_text())
        env.update(
            FINORA_KEYSTORE=str(keystore),
            FINORA_STORE_PASSWORD=config["store_password"],
            FINORA_KEY_PASSWORD=config["key_password"],
        )
    gradle = env.get("FINORA_GRADLE", str(ROOT / "android/gradlew"))
    run(
        [
            gradle,
            "--console=plain",
            "checkKotlinFormat",
            ":app:testDebugUnitTest",
            ":app:lintRelease",
            ":app:assembleRelease",
        ],
        env,
    )
    release = ROOT / "android/app/build/outputs/apk/release"
    metadata = json.loads((release / "output-metadata.json").read_text())["elements"][0]
    apk = release / metadata["outputFile"]
    sdk = Path(env.get("ANDROID_HOME", env.get("ANDROID_SDK_ROOT", "")))
    signers = list((sdk / "build-tools").glob("*/apksigner"))
    if not signers:
        parser.error("Set ANDROID_HOME to an SDK with Android build tools")
    signer = max(signers, key=lambda p: tuple(int(x) for x in p.parent.name.split(".")))
    run([str(signer), "verify", "--verbose", "--print-certs", str(apk)], env)
    output = ROOT / "artifacts/android"
    output.mkdir(parents=True, exist_ok=True)
    target = output / f"finora-{metadata['versionName']}.apk"
    shutil.copyfile(apk, target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    target.with_suffix(".apk.sha256").write_text(f"{digest}  {target.name}\n")
    print(f"\nSigned APK: {target}\nSHA-256: {digest}")


if __name__ == "__main__":
    main()
