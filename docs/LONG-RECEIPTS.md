# Live vertical receipt scanner · Android 1.8.0

**Добавить → Снять чек** captures the full width of white paper while you move the phone from top to bottom. It builds one tall grayscale receipt image **during capture**. The camera samples approximately every 300 ms; a separate worker registers and appends overlapping sections. **Готово** waits for the remaining captured frames and finalizes the image, rather than starting the stitching job.

## How to scan

1. Place the top of the receipt inside the guide, with its full width visible. Keep the paper flat and reasonably well lit. Tap **Начать**.
2. Move slowly downward, preserving some shared printed rows between frames. The captured-frame counter, appended-section counter and growing thumbnail show progress. Small handheld rotations and changes in perspective are corrected.
3. Include the total and bottom edge, then tap **Готово**. Use **Пауза / Продолжить** if needed. If overlap is lost, move slightly back toward the last aligned rows; unverified areas are never fabricated.
4. Inspect the complete picture with scrolling and zoom. A warning appears when the last frames were not aligned. **Переснять** restarts; **Использовать** places one image in the existing organization-specific private draft. Recognition, editing and confirmation before posting are unchanged.

A short receipt can be finished after the first accepted frame. Gallery import and additional photos remain available.

## Paper mask and image quality

The scanner finds the connected bright paper region, fills internal printed holes and masks the surrounding desk before matching features. Paper brightness is estimated along the image so a shaded footer is retained. Long boundary lines are used only when the paper side is consistently brighter than the background; aligned printed letters must not become crop edges. It outputs grayscale paper, not a hard binary threshold, preserving faint thermal ink and anti-aliased letters. The final common view is cropped to the detected paper bounds. Bright objects touching the receipt, strong shadows, folds or large loss of overlap can still require reframing and visual review.

## Implementation and limits

- [CameraX ImageAnalysis](https://developer.android.com/media/camera/camerax/analyze) shares the preview viewport and only captures camera pixels inside the guide. Acquisition and stitching have separate executors. The pending buffer holds at most three bitmaps; actual cadence depends on camera throughput and device load.
- [OpenCV 4.13.0](https://docs.opencv.org/4.13.0/d5/df8/tutorial_dev_with_OCV_on_Android.html) is bundled for offline, native processing. Reciprocal feature matches and RANSAC estimate the paper's projective transformation. Spatial coverage and geometry checks reject implausible matches. Exact translation is preferred when the points support it, avoiding unnecessary perspective drift over long pages.
- Every aligned frame updates tracking, including movements too small to append. New strips are added after roughly 8% of a frame's height, and a valid smaller final movement is retained by **Готово**. Seams use light shared paper bands; overlaps are removed.
- Output is bounded to 960 pixels in width, 14,000 pixels in height and 160 appended sections. Capture pauses in the background. Temporary sections remain in private app cache and are cleaned when leaving; the chosen result is copied to the existing draft store.
- The bundled native library increases APK size. ARMv7, ARM64, x86 and x86-64 remain supported; the 64-bit native binaries support 16 KB page alignment. No extra service, AI key or network access is required for scanning.
- Server recognition still receives one image, with the existing detailed views for long receipts. Organization boundaries, HTTPS, source-photo storage and explicit confirmation remain unchanged.
