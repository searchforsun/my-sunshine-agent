# 阶段五：运营化与开放化 — 技术设计（SSOT）

> **周期**：按需启动（子项独立排期）
> **状态**：⬜ 规划（2026-07-27 立项）
> **触发**：阶段四收口 + 平台需对外交付/量化运营效果
> **前置**：[阶段四](./phase4-platformization-design.md) 4.1/4.2/4.5/4.6/4.7/4.8/4.13 检查门通过；**4.11 Prompt 后台收口**（5.3/5.4 依赖其 Catalog 版本模型）
> **对标缺口**：智能体中台蓝图 §5 运营管控与观测层、§6 应用输出层、§1 多模型混部路由、§4 工具 RAG 检索

---

## 1. 定位与启动条件

阶段三/四完成了"Agent 核心运行时"（编排、协同、沙箱、HITL、Workflow Studio）。阶段五补"中台"属性的最后两块拼图：

- **运营闭环**：Badcase → 评测 → 调优 → 灰度 → 再评测，让平台效果可量化、可迭代；
- **开放输出**：开放 API / SDK / 渠道嵌入，让平台能力被业务系统真正集成。

| 条件 | 说明 |
|------|------|
| 阶段四检查门（含 4.11）通过 | Prompt Catalog draft/published + rollback 可用 |
| 业务阈值（任一） | 需向业务方交付集成 API；租户/模型成本需分摊；对话 Badcase 靠人工翻日志；工具数 >50 导致 ReAct 工具注入膨胀；需按场景控制模型成本 |

---

## 2. 任务总览

| 任务卡 | 摘要 | 触发 | 优先级 |
|--------|------|------|:------:|
| **5.1** | 对话域 Badcase 闭环 + 效果报表：标注 → 回流评测 → 趋势看板 | 运营人工翻日志定位问题 | **高** |
| **5.2** | 用量计量与租户配额：token 落库聚合 + 配额校验 + 用量页 | 成本分摊/限额诉求 | **高** |
| **5.3** | 多模型场景路由：场景→模型池策略表 + 成本/时延权重 | 模型成本/效果分层 | 中 |
| **5.4** | Optimizer 优化智能体 MVP：评测→建议→draft→灰度→复评闭环 | 提示词/检索策略持续调优 | 中 |
| **5.5** | 工具语义检索（tool RAG）：工具描述向量索引 + Top-K 注入 | 工具规模膨胀 | 中 |
| **5.6** | 开放 API + API Key：对外 chat/chat-completions + 配额 | 业务系统集成 | **高** |
| **5.7** | Prompt 版本灰度发布：百分比分流 + 指标对比 | 提示词安全迭代 | 中 |
| **5.8** | 渠道嵌入（企微/Web widget） | 终端触达 | 低（按需） |
| **5.9** | 组织分级（部门层级/数据权限） | 集团型客户 | 低（按需） |
| **5.10** | ASR/TTS 接入 | 语音场景 | 低（按需） |
| — | 通用 A/B 实验平台 | — | **明确不做**（5.7 仅做 prompt 版本灰度；检索策略对比走 rag-service 评测双轨，不建通用实验框架） |
| — | 多 Agent 通用通信总线（广播/点对点消息中间件） | — | **明确不做**（维持委派/会诊/编排三范式，见 §7 决策 D3） |

**建议顺序**：5.1 → 5.2 → 5.6 → 5.3 → 5.7 → 5.4 → 5.5 →（5.8/5.9/5.10 按需）

> 顺序依据：5.1/5.2 复用现有数据链路（审计 + `LlmIoTracer`），改动小收益直接；5.6 是对外交付前提；5.3/5.7 为 5.4 提供灰度与路由底座；5.4 依赖 5.1（Badcase 回流）+ 5.7（灰度）；5.5 在工具规模上来后再做不迟。

---

## 3. 任务详设

### 5.1 对话域 Badcase 闭环 + 效果报表

> **现状**：Badcase 仅在 RAG 评测域（`EvalReportPersister` / `EvalSuggestContextBuilder` 的 positive_miss/negative_fp + LLM Suggest）；对话域（ReAct/Workflow/peer）全靠人工翻 `chat_audit_log`（`11-sunshine-orchestrator.sql`）。
> **目标**：对话级反馈标注 → 分类归因 → 回流 RAG golden-set / Prompt 优化输入 → 效果趋势看板。

| 子任务 | 内容 |
|--------|------|
| **5.1.1** | `chat_message_feedback` 表（`11-sunshine-orchestrator.sql` 追加，禁 Flyway）：message_id / user_id / tenant_id /  thumbs(up\|down) / reason_code（检索错误\|答非所问\|工具失败\|幻觉\|时延\|其他）/ comment / trace 快照（execution_mode、plan_id、tool_calls、model）/ created_at |
| **5.1.2** | Chat 前端消息气泡 👍/👎 + 原因选择弹层（复用现有 Codex 简约风格，`--sun-*` 变量） |
| **5.1.3** | `GET /api/ops/badcases` 列表 API（orchestrator）：按租户/模式/原因/时间过滤，关联 `chat_audit_log` 取工具调用明细 |
| **5.1.4** | RAG 类 Badcase 一键回流：按钮 → `POST /api/rag/feedback`（复用 4.1.6 链路）入 golden-set 待审 |
| **5.1.5** | `/ops` 运营页（sunshine-ui 新增视图）：Badcase 列表 + 分布图（按原因/模式/模型）+ 周趋势 |
| **5.1.6** | 效果指标聚合：问答好评率、工具调用成功率、P95 时延按日聚合（读审计 + 5.2 用量表） |

**检查门**：对话点踩 → `/ops` 可见且 trace 完整；RAG 类回流 golden-set 后可被 `rag_eval.py` 收录；好评率/工具成功率趋势可在页面按周对比。

### 5.2 用量计量与租户配额

> **现状**：`ChatCompletionResponse.Usage`（prompt/completion/total tokens）在 adapter 层已有，仅打日志（`QwenAdapter`/`DeepSeekAdapter`），未落库。
> **目标**：token 用量全量落库 → 按租户/模型/会话聚合 → 配额校验 → 用量页。

| 子任务 | 内容 |
|--------|------|
| **5.2.1** | `TokenUsageCollector`（llm-gateway）：非流式直接取 `usage`；流式从末尾 chunk `usage` 提取，缺失时按 messages 估算（标记 `estimated=true`） |
| **5.2.2** | 写 RocketMQ topic `llm-usage`（复用现有 MQ 基建，与审计同模式）；消费端 `llm_usage_record` 落 MySQL（新增 `19-sunshine-ops.sql`，一项目一文件） |
| **5.2.3** | 聚合任务（xxl-job，复用 `03-xxl-job-tables.sql` 基建）：小时/日 级 `llm_usage_daily`（tenant/model/mode → tokens、calls、成本估算） |
| **5.2.4** | 租户配额表 `tenant_quota`（月 token 上限/模型白名单）+ llm-gateway 请求前校验切面（超限 429 + 明确错误码） |
| **5.2.5** | `/ops` 用量 Tab：租户×模型用量排行、日趋势、成本估算（每模型 单价配置存 Nacos 非提示词参数） |

**检查门**：一次 ReAct 对话后 `llm_usage_record` 有全链路各次调用记录（含 tool 循环内多次 LLM 调用）；超限租户收到 429；用量页数据与记录一致。

### 5.3 多模型场景路由

> **现状**：`ModelRouter`（llm-gateway）仅"点名 model → 厂商适配器 + 失败降级/熔断"；无场景感知。
> **目标**：请求不指定模型时，按场景标签从模型池选模型（成本/时延/能力权重）。

| 子任务 | 内容 |
|--------|------|
| **5.3.1** | `model_route_policy` 表（`19-sunshine-ops.sql`）：scene（chat\|plan\|tool-call\|rewrite\|summarize\|subagent）→ 模型池（按优先级 + 权重）+ 约束（max_cost_per_1k、max_latency_ms） |
| **5.3.2** | orchestrator 在 `ChatCompletionRequest` 注入 `scene` 扩展字段（来源：ExecutionDispatcher 模式 + 调用点，如 QueryRewriteService=rewrite、Planner=plan）；BFF/Gateway 透传，客户端不得自填（同 `x-user-id` 约定） |
| **5.3.3** | `ModelRouter` 扩展：`model=auto` 或缺省时查策略表选模型，选中结果写 trace 头便于观测；保留显式指定 model 直路由 + 现有降级链 |
| **5.3.4** | `/tools` 或 `/ops` 增加路由策略编辑页（复用 `execution_mode_policy` 编辑模式） |
| **5.3.5** | Grafana 面板：scene × model 的调用量/时延/成本（接 5.2 数据） |

**检查门**：`model=auto` 时 rewrite 请求路由到轻量模型、plan 请求路由到强模型（策略表驱动）；改策略表热生效；显式 model 行为不回归（`phase2_agent_demo.py --suite all` PASS）。

### 5.4 Optimizer 优化智能体 MVP

> **现状**：最接近的是 RAG 评测 `EvalSuggestContextBuilder`（LLM 输出调参建议，人工采纳）。
> **目标**：对"提示词 + 检索策略"的半自动迭代闭环：Badcase/评测输入 → 优化建议 → 生成 draft 版本 → 人工确认 → 灰度 → 复评对比。
> **边界**：MVP **不做**全自动无人值守调优；每次发布必须人工确认（沿用 HITL 哲学）。

| 子任务 | 内容 |
|--------|------|
| **5.4.1** | `OptimizationRun` 编排（rag-service 复用 `EvalFullRunOrchestrator` 模式）：输入 = Badcase 集（5.1.3）+ 最近一次评测报告；调用 Suggest LLM 生成优化提案（prompt 修改 diff / 检索参数调整） |
| **5.4.2** | 提案落 `optimization_proposal` 表 + `/ops` 优化 Tab：提案列表、diff 预览、采纳/驳回 |
| **5.4.3** | 采纳动作：prompt 类 → prompt-manager 创建 **draft 版本**（复用 4.11 Catalog API）；检索参数类 → kb 配置 **draft**（复用 4.1 配置版本链） |
| **5.4.4** | 复评：draft 跑评测门禁（`POST /api/kb/{kbId}/evaluate` / prompt dry-run golden-set），报告对比 active 基线 |
| **5.4.5** | 达标后人工一键 publish（prompt 走 5.7 灰度；kb 配置走 active 切换） |

**检查门**：从一批真实 Badcase 出发，Optimizer 产出可解释提案 → draft → 评测报告含 vs 基线对比 → 人工发布；全链路在 `/ops` 可追踪。

### 5.5 工具语义检索（tool RAG）

> **现状**：ReAct 工具注入 = 工具集全量 schema 注入 prompt（`DynamicToolkitFactory`）；工具规模膨胀将推高 token 成本与选择错误率。
> **目标**：工具描述建 Milvus 索引，ReAct 每轮按 query+上下文检索 Top-K 工具注入。

| 子任务 | 内容 |
|--------|------|
| **5.5.1** | tool-manager：`ToolEmbeddingIndexer`——工具 Catalog（name+description+参数摘要）向量化入 Milvus collection `tool_index`（复用 rag-service embedding 通道，租户隔离 namespace） |
| **5.5.2** | Catalog 变更事件（已有 Redis `catalog-changed` 频道）触发增量重建索引 |
| **5.5.3** | orchestrator `ToolSetResolver` 增加 `retrieval` 模式：ReAct 首轮按 query 检索 Top-K（默认 8），后续轮次按 think 上下文增量补充；`full` 模式保留兼容小工具集 |
| **5.5.4** | HITL/白名单工具（require_confirmation、sandbox、manage 类元工具）始终注入，不参与检索过滤 |
| **5.5.5** | 评测：构造工具选择 golden-set（query → 期望工具），门禁 = 检索命中率 ≥ 基线 & ReAct 任务成功率不回退 |

**检查门**：工具集 50+ 时 ReAct 首轮注入工具数 ≤10；golden-set 工具命中率 ≥0.9；`verify_tool_integration_live.py --suite all` + spawn/沙箱/HITL Live 不回退。

### 5.6 开放 API + API Key

> **现状**：BFF 接口面向自家前端 + Sa-Token 会话；无第三方集成通道。
> **目标**：业务系统以 API Key 调用对话能力（SSE 流式 + 非流式），带租户/配额/审计。

| 子任务 | 内容 |
|--------|------|
| **5.6.1** | `api_key` 表（`19-sunshine-ops.sql`）：key_hash / tenant_id / name / scopes（chat\|rag-search\|workflow-run）/ 状态 / 过期时间；auth-center 管理 API + `/ops` 密钥管理 Tab |
| **5.6.2** | Gateway 过滤器：`Authorization: Bearer sk-*` 校验 → 注入 `x-user-id`（apikey 体系）+ tenant，与 Sa-Token 链路并存（客户端不得自填 `x-user-id` 约定不变） |
| **5.6.3** | 开放端点（gateway 新增路由，直转 orchestrator，**不经 BFF**）：`POST /open/v1/chat/completions`（SSE，对齐 OpenAI 语义子集）、`POST /open/v1/rag/search`、`POST /open/v1/workflows/{id}/run` |
| **5.6.4** | 配额联动 5.2.4（key 维度限流：Sentinel 规则按 appkey）；调用全量入审计（`caller_type=apikey`） |
| **5.6.5** | 接入文档 + curl/HTTP 示例（docs/open-api/README.md） |

**检查门**：外部脚本以 sk-* 调通 SSE 对话（五模式至少 react + workflow 模板）；无效/过期 key 401；超配额 429；审计可按 key 检索。

### 5.7 Prompt 版本灰度发布

> **现状**：4.11 Catalog 为 draft/published 两态 + rollback；无百分比分流。
> **目标**：published 主版本 + canary 版本按百分比分流，指标对比后全量或回滚。
> **边界**：**仅 prompt 版本灰度，不建通用 A/B 平台**（决策 D1）。

| 子任务 | 内容 |
|--------|------|
| **5.7.1** | Catalog 增加 `canary_version` + `canary_percent`（0–100）；`PromptComposer` 按 conversation_id 哈希稳定分流 |
| **5.7.2** | 每次 LLM 调用在 5.2 用量记录 + 审计中打 `prompt_version` 标签 |
| **5.7.3** | `/prompts` 页：灰度发布操作 + canary vs 主版本指标对比（好评率来自 5.1、时延/tokens 来自 5.2） |
| **5.7.4** | 一键全量（canary → published）/ 一键回滚（复用现有 rollback） |

**检查门**：10% 灰度时约 10% 会话走 canary 且同会话稳定；对比页指标正确；全量/回滚行为与 `verify_prompt_catalog_live.py` 门禁兼容。

### 5.8 渠道嵌入（按需 · 低）

| 子任务 | 内容 |
|--------|------|
| **5.8.1** | Web embed widget（独立轻量构建产物，iframe/script 嵌入，走 5.6 开放 API） |
| **5.8.2** | 企业微信应用消息回调适配（auth-center 绑定企微 userid ↔ 平台 user） |

### 5.9 组织分级（按需 · 低）

| 子任务 | 内容 |
|--------|------|
| **5.9.1** | `dept` 维度：租户下部门树 + 用户归属；知识库 namespace 启用 `dept` 预留位（4.1.1 已预留） |
| **5.9.2** | 数据权限：知识库/会话/报表按部门可见性过滤 |

### 5.10 ASR/TTS（按需 · 低）

| 子任务 | 内容 |
|--------|------|
| **5.10.1** | llm-gateway 增加语音适配（DashScope ASR/TTS）：`/open/v1/audio/transcriptions` + Chat 语音输入 |

---

## 4. 阶段五检查门（汇总）

- [ ] 5.1：对话 👎 → `/ops` Badcase 可见且 trace 完整；RAG 类回流 golden-set 生效
- [ ] 5.2：token 用量落库 = 实际调用（误差可解释）；超限 429；用量页准确
- [ ] 5.3：`model=auto` 按场景路由；显式 model 不回归（`phase2_agent_demo.py --suite all` PASS）
- [ ] 5.4：Badcase → 提案 → draft → 复评对比 → 人工发布全链路走通
- [ ] 5.5：50+ 工具时注入 ≤10 且命中率 ≥0.9；工具 Live 套件不回退
- [ ] 5.6：sk-* 调通 SSE + 401/429 边界 + 审计可查
- [ ] 5.7：10% 灰度稳定分流 + 对比指标 + 全量/回滚
- [ ] 回归：`phase2_agent_demo.py --suite all`、spawn/沙箱/HITL/peer/expert Live 全绿、orchestrator 单测全绿

---

## 5. 库表与模块变更索引

| 变更 | 位置 |
|------|------|
| `chat_message_feedback` | `docker/mysql/init/11-sunshine-orchestrator.sql` 追加 |
| `19-sunshine-ops.sql`（新建）：`llm_usage_record` / `llm_usage_daily` / `tenant_quota` / `model_route_policy` / `api_key` / `optimization_proposal` | `docker/mysql/init/`（一项目一文件，禁 Flyway） |
| 前端新页 `/ops`（Badcase/用量/密钥/优化 Tab）+ 路由策略编辑 | `sunshine-ui/src/views/OpsView.vue`（Codex 简约风格，`--sun-*` 变量） |
| llm-gateway | `TokenUsageCollector`、MQ 生产者、配额切面、`ModelRouter` 场景路由 |
| orchestrator | Badcase API、`scene` 注入、`ToolSetResolver` retrieval 模式、`PromptComposer` 灰度分流 |
| 新 Nacos 配置 | 模型单价、限流规则（非提示词参数，走 `docs/nacos/*.yaml` + `sync_nacos.py`） |

---

## 6. 风险与依赖

| 风险 | 缓解 |
|------|------|
| 5.2 流式 usage 缺失导致计量偏差 | 估算标记 `estimated` + 定期对账；优先选支持 stream usage 的厂商参数 |
| 5.5 工具检索漏召回导致 ReAct 能力回退 | HITL/元工具白名单必注入 + golden-set 门禁 + `full` 模式可回切 |
| 5.7 分流不稳定导致指标不可比 | conversation_id 哈希稳定分流；同会话不换版本 |
| 5.4 自动优化引入回归 | MVP 强制人工确认发布；提案必须附复评对比报告 |
| 开放 API 鉴权攻击面扩大 | key 哈希存储、scope 最小化、Sentinel 按 key 限流、全量审计 |

---

## 7. 决策记录

- **D1（不做通用 A/B 平台）**：阶段四 4.1.7 已决策检索策略 A/B 不做；阶段五沿用——prompt 走 5.7 轻量灰度，检索策略走 rag-service 评测双轨，不建通用实验框架（2-3 人团队维护不起）。
- **D2（5.6 不经 BFF）**：开放端点在 gateway 直转 orchestrator，BFF 保持纯前端服务定位，避免双份会话逻辑。
- **D3（不做通用多 Agent 通信总线）**：委派（spawn_subagent）/ 会诊（peer-collab）/ 编排（workflow）三范式已覆盖当前场景；通用消息总线在出现真实"数十 Agent 自由组网"需求前不预建。
- **D4（Optimizer 半自动）**：MVP 每次发布必须人工确认，与平台 HITL 哲学一致；全自动调优待 5.4 闭环稳定后再评估。
- **D5（AS2 遗留先行）**：启动 5.x 前需先人工验收 AS2 迁移遗留项（e2e 3 例选择器漂移修复、ReAct 停→续跑 / kill-15 重启恢复交互式验收）。
