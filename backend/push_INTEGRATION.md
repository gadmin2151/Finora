# Provider-neutral push foundation — inactive

Status: integrated in local source for candidate 1.17.0, **not deployed**. Router,
session/receipt hooks, pinned requirements and a conditional worker task are wired.
No production migration or provider activation has been performed. Default flags
remain false; capabilities stay unconfigured until explicitly enabled with successful
scoped ADC refresh. See the final integrated-candidate section for configuration,
deployment order and rollback. The separate integration/dependency patch files are
historical proposals already reflected in local source; do not apply them again.

Firebase project `finora-5fb96`, Android package `work.gadmin.finora` and HTTP v1
enablement are user-confirmed. IAM/credential handoff and physical Android delivery
acceptance remain pending. No real ADC credentials were read and no real provider
request was made during development. Earlier worker tests used a temporary synthetic
google-auth test target; the integrator's fresh mock rerun reuses this existing
synthetic environment without installing new dependencies. This document distinguishes source integration from
running production and real device delivery.

## Android contract (schema 1)

Use existing Finora HTTPS API origin and encrypted session cookie. All endpoints require
a current active user session. Mutations also require the existing `X-CSRF-Token`.
Registration, preferences and event resolution require explicit `X-Organization-ID` and
current membership; ordinary members may manage their own preferences. There is no
client-supplied owner or session ID. Request bodies reject unknown fields.

- `GET /api/push/capabilities`: `{ "schema_version": 1, "delivery_configured": false,
  "provider": null, "scope": "selected_organization" }`. Keep the UI honest: unavailable
  before integration/configuration; do not call local reminders or polling true push.
- `POST /api/push/devices`: `{ "installation_id": "<UUID>", "provider": "<adapter name>",
  "token": "<opaque token>", "enabled": true }`. Response: `{ "device_id": "<UUID>",
  "organization_id": "<UUID>", "enabled": true, "delivery_configured": false }`.
  Provider names match `[a-z][a-z0-9_-]{0,23}`; tokens 16–4096 characters, no whitespace.
  Backend is provider neutral; the FCM adapter/client uses `fcm`. Enabled
  registration alone does not enable provider delivery; preferences are opt-in too.
- `DELETE /api/push/devices/{device_id}`: `{ "ok": true }`; no org header needed.
  Same owner can revoke across organization/session changes; unknown/foreign ID is 404.
  Revocation is idempotent, cancels queued/claimed deliveries and clears ciphertext.
- `GET /api/push/preferences`: defaults below when no row exists.
- `PUT /api/push/preferences`: full replacement; missing fields reset to defaults.
  `{ "enabled": false, "payments": false, "receipts": false, "important": false,
  "timezone": "UTC", "quiet_start": null, "quiet_end": null,
  "payment_hour": 9, "lead_days": 1 }`.
  Quiet start/end are minutes after midnight (0–1439), supplied together and distinct;
  overnight ranges supported, end exclusive. Timezone is an installed IANA zone.
  Hour 0–23, lead days 0–30. All preferences belong to user + selected organization.
- `GET /api/push/events/{event_id}`: `{ "event_id": "<UUID>", "category": "receipts",
  "route": "receipts", "entity_id": "<receipt UUID>", "organization_id": "<UUID>",
  "due_date": null, "expires_at": "2026-10-12T09:00:00+00:00" }`. Requires matching owner/org, a nonrevoked enabled device binding
  to the current session, unexpired event and still-accessible/current entity. Otherwise
  404. This endpoint returns navigation metadata only; load entity details via existing
  authenticated native API. expires_at is a timezone-aware UTC ISO-8601 timestamp;
  parse both Z and +00:00. An event from another workspace cannot silently switch it.

Native route allowlist: payments → `bills` with bill UUID + ISO due date;
receipts → `receipts` with receipt UUID; important → `overview`, no entity.
These are external contract identifiers: explicitly map to current NativeRoute enum;
never interpret arbitrary URL/HTML/route strings from a push intent. On cold start,
wait for session/workspace restoration, then resolve event; onNewIntent must revalidate.
Display no stale entity data after logout/org change. Lock-screen payload is a neutral
Finora title/body and opaque event UUID only. No org/name/sum/vendor/photo/deep URL is
sent to provider. Prefer private visibility with a neutral public version.

One installation has **one current organization binding**, not automatic all-org fanout.
Save installation UUID and returned device ID in no-backup storage; resend on authenticated
login, org change and token rotation. Scope/session/token changes cancel pending events;
historical events do not replay onto newly registered/rebound devices. Token hashes are
unique per provider across owners/installations: a token cannot be taken by another
account, even after revocation. On account change/logout, native client must invalidate
its provider token and acquire a new token for next owner (FCM: deleteToken when adopted).
A UUID is an installation handle, not proof of token possession; a future provider should
add attestation/registration verification as appropriate, never treat it as authorization.

Handle 401 by authentication flow; 403 by clearing an invalid workspace binding; 409
by explicit scope selection/token refresh; 422 by correcting the request; 429 with
Retry-After (10 seconds for registration changes). Idempotent registration does not
refresh the mutation throttle. At most 10 nonrevoked installations per user; revoke
unused devices before adding more. Do not log tokens or echo validation bodies.

Online logout: revoke device while cookie/CSRF still exist, then existing auth logout.
Local logout must always clear local binding, cancel notifications, suppress messages
and invalidate provider token even offline. Offline logout cannot immediately inform
server; server checks active session before sending and its expiry bounds registration.
Generic pending OS notification may race logout, so a tap must always reauthenticate.

## Backend integration patch (not applied)

`push_integration.patch` is a small proposal for shared-file owner: import metadata into
Alembic env, mount router, call revoke hook before logout/session deletion, and enqueue
receipt-completion/error hooks within existing transactions. It does not add credentials,
provider SDKs, a scheduler process, external sends or production configuration.
Review/apply patch only after coordinating shared files; do not apply migration yet.
New table revision: `a92e7810bc43`, parent `16b4029ae831`.

Current 1.18: `receipt_created()` runs only on fresh upload/manual creation and
notifies other members with durable creation markers and stable receipt-ID dedup.
`receipt_ready()` is a compatibility no-op; historical completion/reprocess never
notifies. See [current receipt/update contract](../docs/PUSH-RECEIPTS-AND-UPDATES.md). `enqueue()` is an internal server
primitive for explicitly approved important events; no public send endpoint or financial
threshold hooks are enabled yet. Important events resolve to overview only.

Call `schedule_payments(db, now, limit=100, after_id=cursor)` inside an explicit transaction,
commit and continue cursor pages until None. Schedule tick should run at least hourly;
catch-up is bounded to 24 hours. It computes recurrence dates without generating ledger
occurrences, excludes inactive/paid/skipped bills, deduplicates each due occurrence per
recipient, and expires at local midnight after the due date. DST folds choose earliest
instant; gaps advance to next valid local minute. Receipt/important events expire after
caller-defined TTL (receipt hook 48h). Quiet time is rechecked at dispatch, and deferred
past-expiry messages are skipped. Existing settings edits can intentionally leave a
missed older event unsent; no historical replay/backfill on login.

`deliver_one(SessionLocal)` returns `disabled` without querying/claiming outbox.
The new `push_fcm.py` adapter uses official google-auth ADC with Firebase Messaging scope,
short-lived in-memory access-token refresh/cache, a fixed HTTPS FCM endpoint and redirects
disabled. Import and default FCMConfig(enabled=false) do not probe ADC. No credential/key
is generated, no token persisted by adapter, and missing ADC yields DisabledProvider.
A configured adapter requires the integrator's explicit approved activation; merely
registering Firebase or adding a client JSON does not enable backend sends.

Actual transport payload is **data-only**: device token + `{ "event_id": "UUID" }`, Android
HIGH priority, bounded TTL and restricted package name. NeutralPayload.title/body are
intentionally ignored by this adapter. Android renders its own generic visible notification;
no financial or workspace information goes through FCM. HIGH is used only for user-visible
notifications; Android must not turn these messages into silent background sync.

Typed FCM UNREGISTERED/INVALID_ARGUMENT token errors disable that token. Generic bad request,
missing project, redirects and IAM/SENDER_ID_MISMATCH errors do not invalidate device tokens;
permanent failures end that delivery. 401 invalidates cached OAuth token and retries.
408/429/5xx and transport/refresh errors retry with exponential backoff + jitter, at least
60 seconds, respecting numeric/date Retry-After (bounded to FCM's 28-day maximum TTL).
All exception codes persisted by worker are neutral; provider response text is discarded.
HTTP reads have response-size/time limits, a request deadline and 10s individual timeouts;
approved ADC must not depend on slow external executable credential providers. Keep total
send duration below the 2-minute outbox lease. Never deploy mock provider.

`push_dependencies.patch` adds google-auth==2.61.0 and its two new transitive pins
pyasn1==0.6.4 / pyasn1-modules==0.4.2 to existing lock, plus declaration range; existing
cryptography/cffi pins satisfy official dependency constraints and are not upgraded.
The patch is not applied. Apply and verify dependencies before enabling the adapter.

Worker claims with SKIP LOCKED + 2-minute leases, rechecks authorization/preferences/entity
at dispatch, limits attempts to 5 and successful sends to 20/device/hour. Add provider-wide
quota coordination and sanitized operational metrics during service integration. Replace
hardcoded false capabilities only after explicitly approved server configuration exists.

Delivery is at least once: a crash after provider accept and before database commit may
redeliver. Android must use event UUID as stable notification identity/dedup. `sent` means
provider accepted, not handset delivered. Receipt hooks flush domain changes first, isolate push inserts in a savepoint and log a
neutral warning on SQLAlchemy enqueue failure without blocking receipt completion. Internal
`enqueue()` remains transaction-owned; important-event callers must choose their error policy. No real provider guarantee is asserted.

User/session deletion, account disable, organization deletion/membership revocation and
preference changes stop subsequent sends through dispatch-time checks; FK cascades
remove records on actual deletion. Generic send already in flight may race a revocation;
strict ordering of all auth mutations is an integration concern. Never place financial
information in transport payload even after consent.

## Verification and remaining gates

PostgreSQL 17.11 QA used an independent UTF-8 disposable `finora_push_isolated_test` on a
0700 temporary Unix-socket directory; TCP disabled. See push_pg_EVIDENCE.json. Verified
upgrade -> downgrade -> re-upgrade and two clean Alembic checks. Single head is now
`a92e7810bc43`, predecessor `16b4029ae831` (corrected before any production application).
Hashes/counts of all 24 baseline tables, including fictional account marker, remained
identical across migration operations. New revision creates only four push tables/indexes/
constraints and never updates users, organizations or financial rows.

75 targeted PostgreSQL tests passed: real concurrent enqueue/token rotation, SKIP LOCKED,
claimed delivery revoked before provider send, FK session cascade, worker retries, quiet
hours/DST and official google-auth with synthetic Credentials + httpx MockTransport.
Migration metadata was also compared in memory. Full backend PostgreSQL regression passed: **516 tests** (one existing Starlette/httpx
deprecation warning). After the final clock/expired-lease change, 6 focused dispatch and
concurrency tests passed. The disposable cluster was stopped after QA. No family/prod data or real private
credential was used. Synthetic test SECRET_KEY existed only in process RAM.

Remaining gates: IAM approval/credential handoff, integrated server configuration/worker
lifecycle/global quota controls, and actual-device background delivery, Doze/force-stop,
permission denial, token rotation, offline logout and stale-tap acceptance. No production
server or database was contacted.

## Migration impact, rollout and rollback proposal (not executed)

Additive schema only: push_devices, push_preferences, push_events, push_deliveries. Foreign
keys reference existing user/session/org identities but no financial table is rewritten or
backfilled. Existing clients and old server code can run while new tables are present.
There is no mandatory full application downtime; short DDL/FK catalog locks may delay
concurrent auth/workspace writes. Use a short controlled migration step with push disabled,
lock_timeout (e.g. 5s) and statement_timeout (e.g. 60s), abort/retry rather than wait during
heavy traffic. Disposable test timing does not guarantee production lock latency.

Order: backup/check restore point; confirm sole head and current predecessor; apply additive
migration with provider disabled; deploy router/hooks with disabled capabilities; verify
existing auth/receipt workflows and schema; only after separate credential/IAM approval
configure worker and perform an explicitly approved neutral device test. Client preferences
remain opt-in. Never start a hook-using server before the tables exist.

Operational rollback: stop push worker and disable provider first; roll server code back to
pre-integration code while leaving additive tables in place. This is the least disruptive
rollback and preserves registry/preferences/outbox for diagnosis. If schema rollback is
explicitly chosen, stop/remove all push router/hooks/schedulers first, then downgrade to
16b4029ae831. It drops delivery/events/preferences/devices in dependency order and deletes
only notification metadata, so future re-enablement requires re-registration/opt-in again.
It does not undo or alter financial records. No downgrade, deployment or production action
has been performed by this task.

Official sources: [FCM HTTP v1 / ADC](https://firebase.google.com/docs/cloud-messaging/send/v1-api),
[FCM error handling](https://firebase.google.com/docs/cloud-messaging/error-codes),
[google-auth API](https://google-auth.readthedocs.io/en/latest/reference/google.auth.html).

## Integrated local candidate 1.17.0 (not deployed)

The router, receipt/session hooks, metadata import and pinned dependencies are now in
local source. `push_runtime.py` connects the provider to the existing worker through
a separate bounded asyncio task, leaving receipt jobs independent. Defaults remain
disabled. API and worker must both be deployed with `PUSH_ENABLED=true`,
`PUSH_WORKER_ENABLED=true` and `PUSH_FCM_PROJECT_ID=finora-5fb96`. Configure approved
ADC through the platform's secret mount or workload identity; credentials are never
bundled in source/APK/image. A configuration restart is required: provider readiness
is process cached. The API advertises configured only after scoped credentials are
successfully obtained, not merely because an ADC file exists. This indicates local
provider readiness, not a proof that a separate worker is alive or a device received
anything; deployment health and physical delivery acceptance remain required.

The worker scans reminder preferences every minute, uses cursor pages and existing
idempotent event keys, then performs bounded single-delivery ticks. Disabled startup
never probes ADC or accesses the push queue. Errors are logged without provider
response, credentials or tokens. No real provider calls are used in local tests.

Deployment order (requires approval): preserve database/document backup; use a
one-shot candidate-image migrator to apply additive revision `a92e7810bc43` whose
parent is `16b4029ae831` while the old API/worker continue running; verify one head,
new four tables and old records; only then deploy the new API/worker image with push
disabled (session hooks already depend on those tables even when delivery is off);
configure approved
ADC/IAM and matching project in API/worker; restart the two services; check auth-only
capabilities and worker health; opt in a synthetic QA account on a physical device;
verify foreground/background/cold start/Doze/token rotation/logout/org revocation.
Do not roll back the schema while code imports/hooks still depend on these tables.
To stop delivery first disable both flags/restart; downgrade only after disabling
push and removing dependent hooks, accepting loss of notification-only rows.
