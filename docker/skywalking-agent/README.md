# SkyWalking Java Agent

## 下载

### 方式 1：脚本（推荐）

```bash
# Linux / Git Bash
bash scripts/download-skywalking-agent.sh

# Windows PowerShell
powershell -ExecutionPolicy Bypass -File scripts/download-skywalking-agent.ps1
```

脚本会将 **9.7.0** 解压并复制 `skywalking-agent.jar` 到本目录。

### 方式 2：手动 curl（Linux / Git Bash）

```bash
curl -L -o /tmp/skywalking-agent.tgz \
  https://dlcdn.apache.org/skywalking/java-agent/9.7.0/apache-skywalking-java-agent-9.7.0.tgz
tar -xzf /tmp/skywalking-agent.tgz -C /tmp/
cp /tmp/skywalking-agent/skywalking-agent.jar ./
```

### 方式 3：PowerShell 手动下载

```powershell
$ver = "9.7.0"
$url = "https://dlcdn.apache.org/skywalking/java-agent/$ver/apache-skywalking-java-agent-$ver.tgz"
$tgz = Join-Path $env:TEMP "skywalking-agent.tgz"
Invoke-WebRequest -Uri $url -OutFile $tgz -UseBasicParsing
tar -xzf $tgz -C $env:TEMP
Copy-Item (Join-Path $env:TEMP "skywalking-agent\skywalking-agent.jar") -Destination .
```

### 方式 4：浏览器

打开 https://skywalking.apache.org/downloads/ ，选择 Java Agent 9.7.0，解压后复制 `skywalking-agent.jar` 到此处。

## 启动注入

`scripts/start.sh` / `scripts/start.ps1` 在检测到本目录存在 `skywalking-agent.jar` 时，为核心 Java 服务自动追加：

```
-javaagent:docker/skywalking-agent/skywalking-agent.jar
-DSW_AGENT_NAME=sunshine-<service>
-DSW_AGENT_COLLECTOR_BACKEND_SERVICES=ecs4c16g:11800
```

## 版本

- **Agent**: 9.7.0（匹配服务器 OAP 版本）
- **OAP Backend**: ecs4c16g:11800
- **UI**: http://ecs4c16g:8084

## Live Trace

Agent 注入后本地服务会向 OAP 上报 Trace。**完整拓扑验证**（`sunshine-gateway → sunshine-bff → sunshine-orchestrator → sunshine-llm-gateway`）需远程 OAP/UI 已启动；中间件未启动时仅完成 Agent 接线，gap-closure **D2 仍待 live 验收**。
