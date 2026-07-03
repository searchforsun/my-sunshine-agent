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
  createDocument,
  createKb,
  listDocuments,
  listKbs,
  type KbDocument,
  type KnowledgeBase,
} from '../api/ragAdmin'
import {
  createKbWorkbenchContext,
  provideKbWorkbenchContext,
} from '../composables/useKbWorkbenchContext'
import { useKbWorkbenchRouteState } from '../composables/useKbWorkbenchRouteState'
import { useTenantPreference } from '../composables/useTenantPreference'
import { friendlyErrorMessage } from '../api/apiError'

const { tenantId, setTenantId } = useTenantPreference()
const message = useMessage()
const routeState = useKbWorkbenchRouteState()

const kbs = ref<KnowledgeBase[]>([])
const documents = ref<KbDocument[]>([])
const selectedKbId = ref<string | null>(null)
const selectedDocId = ref<string | null>(null)
const loadingKbs = ref(false)
const loadingDocs = ref(false)

const workbench = createKbWorkbenchContext(tenantId, selectedKbId)
provideKbWorkbenchContext(workbench)

const showCreateKb = ref(false)
const createForm = ref({ kbId: '', displayName: '', description: '' })
const creating = ref(false)

const showCreateDoc = ref(false)
const createDocForm = ref({ docId: '', displayName: '' })
const creatingDoc = ref(false)

let bootstrapping = false
let tenantSyncing = false

async function loadKbs() {
  loadingKbs.value = true
  try {
    kbs.value = await listKbs(tenantId.value)
    const fromQuery = routeState.readKbId()
    if (fromQuery && kbs.value.some((kb) => kb.kbId === fromQuery)) {
      selectedKbId.value = fromQuery
    } else if (!selectedKbId.value && kbs.value.length > 0) {
      const def = kbs.value.find((kb) => kb.isDefault) ?? kbs.value[0]
      selectedKbId.value = def.kbId
    } else if (selectedKbId.value && !kbs.value.some((kb) => kb.kbId === selectedKbId.value)) {
      selectedKbId.value = kbs.value[0]?.kbId ?? null
    }
    if (!bootstrapping) {
      routeState.syncQuery({ kb: selectedKbId.value })
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
    const fromQuery = bootstrapping ? routeState.readDocId() : null
    if (fromQuery && documents.value.some((d) => d.docId === fromQuery)) {
      selectedDocId.value = fromQuery
    } else if (selectedDocId.value && !documents.value.some((d) => d.docId === selectedDocId.value)) {
      selectedDocId.value = documents.value[0]?.docId ?? null
    }
    if (!bootstrapping) {
      routeState.syncQuery({ doc: selectedDocId.value })
    }
  } catch (e) {
    message.error(friendlyErrorMessage(e, '加载文档失败'))
    documents.value = []
  } finally {
    loadingDocs.value = false
  }
}

async function handleDocDeleted() {
  selectedDocId.value = null
  routeState.syncQuery({ doc: null })
  await loadDocuments()
}

async function refreshAll() {
  await loadKbs()
  await loadDocuments()
}

function selectKb(kbId: string) {
  if (selectedKbId.value === kbId) return
  selectedKbId.value = kbId
}

function selectDoc(docId: string) {
  selectedDocId.value = docId
  routeState.syncQuery({ doc: docId })
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

async function handleCreateDoc() {
  if (!selectedKbId.value || !createDocForm.value.docId.trim() || !createDocForm.value.displayName.trim()) return
  creatingDoc.value = true
  const newDocId = createDocForm.value.docId.trim()
  try {
    await createDocument(
      tenantId.value,
      selectedKbId.value,
      newDocId,
      createDocForm.value.displayName.trim(),
    )
    showCreateDoc.value = false
    createDocForm.value = { docId: '', displayName: '' }
    message.success('文档已创建，请上传 Markdown 或在线编写')
    await loadDocuments()
    selectedDocId.value = newDocId
    routeState.syncQuery({ doc: newDocId })
    workbench.bumpRevision()
  } catch (e) {
    message.error(friendlyErrorMessage(e, '创建失败'))
  } finally {
    creatingDoc.value = false
  }
}

watch(tenantId, async () => {
  tenantSyncing = true
  bootstrapping = true
  selectedKbId.value = null
  selectedDocId.value = null
  documents.value = []
  routeState.syncQuery({ kb: null, doc: null })
  await loadKbs()
  await loadDocuments()
  tenantSyncing = false
  bootstrapping = false
  routeState.syncQuery({ kb: selectedKbId.value, doc: selectedDocId.value })
  workbench.bumpRevision()
})

watch(selectedKbId, async (kbId, prev) => {
  if (tenantSyncing || bootstrapping) return
  if (kbId === prev) return
  selectedDocId.value = null
  routeState.syncQuery({ kb: kbId, doc: null })
  await loadDocuments()
  workbench.bumpRevision()
})

onMounted(async () => {
  bootstrapping = true
  await loadKbs()
  await loadDocuments()
  bootstrapping = false
  routeState.syncQuery({ kb: selectedKbId.value, doc: selectedDocId.value })
  workbench.bumpRevision()
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
    @create-doc="showCreateDoc = true"
    @doc-ingested="refreshAll"
    @refresh-documents="loadDocuments"
    @doc-deleted="handleDocDeleted"
  />

  <NModal v-model:show="showCreateKb" preset="dialog" title="新建知识库" class="sunshine-dialog">
    <NForm label-placement="left" label-width="90">
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
    <template #action>
      <NButton @click="showCreateKb = false">取消</NButton>
      <NButton type="primary" class="action-btn" :loading="creating" @click="handleCreateKb">创建</NButton>
    </template>
  </NModal>

  <NModal v-model:show="showCreateDoc" preset="dialog" title="新建文档" class="sunshine-dialog">
    <NForm label-placement="left" label-width="90">
      <NFormItem label="文档 ID" required>
        <NInput v-model:value="createDocForm.docId" placeholder="如 attendance-policy" />
      </NFormItem>
      <NFormItem label="显示名称" required>
        <NInput v-model:value="createDocForm.displayName" placeholder="如 考勤与加班管理规定" />
      </NFormItem>
    </NForm>
    <template #action>
      <NButton @click="showCreateDoc = false">取消</NButton>
      <NButton type="primary" class="action-btn" :loading="creatingDoc" @click="handleCreateDoc">创建</NButton>
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
