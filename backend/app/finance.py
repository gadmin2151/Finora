import calendar
import hashlib
import json
from collections import defaultdict
from datetime import date, datetime, timedelta
from decimal import ROUND_HALF_UP, Decimal
from zoneinfo import ZoneInfo

from fastapi import HTTPException
from sqlalchemy import case, func, select
from sqlalchemy.orm import Session

from . import models as m
from .category_catalog import DEFAULT_CATEGORIES
from .schemas import SplitInput, TransactionInput


def today() -> date:
    return datetime.now(ZoneInfo("Europe/Chisinau")).date()


def fail(message: str, status: int = 422):
    raise HTTPException(status, message)


def minor(amount: Decimal | str | int) -> int:
    value = Decimal(str(amount))
    if not value.is_finite() or value != value.quantize(Decimal("0.01")):
        fail("Укажите сумму с точностью до двух знаков после запятой")
    return int(value * 100)


def base_value(amount: int, rate: Decimal) -> int:
    return int((Decimal(amount) * rate).quantize(Decimal(1), rounding=ROUND_HALF_UP))


def apportion_minor(total: int, weights: list[int]) -> list[int]:
    """Distribute a nonnegative amount exactly, without rounding a category below zero."""
    denominator = sum(weights)
    if total < 0 or denominator <= 0 or any(weight < 0 for weight in weights):
        raise ValueError("Invalid monetary allocation")
    parts = [divmod(weight * total, denominator) for weight in weights]
    values = [value for value, _ in parts]
    for index in sorted(range(len(parts)), key=lambda i: parts[i][1], reverse=True)[
        : total - sum(values)
    ]:
        values[index] += 1
    return values


def money(amount: int) -> str:
    return format(Decimal(amount) / 100, ".2f")


def rate_for(currency: str, rate: Decimal | None) -> Decimal:
    if currency == "MDL":
        return Decimal(1)
    if rate is None:
        fail("Для иностранной валюты укажите курс к MDL на дату операции")
    return rate


def owned(db: Session, model, key: str, organization_id: str, lock: bool = False):
    stmt = select(model).where(model.id == key, model.organization_id == organization_id)
    if model is m.Receipt:
        stmt = stmt.where(m.Receipt.deleted_at.is_(None))
    obj = db.scalar(stmt.with_for_update() if lock else stmt)
    if obj is None:
        fail("Запись не найдена", 404)
    return obj


def lock_organization(db: Session, organization_id: str):
    db.scalar(select(m.Organization).where(m.Organization.id == organization_id).with_for_update())


def audit(
    db: Session, organization_id: str, action: str, entity_id: str, details: dict | None = None
):
    db.add(
        m.Audit(
            organization_id=organization_id,
            actor_id=db.info.get("actor_id"),
            action=action,
            entity_id=entity_id,
            details=details or {},
        )
    )


def seed_user(db: Session, user: m.User):
    db.flush()
    organization = m.Organization(id=user.id, name="Моя организация")
    db.add(organization)
    db.flush()
    db.add(m.Membership(user_id=user.id, organization_id=organization.id, role="admin"))
    seed_organization(db, organization)


def seed_organization(db: Session, user: m.Organization):
    db.flush()
    for name, icon, color in DEFAULT_CATEGORIES:
        db.add(m.Category(organization_id=user.id, name=name, color=color, icon=icon))
    cash = m.Account(organization_id=user.id, name="Наличные", currency="MDL", kind="cash")
    db.add(cash)
    db.add(
        m.Account(
            organization_id=user.id,
            name="Основная карта",
            currency="MDL",
            kind="card",
            color="#6872dd",
        )
    )
    db.flush()
    db.add(m.Preferences(organization_id=user.id, default_account_id=cash.id))


def month_range(month: str) -> tuple[date, date]:
    try:
        start = date.fromisoformat(month + "-01")
        if not 1990 <= start.year <= 2100:
            raise ValueError
    except ValueError:
        fail("Некорректный месяц")
    return start, start.replace(day=calendar.monthrange(start.year, start.month)[1])


def account_balances(db: Session, organization_id: str):
    sums = dict(
        db.execute(
            select(m.Posting.account_id, func.sum(m.Posting.amount_minor))
            .join(m.Transaction, m.Posting.transaction_id == m.Transaction.id)
            .where(m.Transaction.organization_id == organization_id, ~m.Transaction.voided)
            .group_by(m.Posting.account_id)
        ).all()
    )
    return [
        {
            "id": a.id,
            "name": a.name,
            "currency": a.currency,
            "kind": a.kind,
            "opening_minor": a.opening_minor,
            "color": a.color,
            "archived": a.archived,
            "balance_minor": a.opening_minor + int(sums.get(a.id, 0)),
        }
        for a in db.scalars(
            select(m.Account)
            .where(m.Account.organization_id == organization_id)
            .order_by(m.Account.created_at)
        )
    ]


def create_transaction(
    db: Session,
    organization_id: str,
    data: TransactionInput,
    internal_kind: str | None = None,
    debt_id: str | None = None,
    receipt_id: str | None = None,
    occurrence_id: str | None = None,
    record_audit: bool = True,
):
    lock_organization(db, organization_id)
    fingerprint = hashlib.sha256(
        json.dumps(
            {
                **data.model_dump(mode="json"),
                "internal_kind": internal_kind,
                "debt_id": debt_id,
                "receipt_id": receipt_id,
                "occurrence_id": occurrence_id,
            },
            sort_keys=True,
        ).encode()
    ).hexdigest()
    old = db.scalar(
        select(m.Transaction).where(
            m.Transaction.organization_id == organization_id,
            m.Transaction.idempotency_key == data.idempotency_key,
        )
    )
    if old:
        if old.request_hash != fingerprint:
            fail("Этот запрос уже использован для другой операции", 409)
        return old
    if data.occurred_on > today():
        fail("Будущие расходы добавляйте в план платежей")
    account = owned(db, m.Account, data.account_id, organization_id)
    if account.archived:
        fail("Счёт находится в архиве")
    amount = minor(data.amount)
    kind = internal_kind or data.kind
    rate = rate_for(account.currency, data.fx_rate)
    if data.category_id:
        owned(db, m.Category, data.category_id, organization_id)
    target = None
    target_minor = None
    if kind == "transfer":
        if not data.target_account_id or data.target_account_id == account.id:
            fail("Выберите другой счёт для перевода")
        target = owned(db, m.Account, data.target_account_id, organization_id)
        if target.archived:
            fail("Счёт назначения находится в архиве")
        if target.currency == account.currency:
            if data.target_amount is not None and minor(data.target_amount) != amount:
                fail("Суммы перевода в одной валюте должны совпадать; комиссию внесите отдельно")
            target_minor = amount
        elif data.target_amount is None:
            fail("Укажите полученную сумму в валюте второго счёта")
        else:
            target_minor = minor(data.target_amount)
    elif data.target_account_id is not None:
        fail("Второй счёт доступен только для перевода")
    if data.refund_of and kind != "refund":
        fail("Ссылка на покупку доступна только для возврата")
    if kind == "refund":
        if not data.refund_of:
            fail("Выберите исходную покупку для возврата")
        original = owned(db, m.Transaction, data.refund_of, organization_id, True)
        if original.kind != "expense" or original.voided or original.currency != account.currency:
            fail("Возврат должен соответствовать действующей покупке и её валюте")
        returned = db.scalar(
            select(func.coalesce(func.sum(m.Transaction.amount_minor), 0)).where(
                m.Transaction.refund_of == original.id, ~m.Transaction.voided
            )
        )
        if amount + returned > original.amount_minor:
            fail("Возврат превышает оставшуюся сумму покупки")
        if data.occurred_on < original.occurred_on:
            fail("Возврат не может быть раньше исходной покупки")
        if not data.category_id and not data.splits:
            allocations = list(
                db.scalars(
                    select(m.Allocation)
                    .where(m.Allocation.transaction_id == original.id)
                    .order_by(m.Allocation.created_at, m.Allocation.id)
                )
            )
            # Largest-remainder allocation preserves every cent for a partial split refund.
            rounded = apportion_minor(amount, [a.amount_minor for a in allocations])
            data = data.model_copy(
                update={
                    "category_id": original.category_id,
                    "splits": [
                        SplitInput(category_id=a.category_id, amount=money(v))
                        for a, v in zip(allocations, rounded, strict=True)
                        if v > 0
                    ],
                }
            )
    split_values = [(s.category_id, minor(s.amount)) for s in data.splits]
    if split_values and kind not in {"expense", "refund"}:
        fail("Распределение по категориям используется для расходов и возвратов")
    if split_values and sum(v for _, v in split_values) != amount:
        fail("Сумма категорий должна совпадать с суммой операции")
    requested_categories = {c for c, _ in split_values if c}
    if requested_categories:
        actual = set(
            db.scalars(
                select(m.Category.id).where(
                    m.Category.organization_id == organization_id,
                    m.Category.id.in_(requested_categories),
                )
            )
        )
        if actual != requested_categories:
            fail("Категория не найдена", 404)
    tx = m.Transaction(
        organization_id=organization_id,
        kind=kind,
        amount_minor=amount,
        currency=account.currency,
        fx_rate=rate,
        base_minor=base_value(amount, rate),
        occurred_on=data.occurred_on,
        account_id=account.id,
        target_account_id=target.id if target else None,
        target_minor=target_minor,
        category_id=data.category_id,
        merchant=data.merchant,
        note=data.note,
        debt_id=debt_id,
        receipt_id=receipt_id,
        occurrence_id=occurrence_id,
        refund_of=data.refund_of,
        idempotency_key=data.idempotency_key,
        request_hash=fingerprint,
    )
    db.add(tx)
    db.flush()
    incoming = kind in {"income", "refund", "debt_borrow", "debt_repayment_in", "adjustment"}
    db.add(
        m.Posting(
            transaction_id=tx.id,
            account_id=account.id,
            amount_minor=amount if incoming else -amount,
        )
    )
    if target:
        db.add(m.Posting(transaction_id=tx.id, account_id=target.id, amount_minor=target_minor))
    if kind in {"expense", "refund"}:
        splits = split_values or [(data.category_id, amount)]
        converted_values = apportion_minor(tx.base_minor, [value for _, value in splits])
        for (category, value), converted in zip(splits, converted_values, strict=True):
            db.add(
                m.Allocation(
                    transaction_id=tx.id,
                    category_id=category,
                    amount_minor=value,
                    base_minor=converted,
                )
            )
    if record_audit:
        audit(db, organization_id, "transaction.created", tx.id, {"kind": kind})
    db.flush()
    return tx


def transaction_dict(tx: m.Transaction):
    fields = [
        "id",
        "kind",
        "amount_minor",
        "currency",
        "base_minor",
        "account_id",
        "target_account_id",
        "target_minor",
        "category_id",
        "merchant",
        "note",
        "receipt_id",
        "debt_id",
        "occurrence_id",
        "refund_of",
        "voided",
        "version",
    ]
    return {
        **{key: getattr(tx, key) for key in fields},
        "occurred_on": tx.occurred_on.isoformat(),
        "fx_rate": str(tx.fx_rate),
    }


def debt_remaining(db: Session, debt: m.Debt) -> int:
    movement = db.scalar(
        select(
            func.coalesce(
                func.sum(
                    case(
                        (
                            m.Transaction.kind.in_(["debt_lend", "debt_borrow"]),
                            m.Transaction.amount_minor,
                        ),
                        else_=-m.Transaction.amount_minor,
                    )
                ),
                0,
            )
        ).where(m.Transaction.debt_id == debt.id, ~m.Transaction.voided)
    )
    return debt.initial_minor + int(movement)


def debt_list(db: Session, organization_id: str):
    movements = dict(
        db.execute(
            select(
                m.Transaction.debt_id,
                func.sum(
                    case(
                        (
                            m.Transaction.kind.in_(["debt_lend", "debt_borrow"]),
                            m.Transaction.amount_minor,
                        ),
                        else_=-m.Transaction.amount_minor,
                    )
                ),
            )
            .where(
                m.Transaction.organization_id == organization_id,
                m.Transaction.debt_id.is_not(None),
                ~m.Transaction.voided,
            )
            .group_by(m.Transaction.debt_id)
        ).all()
    )
    return [
        {
            "id": d.id,
            "person": d.person,
            "direction": d.direction,
            "currency": d.currency,
            "initial_minor": d.initial_minor,
            "remaining_minor": d.initial_minor + int(movements.get(d.id, 0)),
            "due_date": d.due_date.isoformat() if d.due_date else None,
            "note": d.note,
        }
        for d in db.scalars(
            select(m.Debt)
            .where(m.Debt.organization_id == organization_id)
            .order_by(m.Debt.created_at.desc())
        )
    ]


def occurrence_dates(bill: m.Bill, start: date, end: date):
    if bill.recurrence == "once":
        return [bill.start_date] if start <= bill.start_date <= end else []
    if bill.recurrence == "weekly":
        n = max(0, (start - bill.start_date).days // 7)
        values = []
        candidate = bill.start_date + timedelta(days=7 * n)
        while candidate <= end:
            if candidate >= start:
                values.append(candidate)
            candidate += timedelta(days=7)
        return values
    interval = {"monthly": 1, "quarterly": 3, "yearly": 12}[bill.recurrence]
    delta = (start.year - bill.start_date.year) * 12 + start.month - bill.start_date.month
    n = max(0, delta // interval)
    values = []
    while True:
        absolute = bill.start_date.year * 12 + bill.start_date.month - 1 + n * interval
        year, month = divmod(absolute, 12)
        month += 1
        candidate = date(year, month, min(bill.start_date.day, calendar.monthrange(year, month)[1]))
        if candidate > end:
            break
        if candidate >= start:
            values.append(candidate)
        n += 1
    return values


def bills_for_month(db: Session, organization_id: str, month: str, kind: str = "expense"):
    start, end = month_range(month)
    lock_organization(db, organization_id)
    bills = list(
        db.scalars(
            select(m.Bill).where(m.Bill.organization_id == organization_id, m.Bill.kind == kind)
        )
    )
    existing = {
        (o.bill_id, o.due_date)
        for o in db.scalars(
            select(m.Occurrence).where(
                m.Occurrence.organization_id == organization_id,
                m.Occurrence.due_date.between(start, end),
            )
        )
    }
    for bill in bills:
        if bill.active:
            for due in occurrence_dates(bill, start, end):
                if (bill.id, due) not in existing:
                    db.add(
                        m.Occurrence(
                            organization_id=organization_id,
                            bill_id=bill.id,
                            due_date=due,
                            amount_minor=bill.amount_minor,
                            currency=bill.currency,
                            fx_rate=bill.fx_rate,
                        )
                    )
    db.flush()
    rows = db.execute(
        select(m.Occurrence, m.Bill, m.Transaction.id)
        .join(m.Bill)
        .outerjoin(
            m.Transaction, (m.Transaction.occurrence_id == m.Occurrence.id) & ~m.Transaction.voided
        )
        .where(
            m.Occurrence.organization_id == organization_id,
            m.Occurrence.due_date.between(start, end),
            m.Bill.kind == kind,
        )
        .order_by(m.Occurrence.due_date)
    ).all()
    return [
        {
            "id": o.id,
            "bill_id": b.id,
            "name": b.name,
            "amount_minor": o.amount_minor,
            "base_minor": base_value(o.amount_minor, o.fx_rate),
            "currency": o.currency,
            "fx_rate": str(o.fx_rate),
            "category_id": b.category_id,
            "account_id": b.account_id,
            "due_date": o.due_date.isoformat(),
            "recurrence": b.recurrence,
            "active": b.active,
            "transaction_id": txid,
            "status": "paid"
            if txid
            else "skipped"
            if o.skipped
            else "overdue"
            if o.due_date < today()
            else "upcoming",
        }
        for o, b, txid in rows
    ]


def category_spending(db: Session, organization_id: str, start: date, end: date):
    return dict(
        db.execute(
            select(
                m.Allocation.category_id,
                func.sum(
                    case(
                        (m.Transaction.kind == "refund", -m.Allocation.base_minor),
                        else_=m.Allocation.base_minor,
                    )
                ),
            )
            .join(m.Transaction, m.Allocation.transaction_id == m.Transaction.id)
            .where(
                m.Transaction.organization_id == organization_id,
                ~m.Transaction.voided,
                m.Transaction.occurred_on.between(start, end),
            )
            .group_by(m.Allocation.category_id)
        ).all()
    )


def dashboard(db: Session, organization_id: str, month: str):
    start, end = month_range(month)
    prev_end = start - timedelta(days=1)
    prev_start = prev_end.replace(day=1)
    comparison_end = prev_end
    if month == today().strftime("%Y-%m"):
        comparison_end = prev_end.replace(day=min(today().day, prev_end.day))
    amounts = dict(
        db.execute(
            select(m.Transaction.kind, func.sum(m.Transaction.base_minor))
            .where(
                m.Transaction.organization_id == organization_id,
                ~m.Transaction.voided,
                m.Transaction.occurred_on.between(start, end),
            )
            .group_by(m.Transaction.kind)
        ).all()
    )
    income = int(amounts.get("income", 0))
    expense = int(amounts.get("expense", 0) - amounts.get("refund", 0))
    cats = list(
        db.scalars(
            select(m.Category)
            .where(m.Category.organization_id == organization_id)
            .order_by(m.Category.created_at)
        )
    )
    spent = category_spending(db, organization_id, start, end)
    previous = category_spending(db, organization_id, prev_start, comparison_end)
    limits = {
        b.category_id: b
        for b in db.scalars(
            select(m.Budget).where(
                m.Budget.organization_id == organization_id, m.Budget.month == month
            )
        )
    }
    categories = [
        {
            "id": c.id,
            "name": c.name,
            "color": c.color,
            "icon": c.icon,
            "spent_minor": int(spent.get(c.id, 0)),
            "previous_minor": int(previous.get(c.id, 0)),
            "budget_minor": limits[c.id].amount_minor if c.id in limits else None,
            "budget_id": limits[c.id].id if c.id in limits else None,
        }
        for c in cats
    ]
    if spent.get(None):
        categories.append(
            {
                "id": "uncategorized",
                "name": "Без категории",
                "color": "#a0a8b5",
                "icon": "tag",
                "spent_minor": int(spent[None]),
                "previous_minor": int(previous.get(None, 0)),
                "budget_minor": None,
                "budget_id": None,
            }
        )
    days = defaultdict(lambda: {"income": 0, "expense": 0})
    rows = db.execute(
        select(m.Transaction.occurred_on, m.Transaction.kind, func.sum(m.Transaction.base_minor))
        .where(
            m.Transaction.organization_id == organization_id,
            ~m.Transaction.voided,
            m.Transaction.occurred_on.between(start, end),
            m.Transaction.kind.in_(["expense", "income", "refund"]),
        )
        .group_by(m.Transaction.occurred_on, m.Transaction.kind)
    ).all()
    for day, kind, amount in rows:
        days[day]["income" if kind == "income" else "expense"] += int(amount) * (
            -1 if kind == "refund" else 1
        )
    chart = [{"day": day, **days[start.replace(day=day)]} for day in range(1, end.day + 1)]
    bills = bills_for_month(db, organization_id, month)
    pending = [b for b in bills if b["status"] in {"upcoming", "overdue"}]
    accounts = account_balances(db, organization_id)
    balances: dict[str, int] = defaultdict(int)
    for account in accounts:
        balances[account["currency"]] += account["balance_minor"]
    debts = debt_list(db, organization_id)
    due_debt = sum(
        d["remaining_minor"]
        for d in debts
        if d["direction"] == "borrowed"
        and d["currency"] == "MDL"
        and d["due_date"]
        and d["due_date"] <= end.isoformat()
    )
    return {
        "month": month,
        "income_minor": income,
        "expense_minor": expense,
        "net_minor": income - expense,
        "previous_expense_minor": int(sum(previous.values())),
        "categories": categories,
        "chart": chart,
        "accounts": accounts,
        "balances": dict(balances),
        "bills": bills,
        "debts": debts,
        "planned_remaining_minor": sum(b["base_minor"] for b in pending),
        "available_mdl_minor": balances.get("MDL", 0)
        - sum(b["amount_minor"] for b in pending if b["currency"] == "MDL")
        - due_debt,
        "comparison_label": "За одинаковое число дней"
        if month == today().strftime("%Y-%m")
        else "К предыдущему месяцу",
        "as_of": today().isoformat(),
    }


def recommendations(db: Session, organization_id: str, month: str):
    report = dashboard(db, organization_id, month)
    cards = []
    for c in sorted(report["categories"], key=lambda x: x["spent_minor"], reverse=True):
        spent = c["spent_minor"]
        if c["budget_minor"] and spent > c["budget_minor"]:
            cards.append(
                {
                    "id": "budget-" + c["id"],
                    "kind": "budget",
                    "title": f"Лимит: {c['name']}",
                    "text": f"Потрачено {money(spent)} MDL при лимите {money(c['budget_minor'])} MDL. Пересмотрите оставшиеся покупки этой категории.",
                    "saving_minor": 0,
                    "category_id": c["id"],
                    "basis": "Ваш бюджет и подтверждённые операции",
                }
            )
        elif c["previous_minor"] > 0 and spent > c["previous_minor"] * Decimal("1.25"):
            cards.append(
                {
                    "id": "growth-" + c["id"],
                    "kind": "trend",
                    "title": f"Выросли расходы: {c['name']}",
                    "text": f"Сейчас {money(spent)} MDL, в периоде сравнения — {money(c['previous_minor'])} MDL. Проверьте, были ли крупные разовые покупки.",
                    "saving_minor": 0,
                    "category_id": c["id"],
                    "basis": report["comparison_label"],
                }
            )
        if c["name"] in {"Рестораны и кафе", "Отдых", "Покупки"} and spent > 0:
            cards.append(
                {
                    "id": "scenario-" + c["id"],
                    "kind": "scenario",
                    "title": f"Сценарий −20%: {c['name']}",
                    "text": "Если сократить частоту или стоимость таких покупок на 20%, разница составит указанную сумму. Это расчёт сценария, а не обещанная экономия.",
                    "saving_minor": base_value(spent, Decimal("0.2")),
                    "category_id": c["id"],
                    "basis": f"20% от фактических {money(spent)} MDL",
                }
            )
    start, end = month_range(month)
    from .purchases import PurchaseFilters, history

    comparisons = history(
        db,
        organization_id,
        PurchaseFilters(date_from=start - timedelta(days=90), date_to=end, currency="MDL"),
    )["comparisons"]
    for index, price in enumerate(comparisons):
        low, high = Decimal(price["min_unit_minor"]), Decimal(price["max_unit_minor"])
        if low > 0 and high > low * Decimal("1.15"):
            cards.append(
                {
                    "id": f"price-{price['best_receipt_id']}-{index}",
                    "kind": "price",
                    "title": f"Сравните цену: {price['name']}",
                    "text": f"В ваших чеках цена менялась от {money(int(low.quantize(Decimal(1), rounding=ROUND_HALF_UP)))} до {money(int(high.quantize(Decimal(1), rounding=ROUND_HALF_UP)))} MDL/{price['unit']}. Минимум: «{price['best_merchant']}» ({price['best_on']}). Проверьте совпадение товара и актуальную цену перед покупкой.",
                    "saving_minor": 0,
                    "basis": f"Одинаковое название и единица, {price['receipt_count']} разных чеков. Прошлая цена не гарантирует сегодняшнюю.",
                }
            )
    if not cards:
        cards.append(
            {
                "id": "start",
                "kind": "info",
                "title": "Начнём с ваших данных",
                "text": "Добавьте несколько покупок и лимиты категорий. Здесь появятся изменения расходов, сценарии экономии и сравнение цен из ваших чеков.",
                "saving_minor": 0,
                "basis": "Без вымышленных цен и оценок",
            }
        )
    return {"cards": cards[:16], "report": report}
