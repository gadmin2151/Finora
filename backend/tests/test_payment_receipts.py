"""Bill documents must expose existing expenses without reposting or rewriting their money."""

from concurrent.futures import ThreadPoolExecutor, TimeoutError
from datetime import UTC, datetime
from threading import Event
from uuid import uuid4

import pytest
from sqlalchemy import func, select
from test_assistant_reports import fetch_report
from test_ledger import post_tx
from test_manual_receipts import entry

from app import ledger, payment_receipts
from app import models as m
from app.db import Base, SessionLocal, engine
from app.push_models import PushEvent
from app.schemas import BillPayment


def schedule(client, account, category=None, amount="25", currency="MDL"):
    result = client.post(
        "/api/bills",
        json={
            "name": "Synthetic mandatory rent",
            "amount": amount,
            "currency": currency,
            "fx_rate": "1.5" if currency != "MDL" else "1",
            "start_date": "2025-02-28",
            "recurrence": "once",
            "account_id": account["id"],
            "category_id": category,
        },
    )
    assert result.status_code == 200, result.text
    return next(
        row
        for row in client.get("/api/bills?month=2025-02").json()
        if row["bill_id"] == result.json()["id"]
    )


def payment(account, amount="25", **extra):
    return {
        "account_id": account["id"],
        "amount": amount,
        "occurred_on": "2025-02-28",
        "idempotency_key": uuid4().hex,
        **extra,
    }


def pay(client, occurrence, body):
    response = client.post(f"/api/occurrences/{occurrence['id']}/pay", json=body)
    assert response.status_code == 200, response.text
    return response.json()


def legacy_payment(client, occurrence, body, monkeypatch):
    # Reproduce pre-release payments through the original ledger path, omitting documents only.
    with monkeypatch.context() as patch:
        patch.setattr(payment_receipts, "attach_receipt", lambda *args, **kwargs: None)
        return pay(client, occurrence, body)


def snapshot(*, financial=False):
    tables = (
        [
            model.__table__
            for model in (m.Transaction, m.Posting, m.Allocation, m.Account, m.Bill, m.Occurrence)
        ]
        if financial
        else Base.metadata.sorted_tables
    )
    with SessionLocal() as db:
        result = {}
        for table in tables:
            columns = [
                col
                for col in table.c
                if not (
                    financial
                    and table.name == "transactions"
                    and col.name in {"receipt_id", "version"}
                )
            ]
            result[table.name] = sorted(
                [dict(row) for row in db.execute(select(*columns)).mappings()], key=str
            )
        return result


def test_paid_bill_is_one_manual_receipt_in_category_history_and_reports(
    client, accounts, categories, owner
):
    occurrence = schedule(client, accounts[0], categories[0]["id"])
    body = payment(accounts[0])
    tx = pay(client, occurrence, body)
    assert tx["is_mandatory"] is True
    receipt = client.get(f"/api/receipts/{tx['receipt_id']}").json()
    marker = {
        "bill_id": occurrence["bill_id"],
        "occurrence_id": occurrence["id"],
        "due_date": "2025-02-28",
    }
    assert receipt["mandatory_payment"] == marker
    assert (receipt["source"], receipt["document_type"], receipt["status"]) == (
        "manual",
        "manual_receipt",
        "posted",
    )
    assert receipt["created_by"] == owner["id"] and receipt["files"] == []
    assert receipt["total_minor"] == receipt["items"][0]["total_minor"] == 2500
    assert receipt["items"][0]["category_id"] == categories[0]["id"]
    purchases = client.get("/api/purchases", params={"category_id": categories[0]["id"]}).json()
    assert purchases["total"] == 1 and purchases["items"][0]["mandatory_payment"] == marker
    assert purchases["totals"] == [{"currency": "MDL", "total_minor": 2500}]
    for kind in ("transactions", "receipts", "purchases"):
        report = fetch_report(client, kind)
        assert report["rows"][0]["is_mandatory"] is True
    assert client.get("/api/receipts").json()["items"][0]["mandatory_payment"] == marker
    dashboard = client.get("/api/dashboard?month=2025-02").json()
    assert dashboard["expense_minor"] == dashboard["categories"][0]["spent_minor"] == 2500
    before = snapshot()
    for request in (body, {**body, "idempotency_key": uuid4().hex}):
        retry = pay(client, occurrence, request)
        assert (retry["id"], retry["receipt_id"], retry["version"]) == (
            tx["id"],
            tx["receipt_id"],
            tx["version"],
        )
    # Auth sessions may refresh on requests; all document and ledger rows stay untouched.
    after = snapshot()
    for table in ("transactions", "receipts", "receipt_items", "postings", "allocations", "audit"):
        assert after[table] == before[table]
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(m.Job)) == 0
        assert db.scalar(select(func.count()).select_from(PushEvent)) == 0


def test_linked_original_receipt_is_preserved_and_loses_badge_when_voided(
    client, accounts, categories
):
    body = entry(accounts[0], categories[1]["id"])
    body["purchased_on"] = "2025-02-28"
    receipt = client.post("/api/receipts/manual", json=body).json()["receipt"]
    with SessionLocal() as db:
        original = db.get(m.Receipt, receipt["id"])
        original.source = "photo"
        original.original = {"synthetic_original": {"keep": True}}
        original.file_names = ["synthetic.png"]
        tx = db.scalar(select(m.Transaction).where(m.Transaction.receipt_id == receipt["id"]))
        txid = tx.id
        db.commit()
    occurrence = schedule(client, accounts[0], categories[0]["id"], amount="12.50")
    posted = pay(client, occurrence, payment(accounts[0], "12.50", transaction_id=txid))
    linked = client.get(f"/api/receipts/{receipt['id']}").json()
    assert posted["receipt_id"] == receipt["id"] and linked["source"] == "photo"
    assert linked["items"] == receipt["items"]
    with SessionLocal() as db:
        row = db.get(m.Receipt, receipt["id"])
        assert row.original["synthetic_original"] == {"keep": True}
        assert row.file_names == ["synthetic.png"]
    assert client.get("/api/transactions").json()["total"] == 1
    assert (
        client.delete(
            f"/api/transactions/{posted['id']}", params={"version": posted["version"]}
        ).status_code
        == 200
    )
    reopened = client.get(f"/api/receipts/{receipt['id']}").json()
    assert reopened["status"] == "review" and reopened["mandatory_payment"] is None
    assert reopened["source"] == "photo"


def test_void_generated_receipt_cannot_be_reposted_and_replacement_has_new_document(
    client, accounts
):
    occurrence = schedule(client, accounts[0])
    body = payment(accounts[0])
    tx = pay(client, occurrence, body)
    assert client.delete(f"/api/transactions/{tx['id']}?version={tx['version']}").status_code == 200
    assert client.get(f"/api/receipts/{tx['receipt_id']}").status_code == 404
    assert client.post(f"/api/receipts/{tx['receipt_id']}/retry").status_code == 404
    assert (
        client.post(
            f"/api/receipts/{tx['receipt_id']}/accept",
            json={"version": 2, "account_id": accounts[0]["id"]},
        ).status_code
        == 404
    )
    assert client.get("/api/purchases").json()["total"] == 0
    replacement = pay(client, occurrence, {**body, "idempotency_key": uuid4().hex})
    assert replacement["receipt_id"] != tx["receipt_id"]
    assert client.get("/api/receipts").json()["total"] == 1
    assert client.get("/api/dashboard?month=2025-02").json()["expense_minor"] == 2500
    with SessionLocal() as db:
        old = db.get(m.Receipt, tx["receipt_id"])
        assert old.deleted_at is not None and old.status == "deleted"


def test_backfill_dry_run_apply_batches_preserve_ledger_and_original_receipt(
    client, accounts, categories, owner, monkeypatch
):
    occurrence = schedule(client, accounts[0], categories[0]["id"])
    tx = legacy_payment(client, occurrence, payment(accounts[0]), monkeypatch)
    body = entry(accounts[0], categories[1]["id"])
    body["purchased_on"] = "2025-02-28"
    existing = client.post("/api/receipts/manual", json=body).json()["receipt"]
    linked_tx = next(
        row
        for row in client.get("/api/transactions").json()["items"]
        if row["receipt_id"] == existing["id"]
    )
    second = schedule(client, accounts[0], amount="12.50")
    legacy_payment(
        client, second, payment(accounts[0], "12.50", transaction_id=linked_tx["id"]), monkeypatch
    )
    before = snapshot()
    money_before = snapshot(financial=True)
    expected = {"scanned": 2, "created": 1, "marked": 1, "unchanged": 0}
    assert payment_receipts.backfill(apply=False, batch_size=1) == {"mode": "dry-run", **expected}
    assert snapshot() == before
    assert payment_receipts.backfill(apply=True, batch_size=1) == {"mode": "apply", **expected}
    assert snapshot(financial=True) == money_before
    with SessionLocal() as db:
        stored = db.get(m.Transaction, tx["id"])
        generated = db.get(m.Receipt, stored.receipt_id)
        assert generated.created_at == stored.created_at
        assert generated.created_by == owner["id"]
        assert stored.version == tx["version"] + 1
        original = db.get(m.Receipt, existing["id"])
        before_original = next(row for row in before["receipts"] if row["id"] == original.id)
        assert original.original == {
            **before_original["original"],
            "mandatory_payment": {
                "bill_id": second["bill_id"],
                "occurrence_id": second["id"],
                "due_date": "2025-02-28",
            },
        }
        for col in m.Receipt.__table__.c:
            if col.name not in {"original", "version"}:
                assert getattr(original, col.name) == before_original[col.name]
    after = snapshot()
    assert payment_receipts.backfill(apply=True) == {
        "mode": "apply",
        "scanned": 2,
        "created": 0,
        "marked": 0,
        "unchanged": 2,
    }
    assert snapshot() == after


def test_backfill_foreign_split_rounding_and_refunds_do_not_change_money(
    client, categories, monkeypatch
):
    account = client.post("/api/accounts", json={"name": "Synthetic EUR", "currency": "EUR"}).json()
    tx = post_tx(
        client,
        account["id"],
        amount="0.03",
        fx_rate="1.5",
        occurred_on="2025-02-28",
        splits=[
            {"category_id": categories[0]["id"], "amount": "0.01"},
            {"category_id": categories[1]["id"], "amount": "0.02"},
        ],
    )
    occurrence = schedule(client, account, amount="0.03", currency="EUR")
    legacy_payment(
        client,
        occurrence,
        payment(account, "0.03", fx_rate="1.5", transaction_id=tx["id"]),
        monkeypatch,
    )
    post_tx(
        client,
        account["id"],
        kind="refund",
        amount="0.01",
        fx_rate="1.5",
        refund_of=tx["id"],
        occurred_on="2025-02-28",
    )
    before = snapshot(financial=True)
    report_before = client.get("/api/dashboard?month=2025-02").json()
    assert payment_receipts.backfill(apply=True)["created"] == 1
    assert snapshot(financial=True) == before
    report_after = client.get("/api/dashboard?month=2025-02").json()
    assert report_after["expense_minor"] == report_before["expense_minor"]
    assert report_after["categories"] == report_before["categories"]
    for category, amount in [(categories[0], 1), (categories[1], 2)]:
        items = client.get("/api/purchases", params={"category_id": category["id"]}).json()
        assert items["total"] == 1 and items["items"][0]["total_minor"] == amount
        assert items["totals"] == [{"currency": "EUR", "total_minor": amount}]


def test_backfill_is_scoped_and_rejects_bad_allocations_atomically(client, accounts, monkeypatch):
    occurrence = schedule(client, accounts[0])
    tx = legacy_payment(client, occurrence, payment(accounts[0]), monkeypatch)
    other = client.post("/api/organizations", json={"name": "Other synthetic workspace"}).json()[
        "id"
    ]
    before = snapshot()
    assert payment_receipts.backfill(apply=True, organization_id=other)["scanned"] == 0
    assert snapshot() == before
    with SessionLocal() as db:
        db.scalar(
            select(m.Allocation).where(m.Allocation.transaction_id == tx["id"])
        ).base_minor += 1
        db.commit()
    before = snapshot()
    for apply in (False, True):
        with pytest.raises(
            payment_receipts.PaymentReceiptError, match="allocation_amount_mismatch"
        ):
            payment_receipts.backfill(apply=apply)
        assert snapshot() == before
    response = client.post(f"/api/occurrences/{occurrence['id']}/pay", json=payment(accounts[0]))
    assert response.status_code == 409
    assert "allocation_amount_mismatch" not in response.text
    assert client.get("/api/receipts").json()["total"] == 0


@pytest.mark.skipif(
    engine.dialect.name != "postgresql", reason="Requires PostgreSQL organization lock"
)
def test_concurrent_bill_payment_and_backfill_wait_and_create_one_receipt(client, accounts, owner):
    occurrence = schedule(client, accounts[0])
    data = BillPayment(**payment(accounts[0]))
    org = client.headers["X-Organization-ID"]
    created, release = Event(), Event()

    def post(hold=False):
        with SessionLocal() as db:
            db.info["actor_id"] = owner["id"]
            tx = ledger.pay_bill(db, org, occurrence["id"], data)
            if hold:
                created.set()
                assert release.wait(5)
            db.commit()
            return tx.id, tx.receipt_id

    with ThreadPoolExecutor(max_workers=3) as pool:
        first = pool.submit(post, True)
        assert created.wait(5)
        second = pool.submit(post)
        backfill = pool.submit(payment_receipts.backfill, apply=True, organization_id=org)
        try:
            with pytest.raises(TimeoutError):
                second.result(timeout=0.2)
            with pytest.raises(TimeoutError):
                backfill.result(timeout=0.2)
        finally:
            release.set()
        assert first.result(timeout=5) == second.result(timeout=5)
        assert backfill.result(timeout=5)["unchanged"] == 1
    assert client.get("/api/receipts").json()["total"] == 1
    assert client.get("/api/transactions").json()["total"] == 1


def test_debt_history_exposes_recorded_utc_timestamp_and_both_comments(client, accounts):
    from test_debt_movements import create_debt, movement

    debt = create_debt(client, accounts[0]["id"], mode="existing")
    stamp = datetime(2025, 3, 2, 21, 34, 56, tzinfo=UTC)
    for endpoint, note in (("increase", "Added for repair"), ("repay", "Partial repayment")):
        response = client.post(
            f"/api/debts/{debt}/{endpoint}", json=movement(accounts[0]["id"], "10", note=note)
        )
        assert response.status_code == 200, response.text
        with SessionLocal() as db:
            db.get(m.Transaction, response.json()["id"]).created_at = stamp
            db.commit()
    rows = client.get(f"/api/debts/{debt}/history").json()["items"]
    assert {row["note"] for row in rows} == {"Added for repair", "Partial repayment"}
    assert all(row["created_at"] == "2025-03-02T21:34:56+00:00" for row in rows)
    assert all(row["occurred_on"] == "2025-02-02" for row in rows)
