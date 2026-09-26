import { useState } from "react";
import { RefreshCw, Sparkles } from "lucide-react";
import { api, useAction } from "./api";
import { useApp } from "./context";
import { ErrorBox, Loading } from "./ui";

type RefreshResult = {
  scanned: number;
  receipts_updated: number;
  items_updated: number;
  names_repaired: number;
  merchants_repaired: number;
  categories_added: number;
  skipped: Record<string, number>;
  next_cursor: string | null;
};

export function CategoryRefresh() {
  const { organization, toast } = useApp();
  const [progress, setProgress] = useState(0);
  const [result, setResult] = useState<RefreshResult | null>(null);
  const headers = { "X-Organization-ID": organization.id };
  const add = useAction(async () => {
    const response = await api<{ added: number }>("/categories/daily", {
      method: "POST",
      headers,
    });
    toast(
      response.added
        ? `Добавлено категорий: ${response.added}`
        : "Повседневные категории уже добавлены",
    );
  });
  const refresh = useAction(
    async () => {
      setResult(null);
      setProgress(0);
      const summary: RefreshResult = {
        scanned: 0,
        receipts_updated: 0,
        items_updated: 0,
        names_repaired: 0,
        merchants_repaired: 0,
        categories_added: 0,
        skipped: {},
        next_cursor: null,
      };
      let cursor = "";
      do {
        const page = await api<RefreshResult>("/categories/refresh", {
          method: "POST",
          headers,
          body: JSON.stringify({ after: cursor }),
        });
        for (const key of [
          "scanned",
          "receipts_updated",
          "items_updated",
          "names_repaired",
          "merchants_repaired",
          "categories_added",
        ] as const)
          summary[key] += page[key];
        for (const [reason, count] of Object.entries(page.skipped))
          summary.skipped[reason] = (summary.skipped[reason] ?? 0) + count;
        setProgress(summary.scanned);
        cursor = page.next_cursor ?? "";
      } while (cursor);
      setResult(summary);
    },
    () => toast("Категории чеков и аналитика обновлены"),
  );
  const pending = add.isPending || refresh.isPending;
  return (
    <div className="category-refresh">
      <strong>Повседневные покупки — подробнее</strong>
      <p>
        Продукты, сладости, кофе, энергетики, табак, уход и товары для дома.
        Перераспределение обновит товары и аналитику без изменения сумм. Ваши
        правила имеют приоритет, собственные категории сохраняются.
      </p>
      <div className="category-refresh-actions">
        <button
          className="button secondary"
          disabled={pending}
          onClick={() => add.mutate(undefined)}
        >
          <Sparkles size={16} /> Добавить категории
        </button>
        <button
          className="button secondary"
          disabled={pending}
          onClick={() => refresh.mutate(undefined)}
        >
          <RefreshCw size={16} /> Перераспределить чеки
        </button>
      </div>
      {pending && (
        <Loading
          text={
            refresh.isPending
              ? `Проверено чеков: ${progress}…`
              : "Добавляю категории…"
          }
        />
      )}
      <ErrorBox error={add.error ?? refresh.error} />
      {result && (
        <div role="status">
          <p>
            Проверено: {result.scanned}. Обновлено чеков:{" "}
            {result.receipts_updated}, категорий товаров: {result.items_updated}
            , названий: {result.names_repaired}.
          </p>
          {Object.entries(result.skipped).map(([reason, count]) => (
            <p className="muted" key={reason}>
              {{
                refund: "С возвратами — требуют ручной проверки категорий",
                processing: "Ещё распознаются",
                inactive: "Без действующей покупки или товаров",
                amount_mismatch: "Требуют сверки сумм",
              }[reason] ?? "Пропущено"}
              : {count}
            </p>
          ))}
        </div>
      )}
    </div>
  );
}
