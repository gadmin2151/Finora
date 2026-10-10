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
import { t, useLanguage } from "../i18n";
import { ManagementTabs } from "../ManagementTabs";
import { Loading, Modal, PageHeading } from "../ui";
import {
  readSettings,
  writeSettings,
  failureKind,
  failureFields,
  type FailureKind,
} from "./api";
import { localizedCopy } from "./copy";
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
  useLanguage();
  const { groups: groupCopy, fields: fieldCopy } = localizedCopy();
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

  const load = useCallback(
    async (force = false) => {
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
    },
    [setDrafts],
  );
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
    fieldCopy[field.name]?.[0] ?? field.label;
  const description = (field: SettingsField) =>
    fieldCopy[field.name]?.[1] ?? field.description;
  const source = (field: SettingsField) =>
    ({
      override: t("Переопределено здесь"),
      environment: t("Окружение сервера"),
      default: t("По умолчанию"),
      disabled: t("Отключено"),
    })[field.source];
  const apply = (field: SettingsField) =>
    ({
      request: t("На следующем запросе"),
      worker_boundary: t("На границе следующей задачи"),
      restart: t("После перезапуска"),
    })[field.apply];
  const publicValue = (value: SettingsField["effective"]) =>
    typeof value === "boolean"
      ? value
        ? t("Включено")
        : t("Выключено")
      : value === null
        ? "—"
        : String(value);
  const validation = (code: ValidationCode) =>
    ({
      invalid: t("Проверьте значение этого поля."),
      range: t("Значение выходит за допустимые пределы."),
      empty: t("Для замены нужен новый ключ или корректный JSON до 64 КиБ."),
      confirmation: t("Подтвердите изменение способа получения ключа."),
    })[code];
  const failureText = failure
    ? {
        conflict: t(
          "Настройки уже изменились. Обновите данные перед повторным сохранением.",
        ),
        validation: t(
          "Настройки не сохранены: проверьте отмеченные поля и требования к значениям.",
        ),
        forbidden: t(
          "Действие запрещено. Нужны права администратора сервера и действующий сеанс.",
        ),
        authentication: t("Сеанс завершён. Войдите снова."),
        uncertain: t(
          "Результат не подтверждён. Обновите данные, чтобы проверить состояние на сервере.",
        ),
      }[failure]
    : "";
  const renderObservation = (name: string, value: RuntimeObservation) => (
    <span>
      {name}:{" "}
      {snapshot && isApplied(value, snapshot.revision)
        ? t("применено")
        : value.state === "unknown"
          ? t("нет подтверждения")
          : t("ожидает применения")}
      {value.revision !== null ? ` · ${t("рев.")} ${value.revision}` : ""}
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
                ? t("Настроено · значение скрыто")
                : t("Не настроено")
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
                <option value="keep">{t("Оставить без изменений")}</option>
                {field.actions.includes("replace") && (
                  <option value="replace">{t("Заменить")}</option>
                )}
                {field.actions.includes("inherit") && (
                  <option value="inherit">
                    {t("Использовать окружение / ADC")}
                  </option>
                )}
                {field.actions.includes("disable") && (
                  <option value="disable">
                    {t("Отключить ключ и fallback")}
                  </option>
                )}
              </select>
              {secret.action === "replace" && (
                <input
                  id={id}
                  aria-label={t("Новое значение: ") + label(field)}
                  type="password"
                  autoComplete="new-password"
                  spellCheck={false}
                  value={secret.value}
                  maxLength={65536}
                  disabled={!!busy}
                  aria-invalid={!!error}
                  aria-describedby={`${id}-help${error ? ` ${id}-error` : ""}`}
                  placeholder={t("Введите новое значение")}
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
                ? t("Fallback отключён")
                : field.configured
                  ? t("Ключ настроен. Сохранённое значение не показывается.")
                  : t("Ключ не настроен")}
            </small>
            {(secret.action === "inherit" || secret.action === "disable") && (
              <div className="system-confirm">
                <p>
                  {secret.action === "inherit"
                    ? t(
                        "Сохранённый override будет удалён. Сервис сможет использовать окружение сервера или Application Default Credentials.",
                      )
                    : t(
                        "Сохранённый override будет удалён, а получение ключа из окружения и ADC заблокировано.",
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
                  {t("Подтверждаю изменение источника ключа")}
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
                {current.value === true ? t("Включено") : t("Выключено")}
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
              {t("Использовать окружение или значение по умолчанию")}
            </label>
          </>
        )}
        <div className="system-meta">
          <span>
            {t("Источник")}: {source(field)}
          </span>
          {!field.secret && (
            <span>
              {t("По умолчанию")}: {publicValue(field.default)}
            </span>
          )}
          <span>
            {t("Применение")}: {apply(field)}
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
        {t("Системные настройки доступны только администратору сервера.")}
      </div>
    );
  return (
    <>
      <ManagementTabs current="system-settings" />
      <PageHeading
        eyebrow={t("АДМИНИСТРАТОР СЕРВЕРА")}
        title={t("Системные настройки")}
        text={t(
          "Firebase, AI и общие параметры этого сервера. Изменения не включают подписки пользователей и не меняют настройки организаций.",
        )}
        actions={
          <button
            type="button"
            className="button secondary"
            disabled={!!busy}
            onClick={refresh}
          >
            <RefreshCw size={17} />
            {t("Обновить")}
          </button>
        }
      />
      <div className="system-settings" aria-busy={!!busy}>
        {failure && (
          <div className="system-notice" role="alert">
            {failureText}
            {secretsCleared && (
              <p>
                {t(
                  "Новые ключи удалены из формы. При необходимости введите их снова.",
                )}
              </p>
            )}
          </div>
        )}
        {saved !== null && (
          <div className="system-notice" role="status">
            {t("Сохранено на сервере.")}{" "}
            {t("Применение API и обработчиком показано отдельно ниже.")}
            {snapshot &&
              saved.some((name) =>
                snapshot.groups.some((group) =>
                  group.fields.some(
                    (field) => field.name === name && field.apply === "restart",
                  ),
                ),
              ) && (
                <p>
                  {t(
                    "Для части изменённых параметров нужен перезапуск сервера.",
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
                {t("Ревизия настроек")}: {snapshot.revision}
              </strong>
              <p>{t("Ревизия на границе push-провайдера")}</p>
              {renderObservation("API", snapshot.runtime.api)}
              {renderObservation(t("Обработчик"), snapshot.runtime.worker)}
              <p>
                {t(
                  "AI, OCR и лимит загрузки применяются к следующему запросу. Наблюдения API и обработчика относятся к границе push-провайдера; они не подтверждают применение всем процессом или проверку внешнего соединения.",
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
                    {copy?.[0] ?? group.id}
                  </h2>
                  <p>{copy?.[1]}</p>
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
                          ? t("Сохраняем…")
                          : t("Сохранить раздел")}
                      </button>
                      <button
                        type="button"
                        className="button secondary"
                        disabled={!!busy || !draft}
                        onClick={() => cancel(group.id)}
                      >
                        {t("Отменить изменения")}
                      </button>
                      <span className="system-revision">
                        {t("На основе ревизии")}{" "}
                        {draft?.revision ?? snapshot.revision}
                      </span>
                    </div>
                  )}
                </form>
              );
            })}
            {organization?.id && (
              <div className="system-notice">
                {t(
                  "Ключи и выбранный AI-провайдер текущей организации настраиваются отдельно.",
                )}{" "}
                <button
                  type="button"
                  className="text-button"
                  onClick={() => navigate("settings")}
                >
                  {t("Настройки организации")}
                </button>
              </div>
            )}
          </>
        )}
      </div>
      {confirmRefresh && (
        <Modal
          title={t("Обновить системные настройки?")}
          description={t(
            "Несохранённые изменения и новые ключи будут удалены из формы.",
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
              {t("Отменить изменения и обновить")}
            </button>
            <button
              type="button"
              className="button secondary"
              onClick={() => setConfirmRefresh(false)}
            >
              {t("Продолжить редактирование")}
            </button>
          </div>
        </Modal>
      )}
    </>
  );
}
