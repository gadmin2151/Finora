import { useState } from "react";
import { ArrowUpRight, SlidersHorizontal, Wallet } from "lucide-react";
import { useApp } from "./context";
import { t } from "./i18n";
import type { Dashboard } from "./types";
import { amount } from "./ui";
import { BalanceAdjustment } from "./BalanceAdjustment";
import { combinedBalanceTarget, type BalanceTarget } from "./wallet";

export function WalletCard({ dashboard }: { dashboard: Dashboard }) {
  const { isAdmin, navigate, prefs } = useApp();
  const [details, setDetails] = useState(false);
  const combined = prefs?.accounting_mode === "combined";
  const [selected, setSelected] = useState("");
  const [editing, setEditing] = useState<BalanceTarget | null>(null);
  const wallet = dashboard.wallet ?? dashboard;
  const accounts = wallet.accounts;
  const account =
    !combined || details
      ? accounts.find((value) => value.id === selected)
      : undefined;
  const correction = account
    ? account.archived
      ? null
      : { account, balance_minor: account.balance_minor }
    : combinedBalanceTarget(accounts, prefs);
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
      {combined && (
        <div className="between">
          <span>{t("Всё вместе")}</span>
          {accounts.length > 1 && (
            <button
              className="text-button"
              onClick={() => setDetails(!details)}
            >
              {details ? t("Скрыть счета") : t("Показать счета")}
            </button>
          )}
        </div>
      )}
      {(!combined || details) && (
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
      )}
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
        {isAdmin && correction && (
          <button
            className="button secondary"
            onClick={() => setEditing(correction)}
          >
            <SlidersHorizontal size={16} />
            {t("Указать текущий остаток")}
          </button>
        )}
        {isAdmin &&
          !account &&
          !correction &&
          accounts.length > 0 &&
          (!combined || details) && (
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
        <BalanceAdjustment target={editing} onClose={() => setEditing(null)} />
      )}
    </section>
  );
}
