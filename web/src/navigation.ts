import { t } from "./i18n";
import {
  Banknote,
  Building2,
  CalendarClock,
  ChartNoAxesCombined,
  FolderCog,
  LayoutDashboard,
  ListFilter,
  ScanLine,
  Settings,
  ShoppingBasket,
  Sparkles,
  Target,
  Users,
  Wallet,
  type LucideIcon,
} from "lucide-react";
import type { Route } from "./types";

export interface NavigationItem {
  id: Route;
  label: string;
  icon: LucideIcon;
  adminOnly?: boolean;
  serverOnly?: boolean;
}

interface NavigationGroup {
  id: string;
  label: string;
  description: string;
  memberDescription?: string;
  icon: LucideIcon;
  items: NavigationItem[];
}

export const overviewLink: NavigationItem = {
  id: "overview",
  get label() {
    return t("Обзор");
  },
  icon: LayoutDashboard,
};

export const navigationGroups: NavigationGroup[] = [
  {
    id: "money",
    get label() {
      return t("Деньги");
    },
    get description() {
      return t("Счета, доходы, операции");
    },
    get memberDescription() {
      return t("Доходы и операции");
    },
    icon: Wallet,
    items: [
      {
        id: "accounts",
        get label() {
          return t("Счета и остатки");
        },
        icon: Wallet,
        adminOnly: true,
      },
      {
        id: "income",
        get label() {
          return t("Доходы");
        },
        icon: Banknote,
      },
      {
        id: "transactions",
        get label() {
          return t("Все операции");
        },
        icon: ListFilter,
      },
      {
        id: "debts",
        get label() {
          return t("Долги");
        },
        icon: Users,
        adminOnly: true,
      },
    ],
  },
  {
    id: "shopping",
    get label() {
      return t("Чеки и покупки");
    },
    get description() {
      return t("Чеки, товары, цены");
    },
    icon: ShoppingBasket,
    items: [
      {
        id: "receipts",
        get label() {
          return t("Чеки");
        },
        icon: ScanLine,
      },
      {
        id: "purchases",
        get label() {
          return t("Товары и цены");
        },
        icon: ShoppingBasket,
      },
    ],
  },
  {
    id: "planning",
    get label() {
      return t("Планирование");
    },
    get description() {
      return t("Бюджеты и платежи");
    },
    icon: CalendarClock,
    items: [
      {
        id: "budgets",
        get label() {
          return t("Бюджеты");
        },
        icon: Target,
        adminOnly: true,
      },
      {
        id: "bills",
        get label() {
          return t("Регулярные платежи");
        },
        icon: CalendarClock,
        adminOnly: true,
      },
    ],
  },
  {
    id: "analytics",
    get label() {
      return t("Аналитика");
    },
    get description() {
      return t("Экономия и AI-помощник");
    },
    get memberDescription() {
      return t("Где можно сэкономить");
    },
    icon: ChartNoAxesCombined,
    items: [
      {
        id: "reports",
        get label() {
          return t("Подробные отчёты");
        },
        icon: ChartNoAxesCombined,
      },
      {
        id: "insights",
        get label() {
          return t("Анализ и экономия");
        },
        icon: ChartNoAxesCombined,
      },
      {
        id: "assistant",
        get label() {
          return t("AI-помощник");
        },
        icon: Sparkles,
      },
    ],
  },
  {
    id: "management",
    get label() {
      return t("Управление");
    },
    get description() {
      return t("Команда и настройки");
    },
    icon: FolderCog,
    items: [
      {
        id: "organizations",
        get label() {
          return t("Организации и люди");
        },
        icon: Building2,
      },
      {
        id: "users",
        get label() {
          return t("Пользователи");
        },
        icon: Users,
        serverOnly: true,
      },
      {
        id: "settings",
        get label() {
          return t("Настройки");
        },
        icon: Settings,
      },
    ],
  },
];

export const navigationItems = [
  overviewLink,
  ...navigationGroups.flatMap((group) => group.items),
];

export function canAccessRoute(
  route: Route,
  isAdmin: boolean,
  isServerAdmin = false,
) {
  const item = navigationItems.find((item) => item.id === route);
  return Boolean(
    item && (!item.serverOnly || isServerAdmin) && (isAdmin || !item.adminOnly),
  );
}

export function navigationGroupFor(route: Route) {
  return navigationGroups.find((group) =>
    group.items.some((item) => item.id === route),
  )?.id;
}
