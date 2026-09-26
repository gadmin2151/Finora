"""Bounded, organization-scoped reports shared by the UI and the read-only assistant."""

from datetime import date
from decimal import ROUND_HALF_UP, Decimal
from typing import Literal

from pydantic import Field, model_validator
from sqlalchemy import case, extract, func, select

from . import models as m
from .finance import money, owned
from .i18n import t, unit_label
from .purchases import PurchaseFilters, history
from .schemas import Currency, Strict


class ReportQuery(Strict):
    kind: Literal["summary", "categories", "merchants", "trend", "purchases", "prices"]
    date_from: date
    date_to: date
    search: str = Field(default="", max_length=100)
    merchant: str = Field(default="", max_length=100)
    category_id: str = Field(default="", max_length=36)
    currency: Currency | None = None

    @model_validator(mode="after")
    def bounded_period(self):
        if not 1990 <= self.date_from.year <= self.date_to.year <= 2100:
            raise ValueError(t("Допустимы даты с 1990 по 2100 год"))
        if not 0 <= (self.date_to - self.date_from).days <= 730:
            raise ValueError(t("Выберите период до двух лет, от начала к концу"))
        if self.search and self.kind not in {"purchases", "prices"}:
            raise ValueError(t("Поиск по названию доступен для товаров и цен"))
        return self


class ReportMetric(Strict):
    label: str
    value: str


class ReportRow(Strict):
    label: str
    value: str
    detail: str = ""
    receipt_id: str | None = None


class Report(Strict):
    query: ReportQuery
    title: str
    metrics: list[ReportMetric]
    rows: list[ReportRow]
    total_rows: int
    notices: list[str]


def currency_value(value, currency="MDL") -> str:
    return f"{money(int(Decimal(value).quantize(Decimal(1), rounding=ROUND_HALF_UP)))} {currency}"


def _purchase_report(db, organization_id: str, query: ReportQuery) -> Report:
    result = history(
        db,
        organization_id,
        PurchaseFilters(**query.model_dump(exclude={"kind"}), limit=20),
    )
    metrics = [
        ReportMetric(label=t("Найдено позиций"), value=str(result["total"])),
        ReportMetric(label=t("Чеков"), value=str(result["receipt_count"])),
    ]
    metrics.extend(
        ReportMetric(
            label=t("Стоимость покупок"),
            value=currency_value(total["total_minor"], total["currency"]),
        )
        for total in result["totals"]
    )
    notices = [
        t(
            "Только подтверждённые чеки. Возвраты учтены в финансовой сводке, но не вычитаются из товарных строк."
        )
    ]
    if query.kind == "prices":
        rows = [
            ReportRow(
                label=p["name"],
                value=f"{currency_value(p['min_unit_minor'], p['currency'])} — {currency_value(p['max_unit_minor'], p['currency'])}/{unit_label(p['unit'])}",
                detail=t(
                    "Минимум: {p0}, {p1}. Чеков: {p2}. Сценарий по прошлому минимуму: {p3}.",
                    p0=p["best_merchant"],
                    p1=p["best_on"],
                    p2=p["receipt_count"],
                    p3=currency_value(p["potential_minor"], p["currency"]),
                ),
                receipt_id=p["best_receipt_id"],
            )
            for p in result["comparisons"]
        ]
        notices.append(
            t(
                "Сравнение одинаковых названий, единиц и валют минимум в двух чеках. Прошлая цена не гарантирует такую цену сегодня; сравнение не учитывает размер упаковки."
            )
        )
        notices.append(
            t(
                "Показано до 12 вариантов сравнения. Это не полный каталог товаров; уточните название или валюту, чтобы сузить выборку."
            )
        )
        return Report(
            query=query,
            title=t("Цены из вашей истории"),
            metrics=metrics,
            rows=rows,
            total_rows=len(rows),
            notices=notices,
        )
    rows = [
        ReportRow(
            label=p["name"],
            value=currency_value(p["total_minor"], p["currency"]),
            detail=f"{p['purchased_on']} · {p['merchant']} · {format(Decimal(p['quantity']).normalize(), 'f')} {unit_label(p['unit'])} × {currency_value(p['unit_minor'], p['currency'])}",
            receipt_id=p["receipt_id"],
        )
        for p in result["items"]
    ]
    return Report(
        query=query,
        title=t("Найденные покупки"),
        metrics=metrics,
        rows=rows,
        total_rows=result["total"],
        notices=notices,
    )


def report(db, organization_id: str, query: ReportQuery) -> Report:
    if query.category_id and query.category_id != "uncategorized":
        owned(db, m.Category, query.category_id, organization_id)
    if query.kind in {"purchases", "prices"}:
        return _purchase_report(db, organization_id, query)
    tx, allocation = m.Transaction, m.Allocation
    clauses = [
        tx.organization_id == organization_id,
        ~tx.voided,
        tx.kind.in_(["income", "expense", "refund"]),
        tx.occurred_on.between(query.date_from, query.date_to),
    ]
    if query.merchant:
        clauses.append(tx.merchant.icontains(query.merchant, autoescape=True))
    if query.currency:
        clauses.append(tx.currency == query.currency)
    category_clause = (
        allocation.category_id.is_(None)
        if query.category_id == "uncategorized"
        else allocation.category_id == query.category_id
    )
    base = tx.base_minor
    if query.category_id:
        base = (
            select(func.sum(allocation.base_minor))
            .where(allocation.transaction_id == tx.id, category_clause)
            .scalar_subquery()
        )
        clauses.append(base.is_not(None))
    selected = (
        select(tx.id, tx.kind, tx.merchant, tx.occurred_on, base.label("value"))
        .where(*clauses)
        .subquery()
    )
    expense = case(
        (selected.c.kind == "expense", selected.c.value),
        (selected.c.kind == "refund", -selected.c.value),
        else_=0,
    )
    income = case((selected.c.kind == "income", selected.c.value), else_=0)
    count, expenses, incomes, refunds = db.execute(
        select(
            func.count(),
            func.coalesce(func.sum(expense), 0),
            func.coalesce(func.sum(income), 0),
            func.coalesce(
                func.sum(case((selected.c.kind == "refund", selected.c.value), else_=0)), 0
            ),
        )
    ).one()
    metrics = [
        ReportMetric(label=t("Доходы"), value=currency_value(incomes)),
        ReportMetric(label=t("Расходы после возвратов"), value=currency_value(expenses)),
        ReportMetric(label=t("Остаток доходов"), value=currency_value(incomes - expenses)),
        ReportMetric(label=t("Возвраты"), value=currency_value(refunds)),
        ReportMetric(label=t("Операций"), value=str(count)),
    ]
    notices = [
        t(
            "Суммы в MDL по сохранённым курсам операций. Переводы, долги и отменённые операции исключены."
        )
    ]
    if query.category_id:
        notices.append(
            t("Для категории учтена только её часть разделённых расходов; доходы сюда не входят.")
        )
    rows: list[ReportRow] = []
    total_rows = 0
    titles = {
        "summary": t("Финансовая сводка"),
        "categories": t("Расходы по категориям"),
        "merchants": t("Расходы по магазинам"),
        "trend": t("Динамика по месяцам"),
    }
    if query.kind == "categories":
        signed = case(
            (selected.c.kind == "refund", -allocation.base_minor), else_=allocation.base_minor
        )
        grouped = (
            select(
                allocation.category_id,
                func.coalesce(m.Category.name, t("Без категории")).label("label"),
                func.sum(signed).label("amount"),
                func.count(func.distinct(selected.c.id)).label("count"),
            )
            .join(selected, selected.c.id == allocation.transaction_id)
            .outerjoin(
                m.Category,
                (m.Category.id == allocation.category_id)
                & (m.Category.organization_id == organization_id),
            )
            .group_by(allocation.category_id, m.Category.name)
        )
        if query.category_id:
            grouped = grouped.where(category_clause)
        grouped = grouped.subquery()
        total_rows = db.scalar(select(func.count()).select_from(grouped))
        rows = [
            ReportRow(
                label=r.label,
                value=currency_value(r.amount),
                detail=t("Операций: {p0}", p0=r.count),
            )
            for r in db.execute(
                select(grouped).order_by(grouped.c.amount.desc(), grouped.c.label).limit(20)
            )
        ]
    elif query.kind == "merchants":
        grouped = (
            select(
                selected.c.merchant.label("label"),
                func.sum(expense).label("amount"),
                func.count().label("count"),
            )
            .where(selected.c.kind.in_(["expense", "refund"]))
            .group_by(selected.c.merchant)
            .subquery()
        )
        total_rows = db.scalar(select(func.count()).select_from(grouped))
        rows = [
            ReportRow(
                label=r.label or t("Без магазина"),
                value=currency_value(r.amount),
                detail=t("Операций: {p0}", p0=r.count),
            )
            for r in db.execute(
                select(grouped).order_by(grouped.c.amount.desc(), grouped.c.label).limit(20)
            )
        ]
    elif query.kind == "trend":
        year, month = (
            extract("year", selected.c.occurred_on),
            extract("month", selected.c.occurred_on),
        )
        values = db.execute(
            select(year, month, func.sum(expense), func.sum(income))
            .group_by(year, month)
            .order_by(year, month)
        ).all()
        rows = [
            ReportRow(
                label=f"{int(y)}-{int(mo):02}",
                value=currency_value(ex),
                detail=t(
                    "Доходы: {p0} · Остаток: {p1}",
                    p0=currency_value(inc),
                    p1=currency_value(inc - ex),
                ),
            )
            for y, mo, ex, inc in values
        ]
        total_rows = len(rows)
        notices.append(
            t("Показаны месяцы с операциями; первый и последний месяц могут быть неполными.")
        )
    else:
        days = (query.date_to - query.date_from).days + 1
        metrics.append(
            ReportMetric(
                label=t("Расходы в среднем за день периода"),
                value=currency_value(Decimal(expenses) / days),
            )
        )
    return Report(
        query=query,
        title=titles[query.kind],
        metrics=metrics,
        rows=rows,
        total_rows=total_rows,
        notices=notices,
    )
