<script setup lang="ts">
import { computed, ref, watch, nextTick } from 'vue'
import { NIcon } from 'naive-ui'
import {
  WarningOutline,
  AddOutline,
  CheckmarkOutline,
  ChevronForwardOutline,
  LayersOutline,
  CreateOutline,
} from '@vicons/ionicons5'
import CodiconDiscardIcon from '../icons/CodiconDiscardIcon.vue'
import { gitDiffSummary, gitDiffFile, gitRevert, gitStage, gitUnstage, gitCommit } from '../../api/workspaceGit'
import type { GitDiffSummaryItem, GitDiffDetail, GitDiffCounts } from '../../api/workspaceGit'
import type { SandboxDiffLine } from '../../api/sandboxEditDiff'
import SandboxDiffView from './SandboxDiffView.vue'

const props = defineProps<{
  workspaceId: string
  checkoutId: string
  /** 直接打开的改动文件（来自消息卡片点击）；空则停留在文件列表 */
  initialPath?: string
}>()

const emit = defineEmits<{
  /** 点击文件名 -> 跳转文件区定位该文件 */
  openFile: [path: string]
}>()

const summary = ref<GitDiffSummaryItem[]>([])
const summaryLoading = ref(false)
const summaryError = ref('')
/** 懒加载：已加载过则切回不重复拉取；checkoutId 变化或主动刷新（force）才重载 */
const hasLoadedSummary = ref(false)

/** 改动视图：VSCode 风格纵向分区 —— 已暂存区 + 未暂存区（未跟踪归入未暂存） */
type DiffSection = 'staged' | 'unstaged'

/** commit 消息 */
const commitMessage = ref('')
/** 提交按钮状态机：idle -> loading（转圈）-> done（√，稍后回 idle） */
const commitState = ref<'idle' | 'loading' | 'done'>('idle')

/** 操作错误提示：显示在改动面板顶部（commit 栏上方）一行，不再用全局 alert */
const opError = ref('')

/** 分区折叠状态（点击分区标题行切换） */
const collapsedSection = ref<Set<DiffSection>>(new Set())

function isSectionCollapsed(section: DiffSection): boolean {
  return collapsedSection.value.has(section)
}

function toggleSection(section: DiffSection) {
  if (collapsedSection.value.has(section)) collapsedSection.value.delete(section)
  else collapsedSection.value.add(section)
}

/** 详情缓存：path -> 详情（undefined=未加载，null=加载中/失败，对象=已缓存）。
    折叠不删除缓存，重新展开直接复用，避免每次展开都重新请求（与文件区预览缓存一致）。 */
const expanded = ref<Record<string, GitDiffDetail | null>>({})
/** 已缓存但被用户折叠的 path 集合（详情保留在 expanded，仅控制显示） */
const collapsed = ref<Set<string>>(new Set())
const detailLoading = ref<Record<string, boolean>>({})
const detailError = ref<Record<string, string>>({})

/** 勾选的文件 path 集合（未暂存区） */
const selected = ref<Set<string>>(new Set())
/** 已暂存区勾选集合：配合头部全选框与「回退全部已暂存」 */
const stagedSelected = ref<Set<string>>(new Set())

/** 批量操作 loading */
const batchStaging = ref(false)
const batchReverting = ref(false)
const stagedReverting = ref(false)
const singleBusyPath = ref('')

/** 就地二次确认（替代全局弹窗）：单文件回退等待确认的 path；批量回退确认开关（仅未暂存区需要确认，已暂存回退直接执行） */
const confirmingRevertPath = ref('')
const confirmingBatchRevert = ref(false)

function pathBasename(p: string): string {
  const i = p.lastIndexOf('/')
  return i >= 0 ? p.slice(i + 1) : p
}

function pathDirname(p: string): string {
  const i = p.lastIndexOf('/')
  return i >= 0 ? p.slice(0, i) : ''
}

const statusLabels: Record<string, string> = {
  M: 'M',
  A: 'A',
  D: 'D',
  R: 'R',
  '?': 'U',
}

const selectedCount = computed(() => selected.value.size)
/** 勾选仅作用于未暂存区文件（已暂存区直接操作，无需勾选） */
const selectedPaths = computed(() => unstagedFiles.value.filter(s => selected.value.has(s.path)).map(s => s.path))
/** 选中中是否有未跟踪文件（回退会删除） */
const hasUntrackedSelected = computed(() =>
  unstagedFiles.value.some(s => selected.value.has(s.path) && s.status === '?'),
)

/** porcelain XY 码：首个字符 X = 暂存区 vs HEAD，第二个字符 Y = 工作区 vs 暂存区 */
function hasStaged(item: GitDiffSummaryItem): boolean {
  const x = item.rawStatus[0]
  return !!x && x !== ' ' && x !== '?'
}

function hasUnstaged(item: GitDiffSummaryItem): boolean {
  const y = item.rawStatus[1]
  return !!y && y !== ' '
}

/** 已暂存区文件列表（按 porcelain X 码） */
const stagedFiles = computed(() => summary.value.filter(hasStaged))
/** 未暂存区文件列表（按 porcelain Y 码，含未跟踪） */
const unstagedFiles = computed(() => summary.value.filter(hasUnstaged))

/** 批量操作目标：有勾选时作用于勾选文件，否则作用于全部未暂存文件 */
const bulkTargets = computed(() =>
  selected.value.size > 0 ? selectedPaths.value : unstagedFiles.value.map(s => s.path),
)

/** 已暂存区勾选的 path 列表 */
const selectedStagedPaths = computed(() => stagedFiles.value.filter(s => stagedSelected.value.has(s.path)).map(s => s.path))
/** 已暂存区批量回退目标：有勾选时作用于勾选，否则全部已暂存文件 */
const stagedRevertTargets = computed(() =>
  stagedSelected.value.size > 0 ? selectedStagedPaths.value : stagedFiles.value.map(s => s.path),
)
/** 已暂存区是否全选（头部复选框） */
const stagedAllChecked = computed(() =>
  stagedFiles.value.length > 0 && stagedSelected.value.size === stagedFiles.value.length,
)

/** 该区可提交（暂存区非空且消息非空） */
const canCommit = computed(() => stagedFiles.value.length > 0 && commitMessage.value.trim().length > 0)

/** 该区行数统计 */
function sectionCounts(item: GitDiffSummaryItem, section: DiffSection): GitDiffCounts {
  return section === 'staged' ? item.staged : item.unstaged
}

/** 该区徽章字母：已暂存看 X 码，未暂存看 Y 码（未跟踪为 U） */
function sectionStatus(item: GitDiffSummaryItem, section: DiffSection): string {
  const code = section === 'staged' ? item.rawStatus[0] : item.rawStatus[1]
  return statusLabels[code] ?? code ?? '?'
}

/** 该区展开详情的 diff 行（null = 该区无内容）；缓存同时含三区，切换分区不重新请求 */
function sectionLines(item: GitDiffSummaryItem, section: DiffSection): SandboxDiffLine[] | null {
  const d = expanded.value[item.path]
  if (!d) return null
  const part = section === 'staged' ? d.staged : d.unstaged
  return part.present ? part.lines : null
}

/** 拉取改动摘要。silent=true 时用于 git 操作后的静默刷新：不切换全屏「加载中」，保留旧内容直到新数据就绪 */
async function loadSummary(silent = false) {
  // 懒加载：已加载过（切回改动 tab）不重复拉取，避免每次进入都请求
  if (hasLoadedSummary.value) return
  // checkoutId 未就绪（新任务尚未发送 / 会话切换中）时不发起请求，等 watch 就绪后再拉取
  if (!props.checkoutId) return
  if (!silent) summaryLoading.value = true
  if (!silent) summaryError.value = ''
  try {
    summary.value = await gitDiffSummary(props.workspaceId, props.checkoutId)
    hasLoadedSummary.value = true
    // 清理已不存在改动的展开/选中/折叠（详情缓存随之释放）
    const paths = new Set(summary.value.map(s => s.path))
    for (const p of Object.keys(expanded.value)) {
      if (!paths.has(p)) delete expanded.value[p]
    }
    for (const p of [...collapsed.value]) {
      if (!paths.has(p)) collapsed.value.delete(p)
    }
    for (const p of [...selected.value]) {
      if (!paths.has(p)) selected.value.delete(p)
    }
    if (props.initialPath && paths.has(props.initialPath)) {
      expandOnly(props.initialPath)
    }
  } catch (e) {
    // 静默刷新失败保留旧内容，仅顶部错误行提示；显式加载失败才展示错误态
    if (silent) {
      opError.value = (e as Error)?.message || '刷新改动失败'
    } else {
      summaryError.value = (e as Error)?.message || '获取改动失败'
    }
  } finally {
    if (!silent) summaryLoading.value = false
  }
}

/** 刷新：已加载过才强制重载摘要（清懒加载标记）；未进入过改动视图则保持懒加载，待进入时再拉取。
    已展开的详情一并静默重载（工作区可能已变化），折叠中的详情保留缓存不重拉 */
async function refresh() {
  if (!hasLoadedSummary.value) return
  const openPaths = Object.keys(expanded.value)
    .filter(p => !collapsed.value.has(`staged:${p}`) || !collapsed.value.has(`unstaged:${p}`))
  hasLoadedSummary.value = false
  await loadSummary(true)
  for (const p of openPaths) {
    if (!collapsed.value.has(`staged:${p}`) || !collapsed.value.has(`unstaged:${p}`)) await openDetail(p, true)
  }
}

/** 拉取单文件 diff 详情。silent=true 用于静默刷新：保留旧详情直到新数据就绪，失败不清空 */
async function openDetail(path: string, silent = false) {
  if (!props.checkoutId) return
  if (!silent) detailLoading.value[path] = true
  if (!silent) detailError.value[path] = ''
  try {
    expanded.value[path] = await gitDiffFile(props.workspaceId, props.checkoutId, path)
  } catch (e) {
    if (silent) return // 静默刷新失败保留旧详情
    detailError.value[path] = (e as Error)?.message || '读取 diff 失败'
    expanded.value[path] = null
  } finally {
    if (!silent) detailLoading.value[path] = false
  }
}

function toggleExpand(section: DiffSection, path: string) {
  const loaded = expanded.value[path] !== undefined
  const key = `${section}:${path}`
  const isOpen = loaded && !collapsed.value.has(key)
  if (isOpen) {
    // 展开态 -> 折叠：成功详情保留缓存，重新展开不再请求；失败态（null）移除缓存以便重试
    if (expanded.value[path] === null) delete expanded.value[path]
    else collapsed.value.add(key)
    return
  }
  collapsed.value.delete(key)
  if (!loaded) {
    expanded.value[path] = null
    void openDetail(path)
  }
}

function isExpanded(section: DiffSection, path: string): boolean {
  // 注意：不能用 Object.prototype.hasOwnProperty.call —— Vue 3.5 reactive 无
  // getOwnPropertyDescriptor trap，该调用不触发 track，expanded 变化不会重渲染。
  // 改用读值（走 get trap 建立依赖），null 与详情对象均视为已展开。
  return expanded.value[path] !== undefined && !collapsed.value.has(`${section}:${path}`)
}

/** 只展开目标文件的两个区：折叠其他已展开的 diff 详情，仅保留该文件（用于改动卡片定位）。缓存命中直接复用 */
async function expandOnly(path: string) {
  for (const p of Object.keys(expanded.value)) {
    if (p !== path && expanded.value[p] !== undefined) {
      collapsed.value.add(`staged:${p}`)
      collapsed.value.add(`unstaged:${p}`)
    }
  }
  // 先折叠其他文件，还原布局 → 滚动到目标行 → 再展开，避免布局突变
  const item = summary.value.find(s => s.path === path)
  if (item) {
    collapsed.value.delete(`staged:${path}`)
    collapsed.value.delete(`unstaged:${path}`)
  }
  await nextTick()
  const el = document.querySelector(`.diff-file[data-path="${CSS.escape(path)}"]`) as HTMLElement | null
  if (el) el.scrollIntoView({ block: 'nearest' })
  if (expanded.value[path] === undefined) {
    expanded.value[path] = null
    await openDetail(path)
  }
}

function toggleSelected(path: string) {
  if (selected.value.has(path)) selected.value.delete(path)
  else selected.value.add(path)
}

function isSelected(path: string): boolean {
  return selected.value.has(path)
}

function isStagedSelected(path: string): boolean {
  return stagedSelected.value.has(path)
}

function toggleAll() {
  if (selected.value.size === unstagedFiles.value.length) {
    selected.value.clear()
  } else {
    unstagedFiles.value.forEach(s => selected.value.add(s.path))
  }
}

/** 已暂存区：勾选单个文件 */
function toggleStagedSelected(path: string) {
  if (stagedSelected.value.has(path)) stagedSelected.value.delete(path)
  else stagedSelected.value.add(path)
}

/** 已暂存区：全选/取消全选 */
function toggleStagedAll() {
  if (stagedSelected.value.size === stagedFiles.value.length) {
    stagedSelected.value.clear()
  } else {
    stagedFiles.value.forEach(s => stagedSelected.value.add(s.path))
  }
}

/** diff 详情选中行 -> 添加到输入框（复用 ChatView 注入的全局回调） */
function onAddSelection(path: string, payload: { start: number; end: number }) {
  const cb = (window as any).__smd_addSandboxSelection as
    | ((path: string, start: number, end: number) => void)
    | undefined
  if (!path || !cb) return
  cb(path, payload.start, payload.end)
}

function applyAfterChange() {
  selected.value.clear()
  stagedSelected.value.clear()
  confirmingRevertPath.value = ''
  confirmingBatchRevert.value = false
  // git 操作已改变工作区：静默刷新，不切换全屏「加载中」、不收起已展开的文件
  const openPaths = Object.keys(expanded.value)
    .filter(p => expanded.value[p] !== undefined && (!collapsed.value.has(`staged:${p}`) || !collapsed.value.has(`unstaged:${p}`)))
  hasLoadedSummary.value = false
  void loadSummary(true).then(() => {
    for (const p of openPaths) {
      if (expanded.value[p] !== undefined) void openDetail(p, true)
    }
  })
}

async function stageBulk() {
  if (batchStaging.value || bulkTargets.value.length === 0) return
  batchStaging.value = true
  opError.value = ''
  const targets = bulkTargets.value
  try {
    await gitStage(props.workspaceId, props.checkoutId, targets)
    applyAfterChange()
  } catch (e) {
    opError.value = (e as Error)?.message || '暂存失败'
  } finally {
    batchStaging.value = false
  }
}

/** 单文件暂存（未暂存区行内）：图标按钮，成功静默、失败进顶部错误行 */
async function stageOne(item: GitDiffSummaryItem) {
  if (singleBusyPath.value) return
  singleBusyPath.value = item.path
  opError.value = ''
  try {
    await gitStage(props.workspaceId, props.checkoutId, [item.path])
    applyAfterChange()
  } catch (e) {
    opError.value = (e as Error)?.message || '暂存失败'
  } finally {
    singleBusyPath.value = ''
  }
}

/** 单文件撤回暂存（已暂存区行内）：仅清暂存区，保留工作区改动；成功静默、失败进顶部错误行 */
async function unstageOne(item: GitDiffSummaryItem) {
  if (singleBusyPath.value) return
  singleBusyPath.value = item.path
  opError.value = ''
  try {
    await gitUnstage(props.workspaceId, props.checkoutId, [item.path])
    applyAfterChange()
  } catch (e) {
    opError.value = (e as Error)?.message || '撤回失败'
  } finally {
    singleBusyPath.value = ''
  }
}

/** 提交：暂存区全部改动。实体按钮：原地 loading 转圈 -> √ -> 恢复「提交」；失败进顶部错误行 */
async function commitChanges() {
  const msg = commitMessage.value.trim()
  if (!msg || commitState.value !== 'idle' || stagedFiles.value.length === 0) return
  commitState.value = 'loading'
  opError.value = ''
  try {
    await gitCommit(props.workspaceId, props.checkoutId, msg)
    commitMessage.value = ''
    commitState.value = 'done'
    window.setTimeout(() => {
      commitState.value = 'idle'
    }, 900)
    applyAfterChange()
  } catch (e) {
    commitState.value = 'idle'
    opError.value = (e as Error)?.message || '提交失败'
  }
}

/** 点击批量「回退」→ 就地展开二次确认（作用于勾选或全部未暂存） */
function requestBatchRevert() {
  if (batchReverting.value || bulkTargets.value.length === 0) return
  confirmingBatchRevert.value = true
}

function cancelBatchRevert() {
  confirmingBatchRevert.value = false
}

function confirmBatchRevert() {
  if (!confirmingBatchRevert.value) return
  confirmingBatchRevert.value = false
  void revertPaths(bulkTargets.value)
}

/** 已暂存区「回退全部」：已暂存改动回退可任意执行，无需二次确认（仅未暂存回退需确认） */
function revertStaged() {
  if (stagedReverting.value || stagedRevertTargets.value.length === 0) return
  void revertPaths(stagedRevertTargets.value)
}

async function revertPaths(paths: string[]) {
  batchReverting.value = true
  stagedReverting.value = true
  opError.value = ''
  try {
    await gitRevert(props.workspaceId, props.checkoutId, paths)
    applyAfterChange()
  } catch (e) {
    opError.value = (e as Error)?.message || '回退失败'
  } finally {
    batchReverting.value = false
    stagedReverting.value = false
  }
}

/** 点击行尾「回退」→ 该行就地展开二次确认 */
function requestSingleRevert(item: GitDiffSummaryItem) {
  confirmingRevertPath.value = item.path
}

function cancelSingleRevert() {
  confirmingRevertPath.value = ''
}

async function confirmSingleRevert(item: GitDiffSummaryItem) {
  if (singleBusyPath.value) return
  singleBusyPath.value = item.path
  confirmingRevertPath.value = ''
  opError.value = ''
  try {
    await gitRevert(props.workspaceId, props.checkoutId, [item.path])
    applyAfterChange()
  } catch (e) {
    opError.value = (e as Error)?.message || '回退失败'
  } finally {
    singleBusyPath.value = ''
  }
}

watch(
  () => [props.workspaceId, props.checkoutId] as const,
  () => {
    expanded.value = {}
    collapsed.value.clear()
    detailLoading.value = {}
    detailError.value = {}
    selected.value.clear()
    stagedSelected.value.clear()
    // checkoutId 变化 = 工作区切换：旧摘要/旧缓存属旧 checkout，清空后静默重载新工作区数据
    summary.value = []
    summaryError.value = ''
    hasLoadedSummary.value = false
    void loadSummary(true)
  },
  { immediate: true },
)

watch(
  () => props.initialPath,
  (p) => {
    // 改动卡片定位：只展开该文件，折叠其他已展开的 diff 详情（静默加载，不闪加载态）
    if (p) void loadSummary(true).then(() => expandOnly(p))
  },
)

defineExpose({ refresh })
</script>

<template>
  <div class="sandbox-diff-panel">
    <!-- 操作错误行：顶部第一行（commit 栏上方）单独一行，替代全局 alert -->
    <div v-if="opError" class="diff-op-error">
      <NIcon :component="WarningOutline" :size="13" /> {{ opError }}
    </div>
    <!-- commit 栏：输入消息 + 提交（暂存区非空且消息非空才可提交） -->
    <div class="diff-commit-bar">
      <input
        v-model="commitMessage"
        class="diff-commit-input"
        type="text"
        placeholder="feat: 提交变更信息"
        :disabled="commitState !== 'idle'"
        @keyup.enter="commitChanges"
      />
      <button
        type="button"
        class="diff-commit-btn"
        :disabled="!canCommit || commitState !== 'idle'"
        title="提交暂存区改动"
        @click="commitChanges"
      >
        <span v-if="commitState === 'loading'" class="diff-btn-spinner"></span>
        <NIcon v-else-if="commitState === 'done'" :component="CheckmarkOutline" :size="14" />
        <template v-else>提交</template>
      </button>
    </div>

    <div class="diff-summary">
      <!-- 加载态：铺满剩余空间上下左右居中，与文件区一致，不使用原生 spinner -->
      <div v-if="summaryLoading" class="diff-loading">加载中...</div>
      <div v-else-if="summaryError" class="diff-summary-error">
        <NIcon :component="WarningOutline" :size="13" /> {{ summaryError }}
      </div>
      <div v-else-if="summary.length === 0" class="diff-empty">
        <span class="diff-empty-text">暂无改动</span>
      </div>
      <template v-else>
        <!-- 已暂存区 -->
        <div v-if="stagedFiles.length" class="diff-section">
          <div
            class="diff-section-head"
            :class="{ 'is-collapsed': isSectionCollapsed('staged') }"
            :title="isSectionCollapsed('staged') ? '展开已暂存' : '折叠已暂存'"
            @click="toggleSection('staged')"
          >
            <span class="diff-section-lead">
              <span class="diff-section-toggle-arrow" :class="{ 'is-open': !isSectionCollapsed('staged') }">
                <NIcon :component="ChevronForwardOutline" :size="13" />
              </span>
              <NIcon :component="LayersOutline" :size="13" class="diff-section-icon" />
            </span>
            <span class="diff-section-title">已暂存</span>
            <span class="diff-section-actions" @click.stop>
              <label v-if="stagedSelected.size > 0" class="diff-select-all" :title="stagedAllChecked ? '取消全选' : '全选'">
                <input type="checkbox" :checked="stagedAllChecked" @change="toggleStagedAll" />
                <span class="diff-select-all-label">已选 {{ stagedSelected.size }}</span>
              </label>
              <button
                type="button"
                class="diff-action-btn"
                :disabled="stagedReverting || stagedFiles.length === 0"
                title="回退全部已暂存改动"
                @click="revertStaged"
              >
                <CodiconDiscardIcon :size="14" />
              </button>
            </span>
          </div>
          <template v-if="!isSectionCollapsed('staged')">
          <div v-for="item in stagedFiles" :key="`staged-${item.path}`" class="diff-file" :data-path="item.path">
            <div
              class="diff-file-head"
              :class="{ 'is-selected': isStagedSelected(item.path) }"
              :title="isExpanded('staged', item.path) ? '折叠改动' : '展开改动'"
              @click="toggleExpand('staged', item.path)"
            >
              <span class="diff-lead">
                <span
                  class="diff-toggle-arrow"
                  :class="{ 'is-open': isExpanded('staged', item.path) }"
                  :title="isExpanded('staged', item.path) ? '折叠改动' : '展开改动'"
                >
                  <NIcon :component="ChevronForwardOutline" :size="13" />
                </span>
                <span class="diff-file-status" :class="`is-${sectionStatus(item, 'staged').toLowerCase()}`">{{ sectionStatus(item, 'staged') }}</span>
              </span>
              <button
                type="button"
                class="diff-file-link"
                :title="`在文件区打开 ${item.path}`"
                @click.stop="emit('openFile', item.path)"
              >
                <span v-if="pathDirname(item.path)" class="diff-file-dir">{{ pathDirname(item.path) }}/</span>{{ pathBasename(item.path) }}
              </button>
              <span class="diff-file-counts">
                <span v-if="sectionCounts(item, 'staged').deleted > 0" class="count-del">-{{ sectionCounts(item, 'staged').deleted }}</span>
                <span v-if="sectionCounts(item, 'staged').added > 0" class="count-add">+{{ sectionCounts(item, 'staged').added }}</span>
              </span>
              <span class="diff-row-actions">
                <button
                  type="button"
                  class="diff-action-btn"
                  :disabled="!!singleBusyPath"
                  title="撤回暂存（保留工作区改动）"
                  @click.stop="unstageOne(item)"
                >
                  <CodiconDiscardIcon :size="14" />
                </button>
                <input
                  type="checkbox"
                  class="diff-select-check"
                  :checked="isStagedSelected(item.path)"
                  :title="`选中 ${item.path}`"
                  @click.stop
                  @change="toggleStagedSelected(item.path)"
                />
              </span>
            </div>
            <div v-if="isExpanded('staged', item.path)" class="diff-file-body">
              <p v-if="detailLoading[item.path]" class="diff-pane-hint">加载中...</p>
              <p v-else-if="detailError[item.path]" class="diff-pane-hint is-error">
                <NIcon :component="WarningOutline" :size="13" /> {{ detailError[item.path] }}
              </p>
              <p v-else-if="!sectionLines(item, 'staged') || sectionLines(item, 'staged')!.length === 0" class="diff-pane-hint">无改动内容</p>
              <SandboxDiffView
                v-else
                :lines="sectionLines(item, 'staged')!"
                :lang="null"
                selectable
                @add-selection="(payload) => onAddSelection(item.path, payload)"
              />
            </div>
          </div>
          </template>
        </div>

        <!-- 未暂存区 -->
        <div v-if="unstagedFiles.length" class="diff-section">
          <div
            class="diff-section-head"
            :class="{ 'is-collapsed': isSectionCollapsed('unstaged') }"
            :title="isSectionCollapsed('unstaged') ? '展开未暂存' : '折叠未暂存'"
            @click="toggleSection('unstaged')"
          >
            <span class="diff-section-lead">
              <span class="diff-section-toggle-arrow" :class="{ 'is-open': !isSectionCollapsed('unstaged') }">
                <NIcon :component="ChevronForwardOutline" :size="13" />
              </span>
              <NIcon :component="CreateOutline" :size="13" class="diff-section-icon" />
            </span>
            <span class="diff-section-title">未暂存</span>
            <span class="diff-section-actions" @click.stop>
              <label v-if="selectedCount > 0" class="diff-select-all" :title="selectedCount === unstagedFiles.length ? '取消全选' : '全选'">
                <input type="checkbox" :checked="selectedCount === unstagedFiles.length" @change="toggleAll" />
                <span class="diff-select-all-label">已选 {{ selectedCount }}</span>
              </label>
              <button
                type="button"
                class="diff-action-btn"
                :disabled="batchReverting || unstagedFiles.length === 0"
                title="回退全部未暂存改动"
                @click="requestBatchRevert"
              >
                <CodiconDiscardIcon :size="14" />
              </button>
              <button
                type="button"
                class="diff-action-btn"
                :disabled="batchStaging || unstagedFiles.length === 0"
                title="暂存全部未暂存改动"
                @click="stageBulk"
              >
                <NIcon :component="AddOutline" :size="14" />
              </button>
            </span>
          </div>
          <!-- 批量回退就地二次确认（复用确认卡片 + HITL 按钮样式，替代全局弹窗） -->
          <template v-if="!isSectionCollapsed('unstaged')">
          <div v-if="confirmingBatchRevert" class="diff-inline-confirm">
            <span class="diff-inline-confirm-text">
              回退 {{ bulkTargets.length }} 个文件到 HEAD{{ hasUntrackedSelected ? '（未跟踪文件将被删除）' : '' }}，不可恢复
            </span>
            <div class="diff-inline-confirm-actions">
              <button
                type="button"
                class="diff-inline-btn is-ghost"
                :disabled="batchReverting"
                @click="cancelBatchRevert"
              >
                取消
              </button>
              <button
                type="button"
                class="diff-inline-btn is-primary"
                :disabled="batchReverting"
                @click="confirmBatchRevert"
              >
                {{ batchReverting ? '回退中…' : '确认回退' }}
              </button>
            </div>
          </div>
          <div v-for="item in unstagedFiles" :key="`unstaged-${item.path}`" class="diff-file" :data-path="item.path">
            <div
              class="diff-file-head"
              :class="{ 'is-selected': isSelected(item.path) }"
              :title="isExpanded('unstaged', item.path) ? '折叠改动' : '展开改动'"
              @click="toggleExpand('unstaged', item.path)"
            >
              <span class="diff-lead">
                <span
                  class="diff-toggle-arrow"
                  :class="{ 'is-open': isExpanded('unstaged', item.path) }"
                  :title="isExpanded('unstaged', item.path) ? '折叠改动' : '展开改动'"
                >
                  <NIcon :component="ChevronForwardOutline" :size="13" />
                </span>
                <span class="diff-file-status" :class="`is-${sectionStatus(item, 'unstaged').toLowerCase()}`">{{ sectionStatus(item, 'unstaged') }}</span>
              </span>
              <button
                type="button"
                class="diff-file-link"
                :title="`在文件区打开 ${item.path}`"
                @click.stop="emit('openFile', item.path)"
              >
                <span v-if="pathDirname(item.path)" class="diff-file-dir">{{ pathDirname(item.path) }}/</span>{{ pathBasename(item.path) }}
              </button>
              <span class="diff-file-counts">
                <span v-if="sectionCounts(item, 'unstaged').deleted > 0" class="count-del">-{{ sectionCounts(item, 'unstaged').deleted }}</span>
                <span v-if="sectionCounts(item, 'unstaged').added > 0" class="count-add">+{{ sectionCounts(item, 'unstaged').added }}</span>
              </span>
              <span class="diff-row-actions">
                <button
                  type="button"
                  class="diff-action-btn"
                  :disabled="!!singleBusyPath"
                  title="回退此文件到 HEAD"
                  @click.stop="requestSingleRevert(item)"
                >
                  <CodiconDiscardIcon :size="14" />
                </button>
                <button
                  type="button"
                  class="diff-action-btn"
                  :disabled="!!singleBusyPath"
                  title="暂存此文件"
                  @click.stop="stageOne(item)"
                >
                  <NIcon :component="AddOutline" :size="14" />
                </button>
                <input
                  type="checkbox"
                  class="diff-select-check"
                  :checked="isSelected(item.path)"
                  :title="`选中 ${item.path}`"
                  @click.stop
                  @change="toggleSelected(item.path)"
                />
              </span>
            </div>
            <!-- 单文件回退就地二次确认（复用确认卡片 + HITL 按钮样式，替代全局弹窗） -->
            <div v-if="confirmingRevertPath === item.path" class="diff-inline-confirm">
              <span class="diff-inline-confirm-text">
                回退 {{ pathBasename(item.path) }} 到 HEAD{{ item.status === '?' ? '（未跟踪文件将被删除）' : '' }}，不可恢复
              </span>
              <div class="diff-inline-confirm-actions">
                <button
                  type="button"
                  class="diff-inline-btn is-ghost"
                  :disabled="!!singleBusyPath"
                  @click="cancelSingleRevert"
                >
                  取消
                </button>
                <button
                  type="button"
                  class="diff-inline-btn is-primary"
                  :disabled="!!singleBusyPath"
                  @click="confirmSingleRevert(item)"
                >
                  {{ singleBusyPath === item.path ? '回退中…' : '确认回退' }}
                </button>
              </div>
            </div>
            <div v-if="isExpanded('unstaged', item.path)" class="diff-file-body">
              <p v-if="detailLoading[item.path]" class="diff-pane-hint">加载中...</p>
              <p v-else-if="detailError[item.path]" class="diff-pane-hint is-error">
                <NIcon :component="WarningOutline" :size="13" /> {{ detailError[item.path] }}
              </p>
              <p v-else-if="!sectionLines(item, 'unstaged') || sectionLines(item, 'unstaged')!.length === 0" class="diff-pane-hint">无改动内容</p>
              <SandboxDiffView
                v-else
                :lines="sectionLines(item, 'unstaged')!"
                :lang="null"
                selectable
                @add-selection="(payload) => onAddSelection(item.path, payload)"
              />
            </div>
          </div>
          </template>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.sandbox-diff-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  flex: 1 1 auto;
  background: var(--sun-black);
}

/* 操作错误行：commit 栏上方单独一行 */
.diff-op-error {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  font-size: var(--sun-font-sm);
  color: #c44;
  border-bottom: 1px solid var(--sun-border);
  background: color-mix(in srgb, #c44 8%, transparent);
  flex-shrink: 0;
}

/* commit 栏 */
.diff-commit-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px;
  border-bottom: 1px solid var(--sun-border);
  flex-shrink: 0;
}

.diff-commit-input {
  flex: 1;
  min-width: 0;
  height: 26px;
  padding: 0 8px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-sm, 6px);
  background: var(--sun-black);
  color: var(--sun-text);
  font-size: var(--sun-font-sm);
  font-family: inherit;
  outline: none;
}

.diff-commit-input::placeholder {
  color: var(--sun-text-muted);
}

.diff-commit-input:focus {
  border-color: var(--sun-accent);
}

.diff-commit-btn {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  min-width: 64px;
  height: 26px;
  padding: 0 14px;
  border: 1px solid var(--sun-accent);
  border-radius: var(--radius-sm, 6px);
  background: var(--sun-accent);
  color: var(--btn-primary-text, #212121);
  font-size: var(--sun-font-sm);
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  white-space: nowrap;
  transition: border-color 0.15s, background 0.15s;
}

.diff-commit-btn:hover:not(:disabled) {
  background: var(--sun-accent-hover);
  border-color: var(--sun-accent-hover);
}

/* 提交按钮内 loading 转圈 */
.diff-btn-spinner {
  display: inline-block;
  width: 12px;
  height: 12px;
  border: 2px solid currentColor;
  border-top-color: transparent;
  border-radius: 50%;
  animation: diff-btn-spin 0.7s linear infinite;
}

@keyframes diff-btn-spin {
  to {
    transform: rotate(360deg);
  }
}

.diff-commit-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 分区 */
.diff-section {
  border-bottom: 1px solid var(--sun-border);
}

.diff-section:last-child {
  border-bottom: none;
}

.diff-section-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  background: color-mix(in srgb, var(--sun-text-muted) 6%, transparent);
  flex-shrink: 0;
  cursor: pointer;
  user-select: none;
}

.diff-section-head:hover {
  background: color-mix(in srgb, var(--sun-text-muted) 10%, transparent);
}

/* 图标位：箭头与图标重叠，hover / 展开态箭头替换图标 */
.diff-section-lead {
  position: relative;
  flex-shrink: 0;
  width: 16px;
  height: 16px;
}

.diff-section-toggle-arrow,
.diff-section-icon {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.diff-section-toggle-arrow {
  color: var(--sun-text-muted);
  opacity: 0;
  transition: transform 0.15s ease, opacity 0.12s ease;
}

.diff-section-toggle-arrow.is-open {
  transform: rotate(90deg);
  opacity: 1;
}

.diff-section-icon {
  color: var(--sun-text-muted);
  opacity: 0.85;
  transition: opacity 0.12s ease;
}

.diff-section-head:hover .diff-section-toggle-arrow {
  opacity: 1;
}

.diff-section-head:hover .diff-section-icon {
  opacity: 0;
}

.diff-section-toggle-arrow.is-open ~ .diff-section-icon {
  opacity: 0;
}

.diff-section-title {
  font-size: var(--sun-font-xs);
  color: var(--sun-text-secondary);
  font-weight: 500;
  letter-spacing: 0.03em;
}

.diff-section-actions {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.diff-select-all {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--sun-text-secondary);
  cursor: pointer;
  min-width: 0;
  font-size: var(--sun-font-xs);
}

.diff-select-all input[type='checkbox'],
.diff-select-check {
  accent-color: var(--sun-blue, #58a6ff);
  cursor: pointer;
  flex-shrink: 0;
}

.diff-select-all-label {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.diff-summary {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  /* 空态 / 加载态整体上下左右居中 */
  display: flex;
  flex-direction: column;
}

/* 加载中 / 暂无改动：铺满剩余空间居中，不使用原生空态图标 */
.diff-loading {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 120px;
  color: var(--sun-text-muted);
  font-size: var(--sun-font-sm);
}

.diff-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 120px;
}

.diff-empty-text {
  color: var(--sun-text-muted);
  font-size: var(--sun-font-sm);
}

.diff-file {
  border-bottom: 1px solid var(--sun-border);
}

.diff-file:last-child {
  border-bottom: none;
}

.diff-file-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 5px 8px 5px 4px;
  color: var(--sun-text);
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace);
  font-size: var(--sun-font-sm);
  text-align: left;
  cursor: pointer;
}

.diff-file-head:hover {
  background: color-mix(in srgb, var(--sun-text-muted) 8%, transparent);
}

.diff-file-head.is-selected {
  background: color-mix(in srgb, var(--sun-blue, #58a6ff) 8%, transparent);
}

/* 最左侧图标位：状态徽章与展开箭头重叠，hover 行时箭头替换徽章 */
.diff-lead {
  position: relative;
  flex-shrink: 0;
  width: 16px;
  height: 16px;
}

.diff-toggle-arrow,
.diff-file-status {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.diff-file-status {
  width: 16px;
  height: 16px;
  font-size: 10px;
  font-weight: 700;
  border-radius: 3px;
  border: 1px solid var(--sun-border-light);
  color: var(--sun-text-secondary);
  transition: opacity 0.12s ease;
}

.diff-toggle-arrow {
  color: var(--sun-text-muted);
  opacity: 0;
  transition: transform 0.15s ease, opacity 0.12s ease;
  cursor: pointer;
}

.diff-toggle-arrow.is-open {
  transform: rotate(90deg);
  opacity: 1;
}

/* hover 行：箭头替换状态徽章（箭头常显、徽章隐去） */
.diff-file-head:hover .diff-toggle-arrow {
  opacity: 1;
}

.diff-file-head:hover .diff-toggle-arrow ~ .diff-file-status {
  opacity: 0;
}

/* 展开态：箭头常显，状态徽章一直隐藏（图标位为箭头） */
.diff-toggle-arrow.is-open ~ .diff-file-status {
  opacity: 0;
}

.diff-file-status.is-m {
  color: #d29922;
  border-color: rgba(210, 153, 34, 0.5);
}

.diff-file-status.is-a {
  color: #2a9a5c;
  border-color: rgba(42, 154, 92, 0.5);
}

.diff-file-status.is-d {
  color: #c44;
  border-color: rgba(204, 68, 68, 0.5);
}

.diff-file-status.is-r {
  color: #58a6ff;
  border-color: rgba(88, 166, 255, 0.5);
}

.diff-file-status.is-?,
.diff-file-status.is-u {
  color: var(--sun-text-muted);
  border-color: var(--sun-border-light);
}

.diff-file-link {
  /* 不占满剩余行宽（flex:1 会让点击行多数区域命中链接跳转，导致无法点击展开）；
     只占自身宽度，行内空白区域点击归属行（展开/折叠） */
  flex: 0 1 auto;
  max-width: 70%;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  border: none;
  background: transparent;
  padding: 0;
  color: var(--sun-blue, #58a6ff);
  font-family: inherit;
  font-size: inherit;
  text-align: left;
  cursor: pointer;
}

.diff-file-link:hover {
  text-decoration: underline;
}

.diff-file-dir {
  color: var(--sun-text-muted);
  opacity: 0.8;
}

.diff-file-counts {
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

/* 行尾操作组：撤回/回退/暂存/勾选，整体推至最右侧贴边 */
.diff-row-actions {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}

.diff-action-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 20px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  flex-shrink: 0;
  border-radius: 4px;
}

.diff-action-btn:hover:not(:disabled) {
  color: var(--sun-text);
  background: color-mix(in srgb, var(--sun-text-muted) 14%, transparent);
}

.diff-action-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

/* 回退按钮 hover 呈红色警示 */
.diff-row-actions .diff-action-btn[title^='回退']:hover:not(:disabled),
.diff-section-actions .diff-action-btn[title^='回退']:hover:not(:disabled) {
  color: #c44;
  background: color-mix(in srgb, #c44 12%, transparent);
}

.diff-select-check {
  flex-shrink: 0;
}

.diff-file-body {
  border-top: 1px dashed var(--sun-border);
  padding: 2px 0 8px;
  /* 展开的变更行最大高度：超过后行内滚动（配合变更行惰性渲染） */
  max-height: 560px;
  overflow-y: auto;
}

.diff-pane-hint {
  margin: 10px 12px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--sun-font-sm);
  color: var(--sun-text-muted);
}

.diff-pane-hint.is-error {
  color: #c44;
}

.diff-summary-error {
  margin: 12px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--sun-font-sm);
  color: #c44;
}

/* 就地二次确认卡片：复用 CollapsibleConfirmPanel confirm-card 视觉 + HITL 确认按钮样式 */
.diff-inline-confirm {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 2px 8px 8px;
  padding: 8px 10px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-sm, 6px);
  background: var(--sun-black);
  font-size: var(--sun-font-sm, 12px);
  color: var(--sun-text-muted);
}

.diff-inline-confirm-text {
  flex: 1;
  min-width: 0;
  line-height: 1.4;
}

.diff-inline-confirm-actions {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.diff-inline-btn {
  height: 26px;
  padding: 0 12px;
  border-radius: var(--radius-sm, 6px);
  font-size: var(--sun-font-sm, 12px);
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  white-space: nowrap;
  transition: border-color 0.15s, color 0.15s, background 0.15s;
}

.diff-inline-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.diff-inline-btn.is-ghost {
  border: 1px solid var(--sun-border);
  background: transparent;
  color: var(--sun-text-secondary);
}

.diff-inline-btn.is-ghost:hover:not(:disabled) {
  border-color: var(--sun-border-light);
  color: var(--sun-text);
}

.diff-inline-btn.is-primary {
  border: 1px solid var(--sun-accent);
  background: var(--sun-accent);
  color: var(--btn-primary-text, #212121);
}

.diff-inline-btn.is-primary:hover:not(:disabled) {
  background: var(--sun-accent-hover);
  border-color: var(--sun-accent-hover);
}
</style>
