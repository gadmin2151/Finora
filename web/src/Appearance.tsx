import { t } from "./i18n";
import { useId } from "react";
import { Check, Monitor, Moon, Sun } from "lucide-react";
import { useAppearance, type ThemePreference } from "./themeStore";

const choices = [
  {
    value: "system",
    get title() {
      return t("Как в системе");
    },
    icon: Monitor,
  },
  {
    value: "light",
    get title() {
      return t("Светлая");
    },
    icon: Sun,
  },
  {
    value: "dark",
    get title() {
      return t("Тёмная");
    },
    icon: Moon,
  },
] as const;

export function AppearanceSettings() {
  const { preference, dark, setTheme } = useAppearance();
  const id = useId();
  return (
    <fieldset className="appearance-settings panel">
      <legend>{t("Оформление")}</legend>
      <p>{t("Выберите тему, в которой вам комфортно.")}</p>
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
          ? t(
              "Сейчас {0} · меняется вместе с системой.",
              dark ? t("тёмная") : t("светлая"),
            )
          : t("Выбрана вручную.")}{" "}
        {t("Выбор сохраняется на этом устройстве.")}
      </small>
    </fieldset>
  );
}

export function AppearanceToggle() {
  const { dark, setTheme } = useAppearance();
  const label = dark ? t("Включить светлую тему") : t("Включить тёмную тему");
  return (
    <button
      type="button"
      className="icon-button appearance-trigger"
      aria-label={label}
      title={label}
      onClick={() => setTheme(dark ? "light" : "dark")}
    >
      {dark ? (
        <Moon size={20} aria-hidden="true" />
      ) : (
        <Sun size={20} aria-hidden="true" />
      )}
    </button>
  );
}
