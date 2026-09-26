# Management and source retention verification · 2026-09-26

This change adds recoverable organization/account deletion, explicit administration controls and retained receipt source images. Financial data is not purged by these operations.

## Local checks

- PostgreSQL 17: **214 tests passed**, plus **11 installer subtests**. Covers owner/organization roles, last-administrator protection, login/session revocation, trash visibility, recovery without any active organization, original-image permissions, deduplication, source retention and unchanged financial values.
- SQLite developer runner: **209 passed, 5 PostgreSQL-only checks skipped**, plus **11 subtests**. Fixed UTC normalization in the heartbeat test, since SQLite strips timezone metadata.
- Alembic: upgrade from `7c9120efab34` to `9ad271c084fe` preserves a pre-existing organization; downgrade/upgrade round trip on an isolated test database; `alembic check` reports no schema drift.
- Ruff check and format verification passed. Web ESLint has no errors (one pre-existing dependency-array warning in `Assistant.tsx`); TypeScript and production Vite build passed.
- Browser workflows with synthetic data passed: organization creation/rename/trash/restore; user creation, organization-role assignment, blocking, deletion, recovery and enabling sign-in; final-organization removal and restoration without getting locked out of management.
- Browser receipt checks passed: attach a source to an existing draft, preserve unsaved field edits, view/zoom/download the original, and use the viewer at 390px width. Management layouts were inspected at desktop and phone widths without horizontal overflow.

## Compatibility and limits

Existing organization and receipt image endpoints remain compatible with Android. Phone-page processing still performs no receipt-site network request on the server. There is no new AI credential, Android package or mandatory mobile update.

A source image never captured in an earlier release cannot be reconstructed from recognized text. The receipt explicitly shows the missing original and permits an authorized photo/screenshot attachment. External pages may be unavailable; no CAPTCHA, regional restriction or TLS validation is bypassed. Deletion is reversible trash, not permanent erasure, and retains storage usage.
