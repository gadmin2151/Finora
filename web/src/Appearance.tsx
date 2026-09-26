import { useEffect, useId, useRef, useState } from "react";
import { Check, Monitor, Moon, Sun } from "lucide-react";
import { useAppearance, type ThemePreference } from "./themeStore";

const choices = [
  { value: "system", title: "Как в системе", icon: Monitor },
  { value: "light", title: "Светлая", icon: Sun },
  { value: "dark", title: "Тёмная", icon: Moon },
] as const;

export function AppearanceSettings({ compact = false }: { compact?: boolean }) {
  const { preference, dark, setTheme } = useAppearance();
  const id = useId();
  return (
    <fieldset
      className={`appearance-settings ${compact ? "compact" : "panel"}`}
    >
      <legend>Оформление</legend>
      <p>Выберите тему, в которой вам комфортно.</p>
      <div className="appearance-choices">
        {choices.map(({ value, title, icon: Icon }) => (
          <label
            className={`appearance-choice ${preference === value ? "selected" : ""}`}
            key={value}
          >
            <input
              type="radio"
              name={id}
              value={value}
              checked={preference === value}
              onChange={() => setTheme(value as ThemePreference)}
            />
            <span className={`theme-miniature ${value}`} aria-hidden="true">
              <i />
              <span>
                <b />
                <em />
                <em />
              </span>
            </span>
            <span className="appearance-label">
              <Icon size={15} />
              <span>{title}</span>
            </span>
            <span className="appearance-check" aria-hidden="true">
              {preference === value && <Check size={12} />}
            </span>
          </label>
        ))}
      </div>
      <small aria-live="polite">
        {preference === "system"
          ? `Сейчас ${dark ? "тёмная" : "светлая"} · меняется вместе с системой.`
          : "Выбрана вручную."}{" "}
        Выбор сохраняется на этом устройстве.
      </small>
    </fieldset>
  );
}

export function AppearanceMenu() {
  const [open, setOpen] = useState(false);
  const { dark } = useAppearance();
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const id = useId();
  useEffect(() => {
    if (!open) return;
    root.current?.querySelector<HTMLInputElement>("input:checked")?.focus();
    const outside = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
        trigger.current?.focus();
      }
    };
    document.addEventListener("pointerdown", outside);
    document.addEventListener("keydown", escape);
    return () => {
      document.removeEventListener("pointerdown", outside);
      document.removeEventListener("keydown", escape);
    };
  }, [open]);
  return (
    <div
      className="appearance-menu"
      ref={root}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
      }}
    >
      <button
        className="icon-button appearance-trigger"
        ref={trigger}
        aria-label="Выбрать тему оформления"
        aria-expanded={open}
        aria-controls={id}
        onClick={() => setOpen(!open)}
      >
        {dark ? <Moon size={20} /> : <Sun size={20} />}
      </button>
      {open && (
        <div className="appearance-popover" id={id}>
          <AppearanceSettings compact />
        </div>
      )}
    </div>
  );
}
