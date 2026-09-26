import { t } from "./i18n";
import { Building2, Users } from "lucide-react";
import { useApp } from "./context";

export function ManagementTabs({
  current,
}: {
  current: "organizations" | "users";
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
        <button
          aria-current={current === "users" ? "page" : undefined}
          onClick={() => navigate("users")}
        >
          <Users size={18} /> {t("Пользователи")}
        </button>
      )}
    </nav>
  );
}
