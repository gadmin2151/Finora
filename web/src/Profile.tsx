import { t } from "./i18n";
import { useRef, useState } from "react";
import { LanguageSettings } from "./Language";
import { AppearanceSettings } from "./Appearance";
import { Camera, Trash2 } from "lucide-react";
import { queryClient, send, useAction } from "./api";
import { useApp } from "./context";
import type { User } from "./types";
import { ErrorBox, Field, Form, Loading, Submit } from "./ui";

export function Avatar({
  user,
  large = false,
}: {
  user: Pick<User, "name" | "avatar_url">;
  large?: boolean;
}) {
  const [failed, setFailed] = useState<string | null>(null);
  // Only authenticated same-origin image routes are allowed, including after API responses change.
  const source =
    user.avatar_url?.startsWith("/api/users/") &&
    !user.avatar_url.includes("\\")
      ? user.avatar_url
      : null;
  return (
    <span className={`profile-avatar ${large ? "large" : ""}`}>
      {source && failed !== source ? (
        <img
          src={source}
          alt={t("Фото профиля: {0}", user.name)}
          onError={() => setFailed(source)}
        />
      ) : (
        <span aria-label={user.name}>
          {user.name.charAt(0).toLocaleUpperCase()}
        </span>
      )}
    </span>
  );
}

export function ProfileSettings() {
  const { user, toast } = useApp();
  const [name, setName] = useState(user.name);
  const picker = useRef<HTMLInputElement>(null);
  const save = useAction(
    async () => {
      const result = await send<User>("/auth/profile", { name }, "PUT");
      queryClient.setQueryData(["me"], result);
    },
    () => toast(t("Профиль сохранён")),
  );
  const photo = useAction(
    async (file: File | null) => {
      if (file && file.size > 5 * 1024 * 1024)
        throw new Error(t("Выберите фотографию размером до 5 МБ"));
      const body = new FormData();
      if (file) body.append("file", file);
      const result = await send<User>(
        "/auth/avatar",
        file ? body : undefined,
        file ? "POST" : "DELETE",
      );
      queryClient.setQueryData(["me"], result);
    },
    () => toast(t("Фото профиля обновлено")),
  );
  return (
    <>
      <LanguageSettings />
      <AppearanceSettings />
      <section className="panel profile-settings">
        <div className="profile-photo-row">
          <Avatar user={user} large />
          <div className="grow">
            <h2>{t("Ваше фото")}</h2>
            <p>{t("Так вас проще узнать в организации.")}</p>
            <small>
              {t(
                "JPEG, PNG, WebP или HEIC · до 5 МБ. Фото обрезается по центру.",
              )}
            </small>
            <div className="profile-photo-actions">
              <button
                className="button secondary"
                disabled={photo.isPending}
                onClick={() => picker.current?.click()}
              >
                <Camera size={17} />
                {user.avatar_url ? t("Изменить фото") : t("Добавить фото")}
              </button>
              {user.avatar_url && (
                <button
                  className="text-button"
                  disabled={photo.isPending}
                  onClick={() => photo.mutate(null)}
                >
                  <Trash2 size={16} /> {t("Удалить фото")}
                </button>
              )}
            </div>
            <input
              ref={picker}
              type="file"
              accept="image/jpeg,image/png,image/webp,image/heic,image/heif"
              hidden
              aria-label={t("Фотография профиля")}
              disabled={photo.isPending}
              onChange={(event) => {
                const file = event.target.files?.[0];
                if (file) photo.mutate(file);
                event.target.value = "";
              }}
            />
          </div>
        </div>
        {photo.isPending && <Loading text={t("Обновляем фото профиля…")} />}
        <ErrorBox error={photo.error ?? save.error} />
        <Form onSubmit={() => save.mutate(undefined)}>
          <Field label={t("Как к вам обращаться")}>
            <input
              value={name}
              onChange={(event) => setName(event.target.value)}
              required
              maxLength={100}
              autoComplete="name"
            />
          </Field>
          <Submit pending={save.isPending}>{t("Сохранить имя")}</Submit>
        </Form>
      </section>
    </>
  );
}
