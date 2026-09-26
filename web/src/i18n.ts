import { useSyncExternalStore } from "react";
import english from "./locales/en.json";
import {
  formatMessage,
  parseLanguagePreference,
  resolveLanguage,
  type LanguagePreference,
} from "./i18nCore";
export type { Language, LanguagePreference } from "./i18nCore";

const key = "finora.language";
const listeners = new Set<() => void>();
function read(): LanguagePreference {
  try {
    return parseLanguagePreference(localStorage.getItem(key));
  } catch {
    return "system";
  } // A blocked storage still allows an in-memory choice.
}
let preference = read();
let language = resolveLanguage(preference, navigator.language);
export const getLanguage = () => language;
export const getLocale = () => (language === "ru" ? "ru-RU" : "en-GB");
export const t = (
  source: string,
  ...values: (string | number | null | undefined)[]
) => formatMessage(source, language, english, values);
function apply() {
  const previous = language;
  language = resolveLanguage(preference, navigator.language);
  document.documentElement.lang = language;
  document
    .querySelector('meta[name="description"]')
    ?.setAttribute(
      "content",
      language === "ru"
        ? "Finora — ваши деньги, чеки и планы в одном месте на вашем сервере."
        : "Finora — your money, receipts and plans in one place, on your own server.",
    );
  listeners.forEach((listener) => listener());
  if (previous !== language) window.dispatchEvent(new Event("finora:language"));
}
export function setLanguage(value: LanguagePreference) {
  preference = parseLanguagePreference(value);
  try {
    localStorage.setItem(key, preference);
  } catch {
    /* Keep the choice for this visit when storage is disabled. */
  }
  apply();
}
window.addEventListener("storage", (event) => {
  if (event.key === key || event.key === null) {
    preference = read();
    apply();
  }
});
window.addEventListener("languagechange", apply);
apply();
const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};
export function useLanguage() {
  useSyncExternalStore(subscribe, () => `${preference}:${language}`);
  return { preference, language, setLanguage };
}
