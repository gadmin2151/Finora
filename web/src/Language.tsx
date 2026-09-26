import { useId } from "react";
import { Globe2 } from "lucide-react";
import { useLanguage, t, type LanguagePreference } from "./i18n";

export function LanguageSelect({
  compact = false,
  disabled = false,
  onChange,
}: {
  compact?: boolean;
  disabled?: boolean;
  onChange?: () => void;
}) {
  const { preference, setLanguage } = useLanguage();
  const id = useId();
  return (
    <div className={`language-select${compact ? " compact" : ""}`}>
      <label htmlFor={id}>
        <Globe2 size={17} aria-hidden="true" />
        {t("Язык / Language")}
      </label>
      <select
        id={id}
        value={preference}
        disabled={disabled}
        onChange={(event) => {
          setLanguage(event.target.value as LanguagePreference);
          onChange?.();
        }}
      >
        <option value="system">{t("Язык устройства")}</option>
        <option value="en" lang="en">
          English
        </option>
        <option value="ru" lang="ru">
          Русский
        </option>
      </select>
    </div>
  );
}
export function LanguageSettings() {
  return (
    <section className="panel language-settings">
      <h2>{t("Язык интерфейса")}</h2>
      <p>
        {t(
          "Русский или английский — выберите удобный язык. Выбор сохраняется на этом устройстве.",
        )}
      </p>
      <LanguageSelect />
      <small>
        {t(
          "Автоматически: русский для устройства на русском, английский — для остальных языков. Ваши данные не переводятся.",
        )}
      </small>
    </section>
  );
}
