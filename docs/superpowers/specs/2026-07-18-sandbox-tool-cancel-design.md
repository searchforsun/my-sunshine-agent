# 可取消沙箱工具（exec / grep / glob）单工具暂停

> **状态**：设计待审阅  
> **日期**：2026-07-18  
> **相关**：子 Agent 单独取消（`SpawnRunRegistry`）· `SandboxAgentTools` · `OperationCard` · Chat 底栏停止图标  
> **示意**：`docs/superpowers/mockups/2026-07-18-sandbox-tool-cancel-hover.html`

---

## 1. 背景与目标

`sandbox__exec`（及可能较久的 `grep` / `glob`）运行时，工具行右侧只显示耗时，用户无法只停这一次调用。整轮「停止生成」会误伤主 ReAct。

| 目标 | 说明 |
|------|------|
| 单工具取消 | Hover 出暂停钮 → 杀掉该次沙箱调用；**主 Agent 继续** |
| 换方案重试 | 取消后由 **LLM** 换命令/换工具；同族最多再执行 **3** 次 |
| UI 一致 | 图标与 Chat 底栏「停止生成」同形（圆钮 + 圆角方块），缩到工具行一行高 |

**不做**：整轮 `bumpStreamEpoch`；引擎内自动换参；read/write/edit 取消；对模型输出截断二次加工。

---

## 2. 方案选型

采用 **方案 1：CancellableToolRunRegistry + sandbox kill + cancel-result（对齐 Spawn）**。

| 方案 | 结论 |
|------|------|
| 1. Registry + kill Process + LLM 接手 | **采用** |
| 2. 仅打断 HTTP、不杀容器内进程 | 否：资源泄漏 |
| 3. 整轮 generation cancel | 否：误伤整轮 |

---

## 3. 范围与产品约定

| 项 | 约定 |
|----|------|
| 可取消工具 | `sandbox__exec`、`sandbox__grep`、`sandbox__glob`（Nacos 名单可配） |
| 按钮语义 | 展示「暂停」；行为 = **真取消**该次调用（杀进程） |
| 重试主体 | 主 Agent（LLM）；引擎不自动换参 |
| 预算 | 本轮消息内，**首次用户取消**可取消工具后激活：同族后续再调用最多 **3** 次；第 4 次硬拒 |
| 预算计数 | 每次「进入 invoke 前」+1；被取消的那次**不占**这 3 次 |
| 整轮停止 | Composer「停止生成」语义不变 |

---

## 4. 架构与数据流

```
Main ReAct
  └─ tool_call: sandbox__exec|grep|glob
        ├─ CancellableToolRunRegistry.register(toolUseId, …)
        ├─ SandboxClient.invoke(sessionId, rpc, invocationId=toolUseId)
        │     └─ sandbox-service DockerCli Process（可 destroyForcibly）
        └─ UI hover → POST .../tools/{toolUseId}/cancel
              → Registry.cancel → kill + unblock
              → timeline paused + cancel-result → Main 换方案（≤3）
```

**原则**

- 按 `toolUseId` 中断；**禁止** `GenerationRegistry.cancel` / `bumpStreamEpoch`
- `toolUseId` 与 `ProcessingStepHook` / `StepEventBridge.bindToolUseStep` 一致
- SSE `metadata.toolUseId` 供前端（若仅有 step.id，后端支持映射）

---

## 5. 后端

### 5.1 `CancellableToolRunRegistry`

Spring 单例：`toolUseId → { messageId, toolName, sessionId, invocationId, cancelled }`  
`register` / `unregister` / `cancel(toolUseId)` / `isCancelled`。

### 5.2 sandbox-service

- `DockerCli.runCapture`：进行中 `Process` 按 `invocationId` 登记
- `POST /sessions/{sessionId}/invocations/{invocationId}/cancel` → `destroyForcibly`
- Orchestrator `SandboxClient.invoke` 传递 `invocationId`（建议 = `toolUseId`）

### 5.3 `SandboxAgentTools`

1. 若预算耗尽 → 直接返回 Nacos `budget-exhausted`（不 RPC）
2. register → invoke；若 cancelled / interrupt → timeline cancel + 返回 `cancel-result`
3. `finally` unregister

### 5.4 预算（message 级）

首次用户 cancel 成功后激活；同族（exec/grep/glob）后续发起调用计数；上限 3。

### 5.5 HTTP

`POST /api/generations/{generationId}/tools/{toolUseId}/cancel`  
校验 generation 归属与 messageId；BFF 透传。

### 5.6 时间线

工具步 `lifecycle=paused`，`summary.after` = Nacos；**必须**下发终态 step SSE。

### 5.7 Nacos（SSOT，禁止硬编码业务句）

建议键（落 `docs/nacos/sunshine-orchestrator.yaml`）：

| 键 | 用途 |
|----|------|
| `agent.sandbox.cancellable-tools` | 名单 |
| `agent.sandbox.cancel-result` | 含原参数 + `{remaining}` |
| `agent.sandbox.budget-exhausted` | 超限拒调 |
| `agent.timeline.sandbox.*-after-cancel`（或统一 after-cancel） | 父行 after |
| `agent.prompt.mode-overlays.react` | 取消后须换方案，勿无意义重复同命令 |

---

## 6. 前端

### 6.1 `OperationCard`

- 条件：`isCancellableSandboxTool(step) && live && running`
- 默认：`.op-dur`；`:hover` / `:focus-within`：藏耗时，显示暂停钮
- 图标：与 `ChatView` composer stop **同一 SVG**  
  `<rect x="3" y="3" width="10" height="10" rx="1.5"/>`，圆钮边框；高度约一行（非 34px 底栏尺寸）
- `title` / `aria-label`：`暂停`
- 点击 → `cancelCancellableTool(toolUseId)`（非整轮 `stopGeneration`）

### 6.2 接线

- `chatSessions.cancelCancellableTool` + ChatView `provide` / `inject`
- `toolUseId` 来自 step metadata（后端补齐）

### 6.3 状态展示

取消后 `paused` → 「已取消」类文案（可映射现有 paused 展示）。

---

## 7. 验收

| 项 | 期望 |
|----|------|
| UI | running + hover 出 Chat 同款方块暂停钮；非名单/非 running 无钮 |
| Cancel | 进程停；工具步 paused；主消息 `completed` |
| 接手 | 正文体现主 Agent 换方案继续 |
| 预算 | 取消后同族第 4 次调用被拒 |
| 回归 | Composer 整轮停止仍可用 |

Live：扩展现有 sandbox verify 或新增脚本（中途 cancel API）。

---

## 8. 明确不做

- message 级 `bumpStreamEpoch` 杀单个工具
- 引擎同参/自动换参重试循环
- read / write / edit 取消钮
- 对模型输出截断、摘要、过滤兜底
