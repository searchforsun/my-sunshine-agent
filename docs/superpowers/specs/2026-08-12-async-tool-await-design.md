# 异步长工具 + await_tool_run（exec / spawn）

> **状态**：✅ 已实现 · Live：`scripts/verify_async_tool_await_live.py`（S-EXEC / S-SPAWN）· 实施计划见 [`../plans/2026-08-12-async-tool-await.md`](../plans/2026-08-12-async-tool-await.md)  
> **日期**：2026-08-12  
> **编号**：阶段四增量（工具执行模型）  
> **相关**：`SpawnRunRegistry` · `CancellableToolRunRegistry` · `DynamicToolkitFactory` · spawn-subagent · sandbox-tool-cancel · Catalog `mode-overlay.react`  
> **决策**：方案 **A**；首期覆盖 **`sandbox__exec` + `spawn_subagent`**

---

## 1. 背景与目标

长工具（沙箱长命令、子 Agent）同步占用主 ReAct 的 tool 槽时，整轮被堵死。AgentScope Toolkit 默认工具超时 **5min**，会先于业务 `blockLast` 掐断，UI 易呈现「已取消 · 300s」。继续拉长同步超时治标不治本。

| 目标 | 说明 |
|------|------|
| 不堵主循环 | 长工具可后台跑，立即返回 `runId` |
| 分片观察 | 元工具 `await_tool_run` 在合理窗口内等待/窥视状态 |
| 硬预算 | **运行时强制**每 run 最多 **3** 次 await；超限结构化返回，模型收束或换方案 |
| 可取消 | 复用现有 cancel 链路，禁止 bump 整轮 stream epoch |

### 1.1 与既有能力正交

| 能力 | 关系 |
|------|------|
| `CancellableToolRunRegistry` | 同步 exec 取消；异步 exec 委托同一 kill 能力 |
| `SpawnRunRegistry` | 子 Agent 单独 interrupt；异步 spawn 仍注册，await 读终态 |
| HITL / `request_decision` | 用户闸；本能力是工具执行模型，不替代决策 |
| Toolkit `executionConfig` | 同步工具墙钟；异步 start 的短回执不受 5min 误伤；后台 run 另有墙钟 |

### 1.2 明确不做

- 任意 Catalog 工具一刀切后台化  
- wait 真 `Thread.sleep` 占满 worker  
- 仅靠提示词「请只等三次」  
- 前端本地话术 Map / 对模型输出截断兜底  

---

## 2. 方案选型

**方案 A（已选）**：长工具 `background=true` 时立即返回 `runId`；元工具 `await_tool_run(runId, timeout_sec?)`；注册表强制 wait 预算；超限 `budget_exhausted`。

备选已否决：仅异步 spawn（覆盖不全）；继续加长同步超时（主循环仍假死）。

---

## 3. 运行时模型

### 3.1 `AsyncToolRunRegistry`（新建）

统一门面；内部委托：

- `kind=sandbox_exec` → 会话/invocation 与 `CancellableToolRunRegistry` 对齐  
- `kind=spawn_subagent` → `SpawnRunRegistry` + 后台 Flux 收集终态  

| 字段 | 说明 |
|------|------|
| `runId` | 对外句柄（exec 可用 toolUseId 或新 UUID；spawn 用既有 runId） |
| `kind` | `sandbox_exec` \| `spawn_subagent` |
| `messageId` / `conversationId` | 审计与 cancel 路由 |
| `status` | `running` \| `done` \| `error` \| `cancelled` \| `budget_exhausted` \| `wall_timeout` |
| `waitCount` | 已消耗的 await 次数（0–3）；**终态 peek 不计次** |
| `startedAt` / `deadlineAt` | 墙钟 |
| `result` / `partial` | 终态全文或超时前片段 |

### 3.2 调用时序

```
MAIN ReAct
  ├─ sandbox__exec({ background:true, command, ... })
  │     → 注册 run → 后台 docker exec
  │     → tool result: { ok:true, runId, status:"running" }   // 立即
  ├─ spawn_subagent({ background:true, prompt, ... })
  │     → 同今日 begin 卡 + 后台 dispatch
  │     → tool result: { ok:true, runId, status:"running" }
  ├─ await_tool_run({ runId, timeout_sec? })   // 最多 3 次（running 等待才计次）
  │     → running 且未超时：事件完成即醒 / 到期仍 running
  │     → 终态：返回 result（不计 wait 次）
  │     → 第 4 次企图：budget_exhausted + partial
  └─ 模型收束 | 换方案 | 再调其它工具
```

---

## 4. 工具契约

### 4.1 `sandbox__exec`

| 参数 | 说明 |
|------|------|
| 既有 | `command` / `cwd` / `timeout_sec` 等不变 |
| `background` | **默认 `false`**；`true` 时异步 |

- `background=false`：保持同步语义（受 Toolkit + 命令 timeout 约束）  
- `background=true`：立即 `{ok, runId, status:"running"}`；命令在后台跑；cancel 仍走现有 generations tools cancel  

### 4.2 `spawn_subagent`

| 参数 | 说明 |
|------|------|
| 既有 | `prompt` / `agent_id` / `label` |
| `background` | **默认 `false`** |

- `false`：同步 `blockLast`（chat/task 分档 timeout-ms / task-timeout-ms）  
- `true`：立即返回 `runId`；子卡 `subagent-{runId}` 与 subSteps 照旧；主 Agent 用 await 收结果  

可选后续（非首期）：Nacos `subagent.background-default-for-task=true`。

### 4.3 元工具 `await_tool_run`（仅 MAIN）

| 参数 | 类型 | 说明 |
|------|------|------|
| `runId` | string | 必填 |
| `timeout_sec` | number | 可选；**默认 30**；**上限 120**；小于 1 按 1 |

**返回（JSON，禁止散文二次加工）**：

```json
{
  "ok": true,
  "runId": "...",
  "status": "running|done|error|cancelled|budget_exhausted|wall_timeout",
  "waitCount": 2,
  "waitBudget": 3,
  "elapsedMs": 45000,
  "result": "...",
  "partial": "..."
}
```

| 规则 | 行为 |
|------|------|
| 未知 runId | `ok=false`, error 文案 |
| 已终态 | 立即返回；**不增加** `waitCount` |
| 仍 running | 最多等 `timeout_sec`；完成则终态；否则 `status=running` 且 `waitCount++` |
| `waitCount` 已达 3 且仍要等 | 不再阻塞；`status=budget_exhausted`，附 `partial` |
| 墙钟到期 | 后台标 `wall_timeout`，cancel/kill 尽力；await 读到即返回 |

### 4.4 Catalog

- 新增工具描述 / MAIN overlay 条款：何时 `background=true`、如何 await、超预算必须收束或换方案  
- 文案 SSOT：`/prompts` Catalog；禁止 orchestrator/前端硬编码话术 Map  
- `await_tool_run` **不**进 tool-manager Catalog 也可（与 spawn/decision 同属内置元工具）；若进 Catalog 须 `@SunshineTool` 或 orchestrator 内置注册一致  

---

## 5. 时间线 / SSE

| 事件 | 表现 |
|------|------|
| 异步 start | exec：工具步 `lifecycle=running`；spawn：既有 subagent 卡 |
| await 进行中 | `step_delta` 更新 `summary.active`（如等待进度）；**不**新开假 think |
| await 计次 | metadata 可带 `asyncWait: { count, budget }`（可选，供抽屉） |
| 终态 | `done` / `error` / `paused`（取消）与现契约一致 |
| 预算耗尽 | 工具步/`subagent` 卡 `error` 或明确 after；result 为结构化 JSON 字符串 |

主时间线仍禁止本地步骤话术 Map；子 Agent 抽屉 subSteps 不变。

---

## 6. 超时与预算分层

| 层 | 配置（建议默认） | 作用 |
|----|------------------|------|
| Toolkit `executionConfig` | `max(chat,task)` spawn 上限 | 同步工具不被默认 5min 误杀 |
| await `timeout_sec` | 默认 30，上限 120 | 单次观察窗口 |
| await 次数 | **3**（硬编码 + Nacos 可调 `await-max-waits`） | 防空转 |
| 后台 exec 墙钟 | Nacos `async.exec.wall-timeout-sec` 默认 600 | 命令级 |
| 后台 spawn 墙钟 | 复用 `timeout-ms` / `task-timeout-ms` | 与同步分档一致 |

`budget_exhausted` 与 `wall_timeout` 均可触发「向用户说明 / 换方案 / 主 Agent 接手」（对齐 `react.subagent.cancel-result` 模式，另备 Catalog 模板）。

---

## 7. 取消

| 入口 | 行为 |
|------|------|
| 用户取消子 Agent 卡 | 既有 `SpawnRunRegistry.cancel`；registry 状态 `cancelled` |
| 用户取消沙箱工具步 | 既有 cancellable cancel + sandbox kill |
| 主会话 stop | 取消该 message 下所有 async runs |
| await 中取消 | Future/ sink 完成；await 返回 `cancelled` |

禁止因单 run 取消 bump 整轮 stream epoch（保持 4.7.6 约束）。

---

## 8. Nacos / 配置（SSOT）

```yaml
agent:
  execution:
    react:
      async-tool:
        enabled: true
        await-default-sec: 30
        await-max-sec: 120
        await-max-waits: 3
        exec-wall-timeout-sec: 600
      subagent:
        timeout-ms: 300000          # chat
        task-timeout-ms: 600000     # task（亦作后台 spawn 墙钟）
```

灰度：`async-tool.enabled` 默认 **true**（工具参数默认 `background=false`，行为不突变）；若需总闸可先 false。

---

## 9. 实施切片（建议）

| 序 | 切片 | 验收要点 |
|----|------|----------|
| P0 | `AsyncToolRunRegistry` + `await_tool_run` 元工具 + Catalog | 假 run 可 await / 预算 3 次 |
| P1 | `sandbox__exec` `background=true` | 立即返回；await 收 stdout；cancel kill |
| P2 | `spawn_subagent` `background=true` | 子卡仍流式；await 收终稿；单独取消 |
| P3 | Live 脚本 `verify_async_tool_await_live.py` | exec + spawn 各一条路径 |

---

## 10. 风险与约束

| 风险 | 缓解 |
|------|------|
| 模型不 await 就结束 | overlay 要求：有 running run 时终态前必须 await 或显式放弃 |
| 并行过多后台 exec | 同 message 并发上限（建议 3，Nacos） |
| Toolkit 仍掐同步路径 | 保持 executionConfig ≥ task-timeout |
| 结果过大 | result 截断策略走现有 sandbox 输出上限，不另造摘要兜底 |

---

## 11. 已锁定默认值（评审确认）

1. await 默认 **30s**，单次上限 **120s**，最多 **3** 次  
2. `background` 默认 **false**（显式才异步）  
3. 终态 peek **不计** wait 次数  
4. 首期仅 `sandbox__exec` + `spawn_subagent`  
