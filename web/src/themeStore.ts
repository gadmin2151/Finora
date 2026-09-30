import { useSyncExternalStore } from "react";
import {
  parseThemePreference,
  resolveTheme,
  type ThemePreference,
} from "./themeCore";

export type { ThemePreference } from "./themeCore";
const key = "finora.appearance";
const system = window.matchMedia("(prefers-color-scheme: dark)");
const listeners = new Set<() => void>();
function read(): ThemePreference {
  try {
    return parseThemePreference(localStorage.getItem(key));
  } catch {
    // Storage may be unavailable in a restricted browser; switching still works for this visit.
    return "system";
  }
}
let preference = read();
let resolved = resolveTheme(preference, system.matches);
function apply() {
  const theme = resolveTheme(preference, system.matches);
  resolved = theme;
  document.documentElement.dataset.theme = theme;
  document.documentElement.style.colorScheme =
    theme === "light" ? "light" : "dark";
  document
    .querySelector('meta[name="theme-color"]')
    ?.setAttribute(
      "content",
      theme === "light"
        ? "#f5f8f5"
        : theme === "material"
          ? "#18191b"
          : "#101715",
    );
  listeners.forEach((listener) => listener());
}
apply();
system.addEventListener("change", apply);
window.addEventListener("storage", (event) => {
  if (event.key === key || event.key === null) {
    preference = read();
    apply();
  }
});
export function setTheme(value: ThemePreference) {
  preference = value;
  try {
    localStorage.setItem(key, value);
  } catch {
    // Keep the in-memory choice when browser storage is disabled.
  }
  apply();
}
const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};
export function useAppearance() {
  const choice = useSyncExternalStore(subscribe, () => preference);
  const theme = useSyncExternalStore(subscribe, () => resolved);
  return { preference: choice, theme, dark: theme !== "light", setTheme };
}
