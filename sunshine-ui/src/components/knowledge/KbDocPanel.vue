<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  NButton,
  NCard,
  NEmpty,
  NInput,
  NResult,
  NSelect,
  NSpace,
  NSpin,
  NTag,
  NText,
} from 'naive-ui'
import {
  getDocument,
  ingestText,
  listChunks,
  type ChunkPreview,
  type DocumentDetail,
} from '../../api/ragAdmin'
import type { TenantId } from '../../api/tenants'
import { friendlyErrorMessage } from '../../api/apiError'

const props = defineProps<{
  tenantId: TenantId
  kbId: string | null
  docId: string | null
}>()

const emit = defineEmits<{
  ingested: []
}>()

const docContent = ref('')
const uploading = ref(false)
const uploadResult = ref<{ chunks: number; msg: string } | null>(null)

const detail = ref<DocumentDetail | null>(null)
const loadingDetail = ref(false)
const selectedVersion = ref<number | null>(null)
const chunks = ref<ChunkPreview[]>([])
const loadingChunks = ref(false)

const versionOptions = computed(() =>
  (detail.value?.versions ?? []).map((v) => ({
    label: `v${v.version} · ${v.status} · ${v.chunkCount} chunks`,
    value: v.version,
  })),
)

async function loadDetail() {
  if (!props.kbId || !props.docId) {
    detail.value = null
    chunks.value = []
    return
  }
  loadingDetail.value = true
  try {
    detail.value = await getDocument(props.tenantId, props.kbId, props.docId)
    const active = detail.value.versions.find((v) => v.status === 'active')
    selectedVersion.value = active?.version ?? detail.value.versions[0]?.version ?? null
  } catch {
    detail.value = null
  } finally {
    loadingDetail.value = false
  }
}

async function loadChunks() {
  if (!props.kbId || !props.docId || selectedVersion.value == null) {
    chunks.value = []
    return
  }
  loadingChunks.value = true
  try {
    chunks.value = await listChunks(
      props.tenantId,
      props.kbId,
      props.docId,
      selectedVersion.value,
    )
  } catch {
    chunks.value = []
  } finally {
    loadingChunks.value = false
  }
}

watch(
  () => [props.kbId, props.docId, props.tenantId] as const,
  () => {
    void loadDetail()
  },
  { immediate: true },
)

watch(selectedVersion, () => {
  void loadChunks()
})

async function handleUpload() {
  if (!props.kbId || !docContent.value.trim()) return
  uploading.value = true
  uploadResult.value = null
  try {
    const result = await ingestText(props.tenantId, props.kbId, docContent.value)
    uploadResult.value = { chunks: result.chunks, msg: `入库成功 · ${result.docName} v${result.version}` }
    docContent.value = ''
    emit('ingested')
  } catch (e) {
    uploadResult.value = { chunks: 0, msg: friendlyErrorMessage(e, '上传失败') }
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <div class="doc-panel">
    <NCard title="文本入库" class="panel-card" size="small">
      <NInput
        v-model:value="docContent"
        type="textarea"
        placeholder="粘贴 Markdown 或纯文本…"
        :autosize="{ minRows: 4, maxRows: 10 }"
        class="kb-input"
        :disabled="!kbId || uploading"
      />
      <div class="card-footer">
        <NButton
          type="primary"
          class="action-btn"
          round
          :loading="uploading"
          :disabled="!kbId || !docContent.trim()"
          @click="handleUpload"
        >
          上传入库
        </NButton>
      </div>
      <NResult
        v-if="uploadResult"
        :status="uploadResult.chunks > 0 ? 'success' : 'error'"
        :title="uploadResult.chunks > 0 ? '成功' : '失败'"
        :description="uploadResult.msg"
        size="small"
        style="margin-top: 12px"
      />
    </NCard>

    <NSpin :show="loadingDetail" class="detail-spin">
      <div v-if="!docId" class="empty-wrap">
        <NEmpty description="未选择文档" />
      </div>
      <div v-else-if="detail" class="doc-detail">
        <div class="detail-head">
          <div>
            <h3>{{ detail.displayName }}</h3>
            <NText depth="3">{{ detail.docId }}</NText>
          </div>
          <NSelect
            v-model:value="selectedVersion"
            :options="versionOptions"
            size="small"
            style="min-width: 220px"
            placeholder="选择版本"
          />
        </div>

        <NSpin :show="loadingChunks" size="small" class="chunk-spin">
          <div v-if="chunks.length === 0 && !loadingChunks" class="empty-wrap">
            <NEmpty size="small" description="该版本无 chunk" />
          </div>
          <div v-else class="chunk-scroll">
            <NCard
              v-for="chunk in chunks"
              :key="chunk.chunkIndex"
              size="small"
              class="chunk-card"
            >
              <template #header>
                <NSpace align="center" :size="8">
                  <NTag :bordered="false" size="tiny">#{{ chunk.chunkIndex }}</NTag>
                  <NText depth="3">{{ chunk.docName }}</NText>
                </NSpace>
              </template>
              <div class="chunk-content">{{ chunk.content }}</div>
            </NCard>
          </div>
        </NSpin>
      </div>
    </NSpin>
  </div>
</template>

<style scoped>
.doc-panel {
  flex: 1;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: hidden;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-lg);
  padding: 12px;
  background: var(--sun-black);
}

.panel-card {
  border: 1px solid var(--sun-border) !important;
  background: var(--sun-black) !important;
  flex-shrink: 0;
}

.detail-spin {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.detail-spin :deep(.n-spin-container),
.detail-spin :deep(.n-spin-content) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.doc-detail {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chunk-spin {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chunk-spin :deep(.n-spin-container),
.chunk-spin :deep(.n-spin-content) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chunk-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-right: 2px;
}

.panel-card :deep(.n-card-header) {
  background: transparent;
}

.kb-input {
  --n-color: var(--sun-black) !important;
  --n-color-focus: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-placeholder-color: var(--sun-text-muted) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
}

.card-footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 10px;
}

.action-btn {
  --n-color: var(--sun-accent) !important;
  --n-color-hover: var(--sun-accent-hover) !important;
  --n-color-pressed: var(--sun-accent-hover) !important;
  --n-color-focus: var(--sun-accent-hover) !important;
  --n-color-disabled: var(--sun-border) !important;
  --n-text-color: var(--btn-primary-text) !important;
  --n-text-color-hover: var(--btn-primary-text) !important;
  --n-text-color-pressed: var(--btn-primary-text) !important;
  --n-text-color-focus: var(--btn-primary-text) !important;
  --n-text-color-disabled: var(--sun-text-muted) !important;
  --n-border: none !important;
  --n-border-disabled: none !important;
  flex-shrink: 0;
}

.doc-detail h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--sun-text);
}

.detail-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 12px;
  flex-shrink: 0;
}

.chunk-card {
  border: 1px solid var(--sun-border) !important;
  background: var(--sun-black) !important;
}

.chunk-card :deep(.n-card-header) {
  background: transparent;
}

.chunk-content {
  font-size: var(--sun-font-base, 14px);
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--sun-text);
}

.empty-wrap {
  padding: 32px 0;
}
</style>
