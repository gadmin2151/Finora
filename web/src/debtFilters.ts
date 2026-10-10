export function debtIsOverdue(
  debt: { remaining_minor: number; due_date: string | null },
  today: string,
) {
  const due = debt.due_date && new Date(`${debt.due_date}T00:00:00Z`);
  return (
    debt.remaining_minor > 0 &&
    debt.due_date !== null &&
    /^\d{4}-\d{2}-\d{2}$/.test(debt.due_date) &&
    due !== null &&
    due !== "" &&
    Number.isFinite(due.getTime()) &&
    due.toISOString().slice(0, 10) === debt.due_date &&
    debt.due_date < today
  );
}

export function filterDebts<
  T extends {
    direction: string;
    remaining_minor: number;
    due_date: string | null;
  },
>(
  debts: ReadonlyArray<T>,
  direction: string,
  includeClosed: boolean,
  overdueOnly: boolean,
  today: string,
) {
  return debts.filter(
    (debt) =>
      (direction === "all" || debt.direction === direction) &&
      (includeClosed || debt.remaining_minor > 0) &&
      (!overdueOnly || debtIsOverdue(debt, today)),
  );
}
