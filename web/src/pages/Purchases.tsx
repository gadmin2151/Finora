import { purchaseHashFilters } from "../purchaseNavigation";
import { receiptUnitOptions, unitLabel } from "../units";
import { t, getLocale } from "../i18n";
import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Search, SlidersHorizontal, ArrowUpRight } from "lucide-react";
import { api, useAction } from "../api";
import { useApp } from "../context";
import type { Receipt } from "../types";
import {
  Empty,
  ErrorBox,
  Field,
  Loading,
  PageHeading,
  amount,
  counted,
  dateLabel,
} from "../ui";

type Purchase = {
  id: string;
  receipt_id: string;
  name: string;
  category_id: string | null;
  quantity: string;
  unit: string;
  unit_minor: string;
  total_minor: number;
  merchant: string;
  currency: string;
  purchased_on: string;
};
type Result = {
  items: Purchase[];
  total: number;
  receipt_count: number;
  totals: { currency: string; total_minor: number }[];
  categories: {
    category_id: string | null;
    currency: string;
    total_minor: number;
  }[];
  comparisons: {
    name: string;
    currency: string;
    unit: string;
    min_unit_minor: string;
    max_unit_minor: string;
    best_merchant: string;
    best_on: string;
    receipt_count: number;
    potential_minor: number;
  }[];
};
const initial = {
  search: "",
  merchant: "",
  category_id: "",
  account_id: "",
  currency: "",
  unit: "",
  sort: "newest",
  period: "month",
  date_from: "",
  date_to: "",
};

export default function Purchases() {
  const { month, accounts, categories, open } = useApp();
  const [filters, setFilters] = useState(() => ({
    ...initial,
    category_id: purchaseHashFilters(location.hash).category,
  }));
  const [debounced, setDebounced] = useState(filters);
  const [offset, setOffset] = useState(0);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(filters), 250);
    return () => clearTimeout(timer);
  }, [filters]);
  function change(field: keyof typeof filters, value: string) {
    setFilters({ ...filters, [field]: value });
    setOffset(0);
  }
  const params = new URLSearchParams({ offset: String(offset), limit: "30" });
  for (const [field, value] of Object.entries(debounced))
    if (value && !["period", "date_from", "date_to"].includes(field))
      params.set(field, value);
  if (debounced.period === "month") {
    const [year, number] = month.split("-").map(Number);
    params.set("date_from", `${month}-01`);
    params.set("date_to", `${month}-${new Date(year, number, 0).getDate()}`);
  } else if (debounced.period === "custom") {
    if (debounced.date_from) params.set("date_from", debounced.date_from);
    if (debounced.date_to) params.set("date_to", debounced.date_to);
  }
  const query = useQuery({
    queryKey: ["purchases", params.toString()],
    queryFn: () => api<Result>(`/purchases?${params}`),
  });
  const details = useAction(async (id: string) => {
    const receipt = await api<Receipt>(`/receipts/${id}`);
    open({ type: "receipt", receipt });
  });
  const categoryName = (id: string | null) =>
    categories.find((c) => c.id === id)?.name ?? t("Без категории");
  return (
    <>
      <PageHeading
        eyebrow={t("АНАЛИТИКА ПОКУПОК")}
        title={t("Что вы покупаете")}
        text={t(
          "Найдите любой товар, сравните свои цены и посмотрите, на что уходит больше всего.",
        )}
      />
      <section className="panel purchase-filters">
        <div className="panel-heading">
          <h2>
            <SlidersHorizontal size={18} /> {t("Фильтры")}
          </h2>
          <button
            className="text-button"
            onClick={() => {
              setFilters(initial);
              setOffset(0);
            }}
          >
            {t("Сбросить")}
          </button>
        </div>
        <div className="purchase-filter-grid">
          <Field label={t("Название товара")}>
            <div className="search-field">
              <Search size={18} />
              <input
                placeholder={t("Молоко, LAPTE, кофе…")}
                maxLength={100}
                value={filters.search}
                onChange={(e) => change("search", e.target.value)}
              />
            </div>
          </Field>
          <Field label={t("Категория")}>
            <select
              value={filters.category_id}
              onChange={(e) => change("category_id", e.target.value)}
            >
              <option value="">{t("Все категории")}</option>
              <option value="uncategorized">{t("Без категории")}</option>
              {categories.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label={t("Магазин")}>
            <input
              placeholder={t("Название магазина")}
              maxLength={100}
              value={filters.merchant}
              onChange={(e) => change("merchant", e.target.value)}
            />
          </Field>
          <Field label={t("Период")}>
            <select
              value={filters.period}
              onChange={(e) => change("period", e.target.value)}
            >
              <option value="month">
                {t("Месяц ·")} {month}
              </option>
              <option value="all">{t("Вся история")}</option>
              <option value="custom">{t("Выбрать даты")}</option>
            </select>
          </Field>
          {filters.period === "custom" && (
            <>
              <Field label={t("С даты")}>
                <input
                  type="date"
                  value={filters.date_from}
                  onChange={(e) => change("date_from", e.target.value)}
                />
              </Field>
              <Field label={t("По дату")}>
                <input
                  type="date"
                  value={filters.date_to}
                  onChange={(e) => change("date_to", e.target.value)}
                />
              </Field>
            </>
          )}
        </div>
        <div className="purchase-filter-grid secondary-filters">
          <Field label={t("Счёт")}>
            <select
              value={filters.account_id}
              onChange={(e) => change("account_id", e.target.value)}
            >
              <option value="">{t("Все счета")}</option>
              {accounts.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name} · {a.currency}
                </option>
              ))}
            </select>
          </Field>
          <Field label={t("Валюта")}>
            <select
              value={filters.currency}
              onChange={(e) => change("currency", e.target.value)}
            >
              <option value="">{t("Все валюты")}</option>
              {["MDL", "EUR", "USD", "RON"].map((c) => (
                <option key={c}>{c}</option>
              ))}
            </select>
          </Field>
          <Field label={t("Единица")}>
            <select
              value={filters.unit}
              onChange={(e) => change("unit", e.target.value)}
            >
              <option value="">{t("Все единицы")}</option>
              {receiptUnitOptions.map((u) => (
                <option key={u.value} value={u.value}>
                  {u.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label={t("Сортировка")}>
            <select
              value={filters.sort}
              onChange={(e) => change("sort", e.target.value)}
            >
              <option value="newest">{t("Сначала новые")}</option>
              <option value="oldest">{t("Сначала старые")}</option>
              <option value="amount_desc">{t("По сумме покупки ↓")}</option>
              <option value="price_asc">{t("По цене единицы ↑")}</option>
              <option value="price_desc">{t("По цене единицы ↓")}</option>
            </select>
          </Field>
        </div>
      </section>
      <ErrorBox error={query.error ?? details.error} />
      {query.isPending ? (
        <Loading />
      ) : (
        query.data && (
          <>
            <div className="purchase-summary">
              <div>
                <span>{t("Найдено позиций")}</span>
                <strong>{query.data.total}</strong>
              </div>
              <div>
                <span>{t("Чеков")}</span>
                <strong>{query.data.receipt_count}</strong>
              </div>
              {query.data.totals.map((total) => (
                <div key={total.currency}>
                  <span>
                    {t("Потрачено ·")} {total.currency}
                  </span>
                  <strong>{amount(total.total_minor, total.currency)}</strong>
                </div>
              ))}
            </div>
            <section className="panel">
              {query.data.items.length ? (
                <>
                  <div className="table-scroll">
                    <table>
                      <thead>
                        <tr>
                          <th>{t("Товар / магазин")}</th>
                          <th>{t("Дата")}</th>
                          <th>{t("Категория")}</th>
                          <th>{t("Количество")}</th>
                          <th>{t("Цена за единицу")}</th>
                          <th>{t("Сумма")}</th>
                          <th>
                            <span className="sr-only">{t("Чек")}</span>
                          </th>
                        </tr>
                      </thead>
                      <tbody>
                        {query.data.items.map((item) => (
                          <tr key={item.id}>
                            <td>
                              <strong>{item.name}</strong>
                              <small className="block muted">
                                {item.merchant}
                              </small>
                            </td>
                            <td>{dateLabel(item.purchased_on)}</td>
                            <td>{categoryName(item.category_id)}</td>
                            <td>
                              {Number(item.quantity).toLocaleString(
                                getLocale(),
                                {
                                  maximumFractionDigits: 6,
                                },
                              )}{" "}
                              {unitLabel(item.unit)}
                            </td>
                            <td>
                              {amount(Number(item.unit_minor), item.currency)} /{" "}
                              {unitLabel(item.unit)}
                            </td>
                            <td className="money">
                              {amount(item.total_minor, item.currency)}
                            </td>
                            <td>
                              <button
                                className="icon-button"
                                disabled={details.isPending}
                                aria-label={t("Открыть чек: {0}", item.name)}
                                onClick={() => details.mutate(item.receipt_id)}
                              >
                                <ArrowUpRight size={18} />
                              </button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <div className="pagination">
                    <span>
                      {offset + 1}–{offset + query.data.items.length} {t("из")}{" "}
                      {query.data.total}
                    </span>
                    <div className="button-row">
                      <button
                        disabled={!offset}
                        onClick={() => setOffset(Math.max(0, offset - 30))}
                      >
                        {t("Назад")}
                      </button>
                      <button
                        disabled={offset + 30 >= query.data.total}
                        onClick={() => setOffset(offset + 30)}
                      >
                        {t("Далее")}
                      </button>
                    </div>
                  </div>
                </>
              ) : (
                <Empty
                  title={t("Покупки не найдены")}
                  text={t(
                    "Измените фильтры или период. Здесь появляются товары из подтверждённых чеков.",
                  )}
                />
              )}
            </section>
            {!!query.data.categories.length && (
              <section className="panel">
                <div className="panel-heading">
                  <div>
                    <h2>{t("Расходы по категориям")}</h2>
                    <p>{t("Итоги по всей выборке, включая другие страницы")}</p>
                  </div>
                </div>
                <div className="purchase-categories">
                  {query.data.categories.map((c) => (
                    <button
                      key={`${c.category_id}-${c.currency}`}
                      onClick={() =>
                        change("category_id", c.category_id ?? "uncategorized")
                      }
                    >
                      <span>{categoryName(c.category_id)}</span>
                      <strong>{amount(c.total_minor, c.currency)}</strong>
                    </button>
                  ))}
                </div>
              </section>
            )}
            <section className="panel">
              <div className="panel-heading">
                <div>
                  <h2>{t("Где цена была ниже")}</h2>
                  <p>
                    {t(
                      "Одинаковые названия, валюта и единицы · минимум два разных чека",
                    )}
                  </p>
                </div>
              </div>
              {query.data.comparisons.length ? (
                <div className="comparison-grid">
                  {query.data.comparisons.map((item, i) => (
                    <article className="price-comparison" key={i}>
                      <h3>{item.name}</h3>
                      <div className="price-range">
                        <strong className="positive">
                          {amount(Number(item.min_unit_minor), item.currency)}
                        </strong>
                        <span>
                          — {amount(Number(item.max_unit_minor), item.currency)}{" "}
                          / {unitLabel(item.unit)}
                        </span>
                      </div>
                      <p>
                        {t("Ниже всего:")} {item.best_merchant},{" "}
                        {dateLabel(item.best_on)}
                      </p>
                      <small>
                        {counted(item.receipt_count, [
                          t("чек"),
                          t("чека"),
                          t("чеков"),
                        ])}{" "}
                        {t("· разница при минимальной цене:")}{" "}
                        {amount(item.potential_minor, item.currency)}
                      </small>
                    </article>
                  ))}
                </div>
              ) : (
                <Empty
                  title={t("Для сравнения нужно больше покупок")}
                  text={t(
                    "При повторных покупках одного товара покажем разброс цен и магазин с самой низкой ценой в выбранном периоде.",
                  )}
                />
              )}
              <p className="analysis-footnote">
                {t(
                  "Цены рассчитаны с учётом скидок в строках чека. Исторический минимум не гарантирует сегодняшнюю цену. Возвраты учитываются в общем отчёте, но не распределены по отдельным товарам.",
                )}
              </p>
            </section>
          </>
        )
      )}
    </>
  );
}
