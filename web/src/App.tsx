import { lazy, Suspense, useEffect, useRef, useState } from "react";
import { useIsMutating, useMutation, useQuery } from "@tanstack/react-query";
import {
  ArrowRight,
  Building2,
  Check,
  ChevronLeft,
  ChevronRight,
  CircleHelp,
  Menu,
  Plus,
  ShieldCheck,
  Wallet,
  WifiOff,
  X,
} from "lucide-react";
import {
  api,
  clearSession,
  errorText,
  queryClient,
  send,
  setCsrf,
  setOrganization,
  useAction,
} from "./api";
import { AppContext, type ModalState } from "./context";
import type {
  Account,
  Category,
  Preferences,
  Route,
  User,
  Organization,
} from "./types";
import {
  ErrorBox,
  Field,
  Form,
  Loading,
  Submit,
  currentMonth,
  monthLabel,
} from "./ui";
import TransactionForm from "./TransactionForm";
import Sidebar from "./Sidebar";
import { canAccessRoute, navigationItems } from "./navigation";
import { ReceiptEditor, UploadReceipt } from "./ReceiptDialogs";
const Income = lazy(() => import("./pages/Income"));
const Purchases = lazy(() => import("./pages/Purchases"));
const Organizations = lazy(() => import("./pages/Organizations"));
const Overview = lazy(() => import("./pages/Overview"));
const Transactions = lazy(() => import("./pages/Transactions"));
const Assistant = lazy(() =>
  import("./pages/Assistant").then((m) => ({ default: m.Assistant })),
);
const Receipts = lazy(() =>
  import("./pages/Assistant").then((m) => ({ default: m.Receipts })),
);
const Insights = lazy(() =>
  import("./pages/Assistant").then((m) => ({ default: m.Insights })),
);
const Budgets = lazy(() =>
  import("./pages/Planning").then((m) => ({ default: m.Budgets })),
);
const Bills = lazy(() =>
  import("./pages/Planning").then((m) => ({ default: m.Bills })),
);
const Debts = lazy(() =>
  import("./pages/Planning").then((m) => ({ default: m.Debts })),
);
const Accounts = lazy(() =>
  import("./pages/Settings").then((m) => ({ default: m.Accounts })),
);
const Settings = lazy(() => import("./pages/Settings"));

function routeFromHash(): Route {
  const hash = location.hash.slice(1);
  return navigationItems.some((n) => n.id === hash)
    ? (hash as Route)
    : "overview";
}
function Logo() {
  return (
    <div className="brand">
      <span className="brand-mark" aria-hidden="true">
        <i />
        <i />
        <i />
      </span>
      <span>
        finora<span className="brand-dot">.</span>
      </span>
    </div>
  );
}

export default function App() {
  const auth = useQuery({
    queryKey: ["me"],
    queryFn: () => api<User | null>("/auth/me"),
    retry: false,
    staleTime: 300_000,
  });
  useEffect(() => {
    const unauthorized = () => {
      void clearSession();
    };
    window.addEventListener("finora:unauthorized", unauthorized);
    return () =>
      window.removeEventListener("finora:unauthorized", unauthorized);
  }, []);
  useEffect(() => {
    if (auth.data) setCsrf(auth.data.csrf);
  }, [auth.data]);
  if (auth.isPending)
    return (
      <div className="boot">
        <Logo />
        <Loading text="Открываю ваше пространство…" />
      </div>
    );
  if (!auth.data) return <Login />;
  return <OrganizationGate user={auth.data} />;
}

function Login() {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const action = useAction(async () => {
    const user = await send<User>("/auth/login", { username, password });
    setCsrf(user.csrf);
    queryClient.setQueryData(["me"], user);
  });
  return (
    <div className="login-page">
      <aside className="login-story">
        <Logo />
        <div className="login-illustration" aria-hidden="true">
          <div className="orbital orbital-one" />
          <div className="orbital orbital-two" />
          <div className="login-card-shape">
            <Wallet size={33} />
            <span>Больше ясности</span>
            <strong>в каждом дне.</strong>
            <div className="abstract-chart">
              <i />
              <i />
              <i />
              <i />
              <i />
              <i />
              <i />
            </div>
          </div>
          <span className="floating-icon">
            <Check size={28} />
          </span>
        </div>
        <div className="login-story-text">
          <h1>
            Спокойствие начинается
            <br />с понятных финансов.
          </h1>
          <p>
            Расходы, планы и маленькие шаги к большим целям — в вашем личном
            пространстве.
          </p>
        </div>
        <span className="private-note">
          <ShieldCheck size={17} />
          Ваш сервер. Ваши данные. Ваши решения.
        </span>
      </aside>
      <main className="login-main">
        <div className="login-form">
          <span className="eyebrow">РАДЫ ВАС ВИДЕТЬ</span>
          <h2>Добро пожаловать</h2>
          <p>Войдите, чтобы продолжить заботиться о своих финансах.</p>
          <Form onSubmit={() => action.mutate(undefined)}>
            <Field label="Логин">
              <input
                autoFocus
                autoComplete="username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                required
                maxLength={80}
                placeholder="Ваш логин"
              />
            </Field>
            <Field label="Пароль">
              <input
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                maxLength={256}
                placeholder="Введите пароль"
              />
            </Field>
            <ErrorBox error={action.error} />
            <Submit pending={action.isPending}>
              Войти в Finora
              <ArrowRight size={18} />
            </Submit>
          </Form>
          <div className="login-help">
            <CircleHelp size={18} />
            <span>
              Первый вход? Логин и пароль выданы владельцем сервера.
              Самостоятельная регистрация закрыта.
            </span>
          </div>
        </div>
        <footer>Finora · личные финансы, с заботой о вас</footer>
      </main>
    </div>
  );
}

function OrganizationGate({ user }: { user: User }) {
  const [selected, setSelected] = useState("");
  const [changing, setChanging] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const organization = user.organizations.find((org) => org.id === selected);
  const logout = useMutation({
    mutationFn: () => send("/auth/logout"),
    onSuccess: clearSession,
  });
  async function switchOrganization(id: string) {
    if (id === selected) return;
    setChanging(true);
    setFailure(null);
    try {
      await queryClient.cancelQueries({
        predicate: (query) => query.queryKey[0] !== "me",
      });
      setOrganization(id);
      queryClient.removeQueries({
        predicate: (query) => query.queryKey[0] !== "me",
      });
      setSelected(id);
    } catch (error) {
      setFailure(error);
    } finally {
      setChanging(false);
    }
  }
  if (changing)
    return (
      <div className="boot">
        <Logo />
        <Loading text="Открываю организацию…" />
      </div>
    );
  if (!organization)
    return (
      <main className="organization-picker">
        <Logo />
        <span className="eyebrow">ВАШИ ОРГАНИЗАЦИИ</span>
        <h1>Где будем вести учёт?</h1>
        <p>
          Выберите организацию. Все новые чеки и операции попадут в её общую
          историю.
        </p>
        <div className="organization-grid">
          {user.organizations.map((org) => (
            <button
              key={org.id}
              className="panel organization-card"
              onClick={() => {
                void switchOrganization(org.id);
              }}
            >
              <Building2 size={28} />
              <strong>{org.name}</strong>
              <small>
                {org.role === "admin" ? "Администратор" : "Пользователь"}
              </small>
              <ArrowRight size={20} />
            </button>
          ))}
        </div>
        {!user.organizations.length && (
          <p>
            Нет доступных организаций. Попросите администратора добавить ваш
            логин: {user.username}.
          </p>
        )}
        <ErrorBox error={failure ?? logout.error} />
        <button
          className="text-button"
          disabled={logout.isPending}
          onClick={() => logout.mutate()}
        >
          Выйти из аккаунта
        </button>
      </main>
    );
  return (
    <Workspace
      key={`${organization.id}-${organization.role}`}
      user={user}
      organization={organization}
      switchOrganization={switchOrganization}
    />
  );
}

function Workspace({
  user,
  organization,
  switchOrganization,
}: {
  user: User;
  organization: Organization;
  switchOrganization: (id: string) => Promise<void>;
}) {
  const isAdmin = organization.role === "admin";
  const mutating = useIsMutating();
  const [route, setRoute] = useState<Route>(routeFromHash);
  const [month, setMonth] = useState(currentMonth);
  const [modal, setModal] = useState<ModalState>(null);
  const [menu, setMenu] = useState(false);
  const menuButton = useRef<HTMLButtonElement>(null);
  const [notice, setNotice] = useState("");
  const [online, setOnline] = useState(navigator.onLine);
  const accounts = useQuery({
    queryKey: ["accounts"],
    queryFn: () => api<Account[]>("/accounts"),
  });
  const categories = useQuery({
    queryKey: ["categories"],
    queryFn: () => api<Category[]>("/categories"),
  });
  const preferences = useQuery({
    queryKey: ["settings"],
    queryFn: () => api<Preferences>("/settings"),
  });
  const logout = useMutation({
    mutationFn: () => send("/auth/logout"),
    onSuccess: clearSession,
  });
  useEffect(() => {
    const change = () => {
      setRoute(routeFromHash());
      setMenu(false);
      window.scrollTo({ top: 0 });
    };
    window.addEventListener("hashchange", change);
    return () => window.removeEventListener("hashchange", change);
  }, []);
  useEffect(() => {
    const media = window.matchMedia("(max-width: 800px)");
    const change = () => {
      if (!media.matches) setMenu(false);
    };
    media.addEventListener("change", change);
    return () => media.removeEventListener("change", change);
  }, []);
  useEffect(() => {
    const change = () => setOnline(navigator.onLine);
    window.addEventListener("online", change);
    window.addEventListener("offline", change);
    return () => {
      window.removeEventListener("online", change);
      window.removeEventListener("offline", change);
    };
  }, []);
  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(""), 4500);
    return () => window.clearTimeout(timer);
  }, [notice]);
  const navigate = (value: Route) => {
    location.hash = value;
  };
  function stepMonth(delta: number) {
    const date = new Date(month + "-01T12:00:00");
    date.setMonth(date.getMonth() + delta);
    const value = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}`;
    if (date.getFullYear() >= 1990 && date.getFullYear() <= 2100)
      setMonth(value);
  }
  const pages = {
    income: Income,
    purchases: Purchases,
    organizations: Organizations,
    overview: Overview,
    transactions: Transactions,
    assistant: Assistant,
    receipts: Receipts,
    budgets: Budgets,
    bills: Bills,
    debts: Debts,
    insights: Insights,
    accounts: Accounts,
    settings: Settings,
  };
  const activeRoute = canAccessRoute(route, isAdmin) ? route : "overview";
  const Page = pages[activeRoute];
  return (
    <AppContext.Provider
      value={{
        user,
        organization,
        isAdmin,
        switchOrganization,
        month,
        accounts: accounts.data ?? [],
        categories: categories.data ?? [],
        prefs: preferences.data,
        navigate,
        open: (value) => {
          if (!isAdmin && value?.type === "transaction") return;
          setModal(value);
        },
        toast: setNotice,
      }}
    >
      <a
        href="#main-content"
        className="skip-link"
        onClick={(event) => {
          event.preventDefault();
          document.getElementById("main-content")?.focus();
        }}
      >
        Перейти к содержимому
      </a>
      <Sidebar
        user={user}
        organization={organization}
        route={activeRoute}
        open={menu}
        busy={mutating > 0}
        logoutPending={logout.isPending}
        menuButton={menuButton}
        brand={<Logo />}
        onClose={() => setMenu(false)}
        onSwitch={switchOrganization}
        onLogout={() => logout.mutate(undefined)}
      />
      <div className="workspace" inert={menu}>
        <header className="topbar">
          <div className="breadcrumb">
            <button
              className="icon-button mobile-only"
              ref={menuButton}
              aria-label="Открыть меню"
              aria-expanded={menu}
              aria-controls="app-navigation"
              onClick={() => setMenu(true)}
            >
              <Menu size={22} />
            </button>
            <span className="breadcrumb-home" title={organization.name}>
              {organization.name}
            </span>
            <ChevronRight size={14} />
            <strong>
              {navigationItems.find((n) => n.id === activeRoute)?.label}
            </strong>
          </div>
          <div className="topbar-actions">
            <div className="month-control">
              <button
                className="icon-button"
                aria-label="Предыдущий месяц"
                onClick={() => stepMonth(-1)}
              >
                <ChevronLeft size={17} />
              </button>
              <label title="Выбрать месяц">
                <span>{monthLabel(month)}</span>
                <input
                  type="month"
                  aria-label="Месяц отчёта"
                  value={month}
                  min="1990-01"
                  max="2100-12"
                  onChange={(e) => {
                    if (/^\d{4}-(0[1-9]|1[0-2])$/.test(e.target.value))
                      setMonth(e.target.value);
                  }}
                />
              </label>
              <button
                className="icon-button"
                aria-label="Следующий месяц"
                onClick={() => stepMonth(1)}
              >
                <ChevronRight size={17} />
              </button>
            </div>
            <button
              className="button primary quick-add"
              aria-label={isAdmin ? "Добавить операцию" : "Добавить чек"}
              onClick={() =>
                setModal({ type: isAdmin ? "transaction" : "upload" })
              }
            >
              <Plus size={19} />
              <span>Добавить</span>
            </button>
          </div>
        </header>
        {!online && (
          <div className="offline-banner">
            <WifiOff size={17} />
            Нет связи. Уже открытые данные остаются на экране. Перед сохранением
            восстановите соединение.
          </div>
        )}
        <main
          id="main-content"
          tabIndex={-1}
          className={`main-content route-${activeRoute}`}
        >
          <ErrorBox
            error={
              accounts.error ??
              categories.error ??
              preferences.error ??
              logout.error
            }
          />
          <Suspense fallback={<Loading />}>
            <ErrorBox error={logout.error} />
            <Page key={`${activeRoute}-${month}`} />
          </Suspense>
        </main>
        <footer className="workspace-footer">
          <span>Finora · больше ясности в каждом дне</span>
          <span>Учёт в MDL · Europe/Chisinau</span>
        </footer>
      </div>
      {isAdmin && modal?.type === "transaction" && (
        <TransactionForm {...modal} onClose={() => setModal(null)} />
      )}{" "}
      {modal?.type === "upload" && (
        <UploadReceipt onClose={() => setModal(null)} />
      )}{" "}
      {modal?.type === "receipt" && (
        <ReceiptEditor receipt={modal.receipt} onClose={() => setModal(null)} />
      )}
      {notice && (
        <div className="toast" role="status">
          <Check size={18} />
          {notice}
          <button
            className="icon-button"
            aria-label="Закрыть уведомление"
            onClick={() => setNotice("")}
          >
            <X size={15} />
          </button>
        </div>
      )}
    </AppContext.Provider>
  );
}

export function FatalError({ error }: { error: unknown }) {
  return (
    <div className="boot">
      <Logo />
      <ErrorBox
        error={new Error(`Не удалось открыть интерфейс. ${errorText(error)}`)}
      />
      <button className="button primary" onClick={() => location.reload()}>
        Перезагрузить
      </button>
    </div>
  );
}
