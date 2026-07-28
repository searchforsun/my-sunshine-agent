# 4.7.7 ReAct Request Decision（主 Agent 主动向用户出选择题 / 需求澄清）

> **状态**：spec（待实施）
> **日期**：2026-07-28
> **编号**：阶段四 **4.7.7**
> **相关**：`AgentRuntime` / `HitlTokenRegistry`（阻塞唤醒复用）· [spawn-subagent](./2026-07-18-react-spawn-subagent-design.md)（元工具范式）· [plan-user-approval](./2026-06-27-plan-user-approval-design.md)（阻塞确认范式）· [taskboard](./2026-06-24-react-taskboard-design.md)

---

## 1. 背景与目标

ReAct Agent 在长链路推理中常遇到**需求歧义**或**多方案抉择**：用户提问存在多种合理解读，或执行路径有多条可行方案。当前 Agent 只能"猜"一个方向继续，一旦猜错整个链路白跑。

类似 Cursor 的行为：Agent 在执行中发现"我不确定你要哪种"，**主动暂停**并向用户出一道选择题，用户选择后继续。这与现有的「工具调用确认（HITL confirm_cancel）」是**两条平行路径**：

| 路径 | 触发方 | 触发时机 | 用途 |
|------|--------|----------|------|
| 工具确认 HITL（现有） | 平台自动拦截 | 写工具执行前 | 安全闸：要不要执行这个工具 |
| **决策选择（本设计）** | **Agent 主动调用元工具** | 推理中遇到歧义/多方案 | 需求澄清：我理解有这几个方案，你选哪个 |

| 目标 | 说明 |
|------|------|
| 需求澄清 | Agent 遇到歧义时主动暂停，向用户出选择题（至少 2 项），等待用户决策后续跑 |
| 决策回传 | 用户选择结果作为 tool result 回传 Agent，Agent 据此继续推理 |
| 输入支持 | 选项可声明 `requireInput`，允许用户附加文本说明（类似 Plan 确认的 modificationHint） |
| 样式复用 | 前端复用 `ExecutionModeSelector` 三兄弟的选项卡片样式（对号 18px + 透明底 + 边框分区） |
| 阻塞复用 | 后端复用 `HitlTokenRegistry` 的 `CompletableFuture + Redis token` 阻塞唤醒模式 |
| 暂停/续跑 | 暂停时保留待决策快照；续跑 re-await 同一决策，不重新出题 |

### 1.1 与既有能力正交

| 能力 | 关系 |
|------|------|
| 工具确认 HITL（confirm_cancel） | 安全闸，平台自动拦截；本能力是 **Agent 主动发起的需求澄清**，不混用 |
| `spawn_subagent` | 隔离子 Agent 执行；本能力不产生子 Agent，仅阻塞等待用户输入 |
| `manage_tasks` / TaskBoard | 软规划清单；本能力是**一次性决策**，不维护任务状态 |
| Plan 用户确认 | Plan 执行前 approve/regenerate；本能力是 **ReAct 推理中**的任意时机决策 |
| Workflow 节点失败 Recovery | 节点失败后 retry/skip/terminate；本能力是**主动澄清**，非失败恢复 |

---

## 2. 方案选型

采用 **方案 1：元工具 `request_decision`**（仅 `AgentRole.MAIN` 注册），内部复用 `HitlTokenRegistry` 的阻塞唤醒模式等待用户决策。

不采用：
- 扩展写工具 HITL 为选择题（用户已明确：决策选择是独立路径，不混用工具确认）
- 临时物化迷你 Plan（过重，决策不是规划）
- 前端轮询（SSE 阻塞推送已有成熟模式）

---

## 3. 架构与调用契约

```
主 ReAct (MAIN)
  └─ tool_call: request_decision({ question, options[], allowCustomInput? })
        └─ DecisionRegistry.register -> 下发 phase=decision 卡片 + 阻塞等待
              · 用户在前端选择 -> POST /api/generations/{id}/decisions/{token}/resolve
              · DecisionRegistry.resolve(token, choice, customInput) 唤醒
        └─ 返回：决策结果文本 -> 该次 tool result
              · 格式："用户选择了: {label}（value={value}）；补充说明: {customInput}"
```

| 项 | 约定 |
|----|------|
| 工具名 | `request_decision` |
| Catalog | **orchestrator 内置元工具**（同 `spawn_subagent` / `manage_tasks`），**不**进 tool-manager |
| 参数 `question` | 必填；向用户展示的决策问题，清晰描述需要澄清的点 |
| 参数 `options` | 必填；可选项列表，每项含 `value`/`label`/`description`/`requireInput?`；至少 2 项 |
| 参数 `allowCustomInput` | 可选；是否允许用户补充自定义答案（附加输入框），默认 false |
| 注册 | 仅 MAIN；SUB / Workflow agent / Expert **不注册** |
| 嵌套 | 与 spawn_subagent 一样禁止从子 Agent 调用 |
| 回传 | tool result = 决策结果文本（不对结果二次加工） |
| 阻塞 | 复用 `HitlTokenRegistry` 模式：`CompletableFuture<DecisionResult>` + Redis token |
| 失败 | 超时 -> tool result 含超时信息；主 Agent 可改用默认方案或再次追问 |

### 3.1 决策选项数据结构

```java
public record DecisionOption(
        String value,           // 选项值（英文蛇形，回传后端）
        String label,           // 中文展示名
        String description,     // 选项说明（取舍描述）
        boolean requireInput) { // 该选项是否需要附加文本输入
}

public record DecisionResult(
        String choice,          // 用户选择的 option value，或 "__custom__"
        String customInput,     // 用户附加文本（requireInput 或 allowCustomInput 时）
        long decidedAt) {
}
```

### 3.2 组件

| 组件 | 职责 |
|------|------|
| `RequestDecisionTool` | 元工具；解析参数；注册 token 阻塞等待；返回决策结果文本 |
| `DecisionRegistry` | `CompletableFuture<DecisionResult>` + Redis token；`register` / `awaitDecision` / `resolve` |
| `DecisionTimelineSupport` | 下发 `phase=decision` 卡片（running/awaiting/done/paused） |
| `DynamicToolkitFactory` | MAIN 注册 `request_decision`；SUB 剥离 |
| `ProcessingStepMiddleware` | 识别 `request_decision` 并跳过 `tool-*` 步（同 spawn_subagent） |
| 前端 `DecisionCard` | 主时间线决策卡片；选项列表 + 附加输入 + 提交 |
| 前端 `OperationStack` | 按 `phase=decision` 分发到 `DecisionCard` |

---

## 4. Timeline / UI

### 4.1 主时间线

- 每次成功发起的 `request_decision` -> **一张**卡片（id=`decision-{token}`，`phase=decision`）。
- 卡片显示：
  - **状态**：等待决策（running） / 已完成（done） / 已超时 / 已取消（paused）
  - **一行**当前摘要（SSE `summary`；Catalog `timeline.steps.decision`）
  - **决策问题**：Agent 写入的 `question`（`metadata.decision.question`）
  - **选项列表**：复用 `ExecutionModeSelector` 三兄弟的选项卡片样式
  - **附加输入**：`requireInput` 选项选中后展开 textarea；`allowCustomInput` 时额外显示"自定义"选项
  - **提交按钮**：选中后可提交
- 终态 `COMPLETE`/`FAIL`/`paused` **必须下发**。
- **禁止**：把决策问题/选项硬编码在前端；**禁止**对模型生成的 question/options 做截断/过滤兜底。

### 4.2 决策卡片 UI（复用三兄弟样式）

组件 `DecisionCard.vue` 复用 `CollapsibleConfirmPanel` 作容器（与 HITL/Plan/Recovery 同容器），选项列表复用 `ExecutionModeSelector.vue:204-264` 的 `.mode-menu-item` 全套样式：

| 元素 | 样式规则 | 复用来源 |
|------|----------|----------|
| 选项行 | `display:flex; gap:10px; padding:8px 10px; background:transparent` | `.mode-menu-item` |
| 选项行边框 | `1px solid var(--sun-border)`（卡片内嵌需边框分区） | **新增**（三兄弟是浮层无边框） |
| hover | `background: var(--sun-row-hover)` | `.mode-menu-item:hover` |
| 选中态 | `border-color: var(--sun-accent)`（内描边，无灰底） | CLAUDE.md 卡片选中规则 |
| 对号 | `CheckmarkOutline` 18px，`color: var(--sun-text)` | `.mode-menu-check` |
| 选项标题 | `--sun-font-base` 14px，`font-weight:500`，`color:var(--sun-text)` | `.mode-menu-title` |
| 选项描述 | `--sun-font-base` 14px，`color:var(--sun-text-muted)` | `.mode-menu-desc` |
| 附加输入框 | `--sun-black` 底 + `1px var(--sun-border)` | `.sun-field` / HITL 按钮 |
| 提交按钮 | `hitl-btn hitl-btn-primary`（28px 高） | `HitlStepActions` |

**与三兄弟的唯一差异**：选项行加 `1px var(--sun-border)` 边框做分区（HITL 确认框是内嵌卡片不是浮层下拉，需要更明确的视觉边界）。选中态用**内描边**而非背景填充，符合 CLAUDE.md「卡片/DAG 选中：内描边或 ring，hover 仅改边框」。

### 4.3 交互流程

1. Agent 调用 `request_decision` -> SSE 下发 `phase=decision` 卡片（`lifecycle=running`，`metadata.decision` 含 question/options/allowCustomInput/token/expiresAt）
2. 前端 `DecisionCard` 渲染问题 + 选项列表（默认无选中）
3. 用户点选某项 -> 该项 `is-selected`（内描边 + 对号 18px）
4. 若该选项 `requireInput=true` -> 展开 textarea
5. 若 `allowCustomInput=true` -> 选项列表末尾显示"自定义"项，选中后展开 textarea
6. 用户点「提交决策」-> `POST /api/generations/{id}/decisions/{token}/resolve`（body: `{choice, customInput?}`）
7. 后端 `DecisionRegistry.resolve` 唤醒 -> Agent 收到 tool result -> 卡片 `lifecycle=done`

### 4.4 SSE

- 复用 `metaStep` 下发 `phase=decision` 步骤（`lifecycle` + `summary` + `metadata.decision`）。
- **不新增** SSE type（不引入 `type:decision`），全部经 `type:step` 携带。
- `metadata.decision` 结构：

```json
{
  "token": "{uuid}",
  "question": "您希望按哪种方式处理？",
  "options": [
    {"value": "plan_a", "label": "方案A：快速处理", "description": "...", "requireInput": false},
    {"value": "plan_b", "label": "方案B：完整流程", "description": "...", "requireInput": true}
  ],
  "allowCustomInput": false,
  "expiresAt": 1753721880000
}
```

- 终态 `COMPLETE`/`FAIL`/`paused` **必须下发**。
- 不对模型输出做截断/过滤兜底。

---

## 5. 后端实现

### 5.1 元工具 `RequestDecisionTool`

仿 `SpawnSubagentTool`（`orchestrator/src/main/java/com/sunshine/orchestrator/agent/SpawnSubagentTool.java`），在 orchestrator 内置：

```java
@Slf4j
@Component
public class RequestDecisionTool {

    public static final String NAME = "request_decision";

    private final DecisionRegistry decisionRegistry;
    private final DecisionTimelineSupport timelineSupport;
    private final AgentExecutionProperties executionProperties;

    @Tool(name = NAME,
            description = "当用户需求存在歧义或需要用户在多个方案间做选择时调用此工具，"
                    + "向用户展示选择题并等待用户决策。不要用于工具调用确认。")
    public String requestDecision(
            @ToolParam(name = "question", description = "向用户展示的决策问题，清晰描述需要澄清的点（必填）")
                    String question,
            @ToolParam(name = "options", description = "可选项列表，每项含 value/label/description/requireInput；至少 2 项（必填）")
                    List<DecisionOption> options,
            @ToolParam(name = "allowCustomInput", description = "是否允许用户补充自定义答案，默认 false")
                    boolean allowCustomInput) {
        // 1. 校验：启用 / question 非空 / options >= 2 / 仅 MAIN scope
        // 2. StepEventBridge.activeMessageId() 定位会话
        // 3. DecisionRegistry.register(question, options, allowCustomInput) -> token + 阻塞
        // 4. DecisionTimelineSupport.begin 下发 phase=decision 卡片（lifecycle=running）
        // 5. 阻塞等待：decisionRegistry.awaitDecision(token, timeoutSec)
        // 6. 唤醒后：timelineSupport.complete 下发 lifecycle=done
        // 7. 返回决策结果文本给 Agent
    }
}
```

**校验逻辑**（对齐 SpawnSubagentTool）：
- `executionProperties.getReact().getDecision().isEnabled()` 为 false -> 返回错误 JSON
- `question` 空白 -> 返回错误 JSON
- `options == null || options.size() < 2` -> 返回错误 JSON
- `StepEventBridge.activeMessageId()` 为空 -> 返回错误 JSON
- `StepEventBridge.activeMainBridge(messageId)` 为空（非主 Agent） -> 返回错误 JSON
- `activeBridgeId` 以 `sub-` 开头（子 Agent 嵌套） -> 硬拒

**决策结果回传格式**（tool result，不对结果二次加工）：

```
用户选择了: {label}（value={value}）；补充说明: {customInput}
```

若用户超时未决策：

```
用户未在规定时间内做出决策（超时 {timeoutSec}s）
```

### 5.2 阻塞唤醒 `DecisionRegistry`

复用 `HitlTokenRegistry`（`orchestrator/src/main/java/com/sunshine/orchestrator/hitl/HitlTokenRegistry.java`）的 `CompletableFuture + Redis token` 模式：

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class DecisionRegistry {

    private static final String REDIS_KEY_PREFIX = "sunshine:decision:pending:";

    private final AgentExecutionProperties properties;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    // 同实例阻塞唤醒（与 HitlTokenRegistry 一致）
    private final Map<String, CompletableFuture<DecisionResult>> waiters = new ConcurrentHashMap<>();
    private final Map<String, DecisionRegistration> registrations = new ConcurrentHashMap<>();

    public DecisionRegistration register(
            String messageId, String question,
            List<DecisionOption> options, boolean allowCustomInput) {
        String token = UUID.randomUUID().toString();
        CompletableFuture<DecisionResult> future = new CompletableFuture<>();
        waiters.put(token, future);
        long expiresAt = Instant.now()
                .plusSeconds(properties.getReact().getDecision().getTimeoutSec())
                .toEpochMilli();
        // Redis 存元数据（跨实例可见 + 续跑恢复）
        storeToken(token, messageId, question, options, allowCustomInput, expiresAt);
        return new DecisionRegistration(token, future, expiresAt);
    }

    public DecisionResult awaitDecision(String token, long timeoutSec)
            throws InterruptedException, TimeoutException {
        CompletableFuture<DecisionResult> future = waiters.get(token);
        if (future == null) {
            throw new IllegalStateException("决策 token 不存在或已过期: " + token);
        }
        return future.get(timeoutSec, TimeUnit.SECONDS);
    }

    public boolean resolve(String token, String choice, String customInput) {
        CompletableFuture<DecisionResult> future = waiters.get(token);
        if (future == null) return false;
        future.complete(new DecisionResult(choice, customInput, Instant.now().toEpochMilli()));
        return true;
    }

    // 暂停/取消时中断等待（对齐 HitlWaitInterruptedException）
    public void cancelWaitersForMessage(String messageId) { ... }
}
```

**关键约定**：
- Redis key 前缀 `sunshine:decision:pending:`（区别于 HITL 的 `sunshine:hitl:pending:`）
- 超时 / 中断 / 异常三路回调，复用 `HitlConfirmationService.waitForDecision` 的骨架
- 暂停时 `cancelWaitersForMessage` 中断所有该消息的决策等待，写 `PendingInteraction(kind=decision)`

### 5.3 时间线 `DecisionTimelineSupport`

仿 `SpawnSubagentTimelineSupport`（`orchestrator/src/main/java/com/sunshine/orchestrator/agent/SpawnSubagentTimelineSupport.java`），下发 `phase=decision` 卡片：

| 方法 | 作用 |
|------|------|
| `begin(bridgeId, token, question, options, allowCustomInput, expiresAt)` | 下发 `lifecycle=running` 卡片，`metadata.decision` 含完整决策载荷 |
| `complete(bridgeId, token, result)` | 下发 `lifecycle=done`，`summary.after` 含用户选择摘要 |
| `fail(bridgeId, token, errorMsg)` | 下发 `lifecycle=error` |
| `pause(bridgeId, token)` | 暂停时下发 `lifecycle=paused`（用户停止 / 超时） |

SSE 经 `GenerationFlushScheduler.metaStep` 序列化，**不新增** SSE type。

### 5.4 注册到 ReAct 工具集

改 `DynamicToolkitFactory.buildFromWhitelist`（`orchestrator/src/main/java/com/sunshine/orchestrator/agent/DynamicToolkitFactory.java`）：

```java
// 白名单显式拒绝（与 spawn_subagent 同处理，第 90-93 行附近）
if (toolName.equals(RequestDecisionTool.NAME)) {
    log.warn("[Orchestrator] request_decision 为内置元工具，勿放入 ReAct 工具集");
    continue;
}

// MAIN scope 注入（第 109-116 行 if (scope == ToolkitScope.MAIN) 块内）
if (scope == ToolkitScope.MAIN) {
    AgentExecutionProperties.React react = executionProperties.getReact();
    // ... spawn_subagent 注册 ...
    if (react != null && react.getDecision() != null && react.getDecision().isEnabled()) {
        tk.registerTool(requestDecisionTool);
        registered.add(RequestDecisionTool.NAME);
    }
}
```

### 5.5 中间件跳过 tool-* 步

改 `ProcessingStepMiddleware.completeToolStep`（`orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepMiddleware.java`，第 250-256 行附近）：

```java
// request_decision 不上 tool-* 步，但须 recordToolCompleted（与 spawn_subagent 同处理）
if (RequestDecisionTool.NAME.equals(toolName)) {
    StepEventBridge.emit(bridgeId, session ->
            session.recordToolCompleted(DecisionLabels.label()));
    StepEventBridge.unbindToolUseBridge(toolUseId);
    return;
}
```

在 `partitionByReadWrite` 中将 `request_decision` 视为只读（不触发写工具串行门闸）。

### 5.6 Controller 端点

新增 `POST /api/generations/{id}/decisions/{token}/resolve`（BFF 转发，对齐 `cancelSubagent` 端点模式）：

```java
@PostMapping("/generations/{id}/decisions/{token}/resolve")
public Mono<Map<String, Object>> resolveDecision(
        @PathVariable String id,
        @PathVariable String token,
        @RequestBody ResolveDecisionRequest request) {  // {choice: string, customInput?: string}
    boolean ok = decisionRegistry.resolve(token, request.choice(), request.customInput());
    if (!ok) {
        // 检查超时/取消 -> 更新 step lifecycle=paused
    }
    return Mono.just(Map.of("accepted", ok));
}
```

**DTO**：

```java
public record ResolveDecisionRequest(String choice, String customInput) {}
```

### 5.7 续跑恢复

`PendingInteraction`（`orchestrator/src/main/java/com/sunshine/orchestrator/plan/PendingInteraction.java`）扩展 `kind=decision` 分支：

```java
public record PendingInteraction(
        String kind,           // "hitl" | "recovery" | "decision"（新增）
        String nodeId,
        String errorMessage,
        String hitlToolId,
        String hitlParamsSummary,
        String recoveryAttemptsJson,
        // 新增 decision 字段
        String decisionToken,
        String decisionQuestion,
        String decisionOptionsJson,    // List<DecisionOption> 序列化
        boolean decisionAllowCustomInput) {
}
```

续跑时 `WorkflowNodeRunner`（ReAct 路径由 `ReActResumeService`）检测 `pending.kind()=="decision"`：
- 重新注册 token + 阻塞等待（**不重新出题**，复用原 question/options）
- 若续跑前用户已决策（`consumeDecisionPreApproval`）-> 跳过二次阻塞，直接返回结果
- 复用 HITL 的 `consumeHitlPreApproval` 机制模式

---

## 6. 前端实现

### 6.1 新建 `DecisionCard.vue`

路径：`sunshine-ui/src/components/operation/DecisionCard.vue`

仿 `SubagentCard.vue` 结构，复用 `CollapsibleConfirmPanel` 容器 + 三兄弟选项样式：

```vue
<template>
  <CollapsibleConfirmPanel
    :summary="summaryLine"
    :resolved="isResolved"
    :default-collapsed="isResolved"
  >
    <p class="decision-question">{{ question }}</p>
    <div class="decision-options" role="listbox" :aria-label="question">
      <button
        v-for="opt in options"
        :key="opt.value"
        type="button"
        role="option"
        class="decision-option-item"
        :class="{ 'is-selected': selected === opt.value }"
        :aria-selected="selected === opt.value"
        :disabled="loading || isResolved"
        @click="select(opt.value)"
      >
        <span class="decision-option-text">
          <span class="decision-option-title">{{ opt.label }}</span>
          <span v-if="opt.description" class="decision-option-desc">{{ opt.description }}</span>
        </span>
        <span class="decision-option-check-slot" aria-hidden="true">
          <NIcon
            v-if="selected === opt.value"
            class="decision-option-check"
            :component="CheckmarkOutline"
            :size="18"
          />
        </span>
      </button>
    </div>
    <textarea
      v-if="showCustomInput"
      v-model="customInput"
      class="decision-custom-input sun-field"
      placeholder="请补充说明"
      rows="2"
    />
    <template v-if="canAct" #footer>
      <button
        type="button"
        class="hitl-btn hitl-btn-primary"
        :disabled="loading || !selected"
        @click="submit"
      >
        {{ loading ? '提交中…' : '提交决策' }}
      </button>
    </template>
  </CollapsibleConfirmPanel>
</template>
```

**props**：

```ts
interface DecisionCardProps {
  step: ProcessingStep
  live: boolean
}
```

**核心逻辑**：
- 从 `step.metadata.decision` 读取 `question` / `options` / `allowCustomInput` / `token` / `expiresAt`
- `selected` ref 跟踪当前选中项（`null` 初始）
- `showCustomInput` computed：选中项 `requireInput=true` 或 `allowCustomInput && selected==='__custom__'`
- `submit()` 调 `resolveDecision(messageId, token, choice, customInput)` -> 成功后标记 `isResolved`
- `isResolved` 由 `step.lifecycle` 推导（`done` / `error` / `paused`）

### 6.2 样式（复用三兄弟变量）

```css
.decision-option-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--sun-border);          /* 卡片内嵌，需边框分区 */
  border-radius: var(--radius-sm, 6px);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.decision-option-item:hover:not(:disabled) {
  border-color: var(--sun-border-light);
  background: var(--sun-row-hover);
}
.decision-option-item.is-selected {
  border-color: var(--sun-accent);              /* 选中态：内描边，无灰底 */
}
.decision-option-item:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.decision-option-text {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.decision-option-title {
  font-size: var(--sun-font-base, 14px);
  font-weight: 500;
  color: var(--sun-text);
}
.decision-option-desc {
  font-size: var(--sun-font-base, 14px);
  color: var(--sun-text-muted);
  white-space: nowrap;
}
.decision-option-check-slot {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 20px;
}
.decision-option-check {
  color: var(--sun-text);
}
.decision-custom-input {
  width: 100%;
  background: var(--sun-black);
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-sm, 6px);
  color: var(--sun-text);
  font-size: var(--sun-font-base, 14px);
  padding: 6px 8px;
  margin-top: 8px;
}
.decision-custom-input:focus {
  border-color: var(--sun-border-light);
  outline: none;
}
```

### 6.3 OperationStack 分发

改 `sunshine-ui/src/components/operation/OperationStack.vue`（第 389-393 行 `SubagentCard` 分支后）：

```vue
<DecisionCard
  v-else-if="step.phase === 'decision'"
  :step="step"
  :live="live && lifecycleOf(step) === 'running'"
  @resolved="onDecisionResolved"
/>
```

### 6.4 决策回传 API

新建 `sunshine-ui/src/api/decisions.ts`：

```ts
export interface DecisionOption {
  value: string
  label: string
  description?: string
  requireInput?: boolean
}

export interface DecisionPayload {
  token: string
  question: string
  options: DecisionOption[]
  allowCustomInput: boolean
  expiresAt: number
}

export async function resolveDecision(
  messageId: string,
  token: string,
  choice: string,
  customInput?: string,
): Promise<boolean> {
  const response = await fetch(
    `${resolveBffStreamBase()}/api/generations/${messageId}/decisions/${token}/resolve`,
    {
      method: 'POST',
      headers: apiHeaders(),
      body: JSON.stringify({ choice, customInput }),
    },
  )
  const body = await parseBffPayload<{ accepted?: boolean }>(response)
  return body.accepted === true
}
```

### 6.5 SSE 消费

`sunshine-ui/src/api/chatSessionSseConsumer.ts` 复用现有 `type:step` 处理逻辑（`phase=decision` 的 step 经 `metaStep` 下发，前端 `sseDispatch` 已能解析）。**不新增** SSE handler。

`processingSteps.ts` 中增加 `phase=decision` 的类型定义和 `metadata.decision` 的解析辅助函数。

---

## 7. 配置（Nacos SSOT）

`docs/nacos/sunshine-orchestrator.yaml` 新增：

```yaml
agent:
  execution:
    react:
      decision:
        enabled: true           # Feature flag
        timeout-sec: 300        # 决策超时（秒）
```

`AgentExecutionProperties.React` 新增内部类（对齐 `Subagent` 配置类，`orchestrator/src/main/java/com/sunshine/orchestrator/config/AgentExecutionProperties.java` 第 35-39 行）：

```java
@Data
public static class Decision {
    private boolean enabled = true;
    private int timeoutSec = 300;
}
```

```java
public static class React {
    private int maxIters = 5;
    private Taskboard taskboard = new Taskboard();
    private Subagent subagent = new Subagent();
    private Decision decision = new Decision();  // 新增
    // ...
}
```

改 YAML 后：`python scripts/sync_nacos.py --data-id sunshine-orchestrator.yaml` -> 重启 orchestrator。

---

## 8. Prompt Catalog

`docker/mysql/init/17-sunshine-prompt-manager.sql` 中：

### 8.1 `mode-overlay.react` 追加使用约束

在 `mode-overlay.react` 的 content_text 末尾追加：

```sql
-- mode-overlay.react 追加内容（UPDATE 语句）
- 【RequestDecision·使用场景】当用户提问存在歧义（多种合理解读）或需要在多个方案间做选择时，调用 `request_decision` 向用户出选择题。
- 【RequestDecision·禁止场景】不要用于工具调用确认（工具确认由平台自动拦截）；不要在用户已明确表达意图后仍出题；不要在无需用户决策时滥用。
- 【RequestDecision·选项要求】至少 2 个选项；每项 value 简短（英文蛇形）、label 中文、description 说明取舍；requireInput 仅在该选项需要用户补充说明时设 true。
- 【RequestDecision·续跑】用户补充的自定义答案视为最终决策，直接执行，不要再追问。
- 【RequestDecision·与 TaskBoard 分工】清单规划用 todo_write；需求澄清/方案抉择用 request_decision。
```

### 8.2 新增 `timeline.steps.decision`

```sql
INSERT IGNORE INTO prompt_definition (id, kind, display_name, ...) 
VALUES ('timeline.steps.decision', 'timeline', '时间线 · 决策', ...);

INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, ...)
VALUES ('timeline.steps.decision', 1, 'published', 
'{"before":"正在等待用户决策","active":"决策中：{question}","after":"用户已选择：{choice}"}',
...);
```

---

## 9. 边界与非目标

**做**

- ReAct MAIN 元工具 `request_decision` + 阻塞唤醒 + 主时间线决策卡片 + 暂停/续跑恢复
- 复用 `HitlTokenRegistry` 阻塞模式 + `CollapsibleConfirmPanel` 容器 + 三兄弟选项样式
- 支持选项 `requireInput` 附加文本输入 + `allowCustomInput` 自定义答案

**不做**

- 子 Agent 调用 `request_decision`（仅 MAIN）
- Workflow / Plan 节点内调用（本版仅 ReAct MAIN）
- 决策历史多轮（单次决策即回传，不维护 rounds[]）
- 前端硬编码问题/选项模板（全部由 Agent 生成，后端 SSE 下发）
- 对决策结果二次加工/过滤兜底

**锁定决策**

- 决策选择是**独立路径**，不混用工具确认 HITL（用户已拍板）
- 阻塞唤醒复用 `HitlTokenRegistry` 模式，不重复造轮子
- 前端样式复用三兄弟，不引入新 CSS 变量
- 元工具**不进 tool-manager Catalog**（与 spawn_subagent 一致）

---

## 10. 检查门

| # | 场景 | 期望 |
|---|------|------|
| D1 | Agent 调用 `request_decision`（2 选项） | 主时间线一张决策卡片 + 问题 + 2 选项；Agent 阻塞等待 |
| D2 | 用户选择某项 | 卡片 `lifecycle=done`；Agent 收到 tool result 含选择结果；继续推理 |
| D3 | 选项 `requireInput=true` | 选中后展开 textarea；提交时 customInput 一并回传 |
| D4 | `allowCustomInput=true` | 选项列表末尾显示"自定义"项；选中后展开 textarea |
| D5 | 暂停（用户点停止） | 卡片 `lifecycle=paused`；`PendingInteraction(kind=decision)` 落库 |
| D6 | 续跑恢复 | re-await 同一决策（不重新出题）；用户已决策则跳过二次阻塞 |
| D7 | 超时 | 卡片 `lifecycle=paused`；Agent 收到超时 tool result |
| D8 | SUB Agent 调用 | 硬拒；错误进 tool result；无决策卡片 |
| D9 | 选项 < 2 | 硬拒；错误进 tool result；无决策卡片 |
| D10 | 样式一致性 | 选项行对号 18px、透明底、边框分区、内描边选中（与三兄弟一致） |

Live：`scripts/verify_decision_live.py`（仿 `verify_spawn_subagent_live.py`）。

---

## 11. 文档与编号同步（实施时）

- [ ] `phase4-platformization-design.md` §4.7：新增 **4.7.7**
- [ ] `implementation-plan.md` 阶段四 4.7 行同步
- [ ] `CLAUDE.md` 时间线表增补 ReAct `decision-*` 一行；架构扩展表增补 `request_decision` 元工具
- [ ] 本目录 README / specs 索引按需挂链

---

## 12. 风险与对策

| 风险 | 对策 |
|------|------|
| Agent 滥调导致频繁打断 | Prompt overlay 约束使用场景；可选后续加 max-per-session（本版不强制） |
| 决策卡片与 HITL 确认框视觉混淆 | `phase=decision` 独立卡片（非内联 HITL）；问题文本 + 选项列表形态区别于按钮组 |
| 并行多个 request_decision | 同轮多 tool_call 各自独立卡片 + token；不强制全局串行（与 spawn_subagent 一致） |
| 续跑 re-await 时 token 失效 | `PendingInteraction` 保留原 question/options；续跑重新注册 token |
| 决策结果被前端兜底过滤 | 约定：前端原样展示 question/options；不对模型输出截断/去重 |

---

## 13. 改动文件清单

| 层 | 文件 | 改动类型 |
|----|------|----------|
| 后端-元工具 | `orchestrator/.../agent/RequestDecisionTool.java` | **新建** |
| 后端-阻塞 | `orchestrator/.../agent/DecisionRegistry.java` | **新建** |
| 后端-时间线 | `orchestrator/.../agent/DecisionTimelineSupport.java` | **新建** |
| 后端-标签 | `orchestrator/.../processing/DecisionLabels.java` | **新建** |
| 后端-DTO | `orchestrator/.../model/ResolveDecisionRequest.java` | **新建** |
| 后端-DTO | `orchestrator/.../model/DecisionOption.java` | **新建** |
| 后端-DTO | `orchestrator/.../model/DecisionResult.java` | **新建** |
| 后端-注册 | `orchestrator/.../agent/DynamicToolkitFactory.java` | 修改（MAIN 注入 + 白名单拒绝） |
| 后端-中间件 | `orchestrator/.../agent/ProcessingStepMiddleware.java` | 修改（跳过 tool-* 步） |
| 后端-Controller | `orchestrator/.../controller/GenerationController.java` | 修改（新增 resolve 端点） |
| 后端-配置 | `orchestrator/.../config/AgentExecutionProperties.java` | 修改（新增 Decision 内部类） |
| 后端-续跑 | `orchestrator/.../plan/PendingInteraction.java` | 修改（新增 decision 字段） |
| 后端-续跑 | `orchestrator/.../execution/workflow/WorkflowNodeRunner.java` | 修改（kind=decision 分支） |
| 后端-SSE | `orchestrator/.../conversation/GenerationFlushScheduler.java` | 复用 metaStep（无改动） |
| DB-Prompt | `docker/mysql/init/17-sunshine-prompt-manager.sql` | 修改（mode-overlay.react 追加 + timeline.steps.decision） |
| Nacos | `docs/nacos/sunshine-orchestrator.yaml` | 修改（decision 配置） |
| 前端-卡片 | `sunshine-ui/src/components/operation/DecisionCard.vue` | **新建** |
| 前端-分发 | `sunshine-ui/src/components/operation/OperationStack.vue` | 修改（phase=decision 分支） |
| 前端-API | `sunshine-ui/src/api/decisions.ts` | **新建** |
| 前端-类型 | `sunshine-ui/src/api/processingSteps.ts` | 修改（phase=decision + metadata.decision） |
| 运维 | `scripts/verify_decision_live.py` | **新建** |

---

## 14. 自检清单

- [x] 无 TBD/TODO 占位需求
- [x] 与 §1-§3 对话结论一致（独立路径、元工具、阻塞复用、样式复用、输入支持）
- [x] 范围可落单一实施计划（4.7.7）
- [x] 工具名/参数/UI/Nacos/检查门无歧义
- [x] 不混用工具确认 HITL（用户已拍板）
- [x] 不引入新 SSE type（复用 metaStep）
- [x] 不引入新 CSS 变量（复用 --sun-* + 三兄弟样式）
- [x] 元工具不进 tool-manager Catalog

