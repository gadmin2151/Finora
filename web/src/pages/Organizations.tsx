import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Building2, Plus, Users, Trash2 } from "lucide-react";
import { api, send, useAction } from "../api";
import { useApp } from "../context";
import {
  Badge,
  ErrorBox,
  Field,
  Form,
  Loading,
  Modal,
  PageHeading,
  Submit,
} from "../ui";

type Member = {
  id: string;
  user_id: string;
  username: string;
  name: string;
  role: "admin" | "user";
};
export default function Organizations() {
  const { organization, user, isAdmin, switchOrganization } = useApp();
  const [name, setName] = useState(organization.name);
  const [creating, setCreating] = useState(false);
  const [adding, setAdding] = useState(false);
  const [remove, setRemove] = useState<Member | null>(null);
  const members = useQuery({
    queryKey: ["members"],
    queryFn: () => api<Member[]>("/organizations/current/members"),
    enabled: isAdmin,
  });
  const rename = useAction(() =>
    send("/organizations/current", { name }, "PUT"),
  );
  const role = useAction((member: Member) =>
    send(
      `/organizations/current/members/${member.id}`,
      { role: member.role === "admin" ? "user" : "admin" },
      "PUT",
    ),
  );
  const revoke = useAction(
    () =>
      send(`/organizations/current/members/${remove?.id}`, undefined, "DELETE"),
    () => setRemove(null),
  );
  return (
    <>
      <PageHeading
        eyebrow="ОБЩЕЕ ПРОСТРАНСТВО"
        title="Организации и участники"
        text="Одна учётная запись — несколько организаций. История, счета и настройки каждой организации хранятся отдельно."
        actions={
          isAdmin && (
            <button
              className="button primary"
              onClick={() => setCreating(true)}
            >
              <Plus size={18} />
              Создать организацию
            </button>
          )
        }
      />
      <div className="organization-grid">
        {user.organizations.map((org) => (
          <button
            className={`panel organization-card ${org.id === organization.id ? "selected" : ""}`}
            key={org.id}
            onClick={() => {
              void switchOrganization(org.id);
            }}
          >
            <Building2 size={25} />
            <strong>{org.name}</strong>
            <Badge>
              {org.role === "admin" ? "Администратор" : "Пользователь"}
            </Badge>
            <small>
              {org.id === organization.id ? "Выбрана сейчас" : "Переключиться"}
            </small>
          </button>
        ))}
      </div>
      <div className="notice">
        Администратор управляет финансами, участниками и удалением. Пользователь
        добавляет чеки, оставляет комментарии и просматривает статистику. Права
        назначаются отдельно в каждой организации.
      </div>
      {isAdmin && (
        <>
          <section className="panel organization-settings">
            <Form onSubmit={() => rename.mutate(undefined)}>
              <Field label="Название выбранной организации">
                <input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  required
                  maxLength={100}
                />
              </Field>
              <Submit pending={rename.isPending}>Сохранить название</Submit>
            </Form>
          </section>
          <section className="panel">
            <div className="panel-heading">
              <h2>
                <Users size={20} /> Участники
              </h2>
              <button
                className="button secondary"
                onClick={() => setAdding(true)}
              >
                <Plus size={17} />
                Добавить участника
              </button>
            </div>
            <ErrorBox error={members.error ?? role.error ?? rename.error} />
            {members.isPending ? (
              <Loading />
            ) : (
              <div className="bill-list">
                {members.data?.map((member) => (
                  <div className="bill-row" key={member.id}>
                    <div className="grow">
                      <strong>
                        {member.name}
                        {member.user_id === user.id ? " · вы" : ""}
                      </strong>
                      <small>{member.username}</small>
                    </div>
                    <Badge>
                      {member.role === "admin"
                        ? "Администратор"
                        : "Пользователь"}
                    </Badge>
                    <button
                      className="text-button"
                      disabled={role.isPending}
                      onClick={() => role.mutate(member)}
                    >
                      {member.role === "admin"
                        ? "Сделать пользователем"
                        : "Сделать администратором"}
                    </button>
                    <button
                      className="icon-button danger-hover"
                      aria-label={`Убрать участника ${member.username}`}
                      onClick={() => setRemove(member)}
                    >
                      <Trash2 size={17} />
                    </button>
                  </div>
                ))}
              </div>
            )}
          </section>
        </>
      )}
      {creating && <CreateOrganization onClose={() => setCreating(false)} />}
      {adding && <MemberForm onClose={() => setAdding(false)} />}
      {remove && (
        <Modal
          title="Убрать участника?"
          description={`${remove.name} потеряет доступ к этой организации. Его учётная запись и доступ к другим организациям сохранятся.`}
          onClose={() => setRemove(null)}
        >
          <ErrorBox error={revoke.error} />
          <footer className="modal-footer">
            <button
              className="button secondary"
              onClick={() => setRemove(null)}
            >
              Отмена
            </button>
            <button
              className="button danger"
              disabled={revoke.isPending}
              onClick={() => revoke.mutate(undefined)}
            >
              Убрать из организации
            </button>
          </footer>
        </Modal>
      )}
    </>
  );
}

function CreateOrganization({ onClose }: { onClose: () => void }) {
  const [name, setName] = useState("");
  const { toast } = useApp();
  const action = useAction(
    () => send("/organizations", { name }),
    () => {
      toast("Организация создана. Выберите её в переключателе");
      onClose();
    },
  );
  return (
    <Modal
      title="Новая организация"
      description="Создадим отдельные счета, категории и историю. Вы станете администратором."
      onClose={onClose}
    >
      <Form onSubmit={() => action.mutate(undefined)}>
        <Field label="Название организации">
          <input
            autoFocus
            required
            maxLength={100}
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Например, Семья или Компания"
          />
        </Field>
        <ErrorBox error={action.error} />
        <footer className="modal-footer">
          <button type="button" className="button secondary" onClick={onClose}>
            Отмена
          </button>
          <Submit pending={action.isPending}>Создать</Submit>
        </footer>
      </Form>
    </Modal>
  );
}

function MemberForm({ onClose }: { onClose: () => void }) {
  const [mode, setMode] = useState("existing");
  const [username, setUsername] = useState("");
  const [name, setName] = useState("");
  const [password, setPassword] = useState("");
  const [role, setRole] = useState("user");
  const action = useAction(
    () =>
      send("/organizations/current/members", {
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
      onClose={onClose}
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
