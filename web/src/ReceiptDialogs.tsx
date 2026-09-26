import { defaultReceiptUnit, unitLabel, canonicalUnit } from "./units";
import { t, getLocale } from "./i18n";
import { ReceiptOriginals } from "./ReceiptOriginals";
import { receiptLineTotal } from "./receiptAmounts";
import { moneyMinor, minorDecimal } from "./wallet";
import { useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Camera,
  Check,
  FileImage,
  Link2,
  PenLine,
  Plus,
  RefreshCw,
  Trash2,
  UploadCloud,
} from "lucide-react";
import { api, send, useAction } from "./api";
import { useApp } from "./context";
import type { Receipt, Transaction } from "./types";
import {
  AccountSelect,
  Badge,
  CategorySelect,
  CurrencySelect,
  ErrorBox,
  Field,
  Form,
  Modal,
  MoneyInput,
  Submit,
  amount,
  decimal,
  today,
} from "./ui";

export function UploadReceipt({ onClose }: { onClose: () => void }) {
  const { accounts, prefs, toast, navigate, organization, isAdmin } = useApp();
  const [mode, setMode] = useState("photo");
  const [files, setFiles] = useState<File[]>([]);
  const [url, setUrl] = useState("");
  const [account, setAccount] = useState(
    prefs?.default_account_id ?? accounts.find((a) => !a.archived)?.id ?? "",
  );
  const [rate, setRate] = useState("");
  const [drag, setDrag] = useState(false);
  const [fileError, setFileError] = useState("");
  const input = useRef<HTMLInputElement>(null);
  const camera = useRef<HTMLInputElement>(null);
  const duplicate = useRef(false);
  const currency = accounts.find((a) => a.id === account)?.currency ?? "MDL";
  const action = useAction(
    async () => {
      if (mode === "link") {
        const result = await send<{ duplicate: boolean }>("/receipts/link", {
          url,
          account_id: account || null,
          fx_rate: currency === "MDL" ? null : rate,
        });
        duplicate.current = result.duplicate;
        return result;
      }
      if (!files.length) throw new Error(t("Выберите фотографию чека"));
      const body = new FormData();
      files.forEach((f) => body.append("files", f));
      body.append("account_id", account);
      body.append("fx_rate", currency === "MDL" ? "1" : rate);
      const result = await send<{ duplicate: boolean }>(
        "/receipts/upload",
        body,
      );
      duplicate.current = result.duplicate;
      return result;
    },
    () => {
      toast(
        duplicate.current
          ? t("Этот чек уже загружен. Повторного расхода не будет")
          : t("Чек отправлен. Результат появится в чате"),
      );
      navigate(duplicate.current || !isAdmin ? "receipts" : "assistant");
      onClose();
    },
  );
  function selectFiles(selected: File[]) {
    if (
      selected.length > 4 ||
      selected.some((f) => f.size > 15 * 1024 * 1024)
    ) {
      setFileError(t("До 4 фотографий одного чека, не более 15 МБ каждая"));
      return;
    }
    setFiles(selected);
    setFileError("");
  }
  if (mode === "manual" && isAdmin)
    return (
      <ReceiptForm
        manual
        receipt={{
          id: "manual",
          source: "manual",
          source_url: null,
          merchant: "",
          purchased_on: today(),
          currency,
          total_minor: null,
          status: "review",
          error: null,
          warnings: [],
          account_id: account || null,
          fx_rate: currency === "MDL" ? "1" : rate,
          version: 1,
          transaction_id: null,
          files: [],
          created_at: "",
          items: [],
        }}
        onClose={onClose}
        onBack={() => setMode("photo")}
      />
    );
  return (
    <Modal
      title={t("Добавить чек")}
      description={t(
        "Фото или QR молдавского чека. Товары и категории — в одном месте.",
      )}
      onClose={onClose}
    >
      <div className="notice">
        <strong>
          {t("Организация:")} {organization.name}
        </strong>
        <p>
          {t(
            "Чек будет доступен её участникам. Для другой организации закройте окно и используйте переключатель в меню.",
          )}
        </p>
      </div>
      <Form onSubmit={() => action.mutate(undefined)}>
        <div className="segmented receipt-input-modes">
          <button
            type="button"
            className={mode === "photo" ? "selected" : ""}
            onClick={() => setMode("photo")}
          >
            <Camera size={17} />
            {t("Фото чека")}
          </button>
          <button
            type="button"
            className={mode === "link" ? "selected" : ""}
            onClick={() => setMode("link")}
          >
            <Link2 size={17} />
            {t("QR-ссылка")}
          </button>
          {isAdmin && (
            <button type="button" onClick={() => setMode("manual")}>
              <PenLine size={17} />
              {t("Вручную")}
            </button>
          )}
        </div>
        {mode === "photo" ? (
          <>
            <div
              role="button"
              tabIndex={0}
              aria-label={t("Выбрать фотографии чека")}
              className={`dropzone ${drag ? "dragging" : ""}`}
              onClick={() => input.current?.click()}
              onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === " ") {
                  e.preventDefault();
                  input.current?.click();
                }
              }}
              onDragOver={(e) => {
                e.preventDefault();
                setDrag(true);
              }}
              onDragLeave={() => setDrag(false)}
              onDrop={(e) => {
                e.preventDefault();
                setDrag(false);
                selectFiles(Array.from(e.dataTransfer.files));
              }}
            >
              <UploadCloud size={34} />
              <strong>
                {files.length
                  ? t("Выбрано фото: {0}", files.length)
                  : t("Перетащите чек сюда")}
              </strong>
              <span>{t("или нажмите, чтобы выбрать фотографии")}</span>
              <small>
                {t("JPEG, PNG, WebP, HEIC · до 15 МБ · до 4 фото одного чека")}
              </small>
            </div>
            <input
              ref={input}
              hidden
              type="file"
              accept="image/jpeg,image/png,image/webp,image/heic,image/heif"
              multiple
              onChange={(e) => selectFiles(Array.from(e.target.files ?? []))}
            />
            <input
              ref={camera}
              hidden
              type="file"
              accept="image/*"
              capture="environment"
              onChange={(e) => selectFiles(Array.from(e.target.files ?? []))}
            />
            <button
              type="button"
              className="text-button camera-button"
              onClick={() => camera.current?.click()}
            >
              <Camera size={18} />
              {t("Снять чек или его QR камерой")}
            </button>
            {files.map((f, i) => (
              <div className="file-chip" key={i}>
                <FileImage size={18} />
                <span>{f.name}</span>
                <button
                  type="button"
                  className="icon-button"
                  aria-label={t("Убрать {0}", f.name)}
                  onClick={() => setFiles(files.filter((_, n) => n !== i))}
                >
                  <Trash2 size={16} />
                </button>
              </div>
            ))}
          </>
        ) : (
          <Field
            label={t("Ссылка из QR-кода")}
            hint={t("HTTPS-ссылка на электронный чек любого магазина")}
          >
            <input
              type="url"
              required
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              placeholder={t("Вставьте ссылку из QR-кода")}
              maxLength={1000}
            />
          </Field>
        )}
        <div className="form-grid">
          <Field label={t("Оплачено со счёта")}>
            <AccountSelect
              accounts={accounts}
              value={account}
              onChange={setAccount}
              optional
            />
          </Field>
          {currency !== "MDL" && (
            <Field label={t("Курс 1 {0} в MDL", currency)}>
              <input
                type="number"
                required
                min="0.00000001"
                step="0.00000001"
                value={rate}
                onChange={(e) => setRate(e.target.value)}
              />
            </Field>
          )}
        </div>
        <div className="notice">
          {prefs?.auto_post
            ? t(
                "Проверенный чек запишется автоматически на выбранный счёт. Спорные суммы и возможные дубликаты останутся на проверке.",
              )
            : t(
                "Распознанный чек останется черновиком до вашего подтверждения.",
              )}
          {prefs?.provider === "disabled" && (
            <p>
              {t(
                "Доступны MEV и локальный OCR. Для сложных фотографий подключите модель с поддержкой изображений в настройках.",
              )}
            </p>
          )}
          {prefs?.provider === "openai" && (
            <p>
              {t(
                "Если локальный OCR не справится, фото будет отправлено в OpenAI.",
              )}
            </p>
          )}
        </div>
        <ErrorBox error={fileError || action.error} />
        <footer className="modal-footer">
          <button type="button" className="button secondary" onClick={onClose}>
            {t("Отмена")}
          </button>
          <Submit pending={action.isPending}>{t("Распознать чек")}</Submit>
        </footer>
      </Form>
    </Modal>
  );
}

export function ReceiptEditor({
  receipt,
  onClose,
}: {
  receipt: Receipt;
  onClose: () => void;
}) {
  const query = useQuery({
    queryKey: ["receipt-detail", receipt.id],
    queryFn: () => api<Receipt>(`/receipts/${receipt.id}`),
    initialData: receipt,
    refetchInterval: (q) =>
      ["queued", "processing"].includes(q.state.data?.status ?? "")
        ? 3000
        : false,
  });
  return (
    <ReceiptForm
      key={`${receipt.id}-${query.data.version}`}
      receipt={query.data}
      onClose={onClose}
    />
  );
}

function ReceiptModeration({
  receipt,
  onClose,
}: {
  receipt: Receipt;
  onClose: () => void;
}) {
  const [removing, setRemoving] = useState<{ id: string; name: string } | null>(
    null,
  );
  const { toast } = useApp();
  const remove = useAction(
    async () => {
      const item = removing?.id;
      await send(
        `/receipts/${receipt.id}${item ? `/items/${item}` : ""}?version=${receipt.version}`,
        undefined,
        "DELETE",
      );
      if (!item) onClose();
    },
    () => {
      toast(t("Учёт и статистика обновлены"));
      setRemoving(null);
    },
  );
  return (
    <section className="receipt-moderation">
      <div className="between">
        <h3>{t("Управление чеком")}</h3>
        <button
          className="text-button negative"
          onClick={() => setRemoving({ id: "", name: t("весь чек") })}
        >
          <Trash2 size={16} />
          {t("Удалить чек")}
        </button>
      </div>
      {receipt.status === "posted" && (
        <details>
          <summary>{t("Удалить отдельный товар")}</summary>
          <div className="bill-list">
            {receipt.items.map((item) => (
              <div className="bill-row" key={item.id}>
                <div className="grow">{item.name}</div>
                <span>{amount(item.total_minor, receipt.currency)}</span>
                <button
                  className="icon-button danger-hover"
                  aria-label={t("Удалить из учёта: {0}", item.name)}
                  onClick={() => setRemoving({ id: item.id, name: item.name })}
                >
                  <Trash2 size={16} />
                </button>
              </div>
            ))}
          </div>
        </details>
      )}
      {removing && (
        <div className="notice warning" role="alert">
          <strong>
            {t("Удалить")} {removing.name}?
          </strong>
          <p>
            {removing.id
              ? t("Сумма чека, расход и остаток на счёте будут пересчитаны.")
              : t(
                  "Чек исчезнет из списка, связанный расход будет отменён.",
                )}{" "}
            {t("Оригинал и запись об удалении сохранятся в истории сервера.")}
          </p>
          <div className="button-row">
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
              {t("Подтвердить удаление")}
            </button>
          </div>
        </div>
      )}
      <ErrorBox error={remove.error} />
    </section>
  );
}

function ReceiptComments({ receiptId }: { receiptId: string }) {
  const [text, setText] = useState("");
  const [offset, setOffset] = useState(0);
  const query = useQuery({
    queryKey: ["receipt-comments", receiptId, offset],
    queryFn: () =>
      api<{ id: string; author: string; text: string; created_at: string }[]>(
        `/receipts/${receiptId}/comments?offset=${offset}`,
      ),
  });
  const action = useAction(
    () => send(`/receipts/${receiptId}/comments`, { text }),
    () => {
      setText("");
      setOffset(0);
    },
  );
  return (
    <section className="receipt-comments">
      <h3>{t("Комментарии к чеку")}</h3>
      <Form onSubmit={() => action.mutate(undefined)}>
        <Field label={t("Ваш комментарий")}>
          <textarea
            required
            maxLength={3000}
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder={t(
              "Например, покупка для офиса или пояснение к товару",
            )}
          />
        </Field>
        <Submit pending={action.isPending}>{t("Добавить комментарий")}</Submit>
      </Form>
      <ErrorBox error={query.error ?? action.error} />
      <div className="comment-list">
        {query.data?.map((comment) => (
          <article key={comment.id}>
            <strong>{comment.author}</strong>
            <small>
              {new Date(comment.created_at).toLocaleString(getLocale())}
            </small>
            <p>{comment.text}</p>
          </article>
        ))}
      </div>
      {(offset > 0 || query.data?.length === 50) && (
        <div className="pagination">
          <button
            disabled={!offset}
            onClick={() => setOffset(Math.max(0, offset - 50))}
          >
            {t("Новые")}
          </button>
          <button
            disabled={query.data?.length !== 50}
            onClick={() => setOffset(offset + 50)}
          >
            {t("Ранее")}
          </button>
        </div>
      )}
    </section>
  );
}

function ReceiptForm({
  receipt,
  onClose,
  manual = false,
  onBack,
}: {
  receipt: Receipt;
  onClose: () => void;
  manual?: boolean;
  onBack?: () => void;
}) {
  const { accounts, categories, toast, isAdmin, organization, navigate } =
    useApp();
  const [requestKey] = useState(() => crypto.randomUUID());
  const [merchant, setMerchant] = useState(receipt.merchant);
  const [address, setAddress] = useState(receipt.merchant_address ?? "");
  const [date, setDate] = useState(receipt.purchased_on ?? today());
  const [currency, setCurrency] = useState<string>(receipt.currency);
  const [enteredTotal, setTotal] = useState(
    receipt.total_minor ? decimal(receipt.total_minor) : "",
  );
  const [account, setAccount] = useState(receipt.account_id ?? "");
  const [rate, setRate] = useState(receipt.fx_rate);
  const [transaction, setTransaction] = useState("");
  const blank = () => ({
    name: "",
    quantity: "1",
    unit: defaultReceiptUnit,
    unit_price: "",
    total: "",
    category_id: "",
  });
  const [items, setItems] = useState(
    receipt.items.length
      ? receipt.items.map((i) => ({
          name: i.name,
          quantity: i.quantity,
          unit: i.unit,
          unit_price: decimal(i.unit_price_minor),
          total: decimal(i.total_minor),
          category_id: i.category_id ?? "",
        }))
      : [blank()],
  );
  const readonly =
    !isAdmin || ["posted", "queued", "processing"].includes(receipt.status);
  const sum = items.reduce(
    (value, item) => value + (moneyMinor(item.total) ?? 0),
    0,
  );
  const total = manual ? minorDecimal(sum) : enteredTotal;
  const matches = useQuery({
    queryKey: ["receipt-matches", date, account, total],
    enabled: !manual && !readonly && !!account && !!date && Number(total) > 0,
    queryFn: () =>
      api<{ items: Transaction[] }>(
        `/transactions?month=${date.slice(0, 7)}&account_id=${account}&kind=expense&limit=200`,
      ),
  });
  const action = useAction(
    () =>
      send(manual ? "/receipts/manual" : `/receipts/${receipt.id}/confirm`, {
        merchant,
        merchant_address: address,
        purchased_on: date,
        currency,
        total,
        account_id: account,
        fx_rate: currency === "MDL" ? null : rate,
        items: items.map((i) => ({ ...i, category_id: i.category_id || null })),
        ...(manual
          ? { request_key: requestKey }
          : { version: receipt.version, transaction_id: transaction || null }),
      }),
    () => {
      toast(t("Чек и товары сохранены в учёте"));
      if (manual) navigate("receipts");
      onClose();
    },
  );
  const retry = useAction(
    () => send(`/receipts/${receipt.id}/retry`),
    () => {
      toast(t("Повторное распознавание запущено"));
      onClose();
    },
  );
  const difference = Math.round(Number(total || 0) * 100) - sum;
  function change(
    index: number,
    field: keyof ReturnType<typeof blank>,
    value: string,
  ) {
    setItems(
      items.map((item, n) => {
        if (n !== index) return item;
        const changed = { ...item, [field]: value };
        if (manual && (field === "quantity" || field === "unit_price"))
          changed.total = receiptLineTotal(
            changed.quantity,
            changed.unit_price,
          );
        return changed;
      }),
    );
  }
  return (
    <Modal
      wide
      title={
        manual
          ? t("Создать чек вручную")
          : receipt.status === "posted"
            ? t("Чек в вашем учёте")
            : t("Проверить чек")
      }
      description={
        manual
          ? t("Введите покупки. Фото, QR и банковская операция не нужны.")
          : t(
              "Проверьте магазин, дату и итог каждой строки. Скидки должны входить в суммы товаров.",
            )
      }
      onClose={() => {
        if (!action.isPending) onClose();
      }}
    >
      <div className="receipt-status">
        {manual ? (
          <strong>
            {t("Организация:")} {organization.name}
          </strong>
        ) : (
          <Badge status={receipt.status} />
        )}
        {receipt.source === "manual" && (
          <span className="badge">{t("Ручной чек")}</span>
        )}
        {receipt.source_url && (
          <a href={receipt.source_url} target="_blank" rel="noreferrer">
            {t("Открыть сайт чека ↗")}
          </a>
        )}
      </div>
      {receipt.error && <div className="notice warning">{receipt.error}</div>}
      {receipt.warnings.map((w, i) => (
        <div className="notice warning" key={i}>
          {w}
        </div>
      ))}
      <Form onSubmit={() => action.mutate(undefined)}>
        <div className={manual ? "receipt-manual-layout" : "receipt-layout"}>
          {!manual && <ReceiptOriginals receipt={receipt} />}
          <div>
            <fieldset disabled={readonly || action.isPending}>
              <div className="form-grid">
                <Field label={t("Магазин")}>
                  <input
                    required
                    maxLength={200}
                    value={merchant}
                    onChange={(e) => setMerchant(e.target.value)}
                  />
                </Field>
                <Field label={t("Адрес магазина")} wide>
                  <input
                    maxLength={500}
                    value={address}
                    onChange={(e) => setAddress(e.target.value)}
                    placeholder={
                      manual
                        ? t("Необязательно")
                        : t("Адрес, напечатанный на чеке")
                    }
                  />
                </Field>
                <Field label={t("Дата покупки")}>
                  <input
                    type="date"
                    required
                    min="1990-01-01"
                    max={today()}
                    value={date}
                    onChange={(e) => setDate(e.target.value)}
                  />
                </Field>
                <Field label={t("Валюта")}>
                  <CurrencySelect
                    value={currency}
                    onChange={(value) => {
                      setCurrency(value);
                      if (manual) {
                        setAccount(
                          accounts.find(
                            (a) => !a.archived && a.currency === value,
                          )?.id ?? "",
                        );
                        setRate(value === "MDL" ? "1" : "");
                      }
                    }}
                  />
                </Field>
                <Field label={t("Счёт оплаты")}>
                  <AccountSelect
                    accounts={accounts}
                    value={account}
                    onChange={setAccount}
                    currency={currency}
                  />
                </Field>
                {currency !== "MDL" && (
                  <Field label={t("Курс к MDL")}>
                    <input
                      type="number"
                      min="0.00000001"
                      step="0.00000001"
                      value={rate}
                      onChange={(e) => setRate(e.target.value)}
                      required
                    />
                  </Field>
                )}
              </div>
              <div className="receipt-items">
                {items.map((i, n) => (
                  <div className="receipt-item" key={n}>
                    <div className="item-number">
                      {String(n + 1).padStart(2, "0")}
                    </div>
                    <div className="item-fields">
                      <Field label={t("Товар")}>
                        <input
                          required
                          value={i.name}
                          maxLength={300}
                          onChange={(e) => change(n, "name", e.target.value)}
                          placeholder={t("Название товара")}
                        />
                      </Field>
                      <Field label={t("Категория")}>
                        <CategorySelect
                          categories={categories}
                          value={i.category_id}
                          onChange={(v) => change(n, "category_id", v)}
                        />
                      </Field>
                      <div className="item-numbers">
                        <Field label={t("Кол-во")}>
                          <input
                            type="number"
                            min="0.000001"
                            step="0.000001"
                            required
                            value={i.quantity}
                            onChange={(e) =>
                              change(n, "quantity", e.target.value)
                            }
                          />
                        </Field>
                        <Field label={t("Ед.")}>
                          <input
                            required
                            maxLength={12}
                            value={unitLabel(i.unit)}
                            onChange={(e) =>
                              change(n, "unit", canonicalUnit(e.target.value))
                            }
                          />
                        </Field>
                        <Field label={t("Цена")}>
                          <MoneyInput
                            value={i.unit_price}
                            min="0"
                            onChange={(v) => change(n, "unit_price", v)}
                          />
                        </Field>
                        <Field label={t("Итог строки")}>
                          <MoneyInput
                            value={i.total}
                            min="0"
                            onChange={(v) => change(n, "total", v)}
                          />
                        </Field>
                      </div>
                    </div>
                    {!readonly && (
                      <button
                        className="icon-button danger-hover"
                        type="button"
                        disabled={manual && items.length === 1}
                        aria-label={t("Удалить товар {0}", n + 1)}
                        onClick={() =>
                          setItems(items.filter((_, index) => index !== n))
                        }
                      >
                        <Trash2 size={17} />
                      </button>
                    )}
                  </div>
                ))}
              </div>
              {!readonly && (
                <button
                  className="text-button"
                  type="button"
                  disabled={items.length >= 200}
                  onClick={() => setItems([...items, blank()])}
                >
                  <Plus size={17} />
                  {t("Добавить товар")}
                </button>
              )}
              <div className="receipt-total">
                <Field label={t("Итого по чеку · {0}", currency)}>
                  {manual ? (
                    <input
                      readOnly
                      value={total}
                      aria-label={t("Итог рассчитан по товарам")}
                    />
                  ) : (
                    <MoneyInput value={total} onChange={setTotal} />
                  )}
                </Field>
                <div className={difference ? "negative" : "positive"}>
                  {difference ? (
                    t("Разница со строками: {0}", amount(difference, currency))
                  ) : (
                    <>
                      <Check size={17} />
                      {t("Сумма товаров совпадает")}
                    </>
                  )}
                </div>
              </div>
              {!readonly && !manual && (
                <Field
                  label={t("Привязать к существующему расходу (необязательно)")}
                  hint={t(
                    "Показаны покупки с той же датой, суммой и счётом. Привязка не списывает деньги повторно.",
                  )}
                >
                  <select
                    value={transaction}
                    onChange={(e) => setTransaction(e.target.value)}
                  >
                    <option value="">{t("Создать новый расход")}</option>
                    {matches.data?.items
                      .filter(
                        (tx) =>
                          !tx.receipt_id &&
                          tx.occurred_on === date &&
                          tx.currency === currency &&
                          tx.amount_minor === Math.round(Number(total) * 100),
                      )
                      .map((tx) => (
                        <option key={tx.id} value={tx.id}>
                          {tx.merchant || t("Покупка")} ·{" "}
                          {amount(tx.amount_minor, tx.currency)} ·{" "}
                          {tx.occurred_on}
                        </option>
                      ))}
                  </select>
                </Field>
              )}
            </fieldset>
          </div>
        </div>
        {manual && (
          <div className="notice">
            {t(
              "После сохранения чек и товары попадут в аналитику, а сумма спишется с выбранного счёта. Итог считается по строкам; скидку можно учесть в итоге товара.",
            )}
          </div>
        )}
        <ErrorBox error={action.error ?? retry.error} />
        <footer className="modal-footer">
          {!readonly && !manual && (
            <button
              type="button"
              className="button secondary"
              disabled={retry.isPending}
              onClick={() => retry.mutate(undefined)}
            >
              <RefreshCw size={16} />
              {t("Распознать снова")}
            </button>
          )}
          <button
            type="button"
            className="button secondary"
            onClick={onClose}
            disabled={action.isPending}
          >
            {t("Закрыть")}
          </button>
          {manual && onBack && (
            <button
              type="button"
              className="button secondary"
              disabled={action.isPending}
              onClick={onBack}
            >
              {t("Назад")}
            </button>
          )}
          {!readonly && (
            <Submit pending={action.isPending}>{t("Сохранить расход")}</Submit>
          )}
        </footer>
      </Form>
      {!manual &&
        isAdmin &&
        !["queued", "processing"].includes(receipt.status) && (
          <ReceiptModeration receipt={receipt} onClose={onClose} />
        )}
      {!manual && <ReceiptComments receiptId={receipt.id} />}
    </Modal>
  );
}
