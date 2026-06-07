# SkyWalking Java Agent

## 下载

```bash
# 方式 1：直接下载（推荐）
curl -L -o skywalking-agent.jar \
  https://dlcdn.apache.org/skywalking/java-agent/9.7.0/apache-skywalking-java-agent-9.7.0.tgz
tar -xzf apache-skywalking-java-agent-9.7.0.tgz
cp skywalking-agent/skywalking-agent.jar ./

# 方式 2：手动下载
# 浏览器打开 https://skywalking.apache.org/downloads/
# 选择 Java Agent 9.7.0 → 解压后复制 skywalking-agent.jar 到此处
```

## 版本

- **Agent**: 9.7.0（匹配服务器 OAP 版本）
- **OAP Backend**: 8.140.48.6:11800
- **UI**: http://8.140.48.6:8084
