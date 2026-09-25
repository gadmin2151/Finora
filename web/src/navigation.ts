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
  label: "Обзор",
  icon: LayoutDashboard,
};

export const navigationGroups: NavigationGroup[] = [
  {
    id: "money",
    label: "Деньги",
    description: "Счета, доходы, операции",
    memberDescription: "Доходы и операции",
    icon: Wallet,
    items: [
      {
        id: "accounts",
        label: "Счета и остатки",
        icon: Wallet,
        adminOnly: true,
      },
      { id: "income", label: "Доходы", icon: Banknote },
      { id: "transactions", label: "Все операции", icon: ListFilter },
      { id: "debts", label: "Долги", icon: Users, adminOnly: true },
    ],
  },
  {
    id: "shopping",
    label: "Чеки и покупки",
    description: "Чеки, товары, цены",
    icon: ShoppingBasket,
    items: [
      { id: "receipts", label: "Чеки", icon: ScanLine },
      { id: "purchases", label: "Товары и цены", icon: ShoppingBasket },
    ],
  },
  {
    id: "planning",
    label: "Планирование",
    description: "Бюджеты и платежи",
    icon: CalendarClock,
    items: [
      { id: "budgets", label: "Бюджеты", icon: Target, adminOnly: true },
      {
        id: "bills",
        label: "Регулярные платежи",
        icon: CalendarClock,
        adminOnly: true,
      },
    ],
  },
  {
    id: "analytics",
    label: "Аналитика",
    description: "Экономия и AI-помощник",
    memberDescription: "Где можно сэкономить",
    icon: ChartNoAxesCombined,
    items: [
      { id: "insights", label: "Анализ и экономия", icon: ChartNoAxesCombined },
      {
        id: "assistant",
        label: "AI-помощник",
        icon: Sparkles,
        adminOnly: true,
      },
    ],
  },
  {
    id: "management",
    label: "Управление",
    description: "Команда и настройки",
    icon: FolderCog,
    items: [
      { id: "organizations", label: "Организации и люди", icon: Building2 },
      { id: "settings", label: "Настройки", icon: Settings },
    ],
  },
];

export const navigationItems = [
  overviewLink,
  ...navigationGroups.flatMap((group) => group.items),
];

export function canAccessRoute(route: Route, isAdmin: boolean) {
  const item = navigationItems.find((item) => item.id === route);
  return Boolean(item && (isAdmin || !item.adminOnly));
}

export function navigationGroupFor(route: Route) {
  return navigationGroups.find((group) =>
    group.items.some((item) => item.id === route),
  )?.id;
}
