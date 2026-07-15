# Workflow Loop 容器节点设计

> **状态**：✅ **已落地收口** · 引擎 + Studio + 种子 `knowledge-loop` + Live `--suite loop`  
> **日期**：2026-07-14  
> **依赖**：4.13 Studio · exclusive-gateway 边条件算子 · Vue Flow 画布
> **计划**：`docs/superpowers/plans/2026-07-14-workflow-loop-container.md`

## 目标

引入 **loop 容器**（类 BPMN embedded subprocess）：**do-while（至少一轮）** + 线性 body + `maxIterations` 硬顶；**继续条件**与超限降级均可配置。

## 已确认决策

| 项 | 选择 |
|----|------|
| 语义 | **do-while**：首轮必进；body 后求值 **继续条件**（真→再一轮） |
| 条件含义 | **继续条件**（非结束条件）：为真则继续循环 |
| 形态 | 容器节点，body 在框内 |
| Body v1 | 仅线性 `rag` / `tool` / `agent` |
| 条件位置 | loop 容器 params |
| 图存储 | **扁平 nodes/edges** + body 节点 `parentId=loopId`（方案 B） |
| 超限 | **可配置降级**（见下） |

## 模型

```json
{
  "id": "loop-a1b2c3d4",
  "type": "loop",
  "displayName": "循环",
  "params": {
    "condition.left": "{{start.userQuery}}",
    "condition.op": "contains",
    "condition.right": "继续",
    "maxIterations": "3",
    "onMaxIterations": "fail_fast",
    "retry.maxAttempts": "1",
    "retry.backoffMs": "500",
    "retry.onFailure": "fail_fast"
  }
}
```

Body（同级 `nodes`，归属容器）：

```json
{ "id": "rag-xx", "type": "rag", "parentId": "loop-a1b2c3d4", "displayName": "框内检索", "params": { "...": "..." } }
```

### 边

| 边 | 约束 |
|----|------|
| 外图 `上游 → loop` | 入度 ≥ 1 |
| 外图 `loop → 下游` | **出度 = 1**（v1） |
| 框内 body 边 | 仅 `parentId` 相同的节点之间；须为单链（线性） |
| 禁止 | 跨框边；框内 parallel / exclusive / 嵌套 loop；任意回边 |

### 条件（复用 exclusive 算子）

- `condition.op`：`empty` \| `not_empty` \| `contains` \| `eq`
- `condition.left`：随 **loop 入边上数据前驱** 自动填入（业务前驱 `{{id.output\|answer}}`，否则 `{{start.userQuery}}`）；Studio 只读
- `condition.right`：`empty`/`not_empty` 可不填
- 求值：`TemplateResolver` + 现有 `EdgeConditionEvaluator` 逻辑

### 超限降级（可配置）

参数 **`onMaxIterations`**（与节点执行失败的 `retry.onFailure` 分立）：

| 值 | 行为 |
|----|------|
| `fail_fast` | **默认**。达到硬顶仍需继续循环时，终止整图，loop 步失败 |
| `exit` | 不再进入下一轮，携带 **最后一轮 body 末节点输出** 出框，继续下游 |
| `fallback_react` | 终止静态/Plan workflow，降级主 ReAct（与节点 `retry.onFailure=fallback_react` 同语义） |

- `maxIterations`：默认 **3**，Studio/校验硬顶 **5**（含）
- 达到硬顶的判定：完成本轮 body 后 `iter >= maxIterations` 且条件仍为 true 时触发策略（预检测：下一轮开始前发现 `iter >= max` 即触发）

## 运行时

1. 进入 loop；`iter = 0`；清空输出缓冲
2. 若 `iter >= maxIterations` → 按 `onMaxIterations` 处理，结束本节点
3. 按框内拓扑执行 body → 缓冲 = 末节点 output/answer → `iter++`
4. 求值 **继续条件**；false → 出框走唯一下游；true → 回步骤 2  
   - 第 2 轮起，条件/模板若引用 body 内节点，读 **上一轮** 已写入 ctx 的产出

Timeline：主时间线 **一个** `node-{loopId}` 步；body 内节点作为该步 `subSteps`（或等价折叠明细），避免主时间线被多轮打爆。

## Studio

- 工具栏「循环」→ 放置 loop 容器；**选中 loop 或框内节点**后再点 RAG/Tool/Agent → 写入 `parentId` 加入框内线性链；框内禁止再放网关/嵌套 loop
- 侧栏：**继续条件**（left 只读自动 / op / right）+ `maxIterations` + `onMaxIterations`；**不展示**节点「执行策略」
- 校验：body 非空且线性；无跨框边；loop 出度=1；条件完整；`maxIterations∈[1,5]`；`onMaxIterations` 合法
- Chat 抽屉：展示继续条件摘要、`maxIterations`、`onMaxIterations`、实际迭代次数（若有）
- 自动布局：外图只排无 `parentId` 节点（连线只挂 loop 框）；框内 body 按线性链横排；loop `width/height` 随内容撑开；可选 NodeResizer 手动调框

## 非目标（明确不做）

以下能力**不在当前形态范围内，不再排期**：

- for-each
- 预检测 while（允许 0 轮）
- 框内 parallel / exclusive / 嵌套 loop
- 多出边汇合
- 画布边条件标签

## 验收

- 单元：继续条件真/假、超限 `fail_fast` / `exit`、parentId 校验
- Live：首轮必进；继续条件为真可多轮；超限按策略

## 修订记录

| 日期 | 说明 |
|------|------|
| 2026-07-14 | 初稿：容器 + while + parentId；`onMaxIterations` 可配置 |
| 2026-07-14 | Studio：去掉循环说明文案；loop 侧栏隐藏「执行策略」 |
| 2026-07-14 | 修复：选中 loop/框内节点时工具栏 RAG/Tool/Agent 写入 parentId |
| 2026-07-14 | 自动布局：外图只连 loop 框；框内横排；尺寸随内容 + NodeResizer |
| 2026-07-14 | 种子 knowledge-loop 框内改为 rag→tool→agent 三节点经典链 |
| 2026-07-14 | 未进循环：种子条件改为 not_empty；Chat DAG 保留 parentId + layout 宽高 |
| 2026-07-14 | 语义改为 do-while + **继续条件**；首轮必进 |
| 2026-07-14 | Agent Hook 直刷 Generation 时经 `LoopBodyFlushFold` 折叠，避免主时间线泄漏 `node-agent` |
| 2026-07-15 | **收口**：当前 do-while + 线性 body 形态为终态；§非目标明确不做 |
