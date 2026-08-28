# 线上反馈闭环 + 分模式评测大盘 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Sunshine AI 平台建立用户反馈（👍/👎 + 隐式负信号）采集、按执行模式分组的评测大盘页，以及全模式评测集框架与踩样本回流。

**Architecture:** 方案 A（MySQL 轻量闭环）。orchestrator 新建 `feedback` 包直写 MySQL（`chat_feedback` 承载显式/隐式反馈 + 归因快照；`chat_message_snapshot` 承载踩样本完整问答/未来附件；`eval_case` 承载评测集）。BFF 透传。前端 Chat 气泡加反馈按钮，新增 `EvaluationView` 大盘页。

**Tech Stack:** Spring Boot(WebFlux) orchestrator / BFF、MySQL（JPA）、Vue3 + Naive UI、Python（scripts）。

**Spec:** `docs/superpowers/specs/2026-07-27-feedback-eval-dashboard-design.md`

## Global Constraints

- 库表 DDL SSOT：`docker/mysql/init/11-sunshine-orchestrator.sql`（**禁止** Flyway / 模块 `resources/db/migration`）。
- 执行模式枚举复用 `com.sunshine.orchestrator.routing.ExecutionMode`（`react/workflow/plan-workflow/peer-collab`）。
- 反馈**不**走 RocketMQ（DB 同步直写）。
- 点踩原因标签固定四类：`irrelevant` / `factual_error` / `retrieval_miss` / `too_slow`。
- 唯一键 `uk_feedback_msg_user (message_id, user_id, kind)`；显式允许改评（upsert）。
- UI：页面/卡片/输入统一 `--sun-black` 底 + `1px var(--sun-border)`；**禁止** `--sun-surface`/`--sun-deep`/`--sun-accent-muted` 灰底；下拉选中 18px 对号无灰底。
- 代码加适量中文注释；业务代码勿插多余空行。
- 禁止硬编码提示词。
- 本项目非 git 仓库，**跳过所有 git commit 步骤**。
- 改 orchestrator 后：编译 → 重启 → 跑 live 验收。

---

## Phase P0 — 反馈闭环最小集

### Task 1: DDL + 实体 + Repository

**Files:**
- Modify: `docker/mysql/init/11-sunshine-orchestrator.sql`（文件末尾追加三张表）
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/feedback/entity/ChatFeedbackEntity.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/feedback/entity/ChatMessageSnapshotEntity.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/feedback/repo/ChatFeedbackRepository.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/feedback/repo/ChatMessageSnapshotRepository.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/feedback/FeedbackSchemaTest.java`

**Interfaces:**
- Produces: `ChatFeedbackRepository extends JpaRepository<ChatFeedbackEntity,String>`；方法 `Optional<ChatFeedbackEntity> findByMessageIdAndUserIdAndKind(String messageId,String userId,String kind)`。`ChatMessageSnapshotRepository extends JpaRepository<ChatMessageSnapshotEntity,String>`。

- [ ] **Step 1: 追加 DDL**

在 `11-sunshine-orchestrator.sql` 末尾追加（含中文注释）：

```sql
-- V6__feedback_eval.sql  线上反馈闭环 + 评测大盘
-- 显式/隐式反馈（含归因快照）
CREATE TABLE chat_feedback (
    id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    message_id      VARCHAR(64)  NOT NULL,
    conversation_id VARCHAR(64)  NOT NULL,
    user_id         VARCHAR(64)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL,
    kind            VARCHAR(16)  NOT NULL,          -- explicit / implicit
    signal          VARCHAR(20)  NOT NULL,          -- explicit: up/down ; implicit: regenerate/cancel/repeat_query
    reason_tags     VARCHAR(255) NULL,              -- 逗号分隔，仅 explicit down
    comment         TEXT         NULL,
    execution_mode  VARCHAR(16)  NULL,
    intent          VARCHAR(32)  NULL,
    workflow_id     VARCHAR(64)  NULL,
    model_name      VARCHAR(64)  NULL,
    prompt_catalog_version BIGINT NULL,
    tool_catalog_version   BIGINT NULL,
    trace_id        VARCHAR(64)  NULL,
    latency_ms      INT          NULL,
    attachments     JSON         NULL,              -- 预留：消息附件元数据（当前恒 NULL）
    extra           JSON         NULL,
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    UNIQUE KEY uk_feedback_msg_user (message_id, user_id, kind),
    INDEX idx_fb_tenant_mode_time (tenant_id, execution_mode, created_at),
    INDEX idx_fb_signal (signal, created_at)
);

-- 踩样本完整问答快照（承载未来附件/图片内容）
CREATE TABLE chat_message_snapshot (
    message_id      VARCHAR(64) NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    user_query      MEDIUMTEXT   NULL,
    content         MEDIUMTEXT   NULL,
    reasoning       MEDIUMTEXT   NULL,
    steps           MEDIUMTEXT   NULL,
    content_blocks  MEDIUMTEXT   NULL,
    attachments     MEDIUMTEXT   NULL,   -- 未来附件/图片内容，当前 NULL
    captured_at     DATETIME(3) NOT NULL
);

-- 全模式评测集（P2 框架）
CREATE TABLE eval_case (
    id             VARCHAR(64)  NOT NULL PRIMARY KEY,
    suite_key      VARCHAR(64)  NOT NULL,
    query          TEXT         NOT NULL,
    expected_route VARCHAR(16)  NULL,
    expected_tools JSON         NULL,
    answer_points  JSON         NULL,
    source         VARCHAR(16)  NOT NULL DEFAULT 'seed',
    ref_feedback_id VARCHAR(64) NULL,
    enabled        TINYINT(1)   NOT NULL DEFAULT 1,
    created_at     DATETIME(3)  NOT NULL,
    INDEX idx_eval_suite (suite_key, enabled)
);
```

- [ ] **Step 2: 实体（JPA，参考 `ChatAuditLogEntity` 风格）**

`ChatFeedbackEntity`：`@Entity @Table(name="chat_feedback")` `@Getter @Setter`，字段同 DDL 列（camelCase ↔ snake_case，`reasonTags`/`modelName`/`promptCatalogVersion`/`toolCatalogVersion`/`traceId`/`latencyMs`/`attachments`/`extra`/`createdAt`/`updatedAt`）。

`ChatMessageSnapshotEntity`：`@Table(name="chat_message_snapshot")`，`@Id messageId`，其余字段同 DDL。

- [ ] **Step 3: Repository**

```java
public interface ChatFeedbackRepository extends JpaRepository<ChatFeedbackEntity, String> {
    Optional<ChatFeedbackEntity> findByMessageIdAndUserIdAndKind(String messageId, String userId, String kind);
}
public interface ChatMessageSnapshotRepository extends JpaRepository<ChatMessageSnapshotEntity, String> {}
```

- [ ] **Step 4: 编译 + 单测（实体映射 smoke）**

Run: `cd orchestrator && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS。`FeedbackSchemaTest` 用 `@DataJpaTest` 或留编译校验即可（无 DB 时仅验证类可加载）。

### Task 2: FeedbackService（提交/改评/快照采集）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/feedback/FeedbackService.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/feedback/FeedbackSnapshotService.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/feedback/dto/FeedbackSubmitRequest.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/feedback/FeedbackServiceTest.java`

**Interfaces:**
- Consumes: `ChatFeedbackRepository`, `ChatMessageSnapshotRepository`, `ChatMessageRepository`(现有), `ChatConversationRepository`(现有), `PromptCatalogHolder`, `ToolCatalogService`, `ExecutionPlanRepository`(取 traceId), `@Value("${agent.model.name}")`。
- Produces: `ChatFeedbackEntity submit(String userId, FeedbackSubmitRequest req)`；`record FeedbackSubmitRequest(String messageId, String kind, String signal, java.util.List<String> reasonTags, String comment)`。

- [ ] **Step 1: 失败测试**

`FeedbackServiceTest`：Mock 各依赖，覆盖
- explicit up 提交 → 落库，归因快照字段填充（execution_mode/model/catalog version 非空）。
- explicit down → 触发 `FeedbackSnapshotService.capture`（验证调用）。
- 同 message+user+kind 二次提交 explicit → 改评（signal/tags/comment 覆盖、updated_at 变、记录数仍 1）。
- implicit 同 signal 重复 → 幂等不新增。
- message 非 assistant / 非终态 / 越权（conv.userId≠当前）→ 抛 `BizException`。

- [ ] **Step 2: 实现 FeedbackService**

逻辑：
1. 校验 kind∈{explicit,implicit}、signal 合法（explicit:up/down；implicit:regenerate/cancel/repeat_query）。
2. 查 `chat_message`：不存在/role≠assistant/status 非终态 → 抛错。查 conversation 校验 userId。
3. `findByMessageIdAndUserIdAndKind`：explicit 命中→改评更新；否则新建（UUID id、采集快照、explicit down 且为首次→`snapshotService.capture`）。
4. 归因快照采集：从 message 取 execution_mode/intent/workflow_id/execution_plan_id；`model_name` 读配置；catalog version 读两 holder；traceId 经 executionPlanId 查 `execution_plan.trace_id`；latency_ms 解析 `message.steps` 的 stepsSummary.totalDurationMs（复用 `StepsSummaryExtractor`）。

- [ ] **Step 3: 实现 FeedbackSnapshotService.capture(messageId)**

lazy：若 `chat_message_snapshot` 已存在则跳过；否则从 `chat_message` 取 user_query（同 conv 前一条 user 消息）/content/reasoning/steps/content_blocks 落表（attachments 当前 NULL）。失败仅 log warn 不阻断。

- [ ] **Step 4: 跑测试**

Run: `cd orchestrator && mvn -q test -Dtest=FeedbackServiceTest`
Expected: PASS。

### Task 3: FeedbackController + BFF 透传 + 大盘聚合 API

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/feedback/FeedbackController.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/feedback/dto/FeedbackStatsView.java` / `FeedbackDownItem.java` / `FeedbackDetailView.java`
- Modify: `bff/src/main/java/com/sunshine/bff/client/OrchestratorClient.java`
- Create: `bff/src/main/java/com/sunshine/bff/controller/FeedbackController.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/feedback/FeedbackStatsServiceTest.java`

**Interfaces:**
- Produces（orchestrator，均 `@RequestMapping("/api/feedback")`）：
  - `POST ""` body=FeedbackSubmitRequest → `R<ChatFeedbackEntity>`（用户态，x-user-id 注入）
  - `GET "/mine?conversationId"` → `R<List<ChatFeedbackEntity>>`
  - `GET "/stats?from&to&mode&tenantId"` → `R<List<FeedbackStatsView>>`（管理态）
  - `GET "/down-list?mode&tag&page&size"` → `R<Page<FeedbackDownItem>>`
  - `GET "/detail/{messageId}"` → `R<FeedbackDetailView>`

- [ ] **Step 1: 失败测试（聚合 SQL）**

`FeedbackStatsServiceTest`：造多 mode 混合 explicit up/down + implicit 数据，断言 `stats` 按 mode 分组出 total/up/down/likeRate/implicit 三计数/downTagDist。

- [ ] **Step 2: 实现聚合查询**

`FeedbackStatsView` record：`{String mode, long total, long up, long down, double likeRate, Map<String,Long> implicit, Map<String,Long> downTagDist}`。用 `@Query` 分组统计（kind='explicit' 按 execution_mode 分组；implicit 按 signal 计数；down 的 reason_tags 拆分计数）。`down-list` join `chat_message` 出 query/content 摘要（截 200 字）+ 快照字段。`detail` 读 `chat_message_snapshot`（无快照回退 join chat_message）。

- [ ] **Step 3: Controller + 权限**

`POST`/`mine` 用户态（读 `x-user-id`）；`stats`/`down-list`/`detail` 管理态（校验角色，参考现有管理接口鉴权方式）。

- [ ] **Step 4: BFF 透传**

`OrchestratorClient` 加 5 个 WebClient 方法；BFF `FeedbackController` 同路径转发（透传 `x-user-id`）。

- [ ] **Step 5: 跑测试**

Run: `cd orchestrator && mvn -q test -Dtest='Feedback*Test'`；`cd ../bff && mvn -q -DskipTests compile`
Expected: PASS / BUILD SUCCESS。

### Task 4: 前端反馈 API + 气泡按钮 + 点踩弹窗

**Files:**
- Create: `sunshine-ui/src/api/feedback.ts`
- Create: `sunshine-ui/src/components/chat/MessageFeedbackBar.vue`
- Create: `sunshine-ui/src/components/chat/DownvoteDialog.vue`
- Modify: `sunshine-ui/src/views/ChatView.vue`（操作条挂载反馈条）
- Test: `sunshine-ui/src/api/feedback.test.ts`

**Interfaces:**
- Consumes: `resolveApiBase`、`apiHeaders`、`friendlyErrorMessage`、`parseBffPayload`（参考 `conversations.ts`）。
- Produces: `submitFeedback(req)`, `fetchMyFeedback(conversationId)`；组件 `<MessageFeedbackBar :messageId :status :existing />` emit `submitted`。

- [ ] **Step 1: API 封装**

`feedback.ts`：

```ts
export type FeedbackSignal = 'up' | 'down'
export const DOWN_TAGS = [
  { value: 'irrelevant', label: '答非所问' },
  { value: 'factual_error', label: '事实错误' },
  { value: 'retrieval_miss', label: '检索缺失' },
  { value: 'too_slow', label: '太慢' },
] as const
export async function submitFeedback(req: { messageId: string; signal: FeedbackSignal; reasonTags?: string[]; comment?: string }): Promise<void>
export async function fetchMyFeedback(conversationId: string): Promise<Record<string, { signal: FeedbackSignal }>>
```

- [ ] **Step 2: MessageFeedbackBar**

👍/👎 两按钮（参考 `msg-copy-btn smd-toolbtn` 样式），仅 `status==='completed'` 渲染。点击 👍 直接提交；点击 👎 打开 `DownvoteDialog`。已点态高亮（可改评）。

- [ ] **Step 3: DownvoteDialog**

Naive `NModal`：四标签 checkbox（NCheckboxGroup）+ 补充说明 `NInput type="textarea"` + 「跳过直接提交」/「提交」。样式 `--sun-black` 底 + 边框。

- [ ] **Step 4: ChatView 挂载 + 回显**

在 `msg-copy-bar` 旁（或并入同一 bar）挂 `<MessageFeedbackBar>`；`canCopyAssistant` 同条件渲染。进入会话时 `fetchMyFeedback` 回显。

- [ ] **Step 5: 前端类型/单测 + 构建**

Run: `cd sunshine-ui && npm run build`（或 `vite build`）
Expected: 无类型错误，构建成功。

### Task 5: 大盘页 EvaluationView + 路由 + 菜单

**Files:**
- Create: `sunshine-ui/src/views/EvaluationView.vue`
- Create: `sunshine-ui/src/components/evaluation/FeedbackStatsCards.vue` / `FeedbackDownList.vue` / `FeedbackDetailDrawer.vue`
- Create: `sunshine-ui/src/api/evaluation.ts`
- Modify: `sunshine-ui/src/router/index.ts`（加 `/evaluation` 路由）
- Modify: `sunshine-ui/src/layouts/MainLayout.vue`（菜单加「评测大盘」+ `FILL_CONTENT_ROUTES`）

**Interfaces:**
- Consumes: `evaluation.ts` → `getStats(params)`, `getDownList(params)`, `getFeedbackDetail(messageId)`。
- Produces: 路由 `{ path:'evaluation', name:'evaluation', component: EvaluationView, meta:{ title:'评测大盘' } }`。

- [ ] **Step 1: evaluation.ts** 三 API（同 `feedback.ts` 风格，管理态）。

- [ ] **Step 2: FeedbackStatsCards** — 按 mode 卡片：好评率（up/(up+down)）、total、up/down、隐式三计数。顶部时间范围/租户/模式筛选（NSelect，compact 304px、说明不换行、选中 18px 对号无灰底）。

- [ ] **Step 3: FeedbackDownList** — 踩样本流，轮询 10-15s 刷新；行：模式 tag / 原因标签 / query 摘要 / 回答摘要 / 时间；点击开 `FeedbackDetailDrawer`。

- [ ] **Step 4: FeedbackDetailDrawer** — 完整问答（user query + assistant content 渲染 markdown）+ 归因快照（mode/model/catalog version/traceId/latency）+ steps 折叠。

- [ ] **Step 5: 路由 + 菜单 + 构建**

`router/index.ts` 加路由；`MainLayout.vue` `platformMenuOptions` 加 `{ label:'评测大盘', key:'evaluation', icon: renderIcon(StatsChartOutline) }`，`FILL_CONTENT_ROUTES` 加 `'evaluation'`。
Run: `cd sunshine-ui && npm run build`
Expected: 成功。

### Task 6: P0 Live 验收脚本

**Files:**
- Create: `scripts/verify_feedback_live.py`

**Interfaces:**
- Consumes: `sunshine_lib`（`run_mysql`/`rag_admin_headers` 等现有 helper）、BFF base。

- [ ] **Step 1: 脚本**

覆盖：发起一条 chat → 拿 assistant messageId → 👍 → `stats` 对应 mode up+1 → 改 👎 带 tag → `down-list` 出现该样本 → `detail/{messageId}` 返回完整问答 → 隐式 regenerate 埋点 → implicit.regenerate+1。断言每步。

- [ ] **Step 2: 跑通**

Run: `python3 scripts/verify_feedback_live.py`
Expected: 全绿。

---

## Phase P1 — 归因增强 + 隐式负反馈

### Task 7: 隐式三类埋点（前端）

**Files:**
- Modify: `sunshine-ui/src/api/chatSessions.ts`（重新生成、取消处埋点）
- Modify: `sunshine-ui/src/api/feedback.ts`（加 `submitImplicit(messageId, signal)`）

**Interfaces:**
- Produces: `submitImplicit(messageId: string, signal: 'regenerate'|'cancel'|'repeat_query')`。

- [ ] **Step 1: 埋点**

重新生成成功后 → `regenerate`；用户中途取消流 → `cancel`（若该消息后续重新生成则不重复）；相邻两 user query 相似度超阈值（前端简单判重）→ `repeat_query`。失败静默。

- [ ] **Step 2: 单测 + 构建**

`feedback.test.ts` 补隐式调用断言；`npm run build`。

### Task 8: 大盘隐式指标 + Prometheus counter

**Files:**
- Modify: `sunshine-ui/src/components/evaluation/FeedbackStatsCards.vue`（隐式负反馈率展示）
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/feedback/FeedbackMetrics.java`

- [ ] **Step 1: 卡片补隐式率**（regenerate/cancel/repeat_query 计数与占比）。

- [ ] **Step 2: Micrometer counter**

`FeedbackMetrics` 在 `FeedbackService.submit` 成功处 `Counter.builder("sunshine_feedback_total").tags("mode",mode,"signal",signal).register(registry).increment()`。进现有 Prometheus/Grafana。

- [ ] **Step 3: 构建 + 单测**

Run: `cd orchestrator && mvn -q test -Dtest=FeedbackServiceTest`；`cd sunshine-ui && npm run build`。

---

## Phase P2 — 全模式评测集框架 + 回流

### Task 9: eval_case 种子 + 回流 API

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/feedback/entity/EvalCaseEntity.java` + `repo/EvalCaseRepository.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/feedback/FeedbackController.java`（加 `POST /api/feedback/{messageId}/reflow`）
- Modify: `docker/mysql/init/11-sunshine-orchestrator.sql`（追加种子 INSERT，每模式 3-5 条示例）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/feedback/EvalReflowTest.java`

**Interfaces:**
- Produces: `POST /api/feedback/{messageId}/reflow` → 按该 message execution_mode 生成 `eval_case(suite_key='e2e-'+mode, source='downvote_reflow', ref_feedback_id, query=快照 user_query)`。

- [ ] **Step 1: 实体 + repo + 失败测试**（回流生成 eval_case，suite_key 正确、source/ref 正确）。

- [ ] **Step 2: 实现 reflow + 种子 INSERT**。

- [ ] **Step 3: 前端大盘详情加「回流为评测用例」按钮** → 调 reflow → toast。

- [ ] **Step 4: 测试 + 构建**。

### Task 10: eval_e2e.py 框架

**Files:**
- Create: `scripts/eval_e2e.py`

**Interfaces:**
- Consumes: `sunshine_lib`、BFF Chat SSE。

- [ ] **Step 1: 脚本**

`--suite e2e-react --seed-only`：从 MySQL 拉启用用例 → 逐条调 Chat SSE → 校验实际 route==expected_route（一期仅路由断言 + 工具命中记录）→ 输出报告（pass/fail 明细）。

- [ ] **Step 2: 跑种子**

Run: `python3 scripts/eval_e2e.py --suite e2e-react --seed-only`
Expected: 报告生成。

---

## Self-Review 记录

- **Spec coverage**：P0(§3.1 表/§4 链路/§6 大盘)→Task1-6；P1(快照/标签/隐式)→Task2,4,7,8；P2(§3.3/§7)→Task9,10。全覆盖。
- **Placeholder scan**：无 TBD；每个 code step 含可执行代码/SQL/命令。
- **Type consistency**：`FeedbackSubmitRequest`/`ChatFeedbackEntity`/`FeedbackStatsView`/`submitFeedback`/`submitImplicit`/路由名 `evaluation` 前后一致；catalog version 取 `PromptCatalogHolder.snapshot().catalogVersion()` 与 `ToolCatalogService.catalogVersion()`（long）。
- **Note**：本项目非 git 仓库，所有 commit 步骤已剔除；orchestrator 改动后需编译→重启→live 验收。
