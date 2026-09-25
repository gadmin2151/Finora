import asyncio
import io
from decimal import Decimal
from types import SimpleNamespace

import pytest
from PIL import Image

from app import ai, receipts


def photo(width=600, height=1500):
    output = io.BytesIO()
    Image.new("RGB", (width, height), "white").save(output, "JPEG")
    return output.getvalue()


def extraction(total="", line_total="235.08"):
    return receipts.ExtractedReceipt(
        merchant="TEST MARKET",
        purchased_on="2026-09-25",
        currency="MDL",
        total=total,
        items=[
            dict(
                name="Товары",
                quantity="1",
                unit="шт",
                unit_price=line_total,
                total=line_total,
                category="",
            )
        ],
        readable=False,
        warnings=["Проверьте чек"],
    )


def test_detail_views_keep_full_photo_and_overlap():
    original = photo()
    views = receipts.total_detail_images([original])
    assert views[0] == original
    sizes = [Image.open(io.BytesIO(view)).size for view in views]
    assert sizes == [(600, 1500), (600, 900), (600, 900)]
    assert receipts.total_detail_images([b"invalid image"]) == [b"invalid image"]


def test_receipt_column_includes_total_amount_outside_item_block(monkeypatch):
    rows = ["block_num\tleft\ttop\twidth\theight\ttext"]
    for i in range(25):
        rows.append(f"1\t180\t{50 + i * 35}\t120\t20\t{'kg' if i % 5 == 0 else 'ITEM'}")
    rows += ["2\t180\t1000\t110\t25\tTOTAL", "3\t400\t1000\t150\t25\t235.08"]
    monkeypatch.setattr(
        receipts.subprocess,
        "run",
        lambda *a, **kw: SimpleNamespace(stdout="\n".join(rows).encode()),
    )
    original = photo()
    views = receipts.vision_images([original])
    assert len(views) == 3 and views[0] == original
    # The total's right edge is 550, beyond the old crop's right edge of 314.
    assert Image.open(io.BytesIO(views[1])).width >= 370


@pytest.mark.parametrize("old_total", ["", "0", "NaN", "300.00", "bad"])
def test_one_reread_restores_printed_total_without_changing_items(monkeypatch, old_total):
    calls = []

    async def read(*args):
        calls.append(args)
        return {"total": "235.08", "label": "TOTAL LEI", "currency": "MDL", "readable": True}, None

    monkeypatch.setattr(ai, "generate", read)
    draft = extraction(old_total)
    before = draft.items[0].model_dump()
    evidence = asyncio.run(receipts.verify_printed_total("test-org", draft, [photo()]))
    assert len(calls) == 1
    assert draft.total == "235.08" and evidence["previous_total"] == old_total
    assert draft.items[0].model_dump() == before
    assert not draft.readable and draft.warnings == ["Проверьте чек"]


@pytest.mark.parametrize(
    "label", ["NUMERAR", "REST", "SUBTOTAL", "BRUT A", "TVA", "REDUCERE TOTAL", "CARD"]
)
def test_payment_tax_discount_are_never_used_as_total(monkeypatch, label):
    async def read(*args):
        return {"total": "300.00", "label": label, "currency": "MDL", "readable": True}, None

    monkeypatch.setattr(ai, "generate", read)
    draft = extraction()
    assert asyncio.run(receipts.verify_printed_total("test-org", draft, [photo()])) is None
    assert draft.total == ""


def test_correct_total_does_not_spend_another_ai_call(monkeypatch):
    async def unexpected(*args):
        pytest.fail("No verification request needed")

    monkeypatch.setattr(ai, "generate", unexpected)
    draft = extraction("235.08")
    assert not receipts.needs_total_check(draft)
    assert asyncio.run(receipts.verify_printed_total("test-org", draft, [photo()])) is None


def test_total_reread_failure_preserves_draft(monkeypatch):
    async def unavailable(*args):
        raise ai.AIError("Модель недоступна")

    monkeypatch.setattr(ai, "generate", unavailable)
    draft = extraction("200.00")
    assert asyncio.run(receipts.verify_printed_total("test-org", draft, [photo()])) is None
    assert Decimal(draft.total) == 200 and draft.items[0].total == "235.08"
    assert len(draft.warnings) == 2
