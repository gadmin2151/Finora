import { moneyMinor, minorDecimal } from "./wallet";

/** Quantity has six decimal places; round a line once, in integer cents. */
export function receiptLineTotal(quantity: string, price: string): string {
  const text = quantity.trim().replace(",", ".");
  const cents = moneyMinor(price);
  if (!/^\d{1,6}(?:\.\d{1,6})?$/.test(text) || cents === null || cents < 0)
    return "";
  const [whole, fraction = ""] = text.split(".");
  const units = BigInt(whole) * 1_000_000n + BigInt(fraction.padEnd(6, "0"));
  if (units <= 0n || units > 100_000_000_000n) return "";
  const total = (units * BigInt(cents) + 500_000n) / 1_000_000n;
  return total <= 99_999_999_999n ? minorDecimal(Number(total)) : "";
}
