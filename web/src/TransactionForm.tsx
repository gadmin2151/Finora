import { t } from "./i18n";
import { useState } from "react";
import { Plus, Trash2 } from "lucide-react";
import { send, useAction } from "./api";
import { useApp } from "./context";
import type { Transaction } from "./types";
import {
  AccountSelect,
  CategorySelect,
  ErrorBox,
  Field,
  Form,
  Modal,
  MoneyInput,
  Submit,
  decimal,
  key,
  kinds,
  today,
} from "./ui";

export default function TransactionForm({
  transaction: tx,
  kind: initialKind,
  refund,
  incomeOnly = false,
  onClose,
}: {
  transaction?: Transaction;
  kind?: string;
  refund?: Transaction;
  incomeOnly?: boolean;
  onClose: () => void;
}) {
  const { accounts, categories, prefs, toast } = useApp();
  const [kind, setKind] = useState(
    refund ? "refund" : (tx?.kind ?? initialKind ?? "expense"),
  );
  const [value, setValue] = useState(
    tx ? decimal(tx.amount_minor) : refund ? decimal(refund.amount_minor) : "",
  );
  const [account, setAccount] = useState(
    tx?.account_id ??
      refund?.account_id ??
      prefs?.default_account_id ??
      accounts.find((a) => !a.archived)?.id ??
      "",
  );
  const [date, setDate] = useState(tx?.occurred_on ?? today());
  const [category, setCategory] = useState(
    tx?.category_id ?? refund?.category_id ?? "",
  );
  const [merchant, setMerchant] = useState(
    tx?.merchant ?? refund?.merchant ?? "",
  );
  const [note, setNote] = useState(tx?.note ?? "");
  const [target, setTarget] = useState(tx?.target_account_id ?? "");
  const [targetValue, setTargetValue] = useState(
    tx?.target_minor ? decimal(tx.target_minor) : "",
  );
  const [rate, setRate] = useState(tx?.fx_rate ?? "");
  const [split, setSplit] = useState((tx?.splits.length ?? 0) > 1);
  const [splits, setSplits] = useState(
    tx?.splits.map((s) => ({
      category_id: s.category_id ?? "",
      amount: decimal(s.amount_minor),
    })) ?? [
      { category_id: "", amount: "" },
      { category_id: "", amount: "" },
    ],
  );
  const [requestKey] = useState(key);
  const currency = accounts.find((a) => a.id === account)?.currency ?? "MDL";
  const targetCurrency = accounts.find((a) => a.id === target)?.currency;
  const action = useAction(
    () =>
      send(
        tx ? `/transactions/${tx.id}` : "/transactions",
        {
          kind,
          amount: value,
          account_id: account,
          occurred_on: date,
          fx_rate: currency === "MDL" ? null : rate,
          category_id:
            ["expense", "income", "refund"].includes(kind) && !split
              ? category || null
              : null,
          merchant,
          note,
          target_account_id: kind === "transfer" ? target : null,
          target_amount:
            kind === "transfer" && targetCurrency !== currency
              ? targetValue
              : null,
          refund_of: refund?.id ?? null,
          splits:
            split && ["expense", "refund"].includes(kind)
              ? splits.map((s) => ({
                  ...s,
                  category_id: s.category_id || null,
                }))
              : [],
          idempotency_key: requestKey,
          ...(tx ? { version: tx.version } : {}),
        },
        tx ? "PUT" : "POST",
      ),
    () => {
      toast(tx ? t("Операция обновлена") : t("Операция добавлена"));
      onClose();
    },
  );
  return (
    <Modal
      title={
        tx
          ? t("Изменить операцию")
          : refund
            ? t("Возврат покупки")
            : incomeOnly
              ? t("Разовый доход")
              : t("Новая операция")
      }
      description={t("Изменения сразу появятся в остатках и отчётах.")}
      onClose={onClose}
    >
      <Form onSubmit={() => action.mutate(undefined)}>
        {!refund && !incomeOnly && (
          <div
            className="segmented"
            role="group"
            aria-label={t("Тип операции")}
          >
            {["expense", "income", "transfer"].map((k) => (
              <button
                type="button"
                className={kind === k ? "selected" : ""}
                key={k}
                onClick={() => setKind(k)}
              >
                {kinds[k]}
              </button>
            ))}
          </div>
        )}
        <div className="form-grid">
          <Field label={t("Сумма · {0}", currency)}>
            <MoneyInput value={value} onChange={setValue} autoFocus />
          </Field>
          <Field label={t("Дата")}>
            <input
              type="date"
              required
              min="1990-01-01"
              max={today()}
              value={date}
              onChange={(e) => setDate(e.target.value)}
            />
          </Field>
          <Field label={kind === "transfer" ? t("Со счёта") : t("Счёт")}>
            <AccountSelect
              accounts={accounts}
              value={account}
              onChange={setAccount}
            />
          </Field>
          {kind === "transfer" ? (
            <Field label={t("На счёт")}>
              <AccountSelect
                accounts={accounts.filter((a) => a.id !== account)}
                value={target}
                onChange={setTarget}
              />
            </Field>
          ) : (
            <Field label={t("Категория")}>
              <CategorySelect
                categories={categories}
                value={category}
                onChange={setCategory}
              />
            </Field>
          )}
          {kind === "transfer" &&
            targetCurrency &&
            targetCurrency !== currency && (
              <Field label={t("Получено · {0}", targetCurrency)}>
                <MoneyInput value={targetValue} onChange={setTargetValue} />
              </Field>
            )}
          {currency !== "MDL" && (
            <Field
              label={t("Курс: 1 {0} в MDL", currency)}
              hint={t("Исторический курс на дату операции")}
            >
              <input
                type="number"
                step="0.00000001"
                min="0.00000001"
                required
                value={rate}
                onChange={(e) => setRate(e.target.value)}
              />
            </Field>
          )}
          <Field
            label={
              kind === "income"
                ? t("Источник дохода")
                : t("Магазин или получатель")
            }
            wide
          >
            <input
              value={merchant}
              maxLength={200}
              onChange={(e) => setMerchant(e.target.value)}
              placeholder={
                kind === "income"
                  ? t("Например, зарплата")
                  : t("Например, Linella")
              }
            />
          </Field>
          <Field label={t("Примечание")} wide>
            <textarea
              rows={2}
              maxLength={3000}
              value={note}
              onChange={(e) => setNote(e.target.value)}
              placeholder={t("Необязательно")}
            />
          </Field>
        </div>
        {["expense", "refund"].includes(kind) && (
          <>
            <label className="check-field">
              <input
                type="checkbox"
                checked={split}
                onChange={(e) => setSplit(e.target.checked)}
              />
              {t("Разделить сумму по категориям")}
            </label>
            {split && (
              <div className="split-list">
                {splits.map((s, i) => (
                  <div className="split-row" key={i}>
                    <CategorySelect
                      categories={categories}
                      value={s.category_id}
                      onChange={(value) =>
                        setSplits(
                          splits.map((r, n) =>
                            n === i ? { ...r, category_id: value } : r,
                          ),
                        )
                      }
                    />
                    <MoneyInput
                      value={s.amount}
                      onChange={(value) =>
                        setSplits(
                          splits.map((r, n) =>
                            n === i ? { ...r, amount: value } : r,
                          ),
                        )
                      }
                    />
                    <button
                      type="button"
                      className="icon-button"
                      aria-label={t("Удалить часть")}
                      disabled={splits.length < 2}
                      onClick={() =>
                        setSplits(splits.filter((_, n) => n !== i))
                      }
                    >
                      <Trash2 size={17} />
                    </button>
                  </div>
                ))}
                <button
                  type="button"
                  className="text-button"
                  onClick={() =>
                    setSplits([...splits, { category_id: "", amount: "" }])
                  }
                >
                  <Plus size={16} />
                  {t("Добавить категорию")}
                </button>
                <small>
                  {t("Сумма частей должна совпадать с итогом операции.")}
                </small>
              </div>
            )}
          </>
        )}
        <ErrorBox error={action.error} />
        <footer className="modal-footer">
          <button type="button" className="button secondary" onClick={onClose}>
            {t("Отмена")}
          </button>
          <Submit pending={action.isPending} />
        </footer>
      </Form>
    </Modal>
  );
}
