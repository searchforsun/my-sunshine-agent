<script setup lang="ts">
import { ref } from 'vue'
import { uploadDocument, searchKnowledge } from '../api/knowledge'
import { NCard, NInput, NButton, NSpace, NResult, NText, NScrollbar } from 'naive-ui'

const docContent = ref('')
const uploading = ref(false)
const uploadResult = ref<{ chunks: number } | null>(null)

const searchQuery = ref('')
const searching = ref(false)
const searchResults = ref<string[]>([])

async function handleUpload() {
  if (!docContent.value.trim()) return
  uploading.value = true
  try {
    uploadResult.value = await uploadDocument(docContent.value)
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
  <NScrollbar style="height: 100vh">
    <div style="padding: 24px; max-width: 900px; margin: 0 auto">
      <div style="font-size: 16px; font-weight: 600; margin-bottom: 24px">知识库管理</div>

      <!-- 文档上传 -->
      <NCard title="📄 上传文档" style="margin-bottom: 24px">
        <NSpace vertical style="width: 100%">
          <NInput
            v-model:value="docContent"
            type="textarea"
            placeholder="粘贴 Markdown 文档内容..."
            :autosize="{ minRows: 6, maxRows: 16 }"
          />
          <div style="display: flex; justify-content: space-between; align-items: center">
            <NText depth="3" style="font-size: 12px">
              支持 Markdown，自动分段并向量化存入 Milvus
            </NText>
            <NButton
              type="primary"
              @click="handleUpload"
              :loading="uploading"
              :disabled="!docContent.trim()"
            >
              上传入库
            </NButton>
          </div>
        </NSpace>
        <NResult
          v-if="uploadResult"
          status="success"
          title="入库成功"
          :description="`文档已拆分为 ${uploadResult.chunks} 个片段`"
          style="margin-top: 16px"
        />
      </NCard>

      <!-- 检索测试 -->
      <NCard title="🔍 检索测试">
        <NSpace vertical style="width: 100%">
          <NSpace style="width: 100%">
            <NInput
              v-model:value="searchQuery"
              placeholder="输入查询文本..."
              style="flex: 1"
              @keydown.enter="handleSearch"
            />
            <NButton @click="handleSearch" :loading="searching" type="primary">检索</NButton>
          </NSpace>
          <div v-if="searchResults.length > 0">
            <NCard
              v-for="(result, idx) in searchResults"
              :key="idx"
              style="margin-bottom: 8px"
              size="small"
            >
              <div style="font-size: 13px; line-height: 1.6; white-space: pre-wrap">
                {{ result }}
              </div>
            </NCard>
          </div>
        </NSpace>
      </NCard>
    </div>
  </NScrollbar>
</template>
