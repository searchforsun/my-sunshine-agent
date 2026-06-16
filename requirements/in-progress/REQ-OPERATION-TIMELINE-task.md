# REQ-OPERATION-TIMELINE — Codex 式操作级时间线 实施计划

> **执行说明：** 按 Task 顺序实施；**仅**更新每个 Task 标题下一行的任务级 `- [ ]` / `- [x]`。

**Goal:** 步骤级流式思考 + OperationStack 卡片 UI，SSE `step_delta` 与 V3 步骤快照，刷新/重连可恢复。

**Architecture:** Orchestrator 扩展 `StreamToken.stepDelta` + `ProcessingTimelineSession.activeStepId` + Aggregator 文本累加；GenerationJob 缓冲重放；前端 `sseDispatch` + `OperationStack` 替代 Timeline/ReasoningPanel。

**Tech Stack:** JDK 21, Spring Boot 3.2.9, AgentScope 1.0.7, Vue3 + Pinia, Redis generation buffer, MySQL steps JSON

**Constraints:**

### 项目规则
- 不升级 Spring Boot 3.3+ / AgentScope 2.0（`CLAUDE.md`）
- BFF 透传 SSE，不改业务聚合
- 未知 SSE type 忽略（§3.5 forward compatible）
- IC-01～IC-04 均为 in-repo（见 design）

---

## 文件结构

| 操作 | 路径 | 职责 |
|------|------|------|
| 修改 | `orchestrator/.../StreamToken.java` | `stepDelta` kind |
| 新建 | `orchestrator/.../StepDeltaEmitter.java` | wire JSON v1 |
| 修改 | `orchestrator/.../ProcessingStep.java` | V3 字段 |
| 修改 | `orchestrator/.../TimelineAggregator.java` | reasoning/output/result 累加 |
| 修改 | `orchestrator/.../ProcessingTimelineSession.java` | activeStepId + appendDelta |
| 修改 | `orchestrator/.../AgentScopeEventMapper.java` | REASONING → step_delta |
| 修改 | `orchestrator/.../GenerationFlushScheduler.java` | metaStepDelta |
| 修改 | `orchestrator/.../GenerationJob.java` | step_delta 缓冲 |
| 修改 | `orchestrator/.../ProcessingStepMerger.java` | V3 merge |
| 修改 | `orchestrator/.../ChatController.java` | wrapStream step_delta（若仍走旧路径） |
| 测试 | `orchestrator/src/test/.../TimelineAggregatorTest.java` | delta append |
| 测试 | `orchestrator/src/test/.../GenerationJobTest.java` | step_delta seq |
| 修改 | `sunshine-ui/src/api/processingSteps.ts` | V3 + applyStepDelta |
| 新建 | `sunshine-ui/src/api/sseDispatch.ts` | handler registry |
| 修改 | `sunshine-ui/src/api/chatSessions.ts` | 接入 dispatch |
| 新建 | `sunshine-ui/src/components/operation/*` | OperationStack/Card |
| 修改 | `sunshine-ui/src/views/ChatView.vue` | 接入新 UI |
| 测试 | `sunshine-ui/e2e/processing-timeline-real.spec.ts` | 扩展 |

---

### Task 1: 后端 V3 模型 + Aggregator delta
- [x]

**涉及文件：**
- 修改：`orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStep.java`
- 修改：`orchestrator/src/main/java/com/sunshine/orchestrator/processing/TimelineAggregator.java`
- 修改：`orchestrator/src/main/java/com/sunshine/orchestrator/processing/ProcessingTimelineSession.java`
- 测试：`orchestrator/src/test/java/com/sunshine/orchestrator/processing/TimelineAggregatorDeltaTest.java`

**实施说明：**

1. `ProcessingStep` record 增加 `reasoning`、`output`、`result`（nullable String）；snapshot 序列化兼容 null。
2. `TimelineAggregator.StepState` 增加三字段累加器；新增 `appendDelta(stepId, channel, text)`：`reasoning`/`output` append，`result` 覆盖。
3. `ProcessingTimelineSession`：`activeStepId` 字段；`start` 时 setActive 并 complete 上一 running；`appendDelta(channel,text)` 校验 activeStep。
4. 单测：START → delta reasoning×3 → COMPLETE → snapshot 含 reasoning 与 durationMs。

**验证：**

- 命令：`mvn test -pl orchestrator -Dtest=TimelineAggregatorDeltaTest -am`
- 预期：BUILD SUCCESS

---

### Task 2: StreamToken + StepDelta + Generation 管线
- [x]

**涉及文件：**
- 修改：`orchestrator/src/main/java/com/sunshine/orchestrator/client/StreamToken.java`
- 新建：`orchestrator/src/main/java/com/sunshine/orchestrator/processing/StepDeltaEmitter.java`
- 修改：`orchestrator/src/main/java/com/sunshine/orchestrator/conversation/GenerationFlushScheduler.java`
- 修改：`orchestrator/src/main/java/com/sunshine/orchestrator/generation/GenerationJob.java`
- 修改：`orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepMerger.java`
- 测试：`orchestrator/src/test/java/com/sunshine/orchestrator/generation/GenerationJobStepDeltaTest.java`

**实施说明：**

1. `StreamToken` 新增 `KIND_STEP_DELTA`、`stepDelta(stepId, channel, text)`、`isStepDelta()`。
2. `StepDeltaEmitter.emit(session, channel, text)` → 调 session.appendDelta + 返回 wire JSON `{type, v:1, stepId, channel, text}`。
3. `GenerationFlushScheduler.metaStepDelta(...)`；`GenerationJob.onChunk` 对 step_delta upsert stepsBuffer + Redis。
4. `ProcessingStepMerger` 合并 reasoning/output 取更长字符串。
5. 单测：Job 收到 step_delta 后 stepsBuffer 对应 step.reasoning 增长。

**验证：**

- 命令：`mvn test -pl orchestrator -Dtest=GenerationJobStepDeltaTest,TimelineAggregatorDeltaTest -am`
- 预期：BUILD SUCCESS

---

### Task 3: AgentScope 路由 REASONING → step_delta
- [x]

**涉及文件：**
- 修改：`orchestrator/src/main/java/com/sunshine/orchestrator/agent/AgentScopeEventMapper.java`
- 修改：`orchestrator/src/main/java/com/sunshine/orchestrator/controller/ChatController.java`（RAG output delta，若适用）
- 测试：`orchestrator/src/test/java/com/sunshine/orchestrator/agent/AgentScopeEventMapperTest.java`

**实施说明：**

1. `appendThinkingTokens` 改为 emit `StreamToken.stepDelta(activeStepId, "reasoning", text)`；REASONING 前确保 agent step started。
2. 保留无 activeStep 时 fallback `StreamToken.reasoning`（过渡期）。
3. RAG complete 时可选 `appendDelta("rag","output", truncated)`。
4. 单测：ThinkingBlock → stepDelta token，非 reasoning token。

**验证：**

- 命令：`mvn test -pl orchestrator -Dtest=AgentScopeEventMapperTest -am`
- 预期：BUILD SUCCESS

---

### Task 4: 前端 applyStepDelta + sseDispatch
- [x]

**涉及文件：**
- 修改：`sunshine-ui/src/api/processingSteps.ts`
- 新建：`sunshine-ui/src/api/sseDispatch.ts`
- 修改：`sunshine-ui/src/api/chatSessions.ts`

**实施说明：**

1. `ProcessingStep` TS 增加 reasoning/output/result；`applyStepDelta(steps, delta)`。
2. `sseDispatch.ts`：handlers for step, step_delta, content, reasoning, meta；unknown type noop。
3. `chatSessions.consumeSseStream` 改用 dispatch。
4. step_delta 时 mirror reasoning 到 message.reasoning（过渡期）。

**验证：**

- 命令：`cd sunshine-ui && npm run build`（允许既有 KnowledgeView 错误则仅 typecheck chat 相关）
- 预期：新增文件无 TS 错误

---

### Task 5: OperationStack UI + ChatView 接入
- [x]

**涉及文件：**
- 新建：`sunshine-ui/src/components/operation/OperationStack.vue`
- 新建：`sunshine-ui/src/components/operation/OperationCard.vue`
- 修改：`sunshine-ui/src/views/ChatView.vue`

**实施说明：**

1. `OperationCard`：header + reasoning/output/result + running 水波纹。
2. `OperationStack`：遍历 steps，running 默认展开。
3. ChatView 替换 ProcessingTimeline + ReasoningPanel；保留 AnswerMarkdown。
4. 折叠摘要条 + 总耗时。

**验证：**

- 命令：浏览器手动 / `npm run dev` + 发起知识库问答
- 预期：四卡可见，agent 卡内思考流式

---

### Task 6: E2E + 文档索引
- [x]

**涉及文件：**
- 修改：`sunshine-ui/e2e/processing-timeline-real.spec.ts`
- 修改：`requirements/in-progress/REQ-OPERATION-TIMELINE.md`
- 修改：`requirements/INDEX.md`

**实施说明：**

1. E2E：断言 step 卡存在、刷新后会话含 steps reasoning。
2. 需求验收勾选更新。

**验证：**

- 命令：`cd sunshine-ui && npx playwright test e2e/processing-timeline-real.spec.ts`
- 预期：pass（服务可用时）

---
