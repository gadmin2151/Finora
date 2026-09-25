import { ArrowUpRight, ChartNoAxesCombined } from "lucide-react";
import { api, useAction } from "./api";
import { useApp } from "./context";
import type { AnalyticsReport, Receipt } from "./types";
import { ErrorBox } from "./ui";

function displayValue(value: string) {
  return value.replace(
    /(-?\d+)\.(\d{2})(?=\s(?:MDL|EUR|USD|RON)\b)/g,
    (_, whole: string, fraction: string) =>
      `${whole.replace(/\B(?=(\d{3})+(?!\d))/g, "\u00a0")},${fraction}`,
  );
}

export function ReportCard({
  report,
  compact = false,
}: {
  report: AnalyticsReport;
  compact?: boolean;
}) {
  const { open, categories } = useApp();
  const details = useAction(async (id: string) =>
    open({ type: "receipt", receipt: await api<Receipt>(`/receipts/${id}`) }),
  );
  const category =
    categories.find((c) => c.id === report.query.category_id)?.name ??
    (report.query.category_id === "uncategorized" ? "Без категории" : "");
  return (
    <article className={`report-card ${compact ? "compact" : "panel"}`}>
      <header className="report-heading">
        <span className="round-icon mint">
          <ChartNoAxesCombined size={20} />
        </span>
        <div>
          <h2>{report.title}</h2>
          <p>
            {report.query.date_from} — {report.query.date_to}
          </p>
        </div>
        <span className="report-verified">Из вашего учёта</span>
      </header>
      {[
        report.query.search,
        report.query.merchant,
        category,
        report.query.currency,
      ].some(Boolean) && (
        <p className="report-filters">
          {[
            report.query.search && `Товар: ${report.query.search}`,
            report.query.merchant && `Магазин: ${report.query.merchant}`,
            category,
            report.query.currency,
          ]
            .filter(Boolean)
            .join(" · ")}
        </p>
      )}
      <dl className="report-metrics">
        {report.metrics.map((metric, i) => (
          <div key={`${metric.label}-${i}`}>
            <dt>{metric.label}</dt>
            <dd>{displayValue(metric.value)}</dd>
          </div>
        ))}
      </dl>
      {report.rows.length > 0 && (
        <div className="report-rows">
          {report.rows.map((row, i) => (
            <div className="report-row" key={`${row.label}-${i}`}>
              <div>
                <strong>{row.label}</strong>
                {row.detail && <small>{row.detail}</small>}
              </div>
              <b>{displayValue(row.value)}</b>
              {row.receipt_id && (
                <button
                  className="icon-button"
                  aria-label={`Открыть чек: ${row.label}`}
                  disabled={details.isPending}
                  onClick={() => details.mutate(row.receipt_id!)}
                >
                  <ArrowUpRight size={19} />
                </button>
              )}
            </div>
          ))}
        </div>
      )}
      {report.query.kind !== "summary" && report.rows.length === 0 && (
        <p className="report-empty">
          За этот период нет подходящих данных. Попробуйте другой период или
          уточните фильтры.
        </p>
      )}
      {report.total_rows > report.rows.length && (
        <p className="muted">
          Показано {report.rows.length} из {report.total_rows}. Итоги рассчитаны
          по всем найденным записям.
        </p>
      )}
      <ErrorBox error={details.error} />
      <details className="report-method">
        <summary>Как рассчитано</summary>
        {report.notices.map((notice) => (
          <p key={notice}>{notice}</p>
        ))}
      </details>
    </article>
  );
}
