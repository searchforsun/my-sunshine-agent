<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { NIcon } from 'naive-ui'
import { CheckmarkOutline, CopyOutline } from '@vicons/ionicons5'
import type { ProcessingStep } from '../../api/processingSteps'
import {
  formatDuration,
  resolveStepDurationMs,
  formatStepLabel,
  stepLifecycle,
  resolveStepHeaderText,
  resolveStepExpandPanels,
  shouldShiftSummaryOnExpand,
  hasExpandableContent,
  resolvePlanIdFromStep,
  isSandboxToolStep,
  isSandboxExecStep,
  extractSandboxExecCommand,
  resolveSandboxFocusPath,
  extractSandboxSearchRoot,
  inferSandboxSearchRoot,
  parseSandboxPathList,
  isSandboxPathListOutput,
  resolveStepExpandInner,
} from '../../api/processingSteps'
import { parseSandboxEditDiff, writeContentAsAddLines, isSandboxWriteStep, summarizeDiffCounts, type SandboxDiffLine } from '../../api/sandboxEditDiff'
import { useRouter } from 'vue-router'
import StaticMarkdown from '../StaticMarkdown.vue'
import { isToolStepId, type HitlConfirmationPayload } from '../../api/hitlSteps'
import HitlStepActions from './HitlStepActions.vue'
import { useSandboxWorkspaceDrawer } from '../../composables/useSandboxWorkspaceDrawer'
import { useChatStore } from '../../stores/chatStore'
import { registerHljsLanguages } from '../../utils/markdown/registerHljsLanguages'
import { copyText } from '../../utils/stream-markdown/clipboard'

const hljs = registerHljsLanguages()

function langFromPath(path: string): string | null {
  const dot = path.lastIndexOf('.')
  if (dot < 0) return null
  const ext = path.slice(dot).toLowerCase()
  const map: Record<string, string> = {
    '.py': 'python',
    '.sh': 'bash',
    '.bash': 'bash',
    '.json': 'json',
    '.yaml': 'yaml',
    '.yml': 'yaml',
    '.sql': 'sql',
    '.xml': 'xml',
    '.html': 'xml',
    '.htm': 'xml',
    '.js': 'javascript',
    '.ts': 'typescript',
    '.jsx': 'javascript',
    '.tsx': 'typescript',
    '.java': 'java',
    '.rs': 'rust',
    '.cpp': 'cpp',
    '.c': 'c',
  }
  return map[ext] ?? null
}

function highlightCode(text: string, lang: string | null): string {
  if (!text) return ''
  try {
    if (lang && hljs.getLanguage(lang)) {
      return hljs.highlight(text, { language: lang }).value
    }
    return hljs.highlightAuto(text).value
  } catch {
    return ''
  }
}

const props = withDefaults(defineProps<{
  step: ProcessingStep
  expanded: boolean
  live?: boolean
  /** 消息级 executionPlanId 兜底（历史数据） */
  executionPlanId?: string
  /** 为 false 时不在卡片内嵌 HITL（Plan 抽屉等外层承载） */
  embedHitl?: boolean
  pendingHitlConfirmation?: HitlConfirmationPayload
  hitlUiKey?: string
}>(), {
  embedHitl: true,
  pendingHitlConfirmation: undefined,
  hitlUiKey: '',
})

const router = useRouter()
const chatStore = useChatStore()
const sandboxDrawer = useSandboxWorkspaceDrawer()

const emit = defineEmits<{
  toggle: []
  hitlDecided: [token: string, approved: boolean]
}>()

function onRowActivate() {
  if (isSandboxTool.value && chatStore.currentId) {
    const focus = resolveSandboxFocusPath(props.step)
    sandboxDrawer.open({
      conversationId: chatStore.currentId,
      focusPath: focus,
    })
  }
  if (canExpand.value) {
    emit('toggle')
  }
}

function openSandboxPath(path: string) {
  if (!chatStore.currentId || !path) return
  sandboxDrawer.open({
    conversationId: chatStore.currentId,
    focusPath: path,
  })
}

const showEmbeddedHitl = computed(() =>
  props.embedHitl !== false && isToolStepId(props.step.id),
)

const hitlPanelKey = computed(() =>
  props.hitlUiKey
  || props.step.metadata?.hitlToken
  || props.step.metadata?.hitlStatus
  || props.pendingHitlConfirmation?.confirmationToken
  || props.step.summary?.active
  || props.step.id,
)

const lifecycle = computed(() => stepLifecycle(props.step))
const isRunning = computed(() => lifecycle.value === 'running')
const isDone = computed(() => lifecycle.value === 'done')
const label = computed(() => formatStepLabel(props.step))

/** 主行摘要：折叠时一行预览；展开且可下移时主行仅保留 label */
const headerText = computed(() => resolveStepHeaderText(props.step))
const shiftSummary = computed(() => shouldShiftSummaryOnExpand(props.step))
const isSandboxTool = computed(() => isSandboxToolStep(props.step))
const isSandboxExec = computed(() => isSandboxExecStep(props.step))
const execCommand = computed(() => extractSandboxExecCommand(props.step) ?? '')
const sandboxRaw = computed(() => {
  if (!isSandboxTool.value) return ''
  return resolveStepExpandInner(props.step)
})
const sandboxPathEntries = computed(() => {
  if (!isSandboxTool.value || isSandboxExec.value || !sandboxRaw.value) return []
  if (!isSandboxPathListOutput(props.step, sandboxRaw.value)) return []
  const fromSummary = extractSandboxSearchRoot(props.step.summary?.after)
    || extractSandboxSearchRoot(props.step.summary?.active)
  const paths = parseSandboxPathList(sandboxRaw.value).map(e => e.path)
  const root = fromSummary || inferSandboxSearchRoot(paths)
  return parseSandboxPathList(sandboxRaw.value, root)
})
const sandboxEditDiffLines = computed((): SandboxDiffLine[] => {
  if (!isSandboxTool.value || isSandboxExec.value || !sandboxRaw.value) return []
  if (sandboxPathEntries.value.length) return []
  const parsed = parseSandboxEditDiff(sandboxRaw.value)
  if (parsed?.length) return parsed
  // write：正文整段作新增行
  if (isSandboxWriteStep(props.step)) {
    return writeContentAsAddLines(sandboxRaw.value)
  }
  return []
})
const editDiffSummary = computed(() => {
  const lines = sandboxEditDiffLines.value
  if (!lines.length) return null
  const { add, del } = summarizeDiffCounts(lines)
  if (!add && !del) return null
  return { add, del }
})
const editDiffLang = computed(() => {
  const path = resolveSandboxFocusPath(props.step)
  return path ? langFromPath(path) : null
})
const editDiffRendered = computed(() => {
  const lang = editDiffLang.value
  return sandboxEditDiffLines.value.map(line => ({
    kind: line.kind,
    html: highlightCode(line.text || ' ', lang) || escapeHtml(line.text || ' '),
  }))
})

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}
const execCommandHtml = computed(() => {
  if (!execCommand.value) return ''
  return highlightCode(execCommand.value, 'bash')
})
const execOutputHtml = computed(() => {
  if (!isSandboxExec.value || !sandboxRaw.value) return ''
  return highlightCode(sandboxRaw.value, 'bash')
})
const sandboxContentHtml = computed(() => {
  if (!isSandboxTool.value || isSandboxExec.value || !sandboxRaw.value) return ''
  if (sandboxPathEntries.value.length || sandboxEditDiffLines.value.length) return ''
  const path = resolveSandboxFocusPath(props.step)
  return highlightCode(sandboxRaw.value, path ? langFromPath(path) : null)
})
const sandboxCopyDone = ref(false)
let sandboxCopyTimer: ReturnType<typeof setTimeout> | null = null

async function copySandboxContent() {
  const parts: string[] = []
  if (isSandboxExec.value && execCommand.value) {
    parts.push(`$ ${execCommand.value}`)
  }
  if (sandboxRaw.value) parts.push(sandboxRaw.value)
  const text = parts.join('\n')
  if (!text) return
  const ok = await copyText(text)
  if (!ok) return
  sandboxCopyDone.value = true
  if (sandboxCopyTimer) clearTimeout(sandboxCopyTimer)
  sandboxCopyTimer = setTimeout(() => {
    sandboxCopyDone.value = false
    sandboxCopyTimer = null
  }, 2000)
}

const showHeaderPreview = computed(
  () => !!headerText.value && (!props.expanded || !shiftSummary.value),
)

const expandPanels = computed(() => resolveStepExpandPanels(props.step))
const expandSummary = computed(() => expandPanels.value.lead)
const expandBody = computed(() => expandPanels.value.body)

const canExpand = computed(() => hasExpandableContent(props.step))
const rowClickable = computed(() => canExpand.value || isSandboxTool.value)

const planLinkId = computed(() => {
  if (props.step.phase !== 'plan') return undefined
  return resolvePlanIdFromStep(props.step) ?? props.executionPlanId
})

function openPlanDetail() {
  const id = planLinkId.value
  if (!id) return
  void router.push({ name: 'plan-detail', params: { planId: id } })
}

const liveElapsedMs = ref<number | null>(null)
let elapsedTimer: ReturnType<typeof setInterval> | null = null

function clearElapsedTimer() {
  if (elapsedTimer != null) {
    clearInterval(elapsedTimer)
    elapsedTimer = null
  }
}

watch(
  () => [props.live, isRunning.value, props.step.startedAt] as const,
  ([live, running, startedAt]) => {
    clearElapsedTimer()
    if (live && running && typeof startedAt === 'number') {
      const tick = () => {
        liveElapsedMs.value = Math.max(0, Date.now() - startedAt)
      }
      tick()
      elapsedTimer = setInterval(tick, 200)
    } else {
      liveElapsedMs.value = null
    }
  },
  { immediate: true },
)

onUnmounted(() => {
  clearElapsedTimer()
  if (sandboxCopyTimer) clearTimeout(sandboxCopyTimer)
})

const durationText = computed(() => {
  if (isDone.value) {
    const ms = resolveStepDurationMs(props.step)
    if (ms != null) return formatDuration(ms)
  }
  if (isRunning.value && props.live && liveElapsedMs.value != null) {
    return formatDuration(liveElapsedMs.value)
  }
  return ''
})

const showShimmer = computed(() => isRunning.value && !!props.live)
</script>

<template>
  <div
    class="op-line"
    :class="{
      'is-expanded': expanded,
      'is-running': isRunning && live,
      'is-clickable': rowClickable,
    }"
  >
    <div
      class="op-line-row"
      :role="rowClickable ? 'button' : undefined"
      :tabindex="rowClickable ? 0 : -1"
      @click="onRowActivate"
      @keydown.enter.prevent="onRowActivate"
      @keydown.space.prevent="onRowActivate"
    >
      <span class="op-gutter" aria-hidden="true">
        <svg
          v-if="canExpand"
          class="op-chevron"
          width="9"
          height="9"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linecap="round"
        >
          <polyline points="9 18 15 12 9 6" />
        </svg>
      </span>
      <span class="op-main">
        <span
          class="op-label operation-card-title"
          :class="{ 'op-shimmer': showShimmer }"
        >{{ label }}</span>
        <span
          v-if="showHeaderPreview"
          class="op-text"
          :class="{ 'op-shimmer': showShimmer }"
        >
          {{ headerText }}
          <span v-if="editDiffSummary" class="op-diff-summary" aria-label="变更行数">
            <span v-if="editDiffSummary.add" class="op-diff-stat is-add">+{{ editDiffSummary.add }}</span>
            <span v-if="editDiffSummary.del" class="op-diff-stat is-del">-{{ editDiffSummary.del }}</span>
          </span>
          <span v-if="isRunning && live" class="op-pulse">…</span>
        </span>
      </span>
      <span v-if="durationText" class="op-dur">{{ durationText }}</span>
      <button
        v-if="planLinkId"
        type="button"
        class="op-plan-link"
        @click.stop="openPlanDetail"
      >
        查看详情
      </button>
    </div>

    <HitlStepActions
      v-if="showEmbeddedHitl"
      :key="hitlPanelKey"
      :step="step"
      :pending-confirmation="pendingHitlConfirmation"
      @decided="(token, approved) => emit('hitlDecided', token, approved)"
    />

    <div v-if="expanded && canExpand" class="op-detail">
      <div v-if="isSandboxTool" class="op-sandbox">
        <button
          v-if="execCommand || sandboxRaw"
          type="button"
          class="op-sandbox-copy smd-toolbtn"
          :title="sandboxCopyDone ? '已复制' : '复制'"
          @click.stop="copySandboxContent"
        >
          <NIcon :component="sandboxCopyDone ? CheckmarkOutline : CopyOutline" :size="14" />
        </button>
        <template v-if="isSandboxExec">
          <pre v-if="execCommand" class="op-exec-cmd"><span class="op-exec-prompt">$</span><code v-if="execCommandHtml" class="hljs language-bash" v-html="execCommandHtml" /><span v-else class="op-exec-cmd-plain">{{ execCommand }}</span></pre>
          <pre v-if="sandboxRaw" class="op-exec-out"><code v-if="execOutputHtml" class="hljs language-bash" v-html="execOutputHtml" /><template v-else>{{ sandboxRaw }}</template></pre>
          <p v-if="!execCommand && !sandboxRaw" class="op-exec-empty">无输出</p>
        </template>
        <template v-else-if="sandboxPathEntries.length">
          <ul class="op-sandbox-paths">
            <li v-for="entry in sandboxPathEntries" :key="entry.path">
              <button
                type="button"
                class="op-sandbox-path-link"
                :title="entry.path"
                @click.stop="openSandboxPath(entry.path)"
              >{{ entry.name }}</button>
            </li>
          </ul>
        </template>
        <template v-else-if="editDiffRendered.length">
          <pre class="op-sandbox-diff"><code
            v-for="(line, idx) in editDiffRendered"
            :key="idx"
            class="op-diff-line hljs"
            :class="`is-${line.kind}`"
            v-html="line.html"
          /></pre>
        </template>
        <template v-else>
          <pre v-if="sandboxContentHtml" class="op-sandbox-code"><code class="hljs" v-html="sandboxContentHtml" /></pre>
          <pre v-else-if="sandboxRaw" class="op-sandbox-code">{{ sandboxRaw }}</pre>
          <p v-else class="op-exec-empty">无输出</p>
        </template>
      </div>
      <template v-else>
        <div v-if="expandSummary && shiftSummary" class="op-detail-after">
          <StaticMarkdown :source="expandSummary" compact />
        </div>
        <StaticMarkdown v-if="expandBody" :source="expandBody" compact />
        <div v-if="step.reasoning?.trim()" class="op-detail-thinking">
          <StaticMarkdown :source="step.reasoning" compact />
        </div>
        <StaticMarkdown v-if="step.output?.trim()" :source="step.output" compact />
      </template>
    </div>
  </div>
</template>

<style scoped>
.op-line {
  --op-gutter: 12px;
  --op-detail-inset: calc(var(--op-gutter) + 4px);
  --op-font: var(--sun-font-md);
  --op-font-sm: var(--sun-font-sm);
  --op-detail-font: var(--sun-font-base);
  font-size: var(--op-font);
  line-height: 1.5;
  color: var(--sun-text-muted);
}

.op-line-row {
  display: grid;
  grid-template-columns: var(--op-gutter) minmax(0, 1fr) auto auto;
  column-gap: 4px;
  align-items: start;
  width: 100%;
  padding: 1px 0;
  border: none;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: default;
}

.op-line.is-clickable .op-line-row {
  cursor: pointer;
}

.op-line.is-clickable:hover .op-label {
  color: var(--sun-text-secondary);
}

.op-gutter {
  display: flex;
  align-items: flex-start;
  justify-content: flex-start;
  width: var(--op-gutter);
  padding-top: 4px;
  flex-shrink: 0;
}

.op-chevron {
  flex-shrink: 0;
  color: var(--sun-text-muted);
  opacity: 0.5;
  transition: transform 0.15s ease;
}

.op-line.is-expanded .op-chevron {
  transform: rotate(90deg);
}

.op-main {
  display: flex;
  flex-wrap: nowrap;
  align-items: baseline;
  gap: 0 6px;
  min-width: 0;
}

.op-shimmer {
  --op-shimmer-base: var(--sun-text-muted);
  --op-shimmer-peak: color-mix(in srgb, var(--sun-text-muted) 32%, white);
  display: inline-block;
  max-width: 100%;
  background-image: linear-gradient(
    90deg,
    var(--op-shimmer-base) 0%,
    var(--op-shimmer-base) 36%,
    var(--op-shimmer-peak) 50%,
    var(--op-shimmer-base) 64%,
    var(--op-shimmer-base) 100%
  );
  background-size: 220% 100%;
  background-repeat: no-repeat;
  background-position: 100% center;
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  animation: op-text-shimmer 2.6s linear infinite;
  will-change: background-position;
}

.op-label.op-shimmer {
  --op-shimmer-base: var(--sun-text);
  --op-shimmer-peak: color-mix(in srgb, var(--sun-text) 22%, white);
}

.op-text.op-shimmer {
  opacity: 1;
}

.op-label {
  flex-shrink: 0;
  color: var(--sun-text-secondary);
  font-weight: 450;
}

.op-text {
  flex: 1 1 0;
  color: var(--sun-text-muted);
  opacity: 0.92;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}

.op-dur {
  flex-shrink: 0;
  padding-left: 10px;
  padding-top: 1px;
  font-size: var(--op-font-sm);
  color: var(--sun-text-muted);
  opacity: 0.65;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.op-plan-link {
  flex-shrink: 0;
  margin-left: 8px;
  padding: 0 8px;
  height: 22px;
  border: 1px solid var(--sun-border);
  border-radius: 999px;
  background: transparent;
  color: var(--sun-text-secondary);
  font-size: 11px;
  cursor: pointer;
  transition: color 0.15s, border-color 0.15s;
}

.op-plan-link:hover {
  color: var(--sun-text);
  border-color: var(--sun-border-light);
}

.op-line.is-running .op-label:not(.op-shimmer) {
  color: var(--sun-text);
}

.op-pulse {
  animation: op-pulse 1.2s ease-in-out infinite;
}

.op-detail {
  margin: 2px 0 6px var(--op-detail-inset);
  padding-left: 8px;
  border-left: 1px solid color-mix(in srgb, var(--sun-text-muted) 18%, transparent);
  display: flex;
  flex-direction: column;
  gap: 3px;
  max-height: min(40vh, 320px);
  overflow-y: auto;
  overscroll-behavior: contain;
  padding-right: 2px;
}

.op-sandbox {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-right: 28px;
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace);
  font-size: var(--sun-font-sm, 12px);
  font-weight: 400;
  line-height: 1.55;
  letter-spacing: 0;
  font-variant-ligatures: none;
  tab-size: 4;
  color: var(--sun-text-muted);
}

.op-sandbox :deep(pre),
.op-sandbox :deep(code),
.op-sandbox :deep(.hljs),
.op-sandbox :deep(span) {
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace) !important;
  letter-spacing: 0;
  font-variant-ligatures: none;
}

.op-sandbox-copy {
  position: absolute;
  top: -2px;
  right: 0;
  z-index: 1;
}

.op-exec-cmd,
.op-exec-out,
.op-sandbox-code,
.op-sandbox-diff {
  margin: 0;
  padding: 0;
  white-space: pre-wrap;
  overflow-wrap: break-word;
  word-break: normal;
  background: transparent;
  border: none;
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace);
  font-size: var(--sun-font-sm, 12px);
  font-weight: 400;
  line-height: 1.55;
  letter-spacing: 0;
  font-variant-ligatures: none;
  tab-size: 4;
}

/* 命令：略亮于摘要，仍属时间线次级信息 */
.op-exec-cmd {
  color: var(--sun-text-secondary);
  font-weight: 500;
}

.op-exec-cmd :deep(.hljs),
.op-exec-cmd-plain {
  display: inline;
  padding: 0;
  background: transparent !important;
  color: var(--sun-text-secondary);
  white-space: inherit;
}

.op-exec-prompt {
  color: color-mix(in srgb, var(--sun-accent, #6cb6ff) 72%, var(--sun-text-muted));
  margin-right: 6px;
  font-weight: 600;
  user-select: none;
}

/* 输出 / 工具正文：摘要灰 + hljs token 着色 */
.op-exec-out,
.op-sandbox-code {
  color: var(--sun-text-muted);
  opacity: 0.88;
}

.op-exec-out :deep(.hljs),
.op-sandbox-code :deep(.hljs) {
  display: block;
  padding: 0;
  background: transparent !important;
  color: var(--sun-text-muted);
  white-space: inherit;
  word-break: inherit;
}

.op-exec-empty {
  margin: 0;
  font-size: var(--sun-font-sm);
  color: var(--sun-text-muted);
  opacity: 0.85;
}

.op-sandbox-paths {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.op-sandbox-path-link {
  display: inline;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--sun-text-secondary);
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace);
  font-size: inherit;
  font-weight: 500;
  text-align: left;
  cursor: pointer;
}

.op-sandbox-path-link:hover {
  color: var(--sun-accent, #6cb6ff);
  text-decoration: underline;
}

.op-diff-summary {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  flex-shrink: 0;
  margin-left: 6px;
  font-size: var(--sun-font-sm, 12px);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.op-diff-stat.is-add {
  color: #2a9a5c;
}

.op-diff-stat.is-del {
  color: #c44;
}

.op-sandbox-diff {
  margin: 0;
  padding: 0;
  white-space: pre-wrap;
  overflow-wrap: break-word;
  word-break: normal;
  background: transparent;
  border: none;
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace);
  font-size: var(--sun-font-sm, 12px);
  line-height: 1.45;
  letter-spacing: 0;
  font-variant-ligatures: none;
  tab-size: 4;
}

.op-diff-line {
  display: block;
  padding: 0 4px;
  background: transparent !important;
  white-space: pre-wrap;
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace) !important;
}

.op-sandbox-diff :deep(span) {
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace) !important;
}

.op-diff-line.is-del {
  background: color-mix(in srgb, #c44 14%, transparent) !important;
}

.op-diff-line.is-add {
  background: color-mix(in srgb, #2a9a5c 14%, transparent) !important;
}

.op-detail-after {
  opacity: 0.92;
}

.op-detail-after :deep(.static-md-compact) {
  color: var(--sun-text-muted);
}

.op-detail-thinking {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.op-detail :deep(.static-md-compact) {
  color: var(--sun-text-muted);
  opacity: 0.9;
}

.op-line :deep(.collapsible-confirm) {
  --confirm-inset-left: 0;
  margin-left: var(--op-detail-inset);
}

@keyframes op-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

@keyframes op-text-shimmer {
  0% { background-position: 100% center; }
  100% { background-position: 0% center; }
}
</style>
