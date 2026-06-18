# CLAUDE.md

Sunshine AI Platform — 企业级 AI 中台（AgentScope-Java + Spring Cloud Alibaba + Vue3/Naive UI）。

**进度**：阶段二 MVP + Workflow 编排已完成；缺口见 `docs/implementation-plan.md`。

## 常用命令

```bash
# JDK 21
mvn clean package -DskipTests
mvn compile -pl <module> -am

# 配置：docs/nacos/ → 同步 → 重启
powershell -ExecutionPolicy Bypass -File scripts/sync-nacos.ps1
powershell -ExecutionPolicy Bypass -File scripts/sync-nacos.ps1 -DataId sunshine-orchestrator.yaml
powershell -ExecutionPolicy Bypass -File scripts/sync-nacos.ps1 -DataId sunshine-workflows.yaml
powershell -ExecutionPolicy Bypass -File scripts/start.ps1

# 前端 :5173；SSE 直连 Gateway :8000
cd sunshine-ui && npm run dev

# 验收
powershell -ExecutionPolicy Bypass -File scripts/phase2-agent-demo.ps1
mvn test -pl llm-gateway -Dtest=ModelRouterTest,AdapterCircuitBreakerTest
```

**首次部署**：MySQL `CREATE DATABASE sunshine_auth;` → `sync-nacos.ps1` → `start.ps1`。

## 请求链路与模块

```
Browser → Gateway :8000 [JWT] → BFF :8001 → Orchestrator :8200
  ├─ simple-llm / workflow(DAG) / react(ReActAgent)
  ├─ llm-gateway :8300  rag :8400  tool-manager :8210 → finance :8710
  └─ desensitize :8600
```

| 模块 | 端口 | 职责 |
|------|:----:|------|
| gateway / bff / auth-center | 8000/8001/8100 | 路由、SSE 透传、JWT |
| orchestrator | 8200 | 三模式 + Timeline |
| tool-manager | 8210 | `ToolRegistry` + `/api/tools/catalog` |
| llm-gateway / rag-service | 8300/8400 | 模型路由 / Milvus |

各服务 `application.yml` 仅 Nacos 入口；业务配置 SSOT 在 `docs/nacos/`。

## 架构与扩展（要点）

**原则**：注册 + Catalog 驱动，禁止 orchestrator/前端硬编码工具 Map。

| 要扩展 | 改哪里 |
|--------|--------|
| 新工具 | `tool-manager` 新增 `ToolHandler`（含 displayName / timelinePhase / outputSummaryKind）→ Nacos `agent.execution.react.tools` 或 workflow 节点 `params.tool` → sync + 重启 tool-manager、orchestrator |
| 新 Workflow | `docs/nacos/sunshine-workflows.yaml` 的 catalog + definitions → sync → 重启 orchestrator |
| 意图步骤文案 | Nacos `agent.timeline.intent`（before/active/after 模板）+ catalog 可选 `intentAfter`；**禁止**在 `StepSummarizer` 硬编码流程名 |
| 步骤 before/active | Nacos `agent.timeline.steps`（plan / rag / generate 等）；前端 **只展示** SSE `summary`，勿写死步骤话术 |
| 步骤中文名 | tool-manager catalog → `ToolCatalogService` → SSE `step.label`；前端 **勿**维护 `TOOL_DISPLAY_NAMES` |

**Tool 链路**：`ToolRegistry` → `GET /api/tools/catalog` → orchestrator `ToolCatalogService` → `DynamicToolkitFactory`（`RagTool` + `CatalogRemoteAgentTool`）→ `StepLabels` / `ToolResultSummarizer`。

**ReAct 时间线**：`intent → think → tool → think-2 → generate`；reasoning 走 `step_delta`；工具 `PreActing` 时 `noteToolCallPending()` 结束当前 think 并屏蔽工具往返 planning。

## 关键约定

1. OpenAIChatModel 对接 Gateway `/v1/chat/completions`。
2. Gateway 鉴权注入 `x-user-id`；BFF/Orchestrator 只读，客户端不得自填。
3. Nacos SSOT：改 `docs/nacos/*.yaml` → `sync-nacos.ps1` → 重启（无 `application-dev.yaml`）。
4. 三模式：`IntentRouter` → `ExecutionDispatcher`；workflow 图在 `sunshine-workflows.yaml`。
5. 财务/react 工具经 tool-manager；**禁止** Controller 拼 prompt 模板（见 Nacos `agent.system-prompt`）。
6. `ChatCompletionResponse` 用 `@Builder` 须加 `@NoArgsConstructor` + `@AllArgsConstructor`。
7. 审计：assistant 终态 → RocketMQ / MySQL / ES；`GET /api/audit/recent`。

## 中间件（ecs4c16g）

Nacos 8848 | MySQL 3306 root/root123 | Redis 6379 | Milvus 19530 | RocketMQ 9876 | ES 9200 | SkyWalking 11800/8084

## 版本与前端

- 勿升 Spring Boot 3.3+、AgentScope 2.0.0；Sa-Token **1.45.0**（需 `sa-token-jwt`）。
- UI：Codex 灰阶；令牌 `src/styles/global.css`；`OperationStack` 用 SSE `step.label`；SSE 基址 `BFF_STREAM_BASE`（默认 `:8000`）。

## 其他

- 禁止保存临时脚本；代码加适量中文注释。
- `start.ps1` 可带 SkyWalking agent。
