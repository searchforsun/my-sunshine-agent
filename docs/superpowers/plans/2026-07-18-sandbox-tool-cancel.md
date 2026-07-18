# Sandbox 工具单次取消（暂停）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 对 `sandbox__exec` / `grep` / `glob` 提供按 `toolUseId` 真取消（杀 docker 进程）；工具行 hover 显示 Chat 同款暂停图标；取消后 LLM 换方案，同族最多再执行 3 次。

**Architecture:** 对齐 `SpawnRunRegistry`：`CancellableToolRunRegistry` 登记 in-flight；sandbox-service 按 `invocationId` 持有 `Process` 并可 `destroyForcibly`；取消 **不** bump stream epoch；Tool result / Nacos 引导主 Agent 接手；前端 `OperationCard` hover 切换 duration ↔ 暂停钮。

**Tech Stack:** Java/Spring · sandbox-service DockerCli · Vue3 · Nacos · Live Python

**Spec:** [2026-07-18-sandbox-tool-cancel-design.md](../specs/2026-07-18-sandbox-tool-cancel-design.md)

---

## File map

| 文件 | 职责 |
|------|------|
| `orchestrator/.../sandbox/CancellableToolRunRegistry.java` | toolUseId → 句柄；cancel；message 级预算 |
| `orchestrator/.../sandbox/SandboxAgentTools.java` | register/budget/cancel 返回 |
| `orchestrator/.../client/SandboxClient.java` | invoke 带 invocationId；cancelInvocation |
| `sandbox-service/.../docker/DockerCli.java` | Process 登记 + cancel |
| `sandbox-service/.../api/SandboxSessionController.java` | cancel invocation API |
| `sandbox-service/.../tool/SandboxToolExecutor.java` | 透传 invocationId |
| `orchestrator/.../generation/GenerationController.java` | `POST .../tools/{toolUseId}/cancel` |
| `bff/.../GenerationController.java` + `OrchestratorClient` | 透传 |
| `orchestrator/.../processing/StepMetadata.java` (+ serde) | `toolUseId` |
| `orchestrator/.../agent/ProcessingStepHook.java` | begin 时写入 metadata.toolUseId；cancel 终态 |
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
- Test: `sandbox-service/src/test/.../DockerCliCancelTest.java`（或现有 DockerCli 测）

- [ ] **Step 1: DockerCli 支持 invocationId**

`runCapture` / `exec` 增加可选 `invocationId`：`start` 后 `activeProcesses.put(id, p)`；结束/超时 `remove`；新增 `cancel(String invocationId)` → `destroyForcibly`。

- [ ] **Step 2: Controller**

```
POST /api/sandbox/sessions/{id}/invocations/{invocationId}/cancel
→ 200 { cancelled: true|false }
```

工具 invoke 请求体或 header 带 `invocationId`（与 orchestrator 约定：body 字段 `_invocationId` 或 query；**优先** request header `x-sandbox-invocation-id`，避免污染工具参数）。

- [ ] **Step 3: 单测** — register Process 后 cancel 使 wait 结束

- [ ] **Step 4: Commit** `feat(sandbox): kill in-flight docker exec by invocationId`

---

### Task 2: CancellableToolRunRegistry + 预算

**Files:**
- Create: `orchestrator/.../sandbox/CancellableToolRunRegistry.java`
- Create: `orchestrator/.../sandbox/SandboxCancelBudget.java`（可内嵌 Registry）
- Test: `orchestrator/.../sandbox/CancellableToolRunRegistryTest.java`
- Modify: `docs/nacos/sunshine-orchestrator.yaml` + `AgentSandboxProperties`（或现有 sandbox props）

- [ ] **Step 1: Registry API**

```java
register(toolUseId, messageId, toolName, sessionId, invocationId)
cancel(toolUseId) // 调 SandboxClient.cancelInvocation；设 cancelled；激活预算
isCancelled / unregister
tryConsumeBudget(messageId, toolName) // 未激活则放行；激活后同族剩余>0 则 -- 并放行；否则 false
remaining(messageId)
```

默认名单：`sandbox__exec`,`sandbox__grep`,`sandbox__glob`；预算上限 3（Nacos）。

- [ ] **Step 2: 单测** cancel 标记；预算 3 次后拒绝；cancel 本身不占预算

- [ ] **Step 3: Commit** `feat(orch): CancellableToolRunRegistry and cancel budget`

---

### Task 3: SandboxClient + SandboxAgentTools 接线

**Files:**
- Modify: `orchestrator/.../client/SandboxClient.java`
- Modify: `orchestrator/.../sandbox/SandboxAgentTools.java`
- Modify: timeline complete/pause 路径（`ProcessingTimelineSession` / Hook）
- Test: `SandboxAgentToolsCancelTest.java`

- [ ] **Step 1: Client**

`invoke(sessionId, toolName, body, invocationId)` 设 header `x-sandbox-invocation-id`  
`cancelInvocation(sessionId, invocationId)`

- [ ] **Step 2: Tool.execute**

可取消工具：预算检查 → register → invoke → 若 cancelled 或异常含 interrupt/cancel：  
emit 工具步 `lifecycle=paused` + after 文案 → return cancel-result（含参数摘要 + remaining）  
`finally` unregister

- [ ] **Step 3: 单测** mock client；cancel 后返回文案且不 fail 整轮

- [ ] **Step 4: Commit** `feat(orch): wire sandbox tool cancel into SandboxAgentTools`

---

### Task 4: Generation cancel API + toolUseId metadata

**Files:**
- Modify: `GenerationController`（orch + bff + OrchestratorClient）
- Modify: `GenerationAutoConfiguration`（若手工 new Controller）
- Modify: `ProcessingStepHook` beginToolStep metadata
- Modify: `StepMetadata` + frontend parse

- [ ] **Step 1:** `POST /generations/{id}/tools/{toolUseId}/cancel`（鉴权同 spawn cancel）

- [ ] **Step 2:** begin 时 `StepMetadata.toolUseId`；SSE 下发

- [ ] **Step 3:** Commit `feat: API cancel tool by toolUseId + metadata`

---

### Task 5: Nacos 文案 + react overlay

**Files:**
- Modify: `docs/nacos/sunshine-orchestrator.yaml`
- Properties 绑定类

```yaml
agent.sandbox:
  cancellable-tools: [sandbox__exec, sandbox__grep, sandbox__glob]
  cancel-max-followups: 3
  cancel-result: |
    用户已暂停该沙箱工具调用。请换方案继续（勿重复同一命令）。原参数：{params}。本轮同族还可再调用 {remaining} 次。
  budget-exhausted: |
    本轮用户取消后同族沙箱工具调用次数已用尽，请直接作答或改用其它能力。
```

react overlay 增加取消后换方案条款。

- [ ] sync_nacos + Commit `feat: Nacos sandbox cancel copy and budget`

---

### Task 6: 前端 OperationCard hover 暂停

**Files:**
- Modify: `sunshine-ui/src/api/processingStepsDisplay.ts` — `isCancellableSandboxTool`
- Modify: `sunshine-ui/src/components/operation/OperationCard.vue`
- Modify: `sunshine-ui/src/api/chatSessions.ts` — `cancelCancellableTool`
- Modify: `sunshine-ui/src/views/ChatView.vue` — provide
- Modify: parse metadata.toolUseId

- [ ] Hover：`live && running && cancellable` → 藏 duration，显示圆钮+方块 SVG（同 ChatView），title/aria=`暂停`
- [ ] 点击调 API；paused 展示「已取消」
- [ ] Commit `feat(ui): hover pause to cancel sandbox tool row`

---

### Task 7: Live 验收

**Files:**
- Create: `scripts/verify_sandbox_tool_cancel_live.py`

- [ ] 诱导长 `sandbox__exec`（如 `sleep 60`）→ 捕获 generationId + toolUseId → cancel → 主消息 completed、工具步 paused
- [ ] 重启 orchestrator + sandbox-service 后跑脚本
- [ ] Commit `test: live verify sandbox tool cancel`

---

## 执行注意

- 禁止 bumpStreamEpoch
- 禁止硬编码业务句（走 Nacos）
- 改 docs/nacos 后 `python scripts/sync_nacos.py` 并重启消费服务
- 参考实现：`SpawnRunRegistry` / `GenerationController.cancelSubagent` / `SubagentCard` 停止接线
