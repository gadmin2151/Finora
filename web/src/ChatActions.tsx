import { Check, X } from "lucide-react";
import { useMutation } from "@tanstack/react-query";
import { queryClient, send } from "./api";
import { useApp } from "./context";
import { t } from "./i18n";
import type { Message } from "./types";
import { ErrorBox } from "./ui";

const labels = () => ({
  amount: t("Сумма"),
  currency: t("Валюта"),
  occurred_on: t("Дата"),
  purchased_on: t("Дата покупки"),
  account_id: t("Счёт"),
  category_id: t("Категория"),
  name: t("Название"),
  merchant: t("Магазин"),
  note: t("Комментарий"),
  total: t("Итого"),
  quantity: t("Количество"),
  unit_price: t("Цена"),
  kind: t("Вид операции"),
  direction: t("Направление"),
  balance: t("Остаток"),
  version: t("Версия"),
  active: t("Активен"),
  archived: t("В архиве"),
  due_on: t("Срок"),
  items: t("Позиции"),
  text: t("Текст"),
  to_account_id: t("Счёт назначения"),
  fx_rate: t("Курс"),
  limit: t("Лимит"),
  month: t("Месяц"),
});

export function ChatActions({ message }: { message: Message }) {
  const { user, accounts, categories } = useApp();
  const action = useMutation({
    mutationFn: (confirm: boolean) =>
      send<{
        status: NonNullable<Message["details"]["action_status"]>;
        results: NonNullable<Message["details"]["action_results"]>;
      }>(`/chat/${message.id}/actions`, { confirm }),
    onSuccess: async () => {
      await queryClient.invalidateQueries();
    },
  });
  const status = action.data?.status ?? message.details.action_status;
  const results = action.data?.results ?? message.details.action_results;
  const fieldLabels: Record<string, string> = labels();
  const names = new Map(
    [...accounts, ...categories].map((row) => [row.id, row.name]),
  );
  function values(value: unknown, prefix = ""): [string, string][] {
    if (Array.isArray(value))
      return value.flatMap((child, index) =>
        values(child, `${prefix} ${index + 1}`),
      );
    if (value !== null && typeof value === "object")
      return Object.entries(value).flatMap(([key, child]) =>
        values(
          child,
          `${prefix ? prefix + " · " : ""}${fieldLabels[key] ?? key}`,
        ),
      );
    const display =
      value === null
        ? "—"
        : typeof value === "boolean"
          ? value
            ? t("Да")
            : t("Нет")
          : String(value);
    return [[prefix, names.get(display) ?? display]];
  }
  function parse(text: string): unknown {
    try {
      return JSON.parse(text);
    } catch {
      return t("Не удалось прочитать данные");
    }
  }
  return (
    <section className="chat-actions" aria-label={t("Подготовленные действия")}>
      <strong>{t("Проверьте перед выполнением")}</strong>
      {message.details.actions?.map((proposal, index) => (
        <article key={index}>
          <h3>{proposal.label}</h3>
          <p>{proposal.description}</p>
          <dl>
            {values(parse(proposal.body_json)).map(([label, value], index) => (
              <div key={index}>
                <dt>{label}</dt>
                <dd>{value}</dd>
              </div>
            ))}
          </dl>
          <details>
            <summary>{t("Запись и параметры действия")}</summary>
            <p>{proposal.operation}</p>
            <dl>
              {values(parse(proposal.parameters_json)).map(
                ([label, value], index) => (
                  <div key={index}>
                    <dt>{label}</dt>
                    <dd>{value}</dd>
                  </div>
                ),
              )}
            </dl>
          </details>
        </article>
      ))}
      {results?.map((result, index) => (
        <p key={index} className={result.status >= 300 ? "text-danger" : ""}>
          {result.label}: {result.detail}
        </p>
      ))}
      {status === "pending" && message.details.actor_id === user.id ? (
        <div className="button-row">
          <button
            className="button primary"
            disabled={action.isPending}
            onClick={() => action.mutate(true)}
          >
            <Check size={17} />
            {action.isPending ? t("Выполняю…") : t("Выполнить")}
          </button>
          <button
            className="button secondary"
            disabled={action.isPending}
            onClick={() => action.mutate(false)}
          >
            <X size={17} />
            {t("Отмена")}
          </button>
        </div>
      ) : (
        <small>
          {status === "completed"
            ? t("Выполнено")
            : status === "cancelled"
              ? t("Отменено")
              : status === "executing"
                ? t("Выполняю…")
                : status === "failed"
                  ? t(
                      "Выполнение остановлено. Проверьте результат и повторите запрос для оставшихся действий.",
                    )
                  : t("Выполнить предложение может только автор запроса")}
        </small>
      )}
      <ErrorBox error={action.error} />
    </section>
  );
}
