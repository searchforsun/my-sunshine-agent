# 可取消沙箱工具（exec / grep / glob）单工具取消

> **状态**：✅ 已落地（Live：`verify_sandbox_tool_cancel_live.py`）  
> **计划**：[2026-07-18-sandbox-tool-cancel.md](../../plans/2026-07-18-sandbox-tool-cancel.md)  

> **日期**：2026-07-18  
> **相关**：子 Agent 单独取消（`SpawnRunRegistry`）· `SandboxAgentTools` · `OperationCard` · Chat 底栏停止图标  
> **示意**：`docs/superpowers/mockups/2026-07-18-sandbox-tool-cancel-hover.html`  
> **索引**：[`docs/sandbox/README.md`](../../../sandbox/README.md)

---

## 1. 背景与目标

`sandbox__exec`（及可能较久的 `grep` / `glob`）运行时，工具行右侧只显示耗时，用户无法只停这一次调用。整轮「停止生成」会误伤主 ReAct。

| 目标 | 说明 |
|------|------|
| 单工具取消 | Hover 出取消钮 → 杀掉该次沙箱调用；**主 Agent 继续** |
| 换方案重试 | 取消后由 **LLM** 换命令/换工具；同族最多再执行 **3** 次 |
| UI 一致 | 图标与 Chat 底栏「停止生成」同形（**圆形**外框 + 圆角方块），缩到工具行一行高 |
| 时间线 | 主行 `summary.after` = **已取消**（不可恢复，勿用「已暂停」）；展开可看原 command/pattern |

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
| 可取消工具 | `sandbox__exec`、`sandbox__grep`、`sandbox__glob`（Nacos `agent.sandbox.cancellable-tools`） |
| 按钮语义 | 展示「暂停」；行为 = **真取消**（杀进程，**不可恢复**） |
| 终态文案 | `cancel-after: 已取消`；`cancel-result` 引导换方案 |
| 重试主体 | 主 Agent（LLM）；引擎不自动换参 |
| 预算 | 本轮消息内，**首次用户取消**后激活：同族后续再调用最多 **3** 次；第 4 次硬拒 |
| 预算计数 | 每次「进入 invoke 前」+1；被取消的那次**不占**这 3 次 |
| 整轮停止 | Composer「停止生成」语义不变 |

---

## 4. 架构与数据流

```
Main ReAct
  └─ tool_call: sandbox__exec|grep|glob
        ├─ PreActing / execute 入口 CancellableToolRunRegistry.register
        ├─ SandboxClient.invoke(..., invocationId=toolUseId)
        │     └─ sandbox-service DockerCli Process（可 destroyForcibly）
        └─ UI hover → POST .../tools/{stepId|toolUseId}/cancel
              → Registry.cancel（pending 竞态可提前记）→ kill
              → timeline paused + after=已取消 + detail=command
              → cancel-result → Main 换方案（≤3）
```

**原则**

- 按 `toolUseId` / 时间线 `tool-*` stepId 中断；**禁止** `GenerationRegistry.cancel` / `bumpStreamEpoch`
- PreActing 出卡即 register；cancel 早于 execute 时走 `pendingCancel`
- SSE：`lifecycle=paused` 时 `ProcessingStepSerde` **必须下发 `summary.after`**（勿落入 default→仅 active）

---

## 5. 后端（落地要点）

| 组件 | 职责 |
|------|------|
| `CancellableToolRunRegistry` | in-flight + pending cancel + 同族预算 |
| sandbox-service | `POST .../invocations/{id}/cancel`；header `x-sandbox-invocation-id` |
| `SandboxAgentTools` | register / bindSession / cancelResult；展开 detail=command\|pattern |
| `GenerationController` | `POST /generations/{id}/tools/{toolRef}/cancel`；立刻 pause SSE |
| `ProcessingStepSerde` | `paused` → `currentPhaseSummary` 下发 **after** |
| Nacos | `cancellable-tools` / `cancel-after` / `cancel-result` / `budget-exhausted` / react overlay |

---

## 6. 前端

| 项 | 约定 |
|----|------|
| `OperationCard` | `live && running && isCancellableSandboxTool` → hover 圆钮+方块 |
| 主行 | `paused` → `resolveStepHeaderText` 显示 **已取消**（读 `summary.after`） |
| 展开 | exec：`$ {command}`（command 来自 detail / 原 active 快照） |
| API | `cancelCancellableTool(stepId)`；path 可为 `tool-sandbox__exec@…` |

---

## 7. 验收

| 项 | 期望 |
|----|------|
| UI | running + hover 出圆钮；取消后主行「已取消」、可展开命令 |
| Cancel | 进程停；工具步 `paused`；主消息 `completed` |
| 接手 | 正文体现主 Agent 换方案 |
| 预算 | 取消后同族第 4 次硬拒 |
| 回归 | Composer 整轮停止仍可用 |

**Live**：`python3 scripts/verify_sandbox_tool_cancel_live.py`

---

## 8. 明确不做

- message 级 `bumpStreamEpoch` 杀单个工具
- 引擎同参/自动换参重试循环
- read / write / edit 取消钮
- 对模型输出截断、摘要、过滤兜底
