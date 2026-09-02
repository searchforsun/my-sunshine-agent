# 阶段五：运营化与开放化 — 技术设计（SSOT）

> **周期**：按需启动（子项独立排期）
> **状态**：⬜ 规划（2026-07-27 立项）· **v2（2026-08-01）**：以 harness 长任务为终点重定位；5.2/5.3/5.5 随 harness 上线**前置拆分触发**（不必等阶段四全收口），5.1/5.4/5.7 等 harness 稳定后接 · **v8（2026-08-02 · 历史）**：曾扩展 `plan-phase`——**已由 [rebuild S5 v4](./2026-08-05-planner-executor-rebuild-design.md) 作废**（统一 `call_scene=plan`）· **v9（2026-08-10）**：汇聚点改为 [planner-executor-rebuild](./2026-08-05-planner-executor-rebuild-design.md)；原 [harness](./archive/2026-07-31-planner-harness-loop-design.md) 已归档 · **5.2 阶段一 ✅（2026-08-27）**：token 落库闭环（见 §3 5.2 注记）· **5.2 阶段二 ✅（2026-08-27）**：日聚合 + 配额 429 + 用量页（见 §3 5.2 注记）· **5.3 多模型场景路由 ✅（2026-08-27）**：`model=auto` 按 `call_site` 策略路由 + 用量/缓存按实际生效模型（见 §3 5.3 注记；5.3.5 Grafana 面板仍待接）· **5.5 工具语义检索 ✅（2026-08-27）**：工具目录 Milvus 索引 + retrieval 分层注入（Tier 0 名列表 + 每轮 Top-K schema），Nacos `tool-inject.mode` 二选一（见 §3 5.5 注记；5.5.5 golden-set 评测待接）
> **触发**：① 阶段四收口 + 平台需对外交付/量化运营效果（全量）② **5.2/5.3/5.5 随 harness 上线前置**（见 §1 触发拆分）
> **前置**：[阶段四](./phase4-platformization-design.md) 4.1/4.2/4.5/4.6/4.7/4.8/4.13 检查门通过；**4.11 Prompt 后台收口**（5.3/5.4 依赖其 Catalog 版本模型）；5.1 系列在 **4.14 / 长任务可评估** 后启动（原「CompletionGuard 前提」随 [4.7.8 归档](./archive/2026-07-28-harness-loop-enhancement-design.md) 取消；可选门禁见 [goal-alignment §12](./2026-07-27-react-goal-alignment-design.md)）
> **对标缺口**：智能体中台蓝图 §5 运营管控与观测层、§6 应用输出层、§1 多模型混部路由、§4 工具 RAG 检索

---

## 1. 定位与启动条件

阶段三/四完成了"Agent 核心运行时"（编排、协同、沙箱、HITL、Workflow Studio）。阶段五补"中台"属性的最后两块拼图：

- **运营闭环**：Badcase → 评测 → 调优 → 灰度 → 再评测，让平台效果可量化、可迭代；
- **开放输出**：开放 API / SDK / 渠道嵌入，让平台能力被业务系统真正集成。

**v2 / v9 重定位（以 4.14 Planner-Executor 长任务为终点）**：[rebuild](./2026-08-05-planner-executor-rebuild-design.md)（`executionMode=pro`）是横跨沙箱/ReAct/路由/上下文的汇聚点，运行期依赖 **5.2 用量计量**、**5.3 场景路由**（Planner 强 / Worker 快，**不**绑 `plan-phase`）、**5.5 工具检索**（Planner 检索 → 下发 toolWhitelist）。三块随 4.14 上线前置启动；5.1/5.4/5.7 等稳定后接。

**触发拆分**：

| 批次 | 子项 | 触发 |
|------|------|------|
| A（随 harness 前置） | 5.2 / 5.3 / 5.5 | planner-harness 上线前完成（提供成本 / 模型分层 / 工具动态底座） |
| B（长任务可评估后） | 5.1 / 5.4 / 5.7 | 4.14 / ReAct 长任务有稳定样本后（不绑 CompletionGuard） |
| C（按需） | 5.6 / 5.8 / 5.9 / 5.10 | 对外交付诉求 |

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

**建议顺序**：A 批次（随 harness 前置）：**5.2 → 5.3 → 5.5**；B 批次（harness 稳定后）：**5.1 → 5.7 → 5.4**；C 批次（按需）：**5.6** →（5.8/5.9/5.10 按需）

> 顺序依据（v2）：A 批次是 harness 运行期底座——5.2 计量（长任务多次 LLM 调用成本可控）、5.3 模型分层路由（Planner 强/Worker 快）、5.5 工具检索（Worker toolWhitelist 动态化），随 harness 上线前置；B 批次在 harness 效果可评估后接——5.1（Badcase 回流 harness task 级成功率）、5.7（灰度底座供 5.4）、5.4（依赖 5.1 + 5.7）；5.6 对 harness 弱相关，按需。
> 原顺序依据（5.1 → 5.2 → 5.6 → 5.3 → 5.7 → 5.4 → 5.5）在 v2 触发拆分后不再作为严格排期，仅作 B/C 批次内部参考。

---

## 3. 任务详设

### 5.1 对话域 Badcase 闭环 + 效果报表

> **现状**：Badcase 仅在 RAG 评测域（`EvalReportPersister` / `EvalSuggestContextBuilder` 的 positive_miss/negative_fp + LLM Suggest）；对话域（ReAct/Workflow/peer）全靠人工翻 `chat_audit_log`（`11-sunshine-orchestrator.sql`）。
> **目标**：对话级反馈标注 → 分类归因 → 回流 RAG golden-set / Prompt 优化输入 → 效果趋势看板。

| 子任务 | 内容 |
|--------|------|
| **5.1.1** | `chat_message_feedback` 表（`11-sunshine-orchestrator.sql` 追加，禁 Flyway）：message_id / user_id / tenant_id /  thumbs(up\|down) / reason_code（检索错误\|答非所问\|工具失败\|幻觉\|时延\|其他）/ comment / trace 快照（execution_mode、plan_id、tool_calls、model）/ created_at。**v2 新增 `run_id` + `round_id`**（harness 多轮 Planner-Worker 定位维度，见下） |
| **5.1.2** | Chat 前端消息气泡 👍/👎 + 原因选择弹层（复用现有 Codex 简约风格，`--sun-*` 变量） |
| **5.1.3** | `GET /api/ops/badcases` 列表 API（orchestrator）：按租户/模式/原因/时间过滤，关联 `chat_audit_log` 取工具调用明细 |
| **5.1.4** | RAG 类 Badcase 一键回流：按钮 → `POST /api/rag/feedback`（复用 4.1.6 链路）入 golden-set 待审 |
| **5.1.5** | `/ops` 运营页（sunshine-ui 新增视图）：Badcase 列表 + 分布图（按原因/模式/模型）+ 周趋势 |
| **5.1.6** | 效果指标聚合：问答好评率、工具调用成功率、P95 时延按日聚合（读审计 + 5.2 用量表） |

> **v2 注记（harness 计量粒度）**：harness 一条 assistant message 内部是多轮 Planner-Worker（含 Worker handoff），`plan_id` 语义是 Plan-Workflow 的 plan，**不是** Planner-Harness 的 run。一个点踩无法定位「哪一轮的哪个 Worker 出问题」。为此：
>
> 1. `chat_message_feedback` 增加 `run_id`（harness run 标识，普通 ReAct 为空）+ `round_id`（run 内轮次，Planner-Worker round 序号；普通对话为空）。
> 2. **自判结果落库**：harness 采用 Planner 自判（[简化决议 S1](./2026-08-05-planner-executor-rebuild-design.md#01-简化决议v2--2026-08-05) 砍独立 Evaluator）——Planner `selfAssess` 的 task 结果（PASS/FAIL + reason）写入 `harness_eval_result`（`run_id` + task + PASS/FAIL + reason，随 feedback 表同库），供 5.1.6 按 run 聚合 task 级成功率——这是 harness 效果可视化的唯一来源。字段语义保留，数据来源由 Evaluator 变为 Planner 自判。
>
> 字段在 phase5 阶段即定死，harness 落地时直接写入，避免事后 ALTER。

**检查门**：对话点踩 → `/ops` 可见且 trace 完整；RAG 类回流 golden-set 后可被 `rag_eval.py` 收录；好评率/工具成功率趋势可在页面按周对比。

### 5.2 用量计量与租户配额

> **现状**：`ChatCompletionResponse.Usage`（prompt/completion/total tokens）在 `OpenAiCompatibleAdapter` 层已有，仅打日志，未落库。（旧 `QwenAdapter`/`DeepSeekAdapter` 已合并删除）
> **目标**：token 用量全量落库 → 按租户/模型/会话聚合 → 配额校验 → 用量页。
> **阶段一 ✅（2026-08-27）**：token 落库闭环已落地——5.2.1 `TokenUsageCollector`（非流式直接取 usage【修复 `Usage` 的 `@JsonProperty` snake_case 映射，原反序列化恒 null】；流式从末尾 chunk 根级 usage 提取、缺失按 messages+流式字符估算）+ 5.2.2 MQ 生产（llm-gateway topic=`llm-usage`，RocketMQ v5 proxy）/ orchestrator 消费落库 `llm_usage_record` + 查询端点 `GET /api/usage/records`、`GET /api/usage/summary`（按 model 聚合）。维度字段 `call_site`/`run_id`/`round_id`/`user_id` 已在表与 MQ 消息预留（阶段一为 null，5.3 链路透传后填充）。
> **阶段二 ✅（2026-08-27）**：5.2.3 `UsageDailyAggregationJob`（orchestrator @Scheduled 5min，删除重建幂等，按 `DATE(request_at)`/tenant/model/call_site 聚合 `llm_usage_daily`，est_cost 按 Nacos `sunshine.llm-usage.price` 模型单价估算，未配置为 0）+ `GET /api/usage/daily`；5.2.4 `tenant_quota`（orchestrator 管理 CRUD `/api/usage/quota` + 校验单点 `/check`：模型白名单 `model_not_allowed` / 月 token 上限聚合当月 `llm_usage_record` `quota_exceeded`）+ llm-gateway 请求前校验（`QuotaCheckClient` 30s TTL 缓存、fail-open，`llm.usage.quota.enabled` 默认 false 热切，超限 429 OpenAI 兼容 `error{code}`）；5.2.5 `/ops` 用量页（BFF 透传 + `OpsView` 用量/配额双 Tab：统计卡 + 模型排行 + 日趋势 + 配额管理）。**后置**：5.3 `call_site` 链路透传后日聚合与配额按调用点细化。单测 orchestrator 1403 + llm-gateway 38 全绿；Live：summary/daily estCost 正确、白名单外 429 `model_not_allowed`、月度超限 429 `quota_exceeded`（monthlyUsed 真实聚合）、开关关恢复放行。

| 子任务 | 内容 |
|--------|------|
| **5.2.1** | `TokenUsageCollector`（llm-gateway）：非流式直接取 `usage`；流式从末尾 chunk `usage` 提取，缺失时按 messages 估算（标记 `estimated=true`）· **✅** |
| **5.2.2** | 写 RocketMQ topic `llm-usage`；消费端 `llm_usage_record` 落 MySQL（`19-sunshine-ops.sql` → 实落 11 号）。记录 **`call_site`**（旧称 call_scene；来自 5.3）+ `run_id`/`round_id` · **✅** |
| **5.2.3** | 聚合任务：小时/日级 `llm_usage_daily`（tenant/model/`call_site` → tokens、calls、成本估算）· **✅**（`UsageDailyAggregationJob` 5min 删除重建 + `GET /api/usage/daily`） |
| **5.2.4** | 租户配额表 `tenant_quota`（月 token 上限/模型白名单）+ llm-gateway 请求前校验切面（超限 429 + 明确错误码）· **✅**（orchestrator CRUD + `/check`，llm-gateway `QuotaCheckClient` TTL 缓存 fail-open） |
| **5.2.5** | `/ops` 用量 Tab：租户×模型用量排行、日趋势、成本估算；加 **call_site × run** 维度 · **✅**（`OpsView` 用量/配额双 Tab；call_site 维度待 5.3 透传） |

**检查门**：一次 ReAct 对话后 `llm_usage_record` 有全链路各次调用记录（含 tool 循环内多次 LLM 调用）· ✅（阶段一 Live）；超限租户收到 429 · ✅（Live 白名单外/月度超限均 429 + 明确错误码）；用量页数据与记录一致 · ✅（daily/summary 与记录同源聚合）。

### 5.3 多模型场景路由

> **现状**：`ModelRouter`（llm-gateway）仅"点名 model → 厂商适配器 + 失败降级/熔断"；无场景感知。
> **目标**：请求不指定模型时，按场景标签从模型池选模型（成本/时延/能力权重）。
> **实现注记 ✅（2026-08-27）**：`call_site` 七枚举 SSOT = `CallSiteKey`（sunshine-common，禁止自定义）· 策略表 `model_route_policy` 落 resource-manager（模型注册表 SSOT，`20-sunshine-model-registry.sql`）· 透传链路：Agent 经 `LoadBalancedWebClientTransport` 按 `AgentRole` 注入（MAIN→chat/SUB→subagent/PLANNER→plan/WORKER→worker）、`LlmGatewayClient` 内部辅助默认 summarize、IntentRouter=rewrite · `ModelRouter.resolveEffectiveModel`：显式 model 直路由，`model=auto` 按 call_site 查池选首个 enabled，生效模型回写请求（用量计量/语义缓存按实际模型）· 热更新经 Redis `model-catalog-changed`（同 5.2 机制）· 语义缓存隔离：auto 请求不入缓存 + key 含 call_site · 前端 `/models` 增「路由策略」Tab（BFF `/api/models/routes` 透传）。Live `verify_model_route_live.py` R1–R7 全绿。

| 子任务 | 内容 |
|--------|------|
| **5.3.1** | `model_route_policy` 表（`20-sunshine-model-registry.sql`）：主键列 **`call_site`**（旧稿 `call_scene`；取值 chat\|plan\|worker\|tool-call\|rewrite\|summarize\|subagent；**无** plan-phase/evaluator）→ 模型池 + 约束。与会话形态 **`kind`**、业务域 `biz_scene` 隔离（见 [routing 命名四轴](./2026-07-29-unified-routing-design.md)）· **✅**（resource-manager JPA CRUD + catalog routes；`CallSiteKey` 枚举 SSOT） |
| **5.3.2** | orchestrator 在 `ChatCompletionRequest` 注入 **`callSite`**（JSON 亦可 `call_site`；来源：调用点，如 rewrite / plan / worker / self-assess）；BFF/Gateway 透传，客户端不得自填。过渡期可读旧键 `call_scene` · **✅**（AgentScope transport 按角色注入 + `LlmGatewayClient` 默认 summarize + IntentRouter rewrite；消费端 `TokenUsageCollector` 从 request 读取） |
| **5.3.3** | `ModelRouter` 扩展：`model=auto` 或缺省时查策略表选模型，选中结果写 trace 头便于观测；保留显式指定 model 直路由 + 现有降级链 · **✅**（`resolveEffectiveModel` 回写生效模型；auto 无策略 400 明确报错） |
| **5.3.4** | `/tools` 或 `/ops` 增加路由策略编辑页（复用 `execution_mode_policy` 编辑模式）· **✅**（`/models` 增「路由策略」Tab：列表/新建/编辑/启停/删除，BFF 透传） |
| **5.3.5** | Grafana 面板：`call_site` × model 的调用量/时延/成本（接 5.2 数据）· **⏳ 待接**（数据已具备：`llm_usage_record.call_site` + 单价） |

> **v2 注记（命名隔离 · 2026-08-13 更新）**：会话形态用 **`kind`**（chat/task；旧 `RoutingResult.scene` 废弃）；llm-gateway 模型路由用 **`callSite` / `call_site`**（旧 `call_scene` 废弃）。业务域用 `biz_scene`。三者 + `executionMode` 硬隔离。**禁止**复用任一字段承载另一轴语义。
>
> **v2 注记（harness 模型分层）**：harness 有 4 类 LLM 调用——Planner（=plan，强模型）、Worker（**forWorker 内部多次 LLM 调用**，中等快模型）、Evaluator（Chat 模式独立 LLM，快模型）、普通 tool-call。5.3.1 枚举扩展 `worker`/`evaluator` 后，策略表可配置「Planner → 强模型、Worker → 快模型」，实现 harness 的模型成本分层；否则 Worker 只能沿用 `plan` 场景，无法按成本分流。
>
> **v9 注记（S1/S5 修正 harness 模型分层）**：[简化决议 S1](./2026-08-05-planner-executor-rebuild-design.md#01-简化决议v2--2026-08-05) 砍独立 Evaluator——调用点收敛为 **Planner（=plan，强模型）、Worker（=worker，快模型）、阶段细拆（=plan-phase，快模型）、Planner 自判（=plan，与规划同调用点）**。策略表配置「Planner → 强模型、Worker/plan-phase → 快模型」即可覆盖 harness 全部调用；`evaluator` 枚举不建。
>
> **v9 注记（取代 v8 `plan-phase`）**：rebuild S5 v4 **不建** `callSite=plan-phase`；Planner 统一 `callSite=plan`。若需强弱模型分层，走本文件 5.3 策略表（按角色/负载），**不**绑分解模式。

**检查门**：`model=auto` 时 rewrite 请求路由到轻量模型、plan 请求路由到强模型（策略表驱动）；改策略表热生效；显式 model 行为不回归（`phase2_agent_demo.py --suite all` PASS）· ✅（Live `verify_model_route_live.py` R1 池首路由 / R2 显式不回归 / R3 auto 无策略 400 / R4 用量 call_site 落库 / R5 CRUD / R6 热更新 30s 内换序生效 / R7 auto 不入语义缓存；单测 llm-gateway 45 全绿）

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

> **v2 注记（优化面边界）**：Optimizer 的可优化面 = Catalog 管理的 prompt（含 `planner.harness`/`harness.worker`）+ 检索参数。H1 渲染与 run 内 compaction 阈值（五层 §4.5 / Nacos `agent.memory.auto-context`）是代码/配置，Optimizer 覆盖不到。MVP 不做「代码参数自调」。

**检查门**：从一批真实 Badcase 出发，Optimizer 产出可解释提案 → draft → 评测报告含 vs 基线对比 → 人工发布；全链路在 `/ops` 可追踪。

### 5.5 工具语义检索（tool RAG）

> **现状**：ReAct 工具注入 = 工具集全量 schema 注入 prompt（`DynamicToolkitFactory`）；工具规模膨胀将推高 token 成本与选择错误率。
> **目标**：工具描述建 Milvus 索引，ReAct 每轮按 query+上下文检索 Top-K 工具注入。
> **实现注记 ✅（2026-08-27）**：工具语义检索已落地（v2 分层注入方案全量）——**索引侧**（rag-service）：`ToolMilvusService`（Milvus collection `sunshine_tool_index`，`tool_id` 主键 + `tenant_id` 租户隔离 + embedding 向量，`replaceAll` 删后插 + **flush 强制落盘**保证 BOUNDED 一致性下 sync 返回即可检索）+ `ToolIndexService`（embedding 复用 `EmbeddingService`，minScore/默认 TopK 由 Nacos `rag.tool-index.*` 控制）+ `POST /api/tool-index/sync|search`；**注入侧**（orchestrator）：`ToolRetrievalService`（恒注入判定 = 内置元工具/沙箱/HITL `require_confirmation` 不入组；检索命中收敛到 (tenant, kind) 可检索集；目录内容指纹变化才全量重建索引；Tier 0 目录渲染确定性 id 排序）+ `ToolRetrievalMiddleware`（每轮 `onReasoning` 按最近 USER 消息检索 Top-K，`setActivatedGroups` 写入 `ToolContextState`——AgentScope 原生按激活组重算 schema）+ `DynamicToolkitFactory`（retrieval 模式下业务工具按 `tool:{id}` 组注册、默认 inactive，恒注入工具未分组始终可见）+ `ReActSystemPromptResolver`（MAIN + retrieval 时把 Catalog `context.tool-directory` 模板渲染的全量工具名目录追加进稳定前缀）+ `ReActAgentRuntime`（首轮 `presetInitialToolGroups` 预置激活组——AgentScope 首轮 schema 在 middleware 之前解析；ctx 注入 tenantId/conversationKind）。**开关**：Nacos `agent.execution.react.tool-inject.mode`（`full` 默认 / `retrieval`），检索失败或空结果按 `fallback-full` 回退全量注入。Live `verify_tool_retrieval_live.py` T1–T4 全绿（T1 直调 sync/search 命中+minScore 过滤 / T2 首次索引同步 + 每轮注入 Top-K / T3 指纹幂等 / T4 激活组仅业务工具）。

| 子任务 | 内容 |
|--------|------|
| **5.5.1** | tool-manager：`ToolEmbeddingIndexer`——工具 Catalog（name+description+参数摘要）向量化入 Milvus collection `tool_index`（复用 rag-service embedding 通道，租户隔离 namespace）· **✅**（落 rag-service `ToolMilvusService` + `ToolIndexService`，Nacos `rag.tool-index`） |
| **5.5.2** | Catalog 变更事件（已有 Redis `catalog-changed` 频道）触发增量重建索引 · **✅**（`ToolRetrievalService.ensureIndexSynced` 目录内容指纹变化 → 全量重建幂等；无目录变更不重复同步） |
| **5.5.3** | orchestrator `ToolSetResolver` 增加 `retrieval` 模式：**分层注入**——Tier 0 静态注入「全量工具名列表」（字节稳定，见下注记），Tier 2 尾部按 query 检索 Top-K（默认 8）注入详细 schema；`full` 模式保留兼容小工具集 · **✅**（`ToolRetrievalMiddleware` 每轮激活组 + `ReActSystemPromptResolver` Tier 0 目录；开关 `tool-inject.mode` 二选一） |
| **5.5.4** | HITL/白名单工具（require_confirmation、sandbox、manage 类元工具）始终注入，不参与检索过滤 · **✅**（`ToolRetrievalService.isAlwaysInject`：内置元工具/沙箱/HITL 未分组恒可见） |
| **5.5.5** | 评测：构造工具选择 golden-set（query → 期望工具），门禁 = 检索命中率 ≥ 基线 & ReAct 任务成功率不回退 · **⏳ 待接**（Live 已用报销 query 验证语义命中；golden-set 规模评测后续） |

> **v2 注记（与五层 Tier 0 的兼容，冲突解决）**：naive retrieval 模式（每轮注入动态 Top-K 工具 schema）会改变 `tools` 块字节 → 破坏五层 spec §5.5.3 的 Tier 0 绝对静态 → 全量 KV cache miss。本设计采用**分层注入**：
>
> - **Tier 0**：全量工具**名列表**（确定性序列化，字节稳定，仅名字+一行描述，对齐 task-scene §7.4 的 MCP 描述按需读策略）；
> - **Tier 2 尾部**：Top-K 工具的完整 schema（随 query/think 动态变化，放尾部只 miss 尾部小块）。
>
> 工具规模 ≤ 阈值（如 20）时 `full` 模式（全量 schema 进 Tier 0）仍可用——由 Nacos `agent.tool.inject` 模式开关切换，二选一不并存。
>
> **v2/v9 注记（Worker 检索基准）**：`pro` 下 Worker 的 `toolWhitelist` 由 **Planner 下发**（[rebuild §3.1.1](./2026-08-05-planner-executor-rebuild-design.md)），**不是** Worker 自检索。路径：**Planner 用 5.5 检索 → 下发 toolWhitelist**；Worker 不再二次检索。

**检查门**：工具集 50+ 时 ReAct 首轮注入工具数 ≤10 · ✅（retrieval 模式每轮按 `top-k` 默认 8 激活，Tier 0 仅名列表）；golden-set 工具命中率 ≥0.9 · ⏳（T1 报销 query 命中报销工具 + T2 对话注入 Top-K 含报销族；golden-set 规模评测 5.5.5 后续）；`verify_tool_integration_live.py --suite all` + spawn/沙箱/HITL Live 不回退 · ✅（full 模式默认；retrieval 失败回退全量）。

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

> **v2 注记（v1 范围）**：开放端点 SSE「对齐 OpenAI 语义子集」——harness 的 SSE 含 `subSteps`/node 事件/plan 阶段（OpenAI 语义之外），**v1 开放 API 仅暴露 react + workflow 模板**，harness/planner-harness 不进 v1；后续按需以扩展事件形式暴露。

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

> **v2 注记（灰度与前缀稳定性）**：canary 切换 = prompt 字节变化 → Tier 0/1 前缀一次性失效（低频，可接受）。但 **harness 长任务 run 内禁止切换**——5.7.1 的 conversation_id 哈希稳定分流已保证同会话不换版本；对 run 内多轮 Planner-Worker 同样成立（同一 conversation 全 run 走同版本）。5.7.2 的 `prompt_version` 标签写入 5.2 用量记录 + 审计，供 canary vs 主版本指标对比（对齐五层 spec §5.5.6 版本标签约束）。

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
- [x] 5.2（阶段一 ✅ 2026-08-27）：token 用量落库 = 实际调用（流式末尾 usage / 非流式 usage / 估算标记；Live 验证一次对话全链路各次调用均落库且 estimated=false）
- [x] 5.2（阶段二 ✅ 2026-08-27）：超限 429（Live 白名单外 `model_not_allowed` + 月度超限 `quota_exceeded`）；用量页准确（summary/daily estCost 与记录同源聚合）
- [x] 5.3（✅ 2026-08-27）：`model=auto` 按场景路由（Live R1 池首/rewrite→轻量、plan→强模型）；显式 model 不回归（R2）；策略热生效（R6）；用量 call_site 落库（R4）；CRUD（R5）
- [ ] 5.4：Badcase → 提案 → draft → 复评对比 → 人工发布全链路走通
- [ ] 5.5：50+ 工具时注入 ≤10 且命中率 ≥0.9；工具 Live 套件不回退
- [ ] 5.6：sk-* 调通 SSE + 401/429 边界 + 审计可查
- [ ] 5.7：10% 灰度稳定分流 + 对比指标 + 全量/回滚
- [ ] 回归：`phase2_agent_demo.py --suite all`、spawn/沙箱/HITL/peer/expert Live 全绿、orchestrator 单测全绿

---

## 5. 库表与模块变更索引

| 变更 | 位置 |
|------|------|
| `chat_message_feedback`（含 `run_id`/`round_id` v2） | `docker/mysql/init/11-sunshine-orchestrator.sql` 追加 |
| `harness_eval_result`（v2：task PASS/FAIL 落库；**v9 S1：数据源改为 Planner 自判**） | `docker/mysql/init/11-sunshine-orchestrator.sql` 追加 |
| `llm_usage_record`（含 `call_site`/`run_id`/`round_id`；**阶段一 ✅**，消费端 orchestrator 故落 11 号） | `docker/mysql/init/11-sunshine-orchestrator.sql` |
| `llm_usage_daily`（日聚合 + est_cost；**阶段二 ✅**，聚合任务在 orchestrator 故落 11 号） | `docker/mysql/init/11-sunshine-orchestrator.sql` |
| `tenant_quota`（月 token 上限/模型白名单；**阶段二 ✅**，管理端 orchestrator 故落 11 号） | `docker/mysql/init/11-sunshine-orchestrator.sql` |
| `model_route_policy`（`call_site` 主键 + 模型池 + 策略；**5.3 ✅**，管理端 resource-manager 模型注册表 SSOT 故落 20 号） | `docker/mysql/init/20-sunshine-model-registry.sql` |
| `19-sunshine-ops.sql`（待落地）：`api_key` / `optimization_proposal` | `docker/mysql/init/`（一项目一文件，禁 Flyway） |
| 前端新页 `/ops`（Badcase/用量/密钥/优化 Tab）+ 路由策略编辑 | `sunshine-ui/src/views/OpsView.vue`（Codex 简约风格，`--sun-*` 变量） |
| llm-gateway | `TokenUsageCollector`、MQ 生产者、配额切面、`ModelRouter`（按 `call_site`） |
| orchestrator | Badcase API、`callSite` 注入、`ToolSetResolver` retrieval 分层模式、`PromptComposer` 灰度分流 |
| 新 Nacos 配置 | 模型单价、限流规则、`agent.tool.inject` 模式开关（非提示词参数，走 `docs/nacos/*.yaml` + `sync_nacos.py`） |

---

## 6. 风险与依赖

| 风险 | 缓解 |
|------|------|
| 5.2 流式 usage 缺失导致计量偏差 | 估算标记 `estimated` + 定期对账；优先选支持 stream usage 的厂商参数 |
| 5.5 工具检索漏召回导致 ReAct 能力回退 | HITL/元工具白名单必注入 + golden-set 门禁 + `full` 模式可回切 |
| 5.5 动态 Top-K 破坏 Tier 0 前缀（v2） | 分层注入：工具名列表进 Tier 0 静态 + schema 进 Tier 2 尾部；≤阈值用 `full` 不并存 |
| 5.7 分流不稳定导致指标不可比 | conversation_id 哈希稳定分流；同会话/同 run 不换版本 |
| 5.4 自动优化引入回归 | MVP 强制人工确认发布；提案必须附复评对比报告 |
| 开放 API 鉴权攻击面扩大 | key 哈希存储、scope 最小化、Sentinel 按 key 限流、全量审计 |
| `call_scene` 与用户 `scene` 混用（v2） | **已决议改名**：`kind` + `callSite`；过渡读旧键后删除（§5.3.2 注记 / D6） |
| harness 计量粒度不足导致点踩无法归因（v2） | feedback/usage 预置 `run_id`+`round_id` + 自判结果落库（§5.1 注记；**v9 S1 数据源为 Planner 自判**） |

---

## 7. 决策记录

- **D1（不做通用 A/B 平台）**：阶段四 4.1.7 已决策检索策略 A/B 不做；阶段五沿用——prompt 走 5.7 轻量灰度，检索策略走 rag-service 评测双轨，不建通用实验框架（2-3 人团队维护不起）。
- **D2（5.6 不经 BFF）**：开放端点在 gateway 直转 orchestrator，BFF 保持纯前端服务定位，避免双份会话逻辑。
- **D3（不做通用多 Agent 通信总线）**：委派（spawn_subagent）/ 会诊（peer-collab）/ 编排（workflow）三范式已覆盖当前场景；通用消息总线在出现真实"数十 Agent 自由组网"需求前不预建。
- **D4（Optimizer 半自动）**：MVP 每次发布必须人工确认，与平台 HITL 哲学一致；全自动调优待 5.4 闭环稳定后再评估。
- **D5（AS2 遗留先行）**：启动 5.x 前需先人工验收 AS2 迁移遗留项（e2e 3 例选择器漂移修复、ReAct 停→续跑 / kill-15 重启恢复交互式验收）。
- **D6（kind / callSite 命名隔离，v2→2026-08-13）**：会话形态用 `kind`（废 `scene=chat|task`）；llm-gateway 模型路由用 **`callSite`/`call_site`**（废 `call_scene`）。避免同名字段两义；与 `biz_scene`、`executionMode` 四轴正交（见 [routing v6](./2026-07-29-unified-routing-design.md) 命名四轴）。
- **D7（5.5 工具分层注入，v2）**：工具名列表进 Tier 0 静态 + Top-K schema 进 Tier 2 尾部；`full`/`retrieval` 二选一不并存。对齐五层 spec §5.5.3 前缀稳定性。
- **D8（harness 计量维度，v2）**：feedback/usage 预置 `run_id`+`round_id`，task 评估结果落 `harness_eval_result`（**v9 S1：数据源为 Planner 自判，字段语义不变**）；phase5 阶段定死字段，harness 直接写入。
- **D9（phase5 触发拆分，v2）**：5.2/5.3/5.5 随 harness 前置启动，5.1/5.4/5.7 等 harness 稳定后接，5.6 按需。
- **D10（`plan-phase` · v8 历史 · v9 作废）**：原 HIERARCHICAL 细拆调用点；rebuild S5 v4 后 **不实现** `plan-phase`，统一 `callSite=plan`（强弱分层见 5.3）。
