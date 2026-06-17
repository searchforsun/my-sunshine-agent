# CLAUDE.md

Sunshine AI Platform — 企业级 AI 中台（AgentScope-Java + Spring Cloud Alibaba + Vue3/Naive UI）。

**进度**：阶段二 2.1–2.8 MVP 已完成；**Workflow 编排**（三模式 + DAG 引擎）已落地；阶段三待启动。已知缺口见 `docs/implementation-plan.md`。

## 常用命令

```bash
# 后端（JDK 21，本机默认可能是 17：switch-java 21）
mvn clean package -DskipTests
mvn compile -pl <module> -am

# Nacos 配置 SSOT：docs/nacos/ → 同步线上 → 启动
powershell -ExecutionPolicy Bypass -File scripts/sync-nacos.ps1
powershell -ExecutionPolicy Bypass -File scripts/sync-nacos.ps1 -DataId sunshine-orchestrator.yaml
powershell -ExecutionPolicy Bypass -File scripts/sync-nacos.ps1 -DataId sunshine-workflows.yaml

# 一键启动（10 个微服务，配置来自 Nacos）
powershell -ExecutionPolicy Bypass -File scripts/start.ps1
# :8300 llm-gateway → :8400 rag → :8710 finance → :8210 tool-manager → :8600 desensitize
# → :8500 prompt-manager → :8200 orchestrator → :8100 auth → :8001 bff → :8000 gateway

# 前端
cd sunshine-ui && npm run dev   # :5173，/api → Gateway :8000

# 验收
powershell -ExecutionPolicy Bypass -File scripts/phase2-auth-demo.ps1
powershell -ExecutionPolicy Bypass -File scripts/phase2-demo.ps1          # 分层 HTTP + 可选 Agent
powershell -ExecutionPolicy Bypass -File scripts/phase2-agent-demo.ps1    # 2.4 全链路 SSE
# 跳过 Agent 全链路：$env:PHASE2_SKIP_AGENT=1

# 单测（模型降级）
mvn test -pl llm-gateway -Dtest=ModelRouterTest,AdapterCircuitBreakerTest
```

**首次部署**：MySQL 执行 `CREATE DATABASE sunshine_auth;`（auth）；`sync-nacos.ps1` 后再 `start.ps1`。

## 请求链路

```
Browser (:5173)
  → Gateway (:8000)  [JWT 校验，注入 x-user-id]
       ├─ /api/auth/** → auth-center (:8100)
       └─ /api/**      → BFF (:8001) → Orchestrator (:8200)
                              ├─ LLM Gateway (:8300) → DeepSeek / Qwen
                              ├─ rag-service (:8400) Milvus 检索
                              ├─ tool-manager (:8210) → finance-service (:8710)
                              └─ desensitize (:8600) 入站/出站脱敏
```

SSE 流式直连 Gateway `:8000`（`Authorization: Bearer`），避免 Vite 缓冲。

## 模块

| 模块 | 端口 | 职责 | 状态 |
|------|:----:|------|:----:|
| `gateway` | 8000 | 路由、JWT、header 注入 | ✅ |
| `bff` | 8001 | SSE/会话 CRUD 透传 | ✅ |
| `auth-center` | 8100 | 注册/登录、JWT | ✅ |
| `orchestrator` | 8200 | 三模式执行（simple-llm / workflow / react）+ ReActAgent + Timeline | ✅ |
| `tool-manager` | 8210 | 业务 API → AgentScope `@Tool` + `ToolRegistry` | ✅ |
| `llm-gateway` | 8300 | `LlmAdapter` + `ModelRouter` + 语义缓存 + 降级 | ✅ |
| `rag-service` | 8400 | Milvus + Embedding | 🔶 待联调 |
| `prompt-manager` | 8500 | 提示词模板（骨架） | 🟡 |
| `desensitize` | 8600 | 正则脱敏 | ✅ MVP |
| `finance-service` | 8710 | 财务消息 Mock API | ✅ |
| `oa-service` | 8700 | OA 模拟 | 🟡 骨架 |

各服务 `application.yml` **仅** Nacos 入口（`import nacos:{dataId}.yaml`），业务配置在 `docs/nacos/`。

## 关键约定

1. **OpenAIChatModel** 对接自建 Gateway `/v1/chat/completions`（不用 DashScope 专有路径）。
2. **Gateway 集中鉴权**：BFF/Orchestrator 只读 `x-user-id`，客户端不得自填。
3. **BFF 只透传**；**不做多租户**（`x-tenant-id` 固定 `default`）。
4. `ChatCompletionResponse` 用 `@Builder` 时须加 `@NoArgsConstructor` + `@AllArgsConstructor`。
5. **Nacos SSOT**：只改 `docs/nacos/*.yaml` → `sync-nacos.ps1` → 重启受影响服务（无 `application-dev.yaml`）。
6. **ReActAgent 流式**：`agent.stream()` 过滤 `event.getMessage().getTextContent()` 非空；reasoning 统一经 `ThinkStepMapper` / `AgentScopeEventMapper` 拆为独立 `think` 步骤（废弃 `agent` 容器步）。
7. **三模式路由**：`IntentRouter.classifyPlan()` → `ExecutionDispatcher`；workflow 图 SSOT 在 `docs/nacos/sunshine-workflows.yaml`（catalog + definitions）；无图定义时回退 legacy。
8. **财务路径**：workflow `finance-*` 或 react 模式经 `tool-manager` 预取/调用；**禁止在 Controller 拼接 prompt 模板**，输出规范见 Nacos `agent.system-prompt`。
9. **审计**：assistant 消息终态 → RocketMQ `sunshine-audit` / 直写 MySQL `chat_audit_log` + ES `sunshine-audit`；查询 `GET /api/audit/recent`（orchestrator :8200）。

## 中间件（ecs4c16g）

| 组件 | 端口 | 凭据 |
|------|------|------|
| Nacos | 8848 | nacos/nacos |
| MySQL | 3306 | root/root123 |
| Redis | 6379 | redis123 |
| Milvus | 19530 | standalone |
| RocketMQ NS | 9876 | — |
| Elasticsearch | 9200 | — |
| SkyWalking OAP/UI | 11800 / 8084 | — |
| Sentinel | 8858 | sentinel/sentinel123 |

## 版本约束

勿升 Spring Boot 3.3+、AgentScope 2.0.0；Sa-Token **1.45.0**（需 `sa-token-jwt`）。

## 前端（sunshine-ui）

Codex 风格：灰阶扁平，品牌色仅 Logo。令牌 SSOT：`src/styles/global.css`（`--sun-*`）。

- 认证：`authStore` + `sunshine-token`；`apiHeaders()` 发 Bearer
- CRUD：`/api`（Vite 代理）；SSE：`BFF_STREAM_BASE`（默认 `http://localhost:8000`）
- 对话页：`OperationStack` 展示步骤流（含 `think` 独立步骤与耗时）
- **工具步骤中文名**：后端 `StepLabels.toolDisplayName` / 前端 `processingSteps.toolDisplayName` 映射工具 id（如 `list_finance_messages` →「查询待审批财务消息」）；OperationStack 标题统一为「调用工具 {中文名}」，勿直接展示 snake_case

## 其他

- 禁止保存临时脚本
- 上线 auth 前可跑 `scripts/phase2-auth-reset.sql`
- `start.ps1` SkyWalking：`skywalking.agent.service_name` + stdout/stderr 分文件重定向
- 生成的代码要加上一定量的中文注释方便阅读
