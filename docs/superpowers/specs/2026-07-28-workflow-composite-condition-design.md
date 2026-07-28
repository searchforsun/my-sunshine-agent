# Workflow 条件复合化设计（loop 继续条件 + exclusive-gateway 出边条件）

## 背景

当前 workflow 的条件判断过于简单：

- **loop 继续条件**：单一三元组 `condition.left + condition.op + condition.right`，无法表达"检索结果数 > 0 且 agent 状态 != done"这样的复合逻辑。
- **exclusive-gateway 出边条件**：同样是单个 `PlanEdgeCondition`，无法表达"金额 > 1000 且类型 = 紧急"这种多条件分支。

对比 Dify loop 节点，它支持终止条件表达式（可引用 body 内节点变量）、最大循环次数、Exit Loop 节点三种终止机制。当前实现连最基本的"多条件组合"都做不到。

## 目标

将 loop 继续条件和 exclusive-gateway 出边条件统一升级为**多条件数组 + AND/OR 组合**，复用现有结构化算子体系，不引入脚本引擎。

## 非目标

- 不做 Exit Loop 节点（YAGNI，多条件组合已覆盖主要场景）
- 不引入表达式字符串求值（不符合"结构化算子，无脚本"哲学）
- 不改变 loop 的 do-while 语义（至少一轮，body 后求值）

## 数据模型

### 新增类型：`PlanEdgeConditionGroup`

```java
public record PlanEdgeConditionGroup(
        String logic,                      // "and" | "or"，默认 "and"
        List<PlanEdgeCondition> items      // 条件列表
) {
    public PlanEdgeConditionGroup {
        logic = (logic == null || logic.isBlank()) ? "and" : logic.strip().toLowerCase();
        items = items != null ? List.copyOf(items) : List.of();
    }

    public static PlanEdgeConditionGroup single(PlanEdgeCondition c) { ... }
    public static PlanEdgeConditionGroup empty() { ... }
    public boolean isEmpty() { return items.isEmpty(); }
}
```

`PlanEdgeCondition`（单个三元组）保持不变，仅新增算子。

### 新增算子

在 `PlanEdgeCondition` 和 `EdgeConditionEvaluator` 中新增：

| 算子 | 语义 | `isComplete()` 要求 |
|------|------|---------------------|
| `not_eq` | 不等于 | left + right |
| `not_contains` | 不包含 | left + right |

### loop 参数结构

```json
{
  "conditions": [
    { "left": "{{rag-1.hitsCount}}", "op": "gt", "right": "0" },
    { "left": "{{agent-1.status}}", "op": "not_eq", "right": "done" }
  ],
  "conditionLogic": "and",
  "maxIterations": "3",
  "onMaxIterations": "exit"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `conditions` | `Array<{left, op, right}>` | 条件列表。空数组 = 永远继续（靠 maxIterations 兜底） |
| `conditionLogic` | `"and"` \| `"or"` | 条件间组合逻辑，默认 `and` |

**向后兼容**：旧 `condition.left`/`condition.op`/`condition.right` 自动转换为单元素 `conditions` 数组。

### exclusive-gateway 出边条件结构

```json
{
  "from": "gw-1",
  "to": "branch-a",
  "condition": {
    "logic": "and",
    "items": [
      { "left": "{{tool-1.output.amount}}", "op": "gt", "right": "1000" },
      { "left": "{{tool-1.output.type}}", "op": "eq", "right": "urgent" }
    ]
  }
}
```

**向后兼容**：旧 `{left, op, right}` 格式自动转换为 `{logic:"and", items:[{left,op,right}]}`。

## 求值规则

### 统一求值方法

`EdgeConditionEvaluator` 新增：

```java
public static boolean matchesGroup(PlanEdgeConditionGroup group, WorkflowContext ctx)
```

- `items` 为空 -> 返回 `true`（组内无条件 = 恒真）
- `logic == "or"` -> 任一 `matches(item, ctx)` 为 true 则 true
- `logic == "and"`（默认）-> 全部 `matches(item, ctx)` 为 true 则 true

### loop 求值语义

```java
// body 执行后
if (!EdgeConditionEvaluator.matchesGroup(loopConditions, wfCtx)) {
    // 终止循环
}
```

- **空 conditions = 永远继续**（靠 maxIterations 兜底），与 Dify "无条件则跑到 max" 一致。
- 条件可引用 body 内节点变量（求值点在 `executeNodeOrder(body)` 完成之后，`wfCtx` 已含 body 输出）。

### exclusive-gateway 求值语义

```java
for (ExclusiveArm arm : arms) {
    if (arm.isDefault()) { fallback = arm; continue; }
    if (EdgeConditionEvaluator.matchesGroup(arm.conditionGroup(), wfCtx)) {
        return arm;
    }
}
return fallback;
```

- **空 items = 永远不匹配**（走 default）。与 loop 的"空=继续"语义相反，因为 edge 的条件是"选中此臂"的条件，而非"继续"的条件。
- `PlanEdgeConditionGroup` 的 `isEmpty()` 返回 true 时，`matchesGroup` 返回 true（恒真），但在 `pickExclusiveArm` 中空组臂不参与匹配（等同 `hasCondition() == false`），走 default。

### 空条件的语义差异处理

| 场景 | 空条件语义 | 实现方式 |
|------|-----------|----------|
| loop | 永远继续（true） | `matchesGroup` 空组返回 true，loop 直接用 |
| exclusive edge | 不命中（走 default） | `pickExclusiveArm` 跳过 `isEmpty()` 的臂 |

`PlanEdge.hasCondition()` 改为 `conditionGroup != null && !conditionGroup.isEmpty() && items 中至少一个 isComplete()`。

## 类型变更清单

### `PlanEdgeCondition`（修改）

- `isComplete()` 新增 `not_eq`/`not_contains` 分支（规则同 `eq`/`contains`，需 left + right）

### `PlanEdgeConditionGroup`（新增）

- 如上所述

### `PlanEdge`（修改）

- `condition` 字段类型从 `PlanEdgeCondition` 改为 `PlanEdgeConditionGroup`
- 构造器兼容：`new PlanEdge(from, to)` 仍可用（condition = null）
- `hasCondition()` 语义调整

### `PlanExecutionSchedule.ExclusiveArm`（修改）

- `condition` 字段类型从 `PlanEdgeCondition` 改为 `PlanEdgeConditionGroup`

### `EdgeConditionEvaluator`（修改）

- 新增 `not_eq`/`not_contains` case
- 新增 `matchesGroup(PlanEdgeConditionGroup, WorkflowContext)` 方法
- 原 `matches(PlanEdgeCondition, WorkflowContext)` 保留（内部复用）

### `PlanJsonParser`（修改）

- `parseCondition` -> `parseConditionGroup`：解析 `{logic, items}` 或兼容旧 `{left, op, right}`
- loop params 解析：`parseLoopConditions` 读 `conditions` 数组或兼容旧 `condition.*`

### `WorkflowExecutor`（修改）

- `startLoopCycle`：从读单个 `PlanEdgeCondition` 改为读 `PlanEdgeConditionGroup`
- 求值点：`EdgeConditionEvaluator.matches(condition, wfCtx)` -> `matchesGroup(conditionGroup, wfCtx)`
- `pickExclusiveArm`：`matches(arm.condition(), wfCtx)` -> `matchesGroup(arm.conditionGroup(), wfCtx)`

## 前端 UI

### loop 条件编辑器（重构）

```
┌─ 循环 ────────────────────────────────────────┐
│ 组合逻辑： [● AND] [○ OR]                      │
│                                                │
│ 条件列表：                                     │
│ ┌──────────────────────────────────────────┐  │
│ │ [{{rag-1.hitsCount}}] [📋] [gt ▼] [0  ] [✕]│  │
│ │ [{{agent-1.status}} ] [📋] [not_eq▼][done][✕]│  │
│ └──────────────────────────────────────────┘  │
│ ＋ 添加条件                                    │
│                                                │
│ 最大轮次：   [3   ▾]                           │
│ 超限策略：   [exit ▾]                          │
└────────────────────────────────────────────────┘
```

**条件行**：
- 左值：`NInput`（手动输入 `{{node.field}}`）+ `📋` 按钮（点击弹出 `VariableReferencePicker`，选中后回填）
- 算子：`NSelect`，含全部 12 个算子
- 右值：`NInput`（`empty`/`not_empty` 时隐藏）
- 删除：`✕` 按钮

**组合逻辑**：`NRadioGroup`，AND / OR 二选一。

**数据写入**：直接操作 `params.conditions` 数组和 `params.conditionLogic`。

**向后兼容**：加载时，旧 `condition.left/op/right` 转换为单元素 `conditions`；保存时只写 `conditions` 格式。

### exclusive-gateway 出边条件编辑器（重构）

`WorkflowExclusiveEdgesSection.vue` 中每条非 default 出边：

```
┌─ gw-1 -> branch-a ──────────── [✓默认] ─┐
│ 组合逻辑： [● AND] [○ OR]                │
│ ┌──────────────────────────────────────┐│
│ │ [{{tool-1.output.amount}}] [📋] [gt▼] [1000] [✕]││
│ │ [{{tool-1.output.type}}  ] [📋] [eq▼] [urgent][✕]││
│ └──────────────────────────────────────┘│
│ ＋ 添加条件                              │
└──────────────────────────────────────────┘
```

与 loop 条件行复用同一套组件逻辑。

### 复用组件：`ConditionGroupEditor.vue`

抽取一个通用组件，接收 `modelValue: { logic, items }`，emit 更新。loop 和 exclusive-gateway 共用。

### `loopConditionLeft` / `exclusiveGatewayConditionLeft` 函数

这两个函数强制取上游节点变量。新设计中不再需要，左值由用户自由选择。保留仅用于旧数据加载回填。

### 算子选项

`CONDITION_OP_OPTIONS` 追加：

```js
{ label: '不等于 not_eq', value: 'not_eq' },
{ label: '不包含 not_contains', value: 'not_contains' },
```

## 标杆 workflow 升级

### `knowledge-loop`

当前：`{{start.userQuery}} contains "继续"`（条件恒定，无意义循环）

升级为引用 body 内节点输出的多条件：

```json
{
  "conditions": [
    { "left": "{{rag-l1o2o3p4.status}}", "op": "eq", "right": "no_hits" },
    { "left": "{{tool-t1o2o3p4.output}}", "op": "not_contains", "right": "已完成" }
  ],
  "conditionLogic": "and",
  "maxIterations": "3",
  "onMaxIterations": "exit"
}
```

语义：检索无结果 **且** 工具未返回"已完成"时继续循环。

> 注：具体字段名实施时根据 `RagNodeHandler`/`ToolNodeHandler` 实际输出 schema 调整。

### `knowledge-branch`（exclusive-gateway 标杆）

当前：单条件 `{{start.userQuery}} contains "报销"`

升级为多条件示例：

```json
{
  "condition": {
    "logic": "or",
    "items": [
      { "left": "{{start.userQuery}}", "op": "contains", "right": "报销" },
      { "left": "{{start.userQuery}}", "op": "contains", "right": "发票" }
    ]
  }
}
```

语义：查询包含"报销"或"发票"时走此分支。

## 测试覆盖

### 后端单元测试

- `EdgeConditionEvaluatorTest`：
  - `not_eq`/`not_contains` 算子
  - `matchesGroup` AND 逻辑（全真才真）
  - `matchesGroup` OR 逻辑（一真即真）
  - `matchesGroup` 空组返回 true
- `PlanJsonParserTest`：
  - 解析 `{logic, items}` 复合条件
  - 兼容旧 `{left, op, right}` 单条件
  - loop `conditions` 数组解析
  - 兼容旧 `condition.left/op/right`

### 验收脚本

- `verify_loop_live.py`：
  1. 多条件 AND：全真 -> 继续；一假 -> 终止
  2. 多条件 OR：一真 -> 继续；全假 -> 终止
  3. 空 conditions -> 跑满 maxIterations
  4. 旧单条件格式向后兼容
- `verify_exclusive_gateway_live.py`：
  1. 多条件 AND 命中
  2. 多条件 OR 命中
  3. 多条件不命中 -> 走 default
  4. 旧单条件格式向后兼容

## 向后兼容总结

| 场景 | 旧格式 | 新格式 | 兼容策略 |
|------|--------|--------|----------|
| loop 条件 | `condition.left/op/right` | `conditions[] + conditionLogic` | 解析时旧格式转单元素数组 |
| edge 条件 | `{left, op, right}` | `{logic, items[]}` | 解析时旧格式转 `{logic:"and", items:[...]}` |
| 算子 | 无 `not_eq`/`not_contains` | 新增 2 个 | 纯新增，不影响旧数据 |

所有旧 workflow 的 `plan_json` 无需迁移，解析层自动适配。保存时统一写新格式。
