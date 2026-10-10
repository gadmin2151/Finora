# Android updater: local implementation and publication contract

The published **1.16.0 / code 30** APK is unchanged. Its source snapshot is
`.local/native-20261010/published-source-1.16.0/source.tar.gz`; the adjacent manifest
binds source file hashes, APK SHA-256 and the release signing certificate.
Archive SHA-256: `0fe86c37fb44e4fff24ba25e3ee1abecdfc89bbb2fa68eba2dac886e82ca35b3`.
This snapshot includes documentation/tests completed after the release build; it is
provenance for the reviewed working tree, not a claim of reproducible APK bytes.

The next APK contains a Profile → App updates card. Checking, downloading and
installing are separate explicit actions. Download progress, cancel and retry are
visible. A missing channel is shown as unavailable; no automatic finance login,
background installation or browser fallback is used.

## Static manifest (not published)

Fixed URL: `https://finora.gadmin.work/assets/mobile/update.json`.
No new backend endpoint, credentials, database table or migration is needed.
A separate OkHttp client uses Android system CA validation, no auth headers/cookies,
and rejects redirects. Both manifest and APK must stay on the fixed official HTTPS
origin. Custom finance server settings do not override the release channel.

```json
{
  "schemaVersion": 1,
  "packageName": "work.gadmin.finora",
  "versionCode": 31,
  "versionName": "1.17.0",
  "minSdk": 26,
  "apkUrl": "https://finora.gadmin.work/assets/mobile/finora-1.17.0.apk",
  "sizeBytes": 123456789,
  "sha256": "<64 lowercase hexadecimal characters>",
  "signingCertificateSha256": "3a3c14d603cc9cfeff5092ece5101acc60512787e90f1330292d98e4f45ee6c0",
  "releaseNotes": "User-visible changes"
}
```

The values above are a contract example, **not an existing 1.17.0 release**.
Generate actual values from the final signed APK with:

```sh
python scripts/build-android-update-manifest.py artifacts/android/finora-VERSION.apk \
  --output artifacts/android/update-VERSION.json --release-notes 'Release changes'
```

The generator runs apksigner verification and reads package/version/minSDK from
the APK. It accepts only the existing release certificate, computes size/hash,
creates a new local output, and never publishes. Only the public certificate digest
belongs in the manifest; the signing private key is never included.

## Installation gates

* Require supported schema/package, bounded manifest (64 KiB), bounded APK
  (512 MiB), supported minSDK, safe fixed-origin APK filename and exact SHA-256.
* Require candidate versionCode strictly greater than the currently installed one,
  both before downloading and immediately before installation. Same version and
  downgrade are rejected.
* Download to app-private cache; cancellation/network loss/hash mismatch removes
  the partial file. Fsync complete verified bytes before handing them to Android.
  Cache is not trusted after process recreation; the user downloads again.
* Android PackageManager parses the APK. Its actual package, versionCode,
  versionName, minSDK and signer must match the manifest. Both installed and
  downloaded APK must have exactly the pinned existing release certificate.
  Debug builds therefore cannot install release updates through this mechanism.
* Recheck the cached hash and identity before handing a narrow FileProvider URI
  to the Android package installer. No broad external storage access is requested.
* REQUEST_INSTALL_PACKAGES is declared. If canRequestPackageInstalls is false,
  open ACTION_MANAGE_UNKNOWN_APP_SOURCES for this package. Returning from settings
  does not install: the user taps Install again. Android's own installer performs
  its final verification and asks the user to confirm. Device policy/developer
  verification can prevent installation; this app does not bypass it.

APK signer rotation is deliberately unsupported by this initial contract. Changing
that trust anchor requires a separate reviewed migration of the installed client.

## Controlled publication later

Prepare and test the new signed APK first. After explicit publication authorization,
publish its immutable versioned filename, verify a full HTTPS GET, then atomically
replace the small update.json pointer. Preserve old APKs. Manifest must reference
only the verified published bytes. Give update.json a short/no-cache policy in the
existing serving layer if already supported; otherwise document cache delay before
changing server configuration. No runtime service restarts are required by this
proposal. The 1.17 manifest was published and verified on 2026-10-10; subsequent releases
follow this same readback procedure.

Official references: [PackageManager](https://developer.android.com/reference/android/content/pm/PackageManager),
[SigningInfo](https://developer.android.com/reference/android/content/pm/SigningInfo),
[secure FileProvider sharing](https://developer.android.com/training/secure-file-sharing/setup-sharing).

## Historical 1.17.0 candidate

Version code 31 includes the updater and conditional push host integration. It is
separate from the published 1.16.0/code30 APK. No new APK or update manifest is
published automatically. After signed candidate acceptance, publication requires
approval for a new immutable APK/checksum and an atomic `update.json` replacement.
The manifest must be generated from the final signed APK; do not manually copy a
version, size, hash or signing certificate. Retain 1.16.0 and its frozen source
snapshot. Physical push delivery is a separate credentials/device acceptance gate.

## 1.18 receipt/update notifications

See [current receipt and release notification contract](PUSH-RECEIPTS-AND-UPDATES.md).
