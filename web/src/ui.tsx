import { t, getLanguage, getLocale } from "./i18n";
import { pluralIndex } from "./i18nCore";
import { useApp } from "./context";
import {
  useEffect,
  useId,
  useRef,
  type FormEvent,
  type ReactNode,
} from "react";
import {
  AlertCircle,
  Apple,
  Baby,
  Bath,
  Beef,
  Candy,
  Cigarette,
  Croissant,
  CupSoda,
  Fish,
  Lamp,
  Milk,
  PawPrint,
  Pill,
  Popcorn,
  Shirt,
  Sparkles,
  SprayCan,
  Utensils,
  Wheat,
  Wine,
  Zap,
  ArrowDownLeft,
  ArrowUpRight,
  BookOpen,
  Car,
  Check,
  Coffee,
  Gift,
  Heart,
  House,
  LoaderCircle,
  Plane,
  Repeat2,
  ShoppingBag,
  ShoppingBasket,
  Tag,
  Train,
  X,
} from "lucide-react";
import { BrandMark } from "./BrandMark";
import { errorText } from "./api";
import type { Account, Category } from "./types";

export const today = () =>
  new Intl.DateTimeFormat("en-CA", {
    timeZone: "Europe/Chisinau",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());
export const currentMonth = () => today().slice(0, 7);
export const counted = (n: number, forms: [string, string, string]) => {
  return `${n} ${forms[pluralIndex(n, getLanguage())]}`;
};
export const amount = (
  value: number | null | undefined,
  currency = "MDL",
  compact = false,
) =>
  new Intl.NumberFormat(getLocale(), {
    style: "currency",
    currency,
    minimumFractionDigits: compact ? 0 : 2,
    maximumFractionDigits: compact ? 0 : 2,
  }).format((value ?? 0) / 100);
export const decimal = (value: number | null | undefined) =>
  ((value ?? 0) / 100).toFixed(2);
export const dateLabel = (date: string) =>
  new Intl.DateTimeFormat(getLocale(), {
    day: "numeric",
    month: "short",
  }).format(new Date(date.slice(0, 10) + "T12:00:00"));
export const monthLabel = (month: string) =>
  new Intl.DateTimeFormat(getLocale(), {
    month: "long",
    year: "numeric",
  }).format(new Date(month + "-01T12:00:00"));
export const key = () => crypto.randomUUID();
export const kinds: Record<string, string> = {
  get expense() {
    return t("Расход");
  },
  get income() {
    return t("Доход");
  },
  get transfer() {
    return t("Перевод");
  },
  get refund() {
    return t("Возврат покупки");
  },
  get adjustment() {
    return t("Корректировка +");
  },
  get adjustment_out() {
    return t("Корректировка −");
  },
  get debt_lend() {
    return t("Выдали в долг");
  },
  get debt_borrow() {
    return t("Взяли в долг");
  },
  get debt_repayment_in() {
    return t("Вам вернули долг");
  },
  get debt_repayment_out() {
    return t("Вернули долг");
  },
};
export const recurrenceLabels: Record<string, string> = {
  get monthly() {
    return t("Каждый месяц");
  },
  get quarterly() {
    return t("Раз в квартал");
  },
  get yearly() {
    return t("Каждый год");
  },
  get weekly() {
    return t("Каждую неделю");
  },
  get once() {
    return t("Один раз");
  },
};
export const statusLabels: Record<string, string> = {
  get received() {
    return t("Получен");
  },
  get paused() {
    return t("Приостановлен");
  },
  get paid() {
    return t("Оплачен");
  },
  get skipped() {
    return t("Пропущен");
  },
  get overdue() {
    return t("Просрочен");
  },
  get upcoming() {
    return t("Запланирован");
  },
  get queued() {
    return t("В очереди");
  },
  get processing() {
    return t("Распознаю");
  },
  get review() {
    return t("Нужна проверка");
  },
  get posted() {
    return t("В учёте");
  },
  get done() {
    return t("Готово");
  },
  get failed() {
    return t("Ошибка");
  },
  get running() {
    return t("В работе");
  },
};
export function CategoryIcon({
  category,
  size = 20,
}: {
  category?: Category;
  size?: number;
}) {
  const icons = {
    "shopping-basket": ShoppingBasket,
    coffee: Coffee,
    house: House,
    car: Car,
    train: Train,
    heart: Heart,
    "shopping-bag": ShoppingBag,
    repeat: Repeat2,
    book: BookOpen,
    plane: Plane,
    gift: Gift,
    tag: Tag,
    apple: Apple,
    baby: Baby,
    bath: Bath,
    beef: Beef,
    candy: Candy,
    cigarette: Cigarette,
    croissant: Croissant,
    "cup-soda": CupSoda,
    fish: Fish,
    lamp: Lamp,
    milk: Milk,
    "paw-print": PawPrint,
    pill: Pill,
    popcorn: Popcorn,
    shirt: Shirt,
    sparkles: Sparkles,
    "spray-can": SprayCan,
    utensils: Utensils,
    wheat: Wheat,
    wine: Wine,
    zap: Zap,
  };
  const Icon = icons[category?.icon as keyof typeof icons] ?? Tag;
  return (
    <span
      className="category-icon"
      style={{
        color: category?.color ?? "#8793a2",
        backgroundColor: `${category?.color ?? "#8793a2"}16`,
      }}
    >
      <Icon size={size} />
    </span>
  );
}
export function Badge({
  status,
  children,
}: {
  status?: string;
  children?: ReactNode;
}) {
  return (
    <span className={`badge ${status ?? ""}`}>
      {children ?? statusLabels[status ?? ""] ?? status}
    </span>
  );
}
export function ErrorBox({ error }: { error: unknown }) {
  return error ? (
    <div className="error-box" role="alert">
      <AlertCircle size={18} />
      <span>{errorText(error)}</span>
    </div>
  ) : null;
}
export function Loading({ text = t("Загружаю данные…") }: { text?: string }) {
  return (
    <div className="loading" role="status">
      <span className="brand-loader">
        <BrandMark />
        <span className="brand-loader-label">27G</span>
      </span>
      <span>{text}</span>
    </div>
  );
}
export function Empty({
  icon,
  title,
  text,
  action,
}: {
  icon?: ReactNode;
  title: string;
  text: string;
  action?: ReactNode;
}) {
  return (
    <div className="empty">
      <div className="empty-symbol">{icon ?? <ShoppingBasket size={28} />}</div>
      <h3>{title}</h3>
      <p>{text}</p>
      {action}
    </div>
  );
}
export function Field({
  label,
  children,
  hint,
  wide,
}: {
  label: string;
  children: ReactNode;
  hint?: string;
  wide?: boolean;
}) {
  return (
    <label className={`field ${wide ? "wide" : ""}`}>
      <span>{label}</span>
      {children}
      {hint && <small>{hint}</small>}
    </label>
  );
}
export function MoneyInput({
  value,
  onChange,
  required = true,
  min = "0.01",
  autoFocus = false,
}: {
  value: string;
  onChange: (value: string) => void;
  required?: boolean;
  min?: string;
  autoFocus?: boolean;
}) {
  return (
    <input
      type="number"
      inputMode="decimal"
      step="0.01"
      min={min}
      max="1000000000"
      value={value}
      onChange={(e) => onChange(e.target.value)}
      required={required}
      autoFocus={autoFocus}
      placeholder="0.00"
    />
  );
}
export function AccountSelect({
  accounts,
  value,
  onChange,
  optional = false,
  currency,
}: {
  accounts: Account[];
  value: string;
  onChange: (value: string) => void;
  optional?: boolean;
  currency?: string;
}) {
  const { prefs } = useApp();
  const combined = prefs?.accounting_mode === "combined";
  const primary = accounts.find(
    (a) =>
      a.id === prefs?.default_account_id && !a.archived && a.currency === "MDL",
  );
  const current = accounts.find((a) => a.id === value);
  const resolved =
    combined &&
    primary &&
    (!value || current?.currency === "MDL") &&
    (!currency || currency === "MDL")
      ? primary.id
      : value;
  useEffect(() => {
    if (resolved !== value) onChange(resolved);
  }, [resolved, value, onChange]);
  return (
    <select
      value={resolved}
      onChange={(e) => onChange(e.target.value)}
      required={!optional}
    >
      <option value="">
        {optional ? t("Выбрать позже") : t("Выберите счёт")}
      </option>
      {accounts
        .filter(
          (a) =>
            !a.archived &&
            (!currency || a.currency === currency) &&
            (!combined || a.currency !== "MDL" || a.id === primary?.id),
        )
        .map((a) => (
          <option key={a.id} value={a.id}>
            {a.name} · {a.currency}
          </option>
        ))}
    </select>
  );
}
export function CategorySelect({
  categories,
  value,
  onChange,
  required = false,
}: {
  categories: Category[];
  value: string;
  onChange: (value: string) => void;
  required?: boolean;
}) {
  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      required={required}
    >
      <option value="">{t("Без категории")}</option>
      {categories.map((c) => (
        <option key={c.id} value={c.id}>
          {c.name}
        </option>
      ))}
    </select>
  );
}
export function CurrencySelect({
  value,
  onChange,
}: {
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)}>
      {["MDL", "EUR", "USD", "RON"].map((c) => (
        <option key={c}>{c}</option>
      ))}
    </select>
  );
}
export function Submit({
  pending,
  disabled,
  children = t("Сохранить"),
}: {
  pending?: boolean;
  disabled?: boolean;
  children?: ReactNode;
}) {
  return (
    <button
      className="button primary"
      type="submit"
      disabled={pending || disabled}
    >
      {pending ? (
        <LoaderCircle size={18} className="spin" />
      ) : (
        <Check size={18} />
      )}{" "}
      {pending ? t("Сохраняю…") : children}
    </button>
  );
}
export function Modal({
  title,
  description,
  children,
  onClose,
  wide,
}: {
  title: string;
  description?: string;
  children: ReactNode;
  onClose: () => void;
  wide?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const id = useId();
  useEffect(() => {
    const node = ref.current;
    node?.showModal();
    node
      ?.querySelector<HTMLInputElement>(
        'input[autofocus], input:not([type="checkbox"]):not([type="hidden"])',
      )
      ?.focus();
    return () => {
      node?.close();
    };
  }, []);
  return (
    <dialog
      ref={ref}
      className={`modal ${wide ? "modal-wide" : ""}`}
      aria-labelledby={id}
      onCancel={onClose}
      onClick={(e) => {
        if (e.target === ref.current) onClose();
      }}
    >
      <div className="modal-content">
        <header className="modal-header">
          <div>
            <h2 id={id}>{title}</h2>
            {description && <p>{description}</p>}
          </div>
          <button
            className="icon-button"
            aria-label={t("Закрыть")}
            onClick={onClose}
          >
            <X size={22} />
          </button>
        </header>
        {children}
      </div>
    </dialog>
  );
}
export function Form({
  children,
  onSubmit,
}: {
  children: ReactNode;
  onSubmit: () => void;
}) {
  return (
    <form
      onSubmit={(e: FormEvent) => {
        e.preventDefault();
        onSubmit();
      }}
    >
      {children}
    </form>
  );
}
export function AmountChange({
  value,
  incoming,
  currency = "MDL",
}: {
  value: number;
  incoming: boolean;
  currency?: string;
}) {
  return (
    <span className={`amount-change ${incoming ? "positive" : ""}`}>
      {incoming ? <ArrowDownLeft size={15} /> : <ArrowUpRight size={15} />}{" "}
      {incoming ? "+" : "−"}
      {amount(value, currency)}
    </span>
  );
}
export function PageHeading({
  eyebrow,
  title,
  text,
  actions,
}: {
  eyebrow?: string;
  title: string;
  text: string;
  actions?: ReactNode;
}) {
  return (
    <div className="page-heading">
      <div>
        {eyebrow && <span className="eyebrow">{eyebrow}</span>}
        <h1>{title}</h1>
        <p>{text}</p>
      </div>
      {actions && <div className="heading-actions">{actions}</div>}
    </div>
  );
}
