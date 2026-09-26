import { t } from "./i18n";

// Stored API values are language-independent; only the visible labels change.
export const defaultReceiptUnit = "шт";
export const receiptUnitOptions = [
  {
    value: defaultReceiptUnit,
    get label() {
      return t("шт");
    },
  },
  {
    value: "кг",
    get label() {
      return t("кг");
    },
  },
  {
    value: "г",
    get label() {
      return t("г");
    },
  },
  {
    value: "л",
    get label() {
      return t("л");
    },
  },
  {
    value: "мл",
    get label() {
      return t("мл");
    },
  },
];

export const unitLabel = (value: string) =>
  receiptUnitOptions.find((option) => option.value === value)?.label ?? value;

export const canonicalUnit = (value: string) =>
  receiptUnitOptions.find((option) => option.label === value)?.value ?? value;
