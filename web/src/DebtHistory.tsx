import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  ArrowLeft,
  ArrowRight,
  Check,
  History,
  Plus,
  RefreshCw,
} from "lucide-react";
import { api, ApiError } from "./api";
import { t, getLocale } from "./i18n";
import { debtRecordTime } from "./debtDates";
import type { Debt, DebtHistory, DebtMovementMode } from "./types";
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
  onMovement,
}: {
  debt: Debt;
  onClose: () => void;
  onMovement?: (mode: DebtMovementMode, remaining: number) => void;
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
      title={t("Долг: {0}", debt.person)}
      description={
        debt.direction === "lent" ? t("Вы дали в долг") : t("Вы взяли в долг")
      }
      onClose={onClose}
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
            {(debt.note || debt.due_date) && (
              <div className="debt-card-description">
                {debt.due_date && (
                  <p>{t("Вернуть до: {0}", dateLabel(debt.due_date))}</p>
                )}
                {debt.note && <p className="debt-history-note">{debt.note}</p>}
              </div>
            )}
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
            {onMovement && (
              <div
                className="debt-card-actions"
                aria-label={t("Действия с долгом")}
              >
                {history.remaining_minor > 0 && (
                  <>
                    <button
                      className="button primary"
                      onClick={() =>
                        onMovement("partial", history.remaining_minor)
                      }
                      disabled={query.isFetching}
                    >
                      {t("Погасить частично")}
                    </button>
                    <button
                      className="button secondary"
                      onClick={() =>
                        onMovement("full", history.remaining_minor)
                      }
                      disabled={query.isFetching}
                    >
                      <Check size={16} /> {t("Погасить полностью")}
                    </button>
                  </>
                )}
                <button
                  className="button secondary"
                  onClick={() =>
                    onMovement("increase", history.remaining_minor)
                  }
                  disabled={query.isFetching}
                >
                  <Plus size={16} /> {t("Увеличить долг")}
                </button>
              </div>
            )}
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
            <h3 className="debt-card-history-title">{t("История долга")}</h3>
            <p className="debt-history-order">
              {t("Сначала последние операции. Время указано по Кишинёву.")}
            </p>
            {history.items.length ? (
              <ol
                className="debt-history-list"
                aria-label={t("Операции долга")}
              >
                {history.items.map((entry) => {
                  const recorded = debtRecordTime(
                    entry.created_at,
                    getLocale(),
                  );
                  return (
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
                      <div className="debt-history-recorded">
                        {recorded ? (
                          <time dateTime={recorded.iso}>
                            {t("Записано: {0}", recorded.label)}
                          </time>
                        ) : (
                          <span className="muted">
                            {t("Время записи недоступно")}
                          </span>
                        )}
                        {recorded?.date !== entry.occurred_on && (
                          <span className="debt-history-operation-date">
                            {t("Дата операции: {0}", entry.occurred_on)}
                          </span>
                        )}
                      </div>
                      <dl className="debt-history-entry-details">
                        <div>
                          <dt>{t("Счёт")}</dt>
                          <dd>{entry.account_name || t("Счёт недоступен")}</dd>
                        </div>
                        <div>
                          <dt>{t("Остаток после операции")}</dt>
                          <dd>
                            {amount(
                              entry.balance_after_minor,
                              history.currency,
                            )}
                          </dd>
                        </div>
                      </dl>
                      {entry.note && (
                        <p className="debt-history-note">
                          <span>{t("Комментарий")}: </span>
                          {entry.note}
                        </p>
                      )}
                      {entry.voided && (
                        <p className="debt-history-order">
                          {t("Отменённая операция не влияет на остаток долга.")}
                        </p>
                      )}
                    </li>
                  );
                })}
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
