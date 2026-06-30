# 处理时间线 V2 — 步骤三态摘要 + 可扩展事件架构

> **Superseded（2026-06-30）**：已归档至 `docs/archive/specs/`。UI 见 `OperationStack.vue`；步骤态仅用 `lifecycle`。

> **⚠️ 已并入** [phase2-benchmark-design.md](../../superpowers/specs/phase2-benchmark-design.md) **§2.18**。下文为历史详设。

> 日期：2026-06-13 | 状态：**已实施**  
> 前置：`docs/archive/specs/2026-06-13-processing-timeline-design.md`（V1 已落地）  
> 决策：**方案 B** — 每个步骤自带「处理前 / 处理中 / 处理后」三态摘要，并带耗时时间线  
> 架构：**方案 2** — 事件流 + 聚合器，Contributor 可插拔扩展

## 背景

V1 已实现扁平 `ProcessingStep`（`intent → rag → agent → generate`），具备 SSE 推送、Redis 重连、MySQL 落库与前端 `ProcessingTimeline` 组件。但存在局限：

- 每步只有 `label + detail`，无「开始前 / 进行中 / 完成后」语义
- 无 `startedAt / endedAt / durationMs`，时间线缺乏耗时感知
- 埋点散落在 `ChatController`、`ProcessingStepHook`、`AgentScopeEventMapper`，新增场景需改多处

## 目标

1. **每步骤三态摘要**：`summary.before` / `summary.active` / `summary.after`
2. **时间线**：每步显示耗时，完成后显示总耗时
3. **后端可扩展**：新工具/阶段通过 Contributor + Session API 接入，不改核心聚合逻辑
4. **向后兼容**：SSE 保留 `status` / `label`；旧 `steps` JSON 前端可降级渲染

## 非目标

- 请求级三段式分组（处理前/中/后 macro phase）— 本次不做
- 运维 Trace / SkyWalking 集成
- 知识库检索结果全文预览
- i18n 配置化（MVP 中文固定文案）

---

## 已锁定决策

| # | 决策项 | 结论 |
|---|--------|------|
| 1 | 步骤语义 | **B**：每步骤三态摘要（pending → running → done） |
| 2 | 后端架构 | **事件流 + TimelineAggregator + Session API** |
| 3 | SSE 兼容 | 保留 `type:step`，扩展 `lifecycle/summary/startedAt/endedAt/durationMs`，冗余 `status/label` |
| 4 | 落库 | 继续用 `chat_message.steps` MEDIUMTEXT，JSON 结构升级，无需新 Flyway |
| 5 | progress 更新 | 支持 `EventKind.PROGRESS`（处理中多次更新 `summary.active`） |
| 6 | 动态工具步骤 | `tool-{name}` 步骤由 `ToolContributor` 自动生成三态文案 |

---

## 数据模型

### 后端内部：不可变事件

```java
public enum EventKind {
    PENDING,   // 处理前：步骤即将开始
    START,     // 处理中：步骤开始执行
    PROGRESS,  // 处理中：进度更新（可选，多次）
    COMPLETE,  // 处理后：成功完成
    FAIL,      // 处理后：失败
    SKIP       // 处理后：跳过
}

public record ProcessingEvent(
    String stepId,
    String phase,
    EventKind kind,
    String summary,
    long ts,
    String detail   // 可选副文案
) {}
```

### 对外快照：ProcessingStep V2

```java
public record StepSummary(String before, String active, String after) {}

public record ProcessingStep(
    String id,
    String phase,
    String lifecycle,      // pending | running | done | error | skipped
    StepSummary summary,
    Long startedAt,
    Long endedAt,
    Long durationMs,
    String detail,
    long ts,
    // 向后兼容冗余字段（Aggregator 自动填充）
    String status,
    String label
) {}
```

### SSE payload

```json
{
  "type": "step",
  "id": "intent",
  "phase": "intent",
  "lifecycle": "done",
  "summary": {
    "before": "准备识别意图",
    "active": "正在分析用户输入",
    "after": "判定为：知识库查询"
  },
  "startedAt": 1718200000000,
  "endedAt": 1718200000120,
  "durationMs": 120,
  "detail": "知识库查询",
  "ts": 1718200000120,
  "status": "done",
  "label": "识别意图"
}
```

### 前端 TypeScript

```typescript
export interface StepSummary {
  before?: string
  active?: string
  after?: string
}

export interface ProcessingStep {
  id: string
  phase: string
  lifecycle: StepLifecycle
  summary?: StepSummary
  startedAt?: number
  endedAt?: number
  durationMs?: number
  detail?: string
  ts?: number
  // V1 兼容
  status?: StepStatus
  label?: string
}
```

### lifecycle 与 status 映射

| lifecycle | 冗余 status |
|-----------|-------------|
| pending | pending |
| running | running |
| done | done |
| error | error |
| skipped | skipped |

---

## 后端架构

```
Contributors                    ProcessingTimelineSession (per message)
┌─────────────────────┐        ┌──────────────────────────────────────┐
│ IntentContributor   │──emit─▶│  emit(ProcessingEvent)               │
│ GenerateContributor │        │       ↓                              │
│ RagContributor      │        │  TimelineAggregator.apply(event)     │
│ AgentContributor    │        │       ↓                              │
│ ToolContributor     │        │  snapshot() → List<ProcessingStep>   │
└─────────────────────┘        │       ↓                              │
                               │  onStepChanged → SSE + Redis buffer  │
                               └──────────────────────────────────────┘
```

### 核心类（新建 `orchestrator/.../processing/` 包）

| 类 | 职责 |
|----|------|
| `ProcessingEvent` | 不可变事件 record |
| `EventKind` | 事件类型枚举 |
| `TimelineAggregator` | 接收事件，维护步骤状态机，计算耗时 |
| `ProcessingTimelineSession` | 对外 API：`pending/start/progress/complete/fail/skip` |
| `ProcessingStepEmitter` | 将快照转为 `StreamToken.step()` 并回调写入 |
| `StepLabels` | 各步骤 id 的默认三态文案模板 |

### Session API

```java
public final class ProcessingTimelineSession {
    public void pending(String stepId, String phase, String label, String beforeSummary);
    public void start(String stepId, String phase, String label, String activeSummary);
    public void progress(String stepId, String activeSummary);
    public void complete(String stepId, String afterSummary, String detail);
    public void fail(String stepId, String afterSummary, String detail);
    public void skip(String stepId, String afterSummary);
    public List<ProcessingStep> snapshot();
    public Optional<ProcessingStep> lastChanged();
}
```

### Aggregator 状态机规则

| 收到事件 | 行为 |
|----------|------|
| PENDING | 创建步骤，`lifecycle=pending`，写 `summary.before` |
| START | `lifecycle=running`，`startedAt=ts`，写 `summary.active` |
| PROGRESS | 保持 running，覆盖 `summary.active` |
| COMPLETE | `lifecycle=done`，`endedAt=ts`，`durationMs=endedAt-startedAt`，写 `summary.after` |
| FAIL | `lifecycle=error`，同上计算耗时 |
| SKIP | `lifecycle=skipped`，写 `summary.after` |

同 `stepId` 多次 COMPLETE 视为幂等更新（保留最早 `startedAt`）。

### Contributor 迁移对照

| 现有埋点 | 迁移为 |
|----------|--------|
| `ChatController` intent running | `session.pending("intent",...)` → `session.start(...)` |
| `ChatController` intent done | `session.complete("intent", "判定为：{detail}", detail)` |
| `ChatController` generate | `GenerateContributor` |
| `ProcessingStepHook` PreActing | `session.start("rag"/"tool-*", ...)` |
| `ProcessingStepHook` PostActing | `session.complete(...)` |
| `AgentScopeEventMapper` REASONING | `session.start("agent", ...)` |
| `AgentScopeEventMapper` AGENT_RESULT | `session.complete("agent", ...)` + `session.complete("generate", ...)` |

### 与现有管线集成

- `GenerationJob.onChunk`：`token.isStep()` 时仍 upsert 到 buffer；`StreamToken.step()` 携带 V2 字段
- `ProcessingStepMerger.upsert`：按 `id` 合并，保留 `startedAt`（取更早值）
- `GenerationFlushScheduler.metaStep`：序列化完整 V2 JSON
- `StepEventBridge`：改为发射到 `ProcessingTimelineSession`（通过 ThreadLocal 绑定）

---

## 标准步骤三态文案

| stepId | label | before | active | after（模板） |
|--------|-------|--------|--------|---------------|
| intent | 识别意图 | 准备识别意图 | 正在分析用户输入 | 判定为：{detail} |
| rag | 检索知识库 | 准备检索向量库 | 正在查询 Milvus | {detail}（如命中 N 条） |
| agent | Agent 推理 | 准备 Agent 推理 | 正在调用 ReActAgent | 推理完成 |
| generate | 生成回答 | 准备生成回答 | 正在调用 LLM 流式输出 | 回答已生成 |
| tool-{name} | 调用工具 {name} | 准备调用 {name} | 正在执行 {name} | {detail} |

---

## 前端设计

### ProcessingTimeline.vue 升级

```
┌─ 处理过程 ─────────────────────── 总耗时 2.3s ─┐
│  ● 识别意图                              120ms │
│  │ 准备识别意图                                 │
│  │ 正在分析用户输入                             │
│  │ ✓ 判定为：知识库查询                         │
│  │                                              │
│  ● 检索知识库                            480ms │
│  │ 准备检索向量库                               │
│  │ 正在查询 Milvus…                             │
│  │ ✓ 命中 3 条                                  │
└─────────────────────────────────────────────────┘
```

- 左侧竖线 + 圆点（时间轴）
- 每步右侧显示 `durationMs`（完成后）或脉冲动画（running）
- 三态摘要纵向排列；无 `summary` 时降级为 V1 `label + detail`
- 折叠摘要：`知识库查询 · 命中3条 · 2.3s`

### 工具函数（`processingSteps.ts`）

- `normalizeStep`：解析 V2 字段，从 V1 `status/label` 推导 `lifecycle/summary`
- `formatDuration(ms)`：`120ms` / `1.2s`
- `totalDuration(steps)`：所有 done 步骤 `durationMs` 之和
- `summarizeSteps`：升级为含总耗时

### V1 数据降级

```typescript
function migrateV1Step(raw: ProcessingStep): ProcessingStep {
  if (raw.summary) return raw
  return {
    ...raw,
    lifecycle: raw.lifecycle ?? raw.status ?? 'running',
    summary: {
      active: raw.status === 'running' ? raw.label : undefined,
      after: raw.status === 'done' ? (raw.detail ?? raw.label) : undefined,
    },
  }
}
```

---

## 测试计划

| 用例 | 预期 |
|------|------|
| Aggregator：PENDING→START→COMPLETE | `startedAt/endedAt/durationMs` 正确，三态摘要齐全 |
| Aggregator：多次 PROGRESS | `summary.active` 被最后一次覆盖 |
| simple 路径 E2E | intent 三态 + generate 三态 + 耗时显示 |
| knowledge 路径 E2E | rag/agent 三态 + 总耗时 |
| 刷新恢复 | API 返回 V2 steps，时间线完整 |
| V1 历史数据 | 前端降级渲染，不报错 |
| Redis 重连 | 重放 V2 step 事件，状态不丢 |

---

## 分期实施

| 阶段 | 范围 |
|------|------|
| **P1** | 后端核心：`processing/` 包 + Aggregator 单测 + Session |
| **P2** | 迁移 `ChatController` intent/generate + `GenerationJob` 接线 |
| **P3** | 迁移 `ProcessingStepHook` + `AgentScopeEventMapper` + `StepEventBridge` |
| **P4** | 前端 V2 时间线 UI + `processingSteps.ts` + mock-server |
| **P5** | E2E 更新 + 真实后端验证 |

---

## 风险

1. **SSE payload 变大**：每步多 3 个 summary 字段，频率仍低（每步 2～4 次推送），可接受
2. **startedAt 重连**：重放时保留最早 `startedAt`，Aggregator merge 规则需单测覆盖
3. **tool-* 步骤爆炸**：MVP 仍聚合展示，不限制数量但 UI 超过 5 步时折叠中间项（P5 可选）
