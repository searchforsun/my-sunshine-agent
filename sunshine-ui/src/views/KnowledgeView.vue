<script setup lang="ts">
import { ref } from 'vue'
import { uploadDocument, searchKnowledge } from '../api/knowledge'
import { NCard, NInput, NButton, NResult, NText, NScrollbar, NTag, NSpace, NDivider } from 'naive-ui'

const docContent = ref('')
const uploading = ref(false)
const uploadResult = ref<{ chunks: number; msg: string } | null>(null)

const searchQuery = ref('')
const searching = ref(false)
const searchResults = ref<string[]>([])

async function handleUpload() {
  if (!docContent.value.trim()) return
  uploading.value = true
  uploadResult.value = null
  try {
    const result = await uploadDocument(docContent.value)
    uploadResult.value = { chunks: result.chunks, msg: 'Document ingested successfully' }
    docContent.value = ''
  } catch (e: any) {
    uploadResult.value = { chunks: 0, msg: `Upload failed: ${e.message}` }
  } finally {
    uploading.value = false
  }
}

async function handleSearch() {
  if (!searchQuery.value.trim()) return
  searching.value = true
  try {
    searchResults.value = await searchKnowledge(searchQuery.value)
  } finally {
    searching.value = false
  }
}
</script>

<template>
  <NScrollbar class="knowledge-root">
    <div class="knowledge-content">
      <header class="page-header">
        <h2>Knowledge Base</h2>
        <p>Manage documents and search your vector knowledge base powered by Milvus + Embedding.</p>
      </header>

      <!-- Upload Card -->
      <NCard
        title="Upload Document"
        class="kb-card"
        size="medium"
      >
        <template #header-extra>
          <NTag :bordered="false" size="small" type="info">Markdown / Plain Text</NTag>
        </template>
        <NInput
          v-model:value="docContent"
          type="textarea"
          placeholder="Paste Markdown document content here..."
          :autosize="{ minRows: 5, maxRows: 12 }"
          class="doc-textarea"
        />
        <div class="card-footer">
          <NText depth="3" class="card-hint">
            Content will be chunked, embedded, and stored in Milvus for semantic retrieval.
          </NText>
          <NButton
            type="warning"
            @click="handleUpload"
            :loading="uploading"
            :disabled="!docContent.trim()"
            round
          >
            Upload & Index
          </NButton>
        </div>

        <NResult
          v-if="uploadResult"
          :status="uploadResult.chunks > 0 ? 'success' : 'error'"
          :title="uploadResult.chunks > 0 ? 'Ingested' : 'Failed'"
          :description="uploadResult.msg"
          size="small"
          style="margin-top: 16px"
        />
      </NCard>

      <!-- Search Card -->
      <NCard
        title="Semantic Search"
        class="kb-card"
        size="medium"
      >
        <template #header-extra>
          <NTag :bordered="false" size="small" type="warning">Vector Search</NTag>
        </template>
        <div class="search-row">
          <NInput
            v-model:value="searchQuery"
            placeholder="Enter your search query..."
            size="large"
            round
            @keydown.enter="handleSearch"
          />
          <NButton
            type="warning"
            @click="handleSearch"
            :loading="searching"
            :disabled="!searchQuery.trim()"
            round
            size="large"
          >
            Search
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
                <NTag :bordered="false" size="tiny" type="info">
                  Fragment #{{ idx + 1 }}
                </NTag>
              </template>
              <div class="result-text">{{ result }}</div>
            </NCard>
          </div>
        </template>

        <div v-else-if="!searching && searchQuery" class="no-results">
          <NText depth="3">No results found. Try a different query or upload relevant documents first.</NText>
        </div>
      </NCard>
    </div>
  </NScrollbar>
</template>

<style scoped>
.knowledge-root {
  height: 100vh;
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
.page-header h2 {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.4px;
  margin: 0;
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
  background: var(--sun-surface) !important;
  transition: border-color .2s;
}

/* --- Upload --- */
.doc-textarea {
  --n-color: var(--sun-deep) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-focus: 1px solid var(--sun-amber) !important;
  --n-border-hover: 1px solid var(--sun-border-light) !important;
  --n-border-radius: var(--radius-md) !important;
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

.search-row .n-input {
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
  background: var(--sun-deep) !important;
}

.result-text {
  font-size: 13.5px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--sun-text);
}

.no-results {
  padding: 20px 0 4px;
  text-align: center;
}
</style>
