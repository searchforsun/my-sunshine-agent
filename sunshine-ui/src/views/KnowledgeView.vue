<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import {
  NButton,
  NForm,
  NFormItem,
  NInput,
  NModal,
  useMessage,
} from 'naive-ui'
import KbLayout from '../components/knowledge/KbLayout.vue'
import {
  createKb,
  listDocuments,
  listKbs,
  type KbDocument,
  type KnowledgeBase,
} from '../api/ragAdmin'
import { useTenantPreference } from '../composables/useTenantPreference'
import { friendlyErrorMessage } from '../api/apiError'

const { tenantId, setTenantId } = useTenantPreference()
const message = useMessage()

const kbs = ref<KnowledgeBase[]>([])
const documents = ref<KbDocument[]>([])
const selectedKbId = ref<string | null>(null)
const selectedDocId = ref<string | null>(null)
const loadingKbs = ref(false)
const loadingDocs = ref(false)

const showCreateKb = ref(false)
const createForm = ref({ kbId: '', displayName: '', description: '' })
const creating = ref(false)

async function loadKbs() {
  loadingKbs.value = true
  try {
    kbs.value = await listKbs(tenantId.value)
    if (!selectedKbId.value && kbs.value.length > 0) {
      const def = kbs.value.find((kb) => kb.isDefault) ?? kbs.value[0]
      selectedKbId.value = def.kbId
    } else if (selectedKbId.value && !kbs.value.some((kb) => kb.kbId === selectedKbId.value)) {
      selectedKbId.value = kbs.value[0]?.kbId ?? null
    }
  } catch (e) {
    message.error(friendlyErrorMessage(e, '加载知识库失败'))
  } finally {
    loadingKbs.value = false
  }
}

async function loadDocuments() {
  if (!selectedKbId.value) {
    documents.value = []
    selectedDocId.value = null
    return
  }
  loadingDocs.value = true
  try {
    documents.value = await listDocuments(tenantId.value, selectedKbId.value)
    if (selectedDocId.value && !documents.value.some((d) => d.docId === selectedDocId.value)) {
      selectedDocId.value = documents.value[0]?.docId ?? null
    }
  } catch (e) {
    message.error(friendlyErrorMessage(e, '加载文档失败'))
    documents.value = []
  } finally {
    loadingDocs.value = false
  }
}

async function refreshAll() {
  await loadKbs()
  await loadDocuments()
}

function selectKb(kbId: string) {
  selectedKbId.value = kbId
  selectedDocId.value = null
}

function selectDoc(docId: string) {
  selectedDocId.value = docId
}

async function handleCreateKb() {
  if (!createForm.value.kbId.trim() || !createForm.value.displayName.trim()) return
  creating.value = true
  try {
    await createKb(
      tenantId.value,
      createForm.value.kbId.trim(),
      createForm.value.displayName.trim(),
      createForm.value.description.trim() || undefined,
    )
    showCreateKb.value = false
    createForm.value = { kbId: '', displayName: '', description: '' }
    message.success('知识库已创建')
    await refreshAll()
  } catch (e) {
    message.error(friendlyErrorMessage(e, '创建失败'))
  } finally {
    creating.value = false
  }
}

watch(tenantId, () => {
  selectedKbId.value = null
  selectedDocId.value = null
  void refreshAll()
})

watch(selectedKbId, () => {
  void loadDocuments()
})

onMounted(() => {
  void refreshAll()
})
</script>

<template>
  <KbLayout
    :tenant-id="tenantId"
    :kbs="kbs"
    :documents="documents"
    :selected-kb-id="selectedKbId"
    :selected-doc-id="selectedDocId"
    :loading-kbs="loadingKbs"
    :loading-docs="loadingDocs"
    @update:tenant-id="setTenantId"
    @update:selected-kb-id="selectKb"
    @select-doc="selectDoc"
    @create-kb="showCreateKb = true"
    @doc-ingested="refreshAll"
  />

  <NModal v-model:show="showCreateKb" preset="card" title="新建知识库" style="max-width: 420px">
    <NForm label-placement="top">
      <NFormItem label="知识库 ID" required>
        <NInput v-model:value="createForm.kbId" placeholder="如 finance" />
      </NFormItem>
      <NFormItem label="显示名称" required>
        <NInput v-model:value="createForm.displayName" placeholder="如 财务制度库" />
      </NFormItem>
      <NFormItem label="描述">
        <NInput v-model:value="createForm.description" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" />
      </NFormItem>
    </NForm>
    <template #footer>
      <NButton round secondary @click="showCreateKb = false">取消</NButton>
      <NButton type="primary" class="action-btn" round :loading="creating" @click="handleCreateKb">创建</NButton>
    </template>
  </NModal>
</template>

<style scoped>
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
</style>
