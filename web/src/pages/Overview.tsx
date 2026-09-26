import { t, getLocale } from "../i18n";
import { useQuery } from "@tanstack/react-query";
import { useSyncExternalStore } from "react";
import {
  ArrowDownLeft,
  ArrowRight,
  ArrowUpRight,
  CalendarClock,
  ChevronRight,
  Plus,
  ScanLine,
  Sparkles,
  Wallet,
} from "lucide-react";
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { api } from "../api";
import { useApp } from "../context";
import type { Dashboard, Transaction } from "../types";
import {
  Badge,
  CategoryIcon,
  Empty,
  ErrorBox,
  Loading,
  PageHeading,
  amount,
  dateLabel,
  monthLabel,
} from "../ui";

const reducedMotionQuery = window.matchMedia(
  "(prefers-reduced-motion: reduce)",
);
function subscribeMotion(onChange: () => void) {
  reducedMotionQuery.addEventListener("change", onChange);
  return () => reducedMotionQuery.removeEventListener("change", onChange);
}

export default function Overview() {
  const { month, open, navigate, categories, accounts, isAdmin } = useApp();
  const reducedMotion = useSyncExternalStore(
    subscribeMotion,
    () => reducedMotionQuery.matches,
  );
  const report = useQuery({
    queryKey: ["dashboard", month],
    queryFn: () => api<Dashboard>(`/dashboard?month=${month}`),
  });
  const recent = useQuery({
    queryKey: ["transactions", month, "recent"],
    queryFn: () =>
      api<{ items: Transaction[] }>(`/transactions?month=${month}&limit=5`),
  });
  if (report.isPending) return <Loading />;
  if (!report.data) return <ErrorBox error={report.error} />;
  const data = report.data;
  const ranked = data.categories
    .filter((c) => (c.spent_minor ?? 0) > 0)
    .sort((a, b) => (b.spent_minor ?? 0) - (a.spent_minor ?? 0));
  const pending = data.bills.filter((b) =>
    ["upcoming", "overdue"].includes(b.status),
  );
  const chart = data.chart.map((d) => ({
    ...d,
    expense: d.expense / 100,
    income: d.income / 100,
  }));
  const monthEmpty = data.expense_minor === 0 && data.income_minor === 0;
  return (
    <>
      <PageHeading
        eyebrow={t("PERSONAL FINANCE / ОБЗОР")}
        title={t("Больше ясности. Меньше лишнего.")}
        text={t("Всё важное за {0}", monthLabel(month))}
        actions={
          <button
            className="button secondary"
            onClick={() => open({ type: "upload" })}
          >
            <ScanLine size={18} />
            {t("Добавить чек")}
          </button>
        }
      />
      <div className="stats-grid">
        <section className="stat-card main-stat">
          <div className="stat-label">
            <span className="balance-eyebrow">
              <i /> {t("Финансовый пульс")}
            </span>
            <Wallet size={20} />
          </div>
          <strong>{amount(data.net_minor)}</strong>
          <span className="stat-note">
            {t("Остаток за месяц · доходы минус расходы")}
          </span>
          <button
            className="balance-link"
            onClick={() => navigate(isAdmin ? "accounts" : "transactions")}
          >
            {isAdmin ? t("Мои счета") : t("Все операции")}{" "}
            <ArrowUpRight size={17} />
          </button>
          <div className="balance-orbit" aria-hidden="true">
            <i />
            <i />
            <Wallet size={54} strokeWidth={1} />
          </div>
          <div className="stat-decoration" aria-hidden="true">
            <span />
            <span />
            <span />
            <span />
            <span />
            <span />
          </div>
        </section>
        <section className="stat-card">
          <div className="stat-label">
            <span>{t("Доходы")}</span>
            <span className="round-icon mint">
              <ArrowDownLeft size={18} />
            </span>
          </div>
          <strong>{amount(data.income_minor)}</strong>
          <button className="text-button" onClick={() => navigate("income")}>
            <Plus size={15} />
            {t("Открыть доходы")}
          </button>
        </section>
        <section className="stat-card">
          <div className="stat-label">
            <span>{t("Расходы")}</span>
            <span className="round-icon peach">
              <ArrowUpRight size={18} />
            </span>
          </div>
          <strong>{amount(data.expense_minor)}</strong>
          <span className="stat-note">{t("Возвраты покупок учтены")}</span>
        </section>
        <section className="stat-card">
          <div className="stat-label">
            <span>{t("Ещё к оплате")}</span>
            <span className="round-icon lavender">
              <CalendarClock size={18} />
            </span>
          </div>
          <strong>{amount(data.planned_remaining_minor)}</strong>
          <button
            className="text-button"
            disabled={!isAdmin}
            onClick={() => navigate("bills")}
          >
            {pending.length
              ? t("{0} обязательных платежей", pending.length)
              : t("Составить план платежей")}
            <ChevronRight size={16} />
          </button>
        </section>
      </div>
      {monthEmpty && isAdmin && (
        <div className="welcome-strip">
          <div className="round-icon mint">
            <Sparkles size={22} />
          </div>
          <div>
            <strong>{t("Первый шаг — первый расход")}</strong>
            <p>
              {t(
                "Введите покупку или отправьте фото чека. Finora соберёт вашу финансовую картину.",
              )}
            </p>
          </div>
          <button
            className="button primary"
            onClick={() => open({ type: "transaction" })}
          >
            {t("Добавить операцию")}
            <ArrowRight size={17} />
          </button>
        </div>
      )}
      <div className="dashboard-grid">
        <section className="panel chart-panel">
          <div className="panel-heading">
            <div>
              <h2>{t("Движение денег")}</h2>
              <p>{t("По дням месяца · MDL")}</p>
            </div>
            <div className="chart-legend">
              <span>
                <i className="dot green" />
                {t("Доходы")}
              </span>
              <span>
                <i className="dot purple" />
                {t("Расходы")}
              </span>
            </div>
          </div>
          <div className="chart-wrap">
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart
                data={chart}
                margin={{ top: 15, right: 10, left: -16, bottom: 0 }}
              >
                <defs>
                  <linearGradient id="incomeFill" x1="0" x2="0" y1="0" y2="1">
                    <stop
                      offset="0%"
                      stopColor="var(--chart-income)"
                      stopOpacity={0.17}
                    />
                    <stop
                      offset="100%"
                      stopColor="var(--chart-income)"
                      stopOpacity={0}
                    />
                  </linearGradient>
                  <linearGradient id="expenseFill" x1="0" x2="0" y1="0" y2="1">
                    <stop
                      offset="0%"
                      stopColor="var(--chart-expense)"
                      stopOpacity={0.16}
                    />
                    <stop
                      offset="100%"
                      stopColor="var(--chart-expense)"
                      stopOpacity={0}
                    />
                  </linearGradient>
                </defs>
                <CartesianGrid
                  strokeDasharray="4 5"
                  vertical={false}
                  stroke="var(--chart-grid)"
                />
                <XAxis
                  dataKey="day"
                  tickLine={false}
                  axisLine={false}
                  interval={5}
                  tick={{ fill: "var(--muted)", fontSize: 12 }}
                />
                <YAxis
                  tickLine={false}
                  axisLine={false}
                  tick={{ fill: "var(--muted)", fontSize: 12 }}
                  width={64}
                  tickFormatter={(n) =>
                    Number(n) >= 1000 ? t("{0}к", Number(n) / 1000) : String(n)
                  }
                />
                <Tooltip
                  contentStyle={{
                    background: "var(--surface)",
                    color: "var(--text)",
                    borderRadius: 12,
                    borderColor: "var(--chart-grid)",
                    fontSize: 14,
                  }}
                  formatter={(v, name) => [
                    `${Number(v).toLocaleString(getLocale())} MDL`,
                    name === "income" ? t("Доходы") : t("Расходы"),
                  ]}
                  labelFormatter={(d) => `${d} ${monthLabel(month)}`}
                />
                <Area
                  isAnimationActive={!reducedMotion}
                  type="monotone"
                  dataKey="income"
                  stroke="var(--chart-income)"
                  strokeWidth={2.5}
                  fill="url(#incomeFill)"
                />
                <Area
                  isAnimationActive={!reducedMotion}
                  type="monotone"
                  dataKey="expense"
                  stroke="var(--chart-expense)"
                  strokeWidth={2.5}
                  fill="url(#expenseFill)"
                />
              </AreaChart>
            </ResponsiveContainer>
          </div>
          <div className="panel-foot">
            <span>{data.comparison_label}</span>
            <span>
              {t("Расходы ранее:")} <b>{amount(data.previous_expense_minor)}</b>
            </span>
          </div>
        </section>
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>{t("Куда уходят деньги")}</h2>
              <p>{t("Расходы по категориям")}</p>
            </div>
            <button
              className="icon-button"
              aria-label={t("Открыть бюджеты")}
              hidden={!isAdmin}
              onClick={() => navigate("budgets")}
            >
              <ArrowUpRight size={20} />
            </button>
          </div>
          {ranked.length ? (
            <div className="category-ranking">
              {ranked.slice(0, 5).map((c) => (
                <div className="ranking-row" key={c.id}>
                  <CategoryIcon category={c} />
                  <div>
                    <div className="between">
                      <span>{c.name}</span>
                      <strong>{amount(c.spent_minor, "MDL", true)}</strong>
                    </div>
                    <div className="meter">
                      <span
                        style={{
                          width: `${Math.min(100, ((c.spent_minor ?? 0) / Math.max(data.expense_minor, 1)) * 100)}%`,
                          background: c.color,
                        }}
                      />
                    </div>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <Empty
              title={t("Пока всё впереди")}
              text={t("Категории появятся после первой покупки.")}
            />
          )}
        </section>
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>{t("Последние операции")}</h2>
              <p>{t("Ваша история за выбранный месяц")}</p>
            </div>
            <button
              className="text-button"
              onClick={() => navigate("transactions")}
            >
              {t("Все операции")}
              <ArrowRight size={17} />
            </button>
          </div>
          {recent.data?.items.length ? (
            <div className="recent-list">
              {recent.data.items.map((tx) => (
                <button
                  className="recent-row"
                  key={tx.id}
                  onClick={() => navigate("transactions")}
                >
                  <CategoryIcon
                    category={categories.find((c) => c.id === tx.category_id)}
                  />
                  <div>
                    <strong>
                      {tx.merchant ||
                        tx.note ||
                        (tx.kind === "income" ? t("Доход") : t("Операция"))}
                    </strong>
                    <span>
                      {dateLabel(tx.occurred_on)} ·{" "}
                      {accounts.find((a) => a.id === tx.account_id)?.name}
                    </span>
                  </div>
                  <b
                    className={
                      [
                        "income",
                        "refund",
                        "debt_repayment_in",
                        "debt_borrow",
                      ].includes(tx.kind)
                        ? "positive"
                        : ""
                    }
                  >
                    {[
                      "income",
                      "refund",
                      "debt_repayment_in",
                      "debt_borrow",
                    ].includes(tx.kind)
                      ? "+"
                      : "−"}
                    {amount(tx.amount_minor, tx.currency)}
                  </b>
                </button>
              ))}
            </div>
          ) : (
            <Empty
              title={t("Операций ещё нет")}
              text={t("Начните вести учёт в удобном вам темпе.")}
              action={
                isAdmin && (
                  <button
                    className="text-button"
                    onClick={() => open({ type: "transaction" })}
                  >
                    {t("Добавить первую")}
                    <ArrowRight size={16} />
                  </button>
                )
              }
            />
          )}
        </section>
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>{t("Ближайшие платежи")}</h2>
              <p>{t("Чтобы ничего не упустить")}</p>
            </div>
            <button
              className="icon-button"
              aria-label={t("Открыть платежи")}
              hidden={!isAdmin}
              disabled={!isAdmin}
              onClick={() => navigate("bills")}
            >
              <ArrowUpRight size={20} />
            </button>
          </div>
          {pending.length ? (
            <div className="upcoming-list">
              {pending.slice(0, 4).map((b) => (
                <button
                  className="upcoming-row"
                  key={b.id}
                  disabled={!isAdmin}
                  onClick={() => navigate("bills")}
                >
                  <span className="date-tile">
                    <b>{b.due_date.slice(8)}</b>
                    <small>
                      {dateLabel(b.due_date).split(" ").slice(1).join(" ")}
                    </small>
                  </span>
                  <span>
                    <strong>{b.name}</strong>
                    <Badge status={b.status} />
                  </span>
                  <b>{amount(b.amount_minor, b.currency)}</b>
                </button>
              ))}
            </div>
          ) : (
            <Empty
              icon={<CalendarClock size={28} />}
              title={t("Нет ожидающих платежей")}
              text={t(
                "Добавьте аренду, связь и подписки, чтобы видеть обязательства заранее.",
              )}
            />
          )}
        </section>
      </div>
      <button className="insight-banner" onClick={() => navigate("insights")}>
        <span className="insight-icon">
          <Sparkles size={25} />
        </span>
        <span>
          <strong>{t("Больше ясности. Меньше лишних трат.")}</strong>
          <span>
            {t("Посмотрите, что изменилось в расходах и где можно сэкономить.")}
          </span>
        </span>
        <ArrowRight size={24} />
      </button>
    </>
  );
}
