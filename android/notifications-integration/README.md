# Integrated local Android push candidate (2026-10-10)

Current status: SDK, ApiClient wrappers, FinoraApplication callbacks, PushHost,
persisted session/gate, VM lifecycle, Activity intents and permission UI are wired in
local 1.17.0/code31 source. Default server readiness remains false. MappedPushTransport
is shared by foreground and cold rotation, with bounded Retry-After and scope checks.
Bill notifications use scoped read-only selection; no editor opens automatically.
No new APK/manifest has been published and no physical delivery is claimed.

The original worker handoff below is retained as implementation history. Its proposal
patch has already been integrated; do not apply it again. Statements that the host
is still missing or backend always hardcodes false describe the earlier handoff.
Current configuration/deployment gates are in `backend/push_INTEGRATION.md`.

---

# Isolated Android true-push client handoff (2026-10-10)

New production files live in `app/src/main/java/work/gadmin/finora/notifications`.
Only those files and new tests were edited. `integration.patch` is unapplied and
passes `git apply --check` against the current shared workspace. It adds Firebase
SDK sources, Gradle dependencies, POST_NOTIFICATIONS, a non-exported FCM service,
auto-init opt-out, and authenticated ApiClient push transport wrappers. The main
integrator owns applying/adapting that patch and all lifecycle wiring below.

## Transport and provider

Confirmed against backend `app/push_api.py`: `/api/push/capabilities`, POST
`/devices`, DELETE `/devices/{device_id}`, GET/PUT `/preferences`, GET
`/events/{event_id}`. All paths use the existing ApiClient session cookie/CSRF and
X-Organization-ID. Tokens and responses must never be logged. Supply a
`PushTransport` capturing ApiClient instances by exact PushScope, including the old
client until its registration is revoked. Map HTTP 401/403/event 404 to
`PushRevokedException`; map registration 429 and validated Retry-After <=10 seconds
to `PushRetryException`. Longer backoff stays ERROR for explicit retry. Do not
surface raw HTTP exception text or request bodies in UI/diagnostics.

`synchronize()` first checks server capabilities: schema1 + configured true + fcm
are all required. The current backend returns configured false, so UI correctly
shows UNCONFIGURED. Registration also requires configured true before READY.
Same scope/token sync dedupes. Same-user token/session/org updates use atomic
server upsert; no DELETE gap. 429 gets one bounded, scope-fenced retry. A different
server/user revokes old binding and deletes old Firebase token before registering.

Firebase project/client config has been prepared by another worker; integrator
owns copying the verified `google-services.json` to `android/app/`. No client config,
private key, service account or real token is embedded in these files. No Analytics
SDK or financial analytics events are added. Official supported dependencies checked
on 2026-10-10: Google services plugin4.5.0, Firebase BoM35.0.0, Messaging26.0.0.
Use the main firebase-messaging module, not the retired KTX artifact.

## Required host lifecycle integration

1. Make a random local push session generation on each successful fresh login;
   retain across ordinary process restore and invalidate before logout/401. Never
   use a cookie as sessionKey. PushScope contains exact HTTPS origin,user,generation,
   org. Existing NativeNavigationScope may keep its separate user@server key.
2. Use PushInstallationStore.installationId() as persistent no-backup installation
   identity. Preserve this ID through token rotation. Call enable(scope) only when
   controller READY; disable the persisted gate synchronously before logout/401,
   workspace/server changes, disabled preferences or revoked permission. Clear posted
   notifications at those points. Disable is independent of network revocation.
3. Initialize PushRuntime callbacks in Application.onCreate, synchronously even when
   Android starts the process for FCM, before starting asynchronous UI work.
   Receiver reads delivery gate + existing SessionStore identity; it must require
   saved user/server match the gate and org belongs to that user. Without valid
   restored identity or gate it drops the message. Call PushNotifications.show(data,
   gateScope,true). No network work in onMessageReceived. Foreground and background
   both use this neutral data-only path.
4. Token callback launches lifecycle-owned one-time registration using current
   authenticated scope. Persisted scope/gate, not volatile PushRuntime alone, is
   essential for cold-start messages. Token callback before auth/permission/consent
   must not enable delivery. Provider.token explicitly enables auto-init only after
   controller's consent/permission/capability checks; logout disables and deletes it.
5. Use server preferences as truth. Switch enabled through setEnabled; it preserves
   category toggles/timezone/quiet hours/lead days. Bind UI to host StateFlow after
   each operation. Category controls may call savePreferences with explicit full
   values. Recheck permission on resume, after Android settings and permission result.
6. Request POST_NOTIFICATIONS only after explicit user choice using
   ActivityResultContracts.RequestPermission on API33+. Denial must keep the app
   usable. Channel disabled uses separate UI state; settings should open this app's
   channel settings. Never automatically re-prompt after denial.
7. Cold activity launch: retain its incoming intent until the saved session and
   workspace restore completes. Foreground onNewIntent calls the same consumer.
   `consumeTap` removes event/scope extras once, validates local scope digest and
   UUID. Unknown action, stale login/server/user/org or malformed IDs are ignored.
   No web URL is accepted; PendingIntent is explicit, immutable and unique by UUID
   plus scope digest. Notification content is fixed neutral text, including public
   lockscreen version; message amounts/names/receipts/body/actions are ignored.
8. On explicit notification tap capture current PushScope/NavigationScope, fetch
   openEvent through authenticated API, recheck PushScope once more on main thread,
   and accept only Open results. Use existing openNativeNavigation for a freshly
   scoped read-only destination. Honor Unavailable/Rejected with UI, not a fallback
   screen or editor. No payment, approve, acceptReceipt or other mutation is invoked.
   For bills, PushOpenResult.targetMonth is separately validated server due date:
   only after authenticated tap and successful freshness/access resolution, select
   targetMonth using the host's normal month selector; then open the same read-only
   route/entity with its NEW NavigationScope. Captured month remains unchanged for
   stale-scope validation. Do not switch org based on any push data.
9. Call controller.logout before destroying the old ApiClient, but always perform
   local sign-out/disable gate even if network revoke fails. Backend logout/expired
   session/provider-invalid-token handling must also revoke registrations. Offline
   logout cannot prove immediate remote revocation; local display/navigation stays
   disabled. Bound ApiClients must not be retargeted to a different origin/account.
10. onDeletedMessages may request one refresh when the app next opens, or show a
    missed-events status. Do not create recurring poll timers or claim FCM delivery
    by periodically fetching events. The server provider MUST send data-only FCM
    `data:{event_id:UUID}` without a `notification` section: Android otherwise bypasses
    client neutralization in background. High priority only for actual user-visible
    events; no financial/private text in provider payload. Provider TTL must match
    event expiry. Transport/server revalidate current event access/expiry.

## Verification and capability limits

Run `python3 android/notifications-integration/run-isolated-tests.py` from workspace.
It compiles only core/contract/native resolver + JUnit into independent output;
no Gradle daemon, shared Gradle task or common source edits. 27/27 tests PASS.
Covers permission/channel denial, capability false, scoped auth fetch, stale scopes,
rotation/upsert/429 retry, retry scope changes, revoke failure/logout token deletion,
preserved preferences, injection rejection, redacted token, future due month.
Kotlin files were formatted with the existing ktfmt0.64 tool.
Four Android intent tests are supplied in the isolated androidTest package;
not executed here. Compose UI, Android platform notification posting, Firebase SDK
resolution and physical-device delivery still require integrated build/lint/device
verification by the owner. The patch alone does not install the Application,
ViewModel, settings or MainActivity hooks above; do not report production-ready push
until those hooks and backend provider/migration/outbox/event producers are enabled.

No publication/deploy/IAM/credentials changes were performed. Real FCM delivery is
blocked by backend sender credentials/provider configuration and unapplied lifecycle
wiring. Android force-stop, OEM battery policies, Doze/network loss and expired TTL
can delay/drop data messages; FCM is not a guaranteed financial alarm. Devices need
Google Play services; no alternative provider or polling fallback is included.

Official references:
- https://firebase.google.com/docs/android/setup
- https://firebase.google.com/support/release-notes/android
- https://firebase.google.com/docs/cloud-messaging/android/get-started
- https://firebase.google.com/docs/cloud-messaging/android/receive-messages
