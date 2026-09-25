# Product research · September 2026

This review uses vendors' documentation, not an independent hands-on certification of their products. The goal is to choose improvements that fit Finora's self-hosted, organization-based receipt workflow.

| Reference | Relevant pattern | Decision for Finora 1.5 |
|---|---|---|
| [Actual Budget reports](https://actualbudget.org/docs/reports/) and [custom reports](https://master.www.actualbudget.org/docs/reports/custom-reports/) | Flexible periods, filtering and grouped financial reports | Added six report types with explicit periods, category/merchant/currency filters and export |
| [Lunch Money analytics](https://lunchmoney.app/features/analytics/) | Explore a filtered set of transactions with totals and counts | Calculate totals over the entire selection, independently of visible rows; expose purchase and receipt counts |
| [Firefly III rules](https://docs.firefly-iii.org/how-to/firefly-iii/features/rules/) | Repeatable categorization from explicit rules | Keep existing deterministic rules above AI suggestions; do not let chat silently rewrite categories |
| [Monarch AI](https://help.monarch.com/hc/en-us/articles/37526856682260-AI-in-Monarch) and [winter release](https://www.monarch.com/blog/winter-release) | Conversational financial exploration, specific transactions and period comparisons | Added contextual questions, bounded report selection, exact evidence cards and receipt links in web and Android |
| [Lunch Money features](https://lunchmoney.app/features) | Recurring transactions, multiple currencies and collaboration | Retain organization sharing, actual-versus-planned income and explicit currency boundaries; improve access tests rather than duplicate those modules |

## What makes Finora useful

The product's focus is **a receipt-to-insight loop**: the phone opens the receipt URL, the server extracts a draft, the user corrects and confirms it, and the same item history powers search and price comparisons. Hosting the database yourself and keeping one AI key on the server fits this flow. Android capture and a full web administration interface have different jobs.

The improvements in this release are deliberately connected:

1. Accurate reports provide the assistant's evidence and stand alone without AI.
2. Search results lead back to source receipts, making answers inspectable.
3. Price suggestions use at least two different receipts and keep products, units and currencies separate.
4. Missing totals and uncertain discounts remain visible during review instead of becoming hidden accounting errors.
5. Shared chat follows organization membership; restricted members can analyze but cannot mutate the ledger through chat.

## Follow-on opportunities

These are product candidates, **not claims of implemented functionality**:

- Saved report presets and user-defined dashboards, once repeated filter patterns are observed.
- Cash-flow forecasting that clearly separates expected income/bills from actual balances and handles missed occurrences.
- Product matching across abbreviations and package sizes, with human confirmation before comparing unit prices.
- Optional passkeys or MFA with a complete Android login and recovery flow. Avoid adding a web-only challenge that locks existing mobile users out.
- Bank connectors only with explicit consent, supported local institutions and a reconciliation design. Importing statements without duplicate protection would undermine trustworthy totals.

Finora currently does not query live retail prices, guarantee equivalent replacement products, provide bank synchronization or claim trademark clearance for its name. Those require separate evidence and integration work.
