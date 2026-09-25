import {
  useEffect,
  useId,
  useRef,
  useState,
  type KeyboardEvent,
  type ReactNode,
  type RefObject,
} from "react";
import {
  Building2,
  Check,
  ChevronDown,
  ChevronsUpDown,
  LogOut,
  Search,
  ShieldCheck,
  X,
} from "lucide-react";
import type { Organization, Route, User } from "./types";
import {
  canAccessRoute,
  navigationGroupFor,
  navigationGroups,
  overviewLink,
  type NavigationItem,
} from "./navigation";

function roleLabel(organization: Organization) {
  return organization.role === "admin" ? "Администратор" : "Пользователь";
}

function OrganizationSwitcher({
  user,
  organization,
  busy,
  onSwitch,
  onNavigate,
}: {
  user: User;
  organization: Organization;
  busy: boolean;
  onSwitch: (id: string) => Promise<void>;
  onNavigate: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const panelId = useId();
  const organizations = user.organizations.filter((item) =>
    item.name.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()),
  );

  useEffect(() => {
    if (!open) return;
    const initial = panel.current?.querySelector<HTMLElement>(
      "input, button[aria-pressed='true']",
    );
    initial?.focus();
    const dismiss = (event: Event) => {
      if (event.target instanceof Node && !root.current?.contains(event.target))
        setOpen(false);
    };
    document.addEventListener("pointerdown", dismiss);
    document.addEventListener("focusin", dismiss);
    return () => {
      document.removeEventListener("pointerdown", dismiss);
      document.removeEventListener("focusin", dismiss);
    };
  }, [open]);

  function close() {
    setOpen(false);
    trigger.current?.focus();
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (open && event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      close();
    }
    if (
      open &&
      event.target instanceof HTMLButtonElement &&
      ["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)
    ) {
      const options = Array.from(
        panel.current?.querySelectorAll<HTMLButtonElement>(
          ".organization-option:not(:disabled)",
        ) ?? [],
      );
      const current = options.indexOf(event.target);
      if (current < 0 || !options.length) return;
      event.preventDefault();
      const next =
        event.key === "Home"
          ? 0
          : event.key === "End"
            ? options.length - 1
            : (current +
                (event.key === "ArrowDown" ? 1 : -1) +
                options.length) %
              options.length;
      options[next]?.focus();
    }
  }

  return (
    <div className="organization-switcher" ref={root} onKeyDown={onKeyDown}>
      <button
        ref={trigger}
        className={`organization-trigger ${open ? "expanded" : ""}`}
        aria-label={`Организация: ${organization.name}. Сменить организацию`}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        disabled={busy}
        title={busy ? "Дождитесь завершения сохранения" : "Сменить организацию"}
        onClick={() => {
          setSearch("");
          setOpen(!open);
        }}
      >
        <span className="organization-avatar">
          <Building2 size={19} aria-hidden="true" />
        </span>
        <span className="organization-trigger-text">
          <small>Организация</small>
          <strong title={organization.name}>{organization.name}</strong>
        </span>
        <ChevronsUpDown size={16} aria-hidden="true" />
      </button>
      <div className="organization-context">
        <span className={`role-dot ${organization.role}`} />
        {roleLabel(organization)}
        <span className="organization-context-divider">·</span>Общий учёт
      </div>
      {open && (
        <div
          ref={panel}
          id={panelId}
          className="organization-popover"
          role="dialog"
          aria-labelledby={`${panelId}-title`}
        >
          <div className="organization-popover-heading">
            <strong id={`${panelId}-title`}>Ваши организации</strong>
            <span>{user.organizations.length}</span>
          </div>
          {user.organizations.length > 5 && (
            <div className="organization-search">
              <Search size={16} aria-hidden="true" />
              <input
                aria-label="Найти организацию"
                placeholder="Найти организацию…"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
              />
            </div>
          )}
          <div className="organization-options">
            {organizations.map((item) => (
              <button
                key={item.id}
                className={`organization-option ${item.id === organization.id ? "selected" : ""}`}
                aria-pressed={item.id === organization.id}
                disabled={busy}
                onClick={() => {
                  close();
                  if (item.id !== organization.id) void onSwitch(item.id);
                }}
              >
                <span className="organization-option-avatar">
                  {item.name.trim().charAt(0).toLocaleUpperCase() || (
                    <Building2 size={18} />
                  )}
                </span>
                <span className="organization-option-text">
                  <strong>{item.name}</strong>
                  <small>{roleLabel(item)}</small>
                </span>
                {item.id === organization.id && (
                  <Check size={17} aria-hidden="true" />
                )}
              </button>
            ))}
            {!organizations.length && (
              <p className="organization-search-empty">
                Организация не найдена
              </p>
            )}
          </div>
          <div className="organization-popover-footer">
            <p>Чеки, деньги и статистика относятся к выбранной организации.</p>
            <a
              href="#organizations"
              onClick={() => {
                setOpen(false);
                onNavigate();
              }}
            >
              <Building2 size={16} aria-hidden="true" />
              Организации и участники<span aria-hidden="true">↗</span>
            </a>
          </div>
        </div>
      )}
    </div>
  );
}

function NavigationLink({
  item,
  route,
  onNavigate,
}: {
  item: NavigationItem;
  route: Route;
  onNavigate: () => void;
}) {
  const active = item.id === route;
  return (
    <a
      href={`#${item.id}`}
      className={`navigation-link ${active ? "active" : ""}`}
      aria-current={active ? "page" : undefined}
      onClick={onNavigate}
    >
      <item.icon size={17} strokeWidth={1.7} aria-hidden="true" />
      <span>{item.label}</span>
      {item.id === "assistant" && (
        <span className="navigation-ai-badge">AI</span>
      )}
    </a>
  );
}

export default function Sidebar({
  user,
  organization,
  route,
  open,
  busy,
  logoutPending,
  menuButton,
  brand,
  onClose,
  onSwitch,
  onLogout,
}: {
  user: User;
  organization: Organization;
  route: Route;
  open: boolean;
  busy: boolean;
  logoutPending: boolean;
  menuButton: RefObject<HTMLButtonElement | null>;
  brand: ReactNode;
  onClose: () => void;
  onSwitch: (id: string) => Promise<void>;
  onLogout: () => void;
}) {
  const activeGroup = navigationGroupFor(route);
  const [expandedGroups, setExpandedGroups] = useState<string[]>([
    activeGroup ?? "money",
  ]);
  const [previousRoute, setPreviousRoute] = useState(route);
  const closeButton = useRef<HTMLButtonElement>(null);
  const sidebar = useRef<HTMLElement>(null);
  const isAdmin = organization.role === "admin";
  // Keep the current page visible even when navigation comes from a page shortcut.
  if (route !== previousRoute) {
    setPreviousRoute(route);
    if (activeGroup && !expandedGroups.includes(activeGroup))
      setExpandedGroups([...expandedGroups, activeGroup]);
  }

  useEffect(() => {
    if (!open) return;
    const trigger = menuButton.current;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    closeButton.current?.focus({ preventScroll: true });
    return () => {
      document.body.style.overflow = previousOverflow;
      if (trigger?.isConnected && trigger.getClientRects().length)
        trigger.focus();
    };
  }, [open, menuButton]);

  function onKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (!open) return;
    if (event.key === "Escape") {
      event.preventDefault();
      onClose();
    }
    if (event.key !== "Tab") return;
    const focusable = Array.from(
      sidebar.current?.querySelectorAll<HTMLElement>(
        "a[href], button:not(:disabled), input:not(:disabled)",
      ) ?? [],
    ).filter((item) => item.getClientRects().length > 0);
    const first = focusable[0];
    const last = focusable.at(-1);
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last?.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first?.focus();
    }
  }

  return (
    <>
      {open && (
        <button
          className="sidebar-overlay"
          aria-label="Закрыть меню"
          tabIndex={-1}
          onClick={onClose}
        />
      )}
      <aside
        ref={sidebar}
        id="app-navigation"
        className={`sidebar ${open ? "open" : ""}`}
        aria-label="Навигация Finora"
        onKeyDown={onKeyDown}
      >
        <div className="sidebar-brand">
          <a
            className="sidebar-home"
            href="#overview"
            aria-label="Finora — обзор финансов"
            onClick={onClose}
          >
            {brand}
          </a>
          <button
            ref={closeButton}
            className="icon-button mobile-only sidebar-close"
            aria-label="Закрыть меню"
            onClick={onClose}
          >
            <X size={20} />
          </button>
        </div>
        <OrganizationSwitcher
          user={user}
          organization={organization}
          busy={busy}
          onSwitch={onSwitch}
          onNavigate={onClose}
        />
        <nav className="sidebar-navigation" aria-label="Основная навигация">
          <NavigationLink
            item={overviewLink}
            route={route}
            onNavigate={onClose}
          />
          <div className="navigation-section-label">Рабочее пространство</div>
          {navigationGroups.map((group) => {
            const items = group.items.filter((item) =>
              canAccessRoute(item.id, isAdmin),
            );
            if (!items.length) return null;
            const expanded = expandedGroups.includes(group.id);
            const current = activeGroup === group.id;
            return (
              <div
                className={`navigation-group ${expanded ? "expanded" : ""} ${current ? "current" : ""}`}
                key={group.id}
              >
                <button
                  className="navigation-group-toggle"
                  aria-expanded={expanded}
                  aria-controls={`navigation-${group.id}`}
                  onClick={() =>
                    setExpandedGroups((previous) =>
                      previous.includes(group.id)
                        ? previous.filter((id) => id !== group.id)
                        : [...previous, group.id],
                    )
                  }
                >
                  <span className="navigation-group-icon">
                    <group.icon
                      size={19}
                      strokeWidth={1.65}
                      aria-hidden="true"
                    />
                  </span>
                  <span className="navigation-group-text">
                    <strong>{group.label}</strong>
                    <small>
                      {!isAdmin && group.memberDescription
                        ? group.memberDescription
                        : group.description}
                    </small>
                  </span>
                  <ChevronDown
                    className="navigation-chevron"
                    size={14}
                    aria-hidden="true"
                  />
                </button>
                <div
                  className="navigation-group-items"
                  id={`navigation-${group.id}`}
                  hidden={!expanded}
                >
                  {items.map((item) => (
                    <NavigationLink
                      key={item.id}
                      item={item}
                      route={route}
                      onNavigate={onClose}
                    />
                  ))}
                </div>
              </div>
            );
          })}
        </nav>
        <div className="sidebar-bottom">
          <div className="sidebar-profile">
            <span className="sidebar-user-avatar">{user.name.charAt(0)}</span>
            <span className="sidebar-user-info">
              <strong title={user.name}>{user.name}</strong>
              <small>{user.username}</small>
            </span>
            <button
              className="icon-button"
              aria-label="Выйти из аккаунта"
              title="Выйти из аккаунта"
              disabled={logoutPending}
              onClick={onLogout}
            >
              <LogOut size={17} />
            </button>
          </div>
          <div className="sidebar-server">
            <ShieldCheck size={12} aria-hidden="true" />
            <span>Данные на вашем сервере</span>
            <i />
          </div>
        </div>
      </aside>
    </>
  );
}
