import { useState } from "react";
import { Check, Layers, Wallet } from "lucide-react";
import { send, useAction } from "./api";
import { useApp } from "./context";
import { t } from "./i18n";
import type { Preferences } from "./types";
import { ErrorBox, Field, Form, Submit } from "./ui";

export function AccountingSettings({ initial }: { initial: Preferences }) {
  const { accounts, organization, toast } = useApp();
  const [mode, setMode] = useState(initial.accounting_mode);
  const [account, setAccount] = useState(initial.default_account_id ?? "");
  const action = useAction(
    () =>
      send<{ receipts_moved: number }>(
        "/settings/accounting",
        {
          mode,
          default_account_id: account || null,
          version: initial.accounting_version,
          move_existing_receipts: mode === "combined",
        },
        "PUT",
      ),
    () => {
      toast(t("Настройки сохранены"));
    },
  );
  return (
    <section className="panel settings-panel">
      <h2>{t("Как учитывать деньги")}</h2>
      <p>
        {t(
          "Настройка общая для организации {0}, сайта и телефона.",
          organization.name,
        )}
      </p>
      <Form onSubmit={() => action.mutate(undefined)}>
        <fieldset disabled={action.isPending} className="accounting-fields">
          <div
            className="provider-grid accounting-modes"
            role="group"
            aria-label={t("Режим учёта")}
          >
            {(["separate", "combined"] as const).map((value) => (
              <button
                type="button"
                key={value}
                aria-pressed={mode === value}
                className={`provider-card ${mode === value ? "selected" : ""}`}
                onClick={() => setMode(value)}
              >
                {value === "combined" ? (
                  <Layers size={24} />
                ) : (
                  <Wallet size={24} />
                )}
                <strong>
                  {value === "combined"
                    ? t("Всё вместе")
                    : t("Наличные и карты отдельно")}
                </strong>
                {mode === value && (
                  <Check className="provider-check" size={18} />
                )}
                <small>
                  {value === "combined"
                    ? t(
                        "Один счёт MDL: объединяются остатки, чеки и вся история операций.",
                      )
                    : t(
                        "Выбирайте счёт при покупке, получении дохода и возврате долга.",
                      )}
                </small>
              </button>
            ))}
          </div>
          <Field
            label={
              mode === "combined"
                ? t("Основной счёт MDL")
                : t("Счёт по умолчанию")
            }
          >
            <select
              value={account}
              onChange={(e) => setAccount(e.target.value)}
              required={mode === "combined"}
            >
              <option value="">{t("Выберите счёт")}</option>
              {accounts
                .filter(
                  (a) =>
                    !a.archived &&
                    (mode !== "combined" || a.currency === "MDL"),
                )
                .map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.name} · {a.currency}
                  </option>
                ))}
            </select>
          </Field>
          <p className="muted">
            {t(
              "При объединении все счета MDL станут одним: остатки складываются, операции и планы переносятся на основной счёт. Суммы, категории и оригиналы чеков сохраняются. Другие валюты остаются отдельно.",
            )}
          </p>
          <p className="muted">
            {t(
              "Чтобы снова вести счета отдельно, включите раздельный режим и создайте новые счета. Объединённая история останется на основном счёте.",
            )}
          </p>
        </fieldset>
        <ErrorBox error={action.error} />
        <Submit pending={action.isPending}>{t("Сохранить")}</Submit>
      </Form>
    </section>
  );
}
