package work.gadmin.finora.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.AtomicFile
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** App-private, backup-excluded photos. Every workspace has an independent draft. */
class DraftStore(private val context: Context, scope: String) {
    private val directory = File(context.noBackupFilesDir, "drafts/$scope").apply { mkdirs() }
    private val manifest = AtomicFile(File(directory, "draft.json"))
    private val photoName = Regex("^[a-f0-9-]{36}\\.jpg$")

    fun read(): Draft {
        if (!manifest.baseFile.exists()) return Draft()
        val draft = Json.decodeFromString<Draft>(manifest.readFully().decodeToString())
        require(draft.photos.size <= MAX_PHOTOS && draft.photos.all { photoName.matches(it) }) {
            "Черновик повреждён"
        }
        require(draft.photos.all { photo(it).isFile }) { "Фотографии черновика недоступны" }
        return draft
    }

    fun save(draft: Draft) {
        val stream = manifest.startWrite()
        try {
            stream.write(Json.encodeToString(draft).toByteArray())
            manifest.finishWrite(stream)
        } catch (error: Exception) {
            manifest.failWrite(stream)
            throw error
        }
    }

    fun photo(name: String): File {
        require(photoName.matches(name))
        return File(directory, name)
    }

    fun remove(name: String) {
        photo(name).delete()
    }

    fun clear() {
        save(Draft())
        directory.listFiles()?.filter { photoName.matches(it.name) }?.forEach { it.delete() }
    }

    fun importPhoto(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use(::prepare)
            ?: throw IllegalArgumentException("Не удалось открыть фотографию")

    fun importPhoto(file: File): String = file.inputStream().use(::prepare)

    private fun prepare(input: InputStream): String {
        val raw = File.createTempFile("receipt-", ".source", context.cacheDir)
        val name = "${UUID.randomUUID()}.jpg"
        val output = photo(name)
        try {
            raw.outputStream().use { target ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= 50L * 1024 * 1024) {
                        "Фото больше 50 МБ. Выберите меньший размер."
                    }
                    target.write(buffer, 0, read)
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(raw.path, bounds)
            require(
                bounds.outWidth > 0 &&
                    bounds.outHeight > 0 &&
                    bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000L
            ) {
                "Нужна фотография JPG, PNG, WebP или HEIC размером до 100 Мп"
            }
            val options =
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inSampleSize = 1
                    while (
                        maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 5000 ||
                            bounds.outWidth.toLong() * bounds.outHeight /
                                inSampleSize /
                                inSampleSize > 16_000_000
                    ) inSampleSize *= 2
                }
            val bitmap =
                BitmapFactory.decodeFile(raw.path, options)
                    ?: throw IllegalArgumentException("Не удалось прочитать фото")
            val exif = ExifInterface(raw)
            val matrix =
                Matrix().apply {
                    if (exif.isFlipped) postScale(-1f, 1f)
                    postRotate(exif.rotationDegrees.toFloat())
                }
            val oriented =
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            try {
                output.outputStream().use {
                    require(oriented.compress(Bitmap.CompressFormat.JPEG, 92, it))
                }
            } finally {
                if (oriented !== bitmap) oriented.recycle()
                bitmap.recycle()
            }
            require(output.length() <= 15 * 1024 * 1024) {
                "После обработки фото больше 15 МБ. Снимите чек частями."
            }
            return name
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            raw.delete()
        }
    }
}
