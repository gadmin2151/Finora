import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Download, SlidersHorizontal } from "lucide-react";
import { api } from "../api";
import { useApp } from "../context";
import { ReportCard } from "../ReportCard";
import type { AnalyticsReport, ReportKind } from "../types";
import { ErrorBox, Field, Loading, PageHeading, today } from "../ui";

const kinds: [ReportKind, string][] = [
  ["summary", "Сводка"],
  ["categories", "Категории"],
  ["merchants", "Магазины"],
  ["trend", "По месяцам"],
  ["purchases", "Товары"],
  ["prices", "Сравнение цен"],
];

export default function Reports() {
  const { month, categories } = useApp();
  const [year, number] = month.split("-").map(Number);
  const [filters, setFilters] = useState({
    kind: "summary" as ReportKind,
    date_from: `${month}-01`,
    date_to: today().startsWith(month)
      ? today()
      : `${month}-${new Date(year, number, 0).getDate()}`,
    search: "",
    merchant: "",
    category_id: "",
    currency: "",
  });
  const [applied, setApplied] = useState(filters);
  const params = new URLSearchParams(
    Object.entries(applied).filter(([, value]) => value !== ""),
  );
  const query = useQuery({
    queryKey: ["report", params.toString()],
    queryFn: ({ signal }) =>
      api<AnalyticsReport>(`/reports?${params}`, { signal }),
  });
  const products = ["purchases", "prices"].includes(filters.kind);
  function download() {
    if (!query.data) return;
    const value = query.data;
    const lines = [
      value.title,
      `${value.query.date_from} — ${value.query.date_to}`,
      "",
      ...value.metrics.map((m) => `${m.label}: ${m.value}`),
      "",
      ...value.rows.flatMap((r) => [`${r.label}: ${r.value}`, r.detail]),
      "",
      ...value.notices,
    ];
    const url = URL.createObjectURL(
      new Blob([lines.join("\n")], { type: "text/plain;charset=utf-8" }),
    );
    const link = document.createElement("a");
    link.href = url;
    link.download = `finora-${value.query.kind}-${value.query.date_from}.txt`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  return (
    <>
      <PageHeading
        eyebrow="ВАШИ ДАННЫЕ · ВАШИ ВЫВОДЫ"
        title="Финансы в деталях"
        text="Любой период, категории, магазины и товары. Суммы из учёта, понятные основания."
        actions={
          <button
            className="button secondary"
            disabled={!query.data}
            onClick={download}
          >
            <Download size={18} />
            Сохранить отчёт
          </button>
        }
      />
      <form
        className="panel report-controls"
        onSubmit={(e) => {
          e.preventDefault();
          setApplied({ ...filters, search: products ? filters.search : "" });
        }}
      >
        <div className="report-tabs" role="group" aria-label="Вид отчёта">
          {kinds.map(([kind, label]) => (
            <button
              type="button"
              key={kind}
              aria-pressed={filters.kind === kind}
              className={filters.kind === kind ? "selected" : ""}
              onClick={() => {
                const next = {
                  ...filters,
                  kind,
                  search: ["purchases", "prices"].includes(kind)
                    ? filters.search
                    : "",
                };
                setFilters(next);
                setApplied(next);
              }}
            >
              {label}
            </button>
          ))}
        </div>
        <div className="report-filter-grid">
          <Field label="С даты">
            <input
              required
              type="date"
              min="1990-01-01"
              max={filters.date_to}
              value={filters.date_from}
              onChange={(e) =>
                setFilters({ ...filters, date_from: e.target.value })
              }
            />
          </Field>
          <Field label="По дату">
            <input
              required
              type="date"
              min={filters.date_from}
              max="2100-12-31"
              value={filters.date_to}
              onChange={(e) =>
                setFilters({ ...filters, date_to: e.target.value })
              }
            />
          </Field>
          <Field label="Категория">
            <select
              value={filters.category_id}
              onChange={(e) =>
                setFilters({ ...filters, category_id: e.target.value })
              }
            >
              <option value="">Все категории</option>
              <option value="uncategorized">Без категории</option>
              {categories.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Магазин">
            <input
              maxLength={100}
              placeholder="Любой магазин"
              value={filters.merchant}
              onChange={(e) =>
                setFilters({ ...filters, merchant: e.target.value })
              }
            />
          </Field>
          <Field label="Валюта операций">
            <select
              value={filters.currency}
              onChange={(e) =>
                setFilters({ ...filters, currency: e.target.value })
              }
            >
              <option value="">Все валюты</option>
              {["MDL", "EUR", "USD", "RON"].map((c) => (
                <option key={c}>{c}</option>
              ))}
            </select>
          </Field>
          {products && (
            <Field label="Название в чеке">
              <input
                maxLength={100}
                placeholder="Например, LAPTE"
                value={filters.search}
                onChange={(e) =>
                  setFilters({ ...filters, search: e.target.value })
                }
              />
            </Field>
          )}
          <button className="button primary" disabled={query.isFetching}>
            <SlidersHorizontal size={17} />
            {query.isFetching ? "Считаем…" : "Применить"}
          </button>
        </div>
      </form>
      <ErrorBox error={query.error} />
      {query.isPending ? (
        <Loading />
      ) : (
        query.data && <ReportCard report={query.data} />
      )}
    </>
  );
}
