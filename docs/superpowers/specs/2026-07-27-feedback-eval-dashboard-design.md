# 线上反馈闭环 + 分模式评测大盘 设计

日期：2026-07-27
状态：设计评审通过（待实施）
范围：P0 反馈闭环 + P1 归因增强/隐式负反馈 + P2 全模式评测集框架与回流

## 1. 背景与目标

线上反馈（点赞/点踩）、隐式负信号（重新生成/取消/追问）当前完全空白；线下评测仅覆盖 RAG 域（`rag_eval.py` + corpus-50）。本设计建立：

- **线上**：用户对每条 assistant 回答可 👍/👎（点踩带原因标签+补充），大盘页按执行模式分组统计好评率，实时查看踩样本的完整问答以便快速归因优化。
- **隐式**：自动采集重新生成 / 中途取消 / 重复追问为负信号。
- **线下**：按模式建端到端回归评测集（框架 + 种子集），踩样本审核后可回流为评测用例，形成「踩 → 归因 → 修复 → 回归防退化」闭环。

### 分组维度

执行模式（`ExecutionMode`）：`react` / `workflow` / `plan-workflow` / `peer-collab`。

### 非目标

- 不做 LLM-as-judge 自动打分（二期）。
- 不做评测门禁 CI 接入（二期，复用 rag_eval 门禁模式）。
- 不引入 ES 双写 / 事件流（YAGNI，方案 A MySQL 单表）。
- 反馈数据**不**走 RocketMQ（同步 DB 直写；详见 §5 决策）。

## 2. 现状盘点（已有资产）

| 能力 | 现状 |
|------|------|
| 线下评测 | 仅 RAG：`scripts/rag_eval.py` + `docs/knowledge/eval_suite.json`（Recall@K/MRR 门禁）；路由有 golden set 文档 |
| 审计 | `chat_audit_log`（message_id/conv/user/tenant/event_type/status/payload JSON）+ `/api/audit/*`；assistant 终态事件 `chat.message.completed` 经 `AuditService` → MQ |
| 执行模式 | `ExecutionMode` 枚举（4 值）；`chat_message.execution_mode` / `execution_preference` / `workflow_id` / `execution_plan_id` 已落库 |
| 消息表 | `chat_message`（MEDIUMTEXT content / reasoning / steps / content_blocks），**纯文本，无附件/图片** |
| Catalog 版本 | `PromptCatalogHolder.catalogVersion` + `ToolCatalogService.catalogVersion`（自增长整） |
| 可观测 | Grafana / Sentinel / SkyWalking（3.5 ✅） |
| 前端 | `ChatView` assistant 气泡已有 `msg-copy-bar` 操作条（复制按钮）；RAG 评测历史页 `KbEvalHistoryTab` 可作大盘交互范式参考 |

## 3. 数据模型

DDL SSOT：`docker/mysql/init/11-sunshine-orchestrator.sql`（orchestrator 一项目一文件，禁止 Flyway / 模块内 migration）。

### 3.1 `chat_feedback`（显式 + 隐式反馈，承载归因快照）

```sql
CREATE TABLE chat_feedback (
    id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    message_id      VARCHAR(64)  NOT NULL,          -- assistant 消息
    conversation_id VARCHAR(64)  NOT NULL,
    user_id         VARCHAR(64)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL,
    kind            VARCHAR(16)  NOT NULL,          -- explicit / implicit
    -- explicit: up / down ; implicit: regenerate / cancel / repeat_query
    signal          VARCHAR(20)  NOT NULL,
    reason_tags     VARCHAR(255) NULL,              -- 逗号分隔，仅 explicit down
    comment         TEXT         NULL,              -- 补充说明，仅 explicit
    -- 归因快照
    execution_mode  VARCHAR(16)  NULL,
    intent          VARCHAR(32)  NULL,
    workflow_id     VARCHAR(64)  NULL,
    model_name      VARCHAR(64)  NULL,
    prompt_catalog_version BIGINT NULL,
    tool_catalog_version   BIGINT NULL,
    trace_id        VARCHAR(64)  NULL,
    latency_ms      INT          NULL,
    attachments     JSON         NULL,              -- 预留：消息附件（当前恒 NULL）
    extra           JSON         NULL,              -- 预留扩展
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    UNIQUE KEY uk_feedback_msg_user (message_id, user_id, kind),
    INDEX idx_fb_tenant_mode_time (tenant_id, execution_mode, created_at),
    INDEX idx_fb_signal (signal, created_at)
);
```

要点：
- 唯一键 `(message_id, user_id, kind)`：显式反馈允许改评（upsert 覆盖 signal/tags/comment/updated_at）；隐式同 kind 幂等去重。
- `attachments` 预留 JSON 列，当前 Chat 无附件恒 NULL；未来多模态接入后存 `{name,type,size,ref}`。
- 归因快照在反馈提交时从 `chat_message` + Catalog holder 采集，冗余落表，避免后续 join 漂移。

### 3.2 `chat_message_snapshot`（踩样本完整问答快照，承载附件内容）

大盘「踩样本列表」默认只 join `chat_message` 出文本问答；**点开详情**时读快照表，保证完整保真（含未来附件/图片 base64、content_blocks、steps）。

```sql
CREATE TABLE chat_message_snapshot (
    message_id      VARCHAR(64) NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    user_query      MEDIUMTEXT   NULL,
    content         MEDIUMTEXT   NULL,
    reasoning       MEDIUMTEXT   NULL,
    steps           MEDIUMTEXT   NULL,
    content_blocks  MEDIUMTEXT   NULL,
    attachments     MEDIUMTEXT   NULL,   -- 未来附件/图片（base64 或对象存储引用），当前 NULL
    captured_at     DATETIME(3) NOT NULL
);
```

- 写入时机：**首次对某 message 产生显式 down 反馈时**（lazy，避免全量消息冗余）。
- 与 §3.1 `attachments` 区别：`chat_feedback.attachments` 为轻量元数据（列表展示）；`chat_message_snapshot.attachments` 为完整内容（详情展示）。

### 3.3 `eval_case`（全模式线下评测集，P2 框架）

```sql
CREATE TABLE eval_case (
    id             VARCHAR(64)  NOT NULL PRIMARY KEY,
    suite_key      VARCHAR(64)  NOT NULL,          -- e2e-react / e2e-workflow / e2e-plan-workflow / e2e-peer-collab
    query          TEXT         NOT NULL,
    expected_route VARCHAR(16)  NULL,              -- 期望 execution_mode
    expected_tools JSON         NULL,              -- 期望工具 catalog id 列表
    answer_points  JSON         NULL,              -- 期望答案要点（judge rubric，二期用）
    source         VARCHAR(16)  NOT NULL DEFAULT 'seed', -- seed / downvote_reflow
    ref_feedback_id VARCHAR(64) NULL,              -- 回流来源 chat_feedback.id
    enabled        TINYINT(1)   NOT NULL DEFAULT 1,
    created_at     DATETIME(3)  NOT NULL,
    INDEX idx_eval_suite (suite_key, enabled)
);
```

## 4. 反馈链路（§2 设计确认）

### 4.1 后端 API

orchestrator 新建 `feedback` 包（`FeedbackController` / `FeedbackService` / `FeedbackSnapshotService` / `entity` / `repo`），BFF 透传。

| API | 说明 |
|-----|------|
| `POST /api/feedback` | 提交/改评。body：`{messageId, kind, signal, reasonTags[], comment?}`。鉴权取 `x-user-id`。校验 message 属于当前会话且为 assistant 终态；采集归因快照；upsert（explicit）；implicit 幂等。explicit down 首写时同步落 `chat_message_snapshot` |
| `GET /api/feedback/mine?conversationId=` | 拉当前会话本人的反馈态（回显已点状态） |

### 4.2 前端

- `ChatView` assistant 气泡操作条（现有 `msg-copy-bar`）扩展 👍/👎 按钮，仅 `status=completed` 显示。
- 点 👎 弹出原因标签（标准四类：`irrelevant 答非所问` / `factual_error 事实错误` / `retrieval_miss 检索缺失` / `too_slow 太慢`）+ 补充说明 textarea，可跳过直接提交。
- 已点状态回显，可改评。
- 隐式埋点：重新生成 → `signal=regenerate`；中途取消 → `signal=cancel`（若该消息后续被重新生成/追问则不重复记）；同会话连续追问语义相似（前端简单判重：相邻两条 user query 相似度超阈值）→ `signal=repeat_query`。

### 4.3 归因快照采集

`FeedbackService` 提交时组装：`execution_mode` / `intent` / `workflow_id` / `execution_plan_id`（→ traceId）来自 `chat_message`；`model_name` 读 `agent.model.name`；两个 catalog version 读各自 holder；`latency_ms` 从 `chat_message.steps` 的 stepsSummary.totalDurationMs。

## 5. 关键决策：反馈不走 RocketMQ

| 方案 | 取舍 |
|------|------|
| **DB 同步直写（采用）** | 即时可读、upsert 幂等简单、无 MQ 消费乱序覆盖问题；反馈量级低 |
| 复用审计 MQ→MySQL | 解耦削峰但延迟可见、改评乱序难处理；需保证审计器先就绪 → 引入依赖 |

选择 DB 直写。**但大盘页强依赖 chat_message（归因/正文）与 chat_feedback 同时可读**；审计/消息持久化由 orchestrator 现有链路保证。大盘聚合 SQL 均走 MySQL。

## 6. 大盘页（§3 设计确认）

- 前端 `EvaluationView` + 路由 `/evaluation`（菜单「评测大盘」），样式对齐 StatusView/ExpertsView（`--sun-black` 底 + 边框分区，禁用灰底）。
- BFF 聚合 API（orchestrator 出数据，仅管理员可见）：
  - `GET /api/feedback/stats?from&to&mode&tenantId` → 按模式分组：`{mode, total, up, down, likeRate, implicit: {regenerate, cancel, repeatQuery}, downTagDist}`
  - `GET /api/feedback/down-list?mode&tag&page` → 踩样本流（join chat_message 出 query+content 摘要 + 快照字段）
  - `GET /api/feedback/detail/{messageId}` → 完整问答（读 `chat_message_snapshot`，含 steps/附件），供归因
- 页面：顶部模式分组好评率卡片 + 隐式负反馈率；中部趋势（按天）；下部踩样本实时流（轮询 10-15s），点开抽屉看完整回答 + 归因快照 + traceId。
- 指标另打 Prometheus counter（`sunshine_feedback_total{mode,signal}`）进现有 Grafana（可选增强）。

## 7. 评测集回流（§4 P2 框架确认）

- `eval_case` 表 + 每模式种子集 10-20 条（SQL 种子 / Python 生成脚本）。
- 大盘踩样本详情提供「回流为评测用例」操作 → 生成 `eval_case(source=downvote_reflow, ref_feedback_id)`，suite_key 按该样本 execution_mode 归到对应套件。
- `scripts/eval_e2e.py`：按 suite_key 拉启用用例 → 调 Chat SSE → 校验 route/工具/（二期 judge 要点）→ 报告。一期仅框架 + 种子校验跑通，不接门禁 CI。

## 8. 错误处理

- 反馈提交：message 不存在/非 assistant/非终态/越权 → 明确 4xx 业务错误；前端 toast。
- 快照落库失败不阻断反馈主流程（log warn，快照可下次补）。
- 隐式埋点上报失败静默（不打扰用户）。
- 大盘聚合空数据 → 前端空态展示。

## 9. 测试

- orchestrator 单测：`FeedbackService`（提交/改评/幂等/快照采集/越权）、聚合 stats SQL、隐式去重、eval_case 回流。
- BFF 透传测。
- Live 验收：`scripts/verify_feedback_live.py`（👍/👎/改评/隐式三类/stats/down-list/detail/回流）；`scripts/eval_e2e.py --suite e2e-react --seed-only`。

## 10. 实施拆分（对应 P0/P1/P2）

1. **P0 反馈闭环最小集**：3.1 表 + 提交/改评/mine API + 气泡 👍👎 + 大盘页（好评率 + 踩列表 + 详情）+ verify_feedback_live。
2. **P1 归因增强**：快照字段采集 + 快照表 + 点踩原因标签 + 隐式三类埋点 + 大盘隐式指标。
3. **P2 评测集框架**：3.3 表 + 种子集 + 回流操作 + `eval_e2e.py` 框架。

> 实施顺序按 P0→P1→P2；数据表一次建全（避免二次 DDL），功能分阶段上线。
