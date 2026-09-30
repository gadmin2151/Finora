# AI assistant and reports

## Everyday use

Choose your organization, open **Аналитика → AI-помощник** on the web or **AI** in Android. On the web, **Finora AI** is also available in the lower-right corner of every other page. The dedicated chat fills the workspace height; the expand button opens a focused view, and Escape restores it. Chat history is shared with the organization, not private to a single member.

Users can flag a harmful AI answer directly below that answer in either client. The server records one organization-scoped report per user and answer in the audit log (`chat.answer_reported`); subsequent taps are safe to retry. Operators should review these reports and the linked answer in the shared chat, then use them to improve the AI configuration and safeguards. The report never posts or deletes financial data.

Examples:

- `Сколько ушло на продукты с 1 по 25 сентября?`
- `Сравни расходы в августе и сентябре.`
- `Найди покупки LAPTE за последние три месяца.`
- `А где это молоко было дешевле?`
- `Покажи доходы и расходы по месяцам за этот год.`
- `Сравни расходы всех пользователей за сентябрь.`
- `Покажи категории расходов Марии за всю историю.`
- `Сколько мне должны и какие регулярные платежи запланированы?`
- `Найди доходы за 2023 год.`
- `Переключи меня в чеки.`

The selected month is the default when a question does not give dates. The web sends the current page as validated context, without reading page DOM, passwords or unfinished forms. The planner receives the requester, receipt-author catalogue, financial history date range, category names, the latest eight relevant messages and prior report queries. This is bounded conversational context, not permanent memory of every message. A plan can select up to six reports for comparisons.

## What a number means

| Report | Meaning |
|---|---|
| Summary | Income, expenses net of refunds, remainder, refunds, operation count and daily average |
| Categories | Net expenses allocated to categories, including the category share of split transactions |
| Merchants | Net spending grouped by the recorded merchant |
| Monthly trend | Income, net expenses and remainder for each month in the selected period |
| Purchases | Posted receipt items, quantity, unit, cost and links to source receipts |
| Prices | The same normalized product name, unit and currency across at least two distinct receipts |
| Accounts | Current balances, including archived accounts; currencies remain separate |
| Debts | Current amounts owed and lent, after increases and partial/full repayments |
| Payment / income plans | Schedule from active templates in the requested period; planned amounts are not actual payments |
| Budgets | Category limits and actual net spending, including limits with no spending |
| Transactions | Income, expenses, refunds, transfers, adjustments and debt movements; optional transaction-type filter |
| Receipts | Drafts and confirmed receipts, authors, recent comments and source-card links |
| Users | Net receipt spending grouped by the user who originally added the receipt |

Transfers, debt movements and voided transactions are excluded from financial income/expense reports. Financial reports convert to MDL using the exchange rate stored on each transaction. A category filter selects only that category's allocated amount, not the entire basket. The daily average uses all calendar days in the requested period, including zero-spend days.

Purchase reports retain the receipt currency. Item refunds are not allocated back to product rows; refunds are included in the financial summary. Price comparisons do not infer equivalent packaging or current supermarket offers. An observed minimum is historical evidence, not a promise that a store still has that price.

Historical queries accept dates from 1990 through 2100, including the whole recorded history. Calendar plan queries remain limited to 731 inclusive days. Financial/entity tables show up to 20 rows while totals cover the full selection; monthly trends show up to 120 months and price comparisons up to 12 candidates. Narrow the dates/filter or use **Товары и цены** for paginated browsing. Exact values are stored in the report card at the time of the answer: an old chat answer is a historical snapshot, not a live dashboard. Account and debt reports carry an explicit `as_of` date and describe current balances, not balances at the historical query date.

User attribution means **receipt creator**, not who paid. Refunds follow the original receipt author; transactions without a receipt are not assigned to a user. Ask for `categories` with a specific author to inspect their expense categories. Budget limits are shared by the whole organization, even when actual spending is filtered by author.

On **Бюджеты**, switch between **Карточки / Список**. Both views include all categories and sort by actual spending descending, with alphabetical ties. The list has comparison bars, links to category purchases and budget-limit controls. The selected view is saved on the device.

## Privacy and permissions

- Reports always use the selected organization. The model cannot choose a different organization or run SQL.
- Members can request reports and ask questions; administrator-only financial mutations remain protected on the server.
- Chat has no financial write tools. Suggested savings never change transactions or budgets. It may return a validated page navigation action only for an explicit current request. The web applies it only to the job submitted from that chat instance, checks route permissions again, and never executes actions from other users' shared messages or past history.
- Planning sends the question, current-page identifier, requester/author names, category names, history date bounds and bounded chat context to the configured provider. Explanation sends the requested reports and relevant prior context. Requested report data may include account balances, debt names/notes, transaction notes and receipt comments. Credentials, keys, raw database records and receipt source URLs are not sent through this flow.
- Receipt recognition sends the selected receipt images/text to the configured provider. OpenAI requests use `store: false`; this is not a claim of zero provider retention. The provider's own data policy and your API agreement apply.
- API keys stay on the server and are encrypted using `SECRET_KEY`. A server administrator with the database and key can read them; this is not end-to-end encryption.
- No AI provider is required for manual finance, local OCR or preset reports. Optional Ollama keeps inference on your host. The deployed operator decides which provider to enable.

## Request cost and failure behavior

A free question normally uses two requests: query planning and explanation. A quick report needs no planning request and at most one explanation request. The monthly cap is a request count, not a monetary spending cap. A failed provider attempt also consumes a reservation to prevent unbounded retries.

If explanation fails, exact reports are returned. If the question cannot produce a valid report, the assistant requests clarification or shows an actionable error. Retry after a network failure retains the request key to avoid duplicate chat submissions. Draft text survives polling and refresh; changing organizations starts a separate context.

Receipt recognition may use extra bounded requests for categories, a missing/discrepant total and an explicitly printed discount. Discounts are allocated only when the separately read discount exactly equals the difference between line totals and the printed payable total. The original line totals and verification evidence remain in receipt metadata. This forces review; no guessed total is manufactured from the items.

## API

`GET /api/reports` requires authentication and `X-Organization-ID`:

```text
kind=summary|categories|merchants|trend|purchases|prices|accounts|debts|bills|income_plans|budgets|transactions|receipts|users
date_from=YYYY-MM-DD
date_to=YYYY-MM-DD
category_id=<id>|uncategorized   (optional)
merchant=<text>                 (optional, max 100 characters)
currency=MDL|EUR|USD|RON         (optional)
created_by=<user-id>|unknown     (receipt author, optional)
search=<text>                   (supported entity/product names, max 100 characters)
transaction_kind=<kind>         (transactions only)
```

`POST /api/chat` accepts `text`, `month`, an optional quick-report name in `report`, optional current `page` (validated route name), and an optional `request_key` (16–100 characters). The response contains `job_id`; poll `/api/jobs` and read `/api/chat`. Mutating calls require the existing CSRF token. The assistant response's `details.reports` contains typed metrics, rows, filters, explanatory notices and optional source `receipt_id` values. Optional `details.navigate_to` is a validated route, never a URL or executable command. Existing Android clients can omit `page` and ignore the new navigation field; mobile navigation is unchanged.
