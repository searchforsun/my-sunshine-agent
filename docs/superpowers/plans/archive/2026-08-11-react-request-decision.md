> **状态**：✅ 已实现 · **已归档**（2026-08-11 tech-debt-refactor）

# 4.7.9 ReAct Request Decision（Chat MAIN）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Chat ReAct MAIN 可通过元工具 `request_decision` 主动出选择题并硬阻塞等待用户决策；主时间线一张 `phase=decision` 卡片；resolve API + BFF + DecisionCard；暂停/续跑同一题 re-await。

**Architecture:** 仿 `spawn_subagent` 做 orchestrator 内置元工具（不进 tool-service）；阻塞唤醒仿 `HitlTokenRegistry` 但独立 `DecisionRegistry`（Redis 前缀 `sunshine:decision:pending:`）；时间线 `DecisionTimelineSupport` 下发 `decision-{token}` 步；resolve 挂 `/api/generations/{id}/decisions/{token}/resolve`（对齐 cancelSubagent，**不**走 confirm-tool）。续跑走 `findReactAwaitingDecisionStep` + `DecisionResumeSupport` + pre-approval，**禁止**改 `WorkflowNodeRunner`。

**Tech Stack:** AgentScope-Java Toolkit/Middleware · Spring WebFlux · Redis · Vue3/Naive UI · Nacos `docs/nacos/sunshine-orchestrator.yaml` · Prompt Catalog `docker/mysql/init/19-sunshine-resource.sql` · Live `scripts/verify_decision_live.py`

**Spec:** [2026-07-28-react-request-decision-design.md](../../specs/archive/2026-07-28-react-request-decision-design.md)

## Global Constraints

- **本计划范围 = Chat ReAct MAIN only**；Planner-Executor / Worker / SUB / Expert / 静态 Workflow **全部不做**（spec D14 Planner MAIN 留后续切片；本切片仅在 `ToolkitScope.MAIN` 按 `react.decision.enabled` 注册，与 `spawn_subagent` 同门控——将来 Planner 若复用 MAIN toolkit 会自动带上工具，但不写 Planner harness/续跑/UI）。
- `enabled` 默认 **false**（D21）；Live 前再开。
- lifecycle 等待用户 = **`awaiting`**（D17）；已提交 `done`；超时/停止 `paused`；工具异常 `error`。
- tool result 固定短格式 `choice=/label=/customInput=`（D18）；**禁止**对 question/options 截断/摘要/过滤兜底。
- 同 `messageId` 同时最多 **1** 个 awaiting decision（D15）。
- UI **自建** `DecisionCard`，零依赖 `CollapsibleConfirmPanel` / PlanApproval（D16）。
- 不新增 SSE type；仅 `type:step` + `metadata.decision`。
- **禁止**改 `WorkflowNodeRunner`；**不**扩展 Workflow `PendingInteraction`（ReAct 快照 = `assistant.steps` 里的 `decision-*` 步）。
- 改 Nacos → `python scripts/sync_nacos.py` → 重启 orchestrator；改后端 → `python scripts/start.py --restart orchestrator`（BFF 同理）。
- Prompt SSOT = `19-sunshine-resource.sql`（D22）；任务板工具名 `todo_write`。
- 4.7.7 FailureBudget/GoalAlignment **尚未落地**：Middleware 只做元工具跳过；代码注释注明「落地时不计/不含 request_decision」，勿预埋空壳类。

---

## File map

| 文件 | 职责 |
|------|------|
| `orchestrator/.../agent/RequestDecisionTool.java` | 元工具：校验 → register → begin → await → 短格式 result |
| `orchestrator/.../agent/DecisionRegistry.java` | Future + Redis；register / await / resolve / cancelWaitersForMessage / hasAwaiting |
| `orchestrator/.../agent/DecisionTimelineSupport.java` | begin / complete / pause / fail |
| `orchestrator/.../agent/DecisionResumeSupport.java` | 续跑 re-await + pre-approval 消费 |
| `orchestrator/.../agent/DecisionOption.java` / `DecisionResult.java` | 入参/结果 record |
| `orchestrator/.../agent/ResolveDecisionRequest.java` | API body |
| `orchestrator/.../processing/DecisionStepMeta.java` | `metadata.decision` |
| `orchestrator/.../processing/DecisionLabels.java` + `DecisionLabelService.java` | Catalog `timeline.steps.decision` |
| `orchestrator/.../processing/StepMetadata.java` + Assembler + Serde | 增加 `decision` 字段 |
| `orchestrator/.../agent/DynamicToolkitFactory.java` | MAIN 注册 + 白名单跳过 |
| `orchestrator/.../agent/ProcessingStepMiddleware.java` | 不上 tool-*；recordToolCompleted；只读批 |
| `orchestrator/.../agent/ProcessingStepLifecycleOps.java` | `findReactAwaitingDecisionStep` |
| `orchestrator/.../generation/GenerationController.java` | resolve API |
| `orchestrator/.../generation/GenerationRegistry.java` | cancel 时 cancelDecisionWaiters |
| `orchestrator/.../config/AgentExecutionProperties.java` | `react.decision` |
| `docs/nacos/sunshine-orchestrator.yaml` | enabled/timeout-sec |
| `docker/mysql/init/19-sunshine-resource.sql` | overlay + timeline.steps.decision |
| `bff/.../OrchestratorClient.java` + `GenerationController.java` | 透传 |
| `sunshine-ui/.../DecisionCard.vue` + `OperationStack.vue` + `api/decisions.ts` + `processingSteps.ts` | UI |
| `scripts/verify_decision_live.py` | Live D1–D11（Chat MAIN） |

---

### Task 1: Nacos 配置 + AgentExecutionProperties.Decision

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/config/AgentExecutionProperties.java`
- Modify: `docs/nacos/sunshine-orchestrator.yaml`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/config/AgentExecutionPropertiesDecisionTest.java`

**Interfaces:**
- Produces: `AgentExecutionProperties.React.Decision`（`enabled` 默认 `false`，`timeoutSec` 默认 `300`）；getter `getReact().getDecision()`

- [ ] **Step 1: 写失败单测**

```java
package com.sunshine.orchestrator.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutionPropertiesDecisionTest {
    @Test
    void decision_defaults_disabled_and_timeout300() {
        AgentExecutionProperties.React.Decision d = new AgentExecutionProperties.React.Decision();
        assertThat(d.isEnabled()).isFalse();
        assertThat(d.getTimeoutSec()).isEqualTo(300);
    }
}
```

- [ ] **Step 2: Run 确认失败/或默认尚未存在字段导致编译失败**

```bash
cd /usr/local/gitproj/my-sunshine-agent && mvn -pl orchestrator -Dtest=AgentExecutionPropertiesDecisionTest test
```

Expected: 编译失败（无 `Decision` 类）或断言失败。

- [ ] **Step 3: 实现 Properties**

在 `AgentExecutionProperties.React` 内、`Subagent` 旁追加：

```java
/** 4.7.9 request_decision — SSOT：Nacos agent.execution.react.decision */
private Decision decision = new Decision();

@Data
public static class Decision {
    /** D21：默认关，Live/灰度再开 */
    private boolean enabled = false;
    private int timeoutSec = 300;
}
```

- [ ] **Step 4: Nacos YAML**

在 `docs/nacos/sunshine-orchestrator.yaml` 的 `agent.execution.react.subagent` 块后追加：

```yaml
      decision:
        enabled: false
        timeout-sec: 300
```

- [ ] **Step 5: Run 单测通过**

```bash
mvn -pl orchestrator -Dtest=AgentExecutionPropertiesDecisionTest test
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/config/AgentExecutionProperties.java \
  orchestrator/src/test/java/com/sunshine/orchestrator/config/AgentExecutionPropertiesDecisionTest.java \
  docs/nacos/sunshine-orchestrator.yaml
git commit -m "$(cat <<'EOF'
feat(4.7.9): add react.decision Nacos flags (default off)

EOF
)"
```

---

### Task 2: Decision DTOs + metadata.decision 序列化

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionOption.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionResult.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ResolveDecisionRequest.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/processing/DecisionStepMeta.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/processing/StepMetadata.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/processing/StepMetadataAssembler.java`（所有 `new StepMetadata(...)` / `copy` 补 `decision` 参）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepSerde.java`（读写 `decision`）
- Modify: `sunshine-ui/src/api/processingSteps.ts`（`StepMetadata.decision`）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/agent/ProcessingStepSerdeDecisionTest.java`

**Interfaces:**
- Produces:
  - `DecisionOption(String value, String label, String description, boolean requireInput)`
  - `DecisionResult(String choice, String customInput, long decidedAt)`
  - `ResolveDecisionRequest(String choice, String customInput)`
  - `DecisionStepMeta(String token, String question, List<DecisionOptionView> options, boolean allowCustomInput, Long expiresAt, String choice, String customInput)` — 嵌套 options 用简单 record/Map 均可，但 Serde 键名必须与 SSE 示例一致：`token/question/options/allowCustomInput/expiresAt`
  - `StepMetadata.withDecision(base, DecisionStepMeta)` / `decision()` accessor

- [ ] **Step 1: 写 Serde 往返失败测**

```java
@Test
void serde_roundTrip_preservesDecisionMetadata() {
    DecisionStepMeta decision = new DecisionStepMeta(
            "tok-1",
            "您希望按哪种方式处理？",
            List.of(
                    Map.of("value", "plan_a", "label", "方案A", "description", "快", "requireInput", false),
                    Map.of("value", "plan_b", "label", "方案B", "description", "全", "requireInput", true)),
            false,
            1753721880000L,
            null,
            null);
    // 用 StepMetadataAssembler.withDecision(null, decision) 构造步后 toJson/fromJson
    // assert token/question/options.size/requireInput/expiresAt 一致
}
```

> 实现时 `DecisionStepMeta.options` 建议用 `List<DecisionOption>`（或专用 view record），Serde 写出 `requireInput` 布尔；**勿**把 options 截断。

- [ ] **Step 2: Run 确认失败**

```bash
mvn -pl orchestrator -Dtest=ProcessingStepSerdeDecisionTest test
```

- [ ] **Step 3: 实现 DTOs + StepMetadata 字段**

`DecisionStepMeta.java`（示例骨架）：

```java
package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.DecisionOption;
import java.util.List;

public record DecisionStepMeta(
        String token,
        String question,
        List<DecisionOption> options,
        boolean allowCustomInput,
        Long expiresAt,
        String choice,
        String customInput) {
}
```

`StepMetadata` record 末尾增加 `DecisionStepMeta decision`；`StepMetadataAssembler.withDecision` + 全量 `copy`/`new` 补参（编译器会标出所有调用点）。

`ProcessingStepSerde`：写出 `map.put("decision", {...})`；读入时解析 options 列表。

前端 `processingSteps.ts`：

```ts
export interface DecisionOptionView {
  value: string
  label: string
  description?: string
  requireInput?: boolean
}

export interface DecisionMeta {
  token?: string
  question?: string
  options?: DecisionOptionView[]
  allowCustomInput?: boolean
  expiresAt?: number
  choice?: string
  customInput?: string
}

// StepMetadata 内:
decision?: DecisionMeta

export function isDecisionStep(step: { id?: string; phase?: string }): boolean {
  return step.phase === 'decision' || !!step.id?.startsWith('decision-')
}
```

- [ ] **Step 4: Run 单测通过 + orchestrator 编译**

```bash
mvn -pl orchestrator -Dtest=ProcessingStepSerdeDecisionTest test
mvn -pl orchestrator -DskipTests compile
```

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionOption.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionResult.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/agent/ResolveDecisionRequest.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/processing/DecisionStepMeta.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/processing/StepMetadata.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/processing/StepMetadataAssembler.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepSerde.java \
  orchestrator/src/test/java/com/sunshine/orchestrator/agent/ProcessingStepSerdeDecisionTest.java \
  sunshine-ui/src/api/processingSteps.ts
git commit -m "$(cat <<'EOF'
feat(4.7.9): add decision DTOs and step metadata.decision serde

EOF
)"
```

---

### Task 3: DecisionRegistry（阻塞唤醒）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionRegistry.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionPendingWaiter.java`（可选，也可做 Registry 内部 record）
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/agent/DecisionRegistryTest.java`

**Interfaces:**
- Consumes: `AgentExecutionProperties.React.Decision.timeoutSec`；`StringRedisTemplate`；`ObjectMapper`
- Produces:
  - `record Registration(String token, CompletableFuture<DecisionResult> future, long expiresAt)`
  - `Registration register(String messageId, String userId, String question, List<DecisionOption> options, boolean allowCustomInput)` — 若该 message 已有 awaiting → 抛/返回错误由 Tool 侧处理；Registry 提供 `boolean hasAwaiting(String messageId)`
  - `DecisionResult awaitDecision(Registration reg)` — 超时抛/返回由 Tool 解释为 `__timeout__`；`CancellationException` → `__cancelled__`
  - `ResolveOutcome resolve(String token, String choice, String customInput, String currentUserId)` — 校验 choice ∈ options∪`__custom__`；requireInput/custom 非空；失败不 complete Future
  - `void cancelWaitersForMessage(String messageId)`
  - `void cleanup(String token)`
  - Redis key = `sunshine:decision:pending:` + token；TTL = `timeoutSec + 30`
  - Redis payload 至少含：`messageId,userId,expiresAt,question,optionsJson,allowCustomInput`

- [ ] **Step 1: 写失败单测**

```java
@ExtendWith(MockitoExtension.class)
class DecisionRegistryTest {
    @Test
    void register_secondAwaitingOnSameMessage_rejected() { /* hasAwaiting true → register 拒绝 */ }

    @Test
    void resolve_requireInputBlank_returnsInputRequired_andDoesNotComplete() { /* ... */ }

    @Test
    void resolve_validChoice_completesFuture() throws Exception { /* future.get 得到 DecisionResult */ }

    @Test
    void cancelWaitersForMessage_cancelsFuture() { /* future.isCancelled */ }
}
```

- [ ] **Step 2: Run 确认失败**

```bash
mvn -pl orchestrator -Dtest=DecisionRegistryTest test
```

- [ ] **Step 3: 实现 Registry**

骨架对齐 `HitlTokenRegistry`，差异：
- Future 类型 `CompletableFuture<DecisionResult>`（非 Boolean）
- `resolve` 做 choice/customInput 校验；错误用枚举/record：`ACCEPTED | INVALID_CHOICE | INPUT_REQUIRED | EXPIRED | NOT_FOUND | FORBIDDEN`
- `hasAwaiting(messageId)`：扫内存 waiters（同消息未 complete）

`awaitDecision` 可放在 Registry 或单独 `DecisionAwaitSupport`；建议 Registry 提供 `await`：

```java
public DecisionResult await(Registration reg) throws InterruptedException {
    try {
        return reg.future().get(timeoutSec(), TimeUnit.SECONDS);
    } catch (TimeoutException e) {
        cleanup(reg.token());
        return new DecisionResult("__timeout__", null, System.currentTimeMillis());
    } catch (CancellationException e) {
        cleanup(reg.token());
        return new DecisionResult("__cancelled__", null, System.currentTimeMillis());
    } catch (ExecutionException e) {
        cleanup(reg.token());
        throw new IllegalStateException(e.getCause() != null ? e.getCause() : e);
    } finally {
        cleanup(reg.token());
    }
}
```

> 注意：成功路径 `resolve` 已 complete + del Redis；`finally cleanup` 须幂等。

- [ ] **Step 4: Run 单测通过**

```bash
mvn -pl orchestrator -Dtest=DecisionRegistryTest test
```

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionRegistry.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionPendingWaiter.java \
  orchestrator/src/test/java/com/sunshine/orchestrator/agent/DecisionRegistryTest.java
git commit -m "$(cat <<'EOF'
feat(4.7.9): add DecisionRegistry with Redis pending tokens

EOF
)"
```

---

### Task 4: DecisionLabels + DecisionTimelineSupport

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/processing/DecisionLabels.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/processing/DecisionLabelService.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionTimelineSupport.java`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/agent/DecisionTimelineSupportTest.java`

**Interfaces:**
- Consumes: `TimelinePromptCatalog.steps().get("decision")`；`StepEventBridge.emit`
- Produces:
  - `DecisionLabels.before()/active(question)/after(choice)/afterTimeout()/afterCancel()`
  - `DecisionTimelineSupport.begin(bridgeId, token, question, options, allowCustomInput, expiresAt)` → 步 `id=decision-{token}`，`phase=decision`，`lifecycle=awaiting`，`metadata.decision=...`
  - `complete(bridgeId, token, DecisionResult, labelForChoice)` → `lifecycle=done`，summary.after，metadata 补 choice/customInput
  - `pause(bridgeId, token, afterText)` → `lifecycle=paused`
  - `fail(bridgeId, token, errorMsg)` → `lifecycle=error`

- [ ] **Step 1: 写 begin 发卡单测**（Mock `StepEventBridge` 或用可捕获 session；对齐 `SpawnSubagentTimelineSupportTest` 风格）

```java
@Test
void begin_emitsDecisionStepWithAwaitingLifecycle() {
    // bind 假 Labels；调用 begin；断言 step.id 以 decision- 开头、phase=decision、lifecycle=awaiting
    // metadata.decision.question/options 原文保留
}
```

- [ ] **Step 2: Run 确认失败**

```bash
mvn -pl orchestrator -Dtest=DecisionTimelineSupportTest test
```

- [ ] **Step 3: 实现 Labels + TimelineSupport**

`DecisionLabelService` 仿 `SpawnSubagentLabelService`：`@PostConstruct DecisionLabels.bind(this)`；读 `timeline.steps.decision`；缺省：

| key | default |
|-----|---------|
| before | 正在等待用户决策 |
| active | 等待决策：{question} |
| after | 用户已选择：{choice} |
| after-fail | 决策失败 |
| after-cancel | 已取消 |

`begin` 构造 `ProcessingStep` 时：

```java
String stepId = "decision-" + token;
StepMetadata meta = StepMetadataAssembler.withDecision(null, new DecisionStepMeta(
        token, question, options, allowCustomInput, expiresAt, null, null));
StepSummary summary = new StepSummary(
        DecisionLabels.before(),
        DecisionLabels.active(question),
        null);
// lifecycle = "awaiting"；phase = "decision"；label 可用 question 或 Catalog label
session.enqueueAuxiliary(StreamToken.step(card));
```

- [ ] **Step 4: Run 通过**

```bash
mvn -pl orchestrator -Dtest=DecisionTimelineSupportTest test
```

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/processing/DecisionLabels.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/processing/DecisionLabelService.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionTimelineSupport.java \
  orchestrator/src/test/java/com/sunshine/orchestrator/agent/DecisionTimelineSupportTest.java
git commit -m "$(cat <<'EOF'
feat(4.7.9): add DecisionTimelineSupport and catalog labels facade

EOF
)"
```

---

### Task 5: RequestDecisionTool（元工具）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/RequestDecisionTool.java`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/agent/RequestDecisionToolTest.java`

**Interfaces:**
- Consumes: Task1 Decision config；Task3 Registry；Task4 TimelineSupport；`StepEventBridge.activeMainBridge/activeMessageId/activeBridgeId`；pre-approval（Task 11 会补 `consumeDecisionPreApproval`，本 Task 先留调用点：若 Bridge 尚无方法则先 `false`，Task 11 接上）
- Produces: `@Tool(name="request_decision") String requestDecision(question, optionsJson, allowCustomInput)`；常量 `NAME = "request_decision"`；短格式 result helpers

**校验（失败均 `{"ok":false,"error":"..."}`，不下发卡片）**：
1. `!decision.enabled`
2. `question` 空白或 `>500` 字
3. `options` 解析后 `null || size < 2`；任一项 `value`/`label` 空白；`value` 重复；`label>64`；`description>256`
4. 无 mainBridge / activeBridge 以 `sub-` 开头 → 硬拒
5. `DecisionRegistry.hasAwaiting(messageId)` → 硬拒（D15）

**成功路径**：
1. 若 `StepEventBridge.consumeDecisionPreApproval(messageId, fingerprint)` → 直接短格式（Task 11）
2. `register` → `timeline.begin` → `await`
3. `choice=__timeout__` / `__cancelled__` → `timeline.pause` + 对应短格式
4. 正常 → `timeline.complete` +：

```text
choice={value}
label={label}
customInput={text or empty}
```

超时：

```text
choice=__timeout__
timeoutSec={n}
```

取消：

```text
choice=__cancelled__
```

- [ ] **Step 1: 写失败单测**

```java
@Test void NAME_equals_request_decision() { ... }

@Test void disabled_returnsErrorJson_withoutCard() { ... }

@Test void optionsFewerThan2_returnsErrorJson() { ... }

@Test void duplicateValue_returnsErrorJson() { ... }

@Test void subBridge_returnsErrorJson() { ... }

@Test void success_formatsShortResult() {
    // mock registry.await 返回 DecisionResult("plan_a", null, ts)
    // assert 输出含 choice=plan_a 与 label=
}
```

- [ ] **Step 2: Run 确认失败**

```bash
mvn -pl orchestrator -Dtest=RequestDecisionToolTest test
```

- [ ] **Step 3: 实现工具**

`options` 参数用 JSON 字符串（AgentScope 对复杂 List 不稳定时更稳）：

```java
@Tool(name = NAME, description = "需求歧义或多方案抉择时向用户出选择题并等待决策；勿用于写工具确认。")
public String requestDecision(
        @ToolParam(name = "question", description = "决策问题（中文）") String question,
        @ToolParam(name = "options", description = "JSON 数组：[{value,label,description?,requireInput?}]，≥2") String optionsJson,
        @ToolParam(name = "allow_custom_input", description = "是否允许自定义输入，默认 false") Boolean allowCustomInput) {
    ...
}
```

MAIN 判定复制 `SpawnSubagentTool`：

```java
String mainBridge = StepEventBridge.activeMainBridge(messageId);
if (!StringUtils.hasText(mainBridge)) return errorJson("request_decision 仅可从主 Agent 调用");
String activeBridge = StepEventBridge.activeBridgeId();
if (StringUtils.hasText(activeBridge) && activeBridge.startsWith("sub-"))
    return errorJson("子 Agent 不可调用 request_decision");
```

- [ ] **Step 4: Run 通过**

```bash
mvn -pl orchestrator -Dtest=RequestDecisionToolTest test
```

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/RequestDecisionTool.java \
  orchestrator/src/test/java/com/sunshine/orchestrator/agent/RequestDecisionToolTest.java
git commit -m "$(cat <<'EOF'
feat(4.7.9): add request_decision meta-tool with validation and short result

EOF
)"
```

---

### Task 6: DynamicToolkitFactory + ProcessingStepMiddleware

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/DynamicToolkitFactory.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepMiddleware.java`
- Modify: `orchestrator/src/test/java/com/sunshine/orchestrator/agent/DynamicToolkitFactoryTest.java`
- Modify: `orchestrator/src/test/java/com/sunshine/orchestrator/agent/ProcessingStepMiddlewareTest.java`

**Interfaces:**
- Consumes: `RequestDecisionTool` bean；`react.decision.enabled`
- Produces: MAIN 注册 `request_decision`；SUB 永不注册；whitelist 遇该名 skip；Middleware：`onActing` 跳过开 tool-*；`recordToolCompleted`；partition 视为只读

- [ ] **Step 1: 扩展失败/待写单测**

```java
// DynamicToolkitFactoryTest
@Test
void build_withDecisionEnabled_registersRequestDecision() { ... }

@Test
void buildForSubAgent_doesNotRegisterRequestDecision() { ... }

// ProcessingStepMiddlewareTest
@Test
void onActing_requestDecision_recordsCompletedWithoutToolStep() {
    // 对齐 onActingSpawnSubagentRecordsCompletedWithoutToolStep
}
```

- [ ] **Step 2: Run 确认失败**

```bash
mvn -pl orchestrator -Dtest=DynamicToolkitFactoryTest,ProcessingStepMiddlewareTest test
```

- [ ] **Step 3: 实现注册与跳过**

`DynamicToolkitFactory`：
1. 注入 `RequestDecisionTool`
2. whitelist 循环：`if (toolName.equals(RequestDecisionTool.NAME)) { log.warn(...); continue; }`
3. MAIN 块：

```java
if (react != null && react.getDecision() != null && react.getDecision().isEnabled()) {
    tk.registerTool(requestDecisionTool);
    registered.add(RequestDecisionTool.NAME);
}
```

`ProcessingStepMiddleware`：
1. `onActing`：与 spawn 同分支跳过开步：

```java
if (RequestDecisionTool.NAME.equals(toolName)
        || SpawnSubagentTool.NAME.equals(toolName)
        || TodoTasksBridge.isTodoWrite(toolName)) {
    continue;
}
```

2. 结果处理：`request_decision` 与 spawn 一样 `recordToolCompleted(DecisionLabels.label())`（或固定「用户决策」），并 `unbindToolUseBridge`
3. 类注释更新：元工具列表含 `request_decision`；注明 FailureBudget 落地时不计本工具

- [ ] **Step 4: Run 通过**

```bash
mvn -pl orchestrator -Dtest=DynamicToolkitFactoryTest,ProcessingStepMiddlewareTest test
```

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/DynamicToolkitFactory.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepMiddleware.java \
  orchestrator/src/test/java/com/sunshine/orchestrator/agent/DynamicToolkitFactoryTest.java \
  orchestrator/src/test/java/com/sunshine/orchestrator/agent/ProcessingStepMiddlewareTest.java
git commit -m "$(cat <<'EOF'
feat(4.7.9): register request_decision on MAIN toolkit and skip tool-* steps

EOF
)"
```

---

### Task 7: resolve API + cancel 释放 Decision waiters

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/exception/OrchestratorErrorCode.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/generation/GenerationController.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/generation/GenerationRegistry.java`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/generation/GenerationDecisionResolveTest.java`（可用 `@WebFluxTest` 或纯 service 测 Registry.resolve 映射；若 WebFlux 重，则测 Controller 委托的 package-private helper / 直接测 error 映射函数）

**Interfaces:**
- Produces:
  - `POST /generations/{id}/decisions/{token}/resolve` body `ResolveDecisionRequest` → `{accepted:true}` 或 400
  - ErrorCode：`DECISION_INVALID_CHOICE` / `DECISION_INPUT_REQUIRED` / `DECISION_EXPIRED` / `DECISION_NOT_FOUND`（key 分别 `decision_invalid_choice` 等，与 spec 一致）
  - `GenerationRegistry.releaseBlockingWaits` 增加 `decisionRegistry.cancelWaitersForMessage(messageId)`

- [ ] **Step 1: 写错误码映射测 + resolve 成功测**

```java
@Test
void resolve_mapsInputRequiredTo400() { ... }

@Test
void resolve_acceptedReturnsTrue() { ... }
```

- [ ] **Step 2: Run 确认失败**

```bash
mvn -pl orchestrator -Dtest=GenerationDecisionResolveTest test
```

- [ ] **Step 3: 实现 Controller**

```java
@PostMapping("/generations/{id}/decisions/{token}/resolve")
public Mono<Map<String, Object>> resolveDecision(
        @PathVariable("id") String id,
        @PathVariable("token") String token,
        @RequestBody ResolveDecisionRequest body,
        @RequestHeader("x-user-id") String userId,
        @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
    return ReactiveBlocking.call(() -> {
        streamService.assertOwned(id, userId, tenantId);
        GenerationMeta meta = streamService.getMeta(id)
                .orElseThrow(() -> new BizException(OrchestratorErrorCode.GENERATION_NOT_FOUND));
        var outcome = decisionRegistry.resolve(
                token, body != null ? body.choice() : null,
                body != null ? body.customInput() : null, userId);
        return switch (outcome) {
            case ACCEPTED -> Map.of("accepted", true);
            case INVALID_CHOICE -> throw new BizException(OrchestratorErrorCode.DECISION_INVALID_CHOICE);
            case INPUT_REQUIRED -> throw new BizException(OrchestratorErrorCode.DECISION_INPUT_REQUIRED);
            case EXPIRED -> throw new BizException(OrchestratorErrorCode.DECISION_EXPIRED);
            case NOT_FOUND, FORBIDDEN -> throw new BizException(OrchestratorErrorCode.DECISION_NOT_FOUND);
        };
    });
}
```

`GenerationRegistry.releaseBlockingWaits`：

```java
if (decisionRegistry != null) {
    decisionRegistry.cancelWaitersForMessage(messageId);
}
```

（构造注入 `ObjectProvider<DecisionRegistry>` 或必选依赖，与 HITL 一致。）

- [ ] **Step 4: Run 通过**

```bash
mvn -pl orchestrator -Dtest=GenerationDecisionResolveTest,DecisionRegistryTest test
```

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/exception/OrchestratorErrorCode.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/generation/GenerationController.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/generation/GenerationRegistry.java \
  orchestrator/src/test/java/com/sunshine/orchestrator/generation/GenerationDecisionResolveTest.java
git commit -m "$(cat <<'EOF'
feat(4.7.9): add generations decisions resolve API and cancel waiters

EOF
)"
```

---

### Task 8: BFF 透传 + 前端 API

**Files:**
- Modify: `bff/src/main/java/com/sunshine/bff/client/OrchestratorClient.java`
- Modify: `bff/src/main/java/com/sunshine/bff/controller/GenerationController.java`
- Create: `sunshine-ui/src/api/decisions.ts`

**Interfaces:**
- Produces:
  - BFF `POST /api/generations/{id}/decisions/{token}/resolve`
  - `resolveDecision(generationId, token, choice, customInput?)`（前端）

- [ ] **Step 1: 实现 BFF Client**

```java
public Mono<Map<String, Object>> resolveDecision(
        String generationId, String token, Map<String, Object> body,
        String userId, String tenantId) {
    return webClient.post()
            .uri("/generations/{id}/decisions/{token}/resolve", generationId, token)
            .header("x-user-id", userId)
            .header("x-tenant-id", tenantId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body != null ? body : Map.of())
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
}
```

BFF Controller（旁路 `cancelSubagent`）：

```java
@PostMapping("/api/generations/{id}/decisions/{token}/resolve")
public Mono<Map<String, Object>> resolveDecision(
        @PathVariable String id,
        @PathVariable String token,
        @RequestBody Map<String, Object> body,
        @RequestHeader("x-user-id") String userId,
        @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
    return client.resolveDecision(id, token, body, userId, tenantId);
}
```

- [ ] **Step 2: 前端 `decisions.ts`**

```ts
import { API_BASE, apiHeaders } from './http'

export async function resolveDecision(
  generationId: string,
  token: string,
  choice: string,
  customInput?: string,
): Promise<{ accepted?: boolean }> {
  const res = await fetch(
    `${API_BASE()}/api/generations/${encodeURIComponent(generationId)}/decisions/${encodeURIComponent(token)}/resolve`,
    {
      method: 'POST',
      headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({
        choice,
        ...(customInput != null && customInput !== '' ? { customInput } : {}),
      }),
    },
  )
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw Object.assign(new Error(err.message || res.statusText), { status: res.status, body: err })
  }
  return res.json()
}
```

> `API_BASE`/`apiHeaders` 路径以仓库现有 `hitl.ts` / `chatSessions.ts` 为准，保持一致。

- [ ] **Step 3: 编译 BFF**

```bash
mvn -pl bff -DskipTests compile
```

- [ ] **Step 4: Commit**

```bash
git add bff/src/main/java/com/sunshine/bff/client/OrchestratorClient.java \
  bff/src/main/java/com/sunshine/bff/controller/GenerationController.java \
  sunshine-ui/src/api/decisions.ts
git commit -m "$(cat <<'EOF'
feat(4.7.9): BFF and UI client for decision resolve

EOF
)"
```

---

### Task 9: Prompt Catalog（mode-overlay + timeline.steps.decision）

**Files:**
- Modify: `docker/mysql/init/19-sunshine-resource.sql`

**Interfaces:**
- Produces: `mode-overlay.react` 正文追加 RequestDecision 六条约束；新种子 `timeline.steps.decision`

- [ ] **Step 1: 在 `mode-overlay.react` 的 `content_text` 末尾（SpawnSubagent/TaskBoard 段旁）追加**

```
- 【RequestDecision·使用场景】需求歧义或需在多方案间抉择时，调用 request_decision。
- 【RequestDecision·禁止场景】勿用于工具调用确认；用户意图已明确时勿出题；勿滥用。
- 【RequestDecision·选项】≥2；value 英文蛇形互异；label 中文；description 说明取舍；requireInput 仅必要时 true。
- 【RequestDecision·超时】若 tool result 为 choice=__timeout__：基于已有信息收束或换不依赖用户选择的路径；禁止立刻以相同 question/options 再调 request_decision。
- 【RequestDecision·续跑】用户 customInput 视为最终决策，勿再追问同一题。
- 【RequestDecision·与 TaskBoard】清单用 todo_write；澄清/抉择用 request_decision。
```

注意：该行是超长 SQL 字符串，编辑时保持合法转义（`\n`）。

- [ ] **Step 2: 新增 timeline 种子**（放在 `timeline.steps.subagent` 后）

```sql
INSERT IGNORE INTO prompt_definition (id, kind, display_name, description, enabled, priority, active_version, catalog_version) VALUES ('timeline.steps.decision', 'timeline', '时间线 · Steps · decision', '时间线「用户决策」步骤的 before/active/after 展示文案。', 1, 0, 1, 1);
INSERT IGNORE INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer) VALUES ('timeline.steps.decision', 1, 'published', NULL,
 '{\"label\":\"用户决策\",\"before\":\"正在等待用户决策\",\"active\":\"等待决策：{question}\",\"after\":\"用户已选择：{choice}\",\"after-fail\":\"决策失败\",\"after-cancel\":\"已取消\"}', '4.7.9 request_decision', 'agent');
```

> 已有库需运营在 `/prompts` 发布同等内容，或跑项目既有 prompt 同步手段；**禁止**把正文写回 Nacos。

- [ ] **Step 3: Commit**

```bash
git add docker/mysql/init/19-sunshine-resource.sql
git commit -m "$(cat <<'EOF'
feat(4.7.9): seed request_decision overlay and timeline.steps.decision

EOF
)"
```

---

### Task 10: DecisionCard UI + OperationStack

**Files:**
- Create: `sunshine-ui/src/components/operation/DecisionCard.vue`
- Modify: `sunshine-ui/src/components/operation/OperationStack.vue`
- Modify: `sunshine-ui/src/api/processingSteps.ts`（若 Task2 未加齐 `isDecisionStep`）

**Interfaces:**
- Consumes: `step.metadata.decision`；`resolveDecision`；`live` prop
- Produces: 主时间线决策卡；选项行视觉对齐 `ExecutionModeSelector` `.mode-menu-item`

- [ ] **Step 1: 实现 `DecisionCard.vue`（自建折叠容器，勿 import CollapsibleConfirmPanel）**

要点：
- props: `step: ProcessingStep`, `live: boolean`, `generationId: string`（或从父级 inject/provide——对齐 SubagentCard 取 generationId 的方式）
- `const decision = computed(() => step.metadata?.decision)`
- 仅 `live && lifecycle=== 'awaiting'` 可点选/提交
- 选项行：`CheckmarkOutline` 18px；`background: transparent`；`1px solid var(--sun-border)`；hover `var(--sun-row-hover)`；选中 `border-color: var(--sun-accent)` 内描边
- 描述 CSS：最多 3 行（`line-clamp: 3`），**不要** JS 截断文案
- `requireInput` 或选中自定义项（`allowCustomInput` 时末尾追加 value=`__custom__` 的「自定义」行）→ 展开 `sun-field` textarea（底 `--sun-black`）
- 提交按钮：`hitl-btn hitl-btn-primary`（高度 28px）
- 提交：`resolveDecision(generationId, token, choice, customInput?)`；400 展示后端 `message`/`key`，**不**本地改写 options
- 已 `done`：展示已选 label/choice（来自 metadata），禁用交互

结构示意：

```vue
<script setup lang="ts">
import { computed, ref } from 'vue'
import { NIcon } from 'naive-ui'
import { CheckmarkOutline, ChevronDownOutline } from '@vicons/ionicons5'
import type { ProcessingStep } from '@/api/processingSteps'
import { resolveDecision } from '@/api/decisions'
// ...
</script>
```

- [ ] **Step 2: OperationStack 分支**（紧挨 SubagentCard）

```vue
<DecisionCard
  v-else-if="isDecisionStep(step)"
  :step="step"
  :live="live && lifecycleOf(step) === 'awaiting'"
  :generation-id="generationId"
/>
```

同步 import `isDecisionStep`、`DecisionCard`；collapsedPreview 分支若有 Subagent 镜像处一并加。

> `generationId` 若 OperationStack 尚无 prop：从现有 HITL/cancel 同源字段接入（搜 `cancelSpawnSubagent` / `generationId` 在 Chat 视图的传递链），**不要**新造全局 store。

- [ ] **Step 3: 本地目视**（开发态）

```bash
# UI 已有 dev server 则刷新；否则 npm run dev
# 用 mock step 或等 Live；至少确认组件无 TS 报错
```

- [ ] **Step 4: Commit**

```bash
git add sunshine-ui/src/components/operation/DecisionCard.vue \
  sunshine-ui/src/components/operation/OperationStack.vue \
  sunshine-ui/src/api/processingSteps.ts
git commit -m "$(cat <<'EOF'
feat(4.7.9): add DecisionCard on OperationStack for phase=decision

EOF
)"
```

---

### Task 11: 暂停/续跑（ReAct MAIN）

**背景（必须读）**：现网 ReAct 续跑走 AgentScope native checkpoint + `truncateToLastCompleteThink`，会**丢掉**最后一个完整 think 之后的步。决策卡若落在 think 之后，续跑会被截掉——这是本能力的根因级挂点，禁止只做 Registry 不顾截断。

`findReactAwaitingHitlStep` / `HitlConfirmationService.resumeReactAwaiting` 目前**几乎未挂到 Chat 续跑主路径**；decision 不要幻想「对标即自动有线」。本 Task **显式接线**。

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepLifecycleOps.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionResumeSupport.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/StepEventBridge.java` + `StepEventBridgeRegistry.java`（`grantDecisionPreApproval` / `consumeDecisionPreApproval`）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/processing/ThinkStepIds.java`（或 `ChatController`/`ChatStreamExecutor`/`GenerationJob` 截断调用点）— **保留 awaiting/paused decision 步**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/ReactResumeContextSupport.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/execution/ReactExecutor.java`（或 `ReActAgentRuntime`）— 续跑前调用 `DecisionResumeSupport`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/RequestDecisionTool.java` — 入口消费 pre-approval
- Test: `ProcessingStepLifecycleOpsDecisionTest`、`DecisionResumeSupportTest`、`ReactResumeContextSupportTest`（增补）、截断保留测

**Interfaces:**
- Produces:
  - `ProcessingStepLifecycleOps.findReactAwaitingDecisionStep(List<ProcessingStep>)`：自尾向前；跳过 `node-*`；`phase=decision` 或 id `decision-*`；lifecycle ∈ {`awaiting`,`paused`} 且 metadata.decision 仍无终态 choice（或 choice 空）
  - `DecisionResumeSupport.prepareOnReactResume(messageId, bridgeId, steps)`：
    1. 找 awaiting decision 步
    2. 若用户已在停止后 resolve 且 Registry/预存有结果 → `grantDecisionPreApproval` 并更新步为 done（或保留 done）
    3. 否则 **重新 register token**（新 token），更新步 metadata.token/expiresAt，**不改 question/options**，再 `awaitDecision`（阻塞在 boundedElastic 续跑线程）
    4. 成功 → complete 卡 + `grantDecisionPreApproval(messageId, fingerprint)`，fingerprint = hash(question+optionsJson) 或 token 关联
  - `ThinkStepIds.truncateToLastCompleteThink`：截断时若存在 `findReactAwaitingDecisionStep`，将该决策步（及其前缀至 lastCompleteThink）保留——推荐实现：截断后若原列表有 awaiting decision，则 append 回去（去重 by id）
  - `ReactResumeContextSupport`：对 `phase=decision` 且仍待决，注入：

```text
【待决策】
{question}
选项：
- {value}: {label} — {description}
```

（原文，不截断）

- [ ] **Step 1: 写失败单测**

```java
@Test
void findReactAwaitingDecisionStep_returnsLatestAwaiting() { ... }

@Test
void truncateToLastCompleteThink_preservesAwaitingDecisionStep() { ... }

@Test
void resume_reRegistersToken_withoutChangingQuestion() { ... }

@Test
void consumeDecisionPreApproval_skipsSecondBlock() { ... }

@Test
void buildInjectedBlocks_includesAwaitingDecision() { ... }
```

- [ ] **Step 2: Run 确认失败**

```bash
mvn -pl orchestrator -Dtest=ProcessingStepLifecycleOpsDecisionTest,DecisionResumeSupportTest,ReactResumeContextSupportTest test
```

- [ ] **Step 3: 实现接线**

推荐挂点（选一，保持单一）：
- **A（推荐）**：`ReactExecutor.executeWithInjected` 在构建 `AgentRunRequest` 前，若 `ctx.reactRestart()`，解析 steps → `decisionResumeSupport.blockUntilResolvedOrCancelled(...)`（内部 re-await）；同时 RequestDecisionTool 入口 `consumeDecisionPreApproval` 防止模型再次 tool_call 时二次出题。
- **B**：仅依赖 checkpoint 重放 tool_call + Tool 内 pre-approval；`DecisionResumeSupport` 只负责「停止后用户已点提交」的 grant。若选 B，仍必须修 truncate 保留决策卡，否则 UI 丢卡。

**停止路径**：Task 7 已 `cancelWaitersForMessage` → Tool 得 `__cancelled__` → `timeline.pause`。续跑同一题：以 steps 中 `paused/awaiting` decision 为准 re-await（D5/D6），**不要**让模型用同一 question 再调一次新卡（overlay 已禁；代码侧 pre-approval / ResumeSupport 双保险）。

**明确不改**：`WorkflowNodeRunner`、`PendingInteraction` kind。

- [ ] **Step 4: Run 相关单测全绿**

```bash
mvn -pl orchestrator -Dtest=ProcessingStepLifecycleOpsDecisionTest,DecisionResumeSupportTest,ReactResumeContextSupportTest,RequestDecisionToolTest,DecisionRegistryTest test
```

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepLifecycleOps.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/agent/DecisionResumeSupport.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/agent/StepEventBridge.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/agent/StepEventBridgeRegistry.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/processing/ThinkStepIds.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/execution/ReactResumeContextSupport.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/execution/ReactExecutor.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/agent/RequestDecisionTool.java \
  orchestrator/src/test/java/com/sunshine/orchestrator/
git commit -m "$(cat <<'EOF'
feat(4.7.9): ReAct decision pause/resume re-await and truncate keep

EOF
)"
```

---

### Task 12: Live 脚本 + 文档同步 + 灰度开关

**Files:**
- Create: `scripts/verify_decision_live.py`
- Modify: `docs/implementation-plan.md`（阶段四 4.7 行补 4.7.9）
- Modify: `CLAUDE.md`（时间线表补 `decision-*`；扩展表补 `request_decision`）
- Modify: `docs/nacos/sunshine-orchestrator.yaml` — Live 机临时 `decision.enabled: true`（或脚本前置检查并提示）
- Modify: `docs/superpowers/specs/2026-07-28-react-request-decision-design.md` — 状态改为实施中/已实现（落地后）

**Interfaces:**
- Produces: Live 覆盖 D1–D4、D8–D9、D11（Chat）；D5–D7 能自动化则做，否则脚本标注 manual；**不做 D12 Planner**

- [ ] **Step 1: 写 `verify_decision_live.py`**（结构仿 `verify_spawn_subagent_live.py`）

套件建议：
| 套件 | 期望 |
|------|------|
| D1 | 诱导 `request_decision` 两选项 → SSE 出现 `phase==decision` 且 `lifecycle==awaiting`，仅一张 |
| D2 | `POST .../decisions/{token}/resolve` → 卡 `done`，主对话继续 completed |
| D3 | requireInput 选项空提交 → 400；带 customInput → 唤醒 |
| D4 | allowCustomInput → choice `__custom__` |
| D8 | （单测已覆盖 SUB；Live soft skip 或依赖单测） |
| D9 | 单测覆盖；Live soft |
| D11 | 诱导同轮两次 decision → 第二张 awaiting 不出现 / 工具 error JSON |
| D5–D7 | stop→resume 同题；超时（可把 timeout-sec 临时调低到 5 做独立 suite） |

诱导 prompt 示例：

```text
请立即调用 request_decision：question=验收决策卡片请选择方案；
options=[{"value":"plan_a","label":"方案A：快速","description":"少步骤","requireInput":false},
{"value":"plan_b","label":"方案B：完整","description":"需补充说明","requireInput":true}]；
allow_custom_input=false。选出后根据 choice 用一句话确认即可。
```

- [ ] **Step 2: sync + 重启 + 跑 Live**

```bash
# 先把 docs/nacos/... decision.enabled 改为 true（仅验收环境）
python scripts/sync_nacos.py
python scripts/start.py --restart orchestrator
python scripts/start.py --restart bff
# Prompt：若 DB 无新种子，经 /prompts 发布或按项目惯例导入 19-*.sql 增量
python scripts/verify_decision_live.py --suite all
```

Expected: hard suite 全绿。

- [ ] **Step 3: 文档勾选**

- `implementation-plan.md` 4.7 行增加 **4.7.9 Request Decision ✅**
- `CLAUDE.md`：
  - 进度行补 4.7.9
  - 时间线：ReAct 含 `decision-*`
  - 扩展表：新元工具 `request_decision`（MAIN）
- Spec §12 文档同步清单勾完；状态 → ✅ 已实现后归档（按仓库 Plan/Spec 管理约定）

- [ ] **Step 4: 验收完将默认 `enabled` 保持 false 或按产品要求灰度**（D21）；勿在未约定时把生产默认改 true。

- [ ] **Step 5: Commit**

```bash
git add scripts/verify_decision_live.py \
  docs/implementation-plan.md \
  CLAUDE.md \
  docs/nacos/sunshine-orchestrator.yaml \
  docs/superpowers/specs/2026-07-28-react-request-decision-design.md
git commit -m "$(cat <<'EOF'
feat(4.7.9): add decision live verify and docs sync

EOF
)"
```

---

## Spec coverage（self-review）

| Spec 项 | Task |
|---------|------|
| 元工具 + 校验 + 短格式 | T5 |
| DecisionRegistry Redis/Future | T3 |
| Timeline phase=decision / lifecycle | T4 |
| MAIN 注册 / SUB 拒绝 / whitelist | T5+T6 |
| Middleware 跳过 tool-* / 只读 | T6 |
| resolve API + 错误码 | T7 |
| BFF 透传 | T8 |
| Catalog overlay + timeline.steps.decision | T9 |
| DecisionCard + OperationStack + 样式 | T10 |
| 暂停/续跑 / pre-approval / 注入块 | T11 |
| Nacos enabled 默认 false | T1 |
| Live D1–D11（无 D12） | T12 |
| 不做 Planner/Worker/WorkflowNodeRunner | Global Constraints |
| 不对 question/options 截断兜底 | T5 校验拒收 + T10 CSS clamp |

**刻意延后（非本计划）**：Planner MAIN 专有 harness/续跑（D12）；PendingInteraction.kind=decision；FailureBudget 计数排除（类未落地）；Decision 多轮 history。

---

## 执行交接

Plan complete and saved to `docs/superpowers/plans/2026-08-11-react-request-decision.md`.

**Two execution options:**

1. **Subagent-Driven（推荐）** — 每 Task 新开 subagent，Task 间审查  
2. **Inline Execution** — 本会话用 executing-plans 顺序执行并设检查点  

Which approach?
