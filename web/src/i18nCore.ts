/** Supported interface languages. Financial values and user content are never translated. */
export type Language = "ru" | "en";
export type LanguagePreference = Language | "system";

export function parseLanguagePreference(value: unknown): LanguagePreference {
  return value === "ru" || value === "en" ? value : "system";
}
export function resolveLanguage(
  preference: LanguagePreference,
  deviceLanguage: string,
): Language {
  return preference === "system"
    ? /^ru(?:[-_]|$)/i.test(deviceLanguage)
      ? "ru"
      : "en"
    : preference;
}
export function formatMessage(
  source: string,
  language: Language,
  catalog: Readonly<Record<string, string>>,
  values: readonly (string | number | null | undefined)[] = [],
): string {
  const message = language === "en" ? (catalog[source] ?? source) : source;
  // One pass: braces in a person's name, merchant or other value stay literal.
  return message.replace(/\{(\d+)\}/g, (token, index: string) =>
    Number(index) < values.length ? String(values[Number(index)] ?? "") : token,
  );
}
export function pluralIndex(value: number, language: Language): 0 | 1 | 2 {
  const category = new Intl.PluralRules(language).select(value);
  return category === "one" ? 0 : category === "few" ? 1 : 2;
}
