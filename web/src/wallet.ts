export type BalanceMode = "add" | "subtract" | "set";

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
