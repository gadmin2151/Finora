import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  ArrowLeft,
  ArrowRight,
  Download,
  Pencil,
  Plus,
  RotateCcw,
  Search,
  Trash2,
} from "lucide-react";
import { organizationUrl, api, send, useAction } from "../api";
import { useApp } from "../context";
import type { Receipt, Transaction } from "../types";
import {
  Badge,
  CategoryIcon,
  Empty,
  ErrorBox,
  Loading,
  Modal,
  PageHeading,
  amount,
  dateLabel,
  kinds,
} from "../ui";

export default function Transactions() {
  const { month, accounts, categories, open, toast, isAdmin } = useApp();
  const [search, setSearch] = useState("");
  const [kind, setKind] = useState("");
  const [category, setCategory] = useState("");
  const [account, setAccount] = useState("");
  const [offset, setOffset] = useState(0);
  const [all, setAll] = useState(false);
  const [remove, setRemove] = useState<Transaction | null>(null);
  const query = useQuery({
    queryKey: [
      "transactions",
      month,
      search,
      kind,
      category,
      account,
      offset,
      all,
    ],
    queryFn: () =>
      api<{ items: Transaction[]; total: number }>(
        `/transactions?${new URLSearchParams({ ...(all ? {} : { month }), search, kind, category_id: category, account_id: account, offset: String(offset) })}`,
      ),
  });
  const action = useAction(
    (tx: Transaction) =>
      send(`/transactions/${tx.id}?version=${tx.version}`, undefined, "DELETE"),
    () => {
      toast("Операция отменена, остатки пересчитаны");
      setRemove(null);
    },
  );
  const receiptAction = useAction(async (id: string) => {
    const receipt = await api<Receipt>(`/receipts/${id}`);
    open({ type: "receipt", receipt });
  });
  function reset(change: () => void) {
    change();
    setOffset(0);
  }
  return (
    <>
      <PageHeading
        eyebrow="ИСТОРИЯ"
        title="Каждая операция на месте"
        text="Покупки, доходы, переводы и возвраты — в единой истории."
        actions={
          isAdmin && (
            <>
              <a
                className="button secondary"
                href={organizationUrl("/api/export.csv")}
                download
              >
                <Download size={17} />
                CSV
              </a>
              <button
                className="button primary"
                onClick={() => open({ type: "transaction" })}
              >
                <Plus size={18} />
                Операция
              </button>
            </>
          )
        }
      />
      <section className="panel">
        <div className="filters">
          <div className="search-field">
            <Search size={18} />
            <input
              aria-label="Поиск операций"
              placeholder="Магазин или примечание"
              value={search}
              onChange={(e) => reset(() => setSearch(e.target.value))}
            />
          </div>
          <select
            aria-label="Тип операции"
            value={kind}
            onChange={(e) => reset(() => setKind(e.target.value))}
          >
            <option value="">Все операции</option>
            {Object.entries(kinds).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
          <select
            aria-label="Фильтр категории"
            value={category}
            onChange={(e) => reset(() => setCategory(e.target.value))}
          >
            <option value="">Все категории</option>
            {categories.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
          <select
            aria-label="Фильтр счёта"
            value={account}
            onChange={(e) => reset(() => setAccount(e.target.value))}
          >
            <option value="">Все счета</option>
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
          <label className="check-field">
            <input
              type="checkbox"
              checked={all}
              onChange={(e) => reset(() => setAll(e.target.checked))}
            />
            Вся история
          </label>
        </div>
        <ErrorBox error={query.error ?? receiptAction.error} />
        {query.isPending ? (
          <Loading />
        ) : query.data?.items.length ? (
          <>
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Операция</th>
                    <th>Категория</th>
                    <th>Счёт</th>
                    <th>Дата</th>
                    <th className="align-right">Сумма</th>
                    <th aria-label="Действия" />
                  </tr>
                </thead>
                <tbody>
                  {query.data.items.map((tx) => {
                    const cat = categories.find((c) => c.id === tx.category_id);
                    const linked =
                      tx.receipt_id ||
                      tx.debt_id ||
                      tx.occurrence_id ||
                      tx.kind === "refund";
                    return (
                      <tr key={tx.id}>
                        <td>
                          <div className="table-title">
                            <CategoryIcon category={cat} />
                            <div>
                              <strong>{tx.merchant || kinds[tx.kind]}</strong>
                              <small>
                                {kinds[tx.kind]}
                                {tx.note ? ` · ${tx.note}` : ""}
                              </small>
                              {tx.receipt_id && (
                                <button
                                  className="receipt-link"
                                  onClick={() =>
                                    receiptAction.mutate(tx.receipt_id!)
                                  }
                                >
                                  Чек и товары ↗
                                </button>
                              )}
                            </div>
                          </div>
                        </td>
                        <td>
                          {tx.splits.length > 1 ? (
                            <Badge>{tx.splits.length} категории</Badge>
                          ) : (
                            (cat?.name ?? "—")
                          )}
                        </td>
                        <td>
                          {accounts.find((a) => a.id === tx.account_id)?.name}
                          {tx.target_account_id && (
                            <small className="block">
                              →{" "}
                              {
                                accounts.find(
                                  (a) => a.id === tx.target_account_id,
                                )?.name
                              }
                            </small>
                          )}
                        </td>
                        <td className="nowrap">
                          {dateLabel(tx.occurred_on)}
                          <small className="block">
                            {tx.occurred_on.slice(0, 4)}
                          </small>
                        </td>
                        <td
                          className={`align-right money ${["income", "refund", "debt_borrow", "debt_repayment_in"].includes(tx.kind) ? "positive" : ""}`}
                        >
                          {[
                            "income",
                            "refund",
                            "debt_borrow",
                            "debt_repayment_in",
                          ].includes(tx.kind)
                            ? "+"
                            : tx.kind === "transfer"
                              ? ""
                              : "−"}
                          {amount(tx.amount_minor, tx.currency)}
                          {tx.currency !== "MDL" && (
                            <small className="block">
                              {amount(tx.base_minor)}
                            </small>
                          )}
                        </td>
                        <td>
                          <div className="row-actions" hidden={!isAdmin}>
                            {isAdmin && !linked && (
                              <button
                                className="icon-button"
                                title="Изменить"
                                aria-label={`Изменить ${tx.merchant || "операцию"}`}
                                onClick={() =>
                                  open({ type: "transaction", transaction: tx })
                                }
                              >
                                <Pencil size={16} />
                              </button>
                            )}
                            {isAdmin && tx.kind === "expense" && (
                              <button
                                className="icon-button"
                                title="Возврат покупки"
                                aria-label={`Возврат ${tx.merchant || "покупки"}`}
                                onClick={() =>
                                  open({ type: "transaction", refund: tx })
                                }
                              >
                                <RotateCcw size={16} />
                              </button>
                            )}
                            <button
                              className="icon-button danger-hover"
                              title="Отменить операцию"
                              aria-label={`Отменить ${tx.merchant || "операцию"}`}
                              onClick={() => setRemove(tx)}
                            >
                              <Trash2 size={16} />
                            </button>
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <div className="pagination">
              <span>
                Показано {offset + 1}–{offset + query.data.items.length} из{" "}
                {query.data.total}
              </span>
              <div>
                <button
                  className="icon-button"
                  aria-label="Предыдущая страница"
                  disabled={offset === 0}
                  onClick={() => setOffset(Math.max(0, offset - 50))}
                >
                  <ArrowLeft size={18} />
                </button>
                <button
                  className="icon-button"
                  aria-label="Следующая страница"
                  disabled={offset + 50 >= query.data.total}
                  onClick={() => setOffset(offset + 50)}
                >
                  <ArrowRight size={18} />
                </button>
              </div>
            </div>
          </>
        ) : (
          <Empty
            title="Здесь пока пусто"
            text="Измените фильтры или добавьте первую операцию."
            action={
              isAdmin && (
                <button
                  className="button primary"
                  onClick={() => open({ type: "transaction" })}
                >
                  <Plus size={17} />
                  Добавить
                </button>
              )
            }
          />
        )}
      </section>
      {remove && (
        <Modal
          title="Отменить операцию?"
          description={`${remove.merchant || kinds[remove.kind]} · ${amount(remove.amount_minor, remove.currency)}`}
          onClose={() => setRemove(null)}
        >
          <p>
            Операция перестанет влиять на остатки, долги и отчёты. История
            отмены сохранится в журнале.
          </p>
          <ErrorBox error={action.error} />
          <footer className="modal-footer">
            <button
              className="button secondary"
              onClick={() => setRemove(null)}
            >
              Оставить
            </button>
            <button
              className="button danger"
              disabled={action.isPending}
              onClick={() => action.mutate(remove)}
            >
              Отменить операцию
            </button>
          </footer>
        </Modal>
      )}
    </>
  );
}
