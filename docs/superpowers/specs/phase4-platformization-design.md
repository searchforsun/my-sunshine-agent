# 阶段四：平台化 — 技术设计（SSOT）

> **周期**：按需启动（子项独立排期）  
> **状态**：⬜ 按需  
> **触发**：阶段三检查门通过 + 业务接入量/运维复杂度达阈值  
> **前置**：[阶段三](./phase3-production-hardening-design.md) 文本 RAG hybrid+rerank 稳定

---

## 1. 启动条件

| 条件 | 说明 |
|------|------|
| 阶段三 17 条检查门通过 | 含 v5/v6 评测、PLAN_WORKFLOW、租户、HITL |
| 业务阈值（任一） | 语料 >20 篇需运营自助；PDF/扫描件入库需求；多副本部署；异构系统接入 |

---

## 2. 任务总览

| 任务卡 | 摘要 | 触发 | 优先级 |
|--------|------|------|:------:|
| **4.1** | RAG 平台化：多知识库、运营后台、评测周报、检索调试 | 语料增长 | **高** |
| **4.2** | 文档 OCR 入库 L1：PDF/图片 → 文本 chunk | 非 Markdown 语料 | **高** |
| **4.3** | 文档理解 L2：版面/表格 + quarantine | L1 稳定后 | 中 |
| **4.4** | 多模态对话 L3：Vision + `/chat` 附件 | 拍图问一问 | 中 |
| **4.5** | Skills Docker 沙箱 | 代码执行 skill | 中 |
| **4.6** | 动态 DAG 增强：if-else、并行 fan-out、Replan | 静态 workflow 不够 | 中 |
| **4.7** | 多 Agent 增强：**第五顶层模式 `PEER_COLLAB` ✅**、Coordinator、MsgHub 反应式轮次、Synthesizer、**ReAct TaskBoard ✅** | 复杂协作 / 交叉验证 / ReAct 软规划 | 中 |
| **4.8** | 工具集成（SDK + MCP）：MySQL Catalog + `/tools` 管理页 | 业务解耦 / 异构工具接入 | 中 · [详设](./2026-07-09-tool-integration-design.md) |
| **4.9** | K8s：Helm + HPA + Nacos GitOps | — | **明确不做** |
| **4.10** | Seata 分布式事务 + HITL 串联 | — | **明确不做** |
| **4.11** | Prompt 运营后台：版本/审核/回滚 | 提示词 >10 + 非研发维护 | 中 |
| **4.12** | Serverless 冷启动 | — | **明确不做** |
| **4.13** | **Workflow Studio**：Dify 式可视化维护 + DB PlanJson + MySQL init 种子 | 静态 workflow 运维 / 业务自助编排 | **✅ 收口** |

**三/四交界（已落地）**：Chat 底栏 **执行路径选择器** P0 ✅（`executionPreference` + `ForcedExecutionRouter`）；**workflow 模板 catalog / `#` 绑定** 归属 **4.13**，见 [chat-execution-mode-selector-design.md](./2026-06-25-chat-execution-mode-selector-design.md) §1.1、[workflow-studio-design.md](./2026-06-25-workflow-studio-design.md) §3.4。

**建议顺序**：4.1 → 4.2 → **4.13** → 4.8 → 4.11 → 4.4（**不含** 4.9 / 4.10 / 4.12）

**修订（2026-07-15）**：**4.9 K8s / 4.10 Seata / 4.12 Serverless 明确不做**（维持现有单机/脚本运维；跨服务写继续靠 HITL + 业务幂等，不引入 Seata）。

---

## 3. 任务详设

### 4.1 RAG 平台化

> **详设**：[docs/rag/README.md](../../rag/README.md) · [2026-06-27-rag-knowledge-studio-design.md](./2026-06-27-rag-knowledge-studio-design.md) · **缺口** [backlog.md](../../rag/backlog.md)  
> **Pipeline 边界**：[ADR-002-rag-pipeline-in-rag-service.md](../../architecture/ADR-002-rag-pipeline-in-rag-service.md) — 改写 + hybrid + rerank + fallback 内聚 rag-service；orchestrator 只调干净检索 API

| 子任务 | 内容 |
|--------|------|
| **4.0.1** | `KnowledgeRetrievalPipeline` + 扩展 `POST /api/rag/search`（`trace` / 一次 RPC） |
| **4.0.2** | `QueryRewritePipeline` + `rag.rewrite.*` 迁入 `sunshine-rag.yaml` |
| **4.0.3** | orchestrator 瘦身为 `RagClient`；Timeline 读 response trace |
| **4.0.4** | `rag_eval.py` / CI 对齐 pipeline |
| **4.1.1** | 知识库 `namespace`：`tenant/kbId/docId`（dept 预留） |
| **4.1.2** | 文档版本：新 v 入库自动失效旧 chunk |
| **4.1.3** | `scripts/rag_reindex.py` 全量重建 + 进度 |
| **4.1.4** | Admin API：`POST /api/kb/{kbId}/evaluate` |
| **4.1.5** | 前端检索调试页（vector/bm25/rerank 分数瀑布） |
| **4.1.6** | Badcase：`POST /api/rag/feedback` → 回流 golden-set |
| **4.1.7** | ~~策略 A/B~~ | **不做** |
| **4.1.8** | ~~评测周报 Cron~~ | **不做** |

**检查门**：pipeline 切换后 v5 eval 全绿；UI 上传 5 分钟内可检索；v2 入库后 v1 不可检；调试页可见 rewrite+vector/rerank 各阶段分数。

### 4.2 文档 OCR 入库（L1）

> **详设**：同上 [rag-knowledge-studio-design.md](./2026-06-27-rag-knowledge-studio-design.md) §6.2

**OCR 锁定**：千问 DashScope（与 Embedding 同账号）；电子版 PDF 优先本地文本层，失败再走 OCR。

| 子任务 | 内容 |
|--------|------|
| **4.2.1** | `POST /api/rag/ingest/file` + 类型检测 |
| **4.2.2** | DashScope OCR + PDF 文本层抽取 |
| **4.2.3** | → Markdown 规范化 → 现有 `MarkdownParser` → Milvus |
| **4.2.4** | `/knowledge` 扩展 PDF/图片上传 |
| **4.2.5** | ocr golden-set + `rag_eval` 扩展 |

**原则**：产出文本后复用阶段三 hybrid+rerank；不为 OCR 改 Milvus 主 schema（用 `source_type` metadata）。

详设历史稿：`2026-06-21-multimodal-ocr-design.md` §1–3

### 4.3 文档理解 L2

| 子任务 | 内容 |
|--------|------|
| **4.3.1** | 表格/多栏版面（`qwen-doc-parse`） |
| **4.3.2** | 低置信度 quarantine 队列 |
| **4.3.3** | 脱敏后再 embed |

### 4.4 多模态对话 L3

| 子任务 | 内容 |
|--------|------|
| **4.4.1** | LLM Gateway vision 路由（Qwen-VL） |
| **4.4.2** | `/chat` 图片附件 + BFF 暂存 |
| **4.4.3** | Grounding 强制引用 OCR 原文 |

### 4.5 Skills Docker 沙箱

> **索引**：[docs/sandbox/README.md](../../sandbox/README.md)  
> **详设 SSOT**：[2026-07-15-skills-docker-sandbox-design.md](./2026-07-15-skills-docker-sandbox-design.md) · [方案 B](./2026-07-16-conversation-sandbox-permanent-tools-design.md) · [工作区抽屉](./2026-07-16-sandbox-workspace-drawer-design.md) · [写确认跳过](./2026-07-16-sandbox-write-hitl-skip-design.md)  
> **形态**：Coding Agent 工作区（六工具 `sandbox__*`）+ `sandbox-service`(:8226) + 对话级长容器；工具 **不进** Catalog。方案 B：**MAIN 始终注入**；懒开箱。

| 子任务 | 内容 | 状态 |
|--------|------|:----:|
| **4.5.1** | `sandbox-service` 骨架 + Docker Session + 镜像 | ✅ |
| **4.5.2** | 六工具 + PathJail + 网络白名单 + write Guard / write 拒覆盖 | ✅ |
| **4.5.3** | orchestrator 注入 + HITL + 方案 B 懒开箱 | ✅ |
| **4.5.4** | Skill 元数据 / `/skills` 试跑 / 多 Skill 挂载 | ✅ |
| **4.5.5** | 审计 + Grafana + Live G1–G9 / W1–W5 | ✅ |
| **4.5.6** | 工作区抽屉（多 tab / md 切换 / 路径芯片）+ `writeHitlMode` + 时间线路径展示 | ✅ |
| — | Live：`writeHitlMode` Chat 冒烟 | ⬜ |

锁定：默认 `network=none` + `read_only_rootfs`（可写仅 `/workspace` volume）；`network_allow` 非空时经 egress 白名单代理（修订 D4，见详设）。

### 4.6 动态 DAG 增强

| 子任务 | 内容 |
|--------|------|
| **4.6.1** | `IfElseNodeHandler` |
| **4.6.2** | `ParallelNodeHandler` fan-out/join |
| **4.6.3** | Plan 缓存与 Replan |
| **4.6.4** | P5 `ContextCompressor`（STM 工具结果摘要） |

### 4.7 多 Agent 增强

| 子任务 | 内容 | 状态 |
|--------|------|:----:|
| **4.7.1** | M8 `DelegateSkillTool`（主 Coordinator react 委派） | ⬜ |
| **4.7.2** | M10 并行子 Agent fan-out/join | ⬜ |
| **4.7.3** | **多专家协作**：`PEER_COLLAB` L1 §E + Expert Catalog `$` §K + `expert-manager` + Hub 反应式轮次 + Synthesizer | **✅** |
| **4.7.4** | M9 前端子 Agent 详情展开 UI | ⬜ |
| **4.7.5** | **ReAct TaskBoard**（`manage_tasks` 元工具 + `tasks` Timeline + 审计） · [详设](./2026-06-24-react-taskboard-design.md) · **D11** | **✅** |

**4.7.3 摘要（✅）**：第五顶层模式 `peer-collab`；`ExpertConsultationExecutor` + `ExpertHubEngine`（min/max 轮次、continue、反应式选人）+ `ConsultationSynthesizer`；详设 [expert-consultation-design.md](./2026-07-07-expert-consultation-design.md) · Live `verify_peer_collab_live` + `verify_expert_consultation_live`。

### 4.8 工具集成（SDK + MCP）

> **演进 SSOT**：[2026-07-09-tool-integration-design.md](./2026-07-09-tool-integration-design.md) · 实施计划：[2026-07-09-tool-integration.md](../plans/2026-07-09-tool-integration.md)  
> **阶段归属**：阶段三 **非目标**（见 [phase3](./phase3-production-hardening-design.md) §1）；**前置** 阶段三 3.11 skill-manager + 3.12 `/skills` Catalog 管理模式、3.3 HITL `sideEffect`、3.6 tool 审计。  
> **对称参照**：与 skill-manager（:8225 + `/skills`）同模式 — **tool-manager 扩 MySQL Catalog + Admin API**，前端 **`/tools`** 管理页（SDK / MCP / 工具集 Tab），orchestrator 经 `ToolSetResolver` + `ToolCatalogService` 拉取，**禁止**前端维护工具 Map。

**本设计承接原 §4.8 MCP 目标并扩展 SDK 业务解耦**；原独立 `/mcp` 路由合并为 `/tools` MCP Tab。

| 子任务 | 内容 |
|--------|------|
| **4.8.1** | `sunshine-tool-sdk` + finance/oa Demo；`sdk_application` / `tool_definition` MySQL Catalog |
| **4.8.2** | SdkDiscoveryPuller（Nacos Pull）+ InvokeRouter(sdk)；去除 tool-manager 对 finance/oa HTTP 桥接 |
| **4.8.3** | MCP：`mcp_server` 表 + import/probe + `McpClientPool`；Catalog `kind=mcp`，Tool ID `mcp__{serverId}__{name}` |
| **4.8.4** | 工具集 `global_react_default` + `global_plan_workflow_critical` + 租户覆盖；弃用 Nacos `react.tools` 白名单 |
| **4.8.5** | 前端 **`/tools`**：SDK 应用同步、MCP Server CRUD/探测、工具启停与描述编辑、工具集（ReAct + Planner Workflow）与 Plan 执行策略 |
| **4.8.6** | MCP/SDK 写工具走 **3.3 HITL**；调用审计 **3.6**（`source=sdk\|mcp`） |
| **4.8.7** | Live：`scripts/verify_tool_integration_live.py`（G1–G10） |

**Phase 1 增量（✅ 2026-07-10）**：Catalog ID `sdk__*` / `mcp__*`（`ToolIds`，无 LLM 转换层）；HITL `require_confirmation`；`execution_mode_policy`；llm-gateway `toolCalls` 日志。详设 [tool-integration-design §6.3 / §9 / §14](./2026-07-09-tool-integration-design.md)。

**动态引入流程**：

```
业务 App + SDK → Nacos 注册 → tool-manager Pull catalog → MySQL
运维 → /tools 注册 MCP Server → probe tools/list → Catalog（kind=mcp）
管理页启停 / 工具集 → Redis tool-catalog-changed → orchestrator 热刷新
```

**检查门**：`verify_tool_integration_live.py --suite all` — SDK 2 应用 5 工具、ReAct invoke、MCP probe、工具集、HITL、动态 disable；**✅ 已通过**（2026-07-10）。详见 [tool-integration-design §16](./2026-07-09-tool-integration-design.md#16-检查门)。

### 4.9 K8s 生产部署 — **明确不做**

> 2026-07-15 决策：不排期 Helm / HPA / GitOps；继续现有 `scripts/start.py` + 中间件部署形态。

| 子任务 | 内容 | 状态 |
|--------|------|:----:|
| **4.9.1–4.9.4** | Helm / HPA / 有状态集 / Nacos GitOps | **不做** |

### 4.10 Seata 分布式事务 — **明确不做**

> 2026-07-15 决策：不引入 Seata TCC/SAGA；跨服务写继续依赖 **3.3 HITL** + 业务侧幂等/补偿。

### 4.11 Prompt 运营后台

- `prompt_version` 表；草稿→审核→发布 Nacos；与 **4.1.7** 实验联动 rag_eval

### 4.12 Serverless — **明确不做**

> 2026-07-15 决策：不做无状态服务 Serverless 缩容；保持常驻实例。

### 4.13 Workflow Studio（可视化工作流维护）

> **详设**：[2026-06-25-workflow-studio-design.md](./2026-06-25-workflow-studio-design.md)  
> **对称参照**：skill-manager + `/skills`；执行引擎复用 `WorkflowExecutor` + `PlanMaterializer`

| 子任务 | 内容 |
|--------|------|
| **4.13.1** | `workflow-manager` :8230 + 表结构 | **✅** |
| **4.13.1b** | **MySQL init 种子**（现 **7** 标杆 published v1，含 `knowledge-dual` / `knowledge-branch` / `knowledge-loop`） | **✅** |
| **4.13.2** | Admin / Catalog / Published API + `PlanValidator` 发布校验 | **✅** |
| **4.13.2b** | orchestrator 移除 Nacos workflow + `WorkflowManagerClient` | **✅** |
| **4.13.3** | `WorkflowCatalogService` + **`WorkflowBindingParser/Policy`（L0 `#`）** | **✅** |
| **4.13.3b** | DB workflow 节点重试策略对齐 `NodeRetryPolicyResolver` | **✅** |
| **4.13.4** | BFF/Gateway 透传 | **✅** |
| **4.13.5** | 前端 **`/workflows`** 线性 DAG 编辑器 MVP + Chat `#` | **✅** |
| **4.13.6** | golden-set §I + `verify_workflow_studio_live.py` | **✅** |
| **4.13.7** | 并行 / exclusive 边条件 / loop 容器（引擎 + Studio） | **✅** |

**修订（2026-07-11）**：Workflow **DB 唯一 SSOT**；废弃 Nacos `sunshine-workflows.yaml` 与 `Composite*` 合并逻辑。详设 [workflow-studio-design.md](./2026-06-25-workflow-studio-design.md) · 计划 [2026-07-11-workflow-studio.md](../plans/2026-07-11-workflow-studio.md)

**修订（2026-07-15）**：**4.13 当前形态收口**；v1 非目标（for-each、预检测 while、框内嵌套网关/loop、多出边汇合、画布边条件标签等）**明确不做**。

**检查门**：MySQL init 后 `#knowledge-qa` 命中 DB；orchestrator 无 Nacos workflow 依赖；`@` / `#` 互不混用。

**P0 多 Agent 接入边界**（MsgHub / Parallel / TaskBoard）：[2026-06-25-phase4-agent-capabilities-boundaries.md](./2026-06-25-phase4-agent-capabilities-boundaries.md)

---

## 4. 阶段演进关系

```
阶段一 → 底座 + 会话 + SSE 重连
阶段二 → 三模式 + Workflow + RAG 基线 + Timeline V2
阶段三 → hybrid RAG + 租户 + HITL + PLAN_WORKFLOW + Skills
阶段四 → 运营平台 + OCR + 沙箱 + **MCP 动态引入** + K8s + …
```

---

## 5. 检查门（按启动子项）

| 子项 | 核心检查 |
|------|----------|
| 4.1 | 运营自助上传 + 调试页 + 评测闭环 |
| 4.2–4.3 | PDF/发票 OCR 入库可检索 + ocr eval |
| 4.4 | 聊天发图识图 + Grounding |
| 4.5 | Docker 沙箱六工具 + jail/HITL/审计 · 详设 G1–G9 |
| 4.8 | `/tools` SDK+MCP + probe + 工具集 + Live G1–G10 · **✅** |
| 4.9 / 4.10 / 4.12 | **明确不做**（无对应检查门） |

---

## 6. 相关文档

- [阶段三](./phase3-production-hardening-design.md)
- [第五模式 Peer 协作路由](./2026-06-24-peer-collab-routing-design.md)
- [多 Agent 架构详设](./2026-06-19-multi-agent-architecture-design.md) · [锁定决策 D10](./2026-06-19-locked-architecture-decisions.md#d10-第五顶层模式--peer_collab阶段四)
- [路由 Golden-set §E](../../routing/routing-golden-set.md#e-peer_collab阶段四)
- `docs/rag/golden-set.yaml`
- 历史详设：`2026-06-21-multimodal-ocr-design.md`、`2026-06-19-advanced-capabilities-design.md`
