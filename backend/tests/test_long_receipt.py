import io

import pytest
from PIL import Image, ImageDraw

from app import receipts


def encoded(width, height):
    image = Image.new("RGB", (width, height), "white")
    paint = ImageDraw.Draw(image)
    for y in range(0, height, 100):
        paint.rectangle((0, y, width - 1, min(height - 1, y + 20)), fill=(y // 100 % 255, 40, 70))
    output = io.BytesIO()
    image.save(output, "JPEG", quality=94)
    return output.getvalue()


def test_upload_preserves_narrow_long_receipt_resolution():
    result, qr = receipts.image_bytes(encoded(800, 14_000))
    with Image.open(io.BytesIO(result)) as image:
        assert image.size == (800, 14_000)
    assert qr is None


@pytest.mark.parametrize("width,height", [(600, 14_000), (960, 14_000), (800, 8000), (600, 16_000)])
def test_tall_views_are_bounded_ordered_and_cover_every_row(width, height):
    original = encoded(width, height)
    views = receipts.long_receipt_views([original])
    assert views[0] == original and 3 <= len(views) <= 9
    top = 0
    with Image.open(io.BytesIO(original)) as source:
        for view in views[1:]:
            with Image.open(io.BytesIO(view)) as part:
                assert part.width == width and 0 < part.height <= 3200
                # JPEG rereads can differ slightly; verify the location of every band.
                for y in range(10, part.height - 10, 100):
                    expected = source.getpixel((width // 2, top + y))
                    actual = part.getpixel((width // 2, y))
                    assert max(abs(a - b) for a, b in zip(actual, expected, strict=True)) < 15
                top += part.height - 180
        assert top + 180 == height
    assert receipts.total_detail_images([original]) == views


def test_normal_and_invalid_photos_do_not_gain_tiles():
    original = encoded(600, 1600)
    assert receipts.long_receipt_views([original, b"invalid"]) == [original, b"invalid"]


def test_long_view_generation_does_not_need_ocr(monkeypatch):
    def unexpected(*args, **kwargs):
        pytest.fail("Panoramic photos must not require OCR to choose crop locations")

    monkeypatch.setattr(receipts.subprocess, "run", unexpected)
    assert len(receipts.vision_images([encoded(800, 10_000)])) > 1
