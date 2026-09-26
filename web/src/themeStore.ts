import { useSyncExternalStore } from "react";

export type ThemePreference = "system" | "light" | "dark";
const key = "finora.appearance";
const system = window.matchMedia("(prefers-color-scheme: dark)");
const listeners = new Set<() => void>();
function read(): ThemePreference {
  try {
    const saved = localStorage.getItem(key);
    return saved === "light" || saved === "dark" ? saved : "system";
  } catch {
    // Storage may be unavailable in a restricted browser; switching still works for this visit.
    return "system";
  }
}
let preference = read();
function apply() {
  const theme =
    preference === "system" ? (system.matches ? "dark" : "light") : preference;
  document.documentElement.dataset.theme = theme;
  document.documentElement.style.colorScheme = theme;
  document
    .querySelector('meta[name="theme-color"]')
    ?.setAttribute("content", theme === "dark" ? "#101715" : "#f5f8f5");
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
  const resolved = useSyncExternalStore(
    subscribe,
    () => document.documentElement.dataset.theme,
  );
  return { preference: choice, dark: resolved === "dark", setTheme };
}
