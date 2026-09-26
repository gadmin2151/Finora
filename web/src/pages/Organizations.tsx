import { t, getLocale } from "../i18n";
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
      toast(t("Организация перемещена в корзину. История сохранена."));
    },
  );
  const restore = useAction(
    (org: ManagedOrganization) => send(`/organizations/${org.id}/restore`),
    () => {
      toast(t("Организация восстановлена вместе с историей и участниками"));
      setStatus("active");
    },
  );
  return (
    <>
      <PageHeading
        eyebrow={t("ПРОСТРАНСТВА И ДОСТУП")}
        title={t("Организации")}
        text={t(
          "Выберите организацию, чтобы изменить название и настроить доступ. Финансы каждой организации хранятся отдельно.",
        )}
        actions={
          canCreate && (
            <button
              className="button primary"
              onClick={() => setEditing("new")}
            >
              <Plus size={18} /> {t("Создать организацию")}
            </button>
          )
        }
      />
      <ManagementTabs current="organizations" />
      <div className="management-layout">
        <section
          className="panel organization-directory"
          aria-label={t("Список организаций")}
        >
          <div
            className="segmented management-status"
            aria-label={t("Состояние организаций")}
          >
            <button
              className={status === "active" ? "selected" : ""}
              onClick={() => setStatus("active")}
            >
              {t("Действующие")}
            </button>
            <button
              className={status === "deleted" ? "selected" : ""}
              onClick={() => setStatus("deleted")}
            >
              <Trash2 size={15} /> {t("Корзина")}
            </button>
          </div>
          <label className="user-search">
            <Search size={18} />
            <input
              aria-label={t("Найти организацию")}
              placeholder={t("Найти организацию")}
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
                        t("участник"),
                        t("участника"),
                        t("участников"),
                      ])}{" "}
                      ·{" "}
                      {counted(org.receipts_count, [
                        t("чек"),
                        t("чека"),
                        t("чеков"),
                      ])}
                    </small>
                  </span>
                  {org.id === organization.id && (
                    <span className="current-dot" title={t("Сейчас в учёте")} />
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
                    ? t("Ничего не найдено")
                    : status === "deleted"
                      ? t("Корзина пуста")
                      : t("Пока нет организаций")
                }
                text={
                  search
                    ? t("Попробуйте другое название.")
                    : status === "deleted"
                      ? t("Здесь можно восстановить удалённые организации.")
                      : t(
                          "Создайте организацию или попросите администратора добавить вас.",
                        )
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
                    ? t("В КОРЗИНЕ")
                    : active.id === organization.id
                      ? t("СЕЙЧАС В УЧЁТЕ")
                      : t("ОРГАНИЗАЦИЯ")}
                </span>
                <h2>{active.name}</h2>
                <small>
                  {counted(active.receipts_count, [
                    t("сохранённый чек"),
                    t("сохранённых чека"),
                    t("сохранённых чеков"),
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
                  <ArrowUpRight size={17} /> {t("Открыть учёт")}
                </button>
              )}
              {!active.deleted_at && active.can_manage && (
                <button
                  className="button secondary"
                  onClick={() => setEditing(active)}
                >
                  <Pencil size={16} /> {t("Переименовать")}
                </button>
              )}
            </div>
            {active.deleted_at ? (
              <>
                <div className="notice">
                  {t("Удалена")}{" "}
                  {new Date(active.deleted_at).toLocaleDateString(getLocale())}
                  {t(
                    ". Чеки, оригиналы, операции и права участников сохранены. После восстановления организация снова станет доступна участникам.",
                  )}
                </div>
                <ErrorBox error={restore.error} />
                <button
                  className="button primary"
                  disabled={restore.isPending}
                  onClick={() => restore.mutate(active)}
                >
                  <RotateCcw size={18} /> {t("Восстановить организацию")}
                </button>
              </>
            ) : (
              <>
                {active.can_manage ? (
                  <Members organizationId={active.id} />
                ) : (
                  <div className="notice">
                    <ShieldCheck size={18} />{" "}
                    {t(
                      "Вы можете добавлять чеки и смотреть статистику. Доступ участников настраивает администратор.",
                    )}
                  </div>
                )}
                {active.can_manage && (
                  <div className="management-danger">
                    <div>
                      <strong>{t("Удалить организацию")}</strong>
                      <small>
                        {t(
                          "Скрыть у всех участников и переместить в корзину. История останется доступна после восстановления.",
                        )}
                      </small>
                    </div>
                    <button
                      className="button secondary negative"
                      onClick={() => setRemoving(active)}
                    >
                      <Trash2 size={16} /> {t("Удалить")}
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
          title={t("Переместить организацию в корзину?")}
          description={t(
            "«{0}» исчезнет из учёта у всех участников. Финансовая история и оригиналы чеков сохранятся. Организацию можно восстановить в разделе «Корзина».",
            removing.name,
          )}
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
              {t("Отмена")}
            </button>
            <button
              className="button danger"
              disabled={remove.isPending}
              onClick={() => remove.mutate(undefined)}
            >
              {remove.isPending ? t("Перемещаю…") : t("Переместить в корзину")}
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
          ? t("Название обновлено")
          : t("Организация создана. Можно добавить участников."),
      );
      onClose();
    },
  );
  return (
    <Modal
      title={organization ? t("Название организации") : t("Новая организация")}
      description={
        organization
          ? t("Участники сразу увидят новое название.")
          : t(
              "Отдельные счета, категории и история. Вы станете администратором.",
            )
      }
      onClose={() => {
        if (!action.isPending) onClose();
      }}
    >
      <Form onSubmit={() => action.mutate(undefined)}>
        <Field label={t("Название")}>
          <input
            autoFocus
            required
            maxLength={100}
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t("Например, Семья или Компания")}
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
            {t("Отмена")}
          </button>
          <Submit pending={action.isPending}>
            {organization ? t("Сохранить название") : t("Создать организацию")}
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
      toast(t("Доступ к организации закрыт"));
    },
  );
  return (
    <section className="managed-members">
      <div className="panel-heading">
        <h3>
          <Users size={18} /> {t("Участники")}{" "}
          <small>{members.data?.length ?? ""}</small>
        </h3>
        <button className="button secondary" onClick={() => setAdding(true)}>
          <Plus size={16} /> {t("Добавить")}
        </button>
      </div>
      <p className="management-hint">
        {t(
          "Участник добавляет чеки и смотрит статистику. Администратор также управляет финансами и доступом.",
        )}
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
                {member.user_id === user.id ? t(" · вы") : ""}
              </strong>
              <small>
                @{member.username}
                {!member.is_active ? t(" · аккаунт заблокирован") : ""}
              </small>
            </div>
            <button
              className="role-button"
              onClick={() => setChanging(member)}
              aria-label={t("Изменить роль: {0}", member.username)}
              disabled={lastAdmin(member)}
              title={
                lastAdmin(member)
                  ? t("Сначала назначьте ещё одного администратора")
                  : t("Изменить роль")
              }
            >
              <Badge>
                {member.role === "admin" ? t("Администратор") : t("Участник")}
              </Badge>
              <Pencil size={13} />
            </button>
            <button
              className="icon-button danger-hover"
              onClick={() => setRemove(member)}
              aria-label={t("Убрать из организации: {0}", member.username)}
              disabled={lastAdmin(member)}
              title={
                lastAdmin(member)
                  ? t("Последнего администратора нельзя убрать")
                  : t("Убрать из организации")
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
          title={t("Убрать из организации?")}
          description={t(
            "{0} потеряет доступ только к этой организации. Аккаунт, добавленные чеки и доступ к другим организациям сохранятся.",
            remove.name,
          )}
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
              {t("Отмена")}
            </button>
            <button
              className="button danger"
              disabled={revoke.isPending}
              onClick={() => revoke.mutate(undefined)}
            >
              {t("Убрать участника")}
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
      title={t("Доступ · {0}", member.name)}
      description={t("Роль действует только в выбранной организации.")}
      onClose={() => {
        if (!action.isPending) onClose();
      }}
    >
      <Form onSubmit={() => action.mutate(undefined)}>
        <Field label={t("Роль")}>
          <select
            value={role}
            onChange={(e) => setRole(e.target.value as Member["role"])}
          >
            <option value="user">
              {t("Участник — чеки, комментарии, статистика")}
            </option>
            <option value="admin">
              {t("Администратор — финансы и участники")}
            </option>
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
            {t("Отмена")}
          </button>
          <Submit pending={action.isPending}>{t("Сохранить доступ")}</Submit>
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
      title={t("Добавить участника")}
      description={t(
        "Существующему пользователю достаточно одного логина для всех его организаций.",
      )}
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
            {t("Уже есть аккаунт")}
          </button>
          <button
            type="button"
            className={mode === "new" ? "selected" : ""}
            onClick={() => setMode("new")}
          >
            {t("Новый пользователь")}
          </button>
        </div>
        <Field label={t("Логин")}>
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
            <Field label={t("Имя")}>
              <input
                required
                maxLength={100}
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
            </Field>
            <Field
              label={t("Пароль")}
              hint={t(
                "Не менее 12 символов. Передайте пароль пользователю лично.",
              )}
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
        <Field label={t("Роль в этой организации")}>
          <select value={role} onChange={(e) => setRole(e.target.value)}>
            <option value="user">
              {t("Пользователь — чеки, комментарии, статистика")}
            </option>
            <option value="admin">{t("Администратор — полный доступ")}</option>
          </select>
        </Field>
        <ErrorBox error={action.error} />
        <footer className="modal-footer">
          <button type="button" className="button secondary" onClick={onClose}>
            {t("Отмена")}
          </button>
          <Submit pending={action.isPending}>{t("Добавить участника")}</Submit>
        </footer>
      </Form>
    </Modal>
  );
}
