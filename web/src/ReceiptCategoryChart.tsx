import { useApp } from "./context";
import { t } from "./i18n";
import { creatorName } from "./ReceiptAuthor";
import type { CreatorCategory } from "./types";
import { amount } from "./ui";

export function ReceiptCategoryChart({ rows }: { rows: CreatorCategory[] }) {
  const { categories } = useApp();
  const groups = new Map<string, CreatorCategory[]>();
  for (const row of rows) {
    const key = `${row.creator.id ?? "unknown"}:${row.currency}`;
    const group = groups.get(key) ?? [];
    group.push(row);
    groups.set(key, group);
  }
  return (
    <section className="panel receipt-category-chart">
      <div className="panel-heading">
        <div>
          <h2>{t("Категории по пользователям")}</h2>
          <p>
            {t("По подтверждённым чекам. Пользователь — тот, кто добавил чек.")}
          </p>
        </div>
      </div>
      {!rows.length ? (
        <p className="muted">
          {t("За выбранный период нет подходящих покупок.")}
        </p>
      ) : (
        <div className="creator-chart-grid">
          {[...groups].map(([key, group]) => {
            const total = group.reduce((sum, row) => sum + row.total_minor, 0);
            return (
              <article className="creator-chart" key={key}>
                <header>
                  <h3>{creatorName(group[0].creator)}</h3>
                  <strong>{amount(total, group[0].currency)}</strong>
                </header>
                <ul>
                  {[...group]
                    .sort((a, b) => b.total_minor - a.total_minor)
                    .map((row) => {
                      const category = categories.find(
                        (c) => c.id === row.category_id,
                      );
                      const share =
                        total > 0 ? (row.total_minor / total) * 100 : 0;
                      return (
                        <li key={row.category_id ?? "uncategorized"}>
                          <div className="creator-chart-label">
                            <span>{category?.name ?? t("Без категории")}</span>
                            <strong>
                              {amount(row.total_minor, row.currency)}
                            </strong>
                          </div>
                          <div
                            className="creator-chart-track"
                            aria-hidden="true"
                          >
                            <span
                              style={{
                                width: `${Math.max(0, Math.min(100, share))}%`,
                                backgroundColor:
                                  category?.color ?? "var(--accent)",
                              }}
                            />
                          </div>
                        </li>
                      );
                    })}
                </ul>
              </article>
            );
          })}
        </div>
      )}
      <p className="analysis-footnote">
        {t(
          "Каждая валюта показана отдельно. Старые чеки без автора выделены в отдельную группу.",
        )}
      </p>
    </section>
  );
}
