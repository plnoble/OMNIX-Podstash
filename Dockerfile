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
    PODSTASH_CONCURRENCY=4

RUN mkdir -p /podcasts /config

WORKDIR /app/pc
EXPOSE 8765

CMD ["python", "-u", "app.py"]
