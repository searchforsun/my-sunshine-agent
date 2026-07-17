<script setup lang="ts">
import { computed, ref } from 'vue'
import { NButton, NEmpty, NIcon, NInput, NSpin, NText } from 'naive-ui'
import { AddOutline, DocumentTextOutline, SearchOutline } from '@vicons/ionicons5'
import type { KbDocument } from '../../api/ragAdmin'
import { resolveDocSourceType } from '../../utils/docSourceTypes'
import { formatDocumentVersionKey } from '../../utils/formatSkillVersionTime'
import MetricBadge from './MetricBadge.vue'

const props = defineProps<{
  documents: KbDocument[]
  selectedDocId: string | null
  kbId: string | null
  loading?: boolean
}>()

const emit = defineEmits<{
  'select-doc': [docId: string]
  'create-doc': []
}>()

const docSearch = ref('')

const filteredDocs = computed(() => {
  const q = docSearch.value.trim().toLowerCase()
  if (!q) return props.documents
  return props.documents.filter(
    (doc) =>
      doc.docId.toLowerCase().includes(q) ||
      doc.displayName.toLowerCase().includes(q),
  )
})
</script>

<template>
  <section class="doc-list-pane">
    <header class="pane-head">
      <span class="pane-title">文档列表</span>
      <MetricBadge :value="String(filteredDocs.length)" />
      <NButton
        size="tiny"
        quaternary
        class="create-btn"
        :disabled="!kbId"
        @click="emit('create-doc')"
      >
        <template #icon><NIcon :component="AddOutline" :size="14" /></template>
        新建
      </NButton>
    </header>
    <div class="pane-search">
      <NInput
        v-model:value="docSearch"
        placeholder="搜索文档…"
        size="small"
        round
        clearable
        class="search-input"
        :disabled="!kbId"
      >
        <template #prefix><NIcon :component="SearchOutline" :size="14" /></template>
      </NInput>
    </div>
    <NSpin :show="loading" size="small" class="pane-spin">
      <div class="list-body">
        <div v-if="!kbId" class="empty-wrap">
          <NEmpty size="small" description="请先选择知识库" />
        </div>
        <div v-else-if="filteredDocs.length === 0 && !loading" class="empty-wrap">
          <NEmpty size="small" description="暂无文档" />
        </div>
        <button
          v-for="doc in filteredDocs"
          :key="doc.docId"
          type="button"
          class="list-item"
          :class="{ active: doc.docId === selectedDocId }"
          @click="emit('select-doc', doc.docId)"
        >
          <div class="list-item-top">
            <NIcon :component="DocumentTextOutline" :size="16" class="doc-icon" />
            <span class="list-item-title">{{ doc.displayName }}</span>
          </div>
          <NText depth="3" class="list-item-sub">
            {{ resolveDocSourceType(doc.sourceType).label }} ·
            {{ doc.activeVersion ? formatDocumentVersionKey(doc.activeVersion) : '—' }} · {{ doc.chunkCount }} chunks
          </NText>
        </button>
      </div>
    </NSpin>
  </section>
</template>

<style scoped>
.doc-list-pane {
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-lg);
  background: var(--sun-black);
}

.pane-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px 0;
  flex-shrink: 0;
}

.create-btn { margin-left: auto; }

.pane-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--sun-text);
}

.pane-search {
  padding: 10px 12px;
  flex-shrink: 0;
}

.pane-spin {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.pane-spin :deep(.n-spin-container),
.pane-spin :deep(.n-spin-content) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.list-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.search-input {
  --n-color: var(--sun-black) !important;
  --n-color-focus: var(--sun-black) !important;
  --n-color-disabled: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-text-color-disabled: var(--sun-text-muted) !important;
  --n-placeholder-color: var(--sun-text-muted) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
}

.list-item {
  display: block;
  width: 100%;
  text-align: left;
  padding: 12px 14px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
  color: var(--sun-text);
  cursor: pointer;
  transition: border-color 0.15s ease;
}

.list-item:hover {
  border-color: var(--sun-border-light);
}

.list-item.active {
  font-weight: 600;
  border-color: var(--sun-text);
}

.list-item-top {
  display: flex;
  align-items: center;
  gap: 6px;
}

.list-item-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--sun-text);
}

.list-item-sub {
  display: block;
  margin-top: 4px;
  font-size: 12px;
}

.doc-icon {
  flex-shrink: 0;
  color: var(--sun-text-secondary);
}

.empty-wrap {
  padding: 24px 12px;
}
</style>
