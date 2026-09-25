FROM node:24-bookworm-slim AS web
WORKDIR /build
RUN corepack enable && corepack prepare pnpm@11.25.0 --activate
COPY web/package.json web/pnpm-lock.yaml web/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY web/ ./
RUN pnpm build

FROM python:3.12-slim-bookworm AS runtime
LABEL org.opencontainers.image.title="Finora" \
      org.opencontainers.image.description="Self-hosted finance, receipts and budgeting" \
      org.opencontainers.image.source="https://github.com/gadmin2151/Finora" \
      org.opencontainers.image.licenses="MIT"
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PLAYWRIGHT_BROWSERS_PATH=/opt/browsers
WORKDIR /app
COPY backend/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt && playwright install --with-deps chromium && \
    apt-get install -y --no-install-recommends age tesseract-ocr tesseract-ocr-ron tesseract-ocr-rus tesseract-ocr-eng && rm -rf /var/lib/apt/lists/* && \
    useradd --uid 10001 --create-home finora && mkdir -p /data/receipts && chown -R finora:finora /data /opt/browsers
COPY backend/ ./
COPY --from=web /build/dist ./static
USER finora
EXPOSE 8088
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8088", "--no-access-log", "--limit-concurrency", "100", "--timeout-keep-alive", "10"]
