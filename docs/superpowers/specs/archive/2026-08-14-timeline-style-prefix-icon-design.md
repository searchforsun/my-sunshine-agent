# 时间线前缀图标（极简 / 标准）设计

> **状态**：✅ 已实现
> **日期**：2026-08-14
> **修订**：2026-08-14 多轮迭代（粗几何 / 圆润线条 / 细线条 / squircle 容器 / 饱满细线条 / 极简点线）后，经用户明确指令，**最终完全参考 DeepSeek Harness（deepseek-ai/deepseek-harness）官方 `ic_ds_*` 图标库重绘**（第 6 轮）：全部改用其 **`fill="currentColor"` 饱满填充路径**风格——环形 = 外圆+内圆 fill 出的粗环、符号实心 fill、Q 曲线圆角、图形撑满 16 网格（坐标跨度约 15+）；官方图标（think 大脑、subagent 人形、worker 播放环、plan 列表+铅笔（第 6 轮列表铅笔 → 第 17 轮自绘计划书+对勾 → 第 18 轮 dsh `project_add` 与「新建工作区」重复被否 → 第 19 轮定稿 dsh `List_Pen` 列表+铅笔，计划清单/撰写计划）、answer 对勾、external 外链箭头、rag 放大镜、intent 目标靶、skill 徽章、tasks 圆点清单、tool-edit 铅笔、tool-fetch 地球、tool 线条扳手（第 10 轮三颗星 → 第 11 轮 Lucide wrench 实心 → 第 12 轮线框 fill 仍显实心 → 第 13 轮定稿 **Lucide wrench 描边**：`fill=none` + `stroke=currentColor` + `vector-effect=non-scaling-stroke`，纯线条非实心）、decision 问号环、tool-search 文件夹、summary 队列环）直接移植（MIT License, Copyright (c) 2026 DeepSeek），`node` 圆角菱形环 / `tool-view` 环形眼睛 / `generic` 圆点为同风格自绘；14 网格图标经 `scale(1.14286)` 铺满 16 网格；默认 size 16、统一灰色、无容器框。第 7 轮修订：intent 分支 → dsh 官方 `goal` 目标靶（贴切「意图=目标」）；`sandbox__glob`（查找文件）从 view 眼睛拆出为 `tool-search` 打开文件夹（非实心）；tool-view 眼睛重绘为环形镂空（非实心）；时间线总览折叠行新增专属 `summary` 队列环图标（不再借用首步类型）。第 8 轮修订：`tool-exec` 执行命令图标由 dsh `code`（`<>`）改为**流行的「圆角矩形终端框 + `>_` 提示符」**样式（圆角矩形环 + `>` 折线 + `_` 横线，非实心）。第 9 轮修订：**HITL 相关卡片前缀图标统一 dsh 风格**——`DecisionCard`（request_decision）行首 `HelpCircleOutline` 线条问号 → dsh `question` 问号环（同时间线 decision）；`CollapsibleConfirmPanel`（HITL 确认面板容器）三个场景图标由 Lucide 线条改为 dsh 填充：`tool`（工具调用确认）扳手 → `sparkle` 三颗星（同时间线 tool）、`recovery`（节点失败恢复）重试箭头 → `refresh` 刷新环、`approval`（计划执行确认）剪贴板+对勾 → `checklist` 圆点清单（同时间线 tasks）。第 10 轮修订：**SVG 属性兼容修复**——所有 `fillRule` 改为 kebab-case `fill-rule`（Chrome 仅识别后者；Vue 运行时 setAttribute 原样输出 camelCase 导致 evenodd 挖空失效，`tool-exec` 内外矩形同向被整体填充成实心块）。第 11 轮修订：**最外层轮次折叠层（roundGroup）新增专属 `round` 图标**——两个圆角矩形错落叠放（后层实心偏左上 + 前层环形偏右下，dsh 风格自绘），不再借用该轮首步类型图标；与同类工具折叠（toolGroup 保留工具类型图标）明确区分。第 12 轮修订：round 图标**改为双线框镂空版**（两层圆角矩形环，evenodd 挖空，**无实心**）。第 10 轮修订：**图标灰度与同行文字一致**——`DecisionCard` 问号环由 `--sun-text` 改为 `--sun-text-muted`（同行文字同色）；`CollapsibleConfirmPanel` 图标去除 `opacity: 0.72`，恢复 muted 本色；**决策结果文案全面去内部 id**——后端 `DecisionLabels.formatChoiceFromAnswers` 由 `q1=id` 改为展示选项 label（多选「、」连接、多题「；」分隔、自定义项展示手写内容，无题元数据兜底仅输出手写不暴露 id）；`RequestDecisionTool.formatSuccessResult`（注入模型的结果短格式）由 `q.{questionId}={ids}` 改为 `choice={labels}`（复用 label 映射），所有调用点（RequestDecisionTool/DecisionResumeSupport/ReactResumeContextSupport）同步；修复决策卡片 summary 与模型正文暴露内部参数（如 `target=fin_data`）问题。
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
- **已是卡片形式的组件不加前缀图标**：`SubagentCard` / `DecisionCard`（完整卡片式头部）、`TaskBoardPanel`（面板）——这些保持现状，无行首图标、无 chevron 迁移；`PlanDagPanel`（静态 Workflow DAG 面板）经第 16 轮修订**补充标准模式行首图标**（`symbol="plan"` 列表+铅笔，无 chevron，行不可折叠）
- 图标**无彩色**：统一灰色（`--sun-text-muted`），rag 不保留蓝调、running 态不提亮
- 不做服务端存储 / 跨端同步（纯 UI 偏好，localStorage）
- 不引入图标库依赖（沿用自绘 SVG 风格）

## 3. 偏好层：`useTimelineStyle`

新增 `sunshine-ui/src/composables/useTimelineStyle.ts`，仿 `useExecutionPreference` 的模块级 ref + localStorage 模式：

```ts
export type TimelineStyle = 'minimal' | 'standard'

const TIMELINE_STYLE_STORAGE_KEY = 'sunshine.timeline.style'

function loadTimelineStyle(): TimelineStyle {
  try {
    const raw = localStorage.getItem(TIMELINE_STYLE_STORAGE_KEY)
    if (raw === 'standard' || raw === 'minimal') return raw
  } catch { /* ignore */ }
  return 'minimal'
}
const timelineStyle = ref<TimelineStyle>(loadTimelineStyle())

export function useTimelineStyle() {
  function setTimelineStyle(next: TimelineStyle) {
    timelineStyle.value = next
    try {
      localStorage.setItem(TIMELINE_STYLE_STORAGE_KEY, next)
    } catch { /* ignore */ }
  }
  return { timelineStyle, setTimelineStyle }
}
```

- 默认 `'minimal'`；组件直接读取模块级 `timelineStyle` ref，响应式生效，无需注入。

## 4. 图标层：`TimelineStepIcon.vue`

新增 `sunshine-ui/src/components/operation/TimelineStepIcon.vue`，props `{ step: ProcessingStep; size?: number }`。移植 deepseek-ai/deepseek-harness 官方 `ic_ds_*` 图标（`fill="currentColor"` 饱满填充路径，`viewBox 0 0 16 16`），14 网格图标经 `scale(1.14286)` 铺满；`node`/`tool-view`/`generic` 同风格自绘。

**解析优先级（复用现有判别函数）**：

| 优先级 | 判别 | 图标语义（dsh `ic_ds_*` 官方 / 自绘） |
|:--:|------|------|
| 1 | `isDecisionStep(step)` | 决策：问号环 |
| 2 | `isSubagentStep(step)` | 子智能体：人形 |
| 3 | `isWorkerStep(step)` | worker：播放环（执行） |
| 4 | `isHarnessPlanStep(step)` | 执行计划：计划书+对勾（第 16 轮由列表铅笔改为**dsh 饱满 fill** 版本：外框+内框环带、实心折角、实心对勾） |
| 5 | `phase === 'answer'` 或 `id === 'planner-answer'` | 综合回答（Planner-Executor 合成步）：对勾 |
| 6 | `phase === 'external'` 或 id 前缀 `external-` | 外部智能体（A2A）：外链箭头 |
| 7 | `phase === 'node'` 或 id 前缀 `node-`（`node-answer` 归综合回答） | 业务节点（含 loop 轮次 `i{n}-node-*`）：圆角菱形环+中心点（自绘） |
| 8 | `isRagStepId(step.id)` | 知识检索：放大镜 |
| 9 | `step.phase === 'intent'` | 意图路由：目标靶+落地箭头（dsh `goal`） |
| 10 | `step.phase === 'skill'` | 技能：徽章+文档 |
| 11 | `step.phase === 'tasks'` | 任务清单：圆点清单 |
| 12 | `step.phase === 'plan'` | 执行计划：列表+铅笔（dsh `List_Pen`，计划清单/撰写计划；区别于 tool-edit 单支铅笔） |
| 13 | `isThinkStepId(step.id)` | 深度思考：大脑+中心点（对齐 dsh `think`） |
| 14 | 工具步（`isToolStepId`） | `sandbox__glob` 专属 `tool-search`=打开文件夹（dsh `folder_open`）；其余按 `sandboxToolKind` 细分：`view`=环形眼睛（自绘）/ `edit`=铅笔 / `fetch`=地球 / `exec`=终端 `>_`（自绘）；非沙箱=线条扳手（Lucide wrench 描边，**fill=none 纯线条**） |
| 15 | 总览折叠行（`symbol="summary"`） | 时间线汇总：队列环+横线（dsh `queue`） |
| 16 | 最外层轮次折叠行（`symbol="round"`） | 轮次折叠：堆叠图层（**两层**镂空圆角矩形环带对角错落，**无实心**，自绘；第 14 轮两框→三层→第 15 轮定稿**两层**大环带）——**区别于**同类工具折叠（toolGroup 保留工具类型图标） |
| 兜底 | — | 通用圆点（自绘） |

**图标风格（完全参考 DeepSeek Harness）**：16×16 网格、**默认 size 16**。全部图标采用 deepseek-ai/deepseek-harness 官方 `ic_ds_*` 图标库的 **`fill="currentColor"` 饱满填充路径**语言：环形 = 外圆+内圆 fill 出的粗环、符号实心 fill、Q 曲线圆角、图形撑满网格（坐标跨度约 15+），居中对称、无容器框、无多余细节。官方图标直接移植（MIT License, Copyright (c) 2026 DeepSeek；源文件 `packages/client/ui-primitives/src/icons/index.tsx`），14 网格图标（decision 问号环 / tasks 圆点清单 / tool-fetch 地球 / summary 队列环）经 `transform="scale(1.14286)"` 铺满 16 网格；`node` 圆角菱形环 / `tool-view` 环形眼睛 / `tool-exec` 终端 `>_` / `generic` 圆点为同风格自绘。语义一一对应（think=大脑、subagent=人形、worker=播放环、plan=列表+铅笔（dsh `List_Pen`）、answer=对勾、external=外链箭头、rag=放大镜、intent=目标靶、skill=徽章、tool-search=文件夹、tool-edit=铅笔、tool-fetch=地球、tool-exec=终端、tool=线条扳手、decision=问号环、tasks=圆点清单、summary=队列环）。

**颜色**：统一 `--sun-text-muted`（**无彩色**），running 态不额外提亮、rag 不保留蓝调 mix。图标仅表达类型语义，状态（进行/完成）仍由文字与 shimmer 承担，避免图标抢视觉。

## 5. 接入与交互（标准模式）

### 5.1 行首图标槽位

在 `OperationCard.vue`、`ToolGroupCard.vue`、`PlanDagPanel.vue`（静态 Workflow 执行计划行）、`OperationStack.vue`（`timeline-summary` 行 + `round-group` 行）的 `.op-main` **首部**插入统一图标槽位：

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

- 折叠总览行（`timeline-summary`）：槽位内 chevron 沿用「hover / 展开态」显示逻辑，类型图标用专属 `summary`（队列环），不借用首步类型。
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
| `sunshine-ui/src/components/operation/TimelineStepIcon.vue` | 新增：步骤类型图标映射（含 `summary`/`tool-search`；intent=目标靶、tool-view=环形眼睛） |
| `sunshine-ui/src/components/chat/TimelineStyleSelector.vue` | 新增：设置项 segmented 选择器 |
| `sunshine-ui/src/components/UserSettingsModal.vue` | 「对话偏好」新增「时间线风格」项 |
| `sunshine-ui/src/components/operation/OperationCard.vue` | 行首图标槽位 + 标准模式样式 |
| `sunshine-ui/src/components/operation/ToolGroupCard.vue` | 行首图标槽位 + 标准模式样式 |
| `sunshine-ui/src/components/operation/OperationStack.vue` | 根 class `is-timeline-standard` + summary/roundGroup 槽位 + 样式 |
| `sunshine-ui/src/components/plan/PlanDagPanel.vue` | 静态 Workflow 执行计划行槽位（`symbol="plan"`，无 chevron） |

**明确不做（卡片形式组件）**：`SubagentCard` / `DecisionCard` / `TaskBoardPanel` 不加前缀图标，保持卡片/面板现状。

## 8. 验收

| 用例 | 预期 |
|------|------|
| 默认（未设置） | 时间线完全现状，无行首图标、chevron 仍在行尾（极简） |
| 设置→标准 | 各行首出现类型图标；hover 可展开行 → 图标淡出、`>` 淡入；点击展开 → `^`；再点击收起 → 恢复类型图标 |
| 图标颜色 | 所有图标统一灰色（`--sun-text-muted`），rag / running 态无彩色变化 |
| 卡片形式组件 | `SubagentCard` / `DecisionCard` / `TaskBoardPanel` 不出现前缀图标；`PlanDagPanel` 静态 Workflow 执行计划行出现 `symbol="plan"` 图标（列表+铅笔，无 chevron，行不可折叠） |
| 设置→极简 | 恢复现状样式 |
| 不可展开行（如纯 tool 无 detail） | hover 不出现 chevron，类型图标保持 |
| think / rag / sandbox-view / sandbox-exec / subagent / decision 等各类步骤 | 各自显示对应类型图标 |
| Planner-Executor 综合回答（planner-answer） | 显示「综合回答」气泡+对勾图标 |
| 外部智能体（external-*） | 显示「外部智能体」云端+人形图标 |
| Workflow 业务节点（node-*，含 loop 内 `i{n}-node-*`） | 显示「业务节点」菱形图标；`node-answer` 显示综合回答图标 |
| 刷新页面 / 换浏览器会话 | 偏好持久（localStorage） |

## 9. 不做（后续）

- SubagentCard / DecisionCard 行首图标（v1 保持卡片式）
- 图标库依赖 / 服务端偏好同步
- 更多时间线风格（如紧凑密度）
