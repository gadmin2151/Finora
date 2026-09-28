import { useQuery } from "@tanstack/react-query";
import { UserRound } from "lucide-react";
import { api } from "./api";
import { useApp } from "./context";
import { t } from "./i18n";
import type { ReceiptCreator } from "./types";
import { ErrorBox, Field } from "./ui";

export function creatorName(creator?: ReceiptCreator) {
  if (!creator?.id) return t("Автор не указан");
  return creator.name || creator.username || t("Пользователь");
}

export function ReceiptAuthor({ creator }: { creator?: ReceiptCreator }) {
  return (
    <span className="receipt-author">
      <UserRound size={15} aria-hidden="true" />
      {t("Добавил: {0}", creatorName(creator))}
    </span>
  );
}

export function useReceiptAuthors() {
  const { organization } = useApp();
  return useQuery({
    queryKey: ["receipt-authors", organization.id],
    queryFn: ({ signal }) =>
      api<{ items: ReceiptCreator[] }>("/receipt-authors", { signal }),
  });
}

export function ReceiptAuthorFilter({
  value,
  onChange,
}: {
  value: string;
  onChange: (value: string) => void;
}) {
  const authors = useReceiptAuthors();
  return (
    <div className="receipt-author-filter">
      <Field label={t("Кто добавил чек")}>
        <select
          value={value}
          onChange={(e) => onChange(e.target.value)}
          disabled={authors.isPending || authors.isError}
        >
          <option value="">
            {authors.isPending ? t("Загрузка…") : t("Все пользователи")}
          </option>
          {authors.data?.items.map((creator) => (
            <option
              key={creator.id ?? "unknown"}
              value={creator.id ?? "unknown"}
            >
              {creatorName(creator)}
              {creator.id && !creator.active ? t(" · неактивен") : ""}
            </option>
          ))}
        </select>
      </Field>
      <ErrorBox error={authors.error} />
    </div>
  );
}
