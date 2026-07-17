# 4.7.6 ReAct Spawn Subagent（主 Agent 按需创建隔离子 Agent）

> **状态**：实施完成（检查门 S1 Live + S5 单测；S4 soft）  

> **日期**：2026-07-18  
> **编号**：阶段四 **4.7.6**（取代原 **4.7.1** `DelegateSkillTool` / Skill 委派语义）  
> **相关**：`AgentRuntime` / `AgentRunRequest.sub` · [taskboard](./2026-06-24-react-taskboard-design.md) · [D9 编排器-Worker](./2026-06-19-locked-architecture-decisions.md#d9-子-agent--编排器-worker上下文由编排层传入) · Workflow agent 抽屉 HITL · 方案 B 对话级沙箱

---

## 1. 背景与目标

主 ReAct 长链路易导致 **上下文膨胀**。需要类似 Cursor 的能力：主 Agent **按需创建**子 Agent，由主动态写入任务提示词；子跑 **上下文隔离**，结束后仅把 **最终文本** 回传主 Agent。

| 目标 | 说明 |
|------|------|
| 控膨胀 | 子 think/tool **不进入**主 ReAct memory；主只消费 tool result 终态文本 |
| 隔离 | 每次子跑 `MemoryContext.empty()`，独立 `runId` |
| 动态提示词 | 主写入 `prompt`；**不绑定 Skill** |
| 可观测 | 主时间线 **一张卡 + 一行摘要**；详情进 **子 Agent 抽屉**（含传入 prompt） |

### 1.1 与既有能力正交

| 能力 | 关系 |
|------|------|
| Workflow / Plan `agent` 节点 | DAG 调度的 Worker；本能力是 **ReAct MAIN 元工具** |
| `manage_tasks` / TaskBoard | 软规划清单；**不**替代隔离子跑 |
| `#` / `$` / `PEER_COLLAB` | 路由/协作模式；本工具不参与路由 |
| 原 **4.7.1** Skill 委派 | **废弃**，由本设计取代 |
| 原 **4.7.4** 子 Agent 展开 UI | **不做**（Workflow agent / Peer 已够用） |
| **4.7.2** 并行 agent 节点 | 仍属 Plan/Workflow；本能力并行是 ReAct 同轮多 `spawn_subagent` |

---

## 2. 方案选型

采用 **方案 1：元工具 `spawn_subagent`**（仅 `AgentRole.MAIN` 注册），内部 `AgentRuntime.run(SUB)`。

不采用：临时物化迷你 Plan（过重）；同会话伪隔离（边界不清）。

---

## 3. 架构与调用契约

```
主 ReAct (MAIN)
  └─ tool_call: spawn_subagent({ prompt, label? })  × N（可并行）
        └─ AgentRuntime.run(SUB)
              · memory = empty
              · query = prompt（主写入，原样；禁止截断/摘要加工）
              · tools = 与 MAIN 同 Catalog（不含 spawn_subagent）
              · conversationId = 主会话（沙箱复用）
              · 返回：最终文本 → 该次 tool result
```

| 项 | 约定 |
|----|------|
| 工具名 | `spawn_subagent` |
| Catalog | **orchestrator 内置元工具**（同 `manage_tasks`），**不**进 tool-manager |
| 参数 `prompt` | 必填；主写给子的完整任务说明 |
| 参数 `label` | 可选；主时间线卡片展示名，缺省「子任务」 |
| 注册 | 仅 MAIN；SUB / Workflow agent / Expert **不注册** |
| 嵌套 | SUB 若出现委派调用 → **硬拒**（仅主→子一层） |
| 并行 | 同轮多个 `spawn_subagent` → 并发 SUB；各自独立 runId / 上下文 / 卡片 |
| 回传 | tool result = 子 **最终文本**（不对结果二次加工） |
| 记忆 | 子不写回主 STM；主仅通过 tool result 看见产出 |
| 失败 | 子失败/超时 → tool result 含错误信息；主可重试或改 prompt |

### 3.1 组件

| 组件 | 职责 |
|------|------|
| `SpawnSubagentTool` | 元工具；解析参数；启动 SUB；等待终态；返回文本 |
| `SubagentTimelineBridge`（或扩展现有 Hook/Bridge） | 子事件折叠进 `subagent-{runId}` 的 `subSteps`；主卡一行 `summary` |
| `DynamicToolkitFactory` / Factory | MAIN 注册 `spawn_subagent`；SUB **剥离**该工具 |
| 前端 `SubagentCard` + `SubagentDrawer` | 主时间线卡片；点击开抽屉 |

---

## 4. Timeline / UI（Cursor 式）

### 4.1 主时间线

- 每次成功发起的 `spawn_subagent` → **一张**卡片（id=`subagent-{runId}`，或 `phase=subagent`）。
- 卡片仅显示：
  - **状态**：运行中 / 待确认 / 完成 / 失败
  - **一行**当前执行摘要（SSE `summary` 当前阶段；Nacos `agent.timeline.subagent`）
- **禁止**：把子 think/tool 抬到主 ReAct 步骤栈；**禁止**主卡内联展开长文。

### 4.2 子 Agent 抽屉

点击卡片 → 打开抽屉（交互习惯对齐 `PlanNodeDrawer`）：

| 区块 | 内容 |
|------|------|
| 传入提示词 | 主写入的 `prompt`（原样；可进 `metadata.spawnPrompt`） |
| 执行过程 | `subSteps`（think / tool / …） |
| HITL | 写工具确认（`HitlStepActions`，在抽屉内） |
| 最终输出 | 子终态文本（与 tool result 同源） |

并行：多卡并列；点哪张开哪张抽屉。

### 4.3 HITL

- 对齐 Workflow 子 Agent：确认在 **抽屉内**；主卡状态反映 `awaiting_confirm`。
- Resume 走现有 SUB `bindHitlBridge` / `assistantMessageId` 映射。
- 并行多 HITL：各子独立；UI 可同时多个待确认（不强制全局串行队列）。

### 4.4 沙箱

- SUB 注入与 MAIN 相同的 `sandbox__*`；`conversationId` = 主会话 → **同一对话级容器**。
- 工作区抽屉仍挂主会话；子写入对主可见。

### 4.5 SSE

- 子内部事件经 bridge **折叠**进对应 `subagent-*`.`subSteps`。
- 终态 `COMPLETE`/`FAIL` **必须下发**。
- 不对模型输出做截断/过滤兜底。

---

## 5. 配置（Nacos SSOT）

| 键 | 用途 |
|----|------|
| `agent.prompt.mode-overlays.react`（增量） | 何时调用 `spawn_subagent`、如何写 `prompt`、勿把大段中间推理塞进主上下文 |
| `agent.timeline.subagent` | `summary.{before,active,after}`（**一行**短摘要） |
| `agent.subagent.enabled` | Feature flag；默认 true（或与上线节奏一致） |
| `agent.subagent.max-iters` | 子跑 maxIters（可与现有 SUB 默认对齐） |
| 可选 `agent.subagent.timeout-ms` | 单子超时 |

改 YAML 后：`python scripts/sync_nacos.py` → 重启 orchestrator。

---

## 6. 边界与非目标

**做**

- ReAct MAIN 元工具 + SUB 运行时 + 主卡/抽屉 UI + HITL/沙箱复用

**不做**

- 绑 Skill / Catalog overlay 委派（废弃 4.7.1）
- 4.7.4 独立「展开体验」排期
- 子再委派、无限嵌套
- Plan/Workflow 内并行 agent 节点（4.7.2）
- 对子产出截断/摘要；前端话术 Map

**锁定决策修订**

- D9 仍适用于 **Workflow/Plan agent 节点**。
- 本能力为 **ReAct 可选元工具**：调度权在主 LLM，但实现上仍走 `AgentRunRequest.sub` Worker，不引入第二套运行时。

---

## 7. 检查门

| # | 场景 | 期望 |
|---|------|------|
| S1 | 主调用 `spawn_subagent` | 主时间线一张子卡 + 一行摘要；主 memory 无子 think |
| S2 | 点开抽屉 | 可见传入 **prompt** + `subSteps`；关抽屉主卡仍在 |
| S3 | 子内写工具 HITL | 抽屉确认 → 续跑；主卡 `待确认`→`运行中` |
| S4 | 同轮两个 `spawn_subagent` | 两卡并行；两份 tool result 回主 |
| S5 | SUB 再委派 | 硬拒；错误进 tool result；无二层卡 |
| S6 | 沙箱 | 子 `sandbox__write` 落入主会话工作区 |

Live：建议 `scripts/verify_spawn_subagent_live.py`（可与 `phase2_agent_demo --suite react` 互补）。

---

## 8. 文档与编号同步（实施时）

- [x] `phase4-platformization-design.md` §4.7：新增 **4.7.6**；标注 **4.7.1 废弃**、**4.7.4 不做**
- [x] `implementation-plan.md` 阶段四 4.7 行同步
- [x] `CLAUDE.md` 时间线表增补 ReAct `subagent-*` 一行（实施完成后）
- [x] 本目录 README / specs 索引按需挂链

---

## 9. 风险与对策

| 风险 | 对策 |
|------|------|
| 主滥调子导致扇出 | Nacos 提示词约束；可选后续加 max-concurrent（本版不强制） |
| 并行 HITL 体验乱 | 抽屉按卡隔离；主卡状态清晰 |
| 与 `manage_tasks` 混淆 | overlay 写明：清单用 TaskBoard，重活/隔离用 `spawn_subagent` |
| 子工具集过大 | v1 与主同集（已拍板）；后续若要子集另开迭代 |

---

## 10. 自检清单

- [x] 无 TBD/TODO 占位需求
- [x] 与 §1–§3 对话结论一致（工具同集、一层、并行、卡片+抽屉含 prompt、HITL/沙箱）
- [x] 范围可落单一实施计划（4.7.6）
- [x] 工具名/参数/UI/Nacos/检查门无歧义
