# Installation and operations

[← Back to Finora](../README.md) · [Русская инструкция](../README.ru.md)

## Current deployment · 12 October 2026

The required-expense receipts and debt-card website/API from source `16335b4` and
Android 1.22.0 are published. Schema `d81ac57e09f2` is unchanged. Historical paid
expenses were backfilled into linked receipts after a verified encrypted backup;
financial amounts, postings, allocations and original documents were preserved.
The accepted overlay is `/opt/finora-mandatory-debt-122-20261012/compose.accepted-image.yaml`;
preserve all preceding Compose overlays. The release directory contains
`START-AND-ROLLBACK.txt`, rollback overlay and prior APK manifest. Image rollback
retains new receipts; read its cancellation-compatibility note before reverting.
The old server checkout is not the running source. [Verification and recovery](REQUIRED-EXPENSES-DEBT-122.md).

## Install Finora

### Debian 12 / 13 server

Use a dedicated server with Docker support, Git and Python 3.11+. The installer can install Docker Engine and Compose from Docker's official repository.

```bash
sudo git clone https://github.com/gadmin2151/Finora.git /opt/finora
cd /opt/finora
sudo ./scripts/install-debian.sh --domain finance.example.com
```

Replace the example domain with your own. For automatic HTTPS through Caddy, point DNS to the server and allow ports 80/443. The API stays on loopback; PostgreSQL is not exposed publicly.

The first account is `admin`. Read its **random initial password** on the server:

```bash
sudo cat /opt/finora/.secrets/admin_password
```

Change it in **Настройки → Безопасность**. The initial-password file is not updated when you change your password. There is no public registration or shared default password.

### Local Docker installation

```bash
git clone https://github.com/gadmin2151/Finora.git
cd Finora
python3 scripts/init.py
python3 scripts/deploy.py
```

Open [localhost:8088](http://localhost:8088). The scripts build the application, initialize secrets, apply migrations and wait for readiness. Docker Desktop works for local development; Android connections require a valid HTTPS endpoint.

### Cloudflare Tunnel

For a server without inbound ports, install with `--port 8080`, then follow the [Cloudflare setup](../README.ru.md#cloudflare-tunnel). A token file is mounted into `cloudflared`; tokens do not belong in Compose files, process arguments, screenshots or Git. Keep `APP_URL` equal to your public HTTPS origin and `COOKIE_SECURE=true`.

The [LXC overlay](../README.ru.md#ограниченные-lxc-контейнеры) is available for restricted Linux containers. It uses host networking with loopback-only API and PostgreSQL listeners; do not use it as a general Docker default.

## Updates and recovery

Back up **both** the database/receipt files and the recovery identity. Keep `.env`, `.secrets` and named volumes; a new `SECRET_KEY` cannot decrypt your existing AI keys.

```bash
git pull --ff-only
sudo python3 scripts/deploy.py
```

The deploy script creates an encrypted backup before an update, runs migrations and verifies readiness. It does not automatically select a different image tag. For controlled deployments set `FINORA_IMAGE` to a reviewed immutable GHCR digest in `.env`; use the same image for API, worker and init.

See [backup and isolated restore instructions](../README.ru.md#резервное-копирование-и-восстановление). Rehearse restoration on a separate installation before relying on a backup. Backups contain sensitive data; publish neither archives nor recovery keys.

## Categories and recognition maintenance

New organizations start with 34 categories, including groceries, sweets, coffee and tea, energy drinks, tobacco, household supplies and personal care. Organization admins can install the additional presets and reclassify existing receipts under **Settings → Categories and rules**. Reclassification applies explicit user rules first, preserves custom categories and unknown assignments, and updates the category allocations used by reports. It does not change money, account postings, currencies or receipt originals. Drafts remain drafts. Refund-linked receipts and inconsistent monetary records are skipped with an explanation instead of guessing.

Receipt recognition separates the printed seller and address from products and fiscal identifiers, including full SFS pages captured by a phone. Existing contaminated names are repaired only when the original text has a unique matching quantity, price and line total. Changes are audited. The optional `merchant_address` API field is backward compatible and stored in existing receipt metadata; no database schema migration is required.

The organization-scoped admin endpoints are `POST /api/categories/daily` and `POST /api/categories/refresh` with `{ "after": "" }`. Refresh processes up to 50 receipts per request; pass `next_cursor` as `after` until it is null. A repeated run is safe.

Bank payment confirmations can also be uploaded as photos or screenshots. Recognition distinguishes the charged amount/currency from the original transaction currency, extracts the payee/date/status, and represents the payment as one line. It never counts both currencies as expenses or invents purchased products. Bank confirmations always require review, including pending or declined payments; existing manually posted records are not reprocessed.

## Historical nightly recovery checkpoint, 2026-10-10

This section records the early recovery checkpoint, not the current runtime state.
The later [system-settings deployment evidence](SYSTEM-SETTINGS-DEPLOYMENT.json)
and [runtime/rollback notes](SYSTEM-SETTINGS.md#production-deployment--10-october-2026)
supersede its deployment status. That deployment applied schema `d81ac57e09f2`
and used a verified image with preserved Compose overlays; the server checkout
remained older than the runtime. Check those image/configuration bindings before
an update instead of treating the checkout or inherited image labels as authoritative.

The private encrypted recovery bundle created before the nightly work includes
application/configuration files, receipt originals, a consistent PostgreSQL dump,
existing signing material and saved container images. The recovery identity was
preserved privately; neither archives nor keys belong in release downloads.
Decryption/checksum checks and a restore into a separate local PostgreSQL instance
passed. A complete application restore on a second host has not been performed.
The bundle is on the production host, so it does not protect against losing that
host or disk; a separately authorized secure copy is still useful.

At this earlier checkpoint, the prepared server image had not been deployed:
new Docker containers failed before application startup inside LXC. Running
containers were preserved pending infrastructure recovery. The prepared image
rollback only selects the prior API/worker image; it does not restore the database,
receipt files or configuration. Any complete recovery must use an isolated target,
verify the existing recovery identity and data checksums, restore into empty test
volumes, then verify users/organizations, receipt files and account reconciliation
before a separately reviewed production change. Do not regenerate encryption or
signing keys, restore over live data, or run deployment/migration scripts merely
to validate the backup.
