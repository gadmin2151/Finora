import { useRef, useState } from "react";
import { ArrowUpRight, SlidersHorizontal, Wallet } from "lucide-react";
import { ApiError, api, queryClient, send, useAction } from "./api";
import { useApp } from "./context";
import { t } from "./i18n";
import type { Account, Dashboard } from "./types";
import {
  ErrorBox,
  Field,
  Form,
  Modal,
  MoneyInput,
  Submit,
  amount,
  today,
} from "./ui";
import { correctionTarget, minorDecimal, type BalanceMode } from "./wallet";

export function WalletCard({ dashboard }: { dashboard: Dashboard }) {
  const { isAdmin, navigate } = useApp();
  const [selected, setSelected] = useState("");
  const [editing, setEditing] = useState<Account | null>(null);
  const wallet = dashboard.wallet ?? dashboard;
  const accounts = wallet.accounts;
  const account = accounts.find((value) => value.id === selected);
  const balances = account
    ? [[account.currency, account.balance_minor] as const]
    : Object.entries(wallet.balances);
  return (
    <section className="stat-card main-stat wallet-card">
      <div className="stat-label">
        <span className="balance-eyebrow">
          <i /> {t("Текущий кошелёк")}
        </span>
        <Wallet size={20} />
      </div>
      <label className="wallet-account">
        <span className="sr-only">{t("Выбрать счёт кошелька")}</span>
        <select
          value={account?.id ?? ""}
          onChange={(event) => setSelected(event.target.value)}
        >
          <option value="">{t("Все счета")}</option>
          {accounts.map((item) => (
            <option key={item.id} value={item.id}>
              {item.name} · {item.currency}
              {item.archived ? ` · ${t("Архив")}` : ""}
            </option>
          ))}
        </select>
      </label>
      <div className="wallet-balances" aria-live="polite">
        {balances.length ? (
          balances.map(([currency, value]) => (
            <strong key={currency}>{amount(value, currency)}</strong>
          ))
        ) : (
          <strong>—</strong>
        )}
      </div>
      <span className="stat-note">
        {t("Деньги на счетах сейчас, включая переводы и возвраты долгов.")}
      </span>
      {!account && balances.length > 1 && (
        <span className="stat-note">
          {t("Разные валюты показаны отдельно.")}
        </span>
      )}
      <div className="wallet-actions">
        {isAdmin && account && !account.archived && (
          <button
            className="button secondary"
            onClick={() => setEditing(account)}
          >
            <SlidersHorizontal size={16} />
            {t("Уточнить остаток")}
          </button>
        )}
        {isAdmin && !account && accounts.length > 0 && (
          <span className="stat-note">
            {t("Выберите счёт, чтобы уточнить его остаток.")}
          </span>
        )}
        <button
          className="balance-link"
          onClick={() => navigate(isAdmin ? "accounts" : "transactions")}
        >
          {isAdmin ? t("Мои счета") : t("Все операции")}{" "}
          <ArrowUpRight size={17} />
        </button>
      </div>
      <div className="wallet-month">
        <span>{t("Итог выбранного месяца")}</span>
        <strong>{amount(dashboard.net_minor)}</strong>
        <small>{t("Доходы минус расходы · не остаток кошелька")}</small>
      </div>
      <div className="balance-orbit" aria-hidden="true">
        <i />
        <i />
        <Wallet size={54} strokeWidth={1} />
      </div>
      {editing && (
        <BalanceAdjustment account={editing} onClose={() => setEditing(null)} />
      )}
    </section>
  );
}

function BalanceAdjustment({
  account: initial,
  onClose,
}: {
  account: Account;
  onClose: () => void;
}) {
  const { toast } = useApp();
  const [account, setAccount] = useState(initial);
  const [mode, setMode] = useState<BalanceMode>("set");
  const [input, setInput] = useState(minorDecimal(initial.balance_minor));
  const [effect, setEffect] = useState(false);
  const [date, setDate] = useState(today);
  const [rate, setRate] = useState("");
  const [note, setNote] = useState("");
  const request = useRef({ signature: "", key: "" });
  const target = correctionTarget(account.balance_minor, mode, input);
  const difference = target === null ? null : target - account.balance_minor;
  const action = useAction(
    async () => {
      if (target === null || !difference)
        throw new Error(t("Укажите остаток, отличающийся от текущего."));
      const payload = {
        target_balance: minorDecimal(target),
        expected_balance_minor: account.balance_minor,
        effect: effect ? "income_expense" : "adjustment",
        occurred_on: date,
        fx_rate: account.currency === "MDL" ? null : rate,
        note,
      };
      const signature = JSON.stringify(payload);
      if (request.current.signature !== signature)
        request.current = { signature, key: crypto.randomUUID() };
      await send(`/accounts/${account.id}/balance-adjustment`, {
        ...payload,
        idempotency_key: request.current.key,
      });
    },
    () => {
      toast(t("Остаток обновлён"));
      onClose();
    },
  );
  const refresh = useAction(async () => {
    const accounts = await api<Account[]>("/accounts");
    const updated = accounts.find(
      (item) => item.id === account.id && !item.archived,
    );
    if (!updated)
      throw new Error(
        t("Счёт больше недоступен. Закройте окно и выберите другой счёт."),
      );
    setAccount(updated);
    request.current = { signature: "", key: "" };
    action.reset();
    queryClient.setQueryData(["accounts"], accounts);
  });
  const pending = action.isPending || refresh.isPending;
  return (
    <Modal
      title={t("Уточнить остаток")}
      description={account.name}
      onClose={() => {
        if (!pending) onClose();
      }}
    >
      <Form onSubmit={() => action.mutate(undefined)}>
        <div className="balance-current">
          <span>{t("Сейчас на счёте")}</span>
          <strong>{amount(account.balance_minor, account.currency)}</strong>
        </div>
        <div
          className="balance-modes"
          role="group"
          aria-label={t("Как изменить остаток")}
        >
          {(["add", "subtract", "set"] as const).map((value) => (
            <button
              type="button"
              key={value}
              disabled={pending}
              className={`button ${mode === value ? "primary" : "secondary"}`}
              aria-pressed={mode === value}
              onClick={() => {
                setMode(value);
                setInput(
                  value === "set" ? minorDecimal(account.balance_minor) : "",
                );
                action.reset();
              }}
            >
              {value === "add"
                ? t("Прибавить")
                : value === "subtract"
                  ? t("Списать")
                  : t("Указать фактический")}
            </button>
          ))}
        </div>
        <fieldset className="balance-fields" disabled={pending}>
          <Field label={t("Сумма · {0}", account.currency)}>
            <MoneyInput
              value={input}
              onChange={setInput}
              min={mode === "set" ? "-999999999.99" : "0.01"}
              autoFocus
            />
          </Field>
          <div className="balance-preview" aria-live="polite">
            <span>{t("После изменения")}</span>
            <strong>
              {target === null ? "—" : amount(target, account.currency)}
            </strong>
            <small>
              {difference === null
                ? t("Введите сумму с точностью до двух знаков.")
                : t(
                    "Разница: {0}",
                    `${difference > 0 ? "+" : ""}${amount(difference, account.currency)}`,
                  )}
            </small>
          </div>
          <label className="balance-effect">
            <input
              type="checkbox"
              checked={effect}
              onChange={(event) => setEffect(event.target.checked)}
            />
            <span>{t("Учесть разницу как доход или расход")}</span>
          </label>
          <p className="muted">
            {effect
              ? t(
                  "Увеличение попадёт в доходы, уменьшение — в расходы выбранной даты.",
                )
              : t(
                  "По умолчанию это только сверка остатка. Доходы и расходы не изменятся.",
                )}
          </p>
          <Field label={t("Дата")}>
            <input
              type="date"
              required
              min="1990-01-01"
              max="2100-12-31"
              value={date}
              onChange={(event) => setDate(event.target.value)}
            />
          </Field>
          {account.currency !== "MDL" && (
            <Field label={t("Курс 1 {0} в MDL", account.currency)}>
              <input
                type="number"
                required
                min="0.000001"
                max="1000000"
                step="0.000001"
                value={rate}
                onChange={(event) => setRate(event.target.value)}
              />
            </Field>
          )}
          <Field label={t("Примечание · необязательно")}>
            <input
              maxLength={1000}
              value={note}
              onChange={(event) => setNote(event.target.value)}
            />
          </Field>
        </fieldset>
        <ErrorBox error={action.error ?? refresh.error} />
        {action.error instanceof ApiError && action.error.status === 409 && (
          <button
            type="button"
            className="button secondary"
            disabled={pending}
            onClick={() => refresh.mutate(undefined)}
          >
            {t("Обновить остаток")}
          </button>
        )}
        <div className="form-actions">
          <Submit pending={pending} disabled={target === null || !difference}>
            {t("Подтвердить изменение")}
          </Submit>
        </div>
      </Form>
    </Modal>
  );
}
