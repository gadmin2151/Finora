"""Read-only views of financial entities. No secrets, raw JSON, SQL or writes reach AI."""

from collections import defaultdict

from sqlalchemy import case, func, or_, select
from sqlalchemy.orm import Session, aliased

from . import models as m
from .analytics import Report, ReportMetric, ReportQuery, ReportRow, currency_value
from .finance import occurrence_dates, today
from .i18n import t
from .receipt_authors import creator_filter

ROW_LIMIT = 20
KIND_NAMES = {
    "income": "Доходы",
    "expense": "Расходы",
    "refund": "Возвраты",
    "transfer": "Переводы",
    "adjustment": "Корректировка (+)",
    "adjustment_out": "Корректировка (−)",
    "debt_lend": "Дал в долг",
    "debt_borrow": "Взял в долг",
    "debt_repayment_in": "Мне вернули долг",
    "debt_repayment_out": "Вернул долг",
}


def result(
    query: ReportQuery,
    title: str,
    metrics: list[ReportMetric],
    rows: list[ReportRow],
    count: int,
    *notices: str,
) -> Report:
    return Report(
        query=query,
        title=t(title),
        metrics=metrics,
        rows=rows,
        total_rows=count,
        notices=[t(n) for n in notices],
    )


def snapshot(db: Session, org: str, q: ReportQuery) -> Report:
    """Aggregate balances/debt movements once, including all matching rows in totals."""
    if q.kind == "accounts":
        movement = (
            select(
                m.Posting.account_id.label("id"), func.sum(m.Posting.amount_minor).label("amount")
            )
            .join(m.Transaction, m.Transaction.id == m.Posting.transaction_id)
            .where(m.Transaction.organization_id == org, ~m.Transaction.voided)
            .group_by(m.Posting.account_id)
            .subquery()
        )
        entity, name, value = (
            m.Account,
            m.Account.name,
            m.Account.opening_minor + func.coalesce(movement.c.amount, 0),
        )
        source = (
            select(entity, value.label("balance"))
            .outerjoin(movement, movement.c.id == entity.id)
            .where(entity.organization_id == org)
        )
        title = "Остатки на счетах"
    else:
        movement = (
            select(
                m.Transaction.debt_id.label("id"),
                func.sum(
                    case(
                        (
                            m.Transaction.kind.in_(["debt_lend", "debt_borrow"]),
                            m.Transaction.amount_minor,
                        ),
                        else_=-m.Transaction.amount_minor,
                    )
                ).label("amount"),
            )
            .where(m.Transaction.organization_id == org, ~m.Transaction.voided)
            .group_by(m.Transaction.debt_id)
            .subquery()
        )
        entity, name, value = (
            m.Debt,
            m.Debt.person,
            m.Debt.initial_minor + func.coalesce(movement.c.amount, 0),
        )
        source = (
            select(entity, value.label("balance"))
            .outerjoin(movement, movement.c.id == entity.id)
            .where(entity.organization_id == org)
        )
        title = "Остатки долгов"
    if q.search:
        source = source.where(name.icontains(q.search, autoescape=True))
    if q.currency:
        source = source.where(entity.currency == q.currency)
    selected = source.subquery()
    count = db.scalar(select(func.count()).select_from(selected))
    grouping = [selected.c.currency]
    if q.kind == "debts":
        grouping.append(selected.c.direction)
    totals = db.execute(select(*grouping, func.sum(selected.c.balance)).group_by(*grouping)).all()
    metrics = [ReportMetric(label=t("Записей"), value=str(count))]
    for values in totals:
        label = (
            "Общий остаток"
            if q.kind == "accounts"
            else "Вам должны"
            if values[1] == "lent"
            else "Вы должны"
        )
        metrics.append(ReportMetric(label=t(label), value=currency_value(values[-1], values[0])))
    rows = []
    for row, balance in db.execute(source.order_by(value.desc(), name, entity.id).limit(ROW_LIMIT)):
        if q.kind == "accounts":
            detail = t("В архиве") if row.archived else t("Активный счёт")
            label = row.name
        else:
            detail = t("Вам должны") if row.direction == "lent" else t("Вы должны")
            if row.due_date:
                detail += f" · {row.due_date.isoformat()}"
            detail += f" · {row.note[:300]}" if row.note else ""
            label = row.person
        rows.append(
            ReportRow(label=label, value=currency_value(balance, row.currency), detail=detail)
        )
    return result(
        q,
        title,
        metrics,
        rows,
        count,
        "Текущие остатки на сегодня; период отчёта не ограничивает движения. Валюты показаны отдельно. Архивные счета включены.",
    ).model_copy(update={"as_of": today()})


def transaction_source(org: str, q: ReportQuery):
    tx = m.Transaction
    original = aliased(m.Transaction)
    source = (
        select(
            tx, m.Receipt.created_by, m.User.name.label("author"), m.Account.name.label("account")
        )
        .outerjoin(original, (original.id == tx.refund_of) & (original.organization_id == org))
        .outerjoin(
            m.Receipt,
            (m.Receipt.id == func.coalesce(tx.receipt_id, original.receipt_id))
            & (m.Receipt.organization_id == org),
        )
        .outerjoin(m.User, m.User.id == m.Receipt.created_by)
        .join(m.Account, (m.Account.id == tx.account_id) & (m.Account.organization_id == org))
        .where(
            tx.organization_id == org, ~tx.voided, tx.occurred_on.between(q.date_from, q.date_to)
        )
    )
    if q.currency:
        source = source.where(tx.currency == q.currency)
    if q.merchant:
        source = source.where(tx.merchant.icontains(q.merchant, autoescape=True))
    if q.created_by:
        source = source.where(
            m.Receipt.deleted_at.is_(None),
            m.Receipt.status == "posted",
            creator_filter(q.created_by),
        )
    if q.search:
        source = source.where(
            or_(
                tx.merchant.icontains(q.search, autoescape=True),
                tx.note.icontains(q.search, autoescape=True),
            )
        )
    if q.transaction_kind:
        source = source.where(tx.kind == q.transaction_kind)
    if q.category_id:
        category = (
            m.Allocation.category_id.is_(None)
            if q.category_id == "uncategorized"
            else m.Allocation.category_id == q.category_id
        )
        source = source.where(
            select(m.Allocation.id).where(m.Allocation.transaction_id == tx.id, category).exists()
        )
    return source


def transactions(db: Session, org: str, q: ReportQuery) -> Report:
    source = transaction_source(org, q)
    selected = source.subquery()
    count = db.scalar(select(func.count()).select_from(selected))
    metrics = [ReportMetric(label=t("Операций"), value=str(count))]
    metrics.extend(
        ReportMetric(label=t(KIND_NAMES.get(kind, kind)), value=currency_value(value, currency))
        for kind, currency, value in db.execute(
            select(selected.c.kind, selected.c.currency, func.sum(selected.c.amount_minor))
            .group_by(selected.c.kind, selected.c.currency)
            .order_by(selected.c.kind, selected.c.currency)
        )
    )
    rows = [
        ReportRow(
            label=tx.merchant or t(KIND_NAMES.get(tx.kind, tx.kind)),
            value=currency_value(tx.amount_minor, tx.currency),
            detail=" · ".join(
                str(v)
                for v in [
                    tx.occurred_on,
                    t(KIND_NAMES.get(tx.kind, tx.kind)),
                    account,
                    author,
                    tx.note[:300],
                ]
                if v
            ),
            receipt_id=tx.receipt_id,
        )
        for tx, _, author, account in db.execute(
            source.order_by(
                m.Transaction.occurred_on.desc(), m.Transaction.created_at.desc(), m.Transaction.id
            ).limit(ROW_LIMIT)
        )
    ]
    return result(
        q,
        "История операций",
        metrics,
        rows,
        count,
        "Отменённые операции исключены. Переводы и движения долгов показаны отдельно и не являются доходами или расходами. При фильтре категории показана полная сумма подходящей операции.",
    )


def users(db: Session, org: str, q: ReportQuery) -> Report:
    source = transaction_source(org, q).where(
        m.Transaction.kind.in_(["expense", "refund"]),
        m.Receipt.status == "posted",
        m.Receipt.deleted_at.is_(None),
    )
    selected = source.subquery()
    signed = case(
        (selected.c.kind == "refund", -selected.c.base_minor), else_=selected.c.base_minor
    )
    if q.category_id:
        category = (
            m.Allocation.category_id.is_(None)
            if q.category_id == "uncategorized"
            else m.Allocation.category_id == q.category_id
        )
        part = (
            select(func.sum(m.Allocation.base_minor))
            .where(m.Allocation.transaction_id == selected.c.id, category)
            .scalar_subquery()
        )
        signed = case((selected.c.kind == "refund", -part), else_=part)
    grouped = (
        select(
            selected.c.created_by,
            selected.c.author,
            func.sum(signed).label("spent"),
            func.count().label("count"),
        )
        .group_by(selected.c.created_by, selected.c.author)
        .subquery()
    )
    count = db.scalar(select(func.count()).select_from(grouped))
    author_count = db.scalar(select(func.count(func.distinct(grouped.c.created_by))))
    total = db.scalar(select(func.coalesce(func.sum(grouped.c.spent), 0)))
    rows = [
        ReportRow(
            label=r.author or t("Автор не указан"),
            value=currency_value(r.spent),
            detail=t("Операций: {p0}", p0=r.count),
        )
        for r in db.execute(
            select(grouped).order_by(grouped.c.spent.desc(), grouped.c.created_by).limit(ROW_LIMIT)
        )
    ]
    return result(
        q,
        "Расходы по пользователям",
        [
            ReportMetric(label=t("Расходы после возвратов"), value=currency_value(total)),
            ReportMetric(label=t("Пользователей"), value=str(author_count)),
        ],
        rows,
        count,
        "Автор — пользователь, который добавил чек. Операции без чека не приписываются пользователям. Возвраты отнесены к автору исходного чека; суммы в MDL по сохранённым курсам.",
    )


def receipts(db: Session, org: str, q: ReportQuery) -> Report:
    receipt = m.Receipt
    source = (
        select(receipt, m.User.name.label("author"))
        .outerjoin(m.User, m.User.id == receipt.created_by)
        .where(
            receipt.organization_id == org,
            receipt.deleted_at.is_(None),
            func.coalesce(receipt.purchased_on, func.date(receipt.created_at)).between(
                q.date_from, q.date_to
            ),
        )
    )
    if q.currency:
        source = source.where(receipt.currency == q.currency)
    if q.created_by:
        source = source.where(creator_filter(q.created_by))
    if q.merchant:
        source = source.where(receipt.merchant.icontains(q.merchant, autoescape=True))
    if q.search:
        matches_item = (
            select(m.ReceiptItem.id)
            .where(
                m.ReceiptItem.receipt_id == receipt.id,
                m.ReceiptItem.name.icontains(q.search, autoescape=True),
            )
            .exists()
        )
        matches_comment = (
            select(m.ReceiptComment.id)
            .where(
                m.ReceiptComment.organization_id == org,
                m.ReceiptComment.receipt_id == receipt.id,
                m.ReceiptComment.text.icontains(q.search, autoescape=True),
            )
            .exists()
        )
        source = source.where(
            or_(
                receipt.merchant.icontains(q.search, autoescape=True), matches_item, matches_comment
            )
        )
    if q.category_id:
        item = select(m.ReceiptItem.id).where(m.ReceiptItem.receipt_id == receipt.id)
        item = item.where(
            m.ReceiptItem.category_id.is_(None)
            if q.category_id == "uncategorized"
            else m.ReceiptItem.category_id == q.category_id
        )
        source = source.where(item.exists())
    selected = source.subquery()
    count = db.scalar(select(func.count()).select_from(selected))
    metrics = [ReportMetric(label=t("Чеков"), value=str(count))]
    metrics.extend(
        ReportMetric(label=t("Стоимость чеков"), value=currency_value(value, currency))
        for currency, value in db.execute(
            select(
                selected.c.currency, func.coalesce(func.sum(selected.c.total_minor), 0)
            ).group_by(selected.c.currency)
        )
    )
    chosen = db.execute(
        source.order_by(receipt.purchased_on.desc(), receipt.created_at.desc(), receipt.id).limit(
            ROW_LIMIT
        )
    ).all()
    ids = [r.id for r, _ in chosen]
    comments = defaultdict(list)
    for row in db.scalars(
        select(m.ReceiptComment)
        .where(m.ReceiptComment.organization_id == org, m.ReceiptComment.receipt_id.in_(ids))
        .order_by(m.ReceiptComment.created_at.desc(), m.ReceiptComment.id)
        .limit(60)
    ):
        comments[row.receipt_id].append(row.text[:300])
    rows = [
        ReportRow(
            label=r.merchant or t("Без магазина"),
            value=currency_value(r.total_minor, r.currency)
            if r.total_minor is not None
            else t("Не распознано"),
            detail=" · ".join(
                v
                for v in [
                    str(r.purchased_on or r.created_at.date()),
                    t(r.status),
                    author or t("Автор не указан"),
                    " / ".join(comments[r.id][:3]),
                ]
                if v
            ),
            receipt_id=r.id,
        )
        for r, author in chosen
    ]
    return result(
        q,
        "Найденные чеки",
        metrics,
        rows,
        count,
        "Включены черновики и подтверждённые чеки. Их сумма не равна расходам: только подтверждение создаёт расход. Для показанных чеков приведено до трёх последних комментариев; оригинал открывается по карточке.",
    )


def plans(db: Session, org: str, q: ReportQuery) -> Report:
    kind = "income" if q.kind == "income_plans" else "expense"
    source = select(m.Bill).where(
        m.Bill.organization_id == org,
        m.Bill.kind == kind,
        m.Bill.active,
        m.Bill.start_date <= q.date_to,
    )
    if q.search:
        source = source.where(m.Bill.name.icontains(q.search, autoescape=True))
    if q.currency:
        source = source.where(m.Bill.currency == q.currency)
    if q.category_id:
        source = source.where(
            m.Bill.category_id.is_(None)
            if q.category_id == "uncategorized"
            else m.Bill.category_id == q.category_id
        )
    # Stream templates; generating a schedule must not create Occurrences or lock the organization.
    totals = defaultdict(int)
    preview = []
    matching = 0
    for bill in db.scalars(source.order_by(m.Bill.name, m.Bill.id)).yield_per(100):
        dates = occurrence_dates(bill, q.date_from, q.date_to)
        if not dates:
            continue
        matching += 1
        value = bill.amount_minor * len(dates)
        totals[bill.currency] += value
        if len(preview) < ROW_LIMIT:
            preview.append(
                ReportRow(
                    label=bill.name,
                    value=currency_value(value, bill.currency),
                    detail=t(
                        "Платежей: {p0}; каждый по {p1}. Первый: {p2}, последний: {p3}.",
                        p0=len(dates),
                        p1=currency_value(bill.amount_minor, bill.currency),
                        p2=dates[0],
                        p3=dates[-1],
                    ),
                )
            )
    metrics = [ReportMetric(label=t("Активных планов в периоде"), value=str(matching))]
    metrics.extend(
        ReportMetric(label=t("Запланировано"), value=currency_value(value, currency))
        for currency, value in sorted(totals.items())
    )
    return result(
        q,
        "Планы доходов" if kind == "income" else "Регулярные платежи",
        metrics,
        preview,
        matching,
        "Расписание по текущим активным шаблонам, без изменений в базе. План не означает фактическую оплату; оплаченные операции и пропуски проверяются в истории операций.",
    )


def budgets(db: Session, org: str, q: ReportQuery) -> Report:
    source = transaction_source(org, q).where(m.Transaction.kind.in_(["expense", "refund"]))
    selected = source.subquery()
    category_filter = (
        m.Allocation.category_id.is_(None)
        if q.category_id == "uncategorized"
        else m.Allocation.category_id == q.category_id
    )
    spending_query = (
        select(
            m.Allocation.category_id.label("id"),
            func.sum(
                case(
                    (selected.c.kind == "refund", -m.Allocation.base_minor),
                    else_=m.Allocation.base_minor,
                )
            ).label("spent"),
        )
        .join(selected, selected.c.id == m.Allocation.transaction_id)
        .group_by(m.Allocation.category_id)
    )
    limits_query = (
        select(m.Budget.category_id.label("id"), func.sum(m.Budget.amount_minor).label("budget"))
        .where(
            m.Budget.organization_id == org,
            m.Budget.month.between(q.date_from.strftime("%Y-%m"), q.date_to.strftime("%Y-%m")),
        )
        .group_by(m.Budget.category_id)
    )
    if q.category_id:
        spending_query = spending_query.where(category_filter)
        limits_query = limits_query.where(m.Budget.category_id == q.category_id)
    spending, limits = spending_query.subquery(), limits_query.subquery()
    ids = select(spending.c.id).union(select(limits.c.id)).subquery()
    selected = (
        select(
            func.coalesce(m.Category.name, t("Без категории")).label("name"),
            func.coalesce(spending.c.spent, 0).label("spent"),
            limits.c.budget,
        )
        .select_from(ids)
        .outerjoin(
            spending, or_(spending.c.id == ids.c.id, spending.c.id.is_(None) & ids.c.id.is_(None))
        )
        .outerjoin(limits, limits.c.id == ids.c.id)
        .outerjoin(m.Category, (m.Category.id == ids.c.id) & (m.Category.organization_id == org))
        .subquery()
    )
    count, spent, budget = db.execute(
        select(
            func.count(),
            func.coalesce(func.sum(selected.c.spent), 0),
            func.coalesce(func.sum(selected.c.budget), 0),
        )
    ).one()
    rows = [
        ReportRow(
            label=row.name,
            value=currency_value(row.spent),
            detail=t("Лимит: {p0}", p0=currency_value(row.budget))
            if row.budget is not None
            else t("Без лимита"),
        )
        for row in db.execute(
            select(selected).order_by(selected.c.spent.desc(), selected.c.name).limit(ROW_LIMIT)
        )
    ]
    metrics = [
        ReportMetric(label=t("Запланировано по категориям"), value=currency_value(budget)),
        ReportMetric(label=t("Расходы после возвратов"), value=currency_value(spent)),
    ]
    return result(
        q,
        "Бюджеты и расходы",
        metrics,
        rows,
        count,
        "Лимиты суммируются за месяцы, затронутые периодом; расходы — строго за выбранные даты. Лимиты принадлежат всей организации и не разделяются по авторам. Суммы в MDL по сохранённым курсам.",
    )


def workspace_report(db: Session, organization_id: str, query: ReportQuery) -> Report:
    handlers = {
        "accounts": snapshot,
        "debts": snapshot,
        "transactions": transactions,
        "users": users,
        "receipts": receipts,
        "bills": plans,
        "income_plans": plans,
        "budgets": budgets,
    }
    return handlers[query.kind](db, organization_id, query)
