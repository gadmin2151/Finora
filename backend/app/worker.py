import asyncio
import json
import logging
import signal
from datetime import timedelta

import httpx
from sqlalchemy import select

from . import ai
from . import models as m
from .assistant import answer
from .config import settings
from .db import SessionLocal
from .i18n import current_language, language_context, t
from .job_lease import Lease, LeaseLost, current_lease, renew, require_lease
from .receipts import ReceiptError, process_receipt

logger = logging.getLogger("finora.worker")
stopping = False


def progress(job_id: str, text: str):
    with SessionLocal() as db:
        require_lease(db)
        job = db.get(m.Job, job_id)
        job.progress = text[:200]
        job.started_at = m.now()
        db.commit()


async def pull_model(job: m.Job):
    async with httpx.AsyncClient(timeout=httpx.Timeout(90, read=180)) as client:
        async with client.stream(
            "POST",
            settings().ollama_url.rstrip("/") + "/api/pull",
            json={"model": job.payload["model"], "stream": True},
        ) as response:
            response.raise_for_status()
            last = 0.0
            completed = False
            async for line in response.aiter_lines():
                if stopping:
                    raise ai.AIError(
                        "Загрузка прервана остановкой сервера. Её можно продолжить в настройках."
                    )
                if not line:
                    continue
                result = json.loads(line)
                if result.get("status") == "success":
                    completed = True
                if result.get("error"):
                    raise ai.AIError(
                        "Не удалось загрузить модель. Проверьте место на диске и соединение с Ollama."
                    )
                now = asyncio.get_running_loop().time()
                if now - last > 1 or result.get("status") == "success":
                    total = result.get("total", 0)
                    pct = f" · {int(100 * result.get('completed', 0) / total)}%" if total else ""
                    progress(job.id, result.get("status", "Загрузка") + pct)
                    last = now
            if not completed:
                raise ai.AIError(
                    "Загрузка модели не завершилась. Нажмите «Загрузить» ещё раз, чтобы продолжить."
                )


async def execute_job(job: m.Job):
    with language_context(job.payload.get("language")):
        await _execute_job(job)


async def _execute_job(job: m.Job):
    try:
        if job.kind == "receipt":
            await process_receipt(job.payload["receipt_id"])
        elif job.kind == "model_pull":
            await pull_model(job)
        elif job.kind in {"chat", "analysis"}:
            await answer(job)
        elif job.kind == "ai_test":
            text, provider = await ai.generate(
                job.organization_id,
                "test",
                "Reply with one word: ready."
                if current_language() == "en"
                else "Ответь одним словом: готово.",
                "Connection check" if current_language() == "en" else "Проверка подключения",
            )
            with SessionLocal() as db:
                require_lease(db)
                db.get(m.Job, job.id).result = {"provider": provider, "answer": text[:200]}
                db.commit()
        else:
            raise ValueError("Unknown job type")
        with SessionLocal() as db:
            require_lease(db)
            row = db.get(m.Job, job.id)
            row.status, row.progress = "done", "Готово"
            db.commit()
    except LeaseLost:
        logger.info("job_lease_lost id=%s", job.id)
    except Exception as exc:
        safe = (
            str(exc)
            if isinstance(exc, (ai.AIError, ReceiptError))
            else "Не удалось обработать запрос. Проверьте подключение и повторите."
        )
        logger.warning(
            "job_failed id=%s kind=%s error_type=%s", job.id, job.kind, type(exc).__name__
        )
        with SessionLocal() as db:
            require_lease(db)
            row = db.get(m.Job, job.id)
            safe = t(safe)
            row.status, row.progress = "failed", safe[:200]
            if job.kind == "receipt":
                receipt = db.get(m.Receipt, job.payload["receipt_id"])
                if receipt and not receipt.deleted_at and receipt.status != "posted":
                    receipt.status, receipt.error = "review", safe
                    receipt.version += 1
                    db.add(
                        m.Message(
                            organization_id=job.organization_id,
                            role="assistant",
                            text=safe,
                            receipt_id=receipt.id,
                        )
                    )
            elif job.kind in {"chat", "analysis"}:
                db.add(
                    m.Message(
                        organization_id=job.organization_id,
                        role="assistant",
                        text=safe,
                        details={"error": True, "job_id": job.id},
                    )
                )
            db.commit()


async def run_job(job: m.Job, heartbeat_seconds: float = 20):
    lease = Lease(job.id, job.attempts)
    token = current_lease.set(lease)

    async def heartbeat():
        while True:
            await asyncio.sleep(heartbeat_seconds)
            if not await asyncio.to_thread(renew, lease):
                raise LeaseLost()

    work = asyncio.create_task(execute_job(job))
    pulse = asyncio.create_task(heartbeat())
    try:
        done, _ = await asyncio.wait({work, pulse}, return_when=asyncio.FIRST_COMPLETED)
        for task in done:
            await task
    except LeaseLost:
        logger.info("job_lease_lost id=%s", job.id)
    finally:
        work.cancel()
        pulse.cancel()
        await asyncio.gather(work, pulse, return_exceptions=True)
        current_lease.reset(token)


async def main():
    global stopping
    logging.basicConfig(level=logging.INFO)
    loop = asyncio.get_running_loop()

    def stop():
        global stopping
        stopping = True

    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, stop)
    while not stopping:
        with SessionLocal() as db:
            # A crashed process cannot strand a task forever; at most 2 automatic attempts.
            stale = db.scalars(
                select(m.Job)
                .where(
                    m.Job.status == "running", m.Job.started_at < m.now() - timedelta(minutes=15)
                )
                .with_for_update(skip_locked=True)
            )
            for row in stale:
                row.status = "queued" if row.attempts < 2 else "failed"
                row.progress = (
                    "Возобновление после перезапуска"
                    if row.status == "queued"
                    else "Задача прервана. Повторите её вручную."
                )
                if row.status == "failed" and row.kind == "receipt":
                    receipt = db.get(m.Receipt, row.payload["receipt_id"])
                    if receipt and not receipt.deleted_at and receipt.status != "posted":
                        receipt.status, receipt.error = "review", row.progress
                        receipt.version += 1
            job = db.scalar(
                select(m.Job)
                .where(m.Job.status == "queued", m.Job.available_at <= m.now())
                .order_by(m.Job.created_at)
                .with_for_update(skip_locked=True)
                .limit(1)
            )
            if job:
                job.status, job.started_at, job.attempts = "running", m.now(), job.attempts + 1
                job.progress = "Загрузка модели…" if job.kind == "model_pull" else "Обрабатываю…"
            db.commit()
        if job:
            await run_job(job)
        else:
            await asyncio.sleep(1)


if __name__ == "__main__":
    asyncio.run(main())
