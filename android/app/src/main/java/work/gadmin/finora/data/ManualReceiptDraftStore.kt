package work.gadmin.finora.data

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Separate from the photo manifest, private to server/user/organization scope. */
class ManualReceiptDraftStore(context: Context, scope: String) {
    private val directory = File(context.noBackupFilesDir, "drafts/$scope").apply { mkdirs() }
    private val file = AtomicFile(File(directory, "manual-receipt.json"))

    fun read(): ManualReceiptDraft? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        require(file.baseFile.length() <= ManualReceiptDraft.MAX_SIZE * 4L)
        return ManualReceiptDraft.decode(file.readFully().decodeToString())
    }

    fun save(draft: ManualReceiptDraft) {
        val bytes = draft.encode().toByteArray()
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun clear() {
        file.delete()
        check(
            !file.baseFile.exists() &&
                !File(file.baseFile.path + ".bak").exists() &&
                !File(file.baseFile.path + ".new").exists()
        )
    }
}
