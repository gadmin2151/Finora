import { t, getLocale } from "../i18n";
import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  KeyRound,
  Building2,
  Ban,
  CheckCircle2,
  Trash2,
  RotateCcw,
  Pencil,
  Plus,
  Search,
  ShieldCheck,
  Users as UsersIcon,
} from "lucide-react";
import { api, send, useAction } from "../api";
import { useApp } from "../context";
import { ManagementTabs } from "../ManagementTabs";
import { Avatar } from "../Profile";
import {
  Badge,
  Empty,
  ErrorBox,
  Field,
  Form,
  Loading,
  Modal,
  PageHeading,
  Submit,
} from "../ui";

type Assignment = {
  organization_id: string;
  name?: string;
  deleted_at?: string | null;
  role: "admin" | "user";
};
type ManagedUser = {
  id: string;
  username: string;
  name: string;
  is_active: boolean;
  deleted_at: string | null;
  is_server_admin: boolean;
  avatar_url: string | null;
  sessions: number;
  created_at: string;
  memberships: Assignment[];
};
type OrganizationOption = { id: string; name: string };
type AuditEntry = {
  id: string;
  action: string;
  actor: string;
  created_at: string;
};
const auditLabels: Record<string, string> = {
  get "user.deleted"() {
    return t("Аккаунт перемещён в корзину");
  },
  get "user.restored"() {
    return t("Аккаунт восстановлен без включения входа");
  },
  get "user.created"() {
    return t("Аккаунт создан");
  },
  get "user.updated"() {
    return t("Доступ и профиль обновлены");
  },
  get "user.password_reset"() {
    return t("Пароль сброшен, сеансы отозваны");
  },
  get "profile.updated"() {
    return t("Имя обновлено");
  },
  get "profile.photo_updated"() {
    return t("Фото обновлено");
  },
  get "profile.photo_removed"() {
    return t("Фото удалено");
  },
};

export default function Users() {
  const { user } = useApp();
  const [search, setSearch] = useState("");
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("all");
  const [offset, setOffset] = useState(0);
  const [editing, setEditing] = useState<ManagedUser | "new" | null>(null);
  const [editorTab, setEditorTab] = useState<"profile" | "access">("profile");
  const [lifecycle, setLifecycle] = useState<{
    member: ManagedUser;
    action: "delete" | "restore" | "block" | "activate";
  } | null>(null);
  const [reset, setReset] = useState<ManagedUser | null>(null);
  useEffect(() => {
    const timer = window.setTimeout(() => {
      setQuery(search.trim());
      setOffset(0);
    }, 250);
    return () => window.clearTimeout(timer);
  }, [search]);
  const list = useQuery({
    queryKey: ["admin-users", query, status, offset],
    enabled: Boolean(user.is_server_admin),
    queryFn: ({ signal }) =>
      api<{ items: ManagedUser[]; total: number }>(
        `/admin/users?${new URLSearchParams({ q: query, status, offset: String(offset) })}`,
        { signal },
      ),
  });
  if (!user.is_server_admin) return null;
  return (
    <>
      <PageHeading
        eyebrow={t("ЛЮДИ И ДОСТУП")}
        title={t("Пользователи")}
        text={t(
          "Все аккаунты сервера. Организации и права каждого человека — в одном месте.",
        )}
        actions={
          <button
            className="button primary"
            onClick={() => {
              setEditorTab("profile");
              setEditing("new");
            }}
          >
            <Plus size={18} /> {t("Новый пользователь")}
          </button>
        }
      />
      <ManagementTabs current="users" />
      <div className="notice">
        <ShieldCheck size={20} />
        <span>
          {t(
            "Вы — владелец сервера. Сброс пароля и блокировка завершают сеансы пользователя на всех устройствах.",
          )}
        </span>
      </div>
      <section className="panel user-directory">
        <div className="user-toolbar">
          <label className="user-search">
            <Search size={19} />
            <input
              aria-label={t("Найти пользователя")}
              placeholder={t("Имя или логин")}
              value={search}
              maxLength={80}
              onChange={(event) => setSearch(event.target.value)}
            />
          </label>
          <select
            aria-label={t("Статус аккаунта")}
            value={status}
            onChange={(event) => {
              setStatus(event.target.value);
              setOffset(0);
            }}
          >
            <option value="all">{t("Все пользователи")}</option>
            <option value="active">{t("Активные")}</option>
            <option value="blocked">{t("Заблокированные")}</option>
            <option value="deleted">{t("Корзина")}</option>
          </select>
        </div>
        <ErrorBox error={list.error} />
        {list.isPending ? (
          <Loading text={t("Загружаем пользователей…")} />
        ) : list.data?.items.length ? (
          <>
            <div className="user-grid">
              {list.data.items.map((member) => (
                <article
                  className={`user-card ${member.is_active ? "" : "blocked"}`}
                  key={member.id}
                >
                  <div className="user-card-heading">
                    <Avatar user={member} />
                    <div className="grow">
                      <strong>
                        {member.name}
                        {member.id === user.id ? t(" · вы") : ""}
                      </strong>
                      <small>@{member.username}</small>
                    </div>
                    <Badge>
                      {member.deleted_at
                        ? t("В корзине")
                        : member.is_active
                          ? t("Активен")
                          : t("Заблокирован")}
                    </Badge>
                  </div>
                  {member.is_server_admin && (
                    <span className="owner-label">
                      <ShieldCheck size={14} /> {t("Владелец сервера")}
                    </span>
                  )}
                  <div className="user-memberships">
                    {member.memberships.length ? (
                      member.memberships.map((org) => (
                        <div key={org.organization_id}>
                          <span>
                            {org.name}
                            {org.deleted_at ? t(" · в корзине") : ""}
                          </span>
                          <small>
                            {org.role === "admin"
                              ? t("Администратор")
                              : t("Участник")}
                          </small>
                        </div>
                      ))
                    ) : (
                      <small>{t("Организации пока не назначены")}</small>
                    )}
                  </div>
                  <small>
                    {t(
                      "Активных сеансов: {0} · с {1}",
                      member.sessions,
                      new Date(member.created_at).toLocaleDateString(
                        getLocale(),
                      ),
                    )}
                  </small>
                  <div className="user-card-actions management-user-actions">
                    {member.deleted_at ? (
                      <button
                        className="button secondary"
                        onClick={() =>
                          setLifecycle({ member, action: "restore" })
                        }
                      >
                        <RotateCcw size={16} /> {t("Восстановить")}
                      </button>
                    ) : (
                      <>
                        <button
                          className="button secondary"
                          onClick={() => {
                            setEditorTab("profile");
                            setEditing(member);
                          }}
                        >
                          <Pencil size={16} /> {t("Профиль")}
                        </button>
                        <button
                          className="button secondary"
                          onClick={() => {
                            setEditorTab("access");
                            setEditing(member);
                          }}
                        >
                          <Building2 size={16} /> {t("Организации")}
                        </button>
                        <button
                          className="button secondary"
                          disabled={member.id === user.id}
                          title={
                            member.id === user.id
                              ? t("Свой пароль меняется в настройках профиля")
                              : t("Сбросить пароль")
                          }
                          onClick={() => setReset(member)}
                        >
                          <KeyRound size={16} /> {t("Пароль")}
                        </button>
                        {!member.is_server_admin && (
                          <>
                            <button
                              className="text-button"
                              onClick={() =>
                                setLifecycle({
                                  member,
                                  action: member.is_active
                                    ? "block"
                                    : "activate",
                                })
                              }
                            >
                              {member.is_active ? (
                                <Ban size={16} />
                              ) : (
                                <CheckCircle2 size={16} />
                              )}
                              {member.is_active
                                ? t("Заблокировать")
                                : t("Включить вход")}
                            </button>
                            <button
                              className="text-button negative"
                              onClick={() =>
                                setLifecycle({ member, action: "delete" })
                              }
                            >
                              <Trash2 size={16} /> {t("Удалить")}
                            </button>
                          </>
                        )}
                      </>
                    )}
                  </div>
                </article>
              ))}
            </div>
            <div className="user-pagination">
              <small>
                {t("Всего:")} {list.data.total}
              </small>
              <button
                className="button secondary"
                disabled={!offset}
                onClick={() => setOffset(Math.max(0, offset - 25))}
              >
                {t("Назад")}
              </button>
              <button
                className="button secondary"
                disabled={offset + 25 >= list.data.total}
                onClick={() => setOffset(offset + 25)}
              >
                {t("Далее")}
              </button>
            </div>
          </>
        ) : (
          !list.error && (
            <Empty
              icon={<UsersIcon />}
              title={t("Пользователи не найдены")}
              text={t("Попробуйте другое имя или измените фильтр.")}
            />
          )
        )}
      </section>
      {offset > 0 && list.data?.items.length === 0 && (
        <button className="button secondary" onClick={() => setOffset(0)}>
          {t("К началу списка")}
        </button>
      )}
      {editing && (
        <UserEditor
          initialTab={editorTab}
          member={editing === "new" ? null : editing}
          onClose={() => setEditing(null)}
        />
      )}
      {lifecycle && (
        <UserLifecycle
          {...lifecycle}
          onClose={() => setLifecycle(null)}
          onCompleted={() => {
            if (lifecycle.action === "restore") {
              setStatus("blocked");
              setOffset(0);
            }
            setLifecycle(null);
          }}
        />
      )}
      {reset && <ResetPassword member={reset} onClose={() => setReset(null)} />}
    </>
  );
}

function UserEditor({
  initialTab,
  member,
  onClose,
}: {
  initialTab: "profile" | "access";
  member: ManagedUser | null;
  onClose: () => void;
}) {
  const { toast } = useApp();
  const [tab, setTab] = useState(initialTab);
  const [name, setName] = useState(member?.name ?? "");
  const [username, setUsername] = useState(member?.username ?? "");
  const [password, setPassword] = useState("");
  const [repeat, setRepeat] = useState("");
  const [active, setActive] = useState(member?.is_active ?? true);
  const [assignments, setAssignments] = useState<Assignment[]>(
    member?.memberships ?? [],
  );
  const [filter, setFilter] = useState("");
  const options = useQuery({
    queryKey: ["admin-organizations"],
    queryFn: ({ signal }) =>
      api<OrganizationOption[]>("/admin/organizations", { signal }),
  });
  const audit = useQuery({
    queryKey: ["user-audit", member?.id],
    enabled: Boolean(member),
    queryFn: ({ signal }) =>
      api<AuditEntry[]>(`/admin/users/${member?.id}/audit`, { signal }),
  });
  const save = useAction(
    async () => {
      if (!member && password !== repeat)
        throw new Error(t("Пароли не совпадают"));
      const body = {
        name,
        is_active: active,
        memberships: assignments.map(({ organization_id, role }) => ({
          organization_id,
          role,
        })),
      };
      return member
        ? send(`/admin/users/${member.id}`, body, "PUT")
        : send("/admin/users", { ...body, username, password });
    },
    () => {
      toast(
        member ? t("Доступ пользователя обновлён") : t("Пользователь создан"),
      );
      onClose();
    },
  );
  return (
    <Modal
      title={
        member
          ? t("Управление · {0}", member.username)
          : t("Новый пользователь")
      }
      description={t("Права действуют отдельно в каждой организации.")}
      onClose={() => {
        if (!save.isPending) onClose();
      }}
      wide
    >
      {member && (
        <div className="segmented editor-tabs">
          <button
            className={tab === "profile" ? "selected" : ""}
            onClick={() => setTab("profile")}
          >
            {t("Профиль")}
          </button>
          <button
            className={tab === "access" ? "selected" : ""}
            onClick={() => setTab("access")}
          >
            {t("Организации и доступ")}
          </button>
        </div>
      )}
      <Form onSubmit={() => save.mutate(undefined)}>
        <fieldset disabled={save.isPending}>
          <section hidden={Boolean(member) && tab !== "profile"}>
            <div className="form-grid">
              <Field label={t("Имя")}>
                <input
                  value={name}
                  required
                  maxLength={100}
                  onChange={(event) => setName(event.target.value)}
                  autoComplete="off"
                />
              </Field>
              <Field label={t("Логин")}>
                <input
                  value={username}
                  required
                  minLength={3}
                  maxLength={80}
                  pattern="[a-zA-Z0-9_.@-]+"
                  disabled={Boolean(member)}
                  onChange={(event) => setUsername(event.target.value)}
                  autoComplete="off"
                />
              </Field>
            </div>
            {!member && (
              <div className="form-grid">
                <Field label={t("Пароль · от 12 символов")}>
                  <input
                    type="password"
                    autoComplete="new-password"
                    required
                    minLength={12}
                    maxLength={256}
                    value={password}
                    onChange={(event) => setPassword(event.target.value)}
                  />
                </Field>
                <Field label={t("Повторите пароль")}>
                  <input
                    type="password"
                    autoComplete="new-password"
                    required
                    value={repeat}
                    onChange={(event) => setRepeat(event.target.value)}
                  />
                </Field>
              </div>
            )}
          </section>
          <section hidden={Boolean(member) && tab !== "access"}>
            <label className="account-active">
              <input
                type="checkbox"
                checked={active}
                disabled={member?.is_server_admin}
                onChange={(event) => setActive(event.target.checked)}
              />
              <span>{t("Доступ к аккаунту включён")}</span>
            </label>
            {!active && (
              <div className="notice">
                {t(
                  "Пользователь не сможет входить. При сохранении все его сеансы будут завершены. Чеки и история сохранятся.",
                )}
              </div>
            )}
            <h3>
              {t("Организации ·")}{" "}
              {assignments.filter((org) => !org.deleted_at).length}
            </h3>
            {assignments.some((org) => org.deleted_at) && (
              <p className="management-hint">
                {t(
                  "Доступ к организациям в корзине сохранён и вернётся после их восстановления.",
                )}
              </p>
            )}
            <input
              aria-label={t("Найти организацию для пользователя")}
              placeholder={t("Найти организацию")}
              value={filter}
              onChange={(event) => setFilter(event.target.value)}
            />
            <ErrorBox error={options.error} />
            {options.isPending ? (
              <Loading />
            ) : (
              <div className="user-org-picker">
                {options.data
                  ?.filter((org) =>
                    org.name
                      .toLocaleLowerCase()
                      .includes(filter.toLocaleLowerCase()),
                  )
                  .map((org) => {
                    const selected = assignments.find(
                      (item) => item.organization_id === org.id,
                    );
                    return (
                      <div
                        className={`user-org-choice ${selected ? "selected" : ""}`}
                        key={org.id}
                      >
                        <label>
                          <input
                            type="checkbox"
                            checked={Boolean(selected)}
                            onChange={(event) =>
                              setAssignments(
                                event.target.checked
                                  ? [
                                      ...assignments,
                                      { organization_id: org.id, role: "user" },
                                    ]
                                  : assignments.filter(
                                      (item) => item.organization_id !== org.id,
                                    ),
                              )
                            }
                          />
                          <strong>{org.name}</strong>
                        </label>
                        {selected && (
                          <select
                            aria-label={t("Роль: {0}", org.name)}
                            value={selected.role}
                            onChange={(event) =>
                              setAssignments(
                                assignments.map((item) =>
                                  item.organization_id === org.id
                                    ? {
                                        ...item,
                                        role: event.target
                                          .value as Assignment["role"],
                                      }
                                    : item,
                                ),
                              )
                            }
                          >
                            <option value="user">{t("Участник")}</option>
                            <option value="admin">{t("Администратор")}</option>
                          </select>
                        )}
                      </div>
                    );
                  })}
              </div>
            )}
            <small>
              {t(
                "Участник добавляет чеки, комментирует и смотрит статистику. Администратор управляет финансами и участниками организации.",
              )}
            </small>
            {!assignments.length && (
              <div className="notice">
                {t("Без организации пользователь не увидит финансовые данные.")}
              </div>
            )}
          </section>
        </fieldset>
        <ErrorBox error={save.error} />
        <footer className="modal-footer">
          <button
            type="button"
            className="button secondary"
            disabled={save.isPending}
            onClick={onClose}
          >
            {t("Отмена")}
          </button>
          <Submit
            pending={save.isPending}
            disabled={options.isPending || Boolean(options.error)}
          >
            {member ? t("Сохранить изменения") : t("Создать пользователя")}
          </Submit>
        </footer>
      </Form>
      {member && (
        <details className="account-history">
          <summary>{t("История изменений")}</summary>
          <ErrorBox error={audit.error} />
          {audit.isPending ? (
            <Loading />
          ) : audit.data?.length ? (
            audit.data.map((entry) => (
              <p key={entry.id}>
                <strong>
                  {auditLabels[entry.action] ?? t("Аккаунт обновлён")}
                </strong>
                <small>
                  {entry.actor} ·{" "}
                  {new Date(entry.created_at).toLocaleString(getLocale())}
                </small>
              </p>
            ))
          ) : (
            <small>{t("Пока нет изменений")}</small>
          )}
        </details>
      )}
    </Modal>
  );
}

function ResetPassword({
  member,
  onClose,
}: {
  member: ManagedUser;
  onClose: () => void;
}) {
  const { toast } = useApp();
  const [password, setPassword] = useState("");
  const [repeat, setRepeat] = useState("");
  const action = useAction(
    async () => {
      if (password !== repeat) throw new Error(t("Пароли не совпадают"));
      return send(`/admin/users/${member.id}/password`, { password });
    },
    () => {
      toast(t("Пароль обновлён. Все сеансы пользователя завершены."));
      onClose();
    },
  );
  return (
    <Modal
      title={t("Сбросить пароль · {0}", member.username)}
      description={t(
        "Пользователь выйдет из приложения на всех устройствах и сможет войти с новым паролем.",
      )}
      onClose={() => {
        if (!action.isPending) onClose();
      }}
    >
      <Form onSubmit={() => action.mutate(undefined)}>
        <Field label={t("Новый пароль · от 12 символов")}>
          <input
            type="password"
            autoComplete="new-password"
            required
            minLength={12}
            maxLength={256}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            disabled={action.isPending}
          />
        </Field>
        <Field label={t("Повторите пароль")}>
          <input
            type="password"
            autoComplete="new-password"
            required
            value={repeat}
            onChange={(event) => setRepeat(event.target.value)}
            disabled={action.isPending}
          />
        </Field>
        <ErrorBox error={action.error} />
        <Submit pending={action.isPending}>
          {t("Сбросить пароль и завершить сеансы")}
        </Submit>
      </Form>
    </Modal>
  );
}

function UserLifecycle({
  member,
  action,
  onClose,
  onCompleted,
}: {
  member: ManagedUser;
  action: "delete" | "restore" | "block" | "activate";
  onClose: () => void;
  onCompleted: () => void;
}) {
  const { toast } = useApp();
  const labels = {
    delete: [
      t("Удалить пользователя?"),
      t(
        "Аккаунт попадёт в корзину, вход будет закрыт, а все сеансы завершатся. Чеки, комментарии и история сохранят авторство. Восстановление доступно в корзине.",
      ),
      t("Переместить в корзину"),
      t("Пользователь перемещён в корзину"),
    ],
    restore: [
      t("Восстановить пользователя?"),
      t(
        "Аккаунт и назначения организаций восстановятся. Вход останется заблокированным: проверьте доступ и нажмите «Включить вход».",
      ),
      t("Восстановить"),
      t("Аккаунт восстановлен. Проверьте организации и включите вход."),
    ],
    block: [
      t("Заблокировать вход?"),
      t(
        "Пользователь выйдет на всех устройствах и не сможет войти до разблокировки. Его данные и права в организациях сохранятся.",
      ),
      t("Заблокировать"),
      t("Вход заблокирован, сеансы завершены"),
    ],
    activate: [
      t("Включить вход?"),
      t(
        "Пользователь сможет войти с текущим паролем и получит доступ к назначенным организациям.",
      ),
      t("Включить вход"),
      t("Вход в аккаунт разрешён"),
    ],
  }[action];
  const mutation = useAction(
    () =>
      action === "delete"
        ? send(`/admin/users/${member.id}`, undefined, "DELETE")
        : action === "restore"
          ? send(`/admin/users/${member.id}/restore`)
          : send(
              `/admin/users/${member.id}/status`,
              { is_active: action === "activate" },
              "PUT",
            ),
    () => {
      toast(labels[3]);
      onCompleted();
    },
  );
  return (
    <Modal
      title={`${labels[0]} · ${member.name}`}
      description={labels[1]}
      onClose={() => {
        if (!mutation.isPending) onClose();
      }}
    >
      <ErrorBox error={mutation.error} />
      <footer className="modal-footer">
        <button
          className="button secondary"
          disabled={mutation.isPending}
          onClick={onClose}
        >
          {t("Отмена")}
        </button>
        <button
          className={`button ${action === "delete" || action === "block" ? "danger" : "primary"}`}
          disabled={mutation.isPending}
          onClick={() => mutation.mutate(undefined)}
        >
          {mutation.isPending ? t("Сохраняю…") : labels[2]}
        </button>
      </footer>
    </Modal>
  );
}
