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
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

/** App-private, backup-excluded photos. Every workspace has an independent draft. */
class DraftStore(private val context: Context, scope: String) {
    private val directory = File(context.noBackupFilesDir, "drafts/$scope").apply { mkdirs() }
    private val manifest = AtomicFile(File(directory, "draft.json"))
    private val photoName = Regex("^[a-f0-9-]{36}\\.jpg$")

    fun read(): Draft {
        if (!manifest.baseFile.exists()) return Draft()
        val draft = Json.decodeFromString<Draft>(manifest.readFully().decodeToString())
        require(draft.photos.size <= MAX_PHOTOS && draft.photos.all { photoName.matches(it) }) {
            tr(Message.THE_DRAFT_IS_DAMAGED)
        }
        require(draft.photos.all { photo(it).isFile }) { tr(Message.DRAFT_PHOTOS_ARE_UNAVAILABLE) }
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
            ?: throw IllegalArgumentException(tr(Message.COULD_NOT_OPEN_THE_PHOTO))

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
                        tr(Message.THIS_PHOTO_EXCEEDS_50_MB_CHOOSE_A_SMALLER_ONE)
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
                tr(Message.CHOOSE_A_JPG_PNG_WEBP_OR_HEIC_PHOTO_UP_TO_100_MP)
            }
            val options =
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = 1
                    while (
                        maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 16_000 ||
                            bounds.outWidth.toLong() * bounds.outHeight /
                                inSampleSize /
                                inSampleSize > 16_000_000
                    ) inSampleSize *= 2
                }
            val bitmap =
                BitmapFactory.decodeFile(raw.path, options)
                    ?: throw IllegalArgumentException(tr(Message.COULD_NOT_READ_THE_PHOTO))
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
                    require(oriented.compress(Bitmap.CompressFormat.JPEG, 97, it))
                }
            } finally {
                if (oriented !== bitmap) oriented.recycle()
                bitmap.recycle()
            }
            require(output.length() <= 15 * 1024 * 1024) {
                tr(Message.THE_PROCESSED_PHOTO_EXCEEDS_15_MB_CAPTURE_THE_RECEIPT_IN_P)
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
