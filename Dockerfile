FROM python:3.12-slim

WORKDIR /app

COPY pc/requirements.txt /app/pc/requirements.txt
RUN pip install --no-cache-dir -r /app/pc/requirements.txt

COPY VERSION /app/VERSION
COPY version.json /app/version.json
COPY pc/ /app/pc/

ENV PYTHONUNBUFFERED=1 \
    PODSTASH_HOST=0.0.0.0 \
    PODSTASH_PORT=8765 \
    PODSTASH_OUT_DIR=/podcasts \
    PODSTASH_CONFIG=/config \
    PODSTASH_NO_BROWSER=1 \
    PODSTASH_CONCURRENCY=4 \
    PUID=1000 \
    PGID=1000

RUN groupadd -g 1000 podstash \
    && useradd -u 1000 -g 1000 -d /config podstash \
    && mkdir -p /podcasts /config

WORKDIR /app/pc
EXPOSE 8765

# /api/health is exempt from auth so the probe works with a password set.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD python -c "import urllib.request,sys; urllib.request.urlopen('http://127.0.0.1:8765/api/health', timeout=4); sys.exit(0)" || exit 1

CMD ["python", "-u", "docker-entrypoint.py"]
