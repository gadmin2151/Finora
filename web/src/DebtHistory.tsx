import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, ArrowRight, History, RefreshCw } from "lucide-react";
import { api, ApiError } from "./api";
import { t } from "./i18n";
import type { Debt, DebtHistory } from "./types";
import {
  amount,
  Badge,
  dateLabel,
  Empty,
  ErrorBox,
  Loading,
  Modal,
} from "./ui";
import "./debt-history.css";

const pageSize = 50;

export default function DebtHistoryDialog({
  debt,
  onClose,
}: {
  debt: Debt;
  onClose: () => void;
}) {
  const [offset, setOffset] = useState(0);
  const query = useQuery({
    queryKey: ["debt-history", debt.id, offset],
    queryFn: ({ signal }) =>
      api<DebtHistory>(
        `/debts/${encodeURIComponent(debt.id)}/history?offset=${offset}&limit=${pageSize}`,
        { signal },
      ),
  });
  // Refetch failures retain cached data; stop showing it when access is denied.
  const unavailable =
    query.error instanceof ApiError && [403, 404].includes(query.error.status);
  const history = unavailable ? undefined : query.data;
  return (
    <Modal
      title={t("История долга: {0}", debt.person)}
      description={
        debt.direction === "lent" ? t("Вы дали в долг") : t("Вы взяли в долг")
      }
      onClose={onClose}
      wide
    >
      <div className="debt-history" aria-busy={query.isFetching}>
        <ErrorBox error={query.error} />
        {query.isError && (
          <button
            className="button secondary"
            disabled={query.isFetching}
            onClick={() => void query.refetch()}
          >
            <RefreshCw size={16} /> {t("Повторить")}
          </button>
        )}
        {query.isPending ? (
          <Loading />
        ) : history ? (
          <>
            <dl className="debt-history-summary">
              <div>
                <dt>{t("Общая сумма долга")}</dt>
                <dd>
                  {amount(
                    history.initial_minor + history.increased_minor,
                    history.currency,
                  )}
                </dd>
              </div>
              <div>
                <dt>{t("Всего погашено")}</dt>
                <dd>{amount(history.repaid_minor, history.currency)}</dd>
              </div>
              <div>
                <dt>{t("Остаток долга")}</dt>
                <dd>{amount(history.remaining_minor, history.currency)}</dd>
                <Badge
                  status={history.remaining_minor === 0 ? "paid" : undefined}
                >
                  {history.remaining_minor === 0 ? t("Закрыт") : t("Открыт")}
                </Badge>
              </div>
            </dl>
            {history.initial_minor > 0 && (
              <div className="debt-history-opening">
                <strong>
                  {t("Начальный остаток старого долга")}:{" "}
                  {amount(history.initial_minor, history.currency)}
                </strong>
                <p>
                  {t(
                    "Внесён без движения по счёту. Дата первоначальной передачи денег не сохранена.",
                  )}
                </p>
              </div>
            )}
            <p className="debt-history-order">
              {t(
                "Сначала последние записи. Остатки рассчитаны без отменённых операций.",
              )}
            </p>
            {history.items.length ? (
              <ol
                className="debt-history-list"
                aria-label={t("Операции долга")}
              >
                {history.items.map((entry) => (
                  <li
                    key={entry.id}
                    className={entry.voided ? "debt-history-voided" : ""}
                  >
                    <div className="debt-history-entry-heading">
                      <div>
                        <strong>
                          {entry.operation === "initial"
                            ? t("Долг создан")
                            : entry.operation === "increase"
                              ? t("Увеличение долга")
                              : t("Погашение долга")}
                        </strong>
                        <time dateTime={entry.occurred_on}>
                          {dateLabel(entry.occurred_on)}
                        </time>
                      </div>
                      <div className="debt-history-amount">
                        <strong>
                          {entry.voided
                            ? ""
                            : entry.operation === "repayment"
                              ? "−"
                              : "+"}
                          {amount(entry.amount_minor, history.currency)}
                        </strong>
                        {entry.voided && <Badge>{t("Отменена")}</Badge>}
                      </div>
                    </div>
                    <dl className="debt-history-entry-details">
                      <div>
                        <dt>{t("Счёт")}</dt>
                        <dd>{entry.account_name || t("Счёт недоступен")}</dd>
                      </div>
                      <div>
                        <dt>{t("Остаток после операции")}</dt>
                        <dd>
                          {amount(entry.balance_after_minor, history.currency)}
                        </dd>
                      </div>
                    </dl>
                    {entry.note && (
                      <p className="debt-history-note">{entry.note}</p>
                    )}
                    {entry.voided && (
                      <p className="debt-history-order">
                        {t("Отменённая операция не влияет на остаток долга.")}
                      </p>
                    )}
                  </li>
                ))}
              </ol>
            ) : (
              <Empty
                icon={<History size={24} />}
                title={t("Операций пока нет")}
                text={t(
                  "Здесь появятся увеличение долга и каждый частичный или полный возврат.",
                )}
              />
            )}
            {history.total > pageSize && (
              <div className="pagination">
                <span>
                  {t("Показано")} {history.items.length ? offset + 1 : 0}–
                  {offset + history.items.length} {t("из")} {history.total}
                </span>
                <div>
                  <button
                    className="icon-button"
                    aria-label={t("Предыдущая страница")}
                    disabled={offset === 0 || query.isFetching}
                    onClick={() => setOffset(Math.max(0, offset - pageSize))}
                  >
                    <ArrowLeft size={18} />
                  </button>
                  <button
                    className="icon-button"
                    aria-label={t("Следующая страница")}
                    disabled={
                      offset + pageSize >= history.total || query.isFetching
                    }
                    onClick={() => setOffset(offset + pageSize)}
                  >
                    <ArrowRight size={18} />
                  </button>
                </div>
              </div>
            )}
          </>
        ) : null}
      </div>
    </Modal>
  );
}
