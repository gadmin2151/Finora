# Current wallet · Текущий кошелёк

## Русский

**Текущий кошелёк** показывает остаток на счетах сейчас: начальный остаток плюс все действующие движения денег. Переключение месяца меняет отчёты, но не текущий кошелёк. Разные валюты отображаются отдельно; EUR и MDL не складываются без курса.

Если вам вернули долг 5 000 MDL, остаток выбранного при возврате счёта увеличивается на 5 000 MDL. Возврат своих денег не добавляется к доходам месяца. Поэтому **кошелёк** и **доходы минус расходы за месяц** — разные показатели. Повторно вводить возврат как доход не нужно.

### Уточнить остаток

На страницах **Обзор** и **Счета** нажмите **«Указать текущий остаток»**. Введите фактическую сумму сейчас и сохраните. Дальнейшие поступления и списания считаются от неё. Например, после сверки на 1 000 MDL покупка на 100 MDL оставит 900 MDL. Дополнительные параметры позволяют прибавить/списать сумму, изменить дату, указать примечание или учесть разницу в доходах/расходах.

В режиме «Всё вместе» счета MDL объединяются физически: переносятся все движения, планы и начальные остатки. Опустевшие счета удаляются после переноса ссылок, включая архивные чеки и отменённые операции. В истории остаётся аудит объединения. При возврате к раздельному учёту можно создать новые счета; старые автоматически не воссоздаются.

- **Только корректировка** меняет остаток, не увеличивая доходы или расходы месяца.
- **Как доход/расход** включает положительную разницу в доходы, отрицательную — в расходы.

Каждая корректировка создаёт отдельную операцию в истории; начальный остаток не перезаписывается. Если баланс успел измениться, приложение попросит обновить данные и проверить сумму. Повтор отправки того же запроса не создаёт вторую операцию. Изменять остаток может администратор организации.

### Товары и чат

Нажмите категорию в «На что уходят деньги», чтобы увидеть её товары за выбранный месяц. Список использует позиции подтверждённых чеков и загружает следующие страницы по запросу. Расход, внесённый вручную без чека, влияет на сумму категории, но не создаёт вымышленных товаров.

Ответы помощника отображают Markdown: заголовки, выделение, списки, таблицы и код. При открытии чат переходит к последнему сообщению. Когда вы читаете старую переписку, новые ответы не должны сбивать вашу прокрутку. HTML не исполняется, внешние картинки из сообщений не загружаются.

## English

**Current wallet** is your account balance now: opening balances plus all active money movements. Changing the report month does not change the current wallet. Different currencies remain separate.

A repayment of 5,000 MDL increases the destination account by 5,000 MDL. It does not become new monthly income. The wallet and monthly income minus expenses therefore show different things; do not record the repayment again as income.

Use **Set current balance** on Overview or Accounts, enter the amount you have now, and save. Future transactions start from that balance. Additional options allow adding/subtracting an amount, a date and a note. Review the resulting amount before saving. A balance-only correction does not affect monthly income or expenses. The optional income/expense mode records a positive difference as income and a negative difference as expense. Corrections create auditable transactions; they do not overwrite the opening balance. A stale balance is rejected, retries are idempotent, and only organization administrators can adjust it.

Select a spending category to see its confirmed receipt items for the selected month, with pagination. Manually entered expenses without receipt items affect category totals but do not create products. Chat displays Markdown safely and opens at the latest message while allowing uninterrupted reading of older messages.

## API

`POST /api/accounts/{id}/balance-adjustment` uses the existing authenticated organization and administrator checks.

```json
{
  "target_balance": "1200.00",
  "expected_balance_minor": 110000,
  "effect": "adjustment",
  "occurred_on": "2026-09-26",
  "fx_rate": null,
  "note": "Balance reconciliation",
  "idempotency_key": "unique-request-key"
}
```

`effect` is `adjustment` or `income_expense`. Non-MDL accounts require an exchange rate. The response contains `transaction`, `account`, `previous_balance_minor`, and signed `adjustment_minor`. A stale balance or conflicting idempotency key returns 409. Invalid or unchanged amounts are rejected. Existing account, dashboard, and transaction fields remain available; dashboard additionally exposes `wallet`. No database migration is required.


`POST /api/wallet/balance-adjustment` accepts the same fields plus required `expected_accounting_version` from `/api/settings/accounting`. It reconciles the combined MDL total and records the difference on the primary account. The response includes `transaction`, `previous_balance_minor`, `adjustment_minor`, and the current `balance_minor`. Concurrent balance or accounting-mode changes return 409; the user refreshes and reviews before retrying. Source account IDs from older clients are accepted for new payments only when an organization-scoped merge audit proves ownership. In combined mode `POST /api/accounts` is rejected; choose separate mode first.
