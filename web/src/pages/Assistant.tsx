import { t, getLocale } from "../i18n";
import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import {
  ArrowRight,
  Camera,
  ChevronRight,
  Clock3,
  Paperclip,
  ScanLine,
  Search,
  Send,
  Sparkles,
} from "lucide-react";
import { api, queryClient, send, useAction } from "../api";
import { ReportCard } from "../ReportCard";
import { useApp } from "../context";
import type { Insight, Job, Message, Receipt } from "../types";
import {
  Badge,
  Empty,
  ErrorBox,
  Loading,
  PageHeading,
  amount,
  dateLabel,
  counted,
} from "../ui";

function useJobs() {
  return useQuery({
    queryKey: ["jobs"],
    queryFn: () => api<Job[]>("/jobs"),
    refetchInterval: 3000,
  });
}

export function Assistant() {
  const { month, prefs, open, navigate, isAdmin } = useApp();
  const messages = useQuery({
    queryKey: ["chat"],
    queryFn: ({ signal }) => api<Message[]>("/chat", { signal }),
    refetchInterval: 3000,
  });
  const jobs = useJobs();
  const [text, setText] = useState("");
  const [older, setOlder] = useState<Message[]>([]);
  const [hasOlder, setHasOlder] = useState(true);
  const body = useRef<HTMLDivElement>(null);
  const followLatest = useRef(true);
  const pending = jobs.data?.some(
    (j) =>
      ["chat", "analysis", "receipt"].includes(j.kind) &&
      ["queued", "running"].includes(j.status),
  );
  const draftRequest = useRef({ signature: "", key: "" });
  type QuickReport = "summary" | "categories" | "prices";
  const action = useMutation({
    mutationFn: (value: {
      text: string;
      report?: QuickReport;
      request_key: string;
    }) => send("/chat", { ...value, month }),
    onSuccess: async (_, value) => {
      setText((current) => (current === value.text ? "" : current));
      draftRequest.current = { signature: "", key: "" };
      await queryClient.invalidateQueries({ queryKey: ["chat"] });
      await queryClient.invalidateQueries({ queryKey: ["jobs"] });
    },
  });
  function submit(message = text, report?: QuickReport) {
    if (!message.trim() || action.isPending) return;
    followLatest.current = true;
    const signature = JSON.stringify([message.trim(), report, month]);
    if (draftRequest.current.signature !== signature)
      draftRequest.current = { signature, key: crypto.randomUUID() };
    action.mutate({
      text: message.trim(),
      report,
      request_key: draftRequest.current.key,
    });
  }
  const more = useAction(async () => {
    const first = older[0] ?? messages.data?.[0];
    if (!first) return;
    const rows = await api<Message[]>(`/chat?before=${first.id}`);
    setOlder((previous) => [...rows, ...previous]);
    if (rows.length < 60) setHasOlder(false);
  });
  const receiptAction = useAction(async (id: string) => {
    const receipt = await api<Receipt>(`/receipts/${id}`);
    open({ type: "receipt", receipt });
  });
  const latestMessageId = messages.data?.at(-1)?.id;
  useEffect(() => {
    const element = body.current;
    if (element && followLatest.current)
      element.scrollTo({
        top: element.scrollHeight,
        behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches
          ? "instant"
          : "smooth",
      });
  }, [latestMessageId, pending]);
  return (
    <>
      <PageHeading
        eyebrow={t("ЛИЧНЫЙ ПОМОЩНИК")}
        title={t("Поговорим о ваших деньгах")}
        text={t(
          "Спросите о любом периоде, найдите покупку и сравните свои цены. Общий чат выбранной организации.",
        )}
        actions={
          <Badge status={prefs?.provider === "disabled" ? "skipped" : "posted"}>
            {prefs?.provider === "ollama"
              ? t("Локальный AI · CPU")
              : prefs?.provider === "openai"
                ? "OpenAI"
                : t("AI отключён")}
          </Badge>
        }
      />
      <section className="panel chat-panel">
        <div
          className="chat-body"
          ref={body}
          onScroll={() => {
            const element = body.current;
            if (element)
              followLatest.current =
                element.scrollHeight -
                  element.scrollTop -
                  element.clientHeight <
                120;
          }}
        >
          {messages.isPending ? (
            <Loading />
          ) : !messages.data?.length ? (
            <div className="chat-welcome">
              <div className="assistant-orb">
                <Sparkles size={31} />
              </div>
              <h2>{t("Ваши финансы, понятным языком")}</h2>
              <p>
                {t("Я помогу разобрать покупки и увидеть привычки.")}
                <br />
                {t(
                  "Спросите «Сколько ушло на продукты в августе?» или «Найди LAPTE».",
                )}
              </p>
              <div className="prompt-grid">
                <button onClick={() => open({ type: "upload" })}>
                  <Camera size={22} />
                  <strong>{t("Отправить чек")}</strong>
                  <span>{t("Распознать товары и категории")}</span>
                  <ArrowRight size={17} />
                </button>
                <button
                  onClick={() =>
                    setText(t("На чём я могу сэкономить в этом месяце?"))
                  }
                >
                  <Sparkles size={22} />
                  <strong>{t("Найти экономию")}</strong>
                  <span>{t("На основе моей истории")}</span>
                  <ArrowRight size={17} />
                </button>
                <button
                  onClick={() =>
                    setText(
                      t(
                        "Найди мои покупки LAPTE за последние 3 месяца и сравни цены",
                      ),
                    )
                  }
                >
                  <ScanLine size={22} />
                  <strong>{t("Найти покупку")}</strong>
                  <span>{t("Товары, суммы и исходные чеки")}</span>
                  <ArrowRight size={17} />
                </button>
              </div>
            </div>
          ) : (
            <>
              {hasOlder &&
                (messages.data.length === 60 || older.length > 0) && (
                  <button
                    className="text-button load-older"
                    disabled={more.isPending}
                    onClick={() => more.mutate(undefined)}
                  >
                    {t("Загрузить более ранние сообщения")}
                  </button>
                )}
              {[...older, ...messages.data]
                .filter(
                  (m, i, list) => list.findIndex((x) => x.id === m.id) === i,
                )
                .map((message) => (
                  <div
                    className={`chat-message ${message.role}`}
                    key={message.id}
                  >
                    {message.role === "assistant" && (
                      <span className="bot-avatar">
                        <Sparkles size={17} />
                      </span>
                    )}
                    <div className="message-content">
                      <div
                        className={
                          message.details.error
                            ? "message-bubble message-error"
                            : "message-bubble"
                        }
                      >
                        {message.text
                          .split(/(\*\*[^*]+\*\*)/g)
                          .map((part, index) =>
                            part.startsWith("**") && part.endsWith("**") ? (
                              <strong key={index}>{part.slice(2, -2)}</strong>
                            ) : (
                              part
                            ),
                          )}
                      </div>
                      {message.details.reports?.map((report, index) => (
                        <ReportCard key={index} report={report} compact />
                      ))}
                      {message.receipt_id && (
                        <button
                          className="chat-receipt"
                          onClick={() =>
                            receiptAction.mutate(message.receipt_id!)
                          }
                        >
                          <span className="round-icon mint">
                            <ScanLine size={21} />
                          </span>
                          <span>
                            <strong>{t("Чек и товары")}</strong>
                            <small>{t("Открыть результат и проверить")}</small>
                          </span>
                          <ChevronRight size={20} />
                        </button>
                      )}
                      <small className="message-time">
                        {new Intl.DateTimeFormat(getLocale(), {
                          hour: "2-digit",
                          minute: "2-digit",
                        }).format(new Date(message.created_at))}
                        {message.details.provider
                          ? ` · ${message.details.provider === "reports" ? t("Расчёт Finora") : message.details.provider === "ollama" ? t("Локальная модель") : "OpenAI"}`
                          : ""}
                      </small>
                    </div>
                  </div>
                ))}
            </>
          )}
          {pending && (
            <div className="thinking">
              <span />
              <span />
              <span />
              {jobs.data?.find((j) => ["queued", "running"].includes(j.status))
                ?.progress || t("Подготавливаю ответ…")}
            </div>
          )}
        </div>
        <div className="chat-bottom">
          <div
            className="chat-quick-reports"
            role="group"
            aria-label={t("Быстрые отчёты")}
          >
            <span>{t("За выбранный месяц")}</span>
            <button
              type="button"
              disabled={action.isPending}
              onClick={() => submit(t("Покажи финансовую сводку"), "summary")}
            >
              {t("Сводка")}
            </button>
            <button
              type="button"
              disabled={action.isPending}
              onClick={() =>
                submit(t("Покажи расходы по категориям"), "categories")
              }
            >
              {t("Категории")}
            </button>
            <button
              type="button"
              disabled={action.isPending}
              onClick={() => submit(t("Сравни цены в моих чеках"), "prices")}
            >
              {t("Мои цены")}
            </button>
          </div>
          <ErrorBox
            error={
              action.error ??
              receiptAction.error ??
              messages.error ??
              more.error
            }
          />
          {prefs?.provider === "disabled" && (
            <div className="chat-notice">
              {t("Быстрые отчёты работают без AI. Для свободных вопросов")}{" "}
              {isAdmin ? (
                <button
                  className="text-button"
                  onClick={() => navigate("settings")}
                >
                  {t("подключите AI")}
                  <ArrowRight size={14} />
                </button>
              ) : (
                t("попросите администратора подключить AI.")
              )}
            </div>
          )}
          <form
            className="composer"
            onSubmit={(e) => {
              e.preventDefault();
              submit();
            }}
          >
            <button
              type="button"
              className="icon-button"
              aria-label={t("Прикрепить фото чека")}
              onClick={() => open({ type: "upload" })}
            >
              <Paperclip size={23} />
            </button>
            <textarea
              aria-label={t("Сообщение помощнику")}
              rows={1}
              maxLength={3000}
              placeholder={t("Спросите о расходах или прикрепите чек…")}
              value={text}
              onChange={(e) => setText(e.target.value)}
              onKeyDown={(e) => {
                if (
                  e.key === "Enter" &&
                  !e.shiftKey &&
                  !e.nativeEvent.isComposing
                ) {
                  e.preventDefault();
                  submit();
                }
              }}
            />
            <button
              type="submit"
              className="send-button"
              aria-label={t("Отправить сообщение")}
              disabled={!text.trim() || action.isPending}
            >
              <Send size={19} />
            </button>
          </form>
          <p className="composer-note">
            {t(
              "AI может ошибаться. Числа в отчётах рассчитываются по вашим подтверждённым операциям.",
            )}
          </p>
        </div>
      </section>
    </>
  );
}

export function Receipts() {
  const { open } = useApp();
  const [search, setSearch] = useState("");
  const [offset, setOffset] = useState(0);
  const query = useQuery({
    queryKey: ["receipts", search, offset],
    queryFn: () =>
      api<{ items: Receipt[]; total: number }>(
        `/receipts?search=${encodeURIComponent(search)}&offset=${offset}`,
      ),
    refetchInterval: 4000,
  });
  return (
    <>
      <PageHeading
        eyebrow={t("ПОКУПКИ ПО ТОВАРАМ")}
        title={t("Чеки, которые не теряются")}
        text={t(
          "Оригинал, товары и категории каждой покупки. Фото и молдавские чеки MEV.",
        )}
        actions={
          <button
            className="button primary"
            onClick={() => open({ type: "upload" })}
          >
            <ScanLine size={18} />
            {t("Добавить чек")}
          </button>
        }
      />
      <div className="receipt-toolbar">
        <div className="search-field">
          <Search size={18} />
          <input
            aria-label={t("Поиск чеков")}
            value={search}
            onChange={(e) => {
              setSearch(e.target.value);
              setOffset(0);
            }}
            placeholder={t("Найти магазин")}
          />
        </div>
        <span className="muted">
          {counted(query.data?.total ?? 0, [t("чек"), t("чека"), t("чеков")])}
        </span>
      </div>
      <ErrorBox error={query.error} />
      {query.isPending ? (
        <Loading />
      ) : query.data?.items.length ? (
        <>
          <div className="receipts-grid">
            {query.data.items.map((receipt) => (
              <button
                key={receipt.id}
                className="panel receipt-card"
                onClick={() => open({ type: "receipt", receipt })}
              >
                <div className="receipt-card-top">
                  <span className="round-icon mint">
                    <ScanLine size={23} />
                  </span>
                  <Badge status={receipt.status} />
                </div>
                <h2>{receipt.merchant || t("Новый чек")}</h2>
                <p>
                  {receipt.purchased_on
                    ? dateLabel(receipt.purchased_on)
                    : t("Дата ещё не распознана")}{" "}
                  · {receipt.source === "mev" ? "MEV" : t("Фото чека")}
                </p>
                <div className="receipt-card-lines">
                  {receipt.items.slice(0, 3).map((i) => (
                    <div key={i.id}>
                      <span>{i.name}</span>
                      <span>{amount(i.total_minor, receipt.currency)}</span>
                    </div>
                  ))}
                  {receipt.items.length > 3 && (
                    <small>
                      {t("и ещё")}{" "}
                      {counted(receipt.items.length - 3, [
                        t("товар"),
                        t("товара"),
                        t("товаров"),
                      ])}
                    </small>
                  )}
                  {!receipt.items.length && (
                    <span className="muted">
                      {receipt.status === "queued" ||
                      receipt.status === "processing"
                        ? t("Распознаём товары…")
                        : t("Откройте чек, чтобы заполнить товары")}
                    </span>
                  )}
                </div>
                <div className="receipt-card-total">
                  <span>
                    {counted(receipt.items.length, [
                      t("товар"),
                      t("товара"),
                      t("товаров"),
                    ])}
                  </span>
                  <strong>
                    {receipt.total_minor != null
                      ? amount(receipt.total_minor, receipt.currency)
                      : "—"}
                  </strong>
                </div>
                <div className="receipt-perforation" aria-hidden="true" />
              </button>
            ))}
          </div>
          <div className="pagination">
            <span>
              {t("Показано")} {offset + 1}–{offset + query.data.items.length}{" "}
              {t("из")} {query.data.total}
            </span>
            <div>
              <button
                className="button secondary"
                disabled={offset === 0}
                onClick={() => setOffset(offset - 30)}
              >
                {t("Назад")}
              </button>
              <button
                className="button secondary"
                disabled={offset + 30 >= query.data.total}
                onClick={() => setOffset(offset + 30)}
              >
                {t("Далее")}
              </button>
            </div>
          </div>
        </>
      ) : (
        <section className="panel">
          <Empty
            icon={<ScanLine size={30} />}
            title={t("Ваша коллекция чеков начинается здесь")}
            text={t(
              "Отправьте фото чека. Finora сохранит оригинал, выделит товары и поможет разобраться в расходах.",
            )}
            action={
              <button
                className="button primary"
                onClick={() => open({ type: "upload" })}
              >
                <Camera size={18} />
                {t("Загрузить первый чек")}
              </button>
            }
          />
        </section>
      )}
    </>
  );
}

export function Insights() {
  const { month, navigate, prefs, isAdmin } = useApp();
  const query = useQuery({
    queryKey: ["insights", month],
    queryFn: () => api<Insight[]>(`/insights?month=${month}`),
  });
  const action = useAction(
    () =>
      send("/chat", {
        month,
        text: t(
          "Проанализируй мои расходы за выбранный месяц. Предложи конкретные способы сократить лишние траты и более дешёвые замены привычных покупок. Раздели факты и предположения.",
        ),
      }),
    () => navigate("assistant"),
  );
  const potential = query.data?.reduce((s, c) => s + c.saving_minor, 0) ?? 0;
  return (
    <>
      <PageHeading
        eyebrow={t("ОСМЫСЛЕННЫЕ РАСХОДЫ")}
        title={t("Маленькие изменения, больше свободы")}
        text={t("Наблюдения из вашей истории и идеи, которые стоит проверить.")}
        actions={
          isAdmin && (
            <button
              className="button primary"
              disabled={action.isPending}
              onClick={() =>
                prefs?.provider === "disabled"
                  ? navigate("settings")
                  : action.mutate(undefined)
              }
            >
              <Sparkles size={18} />
              {prefs?.provider === "disabled"
                ? t("Подключить AI-анализ")
                : t("Разобрать с AI")}
            </button>
          )
        }
      />
      <div className="insights-intro panel">
        <span className="insight-icon">
          <Sparkles size={29} />
        </span>
        <div>
          <h2>
            {potential > 0
              ? t("Сценарии экономии: {0}", amount(potential))
              : t("Сначала факты. Затем полезные решения.")}
          </h2>
          <p>
            {potential > 0
              ? t(
                  "Расчёт при сокращении отдельных категорий на 20%. Реальная экономия зависит от ваших решений.",
                )
              : t(
                  "Сравнение с прошлым месяцем, контроль лимитов и цены из ваших чеков работают даже без AI.",
                )}
          </p>
        </div>
      </div>
      <ErrorBox error={query.error ?? action.error} />
      {query.isPending ? (
        <Loading />
      ) : (
        <div className="insights-grid">
          {query.data?.map((card) => (
            <article className="panel insight-card" key={card.id}>
              <div className="between">
                <span
                  className={`round-icon ${card.kind === "budget" ? "peach" : card.kind === "price" ? "lavender" : "mint"}`}
                >
                  {card.kind === "trend" ? (
                    <Clock3 size={20} />
                  ) : (
                    <Sparkles size={20} />
                  )}
                </span>
                <Badge>
                  {
                    (
                      {
                        budget: t("Лимит"),
                        trend: t("Изменение"),
                        scenario: t("Сценарий"),
                        price: t("Ваши цены"),
                        info: t("Начало"),
                      } as Record<string, string>
                    )[card.kind]
                  }
                </Badge>
              </div>
              <h2>{card.title}</h2>
              <p>{card.text}</p>
              {card.saving_minor > 0 && (
                <strong className="saving">
                  {amount(card.saving_minor)}
                  <span>{t("в этом сценарии")}</span>
                </strong>
              )}
              <footer>{card.basis}</footer>
            </article>
          ))}
        </div>
      )}
      <div className="notice">
        {t(
          "Актуальные цены других магазинов сервис не получает автоматически. AI может предложить замену, но её цену и сопоставимость нужно проверить. Советы не меняют ваши записи.",
        )}
      </div>
    </>
  );
}
