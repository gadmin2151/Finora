# Security policy

Finora stores sensitive financial data. Run a supported, updated release behind HTTPS and keep the database, receipt storage, environment files and recovery identity private.

## Reporting a vulnerability

Use [GitHub private vulnerability reporting](https://github.com/gadmin2151/Finora/security/advisories/new) if available. If it is unavailable, contact the [maintainer](https://github.com/gadmin2151) to arrange a private channel before sharing sensitive details. Do not put credentials, receipt URLs, personal receipts, database dumps or working exploits against a live deployment in a public issue.

Include the affected commit/version, prerequisites, impact, and a minimal reproduction using fictional data in an isolated installation. No response SLA or bug-bounty program is promised.

## Security boundaries

- Argon2 password hashing, opaque server-side sessions, CSRF protection and per-request organization membership checks.
- Separate organization administrator, restricted member and server-owner permissions. Hiding a button is not an authorization control.
- Login admission serialized across PostgreSQL workers; password changes and account deactivation invalidate sessions and reject stale in-flight password verification.
- Actual request-byte limits enforced before parsing, plus upload/image bounds and safe image decoding. Request bodies and credentials are not logged.
- Server receipt fetching accepts validated public HTTPS destinations and uses bounded, DNS-pinned network access. The Android receipt browser and application APIs have separate credentials and trust boundaries.
- No AI-generated SQL or financial write tools. Treat provider output and receipt contents as untrusted; validate results and require mobile review before posting.
- AI keys encrypted with `SECRET_KEY`; API/worker containers run without root. Backups are encrypted with age. This is not end-to-end encryption of the database.
- Dependency audits in CI for the Python requirement set and JavaScript lockfile; multi-platform images must pass startup and authenticated API checks before publication.

The current [manual audit record](docs/AUDIT-2026-09.md) describes coverage and limitations. Passing tests or a dependency scanner is not proof that a system has no vulnerabilities.

## Operating the service

Use a unique password and valid HTTPS origin. Keep `COOKIE_SECURE=true` for public deployments. Do not expose PostgreSQL, Ollama or Docker's socket. Default Compose publishes the API only on loopback; use the provided TLS or Cloudflare overlay for public access.

Protect `.env`, `.secrets`, receipt files and backups. Losing or replacing `SECRET_KEY` prevents decryption of stored provider keys; losing the backup identity prevents recovery. Keep an offline copy of the recovery identity and test restoration into a separate installation. Never restore over a live database as a test.

Use immutable image digests for controlled updates. Apply host, container-runtime and dependency security updates. Re-run the audit on material changes to authentication, organization access, receipt fetching, AI tool capabilities, file handling or deployment scripts.

No native MFA or passkey flow is implemented in 1.5. Server compromise, a stolen unlocked device, stolen active sessions and provider-side data handling remain relevant trust boundaries. Security controls should be chosen for the actual deployment rather than interpreted as a guarantee of complete protection.
