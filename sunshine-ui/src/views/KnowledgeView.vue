<script setup lang="ts">
import { ref } from 'vue'
import { uploadDocument, searchKnowledge, type KnowledgeHit } from '../api/knowledge'
import { NCard, NInput, NButton, NResult, NText, NTag, NSpace, NDivider } from 'naive-ui'
import TenantSelector from '../components/knowledge/TenantSelector.vue'
import { useTenantPreference } from '../composables/useTenantPreference'
import { friendlyErrorMessage } from '../api/apiError'

const { tenantId, setTenantId } = useTenantPreference()

const docContent = ref('')
const uploading = ref(false)
const uploadResult = ref<{ chunks: number; msg: string } | null>(null)

const searchQuery = ref('')
const searching = ref(false)
const searchError = ref('')
const searchResults = ref<KnowledgeHit[]>([])

async function handleUpload() {
  if (!docContent.value.trim()) return
  uploading.value = true
  uploadResult.value = null
  try {
    const result = await uploadDocument(docContent.value, tenantId.value)
    uploadResult.value = { chunks: result.chunks, msg: '文档入库成功' }
    docContent.value = ''
  } catch (e) {
    uploadResult.value = { chunks: 0, msg: friendlyErrorMessage(e, '上传失败，请稍后重试') }
  } finally {
    uploading.value = false
  }
}

async function handleSearch() {
  if (!searchQuery.value.trim()) return
  searching.value = true
  searchResults.value = []
  searchError.value = ''
  try {
    searchResults.value = await searchKnowledge(searchQuery.value, tenantId.value)
  } catch (e) {
    searchResults.value = []
    searchError.value = friendlyErrorMessage(e, '检索失败，请稍后重试')
  } finally {
    searching.value = false
  }
}
</script>

<template>
  <div class="knowledge-root">
    <div class="knowledge-content">
      <header class="page-header">
        <div class="page-header-row">
          <div>
            <h2>知识库管理</h2>
            <p>文档上传与语义检索，基于 Milvus + Embedding 向量化。</p>
          </div>
          <div class="tenant-row">
            <NText depth="3" class="tenant-hint">当前租户</NText>
            <TenantSelector
              :model-value="tenantId"
              :disabled="uploading || searching"
              @update:model-value="setTenantId"
            />
          </div>
        </div>
      </header>

      <!-- Upload Card -->
      <NCard
        title="上传文档"
        class="kb-card"
        size="medium"
      >
        <template #header-extra>
          <NTag :bordered="false" size="small" type="info">Markdown / 纯文本</NTag>
        </template>
        <NInput
          v-model:value="docContent"
          type="textarea"
          placeholder="粘贴 Markdown 文档内容..."
          :autosize="{ minRows: 5, maxRows: 12 }"
          class="doc-textarea"
        />
        <div class="card-footer">
          <NText depth="3" class="card-hint">
            文档将自动分段、向量化，存入 Milvus 供语义检索。
          </NText>
          <NButton
            type="warning"
            class="kb-action-btn"
            @click="handleUpload"
            :loading="uploading"
            :disabled="!docContent.trim()"
            round
          >
            上传入库
          </NButton>
        </div>

        <NResult
          v-if="uploadResult"
          :status="uploadResult.chunks > 0 ? 'success' : 'error'"
          :title="uploadResult.chunks > 0 ? '入库成功' : '入库失败'"
          :description="uploadResult.msg"
          size="small"
          style="margin-top: 16px"
        />
      </NCard>

      <!-- Search Card -->
      <NCard
        title="语义检索"
        class="kb-card"
        size="medium"
      >
        <template #header-extra>
          <NTag :bordered="false" size="small" type="warning">向量检索</NTag>
        </template>
        <div class="search-row">
        <NInput
          v-model:value="searchQuery"
          placeholder="输入查询文本..."
          size="large"
          round
          class="search-input"
          @keydown.enter="handleSearch"
        />
        <NButton
          type="warning"
          class="kb-action-btn"
          @click="handleSearch"
            :loading="searching"
            :disabled="!searchQuery.trim()"
            round
            size="large"
          >
            检索
          </NButton>
        </div>

        <template v-if="searchResults.length > 0">
          <NDivider style="margin: 16px 0" />
          <div class="result-list">
            <NCard
              v-for="(result, idx) in searchResults"
              :key="idx"
              size="small"
              :bordered="true"
              class="result-item"
            >
              <template #header>
                <NSpace align="center" :size="8">
                  <NTag :bordered="false" size="tiny" type="info">
                    片段 #{{ idx + 1 }}
                  </NTag>
                  <NText v-if="result.docName" depth="3" class="result-doc-name">{{ result.docName }}</NText>
                  <NTag v-if="result.score != null" :bordered="false" size="tiny">{{ result.score.toFixed(3) }}</NTag>
                </NSpace>
              </template>
              <div class="result-text">{{ result.content }}</div>
            </NCard>
          </div>
        </template>

        <div v-else-if="searchError" class="no-results">
          <NText depth="3">{{ searchError }}</NText>
        </div>

        <div v-else-if="!searching && searchQuery" class="no-results">
          <NText depth="3">未找到结果，尝试其他查询或先上传相关文档。</NText>
        </div>
      </NCard>
    </div>
  </div>
</template>

<style scoped>
.knowledge-root {
  height: 100vh;
  background: var(--sun-black);
}

.knowledge-content {
  max-width: 780px;
  margin: 0 auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* --- Header --- */
.page-header {
  margin-bottom: 4px;
}
.page-header-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
}
.tenant-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.tenant-hint {
  font-size: 12px;
  white-space: nowrap;
}
.page-header h2 {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.4px;
  margin: 0;
  color: var(--sun-text);
}
.page-header p {
  font-size: 13px;
  color: var(--sun-text-muted);
  margin: 4px 0 0;
  line-height: 1.4;
}

/* --- Cards --- */
.kb-card {
  border-radius: var(--radius-lg) !important;
  border: 1px solid var(--sun-border) !important;
  background: var(--sun-black) !important;
  transition: border-color .2s;
}

.kb-card :deep(.n-card-header) {
  background: transparent;
}

.kb-card :deep(.n-card-header__main) {
  color: var(--sun-text);
}

/* --- Naive Input：与 composer 一致 --- */
.doc-textarea,
.search-input {
  --n-color: var(--sun-black) !important;
  --n-color-focus: var(--sun-black) !important;
  --n-color-disabled: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-placeholder-color: var(--sun-text-muted) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-border-hover: 1px solid var(--sun-border-light) !important;
  --n-border-disabled: 1px solid var(--sun-border) !important;
  --n-box-shadow-focus: none !important;
}

.doc-textarea {
  --n-border-radius: var(--radius-md) !important;
}

/* --- Naive Button warning --- */
.kb-action-btn {
  --n-color: var(--sun-accent) !important;
  --n-color-hover: var(--sun-accent-hover) !important;
  --n-color-pressed: var(--sun-text-secondary) !important;
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

.card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
  gap: 16px;
}

.card-hint {
  font-size: 12px;
  max-width: 420px;
  line-height: 1.4;
}

/* --- Search --- */
.search-row {
  display: flex;
  gap: 10px;
  align-items: center;
}

.search-row .search-input {
  flex: 1;
}

/* --- Results --- */
.result-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.result-item {
  border-radius: var(--radius-md) !important;
  border-color: var(--sun-border) !important;
  background: var(--sun-black) !important;
}

.result-item :deep(.n-card-header) {
  background: transparent;
}

.result-text {
  font-size: 13.5px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--sun-text);
}

.result-doc-name {
  font-size: 12px;
}

.no-results {
  padding: 20px 0 4px;
  text-align: center;
}
</style>
