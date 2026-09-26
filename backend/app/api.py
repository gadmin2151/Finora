import csv
import hashlib
import io
import json
from datetime import timedelta
from decimal import Decimal

from fastapi import (
    APIRouter,
    Depends,
    File,
    Form,
    Query,
    Request,
    Response,
    UploadFile,
)
from fastapi.responses import FileResponse, StreamingResponse
from sqlalchemy import delete, func, or_, select
from sqlalchemy.orm import Session

from . import ai, ledger
from . import models as m
from . import schemas as s
from .category_refresh import install_daily_categories, refresh_receipt_categories
from .config import settings
from .db import SessionLocal, get_db
from .finance import (
    account_balances,
    audit,
    bills_for_month,
    create_transaction,
    dashboard,
    debt_list,
    fail,
    lock_organization,
    minor,
    month_range,
    owned,
    recommendations,
    transaction_dict,
)
from .i18n import current_language, t, translated_notice
from .organizations import identity
from .receipt_files import append_images
from .receipts import ReceiptError, confirm_receipt, image_bytes, mev_url, receipt_dict
from .security import (
    current_organization,
    current_user,
    dummy_hash,
    encrypt,
    hasher,
    issue_session,
    lock_login_attempts,
    lock_user_credentials,
    verify_password,
)
from .web_receipts import WebReceiptError, receipt_link

router = APIRouter(prefix="/api")
DB = Depends(get_db)
ACTOR = Depends(current_user)
SCOPE = Depends(current_organization)


def enqueue(db, organization_id: str, kind: str, payload: dict):
    lock_organization(db, organization_id)
    if (
        db.scalar(
            select(func.count())
            .select_from(m.Job)
            .where(
                m.Job.organization_id == organization_id, m.Job.status.in_(["queued", "running"])
            )
        )
        >= 10
    ):
        fail("В очереди уже 10 задач. Дождитесь обработки", 429)
    job = m.Job(
        organization_id=organization_id,
        kind=kind,
        payload={**payload, "language": current_language()},
    )
    db.add(job)
    db.flush()
    return job


@router.get("/health")
def health(db: Session = DB):
    db.execute(select(1))
    return {"status": "ok", "version": "1.0.0"}


@router.post("/auth/login")
def login(data: s.Login, request: Request, response: Response, db: Session = DB):
    if request.headers.get("x-finora-client") != "web":
        fail("Используйте форму входа Finora", 403)
    ip = request.client.host if request.client else "unknown"
    keys = [
        hashlib.sha256(v.encode()).hexdigest()
        for v in ["ip:" + ip, "user:" + data.username.casefold()]
    ]
    lock_login_attempts(db, keys)
    cutoff = m.now() - timedelta(minutes=15)
    db.execute(delete(m.LoginAttempt).where(m.LoginAttempt.created_at < cutoff))
    count = db.scalar(
        select(func.count()).select_from(m.LoginAttempt).where(m.LoginAttempt.key.in_(keys))
    )
    if count >= 16:
        fail("Слишком много попыток входа. Повторите через 15 минут", 429)
    user = db.scalar(select(m.User).where(m.User.username == data.username))
    verified_hash = user.password_hash if user else dummy_hash
    valid = verify_password(data.password, verified_hash)
    if valid and user:
        user = lock_user_credentials(db, user.id)
    if (
        not valid
        or not user
        or not user.is_active
        or user.deleted_at
        or user.password_hash != verified_hash
    ):
        db.add_all([m.LoginAttempt(key=key) for key in keys])
        db.commit()
        fail("Неверный логин или пароль", 401)
    db.execute(delete(m.Session).where(m.Session.expires_at < m.now()))
    session = issue_session(db, user, request, response)
    db.commit()
    return identity(db, user, session.csrf)


@router.get("/auth/me")
def me(request: Request, user: m.User = ACTOR, db: Session = DB):
    return identity(db, user, request.state.session.csrf)


@router.post("/auth/logout")
def logout(request: Request, response: Response, user: m.User = ACTOR, db: Session = DB):
    db.delete(request.state.session)
    db.commit()
    response.delete_cookie(
        "finance_session",
        path="/",
        secure=settings().cookie_secure,
        httponly=True,
        samesite="strict",
    )
    return {"ok": True}


@router.post("/auth/password")
def password(data: s.PasswordChange, request: Request, user: m.User = ACTOR, db: Session = DB):
    user = lock_user_credentials(db, user.id)
    if (
        not user
        or not user.is_active
        or not db.scalar(
            select(m.Session.id).where(
                m.Session.id == request.state.session.id, m.Session.expires_at > m.now()
            )
        )
    ):
        fail("Войдите в свой аккаунт", 401)
    if not verify_password(data.current_password, user.password_hash):
        fail("Текущий пароль неверен")
    user.password_hash = hasher.hash(data.new_password)
    db.execute(
        delete(m.Session).where(
            m.Session.user_id == user.id, m.Session.id != request.state.session.id
        )
    )
    db.commit()
    return {"ok": True}


@router.get("/auth/sessions")
def sessions(request: Request, user: m.User = ACTOR, db: Session = DB):
    return [
        {
            "id": row.id,
            "device": row.device,
            "created_at": row.created_at,
            "current": row.id == request.state.session.id,
        }
        for row in db.scalars(
            select(m.Session).where(m.Session.user_id == user.id, m.Session.expires_at > m.now())
        )
    ]


@router.delete("/auth/sessions/{key}")
def revoke_session(key: str, user: m.User = ACTOR, db: Session = DB):
    row = db.scalar(select(m.Session).where(m.Session.id == key, m.Session.user_id == user.id))
    if row is None:
        fail("Сессия не найдена", 404)
    db.delete(row)
    db.commit()
    return {"ok": True}


@router.get("/dashboard")
def get_dashboard(month: str, user: m.Organization = SCOPE, db: Session = DB):
    result = dashboard(db, user.id, month)
    db.commit()
    return result


@router.get("/accounts")
def accounts(user: m.Organization = SCOPE, db: Session = DB):
    return account_balances(db, user.id)


@router.post("/accounts")
def add_account(data: s.AccountInput, user: m.Organization = SCOPE, db: Session = DB):
    account = m.Account(
        organization_id=user.id,
        **data.model_dump(exclude={"opening_balance"}),
        opening_minor=minor(data.opening_balance),
    )
    db.add(account)
    db.flush()
    audit(db, user.id, "account.created", account.id)
    db.commit()
    return {"id": account.id}


@router.post("/accounts/{key}/balance-adjustment")
def adjust_balance(
    key: str, data: s.BalanceAdjustment, user: m.Organization = SCOPE, db: Session = DB
):
    tx, before, difference = ledger.adjust_account_balance(db, user.id, key, data)
    account = next(row for row in account_balances(db, user.id) if row["id"] == key)
    result = {
        "transaction": transaction_dict(tx),
        "account": account,
        "previous_balance_minor": before,
        "adjustment_minor": difference,
    }
    db.commit()
    return result


@router.put("/accounts/{key}")
def edit_account(key: str, data: s.AccountInput, user: m.Organization = SCOPE, db: Session = DB):
    lock_organization(db, user.id)
    row = owned(db, m.Account, key, user.id)
    has_movements = db.scalar(
        select(func.count()).select_from(m.Posting).where(m.Posting.account_id == key)
    )
    if has_movements and row.currency != data.currency:
        fail("Валюту счёта с операциями нельзя изменить. Для другой валюты создайте отдельный счёт")
    audit(
        db,
        user.id,
        "account.edited",
        row.id,
        {
            "opening_minor_before": row.opening_minor,
            "opening_minor_after": minor(data.opening_balance),
        },
    )
    row.name, row.kind, row.color = data.name, data.kind, data.color
    row.currency, row.opening_minor = data.currency, minor(data.opening_balance)
    db.commit()
    return {"id": row.id}


@router.post("/accounts/{key}/archive")
def archive_account(key: str, user: m.Organization = SCOPE, db: Session = DB):
    lock_organization(db, user.id)
    account = owned(db, m.Account, key, user.id)
    account.archived = not account.archived
    prefs = db.scalar(select(m.Preferences).where(m.Preferences.organization_id == user.id))
    if account.archived and prefs.default_account_id == key:
        prefs.default_account_id = None
    db.commit()
    return {"archived": account.archived}


@router.get("/categories")
def categories(user: m.Organization = SCOPE, db: Session = DB):
    return [
        {
            "id": row.id,
            "name": row.name,
            "color": row.color,
            "icon": row.icon,
            "parent_id": row.parent_id,
        }
        for row in db.scalars(
            select(m.Category)
            .where(m.Category.organization_id == user.id)
            .order_by(m.Category.created_at)
        )
    ]


@router.post("/categories")
def add_category(data: s.CategoryInput, user: m.Organization = SCOPE, db: Session = DB):
    if data.parent_id:
        owned(db, m.Category, data.parent_id, user.id)
    row = m.Category(organization_id=user.id, **data.model_dump())
    db.add(row)
    db.commit()
    return {"id": row.id}


@router.post("/categories/daily")
def add_daily_categories(user: m.Organization = SCOPE, db: Session = DB):
    added = install_daily_categories(db, user.id)
    db.commit()
    return {"added": added}


@router.post("/categories/refresh")
def refresh_categories(data: s.CategoryRefresh, user: m.Organization = SCOPE, db: Session = DB):
    result = refresh_receipt_categories(db, user.id, data.after)
    db.commit()
    return result


@router.put("/categories/{key}")
def edit_category(key: str, data: s.CategoryInput, user: m.Organization = SCOPE, db: Session = DB):
    row = owned(db, m.Category, key, user.id)
    if data.parent_id:
        parent = owned(db, m.Category, data.parent_id, user.id)
        seen = {row.id}
        while parent:
            if parent.id in seen:
                fail("Категории не могут образовывать круг")
            seen.add(parent.id)
            parent = owned(db, m.Category, parent.parent_id, user.id) if parent.parent_id else None
    for key, value in data.model_dump().items():
        setattr(row, key, value)
    db.commit()
    return {"id": row.id}


@router.get("/transactions")
def transactions(
    month: str | None = None,
    search: str = Query("", max_length=100),
    kind: str = "",
    category_id: str = "",
    account_id: str = "",
    offset: int = Query(0, ge=0),
    limit: int = Query(50, ge=1, le=200),
    user: m.Organization = SCOPE,
    db: Session = DB,
):
    query = select(m.Transaction).where(
        m.Transaction.organization_id == user.id, ~m.Transaction.voided
    )
    if month:
        query = query.where(m.Transaction.occurred_on.between(*month_range(month)))
    if kind:
        query = query.where(m.Transaction.kind == kind)
    if category_id:
        query = query.where(
            m.Transaction.id.in_(
                select(m.Allocation.transaction_id).where(m.Allocation.category_id == category_id)
            )
        )
    if account_id:
        query = query.where(
            or_(
                m.Transaction.account_id == account_id,
                m.Transaction.target_account_id == account_id,
            )
        )
    if search:
        query = query.where(
            or_(
                m.Transaction.merchant.icontains(search, autoescape=True),
                m.Transaction.note.icontains(search, autoescape=True),
            )
        )
    count = db.scalar(select(func.count()).select_from(query.subquery()))
    rows = list(
        db.scalars(
            query.order_by(m.Transaction.occurred_on.desc(), m.Transaction.created_at.desc())
            .offset(offset)
            .limit(limit)
        )
    )
    splits = {}
    for a in db.scalars(
        select(m.Allocation).where(m.Allocation.transaction_id.in_([r.id for r in rows]))
    ):
        splits.setdefault(a.transaction_id, []).append(
            {"category_id": a.category_id, "amount_minor": a.amount_minor}
        )
    return {
        "items": [{**transaction_dict(r), "splits": splits.get(r.id, [])} for r in rows],
        "total": count,
    }


@router.post("/transactions")
def add_transaction(data: s.TransactionInput, user: m.Organization = SCOPE, db: Session = DB):
    tx = create_transaction(db, user.id, data)
    db.commit()
    return transaction_dict(tx)


@router.put("/transactions/{key}")
def edit_transaction(
    key: str, data: s.TransactionEdit, user: m.Organization = SCOPE, db: Session = DB
):
    tx = ledger.edit_transaction(db, user.id, key, data)
    db.commit()
    return transaction_dict(tx)


@router.delete("/transactions/{key}")
def void_transaction(key: str, version: int, user: m.Organization = SCOPE, db: Session = DB):
    tx = ledger.void_transaction(db, user.id, key, version)
    db.commit()
    return transaction_dict(tx)


@router.get("/debts")
def debts(user: m.Organization = SCOPE, db: Session = DB):
    return debt_list(db, user.id)


@router.post("/debts")
def add_debt(data: s.DebtInput, user: m.Organization = SCOPE, db: Session = DB):
    debt = ledger.add_debt(db, user.id, data)
    db.commit()
    return {"id": debt.id}


@router.post("/debts/{key}/repay")
def repay_debt(key: str, data: s.DebtPayment, user: m.Organization = SCOPE, db: Session = DB):
    tx = ledger.repay_debt(db, user.id, key, data)
    db.commit()
    return transaction_dict(tx)


@router.post("/debts/{key}/increase")
def increase_debt(key: str, data: s.DebtMovement, user: m.Organization = SCOPE, db: Session = DB):
    tx = ledger.increase_debt(db, user.id, key, data)
    db.commit()
    return transaction_dict(tx)


@router.get("/bills")
def bills(month: str, user: m.Organization = SCOPE, db: Session = DB):
    result = bills_for_month(db, user.id, month)
    db.commit()
    return result


@router.get("/bills/templates")
def bill_templates(user: m.Organization = SCOPE, db: Session = DB):
    return [
        {
            "id": b.id,
            "name": b.name,
            "amount_minor": b.amount_minor,
            "currency": b.currency,
            "fx_rate": str(b.fx_rate),
            "category_id": b.category_id,
            "account_id": b.account_id,
            "start_date": b.start_date,
            "recurrence": b.recurrence,
            "active": b.active,
        }
        for b in db.scalars(
            select(m.Bill)
            .where(m.Bill.organization_id == user.id, m.Bill.kind == "expense")
            .order_by(m.Bill.created_at)
        )
    ]


@router.post("/bills")
def add_bill(data: s.BillInput, user: m.Organization = SCOPE, db: Session = DB):
    row = ledger.save_bill(db, user.id, data)
    db.commit()
    return {"id": row.id}


@router.post("/bills/{key}/toggle")
def toggle_bill(key: str, user: m.Organization = SCOPE, db: Session = DB):
    lock_organization(db, user.id)
    row = owned(db, m.Bill, key, user.id)
    if row.kind != "expense":
        fail("Платёж не найден", 404)
    row.active = not row.active
    if not row.active:
        paid = select(m.Transaction.occurrence_id).where(
            m.Transaction.occurrence_id.is_not(None), ~m.Transaction.voided
        )
        for occurrence in db.scalars(
            select(m.Occurrence).where(m.Occurrence.bill_id == row.id, m.Occurrence.id.not_in(paid))
        ):
            occurrence.skipped = True
    db.commit()
    return {"active": row.active}


@router.post("/occurrences/{key}/pay")
def pay_bill(key: str, data: s.BillPayment, user: m.Organization = SCOPE, db: Session = DB):
    tx = ledger.pay_bill(db, user.id, key, data)
    db.commit()
    return transaction_dict(tx)


@router.post("/occurrences/{key}/skip")
def skip_bill(key: str, user: m.Organization = SCOPE, db: Session = DB):
    lock_organization(db, user.id)
    row = owned(db, m.Occurrence, key, user.id)
    if owned(db, m.Bill, row.bill_id, user.id).kind != "expense":
        fail("Платёж не найден", 404)
    if db.scalar(
        select(m.Transaction.id).where(m.Transaction.occurrence_id == key, ~m.Transaction.voided)
    ):
        fail("Этот платёж уже оплачен")
    row.skipped = not row.skipped
    db.commit()
    return {"skipped": row.skipped}


@router.put("/budgets")
def save_budget(data: s.BudgetInput, user: m.Organization = SCOPE, db: Session = DB):
    month_range(data.month)
    lock_organization(db, user.id)
    owned(db, m.Category, data.category_id, user.id)
    row = db.scalar(
        select(m.Budget).where(
            m.Budget.organization_id == user.id,
            m.Budget.month == data.month,
            m.Budget.category_id == data.category_id,
        )
    )
    if not row:
        row = m.Budget(organization_id=user.id, month=data.month, category_id=data.category_id)
        db.add(row)
    row.amount_minor = minor(data.amount)
    db.commit()
    return {"id": row.id}


@router.delete("/budgets/{key}")
def delete_budget(key: str, user: m.Organization = SCOPE, db: Session = DB):
    db.delete(owned(db, m.Budget, key, user.id))
    db.commit()
    return {"ok": True}


@router.get("/receipts")
def receipts(
    search: str = Query("", max_length=100),
    offset: int = Query(0, ge=0),
    user: m.Organization = SCOPE,
    db: Session = DB,
):
    query = select(m.Receipt).where(
        m.Receipt.organization_id == user.id, m.Receipt.deleted_at.is_(None)
    )
    if search:
        query = query.where(m.Receipt.merchant.icontains(search, autoescape=True))
    rows = list(db.scalars(query.order_by(m.Receipt.created_at.desc()).offset(offset).limit(30)))
    ids = [r.id for r in rows]
    items = {}
    for item in db.scalars(
        select(m.ReceiptItem)
        .where(m.ReceiptItem.receipt_id.in_(ids))
        .order_by(m.ReceiptItem.created_at)
    ):
        items.setdefault(item.receipt_id, []).append(item)
    transactions = {
        tx.receipt_id: tx
        for tx in db.scalars(
            select(m.Transaction).where(m.Transaction.receipt_id.in_(ids), ~m.Transaction.voided)
        )
    }
    return {
        "items": [receipt_dict(db, r, (items, transactions)) for r in rows],
        "total": db.scalar(select(func.count()).select_from(query.subquery())),
    }


def register_receipt(
    db,
    organization_id: str,
    source: str,
    key: str,
    url: str | None,
    account_id: str | None,
    fx_rate: Decimal,
    images: list[bytes],
    review_required: bool = False,
    page_text: str | None = None,
):
    lock_organization(db, organization_id)
    if account_id:
        account = owned(db, m.Account, account_id, organization_id)
        if account.archived:
            fail("Выберите действующий счёт")
    prior = db.scalar(
        select(m.Receipt)
        .where(m.Receipt.organization_id == organization_id, m.Receipt.source_key == key)
        .with_for_update()
    )
    if prior:
        if prior.deleted_at:
            fail("Этот чек ранее удалён администратором", 409)
        retry_page = (
            source == "phone_page"
            and prior.status == "review"
            and prior.error
            and (
                prior.created_by == db.info.get("actor_id")
                or db.info.get("membership_role") == "admin"
            )
        )
        if not retry_page:
            if images and (
                prior.created_by == db.info.get("actor_id")
                or db.info.get("membership_role") == "admin"
            ):
                if append_images(prior, images):
                    audit(db, organization_id, "receipt.originals_added", prior.id)
                db.commit()
            return {"receipt": receipt_dict(db, prior), "duplicate": True}
    receipt = prior or m.Receipt(
        organization_id=organization_id,
        source=source,
        source_key=key,
        source_url=url,
        account_id=account_id,
        fx_rate=fx_rate,
        review_required=review_required,
        created_by=db.info.get("actor_id"),
    )
    db.add(receipt)
    if prior:
        receipt.source, receipt.source_url = source, url
        receipt.status, receipt.error = "queued", None
        receipt.review_required = True
        receipt.account_id = account_id or receipt.account_id
        receipt.fx_rate = fx_rate
        receipt.version += 1
    if page_text is not None:
        receipt.original = {"phone_page_text": page_text}
    db.flush()
    append_images(receipt, images)
    db.add(
        m.Message(
            organization_id=organization_id,
            role="user",
            text=t("Фото чека") if receipt.file_names else t("Чек по QR-ссылке"),
            receipt_id=receipt.id,
        )
    )
    job = enqueue(db, organization_id, "receipt", {"receipt_id": receipt.id})
    db.commit()
    return {"receipt": receipt_dict(db, receipt), "job_id": job.id, "duplicate": False}


@router.post("/receipts/upload")
async def upload_receipt(
    files: list[UploadFile] = File(),
    account_id: str = Form(""),
    fx_rate: str = Form("1"),
    review_required: bool = Form(False),
    resolve_qr: bool = Form(True),
    page_url: str = Form("", max_length=1000),
    page_text: str = Form("", max_length=50000),
    user: m.Organization = SCOPE,
    db: Session = DB,
):
    if not 1 <= len(files) <= 4:
        fail("Загрузите от 1 до 4 фотографий одного чека")
    if (
        account_id
        and owned(db, m.Account, account_id, user.id).currency != "MDL"
        and fx_rate == "1"
    ):
        fail("Для счёта в иностранной валюте укажите курс к MDL")
    try:
        rate = Decimal(fx_rate)
        if not rate.is_finite() or rate <= 0 or rate > 100000:
            raise ValueError
    except (ArithmeticError, ValueError):
        fail("Некорректный курс")
    images, url = [], None
    if page_text and not page_url:
        fail("Для текста страницы нужна ссылка на чек")
    if page_url:
        try:
            try:
                url = mev_url(page_url)
            except ReceiptError:
                url = receipt_link(page_url)
        except WebReceiptError as exc:
            fail(str(exc))
    for upload in files:
        raw = await upload.read(settings().max_upload_mb * 1024 * 1024 + 1)
        import asyncio

        content, qr = await asyncio.to_thread(image_bytes, raw)
        images.append(content)
        if qr and resolve_qr and not page_url:
            try:
                candidate = mev_url(qr)
                if url and candidate != url:
                    fail("На фотографиях разные QR-коды чеков. Загружайте каждый чек отдельно")
                url = candidate
            except ReceiptError:
                pass  # unrelated QR codes on receipts are not network targets
    key = hashlib.sha256(url.encode() if url else b"".join(images)).hexdigest()
    return register_receipt(
        db,
        user.id,
        "phone_page" if page_url else "photo",
        key,
        url,
        account_id or None,
        rate,
        images,
        review_required or bool(page_url),
        page_text if page_url else None,
    )


@router.post("/receipts/{key}/originals")
async def add_receipt_originals(
    key: str,
    files: list[UploadFile] = File(),
    user: m.Organization = SCOPE,
    db: Session = DB,
):
    import asyncio

    if not 1 <= len(files) <= 4:
        fail("Выберите от 1 до 4 фотографий или снимков страницы")
    receipt = owned(db, m.Receipt, key, user.id, lock=True)
    if db.info.get("membership_role") != "admin" and receipt.created_by != db.info.get("actor_id"):
        fail("Дополнять оригиналы чужого чека может администратор", 403)
    images = []
    for upload in files:
        raw = await upload.read(settings().max_upload_mb * 1024 * 1024 + 1)
        content, _ = await asyncio.to_thread(image_bytes, raw)
        images.append(content)
    added = append_images(receipt, images)
    if added:
        audit(db, user.id, "receipt.originals_added", receipt.id, {"count": added})
    db.commit()
    return receipt_dict(db, receipt)


@router.post("/receipts/link")
def link_receipt(data: s.ReceiptLink, user: m.Organization = SCOPE, db: Session = DB):
    try:
        url = mev_url(data.url)
        source = "mev"
    except ReceiptError:
        try:
            url = receipt_link(data.url)
        except WebReceiptError as exc:
            fail(str(exc))
        source = "web"
    return register_receipt(
        db,
        user.id,
        source,
        hashlib.sha256(url.encode()).hexdigest(),
        url,
        data.account_id,
        data.fx_rate or Decimal(1),
        [],
        data.review_required or source == "web",
    )


@router.post("/receipts/{key}/accept")
def accept_receipt(
    key: str,
    data: s.ReceiptAccept,
    request: Request,
    user: m.Organization = SCOPE,
    db: Session = DB,
):
    """Confirm the displayed extraction; members cannot alter prices or another author's draft."""
    row = owned(db, m.Receipt, key, user.id, True)
    if request.state.membership.role != "admin" and row.created_by != db.info.get("actor_id"):
        fail("Подтвердить этот чек может его автор или администратор", 403)
    if row.status not in {"review", "posted"}:
        fail("Дождитесь распознавания чека", 409)
    details = receipt_dict(db, row)
    if not row.purchased_on or not row.merchant or not row.total_minor or not details["items"]:
        fail("Не все данные распознаны. Исправьте данные и позиции перед подтверждением")
    payload = s.ReceiptConfirm(
        merchant=row.merchant,
        purchased_on=row.purchased_on,
        currency=row.currency,
        total=Decimal(row.total_minor) / 100,
        account_id=data.account_id,
        fx_rate=row.fx_rate,
        version=data.version,
        items=[
            dict(
                name=line["name"],
                quantity=line["quantity"],
                unit=line["unit"],
                unit_price=Decimal(line["unit_price_minor"]) / 100,
                total=Decimal(line["total_minor"]) / 100,
                category_id=line["category_id"],
            )
            for line in details["items"]
        ],
    )
    result = confirm_receipt(db, user.id, row, payload)
    db.commit()
    return result


@router.post("/receipts/{key}/review")
def review_receipt(
    key: str,
    data: s.ReceiptReview,
    request: Request,
    user: m.Organization = SCOPE,
    db: Session = DB,
):
    row = owned(db, m.Receipt, key, user.id, True)
    if request.state.membership.role != "admin" and row.created_by != db.info.get("actor_id"):
        fail("Исправить и подтвердить этот чек может его автор или администратор", 403)
    if row.status not in {"review", "posted"}:
        fail("Дождитесь распознавания чека", 409)
    result = confirm_receipt(db, user.id, row, data)
    db.commit()
    return result


@router.get("/receipts/{key}")
def get_receipt(key: str, user: m.Organization = SCOPE, db: Session = DB):
    return receipt_dict(db, owned(db, m.Receipt, key, user.id))


@router.get("/receipts/{key}/image/{index}")
def receipt_image(key: str, index: int, user: m.Organization = SCOPE, db: Session = DB):
    receipt = owned(db, m.Receipt, key, user.id)
    if index < 0 or index >= len(receipt.file_names):
        fail("Изображение не найдено", 404)
    path = settings().data_dir / "receipts" / user.id / receipt.file_names[index]
    if not path.is_file():
        fail("Файл чека не найден. Проверьте восстановление резервной копии", 404)
    return FileResponse(
        path, media_type="image/jpeg", headers={"Cache-Control": "private, no-store"}
    )


@router.post("/receipts/{key}/confirm")
def post_receipt(key: str, data: s.ReceiptConfirm, user: m.Organization = SCOPE, db: Session = DB):
    result = confirm_receipt(db, user.id, owned(db, m.Receipt, key, user.id), data)
    db.commit()
    return result


@router.post("/receipts/{key}/retry")
def retry_receipt(key: str, user: m.Organization = SCOPE, db: Session = DB):
    lock_organization(db, user.id)
    row = owned(db, m.Receipt, key, user.id)
    if row.status in {"posted", "queued", "processing"}:
        fail("Чек уже добавлен или обрабатывается", 409)
    row.status, row.error = "queued", None
    row.version += 1
    job = enqueue(db, user.id, "receipt", {"receipt_id": key})
    db.commit()
    return {"job_id": job.id}


@router.get("/chat")
def chat(before: str | None = None, user: m.Organization = SCOPE, db: Session = DB):
    query = select(m.Message).where(m.Message.organization_id == user.id)
    if before:
        original = owned(db, m.Message, before, user.id)
        query = query.where(
            or_(
                m.Message.created_at < original.created_at,
                (m.Message.created_at == original.created_at) & (m.Message.id < original.id),
            )
        )
    rows = list(
        db.scalars(query.order_by(m.Message.created_at.desc(), m.Message.id.desc()).limit(60))
    )
    return [
        {
            "id": r.id,
            "role": r.role,
            "text": r.text,
            "receipt_id": r.receipt_id,
            "created_at": r.created_at,
            "details": r.details,
            "actor_id": r.details.get("actor_id"),
        }
        for r in reversed(rows)
    ]


@router.post("/chat")
def send_message(data: s.ChatInput, user: m.Organization = SCOPE, db: Session = DB):
    month_range(data.month)
    lock_organization(db, user.id)
    if data.request_key:
        prior = db.scalar(
            select(m.Job).where(
                m.Job.organization_id == user.id,
                m.Job.kind == "chat",
                m.Job.payload["request_key"].as_string() == data.request_key,
            )
        )
        if prior:
            if any(prior.payload.get(key) != value for key, value in data.model_dump().items()):
                fail("Повторный запрос содержит другое сообщение", 409)
            return {"job_id": prior.id}
    prefs = db.scalar(select(m.Preferences).where(m.Preferences.organization_id == user.id))
    if prefs.provider == "disabled" and not data.report:
        fail(
            "Включите локальный AI или OpenAI в настройках. Базовая аналитика уже доступна в разделе «Анализ»"
        )
    message = m.Message(
        organization_id=user.id,
        role="user",
        text=data.text,
        details={"actor_id": db.info.get("actor_id")},
    )
    db.add(message)
    db.flush()
    job = enqueue(
        db,
        user.id,
        "chat",
        {**data.model_dump(), "message_id": message.id, "actor_id": db.info.get("actor_id")},
    )
    db.commit()
    return {"job_id": job.id}


@router.get("/insights")
def insights(month: str, user: m.Organization = SCOPE, db: Session = DB):
    result = recommendations(db, user.id, month)
    db.commit()
    return result["cards"]


@router.get("/jobs")
def jobs(user: m.Organization = SCOPE, db: Session = DB):
    return [
        {
            "id": r.id,
            "kind": r.kind,
            "status": r.status,
            "progress": translated_notice(r.progress),
            "payload": r.payload if r.kind == "model_pull" else {},
            "result": r.result,
            "created_at": r.created_at,
        }
        for r in db.scalars(
            select(m.Job)
            .where(m.Job.organization_id == user.id)
            .order_by(m.Job.created_at.desc())
            .limit(30)
        )
    ]


@router.get("/settings")
def get_settings(user: m.Organization = SCOPE, db: Session = DB):
    p = db.scalar(select(m.Preferences).where(m.Preferences.organization_id == user.id))
    start = m.now().replace(day=1, hour=0, minute=0, second=0, microsecond=0)
    usage = db.execute(
        select(
            func.count(m.AIUsage.id),
            func.coalesce(func.sum(m.AIUsage.input_tokens), 0),
            func.coalesce(func.sum(m.AIUsage.output_tokens), 0),
        ).where(m.AIUsage.organization_id == user.id, m.AIUsage.created_at >= start)
    ).one()
    return {
        "provider": p.provider,
        "model": p.model,
        "vision_model": p.vision_model,
        "has_openai_key": bool(p.openai_key),
        "monthly_request_limit": p.monthly_request_limit,
        "auto_post": p.auto_post,
        "default_account_id": p.default_account_id,
        "usage": {"requests": usage[0], "input_tokens": usage[1], "output_tokens": usage[2]},
        "secure_cookies": settings().cookie_secure,
    }


@router.put("/settings")
def save_settings(data: s.PreferencesInput, user: m.Organization = SCOPE, db: Session = DB):
    lock_organization(db, user.id)
    if data.default_account_id:
        account = owned(db, m.Account, data.default_account_id, user.id)
        if account.archived:
            fail("Выберите действующий счёт")
    row = db.scalar(select(m.Preferences).where(m.Preferences.organization_id == user.id))
    for key in [
        "provider",
        "model",
        "vision_model",
        "monthly_request_limit",
        "auto_post",
        "default_account_id",
    ]:
        setattr(row, key, getattr(data, key))
    if data.clear_key:
        row.openai_key = None
    if data.openai_key:
        row.openai_key = encrypt(data.openai_key)
    if row.provider == "openai" and not row.openai_key:
        fail("Для OpenAI требуется API-ключ")
    audit(db, user.id, "settings.updated", row.id, {"provider": row.provider})
    db.commit()
    return {"ok": True}


@router.get("/ai/models")
async def models(user: m.Organization = SCOPE):
    return await ai.ollama_models()


@router.post("/ai/models/pull")
def pull_model(data: s.ModelPull, user: m.Organization = SCOPE, db: Session = DB):
    lock_organization(db, user.id)
    for prior in db.scalars(
        select(m.Job).where(
            m.Job.organization_id == user.id,
            m.Job.kind == "model_pull",
            m.Job.status.in_(["queued", "running"]),
        )
    ):
        if prior.payload.get("model") == data.model:
            return {"job_id": prior.id}
    job = enqueue(db, user.id, "model_pull", data.model_dump())
    db.commit()
    return {"job_id": job.id}


@router.post("/ai/test")
def ai_test(user: m.Organization = SCOPE, db: Session = DB):
    job = enqueue(db, user.id, "ai_test", {})
    db.commit()
    return {"job_id": job.id}


@router.get("/rules")
def rules(user: m.Organization = SCOPE, db: Session = DB):
    return [
        {"id": r.id, "pattern": r.pattern, "field": r.field, "category_id": r.category_id}
        for r in db.scalars(
            select(m.Rule)
            .where(m.Rule.organization_id == user.id)
            .order_by(m.Rule.created_at.desc())
        )
    ]


@router.post("/rules")
def add_rule(data: s.RuleInput, user: m.Organization = SCOPE, db: Session = DB):
    owned(db, m.Category, data.category_id, user.id)
    row = m.Rule(organization_id=user.id, **data.model_dump())
    db.add(row)
    db.commit()
    return {"id": row.id}


@router.delete("/rules/{key}")
def delete_rule(key: str, user: m.Organization = SCOPE, db: Session = DB):
    db.delete(owned(db, m.Rule, key, user.id))
    db.commit()
    return {"ok": True}


@router.get("/audit")
def audit_log(user: m.Organization = SCOPE, db: Session = DB):
    return [
        {
            "id": r.id,
            "action": r.action,
            "created_at": r.created_at,
            "entity_id": r.entity_id,
            "details": r.details,
        }
        for r in db.scalars(
            select(m.Audit)
            .where(m.Audit.organization_id == user.id)
            .order_by(m.Audit.created_at.desc())
            .limit(100)
        )
    ]


@router.get("/export.csv")
def export_csv(user: m.Organization = SCOPE):
    organization_id = user.id

    def generate():
        buffer = io.StringIO()
        writer = csv.writer(buffer)
        yield "\ufeff"
        writer.writerow(
            [
                t(label)
                for label in [
                    "Дата",
                    "Тип",
                    "Сумма",
                    "Валюта",
                    "Сумма MDL",
                    "Магазин",
                    "Примечание",
                ]
            ]
        )
        yield buffer.getvalue()
        buffer.seek(0)
        buffer.truncate(0)
        with SessionLocal() as db:
            for tx in db.scalars(
                select(m.Transaction)
                .where(m.Transaction.organization_id == organization_id, ~m.Transaction.voided)
                .order_by(m.Transaction.occurred_on)
                .execution_options(yield_per=500)
            ):

                def safe(value):
                    return (
                        "'" + value if value.startswith(("=", "+", "-", "@", "\t", "\r")) else value
                    )

                writer.writerow(
                    [
                        tx.occurred_on,
                        tx.kind,
                        format(Decimal(tx.amount_minor) / 100, ".2f"),
                        tx.currency,
                        format(Decimal(tx.base_minor) / 100, ".2f"),
                        safe(tx.merchant),
                        safe(tx.note),
                    ]
                )
                yield buffer.getvalue()
                buffer.seek(0)
                buffer.truncate(0)

    return StreamingResponse(
        generate(),
        media_type="text/csv; charset=utf-8",
        headers={"Content-Disposition": 'attachment; filename="finora-transactions.csv"'},
    )


@router.get("/export.json")
def export_json(user: m.Organization = SCOPE):
    organization_id = user.id

    def generate():
        yield (
            '{"format":"finora-export-v2","organization_id":'
            + json.dumps(organization_id)
            + ',"tables":{'
        )
        with SessionLocal() as db:
            for index, model in enumerate(
                [
                    m.Account,
                    m.Category,
                    m.Transaction,
                    m.Debt,
                    m.Bill,
                    m.Occurrence,
                    m.Budget,
                    m.Rule,
                    m.Receipt,
                    m.ReceiptItem,
                    m.ReceiptComment,
                    m.Allocation,
                    m.Posting,
                    m.Message,
                ]
            ):
                yield ("," if index else "") + json.dumps(model.__tablename__) + ":["
                query = select(model)
                if hasattr(model, "organization_id"):
                    query = query.where(model.organization_id == organization_id)
                elif model == m.ReceiptItem:
                    query = query.join(m.Receipt).where(
                        m.Receipt.organization_id == organization_id
                    )
                else:
                    query = query.join(m.Transaction).where(
                        m.Transaction.organization_id == organization_id
                    )
                for n, row in enumerate(db.scalars(query.execution_options(yield_per=500))):
                    value = {
                        c.name: getattr(row, c.name)
                        for c in model.__table__.columns
                        if c.name not in {"request_hash"}
                    }
                    yield ("," if n else "") + json.dumps(value, default=str, ensure_ascii=False)
                yield "]"
        yield "}}"

    return StreamingResponse(
        generate(),
        media_type="application/json",
        headers={"Content-Disposition": 'attachment; filename="finora-export.json"'},
    )
