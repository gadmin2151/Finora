package work.gadmin.finora.updates

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val valid =
        UpdateManifest(
            1,
            UpdatePolicy.PACKAGE_NAME,
            31,
            "1.17.0",
            26,
            "https://finora.gadmin.work/assets/mobile/finora-1.17.0.apk",
            172390076,
            "a".repeat(64),
            UpdatePolicy.CERTIFICATE,
            "New version",
        )

    private fun rejected(value: UpdateManifest) {
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.validate(value, 37) }
    }

    @Test
    fun officialManifestIsAccepted() {
        assertEquals(valid, UpdatePolicy.validate(valid, 37))
    }

    @Test
    fun downgradeAndSameVersionCannotInstall() {
        assertThrows(IllegalArgumentException::class.java) {
            UpdatePolicy.requireUpgrade(valid, 31)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdatePolicy.requireUpgrade(valid, 32)
        }
        UpdatePolicy.requireUpgrade(valid, 30)
    }

    @Test
    fun externalAndAmbiguousUrlsAreRejected() {
        listOf(
                "http://finora.gadmin.work/assets/mobile/finora-1.17.0.apk",
                "https://evil.example/assets/mobile/finora-1.17.0.apk",
                "https://finora.gadmin.work:443/assets/mobile/finora-1.17.0.apk",
                "https://user@finora.gadmin.work/assets/mobile/finora-1.17.0.apk",
                "https://finora.gadmin.work/assets/mobile/../finora-1.17.0.apk",
                "https://finora.gadmin.work/assets/mobile/finora-1.17.0.apk?x=1",
                "https://finora.gadmin.work/assets/mobile/finora-1.17.0.apk#x",
                "https://finora.gadmin.work/assets/mobile/%66inora-1.17.0.apk",
            )
            .forEach { rejected(valid.copy(apkUrl = it)) }
    }

    @Test
    fun packageSignerAndHashAreMandatory() {
        rejected(valid.copy(packageName = "evil"))
        rejected(valid.copy(signingCertificateSha256 = "b".repeat(64)))
        rejected(valid.copy(sha256 = ""))
        rejected(valid.copy(sha256 = "z".repeat(64)))
    }

    @Test
    fun sizeSchemaAndCompatibilityAreBounded() {
        rejected(valid.copy(sizeBytes = 0))
        rejected(valid.copy(sizeBytes = UpdatePolicy.MAX_APK_BYTES + 1))
        rejected(valid.copy(schemaVersion = 2))
        rejected(valid.copy(minSdk = 38))
        rejected(valid.copy(releaseNotes = "x".repeat(4001)))
    }

    @Test
    fun archiveFactsMustMatchManifestAndInstalledSigner() {
        val archive =
            UpdateArchiveIdentity(
                UpdatePolicy.PACKAGE_NAME,
                31,
                "1.17.0",
                26,
                setOf(UpdatePolicy.CERTIFICATE),
            )
        UpdatePolicy.verifyIdentity(valid, archive, 30, setOf(UpdatePolicy.CERTIFICATE))
        for (bad in
            listOf(
                archive.copy(packageName = "evil"),
                archive.copy(versionCode = 32),
                archive.copy(versionName = "1.18.0"),
                archive.copy(minSdk = 28),
                archive.copy(certificates = emptySet()),
                archive.copy(certificates = setOf("bad")),
                archive.copy(certificates = setOf(UpdatePolicy.CERTIFICATE, "bad")),
            )) {
            assertThrows(IllegalArgumentException::class.java) {
                UpdatePolicy.verifyIdentity(valid, bad, 30, setOf(UpdatePolicy.CERTIFICATE))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdatePolicy.verifyIdentity(valid, archive, 30, setOf("debug signer"))
        }
    }
}
