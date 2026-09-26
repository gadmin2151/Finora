import type { Account, Preferences } from "./types";

export type BalanceMode = "add" | "subtract" | "set";

export type BalanceTarget = {
  account: Account;
  balance_minor: number;
  accounting_version?: number;
};

/** Match the dashboard total, including historical and archived MDL accounts. */
export function combinedBalanceTarget(
  accounts: Account[],
  prefs?: Pick<
    Preferences,
    "accounting_mode" | "default_account_id" | "accounting_version"
  >,
): BalanceTarget | null {
  if (prefs?.accounting_mode !== "combined") return null;
  const account = accounts.find(
    (item) =>
      item.id === prefs.default_account_id &&
      !item.archived &&
      item.currency === "MDL",
  );
  if (!account) return null;
  return {
    account,
    balance_minor: accounts.reduce(
      (total, item) =>
        total + (item.currency === "MDL" ? item.balance_minor : 0),
      0,
    ),
    accounting_version: prefs.accounting_version,
  };
}

/** Parse user input in minor units without floating-point arithmetic. */
export function moneyMinor(input: string): number | null {
  const normalized = input.trim().replace(",", ".");
  if (!/^-?\d{1,9}(?:\.\d{1,2})?$/.test(normalized)) return null;
  const negative = normalized.startsWith("-");
  const [whole, fraction = ""] = normalized.replace(/^-/, "").split(".");
  const value = Number(whole) * 100 + Number(fraction.padEnd(2, "0"));
  return negative ? -value : value;
}

export function correctionTarget(
  balance: number,
  mode: BalanceMode,
  input: string,
): number | null {
  const value = moneyMinor(input);
  if (value === null || (mode !== "set" && value <= 0)) return null;
  const target =
    mode === "set" ? value : balance + (mode === "add" ? value : -value);
  return Number.isSafeInteger(target) && Math.abs(target) <= 99_999_999_999
    ? target
    : null;
}

export function minorDecimal(value: number): string {
  return `${value < 0 ? "-" : ""}${Math.floor(Math.abs(value) / 100)}.${String(Math.abs(value) % 100).padStart(2, "0")}`;
}
