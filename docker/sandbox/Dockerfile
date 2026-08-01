FROM python:3.11-slim
RUN useradd -m -u 10001 sandbox \
 && mkdir -p /skill /workspace \
 && chown -R sandbox:sandbox /workspace
# 全局豁免 dubious ownership：宿主 root 创建的 repo/裸库挂载进容器后，git 需要 safe.directory
RUN printf '[safe]\n\tdirectory = *\n' > /etc/gitconfig
WORKDIR /workspace
COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt \
 && rm /tmp/requirements.txt
USER sandbox
CMD ["sleep", "infinity"]
