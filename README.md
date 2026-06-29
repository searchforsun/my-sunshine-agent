# Sunshine AI Platform

企业级 AI 中台 — 基于 AgentScope-Java + Spring Cloud Alibaba 的私有化智能体平台。

## 架构概览

```
Browser (Vue3 + Naive UI :5173)
  │
  ▼
Gateway (:8000, JWT + Sentinel) ──▶ BFF (:8001, SSE) ──▶ Orchestrator (:8200)
                                                          │
                    ┌─────────────────────────────────────┼─────────────────────┐
                    │                                     │                     │
              simple-llm / workflow(DAG) / react / plan-workflow                  │
                    │                                     │                     │
                    ▼                                     ▼                     ▼
            LLM Gateway (:8300)                    RAG (:8400)           tool-manager (:8210)
            DeepSeek / Qwen                        Milvus + ES           skill-manager (:8225)
                    │                                     │
               Auth Center (:8100)                  finance / oa 模拟服务
               Sa-Token JWT
```

**执行模式**（IntentRouter → ExecutionDispatcher）：`simple-llm` · 静态 `workflow` · `react` · 动态 `plan-workflow`（Planner DAG + Plan 抽屉 UI）。阶段四规划第五模式 `peer-collab`。

## 技术栈

| 层 | 组件 | 版本 |
|---|------|------|
| **JDK** | OpenJDK | 21 LTS |
| **框架** | Spring Boot + Spring Cloud + Spring Cloud Alibaba | 3.2.9 / 2023.0.3 / 2023.0.3.4 |
| **Agent** | AgentScope-Java | 1.0.7 |
| **认证** | Sa-Token（JWT + Redis） | 1.45.0 |
| **向量库** | Milvus + Elasticsearch | 2.6.16 |
| **消息队列** | Apache RocketMQ | 5.3.2 |
| **可观测** | SkyWalking · Micrometer · Prometheus · Grafana · Sentinel | 9.7.0 |
| **前端** | Vue 3 + TypeScript + Naive UI + Vite | — |

## 项目结构

```
my-sunshine-agent/
├── pom.xml                     # 父 POM（版本管控）
├── common/sunshine-common/     # 公共模块（R<T>、BizException、GlobalExceptionHandler）
├── gateway/         :8000      # Spring Cloud Gateway + Sentinel
├── bff/             :8001      # WebFlux + SSE 流式转发
├── auth-center/     :8100      # Sa-Token 认证中心
├── orchestrator/    :8200      # 四模式编排 + Timeline + AgentRuntime
├── tool-manager/    :8210      # 业务 API → Agent Tool（Catalog 驱动）
├── skill-manager/   :8225      # Skills 上传 / 版本 / Catalog
├── llm-gateway/     :8300      # LLM 网关（多厂商路由 / 缓存 / 熔断）
├── rag-service/     :8400      # RAG 检索（Milvus + Hybrid + Rerank）
├── prompt-manager/  :8500      # 提示词管理
├── desensitize/     :8600      # 数据脱敏
├── oa-service/      :8700      # OA 模拟
├── finance-service/ :8710      # 财务模拟
├── sunshine-ui/     :5173      # 前端 WebUI
├── docker/                     # Docker Compose（中间件 + Prometheus/Grafana）
├── scripts/                    # Python 运维脚本（SSOT：scripts/*.py）
└── docs/                       # 设计文档（Nacos SSOT：docs/nacos/）
```

## 快速开始

### 1. 环境要求

- JDK 21、Maven 3.9+、Node.js 22+、Python 3.10+（运维脚本）
- 中间件已部署在 **ecs4c16g**（见下表）；业务配置 SSOT 在 `docs/nacos/`

**首次部署**：MySQL 执行 `CREATE DATABASE sunshine_auth;`，再同步 Nacos 并启动服务。

### 2. 编译

```bash
mvn clean package -DskipTests
cd sunshine-ui && npm install && npm run build
```

### 3. 配置与启动

```bash
pip install -r scripts/requirements.txt

# 同步 Nacos（改 docs/nacos/*.yaml 后必做）
python scripts/sync_nacos.py

# 按依赖顺序启动全链路（可选 SkyWalking agent）
python scripts/download_skywalking_agent.py   # 首次可选
python scripts/start.py

# 清会话（MySQL + Redis 生成流；可选重启 orchestrator）
python scripts/clear_session_cache.py --force --restart-orchestrator
```

### 4. 启动前端

```bash
cd sunshine-ui && npm run dev    # http://localhost:5173
```

SSE 默认经 Gateway `:8000`（`sunshine-ui` 环境变量 `VITE_BFF_STREAM_BASE`）。

### 5. 验收

```bash
# Agent 四模式（react / workflow / plan-workflow 等）
python scripts/phase2_agent_demo.py --suite all

# RAG 双轨门禁（v5 回归）
python scripts/rag_reset.py
python scripts/rag_ingest_bulk.py
python scripts/rag_eval.py --suite v5 --strategy hybrid+rerank --ci --fail-if-recall5-below 0.98

# Orchestrator 关键单测
mvn test -pl orchestrator -Dtest=ExecutionPlanRouterTest,RoutingGoldenSetTest,WorkflowExecutorTest,ReactExecutorTest
```

#### 集成测试（Orchestrator）

默认 `mvn test` **排除** `@Tag("integration")`（无需外部中间件）：

```bash
mvn test -pl orchestrator -am "-Dtest=ConversationIntegrationTest,GenerationReconnectIntegrationTest" -q
mvn test -pl orchestrator -am "-Dgroups=integration" "-Dtest=ChatIntegrationTest" -q   # 需 :8300 live
```

## 前端页面

| 路由 | 功能 |
|------|------|
| `/chat` | 流式对话；底栏执行路径选择；静态 / Plan workflow 共用 Plan DAG 面板 |
| `/plans/:planId` | Plan 详情与节点 trace |
| `/knowledge` | 知识库入库与检索测试 |
| `/skills` | Skill 管理；版本 diff → `/skills/:skillId/diff` |
| `/status` | 服务与中间件状态 |

## 服务器中间件（ecs4c16g）

| 组件 | 端口 | 凭据 |
|------|------|------|
| Nacos | 8848/9848 | nacos / nacos |
| MySQL | 3306 | root / root123 |
| Redis | 6379 | redis123 |
| Milvus | 19530 | — |
| RocketMQ | 9876 | — |
| Elasticsearch | 9200 | — |
| SkyWalking OAP / UI | 11800 / 8084 | — |
| Prometheus | 9090 | — |
| Grafana | 3000 | admin / admin123 |
| Sentinel Dashboard | 8858 | sentinel / sentinel123 |

## 可观测

| 能力 | 本地开发 | Live 完整（阶段三 3.5 检查门） |
|------|----------|--------------------------------|
| **RAG 质量** | `rag_eval.py`（主验收，不依赖 Grafana） | 同左 |
| **应用指标** | `curl :8400/actuator/prometheus` | Prometheus 能 scrape 到各服务 |
| **Grafana RAG 面板 + 4 告警** | 可跳过（见 [docs/grafana/README.md](./docs/grafana/README.md) 方案 B） | rag-service 与 Prometheus **同机或内网可达**；`docker compose up prometheus grafana` |
| **Sentinel 租户 QPS** | 本机 Gateway 时 Dashboard 可能看不到机器 | Gateway 与 Dashboard **同网段**；见 [docs/sentinel/README.md](./docs/sentinel/README.md) |
| **SkyWalking 链路** | agent 上报 `ecs4c16g:11800`（OAP 在中间件机） | UI `:8084` 查 trace |

本地开发**不必**起 Grafana 也能完成 RAG / Agent 功能开发；**关阶段三 3.5 检查门**需要在 ecs4c16g 联机部署观测栈与被观测服务。

## 环境变量

```bash
export DEEPSEEK_API_KEY=sk-xxx    # DeepSeek API Key
export QWEN_API_KEY=sk-xxx        # 通义千问（Embedding 复用）
```

## 实施阶段

| 阶段 | 状态 | 内容 |
|------|:--:|------|
| 阶段〇 | ✅ | 中间件 + 项目骨架 |
| 阶段一 | ✅ | LLM Gateway · ReActAgent · RAG · SSE · SkyWalking 探针 |
| 阶段二 | ✅ | 认证 · 财务/OA 工具链 · Workflow · Timeline V2 · 会话断点续传 |
| 阶段三 | **检查门基本通过** | 详见 [implementation-plan.md](./docs/implementation-plan.md) |
| 阶段四 | ⬜ | PEER_COLLAB · TaskBoard · Workflow Studio · MCP · K8s（按需） |

进度 SSOT：[docs/implementation-plan.md](./docs/implementation-plan.md)

## 文档

| 文档 | 说明 |
|------|------|
| [implementation-plan.md](./docs/implementation-plan.md) | 分阶段任务卡与检查门 |
| [superpowers/specs/README.md](./docs/superpowers/specs/README.md) | 阶段一～四设计 SSOT 索引 |
| [tech-solution.md](./docs/tech-solution.md) | 架构设计与技术选型 |
| [CLAUDE.md](./CLAUDE.md) | 扩展点、时间线约定、验收脚本索引 |
| [grafana/README.md](./docs/grafana/README.md) | RAG 指标与 Grafana 部署 |
| [sentinel/README.md](./docs/sentinel/README.md) | Gateway 租户 QPS 与 Dashboard |
| [tech-debt-register.md](./docs/tech-debt-register.md) | 技术债 / 文档债 backlog |
| [architecture/README.md](./docs/architecture/README.md) | 架构决策（ADR） |
| [routing/routing-golden-set.md](./docs/routing/routing-golden-set.md) | 意图路由验收集 |
| [2026-06-26-pause-resume-consistency-design.md](./docs/superpowers/specs/2026-06-26-pause-resume-consistency-design.md) | 阶段三收尾 3.9.5 设计 |
| [2026-06-26-pause-resume-consistency.md](./docs/superpowers/plans/2026-06-26-pause-resume-consistency.md) | 3.9.5 实施计划 |
