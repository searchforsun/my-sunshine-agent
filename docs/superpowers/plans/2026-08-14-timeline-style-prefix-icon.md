# 时间线前缀图标（极简/标准）Implementation Plan

> **状态**：📋 待执行
> **Spec**：[2026-08-14-timeline-style-prefix-icon-design](../specs/2026-08-14-timeline-style-prefix-icon-design.md)
> **前置**：`OperationStack` / `OperationCard` / `ToolGroupCard` 现有行模型；`PlanNodeIcon` 图标风格
> **本 plan 不做**：SubagentCard / DecisionCard / TaskBoardPanel / PlanDagPanel 前缀图标；服务端偏好同步；图标库依赖
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 Chat 时间线步骤行加**行首类型图标**（think/工具/检索/沙箱/计划/子智能体/决策…，统一灰色），`>`/`^` 折叠箭头从文字末尾**迁移到图标槽位**（hover 互换）；用户偏好「时间线风格：极简/标准」双模式，极简 = 完全现状。

**Architecture:** 纯前端。偏好走 `useTimelineStyle`（模块级 ref + localStorage，仿 `useExecutionPreference`）；图标判别抽成纯函数 `resolveTimelineStepKind`（放 `api/`，可单测）；`TimelineStepIcon.vue` 仅做 kind→SVG 渲染；三个既有组件在标准模式 `v-if` 条件渲染行首槽位（16px 固定宽，type-icon 与 chevron 绝对定位重叠），极简模式槽位 DOM 不存在 → 零回归。卡片形式组件（SubagentCard/DecisionCard/任务板/计划画布）不动。

**Tech Stack:** Vue3 + Naive UI · Vitest · 自绘 SVG（对齐 `PlanNodeIcon` 线性风格）

## Global Constraints

- **极简模式必须与现状完全一致**：槽位用 `v-if="timelineStyle === 'standard'"` 条件渲染，极简模式不产生额外 DOM/宽度
- **图标统一灰色** `--sun-text-muted`；禁止彩色（rag 无蓝调、running 不提亮、状态由 shimmer/文字承担）
- **卡片形式组件不加前缀图标**：SubagentCard / DecisionCard / TaskBoardPanel / PlanDagPanel 保持现状
- chevron 迁移仅针对**可展开行**：不可展开行（如 sandbox read / 纯 tool 无 detail）hover 保持类型图标
- UI：禁止冗余解释性文案；背景 `--sun-black`、边框分区
- 前端测试：`cd sunshine-ui && npx vitest run <spec>`；UI 改动手动验收（见各 Task 验收步骤）
- 勿提交工作区既有未提交改动（当前分支有工具集相关存量修改，只 add 本 plan 涉及文件）

---

## File map

| 文件 | 职责 |
|------|------|
| `sunshine-ui/src/composables/useTimelineStyle.ts` | 新增：`TimelineStyle` 类型 + 模块级 ref + localStorage |
| `sunshine-ui/src/api/timelineStepIcon.ts` | 新增：`TimelineStepKind` + `resolveTimelineStepKind(step)` 纯函数 |
| `sunshine-ui/src/api/timelineStepIcon.spec.ts` | 新增：判别矩阵单测 |
| `sunshine-ui/src/components/operation/TimelineStepIcon.vue` | 新增：kind → 16×16 SVG 渲染 |
| `sunshine-ui/src/components/chat/TimelineStyleSelector.vue` | 新增：设置项 segmented 选择器 |
| `sunshine-ui/src/components/UserSettingsModal.vue` | 修改：「对话偏好」新增「时间线风格」 |
| `sunshine-ui/src/components/operation/OperationCard.vue` | 修改：行首图标槽位 + chevron 迁移 |
| `sunshine-ui/src/components/operation/ToolGroupCard.vue` | 修改：行首图标槽位 + chevron 迁移 |
| `sunshine-ui/src/components/operation/OperationStack.vue` | 修改：根 class + summary/roundGroup 槽位 |

---

## 现状锚点（实现前必读）

1. **判别函数签名**（已确认）：
   - `isSubagentStep(step: { id?; phase? })` / `isDecisionStep(step)` — `api/processingSteps.ts`
   - `isThinkStepId(id: string)` — `api/processingStepsNormalize.ts`
   - `isWorkerStep(step)` / `isHarnessPlanStep(step)` — `api/harnessHierarchy.ts`
   - `isRagStepId(id)` / `isToolStepId(id)` — `api/hitlSteps.ts`
   - `catalogToolIdFromStepId(id)` / `sandboxToolKind(toolId)` — `api/processingStepsDisplay.ts`
2. **OperationCard chevron 现状**：`canExpand = computed(() => !props.hideChevron && hasExpandableContent(props.step))`；原行尾 `<svg class="op-chevron">` 渲染条件 `v-if="canExpand"`，在 `.op-main` 末尾。
3. **ToolGroupCard chevron 现状**：`.op-chevron` 无条件渲染，`.op-main` 末尾；组总是可展开。
4. **OperationStack summary 行**：`.timeline-summary .op-main` 末尾 `<svg class="op-chevron">` 无条件渲染（opacity 0，hover/展开显示）；行可点击（toggleTimelineBody）。
5. **OperationStack roundGroup 行**：`.round-group .op-main` 末尾 `<svg class="op-chevron">` 无条件渲染（hover/展开显示）。
6. **displayRows 结构**（OperationStack）：`DisplayRow = step | toolGroup | roundGroup`；roundGroup 的 `rows` 可为 step/toolGroup/roundGroup 嵌套。

---

### Task 1: 偏好层 + 设置入口

**Files:**
- Create: `sunshine-ui/src/composables/useTimelineStyle.ts`
- Create: `sunshine-ui/src/components/chat/TimelineStyleSelector.vue`
- Create: `sunshine-ui/src/composables/useTimelineStyle.spec.ts`
- Modify: `sunshine-ui/src/components/UserSettingsModal.vue`

**Interfaces:**
- Consumes: 无（仿 `useExecutionPreference` 模块级模式）
- Produces:
  - `type TimelineStyle = 'minimal' | 'standard'`
  - `useTimelineStyle(): { timelineStyle: Ref<TimelineStyle>; setTimelineStyle(next: TimelineStyle): void }`
  - `TimelineStyleSelector` props `{ modelValue: TimelineStyle; disabled?: boolean }`，emit `update:modelValue`

- [ ] **Step 1: 写 composable**

```ts
import { ref, type Ref } from 'vue'

export type TimelineStyle = 'minimal' | 'standard'

const TIMELINE_STYLE_STORAGE_KEY = 'sunshine.timeline.style'

function loadTimelineStyle(): TimelineStyle {
  try {
    const raw = localStorage.getItem(TIMELINE_STYLE_STORAGE_KEY)
    if (raw === 'standard' || raw === 'minimal') return raw
  } catch { /* ignore */ }
  return 'minimal'
}

/** 模块级单例：与 useExecutionPreference 同模式，组件直接读取响应式生效 */
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

- [ ] **Step 2: 写单测并验证失败**

`useTimelineStyle.spec.ts`（vitest 环境无 localStorage，用 stub）：

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest'

function createLocalStorageStub() {
  const store = new Map<string, string>()
  return {
    getItem: vi.fn((k: string) => store.get(k) ?? null),
    setItem: vi.fn((k: string, v: string) => { store.set(k, v) }),
    removeItem: vi.fn((k: string) => { store.delete(k) }),
    clear: vi.fn(() => { store.clear() }),
    key: vi.fn((i: number) => [...store.keys()][i] ?? null),
    get length() { return store.size },
  }
}

beforeEach(() => {
  vi.resetModules()
  vi.stubGlobal('localStorage', createLocalStorageStub())
})

describe('useTimelineStyle', () => {
  it('无存储时默认 minimal', async () => {
    const { useTimelineStyle } = await import('./useTimelineStyle')
    expect(useTimelineStyle().timelineStyle.value).toBe('minimal')
  })

  it('非法存储值回落 minimal', async () => {
    localStorage.setItem('sunshine.timeline.style', 'fancy')
    const { useTimelineStyle } = await import('./useTimelineStyle')
    expect(useTimelineStyle().timelineStyle.value).toBe('minimal')
  })

  it('setTimelineStyle 写 ref 与 localStorage', async () => {
    const { useTimelineStyle } = await import('./useTimelineStyle')
    const { timelineStyle, setTimelineStyle } = useTimelineStyle()
    setTimelineStyle('standard')
    expect(timelineStyle.value).toBe('standard')
    expect(localStorage.getItem('sunshine.timeline.style')).toBe('standard')
  })
})
```

Run: `cd sunshine-ui && npx vitest run src/composables/useTimelineStyle.spec.ts`
Expected: FAIL（模块不存在）

- [ ] **Step 3: 跑测试验证通过**

Run: `cd sunshine-ui && npx vitest run src/composables/useTimelineStyle.spec.ts`
Expected: 3 个用例全 PASS

- [ ] **Step 4: 写 TimelineStyleSelector**

仿 `SidebarSectionsLayoutSelector.vue`（NTabs type="segment"）：

```vue
<script setup lang="ts">
import { NTabs, NTabPane } from 'naive-ui'
import type { TimelineStyle } from '../../composables/useTimelineStyle'

defineProps<{
  modelValue: TimelineStyle
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: TimelineStyle]
}>()

function onUpdate(value: string) {
  if (value === 'minimal' || value === 'standard') {
    emit('update:modelValue', value)
  }
}
</script>

<template>
  <NTabs
    :value="modelValue"
    type="segment"
    size="small"
    :animated="false"
    class="timeline-style-tabs"
    :class="{ 'is-disabled': disabled }"
    @update:value="onUpdate"
  >
    <NTabPane name="minimal" tab="极简" :disabled="disabled" />
    <NTabPane name="standard" tab="标准" :disabled="disabled" />
  </NTabs>
</template>

<style scoped>
.timeline-style-tabs {
  width: 100%;
}

.timeline-style-tabs.is-disabled {
  opacity: 0.6;
  pointer-events: none;
}

.timeline-style-tabs :deep(.n-tabs-pane-wrapper) {
  display: none;
}
</style>
```

- [ ] **Step 5: UserSettingsModal 接入**

1. import：`TimelineStyleSelector` + `useTimelineStyle` + `type TimelineStyle`。
2. script 中新增：

```ts
const { timelineStyle, setTimelineStyle } = useTimelineStyle()
const timelineStyleLocal = ref<TimelineStyle>('minimal')
```

3. `watch(() => props.show)` 打开时快照：`timelineStyleLocal.value = timelineStyle.value`。
4. `handleSave()` 中提交：`setTimelineStyle(timelineStyleLocal.value)`。
5. 模板「对话偏好」NForm 末尾（`SidebarSectionsLayoutSelector` 之后）新增：

```vue
<NFormItem label="时间线风格">
  <TimelineStyleSelector
    :model-value="timelineStyleLocal"
    :disabled="saving"
    @update:model-value="timelineStyleLocal = $event"
  />
</NFormItem>
```

- [ ] **Step 6: 跑测试 + 手动验收 + Commit**

Run: `cd sunshine-ui && npx vitest run src/composables/useTimelineStyle.spec.ts`
Expected: PASS

手动验收步骤：
1. 打开设置 → 对话偏好 → 应见「时间线风格：极简/标准」分段选择，默认极简。
2. 切到标准 → 保存 → 重新打开设置，仍为「标准」（localStorage 持久化）。
3. 刷新页面 → 时间线保持标准（未实现图标不影响，仅验证偏好持久）。

```bash
git add sunshine-ui/src/composables/useTimelineStyle.ts sunshine-ui/src/composables/useTimelineStyle.spec.ts sunshine-ui/src/components/chat/TimelineStyleSelector.vue sunshine-ui/src/components/UserSettingsModal.vue
git commit -m "feat(ui): 时间线风格偏好（极简/标准）+ 设置入口"
```

---

### Task 2: 图标判别纯函数 + TimelineStepIcon 组件

**Files:**
- Create: `sunshine-ui/src/api/timelineStepIcon.ts`
- Create: `sunshine-ui/src/api/timelineStepIcon.spec.ts`
- Create: `sunshine-ui/src/components/operation/TimelineStepIcon.vue`

**Interfaces:**
- Consumes: `isSubagentStep` / `isDecisionStep`（processingSteps）、`isThinkStepId`（processingStepsNormalize）、`isWorkerStep` / `isHarnessPlanStep`（harnessHierarchy）、`isRagStepId` / `isToolStepId`（hitlSteps）、`catalogToolIdFromStepId` / `sandboxToolKind`（processingStepsDisplay）、`ProcessingStep`
- Produces:
  - `type TimelineStepKind = 'decision' | 'subagent' | 'worker' | 'plan' | 'rag' | 'intent' | 'skill' | 'tasks' | 'think' | 'tool-view' | 'tool-edit' | 'tool-fetch' | 'tool-exec' | 'tool' | 'generic'`
  - `resolveTimelineStepKind(step: ProcessingStep): TimelineStepKind`
  - `TimelineStepIcon` props `{ step: ProcessingStep; size?: number }`（默认 14）

- [ ] **Step 1: 写判别函数**

`timelineStepIcon.ts`：

```ts
import type { ProcessingStep } from './processingSteps'
import { isDecisionStep, isSubagentStep } from './processingSteps'
import { isThinkStepId } from './processingStepsNormalize'
import { isHarnessPlanStep, isWorkerStep } from './harnessHierarchy'
import { isRagStepId, isToolStepId } from './hitlSteps'
import {
  catalogToolIdFromStepId,
  sandboxToolKind,
} from './processingStepsDisplay'

export type TimelineStepKind =
  | 'decision' | 'subagent' | 'worker' | 'plan'
  | 'rag' | 'intent' | 'skill' | 'tasks' | 'think'
  | 'tool-view' | 'tool-edit' | 'tool-fetch' | 'tool-exec' | 'tool'
  | 'generic'

/** 判别优先级：决策 > 子智能体 > worker > plan > 检索 > intent > skill > tasks > think > 工具细分 > 兜底 */
export function resolveTimelineStepKind(step: ProcessingStep): TimelineStepKind {
  if (isDecisionStep(step)) return 'decision'
  if (isSubagentStep(step)) return 'subagent'
  if (isWorkerStep(step)) return 'worker'
  if (isHarnessPlanStep(step)) return 'plan'
  if (isRagStepId(step.id)) return 'rag'
  if (step.phase === 'intent') return 'intent'
  if (step.phase === 'skill') return 'skill'
  if (step.phase === 'tasks') return 'tasks'
  if (step.phase === 'plan') return 'plan'
  if (isThinkStepId(step.id)) return 'think'
  if (isToolStepId(step.id)) {
    const toolId = catalogToolIdFromStepId(step.id)
    const sandboxKind = sandboxToolKind(toolId)
    if (sandboxKind) return `tool-${sandboxKind}` as TimelineStepKind
    return 'tool'
  }
  return 'generic'
}
```

- [ ] **Step 2: 写单测并验证失败**

`timelineStepIcon.spec.ts`（构造最小 step fixture）：

```ts
import { describe, expect, it } from 'vitest'
import { resolveTimelineStepKind } from './timelineStepIcon'
import type { ProcessingStep } from './processingSteps'

function step(partial: Partial<ProcessingStep>): ProcessingStep {
  return { id: 'x', phase: 'think', lifecycle: 'done', ...partial }
}

describe('resolveTimelineStepKind', () => {
  it('decision / subagent 优先', () => {
    expect(resolveTimelineStepKind(step({ id: 'decision-1', phase: 'decision' }))).toBe('decision')
    expect(resolveTimelineStepKind(step({ id: 'subagent-1', phase: 'subagent' }))).toBe('subagent')
  })

  it('worker / harness plan', () => {
    expect(resolveTimelineStepKind(step({ id: 'worker-1', phase: 'worker' }))).toBe('worker')
    expect(resolveTimelineStepKind(step({ id: 'plan', phase: 'plan' }))).toBe('plan')
    expect(resolveTimelineStepKind(step({ id: 'plan-R2', phase: 'plan' }))).toBe('plan')
  })

  it('rag / intent / skill / tasks / think', () => {
    expect(resolveTimelineStepKind(step({ id: 'rag', phase: 'tool' }))).toBe('rag')
    expect(resolveTimelineStepKind(step({ id: 'rag@1699999999999', phase: 'tool' }))).toBe('rag')
    expect(resolveTimelineStepKind(step({ id: 'i1', phase: 'intent' }))).toBe('intent')
    expect(resolveTimelineStepKind(step({ id: 's1', phase: 'skill' }))).toBe('skill')
    expect(resolveTimelineStepKind(step({ id: 't1', phase: 'tasks' }))).toBe('tasks')
    expect(resolveTimelineStepKind(step({ id: 'think-2', phase: 'think' }))).toBe('think')
  })

  it('工具步按 sandbox 细分', () => {
    expect(resolveTimelineStepKind(step({ id: 'tool-sandbox__read@1', phase: 'tool' }))).toBe('tool-view')
    expect(resolveTimelineStepKind(step({ id: 'tool-sandbox__edit@2', phase: 'tool' }))).toBe('tool-edit')
    expect(resolveTimelineStepKind(step({ id: 'tool-sandbox__webfetch@3', phase: 'tool' }))).toBe('tool-fetch')
    expect(resolveTimelineStepKind(step({ id: 'tool-sandbox__exec@4', phase: 'tool' }))).toBe('tool-exec')
    expect(resolveTimelineStepKind(step({ id: 'tool-doc-search@5', phase: 'tool' }))).toBe('tool')
  })

  it('其余兜底 generic', () => {
    expect(resolveTimelineStepKind(step({ id: 'i9', phase: 'loop' }))).toBe('generic')
    expect(resolveTimelineStepKind(step({ id: 'node-answer', phase: 'node' }))).toBe('generic')
  })
})
```

Run: `cd sunshine-ui && npx vitest run src/api/timelineStepIcon.spec.ts`
Expected: FAIL（模块不存在）

- [ ] **Step 3: 跑测试验证通过**

Run: `cd sunshine-ui && npx vitest run src/api/timelineStepIcon.spec.ts`
Expected: 全部 PASS

- [ ] **Step 4: 写 TimelineStepIcon.vue（kind → SVG）**

`TimelineStepIcon.vue`（16×16 线性 stroke，灰色，无彩色）：

```vue
<script setup lang="ts">
import { computed } from 'vue'
import type { ProcessingStep } from '../../api/processingSteps'
import { resolveTimelineStepKind } from '../../api/timelineStepIcon'

const props = withDefaults(defineProps<{
  step: ProcessingStep
  size?: number
}>(), {
  size: 14,
})

const kind = computed(() => resolveTimelineStepKind(props.step))
</script>

<template>
  <svg
    class="timeline-step-icon"
    :class="`is-${kind}`"
    :width="size"
    :height="size"
    viewBox="0 0 16 16"
    fill="none"
    aria-hidden="true"
  >
    <!-- decision：问号对话框 -->
    <template v-if="kind === 'decision'">
      <path d="M3 3.5h10a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H8l-2.5 2v-2H3a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1z" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round" />
      <path d="M6.2 6.2a1.8 1.8 0 0 1 3.2 1.2c0 1.2-1.4 1.4-1.4 2.3" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" />
      <circle cx="8" cy="11.2" r="0.55" fill="currentColor" stroke="none" />
    </template>

    <!-- subagent：人形 -->
    <template v-else-if="kind === 'subagent'">
      <circle cx="8" cy="5" r="2.25" stroke="currentColor" stroke-width="1.25" />
      <path d="M3.5 13c0-2.5 2-4 4.5-4s4.5 1.5 4.5 4" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" />
    </template>

    <!-- worker：齿轮 -->
    <template v-else-if="kind === 'worker'">
      <circle cx="8" cy="8" r="2.4" stroke="currentColor" stroke-width="1.25" />
      <path d="M8 2.8v1.6M8 11.6v1.6M2.8 8h1.6M11.6 8h1.6M4.3 4.3l1.1 1.1M10.6 10.6l1.1 1.1M11.7 4.3l-1.1 1.1M5.4 10.6l-1.1 1.1" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" />
    </template>

    <!-- plan：层次列表 -->
    <template v-else-if="kind === 'plan'">
      <path d="M3.5 4h9M3.5 8h6M3.5 12h8" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" />
      <circle cx="12.5" cy="8" r="1.25" fill="currentColor" stroke="none" />
    </template>

    <!-- rag：放大镜 -->
    <template v-else-if="kind === 'rag'">
      <circle cx="7" cy="7" r="4.25" stroke="currentColor" stroke-width="1.25" />
      <path d="M10.2 10.2L13 13" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" />
    </template>

    <!-- intent：路由岔路 -->
    <template v-else-if="kind === 'intent'">
      <circle cx="8" cy="4.5" r="2" stroke="currentColor" stroke-width="1.25" />
      <path d="M8 6.5v3M8 9.5l-2.8 2.8M8 9.5l2.8 2.8" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" />
    </template>

    <!-- skill：闪电 -->
    <template v-else-if="kind === 'skill'">
      <path d="M8.8 2L4 9h3.2L7 14l5-7H8.6l.2-5z" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round" />
    </template>

    <!-- tasks：剪贴板 -->
    <template v-else-if="kind === 'tasks'">
      <path d="M4 2.5h8l1.5 2v9a1.5 1.5 0 0 1-1.5 1.5H4A1.5 1.5 0 0 1 2.5 13.5V4.5L4 2.5Z" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round" />
      <path d="M5.5 8h5M5.5 10.5h3" stroke="currentColor" stroke-width="1.1" stroke-linecap="round" />
    </template>

    <!-- think：灯泡 -->
    <template v-else-if="kind === 'think'">
      <path d="M8 2.5a4 4 0 0 0-2.6 7c.6.5.9 1.1.9 1.8h3.4c0-.7.3-1.3.9-1.8a4 4 0 0 0-2.6-7z" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round" />
      <path d="M6.6 12.3h2.8M7.1 14h1.8" stroke="currentColor" stroke-width="1.1" stroke-linecap="round" />
    </template>

    <!-- tool-view：文件 -->
    <template v-else-if="kind === 'tool-view'">
      <path d="M3.5 2.5h6L12.5 5.5v8h-9z" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round" />
      <path d="M9.5 2.5V5.5h3" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round" />
    </template>

    <!-- tool-edit：铅笔 -->
    <template v-else-if="kind === 'tool-edit'">
      <path d="M11.5 2.5l2 2L5 13H3v-2L11.5 2.5z" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round" />
      <path d="M10.5 3.5l2 2" stroke="currentColor" stroke-width="1.1" />
    </template>

    <!-- tool-fetch：网页地球 -->
    <template v-else-if="kind === 'tool-fetch'">
      <circle cx="8" cy="8" r="5" stroke="currentColor" stroke-width="1.25" />
      <path d="M3 8h10M8 3c2 1.5 2.5 3.5 2.5 5S10 11.5 8 13c-2-1.5-2.5-3.5-2.5-5S6 4.5 8 3z" stroke="currentColor" stroke-width="1.1" />
    </template>

    <!-- tool-exec：终端 -->
    <template v-else-if="kind === 'tool-exec'">
      <path d="M2.8 4.5l4 3.5-4 3.5" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round" />
      <path d="M8.5 11.5h4.7" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" />
    </template>

    <!-- tool：开口扳手 -->
    <template v-else-if="kind === 'tool'">
      <circle cx="5" cy="11" r="2.6" stroke="currentColor" stroke-width="1.25" />
      <path d="M7.2 8.8l5.3-5.3a1.9 1.9 0 0 1 2.7 2.7l-5.3 5.3" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round" />
      <path d="M10 6l2 2" stroke="currentColor" stroke-width="1.1" />
    </template>

    <!-- generic：圆点 -->
    <template v-else>
      <circle cx="8" cy="8" r="2.5" stroke="currentColor" stroke-width="1.25" />
    </template>
  </svg>
</template>

<style scoped>
.timeline-step-icon {
  flex-shrink: 0;
  display: block;
  color: var(--sun-text-muted);
}
</style>
```

- [ ] **Step 5: 跑测试 + Commit**

Run: `cd sunshine-ui && npx vitest run src/api/timelineStepIcon.spec.ts`
Expected: PASS

```bash
git add sunshine-ui/src/api/timelineStepIcon.ts sunshine-ui/src/api/timelineStepIcon.spec.ts sunshine-ui/src/components/operation/TimelineStepIcon.vue
git commit -m "feat(ui): 时间线步骤类型图标判别 + TimelineStepIcon 组件"
```

---

### Task 3: OperationCard 行首图标槽位 + chevron 迁移

**Files:**
- Modify: `sunshine-ui/src/components/operation/OperationCard.vue`

**Interfaces:**
- Consumes: `useTimelineStyle`（`timelineStyle` ref）、`TimelineStepIcon`、已有 `canExpand` computed
- Produces: `.op-line.is-expandable` class（仅 `canExpand` 时挂，驱动 hover 换箭头）

- [ ] **Step 1: script 接入偏好**

```ts
import TimelineStepIcon from './TimelineStepIcon.vue'
import { useTimelineStyle } from '../../composables/useTimelineStyle'
// ...
const { timelineStyle } = useTimelineStyle()
```

- [ ] **Step 2: 模板改造**

1. `.op-line` class 增加 `'is-expandable': canExpand`（复用已有 computed）。
2. `.op-main` **首部**（`<span class="op-label">` 之前）插入槽位：

```vue
<span v-if="timelineStyle === 'standard'" class="op-step-icon">
  <TimelineStepIcon class="op-type-icon" :step="step" />
  <svg
    v-if="isExpandable"
    class="op-chevron"
    width="12"
    height="12"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="2.5"
    stroke-linecap="round"
    aria-hidden="true"
  >
    <polyline points="9 18 15 12 9 6" />
  </svg>
</span>
```

3. **原行尾 chevron**（`.op-main` 末尾的 `<svg class="op-chevron">`）渲染条件改为 `v-if="canExpand && timelineStyle !== 'standard'"`。

- [ ] **Step 3: 样式**

scoped CSS 新增（替换/补充现有 `.op-main .op-chevron` 规则）：

```css
/* 行首图标槽位：固定 16px，type-icon 与 chevron 绝对定位重叠 */
.op-step-icon {
  position: relative;
  flex-shrink: 0;
  align-self: center;
  width: 16px;
  height: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.op-step-icon .op-type-icon,
.op-step-icon .op-chevron {
  position: absolute;
  transition: opacity 0.12s ease, transform 0.15s ease;
}

.op-step-icon .op-type-icon {
  opacity: 1;
}

.op-step-icon .op-chevron {
  color: var(--sun-text-secondary);
  opacity: 0;
  margin: 0;
}

/* 仅可展开行 hover：图标淡出、> 淡入 */
.op-line.is-expandable:hover .op-step-icon .op-type-icon {
  opacity: 0;
}

.op-line.is-expandable:hover .op-step-icon .op-chevron {
  opacity: 0.85;
}

/* 展开态 ^：图标清除、箭头旋转显示（避免非 hover 时图标与 ^ 叠加） */
.op-line.is-expanded .op-step-icon .op-type-icon {
  opacity: 0;
}

.op-line.is-expanded .op-step-icon .op-chevron {
  transform: rotate(90deg);
  opacity: 0.85;
}
```

保留原 `.op-main .op-chevron`（极简模式行尾箭头）不动。

- [ ] **Step 4: 手动验收步骤**

1. 设置 → 时间线风格 → 标准 → 保存。
2. Chat 发消息（fast/pro/workflow 均可），时间线各行（think/工具/检索）行首出现对应灰色图标，且左对齐。
3. hover 有详情可展开的行（如 think 有摘要、工具步有 detail）→ 图标淡出、`>` 淡入；点击展开 → `^`；再点收起 → 图标恢复。
4. hover 不可展开的行（如 sandbox read、纯 tool 无 detail）→ 图标保持，无 chevron。
5. 切回极简 → 时间线完全恢复现状（无行首图标、chevron 在行尾）。
6. 展开 loop/worker 抽屉内嵌套时间线，行首图标同样生效。

- [ ] **Step 5: Commit**

```bash
git add sunshine-ui/src/components/operation/OperationCard.vue
git commit -m "feat(ui): OperationCard 行首图标槽位 + chevron 迁移（标准模式）"
```

---

### Task 4: ToolGroupCard 行首图标槽位

**Files:**
- Modify: `sunshine-ui/src/components/operation/ToolGroupCard.vue`

**Interfaces:**
- Consumes: `useTimelineStyle`、`TimelineStepIcon`、`props.steps[0]`（组内首步类型）

- [ ] **Step 1: script 接入偏好**

```ts
import TimelineStepIcon from './TimelineStepIcon.vue'
import { useTimelineStyle } from '../../composables/useTimelineStyle'
// ...
const { timelineStyle } = useTimelineStyle()
```

- [ ] **Step 2: 模板改造**

1. `.tool-group-row` 的 `.op-main` **首部**插入槽位：

```vue
<span v-if="timelineStyle === 'standard'" class="op-step-icon">
  <TimelineStepIcon class="op-type-icon" :step="steps[0]" />
  <svg
    class="op-chevron"
    width="12"
    height="12"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="2.5"
    stroke-linecap="round"
    aria-hidden="true"
  >
    <polyline points="9 18 15 12 9 6" />
  </svg>
</span>
```

2. **原行尾 chevron** 渲染条件改为 `v-if="timelineStyle !== 'standard'"`。

- [ ] **Step 3: 样式**

同 Task 3 的 `.op-step-icon` / `.op-type-icon` / `.op-chevron` 规则（本组件是 `.tool-group-row`，hover 选择器为 `.tool-group-row:hover`；展开态沿用 `.tool-group.is-expanded`）。

```css
.op-step-icon {
  position: relative;
  flex-shrink: 0;
  align-self: center;
  width: 16px;
  height: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.op-step-icon .op-type-icon,
.op-step-icon .op-chevron {
  position: absolute;
  transition: opacity 0.12s ease, transform 0.15s ease;
}

.op-step-icon .op-type-icon {
  opacity: 1;
}

.op-step-icon .op-chevron {
  color: var(--sun-text-secondary);
  opacity: 0;
  margin: 0;
}

.tool-group-row:hover .op-step-icon .op-type-icon {
  opacity: 0;
}

.tool-group-row:hover .op-step-icon .op-chevron {
  opacity: 0.85;
}

.tool-group.is-expanded .op-step-icon .op-type-icon {
  opacity: 0;
}

.tool-group.is-expanded .op-step-icon .op-chevron {
  transform: rotate(90deg);
  opacity: 0.85;
}
```

（删除/保留原 `.op-chevron` 规则均可——极简模式仍由行尾箭头走原规则。）

- [ ] **Step 4: 手动验收步骤**

1. 标准模式：连续多个工具步折叠为「调用N个工具」行，行首显示组内首工具图标；hover → `>`，展开 → `^`。
2. 检索组（`检索N次知识库`）行首显示放大镜。
3. sandbox 组（`查看N次文件` / `执行N次命令`）行首显示文件/终端图标。
4. 极简模式：恢复现状（行尾箭头）。

- [ ] **Step 5: Commit**

```bash
git add sunshine-ui/src/components/operation/ToolGroupCard.vue
git commit -m "feat(ui): ToolGroupCard 行首图标槽位 + chevron 迁移"
```

---

### Task 5: OperationStack summary / roundGroup 行槽位 + 根 class

**Files:**
- Modify: `sunshine-ui/src/components/operation/OperationStack.vue`

**Interfaces:**
- Consumes: `useTimelineStyle`、`TimelineStepIcon`、`resolveTimelineStepKind`
- Produces: 内部 helper `firstTimelineStepKind(steps)` / `roundGroupLeadStep(row)`

- [ ] **Step 1: script 接入**

```ts
import TimelineStepIcon from './TimelineStepIcon.vue'
import { useTimelineStyle } from '../../composables/useTimelineStyle'
import { resolveTimelineStepKind, type TimelineStepKind } from '../../api/timelineStepIcon'
// ...
const { timelineStyle } = useTimelineStyle()
```

新增 helper（置于 `roundDisplayRows` computed 附近）：

```ts
function firstTimelineStepKind(steps: ProcessingStep[]): TimelineStepKind | undefined {
  return steps.length ? resolveTimelineStepKind(steps[0]) : undefined
}

function roundGroupLeadStep(row: DisplayRow): ProcessingStep | undefined {
  if (row.kind === 'step') return row.step
  if (row.kind === 'toolGroup') return row.steps[0]
  for (const inner of row.rows) {
    const lead = roundGroupLeadStep(inner)
    if (lead) return lead
  }
  return undefined
}
```

- [ ] **Step 2: 根 class**

模板根：

```vue
<div
  class="operation-lines"
  :class="{ 'is-timeline-standard': timelineStyle === 'standard' }"
>
```

- [ ] **Step 3: summary 行槽位**

`.timeline-summary` 的 `.op-main` 首部插入：

```vue
<span v-if="timelineStyle === 'standard'" class="op-step-icon">
  <TimelineStepIcon
    v-if="firstTimelineStepKind(effectiveSteps)"
    class="op-type-icon"
    :step="effectiveSteps[0]"
  />
  <svg
    class="op-chevron"
    width="12"
    height="12"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="2.5"
    stroke-linecap="round"
    aria-hidden="true"
  >
    <polyline points="9 18 15 12 9 6" />
  </svg>
</span>
```

原 `.timeline-summary .op-chevron`（行尾）渲染条件改为 `v-if="timelineStyle !== 'standard'"`。

- [ ] **Step 4: roundGroup 行槽位**

`.round-group-row` 的 `.op-main` 首部插入：

```vue
<span v-if="timelineStyle === 'standard'" class="op-step-icon">
  <TimelineStepIcon
    v-if="roundGroupLeadStep(row)"
    class="op-type-icon"
    :step="roundGroupLeadStep(row)"
  />
  <svg
    class="op-chevron"
    width="12"
    height="12"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="2.5"
    stroke-linecap="round"
    aria-hidden="true"
  >
    <polyline points="9 18 15 12 9 6" />
  </svg>
</span>
```

原 `.round-group .op-main .op-chevron`（行尾）渲染条件改为 `v-if="timelineStyle !== 'standard'"`。

- [ ] **Step 5: 样式**

新增：

```css
.is-timeline-standard .op-step-icon {
  position: relative;
  flex-shrink: 0;
  align-self: center;
  width: 16px;
  height: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.is-timeline-standard .op-step-icon .op-type-icon,
.is-timeline-standard .op-step-icon .op-chevron {
  position: absolute;
  transition: opacity 0.12s ease, transform 0.15s ease;
}

.is-timeline-standard .op-step-icon .op-type-icon {
  opacity: 1;
}

.is-timeline-standard .op-step-icon .op-chevron {
  color: var(--sun-text-secondary);
  opacity: 0;
  margin: 0;
}

.timeline-summary.is-clickable:hover .op-step-icon .op-type-icon,
.round-group:not(.is-expanded):hover .op-step-icon .op-type-icon {
  opacity: 0;
}

.timeline-summary.is-clickable:hover .op-step-icon .op-chevron,
.round-group:not(.is-expanded):hover .op-step-icon .op-chevron {
  opacity: 0.85;
}

.timeline-summary.is-expanded .op-step-icon .op-type-icon,
.round-group.is-expanded .op-step-icon .op-type-icon {
  opacity: 0;
}

.timeline-summary.is-expanded .op-step-icon .op-chevron,
.round-group.is-expanded .op-step-icon .op-chevron {
  transform: rotate(90deg);
  opacity: 0.85;
}
```

保留原 `.timeline-summary .op-chevron` / `.round-group .op-main .op-chevron`（极简模式行尾箭头）不动。

- [ ] **Step 6: 手动验收步骤**

1. 标准模式：
   - 折叠态：消息总览行（`正在处理 12s`）行首出现首步类型图标；hover → `>`，展开 → `^`。
   - 正文间多轮折叠：roundGroup 行（`调用2次工具`）行首出现组内首步图标；hover/展开行为同上。
   - 折叠预览步（正在处理折叠时最后一步）：仅类型图标、无 chevron（OperationCard `hideChevron` 态）。
2. 极简模式：全部恢复现状。
3. 老消息（无 steps 或纯 content）：无图标也无 chevron 变化。

- [ ] **Step 7: Commit**

```bash
git add sunshine-ui/src/components/operation/OperationStack.vue
git commit -m "feat(ui): OperationStack summary/roundGroup 行首图标槽位 + chevron 迁移"
```

---

### Task 6: 全量回归验证 + 收尾

- [ ] **Step 1: 跑前端全部相关测试**

Run: `cd sunshine-ui && npx vitest run src/api/timelineStepIcon.spec.ts src/composables/useTimelineStyle.spec.ts src/api/processingStepsDisplay.timelineSummary.test.ts src/api/harnessHierarchy.spec.ts`
Expected: 全 PASS（后两个为存量测试，确认未受影响）

- [ ] **Step 2: Lint 检查**

用 ReadLints 检查本次改动文件：`sunshine-ui/src/composables/useTimelineStyle.ts`、`sunshine-ui/src/api/timelineStepIcon.ts`、`sunshine-ui/src/components/operation/TimelineStepIcon.vue`、`TimelineStyleSelector.vue`、`OperationCard.vue`、`ToolGroupCard.vue`、`OperationStack.vue`、`UserSettingsModal.vue`
Expected: 无新增错误（Vue 模板 + TS）

- [ ] **Step 3: 全量手动验收**

按 Task 3–5 的验收步骤走一遍完整流程：极简 ↔ 标准切换、各类步骤图标、hover/展开、折叠/展开态、嵌套时间线。

- [ ] **Step 4: 更新 spec 状态**

spec `2026-08-14-timeline-style-prefix-icon-design.md` 头部状态 `📋 评审中` → `✅ 已实现`；同步 CLAUDE.md 顶部进度行。

```bash
git add docs/superpowers/specs/2026-08-14-timeline-style-prefix-icon-design.md CLAUDE.md
git commit -m "docs: 时间线前缀图标 spec 标为已实现"
```

---

## 风险与对策

| 风险 | 对策 |
|------|------|
| 标准模式下各行图标垂直/水平不对齐 | 槽位统一 16×16 + `align-items:center`；验收步骤强制检查左对齐 |
| hover 换箭头误伤 sandbox 跳转行（可点击但不可展开） | 用 `is-expandable`（= `canExpand`）而非 `is-clickable` 驱动 |
| `effectiveSteps[0]` 为空导致 summary 槽位渲染空图标 | `v-if="firstTimelineStepKind(effectiveSteps)"` 保护 |
| 极简模式回归 | 槽位全部 `v-if="timelineStyle === 'standard'"`，极简模式 DOM 不存在 |
| 工作区既有未提交改动被误提交 | 每个 commit 只 add 本 plan 列出的文件 |
