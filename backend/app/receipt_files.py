"""Append-only, private receipt source images, independent of extracted fields."""

import hashlib
import os
import tempfile

from . import models as m
from .config import settings
from .finance import fail

MAX_RECEIPT_FILES = 16


def append_images(receipt: m.Receipt, images: list[bytes]) -> int:
    directory = settings().data_dir / "receipts" / receipt.organization_id
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    names = list(receipt.file_names or [])
    existing = {
        hashlib.sha256((directory / name).read_bytes()).hexdigest()
        for name in names
        if (directory / name).is_file()
    }
    pending = {}
    for image in images:
        digest = hashlib.sha256(image).hexdigest()
        if digest not in existing:
            pending[digest] = image
    if len(names) + len(pending) > MAX_RECEIPT_FILES:
        fail(f"У чека может быть не более {MAX_RECEIPT_FILES} оригиналов")
    for digest, image in pending.items():
        name = f"{receipt.id}-{digest}.jpg"
        fd, temporary = tempfile.mkstemp(prefix=".receipt-", dir=directory)
        try:
            with os.fdopen(fd, "wb") as stream:
                stream.write(image)
            os.replace(temporary, directory / name)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
        names.append(name)
    receipt.file_names = names
    return len(pending)
