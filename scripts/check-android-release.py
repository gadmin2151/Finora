#!/usr/bin/env python3
"""Fail a release if R8 removes ML Kit's reflectively invoked constructors."""

import argparse
import re
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--mapping",
        type=Path,
        default=Path(__file__).resolve().parents[1]
        / "android/app/build/outputs/mapping/release/mapping.txt",
    )
    args = parser.parse_args()
    mapping = args.mapping.read_text()
    registrars = (
        "com.google.mlkit.common.internal.CommonComponentRegistrar",
        "com.google.mlkit.vision.common.internal.VisionCommonRegistrar",
        "com.google.mlkit.vision.barcode.internal.BarcodeRegistrar",
    )
    for registrar in registrars:
        block = re.search(
            rf"^{re.escape(registrar)} -> [^\n]+:\n((?:[ #][^\n]*\n)*)",
            mapping,
            re.MULTILINE,
        )
        if block is None or "void <init>():" not in block[1]:
            parser.exit(1, f"Missing reflective ML Kit constructor: {registrar}\n")
    print("Release regression check: all 3 ML Kit constructors retained")


if __name__ == "__main__":
    main()
