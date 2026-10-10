# Receipt and APK update notifications (1.18 / code 32)

New uploads and manual receipts enqueue a neutral notification for other current
organization participants. The author is excluded. Creation eligibility is saved
atomically with the receipt, even when preferences or devices suppress delivery.
A receipt ID has one creation notification per recipient. Duplicate uploads,
manual request retries, OCR completion, review and historical reprocessing cannot
create another notification. Existing receipts are not backfilled.

Each recipient must enable notifications and the receipts category in the relevant
organization. Quiet hours are applied again before delivery. Session, user,
organization, membership, category and document availability are checked before
send and again on tap. No merchant, amount, item, bank data or credential is put in
FCM/Android notification payloads.

The optional Android switch for receipts from all subscribed organizations is off
by default. It does not enable another organization's preferences. Only an explicit
notification tap can select a verified organization; the normal workspace must
settle before a fresh authenticated event/detail fetch opens the native receipt.
Logout, account/server change, lost membership and deleted documents reject stale
links. A 403/404 detail response closes cached receipt detail.

APK update notifications also require explicit category opt-in. Only installations
reporting a known older PackageManager version can receive them. Legacy 1.17 clients
with unknown installed-version metadata are skipped. The first upgrade to 1.18 must
use the existing update checker or the published APK link. Future update notices are
deduplicated by owner/installation/numeric release version. Quiet hours, revocation,
expiry, withdrawn release and installation upgrade are rechecked before send/tap.

A trusted publisher attests a release only after public HTTPS manifest and complete
APK readback match package, version, signer, size and SHA-256. There is no public send
or publication API. Tapping an update notification opens the existing native updater
card; it never downloads or installs automatically. Existing verification and Android
installation confirmation remain mandatory.

Schema revision `b73f9012dca4` extends `a92e7810bc43` additively. It creates creation
markers, release attestations and installation dedup tables, plus optional metadata
and false-by-default preferences/capability fields. It contains no historical backfill
or financial DML. Keep the existing push activation overlay, read-only ADC key mount
and SECRET_KEY unchanged during image rollout. Rollback restores the previous image
and leaves additive tables intact; migration downgrade is not a data rollback.

Verification evidence is separate from physical device delivery acceptance. Automated
tests use synthetic documents, accounts and fake FCM transport only.
