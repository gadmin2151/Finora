import { t } from "./i18n";
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
        ? t("Добавлено категорий: {0}", response.added)
        : t("Повседневные категории уже добавлены"),
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
    () => toast(t("Категории чеков и аналитика обновлены")),
  );
  const pending = add.isPending || refresh.isPending;
  return (
    <div className="category-refresh">
      <strong>{t("Повседневные покупки — подробнее")}</strong>
      <p>
        {t(
          "Продукты, сладости, кофе, энергетики, табак, уход и товары для дома. Перераспределение обновит товары и аналитику без изменения сумм. Ваши правила имеют приоритет, собственные категории сохраняются.",
        )}
      </p>
      <div className="category-refresh-actions">
        <button
          className="button secondary"
          disabled={pending}
          onClick={() => add.mutate(undefined)}
        >
          <Sparkles size={16} /> {t("Добавить категории")}
        </button>
        <button
          className="button secondary"
          disabled={pending}
          onClick={() => refresh.mutate(undefined)}
        >
          <RefreshCw size={16} /> {t("Перераспределить чеки")}
        </button>
      </div>
      {pending && (
        <Loading
          text={
            refresh.isPending
              ? t("Проверено чеков: {0}…", progress)
              : t("Добавляю категории…")
          }
        />
      )}
      <ErrorBox error={add.error ?? refresh.error} />
      {result && (
        <div role="status">
          <p>
            {t("Проверено:")} {result.scanned}
            {t(". Обновлено чеков:")} {result.receipts_updated}
            {t(", категорий товаров:")} {result.items_updated}
            {t(", названий:")} {result.names_repaired}.
          </p>
          {Object.entries(result.skipped).map(([reason, count]) => (
            <p className="muted" key={reason}>
              {{
                refund: t("С возвратами — требуют ручной проверки категорий"),
                processing: t("Ещё распознаются"),
                inactive: t("Без действующей покупки или товаров"),
                amount_mismatch: t("Требуют сверки сумм"),
              }[reason] ?? t("Пропущено")}
              : {count}
            </p>
          ))}
        </div>
      )}
    </div>
  );
}
