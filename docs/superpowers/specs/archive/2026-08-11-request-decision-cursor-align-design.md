# 4.7.9-r1 Request Decision 对齐 Cursor `ask_question`

> **状态**：✅ 已实现  

> **日期**：2026-08-11  
> **父文档**：[2026-07-28-react-request-decision-design.md](./2026-07-28-react-request-decision-design.md)（Chat MAIN ✅ 已归档；本修订为工具/UI/回传契约 SSOT）· Planner D12 ✅ 见 [./2026-08-12-react-request-decision-planner-d12.md](./2026-08-12-react-request-decision-planner-d12.md)  
> **参考**：[Cursor ACP `cursor/ask_question`](https://cursor.com/docs/cli/acp.md)  
> **范围**：Chat ReAct MAIN ✅ + Planner MAIN（D12 ✅）；Worker / SUB 不注册  
> **灰度**：`agent.execution.react.decision.enabled` 默认 **false**（D21 不变）

---

## 1. 目标

将已落地的 `request_decision` **端到端对齐** Cursor ACP `cursor/ask_question`：

| 能力 | 约定 |
|------|------|
| 多题 | 一次 tool call 传 `questions[]`（≥1） |
| 多选 | 每题 `allowMultiple?: boolean`（默认 false） |
| 选项形状 | `{ id, label }` only；**label 即答案文案**（删除 `value` / `description` / `requireInput`，避免模型歧义） |
| 手写逃生口 | **平台必有**：每题 UI 始终追加「其他（手写）」；**不**进模型 options，**无** `allow_custom_input` 参数 |
| 回传 | `answers[{ questionId, selectedOptionIds[] }]` + 可选 `customInput`（仅选了 `__custom__` 时） |

**非目标**：Planner MAIN；Cursor `skipped` outcome；改外部 skill；新增 SSE type。

---

## 2. 决策记录

| ID | 决策 |
|----|------|
| C1 | 实现路径 = **端到端 Cursor wire**（入参 + resolve + tool result 一并改），不做半对齐 |
| C2 | 手写 = 平台注入 `__custom__`，每题必有；选中则 `customInput` 非空才可提交 |
| C3 | D15 改写：同一 `messageId` 同时最多 **1 份问卷**（一次 call = 一个 token = 一张卡），卡内多题 |
| C4 | 多题 UI = **同卡一页全展示**（非 wizard 步进） |
| C5 | 删除工具参数：`question`、顶层 `options`、`allow_custom_input`；选项字段删 `value`/`description`/`requireInput`；**label 即答案，不加描述字段** |
| C6 | 工具 description 保持短文；「禁止正文出题」等策略只在 Catalog overlay |

---

## 3. 工具契约（模型可见）

```ts
// request_decision
{
  title?: string;
  questions: Array<{
    id: string;                 // 题内唯一
    prompt: string;             // 展示给用户的问题
    options: Array<{ id: string; label: string }>; // ≥2；id 题内唯一
    allowMultiple?: boolean;    // 默认 false
  }>;                           // ≥1
}
```

**短 description（示意）**：向用户出选择题并等待作答。需求歧义或下一步依赖用户偏好时使用。勿用于写工具 HITL 确认。

**校验（失败 → 错误 JSON，不下发卡片）**：

- `enabled == false`
- `questions` 空 / 缺；任一题 `id`/`prompt` 空；`prompt` 超长（≤500）
- 任一题 `options.size < 2`；option `id`/`label` 空；题内 `id` 重复；问卷内 `question.id` 重复
- `label` ≤64
- 非 MAIN / `sub-` bridge / 已有 awaiting 问卷 → 硬拒

**兼容**：不再接受旧扁平 `{ question, options[{value,...}], allow_custom_input }`（功能仍灰、无外部契约承诺）。

---

## 4. 数据结构

```java
public record DecisionOption(String id, String label) {}

public record DecisionQuestion(
        String id,
        String prompt,
        List<DecisionOption> options,
        boolean allowMultiple) {}

public record DecisionAnswer(
        String questionId,
        List<String> selectedOptionIds,
        String customInput) {}  // 含 __custom__ 时非空；否则 null

public record DecisionResult(
        String outcome,              // answered | timeout | cancelled
        String title,                // 可空
        List<DecisionAnswer> answers,
        long decidedAt) {}

public record DecisionStepMeta(
        String token,
        String title,
        List<DecisionQuestion> questions,
        Long expiresAt,
        String outcome,              // awaiting 时 null
        List<DecisionAnswer> answers) {}
```

平台常量：`CUSTOM_OPTION_ID = "__custom__"`（不出现在模型 options；UI/校验使用）。

---

## 5. Timeline / UI

### 5.1 主时间线

- 仍一张卡：`id=decision-{token}`，`phase=decision`。
- lifecycle：`awaiting` → `done` / `paused` / `error`（不变）。
- `metadata.decision` 示例：

```json
{
  "token": "{uuid}",
  "title": "需要确认",
  "questions": [
    {
      "id": "q1",
      "prompt": "用哪种模式？",
      "options": [
        {"id": "agent", "label": "Agent"},
        {"id": "plan", "label": "Plan"}
      ],
      "allowMultiple": false
    },
    {
      "id": "q2",
      "prompt": "关注哪些方面？",
      "options": [
        {"id": "perf", "label": "性能"},
        {"id": "ux", "label": "体验"}
      ],
      "allowMultiple": true
    }
  ],
  "expiresAt": 1753721880000
}
```

### 5.2 DecisionCard

- 展示可选 `title`；**同卡列出全部** `questions`。
- 单选：对号行单选；多选：对号行可多选（保持三兄弟边框分区样式）。
- 每题选项列表末尾固定追加「其他（手写）」；选中（单选）或勾选（多选）后展开输入框，提交前必填。
- 全部题目合法（每题 ≥1 个选中；`allowMultiple=false` 时恰好 1；含 `__custom__` 则 customInput 非空）后才可提交。
- 禁止前端截断/改写模型 options。

---

## 6. Resolve / Registry / 回传

### 6.1 API

```http
POST /api/generations/{id}/decisions/{token}/resolve
Content-Type: application/json

{
  "answers": [
    { "questionId": "q1", "selectedOptionIds": ["agent"] },
    { "questionId": "q2", "selectedOptionIds": ["perf", "__custom__"], "customInput": "还要安全" }
  ]
}
```

校验：

| 条件 | 错误码 |
|------|--------|
| token 无效/过期/非本 generation | `decision_expired` 等（沿用现有） |
| answers 未覆盖全部 questions / 多余 questionId | `decision_invalid_answers` |
| 单选题选中 ≠1；多选题选中 <1 | `decision_invalid_choice` |
| selected id ∉ 题 options ∪ `{__custom__}` | `decision_invalid_choice` |
| 含 `__custom__` 但 customInput 空白 | `decision_input_required` |

成功 → `{accepted: true}` + complete Future。

### 6.2 tool result（D18 修订，固定短文本）

成功：

```text
outcome=answered
title={title or empty}
q.{questionId}={id1,id2,...}
q.{questionId}.custom={text}          # 仅该题含 __custom__ 时出现
```

超时 / 取消：

```text
outcome=timeout
timeoutSec={n}
```

```text
outcome=cancelled
```

预决策续跑：fingerprint 基于 `title + questions`（id/prompt/options/allowMultiple）；已 resolve 的 `DecisionResult` 整包回放。

### 6.3 DecisionRegistry

- 仍：Redis `sunshine:decision:pending:` + 内存 Future；**一 message 一 awaiting 问卷**。
- `register(messageId, userId, title, questions)`；不再存扁平 question/options/allowCustom。
- `resolve(token, answers, userId, expectedMessageId)`。
- 超时/取消 Future 值：`outcome=timeout|cancelled`，`answers=[]`。

---

## 7. 受影响组件（实施清单）

| 层 | 文件/点 |
|----|---------|
| Tool | `RequestDecisionTool` 入参/校验/result |
| DTO | `DecisionOption` / `DecisionResult` / `DecisionStepMeta`；新增 `DecisionQuestion` / `DecisionAnswer` |
| Registry | `DecisionRegistry` / `DecisionPendingWaiter` / fingerprint |
| Timeline / Resume | `DecisionTimelineSupport` / `DecisionResumeSupport` / Labels 摘要占位 |
| API / BFF / UI API | resolve body；`decisions.ts`；`processingSteps` 类型 |
| UI | `DecisionCard.vue` 多题 + 多选 + 始终其他 |
| Prompt | Catalog `mode-overlay.react` 示例改为 `title/questions`；seed SQL |
| Live | `verify_decision_live.py` |
| Spec | 父文档 §3–§6 标记「已被本修订替换」；实施完成后归档本修订或合并回父文档 |

父文档其余不变：SSE 仅 `type:step`、MAIN 注册、Middleware 跳过 `tool-*`、不改 `WorkflowNodeRunner`、D21 默认关。

---

## 8. 验收要点

| # | 场景 |
|---|------|
| R1 | 单题单选 + 选平台「其他」并填字 → answered + custom 行 |
| R2 | 单题 `allowMultiple=true` 勾 2 项 → `selectedOptionIds` 长度 2 |
| R3 | 两题同卡；未答完不可提交；答完一次 resolve |
| R4 | awaiting 中第二次 `request_decision` → 错误 JSON，无第二张卡 |
| R5 | 暂停/续跑：同问卷 re-await；已提交则预决策跳过 |
| R6 | 模型不得依赖已删除的 `value`/`allow_custom_input` 参数（工具 schema 无这些字段） |

---

## 9. 自检

- 无 TBD；多题 UI 已定为同卡一页全展示（C4）。
- 与父文档冲突处：以本修订为准（工具形状、D15、resolve body、result 格式、选项字段）。
- 范围可单 plan 实施；不拆第二子系统。
