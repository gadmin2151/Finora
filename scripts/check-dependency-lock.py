#!/usr/bin/env python3
"""Check declared dependency ranges against the existing lock, without network access."""

import argparse
from pathlib import Path

from packaging.requirements import InvalidRequirement, Requirement
from packaging.utils import canonicalize_name
from packaging.version import InvalidVersion, Version

ROOT = Path(__file__).resolve().parents[1]


def requirements(path: Path) -> list[Requirement]:
    rows = []
    for number, line in enumerate(path.read_text().splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        try:
            rows.append(Requirement(line))
        except InvalidRequirement:
            raise ValueError(f"{path.name}:{number}: invalid requirement") from None
    return rows


def validate(source: Path, lock: Path) -> list[str]:
    errors = []
    pins = {}
    for requirement in requirements(lock):
        name = canonicalize_name(requirement.name)
        specifiers = list(requirement.specifier)
        if requirement.url or len(specifiers) != 1 or specifiers[0].operator != "==":
            errors.append(f"{name}: lock must contain one exact version")
            continue
        try:
            version = Version(specifiers[0].version)
        except InvalidVersion:
            errors.append(f"{name}: lock must contain one exact version")
            continue
        if name in pins:
            errors.append(f"{name}: duplicate lock entry")
        pins[name] = version
    for requirement in requirements(source):
        name = canonicalize_name(requirement.name)
        if requirement.url:
            errors.append(
                f"{name}: URL requirement cannot be checked against a version lock"
            )
        elif name not in pins:
            errors.append(f"{name}: missing from lock")
        elif not requirement.specifier.contains(pins[name]):
            errors.append(
                f"{name}: locked {pins[name]} is outside {requirement.specifier}"
            )
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=ROOT / "backend/requirements.in")
    parser.add_argument("--lock", type=Path, default=ROOT / "backend/requirements.txt")
    args = parser.parse_args()
    try:
        errors = validate(args.source, args.lock)
    except (OSError, ValueError) as error:
        parser.exit(1, f"Dependency lock check failed: {error}\n")
    if errors:
        parser.exit(1, "Dependency lock check failed:\n" + "\n".join(errors) + "\n")
    print("Dependency lock matches all declared version ranges")


if __name__ == "__main__":
    main()
