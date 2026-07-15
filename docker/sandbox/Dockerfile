FROM python:3.11-slim
RUN useradd -m -u 10001 sandbox \
 && mkdir -p /skill /workspace \
 && chown -R sandbox:sandbox /workspace
WORKDIR /workspace
COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt \
 && rm /tmp/requirements.txt
USER sandbox
CMD ["sleep", "infinity"]
