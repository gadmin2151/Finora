import { t } from "./i18n";
import { Building2, Users, Settings } from "lucide-react";
import { useApp } from "./context";

export function ManagementTabs({
  current,
}: {
  current: "organizations" | "users" | "system-settings";
}) {
  const { user, navigate } = useApp();
  return (
    <nav className="management-tabs" aria-label={t("Управление доступом")}>
      <button
        aria-current={current === "organizations" ? "page" : undefined}
        onClick={() => navigate("organizations")}
      >
        <Building2 size={18} /> {t("Организации")}
      </button>
      {user.is_server_admin && (
        <>
          <button
            aria-current={current === "users" ? "page" : undefined}
            onClick={() => navigate("users")}
          >
            <Users size={18} /> {t("Пользователи")}
          </button>
          <button
            aria-current={current === "system-settings" ? "page" : undefined}
            onClick={() => navigate("system-settings")}
          >
            <Settings size={18} /> {t("Системные настройки")}
          </button>
        </>
      )}
    </nav>
  );
}
