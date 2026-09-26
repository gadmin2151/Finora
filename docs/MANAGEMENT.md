# Organizations, users and receipt originals

## Organization management

Open **Организации**, choose a workspace in the list, and manage its name and members without changing the current accounting organization. **Открыть учёт** explicitly switches the accounting workspace. Members can view their available organizations; administrators manage their own organizations. The server owner can manage all organizations, but must still have membership to open their financial workspace.

**Удалить → Переместить в корзину** hides the organization from accounting and denies finance/API access to every member. Receipts, original images, accounts, postings, comments and memberships remain intact. Deletion waits for queued/running jobs to finish. **Корзина → Восстановить организацию** restores access; if no active administrator remains, the restoring server owner becomes an administrator. Management remains accessible from the organization selection screen even with no active workspace.

There is no permanent purge in this workflow. Trash does not reclaim storage.

## User management

The server owner uses **Пользователи** to search/filter accounts, edit a profile, assign multiple organizations and roles, reset passwords, block sign-in, and move accounts to trash. Role assignment is separate from server ownership: an organization administrator cannot become a server owner through these endpoints.

- **Blocked:** cannot log in; current sessions are revoked; assignments and authorship remain.
- **Deleted:** additionally hidden from normal account/member lists; reserved login prevents identity reuse. Find the account with **Статус аккаунта → Корзина**.
- **Restored:** profile and assignments are restored, but sign-in stays blocked. Review assignments and explicitly enable sign-in. The password does not change.
- **Remove from organization:** revokes only that membership, retaining the account and other memberships.

The server owner cannot be blocked/deleted. Removing, demoting, blocking or deleting the last active administrator of an active organization is rejected. Assign another active administrator first. Organization/user changes are recorded in the corresponding audit trail.

## Source images

Receipt images live in the persistent private receipt volume, scoped to organization and receipt. They are not public assets. Receipt `files` URLs remain compatible with existing web and Android clients; cookie authentication and organization access are required for each image.

The upload path retains normalized source photographs (orientation applied, metadata stripped) before recognition. Capture and recognition do not replace them with crops or parsed text. Server-side link import attempts a full-page screenshot for both MEV and other supported public HTTPS receipt pages. Phone-page imports use the images/text sent by the phone and never reopen the source website from the server. Repeated import of the same receipt can append new source images without adding another financial transaction. Identical images are deduplicated.

Open **Оригиналы чека** for thumbnails, full-image scrolling, zoom and download. **Прикрепить** accepts up to four images per request, with at most 16 originals per receipt. Files must pass the same image validation as initial uploads. Organization administrators can append to any receipt; members can append only to receipts they created. Appending originals does not change line items, totals, posted transactions or receipt review versions.

A website can be unavailable or block a server outside its country. Finora cannot promise a screenshot in that case: the draft indicates the missing source and the user can attach a photo/screenshot or import from Android. Existing text-only receipts are not retroactively turned into an invented “original”. Back up the database **and** receipt volume together; the deployment backup script includes both.

## API and migration

Existing `/organizations/current` and member endpoints remain available. Added endpoints:

- `GET /api/organizations/manage?status=active|deleted`
- `PUT|DELETE /api/organizations/{id}`; `POST /api/organizations/{id}/restore`
- `GET|POST /api/organizations/{id}/members`; `PUT|DELETE /api/organizations/{id}/members/{member_id}`
- `DELETE /api/admin/users/{id}`; `POST /api/admin/users/{id}/restore`
- `PUT /api/admin/users/{id}/status` with `{ "is_active": true|false }`
- `GET /api/admin/users?status=deleted` (existing filters unchanged)
- `POST /api/receipts/{id}/originals` with multipart `files`

Every mutation requires the existing session and CSRF token. Target organization/member checks run server-side, independently of the current UI selection. Migration `9ad271c084fe` adds nullable `deleted_at` columns to organizations and users. Existing data is preserved; apply migrations before starting the updated API/worker. No Android reinstall or new secrets are required.
