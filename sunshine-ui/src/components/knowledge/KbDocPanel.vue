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
  NModal,
  NSelect,
  NSpace,
  NSpin,
  NTag,
  NText,
  useMessage,
  type DropdownOption,
} from 'naive-ui'
import {
  CloudUploadOutline,
  CopyOutline,
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
  publishDocument,
  saveDocumentContent,
  updateDocument,
  uploadDocumentMarkdown,
  type ChunkPreview,
  type DocumentDetail,
} from '../../api/ragAdmin'
import type { TenantId } from '../../api/tenants'
import { friendlyErrorMessage } from '../../api/apiError'
import { formatDocumentVersionKey } from '../../utils/formatSkillVersionTime'
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

const detail = ref<DocumentDetail | null>(null)
const loadingDetail = ref(false)
const selectedVersion = ref<string | null>(null)
const viewTab = ref<ViewTab>('source')
const sourceContent = ref('')
const loadingSource = ref(false)
const savingSource = ref(false)
const uploading = ref(false)
const publishing = ref(false)
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
/** 批量 reload 时避免 selectedVersion watch 与显式 loadSource 竞态 abort */
let suppressVersionWatch = false

const isDraftWritable = computed(() => docPhase.value === 'draft' || docPhase.value === 'setup')

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
      icon: () => h(NIcon, { component: CopyOutline, size: 14 }),
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
}

function applySourceEditingDefault() {
  if (docPhase.value === 'setup') {
    sourceEditing.value = true
    return
  }
  if (docPhase.value === 'draft') {
    sourceEditing.value = !sourceContent.value.trim()
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

function pickFile() {
  fileInputRef.value?.click()
}

async function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || !props.kbId || !props.docId) return
  if (!file.name.toLowerCase().endsWith('.md')) {
    message.error('仅支持 Markdown（.md）文件')
    return
  }
  uploading.value = true
  try {
    const view = await uploadDocumentMarkdown(props.tenantId, props.kbId, props.docId, file)
    message.success(`已上传为草稿 ${formatDocumentVersionKey(view.version)}`)
    emit('refreshed')
    await reloadOnDocChange()
    selectedVersion.value = view.version
  } catch (e) {
    message.error(friendlyErrorMessage(e, '上传失败'))
  } finally {
    uploading.value = false
  }
}

async function handleSaveSource() {
  if (!props.kbId || !props.docId || selectedVersion.value == null || !sourceContent.value.trim()) return
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

async function handlePublish() {
  if (!props.kbId || !props.docId || selectedVersion.value == null) return
  publishing.value = true
  try {
    const result = await publishDocument(
      props.tenantId,
      props.kbId,
      props.docId,
      selectedVersion.value,
    )
    message.success(`已发布 ${formatDocumentVersionKey(result.version)}，${result.chunks} chunks`)
    sourceEditing.value = false
    emit('refreshed')
    await reloadAll(result.version)
  } catch (e) {
    message.error(friendlyErrorMessage(e, '发布失败'))
  } finally {
    publishing.value = false
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
      accept=".md,text/markdown"
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
            <NText depth="3">{{ detail.docId }}</NText>
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
              <template v-if="sourceEditing">
                <div class="source-body">
                  <NInput
                    v-model:value="sourceContent"
                    type="textarea"
                    placeholder="请上传 Markdown 文件（.md）或直接编写内容…"
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
                    :disabled="!sourceContent.trim()"
                    @click="handleSaveSource"
                  >
                    保存草稿
                  </NButton>
                </div>
              </template>
              <template v-else>
                <div class="source-body">
                  <div v-if="!sourceContent.trim()" class="empty-wrap">
                    <NEmpty size="small" description="草稿暂无内容" />
                  </div>
                  <div v-else class="preview-scroll">
                    <StaticMarkdown :source="sourceContent" skill-preview />
                  </div>
                </div>
                <div class="source-footer">
                  <NButton size="small" @click="sourceEditing = true">编辑草稿</NButton>
                  <NButton
                    type="primary"
                    size="small"
                    class="action-btn"
                    :loading="publishing"
                    :disabled="!sourceContent.trim()"
                    @click="handlePublish"
                  >
                    发布生效
                  </NButton>
                </div>
              </template>
            </template>
            <template v-else>
              <div class="source-body">
                <div v-if="!sourceContent.trim()" class="empty-wrap">
                  <NEmpty size="small" description="该版本无原文" />
                </div>
                <div v-else class="preview-scroll">
                  <StaticMarkdown :source="sourceContent" skill-preview />
                </div>
              </div>
            </template>
          </NSpin>
        </div>

        <NSpin v-else :show="loadingChunks" class="chunk-spin">
          <div v-if="activeChunks.length === 0 && !loadingChunks" class="empty-wrap">
            <NEmpty size="small" :description="viewTab === 'es' ? 'ES 中无 chunk（可能未发布或 ES 未启用）' : 'Milvus 中无 chunk'" />
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
.source-footer { display: flex; justify-content: flex-end; gap: 8px; margin-top: 10px; flex-shrink: 0; }
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
