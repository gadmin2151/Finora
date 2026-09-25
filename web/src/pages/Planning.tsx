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
      toast("Лимит сохранён");
    },
  );
  const remove = useAction(
    (id: string) => send(`/budgets/${id}`, undefined, "DELETE"),
    () => {
      setEditing(null);
      toast("Лимит удалён");
    },
  );
  const data = query.data;
  const total =
    data?.categories.reduce((s, c) => s + (c.budget_minor ?? 0), 0) ?? 0;
  const category = data?.categories.find((c) => c.id === editing);
  return (
    <>
      <PageHeading
        eyebrow="ПЛАН НА МЕСЯЦ"
        title="Тратьте с намерением"
        text="Установите комфортные лимиты. Приложение покажет, сколько ещё доступно."
      />
      <div className="budget-summary panel">
        <div className="round-icon mint">
          <Target size={25} />
        </div>
        <div>
          <span>Запланировано по категориям</span>
          <strong>{amount(total)}</strong>
        </div>
        <div>
          <span>Фактические расходы</span>
          <strong>{amount(data?.expense_minor)}</strong>
        </div>
        <p>
          Лимиты не списывают деньги.
          <br />
          Каждый месяц имеет собственный план.
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
                          ? "Лимит превышен"
                          : `${Math.round((spent / budget) * 100)}% использовано`}
                      </Badge>
                    ) : (
                      <Badge>Без лимита</Badge>
                    )}
                  </div>
                  <h2>{c.name}</h2>
                  <div className="budget-amount">
                    <strong>{amount(spent, "MDL", true)}</strong>
                    <span>
                      {budget
                        ? `из ${amount(budget, "MDL", true)}`
                        : "потрачено"}
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
                          ? `Сверх лимита ${amount(spent - budget)}`
                          : `Осталось ${amount(budget - spent)}`
                        : "Вы решаете, сколько потратить"}
                    </small>
                    <button
                      className="text-button"
                      onClick={() => {
                        setEditing(c.id);
                        setValue(budget ? decimal(budget) : "");
                      }}
                    >
                      {budget ? "Изменить" : "Задать лимит"}
                    </button>
                  </div>
                </section>
              );
            })}
        </div>
      )}
      {editing && (
        <Modal
          title={`Лимит: ${categories.find((c) => c.id === editing)?.name}`}
          description="Сумма в MDL для выбранного месяца."
          onClose={() => setEditing(null)}
        >
          <Form onSubmit={() => save.mutate(undefined)}>
            <Field label="Лимит, MDL">
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
                  Удалить лимит
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
  const { toast } = useApp();
  const query = useQuery({
    queryKey: ["debts"],
    queryFn: () => api<Debt[]>("/debts"),
  });
  const [creating, setCreating] = useState(false);
  const [repaying, setRepaying] = useState<Debt | null>(null);
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
        eyebrow="ДЕНЬГИ И ЛЮДИ"
        title="Долги без неловкости"
        text="Помните, кому дали и у кого заняли. Частичные возвраты учитываются автоматически."
        actions={
          <button className="button primary" onClick={() => setCreating(true)}>
            <Plus size={18} />
            Записать долг
          </button>
        }
      />
      <div className="two-cards">
        <section className="panel balance-summary">
          <span className="round-icon mint">
            <ArrowDownLeft size={22} />
          </span>
          <div>
            <span>Вам должны</span>
            <strong>{totals("lent")}</strong>
          </div>
        </section>
        <section className="panel balance-summary">
          <span className="round-icon peach">
            <ArrowUpRight size={22} />
          </span>
          <div>
            <span>Вы должны</span>
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
              Все
            </button>
            <button
              className={direction === "lent" ? "selected" : ""}
              onClick={() => setDirection("lent")}
            >
              Мне должны
            </button>
            <button
              className={direction === "borrowed" ? "selected" : ""}
              onClick={() => setDirection("borrowed")}
            >
              Я должен
            </button>
          </div>
          <label className="check-field">
            <input
              type="checkbox"
              checked={closed}
              onChange={(e) => setClosed(e.target.checked)}
            />
            Показывать закрытые
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
                    {d.direction === "lent" ? "Вам должны" : "Вы должны"}
                    {d.due_date ? ` · до ${dateLabel(d.due_date)}` : ""}
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
                      ? "Закрыт"
                      : d.due_date && d.due_date < today()
                        ? "Срок прошёл"
                        : "Открыт"}
                  </Badge>
                </div>
                {d.remaining_minor > 0 && (
                  <button
                    className="button secondary"
                    onClick={() => setRepaying(d)}
                  >
                    Возврат
                  </button>
                )}
              </div>
            ))}
          </div>
        ) : (
          <Empty
            icon={<Users size={28} />}
            title="Всё спокойно"
            text="Здесь будут ваши долги и возвраты. Выдача и получение долга не считаются расходом и доходом."
            action={
              <button className="text-button" onClick={() => setCreating(true)}>
                <Plus size={17} />
                Добавить запись
              </button>
            }
          />
        )}
      </section>
      {creating && (
        <DebtForm
          onClose={() => setCreating(false)}
          onDone={() => {
            setCreating(false);
            toast("Долг записан");
          }}
        />
      )}{" "}
      {repaying && (
        <RepaymentForm
          debt={repaying}
          onClose={() => setRepaying(null)}
          onDone={() => {
            setRepaying(null);
            toast("Возврат учтён");
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
    <Modal title="Новый долг" onClose={onClose}>
      <Form onSubmit={() => save.mutate(undefined)}>
        <div className="segmented">
          <button
            type="button"
            className={direction === "lent" ? "selected" : ""}
            onClick={() => setDirection("lent")}
          >
            Я дал в долг
          </button>
          <button
            type="button"
            className={direction === "borrowed" ? "selected" : ""}
            onClick={() => setDirection("borrowed")}
          >
            Я взял в долг
          </button>
        </div>
        <div className="form-grid">
          <Field label="Имя человека" wide>
            <input
              required
              maxLength={100}
              value={person}
              onChange={(e) => setPerson(e.target.value)}
              placeholder="Например, Андрей"
              autoFocus
            />
          </Field>
          <Field label="Сумма">
            <MoneyInput value={value} onChange={setValue} />
          </Field>
          <Field label="Валюта">
            <CurrencySelect value={currency} onChange={setCurrency} />
          </Field>
          <Field label="Как учитывать" wide>
            <select value={mode} onChange={(e) => setMode(e.target.value)}>
              <option value="new">
                Деньги передаются сейчас — изменить остаток счёта
              </option>
              <option value="existing">
                Старый долг — остаток счёта уже учитывает его
              </option>
            </select>
          </Field>
          {mode === "new" && (
            <Field label="Счёт">
              <AccountSelect
                accounts={accounts}
                value={account}
                onChange={setAccount}
                currency={currency}
              />
            </Field>
          )}
          <Field label="Дата передачи">
            <input
              type="date"
              required
              max={today()}
              min="1990-01-01"
              value={date}
              onChange={(e) => setDate(e.target.value)}
            />
          </Field>
          <Field label="Вернуть до · необязательно">
            <input
              type="date"
              value={due}
              onChange={(e) => setDue(e.target.value)}
              min="1990-01-01"
              max="2100-12-31"
            />
          </Field>
          {currency !== "MDL" && mode === "new" && (
            <Field label="Курс к MDL">
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
          <Field label="Примечание" wide>
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

function RepaymentForm({
  debt,
  onClose,
  onDone,
}: {
  debt: Debt;
  onClose: () => void;
  onDone: () => void;
}) {
  const { accounts } = useApp();
  const [value, setValue] = useState(decimal(debt.remaining_minor));
  const [account, setAccount] = useState("");
  const [date, setDate] = useState(today());
  const [rate, setRate] = useState("");
  const [requestKey] = useState(key);
  const action = useAction(
    () =>
      send(`/debts/${debt.id}/repay`, {
        amount: value,
        account_id: account,
        occurred_on: date,
        fx_rate: debt.currency === "MDL" ? null : rate,
        idempotency_key: requestKey,
      }),
    onDone,
  );
  return (
    <Modal
      title={`Возврат: ${debt.person}`}
      description={`Осталось ${amount(debt.remaining_minor, debt.currency)}. Можно вернуть часть суммы.`}
      onClose={onClose}
    >
      <Form onSubmit={() => action.mutate(undefined)}>
        <div className="form-grid">
          <Field label={`Сумма · ${debt.currency}`}>
            <MoneyInput value={value} onChange={setValue} autoFocus />
          </Field>
          <Field label="Дата">
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
              debt.direction === "lent"
                ? "Получено на счёт"
                : "Списать со счёта"
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
            <Field label="Курс к MDL">
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
        <ErrorBox error={action.error} />
        <footer className="modal-footer">
          <Submit pending={action.isPending}>Записать возврат</Submit>
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
    () => toast("План обновлён"),
  );
  const skip = useAction(
    (id: string) => send(`/occurrences/${id}/skip`),
    () => toast("Платёж обновлён"),
  );
  return (
    <>
      <PageHeading
        eyebrow="КАЛЕНДАРЬ ОБЯЗАТЕЛЬСТВ"
        title="Платежи без сюрпризов"
        text="Аренда, связь, подписки и регулярные расходы. План превращается в расход только после оплаты."
        actions={
          <button className="button primary" onClick={() => setCreating(true)}>
            <Plus size={18} />
            Добавить платёж
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
              Этот месяц
            </button>
            <button
              className={tab === "templates" ? "selected" : ""}
              onClick={() => setTab("templates")}
            >
              Все регулярные
            </button>
          </div>
          <span className="muted">
            {query.data?.filter((b) => b.status === "paid").length ?? 0}{" "}
            оплачено в этом месяце
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
                        Оплачен
                      </button>
                      <button
                        className="icon-button"
                        disabled={skip.isPending}
                        title={
                          b.status === "skipped"
                            ? "Вернуть в план"
                            : "Пропустить этот платёж"
                        }
                        aria-label={
                          b.status === "skipped"
                            ? "Вернуть в план"
                            : "Пропустить платёж"
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
              title="Месяц без запланированных платежей"
              text="Добавьте регулярные обязательства — и свободный остаток станет понятнее."
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
                    {recurrenceLabels[b.recurrence]} · с{" "}
                    {dateLabel(b.start_date)}
                  </p>
                </div>
                <strong>{amount(b.amount_minor, b.currency)}</strong>
                <Badge status={b.active ? "posted" : "skipped"}>
                  {b.active ? "Активен" : "Остановлен"}
                </Badge>
                <button
                  className="button secondary"
                  disabled={toggle.isPending}
                  onClick={() => toggle.mutate(b.id)}
                >
                  {b.active ? "Остановить" : "Возобновить"}
                </button>
              </div>
            ))}
          </div>
        ) : (
          <Empty
            title="Регулярных платежей пока нет"
            text="Создайте первый шаблон, и платежи будут появляться каждый месяц."
          />
        )}
      </section>
      {creating && (
        <BillForm
          onClose={() => setCreating(false)}
          onDone={() => {
            setCreating(false);
            toast("Платёж добавлен");
          }}
        />
      )}{" "}
      {paying && (
        <BillPay
          bill={paying}
          onClose={() => setPaying(null)}
          onDone={() => {
            setPaying(null);
            toast("Оплата записана");
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
    <Modal title="Запланировать платёж" onClose={onClose}>
      <Form onSubmit={() => save.mutate(undefined)}>
        <div className="form-grid">
          <Field label="Название" wide>
            <input
              required
              autoFocus
              value={name}
              maxLength={100}
              onChange={(e) => setName(e.target.value)}
              placeholder="Например, интернет"
            />
          </Field>
          <Field label="Сумма">
            <MoneyInput value={value} onChange={setValue} />
          </Field>
          <Field label="Валюта">
            <CurrencySelect value={currency} onChange={setCurrency} />
          </Field>
          <Field label="Первая дата оплаты">
            <input
              type="date"
              min="1990-01-01"
              max="2100-12-31"
              required
              value={date}
              onChange={(e) => setDate(e.target.value)}
            />
          </Field>
          <Field label="Повторять">
            <select value={repeat} onChange={(e) => setRepeat(e.target.value)}>
              {Object.entries(recurrenceLabels).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Категория">
            <CategorySelect
              categories={categories}
              value={category}
              onChange={setCategory}
            />
          </Field>
          <Field label="Счёт по умолчанию">
            <AccountSelect
              accounts={accounts}
              value={account}
              onChange={setAccount}
              currency={currency}
              optional
            />
          </Field>
          {currency !== "MDL" && (
            <Field label="Плановый курс к MDL">
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
      title={`Оплата: ${bill.name}`}
      description="Укажите фактическую сумму — она может отличаться от плановой."
      onClose={onClose}
    >
      <Form onSubmit={() => save.mutate(undefined)}>
        <div className="form-grid">
          <Field label={`Сумма · ${bill.currency}`}>
            <MoneyInput value={value} onChange={setValue} autoFocus />
          </Field>
          <Field label="Дата оплаты">
            <input
              required
              type="date"
              max={today()}
              min="1990-01-01"
              value={date}
              onChange={(e) => setDate(e.target.value)}
            />
          </Field>
          <Field label="Счёт" wide>
            <AccountSelect
              accounts={accounts}
              value={account}
              onChange={setAccount}
              currency={bill.currency}
            />
          </Field>
          {bill.currency !== "MDL" && (
            <Field label="Курс на дату оплаты">
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
        <Field label="Учёт оплаты">
          <select
            value={
              candidates.some((item) => item.id === transaction)
                ? transaction
                : ""
            }
            onChange={(e) => setTransaction(e.target.value)}
          >
            <option value="">Создать новый расход</option>
            {candidates.map((item) => (
              <option key={item.id} value={item.id}>
                Уже записано: {item.merchant || "Расход"} ·{" "}
                {amount(item.amount_minor, item.currency)}
              </option>
            ))}
          </select>
          <small>
            Здесь появятся расходы с той же датой, суммой и счётом. Привязка не
            списывает деньги повторно.
          </small>
        </Field>
        <ErrorBox error={save.error ?? matches.error} />
        <footer className="modal-footer">
          <Submit pending={save.isPending}>Записать оплату</Submit>
        </footer>
      </Form>
    </Modal>
  );
}
