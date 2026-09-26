import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  ArrowUpRight,
  Building2,
  Pencil,
  Plus,
  RotateCcw,
  Search,
  ShieldCheck,
  Trash2,
  Users,
} from "lucide-react";
import { api, send, useAction } from "../api";
import { useApp } from "../context";
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
  counted,
} from "../ui";
import { ManagementTabs } from "../ManagementTabs";

type ManagedOrganization = {
  id: string;
  name: string;
  role: "admin" | "user" | null;
  can_manage: boolean;
  deleted_at: string | null;
  members_count: number;
  receipts_count: number;
};
type Member = {
  id: string;
  user_id: string;
  username: string;
  name: string;
  role: "admin" | "user";
  is_active: boolean;
};
export default function Organizations() {
  const { organization, user, switchOrganization, toast } = useApp();
  const [status, setStatus] = useState("active");
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState(organization.id);
  const [editing, setEditing] = useState<ManagedOrganization | "new" | null>(
    null,
  );
  const [removing, setRemoving] = useState<ManagedOrganization | null>(null);
  const list = useQuery({
    queryKey: ["organization-management", status],
    queryFn: ({ signal }) =>
      api<ManagedOrganization[]>(`/organizations/manage?status=${status}`, {
        signal,
      }),
  });
  const visible =
    list.data?.filter((org) =>
      org.name.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()),
    ) ?? [];
  const active = visible.find((org) => org.id === selected) ?? visible[0];
  const canCreate =
    user.is_server_admin ||
    user.organizations.some((org) => org.role === "admin") ||
    list.data?.some((org) => org.can_manage);
  const remove = useAction(
    () => send(`/organizations/${removing!.id}`, undefined, "DELETE"),
    () => {
      setRemoving(null);
      toast("Организация перемещена в корзину. История сохранена.");
    },
  );
  const restore = useAction(
    (org: ManagedOrganization) => send(`/organizations/${org.id}/restore`),
    () => {
      toast("Организация восстановлена вместе с историей и участниками");
      setStatus("active");
    },
  );
  return (
    <>
      <PageHeading
        eyebrow="ПРОСТРАНСТВА И ДОСТУП"
        title="Организации"
        text="Выберите организацию, чтобы изменить название и настроить доступ. Финансы каждой организации хранятся отдельно."
        actions={
          canCreate && (
            <button
              className="button primary"
              onClick={() => setEditing("new")}
            >
              <Plus size={18} /> Создать организацию
            </button>
          )
        }
      />
      <ManagementTabs current="organizations" />
      <div className="management-layout">
        <section
          className="panel organization-directory"
          aria-label="Список организаций"
        >
          <div
            className="segmented management-status"
            aria-label="Состояние организаций"
          >
            <button
              className={status === "active" ? "selected" : ""}
              onClick={() => setStatus("active")}
            >
              Действующие
            </button>
            <button
              className={status === "deleted" ? "selected" : ""}
              onClick={() => setStatus("deleted")}
            >
              <Trash2 size={15} /> Корзина
            </button>
          </div>
          <label className="user-search">
            <Search size={18} />
            <input
              aria-label="Найти организацию"
              placeholder="Найти организацию"
              value={search}
              maxLength={100}
              onChange={(e) => setSearch(e.target.value)}
            />
          </label>
          <ErrorBox error={list.error} />
          {list.isPending ? (
            <Loading />
          ) : visible.length ? (
            <div className="organization-options">
              {visible.map((org) => (
                <button
                  key={org.id}
                  aria-pressed={active?.id === org.id}
                  className={`organization-option ${active?.id === org.id ? "selected" : ""}`}
                  onClick={() => setSelected(org.id)}
                >
                  <span className="management-symbol">
                    <Building2 size={22} />
                  </span>
                  <span>
                    <strong>{org.name}</strong>
                    <small>
                      {counted(org.members_count, [
                        "участник",
                        "участника",
                        "участников",
                      ])}{" "}
                      · {counted(org.receipts_count, ["чек", "чека", "чеков"])}
                    </small>
                  </span>
                  {org.id === organization.id && (
                    <span className="current-dot" title="Сейчас в учёте" />
                  )}
                </button>
              ))}
            </div>
          ) : (
            !list.error && (
              <Empty
                icon={<Building2 />}
                title={
                  search
                    ? "Ничего не найдено"
                    : status === "deleted"
                      ? "Корзина пуста"
                      : "Пока нет организаций"
                }
                text={
                  search
                    ? "Попробуйте другое название."
                    : status === "deleted"
                      ? "Здесь можно восстановить удалённые организации."
                      : "Создайте организацию или попросите администратора добавить вас."
                }
              />
            )
          )}
        </section>
        {active && (
          <section className="panel organization-detail" key={active.id}>
            <div className="organization-detail-heading">
              <span className="management-symbol large">
                <Building2 size={28} />
              </span>
              <div className="grow">
                <span className="eyebrow">
                  {active.deleted_at
                    ? "В КОРЗИНЕ"
                    : active.id === organization.id
                      ? "СЕЙЧАС В УЧЁТЕ"
                      : "ОРГАНИЗАЦИЯ"}
                </span>
                <h2>{active.name}</h2>
                <small>
                  {counted(active.receipts_count, [
                    "сохранённый чек",
                    "сохранённых чека",
                    "сохранённых чеков",
                  ])}
                </small>
              </div>
            </div>
            <div className="management-actions">
              {!active.deleted_at && active.role && (
                <button
                  className="button primary"
                  onClick={() => {
                    location.hash = "overview";
                    void switchOrganization(active.id);
                  }}
                >
                  <ArrowUpRight size={17} /> Открыть учёт
                </button>
              )}
              {!active.deleted_at && active.can_manage && (
                <button
                  className="button secondary"
                  onClick={() => setEditing(active)}
                >
                  <Pencil size={16} /> Переименовать
                </button>
              )}
            </div>
            {active.deleted_at ? (
              <>
                <div className="notice">
                  Удалена {new Date(active.deleted_at).toLocaleDateString("ru")}
                  . Чеки, оригиналы, операции и права участников сохранены.
                  После восстановления организация снова станет доступна
                  участникам.
                </div>
                <ErrorBox error={restore.error} />
                <button
                  className="button primary"
                  disabled={restore.isPending}
                  onClick={() => restore.mutate(active)}
                >
                  <RotateCcw size={18} /> Восстановить организацию
                </button>
              </>
            ) : (
              <>
                {active.can_manage ? (
                  <Members organizationId={active.id} />
                ) : (
                  <div className="notice">
                    <ShieldCheck size={18} /> Вы можете добавлять чеки и
                    смотреть статистику. Доступ участников настраивает
                    администратор.
                  </div>
                )}
                {active.can_manage && (
                  <div className="management-danger">
                    <div>
                      <strong>Удалить организацию</strong>
                      <small>
                        Скрыть у всех участников и переместить в корзину.
                        История останется доступна после восстановления.
                      </small>
                    </div>
                    <button
                      className="button secondary negative"
                      onClick={() => setRemoving(active)}
                    >
                      <Trash2 size={16} /> Удалить
                    </button>
                  </div>
                )}
              </>
            )}
          </section>
        )}
      </div>
      {editing && (
        <OrganizationName
          organization={editing === "new" ? null : editing}
          onCreated={(id) => {
            setStatus("active");
            setSearch("");
            setSelected(id);
          }}
          onClose={() => setEditing(null)}
        />
      )}
      {removing && (
        <Modal
          title="Переместить организацию в корзину?"
          description={`«${removing.name}» исчезнет из учёта у всех участников. Финансовая история и оригиналы чеков сохранятся. Организацию можно восстановить в разделе «Корзина».`}
          onClose={() => {
            if (!remove.isPending) setRemoving(null);
          }}
        >
          <ErrorBox error={remove.error} />
          <footer className="modal-footer">
            <button
              className="button secondary"
              disabled={remove.isPending}
              onClick={() => setRemoving(null)}
            >
              Отмена
            </button>
            <button
              className="button danger"
              disabled={remove.isPending}
              onClick={() => remove.mutate(undefined)}
            >
              {remove.isPending ? "Перемещаю…" : "Переместить в корзину"}
            </button>
          </footer>
        </Modal>
      )}
    </>
  );
}

function OrganizationName({
  organization,
  onCreated,
  onClose,
}: {
  organization: ManagedOrganization | null;
  onCreated: (id: string) => void;
  onClose: () => void;
}) {
  const [name, setName] = useState(organization?.name ?? "");
  const { toast } = useApp();
  const action = useAction(
    async () => {
      if (organization)
        await send(`/organizations/${organization.id}`, { name }, "PUT");
      else {
        const result = await send<{ id: string }>("/organizations", { name });
        onCreated(result.id);
      }
    },
    () => {
      toast(
        organization
          ? "Название обновлено"
          : "Организация создана. Можно добавить участников.",
      );
      onClose();
    },
  );
  return (
    <Modal
      title={organization ? "Название организации" : "Новая организация"}
      description={
        organization
          ? "Участники сразу увидят новое название."
          : "Отдельные счета, категории и история. Вы станете администратором."
      }
      onClose={() => {
        if (!action.isPending) onClose();
      }}
    >
      <Form onSubmit={() => action.mutate(undefined)}>
        <Field label="Название">
          <input
            autoFocus
            required
            maxLength={100}
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Например, Семья или Компания"
            disabled={action.isPending}
          />
        </Field>
        <ErrorBox error={action.error} />
        <footer className="modal-footer">
          <button
            type="button"
            className="button secondary"
            disabled={action.isPending}
            onClick={onClose}
          >
            Отмена
          </button>
          <Submit pending={action.isPending}>
            {organization ? "Сохранить название" : "Создать организацию"}
          </Submit>
        </footer>
      </Form>
    </Modal>
  );
}

function Members({ organizationId }: { organizationId: string }) {
  const { user, toast } = useApp();
  const [adding, setAdding] = useState(false);
  const [changing, setChanging] = useState<Member | null>(null);
  const [remove, setRemove] = useState<Member | null>(null);
  const members = useQuery({
    queryKey: ["managed-members", organizationId],
    queryFn: ({ signal }) =>
      api<Member[]>(`/organizations/${organizationId}/members`, { signal }),
  });
  const lastAdmin = (member: Member) =>
    member.role === "admin" &&
    member.is_active &&
    (members.data?.filter((row) => row.role === "admin" && row.is_active)
      .length ?? 0) <= 1;
  const revoke = useAction(
    () =>
      send(
        `/organizations/${organizationId}/members/${remove!.id}`,
        undefined,
        "DELETE",
      ),
    () => {
      setRemove(null);
      toast("Доступ к организации закрыт");
    },
  );
  return (
    <section className="managed-members">
      <div className="panel-heading">
        <h3>
          <Users size={18} /> Участники{" "}
          <small>{members.data?.length ?? ""}</small>
        </h3>
        <button className="button secondary" onClick={() => setAdding(true)}>
          <Plus size={16} /> Добавить
        </button>
      </div>
      <p className="management-hint">
        Участник добавляет чеки и смотрит статистику. Администратор также
        управляет финансами и доступом.
      </p>
      <ErrorBox error={members.error} />
      {members.isPending ? (
        <Loading />
      ) : (
        members.data?.map((member) => (
          <div className="managed-member" key={member.id}>
            <div className="grow">
              <strong>
                {member.name}
                {member.user_id === user.id ? " · вы" : ""}
              </strong>
              <small>
                @{member.username}
                {!member.is_active ? " · аккаунт заблокирован" : ""}
              </small>
            </div>
            <button
              className="role-button"
              onClick={() => setChanging(member)}
              aria-label={`Изменить роль: ${member.username}`}
              disabled={lastAdmin(member)}
              title={
                lastAdmin(member)
                  ? "Сначала назначьте ещё одного администратора"
                  : "Изменить роль"
              }
            >
              <Badge>
                {member.role === "admin" ? "Администратор" : "Участник"}
              </Badge>
              <Pencil size={13} />
            </button>
            <button
              className="icon-button danger-hover"
              onClick={() => setRemove(member)}
              aria-label={`Убрать из организации: ${member.username}`}
              disabled={lastAdmin(member)}
              title={
                lastAdmin(member)
                  ? "Последнего администратора нельзя убрать"
                  : "Убрать из организации"
              }
            >
              <Trash2 size={17} />
            </button>
          </div>
        ))
      )}
      {adding && (
        <MemberForm
          organizationId={organizationId}
          onClose={() => setAdding(false)}
        />
      )}
      {changing && (
        <MemberRole
          organizationId={organizationId}
          member={changing}
          onClose={() => setChanging(null)}
        />
      )}
      {remove && (
        <Modal
          title="Убрать из организации?"
          description={`${remove.name} потеряет доступ только к этой организации. Аккаунт, добавленные чеки и доступ к другим организациям сохранятся.`}
          onClose={() => {
            if (!revoke.isPending) setRemove(null);
          }}
        >
          <ErrorBox error={revoke.error} />
          <footer className="modal-footer">
            <button
              className="button secondary"
              disabled={revoke.isPending}
              onClick={() => setRemove(null)}
            >
              Отмена
            </button>
            <button
              className="button danger"
              disabled={revoke.isPending}
              onClick={() => revoke.mutate(undefined)}
            >
              Убрать участника
            </button>
          </footer>
        </Modal>
      )}
    </section>
  );
}
function MemberRole({
  organizationId,
  member,
  onClose,
}: {
  organizationId: string;
  member: Member;
  onClose: () => void;
}) {
  const [role, setRole] = useState(member.role);
  const action = useAction(
    () =>
      send(
        `/organizations/${organizationId}/members/${member.id}`,
        { role },
        "PUT",
      ),
    onClose,
  );
  return (
    <Modal
      title={`Доступ · ${member.name}`}
      description="Роль действует только в выбранной организации."
      onClose={() => {
        if (!action.isPending) onClose();
      }}
    >
      <Form onSubmit={() => action.mutate(undefined)}>
        <Field label="Роль">
          <select
            value={role}
            onChange={(e) => setRole(e.target.value as Member["role"])}
          >
            <option value="user">
              Участник — чеки, комментарии, статистика
            </option>
            <option value="admin">Администратор — финансы и участники</option>
          </select>
        </Field>
        <ErrorBox error={action.error} />
        <footer className="modal-footer">
          <button
            type="button"
            className="button secondary"
            disabled={action.isPending}
            onClick={onClose}
          >
            Отмена
          </button>
          <Submit pending={action.isPending}>Сохранить доступ</Submit>
        </footer>
      </Form>
    </Modal>
  );
}
function MemberForm({
  organizationId,
  onClose,
}: {
  organizationId: string;
  onClose: () => void;
}) {
  const [mode, setMode] = useState("existing");
  const [username, setUsername] = useState("");
  const [name, setName] = useState("");
  const [password, setPassword] = useState("");
  const [role, setRole] = useState("user");
  const action = useAction(
    () =>
      send(`/organizations/${organizationId}/members`, {
        username,
        name,
        password: mode === "new" ? password : null,
        role,
      }),
    onClose,
  );
  return (
    <Modal
      title="Добавить участника"
      description="Существующему пользователю достаточно одного логина для всех его организаций."
      onClose={() => {
        if (!action.isPending) onClose();
      }}
    >
      <Form onSubmit={() => action.mutate(undefined)}>
        <div className="segmented">
          <button
            type="button"
            className={mode === "existing" ? "selected" : ""}
            onClick={() => setMode("existing")}
          >
            Уже есть аккаунт
          </button>
          <button
            type="button"
            className={mode === "new" ? "selected" : ""}
            onClick={() => setMode("new")}
          >
            Новый пользователь
          </button>
        </div>
        <Field label="Логин">
          <input
            autoFocus
            required
            minLength={3}
            maxLength={80}
            pattern="[a-zA-Z0-9_.@-]+"
            autoComplete="off"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />
        </Field>
        {mode === "new" && (
          <>
            <Field label="Имя">
              <input
                required
                maxLength={100}
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
            </Field>
            <Field
              label="Пароль"
              hint="Не менее 12 символов. Передайте пароль пользователю лично."
            >
              <input
                type="password"
                autoComplete="new-password"
                required
                minLength={12}
                maxLength={256}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </Field>
          </>
        )}
        <Field label="Роль в этой организации">
          <select value={role} onChange={(e) => setRole(e.target.value)}>
            <option value="user">
              Пользователь — чеки, комментарии, статистика
            </option>
            <option value="admin">Администратор — полный доступ</option>
          </select>
        </Field>
        <ErrorBox error={action.error} />
        <footer className="modal-footer">
          <button type="button" className="button secondary" onClick={onClose}>
            Отмена
          </button>
          <Submit pending={action.isPending}>Добавить участника</Submit>
        </footer>
      </Form>
    </Modal>
  );
}
