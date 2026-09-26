import { t } from "./i18n";
import { useRef, useState } from "react";
import {
  ChevronLeft,
  ChevronRight,
  Download,
  FileImage,
  Plus,
  ZoomIn,
  ZoomOut,
} from "lucide-react";
import { send, useAction } from "./api";
import { useApp } from "./context";
import type { Receipt } from "./types";
import { ErrorBox, Modal } from "./ui";

export function ReceiptOriginals({ receipt }: { receipt: Receipt }) {
  const { user, isAdmin, toast } = useApp();
  const [opened, setOpened] = useState<number | null>(null);
  const [zoom, setZoom] = useState(false);
  const [failure, setFailure] = useState<Error | null>(null);
  const input = useRef<HTMLInputElement>(null);
  const canAdd =
    (isAdmin || receipt.created_by === user.id) && receipt.files.length < 16;
  const upload = useAction(
    async (files: File[]) => {
      if (files.length > 4)
        throw new Error(t("Выберите не более 4 изображений за раз"));
      if (files.some((file) => file.size > 15 * 1024 * 1024))
        throw new Error(t("Каждое изображение должно быть не больше 15 МБ"));
      const form = new FormData();
      files.forEach((file) => form.append("files", file));
      return send(`/receipts/${receipt.id}/originals`, form);
    },
    () => toast(t("Оригиналы сохранены. Товары и суммы не изменены.")),
  );
  return (
    <section className="receipt-originals" aria-label={t("Оригиналы чека")}>
      <div className="between">
        <h3>
          <FileImage size={18} /> {t("Оригиналы чека")}{" "}
          <small>{receipt.files.length || ""}</small>
        </h3>
        {canAdd && (
          <button
            type="button"
            className="text-button"
            disabled={upload.isPending}
            onClick={() => input.current?.click()}
          >
            <Plus size={16} />
            {upload.isPending ? t("Загружаю…") : t("Прикрепить")}
          </button>
        )}
      </div>
      <p className="management-hint">
        {t("Фото и снимки электронной страницы сохраняются вместе с чеком.")}
      </p>
      {receipt.files.length ? (
        <div className="original-thumbnails">
          {receipt.files.map((url, index) => (
            <button
              type="button"
              key={url}
              onClick={() => {
                setOpened(index);
                setZoom(false);
                setFailure(null);
              }}
              aria-label={t("Открыть оригинал {0}", index + 1)}
            >
              <img
                src={url}
                alt={t("Оригинал {0}", index + 1)}
                loading="lazy"
              />
              <span>{index + 1}</span>
            </button>
          ))}
        </div>
      ) : (
        <div className="notice">
          {receipt.source === "manual"
            ? t(
                "Чек введён вручную. Фото можно прикрепить позже, оно не обязательно.",
              )
            : t("Оригинал не был сохранён.")}{" "}
          {receipt.source !== "manual" &&
            (canAdd
              ? t(
                  "Прикрепите фото или снимок страницы — он останется здесь вместе с историей.",
                )
              : t("Попросите автора чека или администратора прикрепить фото."))}
        </div>
      )}
      <input
        ref={input}
        type="file"
        hidden
        multiple
        accept="image/jpeg,image/png,image/webp,image/heic,image/heif"
        disabled={upload.isPending}
        onChange={(event) => {
          const files = Array.from(event.target.files ?? []);
          event.target.value = "";
          if (files.length) upload.mutate(files);
        }}
      />
      <ErrorBox error={upload.error} />
      {opened !== null && receipt.files[opened] && (
        <Modal
          wide
          title={t(
            "Оригинал чека · {0} из {1}",
            opened + 1,
            receipt.files.length,
          )}
          description={
            receipt.merchant ||
            t("Сохранённое фото или снимок электронной страницы")
          }
          onClose={() => setOpened(null)}
        >
          <div className="original-toolbar">
            <div className="management-actions">
              <button
                type="button"
                className="icon-button"
                aria-label={t("Предыдущий оригинал")}
                disabled={opened === 0}
                onClick={() => {
                  setOpened(opened - 1);
                  setFailure(null);
                }}
              >
                <ChevronLeft size={20} />
              </button>
              <button
                type="button"
                className="icon-button"
                aria-label={t("Следующий оригинал")}
                disabled={opened + 1 === receipt.files.length}
                onClick={() => {
                  setOpened(opened + 1);
                  setFailure(null);
                }}
              >
                <ChevronRight size={20} />
              </button>
              <button
                type="button"
                className="button secondary"
                onClick={() => setZoom(!zoom)}
              >
                {zoom ? <ZoomOut size={17} /> : <ZoomIn size={17} />}
                {zoom ? t("Уменьшить") : t("Увеличить")}
              </button>
            </div>
            <a
              className="button secondary"
              href={receipt.files[opened]}
              download={`finora-receipt-${receipt.id}-${opened + 1}.jpg`}
            >
              <Download size={17} /> {t("Скачать")}
            </a>
          </div>
          <ErrorBox error={failure} />
          <div
            className={`original-viewer ${zoom ? "zoomed" : ""}`}
            tabIndex={0}
            aria-label={t("Изображение чека, прокрутите для просмотра")}
          >
            <img
              src={receipt.files[opened]}
              alt={t("Оригинал чека {0}", opened + 1)}
              onError={() =>
                setFailure(
                  new Error(
                    t(
                      "Не удалось загрузить оригинал. Проверьте соединение и откройте его ещё раз.",
                    ),
                  ),
                )
              }
            />
          </div>
        </Modal>
      )}
    </section>
  );
}
