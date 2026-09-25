import { useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Camera,
  Check,
  FileImage,
  Link2,
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
      if (!files.length) throw new Error("Выберите фотографию чека");
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
          ? "Этот чек уже загружен. Повторного расхода не будет"
          : "Чек отправлен. Результат появится в чате",
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
      setFileError("До 4 фотографий одного чека, не более 15 МБ каждая");
      return;
    }
    setFiles(selected);
    setFileError("");
  }
  return (
    <Modal
      title="Добавить чек"
      description="Фото или QR молдавского чека. Товары и категории — в одном месте."
      onClose={onClose}
    >
      <div className="notice">
        <strong>Организация: {organization.name}</strong>
        <p>
          Чек будет доступен её участникам. Для другой организации закройте окно
          и используйте переключатель в меню.
        </p>
      </div>
      <Form onSubmit={() => action.mutate(undefined)}>
        <div className="segmented">
          <button
            type="button"
            className={mode === "photo" ? "selected" : ""}
            onClick={() => setMode("photo")}
          >
            <Camera size={17} />
            Фото чека
          </button>
          <button
            type="button"
            className={mode === "link" ? "selected" : ""}
            onClick={() => setMode("link")}
          >
            <Link2 size={17} />
            Ссылка MEV
          </button>
        </div>
        {mode === "photo" ? (
          <>
            <div
              role="button"
              tabIndex={0}
              aria-label="Выбрать фотографии чека"
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
                  ? `Выбрано фото: ${files.length}`
                  : "Перетащите чек сюда"}
              </strong>
              <span>или нажмите, чтобы выбрать фотографии</span>
              <small>
                JPEG, PNG, WebP, HEIC · до 15 МБ · до 4 фото одного чека
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
              Снять чек или его QR камерой
            </button>
            {files.map((f, i) => (
              <div className="file-chip" key={i}>
                <FileImage size={18} />
                <span>{f.name}</span>
                <button
                  type="button"
                  className="icon-button"
                  aria-label={`Убрать ${f.name}`}
                  onClick={() => setFiles(files.filter((_, n) => n !== i))}
                >
                  <Trash2 size={16} />
                </button>
              </div>
            ))}
          </>
        ) : (
          <Field
            label="Ссылка из QR-кода"
            hint="https://mev.sfs.md/receipt-verifier/…"
          >
            <input
              type="url"
              required
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              placeholder="Вставьте ссылку MEV"
              maxLength={1000}
            />
          </Field>
        )}
        <div className="form-grid">
          <Field label="Оплачено со счёта">
            <AccountSelect
              accounts={accounts}
              value={account}
              onChange={setAccount}
              optional
            />
          </Field>
          {currency !== "MDL" && (
            <Field label={`Курс 1 ${currency} в MDL`}>
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
            ? "Проверенный чек запишется автоматически на выбранный счёт. Спорные суммы и возможные дубликаты останутся на проверке."
            : "Распознанный чек останется черновиком до вашего подтверждения."}
          {prefs?.provider === "disabled" && (
            <p>
              Доступны MEV и локальный OCR. Для сложных фотографий подключите
              модель с поддержкой изображений в настройках.
            </p>
          )}
          {prefs?.provider === "openai" && (
            <p>
              Если локальный OCR не справится, фото будет отправлено в OpenAI.
            </p>
          )}
        </div>
        <ErrorBox error={fileError || action.error} />
        <footer className="modal-footer">
          <button type="button" className="button secondary" onClick={onClose}>
            Отмена
          </button>
          <Submit pending={action.isPending}>Распознать чек</Submit>
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
      toast("Учёт и статистика обновлены");
      setRemoving(null);
    },
  );
  return (
    <section className="receipt-moderation">
      <div className="between">
        <h3>Управление чеком</h3>
        <button
          className="text-button negative"
          onClick={() => setRemoving({ id: "", name: "весь чек" })}
        >
          <Trash2 size={16} />
          Удалить чек
        </button>
      </div>
      {receipt.status === "posted" && (
        <details>
          <summary>Удалить отдельный товар</summary>
          <div className="bill-list">
            {receipt.items.map((item) => (
              <div className="bill-row" key={item.id}>
                <div className="grow">{item.name}</div>
                <span>{amount(item.total_minor, receipt.currency)}</span>
                <button
                  className="icon-button danger-hover"
                  aria-label={`Удалить из учёта: ${item.name}`}
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
          <strong>Удалить {removing.name}?</strong>
          <p>
            {removing.id
              ? "Сумма чека, расход и остаток на счёте будут пересчитаны."
              : "Чек исчезнет из списка, связанный расход будет отменён."}{" "}
            Оригинал и запись об удалении сохранятся в истории сервера.
          </p>
          <div className="button-row">
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
              Подтвердить удаление
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
      <h3>Комментарии к чеку</h3>
      <Form onSubmit={() => action.mutate(undefined)}>
        <Field label="Ваш комментарий">
          <textarea
            required
            maxLength={3000}
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder="Например, покупка для офиса или пояснение к товару"
          />
        </Field>
        <Submit pending={action.isPending}>Добавить комментарий</Submit>
      </Form>
      <ErrorBox error={query.error ?? action.error} />
      <div className="comment-list">
        {query.data?.map((comment) => (
          <article key={comment.id}>
            <strong>{comment.author}</strong>
            <small>
              {new Date(comment.created_at).toLocaleString("ru-RU")}
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
            Новые
          </button>
          <button
            disabled={query.data?.length !== 50}
            onClick={() => setOffset(offset + 50)}
          >
            Ранее
          </button>
        </div>
      )}
    </section>
  );
}

function ReceiptForm({
  receipt,
  onClose,
}: {
  receipt: Receipt;
  onClose: () => void;
}) {
  const { accounts, categories, toast, isAdmin } = useApp();
  const [merchant, setMerchant] = useState(receipt.merchant);
  const [date, setDate] = useState(receipt.purchased_on ?? today());
  const [currency, setCurrency] = useState<string>(receipt.currency);
  const [total, setTotal] = useState(
    receipt.total_minor ? decimal(receipt.total_minor) : "",
  );
  const [account, setAccount] = useState(receipt.account_id ?? "");
  const [rate, setRate] = useState(receipt.fx_rate);
  const [transaction, setTransaction] = useState("");
  const blank = () => ({
    name: "",
    quantity: "1",
    unit: "шт",
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
  const matches = useQuery({
    queryKey: ["receipt-matches", date, account, total],
    enabled: !readonly && !!account && !!date && Number(total) > 0,
    queryFn: () =>
      api<{ items: Transaction[] }>(
        `/transactions?month=${date.slice(0, 7)}&account_id=${account}&kind=expense&limit=200`,
      ),
  });
  const action = useAction(
    () =>
      send(`/receipts/${receipt.id}/confirm`, {
        merchant,
        purchased_on: date,
        currency,
        total,
        account_id: account,
        fx_rate: currency === "MDL" ? null : rate,
        items: items.map((i) => ({ ...i, category_id: i.category_id || null })),
        version: receipt.version,
        transaction_id: transaction || null,
      }),
    () => {
      toast("Чек и товары сохранены в учёте");
      onClose();
    },
  );
  const retry = useAction(
    () => send(`/receipts/${receipt.id}/retry`),
    () => {
      toast("Повторное распознавание запущено");
      onClose();
    },
  );
  const sum = items.reduce(
    (sum, i) => sum + Math.round(Number(i.total || 0) * 100),
    0,
  );
  const difference = Math.round(Number(total || 0) * 100) - sum;
  function change(
    index: number,
    field: keyof ReturnType<typeof blank>,
    value: string,
  ) {
    setItems(items.map((i, n) => (n === index ? { ...i, [field]: value } : i)));
  }
  return (
    <Modal
      wide
      title={
        receipt.status === "posted" ? "Чек в вашем учёте" : "Проверить чек"
      }
      description="Проверьте магазин, дату и итог каждой строки. Скидки должны входить в суммы товаров."
      onClose={onClose}
    >
      <div className="receipt-status">
        <Badge status={receipt.status} />
        {receipt.source_url && (
          <a href={receipt.source_url} target="_blank" rel="noreferrer">
            Оригинал MEV ↗
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
        <div className={receipt.files.length ? "receipt-layout" : ""}>
          {receipt.files.length > 0 && (
            <aside className="receipt-images">
              {receipt.files.map((url, n) => (
                <a href={url} key={url} target="_blank" rel="noreferrer">
                  <img src={url} alt={`Фото чека ${n + 1}`} />
                </a>
              ))}
            </aside>
          )}
          <div>
            <fieldset disabled={readonly}>
              <div className="form-grid">
                <Field label="Магазин">
                  <input
                    required
                    maxLength={200}
                    value={merchant}
                    onChange={(e) => setMerchant(e.target.value)}
                  />
                </Field>
                <Field label="Дата покупки">
                  <input
                    type="date"
                    required
                    min="1990-01-01"
                    max={today()}
                    value={date}
                    onChange={(e) => setDate(e.target.value)}
                  />
                </Field>
                <Field label="Валюта">
                  <CurrencySelect value={currency} onChange={setCurrency} />
                </Field>
                <Field label="Счёт оплаты">
                  <AccountSelect
                    accounts={accounts}
                    value={account}
                    onChange={setAccount}
                    currency={currency}
                  />
                </Field>
                {currency !== "MDL" && (
                  <Field label="Курс к MDL">
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
                      <Field label="Товар">
                        <input
                          required
                          value={i.name}
                          maxLength={300}
                          onChange={(e) => change(n, "name", e.target.value)}
                          placeholder="Название товара"
                        />
                      </Field>
                      <Field label="Категория">
                        <CategorySelect
                          categories={categories}
                          value={i.category_id}
                          onChange={(v) => change(n, "category_id", v)}
                        />
                      </Field>
                      <div className="item-numbers">
                        <Field label="Кол-во">
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
                        <Field label="Ед.">
                          <input
                            required
                            maxLength={12}
                            value={i.unit}
                            onChange={(e) => change(n, "unit", e.target.value)}
                          />
                        </Field>
                        <Field label="Цена">
                          <MoneyInput
                            value={i.unit_price}
                            min="0"
                            onChange={(v) => change(n, "unit_price", v)}
                          />
                        </Field>
                        <Field label="Итог строки">
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
                        aria-label={`Удалить товар ${n + 1}`}
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
                  onClick={() => setItems([...items, blank()])}
                >
                  <Plus size={17} />
                  Добавить товар
                </button>
              )}
              <div className="receipt-total">
                <Field label={`Итого по чеку · ${currency}`}>
                  <MoneyInput value={total} onChange={setTotal} />
                </Field>
                <div className={difference ? "negative" : "positive"}>
                  {difference ? (
                    `Разница со строками: ${amount(difference, currency)}`
                  ) : (
                    <>
                      <Check size={17} />
                      Сумма товаров совпадает
                    </>
                  )}
                </div>
              </div>
              {!readonly && (
                <Field
                  label="Привязать к существующему расходу (необязательно)"
                  hint="Показаны покупки с той же датой, суммой и счётом. Привязка не списывает деньги повторно."
                >
                  <select
                    value={transaction}
                    onChange={(e) => setTransaction(e.target.value)}
                  >
                    <option value="">Создать новый расход</option>
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
                          {tx.merchant || "Покупка"} ·{" "}
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
        <ErrorBox error={action.error ?? retry.error} />
        <footer className="modal-footer">
          {!readonly && (
            <button
              type="button"
              className="button secondary"
              disabled={retry.isPending}
              onClick={() => retry.mutate(undefined)}
            >
              <RefreshCw size={16} />
              Распознать снова
            </button>
          )}
          <button type="button" className="button secondary" onClick={onClose}>
            Закрыть
          </button>
          {!readonly && (
            <Submit pending={action.isPending}>Сохранить расход</Submit>
          )}
        </footer>
      </Form>
      {isAdmin && !["queued", "processing"].includes(receipt.status) && (
        <ReceiptModeration receipt={receipt} onClose={onClose} />
      )}
      <ReceiptComments receiptId={receipt.id} />
    </Modal>
  );
}
