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

export function receiptReviewTotals(
  total: string,
  items: ReadonlyArray<{
    name: string;
    quantity: string;
    unit_price: string;
    total: string;
  }>,
) {
  const receiptMinor = moneyMinor(total);
  const values = items.map((item) => moneyMinor(item.total));
  const invalidLineNumbers = items.flatMap((item, index) =>
    !item.name.trim() ||
    receiptLineTotal(item.quantity, item.unit_price) === "" ||
    values[index] === null ||
    values[index]! < 0
      ? [index + 1]
      : [],
  );
  const itemsMinor =
    items.length && values.every((value) => value !== null && value >= 0)
      ? values.reduce<number>((sum, value) => sum + value!, 0)
      : null;
  return {
    receiptMinor,
    itemsMinor,
    invalidLineNumbers,
    differenceMinor:
      receiptMinor !== null && receiptMinor > 0 && itemsMinor !== null
        ? receiptMinor - itemsMinor
        : null,
  };
}
