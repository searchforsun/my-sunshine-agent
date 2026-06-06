# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Sunshine AI Platform — 企业级 AI 中台。基于 AgentScope-Java 的多智能体编排，Spring Cloud Alibaba 微服务架构，Vue3 + Naive UI 前端。当前阶段一已完成（LLM Gateway + ReActAgent + RAG + SSE 流式对话），阶段二（认证 + 业务工具）待开发。

## Build & Run Commands

```bash
# 后端（需要 JDK 21）
mvn clean package -DskipTests          # 全量打包
mvn compile -pl <module> -am           # 单模块编译（-am 含依赖）

# 启动核心服务链（按顺序，前一个启动完成再启下一个）
java -jar llm-gateway/target/sunshine-llm-gateway-*.jar &     # :8300
java -jar orchestrator/target/sunshine-orchestrator-*.jar &   # :8200
java -jar bff/target/sunshine-bff-*.jar &                     # :8001
bash scripts/start.sh                                         # 一键启动全部 11 服务

# 前端
cd sunshine-ui && npm run dev           # :5173 开发模式
cd sunshine-ui && npm run build         # 生产构建

# 测试
curl -X POST http://localhost:8300/v1/chat/completions -H "Content-Type: application/json" \
  -d '{"model":"deepseek-v4-pro","messages":[{"role":"user","content":"hello"}]}'
curl -N -X POST http://localhost:8001/api/chat/stream -H "Content-Type: application/json" \
  -H "x-user-id:test" -d '{"content":"你好"}'
```

**注意**：本机 bash 默认 Java 17，需用 `D:/MyWorkStation/Java/jdk/jdk-21/bin/java` 或先 `switch-java 21`（PowerShell）。

## Architecture

### Request Flow

```
Browser (:5173) → BFF (:8001) → Orchestrator (:8200) → LLM Gateway (:8300) → DeepSeek/Qwen API
                     ↑                ↑                       ↑
                  WebFlux SSE     AgentScope              LlmAdapter 接口
                  透传流式        OpenAIChatModel          DeepSeekAdapter（唯一实现）
                                  ReActAgent.stream()
```

### Key Modules (by role, not deployment order)

| 模块 | 职责 | 当前状态 |
|------|------|:--:|
| `llm-gateway` | OpenAI 兼容 `/v1/chat/completions`，`LlmAdapter` 接口 → `DeepSeekAdapter` 实现，Redis MD5 语义缓存，`ModelRouter` 路由 | ✅ 可用 |
| `orchestrator` | `AgentConfig` 创建 `OpenAIChatModel` → `ReActAgent`，`SunshineAgent.chat()` 调用 `agent.stream()` 返回 `Flux<String>` | ✅ 可用 |
| `bff` | `OrchestratorClient` (WebClient) 转发到 Orchestrator，`ChatController` SSE 透传 | ✅ 可用 |
| `rag-service` | Milvus 2.6 向量库 + 通义 Embedding API，文档分段入库 + 向量检索 | 🔶 代码完整，Milvus 待联调 |
| `gateway` | Spring Cloud Gateway 骨架，Nacos 路由 + Sentinel | 🟡 骨架 |
| `auth-center` | Sa-Token JWT 认证 | 🟡 骨架，阶段二开发 |
| `tool-manager` | 业务 API → AgentScope `@Tool` 注解包装 | 🟡 骨架 |
| `desensitize` | 正则 + AhoCorasick 脱敏 | 🟡 骨架 |
| `oa/finance-service` | 业务模拟服务 | 🟡 骨架 |
| `prompt-manager` | 提示词模板管理，Nacos 热更新 | 🟡 骨架 |

### Critical Design Decisions

1. **为什么用 `OpenAIChatModel` 而不是 `DashScopeChatModel`**：AgentScope 的 `DashScopeChatModel` 请求路径是 `/v1/services/aigc/text-generation/generation`（阿里 DashScope 专有），而自建 LLM Gateway 提供 OpenAI 兼容接口 `/v1/chat/completions`。`OpenAIChatModel` 的默认 endpoint 与此匹配。

2. **LLM Gateway 的设计模式**：`LlmAdapter` 接口 + `ModelRouter` 路由 + `SemanticCacheService` 缓存。新增厂商只需实现 `LlmAdapter` 并注册为 Spring Bean，`ModelRouter` 自动发现。缓存采用 `(model + messages + temperature)` MD5 哈希，配合 DeepSeek 缓存命中定价（¥0.02/M tokens）成本可降 98%+。

3. **BFF 只做透传**：BFF 不处理业务逻辑，只将请求头和消息体转发给 Orchestrator，SSE 流原样返回前端。身份信息通过 `x-user-id`、`x-tenant-id` 透传。

4. **AgentScope ReActAgent 的流式调用**：`agent.stream(List.of(msg), StreamOptions.defaults())` 返回 `Flux<Event>`，需过滤 `event.getMessage().getTextContent()` 非空的事件才是有意义的文本块。

5. **ChatCompletionResponse 的 Jackson 序列化**：使用 `@Builder` 时必须同时加 `@NoArgsConstructor` + `@AllArgsConstructor`，否则 Jackson 反序列化 DeepSeek 响应时报 `InvalidDefinitionException`。

## Server Infrastructure

所有中间件部署在远程服务器 `8.140.48.6`（Docker 网络 `middleware-docker_smt-net`）：

| 组件 | 端口 | 凭据 |
|------|------|------|
| Nacos | 8848/9848 | nacos/nacos |
| MySQL | 3306 | root/root123 |
| Redis | 6379 | redis123（密码） |
| Milvus | 19530 | standalone 模式（内嵌 etcd+minio） |
| RocketMQ | 9876（ns）/10911（broker） | — |
| SkyWalking OAP | 11800 | — |
| SkyWalking UI | 8084 | — |
| Sentinel | 8858 | sentinel/sentinel123 |
| Grafana | 3000 | admin/admin123 |

每个服务的 `application.yml` 已配置 Nacos `import-check.enabled: false`（因为用 BOM import 方式而非 `spring.config.import`）。

## Version Constraints

- **不要升级 Spring Boot 3.3+**：Spring Cloud Alibaba 尚未发布 2024.x 兼容版，2023.0.3.4 是当前最新稳定版。
- **不要升级 AgentScope 2.0.0**：2026-06-05 刚发布，Maven Central 尚未同步，`1.0.7` 是仓库中实际可用的最新版。
- **前端 Vite proxy**：`/api` 代理到 `localhost:8001`（BFF），开发时绕过跨域问题。
