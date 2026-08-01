FROM python:3.11

# 国内镜像加速 apt（deb.debian.org 在此环境不可达）
RUN sed -i 's|http://deb.debian.org|http://mirrors.ustc.edu.cn|g' /etc/apt/sources.list.d/debian.sources

RUN apt-get update && apt-get install -y --no-install-recommends \
    git curl vim \
    && rm -rf /var/lib/apt/lists/*

# Node.js 22.x 预编译二进制（跳过 apt，秒级安装）
RUN curl -fsSL https://nodejs.org/dist/v22.14.0/node-v22.14.0-linux-x64.tar.xz \
    -o /tmp/node.tar.xz \
    && tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 \
    && rm /tmp/node.tar.xz \
    && node --version && npm --version

RUN useradd -m -u 10001 sandbox && \
    mkdir -p /workspace /skills && \
    chown -R sandbox:sandbox /workspace /skills

# 全局豁免 dubious ownership：宿主 root 创建的 repo/裸库挂载进容器后，git 需要 safe.directory
RUN printf '[safe]\n\tdirectory = *\n' > /etc/gitconfig

USER sandbox
WORKDIR /workspace

CMD ["sleep", "infinity"]
