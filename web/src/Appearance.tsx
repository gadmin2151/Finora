import { t } from "./i18n";
import { useId } from "react";
import { Check, Leaf, Monitor, Moon, Sun } from "lucide-react";
import { useAppearance, type ThemePreference } from "./themeStore";
import { nextTheme, themeNames } from "./themeCore";

const choices = [
  {
    value: "system",
    get title() {
      return t("Как в системе");
    },
    icon: Monitor,
  },
  {
    value: "dark",
    title: themeNames.dark,
    icon: Leaf,
  },
  {
    value: "light",
    title: themeNames.light,
    icon: Sun,
  },
  {
    value: "material",
    title: themeNames.material,
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
  const { theme, setTheme } = useAppearance();
  const next = nextTheme(theme);
  const label = t(
    "Тема: {0}. Включить {1}",
    themeNames[theme],
    themeNames[next],
  );
  const Icon = theme === "dark" ? Leaf : theme === "light" ? Sun : Moon;
  return (
    <button
      type="button"
      className="icon-button appearance-trigger"
      aria-label={label}
      title={label}
      onClick={() => setTheme(next)}
    >
      <Icon size={20} aria-hidden="true" />
    </button>
  );
}
