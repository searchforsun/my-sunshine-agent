FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    git build-essential curl vim nodejs npm \
    && rm -rf /var/lib/apt/lists/*

RUN useradd -m -u 10001 sandbox && \
    mkdir -p /workspace /skills && \
    chown -R sandbox:sandbox /workspace /skills

USER sandbox
WORKDIR /workspace

CMD ["sleep", "infinity"]
