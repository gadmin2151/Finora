package work.gadmin.finora.updates

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** A partial or corrupt file is never returned to an APK parser or installer. */
object VerifiedDownload {
    fun receive(
        input: InputStream,
        destination: File,
        size: Long,
        sha256: String,
        cancelled: () -> Unit = {},
        progress: (Long) -> Unit = {},
    ) {
        require(size in 1..UpdatePolicy.MAX_APK_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var verified = false
        try {
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    cancelled()
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= size) { "APK exceeds expected size" }
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    progress(total)
                }
                cancelled()
                require(total == size && digest.digest().sha256Hex() == sha256) {
                    "APK checksum or size mismatch"
                }
                output.fd.sync()
            }
            verified = true
        } finally {
            if (!verified) destination.delete()
        }
    }
}

internal fun ByteArray.sha256Hex() = joinToString("") { "%02x".format(it) }
