# Sandbox 工具单次取消 Implementation Plan

> **状态**：✅ 已完成（2026-07-18）· Live `verify_sandbox_tool_cancel_live.py`  
> **Spec**：[2026-07-18-sandbox-tool-cancel-design.md](../specs/archive/2026-07-18-sandbox-tool-cancel-design.md)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 对 `sandbox__exec` / `grep` / `glob` 提供按 `toolUseId` 真取消（杀 docker 进程）；工具行 hover 显示 Chat 同款圆形取消图标；取消后 LLM 换方案，同族最多再执行 3 次；主行 after=**已取消**。

**Architecture:** 对齐 `SpawnRunRegistry`：`CancellableToolRunRegistry` 登记 in-flight；sandbox-service 按 `invocationId` 持有 `Process` 并可 `destroyForcibly`；取消 **不** bump stream epoch；Tool result / Nacos 引导主 Agent 接手；前端 `OperationCard` hover 切换 duration ↔ 暂停钮。

**Tech Stack:** Java/Spring · sandbox-service DockerCli · Vue3 · Nacos · Live Python

**Spec:** [2026-07-18-sandbox-tool-cancel-design.md](../specs/archive/2026-07-18-sandbox-tool-cancel-design.md)

---

## File map

| 文件 | 职责 |
|------|------|
| `orchestrator/.../sandbox/CancellableToolRunRegistry.java` | toolUseId → 句柄；cancel；message 级预算 |
| `orchestrator/.../sandbox/SandboxAgentTools.java` | register/budget/cancel 返回 |
| `orchestrator/.../client/SandboxClient.java` | invoke 带 invocationId；cancelInvocation |
| `sandbox-service/.../docker/SandboxInvocationRegistry.java` | Process/flag 句柄；cancel 校验 sessionId |
| `sandbox-service/.../docker/DockerCli.java` | bindProcess + destroyForcibly |
| `sandbox-service/.../api/SandboxSessionController.java` | cancel invocation API |
| `sandbox-service/.../tool/SandboxToolExecutor.java` | 透传 invocationId + session |
| `orchestrator/.../generation/GenerationController.java` | `POST .../tools/{toolRef}/cancel`（stepId 或 toolUseId） |
| `bff/.../GenerationController.java` + `OrchestratorClient` | 透传 |
| `orchestrator/.../processing/StepMetadata.java` (+ serde) | `cancellable`（UI 跟此字段） |
| `orchestrator/.../agent/ProcessingStepHook.java` | begin 时 `markCurrentToolCancellable` + Registry.register |
| `docs/nacos/sunshine-orchestrator.yaml` | cancellable-tools / cancel-result / budget / overlay |
| `sunshine-ui/.../OperationCard.vue` | hover 暂停钮 |
| `sunshine-ui/.../api/chatSessions.ts` + `ChatView.vue` | cancelCancellableTool |
| `sunshine-ui/.../api/processingStepsDisplay.ts` | `isCancellableSandboxTool` |
| `scripts/verify_sandbox_tool_cancel_live.py` | Live：中途 cancel |

---

### Task 1: sandbox-service Process 登记 + cancel API

**Files:**
- Modify: `sandbox-service/src/main/java/com/sunshine/sandbox/docker/DockerCli.java`
- Modify: `sandbox-service/src/main/java/com/sunshine/sandbox/tool/SandboxToolExecutor.java`（及 invoke 入口）
- Modify: `sandbox-service/src/main/java/com/sunshine/sandbox/api/SandboxSessionController.java`
- Test: `sandbox-service/src/test/.../SandboxInvocationRegistryTest.java`

- [x] **Step 1: SandboxInvocationRegistry + DockerCli**

`runCapture` / `exec` 带 `sessionId`+`invocationId` → `bindProcess`；结束/超时 `unbind`；`cancel(sessionId, invocationId)` → `destroyForcibly`（跨 session 拒绝）。

- [x] **Step 2: Controller**

```
POST /api/sandbox/sessions/{id}/invocations/{invocationId}/cancel
→ 200 { cancelled: true|false }
```

工具 invoke 请求体或 header 带 `invocationId`（与 orchestrator 约定：body 字段 `_invocationId` 或 query；**优先** request header `x-sandbox-invocation-id`，避免污染工具参数）。

- [x] **Step 3: 单测** — register Process 后 cancel 使 wait 结束

- [x] **Step 4: Commit** `feat(sandbox): kill in-flight docker exec by invocationId`

---

### Task 2: CancellableToolRunRegistry + 预算

**Files:**
- Create: `orchestrator/.../sandbox/CancellableToolRunRegistry.java`（预算内嵌，无独立 SandboxCancelBudget）
- Test: `orchestrator/.../sandbox/CancellableToolRunRegistryTest.java`
- Modify: `docs/nacos/sunshine-orchestrator.yaml` + `AgentSandboxProperties`（或现有 sandbox props）

- [x] **Step 1: Registry API**

```java
register(toolUseId, messageId, toolName, sessionId, invocationId)
cancel(toolUseId) // 调 SandboxClient.cancelInvocation；设 cancelled；激活预算
isCancelled / unregister
tryConsumeBudget(messageId, toolName) // 未激活则放行；激活后同族剩余>0 则 -- 并放行；否则 false
remaining(messageId)
```

默认名单：`sandbox__exec`,`sandbox__grep`,`sandbox__glob`；预算上限 3（Nacos）。

- [x] **Step 2: 单测** cancel 标记；预算 3 次后拒绝；cancel 本身不占预算

- [x] **Step 3: Commit** `feat(orch): CancellableToolRunRegistry and cancel budget`

---

### Task 3: SandboxClient + SandboxAgentTools 接线

**Files:**
- Modify: `orchestrator/.../client/SandboxClient.java`
- Modify: `orchestrator/.../sandbox/SandboxAgentTools.java`
- Modify: timeline complete/pause 路径（`ProcessingTimelineSession` / Hook）
- Test:（覆盖于 `CancellableToolRunRegistryTest` + Live；无独立 `SandboxAgentToolsCancelTest`）

- [x] **Step 1: Client**

`invoke(sessionId, toolName, body, invocationId)` 设 header `x-sandbox-invocation-id`  
`cancelInvocation(sessionId, invocationId)`

- [x] **Step 2: Tool.execute**

PreActing register；execute 仅补登记/bindSession → invoke → 若 cancelled 或 `meta.cancelled`：  
emit 工具步 `lifecycle=paused` + after/detail → return cancel-result  
PostActing：`consumeRecentlyCancelled` 跳过 complete（禁中文 contains）  
`finally` unregister

- [x] **Step 3: 单测 / Live** Registry + `verify_sandbox_tool_cancel_live.py`

- [x] **Step 4: Commit** `feat(orch): wire sandbox tool cancel into SandboxAgentTools`

---

### Task 4: Generation cancel API + toolUseId metadata

**Files:**
- Modify: `GenerationController`（orch + bff + OrchestratorClient）
- Modify: `GenerationAutoConfiguration`（若手工 new Controller）
- Modify: `ProcessingStepHook` beginToolStep metadata
- Modify: `StepMetadata` + frontend parse

- [x] **Step 1:** `POST /generations/{id}/tools/{toolRef}/cancel`（stepId 或 toolUseId；messageId 归属校验）

- [x] **Step 2:** begin 时 `StepMetadata.cancellable=true`；SSE 下发（UI 跟 metadata，勿硬编码名单）

- [x] **Step 3:** Commit `feat: API cancel tool by toolRef + metadata.cancellable`

---

### Task 5: Nacos 文案 + react overlay

**Files:**
- Modify: `docs/nacos/sunshine-orchestrator.yaml`
- Properties 绑定类

```yaml
agent.sandbox:
  cancellable-tools: [sandbox__exec, sandbox__grep, sandbox__glob]
  cancel-max-followups: 3
  cancel-after: 已取消
  cancel-result: |
    用户已取消该沙箱工具调用。请换方案继续（勿重复同一命令）。原参数：{params}。本轮同族还可再调用 {remaining} 次。
  budget-exhausted: |
    本轮用户取消后同族沙箱工具调用次数已用尽，请直接作答或改用其它能力。
```

react overlay 增加取消后换方案条款。

- [x] sync_nacos + Commit `feat: Nacos sandbox cancel copy and budget`

---

### Task 6: 前端 OperationCard hover 取消

**Files:**
- Modify: `sunshine-ui/src/api/processingStepsDisplay.ts` — `isCancellableSandboxTool`
- Modify: `sunshine-ui/src/components/operation/OperationCard.vue`
- Modify: `sunshine-ui/src/api/chatSessions.ts` — `cancelCancellableTool`
- Modify: `sunshine-ui/src/views/ChatView.vue` — provide
- Modify: parse metadata.toolUseId

- [x] Hover：`live && running && cancellable` → 藏 duration，显示圆钮+方块 SVG（同 ChatView），title/aria=`暂停`
- [x] 点击调 API；paused 主行「已取消」；展开可见 command
- [x] Commit `feat(ui): hover pause to cancel sandbox tool row`

---

### Task 7: Live 验收

**Files:**
- Create: `scripts/verify_sandbox_tool_cancel_live.py`

- [x] 诱导长 `sandbox__exec`（如 `sleep 60`）→ 捕获 generationId + toolUseId → cancel → 主消息 completed、工具步 paused
- [x] 重启 orchestrator + sandbox-service 后跑脚本
- [x] Commit `test: live verify sandbox tool cancel`

---

## 执行注意

- 禁止 bumpStreamEpoch
- 禁止硬编码业务句（走 Nacos）
- 改 docs/nacos 后 `python scripts/sync_nacos.py` 并重启消费服务
- 参考实现：`SpawnRunRegistry` / `GenerationController.cancelSubagent` / `SubagentCard` 停止接线
