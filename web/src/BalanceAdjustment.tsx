import { useRef, useState } from "react";
import { ApiError, api, queryClient, send, useAction } from "./api";
import { useApp } from "./context";
import { t } from "./i18n";
import type { Account, Preferences } from "./types";
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
import {
  combinedBalanceTarget,
  correctionTarget,
  minorDecimal,
  type BalanceMode,
  type BalanceTarget,
} from "./wallet";

export function BalanceAdjustment({
  target: initial,
  onClose,
}: {
  target: BalanceTarget;
  onClose: () => void;
}) {
  const { toast } = useApp();
  const [current, setCurrent] = useState(initial);
  const account = current.account;
  const combined = current.accounting_version !== undefined;
  const [mode, setMode] = useState<BalanceMode>("set");
  const [input, setInput] = useState("");
  const [effect, setEffect] = useState(false);
  const [date, setDate] = useState(today);
  const [rate, setRate] = useState("");
  const [note, setNote] = useState("");
  const request = useRef({ signature: "", key: "" });
  const target = correctionTarget(current.balance_minor, mode, input);
  const difference = target === null ? null : target - current.balance_minor;
  const action = useAction(
    async () => {
      if (target === null || !difference)
        throw new Error(t("Укажите остаток, отличающийся от текущего."));
      const payload = {
        target_balance: minorDecimal(target),
        expected_balance_minor: current.balance_minor,
        effect: effect ? "income_expense" : "adjustment",
        occurred_on: date,
        fx_rate: account.currency === "MDL" ? null : rate,
        note,
        ...(combined
          ? { expected_accounting_version: current.accounting_version }
          : {}),
      };
      const signature = JSON.stringify(payload);
      if (request.current.signature !== signature)
        request.current = { signature, key: crypto.randomUUID() };
      await send(
        combined
          ? "/wallet/balance-adjustment"
          : `/accounts/${account.id}/balance-adjustment`,
        {
          ...payload,
          idempotency_key: request.current.key,
        },
      );
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
    if (combined) {
      const prefs = await api<Preferences>("/settings");
      const wallet = combinedBalanceTarget(accounts, prefs);
      if (!wallet)
        throw new Error(
          t(
            "Режим учёта изменился. Закройте окно и откройте корректировку заново.",
          ),
        );
      setCurrent(wallet);
    } else {
      setCurrent({ account: updated, balance_minor: updated.balance_minor });
    }
    request.current = { signature: "", key: "" };
    action.reset();
    queryClient.setQueryData(["accounts"], accounts);
  });
  const pending = action.isPending || refresh.isPending;
  return (
    <Modal
      title={t("Указать текущий остаток")}
      description={combined ? t("Общий кошелёк · MDL") : account.name}
      onClose={() => {
        if (!pending) onClose();
      }}
    >
      <Form onSubmit={() => action.mutate(undefined)}>
        <div className="balance-current">
          <span>{t("По данным Finora")}</span>
          <strong>{amount(current.balance_minor, account.currency)}</strong>
        </div>
        <fieldset className="balance-fields" disabled={pending}>
          <Field
            label={
              mode === "set"
                ? t("Сколько денег сейчас · {0}", account.currency)
                : t("Сумма · {0}", account.currency)
            }
          >
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
          <p className="muted">
            {t(
              "Введите фактическую сумму. Дальнейшие доходы и расходы будут считаться от неё. История операций сохранится.",
            )}
          </p>
          {combined && (
            <p className="muted">
              {t(
                "Укажите общую сумму в MDL: наличные и карты вместе. Разница будет записана на основной счёт «{0}».",
                account.name,
              )}
            </p>
          )}
          {account.currency !== "MDL" && (
            <Field label={t("Курс 1 {0} в MDL", account.currency)}>
              <input
                type="number"
                required
                min="0.000001"
                max="100000"
                step="0.000001"
                value={rate}
                onChange={(event) => setRate(event.target.value)}
              />
            </Field>
          )}
          <details className="balance-options">
            <summary>{t("Дополнительные параметры")}</summary>
            <div
              className="balance-modes"
              role="group"
              aria-label={t("Как изменить остаток")}
            >
              {(["set", "add", "subtract"] as const).map((value) => (
                <button
                  type="button"
                  key={value}
                  disabled={pending}
                  className={`button ${mode === value ? "primary" : "secondary"}`}
                  aria-pressed={mode === value}
                  onClick={() => {
                    setMode(value);
                    setInput(
                      value === "set"
                        ? minorDecimal(current.balance_minor)
                        : "",
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
            <Field label={t("Примечание · необязательно")}>
              <input
                maxLength={1000}
                value={note}
                onChange={(event) => setNote(event.target.value)}
              />
            </Field>
          </details>
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
            {t("Сохранить текущий остаток")}
          </Submit>
        </div>
      </Form>
    </Modal>
  );
}
