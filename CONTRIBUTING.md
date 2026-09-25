# Contributing to Finora

Start with an issue describing the user problem and a small, reviewable change. Do not include real financial data or credentials. See [SECURITY.md](SECURITY.md) for private vulnerability reports.

## Local development

Use Python 3.12+, Node.js 24, pnpm 11.25.0 and PostgreSQL 17. Docker is the simplest way to run the complete application. See [README](README.md#get-started) for installation; [Android setup](android/README.md#сборка) documents its JDK/SDK requirements.

```bash
python3 -m venv .venv
.venv/bin/pip install -r backend/requirements.txt
.venv/bin/ruff check backend scripts
.venv/bin/ruff format --check backend scripts
.venv/bin/pytest -q backend/tests scripts/tests

cd web
corepack enable
corepack prepare pnpm@11.25.0 --activate
pnpm install --frozen-lockfile
pnpm lint
pnpm typecheck
pnpm build
pnpm audit --audit-level=moderate
```

Default Python tests use temporary SQLite. Concurrency and PostgreSQL-specific behavior require `TEST_DATABASE_URL=postgresql+psycopg://.../finora_test`. The name must end in `_test`; **never supply your real database URL**. Run the complete PostgreSQL suite before changing locking or money calculations.

For schema changes, add an Alembic revision and check `upgrade head` and `alembic check` against a disposable PostgreSQL database. Do not alter already-released migration history.

## Review expectations

- Preserve organization scope and enforce permissions on the server.
- Use integer minor units and Decimal for money; respect refunds, splits, original exchange rates and transaction idempotency.
- Keep financial writes explicit. AI suggestions and a successful OCR response are not permission to post a mobile receipt.
- Test important failure modes, concurrency boundaries and regressions with fictional data.
- Keep keys, passwords, signing identities, photos, logs and backup archives out of Git.
- Preserve the origami wallet identity, jade/mint palette, readable contrast, keyboard access and reduced-motion behavior.
- Update the relevant documentation when behavior or API contracts change. Native and web clients may not update together; optional response fields should remain compatible.

CI must pass before publication. Device acceptance and signed APK publication are separate from a successful Gradle compilation. Do not describe an unsigned CI artifact or emulator test as an installed, physically tested phone release.

Contributions are provided under the repository's [MIT license](LICENSE).
