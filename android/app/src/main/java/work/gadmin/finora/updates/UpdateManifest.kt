package work.gadmin.finora.updates

import java.net.URI
import kotlinx.serialization.Serializable

@Serializable
data class UpdateManifest(
    val schemaVersion: Int,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apkUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val signingCertificateSha256: String,
    val releaseNotes: String = "",
)

object UpdatePolicy {
    const val MANIFEST_URL = "https://finora.gadmin.work/assets/mobile/update.json"
    const val PACKAGE_NAME = "work.gadmin.finora"
    const val CERTIFICATE = "3a3c14d603cc9cfeff5092ece5101acc60512787e90f1330292d98e4f45ee6c0"
    const val MAX_APK_BYTES = 512L * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 64 * 1024

    fun validate(manifest: UpdateManifest, sdk: Int): UpdateManifest {
        require(manifest.schemaVersion == 1) { "Unsupported update manifest" }
        require(manifest.packageName == PACKAGE_NAME) { "Wrong application package" }
        require(
            manifest.versionCode in 1..Int.MAX_VALUE.toLong() &&
                manifest.versionName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))
        ) {
            "Invalid version"
        }
        require(manifest.minSdk in 26..sdk) { "Update does not support this Android version" }
        require(manifest.sizeBytes in 1..MAX_APK_BYTES) { "Invalid APK size" }
        require(manifest.sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid APK checksum" }
        require(manifest.signingCertificateSha256 == CERTIFICATE) { "Untrusted update signer" }
        require(manifest.releaseNotes.length <= 4000) { "Update notes are too large" }
        val uri = URI(manifest.apkUrl)
        require(
            uri.scheme == "https" &&
                uri.host == "finora.gadmin.work" &&
                uri.port == -1 &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null
        ) {
            "Untrusted update URL"
        }
        require(
            uri.rawPath.matches(Regex("/assets/mobile/finora-[0-9]+\\.[0-9]+\\.[0-9]+\\.apk"))
        ) {
            "Invalid APK path"
        }
        return manifest
    }

    fun requireUpgrade(manifest: UpdateManifest, installed: Long) {
        require(manifest.versionCode > installed) { "A newer version is required" }
    }
}

/** Facts obtained by Android's APK parser; the manifest cannot choose the trust anchor. */
data class UpdateArchiveIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int?,
    val certificates: Set<String>,
)

fun UpdatePolicy.verifyIdentity(
    manifest: UpdateManifest,
    archive: UpdateArchiveIdentity,
    installedVersion: Long,
    installedCertificates: Set<String>,
) {
    requireUpgrade(manifest, installedVersion)
    require(
        archive.packageName == PACKAGE_NAME &&
            archive.packageName == manifest.packageName &&
            archive.versionCode == manifest.versionCode &&
            archive.versionName == manifest.versionName
    ) {
        "APK identity or version mismatch"
    }
    require(archive.minSdk == manifest.minSdk) { "APK Android compatibility mismatch" }
    require(
        installedCertificates == setOf(CERTIFICATE) && archive.certificates == installedCertificates
    ) {
        "APK signature does not match installed application"
    }
}
