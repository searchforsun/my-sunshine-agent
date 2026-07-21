<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import {
  NButton,
  NDropdown,
  NEmpty,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NInputNumber,
  NModal,
  NProgress,
  NSelect,
  NSpace,
  NSpin,
  NTag,
  NText,
  useMessage,
  type DropdownOption,
} from 'naive-ui'
import CopyToggleIcon from '../icons/CopyToggleIcon.vue'
import {
  CloudUploadOutline,
  CreateOutline,
  EllipsisHorizontal,
  TrashOutline,
} from '@vicons/ionicons5'
import {
  deleteDocument,
  forkDocumentVersion,
  getDocument,
  getDocumentContent,
  listChunks,
  previewChunks,
  publishDocument,
  saveDocumentContent,
  updateDocument,
  uploadDocumentFile,
  getDocumentParseJob,
  confirmDocumentParseJob,
  type ChunkPreview,
  type ChunkPreviewItem,
  type ChunkStrategy,
  type DocumentDetail,
  type DocumentParseJobStatus,
} from '../../api/ragAdmin'
import type { TenantId } from '../../api/tenants'
import { friendlyErrorMessage } from '../../api/apiError'
import { formatDocumentVersionKey, formatSkillVersionTime } from '../../utils/formatSkillVersionTime'
import { DOC_PARSING_PLACEHOLDER, isDocPlaceholder, resolveDocSourceType } from '../../utils/docSourceTypes'
import { useKbWorkbenchContext, useKbPanelLoad } from '../../composables/useKbWorkbenchContext'
import StaticMarkdown from '../StaticMarkdown.vue'

const props = defineProps<{
  tenantId: TenantId
  kbId: string | null
  docId: string | null
}>()

const wb = useKbWorkbenchContext()
const panelLoad = useKbPanelLoad(wb.revision)
const message = useMessage()

const emit = defineEmits<{
  refreshed: []
  deleted: []
}>()

type DocPhase = 'setup' | 'draft' | 'live' | 'history'
type ViewTab = 'source' | 'milvus' | 'es'

type ChunkParamField = { key: string; label: string; min?: number; max?: number; step?: number }

const STRATEGY_LABELS: Record<ChunkStrategy, string> = {
  markdown: 'Markdown 结构',
  fixed: '定长切分',
  recursive: '递归分隔',
  semantic: '语义边界',
  parent_child: '父子块',
}

const STRATEGY_DEFAULTS: Record<ChunkStrategy, Record<string, number>> = {
  markdown: { maxSize: 1200 },
  fixed: { maxSize: 800, overlap: 100 },
  recursive: { maxSize: 1000, overlap: 80 },
  semantic: { maxSize: 1200, similarityThreshold: 0.55, minChunkSize: 200 },
  parent_child: { parentSize: 2000, childSize: 400, childOverlap: 50 },
}

const STRATEGY_PARAM_FIELDS: Record<ChunkStrategy, ChunkParamField[]> = {
  markdown: [{ key: 'maxSize', label: '最大字符', min: 256, max: 4096 }],
  fixed: [
    { key: 'maxSize', label: '最大字符', min: 256, max: 4096 },
    { key: 'overlap', label: '重叠字符', min: 0, max: 512 },
  ],
  recursive: [
    { key: 'maxSize', label: '最大字符', min: 256, max: 4096 },
    { key: 'overlap', label: '重叠字符', min: 0, max: 512 },
  ],
  semantic: [
    { key: 'maxSize', label: '最大字符', min: 256, max: 4096 },
    { key: 'similarityThreshold', label: '相似度阈值', min: 0, max: 1, step: 0.01 },
    { key: 'minChunkSize', label: '最小块字符', min: 64, max: 2048 },
  ],
  parent_child: [
    { key: 'parentSize', label: '父块大小', min: 512, max: 8192 },
    { key: 'childSize', label: '子块大小', min: 128, max: 2048 },
    { key: 'childOverlap', label: '子块重叠', min: 0, max: 512 },
  ],
}

function defaultParamsForStrategy(strategy: ChunkStrategy): Record<string, number> {
  return { ...STRATEGY_DEFAULTS[strategy] }
}

const detail = ref<DocumentDetail | null>(null)
const loadingDetail = ref(false)
const selectedVersion = ref<string | null>(null)
const viewTab = ref<ViewTab>('source')
const sourceContent = ref('')
const loadingSource = ref(false)
const savingSource = ref(false)
const uploading = ref(false)
const parseProgress = ref<{ pct: number; page: number | null; total: number | null; status: string } | null>(null)
const publishing = ref(false)
const confirmingQuarantine = ref(false)
const forking = ref(false)
const renaming = ref(false)
const deleting = ref(false)
const showRename = ref(false)
const showDeleteConfirm = ref(false)
const renameForm = ref('')
const milvusChunks = ref<ChunkPreview[]>([])
const esChunks = ref<ChunkPreview[]>([])
const loadingChunks = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)
const sourceEditing = ref(true)
const chunkStrategy = ref<ChunkStrategy>('markdown')
const chunkParams = ref<Record<string, number>>(defaultParamsForStrategy('markdown'))
const previewId = ref<string | null>(null)
const previewConfirmed = ref(false)
const previewExpiresAt = ref<string | null>(null)
const draftPreviewChunks = ref<ChunkPreviewItem[]>([])
const previewing = ref(false)
/** 批量 reload 时避免 selectedVersion watch 与显式 loadSource 竞态 abort */
let suppressVersionWatch = false

function resetPreviewState() {
  previewId.value = null
  previewConfirmed.value = false
  previewExpiresAt.value = null
  draftPreviewChunks.value = []
}

const strategyOptions = computed(() =>
  (Object.keys(STRATEGY_LABELS) as ChunkStrategy[]).map((value) => ({
    label: STRATEGY_LABELS[value],
    value,
  })),
)

const activeParamFields = computed(() => STRATEGY_PARAM_FIELDS[chunkStrategy.value])

const publishedChunkStrategyLabel = computed(() => {
  const strategy = selectedVersionMeta.value?.chunkStrategy
  if (!strategy) {
    if (docPhase.value === 'live' || docPhase.value === 'history') return '（历史）markdown'
    return null
  }
  return STRATEGY_LABELS[strategy as ChunkStrategy] ?? strategy
})

const isDraftWritable = computed(() => docPhase.value === 'draft' || docPhase.value === 'setup')

const sourceTypeOption = computed(() => resolveDocSourceType(detail.value?.sourceType))

const canInlineEdit = computed(() => sourceTypeOption.value.inlineEditable)

const uploadAccept = computed(() => sourceTypeOption.value.accept)

const isParsing = computed(() =>
  parseProgress.value != null
  || sourceContent.value.trim() === DOC_PARSING_PLACEHOLDER,
)

const hasSourcePreview = computed(() => {
  const text = sourceContent.value.trim()
  if (!text || text === DOC_PARSING_PLACEHOLDER) return false
  return !isDocPlaceholder(sourceContent.value, sourceTypeOption.value)
})

const canPublishDraft = computed(() =>
  isDraftWritable.value
  && hasSourcePreview.value
  && !isParsing.value
  && !needsQuarantineConfirm.value
  && previewConfirmed.value
  && previewId.value != null,
)

const needsQuarantineConfirm = computed(
  () => selectedVersionMeta.value?.needsQuarantineConfirm === true,
)

const quarantineJobId = computed(
  () => selectedVersionMeta.value?.ingestJobId ?? null,
)

const parseProgressText = computed(() => {
  if (!parseProgress.value) return '解析中…'
  const { page, total, pct, status } = parseProgress.value
  if (status === 'queued') return '排队中…'
  if (page != null && total != null && total > 0) {
    return `解析中 ${page}/${total} 页（${Math.round(pct)}%）`
  }
  return `解析中（${Math.round(pct)}%）`
})

const selectedVersionMeta = computed(() =>
  detail.value?.versions.find((v) => v.version === selectedVersion.value) ?? null,
)

const docPhase = computed((): DocPhase => {
  const ver = selectedVersionMeta.value
  if (!ver) return 'setup'
  if (ver.status === 'draft') return ver.hasContent ? 'draft' : 'setup'
  if (ver.status === 'active') return 'live'
  return 'history'
})

const hasContentDraft = computed(() =>
  (detail.value?.versions ?? []).some((v) => v.status === 'draft' && v.hasContent),
)

const versionOptions = computed(() =>
  (detail.value?.versions ?? []).map((v) => ({
    label: formatDocumentVersionKey(v.version),
    value: v.version,
  })),
)

const activeChunks = computed(() => (viewTab.value === 'es' ? esChunks.value : milvusChunks.value))

const chunkEmptyHint = computed(() => {
  const ver = selectedVersionMeta.value
  if (ver?.status === 'draft') {
    return '草稿未发布，请点击「发布生效」写入 Milvus/ES'
  }
  if (viewTab.value === 'es') {
    return 'ES 中无 chunk（可能 ES 未启用或索引失败）'
  }
  return 'Milvus 中无 chunk'
})

const moreOptions = computed((): DropdownOption[] => {
  const items: DropdownOption[] = [
    {
      label: '重命名',
      key: 'rename',
      icon: () => h(NIcon, { component: CreateOutline, size: 14 }),
    },
  ]
  if ((docPhase.value === 'live' || docPhase.value === 'history') && !hasContentDraft.value) {
    items.push({
      label: '复制为草稿',
      key: 'fork',
      icon: () => h(CopyToggleIcon, { size: 14 }),
      disabled: forking.value,
    })
  }
  if (docPhase.value === 'live' || docPhase.value === 'history') {
    items.push({
      label: '上传新版本',
      key: 'upload',
      icon: () => h(NIcon, { component: CloudUploadOutline, size: 14 }),
      disabled: hasContentDraft.value,
    })
  }
  items.push({ type: 'divider', key: 'divider-delete' })
  items.push({
    label: () => h('span', { class: 'more-menu-delete' }, '删除文档'),
    key: 'delete',
    icon: () => h(NIcon, { component: TrashOutline, size: 14, class: 'more-menu-delete' }),
  })
  return items
})

function versionStatusTag(status: string): { label: string; type: 'success' | 'warning' | 'default' } {
  if (status === 'active') return { label: '生效', type: 'success' }
  if (status === 'draft') return { label: '草稿', type: 'warning' }
  return { label: '历史', type: 'default' }
}

async function loadDetail(signal: AbortSignal) {
  if (!props.kbId || !props.docId) {
    detail.value = null
    return
  }
  loadingDetail.value = true
  try {
    detail.value = await getDocument(props.tenantId, props.kbId, props.docId)
    if (signal.aborted) return
    const draft = detail.value.versions.find((v) => v.status === 'draft')
    const active = detail.value.versions.find((v) => v.status === 'active')
    selectedVersion.value =
      draft?.version ?? active?.version ?? detail.value.versions[0]?.version ?? null
  } catch (e) {
    if (signal.aborted) return
    detail.value = null
    message.error(friendlyErrorMessage(e, '加载文档失败'))
  } finally {
    loadingDetail.value = false
  }
}

async function loadSource(signal: AbortSignal) {
  if (!props.kbId || !props.docId || selectedVersion.value == null) {
    sourceContent.value = ''
    return
  }
  loadingSource.value = true
  try {
    const view = await getDocumentContent(
      props.tenantId,
      props.kbId,
      props.docId,
      selectedVersion.value,
    )
    if (signal.aborted) return
    sourceContent.value = view.content
    applySourceEditingDefault()
  } catch {
    if (signal.aborted) return
    sourceContent.value = ''
  } finally {
    loadingSource.value = false
  }
}

async function loadChunkStores(signal: AbortSignal) {
  if (!props.kbId || !props.docId || selectedVersion.value == null) {
    milvusChunks.value = []
    esChunks.value = []
    return
  }
  if (viewTab.value === 'source') return
  loadingChunks.value = true
  try {
    const store = viewTab.value === 'es' ? 'es' : 'milvus'
    const chunks = await listChunks(
      props.tenantId,
      props.kbId,
      props.docId,
      selectedVersion.value,
      store,
    )
    if (signal.aborted) return
    if (store === 'es') esChunks.value = chunks
    else milvusChunks.value = chunks
  } catch {
    if (signal.aborted) return
    if (viewTab.value === 'es') esChunks.value = []
    else milvusChunks.value = []
  } finally {
    loadingChunks.value = false
  }
}

function resetPanelState() {
  detail.value = null
  selectedVersion.value = null
  sourceContent.value = ''
  milvusChunks.value = []
  esChunks.value = []
  viewTab.value = 'source'
  sourceEditing.value = true
  chunkStrategy.value = 'markdown'
  chunkParams.value = defaultParamsForStrategy('markdown')
  resetPreviewState()
}

function applySourceEditingDefault() {
  if (!canInlineEdit.value) {
    sourceEditing.value = false
    return
  }
  if (docPhase.value === 'setup') {
    sourceEditing.value = true
    return
  }
  if (docPhase.value === 'draft') {
    sourceEditing.value = isDocPlaceholder(sourceContent.value, sourceTypeOption.value)
  }
}

async function reloadAll(preferredVersion?: string | null) {
  const signal = panelLoad.beginLoad()
  resetPanelState()
  suppressVersionWatch = true
  try {
    await loadDetail(signal)
    if (preferredVersion != null) {
      selectedVersion.value = preferredVersion
    }
    await loadSource(signal)
  } finally {
    suppressVersionWatch = false
  }
}

async function reloadOnDocChange() {
  const signal = panelLoad.beginLoad()
  milvusChunks.value = []
  esChunks.value = []
  suppressVersionWatch = true
  try {
    await loadDetail(signal)
    await loadSource(signal)
    if (viewTab.value !== 'source') {
      await loadChunkStores(signal)
    }
  } finally {
    suppressVersionWatch = false
  }
}

watch(() => wb.revision.value, () => { void reloadAll() })
onMounted(() => { void reloadAll() })
watch(() => props.docId, () => { void reloadOnDocChange() })

watch(selectedVersion, () => {
  if (suppressVersionWatch) return
  const signal = panelLoad.beginLoad()
  void loadSource(signal)
  if (viewTab.value !== 'source') void loadChunkStores(signal)
})

watch(viewTab, () => {
  const signal = panelLoad.beginLoad()
  if (viewTab.value === 'source') return
  void loadChunkStores(signal)
})

watch(chunkStrategy, (next) => {
  chunkParams.value = defaultParamsForStrategy(next)
  resetPreviewState()
})

watch(chunkParams, () => {
  resetPreviewState()
}, { deep: true })

function chunkLevelLabel(meta?: Record<string, unknown>): string | null {
  const level = meta?.level
  if (level === 'parent') return '父块'
  if (level === 'child') return '子块'
  return null
}

async function handlePreviewChunks() {
  if (!props.kbId || !props.docId || selectedVersion.value == null) return
  previewing.value = true
  try {
    const response = await previewChunks(props.tenantId, props.kbId, props.docId, {
      version: selectedVersion.value,
      strategy: chunkStrategy.value,
      params: chunkParams.value,
    })
    previewId.value = response.previewId
    previewExpiresAt.value = response.expiresAt
    draftPreviewChunks.value = response.chunks
    previewConfirmed.value = false
    message.success(`已生成 ${response.chunkCount} 个分块预览`)
  } catch (e) {
    message.error(friendlyErrorMessage(e, '预览分块失败'))
  } finally {
    previewing.value = false
  }
}

function handleConfirmPreview() {
  if (!previewId.value || draftPreviewChunks.value.length === 0) return
  previewConfirmed.value = true
  message.success('已确认预览，可以发布')
}

async function handlePublish() {
  const id = previewId.value
  if (!props.kbId || !props.docId || !canPublishDraft.value || !id) return
  publishing.value = true
  try {
    const result = await publishDocument(
      props.tenantId,
      props.kbId,
      props.docId,
      { previewId: id },
    )
    message.success(`已发布 ${formatDocumentVersionKey(result.version)}，${result.chunks} chunks`)
    sourceEditing.value = false
    resetPreviewState()
    emit('refreshed')
    await reloadAll(result.version)
  } catch (e) {
    message.error(friendlyErrorMessage(e, '发布失败'))
  } finally {
    publishing.value = false
  }
}

function pickFile() {
  fileInputRef.value?.click()
}

function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

async function pollParseJob(jobId: number, version: string) {
  while (true) {
    const st: DocumentParseJobStatus = await getDocumentParseJob(
      props.tenantId,
      props.kbId!,
      props.docId!,
      jobId,
    )
    parseProgress.value = {
      pct: st.progressPct ?? 0,
      page: st.progressPage,
      total: st.totalPages,
      status: st.status,
    }
    if (st.status === 'done' || st.status === 'preview' || st.status === 'quarantine') {
      parseProgress.value = null
      await reloadAll(version)
      if (st.needsConfirm) {
        message.warning('解析置信度偏低，请确认内容后再发布')
      } else {
        message.success('解析完成，请确认内容后点击「发布生效」')
      }
      emit('refreshed')
      return
    }
    if (st.status === 'failed') {
      throw new Error(st.errorMsg ?? '解析失败')
    }
    await sleep(1200)
  }
}

async function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || !props.kbId || !props.docId) return
  const opt = sourceTypeOption.value
  const lower = file.name.toLowerCase()
  const extensions = opt.accept.split(',').map((e) => e.trim().toLowerCase())
  const extOk = extensions.some((ext) => lower.endsWith(ext.startsWith('.') ? ext : `.${ext}`))
  if (!extOk) {
    message.error(`当前文档类型「${opt.label}」仅支持：${opt.uploadHint}`)
    return
  }
  uploading.value = true
  parseProgress.value = null
  const isAsyncType = opt.value === 'pdf' || opt.value === 'docx'
  const loadingMsg = isAsyncType ? '文件已上传，正在解析…' : '上传中…'
  const msgReactive = message.loading(loadingMsg, { duration: 0 })
  try {
    const result = await uploadDocumentFile(props.tenantId, props.kbId, props.docId, file)
    msgReactive.destroy()
    if (!result.async) {
      message.success(`已上传为草稿 ${formatDocumentVersionKey(result.version)}`)
      emit('refreshed')
      await reloadOnDocChange()
      selectedVersion.value = result.version
      return
    }
    parseProgress.value = { pct: result.progressPct ?? 0, page: null, total: null, status: result.status }
    selectedVersion.value = result.version
    sourceContent.value = DOC_PARSING_PLACEHOLDER
    await pollParseJob(result.jobId!, result.version)
  } catch (e) {
    msgReactive.destroy()
    parseProgress.value = null
    message.error(friendlyErrorMessage(e, '上传失败'))
    await reloadOnDocChange()
  } finally {
    uploading.value = false
  }
}

async function handleSaveSource() {
  if (!props.kbId || !props.docId || selectedVersion.value == null) return
  if (isDocPlaceholder(sourceContent.value, sourceTypeOption.value)) return
  savingSource.value = true
  try {
    await saveDocumentContent(
      props.tenantId,
      props.kbId,
      props.docId,
      selectedVersion.value,
      sourceContent.value,
    )
    message.success('草稿已保存')
    sourceEditing.value = false
    emit('refreshed')
    await loadDetail(panelLoad.beginLoad())
  } catch (e) {
    message.error(friendlyErrorMessage(e, '保存失败'))
  } finally {
    savingSource.value = false
  }
}

async function handleConfirmQuarantine() {
  if (!props.kbId || !props.docId || quarantineJobId.value == null) return
  confirmingQuarantine.value = true
  try {
    await confirmDocumentParseJob(
      props.tenantId,
      props.kbId,
      props.docId,
      quarantineJobId.value,
    )
    message.success('已确认解析内容，可以发布')
    emit('refreshed')
    await loadDetail(panelLoad.beginLoad())
  } catch (e) {
    message.error(friendlyErrorMessage(e, '确认失败'))
  } finally {
    confirmingQuarantine.value = false
  }
}

async function handleFork() {
  if (!props.kbId || !props.docId || selectedVersion.value == null) return
  forking.value = true
  try {
    const updated = await forkDocumentVersion(
      props.tenantId,
      props.kbId,
      props.docId,
      selectedVersion.value,
    )
    message.success('已复制为草稿')
    emit('refreshed')
    suppressVersionWatch = true
    try {
      detail.value = updated
      const draft = updated.versions.find((v) => v.status === 'draft')
      selectedVersion.value = draft?.version ?? selectedVersion.value
      await loadSource(panelLoad.beginLoad())
    } finally {
      suppressVersionWatch = false
    }
  } catch (e) {
    message.error(friendlyErrorMessage(e, '复制草稿失败'))
  } finally {
    forking.value = false
  }
}

function handleMoreSelect(key: string) {
  if (key === 'rename') {
    renameForm.value = detail.value?.displayName ?? ''
    showRename.value = true
    return
  }
  if (key === 'delete') {
    showDeleteConfirm.value = true
    return
  }
  if (key === 'publish') void handlePublish()
  else if (key === 'fork') void handleFork()
  else if (key === 'upload') pickFile()
}

async function handleRename() {
  const name = renameForm.value.trim()
  if (!props.kbId || !props.docId || !name) return
  renaming.value = true
  try {
    detail.value = await updateDocument(props.tenantId, props.kbId, props.docId, name)
    message.success('名称已更新')
    showRename.value = false
    emit('refreshed')
  } catch (e) {
    message.error(friendlyErrorMessage(e, '重命名失败'))
  } finally {
    renaming.value = false
  }
}

async function handleDelete() {
  if (!props.kbId || !props.docId) return
  deleting.value = true
  try {
    await deleteDocument(props.tenantId, props.kbId, props.docId)
    message.success('文档已删除')
    showDeleteConfirm.value = false
    emit('deleted')
  } catch (e) {
    message.error(friendlyErrorMessage(e, '删除失败'))
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <div class="doc-panel">
    <input
      ref="fileInputRef"
      type="file"
      :accept="uploadAccept"
      class="hidden-file"
      @change="handleFileChange"
    />
    <NSpin :show="loadingDetail" class="detail-spin">
      <div v-if="!docId" class="empty-wrap">
        <NEmpty description="未选择文档" />
      </div>
      <div v-else-if="detail" class="doc-detail">
        <header class="detail-head">
          <div class="detail-title-block">
            <h3>{{ detail.displayName }}</h3>
            <NText depth="3">{{ detail.docId }} · {{ sourceTypeOption.label }}</NText>
          </div>
          <NSpace align="center" :size="8" class="detail-actions">
            <NSelect
              v-model:value="selectedVersion"
              :options="versionOptions"
              size="small"
              class="version-select"
              :menu-props="{ class: 'doc-version-select-menu' }"
              placeholder="版本"
            />
            <NTag
              v-if="selectedVersionMeta"
              :bordered="false"
              size="small"
              :type="versionStatusTag(selectedVersionMeta.status).type"
            >
              {{ versionStatusTag(selectedVersionMeta.status).label }}
            </NTag>
            <NTag v-if="selectedVersionMeta" :bordered="false" size="small">
              {{ selectedVersionMeta.chunkCount }} chunks
            </NTag>
            <NTag v-if="publishedChunkStrategyLabel && !isDraftWritable" :bordered="false" size="small">
              {{ publishedChunkStrategyLabel }}
            </NTag>
            <NDropdown
              trigger="click"
              size="small"
              :options="moreOptions"
              @select="handleMoreSelect"
            >
              <NButton size="small" quaternary title="更多操作" aria-label="更多操作">
                <template #icon><NIcon :component="EllipsisHorizontal" :size="16" /></template>
              </NButton>
            </NDropdown>
          </NSpace>
        </header>

        <div class="view-switch">
          <button type="button" class="view-btn" :class="{ active: viewTab === 'source' }" @click="viewTab = 'source'">原始内容</button>
          <button type="button" class="view-btn" :class="{ active: viewTab === 'milvus' }" @click="viewTab = 'milvus'">Milvus 块</button>
          <button type="button" class="view-btn" :class="{ active: viewTab === 'es' }" @click="viewTab = 'es'">ES 块</button>
        </div>

        <div v-if="viewTab === 'source'" class="source-pane">
          <NSpin :show="loadingSource" class="source-spin">
            <template v-if="isDraftWritable">
              <template v-if="canInlineEdit && sourceEditing">
                <div class="source-body">
                  <NInput
                    v-model:value="sourceContent"
                    type="textarea"
                    :placeholder="sourceTypeOption.placeholder"
                    :autosize="{ minRows: 12, maxRows: 24 }"
                    class="kb-input source-editor"
                  />
                </div>
                <div class="source-footer">
                  <NButton size="small" :loading="uploading" @click="pickFile">上传文件</NButton>
                  <NButton
                    type="primary"
                    size="small"
                    class="action-btn"
                    :loading="savingSource"
                    :disabled="isDocPlaceholder(sourceContent, sourceTypeOption)"
                    @click="handleSaveSource"
                  >
                    保存草稿
                  </NButton>
                </div>
              </template>
              <template v-else-if="canInlineEdit && !sourceEditing">
                <div class="source-body">
                  <div v-if="isDocPlaceholder(sourceContent, sourceTypeOption)" class="empty-wrap">
                    <NEmpty size="small" description="草稿暂无内容" />
                  </div>
                  <div v-else-if="sourceTypeOption.markdownPreview" class="preview-scroll">
                    <StaticMarkdown :source="sourceContent" skill-preview />
                  </div>
                  <pre v-else class="plain-preview">{{ sourceContent }}</pre>
                </div>
                <div v-if="hasSourcePreview && !isParsing" class="chunk-gate">
                  <div class="chunk-gate-head">
                    <span class="chunk-gate-title">分块策略</span>
                    <NText v-if="previewExpiresAt" depth="3" class="chunk-gate-expires">
                      预览有效期至 {{ formatSkillVersionTime(previewExpiresAt) }}
                    </NText>
                  </div>
                  <div class="chunk-gate-form">
                    <NSelect
                      v-model:value="chunkStrategy"
                      :options="strategyOptions"
                      size="small"
                      class="strategy-select"
                    />
                    <div class="chunk-param-grid">
                      <label v-for="field in activeParamFields" :key="field.key" class="chunk-param-field">
                        <span>{{ field.label }}</span>
                        <NInputNumber
                          v-model:value="chunkParams[field.key]"
                          size="small"
                          :min="field.min"
                          :max="field.max"
                          :step="field.step ?? 1"
                          class="chunk-param-input"
                        />
                      </label>
                    </div>
                    <div class="chunk-gate-actions">
                      <NButton size="small" :loading="previewing" @click="handlePreviewChunks">
                        {{ chunkStrategy === 'semantic' && previewing ? '正在计算语义边界…' : '预览分块' }}
                      </NButton>
                      <NButton
                        size="small"
                        :disabled="!previewId || draftPreviewChunks.length === 0"
                        @click="handleConfirmPreview"
                      >
                        确认此预览
                      </NButton>
                      <NTag v-if="previewConfirmed" :bordered="false" size="small" type="success">已确认</NTag>
                    </div>
                  </div>
                  <div v-if="draftPreviewChunks.length > 0" class="chunk-preview-scroll">
                    <article v-for="item in draftPreviewChunks" :key="item.index" class="chunk-card">
                      <header class="chunk-head">
                        <NTag :bordered="false" size="tiny">#{{ item.index }}</NTag>
                        <NTag v-if="chunkLevelLabel(item.meta)" :bordered="false" size="tiny" type="info">
                          {{ chunkLevelLabel(item.meta) }}
                        </NTag>
                        <NText depth="3">{{ item.charCount }} 字</NText>
                      </header>
                      <div class="chunk-content">{{ item.text }}</div>
                    </article>
                  </div>
                </div>
                <div class="source-footer">
                  <NButton size="small" @click="sourceEditing = true">编辑草稿</NButton>
                  <NButton
                    type="primary"
                    size="small"
                    class="action-btn"
                    :loading="publishing"
                    :disabled="!canPublishDraft"
                    @click="handlePublish"
                  >
                    发布生效
                  </NButton>
                </div>
              </template>
              <template v-else>
                <div class="source-body" :class="{ 'upload-only': !hasSourcePreview }">
                  <template v-if="isParsing">
                    <div class="parse-progress-wrap">
                      <NProgress
                        type="line"
                        :percentage="parseProgress?.pct ?? 0"
                        :show-indicator="true"
                        processing
                      />
                      <p class="parse-progress-text">{{ parseProgressText }}</p>
                    </div>
                  </template>
                  <template v-else-if="hasSourcePreview">
                    <div v-if="sourceTypeOption.markdownPreview" class="preview-scroll">
                      <StaticMarkdown :source="sourceContent" skill-preview />
                    </div>
                    <pre v-else class="plain-preview">{{ sourceContent }}</pre>
                  </template>
                  <NEmpty v-else size="small" :description="sourceTypeOption.placeholder" />
                </div>
                <div v-if="hasSourcePreview && !isParsing" class="chunk-gate">
                  <div class="chunk-gate-head">
                    <span class="chunk-gate-title">分块策略</span>
                    <NText v-if="previewExpiresAt" depth="3" class="chunk-gate-expires">
                      预览有效期至 {{ formatSkillVersionTime(previewExpiresAt) }}
                    </NText>
                  </div>
                  <div class="chunk-gate-form">
                    <NSelect
                      v-model:value="chunkStrategy"
                      :options="strategyOptions"
                      size="small"
                      class="strategy-select"
                    />
                    <div class="chunk-param-grid">
                      <label v-for="field in activeParamFields" :key="field.key" class="chunk-param-field">
                        <span>{{ field.label }}</span>
                        <NInputNumber
                          v-model:value="chunkParams[field.key]"
                          size="small"
                          :min="field.min"
                          :max="field.max"
                          :step="field.step ?? 1"
                          class="chunk-param-input"
                        />
                      </label>
                    </div>
                    <div class="chunk-gate-actions">
                      <NButton size="small" :loading="previewing" @click="handlePreviewChunks">
                        {{ chunkStrategy === 'semantic' && previewing ? '正在计算语义边界…' : '预览分块' }}
                      </NButton>
                      <NButton
                        size="small"
                        :disabled="!previewId || draftPreviewChunks.length === 0"
                        @click="handleConfirmPreview"
                      >
                        确认此预览
                      </NButton>
                      <NTag v-if="previewConfirmed" :bordered="false" size="small" type="success">已确认</NTag>
                    </div>
                  </div>
                  <div v-if="draftPreviewChunks.length > 0" class="chunk-preview-scroll">
                    <article v-for="item in draftPreviewChunks" :key="item.index" class="chunk-card">
                      <header class="chunk-head">
                        <NTag :bordered="false" size="tiny">#{{ item.index }}</NTag>
                        <NTag v-if="chunkLevelLabel(item.meta)" :bordered="false" size="tiny" type="info">
                          {{ chunkLevelLabel(item.meta) }}
                        </NTag>
                        <NText depth="3">{{ item.charCount }} 字</NText>
                      </header>
                      <div class="chunk-content">{{ item.text }}</div>
                    </article>
                  </div>
                </div>
                <div class="source-footer">
                  <p v-if="needsQuarantineConfirm" class="quarantine-hint">
                    解析置信度偏低，请先确认内容再发布。
                  </p>
                  <NButton
                    v-if="needsQuarantineConfirm"
                    type="primary"
                    size="small"
                    class="action-btn"
                    :loading="confirmingQuarantine"
                    @click="handleConfirmQuarantine"
                  >
                    确认解析内容
                  </NButton>
                  <NButton
                    type="primary"
                    size="small"
                    class="action-btn"
                    :loading="publishing"
                    :disabled="!canPublishDraft"
                    @click="handlePublish"
                  >
                    发布生效
                  </NButton>
                  <NButton
                    size="small"
                    :type="hasSourcePreview ? 'default' : 'primary'"
                    :class="{ 'action-btn': !hasSourcePreview }"
                    :loading="uploading"
                    :disabled="isParsing"
                    @click="pickFile"
                  >
                    {{ hasSourcePreview ? '重新上传' : `上传 ${sourceTypeOption.label}` }}
                  </NButton>
                </div>
              </template>
            </template>
            <template v-else>
              <div class="source-body">
                <div v-if="!sourceContent.trim()" class="empty-wrap">
                  <NEmpty size="small" description="该版本无原文" />
                </div>
                <div v-else-if="sourceTypeOption.markdownPreview" class="preview-scroll">
                  <StaticMarkdown :source="sourceContent" skill-preview />
                </div>
                <pre v-else class="plain-preview">{{ sourceContent }}</pre>
              </div>
            </template>
          </NSpin>
        </div>

        <NSpin v-else :show="loadingChunks" class="chunk-spin">
          <div v-if="activeChunks.length === 0 && !loadingChunks" class="empty-wrap">
            <NEmpty size="small" :description="chunkEmptyHint" />
          </div>
          <div v-else class="chunk-scroll">
            <article v-for="chunk in activeChunks" :key="chunk.chunkIndex" class="chunk-card">
              <header class="chunk-head">
                <NTag :bordered="false" size="tiny">#{{ chunk.chunkIndex }}</NTag>
                <NText depth="3">{{ chunk.docName }}</NText>
              </header>
              <div class="chunk-content">{{ chunk.content }}</div>
            </article>
          </div>
        </NSpin>
      </div>
    </NSpin>
    <NModal v-model:show="showRename" preset="dialog" title="重命名文档" class="sunshine-dialog">
      <NForm label-placement="left" label-width="72">
        <NFormItem label="显示名称" required>
          <NInput v-model:value="renameForm" placeholder="文档显示名称" />
        </NFormItem>
      </NForm>
      <template #action>
        <NButton @click="showRename = false">取消</NButton>
        <NButton type="primary" class="action-btn" :loading="renaming" @click="handleRename">保存</NButton>
      </template>
    </NModal>
    <NModal v-model:show="showDeleteConfirm" preset="dialog" title="删除文档" class="sunshine-dialog">
      <p>确定删除「{{ detail?.displayName }}」？将同时清除所有版本与索引数据，此操作不可恢复。</p>
      <template #action>
        <NButton @click="showDeleteConfirm = false">取消</NButton>
        <NButton type="error" :loading="deleting" @click="handleDelete">删除</NButton>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.doc-panel {
  flex: 1;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-lg);
  padding: 12px;
  background: var(--sun-black);
}
.hidden-file { display: none; }
.detail-spin { flex: 1; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
.detail-spin :deep(.n-spin-container),
.detail-spin :deep(.n-spin-content) { flex: 1; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
.doc-detail { flex: 1; min-height: 0; display: flex; flex-direction: column; gap: 10px; overflow: hidden; }
.detail-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-shrink: 0; }
.detail-title-block h3 { margin: 0; font-size: 16px; font-weight: 600; color: var(--sun-text); }
.detail-actions { flex-shrink: 0; }
.version-select { width: min(228px, 44vw); min-width: 200px; }
.version-select :deep(.n-base-selection) {
  --n-color: var(--sun-black) !important;
  --n-color-active: var(--sun-black) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-hover: 1px solid var(--sun-border-light) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
}
.version-select :deep(.n-base-selection-label) {
  overflow: visible;
  text-overflow: clip;
}
.view-switch {
  display: inline-flex;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  overflow: hidden;
  flex-shrink: 0;
  align-self: flex-start;
}
.view-btn {
  border: none;
  background: transparent;
  color: var(--sun-text-secondary);
  font-size: 12px;
  padding: 5px 12px;
  cursor: pointer;
}
.view-btn + .view-btn { border-left: 1px solid var(--sun-border); }
.view-btn.active { color: var(--sun-text); font-weight: 600; }
.source-pane, .source-spin, .chunk-spin { flex: 1; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
.source-spin :deep(.n-spin-container),
.source-spin :deep(.n-spin-content),
.chunk-spin :deep(.n-spin-container),
.chunk-spin :deep(.n-spin-content) { flex: 1; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
.kb-input {
  --n-color: var(--sun-black) !important;
  --n-color-focus: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-placeholder-color: var(--sun-text-muted) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
}
.source-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  overflow: hidden;
  background: var(--sun-black);
}
.source-editor { flex: 1; min-height: 0; }
.source-editor :deep(.n-input__border),
.source-editor :deep(.n-input__state-border) { display: none; }
.source-editor :deep(.n-input-wrapper) { height: 100%; }
.source-editor :deep(.n-input__textarea-el) {
  min-height: 100%;
  padding: 12px 14px;
}
.source-footer { display: flex; align-items: center; justify-content: flex-end; gap: 8px; margin-top: 10px; flex-shrink: 0; flex-wrap: wrap; }
.chunk-gate {
  margin-top: 10px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  flex-shrink: 0;
}
.chunk-gate-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
.chunk-gate-title { font-size: 13px; font-weight: 600; color: var(--sun-text); }
.chunk-gate-expires { font-size: 12px; }
.chunk-gate-form { display: flex; flex-direction: column; gap: 10px; }
.strategy-select { width: min(220px, 100%); }
.chunk-param-grid { display: flex; flex-wrap: wrap; gap: 10px 16px; }
.chunk-param-field { display: flex; flex-direction: column; gap: 4px; min-width: 120px; font-size: 12px; color: var(--sun-text-secondary); }
.chunk-param-input { width: 140px; }
.chunk-gate-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.chunk-preview-scroll {
  max-height: 220px;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.quarantine-hint { width: 100%; margin: 0 0 4px; font-size: var(--sun-font-sm, 12px); color: var(--sun-text-secondary); text-align: right; }
.plain-preview {
  flex: 1;
  min-height: 0;
  margin: 0;
  padding: 12px 14px;
  overflow: auto;
  font-size: var(--sun-font-base, 14px);
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--sun-text);
  background: transparent;
  border: none;
}
.upload-only {
  min-height: 200px;
  justify-content: center;
  align-items: center;
}
.parse-progress-wrap {
  width: min(360px, 88%);
  padding: 8px 12px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
}
.parse-progress-wrap :deep(.n-progress) {
  width: 100%;
}
.parse-progress-text {
  margin: 0;
  font-size: 13px;
  color: var(--sun-text-secondary);
  text-align: center;
  width: 100%;
}
.preview-scroll { flex: 1; min-height: 0; overflow-y: auto; padding: 8px; }
.source-body .empty-wrap { min-height: 160px; padding: 24px 0; }
.chunk-scroll { flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 8px; }
.chunk-card { border: 1px solid var(--sun-border); border-radius: var(--radius-md); padding: 10px 12px; background: var(--sun-black); }
.chunk-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.chunk-content { font-size: var(--sun-font-base, 14px); line-height: 1.65; white-space: pre-wrap; word-break: break-word; color: var(--sun-text); }
.empty-wrap { padding: 32px 0; display: flex; align-items: center; justify-content: center; flex: 1; }
.action-btn {
  --n-color: var(--sun-accent) !important;
  --n-color-hover: var(--sun-accent-hover) !important;
  --n-color-pressed: var(--sun-accent-hover) !important;
  --n-color-focus: var(--sun-accent-hover) !important;
  --n-text-color: var(--btn-primary-text) !important;
  --n-text-color-hover: var(--btn-primary-text) !important;
  --n-text-color-pressed: var(--btn-primary-text) !important;
  --n-text-color-focus: var(--btn-primary-text) !important;
  --n-border: none !important;
}
:deep(.more-menu-delete) { color: var(--sun-danger, #d03050); }
</style>

<style>
.doc-version-select-menu.n-base-select-menu {
  min-width: 228px !important;
}
</style>
