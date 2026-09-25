import io
import warnings

from PIL import Image, ImageOps, UnidentifiedImageError

from .finance import fail

MAX_AVATAR_BYTES = 5 * 1024 * 1024


def profile_photo(raw: bytes) -> bytes:
    if not raw or len(raw) > MAX_AVATAR_BYTES:
        fail("Выберите фотографию размером до 5 МБ", 413)
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(raw)) as image:
                if image.format not in {"JPEG", "PNG", "WEBP", "HEIF", "HEIC"}:
                    fail("Нужна фотография JPEG, PNG, WebP или HEIC")
                if image.width * image.height > 32_000_000:
                    fail("Фотография слишком большая: максимум 32 мегапикселя", 413)
                image = ImageOps.fit(ImageOps.exif_transpose(image), (512, 512))
                background = Image.new("RGB", image.size, "#f1f3ec")
                if "A" in image.getbands():
                    background.paste(image, mask=image.getchannel("A"))
                else:
                    background.paste(image.convert("RGB"))
                output = io.BytesIO()
                # Re-encoding removes metadata and active/extra payloads; no raw file is served.
                background.save(output, "JPEG", quality=85, optimize=True)
                return output.getvalue()
    except (
        UnidentifiedImageError,
        OSError,
        ValueError,
        Image.DecompressionBombError,
        Image.DecompressionBombWarning,
    ):
        fail("Не удалось прочитать фотографию. Выберите другой файл")
