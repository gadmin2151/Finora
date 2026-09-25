import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  KeyRound,
  Pencil,
  Plus,
  Search,
  ShieldCheck,
  Users as UsersIcon,
} from "lucide-react";
import { api, send, useAction } from "../api";
import { useApp } from "../context";
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
  role: "admin" | "user";
};
type ManagedUser = {
  id: string;
  username: string;
  name: string;
  is_active: boolean;
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
  "user.created": "Аккаунт создан",
  "user.updated": "Доступ и профиль обновлены",
  "user.password_reset": "Пароль сброшен, сеансы отозваны",
  "profile.updated": "Имя обновлено",
  "profile.photo_updated": "Фото обновлено",
  "profile.photo_removed": "Фото удалено",
};

export default function Users() {
  const { user } = useApp();
  const [search, setSearch] = useState("");
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("all");
  const [offset, setOffset] = useState(0);
  const [editing, setEditing] = useState<ManagedUser | "new" | null>(null);
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
        eyebrow="ЛЮДИ И ДОСТУП"
        title="Ваша команда"
        text="Все аккаунты сервера. Организации и права каждого человека — в одном месте."
        actions={
          <button className="button primary" onClick={() => setEditing("new")}>
            <Plus size={18} /> Новый пользователь
          </button>
        }
      />
      <div className="notice">
        <ShieldCheck size={20} />
        <span>
          Вы — владелец сервера. Сброс пароля и блокировка завершают сеансы
          пользователя на всех устройствах.
        </span>
      </div>
      <section className="panel user-directory">
        <div className="user-toolbar">
          <label className="user-search">
            <Search size={19} />
            <input
              aria-label="Найти пользователя"
              placeholder="Имя или логин"
              value={search}
              maxLength={80}
              onChange={(event) => setSearch(event.target.value)}
            />
          </label>
          <select
            aria-label="Статус аккаунта"
            value={status}
            onChange={(event) => {
              setStatus(event.target.value);
              setOffset(0);
            }}
          >
            <option value="all">Все аккаунты</option>
            <option value="active">Активные</option>
            <option value="blocked">Заблокированные</option>
          </select>
        </div>
        <ErrorBox error={list.error} />
        {list.isPending ? (
          <Loading text="Загружаем пользователей…" />
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
                        {member.id === user.id ? " · вы" : ""}
                      </strong>
                      <small>@{member.username}</small>
                    </div>
                    <Badge>
                      {member.is_active ? "Активен" : "Заблокирован"}
                    </Badge>
                  </div>
                  {member.is_server_admin && (
                    <span className="owner-label">
                      <ShieldCheck size={14} /> Владелец сервера
                    </span>
                  )}
                  <div className="user-memberships">
                    {member.memberships.length ? (
                      member.memberships.map((org) => (
                        <div key={org.organization_id}>
                          <span>{org.name}</span>
                          <small>
                            {org.role === "admin"
                              ? "Администратор"
                              : "Участник"}
                          </small>
                        </div>
                      ))
                    ) : (
                      <small>Организации пока не назначены</small>
                    )}
                  </div>
                  <small>
                    {member.sessions} активных сеансов · с{" "}
                    {new Date(member.created_at).toLocaleDateString("ru")}
                  </small>
                  <div className="user-card-actions">
                    <button
                      className="button secondary"
                      onClick={() => setEditing(member)}
                    >
                      <Pencil size={16} /> Управлять
                    </button>
                    <button
                      className="icon-button"
                      aria-label={`Сбросить пароль: ${member.username}`}
                      title={
                        member.id === user.id
                          ? "Свой пароль меняется в настройках"
                          : "Сбросить пароль"
                      }
                      disabled={member.id === user.id}
                      onClick={() => setReset(member)}
                    >
                      <KeyRound size={19} />
                    </button>
                  </div>
                </article>
              ))}
            </div>
            <div className="user-pagination">
              <small>Всего: {list.data.total}</small>
              <button
                className="button secondary"
                disabled={!offset}
                onClick={() => setOffset(Math.max(0, offset - 25))}
              >
                Назад
              </button>
              <button
                className="button secondary"
                disabled={offset + 25 >= list.data.total}
                onClick={() => setOffset(offset + 25)}
              >
                Далее
              </button>
            </div>
          </>
        ) : (
          !list.error && (
            <Empty
              icon={<UsersIcon />}
              title="Пользователи не найдены"
              text="Попробуйте другое имя или измените фильтр."
            />
          )
        )}
      </section>
      {editing && (
        <UserEditor
          member={editing === "new" ? null : editing}
          onClose={() => setEditing(null)}
        />
      )}
      {reset && <ResetPassword member={reset} onClose={() => setReset(null)} />}
    </>
  );
}

function UserEditor({
  member,
  onClose,
}: {
  member: ManagedUser | null;
  onClose: () => void;
}) {
  const { toast } = useApp();
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
        throw new Error("Пароли не совпадают");
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
      toast(member ? "Доступ пользователя обновлён" : "Пользователь создан");
      onClose();
    },
  );
  return (
    <Modal
      title={member ? `Управление · ${member.username}` : "Новый пользователь"}
      description="Права действуют отдельно в каждой организации."
      onClose={() => {
        if (!save.isPending) onClose();
      }}
      wide
    >
      <Form onSubmit={() => save.mutate(undefined)}>
        <fieldset disabled={save.isPending}>
          <div className="form-grid">
            <Field label="Имя">
              <input
                value={name}
                required
                maxLength={100}
                onChange={(event) => setName(event.target.value)}
                autoComplete="off"
              />
            </Field>
            <Field label="Логин">
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
              <Field label="Пароль · от 12 символов">
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
              <Field label="Повторите пароль">
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
          <label className="account-active">
            <input
              type="checkbox"
              checked={active}
              disabled={member?.is_server_admin}
              onChange={(event) => setActive(event.target.checked)}
            />
            <span>Доступ к аккаунту включён</span>
          </label>
          {!active && (
            <div className="notice">
              Пользователь не сможет входить. При сохранении все его сеансы
              будут завершены. Чеки и история сохранятся.
            </div>
          )}
          <h3>Организации · {assignments.length}</h3>
          <input
            aria-label="Найти организацию для пользователя"
            placeholder="Найти организацию"
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
                          aria-label={`Роль: ${org.name}`}
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
                          <option value="user">Участник</option>
                          <option value="admin">Администратор</option>
                        </select>
                      )}
                    </div>
                  );
                })}
            </div>
          )}
          <small>
            Участник добавляет чеки, комментирует и смотрит статистику.
            Администратор управляет финансами и участниками организации.
          </small>
          {!assignments.length && (
            <div className="notice">
              Без организации пользователь не увидит финансовые данные.
            </div>
          )}
        </fieldset>
        <ErrorBox error={save.error} />
        <Submit
          pending={save.isPending}
          disabled={options.isPending || Boolean(options.error)}
        >
          Сохранить пользователя
        </Submit>
      </Form>
      {member && (
        <details className="account-history">
          <summary>История изменений</summary>
          <ErrorBox error={audit.error} />
          {audit.isPending ? (
            <Loading />
          ) : audit.data?.length ? (
            audit.data.map((entry) => (
              <p key={entry.id}>
                <strong>
                  {auditLabels[entry.action] ?? "Аккаунт обновлён"}
                </strong>
                <small>
                  {entry.actor} ·{" "}
                  {new Date(entry.created_at).toLocaleString("ru")}
                </small>
              </p>
            ))
          ) : (
            <small>Пока нет изменений</small>
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
      if (password !== repeat) throw new Error("Пароли не совпадают");
      return send(`/admin/users/${member.id}/password`, { password });
    },
    () => {
      toast("Пароль обновлён. Все сеансы пользователя завершены.");
      onClose();
    },
  );
  return (
    <Modal
      title={`Сбросить пароль · ${member.username}`}
      description="Пользователь выйдет из приложения на всех устройствах и сможет войти с новым паролем."
      onClose={() => {
        if (!action.isPending) onClose();
      }}
    >
      <Form onSubmit={() => action.mutate(undefined)}>
        <Field label="Новый пароль · от 12 символов">
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
        <Field label="Повторите пароль">
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
          Сбросить пароль и завершить сеансы
        </Submit>
      </Form>
    </Modal>
  );
}
