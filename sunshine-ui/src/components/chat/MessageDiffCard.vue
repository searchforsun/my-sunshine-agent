<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NIcon, NSpin } from 'naive-ui'
import { WarningOutline, ChevronForwardOutline, GitCompareOutline } from '@vicons/ionicons5'
import { gitDiffSummary, loadDiffBaseSnapshot } from '../../api/workspaceGit'
import type { GitDiffSummaryItem } from '../../api/workspaceGit'

const props = defineProps<{
  workspaceId?: string | null
  checkoutId?: string | null
  /** 会话 id：用于读取「本轮改动基线」，只展示本轮新增文件 */
  conversationId?: string | null
}>()

const emit = defineEmits<{
  openDiff: [path: string]
}>()

const allSummary = ref<GitDiffSummaryItem[]>([])
const loading = ref(false)
const error = ref('')

const visible = computed(() => !!props.workspaceId && !!props.checkoutId)

/** 本轮新增改动：过滤掉发送消息前已存在的文件（每轮差集） */
const summary = computed(() => {
  const base = props.conversationId ? loadDiffBaseSnapshot(props.conversationId) : new Set<string>()
  return allSummary.value.filter(s => !base.has(s.path))
})

const statusLabels: Record<string, string> = {
  M: 'M',
  A: 'A',
  D: 'D',
  R: 'R',
  '?': 'U',
}

/** 文件行最多展示条数；超出后折叠，点击标题行展开/收起 */
const MAX_DISPLAY = 10
const collapsed = ref(true)

const hasMore = computed(() => summary.value.length > MAX_DISPLAY)
/** 折叠时仅显示前 MAX_DISPLAY 条，展开时显示全部 */
const displayItems = computed(() =>
  hasMore.value && collapsed.value ? summary.value.slice(0, MAX_DISPLAY) : summary.value,
)

function toggleFold() {
  if (!hasMore.value) return
  collapsed.value = !collapsed.value
}

function pathBasename(p: string): string {
  const i = p.lastIndexOf('/')
  return i >= 0 ? p.slice(i + 1) : p
}

function pathDirname(p: string): string {
  const i = p.lastIndexOf('/')
  return i >= 0 ? p.slice(0, i) : ''
}

async function load() {
  if (!props.workspaceId || !props.checkoutId) {
    allSummary.value = []
    return
  }
  loading.value = true
  error.value = ''
  try {
    allSummary.value = await gitDiffSummary(props.workspaceId, props.checkoutId)
  } catch (e) {
    error.value = (e as Error)?.message || '获取改动失败'
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.workspaceId, props.checkoutId] as const,
  () => { void load() },
  { immediate: true },
)

defineExpose({ refresh: load })
</script>

<template>
  <div v-if="visible && !loading && (summary.length || error)" class="msg-diff-card">
    <div
      class="msg-diff-card-head"
      :class="{ 'is-foldable': hasMore }"
      :title="hasMore ? (collapsed ? '展开全部改动' : '收起改动') : undefined"
      @click="toggleFold"
    >
      <span class="msg-diff-head-icon">
        <span class="msg-diff-toggle-arrow" :class="{ 'is-open': !collapsed }">
          <NIcon :component="ChevronForwardOutline" :size="13" />
        </span>
        <NIcon :component="GitCompareOutline" :size="13" class="msg-diff-file-icon" />
      </span>
      <span class="msg-diff-title">{{ summary.length }}个文件改动</span>
    </div>
    <p v-if="error" class="msg-diff-error">
      <NIcon :component="WarningOutline" :size="12" /> {{ error }}
    </p>
    <button
      v-for="item in displayItems"
      :key="item.path"
      type="button"
      class="msg-diff-row"
      :title="`${item.path}（${item.added} 增 / ${item.deleted} 删）`"
      @click="emit('openDiff', item.path)"
    >
      <span class="msg-diff-status" :class="`is-${item.status.toLowerCase()}`">{{ statusLabels[item.status] ?? item.status }}</span>
      <span class="msg-diff-name">
        <span v-if="pathDirname(item.path)" class="msg-diff-dir">{{ pathDirname(item.path) }}/</span>{{ pathBasename(item.path) }}
      </span>
      <span class="msg-diff-counts">
        <span v-if="item.deleted > 0" class="count-del">-{{ item.deleted }}</span>
        <span v-if="item.added > 0" class="count-add">+{{ item.added }}</span>
      </span>
    </button>
    <div v-if="loading" class="msg-diff-loading"><NSpin size="small" /></div>
  </div>
</template>

<style scoped>
.msg-diff-card {
  margin: 6px 0 12px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-sm, 6px);
  background: var(--sun-black);
  overflow: hidden;
  font-size: var(--sun-font-sm);
  width: 100%;
  min-width: 0;
}

.msg-diff-card-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
}

.msg-diff-card-head.is-foldable {
  cursor: pointer;
}

.msg-diff-card-head.is-foldable:hover {
  background: color-mix(in srgb, var(--sun-text-muted) 10%, transparent);
}

/* 标题前图标位：文件改动图标常显，hover 时可折叠标题时箭头替换图标 */
.msg-diff-head-icon {
  position: relative;
  flex-shrink: 0;
  width: 15px;
  height: 15px;
}

.msg-diff-toggle-arrow,
.msg-diff-file-icon {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.msg-diff-file-icon {
  color: var(--sun-text-secondary);
  transition: opacity 0.12s ease;
}

.msg-diff-toggle-arrow {
  color: var(--sun-text-muted);
  opacity: 0;
  transition: transform 0.15s ease, opacity 0.12s ease;
  cursor: pointer;
}

.msg-diff-toggle-arrow.is-open {
  transform: rotate(90deg);
  opacity: 1;
}

/* hover 标题行：箭头替换文件图标 */
.msg-diff-card-head.is-foldable:hover .msg-diff-toggle-arrow {
  opacity: 1;
}

.msg-diff-card-head.is-foldable:hover .msg-diff-toggle-arrow ~ .msg-diff-file-icon {
  opacity: 0;
}

/* 展开态：箭头常显（旋转 90° 呈 ^），文件图标一直隐藏 */
.msg-diff-toggle-arrow.is-open ~ .msg-diff-file-icon {
  opacity: 0;
}

.msg-diff-title {
  font-weight: 400;
  color: var(--sun-text-muted);
}

.msg-diff-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 5px 10px;
  border: none;
  background: transparent;
  color: var(--sun-text);
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace);
  font-size: var(--sun-font-sm);
  text-align: left;
  cursor: pointer;
}

.msg-diff-row:hover {
  background: color-mix(in srgb, var(--sun-text-muted) 10%, transparent);
}

.msg-diff-status {
  flex-shrink: 0;
  width: 16px;
  height: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-weight: 700;
  border-radius: 3px;
  border: 1px solid var(--sun-border-light);
  color: var(--sun-text-secondary);
}

.msg-diff-status.is-m {
  color: #d29922;
  border-color: rgba(210, 153, 34, 0.5);
}

.msg-diff-status.is-a {
  color: #2a9a5c;
  border-color: rgba(42, 154, 92, 0.5);
}

.msg-diff-status.is-d {
  color: #c44;
  border-color: rgba(204, 68, 68, 0.5);
}

.msg-diff-status.is-r {
  color: #58a6ff;
  border-color: rgba(88, 166, 255, 0.5);
}

.msg-diff-status.is-? {
  color: var(--sun-text-muted);
  border-color: var(--sun-border-light);
}

.msg-diff-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.msg-diff-dir {
  color: var(--sun-text-muted);
  opacity: 0.8;
}

.msg-diff-counts {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--sun-font-xs);
  font-variant-numeric: tabular-nums;
}

.count-add {
  color: #2a9a5c;
}

.count-del {
  color: #c44;
}

.msg-diff-error {
  margin: 0;
  padding: 5px 10px;
  display: flex;
  align-items: center;
  gap: 5px;
  color: #c44;
}

.msg-diff-loading {
  padding: 6px 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
