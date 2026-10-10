package work.gadmin.finora.updates

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** Separate unauthenticated client: no finance credentials, cookies or server overrides. */
class UpdateController(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val client =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
    private val directory = File(app.cacheDir, "updates")
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    @Volatile private var call: Call? = null
    @Volatile private var generation = 0L
    private val json = Json { ignoreUnknownKeys = false }

    init {
        directory.mkdirs()
        // Process recreation never trusts or resumes unverified cached APKs.
        directory
            .listFiles()
            ?.filter { it.name.endsWith(".part") || it.name.endsWith(".apk") }
            ?.forEach { it.delete() }
    }

    fun check() =
        run(UpdatePhase.CHECKING) { token ->
            val manifest =
                withContext(Dispatchers.IO) {
                    val request =
                        client.newCall(
                            Request.Builder()
                                .url(UpdatePolicy.MANIFEST_URL)
                                .header("Accept", "application/json")
                                .build()
                        )
                    call = request
                    request.execute().use { response ->
                        if (response.code == 404) error("Update channel is not published yet")
                        check(response.isSuccessful) { "Update server returned ${response.code}" }
                        val body = requireNotNull(response.body)
                        require(body.contentLength() <= UpdatePolicy.MAX_MANIFEST_BYTES) {
                            "Update manifest is too large"
                        }
                        val bytes =
                            body.byteStream().use { input ->
                                val output = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(4096)
                                while (true) {
                                    ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    require(output.size() + n <= UpdatePolicy.MAX_MANIFEST_BYTES) {
                                        "Update manifest is too large"
                                    }
                                    output.write(buffer, 0, n)
                                }
                                output.toByteArray()
                            }
                        require(bytes.size <= UpdatePolicy.MAX_MANIFEST_BYTES) {
                            "Update manifest is too large"
                        }
                        UpdatePolicy.validate(
                            json.decodeFromString<UpdateManifest>(bytes.toString(Charsets.UTF_8)),
                            Build.VERSION.SDK_INT,
                        )
                    }
                }
            if (generation == token)
                mutable.value =
                    UpdateState(
                        phase =
                            if (manifest.versionCode > installedVersion()) UpdatePhase.AVAILABLE
                            else UpdatePhase.CURRENT,
                        manifest = manifest,
                    )
        }

    fun download() {
        val manifest = mutable.value.manifest ?: return
        run(UpdatePhase.DOWNLOADING, manifest) { token ->
            val completed =
                withContext(Dispatchers.IO) {
                    UpdatePolicy.requireUpgrade(manifest, installedVersion())
                    check(ownCertificates() == setOf(UpdatePolicy.CERTIFICATE)) {
                        "Installed application has a different signer"
                    }
                    check(directory.usableSpace > manifest.sizeBytes + 16 * 1024 * 1024) {
                        "Not enough storage space"
                    }
                    val part = File(directory, "candidate.part")
                    val apk = File(directory, "candidate.apk")
                    part.delete()
                    apk.delete()
                    try {
                        val request = client.newCall(Request.Builder().url(manifest.apkUrl).build())
                        call = request
                        request.execute().use { response ->
                            check(response.code == 200) { "APK server returned ${response.code}" }
                            val body = requireNotNull(response.body)
                            require(
                                body.contentLength() == -1L ||
                                    body.contentLength() == manifest.sizeBytes
                            ) {
                                "APK size changed"
                            }
                            body.byteStream().use { input ->
                                VerifiedDownload.receive(
                                    input,
                                    part,
                                    manifest.sizeBytes,
                                    manifest.sha256,
                                    cancelled = { ensureActive() },
                                    progress = { total ->
                                        if (generation == token)
                                            mutable.value =
                                                UpdateState(
                                                    UpdatePhase.DOWNLOADING,
                                                    manifest,
                                                    total,
                                                )
                                    },
                                )
                            }
                        }
                        verifyArchive(part, manifest)
                        ensureActive()
                        check(part.renameTo(apk)) { "Cannot store verified APK" }
                        apk
                    } finally {
                        part.delete()
                    }
                }
            if (generation == token)
                mutable.value =
                    UpdateState(UpdatePhase.READY, manifest, manifest.sizeBytes, completed)
            else completed.delete()
        }
    }

    /** Always called by an explicit Install tap; permission return never silently installs. */
    suspend fun installIntent(): Intent? =
        withContext(Dispatchers.IO) {
            val before = mutable.value
            val file = before.file ?: return@withContext null
            val manifest = before.manifest ?: return@withContext null
            try {
                verifyArchive(file, manifest)
                require(
                    file.length() == manifest.sizeBytes &&
                        file.inputStream().use { input ->
                            val digest = MessageDigest.getInstance("SHA-256")
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                digest.update(buffer, 0, n)
                            }
                            digest.digest().hex()
                        } == manifest.sha256
                ) {
                    "Cached APK changed; download again"
                }
                if (!app.packageManager.canRequestPackageInstalls()) {
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${app.packageName}"),
                    )
                } else {
                    val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.value = before.copy(phase = UpdatePhase.ERROR, error = error.message)
                null
            }
        }

    fun installerError() {
        mutable.value =
            mutable.value.copy(
                error = "Android could not open the installer or permission settings"
            )
    }

    fun cancel() {
        generation++
        call?.cancel()
        job?.cancel()
        call = null
        mutable.value = UpdateState(manifest = mutable.value.manifest)
    }

    private fun run(
        phase: UpdatePhase,
        manifest: UpdateManifest? = null,
        action: suspend (Long) -> Unit,
    ) {
        if (job?.isCompleted == false) return
        val token = ++generation
        mutable.value = UpdateState(phase, manifest)
        job = viewModelScope.launch {
            try {
                action(token)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == token)
                    mutable.value =
                        UpdateState(
                            UpdatePhase.ERROR,
                            manifest,
                            error = error.message ?: "Update failed",
                        )
            } finally {
                if (generation == token) call = null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(): PackageInfo =
        app.packageManager.getPackageInfo(app.packageName, signingFlags())

    @Suppress("DEPRECATION")
    private fun installedVersion(): Long =
        packageInfo().let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
        }

    private fun ownCertificates() = certificates(packageInfo())

    @Suppress("DEPRECATION")
    private fun signingFlags() =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }
            ?.toSet()
            .orEmpty()

    @Suppress("DEPRECATION")
    private fun verifyArchive(file: File, manifest: UpdateManifest) {
        UpdatePolicy.validate(manifest, Build.VERSION.SDK_INT)
        UpdatePolicy.requireUpgrade(manifest, installedVersion())
        val info =
            requireNotNull(
                app.packageManager.getPackageArchiveInfo(file.absolutePath, signingFlags())
            ) {
                "Android cannot verify this APK"
            }
        val version =
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        require(app.packageName == UpdatePolicy.PACKAGE_NAME) { "Wrong installed package" }
        UpdatePolicy.verifyIdentity(
            manifest,
            UpdateArchiveIdentity(
                info.packageName,
                version,
                info.versionName,
                info.applicationInfo?.minSdkVersion,
                certificates(info),
            ),
            installedVersion(),
            ownCertificates(),
        )
    }

    override fun onCleared() {
        call?.cancel()
        super.onCleared()
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

enum class UpdatePhase {
    IDLE,
    CHECKING,
    CURRENT,
    AVAILABLE,
    DOWNLOADING,
    READY,
    ERROR,
}

data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val manifest: UpdateManifest? = null,
    val downloaded: Long = 0,
    val file: File? = null,
    val error: String? = null,
)
