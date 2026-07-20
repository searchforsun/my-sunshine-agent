# 时间线总览行（总耗时 + 整段折叠）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Chat 时间线顶部增加墙钟总耗时总览行，进行中默认展开、终态默认折叠；折叠时只保留终稿正文，不显示实现步骤与中间穿插段。

**Architecture:** 纯前端。在 `processingStepsDisplay` / `contentInterleave` 增加纯函数（时钟、墙钟、终稿、文案）；`OperationStack` 顶部渲染总览行并按 expand 状态切换「全时间线」与「仅终稿」；仅 `ChatView` 传入 `messageStatus` / `messageContent` 时启用总览（嵌套 Stack / PlanNodeDrawer 不传 → 无总览）。不改 SSE / Nacos。

**Tech Stack:** Vue3 + TypeScript（sunshine-ui）· vitest · Playwright e2e

**Spec:** [2026-07-20-timeline-summary-duration-design.md](../specs/2026-07-20-timeline-summary-duration-design.md)

---

## File map

| 文件 | 职责 |
|------|------|
| `sunshine-ui/src/api/processingStepsDisplay.ts` | `formatElapsedClock` · `resolveTimelineElapsedMs` · `resolveTimelineSummaryPrefix` · `formatTimelineSummaryText` |
| `sunshine-ui/src/api/processingStepsDisplay.timelineSummary.test.ts` | 上述纯函数单测 |
| `sunshine-ui/src/api/contentInterleave.ts` | `resolveCollapsedAnswerText` |
| `sunshine-ui/src/api/contentInterleave.collapsedAnswer.test.ts` | 终稿解析单测 |
| `sunshine-ui/src/api/processingSteps.ts` | re-export 新 display 函数（若现有 barrel 模式需要） |
| `sunshine-ui/src/components/operation/OperationStack.vue` | 总览行 · expand state · 折叠分支 · live tick |
| `sunshine-ui/src/views/ChatView.vue` | 传 `message-status` / `message-content` |
| `sunshine-ui/e2e/processing-timeline.spec.ts` | A1/A2/A5/A7 类断言 |

---

### Task 1: 时钟与墙钟纯函数

**Files:**
- Create: `sunshine-ui/src/api/processingStepsDisplay.timelineSummary.test.ts`
- Modify: `sunshine-ui/src/api/processingStepsDisplay.ts`
- Modify: `sunshine-ui/src/api/processingSteps.ts`（re-export）

- [ ] **Step 1: 写失败单测**

```ts
import { describe, expect, it } from 'vitest'
import type { ProcessingStep } from './processingSteps'
import {
  formatElapsedClock,
  resolveTimelineElapsedMs,
  resolveTimelineSummaryPrefix,
  formatTimelineSummaryText,
} from './processingStepsDisplay'

describe('formatElapsedClock', () => {
  it('formats seconds and minutes per spec', () => {
    expect(formatElapsedClock(Number.NaN)).toBe('')
    expect(formatElapsedClock(-1)).toBe('')
    expect(formatElapsedClock(0)).toBe('0s')
    expect(formatElapsedClock(999)).toBe('0s')
    expect(formatElapsedClock(42_000)).toBe('42s')
    expect(formatElapsedClock(80_000)).toBe('1m20s')
    expect(formatElapsedClock(120_000)).toBe('2m0s')
  })
})

describe('resolveTimelineElapsedMs', () => {
  const step = (partial: Partial<ProcessingStep>): ProcessingStep => ({
    id: 'intent',
    phase: 'intent',
    lifecycle: 'done',
    ...partial,
  })

  it('uses min startedAt/ts to now when live', () => {
    const ms = resolveTimelineElapsedMs({
      steps: [step({ startedAt: 1_000, ts: 9_000 }), step({ id: 'think', startedAt: 2_000 })],
      live: true,
      nowMs: 81_000,
    })
    expect(ms).toBe(80_000)
  })

  it('uses fallbackStart when steps lack timestamps', () => {
    const ms = resolveTimelineElapsedMs({
      steps: [step({})],
      live: true,
      nowMs: 5_000,
      fallbackStartMs: 1_000,
    })
    expect(ms).toBe(4_000)
  })

  it('uses fallbackEnd or max endedAt when not live', () => {
    expect(resolveTimelineElapsedMs({
      steps: [step({ startedAt: 1_000, endedAt: 3_000 }), step({ id: 't', startedAt: 1_500, endedAt: 4_000 })],
      live: false,
    })).toBe(3_000)
    expect(resolveTimelineElapsedMs({
      steps: [step({ startedAt: 1_000 })],
      live: false,
      fallbackEndMs: 5_000,
    })).toBe(4_000)
  })

  it('returns undefined when no start', () => {
    expect(resolveTimelineElapsedMs({ steps: [step({})], live: false })).toBeUndefined()
  })
})

describe('resolveTimelineSummaryPrefix', () => {
  it('maps status and live', () => {
    expect(resolveTimelineSummaryPrefix({ live: true })).toBe('正在进行')
    expect(resolveTimelineSummaryPrefix({ live: false, messageStatus: 'streaming' })).toBe('正在进行')
    expect(resolveTimelineSummaryPrefix({ live: false, messageStatus: 'completed' })).toBe('已完成')
    expect(resolveTimelineSummaryPrefix({ live: false, messageStatus: 'interrupted' })).toBe('已中断')
    expect(resolveTimelineSummaryPrefix({ live: false, messageStatus: 'failed' })).toBe('失败')
    expect(resolveTimelineSummaryPrefix({ live: false })).toBe('已完成')
  })
})

describe('formatTimelineSummaryText', () => {
  it('joins prefix and clock', () => {
    expect(formatTimelineSummaryText('已完成', '1m20s')).toBe('已完成 1m20s')
    expect(formatTimelineSummaryText('失败', '')).toBe('失败')
  })
})
```

- [ ] **Step 2: 跑测确认失败**

Run:

```bash
cd /usr/local/gitproj/my-sunshine-agent/sunshine-ui && npx vitest run src/api/processingStepsDisplay.timelineSummary.test.ts
```

Expected: FAIL（函数未导出 / 未定义）

- [ ] **Step 3: 实现纯函数**

在 `processingStepsDisplay.ts` 末尾追加（勿改 `formatDuration`）：

```ts
export function formatElapsedClock(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return ''
  const totalSec = Math.floor(ms / 1000)
  if (totalSec < 60) return `${totalSec}s`
  const m = Math.floor(totalSec / 60)
  const s = totalSec % 60
  return `${m}m${s}s`
}

export function resolveTimelineElapsedMs(opts: {
  steps: ProcessingStep[]
  live: boolean
  nowMs?: number
  fallbackStartMs?: number
  fallbackEndMs?: number
}): number | undefined {
  let start: number | undefined
  let maxEnded: number | undefined
  for (const step of opts.steps) {
    const t = step.startedAt ?? step.ts
    if (typeof t === 'number' && Number.isFinite(t)) {
      start = start == null ? t : Math.min(start, t)
    }
    if (typeof step.endedAt === 'number' && Number.isFinite(step.endedAt)) {
      maxEnded = maxEnded == null ? step.endedAt : Math.max(maxEnded, step.endedAt)
    }
  }
  if (start == null && opts.fallbackStartMs != null) start = opts.fallbackStartMs
  if (start == null) return undefined
  let end: number | undefined
  if (opts.live) {
    end = opts.nowMs ?? Date.now()
  } else if (opts.fallbackEndMs != null) {
    end = opts.fallbackEndMs
  } else {
    end = maxEnded
  }
  if (end == null || end < start) return undefined
  return end - start
}

export type TimelineMessageStatus = 'streaming' | 'interrupted' | 'failed' | 'completed'

export function resolveTimelineSummaryPrefix(opts: {
  live: boolean
  messageStatus?: TimelineMessageStatus
}): string {
  if (opts.live || opts.messageStatus === 'streaming') return '正在进行'
  if (opts.messageStatus === 'interrupted') return '已中断'
  if (opts.messageStatus === 'failed') return '失败'
  return '已完成'
}

export function formatTimelineSummaryText(prefix: string, clock: string): string {
  const c = clock.trim()
  return c ? `${prefix} ${c}` : prefix
}
```

若文件顶部未 import `ProcessingStep` 类型，用相对 `./processingSteps` 的 type-only import（注意避免循环：`processingSteps.ts` 已 import display——若循环，把类型参数写成 inline duck 或从 `processingStepsNormalize` 不依赖的轻量类型。现有 `resolveStepDurationMs` 已用 `ProcessingStep`，同文件已有该类型引用，照抄即可）。

在 `processingSteps.ts` 的 display re-export 列表中追加：

```ts
  formatElapsedClock,
  resolveTimelineElapsedMs,
  resolveTimelineSummaryPrefix,
  formatTimelineSummaryText,
```

并 `export type { TimelineMessageStatus }`（若 barrel 已有 type export 区则并入）。

- [ ] **Step 4: 跑测确认通过**

Run:

```bash
cd /usr/local/gitproj/my-sunshine-agent/sunshine-ui && npx vitest run src/api/processingStepsDisplay.timelineSummary.test.ts
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add sunshine-ui/src/api/processingStepsDisplay.ts \
  sunshine-ui/src/api/processingStepsDisplay.timelineSummary.test.ts \
  sunshine-ui/src/api/processingSteps.ts
git commit -m "$(cat <<'EOF'
feat(ui): add timeline wall-clock duration helpers

Introduce formatElapsedClock and summary text helpers for the
OperationStack timeline header without changing per-step formatDuration.
EOF
)"
```

---

### Task 2: 折叠终稿解析

**Files:**
- Create: `sunshine-ui/src/api/contentInterleave.collapsedAnswer.test.ts`
- Modify: `sunshine-ui/src/api/contentInterleave.ts`

- [ ] **Step 1: 写失败单测**

```ts
import { describe, expect, it } from 'vitest'
import type { ChatMessage } from './chat'
import { resolveCollapsedAnswerText } from './contentInterleave'

function msg(partial: Partial<ChatMessage>): ChatMessage {
  return { role: 'assistant', content: '', ...partial }
}

describe('resolveCollapsedAnswerText', () => {
  it('prefers message.content when not plan leak', () => {
    expect(resolveCollapsedAnswerText(msg({
      content: '最终回答',
      contentBlocks: [
        { segmentId: 'content-1', afterStepId: 'think', text: '中间段' },
        { segmentId: 'content-2', afterStepId: 'think-2', text: '尾段' },
      ],
    }))).toBe('最终回答')
  })

  it('falls back to joined contentBlocks', () => {
    expect(resolveCollapsedAnswerText(msg({
      content: '',
      contentBlocks: [
        { segmentId: 'content-1', afterStepId: 'think', text: 'A' },
        { segmentId: 'content-2', afterStepId: 'think-2', text: 'B' },
      ],
    }))).toBe('AB')
  })

  it('falls back to last block when join empty', () => {
    expect(resolveCollapsedAnswerText(msg({
      content: '   ',
      contentBlocks: [{ segmentId: 'content-1', afterStepId: 'think', text: 'only' }],
    }))).toBe('only')
  })

  it('uses plan answer SSOT for plan workflows', () => {
    expect(resolveCollapsedAnswerText(msg({
      content: '误入',
      steps: [
        { id: 'plan', phase: 'plan', lifecycle: 'done' },
        { id: 'node-answer', phase: 'node', lifecycle: 'done', result: '计划终稿' },
      ],
      contentBlocks: [{ segmentId: 'tail:node-answer', afterStepId: 'node-answer', text: '块' }],
    }))).toBe('计划终稿')
  })
})
```

- [ ] **Step 2: 跑测确认失败**

Run:

```bash
cd /usr/local/gitproj/my-sunshine-agent/sunshine-ui && npx vitest run src/api/contentInterleave.collapsedAnswer.test.ts
```

Expected: FAIL

- [ ] **Step 3: 实现 `resolveCollapsedAnswerText`**

在 `contentInterleave.ts`（靠近 `resolvePlanAnswerText` / `joinedContentBlocks`）追加：

```ts
/** 折叠时间线时展示的唯一终稿（不按 afterStepId 穿插） */
export function resolveCollapsedAnswerText(
  msg: Pick<ChatMessage, 'role' | 'content' | 'steps' | 'contentBlocks'>,
): string {
  if (msg.steps?.some(s => s.phase === 'plan')) {
    return resolvePlanAnswerText(msg).trim()
  }
  const content = msg.content?.trim() ?? ''
  if (content && !isPlanDrawerLeakContent(msg)) return content
  const joined = joinedContentBlocks(msg.contentBlocks).trim()
  if (joined) return joined
  const blocks = msg.contentBlocks
  if (blocks?.length) {
    const last = blocks[blocks.length - 1]?.text?.trim() ?? ''
    if (last) return last
  }
  return content
}
```

- [ ] **Step 4: 跑测确认通过**

Run:

```bash
cd /usr/local/gitproj/my-sunshine-agent/sunshine-ui && npx vitest run src/api/contentInterleave.collapsedAnswer.test.ts
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add sunshine-ui/src/api/contentInterleave.ts \
  sunshine-ui/src/api/contentInterleave.collapsedAnswer.test.ts
git commit -m "$(cat <<'EOF'
feat(ui): resolve collapsed timeline final answer text

Prefer message.content, then joined contentBlocks, with plan answer
SSOT for plan-workflow messages.
EOF
)"
```

---

### Task 3: OperationStack 总览行 + 默认展开规则

**Files:**
- Modify: `sunshine-ui/src/components/operation/OperationStack.vue`

- [ ] **Step 1: 扩展 props 与 expand / 墙钟状态**

在 `OperationStack.vue` script 中：

1. Import：

```ts
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import {
  formatElapsedClock,
  formatTimelineSummaryText,
  resolveTimelineElapsedMs,
  resolveTimelineSummaryPrefix,
  type TimelineMessageStatus,
} from '../../api/processingSteps'
import {
  isContentFullyInterleaved,
  resolveCollapsedAnswerText,
  // 已有 contentInterleave imports 合并
} from '../../api/contentInterleave'
```

（`TimelineMessageStatus` 若只从 display 导出，从 `processingSteps` barrel 取；否则直接从 `processingStepsDisplay`。）

2. Props 追加：

```ts
  messageStatus?: TimelineMessageStatus
  /** assistant msg.content，折叠终稿优先 */
  messageContent?: string
```

3. 仅当 `messageStatus != null` **或** 显式需要总览时启用——**锁定**：`ChatView` 始终传入 `messageStatus`（缺省用 `'completed'`）；嵌套 / 抽屉不传。用：

```ts
const summaryEnabled = computed(() => props.messageStatus !== undefined)
```

注意：`withDefaults` 不要给 `messageStatus` 默认值，否则嵌套也会启用。

4. Expand 状态：

```ts
const timelineUserToggled = ref(false)
const timelineExpandedOverride = ref(false)

function isTimelineBodyExpanded(): boolean {
  if (!summaryEnabled.value) return true
  if (timelineUserToggled.value) return timelineExpandedOverride.value
  if (props.live || props.messageStatus === 'streaming') return true
  return false
}

function toggleTimelineBody(): void {
  if (!summaryEnabled.value) return
  const next = !isTimelineBodyExpanded()
  timelineUserToggled.value = true
  timelineExpandedOverride.value = next
}
```

5. 墙钟 fallback（组件挂载期间）：

```ts
const fallbackStartMs = ref<number | undefined>(undefined)
const fallbackEndMs = ref<number | undefined>(undefined)
const nowMs = ref(Date.now())
let tickTimer: ReturnType<typeof setInterval> | undefined

watch(
  () => [props.live, props.messageStatus, summaryEnabled.value] as const,
  ([live, status, enabled]) => {
    if (!enabled) return
    if ((live || status === 'streaming') && fallbackStartMs.value == null) {
      fallbackStartMs.value = Date.now()
    }
    if (!live && status && status !== 'streaming' && fallbackEndMs.value == null) {
      fallbackEndMs.value = Date.now()
    }
  },
  { immediate: true },
)

watch(
  () => summaryEnabled.value && isTimelineBodyExpanded() === false
    ? false
    : (props.live || props.messageStatus === 'streaming'),
  (needTick) => {
    if (tickTimer) {
      clearInterval(tickTimer)
      tickTimer = undefined
    }
    if (!summaryEnabled.value) return
    const running = props.live || props.messageStatus === 'streaming'
    if (!running) return
    nowMs.value = Date.now()
    tickTimer = setInterval(() => { nowMs.value = Date.now() }, 200)
  },
  { immediate: true },
)

onUnmounted(() => {
  if (tickTimer) clearInterval(tickTimer)
})
```

（tick watch 可简化为：只要 `summaryEnabled && (live || streaming)` 就 tick。）

6. 文案 computed：

```ts
const summaryText = computed(() => {
  if (!summaryEnabled.value) return ''
  const elapsed = resolveTimelineElapsedMs({
    steps: effectiveSteps.value,
    live: !!(props.live || props.messageStatus === 'streaming'),
    nowMs: nowMs.value,
    fallbackStartMs: fallbackStartMs.value,
    fallbackEndMs: fallbackEndMs.value,
  })
  const clock = elapsed != null ? formatElapsedClock(elapsed) : ''
  const prefix = resolveTimelineSummaryPrefix({
    live: !!props.live,
    messageStatus: props.messageStatus,
  })
  return formatTimelineSummaryText(prefix, clock)
})
```

- [ ] **Step 2: 模板加总览行，主体包在 `v-if="isTimelineBodyExpanded()"`**

将现有 `<div class="operation-lines">` 内结构改为：

```vue
<div class="operation-lines">
  <div
    v-if="summaryEnabled"
    class="op-line timeline-summary"
    :class="{
      'is-expanded': isTimelineBodyExpanded(),
      'is-clickable': true,
      'is-running': live || messageStatus === 'streaming',
    }"
  >
    <button type="button" class="op-line-row" @click="toggleTimelineBody">
      <span class="op-gutter">
        <svg class="op-chevron" width="9" height="9" viewBox="0 0 24 24" fill="none"
          stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
          <polyline points="9 18 15 12 9 6" />
        </svg>
      </span>
      <span class="op-main">
        <span
          class="op-label"
          :class="{ 'op-shimmer': live || messageStatus === 'streaming' }"
        >{{ summaryText }}</span>
      </span>
    </button>
  </div>

  <template v-if="isTimelineBodyExpanded()">
    <!-- 原有 displaySteps / orphanContent 模板原样保留 -->
  </template>

  <!-- Task 4 再加折叠终稿；本 Task 先只做展开/折叠实现线 -->
</div>
```

嵌套 `OperationStack`（loop body）**不要**传 `messageStatus`。

复制 `OperationCard` 中 `.op-line` / `.op-line-row` / `.op-chevron` / `.op-label` / `.op-shimmer` 所需最小样式到本文件 scoped（或抽公共 class；本计划允许在 Stack 内复制最小子集，避免大重构）：

```css
.timeline-summary {
  --op-gutter: 12px;
  font-size: var(--sun-font-md);
  line-height: 1.5;
  color: var(--sun-text-muted);
  margin-bottom: 2px;
}
.timeline-summary .op-line-row {
  display: grid;
  grid-template-columns: var(--op-gutter) minmax(0, 1fr);
  column-gap: 4px;
  align-items: start;
  width: 100%;
  padding: 1px 0;
  border: none;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.timeline-summary .op-chevron {
  display: inline-block;
  transition: transform 0.15s ease;
  transform: rotate(0deg);
}
.timeline-summary.is-expanded .op-chevron {
  transform: rotate(90deg);
}
.timeline-summary .op-label {
  color: var(--sun-text);
  font-weight: 500;
}
/* shimmer：从 OperationCard 复制 .op-shimmer keyframes 或 :deep 复用；若无全局则本地写简版 */
```

- [ ] **Step 3: 手动冒烟（dev）**

Run: `cd sunshine-ui && npm run dev`，发一条 ReAct：顶行出现「正在进行 …」且步骤可见；结束后应变「已完成 …」且步骤默认隐藏（终稿 Task 4 再补）。

- [ ] **Step 4: Commit**

```bash
git add sunshine-ui/src/components/operation/OperationStack.vue
git commit -m "$(cat <<'EOF'
feat(ui): add OperationStack timeline summary header

Wall-clock status line with auto expand while streaming and
collapse after terminal message status; nested stacks stay unchanged.
EOF
)"
```

---

### Task 4: 折叠终稿 + 双显守卫

**Files:**
- Modify: `sunshine-ui/src/components/operation/OperationStack.vue`
- Modify: `sunshine-ui/src/views/ChatView.vue`（可与 Task 5 合并接线；本 Task 至少在 Stack 内闭环）

- [ ] **Step 1: 折叠终稿 computed**

```ts
const collapsedAnswerText = computed(() => {
  if (!summaryEnabled.value || isTimelineBodyExpanded()) return ''
  return resolveCollapsedAnswerText({
    role: 'assistant',
    content: props.messageContent ?? '',
    steps: effectiveSteps.value,
    contentBlocks: props.contentBlocks,
  })
})

/** 仅 interleaved 时由 Stack 渲染折叠终稿，避免与底栏 msg-md 双显 */
const showCollapsedAnswer = computed(() => {
  if (!collapsedAnswerText.value) return false
  return isContentFullyInterleaved({
    role: 'assistant',
    content: props.messageContent ?? '',
    steps: effectiveSteps.value,
    contentBlocks: props.contentBlocks,
  })
})
```

- [ ] **Step 2: 模板折叠分支**

在 `v-if="isTimelineBodyExpanded()"` 的 `</template>` 之后：

```vue
  <div
    v-else-if="showCollapsedAnswer"
    class="op-inline-content timeline-collapsed-answer"
  >
    <span class="op-gutter" aria-hidden="true" />
    <div class="op-inline-body">
      <StaticMarkdown :source="collapsedAnswerText" />
    </div>
  </div>
```

复用已有 `.op-inline-content` 样式。

- [ ] **Step 3: Commit**

```bash
git add sunshine-ui/src/components/operation/OperationStack.vue
git commit -m "$(cat <<'EOF'
feat(ui): show final answer only when timeline summary collapsed

Hide interleaved mid segments; render one collapsed answer block
only when content is fully interleaved to avoid bottom msg-md dup.
EOF
)"
```

---

### Task 5: ChatView 接线

**Files:**
- Modify: `sunshine-ui/src/views/ChatView.vue`

- [ ] **Step 1: 给顶层 OperationStack 传 props**

找到 ChatView 中：

```vue
<OperationStack
  v-if="showTimeline(msg, idx)"
  ...
/>
```

追加：

```vue
  :message-status="msg.status ?? 'completed'"
  :message-content="msg.content"
```

**禁止**给 `PlanNodeDrawer` / loop 嵌套 Stack 传这两个 props。

- [ ] **Step 2: 确认底栏逻辑未改**

`shouldShowBottomContent` / `isContentFullyInterleaved` 保持原样；折叠 interleaved 时底栏仍隐藏，Stack 内出终稿。

- [ ] **Step 3: Commit**

```bash
git add sunshine-ui/src/views/ChatView.vue
git commit -m "$(cat <<'EOF'
feat(ui): wire timeline summary props from ChatView

Pass message status and content into OperationStack so only the
top-level chat timeline shows the summary header.
EOF
)"
```

---

### Task 6: E2E 验收

**Files:**
- Modify: `sunshine-ui/e2e/processing-timeline.spec.ts`
- Modify: `sunshine-ui/e2e/helpers.ts`（若需 summary locator；可选）

- [ ] **Step 1: 追加用例**

在 `processing-timeline.spec.ts` 增加：

```ts
  test('总览行：完成后默认折叠，展开可恢复步骤', async ({ page }) => {
    test.setTimeout(90_000)
    await sendChatMessage(page, '你好，简单聊聊')
    const timeline = lastOperationStack(page)
    await expect(timeline).toBeVisible({ timeout: 20_000 })
    await waitForStreamComplete(page, 60_000)

    const summary = timeline.locator('.timeline-summary')
    await expect(summary).toBeVisible()
    await expect(summary).toContainText(/已完成/)
    // 默认折叠：实现线意图步不可见
    await expect(timeline.locator('.op-label', { hasText: '识别意图' })).toHaveCount(0)

    await summary.locator('.op-line-row').click()
    await expect(timeline.locator('.op-label', { hasText: '识别意图' })).toBeVisible()

    await summary.locator('.op-line-row').click()
    await expect(timeline.locator('.op-label', { hasText: '识别意图' })).toHaveCount(0)
    // 折叠后不应出现两份终稿（Stack 内一块 + 底栏）
    const answers = page.locator('.assistant-body').last().locator('.msg-md, .timeline-collapsed-answer .msg-md')
    await expect(answers).toHaveCount(1)
  })
```

若项目 mock e2e 与 live 行为不同，按 `processing-timeline.spec.ts` 现有模式调整超时与文案；断言核心是：`.timeline-summary`、折叠无「识别意图」、展开有、折叠后 `.msg-md` 计数不双显。

- [ ] **Step 2: 跑 e2e**

Run:

```bash
cd /usr/local/gitproj/my-sunshine-agent/sunshine-ui && npx playwright test e2e/processing-timeline.spec.ts
```

Expected: 相关用例 PASS（环境需可聊；若 CI mock，跟随仓库既有约定）。

- [ ] **Step 3: Commit**

```bash
git add sunshine-ui/e2e/processing-timeline.spec.ts
git commit -m "$(cat <<'EOF'
test(ui): cover timeline summary collapse behavior

Assert completed runs collapse implementation steps and expand
restores them without duplicating the final answer.
EOF
)"
```

---

### Task 7: 文档索引收尾

**Files:**
- Modify: `docs/superpowers/specs/README.md`（plans 表补本 plan 链接）
- Modify: `docs/superpowers/specs/2026-07-20-timeline-summary-duration-design.md`（状态改为实施中/计划已出）

- [x] **Step 1: README plans 行追加链接**

在阶段「四」单元格末尾追加：

`· **时间线总览** 见 [timeline-summary-duration-design.md](./2026-07-20-timeline-summary-duration-design.md) + [2026-07-20-timeline-summary-duration.md](../plans/2026-07-20-timeline-summary-duration.md)`

Spec 头 `状态` 改为：`🟢 已实施`。

- [x] **Step 2: Commit**

```bash
git add docs/superpowers/specs/README.md \
  docs/superpowers/specs/2026-07-20-timeline-summary-duration-design.md \
  docs/superpowers/plans/2026-07-20-timeline-summary-duration.md
git commit -m "$(cat <<'EOF'
docs: add timeline summary duration implementation plan

Link the plan from the specs index and mark the design as planned.
EOF
)"
```

---

## Spec coverage（自检）

| Spec | Task |
|------|------|
| D1 纯前端 | 全任务无后端 |
| D2 墙钟 | Task 1 `resolveTimelineElapsedMs` |
| D3 文案四态 | Task 1 prefix + Task 3/5 |
| D4 `1m20s` / `2m0s` | Task 1 `formatElapsedClock` |
| D5/D6 默认展开 + userToggled | Task 3 |
| D7/D8 折叠只终稿 | Task 2 + 4 |
| D9 双显守卫 | Task 4 `showCollapsedAnswer` |
| D10 嵌套无总览 | Task 3/5 不传 `messageStatus` |
| A1–A8 | Task 6 + 手动冒烟；A3/A4 文案由单测覆盖，e2e 以完成态为主 |
| HITL 折叠可接受 | 不实现特例 |

## 非目标（计划内不做）

- `message.durationMs` 后端字段  
- 改 `formatDuration` / 单卡默认展开  
- localStorage 持久化 expand  
- 折叠态 HITL 浮层
