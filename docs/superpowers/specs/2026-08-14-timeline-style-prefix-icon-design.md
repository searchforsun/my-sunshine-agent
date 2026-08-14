# 时间线前缀图标（极简 / 标准）设计

> **状态**：📋 评审中
> **日期**：2026-08-14
> **编号**：阶段四增量（纯前端 · Chat 时间线展示）
> **前置**：[timeline-summary-duration](./2026-07-20-timeline-summary-duration-design.md)（总览行）· `OperationStack` / `OperationCard` / `ToolGroupCard` 现有行模型
> **一句话**：给 Chat 时间线每种步骤行增加**类型前缀图标**（think/工具/检索/沙箱/计划/子智能体/决策…），把 `>`/`^` 折叠箭头从文字末尾**迁移到图标槽位**（默认图标，hover 换箭头）；通过用户偏好「时间线风格：极简/标准」双模式切换，极简 = 完全现状。

## 1. 背景与问题

Chat 时间线（`OperationStack` 体系）当前所有可展开行都是「文字 + 行尾 `>`/`^` chevron」形态：chevron 默认 `opacity:0`，行 hover 显示 `>`，展开态 rotate 90° 变 `^`。问题：

- **无类型标识**：时间线只有文字，用户扫读时无法靠视觉前缀快速区分「思考 / 工具 / 检索 / 沙箱 / 计划 / 子智能体 / 决策」等步骤类型。
- **chevron 位置靠文字末尾**：随文字长度漂移，行首没有稳定锚点，视觉对齐性弱。

项目已有先例：`PlanNodeIcon.vue`（Workflow DAG 画布节点类型图标）已有 `tool / rag / plan / task / agent / llm / answer` 等线性 stroke 图标；`sandboxToolKind` 已按用途细分沙箱工具（view/edit/fetch/exec）。

## 2. 目标

1. **行首类型图标**：每种步骤行在行首固定宽度槽位显示类型图标（细粒度，一一对应）。
2. **chevron 迁移**：折叠箭头从文字末尾移到图标槽位；默认显示类型图标，行 hover（仅可展开行）时图标淡出、`>`/`^` 淡入；展开态图标恢复。
3. **用户偏好双模式**：设置「对话偏好」新增「时间线风格：极简 / 标准」；**极简 = 完全现状**（默认值，不改存量用户观感）；标准 = 行首图标 + chevron 迁移。

**非目标**：
- **已是卡片形式的组件不加前缀图标**：`SubagentCard` / `DecisionCard`（完整卡片式头部）、`TaskBoardPanel` / `PlanDagPanel`（面板）——这些保持现状，无行首图标、无 chevron 迁移
- 图标**无彩色**：统一灰色（`--sun-text-muted`），rag 不保留蓝调、running 态不提亮
- 不做服务端存储 / 跨端同步（纯 UI 偏好，localStorage）
- 不引入图标库依赖（沿用自绘 SVG 风格）

## 3. 偏好层：`useTimelineStyle`

新增 `sunshine-ui/src/composables/useTimelineStyle.ts`，仿 `useExecutionPreference` 的模块级 ref + localStorage 模式：

```ts
export type TimelineStyle = 'minimal' | 'standard'

const TIMELINE_STYLE_STORAGE_KEY = 'sunshine.timeline.style'

function loadGlobal(): TimelineStyle { /* localStorage 读取；非法值回落 'minimal' */ }
const timelineStyle = ref<TimelineStyle>(loadGlobal())

export function useTimelineStyle() {
  function setTimelineStyle(next: TimelineStyle) {
    timelineStyle.value = next
    localStorage.setItem(TIMELINE_STYLE_STORAGE_KEY, next)
  }
  return { timelineStyle, setTimelineStyle }
}
```

- 默认 `'minimal'`；组件直接读取模块级 `timelineStyle` ref，响应式生效，无需注入。

## 4. 图标层：`TimelineStepIcon.vue`

新增 `sunshine-ui/src/components/operation/TimelineStepIcon.vue`，props `{ step: ProcessingStep; size?: number }`。自绘 16×16 线性 stroke（`viewBox 0 0 16 16`、stroke-width 1.25，风格对齐 `PlanNodeIcon`）。

**解析优先级（复用现有判别函数）**：

| 优先级 | 判别 | 图标语义 |
|:--:|------|------|
| 1 | `isDecisionStep(step)` | 决策：问号对话框 |
| 2 | `isSubagentStep(step)` | 子智能体：人形 |
| 3 | `isWorkerStep(step)` / `isHarnessPlanStep(step)` | worker / plan：执行层 |
| 4 | `isRagStepId(step.id)` | 知识检索：放大镜 |
| 5 | `step.phase === 'intent'` | 意图：岔路 |
| 6 | `step.phase === 'skill'` | 技能：闪电 |
| 7 | `step.phase === 'tasks'` | 任务清单：剪贴板 |
| 8 | `step.phase === 'plan'` | 计划：层次列表 |
| 9 | `isThinkStepId(step.id)` | 深度思考：灯泡 |
| 10 | 工具步（`isToolStepId`） | 按 `sandboxToolKind` 细分：`view`=文件 / `edit`=铅笔 / `fetch`=网页 / `exec`=终端；其余=扳手 |
| 兜底 | — | 通用圆点 |

**颜色**：统一 `--sun-text-muted`（**无彩色**），running 态不额外提亮、rag 不保留蓝调 mix。图标仅表达类型语义，状态（进行/完成）仍由文字与 shimmer 承担，避免图标抢视觉。

## 5. 接入与交互（标准模式）

### 5.1 行首图标槽位

在 `OperationCard.vue`、`ToolGroupCard.vue`、`OperationStack.vue`（`timeline-summary` 行 + `round-group` 行）的 `.op-main` **首部**插入统一图标槽位：

```html
<span class="op-step-icon">
  <TimelineStepIcon class="op-type-icon" :step="step" />
  <svg class="op-chevron" …>  <!-- 迁移自原行尾 -->
</span>
```

- 槽位 CSS：`width:16px; flex-shrink:0;`（`align-items:center`），内部 type-icon 与 chevron **绝对定位同槽重叠**；
- 默认 `opacity` 图标 1 / chevron 0；行 hover（可展开行）图标 0 / chevron 0.85；展开态 chevron rotate 90°（`^`），维持现有过渡动画语义；
- 不可展开行（`canExpand` 为 false）hover 不换 chevron，保持类型图标常显。
- 原行尾 chevron 在标准模式移除（CSS `display:none`），避免双箭头。

### 5.2 双模式切换

`OperationStack.vue` 根元素根据 `timelineStyle` ref 挂 `is-timeline-standard` class；子组件内所有标准模式样式挂在 `.is-timeline-standard .op-step-icon …` 选择器下。**极简模式不渲染槽位 DOM**（`v-if="timelineStyle === 'standard'"`），CSS 完全走现有路径，零回归。

标准模式各行图标槽位宽度统一（16px + gap），保证 card / 工具组 / roundGroup / summary 行**左对齐**。

### 5.3 折叠态

- 折叠总览行（`timeline-summary`）：槽位内 chevron 沿用「hover / 展开态」显示逻辑，类型图标取该消息首步类型（若可判定）。
- 折叠预览步（`collapsedPreviewStep`）：`OperationCard` 的 `hideChevron` 态无 chevron，槽位仅显示类型图标。
- roundGroup：槽位图标取组内首步类型。

## 6. 设置入口

`UserSettingsModal.vue`「对话偏好」组新增「时间线风格」：

- 新增 `TimelineStyleSelector.vue`，用 `NTabs type="segment"`（对齐 `SidebarSectionsLayoutSelector` 风格），选项 `极简 / 标准`；
- 打开设置时读 `timelineStyle` 回显，保存时调 `setTimelineStyle`；
- 走本地设置 `useExecutionPreference` 同路径，**不进 `updateProfile` / 不落后端字段**。

## 7. 改动清单

| 文件 | 改动 |
|------|------|
| `sunshine-ui/src/composables/useTimelineStyle.ts` | 新增：偏好 ref + localStorage |
| `sunshine-ui/src/components/operation/TimelineStepIcon.vue` | 新增：步骤类型图标映射 |
| `sunshine-ui/src/components/chat/TimelineStyleSelector.vue` | 新增：设置项 segmented 选择器 |
| `sunshine-ui/src/components/UserSettingsModal.vue` | 「对话偏好」新增「时间线风格」项 |
| `sunshine-ui/src/components/operation/OperationCard.vue` | 行首图标槽位 + 标准模式样式 |
| `sunshine-ui/src/components/operation/ToolGroupCard.vue` | 行首图标槽位 + 标准模式样式 |
| `sunshine-ui/src/components/operation/OperationStack.vue` | 根 class `is-timeline-standard` + summary/roundGroup 槽位 + 样式 |

**明确不做（卡片形式组件）**：`SubagentCard` / `DecisionCard` / `TaskBoardPanel` / `PlanDagPanel` 不加前缀图标，保持卡片/面板现状。

## 8. 验收

| 用例 | 预期 |
|------|------|
| 默认（未设置） | 时间线完全现状，无行首图标、chevron 仍在行尾（极简） |
| 设置→标准 | 各行首出现类型图标；hover 可展开行 → 图标淡出、`>` 淡入；点击展开 → `^`；再点击收起 → 恢复类型图标 |
| 图标颜色 | 所有图标统一灰色（`--sun-text-muted`），rag / running 态无彩色变化 |
| 卡片形式组件 | `SubagentCard` / `DecisionCard` / `TaskBoardPanel` / `PlanDagPanel` 不出现前缀图标 |
| 设置→极简 | 恢复现状样式 |
| 不可展开行（如纯 tool 无 detail） | hover 不出现 chevron，类型图标保持 |
| think / rag / sandbox-view / sandbox-exec / subagent / decision 等各类步骤 | 各自显示对应类型图标 |
| 刷新页面 / 换浏览器会话 | 偏好持久（localStorage） |

## 9. 不做（后续）

- SubagentCard / DecisionCard 行首图标（v1 保持卡片式）
- 图标库依赖 / 服务端偏好同步
- 更多时间线风格（如紧凑密度）
