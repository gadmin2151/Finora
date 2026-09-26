import { CategoryRefresh } from "../CategoryRefresh";
import { ProfileSettings } from "../Profile";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Archive,
  Check,
  Cloud,
  Cpu,
  Download,
  HardDrive,
  KeyRound,
  Pencil,
  Plus,
  ShieldCheck,
  Sparkles,
  Trash2,
  Wallet,
} from "lucide-react";
import { organizationUrl, api, send, useAction } from "../api";
import { useApp } from "../context";
import type { Account, Category, Job, Preferences, Rule } from "../types";
import {
  AccountSelect,
  Badge,
  CategoryIcon,
  CategorySelect,
  CurrencySelect,
  ErrorBox,
  Field,
  Form,
  Loading,
  Modal,
  MoneyInput,
  PageHeading,
  Submit,
  amount,
  decimal,
} from "../ui";

export function Accounts() {
  const { accounts, toast } = useApp();
  const [editing, setEditing] = useState<Account | "new" | null>(null);
  const archive = useAction(
    (id: string) => send(`/accounts/${id}/archive`),
    () => toast("Статус счёта обновлён"),
  );
  const totals: Record<string, number> = {};
  for (const a of accounts)
    totals[a.currency] = (totals[a.currency] ?? 0) + a.balance_minor;
  return (
    <>
      <PageHeading
        eyebrow="ВСЕ ДЕНЬГИ В ОДНОМ МЕСТЕ"
        title="Ваши счета"
        text="Наличные, карты и накопления. Переводы между своими счетами не считаются расходами."
        actions={
          <button className="button primary" onClick={() => setEditing("new")}>
            <Plus size={18} />
            Новый счёт
          </button>
        }
      />
      <div className="account-totals">
        {Object.entries(totals).map(([c, total]) => (
          <div key={c}>
            <span>Общий остаток · {c}</span>
            <strong>{amount(total, c)}</strong>
          </div>
        ))}
      </div>
      <ErrorBox error={archive.error} />
      <div className="accounts-grid">
        {accounts.map((a) => (
          <article
            className={`panel account-card ${a.archived ? "archived" : ""}`}
            key={a.id}
            style={{ borderTopColor: a.color }}
          >
            <div className="between">
              <span
                className="round-icon"
                style={{ color: a.color, background: `${a.color}17` }}
              >
                <Wallet size={24} />
              </span>
              <Badge>
                {a.archived
                  ? "Архив"
                  : (
                      {
                        cash: "Наличные",
                        card: "Карта",
                        savings: "Накопления",
                      } as Record<string, string>
                    )[a.kind]}
              </Badge>
            </div>
            <h2>{a.name}</h2>
            <strong className="account-amount">
              {amount(a.balance_minor, a.currency)}
            </strong>
            <p>Начальный остаток: {amount(a.opening_minor, a.currency)}</p>
            <div className="account-footer">
              <button className="text-button" onClick={() => setEditing(a)}>
                <Pencil size={15} />
                Изменить
              </button>
              <button
                className="text-button muted"
                disabled={archive.isPending}
                onClick={() => archive.mutate(a.id)}
              >
                <Archive size={15} />
                {a.archived ? "Восстановить" : "В архив"}
              </button>
            </div>
          </article>
        ))}
      </div>
      <div className="notice">
        Остатки разных валют не складываются без курса. Доходы и расходы в
        отчётах пересчитываются в MDL по курсу, указанному при создании
        операции.
      </div>
      {editing && (
        <AccountForm
          account={editing === "new" ? undefined : editing}
          onClose={() => setEditing(null)}
          onDone={() => {
            setEditing(null);
            toast("Счёт сохранён");
          }}
        />
      )}
    </>
  );
}

function AccountForm({
  account,
  onClose,
  onDone,
}: {
  account?: Account;
  onClose: () => void;
  onDone: () => void;
}) {
  const [name, setName] = useState(account?.name ?? "");
  const [currency, setCurrency] = useState<string>(account?.currency ?? "MDL");
  const [kind, setKind] = useState(account?.kind ?? "card");
  const [balance, setBalance] = useState(decimal(account?.opening_minor));
  const [color, setColor] = useState(account?.color ?? "#169e8a");
  const action = useAction(
    () =>
      send(
        account ? `/accounts/${account.id}` : "/accounts",
        { name, currency, kind, opening_balance: balance, color },
        account ? "PUT" : "POST",
      ),
    onDone,
  );
  return (
    <Modal
      title={account ? "Изменить счёт" : "Новый счёт"}
      description="Начальный остаток — деньги, которые уже есть на счёте до начала учёта."
      onClose={onClose}
    >
      <Form onSubmit={() => action.mutate(undefined)}>
        <div className="form-grid">
          <Field label="Название" wide>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
              autoFocus
              maxLength={100}
              placeholder="Например, maib · основная карта"
            />
          </Field>
          <Field label="Тип">
            <select value={kind} onChange={(e) => setKind(e.target.value)}>
              <option value="card">Карта</option>
              <option value="cash">Наличные</option>
              <option value="savings">Накопления</option>
            </select>
          </Field>
          <Field label="Валюта">
            <CurrencySelect value={currency} onChange={setCurrency} />
          </Field>
          <Field label="Начальный остаток">
            <MoneyInput
              value={balance}
              onChange={setBalance}
              min="-1000000000"
            />
            {account && (
              <small>
                Исправление изменит текущий баланс, но не доходы и расходы.
                Валюта счёта с операциями защищена.
              </small>
            )}
          </Field>
          <Field label="Цвет">
            <input
              type="color"
              value={color}
              onChange={(e) => setColor(e.target.value)}
            />
          </Field>
        </div>
        <ErrorBox error={action.error} />
        <footer className="modal-footer">
          <Submit pending={action.isPending} />
        </footer>
      </Form>
    </Modal>
  );
}

export default function Settings() {
  const [tab, setTab] = useState("profile");
  const { prefs, isAdmin } = useApp();
  if (!isAdmin)
    return (
      <>
        <PageHeading
          eyebrow="ВАШ АККАУНТ"
          title="Ваш профиль"
          text="Фото, имя, пароль и активные сеансы."
        />
        <ProfileSettings />
        <SecuritySettings />
      </>
    );
  return (
    <>
      <PageHeading
        eyebrow="ВАШ СЕРВИС — ВАШИ ПРАВИЛА"
        title="Настроено под вас"
        text="AI, категории и защита данных. Всё необходимое для комфортного учёта."
      />
      <div
        className="settings-tabs"
        role="tablist"
        aria-label="Разделы настроек"
      >
        {[
          ["profile", "Мой профиль", Pencil],
          ["ai", "AI и распознавание", Sparkles],
          ["categories", "Категории и правила", Wallet],
          ["security", "Безопасность", ShieldCheck],
          ["data", "Ваши данные", HardDrive],
        ].map(([id, text, Icon]) => {
          const Component = Icon as typeof Sparkles;
          return (
            <button
              key={String(id)}
              role="tab"
              aria-selected={tab === id}
              className={tab === id ? "active" : ""}
              onClick={() => setTab(String(id))}
            >
              <Component size={18} />
              {String(text)}
            </button>
          );
        })}
      </div>
      {tab === "profile" ? (
        <ProfileSettings />
      ) : tab === "ai" ? (
        prefs ? (
          <AISettings initial={prefs} />
        ) : (
          <Loading />
        )
      ) : tab === "categories" ? (
        <CategorySettings />
      ) : tab === "security" ? (
        <SecuritySettings />
      ) : (
        <DataSettings />
      )}
    </>
  );
}

type ModelList = {
  online: boolean;
  models: { name: string; size: number }[];
  catalog: {
    name: string;
    title: string;
    size: string;
    ram: string;
    vision: boolean;
    description: string;
  }[];
};
function AISettings({ initial }: { initial: Preferences }) {
  const { accounts, toast } = useApp();
  const [provider, setProvider] = useState(initial.provider);
  const [model, setModel] = useState(initial.model);
  const [vision, setVision] = useState(initial.vision_model);
  const [apiKey, setApiKey] = useState("");
  const [limit, setLimit] = useState(initial.monthly_request_limit);
  const [automatic, setAutomatic] = useState(initial.auto_post);
  const [account, setAccount] = useState(initial.default_account_id ?? "");
  const [clearKey, setClearKey] = useState(false);
  const models = useQuery({
    queryKey: ["ai-models"],
    queryFn: () => api<ModelList>("/ai/models"),
    refetchInterval: 12000,
  });
  const jobs = useQuery({
    queryKey: ["jobs"],
    queryFn: () => api<Job[]>("/jobs"),
    refetchInterval: 3000,
  });
  const save = useAction(
    () =>
      send(
        "/settings",
        {
          provider,
          model,
          vision_model: vision,
          openai_key: apiKey || null,
          clear_key: clearKey,
          monthly_request_limit: limit,
          auto_post: automatic,
          default_account_id: account || null,
        },
        "PUT",
      ),
    () => {
      setApiKey("");
      setClearKey(false);
      toast("Настройки сохранены");
    },
  );
  const pull = useAction(
    (model: string) => send("/ai/models/pull", { model }),
    () => toast("Загрузка поставлена в очередь"),
  );
  const test = useAction(
    () => send("/ai/test"),
    () => toast("Проверка запущена"),
  );
  const changeProvider = (value: Preferences["provider"]) => {
    setProvider(value);
    if (value === "openai" && model.startsWith("qwen")) {
      setModel("gpt-4.1-mini");
      setVision("gpt-4.1-mini");
    }
    if (value === "ollama" && model.startsWith("gpt")) {
      setModel("qwen3:4b-instruct");
      setVision("gemma3:4b");
    }
  };
  const relevantJobs = jobs.data
    ?.filter((j) => ["model_pull", "ai_test"].includes(j.kind))
    .slice(0, 5);
  return (
    <div className="settings-grid">
      <section className="panel settings-panel">
        <h2>Помощник и распознавание</h2>
        <p>
          Выберите, где обрабатывать данные. Смена провайдера происходит только
          по вашему выбору.
        </p>
        <Form onSubmit={() => save.mutate(undefined)}>
          <div className="provider-grid">
            {[
              {
                value: "disabled" as const,
                label: "Без AI",
                icon: ShieldCheck,
                text: "Учёт и базовая аналитика",
              },
              {
                value: "ollama" as const,
                label: "Локальный AI",
                icon: Cpu,
                text: "CPU и RAM вашего сервера",
              },
              {
                value: "openai" as const,
                label: "OpenAI",
                icon: Cloud,
                text: "Модели через API",
              },
            ].map((p) => (
              <button
                type="button"
                key={p.value}
                className={`provider-card ${provider === p.value ? "selected" : ""}`}
                onClick={() => changeProvider(p.value)}
              >
                <p.icon size={23} />
                <strong>{p.label}</strong>
                <small>{p.text}</small>
                {provider === p.value && (
                  <Check className="provider-check" size={16} />
                )}
              </button>
            ))}
          </div>
          {provider !== "disabled" && (
            <>
              <div className="form-grid">
                <Field label="Модель для текста и анализа">
                  <input
                    required
                    value={model}
                    onChange={(e) => setModel(e.target.value)}
                    maxLength={100}
                    list={
                      provider === "ollama" ? "installed-models" : undefined
                    }
                  />
                </Field>
                <Field
                  label="Модель для фотографий"
                  hint="Нужна модель с поддержкой изображений"
                >
                  <input
                    required
                    value={vision}
                    onChange={(e) => setVision(e.target.value)}
                    maxLength={100}
                    list={
                      provider === "ollama" ? "installed-models" : undefined
                    }
                  />
                </Field>
              </div>
              <datalist id="installed-models">
                {models.data?.models.map((m) => (
                  <option key={m.name} value={m.name} />
                ))}
              </datalist>
              {provider === "openai" && (
                <>
                  <Field
                    label={
                      initial.has_openai_key
                        ? "Новый API-ключ · текущий уже сохранён"
                        : "API-ключ OpenAI"
                    }
                    hint="Ключ хранится зашифрованным на сервере и не возвращается в браузер."
                  >
                    <input
                      type="password"
                      autoComplete="off"
                      placeholder="sk-…"
                      value={apiKey}
                      onChange={(e) => setApiKey(e.target.value)}
                      maxLength={512}
                    />
                  </Field>
                  <label className="check-field">
                    <input
                      type="checkbox"
                      checked={clearKey}
                      onChange={(e) => setClearKey(e.target.checked)}
                    />
                    Удалить сохранённый ключ
                  </label>
                  <div className="notice">
                    Фото чеков и данные для анализа отправляются в OpenAI.
                    Подписка ChatGPT не заменяет API-ключ; использование API
                    оплачивается отдельно.
                  </div>
                </>
              )}
              <Field
                label="Лимит AI-запросов в месяц"
                hint="Включая неудачные попытки. Это ограничение количества, а не денежный лимит API."
              >
                <input
                  type="number"
                  min="1"
                  max="10000"
                  required
                  value={limit}
                  onChange={(e) => setLimit(Number(e.target.value))}
                />
              </Field>
            </>
          )}
          <div className="settings-divider" />
          <Field label="Основной счёт для чеков">
            <AccountSelect
              accounts={accounts}
              value={account}
              onChange={setAccount}
              optional
            />
          </Field>
          <label className="switch-row">
            <span>
              <strong>Добавлять проверенные чеки автоматически</strong>
              <small>
                Только если суммы совпали, категории определены и нет похожего
                расхода.
              </small>
            </span>
            <input
              role="switch"
              type="checkbox"
              checked={automatic}
              onChange={(e) => setAutomatic(e.target.checked)}
            />
          </label>
          <ErrorBox error={save.error ?? test.error} />
          <div className="settings-actions">
            <Submit pending={save.isPending} />
            <button
              type="button"
              className="button secondary"
              disabled={test.isPending}
              onClick={() => test.mutate(undefined)}
            >
              Проверить сохранённое подключение
            </button>
          </div>
        </Form>
      </section>
      <aside className="settings-side">
        <section className="panel settings-panel">
          <div className="between">
            <h2>Использование AI</h2>
            <Badge>Этот месяц</Badge>
          </div>
          <strong className="usage-number">
            {initial.usage.requests}
            <small> / {initial.monthly_request_limit} запросов</small>
          </strong>
          <div className="meter">
            <span
              style={{
                width: `${Math.min(100, (initial.usage.requests / initial.monthly_request_limit) * 100)}%`,
              }}
            />
          </div>
          <p>
            {initial.usage.input_tokens.toLocaleString("ru-RU")} входящих ·{" "}
            {initial.usage.output_tokens.toLocaleString("ru-RU")} исходящих
            токенов
          </p>
        </section>
        <section className="panel settings-panel">
          <span className="round-icon mint">
            <ShieldCheck size={24} />
          </span>
          <h2>Личные данные остаются личными</h2>
          <p>
            С локальной моделью обработка происходит на вашем сервере. Модель
            сначала скачивается на диск, затем загружается в RAM. Видеокарта не
            требуется.
          </p>
          <p>
            Рекомендации AI не могут переводить деньги, удалять записи или
            менять ваш учёт.
          </p>
        </section>
        {relevantJobs?.length ? (
          <section className="panel settings-panel">
            <h2>Задачи AI</h2>
            {relevantJobs.map((j) => (
              <div className="job-row" key={j.id}>
                <div className="between">
                  <strong>
                    {j.kind === "model_pull"
                      ? j.payload.model
                      : "Проверка подключения"}
                  </strong>
                  <Badge status={j.status} />
                </div>
                <p>{j.progress}</p>
                {j.result.answer && <small>{j.result.answer}</small>}
              </div>
            ))}
          </section>
        ) : null}
      </aside>
      <section className="panel settings-panel model-section">
        <div className="panel-heading">
          <div>
            <h2>Локальные модели</h2>
            <p>
              Скачайте модель один раз. После загрузки интернет для её работы не
              нужен.
            </p>
          </div>
          <Badge status={models.data?.online ? "posted" : "skipped"}>
            {models.isPending
              ? "Проверяю подключение…"
              : models.data?.online
                ? "Ollama подключён"
                : "Ollama недоступен"}
          </Badge>
        </div>
        {!models.isPending && !models.data?.online && (
          <div className="notice">
            Для локального AI включите профиль Docker <code>local-ai</code> по
            инструкции в README. После запуска здесь появятся модели.
          </div>
        )}
        <ErrorBox error={pull.error ?? models.error} />
        <div className="model-grid">
          {models.data?.catalog.map((m) => {
            const installed = models.data.models.some((i) => i.name === m.name);
            const pending = jobs.data?.find(
              (j) =>
                j.kind === "model_pull" &&
                j.payload.model === m.name &&
                ["queued", "running"].includes(j.status),
            );
            return (
              <article className="model-card" key={m.name}>
                <div className="between">
                  <Cpu size={24} />
                  {m.vision && <Badge>Фото + текст</Badge>}
                </div>
                <h3>{m.title}</h3>
                <p>{m.description}</p>
                <div className="model-specs">
                  <span>Диск ≈ {m.size}</span>
                  <span>RAM {m.ram}*</span>
                </div>
                <button
                  className="button secondary"
                  disabled={
                    !models.data?.online ||
                    !!pending ||
                    pull.isPending ||
                    installed
                  }
                  onClick={() => pull.mutate(m.name)}
                >
                  {installed ? (
                    <>
                      <Check size={16} />
                      Установлена
                    </>
                  ) : pending ? (
                    pending.progress
                  ) : (
                    <>
                      <Download size={16} />
                      Загрузить
                    </>
                  )}
                </button>
              </article>
            );
          })}
        </div>
        <small className="muted">
          * Ориентиры для модели. Серверу и базе нужна дополнительная память.
          Длинные чеки увеличивают расход RAM; скорость зависит от CPU.
        </small>
      </section>
    </div>
  );
}

function CategorySettings() {
  const { categories, toast } = useApp();
  const rules = useQuery({
    queryKey: ["rules"],
    queryFn: () => api<Rule[]>("/rules"),
  });
  const [editing, setEditing] = useState<Category | "new" | null>(null);
  const [pattern, setPattern] = useState("");
  const [field, setField] = useState("item");
  const [category, setCategory] = useState("");
  const save = useAction(
    () => send("/rules", { pattern, field, category_id: category }),
    () => {
      setPattern("");
      toast("Правило добавлено");
    },
  );
  const remove = useAction((id: string) =>
    send(`/rules/${id}`, undefined, "DELETE"),
  );
  return (
    <div className="two-columns">
      <section className="panel settings-panel">
        <div className="between">
          <h2>Категории</h2>
          <button className="text-button" onClick={() => setEditing("new")}>
            <Plus size={16} />
            Добавить
          </button>
        </div>
        <CategoryRefresh />
        <div className="category-settings">
          {categories.map((c) => (
            <button key={c.id} onClick={() => setEditing(c)}>
              <CategoryIcon category={c} />
              <span>{c.name}</span>
              <Pencil size={15} />
            </button>
          ))}
        </div>
      </section>
      <section className="panel settings-panel">
        <h2>Ваши правила распределения</h2>
        <p>
          Правила имеют приоритет перед AI. Самое новое подходящее правило
          применяется первым. Чтобы применить правила к сохранённым чекам,
          нажмите «Перераспределить чеки».
        </p>
        <Form onSubmit={() => save.mutate(undefined)}>
          <div className="form-grid">
            <Field label="Искать в">
              <select value={field} onChange={(e) => setField(e.target.value)}>
                <option value="item">Названии товара</option>
                <option value="merchant">Названии магазина</option>
              </select>
            </Field>
            <Field label="Содержит текст">
              <input
                required
                minLength={2}
                maxLength={150}
                value={pattern}
                onChange={(e) => setPattern(e.target.value)}
                placeholder="Например, motorina"
              />
            </Field>
            <Field label="Категория" wide>
              <CategorySelect
                categories={categories}
                value={category}
                onChange={setCategory}
                required
              />
            </Field>
          </div>
          <ErrorBox error={save.error ?? remove.error ?? rules.error} />
          <Submit pending={save.isPending}>Добавить правило</Submit>
        </Form>
        <div className="rules-list">
          {rules.data?.map((r) => (
            <div key={r.id}>
              <span>
                <strong>{r.pattern}</strong>
                <small>
                  {r.field === "item" ? "Товар" : "Магазин"} →{" "}
                  {categories.find((c) => c.id === r.category_id)?.name}
                </small>
              </span>
              <button
                className="icon-button danger-hover"
                disabled={remove.isPending}
                aria-label={`Удалить правило ${r.pattern}`}
                onClick={() => remove.mutate(r.id)}
              >
                <Trash2 size={17} />
              </button>
            </div>
          ))}
        </div>
      </section>
      {editing && (
        <CategoryForm
          category={editing === "new" ? undefined : editing}
          onClose={() => setEditing(null)}
          onDone={() => {
            setEditing(null);
            toast("Категория сохранена");
          }}
        />
      )}
    </div>
  );
}

function CategoryForm({
  category,
  onClose,
  onDone,
}: {
  category?: Category;
  onClose: () => void;
  onDone: () => void;
}) {
  const [name, setName] = useState(category?.name ?? "");
  const [color, setColor] = useState(category?.color ?? "#169e8a");
  const [icon, setIcon] = useState(category?.icon ?? "tag");
  const save = useAction(
    () =>
      send(
        category ? `/categories/${category.id}` : "/categories",
        { name, color, icon, parent_id: category?.parent_id ?? null },
        category ? "PUT" : "POST",
      ),
    onDone,
  );
  return (
    <Modal
      title={category ? "Изменить категорию" : "Новая категория"}
      onClose={onClose}
    >
      <Form onSubmit={() => save.mutate(undefined)}>
        <div className="form-grid">
          <Field label="Название" wide>
            <input
              required
              autoFocus
              maxLength={100}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </Field>
          <Field label="Цвет">
            <input
              type="color"
              value={color}
              onChange={(e) => setColor(e.target.value)}
            />
          </Field>
          <Field label="Значок">
            <select value={icon} onChange={(e) => setIcon(e.target.value)}>
              {Object.entries({
                tag: "Метка",
                "shopping-basket": "Продукты",
                coffee: "Кафе",
                house: "Дом",
                car: "Автомобиль",
                train: "Транспорт",
                heart: "Здоровье",
                "shopping-bag": "Покупки",
                repeat: "Подписки",
                book: "Образование",
                plane: "Отдых",
                gift: "Подарки",
                apple: "Овощи и фрукты",
                beef: "Мясо",
                fish: "Рыба",
                milk: "Молочные продукты",
                croissant: "Выпечка",
                wheat: "Бакалея",
                candy: "Сладости",
                popcorn: "Снеки",
                "cup-soda": "Напитки",
                zap: "Энергетики",
                wine: "Алкоголь",
                cigarette: "Табак",
                utensils: "Готовая еда",
                "spray-can": "Бытовая химия",
                bath: "Гигиена",
                lamp: "Товары для дома",
                baby: "Для детей",
                "paw-print": "Питомцы",
                shirt: "Одежда",
                sparkles: "Косметика",
                pill: "Лекарства",
              }).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </select>
          </Field>
        </div>
        <ErrorBox error={save.error} />
        <footer className="modal-footer">
          <Submit pending={save.isPending} />
        </footer>
      </Form>
    </Modal>
  );
}

function SecuritySettings() {
  const { user, toast } = useApp();
  const [current, setCurrent] = useState("");
  const [password, setPassword] = useState("");
  const [repeat, setRepeat] = useState("");
  const sessions = useQuery({
    queryKey: ["sessions"],
    queryFn: () =>
      api<
        { id: string; device: string; current: boolean; created_at: string }[]
      >("/auth/sessions"),
  });
  const change = useAction(
    async () => {
      if (password !== repeat) throw new Error("Новые пароли не совпадают");
      return send("/auth/password", {
        current_password: current,
        new_password: password,
      });
    },
    () => {
      setCurrent("");
      setPassword("");
      setRepeat("");
      toast("Пароль изменён. Другие сеансы завершены");
    },
  );
  const revoke = useAction(
    (id: string) => send(`/auth/sessions/${id}`, undefined, "DELETE"),
    () => toast("Сеанс завершён"),
  );
  return (
    <div className="two-columns">
      <section className="panel settings-panel">
        <span className="round-icon mint">
          <KeyRound size={23} />
        </span>
        <h2>Вход в аккаунт</h2>
        <p>
          Логин: <strong>{user.username}</strong>. Регистрация закрыта. Пароль
          можно изменить здесь.
        </p>
        <Form onSubmit={() => change.mutate(undefined)}>
          <Field label="Текущий пароль">
            <input
              type="password"
              autoComplete="current-password"
              required
              maxLength={256}
              value={current}
              onChange={(e) => setCurrent(e.target.value)}
            />
          </Field>
          <Field label="Новый пароль" hint="Не менее 12 символов">
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
          <Field label="Повторите новый пароль">
            <input
              type="password"
              autoComplete="new-password"
              required
              minLength={12}
              maxLength={256}
              value={repeat}
              onChange={(e) => setRepeat(e.target.value)}
            />
          </Field>
          <ErrorBox error={change.error} />
          <Submit pending={change.isPending}>Изменить пароль</Submit>
        </Form>
      </section>
      <section className="panel settings-panel">
        <h2>Активные сеансы</h2>
        <p>После смены пароля остальные устройства выйдут из аккаунта.</p>
        <ErrorBox error={sessions.error ?? revoke.error} />
        {sessions.data?.map((s) => (
          <div className="session-row" key={s.id}>
            <div>
              <strong>{s.current ? "Этот браузер" : "Другой сеанс"}</strong>
              <p>{s.device}</p>
              <small>{new Date(s.created_at).toLocaleString("ru-RU")}</small>
            </div>
            {s.current ? (
              <Badge status="posted">Активен</Badge>
            ) : (
              <button
                className="text-button negative"
                disabled={revoke.isPending}
                onClick={() => revoke.mutate(s.id)}
              >
                Завершить
              </button>
            )}
          </div>
        ))}
      </section>
    </div>
  );
}

function DataSettings() {
  const audit = useQuery({
    queryKey: ["audit"],
    queryFn: () =>
      api<
        { id: string; action: string; created_at: string; entity_id: string }[]
      >("/audit"),
  });
  const names: Record<string, string> = {
    "transaction.created": "Операция добавлена",
    "transaction.edited": "Операция изменена",
    "transaction.voided": "Операция отменена",
    "debt.created": "Долг добавлен",
    "receipt.confirmed": "Чек подтверждён",
    "bill.paid": "Платёж оплачен",
    "settings.updated": "Настройки обновлены",
    "password.changed": "Пароль изменён",
  };
  return (
    <div className="two-columns">
      <section className="panel settings-panel">
        <span className="round-icon mint">
          <HardDrive size={24} />
        </span>
        <h2>Ваши данные принадлежат вам</h2>
        <p>
          Выгрузите операции для таблиц или все данные учёта в переносимом
          формате JSON.
        </p>
        <div className="export-buttons">
          <a
            className="button secondary"
            href={organizationUrl("/api/export.csv")}
            download
          >
            <Download size={18} />
            Операции CSV
          </a>
          <a
            className="button secondary"
            href={organizationUrl("/api/export.json")}
            download
          >
            <Download size={18} />
            Полный учёт JSON
          </a>
        </div>
        <div className="settings-divider" />
        <h3>Резервная копия сервера</h3>
        <p>
          Скрипт резервного копирования сохраняет базу, фотографии и настройки в
          зашифрованном архиве. Команды создания и восстановления описаны в
          README проекта.
        </p>
        <div className="notice">
          Экспорт JSON содержит данные учёта, но не файлы фото, пароли и
          API-ключи. Для полного восстановления используйте резервную копию
          сервера.
        </div>
      </section>
      <section className="panel settings-panel">
        <h2>Журнал изменений</h2>
        <p>Последние 100 действий с учётом и настройками.</p>
        <ErrorBox error={audit.error} />
        <div className="audit-list">
          {audit.data?.length ? (
            audit.data.map((row) => (
              <div key={row.id}>
                <Check size={15} />
                <span>
                  {names[row.action] ?? row.action}
                  <small>
                    {new Date(row.created_at).toLocaleString("ru-RU")}
                  </small>
                </span>
              </div>
            ))
          ) : (
            <p>Журнал пока пуст.</p>
          )}
        </div>
      </section>
    </div>
  );
}
