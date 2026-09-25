package work.gadmin.finora.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.core.content.edit
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Session cookies are encrypted with a non-exportable device key, never backed up. */
class SessionStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "session.enc"))
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    var lastServer: String
        get() = prefs.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) {
            prefs.edit { putString("server", value) }
        }

    var lastUsername: String
        get() = prefs.getString("username", "") ?: ""
        set(value) {
            prefs.edit { putString("username", value) }
        }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("finora.session.v1", null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            "finora.session.v1",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    fun read(): SavedSession? {
        if (!file.baseFile.exists()) return null
        return try {
            val bytes = file.readFully()
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            Json.decodeFromString<SavedSession>(
                cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString()
            )
        } catch (_: Exception) {
            // A replaced/invalidated Android Keystore key requires fresh authentication.
            clear()
            null
        }
    }

    fun save(session: SavedSession) {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(Json.encodeToString(session).toByteArray())
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        lastServer = session.server
        lastUsername = session.user.username
    }

    fun clear() = file.delete()
}
