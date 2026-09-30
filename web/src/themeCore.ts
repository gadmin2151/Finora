export type Theme = "dark" | "light" | "material";
export type ThemePreference = Theme | "system";

export const themeNames: Record<Theme, string> = {
  dark: "Classic",
  light: "White",
  material: "Material Black",
};

export function parseThemePreference(value: unknown): ThemePreference {
  return value === "dark" || value === "light" || value === "material"
    ? value
    : "system";
}

export function resolveTheme(
  value: ThemePreference,
  systemDark: boolean,
): Theme {
  return value === "system" ? (systemDark ? "dark" : "light") : value;
}

export function nextTheme(value: Theme): Theme {
  return value === "dark" ? "light" : value === "light" ? "material" : "dark";
}
