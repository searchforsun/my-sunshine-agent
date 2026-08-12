# Async Tool Await Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 长工具（`sandbox__exec` / `spawn_subagent`）可 `background=true` 立即返回 `runId`；MAIN 用元工具 `await_tool_run` 分片等待；每 run 最多 3 次 await，超限结构化 `budget_exhausted`。

**Architecture:** 新建 `AsyncToolRunRegistry`（CompletableFuture 事件唤醒 + wait 预算 + 墙钟）；内置 `AwaitToolRunTool`（仅 MAIN）；exec/spawn 在 `background=true` 时后台跑并 `complete` 注册表；取消委托既有 `CancellableToolRunRegistry` / `SpawnRunRegistry`，禁止 bump stream epoch。

**Tech Stack:** AgentScope-Java `AgentTool` · Reactor · Spring `@ConfigurationProperties` · Nacos `docs/nacos/sunshine-orchestrator.yaml` · Prompt Catalog（`docker/mysql/init/19-sunshine-resource.sql`）· Live `scripts/verify_async_tool_await_live.py`

**Spec:** [2026-08-12-async-tool-await-design.md](../specs/2026-08-12-async-tool-await-design.md)

## Global Constraints

- `background` 默认 **false**（行为不突变）
- await 默认 **30s**、单次上限 **120s**、每 run 最多 **3** 次；终态 peek **不计次**
- 首期仅 `sandbox__exec` + `spawn_subagent`
- 禁止 wait 真 `Thread.sleep` 占满 worker（用 `CompletableFuture.get(timeout)` / `future.orTimeout`）
- 禁止对模型输出截断/摘要兜底；文案 SSOT = Catalog
- 单 run 取消禁止 bump 整轮 stream epoch
- 改 Nacos 后：`python scripts/sync_nacos.py` + `python scripts/start.py --restart orchestrator`
- 勿升 Spring Boot 3.3+；Java 命名自解释；无残渣

---

## File map

| 文件 | 职责 |
|------|------|
| `orchestrator/.../agent/AsyncToolRunRegistry.java` | 统一 run 句柄、await 预算、墙钟、终态 Future |
| `orchestrator/.../agent/AwaitToolRunTool.java` | 元工具 `await_tool_run` |
| `orchestrator/.../config/AgentExecutionProperties.java` | `react.async-tool.*` |
| `orchestrator/.../agent/DynamicToolkitFactory.java` | MAIN 注册 await；忽略白名单误配 |
| `orchestrator/.../agent/ProcessingStepMiddleware.java` | await 视只读；工具步策略见 Task 3 |
| `orchestrator/.../sandbox/SandboxAgentTools.java` | `background=true` 异步 exec |
| `orchestrator/.../agent/SpawnSubagentTool.java` | `background=true` 异步 spawn |
| `docs/nacos/sunshine-orchestrator.yaml` | async-tool + exec `background` schema |
| `docker/mysql/init/19-sunshine-resource.sql` | overlay / cancel-result 类文案 bump |
| `scripts/verify_async_tool_await_live.py` | Live 验收 |

---

### Task 1: Nacos + `AgentExecutionProperties.AsyncTool`

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/config/AgentExecutionProperties.java`
- Modify: `docs/nacos/sunshine-orchestrator.yaml`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/config/AgentExecutionPropertiesAsyncToolTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `AgentExecutionProperties.React.AsyncTool`（见下方字段）

- [ ] **Step 1: 写失败单测**

```java
package com.sunshine.orchestrator.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutionPropertiesAsyncToolTest {
    @Test
    void asyncTool_defaults_matchSpec() {
        AgentExecutionProperties.React.AsyncTool cfg = new AgentExecutionProperties.React.AsyncTool();
        assertThat(cfg.isEnabled()).isTrue();
        assertThat(cfg.getAwaitDefaultSec()).isEqualTo(30);
        assertThat(cfg.getAwaitMaxSec()).isEqualTo(120);
        assertThat(cfg.getAwaitMaxWaits()).isEqualTo(3);
        assertThat(cfg.getExecWallTimeoutSec()).isEqualTo(600);
        assertThat(cfg.getMaxConcurrentPerMessage()).isEqualTo(3);
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
cd /usr/local/gitproj/my-sunshine-agent && mvn -pl orchestrator -Dtest=AgentExecutionPropertiesAsyncToolTest test
```

Expected: FAIL（类 `AsyncTool` 不存在）

- [ ] **Step 3: 实现 properties**

在 `AgentExecutionProperties.React` 增加：

```java
/** 异步长工具 + await_tool_run — SSOT：Nacos agent.execution.react.async-tool */
private AsyncTool asyncTool = new AsyncTool();

@Data
public static class AsyncTool {
    private boolean enabled = true;
    private int awaitDefaultSec = 30;
    private int awaitMaxSec = 120;
    private int awaitMaxWaits = 3;
    private int execWallTimeoutSec = 600;
    private int maxConcurrentPerMessage = 3;
}
```

Nacos `agent.execution.react`（`decision` 旁）追加：

```yaml
      async-tool:
        enabled: true
        await-default-sec: 30
        await-max-sec: 120
        await-max-waits: 3
        exec-wall-timeout-sec: 600
        max-concurrent-per-message: 3
```

`sandbox__exec.properties` 追加：

```yaml
          background:
            type: boolean
            description: 可选；true=立即返回 runId，主 Agent 用 await_tool_run 收结果；默认 false
```

- [ ] **Step 4: 跑单测通过**

```bash
mvn -pl orchestrator -Dtest=AgentExecutionPropertiesAsyncToolTest test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/config/AgentExecutionProperties.java \
  orchestrator/src/test/java/com/sunshine/orchestrator/config/AgentExecutionPropertiesAsyncToolTest.java \
  docs/nacos/sunshine-orchestrator.yaml
git commit -m "$(cat <<'EOF'
feat(async-tool): add Nacos async-tool flags and exec background schema

EOF
)"
```

---

### Task 2: `AsyncToolRunRegistry`

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/AsyncToolRunRegistry.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/agent/AsyncToolRunRegistryTest.java`

**Interfaces:**
- Consumes: `AgentExecutionProperties.React.AsyncTool`（max waits / wall / concurrency）
- Produces:
  - `enum Kind { SANDBOX_EXEC, SPAWN_SUBAGENT }`
  - `enum Status { RUNNING, DONE, ERROR, CANCELLED, BUDGET_EXHAUSTED, WALL_TIMEOUT }`
  - `record Snapshot(String runId, Kind kind, Status status, int waitCount, int waitBudget, long elapsedMs, String result, String partial, String error)`
  - `String register(Kind kind, String messageId, String conversationId, long wallTimeoutMs)` → runId（或 `registerWithId(runId, ...)` 给 spawn 复用既有 id）
  - `boolean tryAcquireSlot(String messageId)` / `void releaseSlot(String messageId)`（并发上限）
  - `void complete(String runId, Status terminal, String result)`（terminal ≠ RUNNING）
  - `void updatePartial(String runId, String partial)`
  - `Snapshot await(String runId, int timeoutSec)` — 规则见 spec §4.3
  - `Snapshot peek(String runId)`
  - `boolean cancel(String runId)` — 标 CANCELLED 并 complete Future；**不**直接 kill（由调用方委托 cancellable/spawn registry）
  - `List<String> listRunningByMessage(String messageId)`

- [ ] **Step 1: 写失败单测（核心契约）**

```java
package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class AsyncToolRunRegistryTest {
    private AsyncToolRunRegistry registry;

    @BeforeEach
    void setUp() {
        AgentExecutionProperties props = new AgentExecutionProperties();
        registry = new AsyncToolRunRegistry(props);
    }

    @Test
    void await_terminalPeek_doesNotBurnBudget() {
        String runId = registry.register(
                AsyncToolRunRegistry.Kind.SANDBOX_EXEC, "msg-1", "c1", 60_000L);
        registry.complete(runId, AsyncToolRunRegistry.Status.DONE, "hello");
        var s1 = registry.await(runId, 1);
        var s2 = registry.await(runId, 1);
        assertThat(s1.status()).isEqualTo(AsyncToolRunRegistry.Status.DONE);
        assertThat(s1.result()).isEqualTo("hello");
        assertThat(s1.waitCount()).isZero();
        assertThat(s2.waitCount()).isZero();
    }

    @Test
    void await_threeWaitsThenBudgetExhausted() throws Exception {
        String runId = registry.register(
                AsyncToolRunRegistry.Kind.SANDBOX_EXEC, "msg-1", "c1", 600_000L);
        assertThat(registry.await(runId, 1).status()).isEqualTo(AsyncToolRunRegistry.Status.RUNNING);
        assertThat(registry.await(runId, 1).waitCount()).isEqualTo(2);
        assertThat(registry.await(runId, 1).waitCount()).isEqualTo(3);
        var exhausted = registry.await(runId, 30);
        assertThat(exhausted.status()).isEqualTo(AsyncToolRunRegistry.Status.BUDGET_EXHAUSTED);
        assertThat(exhausted.waitCount()).isEqualTo(3);
    }

    @Test
    void await_wakesOnCompleteBeforeTimeout() throws Exception {
        String runId = registry.register(
                AsyncToolRunRegistry.Kind.SANDBOX_EXEC, "msg-1", "c1", 600_000L);
        Executors.newSingleThreadScheduledExecutor().schedule(
                () -> registry.complete(runId, AsyncToolRunRegistry.Status.DONE, "ok"),
                200, TimeUnit.MILLISECONDS);
        var s = registry.await(runId, 5);
        assertThat(s.status()).isEqualTo(AsyncToolRunRegistry.Status.DONE);
        assertThat(s.result()).isEqualTo("ok");
        assertThat(s.waitCount()).isEqualTo(1); // 一次 running 等待后完成仍计 1 次
    }

    @Test
    void tryAcquireSlot_respectsMaxConcurrent() {
        assertThat(registry.tryAcquireSlot("msg-1")).isTrue();
        assertThat(registry.tryAcquireSlot("msg-1")).isTrue();
        assertThat(registry.tryAcquireSlot("msg-1")).isTrue();
        assertThat(registry.tryAcquireSlot("msg-1")).isFalse();
        registry.releaseSlot("msg-1");
        assertThat(registry.tryAcquireSlot("msg-1")).isTrue();
    }

    @Test
    void unknownRunId_peekReturnsNull() {
        assertThat(registry.peek("nope")).isNull();
    }
}
```

说明：`await_wakesOnCompleteBeforeTimeout` 在「等待中完成」时计 1 次（消耗一次观察窗口）；若实现选择「完成当次不计次」则与上测冲突——**本计划锁定：只要进入阻塞等待就 `waitCount++`，即使期内完成；仅已终态的即时 peek 不计次。**

- [ ] **Step 2: 跑测确认失败**

```bash
mvn -pl orchestrator -Dtest=AsyncToolRunRegistryTest test
```

Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 `AsyncToolRunRegistry`**

要点（完整实现写在该文件，勿拆到多个 registry）：

```java
@Component
public class AsyncToolRunRegistry {
    // Handle: runId, kind, messageId, conversationId, status, waitCount,
    //   startedAtMs, deadlineAtMs, result, partial, CompletableFuture<Void> terminalSignal
    public Snapshot await(String runId, int timeoutSec) {
        // 1) unknown → null（Tool 侧转 ok=false）
        // 2) 已终态 → snapshot，不 ++waitCount
        // 3) waitCount >= awaitMaxWaits → Status.BUDGET_EXHAUSTED（不阻塞；不改后台真实状态，仅响应）
        // 4) clamp timeoutSec: default/max from props；<1 → 1
        // 5) terminalSignal.get(timeoutSec, SECONDS)；完成则读终态；TimeoutException → RUNNING 且 waitCount++
        // 6) InterruptedException → CANCELLED 语义由 Tool 解释
    }
    public void complete(String runId, Status terminal, String result) {
        // CAS status RUNNING→terminal；set result；complete terminalSignal
        // releaseSlot(messageId) 若尚未释放
    }
}
```

墙钟：`register` 后用 `Schedulers.boundedElastic().schedule` 或 `CompletableFuture.delayedExecutor` 在 `deadlineAtMs` 调用 `complete(runId, WALL_TIMEOUT, partial)`；若已终态则 no-op。`cancel` 同理 complete Future。

- [ ] **Step 4: 跑测通过**

```bash
mvn -pl orchestrator -Dtest=AsyncToolRunRegistryTest test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/AsyncToolRunRegistry.java \
  orchestrator/src/test/java/com/sunshine/orchestrator/agent/AsyncToolRunRegistryTest.java
git commit -m "$(cat <<'EOF'
feat(async-tool): add AsyncToolRunRegistry with await budget

EOF
)"
```

---

### Task 3: 元工具 `await_tool_run` + Toolkit / Middleware

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/AwaitToolRunTool.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/DynamicToolkitFactory.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepMiddleware.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/agent/AwaitToolRunToolTest.java`

**Interfaces:**
- Consumes: `AsyncToolRunRegistry.await/peek`；`AgentExecutionProperties.React.AsyncTool`
- Produces: tool name `await_tool_run`；JSON result 字段与 spec §4.3 一致

- [ ] **Step 1: 写失败单测**

```java
@ExtendWith({MockitoExtension.class, TimelineLabelJUnitExtension.class})
class AwaitToolRunToolTest {
    @Mock AgentExecutionProperties executionProperties;
    @Mock AgentExecutionProperties.React reactProps;
    @Mock AsyncToolRunRegistry asyncRegistry;
    // bind StepEventBridgeRegistry + main bridge + toolAuditContext like RequestDecisionToolTest

    @Test
    void NAME_equals_await_tool_run() {
        assertThat(AwaitToolRunTool.NAME).isEqualTo("await_tool_run");
    }

    @Test
    void disabled_returnsErrorJson() {
        AgentExecutionProperties.React.AsyncTool cfg = new AgentExecutionProperties.React.AsyncTool();
        cfg.setEnabled(false);
        when(executionProperties.getReact()).thenReturn(reactProps);
        when(reactProps.getAsyncTool()).thenReturn(cfg);
        AwaitToolRunTool tool = new AwaitToolRunTool(executionProperties, asyncRegistry);
        assertThat(tool.awaitToolRun("r1", 30, "tu-1")).contains("\"ok\":false");
    }

    @Test
    void unknownRun_returnsErrorJson() {
        // enabled=true；asyncRegistry.peek/await → null
        // assert contains ok:false
    }

    @Test
    void clampsTimeoutAndFormatsRunningSnapshot() {
        // await returns Snapshot RUNNING waitCount=1 → JSON 含 status/running waitCount/waitBudget/elapsedMs
    }
}
```

- [ ] **Step 2: 跑测确认失败**

```bash
mvn -pl orchestrator -Dtest=AwaitToolRunToolTest test
```

Expected: FAIL

- [ ] **Step 3: 实现 `AwaitToolRunTool`**

对齐 `RequestDecisionTool`：`AgentTool` + `callAsync` → `boundedElastic`；用 `toolUseId` 解析 `messageId`。

```java
@Component
@RequiredArgsConstructor
public class AwaitToolRunTool implements AgentTool {
    public static final String NAME = "await_tool_run";
    // getDescription: 等待 background 工具/子任务结束或观察窗口到期
    // parameters: runId (required string), timeout_sec (optional number)
    String awaitToolRun(String runId, Integer timeoutSec, String toolUseId) {
        // enabled 校验；MAIN 校验（activeMainBridge）；禁止 sub- bridge
        // timeout = timeoutSec==null ? default : clamp(1, max)
        // Snapshot s = asyncRegistry.await(runId, timeout);
        // if s==null → {"ok":false,"error":"未知 runId"}
        // else → {"ok":true,"runId",status,waitCount,waitBudget,elapsedMs,result?,partial?,error?}
    }
}
```

`DynamicToolkitFactory`：
- 白名单遇到 `await_tool_run` 打 warn 跳过（同 spawn/decision）
- `scope==MAIN && asyncTool.enabled` → `tk.registerAgentTool(awaitToolRunTool)`

`ProcessingStepMiddleware`：
- **保留** `beginToolStep`（await 上 `tool-*` 步，便于 summary.active）
- `isWriteTool` / 只读分区：将 `AwaitToolRunTool.NAME` 视为只读
- `completeToolStep`：正常走工具完成（勿特殊跳过）；若现有 meta 分支会吞掉 End，确保 await **不会**被当成 spawn 那样 skip begin

- [ ] **Step 4: 跑测通过**

```bash
mvn -pl orchestrator -Dtest=AwaitToolRunToolTest,AgentExecutionPropertiesAsyncToolTest,AsyncToolRunRegistryTest test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/AwaitToolRunTool.java \
  orchestrator/src/test/java/com/sunshine/orchestrator/agent/AwaitToolRunToolTest.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/agent/DynamicToolkitFactory.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepMiddleware.java
git commit -m "$(cat <<'EOF'
feat(async-tool): add await_tool_run meta tool for MAIN

EOF
)"
```

---

### Task 4: Catalog overlay（`mode-overlay.react`）

**Files:**
- Modify: `docker/mysql/init/19-sunshine-resource.sql`
- （实施机）对 live DB 插入新 version 或经 `/prompts` 发布；种子文件须可重建

**Interfaces:**
- Consumes: 无
- Produces: overlay 条款指导 `background` + `await_tool_run` + 预算耗尽收束

- [ ] **Step 1: 在种子 SQL 增加 version=2（或 bump 现网 active）**

在 `mode-overlay.react` 现有正文末尾（SpawnSubagent / RequestDecision 节后）追加：

```text
【AsyncTool · background + await_tool_run】
- 长命令 `sandbox__exec` 或长子任务 `spawn_subagent`：可传 `background=true` 立即获得 `runId`，勿同步空等。
- 拿到 `status=running` 后须调用 `await_tool_run(runId, timeout_sec?)` 观察；默认约 30s，单次不超过 120s。
- 每 run 最多 await 3 次；第 4 次返回 `budget_exhausted` 时必须向用户说明进展并收束或换方案，禁止空转重试同一 await。
- 终态（done/error/cancelled/wall_timeout）可再次 await/窥视，不计预算；有 running run 时禁止假装已完成。
```

同时：

```sql
INSERT INTO prompt_version (prompt_id, version, status, content_text, content_json, change_note, maintainer)
VALUES ('mode-overlay.react', 2, 'published', '<完整新正文>', NULL, 'async-tool await', 'agent');
UPDATE prompt_definition SET active_version = 2, catalog_version = catalog_version + 1
WHERE id = 'mode-overlay.react';
UPDATE prompt_catalog_meta SET catalog_version = catalog_version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = 1;
```

注意：`INSERT IGNORE` 不会更新已有 v1；必须 **新 version + 改 active_version**。文件末尾 `catalog_version = 71` 改为 `72`（或 `catalog_version + 1` 写法与仓库惯例一致）。

可选 Catalog：`react.async.budget-exhausted-hint`（短模板，Tool **不**二次加工模型输出；仅当需要固定 tool result 附注时使用——优先把说明放进 JSON `status=budget_exhausted`，本 Task 可不建）。

- [ ] **Step 2: 实施机应用 Catalog（二选一）**

```bash
# 重建库后自然带上；或对现网执行等价 INSERT/UPDATE（经运维流程）
# 然后清 Catalog 缓存 / 重启 resource-manager + orchestrator
python scripts/start.py --restart resource-manager orchestrator
```

- [ ] **Step 3: Commit**

```bash
git add docker/mysql/init/19-sunshine-resource.sql
git commit -m "$(cat <<'EOF'
feat(async-tool): document background+await in mode-overlay.react

EOF
)"
```

---

### Task 5: `sandbox__exec` `background=true`

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/sandbox/SandboxAgentTools.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/sandbox/SandboxAgentToolsBackgroundTest.java`（新建；可用 Mockito 假 `SandboxClient`）

**Interfaces:**
- Consumes: `AsyncToolRunRegistry.registerWithId` / `complete` / `tryAcquireSlot`；`CancellableToolRunRegistry`；`AgentExecutionProperties.React.AsyncTool.execWallTimeoutSec`
- Produces: 同步路径不变；异步路径立即 JSON `{ok:true,runId,status:"running"}`

- [ ] **Step 1: 写失败单测**

```java
@Test
void backgroundTrue_returnsRunningJson_withoutBlockingInvoke() {
    // mock sandboxClient.invoke 延迟 2s
    // body background=true
    // 断言 callAsync block 在 <500ms 内返回且含 "running"
    // 稍后 verify invoke 被调用；complete 后 registry.peek DONE
}
```

- [ ] **Step 2: 跑测确认失败**

```bash
mvn -pl orchestrator -Dtest=SandboxAgentToolsBackgroundTest test
```

Expected: FAIL

- [ ] **Step 3: 实现背景分支**

在 `SandboxAgentTool.execute`，于 HITL / ensureBound / bindSession **之后**、`sandboxClient.invoke` **之前**：

```java
boolean background = Boolean.TRUE.equals(asBoolean(body.get("background")));
if (background && SandboxIds.EXEC.equals(name) && asyncToolEnabled()) {
    if (!asyncToolRunRegistry.tryAcquireSlot(messageId)) {
        return textResult(toolUseId, name, "{\"ok\":false,\"error\":\"本消息后台工具并发已达上限\"}");
    }
    String runId = StringUtils.hasText(invocationId) ? invocationId : UUID.randomUUID().toString();
    long wallMs = execWallTimeoutSec() * 1000L;
    asyncToolRunRegistry.registerWithId(
            runId, Kind.SANDBOX_EXEC, messageId, conversationIdOrNull, wallMs);
    // 保留 cancellable register（勿在 finally 立刻 unregister）
    Schedulers.boundedElastic().schedule(() -> {
        try {
            ToolInvokeResponse resp = sandboxClient.invoke(...);
            if (cancelled) {
                asyncToolRunRegistry.complete(runId, CANCELLED, cancelText);
            } else if (ok) {
                asyncToolRunRegistry.complete(runId, DONE, output);
            } else {
                asyncToolRunRegistry.complete(runId, ERROR, output);
            }
        } catch (Exception e) {
            asyncToolRunRegistry.complete(runId, ERROR, err);
        } finally {
            cancellableToolRunRegistry.unregister(invocationId);
            // releaseSlot 在 complete 内处理
        }
    });
    return textResult(toolUseId, name,
            "{\"ok\":true,\"runId\":\"" + runId + "\",\"status\":\"running\"}");
}
```

要点：
- `background=false` / 非 EXEC：原同步路径
- 异步时 **不要** 走同步 `finally unregister` 提前摘掉 cancel 句柄
- 墙钟到期：`AsyncToolRunRegistry` complete `WALL_TIMEOUT` 时回调或观察后 `cancellableToolRunRegistry.cancel(runId)` kill
- 在 `AsyncToolRunRegistry` 可选 `BiConsumer` onTerminal，或 Tool 侧 `await`/`complete` 钩子里 cancel——推荐 registry 支持 `onCancelRequest(runId, Runnable)` 在 WALL_TIMEOUT/cancel 时跑 kill

- [ ] **Step 4: 跑测通过**

```bash
mvn -pl orchestrator -Dtest=SandboxAgentToolsBackgroundTest,AsyncToolRunRegistryTest test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/sandbox/SandboxAgentTools.java \
  orchestrator/src/test/java/com/sunshine/orchestrator/sandbox/SandboxAgentToolsBackgroundTest.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/agent/AsyncToolRunRegistry.java
git commit -m "$(cat <<'EOF'
feat(async-tool): support background sandbox__exec with runId

EOF
)"
```

---

### Task 6: `spawn_subagent` `background=true`

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/SpawnSubagentTool.java`
- Modify: `orchestrator/src/test/java/com/sunshine/orchestrator/agent/SpawnSubagentToolTest.java`

**Interfaces:**
- Consumes: `AsyncToolRunRegistry.registerWithId(runId=AgentRunRequest.runId)`；既有 `SpawnRunRegistry` / timeline
- Produces: 同步路径不变；异步立即 JSON `{ok:true,runId,status:"running"}`；子卡仍流式

- [ ] **Step 1: 扩展参数 schema + 失败单测**

`getParameters` 增加：

```java
props.put("background", Map.of(
        "type", "boolean",
        "description", "可选；true=立即返回 runId，主 Agent 用 await_tool_run 收终稿；默认 false"));
```

单测：

```java
@Test
void backgroundTrue_returnsRunning_withoutBlockLast() {
    // mock agentExecutorRouter.dispatch → Flux.never() 或 delay
    // spawnSubagent(..., background=true)
    // 断言快速返回含 running；registry.peek RUNNING
    // complete flux → DONE；await peek result
}
```

- [ ] **Step 2: 跑测确认失败**

```bash
mvn -pl orchestrator -Dtest=SpawnSubagentToolTest test
```

Expected: 新用例 FAIL

- [ ] **Step 3: 实现**

在 `timelineSupport.begin` + `spawnRunRegistry.register` 之后分支：

```java
boolean background = Boolean.TRUE.equals(asBoolean(input.get("background")));
if (background && asyncEnabled) {
    if (!asyncToolRunRegistry.tryAcquireSlot(messageId)) {
        return errorJson("本消息后台工具并发已达上限");
    }
    asyncToolRunRegistry.registerWithId(
            runId, Kind.SPAWN_SUBAGENT, messageId, audit.conversationId(), timeoutMs);
    agentExecutorRouter.dispatch(...)
        .doOnNext(answer::accept)
        .doOnError(failure::set)
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe(
            null,
            err -> { /* timeline fail + complete ERROR/CANCELLED */ },
            () -> { /* timeline complete + complete DONE */ });
    // 注意：同步路径的 finally unregister SpawnRunRegistry 须延后到后台终态
    return "{\"ok\":true,\"runId\":\"" + runId + "\",\"status\":\"running\"}";
}
// else 原 blockLast 路径；成功后亦可 register+immediate complete 供统一 await（非必须）
```

墙钟：异步用同一 `timeoutMs`；到期 `spawnRunRegistry.cancel(runId)` + `complete(WALL_TIMEOUT, partial)`。

用户取消子卡：既有 `SpawnRunRegistry.cancel` 后须 `asyncToolRunRegistry.complete(runId, CANCELLED, formatCancelResult)`（在 cancel 路径挂钩，避免 await 永久 RUNNING）。

- [ ] **Step 4: 跑测通过**

```bash
mvn -pl orchestrator -Dtest=SpawnSubagentToolTest,AwaitToolRunToolTest,AsyncToolRunRegistryTest test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/SpawnSubagentTool.java \
  orchestrator/src/test/java/com/sunshine/orchestrator/agent/SpawnSubagentToolTest.java
git commit -m "$(cat <<'EOF'
feat(async-tool): support background spawn_subagent with await

EOF
)"
```

---

### Task 7: 主会话 stop / 取消联动

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/generation/GenerationRegistry.java` 或实际 stop 入口（先 `rg "cancel\\(|interrupt" orchestrator/.../generation` 定位 message 级 stop）
- Modify: `SpawnRunRegistry.cancel` / `GenerationController.cancelTool`（exec）挂钩 `AsyncToolRunRegistry`

**Interfaces:**
- Consumes: `AsyncToolRunRegistry.listRunningByMessage` / `cancel`
- Produces: stop 时该 message 下 async runs → CANCELLED + kill/interrupt

- [ ] **Step 1: 定位并写单测**

```java
@Test
void cancelByMessage_marksAllRunningCancelled() {
    String a = registry.register(... messageId=m ...);
    String b = registry.register(... messageId=m ...);
    registry.cancelByMessage("m");
    assertThat(registry.peek(a).status()).isEqualTo(CANCELLED);
    assertThat(registry.peek(b).status()).isEqualTo(CANCELLED);
}
```

- [ ] **Step 2: 实现 `cancelByMessage` + 接线**

- message stop → `asyncToolRunRegistry.cancelByMessage(messageId)`，并对每个 run：EXEC→`cancellable.cancel`；SPAWN→`spawnRunRegistry.cancel`
- `cancelTool` 成功后 `asyncToolRunRegistry.complete(toolUseId, CANCELLED, ...)`
- `SpawnRunRegistry.cancel` 成功后同样 complete async registry

- [ ] **Step 3: 跑相关单测**

```bash
mvn -pl orchestrator -Dtest=AsyncToolRunRegistryTest,SpawnRunRegistryTest,CancellableToolRunRegistryTest test
```

Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/AsyncToolRunRegistry.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/agent/SpawnRunRegistry.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/generation/GenerationController.java \
  # 以及实际修改的 stop 入口文件
git commit -m "$(cat <<'EOF'
feat(async-tool): cancel async runs with message stop and tool cancel

EOF
)"
```

---

### Task 8: Live 脚本 + 文档状态

**Files:**
- Create: `scripts/verify_async_tool_await_live.py`
- Modify: `docs/superpowers/specs/2026-08-12-async-tool-await-design.md`（状态 → 实施中/已实现）
- Modify: `CLAUDE.md` 进度行（可选一行「异步工具 await」）

**Interfaces:**
- Consumes: Gateway chat SSE（对齐 `verify_spawn_subagent_live.py` / `verify_sandbox_tool_cancel_live.py`）
- Produces: S-EXEC / S-SPAWN 两条 hard 路径

- [ ] **Step 1: 写脚本骨架**

```python
#!/usr/bin/env python3
"""异步长工具 + await_tool_run Live — S-EXEC / S-SPAWN。

用法:
  python3 scripts/verify_async_tool_await_live.py
  python3 scripts/verify_async_tool_await_live.py --suite exec,spawn

前置:
  - agent.execution.react.async-tool.enabled=true（sync_nacos + restart orchestrator）
  - 沙箱 / LLM 可用
"""
# S-EXEC: 诱导 background exec sleep 45 + await_tool_run；断言工具结果含 running→后续 await 见 done
# S-SPAWN: background spawn + await；断言 subagent 卡 + 主 Agent 收到终态 JSON/正文
```

诱导 prompt 须明确要求：`background=true`、调用 `await_tool_run`、禁止同步死等。

- [ ] **Step 2: sync + 重启 + 跑 Live**

```bash
python scripts/sync_nacos.py
python scripts/start.py --restart orchestrator
python scripts/verify_async_tool_await_live.py --suite exec,spawn
```

Expected: 两 suite PASS（或脚本打印清晰 FAIL 原因）

- [ ] **Step 3: 更新 spec 状态为 ✅ 已实现（Live 通过后）**

- [ ] **Step 4: Commit**

```bash
git add scripts/verify_async_tool_await_live.py \
  docs/superpowers/specs/2026-08-12-async-tool-await-design.md \
  CLAUDE.md
git commit -m "$(cat <<'EOF'
test(async-tool): add live verifier for background exec and spawn

EOF
)"
```

---

## Spec coverage（自检）

| Spec 项 | Task |
|---------|------|
| AsyncToolRunRegistry + 预算 3 / peek 不计次 | T2 |
| await_tool_run 元工具 MAIN | T3 |
| Nacos 默认 30/120/3/600 | T1 |
| sandbox__exec background | T5 |
| spawn_subagent background | T6 |
| Catalog overlay | T4 |
| 取消 / 主会话 stop | T7 |
| 墙钟 wall_timeout | T2+T5+T6 |
| 并发上限 | T2+T5+T6 |
| Live | T8 |
| 禁止 bump epoch | T6/T7 复用既有 cancel |

## 锁定细则（实施勿改）

1. 进入阻塞等待即 `waitCount++`；仅已终态即时返回不计次  
2. `budget_exhausted` **不**自动 kill 后台（除非墙钟）；模型收束/换方案  
3. await 上正常 `tool-*` 时间线步（不上 subagent/decision 卡）  
