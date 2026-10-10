package work.gadmin.finora.updates

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifiedDownloadTest {
    @get:Rule val temp = TemporaryFolder()
    private val bytes = ByteArray(130000) { (it % 127).toByte() }
    private val checksum
        get() = MessageDigest.getInstance("SHA-256").digest(bytes).sha256Hex()

    private fun file() = File(temp.root, "candidate.part")

    @Test
    fun completeVerifiedBytesAndProgress() {
        val path = file()
        val progress = mutableListOf<Long>()
        VerifiedDownload.receive(
            bytes.inputStream(),
            path,
            bytes.size.toLong(),
            checksum,
            progress = progress::add,
        )
        assertArrayEquals(bytes, path.readBytes())
        assertEquals(bytes.size.toLong(), progress.last())
    }

    @Test
    fun wrongHashDeletesPartialFile() {
        val path = file()
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedDownload.receive(bytes.inputStream(), path, bytes.size.toLong(), "0".repeat(64))
        }
        assertFalse(path.exists())
    }

    @Test
    fun truncatedAndExcessiveResponsesAreRejected() {
        for (expected in listOf(bytes.size - 1L, bytes.size + 1L)) {
            val path = file()
            assertThrows(IllegalArgumentException::class.java) {
                VerifiedDownload.receive(bytes.inputStream(), path, expected, checksum)
            }
            assertFalse(path.exists())
        }
    }

    @Test
    fun cancellationAndNetworkLossDeletePartialFiles() {
        val path = file()
        var checks = 0
        assertThrows(IOException::class.java) {
            VerifiedDownload.receive(
                bytes.inputStream(),
                path,
                bytes.size.toLong(),
                checksum,
                cancelled = { if (++checks == 2) throw IOException("cancel") },
            )
        }
        assertFalse(path.exists())
        val lost =
            object : InputStream() {
                override fun read(): Int = throw IOException("lost response")
            }
        assertThrows(IOException::class.java) {
            VerifiedDownload.receive(lost, path, bytes.size.toLong(), checksum)
        }
        assertFalse(path.exists())
    }
}
