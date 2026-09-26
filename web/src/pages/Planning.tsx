import { t } from "../i18n";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  ArrowDownLeft,
  ArrowUpRight,
  CalendarClock,
  Check,
  CirclePause,
  Plus,
  Target,
  Users,
} from "lucide-react";
import { api, send, useAction } from "../api";
import { useApp } from "../context";
import type {
  Bill,
  BillTemplate,
  Dashboard,
  Debt,
  Transaction,
} from "../types";
import {
  AccountSelect,
  Badge,
  CategoryIcon,
  CategorySelect,
  CurrencySelect,
  Empty,
  ErrorBox,
  Field,
  Form,
  Loading,
  Modal,
  MoneyInput,
  PageHeading,
  Submit,
  amount,
  dateLabel,
  decimal,
  key,
  recurrenceLabels,
  today,
} from "../ui";

export function Budgets() {
  const { month, categories, toast } = useApp();
  const query = useQuery({
    queryKey: ["dashboard", month],
    queryFn: () => api<Dashboard>(`/dashboard?month=${month}`),
  });
  const [editing, setEditing] = useState<string | null>(null);
  const [value, setValue] = useState("");
  const save = useAction(
    () =>
      send("/budgets", { month, category_id: editing, amount: value }, "PUT"),
    () => {
      setEditing(null);
      toast(t("Лимит сохранён"));
    },
  );
  const remove = useAction(
    (id: string) => send(`/budgets/${id}`, undefined, "DELETE"),
    () => {
      setEditing(null);
      toast(t("Лимит удалён"));
    },
  );
  const data = query.data;
  const total =
    data?.categories.reduce((s, c) => s + (c.budget_minor ?? 0), 0) ?? 0;
  const category = data?.categories.find((c) => c.id === editing);
  return (
    <>
      <PageHeading
        eyebrow={t("ПЛАН НА МЕСЯЦ")}
        title={t("Тратьте с намерением")}
        text={t(
          "Установите комфортные лимиты. Приложение покажет, сколько ещё доступно.",
        )}
      />
      <div className="budget-summary panel">
        <div className="round-icon mint">
          <Target size={25} />
        </div>
        <div>
          <span>{t("Запланировано по категориям")}</span>
          <strong>{amount(total)}</strong>
        </div>
        <div>
          <span>{t("Фактические расходы")}</span>
          <strong>{amount(data?.expense_minor)}</strong>
        </div>
        <p>
          {t("Лимиты не списывают деньги.")}
          <br />
          {t("Каждый месяц имеет собственный план.")}
        </p>
      </div>
      <ErrorBox error={query.error ?? remove.error} />
      {query.isPending ? (
        <Loading />
      ) : (
        <div className="budget-grid">
          {data?.categories
            .filter((c) => c.id !== "uncategorized")
            .map((c) => {
              const spent = c.spent_minor ?? 0;
              const budget = c.budget_minor;
              const over = budget != null && spent > budget;
              return (
                <section key={c.id} className="panel budget-card">
                  <div className="between">
                    <CategoryIcon category={c} />
                    {budget ? (
                      <Badge status={over ? "overdue" : "posted"}>
                        {over
                          ? t("Лимит превышен")
                          : t(
                              "{0}% использовано",
                              Math.round((spent / budget) * 100),
                            )}
                      </Badge>
                    ) : (
                      <Badge>{t("Без лимита")}</Badge>
                    )}
                  </div>
                  <h2>{c.name}</h2>
                  <div className="budget-amount">
                    <strong>{amount(spent, "MDL", true)}</strong>
                    <span>
                      {budget
                        ? t("из {0}", amount(budget, "MDL", true))
                        : t("потрачено")}
                    </span>
                  </div>
                  <div className="meter">
                    <span
                      style={{
                        width: budget
                          ? `${Math.min(100, Math.max(0, (spent / budget) * 100))}%`
                          : "0%",
                        background: over ? "#cc665e" : c.color,
                      }}
                    />
                  </div>
                  <div className="between">
                    <small>
                      {budget
                        ? over
                          ? t("Сверх лимита {0}", amount(spent - budget))
                          : t("Осталось {0}", amount(budget - spent))
                        : t("Вы решаете, сколько потратить")}
                    </small>
                    <button
                      className="text-button"
                      onClick={() => {
                        setEditing(c.id);
                        setValue(budget ? decimal(budget) : "");
                      }}
                    >
                      {budget ? t("Изменить") : t("Задать лимит")}
                    </button>
                  </div>
                </section>
              );
            })}
        </div>
      )}
      {editing && (
        <Modal
          title={t(
            "Лимит: {0}",
            categories.find((c) => c.id === editing)?.name,
          )}
          description={t("Сумма в MDL для выбранного месяца.")}
          onClose={() => setEditing(null)}
        >
          <Form onSubmit={() => save.mutate(undefined)}>
            <Field label={t("Лимит, MDL")}>
              <MoneyInput value={value} onChange={setValue} autoFocus />
            </Field>
            <ErrorBox error={save.error} />
            <footer className="modal-footer">
              {category?.budget_id && (
                <button
                  type="button"
                  className="button secondary"
                  onClick={() => remove.mutate(category.budget_id!)}
                >
                  {t("Удалить лимит")}
                </button>
              )}
              <Submit pending={save.isPending} />
            </footer>
          </Form>
        </Modal>
      )}
    </>
  );
}

export function Debts() {
  const { toast, isAdmin } = useApp();
  const query = useQuery({
    queryKey: ["debts"],
    queryFn: () => api<Debt[]>("/debts"),
  });
  const [creating, setCreating] = useState(false);
  const [movement, setMovement] = useState<{
    debt: Debt;
    mode: DebtMovementMode;
  } | null>(null);
  const [direction, setDirection] = useState("all");
  const [closed, setClosed] = useState(false);
  const totals = (type: string) => {
    const totals: Record<string, number> = {};
    for (const d of query.data ?? [])
      if (d.direction === type)
        totals[d.currency] = (totals[d.currency] ?? 0) + d.remaining_minor;
    return (
      Object.entries(totals)
        .filter(([, v]) => v > 0)
        .map(([c, v]) => amount(v, c))
        .join(" · ") || amount(0)
    );
  };
  const shown = query.data?.filter(
    (d) =>
      (direction === "all" || d.direction === direction) &&
      (closed || d.remaining_minor > 0),
  );
  return (
    <>
      <PageHeading
        eyebrow={t("ДЕНЬГИ И ЛЮДИ")}
        title={t("Долги без неловкости")}
        text={t(
          "Помните, кому дали и у кого заняли. Частичные возвраты учитываются автоматически.",
        )}
        actions={
          isAdmin && (
            <button
              className="button primary"
              onClick={() => setCreating(true)}
            >
              <Plus size={18} />
              {t("Записать долг")}
            </button>
          )
        }
      />
      <div className="two-cards">
        <section className="panel balance-summary">
          <span className="round-icon mint">
            <ArrowDownLeft size={22} />
          </span>
          <div>
            <span>{t("Вам должны")}</span>
            <strong>{totals("lent")}</strong>
          </div>
        </section>
        <section className="panel balance-summary">
          <span className="round-icon peach">
            <ArrowUpRight size={22} />
          </span>
          <div>
            <span>{t("Вы должны")}</span>
            <strong>{totals("borrowed")}</strong>
          </div>
        </section>
      </div>
      <section className="panel">
        <div className="filters">
          <div className="segmented compact">
            <button
              className={direction === "all" ? "selected" : ""}
              onClick={() => setDirection("all")}
            >
              {t("Все")}
            </button>
            <button
              className={direction === "lent" ? "selected" : ""}
              onClick={() => setDirection("lent")}
            >
              {t("Мне должны")}
            </button>
            <button
              className={direction === "borrowed" ? "selected" : ""}
              onClick={() => setDirection("borrowed")}
            >
              {t("Я должен")}
            </button>
          </div>
          <label className="check-field">
            <input
              type="checkbox"
              checked={closed}
              onChange={(e) => setClosed(e.target.checked)}
            />
            {t("Показывать закрытые")}
          </label>
        </div>
        <ErrorBox error={query.error} />
        {query.isPending ? (
          <Loading />
        ) : shown?.length ? (
          <div className="debt-list">
            {shown.map((d) => (
              <div className="debt-row" key={d.id}>
                <span className={`person-avatar ${d.direction}`}>
                  {d.person.slice(0, 2).toUpperCase()}
                </span>
                <div className="grow">
                  <strong>{d.person}</strong>
                  <p>
                    {d.direction === "lent" ? t("Вам должны") : t("Вы должны")}
                    {d.due_date ? t(" · до {0}", dateLabel(d.due_date)) : ""}
                  </p>
                  {d.note && <small>{d.note}</small>}
                </div>
                <div className="debt-value">
                  <strong>{amount(d.remaining_minor, d.currency)}</strong>
                  <Badge
                    status={
                      d.remaining_minor === 0
                        ? "paid"
                        : d.due_date && d.due_date < today()
                          ? "overdue"
                          : undefined
                    }
                  >
                    {d.remaining_minor === 0
                      ? t("Закрыт")
                      : d.due_date && d.due_date < today()
                        ? t("Срок прошёл")
                        : t("Открыт")}
                  </Badge>
                </div>
                {isAdmin && (
                  <div className="debt-actions">
                    {d.remaining_minor > 0 && (
                      <>
                        <button
                          className="button secondary"
                          onClick={() =>
                            setMovement({ debt: d, mode: "partial" })
                          }
                        >
                          {t("Погасить частично")}
                        </button>
                        <button
                          className="button secondary"
                          onClick={() => setMovement({ debt: d, mode: "full" })}
                        >
                          <Check size={16} /> {t("Погасить полностью")}
                        </button>
                      </>
                    )}
                    <button
                      className="text-button"
                      onClick={() => setMovement({ debt: d, mode: "increase" })}
                    >
                      <Plus size={16} /> {t("Увеличить долг")}
                    </button>
                  </div>
                )}
              </div>
            ))}
          </div>
        ) : (
          <Empty
            icon={<Users size={28} />}
            title={t("Всё спокойно")}
            text={t(
              "Здесь будут ваши долги и возвраты. Выдача и получение долга не считаются расходом и доходом.",
            )}
            action={
              isAdmin && (
                <button
                  className="text-button"
                  onClick={() => setCreating(true)}
                >
                  <Plus size={17} />
                  {t("Добавить запись")}
                </button>
              )
            }
          />
        )}
      </section>
      {creating && (
        <DebtForm
          onClose={() => setCreating(false)}
          onDone={() => {
            setCreating(false);
            toast(t("Долг записан"));
          }}
        />
      )}{" "}
      {movement && (
        <DebtMovementForm
          debt={movement.debt}
          mode={movement.mode}
          onClose={() => setMovement(null)}
          onDone={() => {
            setMovement(null);
            toast(
              movement.mode === "increase"
                ? t("Долг увеличен")
                : movement.mode === "full"
                  ? t("Долг полностью погашен")
                  : t("Погашение учтено"),
            );
          }}
        />
      )}
    </>
  );
}

function DebtForm({
  onClose,
  onDone,
}: {
  onClose: () => void;
  onDone: () => void;
}) {
  const { accounts } = useApp();
  const [person, setPerson] = useState("");
  const [direction, setDirection] = useState("lent");
  const [mode, setMode] = useState("new");
  const [value, setValue] = useState("");
  const [currency, setCurrency] = useState("MDL");
  const [account, setAccount] = useState("");
  const [date, setDate] = useState(today());
  const [due, setDue] = useState("");
  const [rate, setRate] = useState("");
  const [note, setNote] = useState("");
  const [requestKey] = useState(key);
  const save = useAction(
    () =>
      send("/debts", {
        person,
        direction,
        mode,
        amount: value,
        currency,
        account_id: mode === "new" ? account : null,
        occurred_on: date,
        due_date: due || null,
        fx_rate: currency === "MDL" || mode === "existing" ? null : rate,
        note,
        idempotency_key: requestKey,
      }),
    onDone,
  );
  return (
    <Modal title={t("Новый долг")} onClose={onClose}>
      <Form onSubmit={() => save.mutate(undefined)}>
        <div className="segmented">
          <button
            type="button"
            className={direction === "lent" ? "selected" : ""}
            onClick={() => setDirection("lent")}
          >
            {t("Я дал в долг")}
          </button>
          <button
            type="button"
            className={direction === "borrowed" ? "selected" : ""}
            onClick={() => setDirection("borrowed")}
          >
            {t("Я взял в долг")}
          </button>
        </div>
        <div className="form-grid">
          <Field label={t("Имя человека")} wide>
            <input
              required
              maxLength={100}
              value={person}
              onChange={(e) => setPerson(e.target.value)}
              placeholder={t("Например, Андрей")}
              autoFocus
            />
          </Field>
          <Field label={t("Сумма")}>
            <MoneyInput value={value} onChange={setValue} />
          </Field>
          <Field label={t("Валюта")}>
            <CurrencySelect value={currency} onChange={setCurrency} />
          </Field>
          <Field label={t("Как учитывать")} wide>
            <select value={mode} onChange={(e) => setMode(e.target.value)}>
              <option value="new">
                {t("Деньги передаются сейчас — изменить остаток счёта")}
              </option>
              <option value="existing">
                {t("Старый долг — остаток счёта уже учитывает его")}
              </option>
            </select>
          </Field>
          {mode === "new" && (
            <Field label={t("Счёт")}>
              <AccountSelect
                accounts={accounts}
                value={account}
                onChange={setAccount}
                currency={currency}
              />
            </Field>
          )}
          <Field label={t("Дата передачи")}>
            <input
              type="date"
              required
              max={today()}
              min="1990-01-01"
              value={date}
              onChange={(e) => setDate(e.target.value)}
            />
          </Field>
          <Field label={t("Вернуть до · необязательно")}>
            <input
              type="date"
              value={due}
              onChange={(e) => setDue(e.target.value)}
              min="1990-01-01"
              max="2100-12-31"
            />
          </Field>
          {currency !== "MDL" && mode === "new" && (
            <Field label={t("Курс к MDL")}>
              <input
                type="number"
                min="0.00000001"
                step="0.00000001"
                required
                value={rate}
                onChange={(e) => setRate(e.target.value)}
              />
            </Field>
          )}
          <Field label={t("Примечание")} wide>
            <textarea
              rows={2}
              value={note}
              onChange={(e) => setNote(e.target.value)}
              maxLength={3000}
            />
          </Field>
        </div>
        <ErrorBox error={save.error} />
        <footer className="modal-footer">
          <Submit pending={save.isPending} />
        </footer>
      </Form>
    </Modal>
  );
}

type DebtMovementMode = "partial" | "full" | "increase";

function DebtMovementForm({
  debt,
  mode,
  onClose,
  onDone,
}: {
  debt: Debt;
  mode: DebtMovementMode;
  onClose: () => void;
  onDone: () => void;
}) {
  const { accounts } = useApp();
  const increasing = mode === "increase";
  const full = mode === "full";
  const [value, setValue] = useState(full ? decimal(debt.remaining_minor) : "");
  const [account, setAccount] = useState(
    accounts.find((a) => !a.archived && a.currency === debt.currency)?.id ?? "",
  );
  const [date, setDate] = useState(today());
  const [rate, setRate] = useState("");
  const [note, setNote] = useState("");
  const [requestKey] = useState(key);
  const action = useAction(
    () =>
      send(`/debts/${debt.id}/${increasing ? "increase" : "repay"}`, {
        amount: value,
        account_id: account,
        occurred_on: date,
        fx_rate: debt.currency === "MDL" ? null : rate,
        note,
        ...(increasing ? {} : { full }),
        idempotency_key: requestKey,
      }),
    onDone,
  );
  return (
    <Modal
      title={`${increasing ? t("Увеличить долг") : full ? t("Погасить полностью") : t("Погасить частично")}: ${debt.person}`}
      description={t(
        "Остаток {0}. {1}",
        amount(debt.remaining_minor, debt.currency),
        increasing
          ? t(
              "Укажите дополнительную сумму. Передача денег изменит баланс выбранного счёта.",
            )
          : full
            ? t("Весь остаток будет погашен после подтверждения.")
            : t("Укажите сумму фактического возврата."),
      )}
      onClose={onClose}
    >
      <Form onSubmit={() => action.mutate(undefined)}>
        <div className="form-grid">
          <Field label={t("Сумма · {0}", debt.currency)}>
            {full ? (
              <input value={value} readOnly aria-readonly="true" />
            ) : (
              <MoneyInput value={value} onChange={setValue} autoFocus />
            )}
          </Field>
          <Field label={t("Дата")}>
            <input
              required
              type="date"
              min="1990-01-01"
              max={today()}
              value={date}
              onChange={(e) => setDate(e.target.value)}
            />
          </Field>
          <Field
            label={
              (debt.direction === "lent") !== increasing
                ? t("Получено на счёт")
                : t("Списать со счёта")
            }
            wide
          >
            <AccountSelect
              accounts={accounts}
              value={account}
              onChange={setAccount}
              currency={debt.currency}
            />
          </Field>
          {debt.currency !== "MDL" && (
            <Field label={t("Курс к MDL")}>
              <input
                required
                type="number"
                min="0.00000001"
                step="0.00000001"
                value={rate}
                onChange={(e) => setRate(e.target.value)}
              />
            </Field>
          )}
          <Field label={t("Примечание · необязательно")} wide>
            <textarea
              rows={2}
              value={note}
              onChange={(e) => setNote(e.target.value)}
              maxLength={3000}
            />
          </Field>
        </div>
        <ErrorBox error={action.error} />
        <footer className="modal-footer">
          <Submit pending={action.isPending}>
            {increasing
              ? t("Увеличить долг")
              : full
                ? t("Погасить весь остаток")
                : t("Записать погашение")}
          </Submit>
        </footer>
      </Form>
    </Modal>
  );
}

export function Bills() {
  const { month, toast } = useApp();
  const query = useQuery({
    queryKey: ["bills", month],
    queryFn: () => api<Bill[]>(`/bills?month=${month}`),
  });
  const templates = useQuery({
    queryKey: ["bill-templates"],
    queryFn: () => api<BillTemplate[]>("/bills/templates"),
  });
  const [creating, setCreating] = useState(false);
  const [paying, setPaying] = useState<Bill | null>(null);
  const [tab, setTab] = useState("month");
  const toggle = useAction(
    (id: string) => send(`/bills/${id}/toggle`),
    () => toast(t("План обновлён")),
  );
  const skip = useAction(
    (id: string) => send(`/occurrences/${id}/skip`),
    () => toast(t("Платёж обновлён")),
  );
  return (
    <>
      <PageHeading
        eyebrow={t("КАЛЕНДАРЬ ОБЯЗАТЕЛЬСТВ")}
        title={t("Платежи без сюрпризов")}
        text={t(
          "Аренда, связь, подписки и регулярные расходы. План превращается в расход только после оплаты.",
        )}
        actions={
          <button className="button primary" onClick={() => setCreating(true)}>
            <Plus size={18} />
            {t("Добавить платёж")}
          </button>
        }
      />
      <section className="panel">
        <div className="filters">
          <div className="segmented compact">
            <button
              className={tab === "month" ? "selected" : ""}
              onClick={() => setTab("month")}
            >
              {t("Этот месяц")}
            </button>
            <button
              className={tab === "templates" ? "selected" : ""}
              onClick={() => setTab("templates")}
            >
              {t("Все регулярные")}
            </button>
          </div>
          <span className="muted">
            {query.data?.filter((b) => b.status === "paid").length ?? 0}{" "}
            {t("оплачено в этом месяце")}
          </span>
        </div>
        <ErrorBox
          error={query.error ?? templates.error ?? toggle.error ?? skip.error}
        />
        {query.isPending ? (
          <Loading />
        ) : tab === "month" ? (
          query.data?.length ? (
            <div className="bill-list">
              {query.data.map((b) => (
                <div className="bill-row" key={b.id}>
                  <span
                    className={`date-tile ${b.status === "overdue" ? "late" : ""}`}
                  >
                    <b>{b.due_date.slice(8)}</b>
                    <small>
                      {dateLabel(b.due_date).split(" ").slice(1).join(" ")}
                    </small>
                  </span>
                  <div className="grow">
                    <strong>{b.name}</strong>
                    <p>{recurrenceLabels[b.recurrence]}</p>
                  </div>
                  <Badge status={b.status} />
                  <strong className="money">
                    {amount(b.amount_minor, b.currency)}
                  </strong>
                  {b.status !== "paid" && (
                    <>
                      <button
                        className="button secondary"
                        onClick={() => setPaying(b)}
                      >
                        <Check size={16} />
                        {t("Оплачен")}
                      </button>
                      <button
                        className="icon-button"
                        disabled={skip.isPending}
                        title={
                          b.status === "skipped"
                            ? t("Вернуть в план")
                            : t("Пропустить этот платёж")
                        }
                        aria-label={
                          b.status === "skipped"
                            ? t("Вернуть в план")
                            : t("Пропустить платёж")
                        }
                        onClick={() => skip.mutate(b.id)}
                      >
                        <CirclePause size={19} />
                      </button>
                    </>
                  )}
                </div>
              ))}
            </div>
          ) : (
            <Empty
              icon={<CalendarClock size={28} />}
              title={t("Месяц без запланированных платежей")}
              text={t(
                "Добавьте регулярные обязательства — и свободный остаток станет понятнее.",
              )}
            />
          )
        ) : templates.data?.length ? (
          <div className="bill-list">
            {templates.data.map((b) => (
              <div className="bill-row" key={b.id}>
                <span className="round-icon lavender">
                  <CalendarClock size={22} />
                </span>
                <div className="grow">
                  <strong>{b.name}</strong>
                  <p>
                    {recurrenceLabels[b.recurrence]} {t("· с")}{" "}
                    {dateLabel(b.start_date)}
                  </p>
                </div>
                <strong>{amount(b.amount_minor, b.currency)}</strong>
                <Badge status={b.active ? "posted" : "skipped"}>
                  {b.active ? t("Активен") : t("Остановлен")}
                </Badge>
                <button
                  className="button secondary"
                  disabled={toggle.isPending}
                  onClick={() => toggle.mutate(b.id)}
                >
                  {b.active ? t("Остановить") : t("Возобновить")}
                </button>
              </div>
            ))}
          </div>
        ) : (
          <Empty
            title={t("Регулярных платежей пока нет")}
            text={t(
              "Создайте первый шаблон, и платежи будут появляться каждый месяц.",
            )}
          />
        )}
      </section>
      {creating && (
        <BillForm
          onClose={() => setCreating(false)}
          onDone={() => {
            setCreating(false);
            toast(t("Платёж добавлен"));
          }}
        />
      )}{" "}
      {paying && (
        <BillPay
          bill={paying}
          onClose={() => setPaying(null)}
          onDone={() => {
            setPaying(null);
            toast(t("Оплата записана"));
          }}
        />
      )}
    </>
  );
}

function BillForm({
  onClose,
  onDone,
}: {
  onClose: () => void;
  onDone: () => void;
}) {
  const { accounts, categories } = useApp();
  const [name, setName] = useState("");
  const [value, setValue] = useState("");
  const [currency, setCurrency] = useState("MDL");
  const [rate, setRate] = useState("");
  const [category, setCategory] = useState("");
  const [account, setAccount] = useState("");
  const [date, setDate] = useState(today());
  const [repeat, setRepeat] = useState("monthly");
  const save = useAction(
    () =>
      send("/bills", {
        name,
        amount: value,
        currency,
        fx_rate: currency === "MDL" ? null : rate,
        category_id: category || null,
        account_id: account || null,
        start_date: date,
        recurrence: repeat,
      }),
    onDone,
  );
  return (
    <Modal title={t("Запланировать платёж")} onClose={onClose}>
      <Form onSubmit={() => save.mutate(undefined)}>
        <div className="form-grid">
          <Field label={t("Название")} wide>
            <input
              required
              autoFocus
              value={name}
              maxLength={100}
              onChange={(e) => setName(e.target.value)}
              placeholder={t("Например, интернет")}
            />
          </Field>
          <Field label={t("Сумма")}>
            <MoneyInput value={value} onChange={setValue} />
          </Field>
          <Field label={t("Валюта")}>
            <CurrencySelect value={currency} onChange={setCurrency} />
          </Field>
          <Field label={t("Первая дата оплаты")}>
            <input
              type="date"
              min="1990-01-01"
              max="2100-12-31"
              required
              value={date}
              onChange={(e) => setDate(e.target.value)}
            />
          </Field>
          <Field label={t("Повторять")}>
            <select value={repeat} onChange={(e) => setRepeat(e.target.value)}>
              {Object.entries(recurrenceLabels).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </select>
          </Field>
          <Field label={t("Категория")}>
            <CategorySelect
              categories={categories}
              value={category}
              onChange={setCategory}
            />
          </Field>
          <Field label={t("Счёт по умолчанию")}>
            <AccountSelect
              accounts={accounts}
              value={account}
              onChange={setAccount}
              currency={currency}
              optional
            />
          </Field>
          {currency !== "MDL" && (
            <Field label={t("Плановый курс к MDL")}>
              <input
                required
                type="number"
                step="0.00000001"
                min="0.00000001"
                value={rate}
                onChange={(e) => setRate(e.target.value)}
              />
            </Field>
          )}
        </div>
        <ErrorBox error={save.error} />
        <footer className="modal-footer">
          <Submit pending={save.isPending} />
        </footer>
      </Form>
    </Modal>
  );
}

function BillPay({
  bill,
  onClose,
  onDone,
}: {
  bill: Bill;
  onClose: () => void;
  onDone: () => void;
}) {
  const { accounts } = useApp();
  const [value, setValue] = useState(decimal(bill.amount_minor));
  const [date, setDate] = useState(today());
  const [account, setAccount] = useState(bill.account_id ?? "");
  const [rate, setRate] = useState(bill.fx_rate);
  const [transaction, setTransaction] = useState("");
  const matches = useQuery({
    queryKey: ["bill-matches", date, account, value],
    enabled: !!account && !!date && Number(value) > 0,
    queryFn: () =>
      api<{ items: Transaction[] }>(
        `/transactions?month=${date.slice(0, 7)}&account_id=${account}&kind=expense&limit=200`,
      ),
  });
  const candidates = (matches.data?.items ?? []).filter(
    (item) =>
      item.occurred_on === date &&
      item.amount_minor === Math.round(Number(value) * 100) &&
      !item.occurrence_id &&
      !item.refund_of,
  );
  const [requestKey] = useState(key);
  const save = useAction(
    () =>
      send(`/occurrences/${bill.id}/pay`, {
        amount: value,
        account_id: account,
        occurred_on: date,
        fx_rate: bill.currency === "MDL" ? null : rate,
        transaction_id: candidates.some((item) => item.id === transaction)
          ? transaction
          : null,
        idempotency_key: requestKey,
      }),
    onDone,
  );
  return (
    <Modal
      title={t("Оплата: {0}", bill.name)}
      description={t(
        "Укажите фактическую сумму — она может отличаться от плановой.",
      )}
      onClose={onClose}
    >
      <Form onSubmit={() => save.mutate(undefined)}>
        <div className="form-grid">
          <Field label={t("Сумма · {0}", bill.currency)}>
            <MoneyInput value={value} onChange={setValue} autoFocus />
          </Field>
          <Field label={t("Дата оплаты")}>
            <input
              required
              type="date"
              max={today()}
              min="1990-01-01"
              value={date}
              onChange={(e) => setDate(e.target.value)}
            />
          </Field>
          <Field label={t("Счёт")} wide>
            <AccountSelect
              accounts={accounts}
              value={account}
              onChange={setAccount}
              currency={bill.currency}
            />
          </Field>
          {bill.currency !== "MDL" && (
            <Field label={t("Курс на дату оплаты")}>
              <input
                required
                type="number"
                min="0.00000001"
                step="0.00000001"
                value={rate}
                onChange={(e) => setRate(e.target.value)}
              />
            </Field>
          )}
        </div>
        <Field label={t("Учёт оплаты")}>
          <select
            value={
              candidates.some((item) => item.id === transaction)
                ? transaction
                : ""
            }
            onChange={(e) => setTransaction(e.target.value)}
          >
            <option value="">{t("Создать новый расход")}</option>
            {candidates.map((item) => (
              <option key={item.id} value={item.id}>
                {t("Уже записано:")} {item.merchant || t("Расход")} ·{" "}
                {amount(item.amount_minor, item.currency)}
              </option>
            ))}
          </select>
          <small>
            {t(
              "Здесь появятся расходы с той же датой, суммой и счётом. Привязка не списывает деньги повторно.",
            )}
          </small>
        </Field>
        <ErrorBox error={save.error ?? matches.error} />
        <footer className="modal-footer">
          <Submit pending={save.isPending}>{t("Записать оплату")}</Submit>
        </footer>
      </Form>
    </Modal>
  );
}
