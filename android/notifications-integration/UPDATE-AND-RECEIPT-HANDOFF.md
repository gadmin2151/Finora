# APK update and subscribed receipt push: Android handoff

Implemented only Android notifications files, PushSettingsCard and tests. No changes
to manifest, release version, public APKs, updater trust policy, receipt processing,
Application or ViewModel. Main integrator owns final build/publish and shared hooks.

## Compatible wire additions

- Capability `update_notifications=true` enables registration fields
  `installed_version_code`/`installed_version_name` (PackageManager facts), and
  preference `updates` (explicit opt-in, false by default). On an old 1.17 server,
  exact old JSON fields are retained and the new settings switch is hidden.
- Event `category=update_available`, `route=updater`, null entity/due date, integer
  version_code and version_name, expires_at. Client requires a higher version than
  its actual installed code, valid identity/expiry and captured scope. No URL/hash/
  signer/install command comes from a push. It returns a dedicated updater target,
  never a financial command or generic settings/web route.
- FCM hints `category=update_available`, version_code/name permit pre-display
  filtering of already-installed versions and local no-backup highest-notified
  version dedupe keyed by server/user (not a global scalar). Server owns per-device one-event/new-release dedupe and eligibility.
  Foreground/background use the same neutral notification. Tap still authenticates
  the event before opening updater. Existing HTTPS/hash/signature checks are untouched.
- Capability `receipt_notifications_all_organizations=true` enables registration
  all_organizations, default false. Explicit installation opt-in is stored noBackup
  and reset on fresh login/logout/401; process restore preserves it. It does not write
  any other organization's notification preferences.
- Unscoped authenticated GET `/api/push/subscriptions` returns receipt_organizations
  UUIDs. Registration/gate may reach READY with current preferences.disabled if
  all-org opt-in is active and at least one authorized receipt subscription exists.
  Notification permission, same-user/session/server fences remain mandatory.
- Unscoped authenticated GET `/api/push/events/{UUID}/context` returns only
  event_id/category/organization_id, after server membership/event/prefs/device/session
  checks. PushTransport.getContext uses ApiClient.pushGet(path,null); MappedPushTransport
  checks current scope before/after response. Only an explicit tap invokes this.

Backend executor confirmed the additive event/registration contract and cross-org
permission only for receipt events with matching current device/session/all-org opt-in.

## Main integrator hooks

PushHost now accepts optional final callbacks:
- `onOpenUpdater: () -> Unit`: select profile/updater card. DO NOT call check/download/
  install from this callback. User uses normal existing buttons and verification flow.
- `onOpenReceiptOrganization: (orgId:String,eventId:String) -> Unit`: capture
  `originalScope=push.currentScope()` BEFORE selecting the exact verified organization
  from current user's organizations. Once normal workspace selection settles, invoke
  `push.openReceiptEvent(eventId,originalScope,orgId)`. This rechecks user/server/session,
  opt-in/current target, authenticates the detail event in its new org, and accepts
  only a read-only RECEIPTS destination. No background org switching is performed.

Application cold token path must instantiate:
`PushApiContract(mappedTransport, installedPushVersion(this@FinoraApplication)) { navScope }`
and `api.setReceiptAllOrganizations(store.receiptAllOrganizations())`.
Its unscoped transport method must call `client.pushGet(path,null)` with current-scope
fencing. Pass installed version getter and `store::receiptAllOrganizations` as final
PushController constructor args. This ensures cold token rotation preserves the
metadata and subscription-only gate. Application/VM remain main-owned files.

## Verification

`python3 android/notifications-integration/run-isolated-tests.py`: 51 tests PASS.
Output uses a separate classes folder, no Gradle invocation/shared build outputs.
Existing old-wire tests still PASS. Added newer/equal/older/malformed/duplicate version,
ignored URLs/actions, exact updater/expiry/entity rules, capability-gated metadata,
subscriptions-only READY without preference writes, permission/subscription failure,
foreign receipt membership/opt-in/context/current scope and settled session tests.
Kotlin formatting completed using repository ktfmt0.64.

Integrated Android build/lint, updater callback UX and cold/foreground multi-org device
paths still need main's integrated verification. No real push, secrets, IAM, installation,
release version change or publishing was performed by this worker.

## Receipt intent scope correction

Poster uses receipt identity digest (server/user/local login generation, with an explicit
mode/category marker) only when category=receipts and persisted local all-org opt-in
is enabled. This survives an explicit organization switch/cold-start workspace chooser.
Host consumeTap accepts it only while that opt-in is still enabled; it subsequently
requires authenticated context.category=receipts and routes only to a receipt detail.
Normal/legacy intents retain the exact full workspace digest, including organization;
updates/payments cannot use the receipt identity mode. Logout/new login/account/server
changes reject both modes appropriately. New marker extras are consumed/removed once.

Update dedupe record filename includes a hashed server/user key, so one user's release
notice cannot suppress another user's or another server's notice. Same user's workspace
or session changes retain dedupe; profile display-name changes do not alter user ID.
The old unscoped scalar record is deliberately not attributed to any user or migrated.

Six new pure policy tests are included in the 51 PASS total; three additional Android
intent acceptance tests are supplied (seven intent tests total), not run by this worker.
