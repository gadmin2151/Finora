import { useCallback, useEffect, useRef, useState } from "react";
import {
  Cloud,
  Cpu,
  HardDrive,
  RefreshCw,
  Save,
  ScanLine,
  ShieldCheck,
} from "lucide-react";
import { useApp } from "../context";
import { useLanguage } from "../i18n";
import { ManagementTabs } from "../ManagementTabs";
import { Loading, Modal, PageHeading } from "../ui";
import {
  readSettings,
  writeSettings,
  failureKind,
  failureFields,
  type FailureKind,
} from "./api";
import { groups as groupCopy, fields as fieldCopy } from "./copy";
import {
  buildPatch,
  clearSecrets,
  fieldDraft,
  hasChanges,
  isApplied,
  SettingsValidationError,
  type GroupDraft,
  type PublicDraft,
  type SecretDraft,
  type SettingsField,
  type SettingsGroup,
  type SettingsSnapshot,
  type ValidationCode,
  type RuntimeObservation,
} from "./model";
import "./settings.css";

const groupIcons = {
  firebase: Cloud,
  ai: Cpu,
  ocr: ScanLine,
  runtime: Cpu,
  infrastructure: HardDrive,
};
const emptyDraft = (revision: number): GroupDraft => ({
  revision,
  fields: {},
  secrets: {},
});
const secretDraft = (): SecretDraft => ({
  action: "keep",
  value: "",
  confirmed: false,
});
export default function SystemSettings() {
  const { user } = useApp();
  return <SettingsForm key={`${user.id}:${user.is_server_admin === true}`} />;
}
function SettingsForm() {
  const { user, organization, navigate } = useApp();
  const { language } = useLanguage();
  const ru = language === "ru";
  const text = (russian: string, english: string) => (ru ? russian : english);
  const [snapshot, setSnapshot] = useState<SettingsSnapshot | null>(null);
  const [drafts, setDrafts] = useState<Record<string, GroupDraft>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const [failure, setFailure] = useState<FailureKind | null>(null);
  const [fieldErrors, setFieldErrors] = useState<
    Record<string, ValidationCode>
  >({});
  const [saved, setSaved] = useState<string[] | null>(null);
  const [confirmRefresh, setConfirmRefresh] = useState(false);
  const [secretsCleared, setSecretsCleared] = useState(false);
  const operation = useRef(false);
  const epoch = useRef(0);
  const controller = useRef<AbortController | null>(null);
  const alive = useRef(true);
  const allowed = user.is_server_admin === true;

  const load = useCallback(async (force = false) => {
    if (operation.current && !force) return;
    controller.current?.abort();
    const request = ++epoch.current;
    const abort = new AbortController();
    controller.current = abort;
    operation.current = true;
    setBusy("load");
    setFailure(null);
    setSaved(null);
    setFieldErrors({});
    try {
      const result = await readSettings(abort.signal);
      if (!alive.current || epoch.current !== request) return;
      setSnapshot(result);
      setDrafts({});
      setSecretsCleared(false);
    } catch (error: unknown) {
      if (!alive.current || epoch.current !== request) return;
      setFailure(failureKind(error));
      setDrafts((previous) =>
        Object.fromEntries(
          Object.entries(previous).map(([id, draft]) => [
            id,
            clearSecrets(draft),
          ]),
        ),
      );
      setSecretsCleared(true);
    } finally {
      if (alive.current && epoch.current === request) {
        operation.current = false;
        setBusy(null);
      }
    }
  }, []);
  useEffect(() => {
    alive.current = true;
    const timer = window.setTimeout(() => {
      if (allowed) void load(true);
    }, 0);
    const requestEpoch = epoch;
    return () => {
      window.clearTimeout(timer);
      alive.current = false;
      ++requestEpoch.current;
      controller.current?.abort();
      operation.current = false;
    };
  }, [user.id, allowed, load]);

  const editPublic = (
    group: SettingsGroup,
    field: SettingsField,
    value: PublicDraft,
  ) => {
    if (operation.current || !snapshot) return;
    setSaved(null);
    setFailure(null);
    setFieldErrors((previous) => {
      const next = { ...previous };
      delete next[field.name];
      return next;
    });
    setDrafts((previous) => {
      const draft = previous[group.id] ?? emptyDraft(snapshot.revision);
      return {
        ...previous,
        [group.id]: {
          ...draft,
          fields: { ...draft.fields, [field.name]: value },
        },
      };
    });
  };
  const editSecret = (
    group: SettingsGroup,
    field: SettingsField,
    value: SecretDraft,
  ) => {
    if (operation.current || !snapshot) return;
    setSaved(null);
    setFailure(null);
    setSecretsCleared(false);
    setFieldErrors((previous) => {
      const next = { ...previous };
      delete next[field.name];
      return next;
    });
    setDrafts((previous) => {
      const draft = previous[group.id] ?? emptyDraft(snapshot.revision);
      return {
        ...previous,
        [group.id]: {
          ...draft,
          secrets: { ...draft.secrets, [field.name]: value },
        },
      };
    });
  };
  const cancel = (id: string) => {
    if (operation.current) return;
    setDrafts((previous) => {
      const next = { ...previous };
      delete next[id];
      return next;
    });
    setFieldErrors({});
    setFailure(null);
    setSaved(null);
  };
  const save = async (group: SettingsGroup) => {
    if (!allowed || operation.current || !snapshot || !drafts[group.id]) return;
    const draft = drafts[group.id];
    let patch;
    try {
      patch = buildPatch(group, draft);
    } catch (error: unknown) {
      setFieldErrors(
        error instanceof SettingsValidationError ? error.fields : {},
      );
      setFailure("validation");
      setSecretsCleared(Object.keys(draft.secrets).length > 0);
      setDrafts((previous) => ({
        ...previous,
        [group.id]: clearSecrets(draft),
      }));
      return;
    }
    if (!hasChanges(patch)) return;
    operation.current = true;
    const request = ++epoch.current;
    const abort = new AbortController();
    controller.current = abort;
    setBusy(group.id);
    setFailure(null);
    setFieldErrors({});
    setSaved(null);
    try {
      const result = await writeSettings(patch, abort.signal);
      if (!alive.current || epoch.current !== request) return;
      setSnapshot(result);
      setSaved(result.changed);
      setDrafts((previous) => {
        const next = { ...previous };
        delete next[group.id];
        return next;
      });
      setSecretsCleared(false);
    } catch (error: unknown) {
      if (!alive.current || epoch.current !== request) return;
      setFailure(failureKind(error));
      setFieldErrors(
        Object.fromEntries(
          failureFields(
            error,
            new Set(group.fields.map((field) => field.name)),
          ).map((name) => [name, "invalid" as const]),
        ),
      );
      setDrafts((previous) => ({
        ...previous,
        [group.id]: clearSecrets(draft),
      }));
      setSecretsCleared(Object.keys(draft.secrets).length > 0);
    } finally {
      if (alive.current && epoch.current === request) {
        operation.current = false;
        setBusy(null);
      }
    }
  };
  const refresh = () => {
    if (operation.current) return;
    if (Object.keys(drafts).length) setConfirmRefresh(true);
    else void load();
  };
  const label = (field: SettingsField) =>
    fieldCopy[field.name]?.[ru ? 0 : 1] ?? field.label;
  const description = (field: SettingsField) =>
    fieldCopy[field.name]?.[ru ? 2 : 3] ?? field.description;
  const source = (field: SettingsField) =>
    ({
      override: text("Переопределено здесь", "Override"),
      environment: text("Окружение сервера", "Server environment"),
      default: text("По умолчанию", "Default"),
      disabled: text("Отключено", "Disabled"),
    })[field.source];
  const apply = (field: SettingsField) =>
    ({
      request: text("На следующем запросе", "Next request"),
      worker_boundary: text(
        "На границе следующей задачи",
        "Next task boundary",
      ),
      restart: text("После перезапуска", "After restart"),
    })[field.apply];
  const publicValue = (value: SettingsField["effective"]) =>
    typeof value === "boolean"
      ? value
        ? text("Включено", "On")
        : text("Выключено", "Off")
      : value === null
        ? "—"
        : String(value);
  const validation = (code: ValidationCode) =>
    ({
      invalid: text(
        "Проверьте значение этого поля.",
        "Check this field's value.",
      ),
      range: text(
        "Значение выходит за допустимые пределы.",
        "Value is outside the allowed range.",
      ),
      empty: text(
        "Для замены нужен новый ключ или корректный JSON до 64 КиБ.",
        "Replacement needs a new key or valid JSON up to 64 KiB.",
      ),
      confirmation: text(
        "Подтвердите изменение способа получения ключа.",
        "Confirm the credential source change.",
      ),
    })[code];
  const failureText = failure
    ? {
        conflict: text(
          "Настройки уже изменились. Обновите данные перед повторным сохранением.",
          "Settings have changed. Refresh before saving again.",
        ),
        validation: text(
          "Настройки не сохранены: проверьте отмеченные поля и требования к значениям.",
          "Settings were not saved. Check the marked fields and value requirements.",
        ),
        forbidden: text(
          "Действие запрещено. Нужны права администратора сервера и действующий сеанс.",
          "Action denied. A server administrator and a valid session are required.",
        ),
        authentication: text(
          "Сеанс завершён. Войдите снова.",
          "Session expired. Sign in again.",
        ),
        uncertain: text(
          "Результат не подтверждён. Обновите данные, чтобы проверить состояние на сервере.",
          "Result is unconfirmed. Refresh to check the server state.",
        ),
      }[failure]
    : "";
  const renderObservation = (name: string, value: RuntimeObservation) => (
    <span>
      {name}:{" "}
      {snapshot && isApplied(value, snapshot.revision)
        ? text("применено", "applied")
        : value.state === "unknown"
          ? text("нет подтверждения", "unconfirmed")
          : text("ожидает применения", "pending")}
      {value.revision !== null
        ? ` · ${text("рев.", "rev.")} ${value.revision}`
        : ""}
    </span>
  );

  const renderField = (group: SettingsGroup, field: SettingsField) => {
    const id = `system-${field.name}`;
    const error = fieldErrors[field.name];
    const draft = drafts[group.id];
    const current = draft?.fields[field.name] ?? fieldDraft(field);
    const secret = draft?.secrets[field.name] ?? secretDraft();
    return (
      <div
        key={field.name}
        className={`system-field ${field.secret ? "secret" : ""} ${!field.editable ? "read-only" : ""}`}
        data-field={field.name}
      >
        <label htmlFor={field.secret ? `${id}-action` : id}>
          {label(field)}
        </label>
        <small id={`${id}-help`}>{description(field)}</small>
        {!field.editable ? (
          <p className="system-value">
            {field.secret
              ? field.configured
                ? text(
                    "Настроено · значение скрыто",
                    "Configured · value hidden",
                  )
                : text("Не настроено", "Not configured")
              : publicValue(field.effective)}
          </p>
        ) : field.secret ? (
          <>
            <div className="system-secret-controls">
              <select
                id={`${id}-action`}
                aria-describedby={`${id}-help`}
                value={secret.action}
                disabled={!!busy}
                onChange={(event) =>
                  editSecret(group, field, {
                    action: event.target.value as SecretDraft["action"],
                    value: "",
                    confirmed: false,
                  })
                }
              >
                <option value="keep">
                  {text("Оставить без изменений", "Keep unchanged")}
                </option>
                {field.actions.includes("replace") && (
                  <option value="replace">{text("Заменить", "Replace")}</option>
                )}
                {field.actions.includes("inherit") && (
                  <option value="inherit">
                    {text(
                      "Использовать окружение / ADC",
                      "Use environment / ADC",
                    )}
                  </option>
                )}
                {field.actions.includes("disable") && (
                  <option value="disable">
                    {text(
                      "Отключить ключ и fallback",
                      "Disable key and fallback",
                    )}
                  </option>
                )}
              </select>
              {secret.action === "replace" && (
                <input
                  id={id}
                  aria-label={
                    text("Новое значение: ", "New value: ") + label(field)
                  }
                  type="password"
                  autoComplete="new-password"
                  spellCheck={false}
                  value={secret.value}
                  maxLength={65536}
                  disabled={!!busy}
                  aria-invalid={!!error}
                  aria-describedby={`${id}-help${error ? ` ${id}-error` : ""}`}
                  placeholder={text(
                    "Введите новое значение",
                    "Enter a new value",
                  )}
                  onChange={(event) =>
                    editSecret(group, field, {
                      ...secret,
                      value: event.target.value,
                    })
                  }
                />
              )}
            </div>
            <small>
              {field.mode === "disable"
                ? text("Fallback отключён", "Fallback disabled")
                : field.configured
                  ? text(
                      "Ключ настроен. Сохранённое значение не показывается.",
                      "Credential configured. Saved values are never displayed.",
                    )
                  : text("Ключ не настроен", "Credential not configured")}
            </small>
            {(secret.action === "inherit" || secret.action === "disable") && (
              <div className="system-confirm">
                <p>
                  {secret.action === "inherit"
                    ? text(
                        "Сохранённый override будет удалён. Сервис сможет использовать окружение сервера или Application Default Credentials.",
                        "The saved override will be removed. The service may use its environment or Application Default Credentials.",
                      )
                    : text(
                        "Сохранённый override будет удалён, а получение ключа из окружения и ADC заблокировано.",
                        "The saved override will be removed and environment / ADC credentials will be suppressed.",
                      )}
                </p>
                <label className="system-check">
                  <input
                    type="checkbox"
                    checked={secret.confirmed}
                    disabled={!!busy}
                    onChange={(event) =>
                      editSecret(group, field, {
                        ...secret,
                        confirmed: event.target.checked,
                      })
                    }
                  />
                  {text(
                    "Подтверждаю изменение источника ключа",
                    "Confirm credential source change",
                  )}
                </label>
              </div>
            )}
          </>
        ) : (
          <>
            {field.type === "boolean" ? (
              <label className="system-check" htmlFor={id}>
                <input
                  id={id}
                  type="checkbox"
                  checked={current.value === true}
                  disabled={!!busy}
                  aria-describedby={`${id}-help`}
                  onChange={(event) =>
                    editPublic(group, field, {
                      mode: "override",
                      value: event.target.checked,
                    })
                  }
                />
                {current.value === true
                  ? text("Включено", "On")
                  : text("Выключено", "Off")}
              </label>
            ) : (
              <input
                id={id}
                type={field.type === "integer" ? "number" : "text"}
                inputMode={field.type === "integer" ? "numeric" : undefined}
                value={String(current.value)}
                min={field.minimum}
                max={field.maximum}
                step={field.type === "integer" ? 1 : undefined}
                maxLength={4096}
                disabled={!!busy}
                aria-invalid={!!error}
                aria-describedby={`${id}-help${error ? ` ${id}-error` : ""}`}
                onChange={(event) =>
                  editPublic(group, field, {
                    mode: "override",
                    value: event.target.value,
                  })
                }
              />
            )}
            <label className="system-check">
              <input
                type="checkbox"
                checked={current.mode === "inherit"}
                disabled={!!busy}
                onChange={(event) =>
                  editPublic(group, field, {
                    ...current,
                    mode: event.target.checked ? "inherit" : "override",
                  })
                }
              />
              {text(
                "Использовать окружение или значение по умолчанию",
                "Use environment or default",
              )}
            </label>
          </>
        )}
        <div className="system-meta">
          <span>
            {text("Источник", "Source")}: {source(field)}
          </span>
          {!field.secret && (
            <span>
              {text("По умолчанию", "Default")}: {publicValue(field.default)}
            </span>
          )}
          <span>
            {text("Применение", "Application")}: {apply(field)}
          </span>
        </div>
        {error && (
          <p className="system-error" id={`${id}-error`} role="alert">
            {validation(error)}
          </p>
        )}
      </div>
    );
  };

  if (!allowed)
    return (
      <div className="panel system-empty" role="alert">
        {text(
          "Системные настройки доступны только администратору сервера.",
          "System settings are available only to server administrators.",
        )}
      </div>
    );
  return (
    <>
      <ManagementTabs current="system-settings" />
      <PageHeading
        eyebrow={text("АДМИНИСТРАТОР СЕРВЕРА", "SERVER ADMINISTRATOR")}
        title={text("Системные настройки", "System settings")}
        text={text(
          "Firebase, AI и общие параметры этого сервера. Изменения не включают подписки пользователей и не меняют настройки организаций.",
          "Firebase, AI and shared server settings. Changes do not enable user subscriptions or alter organization preferences.",
        )}
        actions={
          <button
            type="button"
            className="button secondary"
            disabled={!!busy}
            onClick={refresh}
          >
            <RefreshCw size={17} />
            {text("Обновить", "Refresh")}
          </button>
        }
      />
      <div className="system-settings" aria-busy={!!busy}>
        {failure && (
          <div className="system-notice" role="alert">
            {failureText}
            {secretsCleared && (
              <p>
                {text(
                  "Новые ключи удалены из формы. При необходимости введите их снова.",
                  "New credentials were cleared from the form. Enter them again if needed.",
                )}
              </p>
            )}
          </div>
        )}
        {saved !== null && (
          <div className="system-notice" role="status">
            {text("Сохранено на сервере.", "Saved on the server.")}{" "}
            {text(
              "Применение API и обработчиком показано отдельно ниже.",
              "API and worker application is shown separately below.",
            )}
            {snapshot &&
              saved.some((name) =>
                snapshot.groups.some((group) =>
                  group.fields.some(
                    (field) => field.name === name && field.apply === "restart",
                  ),
                ),
              ) && (
                <p>
                  {text(
                    "Для части изменённых параметров нужен перезапуск сервера.",
                    "Some changed settings require a server restart.",
                  )}
                </p>
              )}
          </div>
        )}
        {!snapshot && busy && <Loading />}
        {snapshot && (
          <>
            <div className="panel system-status" role="status">
              <strong>
                {text("Ревизия настроек", "Settings revision")}:{" "}
                {snapshot.revision}
              </strong>
              <p>
                {text(
                  "Ревизия на границе push-провайдера",
                  "Push provider boundary revision",
                )}
              </p>
              {renderObservation("API", snapshot.runtime.api)}
              {renderObservation(
                text("Обработчик", "Worker"),
                snapshot.runtime.worker,
              )}
              <p>
                {text(
                  "AI, OCR и лимит загрузки применяются к следующему запросу. Наблюдения API и обработчика относятся к границе push-провайдера; они не подтверждают применение всем процессом или проверку внешнего соединения.",
                  "AI, OCR and the upload limit apply to the next request. API and worker observations describe the push provider boundary; they do not verify the whole process or an external connection.",
                )}
              </p>
            </div>
            {snapshot.groups.map((group) => {
              const copy = groupCopy[group.id];
              const Icon =
                groupIcons[group.id as keyof typeof groupIcons] ?? ShieldCheck;
              const draft = drafts[group.id];
              let dirty: boolean;
              try {
                dirty = !!draft && hasChanges(buildPatch(group, draft));
              } catch {
                dirty = true;
              }
              return (
                <form
                  key={group.id}
                  className="panel system-group"
                  data-group={group.id}
                  autoComplete="off"
                  noValidate
                  onSubmit={(event) => {
                    event.preventDefault();
                    void save(group);
                  }}
                >
                  <h2>
                    <Icon size={22} />
                    {copy?.[ru ? 0 : 1] ?? group.id}
                  </h2>
                  <p>{copy?.[ru ? 2 : 3]}</p>
                  <div className="system-fields">
                    {group.fields.map((field) => renderField(group, field))}
                  </div>
                  {group.fields.some((field) => field.editable) && (
                    <div className="system-actions">
                      <button
                        type="submit"
                        className="button primary"
                        disabled={!!busy || !dirty}
                      >
                        <Save size={17} />
                        {busy === group.id
                          ? text("Сохраняем…", "Saving…")
                          : text("Сохранить раздел", "Save section")}
                      </button>
                      <button
                        type="button"
                        className="button secondary"
                        disabled={!!busy || !draft}
                        onClick={() => cancel(group.id)}
                      >
                        {text("Отменить изменения", "Cancel changes")}
                      </button>
                      <span className="system-revision">
                        {text("На основе ревизии", "Based on revision")}{" "}
                        {draft?.revision ?? snapshot.revision}
                      </span>
                    </div>
                  )}
                </form>
              );
            })}
            {organization?.id && (
              <div className="system-notice">
                {text(
                  "Ключи и выбранный AI-провайдер текущей организации настраиваются отдельно.",
                  "The current organization's keys and AI provider are managed separately.",
                )}{" "}
                <button
                  type="button"
                  className="text-button"
                  onClick={() => navigate("settings")}
                >
                  {text("Настройки организации", "Organization settings")}
                </button>
              </div>
            )}
          </>
        )}
      </div>
      {confirmRefresh && (
        <Modal
          title={text(
            "Обновить системные настройки?",
            "Refresh system settings?",
          )}
          description={text(
            "Несохранённые изменения и новые ключи будут удалены из формы.",
            "Unsaved changes and new credentials will be cleared from the form.",
          )}
          onClose={() => setConfirmRefresh(false)}
        >
          <div className="system-actions">
            <button
              type="button"
              className="button primary"
              onClick={() => {
                setConfirmRefresh(false);
                setDrafts({});
                void load();
              }}
            >
              {text(
                "Отменить изменения и обновить",
                "Discard changes and refresh",
              )}
            </button>
            <button
              type="button"
              className="button secondary"
              onClick={() => setConfirmRefresh(false)}
            >
              {text("Продолжить редактирование", "Continue editing")}
            </button>
          </div>
        </Modal>
      )}
    </>
  );
}
