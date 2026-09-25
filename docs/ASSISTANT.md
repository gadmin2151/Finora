# AI assistant and reports

## Everyday use

Choose your organization, open **Аналитика → AI-помощник** on the web or **AI** in Android. Chat history is shared with the organization, not private to a single member.

Examples:

- `Сколько ушло на продукты с 1 по 25 сентября?`
- `Сравни расходы в августе и сентябре.`
- `Найди покупки LAPTE за последние три месяца.`
- `А где это молоко было дешевле?`
- `Покажи доходы и расходы по месяцам за этот год.`

The selected month is the default when a question does not give dates. The planner receives the latest eight relevant messages and prior report queries. This is bounded conversational context, not permanent memory of every message.

## What a number means

| Report | Meaning |
|---|---|
| Summary | Income, expenses net of refunds, remainder, refunds, operation count and daily average |
| Categories | Net expenses allocated to categories, including the category share of split transactions |
| Merchants | Net spending grouped by the recorded merchant |
| Monthly trend | Income, net expenses and remainder for each month in the selected period |
| Purchases | Posted receipt items, quantity, unit, cost and links to source receipts |
| Prices | The same normalized product name, unit and currency across at least two distinct receipts |

Transfers, debt movements and voided transactions are excluded from financial income/expense reports. Financial reports convert to MDL using the exchange rate stored on each transaction. A category filter selects only that category's allocated amount, not the entire basket. The daily average uses all calendar days in the requested period, including zero-spend days.

Purchase reports retain the receipt currency. Item refunds are not allocated back to product rows; refunds are included in the financial summary. Price comparisons do not infer equivalent packaging or current supermarket offers. An observed minimum is historical evidence, not a promise that a store still has that price.

Queries are limited to 731 inclusive days. Financial tables show the leading 20 groups while totals cover the full selection. Purchase tables show the first 20 items; price comparisons show up to 12 candidates. Narrow the dates/filter or use **Товары и цены** for paginated browsing. Exact values are stored in the report card at the time of the answer: an old chat answer is a historical snapshot, not a live dashboard.

## Privacy and permissions

- Reports always use the selected organization. The model cannot choose a different organization or run SQL.
- Members can request reports and ask questions; administrator-only financial mutations remain protected on the server.
- Chat has no write tools. Suggested savings never change transactions or budgets.
- Planning sends the question, bounded chat context and category names to the configured provider. Explanation sends the resulting reports, relevant prior context and the question. Unrelated account names, debt notes, credentials and raw database rows are not included by this flow.
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
kind=summary|categories|merchants|trend|purchases|prices
date_from=YYYY-MM-DD
date_to=YYYY-MM-DD
category_id=<id>|uncategorized   (optional)
merchant=<text>                 (optional, max 100 characters)
currency=MDL|EUR|USD|RON         (optional)
search=<product>               (purchases/prices only, max 100 characters)
```

`POST /api/chat` accepts `text`, `month`, an optional quick-report name in `report`, and an optional `request_key` (16–100 characters). The response contains `job_id`; poll `/api/jobs` and read `/api/chat`. Mutating calls require the existing CSRF token. The assistant response's `details.reports` contains typed metrics, rows, filters, explanatory notices and optional source `receipt_id` values.
