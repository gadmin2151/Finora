# Long receipts · Android 1.6

The **Длинный чек** camera mode assembles overlapping camera frames into one vertical JPEG on the phone. It does not use OpenAI, record a video file, or send camera frames to the server.

## How to scan

1. Select your organization, open **Добавить → Длинный чек**, and place the top of the receipt inside the guide. Fill the guide with the paper's width; include both edges.
2. Tap **Начать** and move the phone slowly from the top toward the bottom. Keep the paper flat and maintain the camera's angle and distance. A small vibration indicates an accepted section; the preview strip grows as sections are added.
3. Include the final total and QR in the guide, then tap **Готово**. You can pause and continue during scanning. If the camera loses overlap, move slightly back toward the last captured section; it does not invent or silently bridge the missing area.
4. Scroll through the assembled image and use **+ / −** to inspect the joins. **Переснять** starts again; **Использовать** adds one photo to the existing private draft. **Распознать чек** sends the draft over HTTPS. The resulting items and total still require review and confirmation before posting.

The regular photo camera and multi-photo upload remain available for curled, folded, badly lit or otherwise difficult receipts.

## Implementation and limits

- [CameraX ImageAnalysis](https://developer.android.com/media/camera/camerax/analyze) uses the latest available frame and a shared preview viewport. Only the guide's camera pixels are captured, without the interface overlay. Frame sampling is throttled; actual cadence depends on the device.
- Local contrast matching checks translation and small changes in scale. Multiple possible overlaps are compared to reject ambiguous repeated patterns. Low-detail frames, blur relative to the last good section, large sideways movement and lost overlap produce guidance instead of adding uncertain content.
- The join is placed in a light shared paper band. Overlap is removed; the common horizontal area is retained. The last small valid movement is included when finishing.
- Capture is bounded to 100 accepted sections, a maximum width of 960 pixels and a maximum height of 14,000 pixels. The app asks the user to finish at the limit. It pauses when backgrounded and keeps the screen awake while open. Leaving the capture screen discards its uncommitted temporary files; this is not a recoverable video recording.
- Temporary sections live in app-private cache. The selected JPEG enters the existing organization-specific, backup-excluded draft store. The same review, HTTPS transport and receipt permissions apply.
- The server preserves a tall image within the existing 32-megapixel input boundary and a 16-megapixel normalized-image budget. For AI recognition, it provides the complete image plus at most eight ordered overlapping detail views per tall photo. The stored receipt remains a single image.
- This is a guided panoramic capture mode, not arbitrary panorama or perspective reconstruction. Check the complete result before sending, especially for repeated identical lines, shadows, bent paper or rapid movement. It never changes amounts or fabricates missing receipt content while stitching.
