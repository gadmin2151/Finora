import { t } from "../i18n";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Plus, Pencil, CirclePause, Play, Check, Wallet } from "lucide-react";
import { api, send, useAction } from "../api";
import { useApp } from "../context";
import type { Bill, Currency, Transaction } from "../types";
import {
  AccountSelect,
  Badge,
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
import TransactionForm from "../TransactionForm";

type Plan = {
  id: string;
  name: string;
  amount_minor: number;
  currency: Currency;
  account_id: string | null;
  recurrence: string;
  start_date: string;
  fx_rate: string;
  active: boolean;
  version: number;
};
type Occurrence = Omit<Bill, "status"> & {
  status: string;
  received_minor?: number;
  received_on?: string;
};
type Report = {
  received_minor: number;
  regular_minor: number;
  occasional_minor: number;
  expected_minor: number;
  occurrences: Occurrence[];
};

export default function Income() {
  const { month, isAdmin } = useApp();
  const [editing, setEditing] = useState<Plan | "new" | null>(null);
  const [receiving, setReceiving] = useState<Occurrence | null>(null);
  const [occasional, setOccasional] = useState(false);
  const [offset, setOffset] = useState(0);
  const report = useQuery({
    queryKey: ["income", month],
    queryFn: () => api<Report>(`/income?month=${month}`),
  });
  const plans = useQuery({
    queryKey: ["income-plans"],
    queryFn: () => api<Plan[]>("/income/templates"),
  });
  const history = useQuery({
    queryKey: ["income-history", month, offset],
    queryFn: () =>
      api<{ items: Transaction[]; total: number }>(
        `/transactions?month=${month}&kind=income&offset=${offset}&limit=30`,
      ),
  });
  const toggle = useAction((plan: Plan) =>
    send(
      `/income/templates/${plan.id}/active`,
      { active: !plan.active, version: plan.version },
      "PUT",
    ),
  );
  const skip = useAction((row: Occurrence) =>
    send(`/income/occurrences/${row.id}/skip`),
  );
  return (
    <>
      <PageHeading
        eyebrow={t("ДЕНЬГИ, КОТОРЫЕ ПРИХОДЯТ")}
        title={t("Доходы")}
        text={t(
          "Регулярные поступления и разовые заработки. План становится деньгами только после подтверждения.",
        )}
        actions={
          isAdmin && (
            <div className="button-row">
              <button
                className="button secondary"
                onClick={() => setOccasional(true)}
              >
                <Plus size={17} />
                {t("Разовый доход")}
              </button>
              <button
                className="button primary"
                onClick={() => setEditing("new")}
              >
                <Plus size={17} />
                {t("Источник дохода")}
              </button>
            </div>
          )
        }
      />
      <ErrorBox
        error={
          report.error ??
          plans.error ??
          history.error ??
          toggle.error ??
          skip.error
        }
      />
      <div className="stats-grid income-stats">
        {[
          [t("Получено за месяц"), report.data?.received_minor],
          [t("Регулярные поступления"), report.data?.regular_minor],
          [t("Разовые поступления"), report.data?.occasional_minor],
          [t("Ожидается по плану"), report.data?.expected_minor],
        ].map(([label, value]) => (
          <div className="stat-card" key={String(label)}>
            <span className="stat-label">{label}</span>
            <strong>{amount(typeof value === "number" ? value : 0)}</strong>
            <small>{t("В пересчёте на MDL")}</small>
          </div>
        ))}
      </div>
      <section className="panel">
        <div className="panel-heading">
          <div>
            <h2>{t("Календарь поступлений")}</h2>
            <p>
              {t("Для выбранного месяца ·")} {month}
            </p>
          </div>
          <Wallet size={22} />
        </div>
        {report.isPending ? (
          <Loading />
        ) : report.data?.occurrences.length ? (
          <div className="bill-list">
            {report.data.occurrences.map((row) => (
              <div className="bill-row" key={row.id}>
                <div className="date-tile">
                  {row.due_date.slice(8)}
                  <small>
                    {dateLabel(row.due_date).split(" ").slice(1).join(" ")}
                  </small>
                </div>
                <div className="grow">
                  <strong>{row.name}</strong>
                  <small>{recurrenceLabels[row.recurrence]}</small>
                  {row.received_on && (
                    <small>
                      {t("Получено")} {dateLabel(row.received_on)} {t("· план")}{" "}
                      {amount(row.amount_minor, row.currency)}
                    </small>
                  )}
                </div>
                <strong className="money">
                  {amount(row.received_minor ?? row.amount_minor, row.currency)}
                </strong>
                <Badge status={row.status} />
                {isAdmin && !["received", "paused"].includes(row.status) && (
                  <div className="button-row">
                    {row.status !== "skipped" && (
                      <button
                        className="button secondary small"
                        onClick={() => setReceiving(row)}
                      >
                        <Check size={15} />
                        {t("Получено")}
                      </button>
                    )}
                    <button
                      className="text-button"
                      disabled={skip.isPending}
                      onClick={() => skip.mutate(row)}
                    >
                      {row.status === "skipped"
                        ? t("Вернуть в план")
                        : t("Пропустить")}
                    </button>
                  </div>
                )}
              </div>
            ))}
          </div>
        ) : (
          <Empty
            title={t("Нет запланированных поступлений")}
            text={t(
              "Добавьте зарплату, аренду или другой регулярный источник. Разовые доходы можно вносить сразу.",
            )}
          />
        )}
      </section>
      <section className="panel">
        <div className="panel-heading">
          <div>
            <h2>{t("Источники дохода")}</h2>
            <p>{t("Сумма, периодичность и счёт зачисления")}</p>
          </div>
        </div>
        {plans.isPending ? (
          <Loading />
        ) : !plans.data?.length ? (
          <Empty
            title={t("Добавьте первый источник")}
            text={t(
              "Например, зарплату с поступлением 10-го числа каждого месяца.",
            )}
          />
        ) : (
          <div className="bill-list">
            {plans.data.map((plan) => (
              <div className="bill-row" key={plan.id}>
                <div className="grow">
                  <strong>{plan.name}</strong>
                  <small>
                    {recurrenceLabels[plan.recurrence]} {t("· с")}{" "}
                    {plan.start_date}
                  </small>
                </div>
                <strong>{amount(plan.amount_minor, plan.currency)}</strong>
                <Badge>{plan.active ? t("Активен") : t("Пауза")}</Badge>
                {isAdmin && (
                  <>
                    <button
                      className="icon-button"
                      aria-label={t("Изменить {0}", plan.name)}
                      onClick={() => setEditing(plan)}
                    >
                      <Pencil size={17} />
                    </button>
                    <button
                      className="icon-button"
                      disabled={toggle.isPending}
                      aria-label={`${plan.active ? t("Приостановить") : t("Возобновить")} ${plan.name}`}
                      onClick={() => toggle.mutate(plan)}
                    >
                      {plan.active ? (
                        <CirclePause size={18} />
                      ) : (
                        <Play size={18} />
                      )}
                    </button>
                  </>
                )}
              </div>
            ))}
          </div>
        )}
      </section>
      <section className="panel">
        <div className="panel-heading">
          <div>
            <h2>{t("Полученные доходы")}</h2>
            <p>{t("Только фактические зачисления")}</p>
          </div>
        </div>
        {history.isPending ? (
          <Loading />
        ) : history.data?.items.length ? (
          <>
            <div className="bill-list">
              {history.data.items.map((tx) => (
                <div className="bill-row" key={tx.id}>
                  <span className="muted">{dateLabel(tx.occurred_on)}</span>
                  <div className="grow">
                    <strong>{tx.merchant || t("Доход")}</strong>
                    <small>
                      {tx.note ||
                        (tx.occurrence_id
                          ? t("По плану")
                          : t("Разовое поступление"))}
                    </small>
                  </div>
                  <strong className="positive">
                    +{amount(tx.amount_minor, tx.currency)}
                  </strong>
                </div>
              ))}
            </div>
            <div className="pagination">
              <button
                disabled={!offset}
                onClick={() => setOffset(Math.max(0, offset - 30))}
              >
                {t("Назад")}
              </button>
              <span>
                {offset + 1}–{offset + history.data.items.length} {t("из")}{" "}
                {history.data.total}
              </span>
              <button
                disabled={offset + 30 >= history.data.total}
                onClick={() => setOffset(offset + 30)}
              >
                {t("Далее")}
              </button>
            </div>
          </>
        ) : (
          <Empty
            title={t("В этом месяце доходов пока нет")}
            text={t(
              "Отметьте запланированное поступление как полученное или добавьте разовый доход.",
            )}
          />
        )}
      </section>
      {editing && (
        <PlanForm
          plan={editing === "new" ? undefined : editing}
          onClose={() => setEditing(null)}
        />
      )}
      {receiving && (
        <ReceiveForm row={receiving} onClose={() => setReceiving(null)} />
      )}
      {occasional && (
        <TransactionForm
          kind="income"
          incomeOnly
          onClose={() => setOccasional(false)}
        />
      )}
    </>
  );
}

function PlanForm({ plan, onClose }: { plan?: Plan; onClose: () => void }) {
  const { accounts, toast } = useApp();
  const [name, setName] = useState(plan?.name ?? "");
  const [value, setValue] = useState(plan ? decimal(plan.amount_minor) : "");
  const [account, setAccount] = useState(
    plan?.account_id ?? accounts.find((a) => !a.archived)?.id ?? "",
  );
  const [date, setDate] = useState(plan?.start_date ?? today());
  const [recurrence, setRecurrence] = useState(plan?.recurrence ?? "monthly");
  const [rate, setRate] = useState(plan?.fx_rate ?? "");
  const [requestKey] = useState(key);
  const currency = accounts.find((a) => a.id === account)?.currency ?? "MDL";
  const action = useAction(
    () =>
      send(
        plan ? `/income/templates/${plan.id}` : "/income/templates",
        {
          name,
          amount: value,
          currency,
          account_id: account,
          start_date: date,
          recurrence,
          fx_rate: currency === "MDL" ? null : rate,
          idempotency_key: requestKey,
          ...(plan ? { version: plan.version } : {}),
        },
        plan ? "PUT" : "POST",
      ),
    () => {
      toast(t("Источник дохода сохранён"));
      onClose();
    },
  );
  return (
    <Modal
      title={plan ? t("Изменить источник дохода") : t("Новый источник дохода")}
      description={t("Запланированные суммы не увеличивают остаток на счёте.")}
      onClose={onClose}
    >
      <Form onSubmit={() => action.mutate(undefined)}>
        <Field label={t("Название источника")}>
          <input
            autoFocus
            required
            maxLength={100}
            placeholder={t("Например, зарплата")}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </Field>
        <div className="form-grid">
          <Field label={t("Ожидаемая сумма")}>
            <MoneyInput value={value} onChange={setValue} />
          </Field>
          <Field label={t("Счёт зачисления")}>
            <AccountSelect
              accounts={accounts}
              value={account}
              onChange={setAccount}
            />
          </Field>
          <Field label={t("Первое поступление")}>
            <input
              type="date"
              required
              min="1990-01-01"
              max="2100-12-31"
              value={date}
              onChange={(e) => setDate(e.target.value)}
            />
          </Field>
          <Field label={t("Повторять")}>
            <select
              value={recurrence}
              onChange={(e) => setRecurrence(e.target.value)}
            >
              {Object.entries(recurrenceLabels).map(([v, label]) => (
                <option value={v} key={v}>
                  {label}
                </option>
              ))}
            </select>
          </Field>
          {currency !== "MDL" && (
            <Field label={t("Курс 1 {0} в MDL", currency)}>
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
        </div>
        <div className="notice">
          {t(
            "Для 29–31 числа в коротком месяце используется его последний день. Уже полученные суммы сохраняются при изменении плана.",
          )}
        </div>
        <ErrorBox error={action.error} />
        <footer className="modal-footer">
          <button type="button" className="button secondary" onClick={onClose}>
            {t("Отмена")}
          </button>
          <Submit pending={action.isPending}>{t("Сохранить источник")}</Submit>
        </footer>
      </Form>
    </Modal>
  );
}

function ReceiveForm({
  row,
  onClose,
}: {
  row: Occurrence;
  onClose: () => void;
}) {
  const { accounts, toast } = useApp();
  const [value, setValue] = useState(decimal(row.amount_minor));
  const [account, setAccount] = useState(row.account_id ?? "");
  const [date, setDate] = useState(today());
  const [rate, setRate] = useState(row.fx_rate);
  const [link, setLink] = useState("");
  const [requestKey] = useState(key);
  const matches = useQuery({
    queryKey: ["income-matches", account, date],
    queryFn: () =>
      api<{ items: Transaction[] }>(
        `/transactions?kind=income&account_id=${account}&month=${date.slice(0, 7)}&limit=200`,
      ),
    enabled: !!account && !!date,
  });
  const action = useAction(
    () =>
      send(`/income/occurrences/${row.id}/receive`, {
        account_id: account,
        amount: value,
        occurred_on: date,
        fx_rate: row.currency === "MDL" ? null : rate,
        transaction_id: link || null,
        idempotency_key: requestKey,
      }),
    () => {
      toast(t("Доход зачислен"));
      onClose();
    },
  );
  return (
    <Modal
      title={t("Получено: {0}", row.name)}
      description={t("Укажите фактическую сумму и дату поступления.")}
      onClose={onClose}
    >
      <Form onSubmit={() => action.mutate(undefined)}>
        <div className="form-grid">
          <Field label={t("Полученная сумма")}>
            <MoneyInput value={value} onChange={setValue} />
          </Field>
          <Field label={t("Дата поступления")}>
            <input
              type="date"
              value={date}
              required
              max={today()}
              onChange={(e) => setDate(e.target.value)}
            />
          </Field>
          <Field label={t("Счёт")}>
            <AccountSelect
              accounts={accounts.filter((a) => a.currency === row.currency)}
              value={account}
              onChange={setAccount}
            />
          </Field>
          {row.currency !== "MDL" && (
            <Field label={t("Курс 1 {0} в MDL", row.currency)}>
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
        <Field
          label={t("Связать с уже внесённым доходом")}
          hint={t(
            "Привязка существующего дохода не увеличивает баланс повторно.",
          )}
        >
          <select value={link} onChange={(e) => setLink(e.target.value)}>
            <option value="">{t("Создать новое зачисление")}</option>
            {matches.data?.items
              .filter(
                (tx) =>
                  !tx.occurrence_id &&
                  tx.occurred_on === date &&
                  tx.amount_minor === Math.round(Number(value) * 100),
              )
              .map((tx) => (
                <option key={tx.id} value={tx.id}>
                  {tx.merchant || t("Доход")} ·{" "}
                  {amount(tx.amount_minor, tx.currency)}
                </option>
              ))}
          </select>
        </Field>
        <ErrorBox error={action.error ?? matches.error} />
        <footer className="modal-footer">
          <button type="button" className="button secondary" onClick={onClose}>
            {t("Отмена")}
          </button>
          <Submit pending={action.isPending}>
            {t("Подтвердить поступление")}
          </Submit>
        </footer>
      </Form>
    </Modal>
  );
}
